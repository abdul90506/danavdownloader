package com.dilhan.vidra;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps a Danav AI voice call alive (and the microphone allowed) while the app is in the background, with an ongoing
 * notification that has Mute and End buttons.
 */
public class CallService extends Service {
    private static final String CH = "danav_call";
    private static final int ID = 7301;
    static final String ACT_END = "com.dilhan.vidra.CALL_END", ACT_MUTE = "com.dilhan.vidra.CALL_MUTE", ACT_SCREEN = "com.dilhan.vidra.CALL_SCREEN";
    private static long since;

    static void start(Context c) {
        since = System.currentTimeMillis();
        try {
            Intent i = new Intent(c, CallService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) {}
    }

    /** After the user allowed screen capture: become a media-projection service too, then start sharing. */
    static void shareScreen(Context c, int code, Intent data) {
        try {
            Intent i = new Intent(c, CallService.class).setAction(ACT_SCREEN).putExtra("code", code).putExtra("data", data);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) { LiveCall.emit("screen", false); }
    }

    static void stop(Context c) { try { c.stopService(new Intent(c, CallService.class)); } catch (Exception ignored) {} }

    static void refresh(Context c) {
        if (!LiveCall.active()) return;
        try { ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).notify(ID, build(c)); } catch (Exception ignored) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACT_END.equals(a)) { LiveCall.stop(); stopSelf(); return START_NOT_STICKY; }
        if (ACT_MUTE.equals(a)) { LiveCall.mute(!LiveCall.muted()); return START_NOT_STICKY; }
        Notification n = build(this);
        if (ACT_SCREEN.equals(a)) {
            Intent data = intent.getParcelableExtra("data");
            try {
                if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                else if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                else startForeground(ID, n);
                if (data == null || !LiveCall.active()) throw new IllegalStateException();
                ScreenShare.start(this, intent.getIntExtra("code", 0), data);
            } catch (Exception e) { LiveCall.emit("screen", false); }
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(ID, n);
        } catch (Exception e) { stopSelf(); }
        if (!LiveCall.active()) stopSelf();
        return START_NOT_STICKY;
    }

    private static Notification build(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "Danav AI calls", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).putExtra("openCall", true);
        PendingIntent pOpen = PendingIntent.getActivity(c, 31, open, fl);
        PendingIntent pEnd = PendingIntent.getService(c, 32, new Intent(c, CallService.class).setAction(ACT_END), fl);
        PendingIntent pMute = PendingIntent.getService(c, 33, new Intent(c, CallService.class).setAction(ACT_MUTE), fl);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Danav AI")
                .setContentText(LiveCall.muted() ? "On a call · muted" : "On a call · tap to return")
                .setContentIntent(pOpen)
                .setOngoing(true)
                .setShowWhen(true).setUsesChronometer(true).setWhen(since)
                .setCategory(Notification.CATEGORY_CALL)
                .addAction(new Notification.Action.Builder(null, LiveCall.muted() ? "Unmute" : "Mute", pMute).build())
                .addAction(new Notification.Action.Builder(null, "End call", pEnd).build());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    @Override public void onTaskRemoved(Intent rootIntent) { LiveCall.stop(); stopSelf(); }

    @Override public void onDestroy() { ScreenShare.stop(false); super.onDestroy(); }

    @Override public IBinder onBind(Intent intent) { return null; }
}
