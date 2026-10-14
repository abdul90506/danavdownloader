package com.dilhan.vidra;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** window.Vidra.* — shared by the main UI and the browser's download / ad-block panels. */
final class Bridge {
    static final int REQ_BROWSER = 11, REQ_PERM = 12, REQ_MEDIA = 13, REQ_DELETE = 14, REQ_VAULT_PICK = 15, REQ_VAULT_TREE = 16, REQ_VAULT_DEL = 17, REQ_AI_PICK = 18, REQ_MIC = 19, REQ_SCREEN = 20, REQ_CAM = 21, REQ_PHONE = 22;

    interface Host {
        Activity activity();
        void send(String type, String json);
        void resolve(String reqId, String json);
        void onPageReady();
        /** Browser panel only: close / resize / browser actions. */
        default void panel(String cmd, String json) {}
    }

    private final Host host;
    private final Activity a;

    Bridge(Host host) { this.host = host; this.a = host.activity(); }

    private void ui(Runnable r) { a.runOnUiThread(r); }

    static JSONObject engineJson(Context c) {
        JSONObject o = new JSONObject();
        try {
            int st = Engine.state();
            o.put("state", st == Engine.READY ? "ready" : st == Engine.INIT ? "init" : "failed")
                    .put("message", Engine.stateMsg()).put("updating", Engine.isUpdating())
                    .put("version", st == Engine.READY ? Engine.version(c) : "");
        } catch (Exception ignored) {}
        return o;
    }

    void ensureDownloadPermissions() {
        ui(() -> {
            ArrayList<String> need = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.POST_NOTIFICATIONS);
            if (Build.VERSION.SDK_INT < 29 && a.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            if (!need.isEmpty()) a.requestPermissions(need.toArray(new String[0]), REQ_PERM);
        });
    }

