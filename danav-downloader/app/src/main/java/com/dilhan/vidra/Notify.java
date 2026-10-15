package com.dilhan.vidra;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

/** Notification channels + "download finished" notifications. */
public final class Notify {
    static final String CH_PROGRESS = "progress", CH_DONE = "done";
    private Notify() {}

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel p = new NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW);
        p.setShowBadge(false);
        nm.createNotificationChannel(p);
        nm.createNotificationChannel(new NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT));
    }

    static Notification.Builder builder(Context c, String ch) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, ch) : new Notification.Builder(c);
    }

    static PendingIntent openApp(Context c, String view) {
        Intent i = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra("view", view);
        return PendingIntent.getActivity(c, view.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void done(Context c, String id, String title, Uri uri, String mime) {
        if (!Store.settings(c).optBoolean("notify", true)) return;
        try {
            Intent open = Saver.openIntent(uri, mime);
            PendingIntent pi = PendingIntent.getActivity(c, id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = builder(c, CH_DONE)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("Download complete")
                    .setContentText(title)
                    .setColor(0xFFDC2626)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            c.getSystemService(NotificationManager.class).notify(id.hashCode(), n);
        } catch (Throwable ignored) {}
    }
}
