package com.dilhan.danavai;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phone control for Danav AI calls: the tools the live model can call (WhatsApp, calls, SMS, email, YouTube, apps,
 * settings, alarms…) plus generic screen control through {@link DanavAccess}. Every tool returns a short, honest
 * result string that the model reads back to the user.
 */
final class Phone {
    private Phone() {}

    static final String WA = "com.whatsapp", WA_B = "com.whatsapp.w4b", YT = "com.google.android.youtube", GMAIL = "com.google.android.gm";

    // ------------------------------------------------------------------ declarations

    static JSONArray tools(boolean wake) throws Exception { return toolsFor(wake, true); }

    /**
     * The tool list for a model. {@code apps} is whether this phone can really bring an app to the front
     * (accessibility service or "display over other apps"); if it cannot, those tools are left out instead
     * of being offered and then failing — the assistant explains what to switch on and asks for it instead.
     */
    static JSONArray toolsFor(boolean wake, boolean apps) throws Exception {
        JSONArray f = new JSONArray();
        // ---------------------------------------------------------------- eyes
        f.put(fn("read_screen", "Read everything written on the screen right now, with the exact words of every button, label, message and field, and the size of the screen. This is your first look at every task."));
        f.put(fn("see_screen", "Look at the screen as a picture. Use it whenever words are not enough — chats, photos, videos, games, maps, custom apps. The picture comes back to you in the next message; look at it before deciding the next step."));
        f.put(fn("phone_state", "What the phone is doing right now: the app on screen, whether a call is running, whether sound is playing, battery, lock state and screen size. Call it when you need to know the situation before acting."));
        // ---------------------------------------------------------------- hands (the same ones a person uses)
        f.put(fn("open_app", "Open any app on this phone by name (WhatsApp, YouTube, Chrome, Settings, Clock, Maps, Camera, anything the user has). The phone must be unlocked. Then look at the screen and carry on.",
                p("name", "The app's name as the user says it, or its package name")));
        f.put(fn("open_url", "Open a link (http/https) in the phone's browser or the app that handles it.", p("url", "Full http(s) address")));
        f.put(fn("tap", "Tap something on the screen, found by the words on it — exactly as read_screen shows them. Use tap_xy only when the thing has no text (an icon, a picture, a button in a game).",
                p("target", "The text written on the thing to tap (or its closest label)")));
        f.put(fn("tap_xy", "Tap a point on the screen. Give fractions of the screen (0.5,0.9) or pixels; read_screen tells you the screen size.",
                p("x", "Across: fraction 0-1 or pixels"), p("y", "Down: fraction 0-1 or pixels")));
        f.put(fn("long_press", "Press and hold a thing on the screen (opens menus, selects, starts drag modes).", p("target", "The text written on it")));
        f.put(fn("type_text", "Type text into the field that is on screen (whatsapp, search boxes, browsers, notes, any app). It clicks the field first, types, and checks the words really landed.",
                p("text", "The exact text to type"), pb("submit", "true to press the keyboard's Search/Send/Go key after typing")));
        f.put(fn("key", "Press one of the phone's own keys, whatever is on screen.",
                pe("action", "Which key", "back", "home", "recents", "enter", "notifications", "quick_settings", "lock", "screenshot", "wake", "volume_up", "volume_down", "volume_mute", "play_pause", "next", "previous")));
        f.put(fn("scroll", "Move within a scrollable screen (a list, a chat, a page) by a small amount. Scroll and look again instead of assuming something is missing.",
                pe("dir", "Which way", "down", "up", "left", "right"), p("target", "Optional: the list or area to scroll inside")));
        f.put(fn("swipe", "Move whole pages — photos, stories, reels, a gallery, a carousel.",
                pe("dir", "Which way", "left", "right", "up", "down"), pi("pages", "How many pages (1-8)"), p("target", "Optional: the area to swipe inside")));
        f.put(fn("drag", "Drag one thing onto another: sliders, progress bars, dragging items, drawing (patterns, games).",
                p("from", "Where to start (a label, or \"x,y\")"), p("to", "Where to drop (a label, or \"x,y\")"), pi("ms", "Optional: how slowly, in milliseconds")));
        f.put(fn("set_slider", "Set a slider on the screen to a value (brightness, volume page, seek bar).", p("target", "Optional: which slider"), pi("value", "0-100")));
        f.put(fn("wait", "Wait for a slow screen (a page loading, a video opening a call, an app starting) before looking again. Never guess: wait, then look.",
                p("seconds", "How long, 0.2 to 15")));
        // ---------------------------------------------------------------- thinking, memory, web
        f.put(fn("recall", "Look up what you have already done on this phone and what you remember about the user. Call it when the user asks what you did, why something happened, or which step is left — never say you have not done something that is listed here.",
                p("what", "\"actions\", \"memory\" or \"both\" (default: both)")));
        f.put(fn("web_search", "Search the internet when the answer depends on current facts, or when you need to know how something is done.", p("queries", "1 to 3 search queries")));
        f.put(fn("read_page", "Open a web page and read it.", p("url", "Full http(s) address")));
        f.put(fn("remember", "Save one short personal fact about the user (name, city, language, people, work, routine, likes, how they want answers). Call it silently; never announce it.",
                p("note", "One short sentence in the third person")));
        f.put(fn("forget", "Delete a saved personal note that is wrong or that the user asks to drop.", p("query", "Words from the note")));
        // ---------------------------------------------------------------- taking part in a call
        f.put(fn("join_call", "Take part in the call that is on the screen right now — WhatsApp, the phone app, or any app: you hear the other person through the loudspeaker and your voice goes to them, and you answer them yourself like a person. "
                        + "Start a call the normal way first (open the app, find the person, press its call button), then join it.",
                p("task", "What the user wants from this call, in one sentence"), p("say", "Optional: the first thing to say when they answer")));
        f.put(fn("leave_call", "Stop taking part in the call that is running (the user takes the phone back)."));
        f.put(fn("say_on_call", "Say something out loud on the call you have joined, right now — used when the user tells you what to say while you are on a call.", p("text", "The words to say")));
        // ---------------------------------------------------------------- this Danav session
        f.put(fn("end_call", "End the Danav call (when the user is finished with you)."));
        f.put(fn("dismiss", "Put Danav away (when the user says goodbye, go away or stop listening)."));
        return new JSONArray().put(new JSONObject().put("functionDeclarations", f));
    }

