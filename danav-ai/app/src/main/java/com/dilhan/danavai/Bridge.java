package com.dilhan.danavai;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * window.Vidra.* — everything the Danav AI page is allowed to ask the phone for.
 * Model names and API keys never leave the native side, so the page never sees one.
 */
final class Bridge {
    static final int REQ_AI_PICK = 18, REQ_MIC = 19, REQ_SCREEN = 20, REQ_CAM = 21, REQ_PHONE = 22;

    interface Host {
        Activity activity();
        void send(String type, String json);
        void resolve(String reqId, String json);
        void onPageReady();
    }

    private final Host host;
    private final Activity a;

    Bridge(Host host) { this.host = host; this.a = host.activity(); }

    private void ui(Runnable r) { a.runOnUiThread(r); }

    // ------------------------------------------------------------------ core

    @JavascriptInterface public void ready() { ui(host::onPageReady); }

    @JavascriptInterface public String getState() {
        JSONObject o = new JSONObject();
        try {
            o.put("version", BuildConfigHelper.version(a)).put("sdk", android.os.Build.VERSION.SDK_INT)
                    .put("dark", MainActivity.isDark(a)).put("phone", phoneJson(a));
        } catch (Exception ignored) {}
        return o.toString();
    }

    @JavascriptInterface public void copy(String text) {
        ui(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Danav", text == null ? "" : text));
            } catch (Exception ignored) {}
        });
    }

    @JavascriptInterface public void toast(String m) { ui(() -> Toast.makeText(a, m, Toast.LENGTH_SHORT).show()); }

    @JavascriptInterface public boolean isDark() { return MainActivity.isDark(a); }

    /** Links inside an answer open in the phone's own browser — this app has no browser of its own. */
    @JavascriptInterface public void openBrowser(String url, String mode) {
        ui(() -> {
            String u = url == null ? "" : url.trim();
            if (u.isEmpty()) return;
            if (!u.matches("(?i)[a-z][a-z0-9+.-]*:.*")) u = "https://" + u;
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); } catch (Exception ignored) {}
        });
    }

    @JavascriptInterface public void openAppSettings() {
        ui(() -> { try { a.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName()))); } catch (Exception ignored) {} });
    }

    // ------------------------------------------------------------------ Danav AI chat

    @JavascriptInterface public void aiSend(String id, String json) { Ai.chat(a, host, id, json); }
    @JavascriptInterface public void aiStop(String id) { Ai.stop(id); }
    @JavascriptInterface public void aiTitle(String reqId, String text) { Ai.title(a, host, reqId, text == null ? "" : text); }
    @JavascriptInterface public void aiDeleteAtt(String json) { try { Ai.deleteAtt(a, new org.json.JSONArray(json)); } catch (Exception ignored) {} }
    @JavascriptInterface public void aiPick(String kind) {
        ui(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                if ("image".equals(kind)) i.setType("image/*"); else i.setType("*/*");
                a.startActivityForResult(i, REQ_AI_PICK);
            } catch (Exception e) { host.send("aiAtt", "{\"items\":[],\"errors\":[\"No file picker on this phone\"]}"); }
        });
    }

    // ------------------------------------------------------------------ the live voice call

    @JavascriptInterface public String micPerm() {
        return a.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED ? "granted" : "denied";
    }
    @JavascriptInterface public void requestMic() { ui(() -> a.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC)); }
    @JavascriptInterface public void liveStart(String voice) { ui(() -> { a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); LiveCall.start(a, host, voice); }); }
    @JavascriptInterface public void liveStop() { ui(() -> { a.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); LiveCall.stop(); }); }
    @JavascriptInterface public void liveMute(boolean on) { LiveCall.mute(on); }
    @JavascriptInterface public void liveSpeaker(boolean on) { ui(() -> LiveCall.speaker(on)); }
    @JavascriptInterface public boolean canOverlay() { return CallBubble.allowed(a); }
    @JavascriptInterface public String liveInfo() { return "{\"active\":" + LiveCall.active() + ",\"wake\":" + LiveCall.isWake() + ",\"muted\":" + LiveCall.muted() + "}"; }

    /** Screen sharing in a call: asks Android for permission, then the call service mirrors the screen. */
    @JavascriptInterface public void liveScreen(boolean on) {
        ui(() -> {
            if (!on) { ScreenShare.stop(true); return; }
            if (!LiveCall.active()) return;
            try {
                android.media.projection.MediaProjectionManager m = (android.media.projection.MediaProjectionManager) a.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                a.startActivityForResult(m.createScreenCaptureIntent(), REQ_SCREEN);
            } catch (Exception e) { LiveCall.emit("screen", false); }
        });
    }
    @JavascriptInterface public void liveVideo(String b64) { LiveCall.video(b64); }

    @JavascriptInterface public String camPerm() { return a.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED ? "granted" : "no"; }
    @JavascriptInterface public void requestCam() { ui(() -> a.requestPermissions(new String[]{android.Manifest.permission.CAMERA}, REQ_CAM)); }

    /** Full screen (status and navigation bars hidden) while the call screen is open. */
    @JavascriptInterface public void callFull(boolean on) { ui(() -> { if (a instanceof MainActivity) ((MainActivity) a).setCallFull(on); }); }

    @JavascriptInterface public void askOverlay() {
        ui(() -> { try { a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.getPackageName()))); } catch (Exception ignored) {} });
    }

    @JavascriptInterface public void voicePreview(String voice) { LiveCall.preview(a, host, voice); }
    @JavascriptInterface public void voicePreviewStop() { LiveCall.stopPreview(); }

    // ------------------------------------------------------------------ phone control & the volume-up shortcut

    static JSONObject phoneJson(Context c) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            android.os.BatteryManager bm = (android.os.BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            int level = bm == null ? -1 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return new JSONObject()
                    .put("level", level < 0 ? JSONObject.NULL : level)
                    .put("access", DanavAccess.on())
                    .put("overlay", CallBubble.allowed(c))
                    .put("contacts", Phone.has(c, android.Manifest.permission.READ_CONTACTS))
                    .put("calls", Phone.has(c, android.Manifest.permission.CALL_PHONE))
                    .put("mic", Phone.has(c, android.Manifest.permission.RECORD_AUDIO))
                    .put("battery", Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(c.getPackageName()))
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("wake", WakeService.state(c))
                    .put("memory", Memory.all(c));
        } catch (Exception e) { return new JSONObject(); }
    }

    @JavascriptInterface public String phoneState() { return phoneJson(a).toString(); }

    @JavascriptInterface public void phoneAsk(String what) {
        ui(() -> {
            try {
                switch (what) {
                    case "access": a.startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); break;
                    case "contacts": a.requestPermissions(new String[]{android.Manifest.permission.READ_CONTACTS}, REQ_PHONE); break;
                    case "calls": a.requestPermissions(new String[]{android.Manifest.permission.CALL_PHONE}, REQ_PHONE); break;
                    case "mic": a.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_PHONE); break;
                    case "battery":
                        a.startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + a.getPackageName()))); break;
                    case "appinfo":
                        a.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName()))); break;
                    case "overlay":
                        a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.getPackageName()))); break;
                }
            } catch (Exception e) { Toast.makeText(a, "This setting couldn't be opened on this phone", Toast.LENGTH_SHORT).show(); }
        });
    }

    /** Returns ok | mic | blocked. */
    @JavascriptInterface public String wakeSet(boolean on) { return WakeService.set(a, on); }

    // Danav AI memory (personalisation) — read, remove one note, wipe everything
    @JavascriptInterface public void memoryAdd(String text) { Memory.add(a, text); pushPhone(); }
    @JavascriptInterface public void memoryForget(String id) { Memory.remove(a, id); pushPhone(); }
    @JavascriptInterface public void memoryClear() { Memory.clear(a); pushPhone(); }
    private void pushPhone() { ui(() -> { try { host.send("phone", phoneJson(a).toString()); } catch (Exception ignored) {} }); }
}
