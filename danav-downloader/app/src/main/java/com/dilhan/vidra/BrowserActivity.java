package com.dilhan.vidra;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Vidra's browser: ad blocking (levels), reliable video detection (network + page script + player
 * events), a draggable download button, and in-browser download / ad-block panels (HTML overlay).
 *   mode "detect" (default) — normal browsing
 *   mode "signin" — sign in to a site, then Done (returns {signin:true})
 */
public class BrowserActivity extends Activity implements Bridge.Host {
    // colours follow Settings → Appearance (light / dark / system + accent)
    private int INK = 0xFF18181B, MUTED = 0xFF71717A, LINE = 0xFFE4E4E7, ACCENT = 0xFFDC2626, SOFT = 0xFFF4F4F5, DARK = 0xFF52525B, BG = Color.WHITE, ICON = 0xFF3F3F46, RIPPLE = 0x1F18181B;
    private boolean dark;
    private static final String[][] ACCENTS = {{"red", "#dc2626"}, {"rose", "#e11d48"}, {"orange", "#ea580c"}, {"amber", "#d97706"}, {"green", "#16a34a"}, {"teal", "#0d9488"},
            {"blue", "#2563eb"}, {"indigo", "#4f46e5"}, {"violet", "#7c3aed"}, {"pink", "#db2777"}, {"graphite", "#3f3f46"}};

