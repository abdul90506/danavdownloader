package com.dilhan.vidra;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

/**
 * Network + cosmetic ad blocking for Vidra's browser. Filter data = uBlock Origin Lite rulesets
 * (EasyList, EasyPrivacy, uBO filters, Peter Lowe, malware lists, AdGuard, annoyances), compiled by
 * tools/build_adblock.py.
 *
 * Levels (like uBOL):  0 Off · 1 Basic (network: ads, trackers, malware)
 *                      2 Optimal (+ element hiding, pop-up blocking, YouTube ad removal)
 *                      3 Complete (+ annoyances: cookie banners, overlays, social widgets, mobile ads)
 */
public final class AdBlock {
    public static final int OFF = 0, BASIC = 1, OPTIMAL = 2, COMPLETE = 3;
    private AdBlock() {}

    /** Domain rule: tier, third-party-only, excluded initiators. */
    private static final class Rule {
        final int tier; final boolean third; final String[] excl;
        Rule(int tier, boolean third, String[] excl) { this.tier = tier; this.third = third; this.excl = excl; }
    }

    private static final HashMap<String, Object> domains = new HashMap<>(140000);     // host -> Integer tier | Rule
    private static final HashMap<String, Integer> allow = new HashMap<>();
    private static final HashMap<String, List<String>> paths = new HashMap<>();        // host -> ["1/path", "3/path"]
    private static final ArrayList<String> subs = new ArrayList<>();                    // substrings
    private static final ArrayList<Integer> subTier = new ArrayList<>();
    private static volatile boolean netReady, css2Ready, css3Ready;
    private static final Object cssLock = new Object();
    private static final StringBuilder high2 = new StringBuilder(), high3 = new StringBuilder();
    private static final HashMap<String, String> low2 = new HashMap<>(), low3 = new HashMap<>();
    private static final HashMap<String, String> spec2 = new HashMap<>(), spec3 = new HashMap<>();
    private static SharedPreferences prefs;
    private static final AtomicLong total = new AtomicLong();
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static Context app;

    public static void init(Context c) {
        app = c.getApplicationContext();
        prefs = app.getSharedPreferences("adblock", Context.MODE_PRIVATE);
        total.set(prefs.getLong("total", 0));
        new Thread(() -> {
            try {
                loadNet("adblock/net1.txt.gz", 1);
                loadNet("adblock/net3.txt.gz", 3);
            } catch (Throwable ignored) {}
            netReady = true;
            if (defaultLevel() >= OPTIMAL) ensureCss(defaultLevel());
        }, "adblock-load").start();
    }

    public static boolean ready() { return netReady; }

    private static BufferedReader open(String asset) throws Exception {
        // aapt un-gzips "*.gz" assets and drops the extension at build time, so read the plain file when present
        java.io.InputStream in;
        try { in = app.getAssets().open(asset.replaceAll("\\.gz$", "")); }
        catch (java.io.IOException e) { in = new GZIPInputStream(app.getAssets().open(asset), 65536); }
        return new BufferedReader(new InputStreamReader(in, "UTF-8"), 65536);
    }