    private static JSONObject fn(String name, String desc, Object... parts) throws Exception {
        JSONObject props = new JSONObject();
        JSONArray required = new JSONArray();
        for (Object o : parts) {
            if (o instanceof JSONArray) required = (JSONArray) o;
            else { JSONObject p = (JSONObject) o; props.put(p.getString("_n"), p.getJSONObject("_s")); }
        }
        JSONObject d = new JSONObject().put("name", name).put("description", desc);
        if (props.length() > 0) d.put("parameters", new JSONObject().put("type", "OBJECT").put("properties", props).put("required", required));
        return d;
    }
    private static JSONObject p(String n, String d) throws Exception { return new JSONObject().put("_n", n).put("_s", new JSONObject().put("type", "STRING").put("description", d)); }
    private static JSONObject pi(String n, String d) throws Exception { return new JSONObject().put("_n", n).put("_s", new JSONObject().put("type", "INTEGER").put("description", d)); }
    private static JSONObject pb(String n, String d) throws Exception { return new JSONObject().put("_n", n).put("_s", new JSONObject().put("type", "BOOLEAN").put("description", d)); }
    private static JSONObject pe(String n, String d, String... vals) throws Exception {
        JSONArray e = new JSONArray();
        for (String v : vals) e.put(v);
        return new JSONObject().put("_n", n).put("_s", new JSONObject().put("type", "STRING").put("description", d).put("enum", e));
    }
    private static JSONArray req(String... names) { JSONArray a = new JSONArray(); for (String s : names) a.put(s); return a; }

    /** Short caption for the call screen while a tool runs. */
    static String label(String name, JSONObject a) {
        if (a == null) return "";
        switch (name) {
            case "read_screen": case "see_screen": case "phone_state": return "Looking at the screen";
            case "open_app": return "Opening " + a.optString("name", a.optString("app"));
            case "open_url": return "Opening link";
            case "tap": case "long_press": return (name.equals("long_press") ? "Holding " : "Tapping ") + a.optString("target");
            case "tap_xy": return "Tapping";
            case "type_text": return "Typing";
            case "key": return a.optString("action").replace('_', ' ');
            case "scroll": case "swipe": return (name.equals("swipe") ? "Swiping " : "Scrolling ") + a.optString("dir", "down");
            case "drag": return "Dragging";
            case "set_slider": return "Moving a slider";
            case "wait": return "Waiting";
            case "web_search": return "Searching: " + a.optString("queries", a.optString("query", ""));
            case "read_page": return "Reading a page";
            case "join_call": return "Joining the call";
            case "leave_call": return "Leaving the call";
            case "say_on_call": return "Saying it on the call";
            case "remember": case "forget": return "";      // personalisation stays silent
            default: return "";
        }
    }


    // ------------------------------------------------------------------ running a tool

    static final class Out {
        final String text; final boolean look; final boolean endCall; final boolean dismiss;
        /** Whether the phone really did the thing. Everything that speaks to the user or the model reads this,
         *  so a failure can never be passed off as success (that was the "says it opened / sent it" bug). */
        final boolean ok;
        Out(String t, boolean look) { this(t, look, false, false); }
        Out(String t, boolean look, boolean end, boolean dismiss) {
            text = t; this.look = look; endCall = end; this.dismiss = dismiss; ok = !failed(t);
        }
        Out as(boolean worked) { return new Out(text, look, endCall, dismiss, worked); }
        private Out(String t, boolean look, boolean end, boolean dismiss, boolean worked) {
            text = t; this.look = look; endCall = end; this.dismiss = dismiss; ok = worked;
        }
    }

    /** True when the result text is a failure (used only where the code cannot say so explicitly). */
    private static boolean failed(String t) {
        if (t == null || t.trim().isEmpty()) return true;
        String s = t.toLowerCase(Locale.ROOT);
        String[] bad = {"couldn't", "could not", "can't ", "cannot ", "failed", "wasn't", "didn't", "doesn't", "isn't", "aren't",
                "not found", "no contact", "no app", "no usable", "no message text", "no text field", "no slider", "nothing readable",
                "isn't installed", "not installed", "permission", "phone control is off", "not on the screen", "not supported",
                "blocked", "unavailable", "no answer", "never answered", "didn't go through", "not opened", "nothing was sent",
                "tell the user", "ask the user", "not reachable", "no clock app", "no email app", "nobody has picked up",
                "did not", "not sure", "found no", "has no ", "still playing", "still in the box", "still on screen",
                "nothing started", "ignored the", "give the", "say a level", "no flashlight", "closest ones",
                "no chat with that name", "not start", "never tell the user"};
        for (String b : bad) if (s.contains(b)) return true;
        return false;
    }

    /** A failure the code states out loud — never inferred from the wording. */
    private static Out bad(String t) { return new Out(t, false, false, false, false); }

    /** A failure that also tells the model to look at the screen and find another way. */
    private static Out badLook(String t) { return new Out(t, true, false, false, false); }

    /** True when a tool changes what is on the screen (so a fresh picture must follow it). */
    static boolean isScreenTool(String name) {
        if (name == null) return false;
        switch (name) {
            case "tap": case "tap_xy": case "long_press": case "type_text": case "key": case "scroll":
            case "swipe": case "drag": case "set_slider": case "open_app": case "open_url": case "wait":
                return true;
            default: return false;
        }
    }

    /** True for every tool this class runs (used by the chat, which offers the same phone control). */
    static boolean isPhoneTool(String name) {
        if (name == null) return false;
        for (String n : TOOLS) if (n.equals(name)) return true;
        return false;
    }
    /** Every tool this class runs. Nothing here is about one task: they are hands, eyes and memory. */
    private static final String[] TOOLS = {"read_screen", "see_screen", "phone_state", "open_app", "open_url", "tap", "tap_xy", "long_press",
            "type_text", "key", "scroll", "swipe", "drag", "set_slider", "wait", "recall", "web_search", "read_page", "remember", "forget",
            "join_call", "leave_call", "say_on_call", "end_call", "dismiss"};

    private static final String NEED_ACCESS = "Phone control is off. Tell the user to turn on Danav in Settings → Accessibility (Danav app → Danav AI → Phone control).";

    /**
     * Runs one tool. Every result — success or failure — is also written to {@link Actions}, which is the
     * memory behind "what did you already do", so the assistant can never again forget its own work.
     */
    static Out run(Context c, String name, JSONObject a) {
        Out out = runTool(c, name, a);
        try { Actions.add(c, name, label(name, a), out != null && out.ok); } catch (Throwable ignored) {}
        return out;
    }

    /** True for the tools that need the screen (so they can wake it first and be honest when it is locked). */
    static boolean needsScreen(String name) {
        if (name == null) return false;
        switch (name) {
            case "read_screen": case "see_screen": case "tap": case "tap_xy": case "long_press": case "type_text":
            case "key": case "scroll": case "swipe": case "drag": case "set_slider":
                return true;
            default: return false;
        }
    }

