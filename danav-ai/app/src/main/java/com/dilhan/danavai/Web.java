package com.dilhan.danavai;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Danav AI web tools: a keyless web search (several engines, first one that answers wins) and a page reader that
 * turns any article into clean text for the model.
 */
final class Web {
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36";
    private static final String UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36";
    private static final Map<String, JSONArray> searchCache = new LinkedHashMap<String, JSONArray>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, JSONArray> e) { return size() > 40; }
    };
    private static final Map<String, Long> searchTime = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long braveRestUntil, ddgRestUntil;

    // ------------------------------------------------------------------ search

    /** [{title,url,snippet,site}] — empty when every engine failed. */
    static JSONArray search(String query, int max) {
        String key = query.trim().toLowerCase(Locale.ROOT);
        synchronized (searchCache) {
            JSONArray c = searchCache.get(key);
            Long t = searchTime.get(key);
            if (c != null && t != null && System.currentTimeMillis() - t < 10 * 60_000) return c;
        }
        JSONArray r = new JSONArray();
        long now = System.currentTimeMillis();
        if (now > braveRestUntil) { r = brave(query, max); if (r.length() == 0) braveRestUntil = now + 90_000; }
        if (r.length() == 0 && now > ddgRestUntil) { r = ddg(query, max); if (r.length() == 0) ddgRestUntil = now + 90_000; }
        if (r.length() == 0) r = bing(query, max);
        if (r.length() == 0 && now <= braveRestUntil) r = brave(query, max);     // all rested: one more honest try
        if (r.length() > 0) synchronized (searchCache) { searchCache.put(key, r); searchTime.put(key, System.currentTimeMillis()); }
        return r;
    }

    private static JSONArray brave(String q, int max) {
        JSONArray out = new JSONArray();
        try {
            String html = get("https://search.brave.com/search?q=" + enc(q) + "&source=web", UA, 9000, 3_000_000);
            if (html == null) return out;
            String[] chunks = html.split("data-type=\"web\"");
            Pattern pu = Pattern.compile("<a href=\"(https?://[^\"]+)\""), pt = Pattern.compile("search-snippet-title[^\"]*\"[^>]*?title=\"([^\"]*)\""),
                    pd = Pattern.compile("class=\"content [^\"]*\"[^>]*>(.*?)</div>", Pattern.DOTALL), pn = Pattern.compile("class=\"desktop-small-semibold[^\"]*\">(.*?)</div>", Pattern.DOTALL);
            for (int i = 1; i < chunks.length && out.length() < max; i++) {
                String ch = chunks[i];
                Matcher u = pu.matcher(ch);
                if (!u.find()) continue;
                String url = unesc(u.group(1));
                if (url.contains("brave.com/")) continue;
                Matcher t = pt.matcher(ch), d = pd.matcher(ch), n = pn.matcher(ch);
                add(out, t.find() ? unesc(t.group(1)) : "", url, d.find() ? text(d.group(1)) : "", n.find() ? text(n.group(1)) : "");
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONArray ddg(String q, int max) {
        JSONArray out = new JSONArray();
        try {
            String html = get("https://html.duckduckgo.com/html/?q=" + enc(q), UA, 9000, 2_000_000);
            if (html == null) return out;
            Matcher m = Pattern.compile("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>(.*?)(?=class=\"result__a\"|$)", Pattern.DOTALL).matcher(html);
            Pattern ps = Pattern.compile("class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);
            while (m.find() && out.length() < max) {
                String href = unesc(m.group(1));
                if (href.startsWith("//")) href = "https:" + href;
                Uri hu = Uri.parse(href);
                String real = hu.getQueryParameter("uddg");
                if (real != null) href = real;
                if (href.contains("duckduckgo.com/y.js")) continue;          // ads
                Matcher s = ps.matcher(m.group(3));
                add(out, text(m.group(2)), href, s.find() ? text(s.group(1)) : "", "");
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONArray bing(String q, int max) {
        JSONArray out = new JSONArray();
        try {
            String html = get("https://www.bing.com/search?q=" + enc(q) + "&setlang=en", UA_DESKTOP, 9000, 3_000_000);
            if (html == null) return out;
            String[] chunks = html.split("class=\"b_algo\"");
            Pattern ph = Pattern.compile("<h2[^>]*>(.*?)</h2>", Pattern.DOTALL), pa = Pattern.compile("href=\"(https?://[^\"]+)\""), pp = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL);
            for (int i = 1; i < chunks.length && out.length() < max; i++) {
                String ch = chunks[i];
                Matcher h = ph.matcher(ch), a = pa.matcher(ch), p = pp.matcher(ch);
                if (!h.find() || !a.find()) continue;
                String url = unesc(a.group(1));
                if (url.contains("bing.com/ck/a")) {
                    String u = Uri.parse(url).getQueryParameter("u");
                    if (u != null && u.startsWith("a1")) {
                        try { url = new String(android.util.Base64.decode(u.substring(2), android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP), "UTF-8"); } catch (Exception e) { continue; }
                    } else continue;
                }
                add(out, text(h.group(1)), url, p.find() ? text(p.group(1)) : "", "");
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void add(JSONArray out, String title, String url, String snippet, String site) {
        try {
            if (!url.startsWith("http")) return;
            for (int i = 0; i < out.length(); i++) if (url.equals(out.getJSONObject(i).optString("url"))) return;
            String host = Uri.parse(url).getHost();
            if (host == null) return;
            if (host.startsWith("www.")) host = host.substring(4);
            out.put(new JSONObject().put("title", title.isEmpty() ? host : title).put("url", url)
                    .put("snippet", snippet.length() > 320 ? snippet.substring(0, 320) + "…" : snippet).put("site", site.isEmpty() ? host : site).put("host", host));
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ read a page

    /** {url,title,text} or {url,error}. */
    static JSONObject read(String url, int maxChars) {
        JSONObject o = new JSONObject();
        try {
            o.put("url", url);
            if (!url.matches("(?i)https?://.+")) return o.put("error", "Not a web address");
            HttpURLConnection c = open(url, UA, 12000);
            int code = c.getResponseCode();
            if (code >= 400) { c.disconnect(); return o.put("error", "The page answered with HTTP " + code); }
            String type = String.valueOf(c.getContentType()).toLowerCase(Locale.ROOT);
            String finalUrl = c.getURL().toString();
            o.put("url", finalUrl);
            if (type.contains("pdf")) { c.disconnect(); return o.put("error", "This link is a PDF file, which can't be read here"); }
            if (!(type.contains("html") || type.contains("text") || type.contains("xml") || type.contains("json") || type.equals("null"))) { c.disconnect(); return o.put("error", "Not a readable page (" + type + ")"); }
            byte[] raw = body(c, 2_500_000);
            String cs = charset(type, raw);
            String html = new String(raw, cs);
            if (type.contains("json") || type.contains("text/plain")) {
                String t = html.length() > maxChars ? html.substring(0, maxChars) : html;
                return o.put("title", Uri.parse(finalUrl).getHost()).put("text", t);
            }
            String title = "";
            Matcher tm = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(html);
            if (tm.find()) title = text(tm.group(1));
            Matcher og = Pattern.compile("<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)", Pattern.CASE_INSENSITIVE).matcher(html);
            if (og.find()) title = unesc(og.group(1));
            String t = readable(html);
            if (t.length() < 200) return o.put("title", title).put("text", t).put("note", "The page has very little text (it may need JavaScript or a sign-in).");
            if (t.length() > maxChars) t = t.substring(0, maxChars) + "\n…(page continues)";
            return o.put("title", title).put("text", t);
        } catch (Exception e) {
            try { o.put("error", "Couldn't open the page"); } catch (Exception ignored) {}
            return o;
        }
    }

    /** Main text of an HTML page: drops scripts, menus, footers and other chrome; keeps headings, paragraphs, lists and tables as plain lines. */
    static String readable(String html) {
        String s = html;
        s = s.replaceAll("(?is)<(script|style|noscript|svg|template|iframe|canvas|form|select|button)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?is)<!--.*?-->", " ");
        // prefer <article> or <main> when the page has one
        Matcher am = Pattern.compile("(?is)<article[^>]*>(.*)</article>").matcher(s);
        if (am.find() && am.group(1).length() > 1500) s = am.group(1);
        else {
            Matcher mm = Pattern.compile("(?is)<main[^>]*>(.*)</main>").matcher(s);
            if (mm.find() && mm.group(1).length() > 1500) s = mm.group(1);
        }
        s = s.replaceAll("(?is)<(nav|footer|aside|header)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<h([1-6])[^>]*>", "\n\n## ").replaceAll("(?i)</h[1-6]>", "\n");
        s = s.replaceAll("(?i)<li[^>]*>", "\n- ").replaceAll("(?i)<(br|hr)\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|section|tr|table|ul|ol|blockquote|pre|figure)>", "\n");
        s = s.replaceAll("(?i)<t[dh][^>]*>", " | ");
        s = s.replaceAll("(?s)<[^>]+>", " ");
        s = unesc(s);
        s = s.replace('\u00a0', ' ').replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n");
        // drop very short noisy lines (menus, share buttons) but keep headings / list items
        StringBuilder b = new StringBuilder();
        for (String line : s.split("\n")) {
            String l = line.trim();
            if (l.isEmpty()) { if (b.length() > 0 && b.charAt(b.length() - 1) != '\n') b.append('\n'); continue; }
            if (l.length() < 3 && !l.startsWith("#")) continue;
            b.append(l).append('\n');
        }
        return b.toString().trim();
    }

    // ------------------------------------------------------------------ http helpers

    private static HttpURLConnection open(String url, String ua, int timeout) throws Exception {
        HttpURLConnection c = null;
        for (int hop = 0; hop < 6; hop++) {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag() + ",en;q=0.8");
            c.setRequestProperty("Accept-Encoding", "gzip");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            return c;
        }
        return c;
    }

    private static String get(String url, String ua, int timeout, int max) {
        HttpURLConnection c = null;
        try {
            c = open(url, ua, timeout);
            if (c.getResponseCode() != 200) return null;
            byte[] raw = body(c, max);
            return new String(raw, charset(String.valueOf(c.getContentType()), raw));
        } catch (Exception e) { return null; }
        finally { if (c != null) c.disconnect(); }
    }

    private static byte[] body(HttpURLConnection c, int max) throws Exception {
        InputStream in = c.getInputStream();
        if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        try (InputStream is = in) { while ((n = is.read(buf)) > 0 && out.size() < max) out.write(buf, 0, n); }
        return out.toByteArray();
    }

    private static String charset(String type, byte[] raw) {
        Matcher m = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(type == null ? "" : type);
        String cs = m.find() ? m.group(1) : null;
        if (cs == null) {
            String head = new String(raw, 0, Math.min(raw.length, 3000), Charset.forName("ISO-8859-1"));
            Matcher mm = Pattern.compile("<meta[^>]+charset=[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(head);
            cs = mm.find() ? mm.group(1) : "UTF-8";
        }
        try { return Charset.isSupported(cs) ? cs : "UTF-8"; } catch (Exception e) { return "UTF-8"; }
    }

    private static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }

    private static String text(String html) { return unesc(html.replaceAll("(?s)<[^>]+>", "")).replaceAll("\\s+", " ").trim(); }

    private static final Map<String, String> ENT = new java.util.HashMap<>();
    static {
        String[] e = {"amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'", "nbsp", " ", "mdash", "\u2014", "ndash", "\u2013", "hellip", "\u2026",
                "rsquo", "\u2019", "lsquo", "\u2018", "ldquo", "\u201c", "rdquo", "\u201d", "copy", "\u00a9", "reg", "\u00ae", "trade", "\u2122",
                "laquo", "\u00ab", "raquo", "\u00bb", "bull", "\u2022", "middot", "\u00b7", "times", "\u00d7", "deg", "\u00b0", "euro", "\u20ac", "pound", "\u00a3"};
        for (int i = 0; i < e.length; i += 2) ENT.put(e[i], e[i + 1]);
    }
    private static final Pattern ENT_RE = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);");

    static String unesc(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = ENT_RE.matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String g = m.group(1), r;
            try {
                if (g.startsWith("#x")) r = new String(Character.toChars(Integer.parseInt(g.substring(2), 16)));
                else if (g.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(g.substring(1))));
                else r = ENT.containsKey(g) ? ENT.get(g) : m.group();
            } catch (Exception e) { r = m.group(); }
            m.appendReplacement(b, Matcher.quoteReplacement(r));
        }
        m.appendTail(b);
        return b.toString();
    }
}