    static String clipboardText(Context c) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData d = cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return null;
            CharSequence t = d.getItemAt(0).coerceToText(c);
            return t == null ? null : t.toString();
        } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ core (same as 1.0)

    @JavascriptInterface public void ready() { ui(host::onPageReady); }

    @JavascriptInterface public String getState() {
        JSONObject o = new JSONObject();
        try {
            JSONArray jobs = new JSONArray();
            for (JSONObject j : Jobs.snapshot()) jobs.put(j);
            JSONArray signed = new JSONArray();
            for (String h : Cookies.hosts(a)) signed.put(h);
            o.put("engine", engineJson(a)).put("settings", Store.settings(a)).put("jobs", jobs)
                    .put("history", Store.history(a)).put("signedIn", signed)
                    .put("sdk", Build.VERSION.SDK_INT).put("appVersion", BuildConfigHelper.version(a))
                    .put("ad", AdBlock.state("")).put("music", MusicHub.state()).put("queue", new JSONArray(MusicHub.queueJson()))
                    .put("perm", Library.permState(a));
        } catch (Exception ignored) {}
        return o.toString();
    }

    @JavascriptInterface public void analyze(final String reqId, final String url, final String extra) {
        new Thread(() -> {
            JSONObject ex = null;
            try { if (extra != null && !extra.isEmpty()) ex = new JSONObject(extra); } catch (Exception ignored) {}
            host.resolve(reqId, Engine.analyzeCached(a, url, ex));
        }, "analyze").start();
    }

    /** Read a copied / pasted link ahead of time. */
    @JavascriptInterface public void prefetch(final String url) { Engine.prefetch(a, url, null); }

    /** Instant title + thumbnail (page meta tags / oEmbed) while yt-dlp reads the formats. */
    @JavascriptInterface public void peek(final String reqId, final String url) {
        new Thread(() -> host.resolve(reqId, Peek.get(url)), "peek").start();
    }

    @JavascriptInterface public String download(String reqJson) {
        try { ensureDownloadPermissions(); return Jobs.start(a, new JSONObject(reqJson)); }
        catch (Exception e) { return ""; }
    }

    @JavascriptInterface public void pause(String id) { Jobs.pause(id); }
    @JavascriptInterface public void resume(String id) { Jobs.resume(id); }
    @JavascriptInterface public void cancel(String id) { Jobs.cancel(id); }
    @JavascriptInterface public void dismiss(String id) { Jobs.dismiss(id); }

    /** Plays a downloaded file in Vidra's own player (video) or music player (audio). */
    @JavascriptInterface public String openFile(String id) {
        JSONObject h = Store.findHistory(a, id);
        if (h == null) return "missing";
        Uri u = Uri.parse(h.optString("uri"));
        if (!Saver.exists(a, u)) return "missing";
        String mime = h.optString("mime");
        try {
            if (mime.startsWith("audio")) {
                JSONObject it = new JSONObject().put("uri", u.toString()).put("title", h.optString("title"))
                        .put("artist", h.optString("host")).put("art", h.optString("thumbFile")).put("dur", (long) (h.optDouble("duration", 0) * 1000));
                MusicHub.command(a, "queue", new JSONObject().put("items", new JSONArray().put(it)));
                return "music";
            }
            // every downloaded video (newest first, same order as the Downloads list) so swiping moves through them
            JSONArray all = Store.history(a), items = new JSONArray();
            int index = 0;
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.optJSONObject(i);
                if (o == null || o.optString("uri").isEmpty()) continue;
                boolean me = id.equals(o.optString("id"));
                String m = o.optString("mime");
                if (!me && (m.startsWith("audio") || (!m.startsWith("video") && !o.optString("name").matches("(?i).*\\.(mp4|mkv|webm|mov|m4v|3gp|avi|ts|flv)$")))) continue;
                Uri ou = Uri.parse(o.optString("uri"));
                if (!me && !Saver.exists(a, ou)) continue;
                if (me) index = items.length();
                items.put(new JSONObject().put("uri", ou.toString()).put("title", o.optString("title")).put("sub", o.optString("label")));
            }
            if (items.length() == 0) { items.put(new JSONObject().put("uri", u.toString()).put("title", h.optString("title")).put("sub", h.optString("label"))); index = 0; }
            final JSONArray fi = items; final int fx = index;
            ui(() -> PlayerActivity.start(a, fi, fx));
            return "ok";
        } catch (Exception e) { return "noapp"; }
    }

    @JavascriptInterface public String openFileWith(String id) {
        JSONObject h = Store.findHistory(a, id);
        if (h == null) return "missing";
        Uri u = Uri.parse(h.optString("uri"));
        if (!Saver.exists(a, u)) return "missing";
        try { a.startActivity(Intent.createChooser(Saver.openIntent(u, h.optString("mime")), "Open with")); return "ok"; }
        catch (Exception e) { return "noapp"; }
    }

    @JavascriptInterface public String shareFile(String id) {
        JSONObject h = Store.findHistory(a, id);
        if (h == null) return "missing";
        Uri u = Uri.parse(h.optString("uri"));
        if (!Saver.exists(a, u)) return "missing";
        try { a.startActivity(Saver.shareIntent(u, h.optString("mime"), h.optString("title"))); return "ok"; }
        catch (Exception e) { return "noapp"; }
    }

    @JavascriptInterface public void shareText(String text) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        try { a.startActivity(Intent.createChooser(i, "Share link")); } catch (Exception ignored) {}
    }

    @JavascriptInterface public boolean deleteItem(String id, boolean alsoFile) {
        JSONObject h = Store.findHistory(a, id);
        boolean ok = true;
        if (h != null && alsoFile) ok = Saver.delete(a, Uri.parse(h.optString("uri")), h.optString("path"));
        Store.removeHistory(a, id);
        return ok;
    }

    @JavascriptInterface public void clearHistory() { Store.clearHistory(a); }

    @JavascriptInterface public void setSettings(String json) {
        try { Store.setSettings(a, new JSONObject(json)); } catch (Exception ignored) {}
    }

    @JavascriptInterface public void copy(String text) {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("link", text));
    }

    @JavascriptInterface public String paste() { String t = clipboardText(a); return t == null ? "" : t; }

    @JavascriptInterface public void openExternal(String url) {
        try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
    }

    @JavascriptInterface public void openBrowser(String url, String mode) {
        Intent i = new Intent(a, BrowserActivity.class).putExtra("url", url).putExtra("mode", mode);
        ui(() -> {
            if ("signin".equals(mode)) a.startActivityForResult(i, REQ_BROWSER); else a.startActivity(i);
        });
    }

    @JavascriptInterface public void signOutAll() { ui(() -> Cookies.clearAll(a)); }

    @JavascriptInterface public void updateEngine(final String reqId) {
        new Thread(() -> {
            String r = Engine.update(a);
            try { host.resolve(reqId, new JSONObject().put("result", r).put("version", Engine.version(a)).toString()); } catch (Exception ignored) {}
        }, "update").start();
    }

    @JavascriptInterface public void toast(String m) { ui(() -> Toast.makeText(a, m, Toast.LENGTH_SHORT).show()); }

    // ------------------------------------------------------------------ library

    @JavascriptInterface public String mediaPerm() { return Library.permState(a); }

    @JavascriptInterface public void requestMediaPerm() { ui(() -> a.requestPermissions(Library.permissions(), REQ_MEDIA)); }

    @JavascriptInterface public void openAppSettings() {
        try { a.startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName()))); } catch (Exception ignored) {}
    }

    @JavascriptInterface public void media(final String reqId, final String kind) {
        new Thread(() -> host.resolve(reqId, ("audio".equals(kind) ? Library.audio(a) : Library.videos(a)).toString()), "media").start();
    }

    /** [{k:'v'|'a', id, mod, uri?}] → events "thumb" {k,id,path}. */
    @JavascriptInterface public void thumbs(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.getJSONObject(i);
                Library.thumbPool.execute(() -> {
                    String k = o.optString("k", "v");
                    String p = Library.thumb(a, k, o.optLong("id"), o.optLong("mod"), o.optString("uri"));
                    try { host.send("thumb", new JSONObject().put("k", k).put("id", o.opt("id")).put("path", p).toString()); } catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}
    }

    /** {items:[{uri,title,sub}], index} */
    @JavascriptInterface public void playVideos(String json) {
        try {
            JSONObject o = new JSONObject(json);
            ui(() -> PlayerActivity.start(a, o.optJSONArray("items"), o.optInt("index")));
        } catch (Exception ignored) {}
    }

    @JavascriptInterface public void deleteMedia(String json) {
        ui(() -> {
            try {
                JSONArray arr = new JSONArray(json);
                List<Uri> uris = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) uris.add(Uri.parse(arr.getString(i)));
                PendingIntent pi = Library.delete(a, uris);
                if (pi == null) host.send("deleted", "{\"ok\":true}");
                else a.startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
            } catch (Exception e) { host.send("deleted", "{\"ok\":false}"); }
        });
    }

    // ------------------------------------------------------------------ private folder

    /** Settings → Appearance: {theme, accent, textSize, compact}. Returns whether the UI should be dark. */
    @JavascriptInterface public boolean setTheme(String json) {
        try { Store.setSettings(a, new JSONObject(json)); } catch (Exception ignored) {}
        if (a instanceof MainActivity) ui(((MainActivity) a)::applyTheme);
        return MainActivity.isDark(a);
    }
    @JavascriptInterface public boolean isDark() { return MainActivity.isDark(a); }

    @JavascriptInterface public String vaultState() { return Vault.state(a).toString(); }
    @JavascriptInterface public void vaultSetup(String reqId, String pin) { new Thread(() -> host.resolve(reqId, Vault.setup(a, pin).toString()), "vault").start(); }
    @JavascriptInterface public void vaultUnlock(String reqId, String pin) { new Thread(() -> host.resolve(reqId, Vault.unlock(a, pin).toString()), "vault").start(); }
    @JavascriptInterface public void vaultChangePin(String reqId, String old, String pin) { new Thread(() -> host.resolve(reqId, Vault.changePin(a, old, pin).toString()), "vault").start(); }
    @JavascriptInterface public void vaultLock() { Vault.lock(a); }
    @JavascriptInterface public void vaultErase(String reqId) { new Thread(() -> host.resolve(reqId, Vault.erase(a).toString()), "vault").start(); }
    @JavascriptInterface public String vaultList() { return Vault.list().toString(); }
    /** No screenshots / recent-apps preview while the private folder is on screen. */
    @JavascriptInterface public void secure(boolean on) {
        ui(() -> { if (on) a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE); else a.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE); });
    }

    static boolean vaultRemove = true;

    /** kind: media | files | folder */
    @JavascriptInterface public void vaultPick(String kind, boolean remove) {
        if (!Vault.isUnlocked()) return;
        vaultRemove = remove;
        ui(() -> {
            try {
                Vault.holdLock = true;
                if ("folder".equals(kind)) {
                    a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION), REQ_VAULT_TREE);
                } else {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    if ("media".equals(kind)) i.setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                    else i.setType("*/*");
                    a.startActivityForResult(i, REQ_VAULT_PICK);
                }
            } catch (Exception e) { Vault.holdLock = false; host.send("vault", "{\"error\":\"No file picker on this phone\"}"); }
        });
    }

    /** Called by MainActivity with the picker result. */
    static void onVaultPicked(Activity a, Host host, int req, Intent data) {
        Vault.holdLock = false;
        if (data == null) { host.send("vault", "{\"canceled\":true}"); return; }
        List<Vault.Src> srcs = new ArrayList<>();
        if (req == REQ_VAULT_TREE && data.getData() != null) {
            final Uri tree = data.getData();
            new Thread(() -> { srcs.addAll(Vault.walkTree(a, tree)); runImport(a, host, srcs, vaultRemove); }, "vault-import").start();
            return;
        }
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) srcs.add(new Vault.Src(data.getClipData().getItemAt(i).getUri(), "", false));
        else if (data.getData() != null) srcs.add(new Vault.Src(data.getData(), "", false));
        new Thread(() -> runImport(a, host, srcs, vaultRemove), "vault-import").start();
    }

    /** Videos / music from Vidra's library → private folder. */
    @JavascriptInterface public void vaultImportUris(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray u = o.optJSONArray("uris");
            List<Vault.Src> srcs = new ArrayList<>();
            for (int i = 0; u != null && i < u.length(); i++) srcs.add(new Vault.Src(Uri.parse(u.getString(i)), o.optString("folder", ""), true));
            final boolean remove = o.optBoolean("remove", true);
            new Thread(() -> runImport(a, host, srcs, remove), "vault-import").start();
        } catch (Exception ignored) {}
    }

    private static void runImport(Activity a, Host host, List<Vault.Src> srcs, boolean remove) {
        if (srcs.isEmpty()) { host.send("vault", "{\"canceled\":true}"); return; }
        final long[] last = {0};
        JSONObject r = Vault.importAll(a, srcs, remove, (done, total, name) -> {
            long now = System.currentTimeMillis();
            if (now - last[0] < 250 && done < total) return;
            last[0] = now;
            try { host.send("vault", new JSONObject().put("busy", done < total).put("done", done).put("total", total).put("name", name).toString()); } catch (Exception ignored) {}
        });
        try { host.send("vault", new JSONObject().put("busy", false).put("result", r).toString()); } catch (Exception ignored) {}
        JSONArray del = r.optJSONArray("mediaToDelete");
        if (del != null && del.length() > 0) {
            a.runOnUiThread(() -> {
                try {
                    List<Uri> uris = new ArrayList<>();
                    for (int i = 0; i < del.length(); i++) uris.add(Uri.parse(del.getString(i)));
                    PendingIntent pi = Library.delete(a, uris);
                    if (pi == null) host.send("vaultRemoved", "{\"ok\":true}");
                    else { Vault.holdLock = true; a.startIntentSenderForResult(pi.getIntentSender(), REQ_VAULT_DEL, null, 0, 0, 0); }
                } catch (Exception e) { host.send("vaultRemoved", "{\"ok\":false}"); }
            });
        }
    }

    @JavascriptInterface public void vaultPlay(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray ids = o.getJSONArray("ids"), items = new JSONArray();
            for (int i = 0; i < ids.length(); i++) {
                JSONObject it = Vault.item(ids.getString(i));
                if (it != null) items.put(new JSONObject().put("uri", "vault://" + it.optString("id")).put("title", it.optString("name")).put("sub", "Private"));
            }
            final int idx = o.optInt("index");
            ui(() -> PlayerActivity.start(a, items, idx));
        } catch (Exception ignored) {}
    }

    /** Other files (PDF, documents…): decrypted to a temporary file that's removed when the folder locks. */
    @JavascriptInterface public void vaultOpen(String reqId, String id) {
        new Thread(() -> {
            try {
                JSONObject it = Vault.item(id);
                java.io.File f = Vault.decryptToTemp(a, id);
                Uri u = androidx.core.content.FileProvider.getUriForFile(a, a.getPackageName() + ".files", f);
                Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, it == null ? "*/*" : it.optString("mime", "*/*")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Vault.holdLock = true;
                ui(() -> { try { a.startActivity(Intent.createChooser(i, "Open with")); } catch (Exception e) { Vault.holdLock = false; } });
                host.resolve(reqId, "{\"ok\":true}");
            } catch (Exception e) { host.resolve(reqId, "{\"ok\":false}"); }
        }, "vault-open").start();
    }

    @JavascriptInterface public void vaultRestore(String reqId, String idsJson) {
        new Thread(() -> {
            int ok = 0, bad = 0; String shown = "";
            try {
                JSONArray ids = new JSONArray(idsJson);
                for (int i = 0; i < ids.length(); i++) { JSONObject r = Vault.restore(a, ids.getString(i)); if (r.optBoolean("ok")) { ok++; shown = r.optString("shown"); } else bad++; }
                host.resolve(reqId, new JSONObject().put("ok", ok).put("failed", bad).put("shown", shown).toString());
            } catch (Exception e) { host.resolve(reqId, "{\"ok\":0,\"failed\":1}"); }
        }, "vault").start();
    }

    @JavascriptInterface public void vaultDelete(String reqId, String idsJson) {
        new Thread(() -> {
            int ok = 0;
            try { JSONArray ids = new JSONArray(idsJson); for (int i = 0; i < ids.length(); i++) { Vault.delete(a, ids.getString(i)); ok++; } } catch (Exception ignored) {}
            host.resolve(reqId, "{\"ok\":" + ok + "}");
        }, "vault").start();
    }

    @JavascriptInterface public void vaultMove(String idsJson, String folder) {
        try { JSONArray ids = new JSONArray(idsJson); for (int i = 0; i < ids.length(); i++) Vault.setFolder(a, ids.getString(i), folder); } catch (Exception ignored) {}
    }

    @JavascriptInterface public void shareMedia(String uri, String mime) {
        try { a.startActivity(Saver.shareIntent(Uri.parse(uri), mime, "")); } catch (Exception ignored) {}
    }

    @JavascriptInterface public void openWith(String uri, String mime) {
        try { a.startActivity(Intent.createChooser(Saver.openIntent(Uri.parse(uri), mime), "Open with")); } catch (Exception ignored) {}
    }

    @JavascriptInterface public String storage() {
        try {
            StatFs s = new StatFs(Environment.getExternalStorageDirectory().getPath());
            return new JSONObject().put("free", s.getAvailableBytes()).put("total", s.getTotalBytes()).toString();
        } catch (Exception e) { return "{}"; }
    }

    // ------------------------------------------------------------------ music

    @JavascriptInterface public void music(String cmd, String json) {
        JSONObject o;
        try { o = json == null || json.isEmpty() ? new JSONObject() : new JSONObject(json); } catch (Exception e) { o = new JSONObject(); }
        MusicHub.command(a, cmd, o);
    }

    @JavascriptInterface public String musicState() { return MusicHub.state().toString(); }

    // ------------------------------------------------------------------ ad block

    @JavascriptInterface public String adState(String host) { return AdBlock.state(host).toString(); }

    @JavascriptInterface public void setAdLevel(String hostName, int level) { AdBlock.setLevel(hostName, level); }

    @JavascriptInterface public void adReset(String what) {
        if ("sites".equals(what)) AdBlock.resetSites(); else if ("stats".equals(what)) AdBlock.resetStats();
    }

    // ------------------------------------------------------------------ browser panel

    // ---------------------------------------------------------------- Danav AI (keys & models stay native)
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
    @JavascriptInterface public String micPerm() {
        return a.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED ? "granted" : "denied";
    }
    @JavascriptInterface public void requestMic() { ui(() -> a.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC)); }
    @JavascriptInterface public void liveStart(String voice) { ui(() -> { a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); LiveCall.start(a, host, voice); }); }
    @JavascriptInterface public void liveStop() { ui(() -> { a.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); LiveCall.stop(); }); }
    @JavascriptInterface public void liveMute(boolean on) { LiveCall.mute(on); }
    @JavascriptInterface public void liveSpeaker(boolean on) { ui(() -> LiveCall.speaker(on)); }
    @JavascriptInterface public boolean canOverlay() { return CallBubble.allowed(a); }
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
    // ---------------------------------------------------------------- Danav AI phone control & Hey Danav

    static JSONObject phoneJson(Context c) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return new JSONObject()
                    .put("access", DanavAccess.on())
                    .put("overlay", CallBubble.allowed(c))
                    .put("contacts", Phone.has(c, android.Manifest.permission.READ_CONTACTS))
                    .put("calls", Phone.has(c, android.Manifest.permission.CALL_PHONE))
                    .put("mic", Phone.has(c, android.Manifest.permission.RECORD_AUDIO))
                    .put("battery", Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(c.getPackageName()))
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("wake", WakeService.state(c));
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
            } catch (Exception e) { V_toast("This setting couldn't be opened on this phone"); }
        });
    }
    private void V_toast(String m) { Toast.makeText(a, m, Toast.LENGTH_SHORT).show(); }
    /** Returns ok | model | mic | blocked. */
    @JavascriptInterface public String wakeSet(boolean on) { return WakeService.set(a, on); }
    @JavascriptInterface public void wakeDownload() { WakeService.download(a); }
    @JavascriptInterface public void wakeDelete() { WakeService.deleteModel(a); }
    /** An ongoing call the page doesn't know about yet (e.g. started by Hey Danav while the app was closed). */
    @JavascriptInterface public String liveInfo() { return "{\"active\":" + LiveCall.active() + ",\"wake\":" + LiveCall.isWake() + ",\"muted\":" + LiveCall.muted() + "}"; }

    @JavascriptInterface public void voicePreview(String voice) { LiveCall.preview(a, host, voice); }
    @JavascriptInterface public void voicePreviewStop() { LiveCall.stopPreview(); }

    @JavascriptInterface public void panel(String cmd, String json) { ui(() -> host.panel(cmd, json)); }
}
