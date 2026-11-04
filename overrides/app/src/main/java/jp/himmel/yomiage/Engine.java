package jp.himmel.yomiage;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Owns the TextToSpeech engine and the reading queue. Lives for the whole process. */
public class Engine implements TextToSpeech.OnInitListener {
    private static Engine inst;

    public static synchronized Engine get(Context c) {
        if (inst == null) inst = new Engine(c.getApplicationContext());
        return inst;
    }

    private static final int AHEAD = 20;
    private static final String BREAKS = "。、！？!?\n，";

    final Context ctx;
    final Handler h = new Handler(Looper.getMainLooper());
    private final TextToSpeech tts;
    private final AudioManager am;
    private final AudioAttributes attrs;
    private AudioFocusRequest focusReq;
    private PowerManager.WakeLock wake;
    private AudioTrack silence; // our own (inaudible) audio so Bluetooth/headset buttons route to this app

    volatile boolean ready = false;
    private Runnable pending;

    String text = "";
    int[] cs = new int[0];
    int[] ce = new int[0];
    String title = "よみあげノート";
    volatile float rate = 2f;
    volatile float cps = 7f;
    int back = 20, fwd = 20;
    String voiceName = "";

    volatile boolean playing = false;
    volatile int doneSeq = 0;
    private int gen = 0;
    private int nextQ = 0, queued = 0;
    volatile int curBase = 0, curLen = 0, pos = 0;
    volatile long t0 = 0;
    volatile boolean rangeSeen = false;

    Runnable listener; // set by PlaybackService

    private Engine(Context c) {
        ctx = c;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        tts = new TextToSpeech(c, this);
    }

    @Override
    public void onInit(int status) {
        h.post(() -> {
            if (status != TextToSpeech.SUCCESS) return;
            ready = true;
            tts.setAudioAttributes(attrs);
            tts.setLanguage(Locale.JAPAN);
            applyVoice();
            tts.setOnUtteranceProgressListener(new Progress());
            if (pending != null) { Runnable r = pending; pending = null; r.run(); }
        });
    }

