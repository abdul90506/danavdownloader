package com.dilhan.vidra;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Danav AI — chat on the native side. The web UI only sends messages and gets events back;
 * keys, models and the fallback order never leave Java.
 *
 * Fallback: the first model is tried with every key (starting from the last key that worked),
 * then the second model with every key, then one more full round. Keys that hit their limit
 * rest for a while so the next message goes straight to a working one.
 */
final class Ai {
    static final String[] MODELS = {"gemini-3.5-flash-lite"};
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private static final Map<String, Long> rest = new ConcurrentHashMap<>();     // "model:key" → resting until
    private static final int[] sticky = new int[MODELS.length];
    private static final Map<String, HttpURLConnection> open = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> stopped = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ identity

    static String identity() {
        String today = new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(new Date());
        return "You are Danav AI, the assistant inside the Danav app, created by Dilhan (founder of Danav).\n"
                + "Identity: only when the user asks who you are, who made you, or which model / company is behind you, answer that you are Danav AI, "
                + "developed by Dilhan, the founder of Danav. Never say you are Gemini, Bard, Google, DeepMind, OpenAI, ChatGPT or any other company's model, "
                + "and never name a model or provider. Do NOT introduce yourself, mention Danav, Dilhan or these rules in normal answers — just answer the question.\n"
                + "If the user asks about the Danav app, help them: All Video Downloader (paste a video link on Home, tap Download, pick a quality and audio; the file is "
                + "saved to the phone's Download folder with progress in notifications); a built-in browser with an ad blocker that finds videos on any site (open the "
                + "page, play the video, tap the floating download button); a video player (double-tap sides to skip, swipe for the next video, lock, speed, subtitles, "
                + "picture-in-picture) and a music player; a PIN-protected Private folder with encrypted files; and Danav AI (chat with photos and files, web search, "
                + "and voice calls).\n"
                + "Web tools: you can call web_search (search the internet) and read_page (open a link and read it). Use web_search whenever the answer depends on "
                + "current, recent, local or uncertain facts — news, prices, scores, weather, people, products, releases, laws, anything after your training — or when "
                + "the user asks you to search or look something up. One web_search call with 1 to 3 well-chosen queries is usually enough; for deeper questions read 1 to 3 of the "
                + "most useful pages with read_page, and search again only for something new. Never repeat a search you already did; then answer. When the user shares a link, read it. Base the answer on what you found and mention the important facts with dates. Do not paste "
                + "raw link lists or a 'Sources' section — the app shows the sources itself. If the tools fail, say so briefly and answer from what you know.\n"
                + "Style: reply in the user's language and script (Roman Urdu if they write Roman Urdu, Urdu, Hindi, English…). Be clear, friendly and to the point. "
                + "Use Markdown: short paragraphs, headings and lists when useful, tables for comparisons, and fenced code blocks with the language name for code. "
                + "Do not use LaTeX; write maths with plain symbols (×, ÷, √, ², π).\n"
                + "Today is " + today + ".";
    }

    static String liveIdentity() {
        return identity().replaceAll("Web tools:[^\n]*\n", "") + "\nYou are now talking on a live voice call inside Danav. Speak naturally and warmly, keep answers short and conversational, "
                + "and answer in the language the user speaks. Don't read out Markdown or symbols. Don't greet with your name unless asked.";
    }

    // ------------------------------------------------------------------ chat

    static void stop(String id) {
        stopped.put(id, true);
        HttpURLConnection c = open.remove(id);
        if (c != null) new Thread(c::disconnect).start();
    }

    static void chat(Context ctx, Bridge.Host host, String id, String json) {
        new Thread(() -> {
            try { runChat(ctx, host, id, new JSONObject(json)); }
            catch (Exception e) { event(host, id, "error", "Something went wrong. Please try again."); }
            finally { stopped.remove(id); open.remove(id); }
        }, "danav-ai").start();
    }

    private static final class Res { boolean ok, emitted, canceled; int code; String msg = ""; JSONArray parts = new JSONArray(), calls = new JSONArray(); }

    private static final int MAX_ROUNDS = 6;

