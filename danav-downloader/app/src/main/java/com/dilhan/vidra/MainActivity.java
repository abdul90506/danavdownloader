package com.dilhan.vidra;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Hosts the Danav downloader UI (assets/app) and bridges it to the engine, library, players and ad blocker. */
public class MainActivity extends Activity implements Bridge.Host {
    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean pageReady;
    private String pendingUrl, pendingView;
    private final Jobs.Listener jobListener = job -> send("job", job.toString());
    private final Runnable engineListener = () -> send("engine", Bridge.engineJson(this).toString());
    private final MusicHub.Listener musicListener = this::send;
    private final Runnable adListener = () -> send("ad", AdBlock.state("").toString());

    /** Appearance (Settings → Appearance): light / dark / system, text size. */
    static boolean isDark(Context c) {
        String t = Store.settings(c).optString("theme", "light");
        if ("dark".equals(t)) return true;
        if ("system".equals(t)) return (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        return false;
    }
    static int textZoom(Context c) { String z = Store.settings(c).optString("textSize", "m"); return "s".equals(z) ? 90 : "l".equals(z) ? 115 : 100; }

    void applyTheme() {
        boolean dark = isDark(this);
        int bg = dark ? Color.parseColor("#0f0f10") : Color.WHITE;
        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (!dark && Build.VERSION.SDK_INT >= 27) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);
        if (web != null) { web.setBackgroundColor(bg); web.getSettings().setTextZoom(textZoom(this)); }
    }

    @Override public void onConfigurationChanged(@androidx.annotation.NonNull android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        applyTheme();
        if (pageReady) send("theme", "{\"dark\":" + isDark(this) + "}");
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        applyTheme();
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);           // thumbnails cached on disk
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(textZoom(this));
        web.addJavascriptInterface(new Bridge(this), "Vidra");
        web.setWebViewClient(new WebViewClient() {
            /** https://vault.vidra/t/<id> (thumbnail) and /f/<id> (photo) — decrypted in memory, only while unlocked. */
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!"vault.vidra".equals(u.getHost())) return null;
                java.util.Map<String, String> hd = new java.util.HashMap<>();
                hd.put("Cache-Control", "no-store"); hd.put("Access-Control-Allow-Origin", "*");
                try {
                    java.util.List<String> seg = u.getPathSegments();
                    boolean thumb = "t".equals(seg.get(0));
                    JSONObject it = Vault.item(seg.get(1));
                    if (it == null || !Vault.isUnlocked()) throw new Exception();
                    return new android.webkit.WebResourceResponse(thumb ? "image/jpeg" : it.optString("mime", "image/jpeg"), null, 200, "OK", hd, Vault.open(MainActivity.this, seg.get(1), thumb, 0));
                } catch (Exception e) {
                    return new android.webkit.WebResourceResponse("text/plain", null, 404, "Not found", hd, new java.io.ByteArrayInputStream(new byte[0]));
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/app/index.html");
        Jobs.addListener(jobListener);
        Engine.addListener(engineListener);
        MusicHub.addListener(musicListener);
        AdBlock.addListener(adListener);
        handleIntent(getIntent());
    }

    @Override protected void onDestroy() {
        Jobs.removeListener(jobListener);
        Engine.removeListener(engineListener);
        MusicHub.removeListener(musicListener);
        AdBlock.removeListener(adListener);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        Vault.holdLock = false;
        if (pageReady) { send("vault", Vault.state(this).toString()); send("resume", "{\"perm\":" + JSONObject.quote(Library.permState(this)) + "}"); send("ad", AdBlock.state("").toString()); }
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);

    static String findUrl(CharSequence text) {
        if (text == null) return null;
        Matcher m = URL.matcher(text);
        return m.find() ? m.group().replaceAll("[).,;!]+$", "") : null;
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        String url = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            url = findUrl(i.getStringExtra(Intent.EXTRA_TEXT));
            if (url == null) url = findUrl(i.getStringExtra(Intent.EXTRA_SUBJECT));
        } else if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            String sc = i.getData().getScheme();
            if ("http".equals(sc) || "https".equals(sc)) url = i.getDataString();
        } else if (Build.VERSION.SDK_INT >= 23 && Intent.ACTION_PROCESS_TEXT.equals(i.getAction())) {
            url = findUrl(i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT));
        }
        String view = i.getStringExtra("view");
        i.setAction(null); i.removeExtra("view");
        if (url != null) { pendingUrl = url; flushPending(); }
        if (view != null) { pendingView = view; flushPending(); }
    }

    private void flushPending() {
        if (!pageReady) return;
        try {
            if (pendingUrl != null) { send("shared", new JSONObject().put("url", pendingUrl).toString()); pendingUrl = null; }
            if (pendingView != null) { send("view", new JSONObject().put("view", pendingView).toString()); pendingView = null; }
        } catch (Exception ignored) {}
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && pageReady && Store.settings(this).optBoolean("clipboard", true)) {
            String u = findUrl(Bridge.clipboardText(this));
            if (u != null) try { send("clipboard", new JSONObject().put("url", u).toString()); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (!pageReady) { super.onBackPressed(); return; }
        web.evaluateJavascript("window.VB && VB.back ? VB.back() : false", v -> {
            if (!"true".equals(v)) moveTaskToBack(true);
        });
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == Bridge.REQ_BROWSER) {
            String r = data == null ? null : data.getStringExtra("result");
            send("browser", r != null ? r : "{\"canceled\":true}");
        } else if (req == Bridge.REQ_DELETE) {
            send("deleted", "{\"ok\":" + (res == RESULT_OK) + "}");        } else if (req == Bridge.REQ_VAULT_PICK || req == Bridge.REQ_VAULT_TREE) {
            Bridge.onVaultPicked(this, this, req, res == RESULT_OK ? data : null);
        } else if (req == Bridge.REQ_VAULT_DEL) {
            Vault.holdLock = false;
            send("vaultRemoved", "{\"ok\":" + (res == RESULT_OK) + "}");
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == Bridge.REQ_MEDIA) {
            boolean denyForever = false;
            for (int i = 0; i < perms.length; i++) if (results[i] != 0 && !shouldShowRequestPermissionRationale(perms[i])) denyForever = true;
            try { send("perm", new JSONObject().put("perm", Library.permState(this)).put("blocked", denyForever).toString()); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------ Bridge.Host

    @Override public Activity activity() { return this; }

    /** Push an event into the page: VB.event(type, payload). */
    @Override public void send(String type, String json) {
        if (web == null) return;
        final String js = "window.VB&&VB.event(" + JSONObject.quote(type) + "," + json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")";
        ui.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    @Override public void resolve(String reqId, String json) {
        if (web == null) return;
        final String js = "window.VB&&VB.resolve(" + JSONObject.quote(reqId) + "," + json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")";
        ui.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    @Override public void onPageReady() { pageReady = true; flushPending(); }
}
