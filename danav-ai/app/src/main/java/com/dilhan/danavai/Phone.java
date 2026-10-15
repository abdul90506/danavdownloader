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
        f.put(fn("whatsapp_message", "Send a WhatsApp message to a person by name (or number). Sends it right away. If the phone's contacts don't have the name, the app searches inside WhatsApp itself, so never say \"not found\" without this call.",
                p("contact", "Contact name as the user said it, or a phone number"), p("message", "The message text, in the language/script the user wants"), req("contact", "message")));
        f.put(fn("whatsapp_call", "Start a WhatsApp voice or video call with a person and then really talk with them on it: Danav speaks out of the loudspeaker and hears them back through the microphone, "
                        + "so it is a two-way conversation. If the phone's contacts don't have the name, the app searches inside WhatsApp itself. Use this whenever the user asks you to call somebody on WhatsApp, "
                        + "to talk to them, to ask them something, to convince them or to explain something. Put the user's instruction in task (and what to open with in say).",
                p("contact", "Contact name or number"), pb("video", "true for a video call"),
                p("say", "What to say as soon as the call connects, in the user's words"), p("task", "What the user wants you to talk about / ask / convince them of"),
                req("contact")));
        f.put(fn("say_on_call", "Say something out loud on the call that is already running (your own words, speakable in a second).",
                p("text", "Exactly what to say, in the user's language"), req("text")));
        f.put(fn("hangup_call", "End the WhatsApp / phone call that is on screen now.", req()));
        f.put(fn("phone_call", "Place a normal phone call to a contact name or number.", p("contact", "Contact name or number"), req("contact")));
        f.put(fn("sms_message", "Send a normal text message (SMS).", p("contact", "Contact name or number"), p("message", "Message text"), req("contact", "message")));
        f.put(fn("send_email", "Write and send an email (Gmail).", p("to", "Email address or contact name"), p("subject", "Subject"), p("body", "Email body"), req("to", "body")));
        f.put(fn("youtube_play", "Find and play a video or song on YouTube.", p("query", "What to play"), req("query")));
        f.put(fn("open_app", "Open an installed app by its name.", p("name", "App name, e.g. Instagram, Camera, Settings"), req("name")));
        f.put(fn("media_control", "Control music / video that is playing in any app.", pe("action", "play, pause, next, previous, stop", "play", "pause", "next", "previous", "stop"), req("action")));
        f.put(fn("navigate", "Directions or map search in Google Maps.", p("destination", "Place or address"), pe("mode", "Travel mode", "driving", "walking", "transit", "bike"), req("destination")));
        f.put(fn("open_settings", "Open a settings page; then use read_screen / tap to change the setting.",
                pe("page", "Which page", "main", "wifi", "bluetooth", "mobile_data", "hotspot", "airplane", "display", "sound", "battery", "location", "apps", "notifications",
                        "storage", "security", "accessibility", "date_time", "language", "nfc", "dnd", "about", "danav"), req("page")));
        f.put(fn("set_volume", "Change a volume.", pe("stream", "Which volume", "media", "ring", "alarm", "call"), p("level", "0-100, or up / down / mute / max"), req("level")));
        f.put(fn("flashlight", "Turn the flashlight on or off.", pb("on", "true = on"), req("on")));
        f.put(fn("set_alarm", "Set an alarm.", pi("hour", "0-23"), pi("minute", "0-59"), p("label", "Optional label"), req("hour", "minute")));
        f.put(fn("set_timer", "Start a countdown timer.", pi("seconds", "Length in seconds"), p("label", "Optional label"), req("seconds")));
        f.put(fn("read_screen", "Read everything on the screen as text with a number before each item. Use the numbers with tap (\"7\") and type_text.", req()));
        f.put(fn("see_screen", "Look at the screen as a picture. The screenshot is sent to you and you can see it. "
                + "Use it when read_screen looks empty, wrong or confusing (apps that draw their own UI: games, Reels, stories, chat apps), "
                + "when you need to check a colour, an image, an icon or what actually happened after a tap, or right before a tricky tap.", req()));
        f.put(fn("tap", "Tap something on screen: the number from read_screen, or its visible text. Works on things further down a list too (it scrolls to find them).",
                p("target", "Number like 7, or text like Send"), pb("long_press", "Long-press instead"), pb("double", "Double-tap instead"), req("target")));
        f.put(fn("tap_xy", "Tap an exact spot on the screen (use only when read_screen can't name the thing, e.g. a custom drawing). Use the same scale as the screenshot: x,y as screen fractions 0-1 or percentages like 45%,70%.",
                p("x", "X position: 0-1 fraction, e.g. 0.45"), p("y", "Y position: 0-1 fraction, e.g. 0.7"), pb("long_press", "Long-press instead"), req("x", "y")));
        f.put(fn("drag", "Slide / drag on the screen — sliders, brightness, volume panels, holding a button, swiping a notification or a story. Give start and end points (fractions like 0.2,0.8 or numbers or node targets).",
                p("from", "Start point, e.g. \"0.2,0.8\" or the text of the element"), p("to", "End point, e.g. \"0.8,0.8\""), pi("ms", "How long the drag takes in milliseconds, default 500"), req("from", "to")));
        f.put(fn("set_slider", "Move a slider (music position, volume, brightness, video quality) to an exact value between 0 and 100.",
                p("target", "The slider: its number from read_screen, or nearby text"), pi("value", "0-100"), req("target", "value")));
        f.put(fn("type_text", "Type text into an input field (the focused one, or the given target).", p("text", "Text to type"), p("target", "Optional field number or text"),
                pb("submit", "Press enter / search / send after typing"), req("text")));
        f.put(fn("scroll", "Scroll the screen a little (lists, feeds).", pe("direction", "Direction", "down", "up", "left", "right"), p("target", "Optional list number"), req("direction")));
        f.put(fn("swipe", "Move a whole page at a time — photos, stories, carousels, pages in an app, Reels/Shorts, or several screens of a list at once. Smoother and more accurate than repeated taps.",
                pe("direction", "Which way the page moves", "left", "right", "up", "down"), pi("pages", "How many pages (default 1)"), p("target", "Optional list / area number"), req("direction")));
        f.put(fn("system_action", "Phone buttons and panels.", pe("action", "Action", "back", "home", "recents", "notifications", "quick_settings", "lock", "screenshot", "power_menu"), req("action")));
        f.put(fn("open_url", "Open a website or link.", p("url", "Full URL"), req("url")));
        f.put(fn("web_search", "Search the web for current information.", p("query", "Search query"), req("query")));
        f.put(fn("recall", "Look up what you have already done on this phone and what you remember about the user. Call it when the user asks what you did, why something happened, or which step is left — never say you have not done something that is listed here.",
                p("what", "\"actions\", \"memory\" or \"both\" (default: both)")));
        f.put(fn("remember", "Save one short personal fact about the user so later calls and chats feel personal (name, city, language, family, work, routine, likes, "
                        + "dislikes, how they want answers). Call it silently whenever you learn something durable — never announce it or repeat it back.",
                p("note", "One short sentence in the third person, e.g. \"Speaks Roman Urdu and works nights\""), req("note")));
        f.put(fn("forget", "Delete a saved personal note that is wrong or that the user asks to drop.", p("query", "Words from the note to delete"), req("query")));
        f.put(fn("end_call", "End this Danav call — only when the user says bye, bas or band karo.", req()));
        if (wake) f.put(fn("dismiss", "The phone started listening by mistake: nobody called Danav. Ends quietly.", req()));
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
        switch (name) {
            case "whatsapp_message": return "WhatsApp → " + a.optString("contact");
            case "whatsapp_call": return "WhatsApp call → " + a.optString("contact");
            case "say_on_call": return "Saying it on the call";
            case "hangup_call": return "Ending the call";
            case "swipe": return "Swiping " + a.optString("direction");
            case "phone_call": return "Calling " + a.optString("contact");
            case "sms_message": return "SMS → " + a.optString("contact");
            case "send_email": return "Email → " + a.optString("to");
            case "youtube_play": return "YouTube: " + a.optString("query");
            case "open_app": return "Opening " + a.optString("name");
            case "media_control": return "Media: " + a.optString("action");
            case "navigate": return "Maps: " + a.optString("destination");
            case "open_settings": return "Settings: " + a.optString("page");
            case "set_volume": return "Volume";
            case "flashlight": return a.optBoolean("on") ? "Flashlight on" : "Flashlight off";
            case "set_alarm": return "Alarm";
            case "set_timer": return "Timer";
            case "read_screen": return "Looking at the screen";
            case "see_screen": return "Looking at the screen";
            case "tap": return "Tap: " + a.optString("target");
            case "tap_xy": return "Tapping";
            case "drag": return "Sliding";
            case "set_slider": return "Sliding to " + a.optInt("value", 50) + "%";
            case "type_text": return "Typing";
            case "scroll": return "Scrolling";
            case "system_action": return a.optString("action").replace('_', ' ');
            case "open_url": return "Opening link";
            case "web_search": return "Searching: " + a.optString("query");
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

    /** True for every tool this class runs (used by the chat, which offers the same phone control). */
    static boolean isPhoneTool(String name) {
        if (name == null) return false;
        for (String n : TOOLS) if (n.equals(name)) return true;
        return false;
    }
    private static final String[] TOOLS = {"recall", "whatsapp_message", "whatsapp_call", "say_on_call", "hangup_call", "phone_call", "sms_message", "send_email",
            "youtube_play", "open_app", "media_control", "navigate", "open_settings", "set_volume", "flashlight", "set_alarm", "set_timer", "read_screen", "see_screen",
            "tap", "tap_xy", "drag", "set_slider", "type_text", "scroll", "swipe", "system_action", "open_url", "web_search", "remember", "forget", "end_call", "dismiss"};

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

    private static Out runTool(Context c, String name, JSONObject a) {
        try {
            switch (name) {
                case "recall": return recall(c, a == null ? "" : a.optString("what", "both"));
                case "whatsapp_message": return whatsapp(c, a.optString("contact"), a.optString("message"));
                case "whatsapp_call": return whatsappCall(c, a.optString("contact"), a.optBoolean("video"), a.optString("say"), a.optString("task"));
                case "say_on_call": {
                    boolean ok = LiveCall.say(a.optString("text"));
                    if (ok) return new Out("Said out loud on the call. Do not repeat it.", false).as(true);
                    return bad("There is no call running to speak on.");
                }
                case "hangup_call": return hangup(c);
                case "phone_call": return call(c, a.optString("contact"));
                case "sms_message": return sms(c, a.optString("contact"), a.optString("message"));
                case "send_email": return email(c, a.optString("to"), a.optString("subject"), a.optString("body"));
                case "youtube_play": return youtube(c, a.optString("query"));
                case "open_app": return openApp(c, a.optString("name"));
                case "media_control": return media(c, a.optString("action"));
                case "navigate": return navigate(c, a.optString("destination"), a.optString("mode"));
                case "open_settings": return settings(c, a.optString("page"));
                case "set_volume": return volume(c, a.optString("stream", "media"), a.optString("level"));
                case "flashlight": return torch(c, a.optBoolean("on", true));
                case "set_alarm": return alarm(c, a.optInt("hour", -1), a.optInt("minute", 0), a.optString("label"));
                case "set_timer": return timer(c, a.optInt("seconds", 0), a.optString("label"));
                case "read_screen": return readScreen();
                case "see_screen": return seeScreen();
                case "tap": return tap(a.optString("target"), a.optBoolean("long_press"), a.optBoolean("double"));
                case "tap_xy": return tapXY(a.optString("x"), a.optString("y"), a.optBoolean("long_press"));
                case "drag": return drag(a.optString("from"), a.optString("to"), a.optInt("ms", 500));
                case "set_slider": return setSlider(a.optString("target"), a.optInt("value", 50));
                case "type_text": return type(a.optString("text"), a.optString("target"), a.optBoolean("submit"));
                case "scroll": return scroll(a.optString("direction", "down"), a.optString("target"));
                case "swipe": return swipe(a.optString("direction", "left"), a.optInt("pages", 1), a.optString("target"));
                case "system_action": return system(a.optString("action"));
                case "open_url": return openUrl(c, a.optString("url"));
                case "web_search": return search(a.optString("query"));
                case "remember": {
                    boolean saved = Memory.add(c, a.optString("note", a.optString("text", "")));
                    return saved ? new Out("Saved silently. Do not mention it.", false).as(true) : bad("Nothing new to save.");
                }
                case "forget": return new Out(Memory.forget(c, a.optString("query", a.optString("note", ""))) + " note(s) forgotten. Do not mention it.", false);
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

    private static int contactsRead = 0;
    private static long namesAt = 0;
    private static final List<String> allNames = new ArrayList<>();

    /** How many contacts were read the last time (the honest number used in the messages). */
    static int contactsRead() { return contactsRead; }

    /** Every contact name, read once and kept for a minute, for "did you mean …" suggestions. */
    private static List<String> names(Context c, boolean email) {
        if (System.currentTimeMillis() - namesAt < 60_000 && !allNames.isEmpty()) return allNames;
        allNames.clear();
        if (!has(c, Manifest.permission.READ_CONTACTS)) return allNames;
        try (Cursor cur = c.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,
                new String[]{ContactsContract.Contacts.DISPLAY_NAME}, null, null, null)) {
            int n = 0;
            while (cur != null && cur.moveToNext()) {
                String name = cur.getString(0);
                if (name == null || name.trim().isEmpty()) continue;
                n++;
                if (!allNames.contains(name)) allNames.add(name);
            }
            contactsRead = n;
            namesAt = System.currentTimeMillis();
        } catch (Throwable ignored) {}
        return allNames;
    }

    /** Up to three names closest to what was said — so the reply can say "did you mean …" instead of "not found". */
    private static String closest(Context c, String q, boolean email) {
        String sk = skel(q);
        if (sk.length() < 3) return "";
        String bestA = "", bestB = "", bestC = "";
        int da = 99, db = 99, dc = 99;
        for (String name : names(c, email)) {
            String sn = skel(name);
            if (sn.length() < 3) continue;
            int d = sn.startsWith(sk.substring(0, Math.min(3, sk.length()))) ? Math.max(1, lev(sn, sk) - 2)
                    : Math.min(lev(sn, sk), Math.max(sn.length(), sk.length()));
            if (d < da) { dc = db; bestC = bestB; db = da; bestB = bestA; da = d; bestA = name; }
            else if (d < db) { dc = db; bestC = bestB; db = d; bestB = name; }
            else if (d < dc) { dc = d; bestC = name; }
        }
        if (bestA.isEmpty()) return "";
        StringBuilder b = new StringBuilder(bestA);
        if (!bestB.isEmpty()) b.append(", ").append(bestB);
        if (!bestC.isEmpty()) b.append(", ").append(bestC);
        return b.toString();
    }

    /** People whose name matches, best first (one entry per contact, the mobile / primary number). */
    static List<Person> people(Context c, String q, boolean email) {
        List<Person> out = new ArrayList<>();
        if (!has(c, Manifest.permission.READ_CONTACTS)) return out;
        Uri uri = email ? ContactsContract.CommonDataKinds.Email.CONTENT_URI : ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
        String data = email ? ContactsContract.CommonDataKinds.Email.ADDRESS : ContactsContract.CommonDataKinds.Phone.NUMBER;
        String[] cols = {ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DISPLAY_NAME, data, ContactsContract.Data.IS_PRIMARY, ContactsContract.Data.DATA2, ContactsContract.Data.TIMES_CONTACTED};
        Map<Long, Person> by = new LinkedHashMap<>();
        Map<Long, Integer> rank = new HashMap<>();
        try (Cursor cur = c.getContentResolver().query(uri, cols, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String name = cur.getString(1), val = cur.getString(2);
                if (name == null || val == null) continue;
                int s = score(name, q);
                if (s == 0) continue;
                long id = cur.getLong(0);
                int r = (cur.getInt(3) > 0 ? 4 : 0) + (!email && cur.getInt(4) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE ? 2 : 0);
                Person p = by.get(id);
                if (p == null || r > rank.get(id)) {
                    if (p == null) { p = new Person(); by.put(id, p); }
                    p.id = id; p.name = name; p.score = s;
                    if (email) p.email = val; else p.number = val;
                    rank.put(id, r);
                }
            }
        } catch (Exception ignored) {}
        // the phone's own fuzzy search — it also matches a name that is written differently, which the
        // plain comparison above can miss
        try (Cursor cur = c.getContentResolver().query(
                Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q)), cols, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String name = cur.getString(1), val = cur.getString(2);
                if (name == null || val == null) continue;
                long id = cur.getLong(0);
                if (by.containsKey(id)) continue;
                Person p = new Person();
                p.id = id; p.name = name; p.number = val; p.score = Math.max(58, score(name, q));
                by.put(id, p);
            }
        } catch (Throwable ignored) {}
        out.addAll(by.values());
        out.sort((x, y) -> y.score - x.score);
        return out;
    }

    /** Picks one person or explains what to ask (several matches / none / no permission). */
    private static Object pick(Context c, String who, boolean email) {
        String w = who == null ? "" : who.trim();
        if (w.isEmpty()) return "No contact given — ask the user who.";
        if (email && w.contains("@")) { Person p = new Person(); p.name = w; p.email = w; return p; }
        if (!email && w.replaceAll("[\\s\\-()]", "").matches("\\+?\\d{5,15}")) { Person p = new Person(); p.name = w; p.number = w.replaceAll("[\\s\\-()]", ""); return p; }
        if (!has(c, Manifest.permission.READ_CONTACTS))
            return "The phone's own contacts are NOT readable — Contacts permission is off for Danav AI (Phone control → Contacts). "
                    + "Say that plainly and ask for it (or the number); meanwhile the app can still search inside WhatsApp itself.";
        List<Person> l = people(c, w, email);
        if (l.isEmpty()) {
            String near = closest(c, w, email);
            return "Contacts permission is fine and " + contactsRead() + " contacts were read from this phone, but no saved name matches \"" + w + "\""
                    + (near.isEmpty() ? "" : " (closest: " + near + ")")
                    + (email ? " with an email address" : "")
                    + ". The app now searches inside WhatsApp itself — if that finds nothing either, ask the user for the number or how the name is saved there.";
        }
        // only people we can really reach — an empty number must never turn into a guessed one
        List<Person> usable = new ArrayList<>();
        for (Person p : l) if (email ? (p.email != null && p.email.contains("@")) : validNumber(p.number)) usable.add(p);
        if (usable.isEmpty()) {
            String near = closest(c, w, email);
            return "A contact called \"" + l.get(0).name + "\" exists but has no " + (email ? "email address" : "phone number") + " saved"
                    + (near.isEmpty() || near.equals(l.get(0).name) ? "" : " (others close to \"" + w + "\": " + near + ")")
                    + ". Ask the user for the " + (email ? "address." : "number.");
        }
        Person top = usable.get(0);
        int same = 0;
        for (Person p : usable) if (norm(p.name).equals(norm(top.name))) same++;
        if (same > 1)
            return "There are " + same + " contacts called \"" + top.name + "\" on this phone. Ask the user which one (the number or how they know them) before doing anything — do not guess.";
        List<Person> close = new ArrayList<>();
        for (Person p : usable) if (p == top || (p.score == top.score && !norm(p.name).equals(norm(top.name)))) close.add(p);
        if (close.size() > 1 && top.score < 100) {
            StringBuilder sb = new StringBuilder("Several contacts match \"" + w + "\": ");
            for (int i = 0; i < Math.min(5, close.size()); i++) sb.append(i > 0 ? ", " : "").append(close.get(i).name);
            return sb.append(". Ask the user which one, then call the tool again with the full name.").toString();
        }
        return top;
    }

    private static final Map<String, String> CC = new HashMap<>();
    static {
        String[] t = {"pk", "92", "in", "91", "bd", "880", "ae", "971", "sa", "966", "us", "1", "ca", "1", "gb", "44", "qa", "974", "om", "968", "kw", "965", "bh", "973",
                "af", "93", "tr", "90", "my", "60", "de", "49", "fr", "33", "it", "39", "es", "34", "au", "61", "cn", "86", "ir", "98", "eg", "20", "ng", "234", "za", "27",
                "lk", "94", "np", "977", "id", "62", "jp", "81", "kr", "82", "ru", "7", "br", "55", "mx", "52", "nl", "31", "se", "46", "no", "47", "ie", "353", "nz", "64", "sg", "65"};
        for (int i = 0; i < t.length; i += 2) CC.put(t[i], t[i + 1]);
    }

    static boolean validNumber(String number) {
        return number != null && number.replaceAll("\\D", "").length() >= 7;
    }

    /** International digits for wa.me (03001234567 → 923001234567 on a Pakistani SIM). "" = not a phone number. */
    static String intl(Context c, String number) {
        String n = number == null ? "" : number.replaceAll("[^\\d+]", "");
        if (n.startsWith("+")) n = n.substring(1);
        else if (n.startsWith("00")) n = n.substring(2);
        if (n.replaceAll("\\D", "").length() < 7) return "";      // never make a number up out of nothing
        String iso = "";
        try {
            TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            iso = tm.getSimCountryIso();
            if (iso == null || iso.isEmpty()) iso = tm.getNetworkCountryIso();
        } catch (Exception ignored) {}
        if (iso == null || iso.isEmpty()) iso = Locale.getDefault().getCountry();
        String cc = CC.get(iso.toLowerCase(Locale.ROOT));
        if (cc == null) return n.replaceFirst("^0", "");
        if (n.startsWith("0")) return cc + n.substring(1);
        if (n.startsWith(cc) && n.length() > 10) return n;
        return cc + n;
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

    private static Out whatsapp(Context c, String who, String msg) {
        String pkg = installed(c, WA) ? WA : installed(c, WA_B) ? WA_B : null;
        if (pkg == null) return bad("WhatsApp isn't installed on this phone.");
        if (msg == null || msg.trim().isEmpty()) return bad("No message text — ask what to send.");
        DanavAccess s = DanavAccess.me;
        StringBuilder note = new StringBuilder();
        Object o = pick(c, who, false);
        Person p = o instanceof Person ? (Person) o : null;
        String num = p == null ? "" : intl(c, p.number);
        if (p == null && o instanceof String) note.append(String.valueOf(o)).append(' ');

        // 1) the phone's own contacts: fastest, opens exactly that chat
        if (p != null && !num.isEmpty()) {
            start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=" + num + "&text=" + enc(msg))).setPackage(pkg));
            if (s == null) return bad("Opened the WhatsApp chat with " + p.name + " with the message typed, but it was NOT sent because phone control is off: the user has to tap Send. " + NEED_ACCESS);
            // WhatsApp was started straight from here, so prove it is really on screen before touching it —
            // otherwise every later step fails in a confusing way ("chat not found") when WhatsApp never opened
            if (!waitForPkg(s, pkg, 7000))
                return badLook("WhatsApp did not come to the front at all — the screen is still showing " + onScreen()
                        + ", so nothing was sent. " + startProblem(c));
            AccessibilityNodeInfo send = s.waitFor(pkg, new String[]{pkg + ":id/send"}, new String[]{"Send"}, 9000);
            if (send != null) {
                String title = chatTitle(s, pkg);
                if (title == null || titleMatches(title, p)) {
                    if (!s.click(send, false)) return badLook("Couldn't press Send in WhatsApp." + now(s));
                    sleep(450);
                    AccessibilityNodeInfo box = s.waitFor(pkg, new String[]{pkg + ":id/entry"}, null, 1200);
                    if (box != null && text(box).trim().equals(msg.trim())) return badLook("Pressed send but the message is still in the box — it did NOT go out." + now(s));
                    return new Out("Sent to " + p.name + " on WhatsApp.", false);
                }
                note.append("The number saved for ").append(p.name).append(" opened a chat with \"").append(title).append("\". ");
            } else {
                note.append("The number saved for ").append(p.name).append(" has no WhatsApp chat. ");
            }
        }

        // 2) WhatsApp's own search — WhatsApp contacts are often NOT in the phone's address book,
        //    so the phone's contacts alone are never a reason to say "not found"
        if (s == null) return new Out(note + NEED_ACCESS, false);
        Wa w = waOpen(c, s, pkg, who);
        if (w.opened == null) return badLook(note + w.why);
        if (!waSend(s, pkg, msg.trim())) return badLook(note + "I opened the WhatsApp chat with " + w.opened + " but couldn't type the message into it." + now(s));
        return new Out(note + "Sent to " + w.opened + " on WhatsApp (found by searching inside WhatsApp).", false);
    }

    // ------------------------------------------------------------------ WhatsApp's own search

    private static final class Wa { String opened; String why = "Nothing was opened."; }

    /**
     * Opens WhatsApp, uses its own search for the name and opens the best matching chat.
     * This is what makes an unknown name work: the phone's address book can be empty while
     * WhatsApp itself knows the person perfectly well.
     */
    private static Wa waOpen(Context c, DanavAccess s, String pkg, String who) {
        Wa w = new Wa();
        String q = who == null ? "" : who.trim();
        if (q.isEmpty()) { w.why = "No name was given to look for."; return w; }
        try {
            start(c, c.getPackageManager().getLaunchIntentForPackage(pkg));
            if (!waitForPkg(s, pkg, 7000)) { w.why = "WhatsApp did not come to the front at all — the screen is still showing " + onScreen() + ". " + startProblem(c); return w; }
            s.settle(1400);
            AccessibilityNodeInfo icon = s.waitFor(pkg,
                    new String[]{pkg + ":id/menuitem_search", pkg + ":id/search", pkg + ":id/search_holder", pkg + ":id/search_bar", pkg + ":id/toolbar_search"},
                    new String[]{"Search"}, 4500);
            if (icon != null) { s.click(icon, false); sleep(700); }
            AccessibilityNodeInfo field = s.waitFor(pkg,
                    new String[]{pkg + ":id/search_src_text", pkg + ":id/search_input", pkg + ":id/search_edit_text", pkg + ":id/search_field", pkg + ":id/search_edit"},
                    new String[]{"Search chats", "Search name or number", "Search"}, 4500);
            if (field == null) field = s.find("Search", true);
            if (field == null) { w.why = "I opened WhatsApp but couldn't find its search box." + now(s); return w; }
            s.click(field, false);
            sleep(250);
            s.setText(field, q);
            s.settle(1300);
            List<AccessibilityNodeInfo> rows = waRows(pkg, q);
            if (rows.isEmpty()) {
                w.why = "I searched \"" + q + "\" inside WhatsApp myself and found no chat with that name — so ask the user for the number or how the name is saved there." + now(s);
                return w;
            }
            String first = text(rows.get(0)).trim();
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            for (int i = 0; i < Math.min(4, rows.size()); i++) {
                String n = text(rows.get(i)).trim();
                if (!n.isEmpty() && score(n, q) >= 55) names.add(n);
            }
            if (names.size() > 1) {
                StringBuilder sb = new StringBuilder("WhatsApp shows ").append(names.size()).append(" chats for \"").append(q).append("\": ");
                int i = 0;
                for (String n : names) sb.append(i++ > 0 ? ", " : "").append(n);
                w.why = sb.append(". Ask the user which one, then do it again with the full name.").toString();
                return w;
            }
            if (!s.click(rows.get(0), false)) { w.why = "I found \"" + first + "\" in WhatsApp but couldn't open the chat." + now(s); return w; }
            s.settle(1100);
            String title = chatTitle(s, pkg);
            w.opened = title == null || title.trim().isEmpty() ? first : title.trim();
            return w;
        } catch (Throwable t) {
            w.why = "I couldn't search inside WhatsApp (" + t.getClass().getSimpleName() + ").";
            return w;
        }
    }

    /** The chat rows WhatsApp is showing for that search, best match first. */
    private static List<AccessibilityNodeInfo> waRows(String pkg, String q) {
        DanavAccess s = DanavAccess.me;
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (s == null) return out;
        AccessibilityNodeInfo r = s.root();
        if (r == null) return out;
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        try {
            for (String id : new String[]{"conversations_row_contact_name", "contact_name", "contactpicker_row_name", "chat_contact_name", "name"})
                all.addAll(r.findAccessibilityNodeInfosByViewId(pkg + ":id/" + id));
        } catch (Throwable ignored) {}
        for (AccessibilityNodeInfo n : all) {
            String t = text(n).trim();
            if (!t.isEmpty() && score(t, q) >= 45 && !out.contains(n)) out.add(n);
        }
        out.sort((a, b) -> score(text(b).trim(), q) - score(text(a).trim(), q));
        return out;
    }

    /** Types the message into the open chat and presses Send. */
    private static boolean waSend(DanavAccess s, String pkg, String msg) {
        AccessibilityNodeInfo entry = s.waitFor(pkg, new String[]{pkg + ":id/entry"}, new String[]{"Type a message", "Message"}, 6000);
        if (entry == null) entry = s.find("Type a message", true);
        if (entry == null) return false;
        s.click(entry, false);
        sleep(300);
        if (!s.setText(entry, msg)) return false;
        sleep(450);
        AccessibilityNodeInfo send = s.waitFor(pkg, new String[]{pkg + ":id/send"}, new String[]{"Send"}, 2500);
        if (send == null) send = s.find("Send", false);
        if (send != null && s.click(send, false)) { sleep(500); return true; }
        if (s.submit(entry)) { sleep(500); return true; }
        return false;
    }

    private static Out whatsappCall(Context c, String who, boolean video, String say, String task) {
        String pkg = installed(c, WA) ? WA : installed(c, WA_B) ? WA_B : null;
        if (pkg == null) return bad("WhatsApp isn't installed on this phone.");
        DanavAccess s = DanavAccess.me;
        if (s == null) return bad(NEED_ACCESS);
        StringBuilder note = new StringBuilder();
        Object o = pick(c, who, false);
        Person p = o instanceof Person ? (Person) o : null;
        String num = p == null ? "" : intl(c, p.number);
        String opened = null;

        // 1) via the saved number, if there is one
        if (p != null && !num.isEmpty()) {
            start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=" + num)).setPackage(pkg));
            if (!waitForPkg(s, pkg, 7000))
                return badLook("WhatsApp did not come to the front at all — the screen is still showing " + onScreen()
                        + ", so the call was not started. " + startProblem(c));
            AccessibilityNodeInfo b = s.waitFor(pkg, null, video ? new String[]{"Video call"} : new String[]{"Voice call", "Audio call", "Call"}, 8000);
            String title = chatTitle(s, pkg);
            if (b != null && (title == null || titleMatches(title, p))) {
                if (callButton(s, pkg, video)) opened = title == null || title.trim().isEmpty() ? p.name : title.trim();
            } else if (b == null) {
                note.append("The number saved for ").append(p.name).append(" has no WhatsApp call button. ");
            } else {
                note.append("The number saved for ").append(p.name).append(" opened a chat with \"").append(title).append("\". ");
            }
        } else if (o instanceof String) note.append(String.valueOf(o)).append(' ');

        // 2) WhatsApp's own search — how a name that isn't in the phone's contacts still works
        if (opened == null) {
            Wa w = waOpen(c, s, pkg, who);
            if (w.opened == null) return badLook(note + w.why);
            if (!callButton(s, pkg, video)) return badLook(note + "I opened the WhatsApp chat with " + w.opened + " but its call button wasn't there." + now(s));
            opened = w.opened;
        }
        // 3) wait until they actually pick up — Danav must not greet a ringing phone
        String state = waitAnswered(s, pkg, 20000);
        if ("no".equals(state)) return badLook("The WhatsApp call to " + opened + " is ringing but nobody has picked up yet." + now(s));
        // 4) answered: Danav stays on the line AND keeps his ears (see LiveCall.bridge)
        LiveCall.bridge(opened, task == null ? "" : task);
        if (say != null && !say.trim().isEmpty()) { sleep(400); LiveCall.say(say); }
        return new Out("The WhatsApp " + (video ? "video" : "voice") + " call to " + opened + " is up and Danav is on the line, talking out of the loudspeaker. "
                + "Danav can hear " + opened + " (their voice comes out of the loudspeaker into the microphone), so hold a real conversation: answer them in short sentences, "
                + "one at a time, in the user's language, and never repeat a sentence. The user may speak too — the microphone belongs to the call, so the user's voice goes straight to "
                + opened + ". If Danav cannot hear them at all, the app says so on screen; then only say the user's message. Never report any of this to the user as a message.", false);
    }

    /**
     * Waits for the call to be answered. "yes" = a call timer is running (they picked up),
     * "no" = it has been ringing for the whole time, "?" = we couldn't tell (treated as answered).
     */
    private static String waitAnswered(DanavAccess s, String pkg, long ms) {
        long end = System.currentTimeMillis() + ms;
        Pattern timer = Pattern.compile("\\b\\d{1,2}:\\d{2}(:\\d{2})?\\b");
        while (System.currentTimeMillis() < end) {
            try {
                AccessibilityNodeInfo r = s.root();
                if (r != null) {
                    for (String id : new String[]{"call_duration", "duration", "call_timer", "timer"}) {
                        List<AccessibilityNodeInfo> l = r.findAccessibilityNodeInfosByViewId(pkg + ":id/" + id);
                        for (AccessibilityNodeInfo n : l) if (timer.matcher(text(n)).find()) return "yes";
                    }
                    // the running call timer is the giveaway: the screen text shows HH:MM once they pick up
                    if (timer.matcher(s.read()).find()) return "yes";
                }
            } catch (Throwable ignored) {}
            sleep(700);
        }
        return "no";
    }

    /** Presses the voice/video call button on the open WhatsApp chat. */
    private static boolean callButton(DanavAccess s, String pkg, boolean video) {
        String[] ids = video
                ? new String[]{pkg + ":id/video_call", pkg + ":id/conversation_contact_video_call", pkg + ":id/menuitem_video_call"}
                : new String[]{pkg + ":id/voice_call", pkg + ":id/conversation_contact_voice_call", pkg + ":id/audio_call", pkg + ":id/menuitem_voice_call"};
        String[] words = video ? new String[]{"Video call"} : new String[]{"Voice call", "Audio call", "Call"};
        AccessibilityNodeInfo b = s.waitFor(pkg, ids, words, 5000);
        if (b == null) return false;
        if (!s.click(b, false)) return false;
        sleep(900);
        AccessibilityNodeInfo ok = s.waitFor(pkg, null, new String[]{"Call", "CALL", "Start call"}, 1200);   // some versions confirm first
        if (ok != null && !ok.equals(b)) s.click(ok, false);
        return true;
    }

    /** The chat title WhatsApp is showing (null when it can't be read). */
    private static String chatTitle(DanavAccess s, String pkg) {
        try {
            String[] ids = {"conversation_contact_name", "conversation_contact_status", "contact_name", "title", "conversation_title"};
            for (String id : ids) {
                AccessibilityNodeInfo n = s.find("#" + id, false);
                String t = n == null ? "" : text(n).trim();
                if (!t.isEmpty()) return t;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** True when the chat we opened looks like that person (their name, or the tail of their number). */
    private static boolean titleMatches(String title, Person p) {
        String t = norm(title), n = norm(p.name);
        if (t.isEmpty()) return true;
        if (!n.isEmpty()) {
            if (t.equals(n) || t.contains(n) || n.contains(t)) return true;
            for (String w : n.split("\\s+")) if (w.length() >= 3 && t.contains(w)) return true;
        }
        String d = p.number == null ? "" : p.number.replaceAll("\\D", "");
        if (d.length() >= 4 && t.replaceAll("\\D", "").contains(d.substring(d.length() - 4))) return true;
        return n.isEmpty();
    }

    /** Ends a WhatsApp (or normal) call that is on screen right now. */
    private static Out hangup(Context c) {
        DanavAccess s = DanavAccess.me;
        if (s != null) {
            AccessibilityNodeInfo b = s.waitFor(null, new String[]{"com.whatsapp:id/end_call_btn", "com.whatsapp:id/call_end_button"},
                    new String[]{"End call", "End Call", "Hang up", "End"}, 3000);
            if (b != null && s.click(b, false)) {
                sleep(900);
                AccessibilityNodeInfo still = s.waitFor(null, new String[]{"com.whatsapp:id/end_call_btn", "com.whatsapp:id/call_end_button"}, null, 900);
                if (still != null) return bad("The end-call button was pressed but the call is still on screen. Tell the user to end it themselves." + now(s));
                LiveCall.endBridge();
                return new Out("The call was ended. Danav is back with the user and listening again.", false).as(true);
            }
        }
        try {
            if (has(c, Manifest.permission.ANSWER_PHONE_CALLS)) {
                android.telecom.TelecomManager tm = (android.telecom.TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
                if (tm != null && tm.isInCall()) { tm.endCall(); sleep(700); LiveCall.endBridge(); return new Out("The call was ended. Danav is back with the user and listening again.", false); }
            }
        } catch (Throwable ignored) {}
        return bad("I couldn't find a call to end on the screen.");
    }

    private static Out call(Context c, String who) {
        Object o = pick(c, who, false);
        if (o instanceof String) return bad((String) o);
        Person p = (Person) o;
        Uri tel = Uri.parse("tel:" + Uri.encode(p.number));
        if (has(c, Manifest.permission.CALL_PHONE)) {
            start(c, new Intent(Intent.ACTION_CALL, tel));
            sleep(1800);
            try {
                android.telecom.TelecomManager tm = (android.telecom.TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
                if (tm != null && !tm.isInCall())
                    return bad("The call to " + p.name + " did NOT start — the dialer opened but no call is running. Tell the user plainly." + now2());
            } catch (Throwable ignored) {}
            return new Out("Calling " + p.name + " (" + p.number + ").", false, true, false).as(true);
        }
        start(c, new Intent(Intent.ACTION_DIAL, tel));
        DanavAccess s = DanavAccess.me;
        if (s != null) {
            AccessibilityNodeInfo b = s.waitFor(null, null, new String[]{"Call", "Dial", "Voice call"}, 4000);
            if (b != null && s.click(b, false)) return new Out("Calling " + p.name + ". Say a very short goodbye — this Danav call closes now.", false, true, false);
        }
        return new Out("Opened the dialer with " + p.name + "'s number, but the call itself did not start — the user must press call. Allow Phone calls in Danav AI → Phone control to call directly.", false).as(false);
    }

    private static Out sms(Context c, String who, String msg) {
        Object o = pick(c, who, false);
        if (o instanceof String) return bad((String) o);
        Person p = (Person) o;
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.number))).putExtra("sms_body", msg);
        start(c, i);
        DanavAccess s = DanavAccess.me;
        if (s == null) return new Out("Opened the SMS to " + p.name + " with the text typed, but it was not sent — the user must tap send. " + NEED_ACCESS, false).as(false);
        AccessibilityNodeInfo b = s.waitFor(null, new String[]{"com.google.android.apps.messaging:id/send_message_button_icon", "com.google.android.apps.messaging:id/send_message_button_container",
                "com.samsung.android.messaging:id/send_button1", "com.samsung.android.messaging:id/send_button"}, new String[]{"Send SMS", "Send message", "Send", "SMS"}, 8000);
        if (b == null) return badLook("The SMS send button wasn't found." + now(s));
        if (!s.click(b, false)) return badLook("Couldn't press send." + now(s));
        sleep(700);
        // the text box must be empty now, otherwise nothing was really sent
        AccessibilityNodeInfo box = s.find("Type a message", true);
        if (box == null) box = s.find("", true);
        if (box != null && text(box).trim().equals(msg.trim()))
            return bad("Pressed send but the message is still in the box — it did NOT go out." + now(s));
        return new Out("SMS sent to " + p.name + ".", false).as(true);
    }

    private static Out email(Context c, String to, String subject, String body) {
        Object o = pick(c, to, true);
        if (o instanceof String) return bad((String) o);
        Person p = (Person) o;
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(p.email)))
                .putExtra(Intent.EXTRA_EMAIL, new String[]{p.email}).putExtra(Intent.EXTRA_SUBJECT, subject == null ? "" : subject).putExtra(Intent.EXTRA_TEXT, body == null ? "" : body);
        boolean gmail = installed(c, GMAIL);
        if (gmail) i.setPackage(GMAIL);
        try { start(c, i); } catch (ActivityNotFoundException e) { return bad("No email app is installed."); }
        DanavAccess s = DanavAccess.me;
        if (s == null) return new Out("Opened the email to " + p.email + " ready to send, but it was not sent — the user must tap Send. " + NEED_ACCESS, false).as(false);
        AccessibilityNodeInfo b = s.waitFor(gmail ? GMAIL : null, new String[]{GMAIL + ":id/send"}, new String[]{"Send"}, 9000);
        if (b == null) return badLook("The email's Send button wasn't found." + now(s));
        if (!s.click(b, false)) return badLook("Couldn't press Send." + now(s));
        sleep(600);
        return new Out("Email sent to " + p.email + ".", false);
    }

    // ------------------------------------------------------------------ media, apps, places

    private static final Pattern YT_ITEM = Pattern.compile("\"videoRenderer\":\\{\"videoId\":\"([\\w-]{11})\".{0,1500}?\"title\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.DOTALL);
    private static final Pattern YT_ID = Pattern.compile("\"videoId\":\"([\\w-]{11})\"");

    /** Finds the top result on YouTube and opens it in the YouTube app, so it plays right away. */
    private static Out youtube(Context c, String q) {
        String id = null, title = null;
        try {
            HttpURLConnection h = (HttpURLConnection) new URL("https://www.youtube.com/results?search_query=" + enc(q) + "&hl=en").openConnection();
            h.setConnectTimeout(6000); h.setReadTimeout(8000);
            h.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36");
            h.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
            h.setRequestProperty("Cookie", "CONSENT=YES+1; SOCS=CAI");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                byte[] buf = new byte[16384]; int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 2_500_000) bo.write(buf, 0, n);
            }
            String sb = bo.toString("UTF-8");
            Matcher m = YT_ITEM.matcher(sb);
            if (m.find()) { id = m.group(1); title = new JSONObject("{\"t\":\"" + m.group(2) + "\"}").optString("t"); }
            else { Matcher m2 = YT_ID.matcher(sb); if (m2.find()) id = m2.group(1); }
        } catch (Exception ignored) {}
        boolean app = installed(c, YT);
        if (id != null) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + id));
            if (app) i.setPackage(YT);
            return launch(c, i, app ? YT : null, "YouTube" + (title != null ? " (" + title + ")" : ""), 9000);
        }
        if (app) {
            Out o = launch(c, new Intent(Intent.ACTION_SEARCH).setPackage(YT).putExtra("query", q), YT, "YouTube search for \"" + q + "\"", 7000);
            return o;
        }
        return launch(c, new Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com/results?search_query=" + enc(q))), null, "YouTube in the browser", 9000);
    }

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

    private static Out media(Context c, String action) {
        int k;
        switch (action) {
            case "pause": k = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case "next": k = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "previous": k = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "stop": k = KeyEvent.KEYCODE_MEDIA_STOP; break;
            default: k = KeyEvent.KEYCODE_MEDIA_PLAY;
        }
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        boolean wasPlaying = am.isMusicActive();
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, k, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t + 20, KeyEvent.ACTION_UP, k, 0));
        if ("play".equals(action) || "pause".equals(action)) {
            sleep(900);
            boolean playing = am.isMusicActive();
            if ("play".equals(action) && !playing && !wasPlaying)
                return bad("Nothing started playing — no music or video app is playing anything. Open the app, start the song or video, "
                        + "then I can play/pause/next it." + now3());
            if ("pause".equals(action) && playing)
                return bad("The sound is still playing — the app ignored the pause key." + now3());
        }
        return new Out("Done: " + action + ".", false).as(true);
    }

    private static Out navigate(Context c, String dest, String mode) {
        String m = "walking".equals(mode) ? "walking" : "transit".equals(mode) ? "transit" : "bike".equals(mode) ? "bicycling" : "driving";
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + enc(dest) + "&travelmode=" + m + ("transit".equals(m) ? "" : "&dir_action=navigate")));
        boolean maps = installed(c, "com.google.android.apps.maps");
        if (maps) i.setPackage("com.google.android.apps.maps");
        return launch(c, i, maps ? "com.google.android.apps.maps" : null, "directions to " + dest, 9000);
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

    private static Out settings(Context c, String page) {
        String a;
        switch (page) {
            case "wifi": a = Settings.ACTION_WIFI_SETTINGS; break;
            case "bluetooth": a = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case "mobile_data": a = Settings.ACTION_DATA_ROAMING_SETTINGS; break;
            case "hotspot": a = "android.settings.TETHER_SETTINGS"; break;
            case "airplane": a = Settings.ACTION_AIRPLANE_MODE_SETTINGS; break;
            case "display": a = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "sound": a = Settings.ACTION_SOUND_SETTINGS; break;
            case "battery": a = Intent.ACTION_POWER_USAGE_SUMMARY; break;
            case "location": a = Settings.ACTION_LOCATION_SOURCE_SETTINGS; break;
            case "apps": a = Settings.ACTION_APPLICATION_SETTINGS; break;
            case "notifications": a = Build.VERSION.SDK_INT >= 26 ? "android.settings.ALL_APPS_NOTIFICATION_SETTINGS" : Settings.ACTION_SETTINGS; break;
            case "storage": a = Settings.ACTION_INTERNAL_STORAGE_SETTINGS; break;
            case "security": a = Settings.ACTION_SECURITY_SETTINGS; break;
            case "accessibility": a = Settings.ACTION_ACCESSIBILITY_SETTINGS; break;
            case "date_time": a = Settings.ACTION_DATE_SETTINGS; break;
            case "language": a = Settings.ACTION_LOCALE_SETTINGS; break;
            case "nfc": a = Settings.ACTION_NFC_SETTINGS; break;
            case "dnd": a = "android.settings.ZEN_MODE_SETTINGS"; break;
            case "about": a = Settings.ACTION_DEVICE_INFO_SETTINGS; break;
            case "danav": a = Settings.ACTION_APPLICATION_DETAILS_SETTINGS; break;
            default: a = Settings.ACTION_SETTINGS;
        }
        Intent i = new Intent(a);
        if ("danav".equals(page)) i.setData(Uri.parse("package:" + c.getPackageName()));
        try { return launch(c, i, "com.android.settings", page.replace('_', ' ') + " settings", 5000); }
        catch (Exception e) { return launch(c, new Intent(Settings.ACTION_SETTINGS), "com.android.settings", "settings", 5000); }
    }

    private static Out volume(Context c, String stream, String level) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        int st = "ring".equals(stream) ? AudioManager.STREAM_RING : "alarm".equals(stream) ? AudioManager.STREAM_ALARM : "call".equals(stream) ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC;
        int max = am.getStreamMaxVolume(st), cur = am.getStreamVolume(st), v;
        String l = level == null ? "" : level.trim().toLowerCase(Locale.ROOT).replace("%", "");
        if (l.equals("up")) v = cur + Math.max(1, max / 6);
        else if (l.equals("down")) v = cur - Math.max(1, max / 6);
        else if (l.equals("mute") || l.equals("off")) v = 0;
        else if (l.equals("max") || l.equals("full")) v = max;
        else { try { v = Math.round(Float.parseFloat(l) / 100f * max); } catch (Exception e) { return bad("Say a level 0-100, up, down or mute."); } }
        v = Math.max(0, Math.min(max, v));
        try { am.setStreamVolume(st, v, AudioManager.FLAG_SHOW_UI); }
        catch (SecurityException e) { return bad("Android didn't allow changing that volume (Do Not Disturb is on)."); }
        return new Out(stream + " volume is now " + Math.round(v * 100f / max) + "%.", false);
    }

    private static Out torch(Context c, boolean on) throws Exception {
        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        for (String id : cm.getCameraIdList()) {
            Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (Boolean.TRUE.equals(f)) { cm.setTorchMode(id, on); return new Out("Flashlight " + (on ? "on." : "off."), false); }
        }
        return bad("This phone has no flashlight.");
    }

    private static Out alarm(Context c, int h, int m, String label) {
        if (h < 0 || h > 23 || m < 0 || m > 59) return bad("Give the alarm time as hour 0-23 and minute.");
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m).putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (label != null && !label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        try { start(c, i); } catch (ActivityNotFoundException e) { return bad("No clock app can set alarms."); }
        return new Out(String.format(Locale.US, "Alarm set for %02d:%02d.", h, m), false);
    }

    private static Out timer(Context c, int sec, String label) {
        if (sec <= 0) return bad("Give the timer length.");
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, sec).putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (label != null && !label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        try { start(c, i); } catch (ActivityNotFoundException e) { return bad("No clock app can set timers."); }
        return new Out("Timer started for " + (sec >= 60 ? (sec / 60) + " min " + (sec % 60 > 0 ? (sec % 60) + " s" : "") : sec + " s").trim() + ".", false);
    }

    // ------------------------------------------------------------------ generic screen control

    private static Out readScreen() {
        DanavAccess s = DanavAccess.me;
        if (s == null) return bad(NEED_ACCESS);
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

    private static Out system(String action) {
        DanavAccess s = DanavAccess.me;
        if (s == null) return bad(NEED_ACCESS);
        if (!s.global(action)) return bad("That isn't supported on this phone.");
        if ("lock".equals(action) || "screenshot".equals(action)) return new Out("Done.", false);
        return new Out("Done." + now(s), true);
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
