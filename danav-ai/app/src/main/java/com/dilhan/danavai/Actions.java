package com.dilhan.danavai;

import android.content.Context;

import org.json.JSONArray;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What Danav actually did, remembered after the fact.
 *
 * Why this exists: the chat only ever re-sends the user's own words — never the tool turns — so a new answer
 * had no idea what the assistant had just done, and the voice call lost it too. ("Pehle maine ise kaam kaha,
 * phir poocha to bhool gaya.") Now every tool that runs is written here, and {@link Phone#contextLine(Context)}
 * hands the last few actions to both the chat and the call, so Danav always knows what it already did.
 */
final class Actions {
    private static final String FILE = "danav.actions";
    private static final String KEY = "log";
    private static final int MAX = 30;
    private static final Object lock = new Object();

    private Actions() {}

    /** One line per tool call: time, tool, what it was for, and whether it really worked. */
    static void add(Context c, String tool, String what, boolean ok) {
        if (c == null || tool == null || tool.isEmpty()) return;
        // only the things the user asked for, never the dozens of small screen steps inside them —
        // otherwise "sent WhatsApp to Amma" would be pushed out of the log by taps and scrolls
        if ("dismiss".equals(tool) || "end_call".equals(tool) || "read_screen".equals(tool) || "see_screen".equals(tool)
                || "tap".equals(tool) || "tap_xy".equals(tool) || "scroll".equals(tool) || "swipe".equals(tool)
                || "drag".equals(tool) || "set_slider".equals(tool) || "type_text".equals(tool)) return;
        try {
            synchronized (lock) {
                JSONArray old = load(c);
                JSONArray out = new JSONArray();
                out.put(new SimpleDateFormat("HH:mm", Locale.ENGLISH).format(new Date()) + " " + tool
                        + (what == null || what.isEmpty() ? "" : " — " + what)
                        + (ok ? "" : "  ✗ did not work"));
                for (int i = 0; i < old.length() && out.length() < MAX; i++) out.put(old.optString(i));
                c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)
                        .edit().putString(KEY, out.toString()).apply();
            }
        } catch (Throwable ignored) {}
    }

    private static JSONArray load(Context c) {
        try {
            String s = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "[]");
            return new JSONArray(s == null || s.isEmpty() ? "[]" : s);
        } catch (Throwable t) { return new JSONArray(); }
    }

    /** The newest {@code n} actions, one per line, newest first ("" when nothing has happened yet). */
    static String recent(Context c, int n) {
        if (c == null) return "";
        try {
            synchronized (lock) {
                JSONArray a = load(c);
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < a.length() && i < n; i++) b.append(b.length() == 0 ? "" : "\n").append(a.optString(i));
                return b.toString();
            }
        } catch (Throwable t) { return ""; }
    }

    static void clear(Context c) {
        try { c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply(); } catch (Throwable ignored) {}
    }
}
