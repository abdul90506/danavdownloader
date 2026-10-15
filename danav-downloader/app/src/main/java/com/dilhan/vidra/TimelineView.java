package com.dilhan.vidra;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Player timeline (Netflix-like): solid red/grey bar with square ends that grows a little while scrubbing, buffered range, small flat dot,
 * generous touch area.
 */
public class TimelineView extends View {
    public interface Listener {
        void onScrubStart();
        void onScrub(long pos);
        void onScrubStop(long pos, boolean canceled);
    }

    private final Paint bg = new Paint(), buf = new Paint(), played = new Paint(), thumb = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long duration, position, buffered, scrubPos;
    private boolean scrubbing;
    private float grow;           // 0 → 1 while scrubbing
    private ValueAnimator anim;
    private Listener listener;
    private final float d;
    private float downX;
    private boolean moved;

    public TimelineView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        bg.setColor(0x66FFFFFF);       // Netflix-style: flat light-grey rest of the track
        buf.setColor(0x8CFFFFFF);
        played.setColor(PlayerActivity.ACCENT);
        thumb.setColor(PlayerActivity.ACCENT);
        halo.setColor(PlayerActivity.ACCENT);
        setClickable(true);
        setFocusable(true);
    }

    public void setListener(Listener l) { listener = l; }

    public void setDuration(long ms) { if (ms != duration) { duration = Math.max(0, ms); invalidate(); } }
    public void setPosition(long ms) { if (!scrubbing && ms != position) { position = ms; invalidate(); } }
    public void setBuffered(long ms) { if (ms != buffered) { buffered = ms; invalidate(); } }
    public boolean isScrubbing() { return scrubbing; }
    public long scrubPosition() { return scrubPos; }

    private float left() { return getPaddingLeft() + 9 * d; }
    private float right() { return getWidth() - getPaddingRight() - 9 * d; }

    /** x of the thumb (for placing the preview box). */
    public float thumbX() {
        long p = scrubbing ? scrubPos : position;
        return left() + (right() - left()) * (duration > 0 ? Math.min(1f, p / (float) duration) : 0);
    }

    private void animateGrow(float to) {
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(grow, to);
        anim.setDuration(160);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> { grow = (float) a.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    @Override protected void onDraw(Canvas c) {
        float l = left(), r = right(), w = r - l, cy = getHeight() / 2f;
        float h = Math.round((5f + 1.5f * grow) * d);   // solid bar with square (box) ends — Netflix style
        float top = Math.round(cy - h / 2), bot = top + h;
        l = Math.round(l); r = Math.round(r); w = r - l;
        c.drawRect(l, top, r, bot, bg);
        if (duration > 0) {
            float bx = l + w * Math.min(1f, buffered / (float) duration);
            if (bx > l) c.drawRect(l, top, bx, bot, buf);
            long p = scrubbing ? scrubPos : position;
            float px = l + w * Math.min(1f, Math.max(0, p) / (float) duration);
            if (px > l) c.drawRect(l, top, px, bot, played);
            float t = (5.5f + 1.5f * grow) * d;                   // small flat dot — no shadow, no halo
            c.drawCircle(px, (top + bot) / 2f, t, thumb);
        }
    }

    private long posAt(float x) {
        float f = (x - left()) / (right() - left());
        f = Math.max(0, Math.min(1, f));
        return (long) (f * duration);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (duration <= 0 || !isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); moved = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                scrubbing = true; scrubPos = posAt(e.getX());
                animateGrow(1);
                if (listener != null) { listener.onScrubStart(); listener.onScrub(scrubPos); }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - downX) > 4 * d) moved = true;
                scrubPos = posAt(e.getX());
                if (listener != null) listener.onScrub(scrubPos);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean cancel = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                scrubbing = false;
                if (!cancel) { position = scrubPos; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
                animateGrow(0);
                if (listener != null) listener.onScrubStop(scrubPos, cancel);
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }
}
