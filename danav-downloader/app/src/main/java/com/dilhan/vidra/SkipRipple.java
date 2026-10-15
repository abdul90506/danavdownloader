package com.dilhan.vidra;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Double-tap seek feedback in the style of the big video apps: a soft arc hugging the tapped side,
 * a circle that grows from the finger, three chevrons that light up one after another and an
 * accumulating "N seconds" label. Pure drawing, no layout cost, never intercepts touches.
 */
public class SkipRipple extends View {
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            chev = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), tri = new Path();
    private final RectF oval = new RectF();
    private int side;                 // -1 left, 1 right
    private int seconds;
    private float tapX, tapY;
    private float ringT = 1f;         // 0 → 1 growing circle
    private float alpha;              // whole overlay
    private long chevStart;
    private final ValueAnimator ringAnim = ValueAnimator.ofFloat(0f, 1f), fade = ValueAnimator.ofFloat(1f, 0f);
    private final Runnable fadeOut = () -> fade.start();

    public SkipRipple(Context c) {
        super(c);
        setClickable(false); setFocusable(false);
        arc.setColor(0x26FFFFFF);
        ring.setColor(0x26FFFFFF);
        chev.setColor(0xFFFFFFFF);
        label.setColor(0xFFFFFFFF);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        label.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13, getResources().getDisplayMetrics()));
        ringAnim.setDuration(420); ringAnim.setInterpolator(new DecelerateInterpolator());
        ringAnim.addUpdateListener(a -> { ringT = (float) a.getAnimatedValue(); invalidate(); });
        fade.setDuration(220);
        fade.addUpdateListener(a -> { alpha = (float) a.getAnimatedValue(); invalidate(); });
        setAlpha(1f);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    /** Show (or extend) the feedback. Same side within the streak keeps the arc up and the counter growing. */
    public void show(int side, int totalSeconds, float x, float y) {
        boolean cont = alpha > 0.05f && side == this.side;
        this.side = side; this.seconds = totalSeconds; tapX = x; tapY = y;
        removeCallbacks(fadeOut); fade.cancel();
        alpha = 1f;
        if (!cont) chevStart = SystemClock.uptimeMillis();
        ringAnim.cancel(); ringAnim.start();
        postDelayed(fadeOut, 650);
        invalidate();
    }

    public boolean isShowing() { return alpha > 0.05f; }

    @Override protected void onDraw(Canvas c) {
        if (alpha <= 0.01f || side == 0) return;
        int W = getWidth(), H = getHeight();
        float half = W * 0.4f;                       // arc covers ~40% of the width
        int sa = c.save();
        // clip to the arc: a big ellipse whose straight edge sits outside the screen
        path.reset();
        if (side < 0) oval.set(-half * 0.55f - H * 0.15f, -H * 0.15f, half, H * 1.15f);
        else oval.set(W - half, -H * 0.15f, W + half * 0.55f + H * 0.15f, H * 1.15f);
        path.addOval(oval, Path.Direction.CW);
        c.clipPath(path);
        arc.setAlpha(Math.round(0x26 * alpha));
        c.drawRect(oval, arc);
        // growing circle from the finger
        if (ringT < 1f) {
            ring.setAlpha(Math.round(0x30 * (1f - ringT) * alpha));
            c.drawCircle(tapX, tapY, dp(28) + ringT * half, ring);
        }
        c.restoreToCount(sa);

        // chevrons + label, centred in the arc
        float cx = side < 0 ? half * 0.48f : W - half * 0.48f, cy = H / 2f;
        float s = dp(9), gap = dp(13);
        long t = SystemClock.uptimeMillis() - chevStart;
        boolean anim = false;
        for (int i = 0; i < 3; i++) {
            int k = side < 0 ? 2 - i : i;            // light up in the direction of travel
            float phase = ((t - k * 160) % 720) / 720f;
            float a = t < k * 160 ? 0.25f : phase < 0.4f ? 0.25f + 0.75f * (phase / 0.4f) : phase < 0.75f ? 1f - 0.75f * ((phase - 0.4f) / 0.35f) : 0.25f;
            chev.setAlpha(Math.round(255 * a * alpha));
            float x = cx + (i - 1) * gap;
            tri.reset();
            if (side > 0) { tri.moveTo(x - s * 0.55f, cy - s - dp(10)); tri.lineTo(x + s * 0.55f, cy - dp(10)); tri.lineTo(x - s * 0.55f, cy + s - dp(10)); }
            else { tri.moveTo(x + s * 0.55f, cy - s - dp(10)); tri.lineTo(x - s * 0.55f, cy - dp(10)); tri.lineTo(x + s * 0.55f, cy + s - dp(10)); }
            tri.close();
            c.drawPath(tri, chev);
            anim = true;
        }
        label.setAlpha(Math.round(255 * alpha));
        c.drawText(seconds + " seconds", cx, cy + dp(22), label);
        if (anim) postInvalidateOnAnimation();
    }
}