    // ---------- voices ----------
    private List<Voice> jaVoices() {
        List<Voice> out = new ArrayList<>();
        if (!ready) return out;
        try {
            Set<Voice> vs = tts.getVoices();
            if (vs == null) return out;
            for (Voice v : vs) {
                if (v.getLocale() != null && "ja".equals(v.getLocale().getLanguage())) out.add(v);
            }
        } catch (Exception ignored) { }
        Collections.sort(out, (a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    String voicesJson() {
        JSONArray arr = new JSONArray();
        try {
            int n = 1;
            for (Voice v : jaVoices()) {
                JSONObject o = new JSONObject();
                o.put("id", v.getName());
                String label = "声" + (n++) + "（" + v.getName() + "）";
                if (v.isNetworkConnectionRequired()) label += " オンライン";
                o.put("name", label);
                arr.put(o);
            }
        } catch (Exception ignored) { }
        return arr.toString();
    }

    private void applyVoice() {
        if (!ready || voiceName == null || voiceName.isEmpty()) return;
        for (Voice v : jaVoices()) {
            if (v.getName().equals(voiceName)) { tts.setVoice(v); return; }
        }
    }

    // ---------- commands (call on main thread) ----------
    void play(String json) {
        try {
            JSONObject o = new JSONObject(json);
            text = o.optString("text", "");
            JSONArray ch = o.optJSONArray("chunks");
            int n = ch == null ? 0 : ch.length();
            cs = new int[n];
            ce = new int[n];
            for (int i = 0; i < n; i++) {
                JSONArray p = ch.getJSONArray(i);
                cs[i] = p.getInt(0);
                ce[i] = p.getInt(1);
            }
            rate = (float) o.optDouble("rate", rate);
            cps = (float) o.optDouble("cps", cps);
            back = o.optInt("back", back);
            fwd = o.optInt("fwd", fwd);
            voiceName = o.optString("voice", voiceName);
            title = o.optString("title", title);
            final int start = o.optInt("start", 0);
            if (!ready) { pending = () -> startAt(start); return; }
            startAt(start);
        } catch (Exception e) {
            playing = false;
            changed();
        }
    }

    void resume() {
        if (text.isEmpty() || cs.length == 0) return;
        int p = currentPos();
        if (p >= text.length()) p = 0;
        if (!ready) { final int q = p; pending = () -> startAt(q); return; }
        startAt(snap(p));
    }

    void pause() {
        if (!playing) return;
        pos = currentPos();
        playing = false;
        gen++;
        try { tts.stop(); } catch (Exception ignored) { }
        stopSilence();
        releaseWake();
        changed();
    }

    void skip(int dir) {
        int sec = dir < 0 ? back : fwd;
        int chars = Math.round(sec * cps * rate);
        seek(currentPos() + dir * chars);
    }

    void seek(int p) {
        p = Math.max(0, Math.min(text.length(), p));
        p = snap(p);
        if (playing) startAt(p);
        else { pos = p; curBase = p; curLen = 0; changed(); }
    }

    void setRate(float r) {
        rate = r;
        if (playing) startAt(snap(currentPos()));
        else changed();
    }

    void setVoice(String name) {
        voiceName = name == null ? "" : name;
        applyVoice();
        if (playing) startAt(snap(currentPos()));
    }

    void setSkip(int b, int f) { back = b; fwd = f; }

    // ---------- queue ----------
    private void startAt(int p) {
        gen++;
        final int g = gen;
        try { tts.stop(); } catch (Exception ignored) { }
        int n = cs.length;
        int idx = 0;
        while (idx < n && p >= ce[idx]) idx++;
        if (idx >= n) { finish(); return; }
        int first = Math.max(p, cs[idx]);
        playing = true;
        pos = first;
        curBase = first;
        curLen = ce[idx] - first;
        t0 = SystemClock.elapsedRealtime();
        rangeSeen = false;
        nextQ = idx;
        queued = 0;
        tts.setSpeechRate(rate);
        applyVoice();
        requestFocus();
        startSilence();
        acquireWake();
        startService();
        topUp(g, first);
        if (queued == 0 && nextQ >= n) finish();
        changed();
    }

    private void topUp(int g, int startOff) {
        int n = cs.length;
        while (g == gen && queued < AHEAD && nextQ < n) {
            int i = nextQ++;
            int s = Math.max(cs[i], startOff);
            startOff = 0;
            if (s >= ce[i]) continue;
            String str = text.substring(s, ce[i]);
            if (str.trim().isEmpty()) continue;
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
            tts.speak(str, TextToSpeech.QUEUE_ADD, params, g + ":" + i + ":" + s);
            queued++;
        }
    }

    private void finish() {
        playing = false;
        pos = text.length();
        doneSeq++;
        stopSilence();
        releaseWake();
        changed();
    }

    private class Progress extends UtteranceProgressListener {
        private int[] parse(String id) {
            try {
                String[] a = id.split(":");
                return new int[]{Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2])};
            } catch (Exception e) { return null; }
        }

        @Override
        public void onStart(String id) {
            final int[] v = parse(id);
            if (v == null) return;
            h.post(() -> {
                if (v[0] != gen) return;
                curBase = v[2];
                curLen = ce[v[1]] - v[2];
                pos = v[2];
                t0 = SystemClock.elapsedRealtime();
                rangeSeen = false;
                changed();
            });
        }

        @Override
        public void onRangeStart(String id, int start, int end, int frame) {
            int[] v = parse(id);
            if (v == null || v[0] != gen) return;
            rangeSeen = true;
            pos = v[2] + start;
        }

        @Override
        public void onDone(String id) {
            final int[] v = parse(id);
            if (v == null) return;
            h.post(() -> {
                if (v[0] != gen) return;
                queued--;
                float dur = (SystemClock.elapsedRealtime() - t0) / 1000f;
                int len = ce[v[1]] - v[2];
                if (dur > 1.2f && len >= 12) {
                    float k = len / (dur * rate);
                    if (k > 2 && k < 20) cps = cps * 0.8f + k * 0.2f;
                }
                pos = ce[v[1]];
                curBase = pos;
                curLen = 0;
                if (!playing) return;
                if (queued <= 0 && nextQ >= cs.length) { finish(); return; }
                topUp(v[0], 0);
            });
        }

        @Override
        public void onError(String id) {
            final int[] v = parse(id);
            if (v == null) return;
            h.post(() -> {
                if (v[0] != gen) return;
                queued--;
                if (queued <= 0 && nextQ >= cs.length) finish();
                else topUp(v[0], 0);
            });
        }
    }

    // ---------- position ----------
    int currentPos() {
        if (!playing) return pos;
        if (rangeSeen) return pos;
        long el = SystemClock.elapsedRealtime() - t0;
        int est = curBase + Math.round(el / 1000f * cps * rate);
        return Math.max(pos, Math.min(est, curBase + curLen));
    }

    private int snap(int p) {
        if (p <= 0) return 0;
        if (p >= text.length()) return text.length();
        for (int i = p; i > Math.max(0, p - 24); i--) {
            if (BREAKS.indexOf(text.charAt(i - 1)) >= 0) return i;
        }
        return p;
    }

    String stateJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("playing", playing);
            o.put("pos", currentPos());
            o.put("base", curBase);
            o.put("len", curLen);
            o.put("cps", (double) cps);
            o.put("rate", (double) rate);
            o.put("done", doneSeq);
            o.put("ready", ready);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    // ---------- system ----------
    private void startService() {
        try {
            ctx.startForegroundService(new Intent(ctx, PlaybackService.class));
        } catch (Exception ignored) { }
    }

    private void requestFocus() {
        try {
            if (focusReq == null) {
                focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attrs)
                        .setOnAudioFocusChangeListener(fc -> {
                            if (fc == AudioManager.AUDIOFOCUS_LOSS || fc == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                                h.post(this::pause);
                            }
                        })
                        .build();
            }
            am.requestAudioFocus(focusReq);
        } catch (Exception ignored) { }
    }

    private void startSilence() {
        try {
            if (silence == null) {
                int sr = 8000, n = sr; // 1 second, looped
                short[] buf = new short[n];
                for (int i = 0; i < n; i++) buf[i] = (short) ((i & 1) == 0 ? 1 : -1); // +-1 LSB: inaudible
                silence = new AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sr)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(n * 2)
                        .build();
                silence.write(buf, 0, n);
                silence.setLoopPoints(0, n, -1);
            }
            if (silence.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) silence.play();
        } catch (Exception ignored) { }
    }

    private void stopSilence() {
        try { if (silence != null && silence.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) silence.pause(); } catch (Exception ignored) { }
    }

    private void acquireWake() {
        try {
            if (wake == null) {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yomiage:tts");
                wake.setReferenceCounted(false);
            }
            wake.acquire(6 * 60 * 60 * 1000L);
        } catch (Exception ignored) { }
    }

    private void releaseWake() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) { }
    }

    private void changed() {
        Runnable l = listener;
        if (l != null) l.run();
    }
}