    private static Out runTool(Context c, String name, JSONObject a) {
        try {
            // Touch and reading need the screen awake; and a locked phone must be named as the reason
            // instead of "that thing isn't on the screen" — this is one of the ways actions used to
            // silently fail for the user.
            if (needsScreen(name)) {
                DanavAccess s = DanavAccess.me;
                if (s == null) return bad(NEED_ACCESS);
                wakeScreen(c);
                if (locked(c) && !"wake".equals(name))
                    return badLook("The phone is locked right now, so I can't touch the screen. Unlock it and tell me to go on.");
            }
            switch (name) {
                // ---- eyes
                case "read_screen": return readScreen();
                case "see_screen": return seeScreen();
                case "phone_state": return phoneState(c);
                // ---- hands
                case "open_app": return openApp(c, a.optString("name", a.optString("app")));
                case "open_url": return openUrl(c, a.optString("url"));
                case "tap": return tap(a.optString("target"), false);
                case "tap_xy": return tapXY(a.optString("x"), a.optString("y"), false);
                case "long_press": return tap(a.optString("target"), true);
                case "type_text": return type(a.optString("text"), a.optString("target"), a.optBoolean("submit"));
                case "key": return key(a.optString("action"));
                case "scroll": return scroll(a.optString("dir", "down"), a.optString("target"));
                case "swipe": return swipe(a.optString("dir", "left"), a.optInt("pages", 1), a.optString("target"));
                case "drag": return drag(a.optString("from"), a.optString("to"), a.optInt("ms", 700));
                case "set_slider": return setSlider(a.optString("target"), a.optInt("value", 50));
                case "wait": return waitFor(a.optString("seconds", "1"));
                // ---- brain / memory / web
                case "recall": return recall(c, a.optString("what", "both"));
                case "web_search": return search(a.optString("queries", a.optString("query", "")));
                case "read_page": return readPage(a.optString("url"));
                case "remember": {
                    boolean saved = Memory.add(c, a.optString("note", a.optString("text", "")));
                    if (saved) return new Out("Saved silently. Do not mention it.", false).as(true);
                    return bad("Nothing new to save.");
                }
                case "forget": return new Out(Memory.forget(c, a.optString("query", a.optString("note", ""))) + " note(s) forgotten. Do not mention it.", false);
                // ---- taking part in a call (any app)
                case "join_call": return joinCall(c, a.optString("task"), a.optString("say"));
                case "leave_call": return leaveCall();
                case "say_on_call": {
                    // Only a call Danav has actually joined can be spoken on. Before, the tool "succeeded"
                    // whenever a session was alive — that is how the app came to say "saying it on the call"
                    // with no call at all.
                    if (!LiveCall.bridging()) return bad(callOnScreen().isEmpty()
                            ? "There is no call on the screen, so there is nothing to say on a call. Start the call first (open the app, find the person, press its call button), look at the screen, then join_call."
                            : "A call is on the screen but you have not joined it yet — call join_call first, then say it.");
                    boolean ok = LiveCall.say(a.optString("text"));
                    if (ok) return new Out("Said out loud on the call. Do not repeat it.", false).as(true);
                    return bad("The call went quiet before it could be said — nothing was said.");
                }
                // ---- this Danav session
                case "end_call": return new Out("Ending the call.", false, true, false);
                case "dismiss": return new Out("ok", false, true, true);
                default: return bad("Unknown tool.");
            }
        } catch (SecurityException e) {
            return bad("Android blocked this (permission missing): " + e.getMessage());
        } catch (Exception e) {
            return bad("Failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ contacts

    static final class Person { String name, number, email; int score; long id; }

    static boolean has(Context c, String perm) { return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    private static String norm(String s) {
        return s == null ? "" : java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * Urdu/Arabic letters in rough Latin. Two people write the same name in two scripts all the time
     * ("بلال" in the phone, "Bilal" out loud) and the old matching simply said "no contact with that name".
     */
    private static String roman(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case 'ا': case 'آ': case 'أ': case 'إ': case 'ٱ': case 'ع': b.append('a'); break;
                case 'ب': case 'پ': b.append('b'); break;
                case 'ت': case 'ٹ': case 'ط': b.append('t'); break;
                case 'ث': case 'س': case 'ص': b.append('s'); break;
                case 'ج': b.append('j'); break;
                case 'چ': b.append("ch"); break;
                case 'ح': case 'ہ': case 'ھ': case 'ه': case 'ة': b.append('h'); break;
                case 'خ': b.append("kh"); break;
                case 'د': case 'ڈ': b.append('d'); break;
                case 'ذ': case 'ز': case 'ژ': case 'ض': case 'ظ': b.append('z'); break;
                case 'ر': case 'ڑ': b.append('r'); break;
                case 'ش': b.append("sh"); break;
                case 'غ': b.append("gh"); break;
                case 'ف': b.append('f'); break;
                case 'ق': b.append('q'); break;
                case 'ک': b.append('k'); break;
                case 'گ': b.append('g'); break;
                case 'ل': b.append('l'); break;
                case 'م': b.append('m'); break;
                case 'ن': case 'ں': b.append('n'); break;
                case 'و': case 'ؤ': b.append('w'); break;
                case 'ی': case 'ئ': case 'ي': case 'ے': b.append('y'); break;
                case 'ء': break;
                default: b.append(ch);
            }
        }
        return b.toString();
    }

    /**
     * The consonant core of a name, so the same person written two ways lands on one string:
     * "Bilal" and "بلال" both give "bl"; "Ammi" (Roman) and "امی" (Urdu) both give "m" — the doubled
     * letter is only how the sound is written, never a difference in the name.
     */
    private static String skel(String s) {
        String r = roman(norm(s)).replace(" ", "");
        StringBuilder b = new StringBuilder();
        char last = 0;
        for (int i = 0; i < r.length(); i++) {
            char ch = r.charAt(i);
            if ("aeiouy".indexOf(ch) >= 0) continue;      // vowels are what the two spellings disagree about
            if (ch == last) continue;                     // Ammi = Ami, Muhammad = Mohammad
            b.append(ch);
            last = ch;
        }
        return b.toString();
    }

    /** How well a contact name matches what the user said (0 = not at all). */
    private static int score(String name, String q) {
        String n = norm(name), k = norm(q);
        if (n.isEmpty() || k.isEmpty()) return 0;
        if (k.length() < 3) {
            // one or two letters on their own are almost always a mis-heard word, not a name:
            // only a real match counts ("ha" must never open a chat with "Hamza")
            return n.equals(k) || n.startsWith(k + " ") ? 90 : 0;
        }
        if (n.equals(k)) return 100;
        if (n.startsWith(k + " ")) return 90;
        if ((" " + n + " ").contains(" " + k + " ")) return 80;
        if (n.startsWith(k)) return 70;
        if (n.replace(" ", "").equals(k.replace(" ", ""))) return 95;
        if (n.contains(k)) return 55;
        // every word the user said starts some word of the name ("ali bhai" → "Ali Raza Bhai")
        String[] qw = k.split(" "), nw = n.split(" ");
        int hit = 0;
        for (String w : qw) for (String x : nw) if (x.startsWith(w)) { hit++; break; }
        if (hit == qw.length) return 60;
        // small spelling differences ("Ahmad" ~ "Ahmed", "Fatima" ~ "Fatimah")
        int best = 0;
        for (String x : nw) {
            int d = lev(x, k), m = Math.max(x.length(), k.length());
            if (m >= 4 && d <= (m >= 7 ? 2 : 1)) best = Math.max(best, 45 - d * 5);
        }
        int dAll = lev(n, k);
        if (Math.max(n.length(), k.length()) >= 5 && dAll <= 2) best = Math.max(best, 50 - dAll * 5);
        // the same name in two scripts ("بلال" heard as "Bilal", "امی" heard as "Ammi")
        String sn = skel(name), skk = skel(q);
        if (!sn.isEmpty() && !skk.isEmpty()) {
            if (sn.equals(skk)) best = Math.max(best, sn.length() >= 2 ? 88 : 84);
                // one letter is never enough to "contain" a name ("Abu" must not match "Bilal")
            else if (sn.length() >= 2 && skk.length() >= 2 && (sn.contains(skk) || skk.contains(sn))) best = Math.max(best, 66);
            else if (Math.max(sn.length(), skk.length()) >= 4 && lev(sn, skk) <= 1) best = Math.max(best, 72);
        }
        return best;
    }

    private static int lev(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }







    // ------------------------------------------------------------------ helpers

    static boolean installed(Context c, String pkg) {
        try { c.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    /** Starts an activity from anywhere (the accessibility service and the overlay permission allow background starts). */
    static void start(Context c, Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!canStart(c))
            throw new IllegalStateException("Android blocks opening apps from the background. Turn on Danav AI in Settings → Accessibility (Danav AI → Phone control), or allow \"Display over other apps\".");
        // the visible activity is always the safest place to start from; otherwise the accessibility service
        // (a service bound by the system) or an app that holds "display over other apps" — both are the
        // exemptions Android itself documents for starting activities from the background.
        MainActivity m = MainActivity.inst;
        Context from = (m != null && m.isVisible()) ? m : DanavAccess.me != null ? DanavAccess.me : c.getApplicationContext();
        from.startActivity(i);
    }

    /** Can this app start an activity right now? True when it is visible, or holds one of Android's exemptions. */
    static boolean canStart(Context c) {
        try {
            MainActivity m = MainActivity.inst;
            if (m != null && m.isVisible()) return true;
            if (DanavAccess.me != null) return true;                     // accessibility service: bound by the system
            if (CallBubble.allowed(c)) return true;                      // display over other apps
        } catch (Throwable ignored) {}
        return false;
    }

    /** Why starting apps may fail, or "" when it is fine — shown in the app's settings. */
    static String startProblem(Context c) {
        if (canStart(c)) return "";
        return "Android blocks this app from opening other apps in the background. Turn on Danav AI in Settings → Accessibility (Danav AI → Phone control), or allow \"Display over other apps\" for Danav AI.";
    }

    /** Wakes the screen so an app that is opened is actually visible (not behind a black screen). */
    @SuppressWarnings("deprecation")
    private static void wakeScreen(Context c) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm == null || pm.isInteractive()) return;
            pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP
                    | android.os.PowerManager.ON_AFTER_RELEASE, "danav:open").acquire(4000);
        } catch (Throwable ignored) {}
    }

