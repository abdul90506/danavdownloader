package com.dilhan.vidra;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * "Hey Danav": listens on the phone for Danav's name with a small offline speech model (nothing leaves the phone
 * until the name is heard), then starts a Danav AI call shown as the floating bubble — even when the app is closed.
 * The microphone is released while any call is running.
 */
public class WakeService extends Service {
    private static final String CH = "danav_wake";
    private static final int ID = 7302, RATE = 16000;
    static final String ACT_OFF = "com.dilhan.vidra.WAKE_OFF";
    static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip";
    private static final String WORD = "दानव";
    private static final String GRAMMAR = "[\"हे दानव\", \"दानव\", \"[unk]\"]";
    private static final float MIN_CONF = 0.62f;        // “हे दानव” — two clear words
    private static final float MIN_CONF_SOLO = 0.78f;   // the bare name alone needs to be really clear (Dana, Daane, Dan were sneaking in)
    private static final float MIN_CONF_MEDIA = 0.88f;  // while music / a video is playing through the speaker
    /** Ignore everything for a while (a false trigger was dismissed, or a call just ended). */
    private static volatile long ignoreUntil;

    private static volatile WakeService me;
    private static volatile Context me0;               // application context of the running listener
    private static volatile String status = "off";           // off | loading | listening | paused | call | error
    private static volatile String error = "";
    private volatile boolean stop;
    private Thread loop;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------------ settings & state

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("danav", Context.MODE_PRIVATE); }
    static boolean wanted(Context c) { return prefs(c).getBoolean("wake", false); }
    static File modelDir(Context c) { return new File(c.getFilesDir(), "vosk-hi"); }
    static boolean modelReady(Context c) { return new File(modelDir(c), ".ok").exists(); }

    static JSONObject state(Context c) {
        try {
            return new JSONObject().put("on", wanted(c)).put("running", me != null).put("status", me == null ? (wanted(c) ? "stopped" : "off") : status)
                    .put("error", error).put("model", modelReady(c)).put("downloading", dl).put("progress", dlProgress);
        } catch (Exception e) { return new JSONObject(); }
    }

    private static void push(Context c) { LiveCall.RELAY.send("wake", state(c).toString()); }

    /** Turns Hey Danav on/off. Must be called while the app is on screen (Android only lets a visible app start mic services). */
    static String set(Context c, boolean on) {
        prefs(c).edit().putBoolean("wake", on).apply();
        if (!on) { c.stopService(new Intent(c, WakeService.class)); status = "off"; push(c); return "ok"; }
        if (!modelReady(c)) return "model";
        if (c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != 0) return "mic";
        return startNow(c) ? "ok" : "blocked";
    }

    /** Resumes listening when the app is opened (after a reboot or after Android stopped the service). */
    static void ensure(Context c) { if (wanted(c) && me == null && modelReady(c) && c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == 0) startNow(c); }

    private static boolean startNow(Context c) {
        try {
            Intent i = new Intent(c, WakeService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            return true;
        } catch (Exception e) { error = "Android didn't allow it to start: " + e.getMessage(); return false; }
    }

    /** A call ended: the listener picks the microphone back up by itself; this only refreshes the notification. */
    static void callEnded(Context c) { WakeService s = me; if (s != null) s.ui.postDelayed(() -> s.notifyNow(), 400); }

    /** Stay deaf for a while — used after a false trigger was dismissed, so the tail of the audio can't wake it again. */
    static void cooldown(Context c, long ms) {
        ignoreUntil = SystemClock.elapsedRealtime() + ms;
        WakeService s = me;
        if (s != null) s.ui.postDelayed(() -> s.notifyNow(), 300);
    }

    private static boolean muted() { return SystemClock.elapsedRealtime() < ignoreUntil; }

    /** Music or video playing out loud makes every word sound like a wake word — listen harder, answer later. */
    private static boolean mediaPlaying() {
        try {
            AudioManager am = (AudioManager) me0.getSystemService(AUDIO_SERVICE);
            return am != null && am.isMusicActive();
        } catch (Exception e) { return false; }
    }

    // ------------------------------------------------------------------ service

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACT_OFF.equals(intent.getAction())) {
            prefs(this).edit().putBoolean("wake", false).apply();
            stopSelf(); return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(ID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(ID, build(this));
        } catch (Exception e) {
            // e.g. restarted by Android in the background — mic services can only start from the open app
            error = "Open Danav once to turn Hey Danav back on.";
            stopSelf(); return START_NOT_STICKY;
        }
        if (loop == null) {
            me = this; me0 = getApplicationContext(); stop = false; error = "";
            loop = new Thread(this::listen, "danav-wake");
            loop.start();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        stop = true;
        if (me == this) me = null;
        status = "off";
        Thread t = loop; loop = null;
        if (t != null) t.interrupt();
        push(this);
        super.onDestroy();
    }

    private void setStatus(String s) { if (!s.equals(status)) { status = s; notifyNow(); push(this); } }

    private void notifyNow() {
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID, build(this)); } catch (Exception ignored) {}
    }

    private static Notification build(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "Hey Danav", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        boolean inCall = LiveCall.isWake();
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (inCall) open.putExtra("openCall", true);
        PendingIntent pOpen = PendingIntent.getActivity(c, 41, open, fl);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat).setOngoing(true).setShowWhen(false).setContentIntent(pOpen).setColor(0xFFDC2626)
                .setContentTitle(inCall ? "Danav is listening" : "Danav")
                .setContentText(inCall ? "Talk now — Danav can use your phone for you" : "Say \u201cHey Danav\u201d anytime");
        if (inCall) {
            PendingIntent pEnd = PendingIntent.getService(c, 42, new Intent(c, CallService.class).setAction(CallService.ACT_END), fl);
            b.addAction(new Notification.Action.Builder(null, "End", pEnd).build());
        } else {
            PendingIntent pOff = PendingIntent.getService(c, 43, new Intent(c, WakeService.class).setAction(ACT_OFF), fl);
            b.addAction(new Notification.Action.Builder(null, "Turn off", pOff).build());
        }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    // ------------------------------------------------------------------ listening

    private boolean busy() {
        if (LiveCall.active()) return true;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int m = am.getMode();
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE;
    }

    @SuppressLint("MissingPermission")
    private void listen() {
        setStatus("loading");
        org.vosk.Model model;
        try { model = new org.vosk.Model(modelDir(this).getAbsolutePath()); }
        catch (Throwable e) { error = "The Hey Danav voice model couldn't load. Download it again."; setStatus("error"); stopSelf(); return; }
        final int ringLen = RATE * 3;                       // last 3 s, replayed to the AI so it hears what woke it
        short[] ring = new short[ringLen];
        long written = 0;
        try {
            while (!stop) {
                if (busy()) { setStatus(LiveCall.active() ? "call" : "paused"); SystemClock.sleep(500); continue; }
                int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); setStatus("paused"); SystemClock.sleep(2000); continue; }
                org.vosk.Recognizer r = new org.vosk.Recognizer(model, RATE, GRAMMAR);
                r.setWords(true);
                rec.startRecording();
                setStatus("listening");
                short[] buf = new short[RATE / 10];          // 100 ms
                long heardAt = 0;
                boolean fired = false;
                try {
                    while (!stop && !busy()) {
                        int n = rec.read(buf, 0, buf.length);
                        if (n <= 0) { SystemClock.sleep(20); continue; }
                        for (int i = 0; i < n; i++) ring[(int) ((written + i) % ringLen)] = buf[i];
                        written += n;
                        String res = null;
                        if (r.acceptWaveForm(buf, n)) { res = r.getResult(); heardAt = 0; }
                        else if (r.getPartialResult().contains(WORD)) {
                            long now = SystemClock.elapsedRealtime();
                            if (heardAt == 0) heardAt = now;
                            else if (now - heardAt > 1000) { res = r.getFinalResult(); heardAt = 0; }   // kept talking: don't wait for a pause
                        }
                        if (res != null && !muted() && heard(res, mediaPlaying() ? MIN_CONF_MEDIA : 0f)) {
                            int keep = (int) Math.min(written, RATE * 5 / 2);
                            byte[] pre = new byte[keep * 2];
                            for (int i = 0; i < keep; i++) { short v = ring[(int) ((written - keep + i) % ringLen)]; pre[i * 2] = (byte) v; pre[i * 2 + 1] = (byte) (v >> 8); }
                            fired = true;
                            rec.stop();
                            ui.post(() -> { if (LiveCall.startWake(this, pre)) { setStatus("call"); } });
                            SystemClock.sleep(1500);         // the call takes the mic over
                            break;
                        }
                    }
                } finally {
                    try { if (!fired) rec.stop(); } catch (Exception ignored) {}
                    rec.release();
                    r.close();
                }
            }
        } catch (Throwable e) {
            error = "Hey Danav stopped: " + e.getMessage();
            setStatus("error");
        } finally {
            model.close();
        }
    }

    /**
     * “Danav” is only accepted when the model is sure: the two-word form “हे दानव” at 0.62, the bare name at 0.78,
     * and even higher while music or a video is playing out loud. Anything less is treated as noise.
     */
    private static boolean heard(String json, float mediaConf) {
        try {
            JSONArray ws = new JSONObject(json).optJSONArray("result");
            if (ws == null) return false;
            boolean twoWords = false;
            double solo = 0;
            for (int i = 0; i < ws.length(); i++) {
                JSONObject w = ws.getJSONObject(i);
                String word = w.optString("word");
                if ("हे".equals(word) || "है".equals(word)) twoWords = true;
                if (WORD.equals(word)) solo = Math.max(solo, w.optDouble("conf", 0));
            }
            if (solo <= 0) return false;
            float need = twoWords ? MIN_CONF : MIN_CONF_SOLO;
            if (mediaConf > 0) need = Math.max(need, mediaConf);
            return solo >= need;
        } catch (Exception ignored) {}
        return false;
    }

    // ------------------------------------------------------------------ voice model download (once, ~44 MB)

    private static volatile boolean dl;
    private static volatile int dlProgress;

    static void download(Context c) {
        if (dl) return;
        dl = true; dlProgress = 0; error = "";
        Context app = c.getApplicationContext();
        push(app);
        new Thread(() -> {
            File dir = modelDir(app), tmp = new File(app.getFilesDir(), "vosk-hi.part");
            deleteAll(tmp); deleteAll(dir);
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(MODEL_URL).openConnection();
                h.setConnectTimeout(15000); h.setReadTimeout(30000);
                if (h.getResponseCode() != 200) throw new IOException("server said " + h.getResponseCode());
                final long total = h.getContentLengthLong();
                final long[] got = {0};
                final long[] lastPush = {0};
                InputStream counted = new FilterInputStream(h.getInputStream()) {
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        int n = super.read(b, off, len);
                        if (n > 0) {
                            got[0] += n;
                            int p = total > 0 ? (int) (got[0] * 100 / total) : 0;
                            long now = SystemClock.elapsedRealtime();
                            if (p != dlProgress && now - lastPush[0] > 250) { dlProgress = p; lastPush[0] = now; push(app); }
                        }
                        return n;
                    }
                };
                try (ZipInputStream z = new ZipInputStream(counted)) {
                    ZipEntry e;
                    byte[] buf = new byte[65536];
                    while ((e = z.getNextEntry()) != null) {
                        String name = e.getName();
                        int slash = name.indexOf('/');
                        String rel = slash >= 0 ? name.substring(slash + 1) : name;
                        if (rel.isEmpty() || rel.contains("..")) continue;
                        File out = new File(tmp, rel);
                        if (e.isDirectory()) { out.mkdirs(); continue; }
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        try (FileOutputStream fo = new FileOutputStream(out)) { int n; while ((n = z.read(buf)) > 0) fo.write(buf, 0, n); }
                    }
                }
                if (!new File(tmp, "am/final.mdl").exists()) throw new IOException("the download was incomplete");
                if (!tmp.renameTo(dir)) throw new IOException("couldn't save the model");
                new FileOutputStream(new File(dir, ".ok")).close();
                dl = false; dlProgress = 100;
                push(app);
            } catch (Exception e) {
                deleteAll(tmp);
                dl = false;
                error = "Download failed: " + e.getMessage() + ". Check the internet and try again.";
                push(app);
            }
        }, "danav-wake-dl").start();
    }

    static void deleteModel(Context c) { c.stopService(new Intent(c, WakeService.class)); prefs(c).edit().putBoolean("wake", false).apply(); deleteAll(modelDir(c)); push(c); }

    private static void deleteAll(File f) {
        if (f == null || !f.exists()) return;
        File[] l = f.listFiles();
        if (l != null) for (File x : l) deleteAll(x);
        f.delete();
    }
}