    private static void loadNet(String asset, int tier) throws Exception {
        try (BufferedReader r = open(asset)) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.length() < 3) continue;
                char t = l.charAt(0);
                String rest = l.substring(2);
                if (t == 'D') {
                    int sp = rest.indexOf(' ');
                    if (sp < 0) { if (!domains.containsKey(rest)) domains.put(rest, tier); continue; }
                    String host = rest.substring(0, sp);
                    boolean third = false; String[] excl = null;
                    for (String f : rest.substring(sp + 1).split(" ")) {
                        if (f.equals("t")) third = true;
                        else if (f.startsWith("x=")) excl = f.substring(2).split(",");
                    }
                    if (!domains.containsKey(host)) domains.put(host, new Rule(tier, third, excl));
                } else if (t == 'A') {
                    allow.put(rest, tier);
                } else if (t == 'P') {
                    int sp = rest.indexOf(' ');
                    if (sp < 0) continue;
                    String host = rest.substring(0, sp);
                    List<String> ps = paths.get(host);
                    if (ps == null) { ps = new ArrayList<>(2); paths.put(host, ps); }
                    ps.add(tier + rest.substring(sp + 1));
                } else if (t == 'S') {
                    subs.add(rest); subTier.add(tier);
                }
            }
        }
    }

    private static void loadCss(String asset, StringBuilder high, HashMap<String, String> low, HashMap<String, String> spec) throws Exception {
        try (BufferedReader r = open(asset)) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.length() < 3) continue;
                char t = l.charAt(0);
                if (t == 'H') { if (high.length() > 0) high.append('\n'); high.append(l, 2, l.length()); continue; }
                int tab = l.indexOf('\t');
                if (tab < 0) continue;
                String key = l.substring(2, tab);
                String sels = l.substring(tab + 1).replace('\t', '\n');
                if (t == 'L') low.put(key, sels); else if (t == 'S') spec.put(key, sels);
            }
        }
    }

    /** Loads the cosmetic data needed for a level (blocking; call off the UI thread). */
    static void ensureCss(int level) {
        if (level < OPTIMAL) return;
        synchronized (cssLock) {
            try {
                if (!css2Ready) { loadCss("adblock/css2.txt.gz", high2, low2, spec2); css2Ready = true; }
                if (level >= COMPLETE && !css3Ready) { loadCss("adblock/css3.txt.gz", high3, low3, spec3); css3Ready = true; }
            } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------------ levels

    public static int defaultLevel() { return prefs == null ? OPTIMAL : prefs.getInt("def", OPTIMAL); }

    public static int level(String host) {
        if (prefs == null) return OPTIMAL;
        if (host == null || host.isEmpty()) return defaultLevel();
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) h = h.substring(4);
        return prefs.getInt("site:" + h, defaultLevel());
    }

    public static boolean hasSiteLevel(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) h = h.substring(4);
        return prefs.contains("site:" + h);
    }

    /** host empty = change the default for every site. */
    public static void setLevel(String host, int level) {
        level = Math.max(OFF, Math.min(COMPLETE, level));
        if (host == null || host.isEmpty()) prefs.edit().putInt("def", level).apply();
        else {
            String h = host.toLowerCase(Locale.ROOT);
            if (h.startsWith("www.")) h = h.substring(4);
            if (level == defaultLevel()) prefs.edit().remove("site:" + h).apply();
            else prefs.edit().putInt("site:" + h, level).apply();
        }
        final int lv = level;
        if (lv >= OPTIMAL) new Thread(() -> ensureCss(lv), "adblock-css").start();
        fire();
    }

    public static int siteCount() {
        int n = 0;
        for (String k : prefs.getAll().keySet()) if (k.startsWith("site:")) n++;
        return n;
    }

    public static void resetSites() {
        SharedPreferences.Editor e = prefs.edit();
        for (String k : prefs.getAll().keySet()) if (k.startsWith("site:")) e.remove(k);
        e.apply();
        fire();
    }

    public static long totalBlocked() { return total.get(); }

    public static void resetStats() { total.set(0); prefs.edit().putLong("total", 0).apply(); fire(); }

    static void addListener(Runnable r) { listeners.add(r); }
    static void removeListener(Runnable r) { listeners.remove(r); }
    private static void fire() { for (Runnable r : listeners) try { r.run(); } catch (Throwable ignored) {} }

    private static long lastSave;
    static void countBlocked() {
        long n = total.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - lastSave > 3000) { lastSave = now; prefs.edit().putLong("total", n).apply(); }
    }

    public static JSONObject state(String host) {
        JSONObject o = new JSONObject();
        try {
            o.put("host", host == null ? "" : host).put("level", level(host)).put("def", defaultLevel())
                    .put("site", hasSiteLevel(host)).put("total", totalBlocked()).put("sites", siteCount())
                    .put("ready", netReady).put("rules", domains.size() + paths.size() + subs.size());
        } catch (Exception ignored) {}
        return o;
    }

    // ------------------------------------------------------------------ matching

    /** Registrable domain, approximately (handles co.uk / com.pk style suffixes). */
    static String site(String host) {
        if (host == null) return "";
        String[] p = host.split("\\.");
        int n = p.length;
        if (n <= 2) return host;
        String sld = p[n - 2];
        boolean two = p[n - 1].length() == 2 && (sld.equals("co") || sld.equals("com") || sld.equals("net") || sld.equals("org")
                || sld.equals("gov") || sld.equals("edu") || sld.equals("ac") || sld.equals("or") || sld.equals("ne") || sld.equals("go"));
        int k = two ? 3 : 2;
        StringBuilder b = new StringBuilder();
        for (int i = n - k; i < n; i++) { if (b.length() > 0) b.append('.'); b.append(p[i]); }
        return b.toString();
    }

    private static boolean suffixIn(String host, String[] list) {
        if (list == null || host == null) return false;
        for (String d : list) if (host.equals(d) || host.endsWith("." + d)) return true;
        return false;
    }

    /** Should this sub-resource request be blocked? Main-frame navigations are never blocked here. */
    public static boolean shouldBlock(Uri url, String pageHost, int level) {
        if (level <= OFF || !netReady || url == null) return false;
        String host = url.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        if (pageHost != null) pageHost = pageHost.toLowerCase(Locale.ROOT);
        // allow-list
        for (String h = host; h != null; h = parent(h)) {
            Integer a = allow.get(h);
            if (a != null && a <= level) return false;
        }
        boolean thirdParty = pageHost == null || !site(host).equals(site(pageHost));
        for (String h = host; h != null; h = parent(h)) {
            Object o = domains.get(h);
            if (o != null) {
                if (o instanceof Integer) { if ((Integer) o <= level) return true; }
                else {
                    Rule r = (Rule) o;
                    if (r.tier <= level && (!r.third || thirdParty) && !suffixIn(pageHost, r.excl)) return true;
                }
            }
            List<String> ps = paths.get(h);
            if (ps != null) {
                String path = url.getEncodedPath();
                if (path != null) {
                    String pl = path.toLowerCase(Locale.ROOT);
                    for (String p : ps) if (p.charAt(0) - '0' <= level && pl.startsWith(p.substring(1))) return true;
                }
            }
        }
        String s = url.toString().toLowerCase(Locale.ROOT);
        for (int i = 0, n = subs.size(); i < n; i++) {
            if (subTier.get(i) <= level && s.contains(subs.get(i))) return true;
        }
        return false;
    }

    /** Pop-up / redirect target that belongs to a blocked (ad, malware) domain. */
    public static boolean isBadHost(String host, int level) {
        if (host == null || level <= OFF || !netReady) return false;
        host = host.toLowerCase(Locale.ROOT);
        for (String h = host; h != null; h = parent(h)) {
            Object o = domains.get(h);
            if (o instanceof Integer && (Integer) o <= level) return true;
            if (o instanceof Rule && ((Rule) o).tier <= level && ((Rule) o).excl == null) return true;
        }
        return false;
    }

    private static String parent(String h) {
        int i = h.indexOf('.');
        if (i < 0) return null;
        String p = h.substring(i + 1);
        return p.indexOf('.') < 0 ? null : p;   // stop before the bare TLD
    }

    // ------------------------------------------------------------------ cosmetic

    /** Selectors (one per line) always hidden on this host: generic (high) + host specific. */
    public static String pageCss(String host, int level) {
        if (level < OPTIMAL) return "";
        ensureCss(level);
        StringBuilder sb = new StringBuilder();
        append(sb, high2);
        if (level >= COMPLETE) append(sb, high3);
        if (host != null) {
            String h = host.toLowerCase(Locale.ROOT);
            for (String x = h; x != null; x = parentAny(x)) {
                appendS(sb, spec2.get(x));
                if (level >= COMPLETE) appendS(sb, spec3.get(x));
            }
        }
        return sb.toString();
    }

    /** Selectors for the given "#id" / ".class" tokens found in the page. */
    public static String tokenCss(Collection<String> tokens, int level) {
        if (level < OPTIMAL || !css2Ready) return "";
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            appendS(sb, low2.get(t));
            if (level >= COMPLETE) appendS(sb, low3.get(t));
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, CharSequence s) { if (s.length() == 0) return; if (sb.length() > 0) sb.append('\n'); sb.append(s); }
    private static void appendS(StringBuilder sb, String s) { if (s == null || s.isEmpty()) return; if (sb.length() > 0) sb.append('\n'); sb.append(s); }
    private static String parentAny(String h) { int i = h.indexOf('.'); return i < 0 ? null : h.substring(i + 1); }
}