    private static boolean locked(Context c) {
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) { return false; }
    }

    /** The package on screen right now ("" when the screen can't be read). */
    private static String onScreen() {
        DanavAccess s = DanavAccess.me;
        if (s == null) return "";
        try { return s.pkg(); } catch (Throwable t) { return ""; }
    }

    /**
     * Starts an activity and then really checks that it happened.
     *
     * Before this, every launcher simply fired the intent and reported "Opened X." — on Android 10+ a
     * background start can be refused without any exception, and a locked or sleeping phone shows nothing.
     * That is exactly how the assistant ended up claiming apps it had never opened. Now the launch is
     * verified against the screen, retried once with different flags, and the result says the truth.
     *
     * @param expect package that must come to the front (null = "something other than what is there now")
     * @param what   the name to use in the answer ("WhatsApp", "Settings", …)
     */
    /** Answers "what did you do / what do you know about me" from what the app actually stored. */
    private static Out recall(Context c, String what) {
        String w = what == null ? "" : what.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        if (!w.contains("memory")) {
            String a = Actions.recent(c, 14);
            b.append("What you have already done on this phone, newest first (never claim you have not done something listed here):\n")
                    .append(a.isEmpty() ? "(nothing yet in this phone's log)" : a);
        }
        if (!w.contains("action")) {
            StringBuilder m = new StringBuilder();
            try {
                JSONArray all = Memory.all(c);
                for (int i = 0; i < all.length(); i++) {
                    String t = all.optJSONObject(i) == null ? "" : all.optJSONObject(i).optString("t", "");
                    if (!t.isEmpty()) m.append(m.length() == 0 ? "" : "\n").append("- ").append(t);
                }
            } catch (Throwable ignored) {}
            if (b.length() > 0) b.append("\n\n");
            b.append("What you remember about the user:\n").append(m.length() == 0 ? "(nothing saved yet)" : m.toString());
        }
        return new Out(b.toString(), false).as(true);
    }

    private static Out launch(Context c, Intent i, String expect, String what) { return launch(c, i, expect, what, 0); }

    private static Out launch(Context c, Intent i, String expect, String what, long waitMs) {
        DanavAccess s = DanavAccess.me;
        if (!canStart(c)) return bad("I couldn't open " + what + " because " + startProblem(c));
        boolean wasLocked = locked(c);
        String before = onScreen();
        wakeScreen(c);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // A cold app start while a call is running easily takes 5-10 seconds. Giving up after 4 seconds
        // used to tell the user "it did NOT come to the front" about an app that opened a moment later —
        // exactly the "kabhi ho jata hai kabhi nahi" complaint. So: watch the screen long enough, twice.
        long wait = Math.max(waitMs > 0 ? waitMs : 9000, 9000);
        try {
            start(c, i);
        } catch (Exception e) {
            return bad("I couldn't open " + what + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        if (s == null) return new Out("Opened " + what + ". (Phone control is off, so I can't check the screen.)", false).as(true);
        boolean home = waitForFront(s, expect, before, Math.max(4000, wait - 2500));
        if (!home) {
            // second attempt: a fresh task sometimes gets through where the first one is refused
            try {
                Intent again = new Intent(i);
                again.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                Context from = (MainActivity.inst != null && MainActivity.inst.isVisible()) ? MainActivity.inst : c.getApplicationContext();
                from.startActivity(again);
            } catch (Throwable ignored) {}
            home = waitForFront(s, expect, before, Math.max(4500, wait - 4000));
        }
        String screen = now(s);
        if (home) {
            String extra = wasLocked && locked(c) ? " The phone is locked — unlock it to use it." : "";
            return new Out("Opened " + what + "." + extra + screen, true).as(true);
        }
        if (locked(c)) return badLook("I tried to open " + what + " but the phone is locked, so nothing can come to the screen. Unlock the phone and tell me again." + screen);
        String shown = onScreen();
        String where = shown.isEmpty() ? "the same screen as before" : DanavAccess.me.appLabel(shown) + " (" + shown + ")";
        return badLook("I tried to open " + what + " but it did NOT come to the front — the screen is still showing " + where
                + ". " + startProblem(c) + screen);
    }

    /** Waits for the wanted package (or any change from {@code before}) to reach the front. */
    private static boolean waitForFront(DanavAccess s, String expect, String before, long ms) {
        long end = System.currentTimeMillis() + Math.max(400, ms);
        while (System.currentTimeMillis() < end) {
            String pkg = s.pkg();
            if (expect != null) {
                if (pkg != null && !pkg.isEmpty() && (pkg.equals(expect) || pkg.startsWith(expect))) return true;
                // some apps jump straight to another package (e.g. a browser chooser) — accept a real change
                if (before != null && !before.isEmpty() && !pkg.equals(before) && !"com.android.systemui".equals(pkg)) return true;
            } else if (before != null && !pkg.isEmpty() && !pkg.equals(before)) return true;
            sleep(200);
        }
        return false;
    }

    private static boolean waitForPkg(DanavAccess s, String pkg0, long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            String pkg = s.pkg();
            if (pkg != null && (pkg.equals(pkg0) || pkg.startsWith(pkg0))) return true;
            sleep(200);
        }
        return false;
    }

    private static String enc(String s) { try { return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return ""; } }
    private static void sleep(long ms) { SystemClock.sleep(ms); }
    private static String cut(String s) { return s.length() > 3500 ? s.substring(0, 3500) + "\n…(more below — scroll)" : s; }

    /** After a screen action: let the app settle, then describe the new screen. */
    private static String now(DanavAccess s) { sleep(750); return "\nScreen now:\n" + cut(s.read()); }

    private static String text(AccessibilityNodeInfo n) { CharSequence t = n == null ? null : n.getText(); return t == null ? "" : t.toString(); }

    // ------------------------------------------------------------------ messaging & calls


    // ------------------------------------------------------------------ WhatsApp's own search














    // ------------------------------------------------------------------ media, apps, places


    private static Out openApp(Context c, String name) {
        PackageManager pm = c.getPackageManager();
        String q = name == null ? "" : name.trim();
        if (q.isEmpty()) return new Out("Which app should I open?", false);
        // the user may have said the package name ("com.whatsapp") or something close to it
        String direct = packageFor(pm, q);
        List<ResolveInfo> apps = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        ResolveInfo best = null; int bs = 0; String bl = "";
        java.util.LinkedHashSet<String> near = new java.util.LinkedHashSet<>();
        for (ResolveInfo r : apps) {
            String label = String.valueOf(r.loadLabel(pm));
            int sc = score(label, q);
            if (sc == 0 && norm(r.activityInfo.packageName).replace(" ", "").contains(norm(q).replace(" ", ""))) sc = 40;
            if (sc >= 40) near.add(label);
            if (sc > bs) { bs = sc; best = r; bl = label; }
        }
        if (best != null && bs < 55) {
            // too weak to be sure: opening the wrong app is worse than asking
            StringBuilder sb = new StringBuilder("I'm not sure which app \"" + q + "\" means.");
            if (!near.isEmpty()) {
                sb.append(" The closest ones are: ");
                int i = 0;
                for (String n : near) { if (i++ >= 5) break; sb.append(i > 1 ? ", " : "").append(n); }
            } else {
                sb.append(" No installed app's name is like it.");
            }
            sb.append(" Say the exact name.");
            return bad(sb.toString());
        }
        if (best == null && direct == null) return bad("No app called \"" + q + "\" is installed on this phone.");
        if (best == null) {
            Intent i = pm.getLaunchIntentForPackage(direct);
            if (i == null) return bad("\"" + direct + "\" is installed but it has nothing to open.");
            String label = direct;
            try { label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(direct, 0))); } catch (Exception ignored) {}
            return launch(c, i, direct, label);
        }
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(best.activityInfo.packageName, best.activityInfo.name)).addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return launch(c, i, best.activityInfo.packageName, bl);
    }

    /** The package whose name the user just said, or one that ends with it ("instagram" → com.instagram.android). */
    private static String packageFor(PackageManager pm, String q) {
        String k = norm(q).replace(" ", "");
        if (k.isEmpty()) return null;
        try {
            List<android.content.pm.ApplicationInfo> all = pm.getInstalledApplications(0);
            String bestPkg = null; int bs = 0;
            for (android.content.pm.ApplicationInfo ai : all) {
                String pn = norm(ai.packageName).replace(" ", "");
                int sc = 0;
                if (pn.equals(k)) sc = 100;
                else if (pn.endsWith("." + k) || pn.startsWith(k + ".")) sc = 80;
                else if (pn.contains("." + k)) sc = 60;
                if (sc > bs && pm.getLaunchIntentForPackage(ai.packageName) != null) { bs = sc; bestPkg = ai.packageName; }
            }
            return bs >= 60 ? bestPkg : null;
        } catch (Throwable t) { return null; }
    }



    private static Out openUrl(Context c, String url) {
        String u = url.trim();
        if (!u.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) u = "https://" + u;
        try {
            return launch(c, new Intent(Intent.ACTION_VIEW, Uri.parse(u)), null, u, 8000);
        } catch (Exception e) {
            return bad("I couldn't open " + u + " — no app on this phone handles that link.");
        }
    }

    private static Out search(String q) throws Exception {
        JSONArray r = Web.search(q, 5);
        if (r.length() == 0) return bad("The search didn't return anything right now.");
        StringBuilder sb = new StringBuilder("Results for \"" + q + "\":\n");
        for (int i = 0; i < r.length(); i++) {
            JSONObject o = r.getJSONObject(i);
            sb.append("- ").append(o.optString("title")).append(" (").append(o.optString("host")).append("): ").append(o.optString("snippet")).append('\n');
        }
        return new Out(sb.toString(), false);
    }

    // ------------------------------------------------------------------ device






    // ------------------------------------------------------------------ generic screen control

    /** The phone's own keys — the same ones a person presses, whatever is on screen. */
    private static Out key(String action) {
        DanavAccess s = DanavAccess.me;
        String a = action == null ? "" : action.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        try {
            switch (a) {
                case "back": case "home": case "recents": case "notifications": case "quick_settings": case "lock": case "screenshot": case "wake":
                    if (a.equals("wake")) { wakeScreen(DanavAccess.me != null ? DanavAccess.me : null); return new Out("Woke the screen.", false).as(true); }
                    if (a.equals("lock")) { if (s == null) return bad(NEED_ACCESS); }
                    if (s == null) return bad(NEED_ACCESS);
                    if (!s.global(a)) return bad("That key is not supported on this phone.");
                    sleep(600);
                    return new Out("Pressed " + a.replace('_', ' ') + "." + now(s), true).as(true);
                case "enter":
                    if (s == null) return bad(NEED_ACCESS);
                    return s.enter() ? new Out("Pressed enter." + now(s), true).as(true) : bad("Couldn't press enter — no field on screen has the keyboard.");
                case "volume_up": case "volume_down": case "volume_mute": {
                    AudioManager am = (AudioManager) c(AUDIO);
                    if (am == null) return bad("This phone gives no volume control.");
                    int dir = a.equals("volume_up") ? AudioManager.ADJUST_RAISE : a.equals("volume_down") ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_TOGGLE_MUTE;
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
                    return new Out("Volume " + a.replace("volume_", "") + "." + now3(), false).as(true);
                }
                case "play_pause": case "next": case "previous": {
                    AudioManager am = (AudioManager) c(AUDIO);
                    if (am == null) return bad("This phone gives no media keys.");
                    boolean playing = am.isMusicActive();
                    int code = a.equals("next") ? KeyEvent.KEYCODE_MEDIA_NEXT : a.equals("previous") ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                            : (playing ? KeyEvent.KEYCODE_MEDIA_PAUSE : KeyEvent.KEYCODE_MEDIA_PLAY);
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                    sleep(700);
                    if (a.equals("play_pause")) {
                        boolean after = am.isMusicActive();
                        if (playing && after) return bad("The sound is still playing — the app ignored the pause key." + now3());
                        if (!playing && !after) return bad("Nothing started playing — no music or video is playing anything." + now3());
                        return new Out((playing ? "Paused." : "Playing.") + now3(), false).as(true);
                    }
                    return new Out("Sent " + a + " to the player." + now3(), false).as(true);
                }
                default: return bad("Unknown key. Use back, home, recents, enter, notifications, quick_settings, lock, screenshot, wake, volume_up/down/mute, play_pause, next, previous.");
            }
        } catch (Throwable t) { return bad("That key failed: " + Net.describe(t)); }
    }

    private static final String AUDIO = Context.AUDIO_SERVICE;
    private static Object c(String service) { Context x = DanavAccess.me; return x == null ? null : x.getSystemService(service); }

    /** Waits, then tells the model what the screen looks like now — "wait, then look", never guess. */
    private static Out waitFor(String seconds) {
        double sec;
        try { sec = Double.parseDouble(seconds); } catch (Exception e) { sec = 1; }
        sec = Math.max(0.2, Math.min(15, sec));
        sleep((long) (sec * 1000));
        DanavAccess s = DanavAccess.me;
        return new Out("Waited " + (sec >= 1 ? Math.round(sec) + " s" : Math.round(sec * 1000) + " ms") + "." + (s == null ? "" : now(s)), true).as(true);
    }

    /** The situation right now, so the model never has to ask the user what is on his own phone. */
    private static Out phoneState(Context ctx) {
        StringBuilder b = new StringBuilder("Phone right now:");
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            b.append(" it is ").append(new java.text.SimpleDateFormat("EEEE h:mm a", Locale.ENGLISH).format(cal.getTime()));
            DanavAccess s = DanavAccess.me;
            if (s != null) {
                String pkg = s.pkg();
                if (pkg != null && !pkg.isEmpty()) b.append(", on screen: ").append(s.appLabel(pkg)).append(" (").append(pkg).append(')');
                b.append(", screen ").append(s.getResources().getDisplayMetrics().widthPixels).append('x')
                        .append(s.getResources().getDisplayMetrics().heightPixels);
            } else b.append(", phone control is off");
            b.append(", ").append(locked(ctx) ? "the phone is LOCKED" : "unlocked");
            try {
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) b.append(", sound ").append(am.isMusicActive() ? "playing" : "not playing");
            } catch (Throwable ignored) {}
            try {
                android.os.BatteryManager bm = (android.os.BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
                int lvl = bm == null ? -1 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (lvl >= 0) b.append(", battery ").append(lvl).append('%');
            } catch (Throwable ignored) {}
            String call = callOnScreen();
            b.append(", ").append(call.isEmpty() ? "no call on the screen" : "a call looks open with \"" + call + "\"");
            if (LiveCall.bridging()) b.append(" (you are on that call right now)");
            b.append(".");
        } catch (Throwable t) { b.append(" (couldn't read it: ").append(Net.describe(t)).append(')'); }
        return new Out(b.toString(), false).as(true);
    }

    /** Is a call really on the screen right now? Used by the bridge to know when the call is over. */
    static String callNow() { return callOnScreen(); }

    /** The name at the top of a call screen, or "" when no call screen is open. */
    private static String callOnScreen() {
        DanavAccess s = DanavAccess.me;
        if (s == null) return "";
        try {
            String pkg = s.pkg();
            if (pkg == null) return "";
            boolean calling = pkg.contains("dialer") || pkg.contains("incallui") || pkg.contains("telecom") || pkg.contains("whatsapp");
            if (!calling) return "";
            String t = s.read();
            if (t == null) return "";
            boolean looksLikeCall = t.contains("End call") || t.contains("end call") || t.contains("Mute") && t.contains("Speaker")
                    || Pattern.compile("\\b\\d{1,2}:\\d{2}\\b").matcher(t).find();
            if (!looksLikeCall) return "";
            for (String line : t.split("\n")) {
                String l = line.trim();
                if (l.isEmpty() || l.length() > 40) continue;
                if (l.matches("[0-9:+.\\s]+")) continue;
                String low = l.toLowerCase(Locale.ROOT);
                if (low.startsWith("end call") || low.startsWith("mute") || low.startsWith("speaker") || low.startsWith("video")
                        || low.startsWith("add call") || low.startsWith("keypad") || low.startsWith("hold") || low.startsWith("search")
                        || low.startsWith("call") || low.startsWith("back") || low.startsWith("message")) continue;
                return l;
            }
            return "the person you called";
        } catch (Throwable t) { return ""; }
    }

    /** Takes part in the call that is already on the screen — whatever app placed it. */
    private static Out joinCall(Context ctx, String task, String say) {
        if (DanavAccess.me == null) return bad(NEED_ACCESS);
        if (locked(ctx)) return badLook("The phone is locked, so I can't see or join any call. Unlock it and tell me again.");
        String who = callOnScreen();
        if (who.isEmpty()) return badLook("I don't see a call on the screen, so there is nothing to join. Open the call first (open the app, find the person, press its call button), then look at the screen and join it.");
        if (LiveCall.bridging()) return new Out("Already on the call with " + who + ". Say what the user wants said.", false).as(true);
        LiveCall.bridge(who, task == null ? "" : task);
        if (say != null && !say.trim().isEmpty()) { sleep(700); LiveCall.say(say.trim()); }
        return new Out("You are on the call with " + who + " now: their voice comes out of the loudspeaker and into your microphone, and your voice goes out of the loudspeaker to them — "
                + "the app keeps the loudspeaker on and the music stream open for as long as the call lasts. Talk with them in the user's language, short sentences, never repeat yourself. "
                + "If no sound from them reaches you at all, say that plainly once — never pretend you heard.", false).as(true);
    }

    private static Out leaveCall() {
        if (!LiveCall.bridging()) return bad("You are not on a call right now.");
        LiveCall.endBridge();
        return new Out("You left the call; the user has the phone back.", false).as(true);
    }

    /** Opens a web page and returns its readable text (a small, honest wrapper around the web reader). */
    private static Out readPage(String url) {
        try {
            JSONObject r = Web.read(url, 24000);
            if (r.has("error")) return bad("I couldn't read that page: " + r.optString("error"));
            String t = r.optString("text", "");
            return new Out("Page: " + r.optString("title", url) + "\n" + cut(t), false).as(true);
        } catch (Throwable t) { return bad("I couldn't read that page: " + Net.describe(t)); }
    }

    private static Out readScreen() {
        DanavAccess s = DanavAccess.me;
        if (s == null) return bad(NEED_ACCESS);
        if (s.root() == null) return badLook("I can't read the screen right now — Android is giving me no screen at all. Phone control may have been switched off, or another app is covering the screen.");
        String t = s.readSettled(1400);                       // let a page finish loading before reporting it
        if (t == null || t.trim().isEmpty() || t.startsWith("Screen can't be read"))
            return badLook("The screen is there but nothing readable is on it (the app draws its own UI). Use see_screen to look at it as a picture." + now(s));
        return new Out(cut(t), true);
    }

    /** Looks at the screen: the live call sends the picture itself, the chat attaches it to the conversation. */
    private static Out seeScreen() {
        DanavAccess s = DanavAccess.me;
        if (s == null) return bad(NEED_ACCESS);
        String shot = shotB64();
        if (shot == null || shot.isEmpty()) return bad("This phone doesn't give Danav a screenshot of the screen (Android 11 or newer is needed). Use read_screen instead.");
        return new Out("The screen is attached as a picture — look at it now and act on what you see." + now(s), true).as(true);
    }

    /** What media is doing right now, in a few words. */
    private static String now3() {
        try {
            AudioManager am = (AudioManager) DanavAccess.me.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && am.isMusicActive()) return " Something is playing right now.";
        } catch (Throwable ignored) {}
        return "";
    }

    /** A very short "what is on screen now", without the full dump (used by callers that speak). */
    private static String now2() {
        DanavAccess s = DanavAccess.me;
        if (s == null) return "";
        try {
            String pkg = s.pkg();
            if (pkg == null || pkg.isEmpty()) return "";
            return " Right now the phone is showing " + s.appLabel(pkg) + ".";
        } catch (Throwable t) { return ""; }
    }

    private static Out tap(String target, boolean longPress) { return tap(target, longPress, false); }

    private static Out tap(String target, boolean longPress, boolean doubleTap) {
        DanavAccess s = DanavAccess.me;
        if (s == null) return bad(NEED_ACCESS);
        AccessibilityNodeInfo n = s.find(target, false);
        if (n == null) n = s.findScroll(target, 3);            // it may be further down the list — go look for it
        if (n == null) return badLook("\"" + target + "\" isn't on the screen." + now(s));
        String what = DanavAccess.label(n);
        boolean ok = s.click(n, longPress);
        if (ok && doubleTap) { sleep(90); ok = s.click(n, false); }
        if (!ok) return bad("Couldn't tap \"" + what + "\".");
        return new Out("Tapped \"" + what + "\"." + now(s), true);
    }

    /** x and y are screen fractions (0-1), percentages or plain pixels — whichever the model sends. */
    private static float[] point(String x, String y) {
        int w = 0, h = 0;
        if (DanavAccess.me != null) { w = DanavAccess.me.getResources().getDisplayMetrics().widthPixels; h = DanavAccess.me.getResources().getDisplayMetrics().heightPixels; }
        float px = frac(x), py = frac(y);
        return new float[]{px <= 1f ? px * w : px, py <= 1f ? py * h : py};
    }

    private static float frac(String v) {
        if (v == null) return 0;
        String t = v.trim().replace("%", "");
        try { return Float.parseFloat(t); } catch (Exception e) { return 0; }
    }

    private static Out tapXY(String x, String y, boolean longPress) {
        DanavAccess s = DanavAccess.me;
        if (s == null) return bad(NEED_ACCESS);
        float[] p = point(x, y);
        if (!s.tapAt(p[0], p[1], longPress ? 700 : 60)) return bad("Couldn't tap there.");
        return new Out("Tapped at " + Math.round(p[0]) + "," + Math.round(p[1]) + "." + now(s), true);
    }

    private static Out drag(String from, String to, int ms) {
        DanavAccess s = DanavAccess.me;
        if (s == null) return bad(NEED_ACCESS);
        float[] a = null, b = null;
        if (from != null && from.contains(",")) { String[] p = from.split(","); a = point(p[0], p[1]); }
        else if (from != null && !from.isEmpty()) { AccessibilityNodeInfo n = s.find(from, false); if (n != null) a = centerOf(n); }
        if (to != null && to.contains(",")) { String[] p = to.split(","); b = point(p[0], p[1]); }
        else if (to != null && !to.isEmpty()) { AccessibilityNodeInfo n = s.find(to, false); if (n != null) b = centerOf(n); }
        if (a == null || b == null) return bad("Tell me both points, e.g. from \"0.2,0.8\" to \"0.8,0.8\".");
        if (!s.drag(a[0], a[1], b[0], b[1], ms)) return bad("The drag didn't go through.");
        return new Out("Dragged." + now(s), true);
    }

    private static float[] centerOf(AccessibilityNodeInfo n) {
        android.graphics.Rect r = new android.graphics.Rect();
        n.getBoundsInScreen(r);
        return new float[]{r.exactCenterX(), r.exactCenterY()};
    }

    private static Out setSlider(String target, int value) {
        DanavAccess s = DanavAccess.me;
        if (s == null) return bad(NEED_ACCESS);
        AccessibilityNodeInfo n = target == null || target.isEmpty() ? null : s.find(target, false);
        if (n == null) n = s.find("slider", false);            // a node the list called "slider"
        if (n == null) return badLook("No slider on the screen. read_screen first.");
        if (!s.setSlider(n, value)) return bad("Couldn't move that slider.");
        return new Out("Slider set to about " + Math.max(0, Math.min(100, value)) + "%." + now(s), true);
    }

    private static Out type(String text, String target, boolean submit) {
        DanavAccess s = DanavAccess.me;
        if (s == null) return bad(NEED_ACCESS);
        AccessibilityNodeInfo n = null;
        if (target != null && !target.isEmpty()) n = s.find(target, true);
        if (n == null) { AccessibilityNodeInfo r = s.root(); if (r != null) n = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT); }
        if (n == null || !n.isEditable()) n = s.find("", true);
        if (n == null) return badLook("No text field on screen." + now(s));
        if (!s.setText(n, text)) return bad("Couldn't type into that field.");
        if (submit) { sleep(150); s.submit(n); }
        return new Out("Typed it." + now(s), true);
    }

    private static Out scroll(String dir, String target) {
        DanavAccess s = DanavAccess.me;
        if (s == null) return bad(NEED_ACCESS);
        AccessibilityNodeInfo n = target == null || target.isEmpty() ? null : s.find(target, false);
        if (!s.scroll(dir, n)) return bad("Couldn't scroll.");
        return new Out("Scrolled " + dir + "." + now(s), true);
    }

    /**
     * A whole-page movement: photos, stories, Reels, pages, or several screens of a list. One long, smooth
     * finger move per page — far more reliable than tapping "next" or many small scrolls.
     */
    private static Out swipe(String dir, int pages, String target) {
        DanavAccess s = DanavAccess.me;
        if (s == null) return bad(NEED_ACCESS);
        int w = s.getResources().getDisplayMetrics().widthPixels, h = s.getResources().getDisplayMetrics().heightPixels;
        int n = Math.max(1, Math.min(8, pages));
        float y0 = h * 0.62f, y1 = h * 0.38f, x0 = w * 0.82f, x1 = w * 0.18f;   // a normal, gentle swipe
        float cx = w * 0.5f, cy = h * 0.5f;
        if (target != null && !target.isEmpty()) {
            AccessibilityNodeInfo t = s.find(target, false);
            if (t != null) {
                android.graphics.Rect r = new android.graphics.Rect(); t.getBoundsInScreen(r);
                if (r.width() > 40 && r.height() > 40) {
                    cx = r.exactCenterX(); cy = r.exactCenterY();
                    x0 = r.left + r.width() * 0.85f; x1 = r.left + r.width() * 0.15f;
                    y0 = r.top + r.height() * 0.72f; y1 = r.top + r.height() * 0.28f;
                }
            }
        }
        boolean ok = true;
        for (int i = 0; i < n && ok; i++) {
            switch (dir) {
                case "right": ok = s.swipe(x1, cy, x0, cy, 260); break;
                case "up": ok = s.swipe(cx, y0, cx, y1, 260); break;
                case "down": ok = s.swipe(cx, y1, cx, y0, 260); break;
                default: ok = s.swipe(x0, cy, x1, cy, 260); break;   // left
            }
            sleep(320);                                            // let the page settle before the next one
        }
        if (!ok) return bad("The swipe didn't go through.");
        return new Out("Moved " + n + (n == 1 ? " page " : " pages ") + dir + "." + now(s), true);
    }


    /**
     * A real, on-device proof that Danav can drive the phone: it looks at the screen, takes a picture of it and
     * then opens the phone's Settings and checks whether Settings actually came to the front — the very thing the
     * user used to be told had worked when it had not. It comes straight back to Danav at the end.
     * Run it from Settings → Test phone control. The answer is the truth, in the user's own hands.
     */
    static JSONObject prove(Context c) {
        JSONObject o = new JSONObject();
        long t0 = System.currentTimeMillis();
        try {
            DanavAccess s = DanavAccess.me;
            o.put("control", s != null);
            if (s == null) {
                o.put("ok", false).put("step", "Phone control is off, so Danav cannot touch the screen at all.")
                        .put("fix", startProblem(c)).put("ms", System.currentTimeMillis() - t0);
                return o;
            }
            try { o.put("from", s.appLabel(s.pkg())); } catch (Throwable ignored) {}
            String t = s.read();
            o.put("read", t == null ? 0 : t.trim().length());
            o.put("shot", android.os.Build.VERSION.SDK_INT >= 30 ? (shotB64() == null ? 0 : 1) : -1);
            Out out = launch(c, new Intent(android.provider.Settings.ACTION_SETTINGS), "com.android.settings", "the phone's Settings", 7000);
            o.put("ok", out.ok).put("said", out.text);
            if (!out.ok) o.put("fix", startProblem(c));
            else { try { o.put("now", s.appLabel(s.pkg())); } catch (Throwable ignored) {} }
            o.put("ms", System.currentTimeMillis() - t0);
            // never leave the user sitting in Settings: come straight back to Danav
            try {
                Intent back = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
                if (back != null) { back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP); c.startActivity(back); }
            } catch (Throwable ignored) {}
            return o;
        } catch (Throwable e) {
            try { o.put("ok", false).put("step", "The test itself hit a problem: " + Net.describe(e)).put("fix", startProblem(c)); } catch (Exception ignored) {}
            return o;
        }
    }

    /** What the phone looks like right now, added to the call's instructions so Danav knows the situation. */
    static String contextLine(Context who) {
        try {
            StringBuilder sb = new StringBuilder("\n\nPhone right now:");
            java.util.Calendar c = java.util.Calendar.getInstance();
            sb.append(" it is ").append(new java.text.SimpleDateFormat("EEEE h:mm a", java.util.Locale.ENGLISH).format(c.getTime()));
            Context ctx = DanavAccess.me != null ? DanavAccess.me : who;
            if (ctx != null) {
                try {
                    android.os.BatteryManager bm = (android.os.BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
                    int lvl = bm == null ? -1 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    if (lvl >= 0) sb.append(", battery ").append(lvl).append('%');
                } catch (Throwable ignored) {}
                try {
                    String pkg = DanavAccess.me.pkg();
                    if (pkg != null && !pkg.isEmpty()) sb.append(", the user is in ").append(DanavAccess.me.appLabel(pkg));
                } catch (Throwable ignored) {}
            }
            sb.append(". Use this instead of asking.");
            try {
                String acts = Actions.recent(ctx, 6);
                if (!acts.isEmpty()) sb.append("\n\nWhat you already did on this phone, newest first — the user has just seen these happen, so never say you have not done something that is listed here and never ask which step to do next:\n").append(acts);
            } catch (Throwable ignored) {}
            try {
                JSONArray mem = Memory.all(ctx);
                if (mem.length() > 0) {
                    sb.append("\n\nWhat you remember about the user:\n");
                    for (int i = 0; i < mem.length() && i < 12; i++) {
                        String t = mem.optJSONObject(i) == null ? "" : mem.optJSONObject(i).optString("t", "");
                        if (!t.isEmpty()) sb.append("- ").append(t).append('\n');
                    }
                }
            } catch (Throwable ignored) {}
            return sb.toString();
        } catch (Throwable e) { return ""; }
    }

    /** JPEG of the screen as base64 for the live model, or null. */
    static String shotB64() {
        DanavAccess s = DanavAccess.me;
        byte[] b = s == null ? null : s.shot();
        return b == null ? null : Base64.encodeToString(b, Base64.NO_WRAP);
    }
}
