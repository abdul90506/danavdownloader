package com.dilhan.vidra;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Sites the user signed in to (or browsed) inside Vidra's browser keep their cookies in the
 * WebView cookie store. yt-dlp needs them as a Netscape cookies.txt, so we export them before
 * every run. Nothing leaves the device.
 */
public final class Cookies {
    private Cookies() {}

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("cookies", 0); }

    public static void rememberHost(Context c, String host) {
        if (host == null || host.isEmpty()) return;
        Set<String> s = new HashSet<>(prefs(c).getStringSet("hosts", new HashSet<>()));
        if (s.add(host)) prefs(c).edit().putStringSet("hosts", s).apply();
    }

    /** Hosts that currently have cookies. */
    public static Set<String> hosts(Context c) {
        Set<String> out = new HashSet<>();
        CookieManager cm;
        try { cm = CookieManager.getInstance(); } catch (Throwable e) { return out; }
        for (String h : prefs(c).getStringSet("hosts", new HashSet<>())) {
            String ck = cm.getCookie("https://" + h);
            if (ck != null && !ck.trim().isEmpty()) out.add(baseDomain(h));
        }
        return out;
    }

    public static void clearAll(Context c) {
        try { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
        prefs(c).edit().remove("hosts").apply();
        new File(c.getFilesDir(), "cookies.txt").delete();
    }

    static String baseDomain(String host) {
        String h = host.toLowerCase();
        for (String p : new String[]{"www.", "m.", "mobile.", "web."}) if (h.startsWith(p)) { h = h.substring(p.length()); break; }
        return h;
    }

    /** Writes cookies.txt and returns it, or null when there are no cookies. */
    public static synchronized File export(Context c) {
        try {
            CookieManager cm = CookieManager.getInstance();
            StringBuilder sb = new StringBuilder("# Netscape HTTP Cookie File\n");
            int n = 0;
            long exp = System.currentTimeMillis() / 1000 + 180L * 24 * 3600;
            Set<String> done = new HashSet<>();
            for (String h : prefs(c).getStringSet("hosts", new HashSet<>())) {
                String ck = cm.getCookie("https://" + h);
                if (ck == null) continue;
                String dom = "." + baseDomain(h);
                for (String part : ck.split(";")) {
                    int eq = part.indexOf('=');
                    if (eq <= 0) continue;
                    String name = part.substring(0, eq).trim(), val = part.substring(eq + 1).trim();
                    if (name.isEmpty() || !done.add(dom + "\t" + name)) continue;
                    sb.append(dom).append("\tTRUE\t/\tTRUE\t").append(exp).append('\t').append(name).append('\t').append(val).append('\n');
                    n++;
                }
            }
            File f = new File(c.getFilesDir(), "cookies.txt");
            if (n == 0) { f.delete(); return null; }
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) { w.write(sb.toString()); }
            return f;
        } catch (Throwable e) {
            return null;
        }
    }
}
