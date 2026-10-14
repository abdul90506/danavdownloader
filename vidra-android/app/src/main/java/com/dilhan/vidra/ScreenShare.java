package com.dilhan.vidra;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Live screen sharing for Danav AI calls: the screen is mirrored into a small virtual display and about one JPEG frame
 * per second is sent to the live session, so the AI can see what you're looking at (also while you use other apps).
 */
final class ScreenShare {
    private static MediaProjection mp;
    private static VirtualDisplay vd;
    private static ImageReader reader;
    private static HandlerThread thread;
    private static Handler h;
    private static int w, hgt;
    private static volatile boolean on;

    static boolean active() { return on; }

    /** Starts capturing. Must be called after the call service has become a media-projection foreground service. */
    static synchronized void start(Context ctx, int code, Intent data) {
        stop(false);
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mp = mpm.getMediaProjection(code, data);
            if (mp == null) throw new IllegalStateException("no projection");
            thread = new HandlerThread("danav-screen"); thread.start();
            h = new Handler(thread.getLooper());
            mp.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stop(true); }
            }, h);
            DisplayMetrics dm = new DisplayMetrics();
            ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
            float scale = Math.min(1f, 960f / Math.max(dm.widthPixels, dm.heightPixels));
            w = Math.max(2, Math.round(dm.widthPixels * scale) & ~1);
            hgt = Math.max(2, Math.round(dm.heightPixels * scale) & ~1);
            reader = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2);
            vd = mp.createVirtualDisplay("danav-share", w, hgt, Math.max(1, Math.round(dm.densityDpi * scale)),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, h);
            on = true;
            LiveCall.emit("screen", true);
            h.postDelayed(grab, 300);
        } catch (Exception e) {
            stop(false);
            LiveCall.emit("screen", false);
            LiveCall.toast("Couldn't share the screen on this phone");
        }
    }

    private static final Runnable grab = new Runnable() {
        @Override public void run() {
            if (!on) return;
            Image img = null;
            try {
                img = reader.acquireLatestImage();
                if (img != null) {
                    Image.Plane p = img.getPlanes()[0];
                    ByteBuffer buf = p.getBuffer();
                    int ps = p.getPixelStride(), rs = p.getRowStride();
                    int bw = rs / ps;                                  // rows can be padded
                    Bitmap full = Bitmap.createBitmap(bw, hgt, Bitmap.Config.ARGB_8888);
                    full.copyPixelsFromBuffer(buf);
                    Bitmap bmp = bw == w ? full : Bitmap.createBitmap(full, 0, 0, w, hgt);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.JPEG, 62, out);
                    if (bmp != full) bmp.recycle();
                    full.recycle();
                    LiveCall.video(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
                }
            } catch (Exception ignored) {
            } finally { if (img != null) img.close(); }
            if (on && h != null) h.postDelayed(this, 1000);
        }
    };

    static synchronized void stop(boolean notify) {
        boolean was = on;
        on = false;
        if (h != null) h.removeCallbacksAndMessages(null);
        if (vd != null) { try { vd.release(); } catch (Exception ignored) {} vd = null; }
        if (reader != null) { try { reader.close(); } catch (Exception ignored) {} reader = null; }
        MediaProjection m = mp; mp = null;
        if (m != null) try { m.stop(); } catch (Exception ignored) {}
        if (thread != null) { thread.quitSafely(); thread = null; h = null; }
        if (notify && was) LiveCall.emit("screen", false);
    }
}