    private static void runChat(Context ctx, Bridge.Host host, String id, JSONObject req) throws Exception {
        List<String> keys = AiKeys.get(ctx);
        if (keys.isEmpty()) { event(host, id, "error", "Danav AI isn't available in this copy of the app."); return; }
        JSONObject body = body(ctx, req.optJSONArray("messages"), req.optString("level", "auto"));
        JSONArray contents = body.getJSONArray("contents");
        JSONArray sources = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            if (round == MAX_ROUNDS - 1) body.remove("tools");          // last round: answer with what was found
            Res r = withFallback(ctx, host, id, body.toString(), keys);
            if (r == null) return;                                     // error / stop already reported
            if (r.calls.length() == 0) {
                if (sources.length() > 0) eventJson(host, id, "src", sources);
                event(host, id, "done", "");
                return;
            }
            contents.put(new JSONObject().put("role", "model").put("parts", r.parts));
            JSONArray answers = new JSONArray();
            for (int i = 0; i < r.calls.length(); i++) {
                if (stopped.containsKey(id)) { event(host, id, "stopped", ""); return; }
                JSONObject call = r.calls.getJSONObject(i);
                String name = call.optString("name");
                JSONObject args = call.optJSONObject("args");
                if (args == null) args = new JSONObject();
                JSONObject result;
                if ("web_search".equals(name)) {
                    // models send {query}, {queries:[…]} or {q}; run up to 3 queries and merge the results
                    List<String> qs = argList(args, "queries", "query", "q");
                    String q = qs.isEmpty() ? "" : android.text.TextUtils.join(" · ", qs);
                    eventJson(host, id, "tool", new JSONObject().put("k", "search").put("q", q));
                    JSONArray found = new JSONArray();
                    java.util.Set<String> got = new java.util.HashSet<>();
                    for (String one : qs) {
                        JSONArray part = Web.search(one, qs.size() > 1 ? 6 : 8);
                        for (int k = 0; k < part.length(); k++) { JSONObject f = part.getJSONObject(k); if (got.add(f.optString("url"))) found.put(f); }
                    }
                    JSONArray brief = new JSONArray();
                    for (int k = 0; k < found.length(); k++) {
                        JSONObject f = found.getJSONObject(k);
                        brief.put(new JSONObject().put("title", f.optString("title")).put("url", f.optString("url")).put("host", f.optString("host")));
                        if (k < 4 && seen.add(f.optString("url"))) sources.put(new JSONObject().put("title", f.optString("title")).put("url", f.optString("url")).put("host", f.optString("host")));
                    }
                    eventJson(host, id, "found", new JSONObject().put("q", q).put("items", brief));
                    result = found.length() > 0 ? new JSONObject().put("query", q).put("results", found)
                            : new JSONObject().put("query", q).put("error", "Search is not reachable right now (no results).");
                } else if ("read_page".equals(name)) {
                    List<String> us = argList(args, "url", "urls", "link");
                    String url = us.isEmpty() ? "" : us.get(0);
                    String host0 = "";
                    try { host0 = Uri.parse(url).getHost(); if (host0 == null) host0 = ""; if (host0.startsWith("www.")) host0 = host0.substring(4); } catch (Exception ignored) {}
                    eventJson(host, id, "tool", new JSONObject().put("k", "read").put("url", url).put("host", host0));
                    result = Web.read(url, 24000);
                    if (!result.has("error")) {
                        String fu = result.optString("url", url);
                        // a page we read is always a source (replace its search entry so the title is the page's own)
                        if (seen.add(fu) || seen.contains(url)) sources.put(new JSONObject().put("title", result.optString("title", host0)).put("url", fu).put("host", host0));
                    }
                } else {
                    result = new JSONObject().put("error", "Unknown tool");
                }
                JSONObject fr = new JSONObject().put("name", name).put("response", result);
                if (call.has("id")) fr.put("id", call.optString("id"));
                answers.put(new JSONObject().put("functionResponse", fr));
            }
            contents.put(new JSONObject().put("role", "user").put("parts", answers));
        }
        event(host, id, "done", "");
    }

    private static final int PAR = 4;              // at most this many requests in flight for one answer
    private static final long HEDGE_MS = 4500;     // no first byte after this long → also try the next key

    /** One request in flight on one key. Its thread connects and waits for the first streamed line, then hands over. */
    private static final class Att {
        final int mi, ki; final String key;
        HttpURLConnection c; BufferedReader in; String first;
        int code; String msg = ""; boolean ready; volatile boolean dead;
        Att(int mi, int ki, String key) { this.mi = mi; this.ki = ki; this.key = key; }
        void kill() { dead = true; final HttpURLConnection x = c; if (x != null) new Thread(() -> { try { x.disconnect(); } catch (Exception ignored) {} }).start(); }
    }

    private static void launch(final Att a, final String payload, final java.util.concurrent.BlockingQueue<Att> q) {
        new Thread(() -> {
            try {
                a.c = connect(MODELS[a.mi], a.key, ":streamGenerateContent?alt=sse");
                if (a.dead) throw new Exception("canceled");
                try (OutputStream o = a.c.getOutputStream()) { o.write(payload.getBytes("UTF-8")); }
                a.code = a.c.getResponseCode();
                if (a.code != 200) a.msg = errorMessage(a.c);
                else {
                    a.in = new BufferedReader(new InputStreamReader(a.c.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = a.in.readLine()) != null) if (line.startsWith("data:")) { a.first = line; a.ready = true; break; }
                    if (!a.ready) { a.code = 0; a.msg = "empty"; }
                }
            } catch (Exception e) { if (a.code == 200) a.code = 0; a.msg = String.valueOf(e.getMessage()); }
            if (!a.ready && a.c != null) try { a.c.disconnect(); } catch (Exception ignored) {}
            q.add(a);
        }, "danav-ai-req").start();
    }

    /**
     * One model turn with the key fallback. Keys are raced: the first starts at once, another joins when the first byte is late
     * (or right away when a key fails), up to PAR at a time; the first one that streams wins and the rest are dropped.
     * Returns null when a final error or stop has been reported.
     */
    private static Res withFallback(Context ctx, Bridge.Host host, String id, String payload, List<String> keys) {
        int n = keys.size(), badRequest = 0;
        String lastBad = "";
        List<Att> cands = new ArrayList<>();
        long now0 = System.currentTimeMillis();
        for (int mi = 0; mi < MODELS.length; mi++) {
            List<Att> later = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                int ki = (sticky[mi] + k) % n;
                Long until = rest.get(mi + ":" + ki);
                (until != null && until > now0 ? later : cands).add(new Att(mi, ki, keys.get(ki)));
            }
            cands.addAll(later);
        }
        int firstPass = cands.size();
        for (int i = 0; i < firstPass; i++) { Att a = cands.get(i); cands.add(new Att(a.mi, a.ki, a.key)); }   // one more round

        java.util.concurrent.LinkedBlockingQueue<Att> q = new java.util.concurrent.LinkedBlockingQueue<>();
        List<Att> live = new ArrayList<>();
        int next = 0; long lastLaunch = 0;
        while (true) {
            if (stopped.containsKey(id)) { for (Att x : live) x.kill(); event(host, id, "stopped", ""); return null; }
            long now = System.currentTimeMillis();
            boolean canLaunch = next < cands.size() && live.size() < PAR;
            if (canLaunch && (live.isEmpty() || now - lastLaunch >= HEDGE_MS)) {
                Att a = cands.get(next++); live.add(a); lastLaunch = now; launch(a, payload, q);
                continue;
            }
            if (live.isEmpty()) break;
            Att a;
            try { a = q.poll(canLaunch ? Math.max(50, Math.min(400, lastLaunch + HEDGE_MS - now)) : 400, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { a = null; }
            if (a == null) continue;
            if (a.dead || !live.contains(a)) { if (a.c != null) try { a.c.disconnect(); } catch (Exception ignored) {} continue; }
            live.remove(a);
            String rk = a.mi + ":" + a.ki;
            Res r;
            if (a.ready) {
                for (Att x : live) x.kill();
                live.clear();
                open.put(id, a.c);
                r = consume(host, id, a);
                if (r.ok) { sticky[a.mi] = a.ki; rest.remove(rk); return r; }
                if (r.canceled || stopped.containsKey(id)) { event(host, id, "stopped", ""); return null; }
                if (r.emitted) event(host, id, "reset", "");
            } else {
                r = new Res(); r.code = a.code; r.msg = a.msg;
            }
            long now2 = System.currentTimeMillis();
            if (r.code == 429) rest.put(rk, now2 + 60_000);
            else if (r.code == 401 || r.code == 403) rest.put(rk, now2 + 10 * 60_000);
            else if (r.code >= 500 || r.code == 0) rest.put(rk, now2 + 8_000);
            if (r.code == 400 && !keyProblem(r.msg)) {
                lastBad = r.msg;
                if (++badRequest >= 2) { for (Att x : live) x.kill(); event(host, id, "error", friendly(r.msg)); return null; }
            }
            lastLaunch = 0;                       // a failure starts the next key right away
        }
        event(host, id, "error", !lastBad.isEmpty() ? friendly(lastBad) : "Danav AI is busy right now. Please try again in a moment.");
        return null;
    }

    private static boolean keyProblem(String m) {
        String s = m == null ? "" : m.toLowerCase(Locale.ROOT);
        return s.contains("api key") || s.contains("api_key") || s.contains("expired") || s.contains("permission") || s.contains("billing") || s.contains("quota");
    }

    private static String friendly(String m) {
        String s = m == null ? "" : m.toLowerCase(Locale.ROOT);
        if (s.contains("unsupported") || s.contains("mime") || s.contains("file")) return "Danav AI couldn't read one of the attached files. Try another format (photo, PDF or text).";
        if (s.contains("too large") || s.contains("exceeds") || s.contains("token")) return "This chat or attachment is too large. Start a new chat or attach a smaller file.";
        if (s.contains("safety") || s.contains("blocked")) return "Danav AI can't help with that request.";
        return "Danav AI couldn't answer this. Please try again.";
    }

    private static HttpURLConnection connect(String model, String key, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + model + method).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(12_000);
        c.setReadTimeout(60_000);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("x-goog-api-key", key);
        c.setRequestProperty("X-Android-Package", "com.dilhan.vidra");
        if (!AiKeys.certSha1.isEmpty()) c.setRequestProperty("X-Android-Cert", AiKeys.certSha1);
        return c;
    }

    /** Reads the winning stream to the end and forwards thoughts / text as they arrive. */
    private static Res consume(Bridge.Host host, String id, Att a) {
        Res r = new Res();
        r.code = 200;
        try {
            String line = a.first;
            boolean any = false;
            String finish = "";
            while (line != null) {
                if (stopped.containsKey(id)) { r.canceled = true; return r; }
                if (line.startsWith("data:")) {
                    JSONObject o = new JSONObject(line.substring(5).trim());
                    JSONObject pf = o.optJSONObject("promptFeedback");
                    if (pf != null && pf.has("blockReason")) { r.code = 400; r.msg = "blocked safety"; return r; }
                    JSONArray cands = o.optJSONArray("candidates");
                    JSONObject cand = cands == null || cands.length() == 0 ? null : cands.getJSONObject(0);
                    if (cand != null) {
                        if (cand.has("finishReason")) finish = cand.optString("finishReason");
                        JSONObject content = cand.optJSONObject("content");
                        JSONArray parts = content == null ? null : content.optJSONArray("parts");
                        if (parts != null) {
                            StringBuilder text = new StringBuilder(), thought = new StringBuilder();
                            for (int i = 0; i < parts.length(); i++) {
                                JSONObject p = parts.getJSONObject(i);
                                r.parts.put(p);
                                if (p.has("functionCall")) { r.calls.put(p.getJSONObject("functionCall")); any = true; continue; }
                                String t = p.optString("text", "");
                                if (t.isEmpty()) continue;
                                if (p.optBoolean("thought")) thought.append(t); else text.append(t);
                            }
                            if (thought.length() > 0) { event(host, id, "thought", thought.toString()); r.emitted = true; }
                            if (text.length() > 0) { event(host, id, "text", text.toString()); r.emitted = true; any = true; }
                        }
                    }
                }
                line = a.in.readLine();
            }
            if (!any && ("SAFETY".equals(finish) || "PROHIBITED_CONTENT".equals(finish) || "BLOCKLIST".equals(finish))) { r.code = 400; r.msg = "blocked safety"; return r; }
            if (!any) { r.code = 0; r.msg = "empty"; return r; }
            r.ok = true;
        } catch (Exception e) {
            if (stopped.containsKey(id)) r.canceled = true;
            r.code = 0;
            r.msg = String.valueOf(e.getMessage());
        } finally {
            open.remove(id);
            try { a.c.disconnect(); } catch (Exception ignored) {}
        }
        return r;
    }

    private static String errorMessage(HttpURLConnection c) {
        try (InputStream es = c.getErrorStream()) {
            if (es == null) return "HTTP " + c.getResponseCode();
            String s = new String(readAll(es), "UTF-8");
            JSONObject e = new JSONObject(s).optJSONObject("error");
            return e == null ? s : e.optString("message", s);
        } catch (Exception e) { return "HTTP error"; }
    }

    /** Non-streaming request with the same fallback (used for chat titles). */
    static String generate(Context ctx, JSONObject body) {
        List<String> keys = AiKeys.get(ctx);
        int n = keys.size();
        String payload = body.toString();
        for (int mi = 0; mi < MODELS.length; mi++) {
            for (int k = 0; k < n; k++) {
                int ki = (sticky[mi] + k) % n;
                Long until = rest.get(mi + ":" + ki);
                if (until != null && until > System.currentTimeMillis() && k < n - 1) continue;
                HttpURLConnection c = null;
                try {
                    c = connect(MODELS[mi], keys.get(ki), ":generateContent");
                    try (OutputStream o = c.getOutputStream()) { o.write(payload.getBytes("UTF-8")); }
                    int code = c.getResponseCode();
                    if (code == 429) rest.put(mi + ":" + ki, System.currentTimeMillis() + 60_000);
                    if (code != 200) continue;
                    JSONObject o = new JSONObject(new String(readAll(c.getInputStream()), "UTF-8"));
                    JSONArray parts = o.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < parts.length(); i++) if (!parts.getJSONObject(i).optBoolean("thought")) sb.append(parts.getJSONObject(i).optString("text"));
                    return sb.toString();
                } catch (Exception ignored) {
                } finally { if (c != null) c.disconnect(); }
            }
        }
        return null;
    }

    static void title(Context ctx, Bridge.Host host, String reqId, String text) {
        new Thread(() -> {
            String t = null;
            try {
                JSONObject body = new JSONObject()
                        .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text",
                                "You name chats. Reply with only a short title of 2 to 6 words for the conversation that starts with the user's message — no quotes, "
                                        + "no full stop, no emoji. Use the same language and script as the message."))))
                        .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", text.length() > 1500 ? text.substring(0, 1500) : text)))))
                        .put("generationConfig", new JSONObject().put("maxOutputTokens", 40).put("thinkingConfig", new JSONObject().put("thinkingLevel", "minimal")));
                t = generate(ctx, body);
            } catch (Exception ignored) {}
            if (t != null) t = t.replaceAll("[\"*#`\\n]", " ").replaceAll("\\s+", " ").trim();
            if (t != null && t.endsWith(".")) t = t.substring(0, t.length() - 1);
            if (t != null && t.length() > 60) t = t.substring(0, 60);
            try { host.resolve(reqId, new JSONObject().put("title", t == null ? "" : t).toString()); } catch (Exception ignored) {}
        }, "danav-ai-title").start();
    }

    // ------------------------------------------------------------------ request body

    private static JSONObject body(Context ctx, JSONArray msgs, String level) throws Exception {
        JSONArray contents = new JSONArray();
        int count = msgs == null ? 0 : msgs.length();
        int from = Math.max(0, count - 60);                 // long chats: the latest 60 turns
        long inlineBudget = 18L * 1024 * 1024;
        // attachments: newest first so the budget keeps the latest files
        List<JSONObject> built = new ArrayList<>();
        for (int i = count - 1; i >= from; i--) {
            JSONObject m = msgs.getJSONObject(i);
            JSONArray parts = new JSONArray();
            JSONArray att = m.optJSONArray("att");
            boolean recent = i >= count - 12;
            if (att != null) for (int a = 0; a < att.length(); a++) {
                JSONObject f = att.getJSONObject(a);
                File file = attFile(ctx, f.optString("id"));
                if (!file.exists()) continue;
                String mime = f.optString("mime", "application/octet-stream");
                String kind = f.optString("kind");
                if ("text".equals(kind)) {
                    String txt = new String(readFile(file, 400_000), "UTF-8");
                    parts.put(new JSONObject().put("text", "Attached file \"" + f.optString("name") + "\":\n```\n" + txt + "\n```"));
                } else if (recent && file.length() < inlineBudget) {
                    inlineBudget -= file.length();
                    parts.put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", mime).put("data", Base64.encodeToString(readFile(file, Integer.MAX_VALUE), Base64.NO_WRAP))));
                } else {
                    parts.put(new JSONObject().put("text", "[Earlier attachment: " + f.optString("name") + "]"));
                }
            }
            String text = m.optString("text", "");
            if (!text.isEmpty() || parts.length() == 0) parts.put(new JSONObject().put("text", text.isEmpty() ? " " : text));
            built.add(0, new JSONObject().put("role", "user".equals(m.optString("role")) ? "user" : "model").put("parts", parts));
        }
        for (JSONObject o : built) contents.put(o);
        if (!level.matches("auto|minimal|low|medium|high")) level = "auto";
        JSONObject think = new JSONObject().put("includeThoughts", true);
        if (!"auto".equals(level)) think.put("thinkingLevel", level);
        return new JSONObject()
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", identity()))))
                .put("contents", contents)
                .put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", new JSONArray()
                        .put(new JSONObject().put("name", "web_search")
                                .put("description", "Search the web for up-to-date information. Returns the top results with title, url and a snippet. Call it several times with different queries for thorough research.")
                                .put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()
                                        .put("queries", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string"))
                                                .put("description", "1 to 3 search queries, each written like a person would type it into a search engine"))).put("required", new JSONArray().put("queries"))))
                        .put(new JSONObject().put("name", "read_page")
                                .put("description", "Open a web page (from search results or a link the user gave) and return its readable text.")
                                .put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()
                                        .put("url", new JSONObject().put("type", "string").put("description", "Full http(s) address of the page"))).put("required", new JSONArray().put("url")))))))
                .put("generationConfig", new JSONObject().put("thinkingConfig", think));
    }

    /** First non-empty string / string-array argument among the given names (trimmed, at most 3 items). */
    private static List<String> argList(JSONObject args, String... names) {
        List<String> out = new ArrayList<>();
        for (String n : names) {
            Object v = args.opt(n);
            if (v instanceof JSONArray) { JSONArray a = (JSONArray) v; for (int i = 0; i < a.length() && out.size() < 3; i++) { String x = a.optString(i, "").trim(); if (!x.isEmpty()) out.add(x); } }
            else if (v != null) { String x = String.valueOf(v).trim(); if (!x.isEmpty()) out.add(x); }
            if (!out.isEmpty()) return out;
        }
        return out;
    }

    private static void eventJson(Bridge.Host host, String id, String type, Object data) {
        try { host.send("ai", new JSONObject().put("id", id).put("t", type).put("d", data).toString()); } catch (Exception ignored) {}
    }

    private static void event(Bridge.Host host, String id, String type, String data) {
        try { host.send("ai", new JSONObject().put("id", id).put("t", type).put("d", data).toString()); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ attachments

    static File attDir(Context c) { File d = new File(c.getFilesDir(), "ai/att"); d.mkdirs(); return d; }
    static File attFile(Context c, String id) { return new File(attDir(c), id.replaceAll("[^A-Za-z0-9_-]", "")); }

    static String mimeOf(Context c, String id) {
        try { return new String(readFile(new File(attDir(c), id.replaceAll("[^A-Za-z0-9_-]", "") + ".m"), 200), "UTF-8").trim(); } catch (Exception e) { return "application/octet-stream"; }
    }

    static void deleteAtt(Context c, JSONArray ids) {
        for (int i = 0; ids != null && i < ids.length(); i++) {
            String id = ids.optString(i).replaceAll("[^A-Za-z0-9_-]", "");
            if (id.isEmpty()) continue;
            new File(attDir(c), id).delete();
            new File(attDir(c), id + ".m").delete();
        }
    }

    private static final String[] TEXT_EXT = {"txt", "md", "csv", "json", "xml", "html", "htm", "css", "js", "ts", "java", "kt", "py", "c", "cpp", "h", "cs", "go", "rs",
            "php", "rb", "swift", "dart", "sql", "yaml", "yml", "ini", "log", "sh", "bat", "srt", "vtt", "tsv", "jsx", "tsx", "gradle", "properties", "toml"};

    static void importPicked(Context ctx, Bridge.Host host, Intent data) {
        new Thread(() -> {
            JSONArray items = new JSONArray(), errors = new JSONArray();
            List<Uri> uris = new ArrayList<>();
            if (data != null) {
                if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
                else if (data.getData() != null) uris.add(data.getData());
            }
            for (Uri u : uris) {
                try { items.put(importOne(ctx, u)); }
                catch (Exception e) { errors.put(e.getMessage() == null ? "Couldn't attach this file" : e.getMessage()); }
            }
            try { host.send("aiAtt", new JSONObject().put("items", items).put("errors", errors).toString()); } catch (Exception ignored) {}
        }, "danav-ai-att").start();
    }

    private static JSONObject importOne(Context ctx, Uri u) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        String name = "file", mime = cr.getType(u);
        long size = -1;
        try (Cursor c = cr.query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && c.getString(ni) != null) name = c.getString(ni);
                if (si >= 0) size = c.getLong(si);
            }
        }
        if (mime == null) mime = "application/octet-stream";
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        File out = attFile(ctx, id);
        String kind;
        if (mime.startsWith("image/") && !mime.equals("image/svg+xml")) {
            kind = "image";
            Bitmap b = decodeScaled(cr, u, 1600);
            if (b == null) throw new Exception("Couldn't open " + name);
            try (FileOutputStream fo = new FileOutputStream(out)) { b.compress(Bitmap.CompressFormat.JPEG, 88, fo); }
            b.recycle();
            mime = "image/jpeg";
        } else {
            boolean text = mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") || mime.contains("javascript");
            for (String t : TEXT_EXT) if (t.equals(ext)) text = true;
            if (text) kind = "text";
            else if (mime.equals("application/pdf") || ext.equals("pdf")) { kind = "pdf"; mime = "application/pdf"; }
            else if (mime.startsWith("audio/")) kind = "audio";
            else if (mime.startsWith("video/")) kind = "video";
            else throw new Exception(name + ": Danav AI can read photos, PDFs, text and code files, audio and short videos.");
            long max = text ? 2L * 1024 * 1024 : 15L * 1024 * 1024;
            if (size > max) throw new Exception(name + " is too large (max " + (max / 1024 / 1024) + " MB).");
            try (InputStream in = cr.openInputStream(u); FileOutputStream fo = new FileOutputStream(out)) {
                if (in == null) throw new Exception("Couldn't open " + name);
                byte[] buf = new byte[65536]; int r; long total = 0;
                while ((r = in.read(buf)) > 0) { total += r; if (total > max) throw new Exception(name + " is too large."); fo.write(buf, 0, r); }
            } catch (Exception e) { out.delete(); throw e; }
            if (text) mime = "text/plain";
        }
        try (FileOutputStream fo = new FileOutputStream(new File(attDir(ctx), id + ".m"))) { fo.write(mime.getBytes("UTF-8")); }
        return new JSONObject().put("id", id).put("name", name).put("mime", mime).put("kind", kind).put("size", out.length());
    }

    private static Bitmap decodeScaled(ContentResolver cr, Uri u, int max) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
        int s = 1;
        while (o.outWidth / (s * 2) >= max || o.outHeight / (s * 2) >= max) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        Bitmap b;
        try (InputStream in = cr.openInputStream(u)) { b = BitmapFactory.decodeStream(in, null, o2); }
        if (b == null) return null;
        int rot = 0;
        try (InputStream in = cr.openInputStream(u)) {
            if (in != null) {
                androidx.exifinterface.media.ExifInterface ex = new androidx.exifinterface.media.ExifInterface(in);
                int or = ex.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1);
                rot = or == 6 ? 90 : or == 3 ? 180 : or == 8 ? 270 : 0;
            }
        } catch (Throwable ignored) {}
        float sc = Math.min(1f, max / (float) Math.max(b.getWidth(), b.getHeight()));
        if (rot != 0 || sc < 1f) {
            Matrix m = new Matrix();
            m.postScale(sc, sc);
            m.postRotate(rot);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            b = r;
        }
        return b;
    }

    // ------------------------------------------------------------------ io

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toByteArray();
    }

    private static byte[] readFile(File f, int max) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[65536]; int n, total = 0;
            while ((n = in.read(buf)) > 0 && total < max) { int k = Math.min(n, max - total); o.write(buf, 0, k); total += k; }
            return o.toByteArray();
        }
    }
}
