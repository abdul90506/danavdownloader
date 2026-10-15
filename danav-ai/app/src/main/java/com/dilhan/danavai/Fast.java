package com.dilhan.danavai;

import android.content.Context;
import android.os.BatteryManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Instant answers for questions the phone can answer on the spot, with no network and no side effects:
 * the time, the date, the battery. Anything that does something — opening, tapping, calling, sending —
 * goes to the assistant, which looks at the screen and drives the phone the way the user does.
 */
final class Fast {
    private Fast() {}

    static final class Hit {
        final String reply;                                // what Danav answers right away
        final String tool, json;                           // tool to run first (may be null)
        Hit(String reply, String tool, String json) { this.reply = reply; this.tool = tool; this.json = json; }
    }

    /** Tries to handle a short message locally. Returns null when the model should answer. */
    static Hit match(Context c, String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty() || t.length() > 200) return null;
        String s = t.toLowerCase(Locale.ROOT);
        boolean ur = urdu(s);
        try {
            // ---- time & date ------------------------------------------------------------------
            if (Pattern.compile("(kya (time|waqt)|kitne|कितने|समय|time kya|what.{0,8}time|time bata)").matcher(s).find() && !s.contains("timer")) {
                String now = new SimpleDateFormat("h:mm a", Locale.ENGLISH).format(new Date());
                return new Hit(ur ? "Abhi " + now + " baje hain." : "It's " + now + ".", null, null);
            }
            if (Pattern.compile("(aaj (kya )?(din|tareekh|tarikh|date)|date kya|today.{0,10}date|आज क्या)").matcher(s).find()) {
                String d = new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(new Date());
                return new Hit(ur ? "Aaj " + d + " hai." : "Today is " + d + ".", null, null);
            }
            // ---- battery ----------------------------------------------------------------------
            if (Pattern.compile("(battery|batteri|charge|چارج|बैटरी)").matcher(s).find() && t.length() < 60) {
                BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
                int lvl = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                boolean charging = bm != null && bm.isCharging();
                if (lvl >= 0) return new Hit(ur ? "Battery " + lvl + "% hai" + (charging ? " aur charge ho rahi hai." : ".") : "Battery is " + lvl + "%" + (charging ? " and charging." : "."), null, null);
            }
            // Everything that DOES something on the phone is left to the assistant itself: it looks at the
            // screen and drives the phone the way the user does. A hidden shortcut per task was the old
            // system — two ways to do one thing, one of them blind. Only pure questions stay instant here.
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Runs the local tool (if any) and answers with what the phone actually reported.
     *
     * This used to answer with a cheerful canned line ("Theek, X ko message bhej diya.") whenever the
     * result didn't match a small list of failure words — so "no contact found" or "the app did not come
     * to the front" came back to the user as a fake success. Now the real text is always used, and a
     * failure is a failure.
     */
    static String run(Context c, Hit h) {
        if (h.tool == null) return h.reply;
        try {
            JSONObject args = new JSONObject(h.json);
            Phone.Out out = Phone.run(c, h.tool, args);
            String text = out.text == null ? "" : out.text;
            if (text.trim().isEmpty()) return h.reply;
            return brief(text);
        } catch (Throwable e) {
            String m = e.getMessage();
            return "I couldn't do that: " + e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : " — " + m);
        }
    }

    /** The screen dump is useful to the model, not to a chat bubble — keep the answer itself short. */
    private static String brief(String t) {
        int i = t.indexOf("\nScreen now:");
        String cut = i > 0 ? t.substring(0, i) : t;
        return cut.trim();
    }

    // ------------------------------------------------------------------ helpers

    private static boolean urdu(String s) {
        if (s.matches(".*[\\u0600-\\u06FF].*")) return true;
        return s.matches(".*(karo|kro|kya|hai|hain|mujhe|mere|mera|lagao|bhejo|likho|bolo|kholo|band|chalu|abhi|baje|bje|tez|kam|yeh|kuch|wala|do|dena|jao|bata|suno|milao|chala).*");
    }

    private static Boolean onOff(String s) {
        boolean on = s.matches(".*(\\bon\\b|chalu|chala|jala|jalao|start|चालू|जला|on\\s*karo|chala\\s*do).*");
        boolean off = s.matches(".*(\\boff\\b|band|bujha|bujhao|बंद|off\\s*karo|band\\s*karo).*");
        if (on && !off) return true;
        if (off && !on) return false;
        return null;
    }

    private static Integer duration(String s) {
        Matcher m = Pattern.compile("(\\d+)\\s*(?:min|minute|minutes|minat|मिनट|منٹ)").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1)) * 60;
        m = Pattern.compile("(\\d+)\\s*(?:sec|second|seconds|seekand|सेकंड|سیکنڈ)").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = Pattern.compile("(\\d+)\\s*(?:hour|ghant\\w*|ghanta|घंट)").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1)) * 3600;
        m = Pattern.compile("(\\d+)\\s*(?:मिनट|min)").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1)) * 60;
        return null;
    }

    /** Reads an hour:minute out of "7:30", "7 baje", "saat baje", "7 am", "7:30 pm". */
    private static int[] time(String s) {
        int hour = -1, min = 0;
        Matcher m = Pattern.compile("(\\d{1,2})\\s*[:.]\\s*(\\d{2})").matcher(s);
        if (m.find()) { hour = Integer.parseInt(m.group(1)); min = Integer.parseInt(m.group(2)); }
        else {
            m = Pattern.compile("(\\d{1,2})\\s*(?:baje|bje|o'?clock|am|pm|बजे)?").matcher(s);
            while (m.find()) {
                int v = Integer.parseInt(m.group(1));
                if (v >= 0 && v <= 23) { hour = v; break; }
            }
            if (hour < 0) {
                String[] words = {"ek", "do", "teen", "char", "paanch", "panch", "che", "chhe", "saat", "aath", "nau", "das", "gyarah", "barah"};
                for (int i = 0; i < words.length; i++)
                    if (s.contains(words[i] + " baje") || s.contains(words[i] + " bje")) { hour = i + 1; break; }
            }
        }
        if (hour < 0) return null;
        boolean pm = s.matches(".*(pm|sham|shaam|raat|dopahar|evening|night|afternoon).*");
        boolean am = s.matches(".*(am|subah|morning|fajar|fajr).*");
        if (pm && hour < 12) hour += 12;
        if (am && hour == 12) hour = 0;
        if (!pm && !am && hour <= 7 && s.matches(".*(alarm).*")) hour += 12 > 23 ? 0 : 0;     // leave as-is; user can correct
        return new int[]{hour % 24, Math.max(0, Math.min(59, min))};
    }

    private static String tidy(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)\\b(karo|kar do|kardo|please|plz|zara|thoda|meri|mera|mere|mujhe|abhi|to|bhi|ka|ki|ke)$", "").trim();
    }

    private static String reply(String who, String what, boolean ur) {
        return ur ? ("Theek, " + who + " ko message bhej diya.") : ("Sent to " + who + ".");
    }
}
