package com.dilhan.vidra;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Keeps downloads running in the background and shows live progress in the notification shade:
 * one notification per download (percent, size, speed, Pause / Cancel) grouped under a summary.
 */
public class DownloadService extends Service {
    private static final int ID = 7001;
    private static final String GROUP = "vidra.downloads";
    private final Handler h = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private boolean fg;
    private final Set<Integer> shown = new HashSet<>();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            update();
            if (Jobs.activeCount() == 0) { stop(); return; }
            h.postDelayed(this, 1000);
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notify.channels(this);
        if (!fg) {
            Notification n = summary(Jobs.snapshot());
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(ID, n);
            fg = true;
            try {
                wake = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vidra:download");
                wake.acquire(6 * 3600 * 1000L);
                wifi = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vidra:download");
                wifi.acquire();
            } catch (Throwable ignored) {}
        }
        h.removeCallbacks(tick);
        h.postDelayed(tick, 300);
        return START_NOT_STICKY;
    }

    static int notifId(String jobId) { return ("p" + jobId).hashCode(); }

    private static boolean active(String st) {
        return st.equals("queued") || st.equals("starting") || st.equals("running") || st.equals("merging") || st.equals("saving");
    }

    static String bytes(long n) {
        if (n <= 0) return "";
        if (n < 1024 * 1024) return String.format(Locale.US, "%.0f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format(Locale.US, n < 10 * 1024 * 1024 ? "%.1f MB" : "%.0f MB", n / 1048576.0);
        return String.format(Locale.US, "%.2f GB", n / 1073741824.0);
    }

    private PendingIntent action(String act, String id) {
        Intent i = new Intent(this, Actions.class).setAction("vidra." + act).putExtra("id", id);
        return PendingIntent.getBroadcast(this, (act + id).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification jobNotification(JSONObject j) {
        String st = j.optString("state");
        JSONObject req = j.optJSONObject("req");
        String title = req != null ? req.optString("title", "Video") : "Video";
        String label = req != null ? req.optString("label") : "";
        double p = j.optDouble("percent", -1);
        long dl = j.optLong("downloaded"), tot = j.optLong("total", -1);
        double sp = j.optDouble("speed", 0);
        String text;
        switch (st) {
            case "queued": text = "Waiting…"; break;
            case "starting": text = "Starting…"; break;
            case "merging": text = "Merging video and audio…"; break;
            case "saving": text = "Saving to phone…"; break;
            case "paused": text = "Paused" + (p >= 0 ? " · " + (int) p + "%" : ""); break;
            default: {
                StringBuilder b = new StringBuilder();
                if (p >= 0) b.append((int) p).append('%');
                if (dl > 0) { if (b.length() > 0) b.append(" · "); b.append(bytes(dl)); if (tot > 0) b.append(" of ").append(bytes(tot)); }
                if (sp > 0) b.append(" · ").append(bytes((long) sp)).append("/s");
                text = b.length() > 0 ? b.toString() : "Downloading…";
            }
        }
        String id = j.optString("id");
        Notification.Builder b = Notify.builder(this, Notify.CH_PROGRESS)
                .setSmallIcon(st.equals("paused") ? R.drawable.ic_stat : android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(label.isEmpty() ? null : label)
                .setColor(0xFFDC2626)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setGroup(GROUP)
                .setOngoing(active(st))
                .setContentIntent(Notify.openApp(this, "downloads"));
        if (Build.VERSION.SDK_INT >= 26) b.setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);
        if (st.equals("running") && p >= 0) b.setProgress(1000, (int) (p * 10), false);
        else if (active(st)) b.setProgress(0, 0, true);
        if (st.equals("paused")) b.addAction(new Notification.Action.Builder(null, "Resume", action("resume", id)).build());
        else if (st.equals("running") || st.equals("starting") || st.equals("queued")) b.addAction(new Notification.Action.Builder(null, "Pause", action("pause", id)).build());
        if (!st.equals("saving") && !st.equals("merging")) b.addAction(new Notification.Action.Builder(null, "Cancel", action("cancel", id)).build());
        return b.build();
    }

    private Notification summary(List<JSONObject> js) {
        int active = 0; double pct = 0; int known = 0; long speed = 0;
        for (JSONObject j : js) {
            if (!active(j.optString("state"))) continue;
            active++;
            double p = j.optDouble("percent", -1);
            if (p >= 0) { pct += p; known++; }
            speed += (long) j.optDouble("speed", 0);
        }
        String title = active == 0 ? "Danav" : active == 1 ? "Downloading 1 video" : "Downloading " + active + " videos";
        String text = (known > 0 ? (int) (pct / known) + "%" : "Preparing…") + (speed > 0 ? " · " + bytes(speed) + "/s" : "");
        Notification.Builder b = Notify.builder(this, Notify.CH_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setColor(0xFFDC2626)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setContentIntent(Notify.openApp(this, "downloads"));
        if (Build.VERSION.SDK_INT >= 26) b.setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);
        if (known > 0) b.setProgress(1000, (int) (pct / known * 10), false); else b.setProgress(0, 0, true);
        return b.build();
    }

    private void update() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            List<JSONObject> js = Jobs.snapshot();
            Set<Integer> now = new HashSet<>();
            for (JSONObject j : js) {
                String st = j.optString("state");
                if (!active(st) && !st.equals("paused")) continue;
                int nid = notifId(j.optString("id"));
                now.add(nid);
                nm.notify(nid, jobNotification(j));
            }
            for (Integer old : shown) if (!now.contains(old)) nm.cancel(old);
            shown.clear(); shown.addAll(now);
            nm.notify(ID, summary(js));
        } catch (Throwable ignored) {}
    }

    private void stop() {
        h.removeCallbacks(tick);
        releaseLocks();
        fg = false;
        // paused downloads keep their (dismissable) notification with a Resume button
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            for (JSONObject j : Jobs.snapshot()) if (j.optString("state").equals("paused")) nm.notify(notifId(j.optString("id")), jobNotification(j));
        } catch (Throwable ignored) {}
        stopSelf();
    }

    private void releaseLocks() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Throwable ignored) {}
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Throwable ignored) {}
    }

    @Override public void onDestroy() { h.removeCallbacks(tick); releaseLocks(); super.onDestroy(); }

    /** Pause / Resume / Cancel buttons in the notification. */
    public static class Actions extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String id = i.getStringExtra("id");
            if (id == null || i.getAction() == null) return;
            switch (i.getAction()) {
                case "vidra.pause": Jobs.pause(id); break;
                case "vidra.resume": Jobs.resume(id); break;
                case "vidra.cancel":
                    Jobs.cancel(id);
                    try { c.getSystemService(NotificationManager.class).cancel(notifId(id)); } catch (Throwable ignored) {}
                    break;
            }
            Jobs.ensureService(c);
        }
    }
}
