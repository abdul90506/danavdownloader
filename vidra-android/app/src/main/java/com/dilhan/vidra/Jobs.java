package com.dilhan.vidra;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlin.Unit;

/**
 * Download queue. Every job is one yt-dlp run:
 *   yt-dlp --load-info-json <saved info> -f <video>+<audio> --merge-output-format mp4/mkv
 * yt-dlp downloads the streams, ffmpeg merges audio + video, then the file is copied into
 * Download/<folder>. Progress is parsed from a machine-readable --progress-template line.
 * Pause = stop the process (the .part files stay), Resume = run again (yt-dlp continues).
 */
public final class Jobs {
    public interface Listener { void onJob(JSONObject job); }

    static final class Job {
        String id; JSONObject req; Context ctx;
        volatile String state = "queued";   // queued starting running merging saving done error paused
        volatile boolean pauseReq, cancelReq;
        long downloaded; long total = -1; double percent = -1; double speed; String error = ""; String phase = "";
        JSONArray notes = new JSONArray(); JSONObject result;
        long[] done; boolean[] finished; long started; boolean triedFresh;
        long lastEmit;
        File work;
    }

    private static final Map<String, Job> jobs = Collections.synchronizedMap(new LinkedHashMap<>());
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final ExecutorService pool = Executors.newFixedThreadPool(2);
    private static final String TEMPLATE = "download:VGP|%(progress.status)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.fragment_index)s|%(progress.fragment_count)s|%(info.format_id)s";

    private Jobs() {}

    public static void addListener(Listener l) { listeners.add(l); }
    public static void removeListener(Listener l) { listeners.remove(l); }

    public static int activeCount() {
        int n = 0;
        synchronized (jobs) { for (Job j : jobs.values()) if (isActive(j)) n++; }
        return n;
    }
    static boolean isActive(Job j) { return j.state.equals("queued") || j.state.equals("starting") || j.state.equals("running") || j.state.equals("merging") || j.state.equals("saving"); }

    public static List<JSONObject> snapshot() {
        List<JSONObject> out = new ArrayList<>();
        synchronized (jobs) { for (Job j : jobs.values()) out.add(toJson(j)); }
        return out;
    }

    static File workRoot(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File d = new File(base, "work"); d.mkdirs(); return d;
    }

    /** Remove leftovers of jobs that were running when the app was killed. */
    public static void cleanup(Context c) {
        File[] fs = workRoot(c).listFiles();
        if (fs != null) for (File f : fs) deleteTree(f);
        File[] info = Engine.infoDir(c).listFiles();
        long old = System.currentTimeMillis() - 24L * 3600 * 1000;
        if (info != null) for (File f : info) if (f.lastModified() < old) f.delete();
    }