    private static int mix(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t), g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t), bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    private void loadColors() {
        dark = MainActivity.isDark(this);
        String key = Store.settings(this).optString("accent", "red");
        int acc = 0xFFDC2626;
        for (String[] a : ACCENTS) if (a[0].equals(key)) acc = Color.parseColor(a[1]);
        if (dark) {
            BG = 0xFF18181B; INK = 0xFFF4F4F5; MUTED = 0xFFA1A1AA; LINE = 0xFF2E2E33; SOFT = 0xFF27272A; DARK = 0xFF52525B; ICON = 0xFFD4D4D8; RIPPLE = 0x29FFFFFF;
            ACCENT = "graphite".equals(key) ? 0xFFA1A1AA : mix(acc, Color.WHITE, 0.12f);
        } else ACCENT = acc;
    }

    private String accentHex() { return String.format("#%06x", ACCENT & 0xFFFFFF); }

    private String startUrl() { return "file:///android_asset/app/start.html?d=" + (dark ? 1 : 0) + "&a=" + Uri.encode(accentHex()); }
        private static final int REQ_FILE = 31;
    private static final Pattern P_HLS = Pattern.compile("\\.m3u8(\\?|#|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_DASH = Pattern.compile("\\.mpd(\\?|#|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_FILE = Pattern.compile("\\.(mp4|webm|m4v|mov|mkv|3gp|flv|ogv)(\\?|#|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_SEG = Pattern.compile("\\.(ts|m4s|aac|m4a|vtt)(\\?|#|$)|/(seg|segment|chunk|frag)[-_]?\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_SKIP = Pattern.compile("(googlevideo\\.com/videoplayback|doubleclick|googlesyndication|imasdk|/ads?/|\\bads?\\.|vast|preroll)", Pattern.CASE_INSENSITIVE);

    private WebView web, overlay;
    private FrameLayout root, holder, fab, fullscreen;
    private EditText address;
    private ProgressBar bar;
    private TextView shieldCount, fabBadge;
    private ImageView shieldIcon, addrIcon;
    private View toolbar;
    private String mode;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // page state (written on the UI thread, read on WebView IO threads)
    private volatile String pageUrl = "", pageHost = "", pageTitle = "";
    private volatile int pageLevel = AdBlock.OPTIMAL;
    private volatile int blockedPage;
    private volatile boolean playing, mse;
    private boolean desktop, overlayReady, overlayOpen;
    private String pendingPanel;
    private String startUa;

    private static final class Cand { final String url, kind, how; final long t; Cand(String u, String k, String h) { url = u; kind = k; how = h; t = System.currentTimeMillis(); } }
    private final Map<String, Cand> found = new LinkedHashMap<>();
    private final HashSet<String> fileKeys = new HashSet<>();
    private String injectJs;
    private ValueCallback<Uri[]> fileCb;
    private final Jobs.Listener jobListener = job -> sendOverlay("job", job.toString());
    private final Runnable adListener = () -> ui.post(this::refreshShield);

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    /** Round ripple for icon buttons. */
    private android.graphics.drawable.Drawable press(int normal) {
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setShape(android.graphics.drawable.GradientDrawable.OVAL); mask.setColor(Color.WHITE);
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(RIPPLE), normal == Color.TRANSPARENT ? null : new ColorDrawable(normal), mask);
    }

    private android.graphics.drawable.GradientDrawable rr(int color, float radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radiusDp)); return g;
    }

    private android.graphics.drawable.GradientDrawable gradient(float radiusDp) {
        return rr(ACCENT, radiusDp);
    }

    private ImageView iconBtn(int res, String desc) {
        ImageView b = new ImageView(this);
        b.setImageResource(res);
        b.setContentDescription(desc);
        b.setColorFilter(ICON);
        b.setPadding(dp(11), dp(11), dp(11), dp(11));
        b.setBackground(press(Color.TRANSPARENT));
        return b;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface", "ClickableViewAccessibility"})
    @Override protected void onCreate(Bundle b) {
        loadColors();
        if (dark) setTheme(R.style.Vidra_Dark);     // also makes web pages that support it (e.g. search results) dark
        super.onCreate(b);
        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "detect";
        prefs = getSharedPreferences("browser", MODE_PRIVATE);
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 27 && !dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);
        try { injectJs = readAsset("inject/page.js"); } catch (Exception e) { injectJs = ""; }

        root = new FrameLayout(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);

        // ---------- toolbar: close · [icon address] · shield(count) · menu
        LinearLayout top = new LinearLayout(this);
        toolbar = top;
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), dp(8), dp(4), dp(8));
        top.setBackgroundColor(BG);
        ImageView close = iconBtn(R.drawable.ic_close, "Close browser");
        close.setOnClickListener(v -> finish());
        top.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout addr = new LinearLayout(this);
        addr.setGravity(Gravity.CENTER_VERTICAL);
        addr.setBackground(rr(SOFT, 6));
        addr.setPadding(dp(14), 0, dp(6), 0);
        addrIcon = new ImageView(this);
        addrIcon.setImageResource(R.drawable.ic_globe);
        addrIcon.setColorFilter(MUTED);
        addr.addView(addrIcon, new LinearLayout.LayoutParams(dp(16), dp(16)));
        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        address.setTextColor(INK);
        address.setHintTextColor(MUTED);
        address.setHint("Search Google or type a web address");
        address.setHighlightColor(mix(ACCENT, BG, 0.65f));
        address.setBackground(null);
        address.setPadding(dp(8), 0, dp(4), 0);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setSelectAllOnFocus(true);
        address.setOnFocusChangeListener((v, has) -> {
            if (has) { address.setText(pageUrl.startsWith("file:") ? "" : pageUrl); address.selectAll(); showSuggest(true); }
            else { showHost(); showSuggest(false); }
        });
        address.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b2, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b2, int d) {}
            @Override public void afterTextChanged(android.text.Editable e) { if (address.hasFocus()) fillSuggest(e.toString()); }
        });
        address.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_GO || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { go(address.getText().toString()); return true; }
            return false;
        });
        addr.addView(address, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, dp(44), 1); ap.setMargins(dp(2), 0, dp(4), 0);
        top.addView(addr, ap);

        FrameLayout shield = new FrameLayout(this);
        shield.setBackground(press(Color.TRANSPARENT));
        shield.setContentDescription("Ad blocker");
        shieldIcon = new ImageView(this);
        shieldIcon.setImageResource(R.drawable.ic_shield_on);
        shieldIcon.setColorFilter(ACCENT);
        shield.addView(shieldIcon, flp(dp(22), dp(22), Gravity.CENTER));
        shieldCount = new TextView(this);
        shieldCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        shieldCount.setTypeface(Typeface.DEFAULT_BOLD);
        shieldCount.setTextColor(Color.WHITE);
        android.graphics.drawable.GradientDrawable scb = rr(ACCENT, 8); scb.setStroke(dp(1.5f), BG);
        shieldCount.setBackground(scb);
        shieldCount.setMinWidth(dp(16));
        shieldCount.setPadding(dp(4), 0, dp(4), 0);
        shieldCount.setGravity(Gravity.CENTER);
        shieldCount.setVisibility(View.GONE);
        FrameLayout.LayoutParams scp = flp(-2, dp(16), Gravity.TOP | Gravity.END); scp.setMargins(0, dp(4), dp(2), 0);
        shield.addView(shieldCount, scp);
        shield.setOnClickListener(v -> openPanel("adblock"));
        top.addView(shield, new LinearLayout.LayoutParams(dp(44), dp(44)));
        ImageView menu = iconBtn(R.drawable.ic_menu, "Menu");
        menu.setOnClickListener(v -> openPanel("menu"));
        top.addView(menu, new LinearLayout.LayoutParams(dp(40), dp(44)));
        col.addView(top);
        View line = new View(this);
        line.setBackgroundColor(LINE);
        col.addView(line, new LinearLayout.LayoutParams(-1, 1));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        bar.setIndeterminate(false);
        col.addView(bar, new LinearLayout.LayoutParams(-1, dp(2)));

        holder = new FrameLayout(this);
        web = new WebView(this);
        holder.addView(web, new FrameLayout.LayoutParams(-1, -1));
        col.addView(holder, new LinearLayout.LayoutParams(-1, 0, 1));

        if (mode.equals("signin")) col.addView(signinBar());
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        if (!mode.equals("signin")) buildFab();
        setContentView(root);

        setupWeb();
        refreshShield();
        String url = getIntent().getStringExtra("url");
        if (url == null || url.trim().isEmpty()) {
            web.loadUrl(startUrl());
            address.requestFocus();
            ui.postDelayed(() -> ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(address, 0), 300);
        } else go(url);
        Jobs.addListener(jobListener);
        AdBlock.addListener(adListener);
    }

    private FrameLayout.LayoutParams flp(int w, int h, int g) { FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h); p.gravity = g; return p; }

    private View signinBar() {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(16), dp(10), dp(12), dp(10));
        android.graphics.drawable.GradientDrawable sbg = new android.graphics.drawable.GradientDrawable(); sbg.setColor(BG);
        bottom.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{new ColorDrawable(LINE), new android.graphics.drawable.InsetDrawable(sbg, 0, 1, 0, 0)}));
        TextView status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setTextColor(MUTED);
        status.setText("Sign in to your account, then tap Done. Danav only uses this sign-in to download your videos.");
        TextView done = new TextView(this);
        done.setText("Done");
        done.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        done.setTypeface(Typeface.DEFAULT_BOLD);
        done.setTextColor(Color.WHITE);
        done.setGravity(Gravity.CENTER);
        done.setPadding(dp(20), 0, dp(20), 0);
        done.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), gradient(8), null));
        done.setOnClickListener(v -> { CookieManager.getInstance().flush(); setResult(RESULT_OK, new Intent().putExtra("result", "{\"signin\":true}")); finish(); });
        bottom.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams dpm = new LinearLayout.LayoutParams(-2, dp(38)); dpm.setMargins(dp(12), 0, 0, 0);
        bottom.addView(done, dpm);
        return bottom;
    }

    private String readAsset(String name) throws Exception {
        try (InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toString("UTF-8");
        }
    }

    // ------------------------------------------------------------------ download button (draggable)

    @SuppressLint("ClickableViewAccessibility")
    private void buildFab() {
        fab = new FrameLayout(this);
        fab.setBackground(fabBg(false));
        fab.setElevation(0);
        fab.setContentDescription("Download video from this page");
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_dl);
        ic.setColorFilter(Color.WHITE);
        fab.addView(ic, flp(dp(22), dp(22), Gravity.CENTER));
        fabBadge = new TextView(this);
        fabBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        fabBadge.setTypeface(Typeface.DEFAULT_BOLD);
        fabBadge.setTextColor(Color.WHITE);
        fabBadge.setBackground(badgeBg(false));
        fabBadge.setGravity(Gravity.CENTER);
        fabBadge.setMinWidth(dp(18));
        fabBadge.setPadding(dp(4), 0, dp(4), 0);
        fabBadge.setVisibility(View.GONE);
        fab.addView(fabBadge, flp(-2, dp(18), Gravity.TOP | Gravity.END));
        root.addView(fab, flp(dp(50), dp(50), Gravity.TOP | Gravity.START));
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        final float[] st = new float[4];
        final boolean[] drag = {false};
        fab.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    st[0] = e.getRawX(); st[1] = e.getRawY(); st[2] = v.getTranslationX(); st[3] = v.getTranslationY(); drag[0] = false;
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - st[0], dy = e.getRawY() - st[1];
                    if (!drag[0] && Math.hypot(dx, dy) > slop) drag[0] = true;
                    if (drag[0]) {
                        v.setTranslationX(clamp(st[2] + dx, 0, root.getWidth() - v.getWidth()));
                        v.setTranslationY(clamp(st[3] + dy, toolbar.getBottom(), root.getHeight() - v.getHeight()));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    if (drag[0]) snapFab(true);
                    else if (e.getActionMasked() == MotionEvent.ACTION_UP) openPanel("download");
                    return true;
            }
            return false;
        });
        root.post(() -> placeFab());
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> { if (r - l != or - ol || bt - t != ob - ot) placeFab(); });
        fab.setVisibility(prefs.getBoolean("fab", true) ? View.VISIBLE : View.GONE);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private void placeFab() {
        if (fab == null || root.getWidth() == 0) return;
        boolean right = prefs.getBoolean("fabRight", true);
        float fy = prefs.getFloat("fabY", 0.72f);
        int m = dp(14);
        fab.setTranslationX(right ? root.getWidth() - dp(50) - m : m);
        fab.setTranslationY(clamp(fy * root.getHeight(), toolbar.getBottom() + m, root.getHeight() - dp(50) - m));
    }

    private void snapFab(boolean save) {
        int m = dp(14);
        boolean right = fab.getTranslationX() + fab.getWidth() / 2f > root.getWidth() / 2f;
        float x = right ? root.getWidth() - fab.getWidth() - m : m;
        float y = clamp(fab.getTranslationY(), toolbar.getBottom() + m, root.getHeight() - fab.getHeight() - m);
        fab.animate().translationX(x).translationY(y).setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        if (save) prefs.edit().putBoolean("fabRight", right).putFloat("fabY", y / Math.max(1, root.getHeight())).apply();
    }

    /** Download button: dark circle when idle, brand gradient once a video is found. */
    private android.graphics.drawable.Drawable fabBg(boolean active) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(active ? ACCENT : DARK);
        g.setStroke(dp(2), Color.WHITE);
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40FFFFFF), g, null);
    }

    private android.graphics.drawable.Drawable badgeBg(boolean found) {
        android.graphics.drawable.GradientDrawable g = rr(found ? 0xFF18181B : ACCENT, 9);
        g.setStroke(dp(1.5f), Color.WHITE);
        return g;
    }

    private int lastBadge = -1;
    private void refreshFab() {
        if (fab == null) return;
        int n = streamCount();
        fab.setBackground(fabBg(n > 0 || playing));
        fabBadge.setBackground(badgeBg(n > 0));
        fabBadge.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        fabBadge.setText(String.valueOf(n));
        if (n > lastBadge && n > 0) {
            fab.animate().cancel();
            fab.setScaleX(1.18f); fab.setScaleY(1.18f);
            fab.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(new OvershootInterpolator(2f)).start();
        }
        lastBadge = n;
        // a video is on this page: start reading it now so the download sheet opens with qualities ready
        if ((n > 0 || playing) && pageUrl != null && pageUrl.startsWith("http") && !pageUrl.equals(prefetched)) {
            String path = Uri.parse(pageUrl).getPath();
            if (path != null && path.length() > 1) {
                prefetched = pageUrl;
                try { Engine.prefetch(this, pageUrl, new JSONObject().put("ua", web.getSettings().getUserAgentString())); } catch (Exception ignored) {}
            }
        }
        // and the stream the player is using (generic sites answer from the stream much sooner than from the page)
        if (n > 0 && pageUrl != null && pageUrl.startsWith("http")) {
            JSONObject top = candidates().optJSONObject(0);
            String u = top == null ? null : top.optString("url");
            if (u != null && !u.equals(prefetchedStream)) {
                prefetchedStream = u;
                try { Engine.prefetch(this, u, new JSONObject().put("referer", pageUrl).put("ua", web.getSettings().getUserAgentString())); } catch (Exception ignored) {}
            }
        }
    }
    private String prefetchedStream = "";
    private String prefetched = "";

    // ------------------------------------------------------------------ web view

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        startUa = s.getUserAgentString().replace("; wv", "");
        s.setUserAgentString(startUa);   // look like normal Chrome
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new PageApi(), "VidraPage");
        boolean docStart = false;
        try {
            if (!injectJs.isEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(web, injectJs, Collections.singleton("*"));
                docStart = true;

            }
        } catch (Throwable ignored) {}
        final boolean haveDocStart = docStart;

        web.setWebChromeClient(new WebChromeClient() {
            private View custom;
            private CustomViewCallback customCb;
            @Override public void onProgressChanged(WebView v, int p) { bar.setProgress(p); bar.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE); }
            @Override public void onReceivedTitle(WebView v, String t) { pageTitle = t == null ? "" : t; }
            @Override public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, Message msg) {
                if (!gesture && pageLevel >= AdBlock.OPTIMAL) { popupBlocked(); return false; }
                // capture the pop-up's address and open it here instead (single-tab browser)
                WebView tmp = new WebView(BrowserActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView t, WebResourceRequest r) { handlePopup(r.getUrl()); t.destroy(); return true; }
                    @Override public void onPageStarted(WebView t, String url, Bitmap f) { handlePopup(Uri.parse(url)); t.stopLoading(); t.destroy(); }
                });
                ((WebView.WebViewTransport) msg.obj).setWebView(tmp);
                msg.sendToTarget();
                return true;
            }
            @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                if (custom != null) { cb.onCustomViewHidden(); return; }
                custom = view; customCb = cb;
                fullscreen = new FrameLayout(BrowserActivity.this);
                fullscreen.setBackgroundColor(Color.BLACK);
                fullscreen.addView(view, new FrameLayout.LayoutParams(-1, -1));
                root.addView(fullscreen, new FrameLayout.LayoutParams(-1, -1));
                immersive(true);
            }
            @Override public void onHideCustomView() {
                if (custom == null) return;
                root.removeView(fullscreen);
                fullscreen = null; custom = null;
                immersive(false);
                if (customCb != null) customCb.onCustomViewHidden();
            }
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try { startActivityForResult(p.createIntent(), REQ_FILE); } catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sc = u.getScheme() == null ? "" : u.getScheme();
                if (sc.equals("http") || sc.equals("https") || sc.equals("file") || sc.equals("about")) {
                    // redirect-style ads: navigation to an ad / malware domain without a tap
                    if (r.isForMainFrame() && !r.hasGesture() && pageLevel >= AdBlock.OPTIMAL && !pageHost.isEmpty()
                            && !AdBlock.site(u.getHost()).equals(AdBlock.site(pageHost)) && AdBlock.isBadHost(u.getHost(), pageLevel)) {
                        popupBlocked();
                        return true;
                    }
                    return false;
                }
                if (!r.hasGesture()) return true;     // apps launched by scripts = spam
                try {
                    Intent i = sc.equals("intent") ? Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) : new Intent(Intent.ACTION_VIEW, u);
                    i.addCategory(Intent.CATEGORY_BROWSABLE); i.setComponent(null); i.setSelector(null);
                    startActivity(i);
                } catch (Exception e) {
                    try {
                        Intent i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                        String fb = i.getStringExtra("browser_fallback_url");
                        if (fb != null) v.loadUrl(fb);
                    } catch (Exception ignored) {}
                }
                return true;
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap f) {
                newPage(url);
                if (!haveDocStart && !injectJs.isEmpty()) v.evaluateJavascript(injectJs, null);
            }
            @Override public void onPageFinished(WebView v, String url) {
                setPageUrl(url);
                remember(url, v.getTitle());
                if (!haveDocStart && !injectJs.isEmpty()) v.evaluateJavascript(injectJs, null);
                try { if (mode.equals("signin")) Cookies.rememberHost(BrowserActivity.this, Uri.parse(url).getHost()); } catch (Exception ignored) {}
                CookieManager.getInstance().flush();
            }
            @Override public void doUpdateVisitedHistory(WebView v, String url, boolean reload) {
                if (!url.equals(pageUrl)) {
                    if (pathChanged(pageUrl, url)) clearFound();   // single-page sites (YouTube, Instagram…) change "page" without loading
                    setPageUrl(url);
                }
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!r.isForMainFrame() && pageLevel > AdBlock.OFF && AdBlock.shouldBlock(u, pageHost, pageLevel)) {
                    blockedPage++;
                    AdBlock.countBlocked();
                    ui.post(BrowserActivity.this::refreshShield);
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                if (!mode.equals("signin")) {
                    String accept = r.getRequestHeaders() == null ? null : r.getRequestHeaders().get("Accept");
                    consider(u.toString(), null, accept != null && accept.startsWith("video/") ? "media" : "net");
                }
                return null;
            }
        });
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            // a direct file link: hand it to Vidra's downloader
            if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/")) || P_FILE.matcher(url).find()) {
                consider(url, "file", "link");
                openPanel("download");
            } else {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { toast("No app can open this file"); }
            }
        });
    }

    private void immersive(boolean on) {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                if (on) { c.hide(WindowInsets.Type.systemBars()); c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
                else c.show(WindowInsets.Type.systemBars());
            }
        } else {
            int f = on ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION : (dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            w.getDecorView().setSystemUiVisibility(f);
        }
        setRequestedOrientation(on ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private void handlePopup(Uri u) {
        ui.post(() -> {
            if (u == null || u.getHost() == null) return;
            if (pageLevel >= AdBlock.OPTIMAL && AdBlock.isBadHost(u.getHost(), pageLevel)) { popupBlocked(); return; }
            web.loadUrl(u.toString());
        });
    }

    private long lastPopupToast;
    private void popupBlocked() {
        blockedPage++;
        AdBlock.countBlocked();
        ui.post(() -> {
            refreshShield();
            if (System.currentTimeMillis() - lastPopupToast > 4000) { lastPopupToast = System.currentTimeMillis(); toast("Pop-up blocked"); }
        });
    }

    private static boolean pathChanged(String a, String b) {
        try {
            Uri x = Uri.parse(a), y = Uri.parse(b);
            return !(String.valueOf(x.getHost()) + x.getPath() + x.getQuery()).equals(String.valueOf(y.getHost()) + y.getPath() + y.getQuery());
        } catch (Exception e) { return true; }
    }

    private void newPage(String url) {
        if (pathChanged(pageUrl, url)) { clearFound(); blockedPage = 0; }
        setPageUrl(url);
        pageLevel = AdBlock.level(pageHost);
        refreshShield();
    }

    private void setPageUrl(String url) {
        pageUrl = url == null ? "" : url;
        String h = "";
        try { h = Uri.parse(pageUrl).getHost(); } catch (Exception ignored) {}
        pageHost = h == null ? "" : h;
        if (!address.hasFocus()) showHost();
        addrIcon.setImageResource(pageUrl.startsWith("https:") ? R.drawable.ic_lock : R.drawable.ic_globe);
        if (overlayOpen) pushPage();
    }

    // ------------------------------------------------------------------ address suggestions (Chrome-like)

    private ScrollView suggest;
    private LinearLayout suggestList;

    private void hideKeyboard() { ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(), 0); }

    private JSONArray history() { try { return new JSONArray(prefs.getString("hist", "[]")); } catch (Exception e) { return new JSONArray(); } }

    /** Visited pages, newest first (kept on this phone only, max 80). */
    private void remember(String url, String title) {
        if (url == null || !url.startsWith("http") || mode.equals("signin")) return;
        try {
            JSONArray old = history(), out = new JSONArray();
            out.put(new JSONObject().put("u", url).put("t", title == null ? "" : title).put("at", System.currentTimeMillis()));
            for (int i = 0; i < old.length() && out.length() < 80; i++) { JSONObject o = old.optJSONObject(i); if (o != null && !url.equals(o.optString("u"))) out.put(o); }
            prefs.edit().putString("hist", out.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void showSuggest(boolean on) {
        if (on) {
            if (suggest == null) {
                suggest = new ScrollView(this);
                suggest.setBackgroundColor(BG);
                suggest.setFillViewport(true);
                suggestList = new LinearLayout(this);
                suggestList.setOrientation(LinearLayout.VERTICAL);
                suggestList.setPadding(0, dp(4), 0, dp(12));
                suggest.addView(suggestList, new FrameLayout.LayoutParams(-1, -2));
                holder.addView(suggest, new FrameLayout.LayoutParams(-1, -1));
            }
            suggest.setVisibility(View.VISIBLE);
            suggest.setAlpha(0f); suggest.animate().alpha(1f).setDuration(120).start();
            fillSuggest("");
        } else if (suggest != null) suggest.setVisibility(View.GONE);
    }

    private void fillSuggest(String typed) {
        if (suggestList == null) return;
        suggestList.removeAllViews();
        String q = typed.trim();
        boolean editing = !q.isEmpty() && !q.equals(pageUrl);
        // the page you're on: copy / share / edit — like Chrome
        if (!editing && pageUrl.startsWith("http")) {
            LinearLayout cur = new LinearLayout(this);
            cur.setGravity(Gravity.CENTER_VERTICAL);
            cur.setPadding(dp(16), dp(10), dp(6), dp(10));
            ImageView fav = new ImageView(this); fav.setImageResource(R.drawable.ic_globe); fav.setColorFilter(MUTED);
            cur.addView(fav, new LinearLayout.LayoutParams(dp(20), dp(20)));
            LinearLayout txt = new LinearLayout(this); txt.setOrientation(LinearLayout.VERTICAL); txt.setPadding(dp(14), 0, dp(8), 0);
            TextView t1 = new TextView(this); t1.setText(web.getTitle() == null || web.getTitle().isEmpty() ? pageHost : web.getTitle()); t1.setTextColor(INK); t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f); t1.setSingleLine(true); t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView t2 = new TextView(this); t2.setText(pageUrl); t2.setTextColor(MUTED); t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); t2.setSingleLine(true); t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txt.addView(t1); txt.addView(t2);
            cur.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
            ImageView copy = iconBtn(R.drawable.ic_copy, "Copy link");
            copy.setOnClickListener(v -> {
                ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("link", pageUrl));
                android.widget.Toast.makeText(this, "Link copied", android.widget.Toast.LENGTH_SHORT).show();
                hideKeyboard(); address.clearFocus();
            });
            ImageView share = iconBtn(R.drawable.ic_share, "Share link");
            share.setOnClickListener(v -> { hideKeyboard(); address.clearFocus(); try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pageUrl), "Share link")); } catch (Exception ignored) {} });
            ImageView edit = iconBtn(R.drawable.ic_arrow_up, "Edit link"); edit.setRotation(-90);
            edit.setOnClickListener(v -> { address.setText(pageUrl); address.setSelection(pageUrl.length()); });
            cur.addView(copy, new LinearLayout.LayoutParams(dp(42), dp(42)));
            cur.addView(share, new LinearLayout.LayoutParams(dp(42), dp(42)));
            cur.addView(edit, new LinearLayout.LayoutParams(dp(42), dp(42)));
            suggestList.addView(cur);
            View sep = new View(this); sep.setBackgroundColor(LINE);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 1); sp.setMargins(dp(16), dp(2), dp(16), dp(4));
            suggestList.addView(sep, sp);
        }
        if (editing) {
            boolean looksUrl = q.matches("(?i)^(https?://)?[^\\s]+\\.[a-z]{2,}(/.*)?$");
            suggestList.addView(suggestRow(looksUrl ? R.drawable.ic_globe : R.drawable.ic_search, q, looksUrl ? "Go to site" : "Search Google", () -> go(q), null));
            remoteBox = new LinearLayout(this);
            remoteBox.setOrientation(LinearLayout.VERTICAL);
            suggestList.addView(remoteBox);
            List<String> cached = suggestCache.get(q.toLowerCase(Locale.ROOT));
            if (cached != null) showRemote(q, cached);
            else if (lastRemote != null && q.toLowerCase(Locale.ROOT).startsWith(lastRemoteQ)) showRemote(q, lastRemote);   // keep the old list until the new one arrives — no flicker
            fetchSuggest(q);
        } else { remoteBox = null; suggestSeq++; }
        JSONArray h = history();
        String ql = q.toLowerCase(java.util.Locale.ROOT);
        int shown = 0;
        boolean header = false;
        for (int i = 0; i < h.length() && shown < (editing ? 4 : 15); i++) {
            JSONObject o = h.optJSONObject(i); if (o == null) continue;
            String u = o.optString("u"), t = o.optString("t");
            if (u.equals(pageUrl) && !editing) continue;
            if (editing && !u.toLowerCase(java.util.Locale.ROOT).contains(ql) && !t.toLowerCase(java.util.Locale.ROOT).contains(ql)) continue;
            if (!header && !editing) { suggestList.addView(sectionHead("Recent")); header = true; }
            suggestList.addView(suggestRow(R.drawable.ic_history, t.isEmpty() ? u : t, u, () -> go(u), () -> { address.setText(u); address.setSelection(u.length()); }));
            shown++;
        }
        if (header) {
            TextView clear = new TextView(this);
            clear.setText("Clear history"); clear.setTextColor(ACCENT); clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f); clear.setTypeface(Typeface.DEFAULT_BOLD);
            clear.setPadding(dp(16), dp(12), dp(16), dp(12));
            clear.setBackground(press(Color.TRANSPARENT));
            clear.setOnClickListener(v -> { prefs.edit().remove("hist").apply(); fillSuggest(address.getText().toString()); });
            suggestList.addView(clear, new LinearLayout.LayoutParams(-2, -2));
        }
    }

    // ------------------------------------------------------------------ live search suggestions

    private LinearLayout remoteBox;
    private int suggestSeq;
    private List<String> lastRemote;
    private String lastRemoteQ = "";
    private Runnable pendingSuggest;
    private final java.util.concurrent.ExecutorService net = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Map<String, List<String>> suggestCache = new LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<String>> e) { return size() > 60; }
    };

    private void fetchSuggest(String q) {
        final int seq = ++suggestSeq;
        final String key = q.toLowerCase(Locale.ROOT);
        if (pendingSuggest != null) ui.removeCallbacks(pendingSuggest);
        if (suggestCache.containsKey(key) || q.matches("(?i)^https?://.*")) return;
        pendingSuggest = () -> net.execute(() -> {
            if (seq != suggestSeq) return;
            List<String> out = new ArrayList<>();
            java.net.HttpURLConnection c = null;
            try {
                String lang = Locale.getDefault().getLanguage();
                c = (java.net.HttpURLConnection) new java.net.URL("https://suggestqueries.google.com/complete/search?client=firefox&ie=UTF-8&oe=UTF-8&hl=" + lang + "&q=" + Uri.encode(q)).openConnection();
                c.setConnectTimeout(3000); c.setReadTimeout(3000);
                c.setRequestProperty("User-Agent", startUa == null ? "Mozilla/5.0" : startUa);
                try (InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    JSONArray arr = new JSONArray(o.toString("UTF-8")).optJSONArray(1);
                    for (int i = 0; arr != null && i < arr.length() && out.size() < 8; i++) { String sug = arr.optString(i).trim(); if (!sug.isEmpty()) out.add(sug); }
                }
            } catch (Exception ignored) { return; } finally { if (c != null) c.disconnect(); }
            ui.post(() -> {
                suggestCache.put(key, out);
                if (seq == suggestSeq && address.hasFocus()) { lastRemote = out; lastRemoteQ = key; showRemote(q, out); }
            });
        });
        ui.postDelayed(pendingSuggest, 60);
    }

    private void showRemote(String q, List<String> list) {
        if (remoteBox == null) return;
        remoteBox.removeAllViews();
        int n = 0;
        for (String sug : list) {
            if (sug.equalsIgnoreCase(q) || n >= 7) continue;
            remoteBox.addView(suggestRow(R.drawable.ic_search, sug, null, () -> go(sug), () -> { address.setText(sug + " "); address.setSelection(address.length()); }));
            n++;
        }
    }

    private TextView sectionHead(String s) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(MUTED); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(16), dp(8), dp(16), dp(4));
        return t;
    }

    private View suggestRow(int icon, String title, String sub, Runnable onTap, Runnable onFill) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(8), dp(4), dp(8));
        r.setBackground(press(Color.TRANSPARENT));
        r.setMinimumHeight(dp(52));
        ImageView ic = new ImageView(this); ic.setImageResource(icon); ic.setColorFilter(MUTED);
        r.addView(ic, new LinearLayout.LayoutParams(dp(20), dp(20)));
        LinearLayout txt = new LinearLayout(this); txt.setOrientation(LinearLayout.VERTICAL); txt.setPadding(dp(14), 0, dp(8), 0);
        TextView t1 = new TextView(this); t1.setText(title); t1.setTextColor(INK); t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f); t1.setSingleLine(true); t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(t1);
        if (sub != null && !sub.isEmpty()) { TextView t2 = new TextView(this); t2.setText(sub); t2.setTextColor(MUTED); t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); t2.setSingleLine(true); t2.setEllipsize(android.text.TextUtils.TruncateAt.END); txt.addView(t2); }
        r.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
        if (onFill != null) {
            ImageView fill = iconBtn(R.drawable.ic_arrow_up, "Edit");
            fill.setRotation(-90);
            fill.setOnClickListener(v -> onFill.run());
            r.addView(fill, new LinearLayout.LayoutParams(dp(42), dp(42)));
        }
        r.setOnClickListener(v -> onTap.run());
        r.setOnLongClickListener(v -> {
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("link", sub == null || sub.isEmpty() ? title : sub));
            android.widget.Toast.makeText(this, "Link copied", android.widget.Toast.LENGTH_SHORT).show();
            return true;
        });
        return r;
    }

    private void showHost() {
        if (pageUrl.startsWith("file:")) { address.setText(""); return; }
        address.setText(pageHost.startsWith("www.") ? pageHost.substring(4) : pageHost);
    }

    private void go(String text) {
        String t = text.trim();
        if (t.isEmpty()) return;
        String url;
        if (t.matches("(?i)^(https?|file)://.*")) url = t;
        else if (t.matches("^[^\\s]+\\.[a-zA-Z]{2,}(/.*)?$")) url = "https://" + t;
        else url = "https://www.google.com/search?q=" + Uri.encode(t);
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(), 0);
        address.clearFocus();
        pageLevel = AdBlock.level(Uri.parse(url).getHost());
        web.loadUrl(url);
    }

    private void refreshShield() {
        int lvl = AdBlock.level(pageHost);
        pageLevel = lvl;
        shieldIcon.setImageResource(lvl == AdBlock.OFF ? R.drawable.ic_shield_off : R.drawable.ic_shield_on);
        shieldIcon.setColorFilter(lvl == AdBlock.OFF ? MUTED : INK);
        int n = blockedPage;
        shieldCount.setVisibility(n > 0 && lvl > 0 ? View.VISIBLE : View.GONE);
        shieldCount.setText(n > 99 ? "99+" : String.valueOf(n));
        if (overlayOpen) sendOverlay("ad", adJson().toString());
    }

    private JSONObject adJson() {
        JSONObject o = AdBlock.state(pageHost);
        try { o.put("page", blockedPage); } catch (Exception ignored) {}
        return o;
    }

    // ------------------------------------------------------------------ detection

    private void clearFound() {
        synchronized (found) { found.clear(); fileKeys.clear(); }
        playing = false; mse = false;
        ui.post(() -> { lastBadge = -1; refreshFab(); });
    }

    private int streamCount() {
        int n = 0;
        synchronized (found) { for (Cand c : found.values()) if (!c.how.equals("data")) n++; }
        return n;
    }

    /** Every request the page makes + reports from the page script. */
    void consider(String url, String kindHint, String how) {
        if (url == null || !url.startsWith("http") || P_SKIP.matcher(url).find()) return;
        String path = url.split("#")[0];
        String kind = kindHint == null || kindHint.isEmpty() ? null : kindHint;
        if (kind == null) {
            if (P_HLS.matcher(path).find()) kind = "hls";
            else if (P_DASH.matcher(path).find()) kind = "dash";
            else if (P_FILE.matcher(path).find() || how.equals("media")) kind = "file";
            else return;
        }
        if (kind.equals("file") && P_SEG.matcher(path).find() && !P_FILE.matcher(path.split("\\?")[0]).find()) return;
        // byte-range pieces of a progressive file (Facebook / Instagram CDNs): ask for the whole file
        String clean = url.replaceAll("([?&])(bytestart|byteend|range)=[^&]*", "$1").replaceAll("[?&]+$", "").replace("?&", "?").replaceAll("&&+", "&");
        synchronized (found) {
            if (found.containsKey(clean)) return;
            if (kind.equals("file")) {
                Uri u = Uri.parse(clean);
                String key = u.getHost() + String.valueOf(u.getPath()).replaceAll("\\d+", "#");
                if (!fileKeys.add(key)) return;          // numbered fragments of the same stream
            }
            if (found.size() >= 40) return;
            found.put(clean, new Cand(clean, kind, how));
        }
        ui.post(() -> { refreshFab(); if (overlayOpen) pushPage(); });
    }

    private JSONArray candidates() {
        JSONArray c = new JSONArray();
        List<Cand> list;
        synchronized (found) { list = new ArrayList<>(found.values()); }
        // what the player actually used first, then manifests (they carry every quality), newest first
        String[][] order = {{"play", "el", "media"}, {"net", "link", "meta"}, {"data"}};
        HashSet<String> added = new HashSet<>();
        try {
            for (String[] hows : order)
                for (String k : new String[]{"hls", "dash", "file"})
                    for (int i = list.size() - 1; i >= 0; i--) {
                        Cand x = list.get(i);
                        if (!x.kind.equals(k) || added.contains(x.url)) continue;
                        boolean match = false;
                        for (String hw : hows) if (x.how.equals(hw)) match = true;
                        if (!match) continue;
                        added.add(x.url);
                        c.put(new JSONObject().put("url", x.url).put("kind", x.kind).put("how", x.how));
                    }
        } catch (Exception ignored) {}
        return c;
    }

    /** Called by the page script in every frame (on a WebView binder thread). */
    final class PageApi {
        @JavascriptInterface public String init(String href, boolean top) {
            int lvl = pageLevel;
            JSONObject o = new JSONObject();
            try {
                o.put("lvl", lvl);
                if (lvl >= AdBlock.OPTIMAL) {
                    String host = Uri.parse(href).getHost();
                    o.put("css", AdBlock.pageCss(host, lvl));
                }
            } catch (Exception ignored) {}
            return o.toString();
        }
        @JavascriptInterface public String low(String tokens) {
            if (tokens == null || tokens.isEmpty()) return "";
            ArrayList<String> t = new ArrayList<>();
            Collections.addAll(t, tokens.split(" "));
            return AdBlock.tokenCss(t, pageLevel);
        }
        @JavascriptInterface public void report(String url, String kind, String how, String frame) { consider(url, kind, how == null ? "" : how); }
        @JavascriptInterface public void signal(String what, String frame) {
            if ("focusAddress".equals(what)) { ui.post(() -> { address.requestFocus(); ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(address, 0); }); return; }
            if ("mse".equals(what)) mse = true;
            if ("playing".equals(what)) playing = true;
            ui.post(BrowserActivity.this::refreshFab);
        }
    }

    // ------------------------------------------------------------------ overlay panels (HTML)

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void ensureOverlay() {
        if (overlay != null) return;
        overlay = new WebView(this);
        overlay.setBackgroundColor(Color.TRANSPARENT);
        overlay.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings s = overlay.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        overlay.addJavascriptInterface(new Bridge(this), "Vidra");
        overlay.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return !"file".equals(r.getUrl().getScheme()); }
        });
        overlay.setVisibility(View.GONE);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.loadUrl("file:///android_asset/app/panel.html");
    }

    private void openPanel(String which) {
        ensureOverlay();
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
        overlayOpen = true;
        if (address.hasFocus()) { address.clearFocus(); ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(), 0); }
        if (!overlayReady) { pendingPanel = which; return; }
        sendPanel(which);
    }

    private static final String THUMB_JS = "(function(){try{var q=function(s){var m=document.querySelector(s);return m&&(m.content||m.href)||''};"
            + "var v=document.querySelector('video[poster]');return q('meta[property=\\'og:image\\']')||q('meta[name=\\'twitter:image\\']')||(v&&v.poster)||q('link[rel=\\'image_src\\']')||'';}catch(e){return ''}})()";

    private void sendPanel(final String which) {
        if (!"download".equals(which) || web == null) { sendPanelNow(which, ""); return; }
        // the page's own preview image, so the sheet shows the video instantly
        final boolean[] sent = {false};
        Runnable fallback = () -> { if (!sent[0]) { sent[0] = true; sendPanelNow(which, ""); } };
        ui.postDelayed(fallback, 250);
        try {
            web.evaluateJavascript(THUMB_JS, (v) -> {
                if (sent[0]) return;
                sent[0] = true; ui.removeCallbacks(fallback);
                String t = "";
                try { if (v != null && v.startsWith("\"")) t = new org.json.JSONArray("[" + v + "]").getString(0); } catch (Exception ignored) {}
                sendPanelNow(which, t.startsWith("http") ? t : "");
            });
        } catch (Exception e) { fallback.run(); }
    }

    private void sendPanelNow(String which, String thumb) {
        try {
            JSONObject o = pageJson().put("panel", which).put("toolbar", Math.round(toolbar.getBottom() / getResources().getDisplayMetrics().density));
            if (!thumb.isEmpty()) o.put("thumb", thumb);
            sendOverlay("open", o.toString());
        } catch (Exception ignored) {}
    }

    private JSONObject pageJson() throws Exception {
        return new JSONObject().put("pageUrl", pageUrl.startsWith("file:") ? "" : pageUrl).put("title", pageTitle).put("host", pageHost)
                .put("ua", web.getSettings().getUserAgentString()).put("candidates", candidates())
                .put("playing", playing).put("mse", mse).put("desktop", desktop).put("canForward", web.canGoForward())
                .put("fab", fab != null && fab.getVisibility() == View.VISIBLE).put("ad", adJson());
    }

    private void pushPage() { try { sendOverlay("page", pageJson().toString()); } catch (Exception ignored) {} }

    private void sendOverlay(String type, String json) {
        if (overlay == null) return;
        final String js = "window.VB&&VB.event(" + JSONObject.quote(type) + "," + json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")";
        ui.post(() -> { if (overlay != null) overlay.evaluateJavascript(js, null); });
    }

    // Bridge.Host
    @Override public Activity activity() { return this; }
    @Override public void send(String type, String json) { sendOverlay(type, json); }
    @Override public void resolve(String reqId, String json) {
        if (overlay == null) return;
        final String js = "window.VB&&VB.resolve(" + JSONObject.quote(reqId) + "," + json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + ")";
        ui.post(() -> { if (overlay != null) overlay.evaluateJavascript(js, null); });
    }
    @Override public void onPageReady() {
        overlayReady = true;
        if (pendingPanel != null) { sendPanel(pendingPanel); pendingPanel = null; }
    }

    @Override public void panel(String cmd, String json) {
        JSONObject a;
        try { a = json == null || json.isEmpty() ? new JSONObject() : new JSONObject(json); } catch (Exception e) { a = new JSONObject(); }
        switch (cmd) {
            case "close": overlayOpen = false; if (overlay != null) overlay.setVisibility(View.GONE); break;
            case "level": {
                int lvl = a.optInt("level", AdBlock.OPTIMAL);
                AdBlock.setLevel(a.optBoolean("all") ? "" : pageHost, lvl);
                if (a.optBoolean("all")) AdBlock.setLevel(pageHost, lvl);
                pageLevel = AdBlock.level(pageHost);
                refreshShield();
                if (a.optBoolean("reload", true) && !pageUrl.startsWith("file:")) { blockedPage = 0; web.reload(); }
                break;
            }
            case "reload": blockedPage = 0; web.reload(); break;
            case "forward": if (web.canGoForward()) web.goForward(); break;
            case "back": if (web.canGoBack()) web.goBack(); break;
            case "desktop": {
                desktop = !desktop;
                WebSettings s = web.getSettings();
                s.setUserAgentString(desktop ? "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chromeVersion() + " Safari/537.36" : startUa);
                web.reload();
                break;
            }
            case "share": if (!pageUrl.isEmpty()) { Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pageUrl); try { startActivity(Intent.createChooser(i, "Share page")); } catch (Exception ignored) {} } break;
            case "external": try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl))); } catch (Exception ignored) {} break;
            case "copy": { android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE); cm.setPrimaryClip(android.content.ClipData.newPlainText("link", pageUrl)); toast("Link copied"); break; }
            case "fab": {
                boolean on = a.optBoolean("on", true);
                prefs.edit().putBoolean("fab", on).apply();
                if (fab != null) fab.setVisibility(on ? View.VISIBLE : View.GONE);
                break;
            }
            case "downloads": startActivity(new Intent(this, MainActivity.class).putExtra("view", "downloads").setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)); break;
            case "home": web.loadUrl(startUrl()); break;
            case "go": go(a.optString("url")); break;
            case "clearData": {
                web.clearCache(true);
                android.webkit.WebStorage.getInstance().deleteAllData();
                toast("Cache and site data cleared");
                break;
            }
        }
    }

    private static String chromeVersion() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                android.content.pm.PackageInfo p = WebView.getCurrentWebViewPackage();
                if (p != null && p.versionName != null) return p.versionName.split(" ")[0];
            }
        } catch (Throwable ignored) {}
        return "124.0.0.0";
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && fileCb != null) { fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data)); fileCb = null; }
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (fullscreen != null) { web.evaluateJavascript("document.exitFullscreen&&document.exitFullscreen()", null); return; }
        if (suggest != null && suggest.getVisibility() == View.VISIBLE) { hideKeyboard(); address.clearFocus(); return; }
        if (overlayOpen && overlay != null) {
            overlay.evaluateJavascript("window.VB&&VB.back?VB.back():false", v -> { if (!"true".equals(v)) panel("close", null); });
            return;
        }
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onPause() { super.onPause(); CookieManager.getInstance().flush(); }

    @Override protected void onDestroy() {
        net.shutdownNow();
        Jobs.removeListener(jobListener);
        AdBlock.removeListener(adListener);
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        if (overlay != null) { overlay.destroy(); overlay = null; }
        super.onDestroy();
    }
}
