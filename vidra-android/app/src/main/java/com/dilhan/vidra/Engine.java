package com.dilhan.vidra;

import android.content.Context;
import android.content.SharedPreferences;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Wraps yt-dlp (Python) + ffmpeg bundled by youtubedl-android.
 * - init() unpacks Python on first launch (a few seconds)
 * - analyze() runs `yt-dlp -J` and returns a compact JSON for the UI; the full info JSON is
 *   kept on disk so the download re-uses the exact same formats (no second extraction)
 * - update() replaces yt-dlp with the latest release (sites change often)
 */
public final class Engine {
    public static final int INIT = 0, READY = 1, FAILED = 2;
    private static volatile int state = INIT;
    private static volatile String stateMsg = "";
    private static volatile boolean updating = false;
    /** jobs + analyses hold the read lock, an engine update takes the write lock */
    static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
    private static final Object initWait = new Object();
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private Engine() {}

    public static int state() { return state; }
    public static String stateMsg() { return stateMsg; }
    public static boolean isUpdating() { return updating; }
    public static void addListener(Runnable r) { listeners.add(r); }
    public static void removeListener(Runnable r) { listeners.remove(r); }
    private static void changed() { for (Runnable r : listeners) try { r.run(); } catch (Exception ignored) {} }

    public static void init(final Context ctx) {
        new Thread(() -> {
            try {
                YoutubeDL.getInstance().init(ctx);
                FFmpeg.getInstance().init(ctx);
                state = READY; stateMsg = "";
            } catch (Throwable e) {
                state = FAILED; stateMsg = "The download engine could not start: " + e.getMessage();
            }
            synchronized (initWait) { initWait.notifyAll(); }
            changed();
            if (state == READY) {
                // warm-up: load Python + yt-dlp once so the first real "Get video" doesn't pay the cold-start cost
                try { YoutubeDLRequest w = new YoutubeDLRequest(java.util.Collections.emptyList()); w.addOption("--version"); YoutubeDL.getInstance().execute(w, "warmup", null); } catch (Throwable ignored) {}
                autoUpdate(ctx);
            }
        }, "engine-init").start();
    }

    /** Block until init finished; returns null when ready, otherwise an error message. */
    static String awaitReady() {
        synchronized (initWait) {
            while (state == INIT) { try { initWait.wait(); } catch (InterruptedException e) { return "Interrupted"; } }
        }
        return state == READY ? null : stateMsg;
    }

    public static String version(Context ctx) {
        try { String v = YoutubeDL.getInstance().versionName(ctx); return v == null ? "" : v; } catch (Throwable e) { return ""; }
    }

    /** Silent daily update so site changes (YouTube etc.) keep working. */
    private static void autoUpdate(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("engine", 0);
        long last = p.getLong("lastUpdate", 0);
        if (System.currentTimeMillis() - last < 24L * 3600 * 1000) return;
        String r = update(ctx);
        if (r != null && !r.startsWith("error")) p.edit().putLong("lastUpdate", System.currentTimeMillis()).apply();
    }

