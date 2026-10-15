package com.dilhan.danavai;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lets Danav AI operate the phone during a call: read what's on screen, tap, type, scroll, go back / home and take
 * screenshots so the live model can see the result of each step. It only acts on requests coming from the AI call.
 */
public class DanavAccess extends AccessibilityService {
    static volatile DanavAccess me;

    static boolean on() { return me != null; }

    @Override protected void onServiceConnected() { me = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    /**
     * Volume-up pressed three times in a row: Danav wakes up (or goes away). The first two presses go to the
     * system as usual so the volume still works; the press that completes the triple is swallowed.
     */
    @Override protected boolean onKeyEvent(android.view.KeyEvent e) {
        try {
            if (e != null && e.getKeyCode() == android.view.KeyEvent.KEYCODE_VOLUME_UP && e.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (WakeService.volumeUp(this)) return true;
            }
        } catch (Throwable ignored) {}
        return super.onKeyEvent(e);
    }
    @Override public void onDestroy() { if (me == this) me = null; super.onDestroy(); }
    @Override public boolean onUnbind(android.content.Intent i) { if (me == this) me = null; return super.onUnbind(i); }

    // ------------------------------------------------------------------ screen reading

    /** Elements of the last read, so "tap 7" means exactly what the AI saw. */
    private final List<AccessibilityNodeInfo> last = new ArrayList<>();

    /** The window the user sees (skips our own floating bubble). */
    AccessibilityNodeInfo root() {
        AccessibilityNodeInfo r = null;
        try { r = getRootInActiveWindow(); } catch (Exception ignored) {}
        if (r != null && !isOurBubble(r)) return r;
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo x = w.getRoot();
                if (x != null && !isOurBubble(x)) return x;
            }
        } catch (Exception ignored) {}
        return r;
    }

    private boolean isOurBubble(AccessibilityNodeInfo r) {
        CharSequence p = r.getPackageName();
        return p != null && p.toString().equals(getPackageName()) && r.getChildCount() == 0;
    }

    String pkg() {
        AccessibilityNodeInfo r = root();
        return r == null || r.getPackageName() == null ? "" : r.getPackageName().toString();
    }

    String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) { return pkg; }
    }

    /** A compact, numbered list of what's visible: the AI uses the numbers with tap / type_text. */
    synchronized String read() {
        AccessibilityNodeInfo r = root();
        last.clear();
        if (r == null) return "Screen can't be read right now.";
        StringBuilder sb = new StringBuilder();
        String p = r.getPackageName() == null ? "" : r.getPackageName().toString();
        sb.append("App on screen: ").append(appLabel(p)).append(" (").append(p).append(")\n");
        walk(r, sb, 0);
        AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f != null && f.isEditable()) sb.append("Keyboard is open on: ").append(label(f)).append('\n');
        if (last.isEmpty()) sb.append("(nothing readable — the app may draw its own UI; use a screenshot to look)\n");
        return sb.toString();
    }

    private void walk(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 40 || last.size() >= 160) return;
        if (!n.isVisibleToUser()) return;
        String lab = label(n);
        boolean act = n.isClickable() || n.isLongClickable() || n.isEditable() || n.isCheckable() || n.isScrollable();
        if (!lab.isEmpty() || n.isEditable() || (act && n.isScrollable())) {
            int i = last.size();
            last.add(n);
            sb.append('[').append(i).append("] ").append(kind(n));
            if (!lab.isEmpty()) sb.append(" \"").append(lab).append('"');
            if (n.isCheckable()) sb.append(n.isChecked() ? " (on)" : " (off)");
            if (n.isSelected()) sb.append(" (selected)");
            if (!n.isEnabled()) sb.append(" (disabled)");
            sb.append('\n');
        }
        for (int c = 0; c < n.getChildCount(); c++) {
            AccessibilityNodeInfo ch = null;
            try { ch = n.getChild(c); } catch (Exception ignored) {}
            walk(ch, sb, depth + 1);
        }
    }

    private static String kind(AccessibilityNodeInfo n) {
        if (n.isEditable()) return "input";
        CharSequence cls = n.getClassName();
        String c = cls == null ? "" : cls.toString();
        if (c.endsWith("Switch") || c.endsWith("ToggleButton")) return "switch";
        if (c.endsWith("CheckBox")) return "checkbox";
        if (c.endsWith("SeekBar")) return "slider";
        if (n.isScrollable()) return "list";
        if (c.endsWith("Button") || c.endsWith("ImageButton")) return "button";
        if (n.isClickable()) return "item";
        return "text";
    }

    static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText(), d = n.getContentDescription();
        String s = t != null && t.length() > 0 ? t.toString() : d != null ? d.toString() : "";
        if (s.isEmpty() && Build.VERSION.SDK_INT >= 26 && n.getHintText() != null) s = n.getHintText().toString();
        if (s.isEmpty() && n.getViewIdResourceName() != null && (n.isClickable() || n.isEditable())) {
            String id = n.getViewIdResourceName();
            s = "#" + id.substring(id.indexOf('/') + 1);
        }
        s = s.replace('\n', ' ').trim();
        return s.length() > 90 ? s.substring(0, 90) + "…" : s;
    }

    // ------------------------------------------------------------------ finding

    /** A target is a number from the last read, a view id (#send / com.app:id/send) or visible text. */
    synchronized AccessibilityNodeInfo find(String target, boolean wantEditable) {
        if (target == null) return null;
        String t = target.trim();
        if (t.matches("\\[?\\d+\\]?")) {
            int i = Integer.parseInt(t.replaceAll("\\D", ""));
            if (i >= 0 && i < last.size()) { AccessibilityNodeInfo n = last.get(i); n.refresh(); return n; }
            return null;
        }
        AccessibilityNodeInfo r = root();
        if (r == null) return null;
        if (t.contains(":id/") || t.startsWith("#")) {
            String id = t.startsWith("#") ? (r.getPackageName() + ":id/" + t.substring(1)) : t;
            List<AccessibilityNodeInfo> l = r.findAccessibilityNodeInfosByViewId(id);
            for (AccessibilityNodeInfo n : l) if (n.isVisibleToUser()) return n;
            if (!l.isEmpty()) return l.get(0);
        }
        String q = t.toLowerCase(Locale.ROOT);
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collect(r, all, 0);
        AccessibilityNodeInfo best = null; int bestScore = 0;
        for (AccessibilityNodeInfo n : all) {
            if (wantEditable && !n.isEditable()) continue;
            String s = full(n).toLowerCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            int score = s.equals(q) ? 4 : s.startsWith(q) ? 3 : s.contains(q) ? 2 : 0;
            if (score == 0) continue;
            if (n.isClickable() || n.isEditable()) score += 1;
            if (score > bestScore) { best = n; bestScore = score; }
        }
        if (best == null && wantEditable) for (AccessibilityNodeInfo n : all) if (n.isEditable()) return n;
        return best;
    }

    private static String full(AccessibilityNodeInfo n) {
        CharSequence t = n.getText(), d = n.getContentDescription();
        String s = (t == null ? "" : t.toString()) + (d == null ? "" : (t == null ? "" : " ") + d);
        if (Build.VERSION.SDK_INT >= 26 && n.getHintText() != null) s += " " + n.getHintText();
        return s.trim();
    }

    private void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 40 || out.size() > 600) return;
        if (n.isVisibleToUser()) out.add(n);
        for (int c = 0; c < n.getChildCount(); c++) {
            try { collect(n.getChild(c), out, depth + 1); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------ actions

    boolean click(AccessibilityNodeInfo n, boolean longPress) {
        if (n == null) return false;
        bringIntoView(n);
        int act = longPress ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK;
        for (AccessibilityNodeInfo p = n; p != null; p = p.getParent()) {
            if ((longPress ? p.isLongClickable() : p.isClickable()) && p.isEnabled()) {
                if (p.performAction(act)) return true;
                break;
            }
        }
        // gesture fallback: tap the middle of the node, kept inside the screen, twice if the first try missed
        for (int attempt = 0; attempt < 2; attempt++) {
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            if (b.isEmpty()) return false;
            DisplayMetrics dm = getResources().getDisplayMetrics();
            float x = Math.max(4, Math.min(dm.widthPixels - 4, b.exactCenterX()));
            float y = Math.max(4, Math.min(dm.heightPixels - 4, b.exactCenterY()));
            if (tapAt(x, y, longPress ? 700 : 60)) return true;
            bringIntoView(n);
        }
        return false;
    }

    /** Scrolls the node into the middle of the screen when Android offers it (Android 12+). */
    void bringIntoView(AccessibilityNodeInfo n) {
        if (n == null || Build.VERSION.SDK_INT < 31) return;
        try { n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId()); } catch (Throwable ignored) {}
    }

    boolean tapAt(float x, float y, long ms) {
        Path p = new Path();
        p.moveTo(x, y);
        return gesture(new GestureDescription.StrokeDescription(p, 0, ms));
    }

    boolean swipe(float x1, float y1, float x2, float y2, long ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return gesture(new GestureDescription.StrokeDescription(p, 0, ms));
    }

    private boolean gesture(GestureDescription.StrokeDescription s) {
        final boolean[] ok = {false};
        CountDownLatch l = new CountDownLatch(1);
        boolean sent = dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { ok[0] = true; l.countDown(); }
            @Override public void onCancelled(GestureDescription g) { l.countDown(); }
        }, null);
        if (!sent) return false;
        try { l.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return ok[0];
    }

    /** A real finger drag between two points (sliders, WhatsApp voice notes, drag & drop, volume panels). */
    boolean drag(float x1, float y1, float x2, float y2, long ms) {
        if (Build.VERSION.SDK_INT < 26) return false;              // continued strokes need Android 8+
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return gesture(new GestureDescription.StrokeDescription(p, 0, Math.max(120, ms), true));
    }

    /** Drags a slider so its thumb lands on the wanted value (0-100) — a plain tap is ignored by most sliders. */
    boolean setSlider(AccessibilityNodeInfo n, int percent) {
        if (n == null) return false;
        bringIntoView(n);
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        if (b.isEmpty()) return false;
        float y = b.exactCenterY();
        float target = b.left + b.width() * Math.max(0, Math.min(100, percent)) / 100f;
        float start = b.left + b.width() * 0.5f;
        return drag(start, y, Math.max(b.left + 8, Math.min(b.right - 8, target)), y, 420);
    }

    boolean setText(AccessibilityNodeInfo n, String text) {
        if (n == null) return false;
        if (!n.isFocused()) { n.performAction(AccessibilityNodeInfo.ACTION_FOCUS); n.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    /** Presses the keyboard's action key (search / send / go) on the focused field when Android supports it. */
    boolean submit(AccessibilityNodeInfo n) {
        if (n == null || Build.VERSION.SDK_INT < 30) return false;
        try { return n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId()); } catch (Exception e) { return false; }
    }

    /** Scrolls the first (or the given) scrollable list; falls back to a swipe across the screen. */
    boolean scroll(String dir, AccessibilityNodeInfo target) {
        boolean fwd = !"up".equals(dir) && !"left".equals(dir);
        AccessibilityNodeInfo s = target;
        while (s != null && !s.isScrollable()) s = s.getParent();
        if (s == null) s = firstScrollable(root(), 0);
        if (s != null && s.performAction(fwd ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float w = dm.widthPixels, h = dm.heightPixels;
        switch (dir == null ? "down" : dir) {
            case "up": return swipe(w / 2, h * 0.3f, w / 2, h * 0.75f, 350);
            case "left": return swipe(w * 0.2f, h / 2, w * 0.85f, h / 2, 300);
            case "right": return swipe(w * 0.85f, h / 2, w * 0.15f, h / 2, 300);
            default: return swipe(w / 2, h * 0.75f, w / 2, h * 0.3f, 350);
        }
    }

    private AccessibilityNodeInfo firstScrollable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) return null;
        if (n.isScrollable() && n.isVisibleToUser()) return n;
        for (int c = 0; c < n.getChildCount(); c++) {
            AccessibilityNodeInfo r = null;
            try { r = firstScrollable(n.getChild(c), depth + 1); } catch (Exception ignored) {}
            if (r != null) return r;
        }
        return null;
    }

    boolean global(String what) {
        int a;
        switch (what == null ? "" : what) {
            case "back": a = GLOBAL_ACTION_BACK; break;
            case "home": a = GLOBAL_ACTION_HOME; break;
            case "recents": a = GLOBAL_ACTION_RECENTS; break;
            case "notifications": a = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": a = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "power_menu": a = GLOBAL_ACTION_POWER_DIALOG; break;
            case "lock": if (Build.VERSION.SDK_INT < 28) return false; a = GLOBAL_ACTION_LOCK_SCREEN; break;
            case "screenshot": if (Build.VERSION.SDK_INT < 28) return false; a = GLOBAL_ACTION_TAKE_SCREENSHOT; break;
            default: return false;
        }
        return performGlobalAction(a);
    }

    /** Waits until a node matching any of the ids / texts appears in the given app, up to ms. */
    AccessibilityNodeInfo waitFor(String pkg, String[] ids, String[] texts, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo r = root();
            if (r != null && (pkg == null || (r.getPackageName() != null && r.getPackageName().toString().startsWith(pkg)))) {
                if (ids != null) for (String id : ids) {
                    List<AccessibilityNodeInfo> l = r.findAccessibilityNodeInfosByViewId(id);
                    for (AccessibilityNodeInfo n : l) if (n.isVisibleToUser()) return n;
                }
                if (texts != null) for (String tx : texts) {
                    List<AccessibilityNodeInfo> l = r.findAccessibilityNodeInfosByText(tx);
                    for (AccessibilityNodeInfo n : l) {
                        if (!n.isVisibleToUser()) continue;
                        String s = full(n);
                        if (s.equalsIgnoreCase(tx)) return n;
                    }
                }
            }
            try { Thread.sleep(150); } catch (InterruptedException e) { return null; }
        }
        return null;
    }

    /** Waits a moment for the screen to settle after something was opened or tapped. */
    void settle(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            try { Thread.sleep(180); } catch (InterruptedException e) { return; }
        }
    }

    /** True when any visible node contains one of the given words right now (used to check a tap worked). */
    boolean hasText(String... needles) {
        AccessibilityNodeInfo r = root();
        if (r == null) return false;
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collect(r, all, 0);
        for (AccessibilityNodeInfo n : all) {
            String s = full(n).toLowerCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            for (String t : needles) if (t != null && !t.isEmpty() && s.contains(t.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** Scrolls around looking for text (up to {@code pages} screenfuls) so the AI can reach things below the fold. */
    AccessibilityNodeInfo findScroll(String text, int pages) {
        for (int i = 0; i <= Math.max(0, pages); i++) {
            AccessibilityNodeInfo n = find(text, false);
            if (n != null) return n;
            scroll("down", null);
            settle(420);
        }
        return find(text, false);
    }

    // ------------------------------------------------------------------ screenshots (Android 11+)

    /** A small JPEG of the current screen, or null. Lets the live model look at the result of what it did. */
    byte[] shot() {
        if (Build.VERSION.SDK_INT < 30) return null;
        final byte[][] out = {null};
        CountDownLatch l = new CountDownLatch(1);
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult res) {
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(res.getHardwareBuffer(), res.getColorSpace());
                        res.getHardwareBuffer().close();
                        if (hw != null) {
                            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                            hw.recycle();
                            float k = Math.min(1f, 768f / Math.max(sw.getWidth(), sw.getHeight()));
                            Bitmap sm = k < 1f ? Bitmap.createScaledBitmap(sw, Math.round(sw.getWidth() * k), Math.round(sw.getHeight() * k), true) : sw;
                            ByteArrayOutputStream bo = new ByteArrayOutputStream();
                            sm.compress(Bitmap.CompressFormat.JPEG, 60, bo);
                            out[0] = bo.toByteArray();
                            if (sm != sw) sm.recycle();
                            sw.recycle();
                        }
                    } catch (Exception ignored) {}
                    l.countDown();
                }
                @Override public void onFailure(int code) { l.countDown(); }
            });
            l.await(2500, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {}
        return out[0];
    }
}
