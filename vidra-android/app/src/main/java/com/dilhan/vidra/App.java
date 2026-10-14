package com.dilhan.vidra;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

public class App extends Application {
    private int started;
    private final Handler h = new Handler(Looper.getMainLooper());
    /** The private folder locks itself as soon as Vidra leaves the screen. */
    private long awaySince;
    private final Runnable autoLock = new Runnable() {
        @Override public void run() {
            if (started != 0 || !Vault.isUnlocked()) return;
            // a file picker / "open with" app is in front: wait for it, but never more than 15 minutes
            if (Vault.holdLock && android.os.SystemClock.elapsedRealtime() - awaySince < 15 * 60_000L) { h.postDelayed(this, 30_000); return; }
            Vault.holdLock = false;
            Vault.lock(App.this);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        Notify.channels(this);
        AdBlock.init(this);
        new Thread(() -> { Jobs.cleanup(this); Vault.clearOpen(this); }, "cleanup").start();
        Engine.init(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { started++; h.removeCallbacks(autoLock); }
            @Override public void onActivityStopped(Activity a) { started = Math.max(0, started - 1); if (started == 0) { awaySince = android.os.SystemClock.elapsedRealtime(); h.postDelayed(autoLock, 1500); } }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
}
