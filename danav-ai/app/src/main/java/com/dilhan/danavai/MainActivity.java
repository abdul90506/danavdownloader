package com.dilhan.danavai;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Hosts the Danav AI page (assets/app): chat, web search, attachments, the live voice call, phone control and
 * the volume-up circle. Everything that needs the phone (models, keys, accessibility, microphone) is native.
 */
public class MainActivity extends Activity implements Bridge.Host {
    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean pageReady;
    /** The open Danav screen (null while the app is closed) — a circle started by volume-up reports here. */
    static volatile MainActivity inst;
    private boolean visible;
    boolean isVisible() { return visible; }

    /** Settings → Appearance (theme / accent / text size) — kept here so JS reads it back on start. */
    static android.content.SharedPreferences look(Context c) { return c.getSharedPreferences("look", Context.MODE_PRIVATE); }
    static void saveLook(Context c, String theme, String accent, String size, boolean compact) {
        look(c).edit().putString("theme", theme).putString("accent", accent).putString("size", size).putBoolean("compact", compact).apply();
    }
    /** System follows the phone's own light / dark setting. */
    static boolean isDark(Context c) {
        String t = look(c).getString("theme", "system");
        if ("dark".equals(t)) return true;
        if ("light".equals(t)) return false;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
    static int textZoom(Context c) { String z = look(c).getString("size", "m"); return "s".equals(z) ? 90 : "l".equals(z) ? 115 : 100; }
    void setTextZoom(int z) { if (web != null) web.getSettings().setTextZoom(z); }

    private boolean callFull;
    /** The call screen hides the status and navigation bars (swipe from the edge shows them for a moment). */
    void setCallFull(boolean on) {
        if (callFull == on) return;
        callFull = on;
        if (on) applyBars();
        else {
            androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).show(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            applyTheme();
        }
    }
    private void applyBars() {
        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#050507"));
        w.setNavigationBarColor(Color.parseColor("#050507"));
        androidx.core.view.WindowInsetsControllerCompat c = androidx.core.view.WindowCompat.getInsetsController(w, w.getDecorView());
        c.setSystemBarsBehavior(androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
    }
    void applyTheme() {
        boolean dark = isDark(this);
        int bg = dark ? Color.parseColor("#0f0f10") : Color.WHITE;
        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        // no divider and no contrast scrim above the phone's navigation strip: no thin line under the input
        if (Build.VERSION.SDK_INT >= 28) w.setNavigationBarDividerColor(bg);
        if (Build.VERSION.SDK_INT >= 29) { w.setNavigationBarContrastEnforced(false); w.setStatusBarContrastEnforced(false); }
        int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (!dark && Build.VERSION.SDK_INT >= 27) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);
        if (callFull) applyBars();
        if (web != null) { web.setBackgroundColor(bg); web.getSettings().setTextZoom(textZoom(this)); }
    }

    @Override public void onConfigurationChanged(@androidx.annotation.NonNull Configuration cfg) {
        super.onConfigurationChanged(cfg);
        applyTheme();
        if (pageReady) send("theme", "{\"dark\":" + isDark(this) + "}");
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        inst = this;
        web = new WebView(this);
        applyTheme();
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(this), "Vidra");
        web.setWebChromeClient(new WebChromeClient() {
            /** Camera for the video call (only after Android's camera permission was granted). */
            @Override public void onPermissionRequest(android.webkit.PermissionRequest r) {
                java.util.List<String> ok = new java.util.ArrayList<>();
                for (String res : r.getResources())
                    if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res) && checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) ok.add(res);
                if (ok.isEmpty()) r.deny(); else r.grant(ok.toArray(new String[0]));
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/app/index.html");
        handleIntent(getIntent());
    }

    @Override protected void onDestroy() {
        if (inst == this) inst = null;
        if (!LiveCall.isWake()) LiveCall.stop();
        LiveCall.stopPreview();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        if (pageReady) { send("resume", "{}"); send("phone", Bridge.phoneJson(this).toString()); }
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true; inst = this;
        CallBubble.hide();
        WakeService.ensure(this);
    }

    @Override protected void onStop() {
        super.onStop();
        visible = false;
        // a Danav call keeps going in the background: show the floating call bubble over other apps
        if (LiveCall.active() && !isChangingConfigurations()) CallBubble.show(this);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        if (i.getBooleanExtra("openCall", false)) {
            i.removeExtra("openCall");
            if (pageReady) send("callOpen", "{}");
        }
        i.setAction(null);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && callFull) applyBars();
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
        if (req == Bridge.REQ_SCREEN) {
            if (res == RESULT_OK && data != null) CallService.shareScreen(this, res, data);
            else send("live", "{\"t\":\"screen\",\"on\":false}");
        } else if (req == Bridge.REQ_AI_PICK) {
            Ai.importPicked(this, this, res == RESULT_OK ? data : null);
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == Bridge.REQ_MIC) {
            boolean ok = results.length > 0 && results[0] == 0;
            send("mic", "{\"ok\":" + ok + ",\"blocked\":" + (!ok && !shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)) + "}");
        }
        if (req == Bridge.REQ_PHONE) send("phone", Bridge.phoneJson(this).toString());
        if (req == Bridge.REQ_CAM) {
            boolean ok = results.length > 0 && results[0] == 0;
            send("cam", "{\"ok\":" + ok + ",\"blocked\":" + (!ok && !shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)) + "}");
        }
    }

    // ------------------------------------------------------------------ Bridge.Host

    @Override public Activity activity() { return this; }

    /** Push an event into the page: VB.event(type, payload). */
    @Override public void send(String type, String json) {
        if (web == null) return;
        final String js = "window.VB&&VB.event(" + org.json.JSONObject.quote(type) + "," + json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")";
        ui.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    @Override public void resolve(String reqId, String json) {
        if (web == null) return;
        final String js = "window.VB&&VB.resolve(" + org.json.JSONObject.quote(reqId) + "," + json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")";
        ui.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    @Override public void onPageReady() {
        pageReady = true;
        Intent i = getIntent();
        if (i != null && i.getBooleanExtra("openCall", false)) { i.removeExtra("openCall"); if (LiveCall.active()) send("callOpen", "{}"); }
    }
}
