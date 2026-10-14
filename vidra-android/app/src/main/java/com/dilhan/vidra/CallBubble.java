package com.dilhan.vidra;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * The small floating circle shown over other apps while a Danav AI call continues in the background. It draws a
 * live mini spectrum (AI warm, you cool), can be dragged anywhere and snaps to the nearest edge; a tap returns to the call.
 */
final class CallBubble {
    private static View view;
    private static WindowManager.LayoutParams lp;

    static boolean allowed(Context c) { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c); }

    @SuppressLint("ClickableViewAccessibility")
    static void show(Context ctx) {
        if (view != null || !LiveCall.active() || !allowed(ctx)) return;
        Context c = ctx.getApplicationContext();
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        float d = c.getResources().getDisplayMetrics().density;
        int size = (int) (68 * d);
        lp = new WindowManager.LayoutParams(size, size,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int sw = c.getResources().getDisplayMetrics().widthPixels, sh = c.getResources().getDisplayMetrics().heightPixels;
        lp.x = sw - size - (int) (10 * d);
        lp.y = (int) (sh * 0.62f);
        Orb v = new Orb(c);
        final float[] down = new float[4];
        final boolean[] moved = {false}, longed = {false};
        // long-press the circle to end the call (handy for Hey Danav calls when the app isn't open)
        final Runnable longPress = () -> {
            if (moved[0]) return;
            longed[0] = true;
            try { v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); } catch (Exception ignored) {}
            LiveCall.stop();
        };
        v.setOnTouchListener((vv, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: down[0] = e.getRawX(); down[1] = e.getRawY(); down[2] = lp.x; down[3] = lp.y; moved[0] = false; longed[0] = false; vv.postDelayed(longPress, 650); return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                    if (Math.abs(dx) + Math.abs(dy) > 10 * d) { moved[0] = true; vv.removeCallbacks(longPress); }
                    if (moved[0]) { lp.x = (int) (down[2] + dx); lp.y = (int) Math.max(0, Math.min(sh - size, down[3] + dy)); try { wm.updateViewLayout(vv, lp); } catch (Exception ignored) {} }
                    return true;
                case MotionEvent.ACTION_UP:
                    vv.removeCallbacks(longPress);
                    if (longed[0]) return true;
                    if (!moved[0]) {
                        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).putExtra("openCall", true);
                        try { c.startActivity(i); } catch (Exception ignored) {}
                    } else {
                        lp.x = lp.x + size / 2 < sw / 2 ? (int) (10 * d) : sw - size - (int) (10 * d);
                        try { wm.updateViewLayout(vv, lp); } catch (Exception ignored) {}
                    }
                    return true;
            }
            return false;
        });
        try { wm.addView(v, lp); view = v; } catch (Exception ignored) {}
    }

    static void hide() {
        if (view == null) return;
        try { ((WindowManager) view.getContext().getSystemService(Context.WINDOW_SERVICE)).removeView(view); } catch (Exception ignored) {}
        view = null;
    }

    /** Mini spectrum orb. */
    static final class Orb extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), core = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] mic = new float[16], ai = new float[16], sm = new float[16], sa = new float[16];
        private float rot;
        Orb(Context c) { super(c); p.setStrokeCap(Paint.Cap.ROUND); }

        @Override protected void onDraw(Canvas c) {
            if (!LiveCall.active()) { post(CallBubble::hide); return; }
            LiveCall.levels(mic, ai);
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, R = Math.min(w, h) / 2f;
            float eA = 0, eM = 0;
            for (int i = 0; i < 16; i++) { sa[i] += (ai[i] - sa[i]) * 0.35f; sm[i] += (mic[i] - sm[i]) * 0.35f; eA += sa[i]; eM += sm[i]; }
            eA /= 16; eM /= 16;
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            p.setColor(0xF20B0B0F);
            c.drawCircle(cx, cy, R * 0.98f, p);
            float cr = R * (0.26f + eA * 0.25f + eM * 0.12f);
            core.setShader(new RadialGradient(cx, cy, cr * 1.9f, new int[]{0xFFFFFFFF, eA >= eM ? 0xCCFF4D5E : 0xCC4DC3FF, 0x00000000}, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, cr * 1.9f, core);
            rot += 0.02f + eA * 0.06f;
            int n = 28;
            p.setStyle(Paint.Style.STROKE);
            for (int j = 0; j < n; j++) {
                double th = j * Math.PI * 2 / n + rot;
                float k = (float) j / n * 2f; if (k > 1) k = 2 - k;
                int b = Math.min(15, (int) (k * 15));
                boolean warm = sa[b] >= sm[b];
                float v = Math.max(sa[b], sm[b]);
                float r0 = R * 0.52f, r1 = r0 + R * (0.06f + v * 0.36f);
                float hue = warm ? (345 + 40 * k) % 360 : 190 + 60 * k;
                p.setColor(android.graphics.Color.HSVToColor(230, new float[]{hue, 0.8f, 1f}));
                p.setStrokeWidth(R * 0.07f);
                c.drawLine(cx + (float) Math.cos(th) * r0, cy + (float) Math.sin(th) * r0, cx + (float) Math.cos(th) * r1, cy + (float) Math.sin(th) * r1, p);
            }
            postInvalidateOnAnimation();
        }
    }
}
