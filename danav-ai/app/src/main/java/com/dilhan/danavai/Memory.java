package com.dilhan.danavai;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * What Danav remembers about its user (personalisation). Small plain-text notes the model saves itself —
 * name, city, language, people, routines, likes/dislikes, how the user wants answers. They are kept on the
 * phone only (files/danav_memory.json), injected into every chat and every voice call, and can be read,
 * removed one by one or wiped completely from Danav AI → Phone control → What Danav remembers.
 */
final class Memory {
    private static final int MAX = 120;          // hard cap so the prompt can never grow out of hand
    private static final int MAX_LEN = 240;      // one note, characters
    private static final String FILE = "danav_memory.json";

    private Memory() {}

    private static File file(Context c) { return new File(c.getFilesDir(), FILE); }

    static synchronized JSONArray all(Context c) {
        try {
            String s = Store.read(file(c));
            JSONArray a = s == null || s.trim().isEmpty() ? new JSONArray() : new JSONArray(s);
            return a;
        } catch (Exception e) { return new JSONArray(); }
    }

    private static void save(Context c, JSONArray a) { Store.write(file(c), a.toString()); }

    private static String clean(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.length() > MAX_LEN) t = t.substring(0, MAX_LEN);
        return t;
    }

    private static String key(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", ""); }

    private static String id(String s) {
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return Long.toHexString(h & 0x7fffffffffffffffL);
    }

    /** Saves one note. Returns true when it was stored (false = empty or already known). */
    static synchronized boolean add(Context c, String text) {
        String t = clean(text);
        if (t.length() < 3) return false;
        JSONArray a = all(c);
        String k = key(t);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String have = key(o.optString("t"));
            // already known, or this note is a shorter/stretch of one we have → keep the newest wording
            if (have.equals(k)) { o.remove("t"); try { o.put("t", t); o.put("at", System.currentTimeMillis()); } catch (Exception ignored) {} save(c, a); return true; }
            if (have.contains(k) || k.contains(have)) { if (k.length() > have.length()) { try { o.put("t", t); o.put("at", System.currentTimeMillis()); } catch (Exception ignored) {} save(c, a); } return true; }
        }
        try {
            a.put(new JSONObject().put("id", id(t)).put("t", t).put("at", System.currentTimeMillis()));
            while (a.length() > MAX) a.remove(0);            // oldest first
        } catch (Exception e) { return false; }
        save(c, a);
        return true;
    }

    /** Forgets everything that matches (a word of the note is enough). Returns how many were removed. */
    static synchronized int forget(Context c, String query) {
        String q = key(clean(query));
        JSONArray a = all(c), n = new JSONArray();
        int removed = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String t = o.optString("t");
            if (!q.isEmpty() && (key(t).contains(q) || q.contains(key(t)))) { removed++; continue; }
            n.put(o);
        }
        if (removed > 0) save(c, n);
        return removed;
    }

    static synchronized void remove(Context c, String id) {
        JSONArray a = all(c), n = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || id == null || !id.equals(o.optString("id"))) n.put(o);
        }
        save(c, n);
    }

    static synchronized void clear(Context c) { save(c, new JSONArray()); }

    static synchronized int count(Context c) { return all(c).length(); }

    /** The block that goes into the system instruction ("" when nothing is remembered yet). */
    static String prompt(Context c) {
        JSONArray a = all(c);
        if (a.length() == 0) return "";
        StringBuilder sb = new StringBuilder("\n\nWhat you remember about this user (personal notes from earlier conversations — "
                + "use them silently so answers feel personal: their name, language, people, places, habits and preferences. "
                + "Never list them out or say \"I remember that…\" unless the user asks; never contradict them; if the user corrects "
                + "something, call remember with the right version):");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String t = o.optString("t", "").trim();
            if (!t.isEmpty()) sb.append("\n- ").append(t);
        }
        return sb.toString();
    }

    /** The same notes as a short, spoken-style reminder for live calls (kept tighter). */
    static String livePrompt(Context c) {
        JSONArray a = all(c);
        if (a.length() == 0) return "";
        StringBuilder sb = new StringBuilder("\n\nPersonal notes about this user (from earlier talks — use them naturally, never read them out):");
        int n = Math.min(a.length(), 40);
        for (int i = 0; i < n; i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String t = o.optString("t", "").trim();
            if (!t.isEmpty()) sb.append("\n- ").append(t);
        }
        return sb.toString();
    }
}
