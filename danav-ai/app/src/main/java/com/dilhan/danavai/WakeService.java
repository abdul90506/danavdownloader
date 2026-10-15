package com.dilhan.danavai;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

/**
 * The Danav circle service.
 *
 * Danav has no wake word any more: three quick volume-up presses bring the circle up, three more send it away.
 * This service does two small things: it keeps the microphone allowed for the live call (Android only lets a
 * foreground service record in the background) and it shows one quiet notification so the shortcut works even
 * while the app is closed. It never listens to anything on its own.
 */
public class WakeService extends Service {
    private static final String CH = "danav_wake";
    private static final int ID = 7302;
    static final String ACT_OFF = "com.dilhan.danavai.WAKE_OFF", ACT_SESSION = "com.dilhan.danavai.SESSION";

    // three volume-up presses in a row = Danav circle on / off
    private static final long VOL_WINDOW = 2200, VOL_GAP = 900;
    private static volatile long volFirst, volLast, ignoreUntil;
    private static volatile int volCount;
    private static final Object volLock = new Object();

    private static volatile WakeService me;
    private static volatile String status = "off";          // off | ready | call | error
    private static volatile String error = "";
    private static volatile boolean holder;                 // quiet microphone holder for the shortcut
    private static volatile boolean sessionOnly;            // started by a triple press: the call lives on its own
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------------ settings & state

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("danav", Context.MODE_PRIVATE); }

    /** On by default: without the shortcut there is no way to reach Danav while another app is in front. */
    static boolean wanted(Context c) { return prefs(c).getBoolean("wake", true); }

    static JSONObject state(Context c) {
        try {
            return new JSONObject().put("on", wanted(c)).put("running", me != null)
                    .put("status", me == null ? (wanted(c) ? "stopped" : "off") : status).put("error", error)
                    .put("ready", me != null && "ready".equals(status));
        } catch (Exception e) { return new JSONObject(); }
    }

    static String status() { return status; }

    private static void push(Context c) { LiveCall.RELAY.send("wake", state(c).toString()); }

    /**
     * One volume-up press recorded (from the accessibility key filter or the media-button receiver).
     * @return true when the key should be swallowed (a triple press fired, or we are in a call).
     */
    static boolean volumeUp(Context c) {
        long now = SystemClock.elapsedRealtime();
        synchronized (volLock) {
            if (volCount > 0 && (now - volLast > VOL_GAP || now - volFirst > VOL_WINDOW)) volCount = 0;
            if (volCount == 0) volFirst = now;
            volLast = now;
            volCount++;
            if (volCount >= 3) { volCount = 0; trigger(c); return true; }
        }
        return LiveCall.active();                    // inside a call the volume keys never blast the speaker
    }

    /** Three quick volume-up presses: bring the Danav circle up, or send the running one away. */
    private static void trigger(Context c) {
        Context app = c.getApplicationContext();
        if (LiveCall.active()) { LiveCall.stop(); return; }        // second triple press → it goes
        if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != 0) {
            error = "Danav needs the microphone permission."; push(app); return;
        }
        try {
            Intent i = new Intent(app, WakeService.class).setAction(ACT_SESSION);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i); else app.startService(i);
        } catch (Exception e) { error = "Android didn't allow Danav to start. Open the app once."; push(app); return; }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { LiveCall.startWake(app, null); } catch (Exception e) { error = String.valueOf(e.getMessage()); push(app); }
        }, 350);
    }

    /** Turns the shortcut on/off. Must be called while the app is on screen (Android only lets a visible app start mic services). */
    static String set(Context c, boolean on) {
        prefs(c).edit().putBoolean("wake", on).apply();
        if (!on) { c.stopService(new Intent(c, WakeService.class)); status = "off"; push(c); return "ok"; }
        if (c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != 0) return "mic";
        return startNow(c) ? "ok" : "blocked";
    }

    /** Resumes the shortcut service when Danav is opened (after a reboot or when Android stopped it). */
    static void ensure(Context c) { if (wanted(c) && me == null && c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == 0) startNow(c); }

    private static boolean startNow(Context c) {
        try {
            Intent i = new Intent(c, WakeService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            return true;
        } catch (Exception e) { error = "Android didn't allow it to start: " + e.getMessage(); return false; }
    }

    /** A call ended: a volume session stops itself here, the quiet holder goes back to "ready". */
    static void callEnded(Context c) {
        WakeService s = me;
        if (s == null) return;
        if (holder) { s.ui.postDelayed(() -> s.setStatus("ready"), 250); return; }
        if (sessionOnly) { s.ui.postDelayed(() -> { if (!LiveCall.active()) s.stopSelf(); }, 250); return; }
        s.ui.postDelayed(() -> s.notifyNow(), 400);
    }

    /** Not used without a wake word, but kept so a stray "dismiss" from the model can never start a loop. */
    static void cooldown(Context c, long ms) {
        ignoreUntil = SystemClock.elapsedRealtime() + ms;
        WakeService s = me;
        if (s != null) s.ui.postDelayed(() -> s.notifyNow(), 300);
    }

    // ------------------------------------------------------------------ service

    /** The volume-up triple press also works through the media-button broadcast (used when accessibility is off). */
    private android.content.BroadcastReceiver mediaButtons;

    private void registerMediaButtons() {
        if (mediaButtons != null) return;
        mediaButtons = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (i == null || !Intent.ACTION_MEDIA_BUTTON.equals(i.getAction())) return;
                android.view.KeyEvent k = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (k == null || k.getKeyCode() != android.view.KeyEvent.KEYCODE_VOLUME_UP || k.getAction() != android.view.KeyEvent.ACTION_DOWN) return;
                if (volumeUp(c) && isOrderedBroadcast()) setResultCode(android.app.Activity.RESULT_CANCELED);
            }
        };
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_MEDIA_BUTTON);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(mediaButtons, f, null, null, Context.RECEIVER_EXPORTED);
            else registerReceiver(mediaButtons, f);
        } catch (Exception e) { mediaButtons = null; }
    }

    private void unregisterMediaButtons() {
        if (mediaButtons == null) return;
        try { unregisterReceiver(mediaButtons); } catch (Exception ignored) {}
        mediaButtons = null;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACT_OFF.equals(intent.getAction())) {
            prefs(this).edit().putBoolean("wake", false).apply();
            stopSelf(); return START_NOT_STICKY;
        }
        if (intent != null && ACT_SESSION.equals(intent.getAction())) {
            // started by the volume-up triple press: keep the microphone allowed for the live call
            try {
                if (Build.VERSION.SDK_INT >= 30) startForeground(ID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                else startForeground(ID, build(this));
            } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
            me = this; sessionOnly = true; error = ""; status = "ready";
            notifyNow(); push(this);
            if (!holder) ui.postDelayed(() -> { if (!LiveCall.active()) stopSelf(); }, 2500);
            return START_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(ID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(ID, build(this));
        } catch (Exception e) {
            // e.g. restarted by Android in the background — mic services can only start from the open app
            error = "Open Danav once to turn the volume-up shortcut back on.";
            stopSelf(); return START_NOT_STICKY;
        }
        if (me == null) {
            me = this; holder = true; error = ""; status = "ready";
            registerMediaButtons();
            notifyNow(); push(this);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        holder = false;
        sessionOnly = false;
        unregisterMediaButtons();
        if (me == this) me = null;
        status = "off";
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
            NotificationChannel ch = new NotificationChannel(CH, "Volume-up shortcut", NotificationManager.IMPORTANCE_MIN);
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
                .setContentTitle("Danav")
                .setContentText(inCall ? "Talk now \u2014 Danav can use your phone for you" : "Ready \u00b7 Volume-Up \u00d73 to wake");
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
}
