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

    static JSONArray tools(boolean wake) throws Exception {
        JSONArray f = new JSONArray();
        f.put(fn("whatsapp_message", "Send a WhatsApp message to a contact by name (or number). Sends it right away.",
                p("contact", "Contact name as the user said it, or a phone number"), p("message", "The message text, in the language/script the user wants"), req("contact", "message")));
        f.put(fn("whatsapp_call", "Start a WhatsApp voice or video call with a contact.",
                p("contact", "Contact name or number"), pb("video", "true for a video call"), req("contact")));
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
        f.put(fn("read_screen", "See what is on the phone screen now: a numbered list of buttons, texts and inputs (and a screenshot).", req()));
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
        f.put(fn("scroll", "Scroll the screen.", pe("direction", "Direction", "down", "up", "left", "right"), p("target", "Optional list number"), req("direction")));
        f.put(fn("system_action", "Phone buttons and panels.", pe("action", "Action", "back", "home", "recents", "notifications", "quick_settings", "lock", "screenshot", "power_menu"), req("action")));
        f.put(fn("open_url", "Open a website or link.", p("url", "Full URL"), req("url")));
        f.put(fn("web_search", "Search the web for current information.", p("query", "Search query"), req("query")));
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
        Out(String t, boolean look) { this(t, look, false, false); }
        Out(String t, boolean look, boolean end, boolean dismiss) { text = t; this.look = look; endCall = end; this.dismiss = dismiss; }
    }

    private static final String NEED_ACCESS = "Phone control is off. Tell the user to turn on Danav in Settings → Accessibility (Danav app → Danav AI → Phone control).";

    static Out run(Context c, String name, JSONObject a) {
        try {
            switch (name) {
                case "whatsapp_message": return whatsapp(c, a.optString("contact"), a.optString("message"));
                case "whatsapp_call": return whatsappCall(c, a.optString("contact"), a.optBoolean("video"));
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
                case "tap": return tap(a.optString("target"), a.optBoolean("long_press"), a.optBoolean("double"));
                case "tap_xy": return tapXY(a.optString("x"), a.optString("y"), a.optBoolean("long_press"));
                case "drag": return drag(a.optString("from"), a.optString("to"), a.optInt("ms", 500));
                case "set_slider": return setSlider(a.optString("target"), a.optInt("value", 50));
                case "type_text": return type(a.optString("text"), a.optString("target"), a.optBoolean("submit"));
                case "scroll": return scroll(a.optString("direction", "down"), a.optString("target"));
                case "system_action": return system(a.optString("action"));
                case "open_url": return openUrl(c, a.optString("url"));
                case "web_search": return search(a.optString("query"));
                case "remember": {
                    boolean saved = Memory.add(c, a.optString("note", a.optString("text", "")));
                    return new Out(saved ? "Saved silently. Do not mention it." : "Nothing new to save.", false);
                }
                case "forget": return new Out(Memory.forget(c, a.optString("query", a.optString("note", ""))) + " note(s) forgotten. Do not mention it.", false);
                case "end_call": return new Out("Ending the call.", false, true, false);
                case "dismiss": return new Out("ok", false, true, true);
                default: return new Out("Unknown tool.", false);
            }
        } catch (SecurityException e) {
            return new Out("Android blocked this (permission missing): " + e.getMessage(), false);
        } catch (Exception e) {
            return new Out("Failed: " + e.getMessage(), false);
        }
    }

    // ------------------------------------------------------------------ contacts

    static final class Person { String name, number, email; int score; long id; }

    static boolean has(Context c, String perm) { return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    private static String norm(String s) {
        return s == null ? "" : java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** How well a contact name matches what the user said (0 = not at all). */
    private static int score(String name, String q) {
        String n = norm(name), k = norm(q);
        if (n.isEmpty() || k.isEmpty()) return 0;
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
            return "Danav can't read contacts yet. Tell the user to allow Contacts in Danav AI → Phone control, or give the number.";
        List<Person> l = people(c, w, email);
        if (l.isEmpty()) return "No contact named \"" + w + "\" was found" + (email ? " with an email address" : "") + ". Ask the user for the exact name or the " + (email ? "address." : "number.");
        Person top = l.get(0);
        List<Person> close = new ArrayList<>();
        for (Person p : l) if (p.score == top.score && !norm(p.name).equals(norm(top.name)) || p == top) close.add(p);
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

    /** International digits for wa.me (03001234567 → 923001234567 on a Pakistani SIM). */
    static String intl(Context c, String number) {
        String n = number.replaceAll("[^\\d+]", "");
        if (n.startsWith("+")) return n.substring(1);
        if (n.startsWith("00")) return n.substring(2);
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
        MainActivity m = MainActivity.inst;
        if (DanavAccess.me == null && !CallBubble.allowed(c) && (m == null || !m.isVisible()))
            throw new IllegalStateException("Android blocks opening apps from the background. Tell the user to turn on Phone control (Danav AI menu → Phone control)");
        Context from = DanavAccess.me != null ? DanavAccess.me : c.getApplicationContext();
        from.startActivity(i);
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
        if (pkg == null) return new Out("WhatsApp isn't installed on this phone.", false);
        if (msg == null || msg.trim().isEmpty()) return new Out("No message text — ask what to send.", false);
        Object o = pick(c, who, false);
        if (o instanceof String) return new Out((String) o, false);
        Person p = (Person) o;
        String num = intl(c, p.number);
        start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=" + num + "&text=" + enc(msg))).setPackage(pkg));
        DanavAccess s = DanavAccess.me;
        if (s == null) return new Out("Opened the WhatsApp chat with " + p.name + " with the message typed, but it was NOT sent because phone control is off: the user has to tap Send. " + NEED_ACCESS, false);
        AccessibilityNodeInfo send = s.waitFor(pkg, new String[]{pkg + ":id/send"}, new String[]{"Send"}, 9000);
        if (send == null) return new Out("WhatsApp opened but the Send button didn't appear (" + p.name + " may not use WhatsApp, or WhatsApp showed something else)." + now(s), true);
        if (!s.click(send, false)) return new Out("Couldn't press Send in WhatsApp." + now(s), true);
        sleep(450);
        AccessibilityNodeInfo r = s.root();
        if (r != null) for (AccessibilityNodeInfo e : r.findAccessibilityNodeInfosByViewId(pkg + ":id/entry"))
            if (text(e).trim().equals(msg.trim())) return new Out("Pressed Send but the message is still in the box." + now(s), true);
        return new Out("Sent to " + p.name + " on WhatsApp.", false);
    }

    private static Out whatsappCall(Context c, String who, boolean video) {
        String pkg = installed(c, WA) ? WA : installed(c, WA_B) ? WA_B : null;
        if (pkg == null) return new Out("WhatsApp isn't installed on this phone.", false);
        DanavAccess s = DanavAccess.me;
        if (s == null) return new Out(NEED_ACCESS, false);
        Object o = pick(c, who, false);
        if (o instanceof String) return new Out((String) o, false);
        Person p = (Person) o;
        start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=" + intl(c, p.number))).setPackage(pkg));
        AccessibilityNodeInfo b = s.waitFor(pkg, null, video ? new String[]{"Video call"} : new String[]{"Voice call", "Audio call", "Call"}, 9000);
        if (b == null) return new Out("The WhatsApp call button wasn't found." + now(s), true);
        if (!s.click(b, false)) return new Out("Couldn't press the call button." + now(s), true);
        sleep(900);
        AccessibilityNodeInfo ok = s.waitFor(pkg, null, new String[]{"Call", "CALL", "Start call"}, 1200);   // some versions confirm first
        if (ok != null && !ok.equals(b)) s.click(ok, false);
        return new Out("WhatsApp " + (video ? "video" : "voice") + " call to " + p.name + " started. Say a very short goodbye — this Danav call closes so the call can use the mic.", false, true, false);
    }

    private static Out call(Context c, String who) {
        Object o = pick(c, who, false);
        if (o instanceof String) return new Out((String) o, false);
        Person p = (Person) o;
        Uri tel = Uri.parse("tel:" + Uri.encode(p.number));
        if (has(c, Manifest.permission.CALL_PHONE)) {
            start(c, new Intent(Intent.ACTION_CALL, tel));
            return new Out("Calling " + p.name + " (" + p.number + "). Say a very short goodbye — this Danav call closes so the phone call can use the mic.", false, true, false);
        }
        start(c, new Intent(Intent.ACTION_DIAL, tel));
        DanavAccess s = DanavAccess.me;
        if (s != null) {
            AccessibilityNodeInfo b = s.waitFor(null, null, new String[]{"Call", "Dial", "Voice call"}, 4000);
            if (b != null && s.click(b, false)) return new Out("Calling " + p.name + ". Say a very short goodbye — this Danav call closes now.", false, true, false);
        }
        return new Out("Opened the dialer with " + p.name + "'s number; the user must press call (allow Phone calls in Danav AI → Phone control to call directly).", false);
    }

    private static Out sms(Context c, String who, String msg) {
        Object o = pick(c, who, false);
        if (o instanceof String) return new Out((String) o, false);
        Person p = (Person) o;
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.number))).putExtra("sms_body", msg);
        start(c, i);
        DanavAccess s = DanavAccess.me;
        if (s == null) return new Out("Opened the SMS to " + p.name + " with the text typed; the user must tap send. " + NEED_ACCESS, false);
        AccessibilityNodeInfo b = s.waitFor(null, new String[]{"com.google.android.apps.messaging:id/send_message_button_icon", "com.google.android.apps.messaging:id/send_message_button_container",
                "com.samsung.android.messaging:id/send_button1", "com.samsung.android.messaging:id/send_button"}, new String[]{"Send SMS", "Send message", "Send", "SMS"}, 8000);
        if (b == null) return new Out("The SMS send button wasn't found." + now(s), true);
        if (!s.click(b, false)) return new Out("Couldn't press send." + now(s), true);
        return new Out("SMS sent to " + p.name + ".", false);
    }

    private static Out email(Context c, String to, String subject, String body) {
        Object o = pick(c, to, true);
        if (o instanceof String) return new Out((String) o, false);
        Person p = (Person) o;
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(p.email)))
                .putExtra(Intent.EXTRA_EMAIL, new String[]{p.email}).putExtra(Intent.EXTRA_SUBJECT, subject == null ? "" : subject).putExtra(Intent.EXTRA_TEXT, body == null ? "" : body);
        boolean gmail = installed(c, GMAIL);
        if (gmail) i.setPackage(GMAIL);
        try { start(c, i); } catch (ActivityNotFoundException e) { return new Out("No email app is installed.", false); }
        DanavAccess s = DanavAccess.me;
        if (s == null) return new Out("Opened the email to " + p.email + " ready to send; the user must tap Send. " + NEED_ACCESS, false);
        AccessibilityNodeInfo b = s.waitFor(gmail ? GMAIL : null, new String[]{GMAIL + ":id/send"}, new String[]{"Send"}, 9000);
        if (b == null) return new Out("The email's Send button wasn't found." + now(s), true);
        if (!s.click(b, false)) return new Out("Couldn't press Send." + now(s), true);
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
            start(c, i);
            return new Out("Playing on YouTube" + (title != null ? ": " + title : "") + ".", false);
        }
        if (app) {
            start(c, new Intent(Intent.ACTION_SEARCH).setPackage(YT).putExtra("query", q));
            return new Out("Opened YouTube search for \"" + q + "\" (couldn't pick a video automatically); use read_screen and tap the first result.", false);
        }
        start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com/results?search_query=" + enc(q))));
        return new Out("Opened YouTube search in the browser.", false);
    }

    private static Out openApp(Context c, String name) {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        ResolveInfo best = null; int bs = 0; String bl = "";
        for (ResolveInfo r : apps) {
            String label = String.valueOf(r.loadLabel(pm));
            int s = score(label, name);
            if (s == 0 && norm(r.activityInfo.packageName).contains(norm(name).replace(" ", ""))) s = 40;
            if (s > bs) { bs = s; best = r; bl = label; }
        }
        if (best == null) return new Out("No app called \"" + name + "\" is installed.", false);
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(best.activityInfo.packageName, best.activityInfo.name)).addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        start(c, i);
        DanavAccess s = DanavAccess.me;
        return new Out("Opened " + bl + "." + (s != null ? now(s) : ""), s != null);
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
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, k, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t + 20, KeyEvent.ACTION_UP, k, 0));
        return new Out("Done: " + action + ".", false);
    }

    private static Out navigate(Context c, String dest, String mode) {
        String m = "walking".equals(mode) ? "walking" : "transit".equals(mode) ? "transit" : "bike".equals(mode) ? "bicycling" : "driving";
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + enc(dest) + "&travelmode=" + m + ("transit".equals(m) ? "" : "&dir_action=navigate")));
        if (installed(c, "com.google.android.apps.maps")) i.setPackage("com.google.android.apps.maps");
        start(c, i);
        return new Out("Opened directions to " + dest + ".", false);
    }

    private static Out openUrl(Context c, String url) {
        String u = url.trim();
        if (!u.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) u = "https://" + u;
        start(c, new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        return new Out("Opened " + u + ".", false);
    }

    private static Out search(String q) throws Exception {
        JSONArray r = Web.search(q, 5);
        if (r.length() == 0) return new Out("The search didn't return anything right now.", false);
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
        try { start(c, i); } catch (ActivityNotFoundException e) { start(c, new Intent(Settings.ACTION_SETTINGS)); }
        DanavAccess s = DanavAccess.me;
        return new Out("Opened " + page.replace('_', ' ') + " settings." + (s != null ? now(s) : " Phone control is off, so the user has to change it themselves."), s != null);
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
        else { try { v = Math.round(Float.parseFloat(l) / 100f * max); } catch (Exception e) { return new Out("Say a level 0-100, up, down or mute.", false); } }
        v = Math.max(0, Math.min(max, v));
        try { am.setStreamVolume(st, v, AudioManager.FLAG_SHOW_UI); }
        catch (SecurityException e) { return new Out("Android didn't allow changing that volume (Do Not Disturb is on).", false); }
        return new Out(stream + " volume is now " + Math.round(v * 100f / max) + "%.", false);
    }

    private static Out torch(Context c, boolean on) throws Exception {
        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        for (String id : cm.getCameraIdList()) {
            Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (Boolean.TRUE.equals(f)) { cm.setTorchMode(id, on); return new Out("Flashlight " + (on ? "on." : "off."), false); }
        }
        return new Out("This phone has no flashlight.", false);
    }

    private static Out alarm(Context c, int h, int m, String label) {
        if (h < 0 || h > 23 || m < 0 || m > 59) return new Out("Give the alarm time as hour 0-23 and minute.", false);
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m).putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (label != null && !label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        try { start(c, i); } catch (ActivityNotFoundException e) { return new Out("No clock app can set alarms.", false); }
        return new Out(String.format(Locale.US, "Alarm set for %02d:%02d.", h, m), false);
    }

    private static Out timer(Context c, int sec, String label) {
        if (sec <= 0) return new Out("Give the timer length.", false);
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, sec).putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (label != null && !label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        try { start(c, i); } catch (ActivityNotFoundException e) { return new Out("No clock app can set timers.", false); }
        return new Out("Timer started for " + (sec >= 60 ? (sec / 60) + " min " + (sec % 60 > 0 ? (sec % 60) + " s" : "") : sec + " s").trim() + ".", false);
    }

    // ------------------------------------------------------------------ generic screen control

    private static Out readScreen() {
        DanavAccess s = DanavAccess.me;
        if (s == null) return new Out(NEED_ACCESS, false);
        return new Out(cut(s.read()), true);
    }

    private static Out tap(String target, boolean longPress) { return tap(target, longPress, false); }

    private static Out tap(String target, boolean longPress, boolean doubleTap) {
        DanavAccess s = DanavAccess.me;
        if (s == null) return new Out(NEED_ACCESS, false);
        AccessibilityNodeInfo n = s.find(target, false);
        if (n == null) n = s.findScroll(target, 3);            // it may be further down the list — go look for it
        if (n == null) return new Out("\"" + target + "\" isn't on the screen." + now(s), true);
        String what = DanavAccess.label(n);
        boolean ok = s.click(n, longPress);
        if (ok && doubleTap) { sleep(90); ok = s.click(n, false); }
        if (!ok) return new Out("Couldn't tap \"" + what + "\".", false);
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
        if (s == null) return new Out(NEED_ACCESS, false);
        float[] p = point(x, y);
        if (!s.tapAt(p[0], p[1], longPress ? 700 : 60)) return new Out("Couldn't tap there.", false);
        return new Out("Tapped at " + Math.round(p[0]) + "," + Math.round(p[1]) + "." + now(s), true);
    }

    private static Out drag(String from, String to, int ms) {
        DanavAccess s = DanavAccess.me;
        if (s == null) return new Out(NEED_ACCESS, false);
        float[] a = null, b = null;
        if (from != null && from.contains(",")) { String[] p = from.split(","); a = point(p[0], p[1]); }
        else if (from != null && !from.isEmpty()) { AccessibilityNodeInfo n = s.find(from, false); if (n != null) a = centerOf(n); }
        if (to != null && to.contains(",")) { String[] p = to.split(","); b = point(p[0], p[1]); }
        else if (to != null && !to.isEmpty()) { AccessibilityNodeInfo n = s.find(to, false); if (n != null) b = centerOf(n); }
        if (a == null || b == null) return new Out("Tell me both points, e.g. from \"0.2,0.8\" to \"0.8,0.8\".", false);
        if (!s.drag(a[0], a[1], b[0], b[1], ms)) return new Out("The drag didn't go through.", false);
        return new Out("Dragged." + now(s), true);
    }

    private static float[] centerOf(AccessibilityNodeInfo n) {
        android.graphics.Rect r = new android.graphics.Rect();
        n.getBoundsInScreen(r);
        return new float[]{r.exactCenterX(), r.exactCenterY()};
    }

    private static Out setSlider(String target, int value) {
        DanavAccess s = DanavAccess.me;
        if (s == null) return new Out(NEED_ACCESS, false);
        AccessibilityNodeInfo n = target == null || target.isEmpty() ? null : s.find(target, false);
        if (n == null) n = s.find("slider", false);            // a node the list called "slider"
        if (n == null) return new Out("No slider on the screen. read_screen first.", true);
        if (!s.setSlider(n, value)) return new Out("Couldn't move that slider.", false);
        return new Out("Slider set to about " + Math.max(0, Math.min(100, value)) + "%." + now(s), true);
    }

    private static Out type(String text, String target, boolean submit) {
        DanavAccess s = DanavAccess.me;
        if (s == null) return new Out(NEED_ACCESS, false);
        AccessibilityNodeInfo n = null;
        if (target != null && !target.isEmpty()) n = s.find(target, true);
        if (n == null) { AccessibilityNodeInfo r = s.root(); if (r != null) n = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT); }
        if (n == null || !n.isEditable()) n = s.find("", true);
        if (n == null) return new Out("No text field on screen." + now(s), true);
        if (!s.setText(n, text)) return new Out("Couldn't type into that field.", false);
        if (submit) { sleep(150); s.submit(n); }
        return new Out("Typed it." + now(s), true);
    }

    private static Out scroll(String dir, String target) {
        DanavAccess s = DanavAccess.me;
        if (s == null) return new Out(NEED_ACCESS, false);
        AccessibilityNodeInfo n = target == null || target.isEmpty() ? null : s.find(target, false);
        if (!s.scroll(dir, n)) return new Out("Couldn't scroll.", false);
        return new Out("Scrolled " + dir + "." + now(s), true);
    }

    private static Out system(String action) {
        DanavAccess s = DanavAccess.me;
        if (s == null) return new Out(NEED_ACCESS, false);
        if (!s.global(action)) return new Out("That isn't supported on this phone.", false);
        if ("lock".equals(action) || "screenshot".equals(action)) return new Out("Done.", false);
        return new Out("Done." + now(s), true);
    }

    /** What the phone looks like right now, added to the call's instructions so Danav knows the situation. */
    static String contextLine() {
        try {
            StringBuilder sb = new StringBuilder("\n\nPhone right now:");
            java.util.Calendar c = java.util.Calendar.getInstance();
            sb.append(" it is ").append(new java.text.SimpleDateFormat("EEEE h:mm a", java.util.Locale.ENGLISH).format(c.getTime()));
            Context ctx = DanavAccess.me != null ? DanavAccess.me : null;
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