    public static String start(Context c, JSONObject req) {
        Job j = new Job();
        j.ctx = c.getApplicationContext();
        j.id = "j" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1e5), 36);
        j.req = req;
        j.work = new File(workRoot(c), j.id);
        JSONArray st = req.optJSONArray("streams");
        int n = st == null || st.length() == 0 ? 1 : st.length();
        j.done = new long[n]; j.finished = new boolean[n];
        jobs.put(j.id, j);
        emit(j, true);
        submit(j);
        return j.id;
    }

    private static void submit(Job j) {
        j.state = "queued"; j.pauseReq = false; j.cancelReq = false; j.error = "";
        emit(j, true);
        ensureService(j.ctx);
        pool.submit(() -> run(j));
    }

    static void ensureService(Context c) {
        Intent i = new Intent(c, DownloadService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable ignored) { /* background start restrictions: downloads still run while the app is open */ }
    }

    public static void pause(String id) {
        Job j = jobs.get(id); if (j == null) return;
        if (j.state.equals("queued")) { j.pauseReq = true; j.state = "paused"; emit(j, true); return; }
        if (j.state.equals("running") || j.state.equals("starting")) { j.pauseReq = true; YoutubeDL.getInstance().destroyProcessById(j.id); }
    }

    public static void resume(String id) {
        Job j = jobs.get(id); if (j == null) return;
        if (j.state.equals("paused") || j.state.equals("error")) submit(j);
    }

    public static void cancel(String id) {
        Job j = jobs.get(id); if (j == null) return;
        j.cancelReq = true;
        if (isActive(j) && !j.state.equals("queued") && !j.state.equals("saving")) YoutubeDL.getInstance().destroyProcessById(j.id);
        if (!isActive(j) || j.state.equals("queued")) finishCancel(j);
    }

    public static void dismiss(String id) {
        Job j = jobs.get(id); if (j == null || isActive(j)) return;
        if (!j.state.equals("done")) deleteTree(j.work);
        jobs.remove(id);
        emitRemoved(id);
    }

    private static void finishCancel(Job j) {
        j.state = "canceled";
        deleteTree(j.work);
        jobs.remove(j.id);
        emitRemoved(j.id);
    }

    private static void emitRemoved(String id) {
        try { JSONObject o = new JSONObject().put("id", id).put("removed", true); for (Listener l : listeners) l.onJob(o); } catch (Exception ignored) {}
    }

    private static void run(Job j) {
        if (j.pauseReq || j.cancelReq) { if (j.cancelReq) finishCancel(j); return; }
        Context c = j.ctx;
        j.state = "starting"; j.phase = ""; j.started = System.currentTimeMillis();
        emit(j, true);
        String err = Engine.awaitReady();
        if (err != null) { fail(j, err); return; }
        if (j.cancelReq) { finishCancel(j); return; }
        if (j.pauseReq) { j.state = "paused"; emit(j, true); return; }
        if (j.req.optBoolean("live")) { fail(j, "Live streams can't be downloaded while they're live. Try again after the stream ends."); return; }
        j.work.mkdirs();
        boolean ok = false; String errText = null;
        Engine.LOCK.readLock().lock();
        try {
            YoutubeDLRequest r = buildRequest(j);
            YoutubeDL.getInstance().execute(r, j.id, (Float p, Long eta, String line) -> { onLine(j, line); return Unit.INSTANCE; });
            ok = true;
        } catch (YoutubeDL.CanceledException e) {
            // stopped by pause / cancel
        } catch (Throwable e) {
            errText = String.valueOf(e.getMessage());
        } finally {
            Engine.LOCK.readLock().unlock();
        }
        if (j.cancelReq) { finishCancel(j); return; }
        if (j.pauseReq && !ok) { j.state = "paused"; j.speed = 0; emit(j, true); return; }
        File media = findMedia(j.work);
        if (!ok) {
            String msg = lastError(errText);
            // URLs in the saved info expire after some hours → retry once with a fresh extraction
            if (!j.triedFresh && j.req.optString("aid").length() > 0 && msg.matches("(?is).*(HTTP Error 40[0-9]|expired|Unable to download|Requested format is not available).*")) {
                j.triedFresh = true;
                deleteTree(j.work);
                j.work.mkdirs();
                run(j);
                return;
            }
            boolean postOnly = media != null && msg.matches("(?is).*(postprocess|fixup|ffprobe).*") && allFinished(j);
            if (!postOnly) { fail(j, msg); return; }
            j.notes.put("The file was saved as downloaded (final repair step failed).");
        }
        if (media == null) { fail(j, "yt-dlp finished but no file was produced."); return; }
        try {
            j.state = "saving"; j.phase = "Saving to your phone"; emit(j, true);
            String ext = extOf(media.getName());
            String label = j.req.optString("label", "");
            String base = Saver.safeName(j.req.optString("title", "video")) + (label.isEmpty() || label.startsWith("Audio") || j.req.optBoolean("audio") ? "" : " (" + label + ")");
            String thumbFile = Saver.thumbnail(c, media, j.id);
            Saver.Result res = Saver.save(c, media, base, ext, (d, t) -> { j.percent = 100; j.phase = "Saving to your phone · " + (t > 0 ? (d * 100 / t) + "%" : ""); emit(j, false); });
            JSONObject h = new JSONObject();
            h.put("id", j.id).put("title", j.req.optString("title")).put("label", label).put("size", res.size)
                    .put("uri", res.uri.toString()).put("path", res.path).put("shown", res.shown).put("name", res.name).put("mime", res.mime)
                    .put("host", j.req.optString("host")).put("pageUrl", j.req.optString("pageUrl")).put("duration", j.req.optDouble("duration", 0))
                    .put("aspect", j.req.optDouble("aspect", 0)).put("thumb", j.req.optString("thumb")).put("finishedAt", System.currentTimeMillis())
                    .put("notes", j.notes).put("ext", ext);
            if (thumbFile != null) h.put("thumbFile", thumbFile);
            Store.addHistory(c, h);
            j.result = h;
            j.total = res.size; j.downloaded = res.size; j.percent = 100; j.speed = 0;
            j.state = "done"; j.phase = "";
            deleteTree(j.work);
            emit(j, true);
            Notify.done(c, j.id, j.req.optString("title"), res.uri, res.mime);
            emitHistory();
        } catch (Throwable e) {
            fail(j, "Could not save the file: " + e.getMessage());
        }
    }

    private static boolean allFinished(Job j) { for (boolean b : j.finished) if (!b) return false; return true; }

    private static YoutubeDLRequest buildRequest(Job j) {
        Context c = j.ctx;
        JSONObject q = j.req;
        File info = new File(Engine.infoDir(c), q.optString("aid") + "_" + q.optInt("idx") + ".json");
        YoutubeDLRequest r;
        if (!j.triedFresh && q.optString("aid").length() > 0 && info.exists()) {
            r = new YoutubeDLRequest(Collections.<String>emptyList());
            r.addOption("--load-info-json", info.getAbsolutePath());
        } else {
            r = new YoutubeDLRequest(q.optString("url"));
            r.addOption("--no-playlist");
        }
        r.addOption("-f", q.optString("format", "bv*+ba/b"));
        r.addOption("--merge-output-format", "mp4/webm/mkv");
        if ("mp3".equals(q.optString("convert"))) {          // audio → MP3 with the bundled ffmpeg
            r.addOption("-x");
            r.addOption("--audio-format", "mp3");
            r.addOption("--audio-quality", q.optInt("kbps", 192) + "K");
        }
        r.addOption("-o", new File(j.work, "media.%(ext)s").getAbsolutePath());
        r.addOption("--newline");
        r.addOption("--progress-template", TEMPLATE);
        r.addOption("--no-mtime");
        r.addOption("--concurrent-fragments", "8");       // HLS / DASH pieces fetched 8 at a time
        String src = q.optString("url", "") + " " + q.optString("pageUrl", "");
        // YouTube throttles one long request; 10 MB ranged requests keep it at full speed
        if (src.contains("youtube.com") || src.contains("youtu.be")) r.addOption("--http-chunk-size", "10M");
        r.addOption("--no-overwrites");
        JSONObject extra = q.optJSONObject("extra");
        Engine.commonOptions(c, r, extra);
        return r;
    }

    private static int streamIndex(Job j, String fmt) {
        JSONArray st = j.req.optJSONArray("streams");
        if (st != null) for (int i = 0; i < st.length(); i++) { JSONObject s = st.optJSONObject(i); if (s != null && fmt.equals(s.optString("id"))) return i; }
        for (int i = 0; i < j.finished.length; i++) if (!j.finished[i]) return i;
        return j.finished.length - 1;
    }

    private static long num(String s) { try { return s == null || s.equals("NA") || s.equals("None") ? -1 : (long) Double.parseDouble(s); } catch (Exception e) { return -1; } }

    static void onLine(Job j, String line) {
        if (line == null) return;
        if (line.startsWith("VGP|")) {
            String[] p = line.split("\\|", -1);
            if (p.length < 9) return;
            String status = p[1];
            long dl = num(p[2]), tot = num(p[3]), est = num(p[4]), fi = num(p[6]), fc = num(p[7]);
            double sp = 0; try { sp = p[5].equals("NA") ? 0 : Double.parseDouble(p[5]); } catch (Exception ignored) {}
            int k = streamIndex(j, p[8]);
            JSONArray st = j.req.optJSONArray("streams");
            double frac;
            if (tot > 0) frac = Math.min(1, dl / (double) tot);
            else if (fc > 0) frac = Math.min(1, fi / (double) fc);
            else if (est > 0) frac = Math.min(0.99, dl / (double) est);
            else frac = -1;
            if (status.equals("finished")) { j.finished[k] = true; j.done[k] = dl > 0 ? dl : tot; frac = 1; }
            else j.done[k] = Math.max(0, dl);
            // weights: exact sizes → bitrate → equal
            int n = j.finished.length;
            double[] w = new double[n]; boolean allSize = true, allTbr = true;
            for (int i = 0; i < n; i++) {
                JSONObject s = st == null ? null : st.optJSONObject(i);
                if (s == null || s.optLong("size", -1) <= 0) allSize = false;
                if (s == null || s.optDouble("tbr", 0) <= 0) allTbr = false;
            }
            long exactTotal = 0;
            for (int i = 0; i < n; i++) {
                JSONObject s = st == null ? null : st.optJSONObject(i);
                w[i] = allSize ? s.optLong("size") : allTbr ? s.optDouble("tbr") : 1;
                if (allSize) exactTotal += s.optLong("size");
            }
            double sumW = 0, got = 0; long bytes = 0;
            for (int i = 0; i < n; i++) {
                sumW += w[i]; bytes += j.done[i];
                if (j.finished[i]) got += w[i]; else if (i == k && frac >= 0) got += w[i] * frac;
            }
            j.downloaded = bytes;
            j.speed = status.equals("finished") ? 0 : sp;
            if (allSize) j.total = exactTotal;
            else if (n == 1 && tot > 0) j.total = tot;
            else j.total = -1;
            j.percent = frac < 0 && !j.finished[k] && n == 1 ? -1 : Math.min(99.9, got * 100 / sumW);
            j.state = "running"; j.phase = n > 1 ? (k == 0 ? "Video" : "Audio") : "";
            emit(j, status.equals("finished"));
        } else if (line.startsWith("[Merger]")) {
            j.state = "merging"; j.phase = "Merging audio and video"; j.speed = 0; emit(j, true);
        } else if (line.startsWith("[ExtractAudio]")) {
            j.state = "merging"; j.phase = "Converting to MP3"; j.speed = 0; emit(j, true);
        } else if (line.startsWith("[Fixup") || line.startsWith("[VideoConvertor]") || line.startsWith("[VideoRemuxer]")) {
            j.state = "merging"; j.phase = "Finishing file"; j.speed = 0; emit(j, true);
        } else if (line.contains("has already been downloaded")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("media\\.f([^.\\s]+)\\.").matcher(line);
            if (m.find()) { int k = streamIndex(j, m.group(1)); j.finished[k] = true; }
            else for (int i = 0; i < j.finished.length; i++) j.finished[i] = true;   // final file already there
        }
    }

    private static String lastError(String err) {
        if (err == null) return "Download failed.";
        String[] lines = err.split("\n"); String last = null;
        for (String l : lines) if (l.startsWith("ERROR:")) last = l.substring(6).trim();
        if (last == null) for (int i = lines.length - 1; i >= 0; i--) if (!lines[i].trim().isEmpty()) { last = lines[i].trim(); break; }
        return last == null ? "Download failed." : last;
    }

    private static void fail(Job j, String msg) {
        j.state = "error"; j.error = msg; j.speed = 0; emit(j, true);
    }

    static File findMedia(File dir) {
        File[] fs = dir.listFiles(); File best = null;
        if (fs == null) return null;
        for (File f : fs) {
            String n = f.getName().toLowerCase();
            if (n.endsWith(".part") || n.endsWith(".ytdl") || n.endsWith(".json") || n.endsWith(".temp") || n.contains(".part-frag") || n.endsWith(".tmp")) continue;
            // intermediate streams before the merge are named media.fNNN.ext
            if (n.matches("media\\.f[^.]+\\..+")) continue;
            if (best == null || f.length() > best.length()) best = f;
        }
        return best;
    }

    static String extOf(String n) { int i = n.lastIndexOf('.'); return i < 0 ? "mp4" : n.substring(i + 1); }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles(); if (fs != null) for (File x : fs) deleteTree(x);
        f.delete();
    }

    static JSONObject toJson(Job j) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", j.id).put("state", j.state).put("downloaded", j.downloaded).put("total", j.total)
                    .put("percent", j.percent).put("speed", j.speed).put("error", j.error).put("phase", j.phase)
                    .put("notes", j.notes).put("req", j.req).put("started", j.started);
            if (j.result != null) o.put("result", j.result);
        } catch (Exception ignored) {}
        return o;
    }

    private static void emit(Job j, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - j.lastEmit < 250) return;
        j.lastEmit = now;
        JSONObject o = toJson(j);
        for (Listener l : listeners) try { l.onJob(o); } catch (Exception ignored) {}
    }

    private static void emitHistory() {
        try { JSONObject o = new JSONObject().put("history", true); for (Listener l : listeners) l.onJob(o); } catch (Exception ignored) {}
    }
}
