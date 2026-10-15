package com.dilhan.vidra;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fast preview of a link: title + thumbnail in well under a second, read from the page's
 * Open Graph tags (what chat apps use for link previews). yt-dlp keeps reading the real formats.
 */
final class Peek {
    private Peek() {}

    private static final Pattern YT = Pattern.compile("(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/|live/|v/)|youtu\\.be/)([\\w-]{11})");
    private static final Pattern META = Pattern.compile("<meta\\s[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z:_-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>([^<]{1,300})</title>", Pattern.CASE_INSENSITIVE);

    static String get(String url) {
        JSONObject o = new JSONObject();
        try {
            o.put("url", url);
            Matcher y = url == null ? null : YT.matcher(url);
            if (y != null && y.find()) {
                String id = y.group(1);
                o.put("thumb", "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg");
                try {
                    String j = fetch("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode("https://www.youtube.com/watch?v=" + id, "UTF-8"), 64 * 1024, false);
                    JSONObject e = new JSONObject(j);
                    o.put("title", e.optString("title", "")).put("author", e.optString("author_name", ""));
                } catch (Exception ignored) {}
                return o.put("ok", true).toString();
            }
            String html = fetch(url, 384 * 1024, true);
            String title = "", image = "", site = "";
            Matcher m = META.matcher(html);
            while (m.find()) {
                String tag = m.group(), name = "", content = null;
                Matcher a = ATTR.matcher(tag);
                while (a.find()) {
                    String k = a.group(1).toLowerCase(), v = a.group(3) != null ? a.group(3) : a.group(4);
                    if (k.equals("property") || k.equals("name") || k.equals("itemprop")) name = v.toLowerCase();
                    else if (k.equals("content")) content = v;
                }
                if (content == null || content.isEmpty()) continue;
                switch (name) {
                    case "og:title": title = content; break;
                    case "twitter:title": if (title.isEmpty()) title = content; break;
                    case "og:image": case "og:image:url": case "og:image:secure_url": if (image.isEmpty()) image = content; break;
                    case "twitter:image": case "twitter:image:src": case "thumbnailurl": if (image.isEmpty()) image = content; break;
                    case "og:site_name": site = content; break;
                    default:
                }
            }
            if (title.isEmpty()) { Matcher t = TITLE.matcher(html); if (t.find()) title = t.group(1).trim(); }
            if (!image.isEmpty()) {
                image = unescape(image);
                if (image.startsWith("//")) image = "https:" + image;
                else if (image.startsWith("/")) { URL u = new URL(url); image = u.getProtocol() + "://" + u.getHost() + image; }
                if (image.startsWith("http")) o.put("thumb", image);
            }
            if (!title.isEmpty()) o.put("title", unescape(title));
            if (!site.isEmpty()) o.put("author", unescape(site));
            return o.put("ok", o.has("title") || o.has("thumb")).toString();
        } catch (Throwable e) {
            try { return o.put("ok", false).toString(); } catch (Exception x) { return "{\"ok\":false}"; }
        }
    }

    /** Reads at most `max` bytes, stopping early after </head> (meta tags live there). */
    private static String fetch(String url, int max, boolean page) throws Exception {
        HttpURLConnection c = null;
        try {
            for (int hop = 0; hop < 5; hop++) {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setInstanceFollowRedirects(false);
                // link-preview crawlers get the Open Graph tags even on sites that need JavaScript for people
                c.setRequestProperty("User-Agent", page ? "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)" : "Mozilla/5.0");
                c.setRequestProperty("Accept", page ? "text/html,application/xhtml+xml" : "*/*");
                c.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null) throw new Exception("redirect");
                    url = new URL(new URL(url), loc).toString();
                    continue;
                }
                if (code >= 400) throw new Exception("http " + code);
                String ct = c.getContentType();
                if (page && ct != null && !ct.contains("html") && !ct.contains("xml")) throw new Exception("not a page");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] b = new byte[16384];
                    int n;
                    while (bo.size() < max && (n = in.read(b)) > 0) {
                        bo.write(b, 0, n);
                        if (page && bo.size() > 4096) {
                            String tail = new String(b, 0, n, StandardCharsets.ISO_8859_1).toLowerCase();
                            if (tail.contains("</head>")) break;
                        }
                    }
                }
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
            throw new Exception("too many redirects");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String unescape(String s) {
        s = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int cp;
            try { cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16); } catch (Exception e) { cp = '?'; }
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return s.isEmpty() ? s : sb.toString().trim();
    }
}
