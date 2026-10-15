package com.dilhan.danavai;

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
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSource;
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
    /** Used only if the list can't be fetched (first run with no network). */
    static final String[] MODELS_FALLBACK = {"gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.6-flash", "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-flash-latest"};
    /** Live (voice) models, newest first; also refreshed from the service. */
    static final String[] LIVE_FALLBACK = {"gemini-3.8-live", "gemini-2.5-flash-native-audio-latest", "gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-preview-12-2025"};
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String CATALOGUE = "https://generativelanguage.googleapis.com/v1beta/models";
    /** The live (voice) endpoint — the settings test opens one to prove the call path really works. */
    private static final String WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

    /**
     * One client for every AI request. OkHttp (not the old HttpURLConnection) so the address order is ours:
     * IPv4 first (see {@link Net}) — phone networks that mis-locate IPv6 otherwise get "location is not
     * supported" and every chat and call fails. The read timeout is long enough for a thinking model.
     */
    static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .dns(Net.dns())
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** The service's own message out of a raw response body. */
    static String errorMessage(String body) {
        try {
            JSONObject e = new JSONObject(body == null ? "" : body).optJSONObject("error");
            String m = e == null ? (body == null ? "" : body) : e.optString("message", body);
            return m == null || m.isEmpty() ? "no message" : m;
        } catch (Exception e) { return body == null || body.isEmpty() ? "no message" : body; }
    }

    /** A ready POST for one model with one key. */
    private static Request post(String model, String key, String method, String payload) {
        return new Request.Builder().url(BASE + model + method)
                .header("x-goog-api-key", key)
                .header("X-Android-Package", "com.dilhan.danavai")
                .post(RequestBody.create(JSON, payload)).build();
    }

    /** The plain error text of a failed response (the service's own message whenever there is one). */
    static String errorMessage(Response r) {
        try {
            String s = r.body() == null ? "" : r.body().string();
            JSONObject e = new JSONObject(s).optJSONObject("error");
            String m = e == null ? s : e.optString("message", s);
            if (m == null || m.isEmpty()) m = "HTTP " + r.code();
            return m;
        } catch (Exception e) { return "HTTP " + r.code(); }
    }

    private static final Map<String, Long> rest = new ConcurrentHashMap<>();     // "model:key" → resting until
    private static final java.util.Set<String> dead = java.util.concurrent.ConcurrentHashMap.newKeySet();   // model names this account doesn't have
    private static volatile String[] modelsCache;                                // discovered chat models (best first)
    private static volatile String[] liveCache;                                  // discovered live models (best first)
    private static volatile long modelsAt;                                       // when they were fetched (ms)

    /**
     * The models this account really has, straight from the service (GET /v1beta/models), best first.
     * Names in this app are never guessed: whatever the account offers is what gets used, and the list is
     * remembered for a day so it costs nothing on later runs.
     */
    static String[] models(Context ctx, boolean live) {
        String[] have = live ? liveCache : modelsCache;
        long now = System.currentTimeMillis();
        if (have == null) have = cached(ctx, live);
        if (have == null || now - modelsAt > 12 * 60 * 60_000L) {
            // never touch the network on the UI thread: on the main thread this is a quiet background warm-up
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                if (modelsAt == 0) new Thread(() -> refresh(ctx), "danav-model-list").start();
            } else refresh(ctx);
        }
        have = live ? liveCache : modelsCache;
        return have == null || have.length == 0 ? (live ? LIVE_FALLBACK : MODELS_FALLBACK) : have;
    }

    /** Names the account does not have; skipped for a while so no time is wasted on them again. */
    static void markDead(String name) { if (name != null && !name.isEmpty()) dead.add(name); }

    /** Fills the model list in the background so the first call or message never waits for it. */
    static void warm(Context ctx) {
        new Thread(() -> { try { models(ctx, false); models(ctx, true); } catch (Throwable ignored) {} }, "danav-model-warm").start();
    }

    private static String[] cached(Context ctx, boolean live) {
        try {
            android.content.SharedPreferences p = ctx.getSharedPreferences("ai", 0);
            long at = p.getLong("modelsAt", 0);
            String j = p.getString(live ? "liveModels" : "chatModels", null);
            if (j == null || at == 0) return null;
            modelsAt = at;
            JSONArray a = new JSONArray(j);
            String[] out = new String[a.length()];
            for (int i = 0; i < a.length(); i++) out[i] = a.getString(i);
            if (live) liveCache = out; else modelsCache = out;
            return out.length == 0 ? null : out;
        } catch (Exception e) { return null; }
    }

    private static void refresh(Context ctx) {
        try {
            List<String> keys = AiKeys.get(ctx);
            if (keys.isEmpty()) return;
            long at = System.currentTimeMillis();
            if (modelsAt != 0 && at - modelsAt < 90_000) return;             // don't hammer the service
            modelsAt = at;
            JSONArray chat = list(keys.get(0), "generateContent", false);
            JSONArray live = list(keys.get(0), "bidiGenerateContent", true);
            android.content.SharedPreferences.Editor e = ctx.getSharedPreferences("ai", 0).edit();
            if (chat.length() > 0) { modelsCache = toArray(chat); e.putString("chatModels", chat.toString()); }
            if (live.length() > 0) { liveCache = toArray(live); e.putString("liveModels", live.toString()); }
            e.putLong("modelsAt", at).apply();
        } catch (Exception ignored) {}
    }

    /** Fetches the catalogue and keeps only the models we can really use, in a sensible order. */
    private static JSONArray list(String key, String method, boolean live) {
        JSONArray out = new JSONArray();
        try {
            Response resp = HTTP.newCall(new Request.Builder().url(CATALOGUE + "?pageSize=200").header("x-goog-api-key", key).build()).execute();
            int code = resp.code();
            String text = resp.body() == null ? "" : resp.body().string();
            resp.close();
            if (code != 200) return out;
            JSONArray ms = new JSONObject(text).optJSONArray("models");
            List<String> names = new ArrayList<>();
            for (int i = 0; ms != null && i < ms.length(); i++) {
                JSONObject m = ms.getJSONObject(i);
                String n = m.optString("name", "").replace("models/", "");
                JSONArray mets = m.optJSONArray("supportedGenerationMethods");
                boolean ok = false;
                for (int j = 0; mets != null && j < mets.length(); j++) if (method.equals(mets.optString(j))) ok = true;
                if (!ok) continue;
                if (live ? usableLive(n) : usableChat(n)) names.add(n);
            }
            names.sort((a, b) -> score(b, live) - score(a, live));      // best first
            for (String n : names) out.put(n);
        } catch (Exception ignored) {}
        return out;
    }

    private static boolean usableChat(String n) {
        if (n.contains("tts") || n.contains("image") || n.contains("embedding") || n.contains("embed") || n.contains("aqa")
                || n.contains("live") || n.contains("robotics") || n.contains("transcribe") || n.contains("translate")
                || n.contains("deep-research") || n.contains("nano-banana") || n.contains("antigravity") || n.contains("lyria")
                || n.contains("gemma") || n.contains("computer-use") || n.contains("veo") || n.contains("imagen")) return false;
        return n.startsWith("gemini-");
    }

    private static boolean usableLive(String n) {
        if (!n.contains("live")) return false;
        return !(n.contains("transcribe") || n.contains("translate") || n.contains("robotics") || n.contains("extended-thinking"));
    }

    /** Higher is better: flash-lite first (fast), then flash, then pro; stable before preview. */
    private static int score(String n, boolean live) {
        int s = 0;
        if (n.contains("flash-lite")) s += 60;
        else if (n.contains("flash")) s += 40;
        else if (n.contains("pro")) s += 10;
        if (!n.contains("preview")) s += 8;
        if (n.equals("gemini-flash-lite-latest") || n.equals("gemini-flash-latest")) s += 20;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("gemini-(\\d+)(?:\\.(\\d+))?").matcher(n);
        if (m.find()) s += Math.min(30, Integer.parseInt(m.group(1)) * 6 + (m.group(2) == null ? 0 : Integer.parseInt(m.group(2))));
        return s;
    }

    private static String[] toArray(JSONArray a) {
        String[] o = new String[a.length()];
        for (int i = 0; i < a.length(); i++) o[i] = a.optString(i);
        return o;
    }
    private static final Map<String, Call> open = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> stopped = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ identity

    static String identity() { return identity(null); }

    static String identity(Context ctx) {
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
                + "Phone: you are running inside the Danav app on the user's own phone, and you are his hands and eyes on it — you can do anything the phone can do, the same way he does it himself, and you never explain how: you just do it. "
                + "Your eyes: read_screen (every word on the screen) and see_screen (a real picture — chats, photos, videos, games, maps, anything where words are not enough); phone_state for the situation. Your hands: open_app, open_url, tap, tap_xy, long_press, type_text, key (back, home, recents, enter, notifications, quick settings, volume, media keys, lock, screenshot), scroll, swipe, drag, set_slider, wait. "
                + "The method for EVERY phone task: (1) look at the screen first — never guess what you have not looked at; (2) do ONE small step; (3) look again at what it really did — the result comes with a fresh picture of the screen — and if it did not work, do not repeat it, try another way; (4) keep going until it is really done, then say one short line and stop. "
                + "Apps are just apps: open the one you need and drive it. A search box inside an app is usually the fastest way to a person, a song, a video or a setting; if you are unsure how something works, look at the screen and think, or web_search how it is done. "
                + "Calls: start them the same way (open the app, find the person, press its call button, wait for them to pick up), then join_call to be on that call yourself and talk with them; leave_call to give the phone back. "
                + "Believe only what a tool result says: never claim something worked unless the result says so, and if it failed, say plainly what happened and try another way — never invent a contact, a number or a chat. "
                + "Do the work without asking twice; only ask one short question when a name is truly ambiguous or the action is risky (deleting, paying, posting publicly). "
                + "Speed and effort: get to the answer without stalling. Never ask the user to confirm something they already clearly asked for — just do it, and pick a "
                + "sensible default when a small detail is missing. For small, reversible steps always act instead of asking. Do every step yourself; never tell the user how to do it in another app.\n"
                + "Personalisation: whenever the user tells you something durable about themselves or their world — their name, city, language or script, family, friends, work, "
                + "studies, routine, birthdays, favourite things, dislikes, ongoing projects, how they like answers — silently call remember with one short sentence. Never announce that "
                + "you saved anything. If a fact changes, call remember again with the new version, and call forget for a note that is no longer true or that the user drops.\n"
                + "Small tasks: for an ordinary request (send a message, call someone, open an app, play something, set a timer) do it and stay quiet about it — do not answer with "
                + "\"I sent it\", \"done\", \"the message has been sent\" or any similar report. Just do it. Only speak about it if the user asks, if something failed, or if a decision is needed.\n"
                + "Today is " + today + "." + (ctx == null ? "" : Memory.prompt(ctx));
    }

    static String liveIdentity(Context ctx) {
        return identity(ctx).replaceAll("Web tools:[^\\n]*\\n", "") + "\\nYou are now talking on a live voice call inside Danav. Speak naturally and warmly, answer in the "
                + "language the user speaks, and keep every reply short — one or two sentences unless more is asked. Don't read out Markdown, lists or symbols. Don't greet with your name "
                + "unless asked. Silence is fine: when the user is quiet, just wait." + (ctx == null ? "" : Memory.livePrompt(ctx));
    }

    // ------------------------------------------------------------------ chat

    static void stop(String id) {
        stopped.put(id, true);
        Call c = open.remove(id);
        if (c != null) new Thread(c::cancel).start();
    }

    static void chat(Context ctx, Bridge.Host host, String id, String json) {
        new Thread(() -> {
            try { runChat(ctx, host, id, new JSONObject(json)); }
            catch (Exception e) { event(host, id, "error", "Something went wrong. Please try again."); }
            finally { stopped.remove(id); open.remove(id); }
        }, "danav-ai").start();
    }

    private static final class Res { boolean ok, emitted, canceled; int code; String msg = ""; JSONArray parts = new JSONArray(), calls = new JSONArray(); }

    private static final int MAX_ROUNDS = 9;      // a real task is often 5+ phone steps: never cut it short early

    private static void runChat(Context ctx, Bridge.Host host, String id, JSONObject req) throws Exception {
        // simple commands are done on the phone itself — instant, no network round trip
        Fast.Hit fast = fastHit(ctx, req);
        if (fast != null) {
            event(host, id, "text", Fast.run(ctx, fast));
            event(host, id, "done", "");
            return;
        }
        List<String> keys = AiKeys.get(ctx);
        if (keys.isEmpty()) { event(host, id, "error", "Danav AI isn't available in this copy of the app."); return; }
        JSONObject body = body(ctx, req.optJSONArray("messages"), req.optString("level", "auto"));
        String noLevel = withoutLevel(body);                       // same request, thinking level left out
        String noTools = withoutTools(body);                       // and one with no tools at all
        JSONArray contents = body.getJSONArray("contents");
        JSONArray sources = new JSONArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int shots = 0;                                             // pictures of the screen attached this answer
        for (int round = 0; round < MAX_ROUNDS; round++) {
            if (round == MAX_ROUNDS - 1) body.remove("tools");          // last round: answer with what was found
            Res r = withFallback(ctx, host, id, body.toString(), new String[]{noLevel, noTools}, keys);
            if (r == null) return;                                     // error / stop already reported
            if (r.calls.length() == 0) {
                if (sources.length() > 0) eventJson(host, id, "src", sources);
                event(host, id, "done", "");
                return;
            }
            contents.put(new JSONObject().put("role", "model").put("parts", r.parts));
            JSONArray answers = new JSONArray();
            String shot = null;                                    // screen picture to attach after the tool answers
            boolean wantLook = false;                              // a screen step failed → the model should see it
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
                } else if ("remember".equals(name)) {
                    String note = args.optString("note", args.optString("text", ""));
                    boolean saved = Memory.add(ctx, note);
                    result = new JSONObject().put("saved", saved).put("total", Memory.count(ctx));
                } else if ("forget".equals(name)) {
                    int n = Memory.forget(ctx, args.optString("query", args.optString("note", "")));
                    result = new JSONObject().put("forgotten", n).put("total", Memory.count(ctx));
                } else if (Phone.isPhoneTool(name)) {
                    eventJson(host, id, "tool", new JSONObject().put("k", "phone").put("name", name));
                    if ("see_screen".equals(name)) {
                        // the assistant really looks: the picture goes into the conversation itself
                        shot = Phone.shotB64();
                        boolean has = shot != null && !shot.isEmpty();
                        result = new JSONObject().put("success", has).put("result", has
                                ? "Here is the phone screen as a picture (see the image right after this) — look at it and act on what you see."
                                : "FAILED — this phone doesn't give a screenshot to Danav (Android 11 or newer is needed). Use read_screen instead.");
                    } else {
                        Phone.Out out = Phone.run(ctx, name, args);
                        if (!out.ok) askForIt(ctx, out.text);
                        result = new JSONObject().put("success", out.ok).put("result", out.ok ? out.text
                                : "FAILED — " + out.text + " Never tell the user this worked: say plainly what actually happened, or do it another way.");
                        if (!out.ok && out.look) wantLook = true;
                        // every step that changes the screen comes back with a picture of what it really did:
                        // without it the model is blind and starts guessing (Google's Computer Use guide)
                        if (!"see_screen".equals(name) && Phone.isScreenTool(name) && shots < 3) {
                            String img = Phone.shotB64();
                            if (img != null && !img.isEmpty()) { shot = img; }
                        }
                    }
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
            if (shot != null && !shot.isEmpty()) {
                contents.put(lookTurn("The phone screen right now, as a picture.", shot));
                shots++;
            } else if (wantLook && shots < 2) {
                // a screen step failed: give the model eyes instead of letting it guess again
                String img = Phone.shotB64();
                if (img != null && !img.isEmpty()) {
                    contents.put(lookTurn("The screen step failed, so here is the phone screen right now. Look at it and continue a different way.", img));
                    shots++;
                }
            }
        }
        event(host, id, "done", "");
    }

    /**
     * The user should not have to hunt through settings for the thing that just blocked his request: the
     * moment a tool fails because a permission is missing, the app asks for exactly that one — a normal
     * Android dialog for contacts, the overlay page for "open other apps". Nothing happens without the user.
     */
    /**
     * When the thing that just blocked the user is a switch, the app opens that switch for him instead of
     * only complaining: "open other apps" (the only permission Danav itself needs for phone control).
     */
    private static void askForIt(Context ctx, String text) {
        if (text == null) return;
        try {
            if (text.contains("Display over other apps") && !Phone.canStart(ctx)) {
                android.app.Activity a = MainActivity.inst;
                if (a != null) a.runOnUiThread(() -> {
                    try { a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.getPackageName()))); }
                    catch (Exception ignored) {}
                });
            }
        } catch (Throwable ignored) {}
    }

    /** A turn that carries a picture (role user + image part) — how the model gets to see the screen. */
    private static JSONObject lookTurn(String note, String b64) throws Exception {
        return new JSONObject().put("role", "user").put("parts", new JSONArray()
                .put(new JSONObject().put("text", note))
                .put(new JSONObject().put("inlineData", new JSONObject().put("mimeType", "image/jpeg").put("data", b64))));
    }

    /** A question the phone can answer instantly, without the network and without touching anything. */
    private static Fast.Hit fastHit(Context ctx, JSONObject req) {
        try {
            JSONArray msgs = req.optJSONArray("messages");
            if (msgs == null || msgs.length() == 0) return null;
            JSONObject last = msgs.getJSONObject(msgs.length() - 1);
            if (!"user".equals(last.optString("role"))) return null;
            JSONArray att = last.optJSONArray("att");
            if (att != null && att.length() > 0) return null;                 // attachments need the model
            String text = last.optString("text", "").trim();
            if (text.isEmpty() || text.length() > 200) return null;
            return Fast.match(ctx, text);
        } catch (Exception e) { return null; }
    }

    private static final int PAR = 4;              // at most this many requests in flight for one answer
    private static final long HEDGE_MS = 4500;     // no first byte after this long → also try the next key

    /** One request in flight on one key. Its thread connects and waits for the first streamed line, then hands over. */
    private static final class Att {
        final String model; final int mi, ki; final String key;
        volatile Call call; Response resp; BufferedSource src; String first;
        int code; String msg = ""; boolean ready; volatile boolean dead;
        Att(String model, int mi, int ki, String key) { this.model = model; this.mi = mi; this.ki = ki; this.key = key; }
        void kill() { dead = true; final Call x = call; if (x != null) new Thread(x::cancel).start(); }
        void close() { try { if (resp != null) resp.close(); } catch (Exception ignored) {} }
    }

    private static void launch(final Att a, final String payload, final java.util.concurrent.BlockingQueue<Att> q) {
        new Thread(() -> {
            try {
                Call c = HTTP.newCall(post(a.model, a.key, ":streamGenerateContent?alt=sse", payload));
                a.call = c;
                if (a.dead) throw new Exception("canceled");
                a.resp = c.execute();
                a.code = a.resp.code();
                if (a.code != 200) a.msg = errorMessage(a.resp);
                else {
                    a.src = a.resp.body() == null ? null : a.resp.body().source();
                    String line;
                    while (a.src != null && (line = a.src.readUtf8Line()) != null)
                        if (line.startsWith("data:")) { a.first = line; a.ready = true; break; }
                    if (!a.ready) { a.code = 0; a.msg = "empty response"; }
                }
            } catch (Exception e) { if (a.code == 200) a.code = 0; a.msg = Net.describe(e); }
            if (!a.ready) a.close();
            q.add(a);
        }, "danav-ai-req").start();
    }

    /**
     * One model turn with the key fallback. Keys are raced: the first starts at once, another joins when the first byte is late
     * (or right away when a key fails), up to PAR at a time; the first one that streams wins and the rest are dropped.
     * Returns null when a final error or stop has been reported.
     */
    /** A copy of the request with the thinking level removed (used when a model doesn't know that field). */
    /** A copy of the request with every tool removed (used when the tools themselves are refused). */
    private static String withoutTools(JSONObject body) {
        try {
            JSONObject o = new JSONObject(body.toString());
            o.remove("tools");
            return o.toString();
        } catch (Exception e) { return body.toString(); }
    }

    private static String withoutLevel(JSONObject body) {
        try {
            JSONObject o = new JSONObject(body.toString());
            JSONObject g = o.optJSONObject("generationConfig");
            JSONObject t = g == null ? null : g.optJSONObject("thinkingConfig");
            if (t != null) t.remove("thinkingLevel");
            return o.toString();
        } catch (Exception e) { return body.toString(); }
    }

    private static Res withFallback(Context ctx, Bridge.Host host, String id, String payload, String[] alts, List<String> keys) {
        int stage = 0;                       // 0 = as sent, then each fuller fallback
        String sent = payload;
        int n = keys.size(), badRequest = 0;
        String lastBad = "";
        List<Att> cands = new ArrayList<>();
        long now0 = System.currentTimeMillis();
        String[] models = models(ctx, false);
        for (int mi = 0; mi < models.length && mi < 8; mi++) {
            if (dead.contains(models[mi])) continue;                    // this account has no such model
            List<Att> later = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                int ki = k;
                Long until = rest.get(models[mi] + ":" + ki);
                (until != null && until > now0 ? later : cands).add(new Att(models[mi], mi, ki, keys.get(ki)));
            }
            cands.addAll(later);
        }
        int firstPass = cands.size();
        for (int i = 0; i < firstPass && cands.size() < 40; i++) { Att a = cands.get(i); cands.add(new Att(a.model, a.mi, a.ki, a.key)); }   // one more round

        java.util.concurrent.LinkedBlockingQueue<Att> q = new java.util.concurrent.LinkedBlockingQueue<>();
        List<Att> live = new ArrayList<>();
        int next = 0; long lastLaunch = 0;
        while (true) {
            if (stopped.containsKey(id)) { for (Att x : live) x.kill(); event(host, id, "stopped", ""); return null; }
            long now = System.currentTimeMillis();
            boolean canLaunch = next < cands.size() && live.size() < PAR;
            if (canLaunch && (live.isEmpty() || now - lastLaunch >= HEDGE_MS)) {
                Att a = cands.get(next++); live.add(a); lastLaunch = now; launch(a, sent, q);
                continue;
            }
            if (live.isEmpty()) break;
            Att a;
            try { a = q.poll(canLaunch ? Math.max(50, Math.min(400, lastLaunch + HEDGE_MS - now)) : 400, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { a = null; }
            if (a == null) continue;
            if (a.dead || !live.contains(a)) { a.close(); continue; }
            live.remove(a);
            String rk = a.model + ":" + a.ki;
            Res r;
            if (a.ready) {
                for (Att x : live) x.kill();
                live.clear();
                open.put(id, a.call);
                r = consume(host, id, a);
                if (r.ok) { rest.remove(rk); return r; }
                if (r.canceled || stopped.containsKey(id)) { event(host, id, "stopped", ""); return null; }
                if (r.emitted) event(host, id, "reset", "");
            } else {
                r = new Res(); r.code = a.code; r.msg = a.msg;
            }
            long now2 = System.currentTimeMillis();
            String low = r.msg == null ? "" : r.msg.toLowerCase(java.util.Locale.ROOT);
            boolean gone = r.code == 404 || low.contains("not found") || low.contains("does not exist") || low.contains("not supported");
            if (gone) dead.add(a.model);                                // never worth another try this run
            if (r.code == 429) rest.put(rk, now2 + 45_000);
            else if (r.code == 401 || r.code == 403) rest.put(rk, now2 + 10 * 60_000);
            else if (r.code >= 500 || r.code == 0) rest.put(rk, now2 + 6_000);
            if (gone || r.code == 429 || r.code == 0 || r.code >= 500) rest.put(a.model + ":all", now2 + (gone ? 300_000 : 12_000));   // skip this model for a bit
            if (r.code == 400 && !keyProblem(r.msg)) {
                // the model may not accept the thinking level, or may not accept one of the tools:
                // step down through the fallbacks instead of giving up on the user's question
                if (stage < alts.length) {
                    sent = alts[stage++];
                    badRequest = 0;
                    lastLaunch = 0;
                    android.util.Log.w("DanavAI", "400 from the model, retrying with fallback " + stage + ": " + r.msg);
                    continue;
                }
                lastBad = r.msg;
                if (++badRequest >= 2) { for (Att x : live) x.kill(); event(host, id, "error", friendly(r.msg)); return null; }
            }
            lastLaunch = 0;                       // a failure starts the next key right away
        }
        // Last resort: one plain, non-streaming call. If streaming is the thing the network dislikes, the
        // user still gets a real answer instead of an error message.
        Res plain = plainOnce(ctx, host, id, sent, keys);
        if (plain != null) return plain;
        String why = !lastBad.isEmpty() ? friendly(lastBad) : "Danav AI is busy right now. Please try again in a moment.";
        if (!lastBad.isEmpty()) android.util.Log.w("DanavAI", "chat failed: " + lastBad);
        event(host, id, "error", why);
        return null;
    }

    /**
     * The safety net: a single plain call (no streaming) with the first few models and every key.
     * Function calls and text are both understood, so tool use keeps working on this path too.
     */
    private static Res plainOnce(Context ctx, Bridge.Host host, String id, String payload, List<String> keys) {
        String[] ms = models(ctx, false);
        StringBuilder log = new StringBuilder();
        for (int mi = 0; mi < ms.length && mi < 3; mi++) {
            if (dead.contains(ms[mi])) continue;
            for (int k = 0; k < keys.size(); k++) {
                if (stopped.containsKey(id)) { event(host, id, "stopped", ""); return null; }
                Response r = null;
                try {
                    r = HTTP.newCall(post(ms[mi], keys.get(k), ":generateContent", payload)).execute();
                    if (r.code() != 200) { log.append(code(r)).append(' '); continue; }
                    JSONObject o = new JSONObject(r.body() == null ? "{}" : r.body().string());
                    Res out = new Res();
                    out.code = 200;
                    JSONArray cands = o.optJSONArray("candidates");
                    JSONObject cand = cands == null || cands.length() == 0 ? null : cands.getJSONObject(0);
                    JSONObject content = cand == null ? null : cand.optJSONObject("content");
                    JSONArray parts = content == null ? null : content.optJSONArray("parts");
                    StringBuilder sb = new StringBuilder(), think = new StringBuilder();
                    for (int i = 0; parts != null && i < parts.length(); i++) {
                        JSONObject pt = parts.getJSONObject(i);
                        out.parts.put(pt);
                        if (pt.has("functionCall")) { out.calls.put(pt.getJSONObject("functionCall")); continue; }
                        String t = pt.optString("text", "");
                        if (t.isEmpty()) continue;
                        if (pt.optBoolean("thought")) think.append(t); else sb.append(t);
                    }
                    if (think.length() > 0) { event(host, id, "thought", think.toString()); out.emitted = true; }
                    if (sb.length() > 0) { event(host, id, "text", sb.toString()); out.emitted = true; }
                    if (out.calls.length() == 0 && sb.length() == 0) continue;      // nothing usable → next key
                    out.ok = true;
                    return out;
                } catch (Exception e) { log.append(Net.describe(e)).append(' '); }
                finally { if (r != null) r.close(); }
            }
        }
        android.util.Log.w("DanavAI", "plain fallback failed: " + log);
        return null;
    }

    private static String code(Response r) {
        try { return "HTTP " + r.code() + " " + clip(errorMessage(r)); } catch (Exception e) { return "HTTP error"; }
    }

    private static boolean keyProblem(String m) {
        String s = m == null ? "" : m.toLowerCase(Locale.ROOT);
        return s.contains("api key") || s.contains("api_key") || s.contains("expired") || s.contains("permission") || s.contains("billing") || s.contains("quota");
    }

    private static String friendly(String m) {
        String s = m == null ? "" : m.toLowerCase(Locale.ROOT);
        if (s.contains("quota") || s.contains("rate limit") || s.contains("resource_exhausted") || s.contains("billing"))
            return "Danav AI has used up its limit for now. Please try again in a little while.";
        if (s.contains("api key") || s.contains("api_key") || s.contains("permission") || s.contains("unauthenticated") || s.contains("credential"))
            return "Danav AI isn't set up right in this copy of the app — it needs a fresh build.";
        if (s.contains("not found") || s.contains("not_found") || s.contains("does not exist"))
            return "Danav AI's model is unavailable right now — it needs a fresh build.";
        if (s.contains("location") || s.contains("failed_precondition"))
            return "The AI service is refusing this network's location. Switch between Wi-Fi and mobile data, or turn on a VPN, and try again.";
        if (s.contains("unknownhost") || s.contains("unable to resolve") || s.contains("timeout") || s.contains("timed out")
                || s.contains("failed to connect") || s.contains("connection refused") || s.contains("unreachable") || s.contains("ssl")
                || s.contains("socket") || s.contains("network"))
            return "Danav AI can't reach the internet right now. Check the connection (or a VPN) and try again.";
        if (s.contains("unsupported") || s.contains("mime") || s.contains("file")) return "Danav AI couldn't read one of the attached files. Try another format (photo, PDF or text).";
        if (s.contains("too large") || s.contains("exceeds") || s.contains("token")) return "This chat or attachment is too large. Start a new chat or attach a smaller file.";
        if (s.contains("safety") || s.contains("blocked")) return "Danav AI can't help with that request.";
        return "Danav AI couldn't answer this. Please try again.";
    }

    /**
     * The Settings connection test: it walks the whole way (keys → network/DNS → model list → a real answer)
     * and reports every step in one line. Whatever fails, this line says what and why — never a vague message.
     */
    static String test(Context ctx) {
        JSONObject out = new JSONObject();
        StringBuilder log = new StringBuilder();
        String model = null;
        int code = 0;
        long ms = 0;
        try {
            List<String> keys = AiKeys.get(ctx);
            log.append("keys=").append(keys.size());
            if (keys.isEmpty()) return report(out, false, 0, log.append(" · app me API key nahi mili").toString(), null, 0);
            String key = keys.get(0);
            log.append(" · ").append(Net.summary(ctx));
            log.append(" · ").append(phoneReport(ctx));
            try {
                Response lr = HTTP.newCall(new Request.Builder().url(CATALOGUE + "?pageSize=5").header("x-goog-api-key", key).build()).execute();
                log.append(" · GET models=").append(lr.code());
                if (lr.code() != 200) log.append(' ').append(clip(errorMessage(lr)));
                lr.close();
            } catch (Exception e) { log.append(" · GET models! ").append(Net.describe(e)); }

            String[] models = models(ctx, false);
            model = models.length == 0 ? "?" : models[0];
            String payload = new JSONObject()
                    .put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", "Say ok")))))
                    .put("generationConfig", new JSONObject().put("maxOutputTokens", 16)).toString();
            // Every key is tried, exactly like chat does — one tired key must not make the test look broken.
            int tried = 0;
            String lastMsg = "";
            for (int ki = 0; ki < Math.min(keys.size(), 4); ki++) {
                String k = keys.get(ki);
                tried++;
                Response r = null;
                try {
                    long t0 = System.currentTimeMillis();
                    r = HTTP.newCall(post(model, k, ":generateContent", payload)).execute();
                    code = r.code();
                    ms = System.currentTimeMillis() - t0;
                    String text = r.body() == null ? "" : r.body().string();
                    if (code != 200) {
                        lastMsg = errorMessage(text);
                        log.append(" · key#").append(ki + 1).append('=').append(code).append(' ').append(clip(lastMsg));
                        if (clip(lastMsg).toLowerCase(java.util.Locale.ROOT).contains("location")) {
                            log.append(" (same for every key — it is the network, not the key)");
                            break;
                        }
                        continue;
                    }
                    String said = "";
                    try {
                        JSONArray c = new JSONObject(text).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts");
                        for (int i = 0; i < c.length(); i++) if (!c.getJSONObject(i).optBoolean("thought")) said += c.getJSONObject(i).optString("text", "");
                    } catch (Exception ignored) {}
                    log.append(" · key#").append(ki + 1).append(" ok ").append(ms).append("ms · ").append(said.trim().isEmpty() ? "answered" : clip(said.trim()));
                    // chat uses the streaming endpoint, which is a different code path — probe it on a key that works
                    try {
                        Response sr = HTTP.newCall(post(model, k, ":streamGenerateContent?alt=sse", payload)).execute();
                        int sc = sr.code();
                        boolean got = false;
                        if (sc == 200 && sr.body() != null) {
                            BufferedSource src = sr.body().source();
                            String line;
                            while ((line = src.readUtf8Line()) != null) if (line.startsWith("data:")) { got = true; break; }
                        }
                        log.append(" · stream=").append(sc).append(got ? " ok" : "");
                        if (sc != 200) log.append(' ').append(clip(errorMessage(sr)));
                        sr.close();
                    } catch (Exception e) { log.append(" · stream! ").append(Net.describe(e)); }
                    log.append(" · ").append(liveReport(ctx));
                    return report(out, true, code, log.toString(), model, ms);
                } catch (Throwable e) {
                    lastMsg = Net.describe(e);
                    log.append(" · key#").append(ki + 1).append("! ").append(clip(lastMsg));
                } finally { if (r != null) r.close(); }
            }
            log.append(" · ").append(liveReport(ctx));
            log.append(" · ").append(tried).append(" key(s) tried");
            return report(out, false, code, log.toString(), model, ms);
        } catch (Throwable e) {
            log.append(" · ").append(Net.describe(e));
            return report(out, false, code, log.toString(), model, ms);
        }
    }

    /** What the phone side can really do right now, in one honest line for the settings report. */
    private static String phoneReport(Context ctx) {
        StringBuilder b = new StringBuilder("phone=");
        try {
            boolean on = DanavAccess.on();
            b.append(on ? "control on" : "control OFF");
            b.append(" apps=").append(Phone.canStart(ctx) ? "yes" : "NO");
            if (on && DanavAccess.me != null) {
                String r = DanavAccess.me.read();
                b.append(" screen=").append(r == null || r.trim().isEmpty() ? "unreadable" : (r.trim().length() + " chars"));
                try {
                    String pkg = DanavAccess.me.pkg();
                    if (pkg != null && !pkg.isEmpty()) b.append(" in ").append(DanavAccess.me.appLabel(pkg));
                } catch (Throwable ignored) {}
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                String shot = Phone.shotB64();
                b.append(" shot=").append(shot == null || shot.isEmpty() ? "no" : ((shot.length() * 3 / 4 / 1024) + "KB"));
            } else b.append(" shot=needs-Android-11");
            if (!Phone.canStart(ctx)) b.append(" (").append(Phone.startProblem(ctx)).append(')');
        } catch (Throwable t) { b.append("! ").append(Net.describe(t)); }
        return b.toString();
    }

    /** Opens a real live-voice session and waits for the service to answer — the only honest way to say the call works. */
    private static String liveReport(Context ctx) {
        StringBuilder b = new StringBuilder("live=");
        try {
            List<String> keys = AiKeys.get(ctx);
            if (keys.isEmpty()) return b.append("no key").toString();
            String[] lm = models(ctx, true);
            String mdl = lm == null || lm.length == 0 ? "?" : lm[0];
            final java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicReference<String> fail = new java.util.concurrent.atomic.AtomicReference<>("");
            final Object lock = new Object();
            String key = keys.get(0);
            okhttp3.WebSocket ws = null;
            long t0 = System.currentTimeMillis();
            try {
                Request.Builder rb = new Request.Builder().url(WS_URL).header("x-goog-api-key", key).header("X-Android-Package", ctx.getPackageName());
                if (!AiKeys.certSha1.isEmpty()) rb.header("X-Android-Cert", AiKeys.certSha1);
                ws = HTTP.newWebSocket(rb.build(), new okhttp3.WebSocketListener() {
                    @Override public void onOpen(okhttp3.WebSocket w, Response r) {
                        try {
                            w.send(new JSONObject().put("setup", new JSONObject().put("model", "models/" + mdl)
                                    .put("generationConfig", new JSONObject().put("responseModalities", new JSONArray().put("AUDIO")))).toString());
                        } catch (Exception e) { w.cancel(); }
                    }
                    @Override public void onMessage(okhttp3.WebSocket w, String text) {
                        if (text != null && text.contains("setupComplete")) {
                            ready.set(true);
                            synchronized (lock) { lock.notifyAll(); }
                            w.close(1000, null);
                        }
                    }
                    @Override public void onMessage(okhttp3.WebSocket w, okio.ByteString bytes) { onMessage(w, bytes.utf8()); }
                    @Override public void onFailure(okhttp3.WebSocket w, Throwable t, Response r) {
                        fail.set(r != null ? ("HTTP " + r.code() + " " + clip(r.message())) : Net.describe(t));
                        synchronized (lock) { lock.notifyAll(); }
                    }
                    @Override public void onClosed(okhttp3.WebSocket w, int code, String reason) { synchronized (lock) { lock.notifyAll(); } }
                });
                synchronized (lock) {
                    long until = System.currentTimeMillis() + 9000;
                    while (!ready.get() && fail.get().isEmpty() && System.currentTimeMillis() < until) {
                        try { lock.wait(300); } catch (InterruptedException e) { break; }
                    }
                }
                long ms = System.currentTimeMillis() - t0;
                if (ready.get()) b.append("ok ").append(ms).append("ms · ").append(mdl);
                else b.append("FAILED ").append(fail.get().isEmpty() ? "no answer in 9 s" : fail.get());
            } finally { if (ws != null) try { ws.cancel(); } catch (Throwable ignored) {} }
        } catch (Throwable t) { b.append('!').append(Net.describe(t)); }
        return b.toString();
    }

    private static String report(JSONObject out, boolean ok, int code, String why, String model, long ms) {
        try {
            out.put("ok", ok).put("why", why == null || why.isEmpty() ? "unknown failure" : why).put("code", code)
                    .put("model", model == null ? "" : model).put("ms", ms);
        } catch (Exception ignored) {}
        return out.toString();
    }

    private static String clip(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 190 ? t.substring(0, 190) + "…" : t;
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
                                // Gemini 3.x requires the thoughtSignature of a function call to be sent back with it —
                                // the API answers 400 "Function call is missing a thought_signature ... required for tools
                                // to work correctly" and every multi-step task dies at the second round. The stream can
                                // deliver that signature in its own later part, so it is folded into the call it belongs to.
                                if (p.has("thoughtSignature") && !p.has("functionCall") && !p.has("text") && r.parts.length() > 0) {
                                    JSONObject last = r.parts.getJSONObject(r.parts.length() - 1);
                                    if (last.has("functionCall")) last.put("thoughtSignature", p.optString("thoughtSignature"));
                                    continue;
                                }
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
                line = a.src == null ? null : a.src.readUtf8Line();
            }
            if (!any && ("SAFETY".equals(finish) || "PROHIBITED_CONTENT".equals(finish) || "BLOCKLIST".equals(finish))) { r.code = 400; r.msg = "blocked safety"; return r; }
            if (!any) { r.code = 0; r.msg = "empty"; return r; }
            r.ok = true;
        } catch (Exception e) {
            if (stopped.containsKey(id)) r.canceled = true;
            r.code = 0;
            r.msg = Net.describe(e);
        } finally {
            open.remove(id);
            a.close();
        }
        return r;
    }

    /** Non-streaming request with the same fallback (used for chat titles). */
    static String generate(Context ctx, JSONObject body) {
        List<String> keys = AiKeys.get(ctx);
        int n = keys.size();
        String payload = body.toString();
        String[] ALL = models(ctx, false);
        for (int mi = 0; mi < ALL.length && mi < 4; mi++) {
            if (dead.contains(ALL[mi])) continue;
            for (int k = 0; k < n; k++) {
                int ki = k;
                Long until = rest.get(ALL[mi] + ":" + ki);
                if (until != null && until > System.currentTimeMillis() && k < n - 1) continue;
                Response r = null;
                try {
                    r = HTTP.newCall(post(ALL[mi], keys.get(ki), ":generateContent", payload)).execute();
                    int code = r.code();
                    if (code == 429) rest.put(ALL[mi] + ":" + ki, System.currentTimeMillis() + 45_000);
                    if (code == 404) { dead.add(ALL[mi]); break; }
                    if (code != 200) continue;
                    JSONArray parts = new JSONObject(r.body() == null ? "{}" : r.body().string())
                            .getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < parts.length(); i++) if (!parts.getJSONObject(i).optBoolean("thought")) sb.append(parts.getJSONObject(i).optString("text"));
                    return sb.toString();
                } catch (Exception ignored) {
                } finally { if (r != null) r.close(); }
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
        JSONArray decls = new JSONArray();
        decls.put(gf("web_search", "Search the web for up-to-date information. Returns the top results with title, url and a snippet. Call it several times with different queries for thorough research.",
                props("queries", arr("1 to 3 search queries, each written like a person would type it into a search engine")), req("queries")));
        decls.put(gf("remember", "Save one short personal fact about the user so later chats and voice calls feel personal (name, city, language, people, work, routine, likes, dislikes, how they want answers). Call it silently whenever you learn something durable; never tell the user you saved it.",
                props("note", str("One short sentence in the third person, e.g. \"Loves cricket and supports Pakistan\"")), req("note")));
        decls.put(gf("forget", "Delete a saved personal note that is wrong or that the user asks to drop. Any word of the note is enough to find it.",
                props("query", str("Words from the note to delete")), req("query")));
        decls.put(gf("read_page", "Open a web page (from search results or a link the user gave) and return its readable text.",
                props("url", str("Full http(s) address of the page")), req("url")));
        try {
            // the phone itself: messages, calls, apps, screen control — a name must never be declared twice,
            // the API rejects the whole request if it is (that was why nothing came back)
            java.util.Set<String> have = new java.util.HashSet<>();
            for (int i = 0; i < decls.length(); i++) have.add(decls.getJSONObject(i).optString("name"));
            JSONArray pt = Phone.toolsFor(false, Phone.canStart(ctx));    // never offer a tool the phone will refuse
            JSONArray more = pt.length() > 0 ? pt.getJSONObject(0).optJSONArray("functionDeclarations") : null;
            for (int i = 0; more != null && i < more.length(); i++) {
                JSONObject d = more.getJSONObject(i);
                String nm = d.optString("name");
                if (nm.equals("end_call") || nm.equals("dismiss") || nm.equals("say_on_call")) continue;  // voice-call only
                if (have.add(nm)) decls.put(d);
            }
        } catch (Exception ignored) {}
        return new JSONObject()
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", identity(ctx) + Phone.contextLine(ctx)))))
                .put("contents", contents)
                .put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", decls)))
                .put("generationConfig", new JSONObject().put("thinkingConfig", think));
    }

    // ------------------------------------------------------------------ small builders for the tool declarations

    private static JSONObject str(String desc) throws Exception { return new JSONObject().put("type", "string").put("description", desc); }
    private static JSONObject num(String desc) throws Exception { return new JSONObject().put("type", "integer").put("description", desc); }
    private static JSONObject bool(String desc) throws Exception { return new JSONObject().put("type", "boolean").put("description", desc); }
    private static JSONObject arr(String desc) throws Exception { return new JSONObject().put("type", "array").put("items", str(desc)); }
    private static JSONObject en(String desc, String... values) throws Exception {
        JSONArray a = new JSONArray(); for (String v : values) a.put(v);
        return new JSONObject().put("type", "string").put("description", desc).put("enum", a);
    }
    private static JSONObject props(Object... pairs) throws Exception {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) o.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return o;
    }
    private static JSONArray req(String... names) { JSONArray a = new JSONArray(); for (String n : names) a.put(n); return a; }
    private static JSONObject gf(String name, String desc, JSONObject properties, JSONArray required) throws Exception {
        return new JSONObject().put("name", name).put("description", desc)
                .put("parameters", new JSONObject().put("type", "object").put("properties", properties).put("required", required));
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
