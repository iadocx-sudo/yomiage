package jp.himmel.yomiage;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    static final String APP_URL = "https://iadocx-sudo.github.io/yomiage/";
    static final String ASSET_URL = "file:///android_asset/index.html";
    private static final int REQ_FILE = 42;

    private WebView wv;
    private ValueCallback<Uri[]> fileCb;
    private boolean usedFallback = false;
    private String pendingShare;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Engine.get(this); // start TTS early so voices are ready
        wv = new WebView(this);
        wv.setBackgroundColor(Color.parseColor("#f3f4f6"));
        setContentView(wv);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);

        wv.addJavascriptInterface(new Bridge(), "YomiageNative");
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String url = u.toString();
                if (url.startsWith(APP_URL) || url.startsWith("file:///android_asset/")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame() && !usedFallback) {
                    usedFallback = true;
                    view.loadUrl(ASSET_URL);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                deliverShare();
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent it = new Intent(Intent.ACTION_GET_CONTENT);
                it.addCategory(Intent.CATEGORY_OPENABLE);
                it.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(it, "ファイルを選択"), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
            }
        });

        handleShare(getIntent());
        wv.loadUrl(APP_URL);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
        deliverShare();
    }

    private void handleShare(Intent intent) {
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String t = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (t != null && !t.isEmpty()) pendingShare = t;
        }
    }

    private void deliverShare() {
        if (pendingShare == null || wv == null) return;
        String js = "window.__ynShare&&window.__ynShare(" + JSONObject.quote(pendingShare) + ")";
        pendingShare = null;
        wv.evaluateJavascript(js, null);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && fileCb != null) {
            Uri[] r = null;
            if (res == RESULT_OK && data != null && data.getData() != null) r = new Uri[]{data.getData()};
            fileCb.onReceiveValue(r);
            fileCb = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (wv != null && wv.canGoBack()) wv.goBack();
        else moveTaskToBack(true); // keep reading in the background
    }

    @Override
    protected void onDestroy() {
        if (wv != null) { wv.destroy(); wv = null; }
        super.onDestroy();
    }

    /** Called from the page's JavaScript. Runs on a background thread, so hop to main for engine work. */
    private class Bridge {
        private Engine e() { return Engine.get(MainActivity.this); }
        private void main(Runnable r) { runOnUiThread(r); }

        @JavascriptInterface public boolean isNative() { return true; }
        @JavascriptInterface public void play(final String json) { main(() -> e().play(json)); }
        @JavascriptInterface public void pause() { main(() -> e().pause()); }
        @JavascriptInterface public void resume() { main(() -> e().resume()); }
        @JavascriptInterface public void skip(final int dir) { main(() -> e().skip(dir)); }
        @JavascriptInterface public void seek(final int p) { main(() -> e().seek(p)); }
        @JavascriptInterface public void setRate(final double r) { main(() -> e().setRate((float) r)); }
        @JavascriptInterface public void setVoice(final String v) { main(() -> e().setVoice(v)); }
        @JavascriptInterface public void setSkip(final int b, final int f) { main(() -> e().setSkip(b, f)); }
        @JavascriptInterface public String state() { return e().stateJson(); }
        @JavascriptInterface public String voices() { return e().voicesJson(); }

        @JavascriptInterface
        public String clipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip()) return "";
                ClipData cd = cm.getPrimaryClip();
                if (cd == null || cd.getItemCount() == 0) return "";
                CharSequence t = cd.getItemAt(0).coerceToText(MainActivity.this);
                return t == null ? "" : t.toString();
            } catch (Exception ex) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean saveDownload(String name, String content) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                ContentResolver cr = getContentResolver();
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return false;
                try (OutputStream os = cr.openOutputStream(uri)) {
                    if (os == null) return false;
                    os.write(content.getBytes(StandardCharsets.UTF_8));
                }
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }
}