    /** @return "updated", "latest" or "error: ..." */
    public static String update(Context ctx) {
        if (awaitReady() != null) return "error: engine not ready";
        try {
            if (!LOCK.writeLock().tryLock(3, java.util.concurrent.TimeUnit.SECONDS)) return "error: wait until the current downloads finish";
        } catch (InterruptedException e) { return "error: interrupted"; }
        updating = true; changed();
        try {
            YoutubeDL.UpdateStatus s = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel._STABLE);
            ctx.getSharedPreferences("engine", 0).edit().putLong("lastUpdate", System.currentTimeMillis()).apply();
            return s == YoutubeDL.UpdateStatus.DONE ? "updated" : "latest";
        } catch (Throwable e) {
            return "error: " + e.getMessage();
        } finally {
            LOCK.writeLock().unlock();
            updating = false; changed();
        }
    }

    static File infoDir(Context ctx) { File d = new File(ctx.getCacheDir(), "info"); d.mkdirs(); return d; }

    /** Common request options (cookies from in-app sign-in, headers from the in-app browser). */
    static void commonOptions(Context ctx, YoutubeDLRequest req, JSONObject extra) {
        req.addOption("--no-warnings");
        req.addOption("--no-colors");
        // persistent cache: YouTube's player JS / signature solutions are reused instead of re-downloaded every time
        File cache = new File(ctx.getCacheDir(), "yt-dlp"); cache.mkdirs();
        req.addOption("--cache-dir", cache.getAbsolutePath());
        // broken IPv6 on many mobile networks makes every request wait for a timeout first
        req.addOption("--force-ipv4");
        req.addOption("--socket-timeout", "15");
        req.addOption("--retries", "5");
        req.addOption("--fragment-retries", "10");
        File cookies = Cookies.export(ctx);
        if (cookies != null) req.addOption("--cookies", cookies.getAbsolutePath());
        if (extra != null) {
            String ref = extra.optString("referer", "");
            if (!ref.isEmpty()) req.addOption("--referer", ref);
            String ua = extra.optString("ua", "");
            if (!ua.isEmpty()) req.addOption("--user-agent", ua);
        }
    }

    /**
     * Runs yt-dlp -J on the url. Returns JSON:
     * {ok:true, aid, url, items:[compact entry...]} or {ok:false, error:"raw yt-dlp error"}
     */
    public static String analyze(Context ctx, String url, JSONObject extra) {
        JSONObject res = new JSONObject();
        try {
            String err = awaitReady();
            if (err != null) return res.put("ok", false).put("error", err).toString();
            LOCK.readLock().lock();
            YoutubeDLResponse r;
            try {
                YoutubeDLRequest req = new YoutubeDLRequest(url);
                req.addOption("-J");
                req.addOption("--no-playlist");          // watch?v=..&list=.. -> just the video
                req.addOption("--playlist-end", "20");    // pure playlists / multi-video pages
                req.addOption("--no-check-formats");
                req.addOption("--no-write-comments");
                req.addOption("--extractor-retries", "1");
                commonOptions(ctx, req, extra);
                r = YoutubeDL.getInstance().execute(req, "analyze-" + UUID.randomUUID(), null);
            } finally {
                LOCK.readLock().unlock();
            }
            String out = r.getOut();
            int brace = out.indexOf('{');
            if (brace < 0) return res.put("ok", false).put("error", r.getErr()).toString();
            JSONObject info = new JSONObject(out.substring(brace));
            String aid = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1e6), 36);
            JSONArray items = new JSONArray();
            JSONArray entries = info.optJSONArray("entries");
            if (entries != null) {
                int n = 0;
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject e = entries.optJSONObject(i);
                    if (e == null || !e.has("formats") && !e.has("url")) continue;
                    if (!e.has("webpage_url")) e.put("webpage_url", info.optString("webpage_url", url));
                    save(new File(infoDir(ctx), aid + "_" + n + ".json"), e);
                    items.put(compact(e, n++));
                }
            } else {
                save(new File(infoDir(ctx), aid + "_0.json"), info);
                items.put(compact(info, 0));
            }
            res.put("ok", true).put("aid", aid).put("url", url).put("items", items);
            if (entries != null) res.put("playlistTitle", info.optString("title", ""));
            return res.toString();
        } catch (Throwable e) {
            try {
                return res.put("ok", false).put("error", e instanceof YoutubeDL.CanceledException ? "Canceled" : String.valueOf(e.getMessage())).toString();
            } catch (Exception x) { return "{\"ok\":false,\"error\":\"failed\"}"; }
        }
    }

    // ---- analyze cache: the same link read twice (prefetch, retry, browser panel) answers instantly
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.FutureTask<String>> inflight = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Object[]> done = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long CACHE_MS = 15 * 60 * 1000L;

    private static String key(String url, JSONObject extra) { return url + "|" + (extra == null ? "" : extra.toString()); }

    /** Like analyze(), but joins a read already running for the same link and reuses a fresh result. */
    public static String analyzeCached(final Context ctx, final String url, final JSONObject extra) {
        final String k = key(url, extra);
        Object[] c = done.get(k);
        if (c != null && System.currentTimeMillis() - (Long) c[0] < CACHE_MS) return (String) c[1];
        java.util.concurrent.FutureTask<String> f = inflight.get(k);
        if (f == null) {
            java.util.concurrent.FutureTask<String> nf = new java.util.concurrent.FutureTask<>(() -> analyze(ctx, url, extra));
            f = inflight.putIfAbsent(k, nf);
            if (f == null) f = nf;
        }
        f.run();                                   // no-op when another thread is already running it
        String r;
        try { r = f.get(); } catch (Exception e) { r = "{\"ok\":false,\"error\":\"failed\"}"; }
        inflight.remove(k, f);
        if (r != null && r.startsWith("{\"ok\":true")) {
            done.put(k, new Object[]{System.currentTimeMillis(), r});
            if (done.size() > 40) { String old = null; long t = Long.MAX_VALUE; for (java.util.Map.Entry<String, Object[]> e : done.entrySet()) if ((Long) e.getValue()[0] < t) { t = (Long) e.getValue()[0]; old = e.getKey(); } if (old != null) done.remove(old); }
        }
        return r;
    }

    /** Start reading a link in the background (copied link, video page in the browser) so it's ready when asked. */
    public static void prefetch(final Context ctx, final String url, final JSONObject extra) {
        if (url == null || !url.startsWith("http")) return;
        final String k = key(url, extra);
        Object[] c = done.get(k);
        if (inflight.containsKey(k) || (c != null && System.currentTimeMillis() - (Long) c[0] < CACHE_MS)) return;
        new Thread(() -> analyzeCached(ctx.getApplicationContext(), url, extra), "prefetch").start();
    }

    private static void save(File f, JSONObject o) throws Exception {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) { w.write(o.toString()); }
    }

    private static final String[] TOP = {"id", "title", "thumbnail", "duration", "uploader", "channel", "extractor_key",
            "webpage_url", "original_url", "width", "height", "live_status", "is_live", "_has_drm", "url", "ext", "vcodec",
            "acodec", "filesize", "protocol", "description"};
    private static final String[] FMT = {"format_id", "ext", "protocol", "vcodec", "acodec", "height", "width", "filesize",
            "tbr", "abr", "vbr", "fps", "format_note", "language", "language_preference", "dynamic_range", "has_drm", "url", "audio_channels"};

    /** Keep only what the UI needs (drops fragments, headers, captions… which can be megabytes). */
    static JSONObject compact(JSONObject e, int idx) throws Exception {
        JSONObject o = new JSONObject();
        for (String k : TOP) if (e.has(k) && !e.isNull(k)) o.put(k, e.get(k));
        if (o.has("description")) { String d = o.optString("description"); if (d.length() > 200) o.put("description", d.substring(0, 200)); }
        if (!o.has("thumbnail")) {
            JSONArray th = e.optJSONArray("thumbnails");
            if (th != null && th.length() > 0) { JSONObject t = th.optJSONObject(th.length() - 1); if (t != null) o.put("thumbnail", t.optString("url")); }
        }
        JSONArray fs = e.optJSONArray("formats"), out = new JSONArray();
        if (fs != null) for (int i = 0; i < fs.length(); i++) {
            JSONObject f = fs.optJSONObject(i); if (f == null) continue;
            JSONObject c = new JSONObject();
            for (String k : FMT) if (f.has(k) && !f.isNull(k)) c.put(k, f.get(k));
            if (c.optString("url").length() > 4000) c.remove("url");
            out.put(c);
        }
        o.put("formats", out);
        o.put("idx", idx);
        return o;
    }
}
