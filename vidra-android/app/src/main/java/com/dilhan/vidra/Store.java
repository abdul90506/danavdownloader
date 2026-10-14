package com.dilhan.vidra;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/** Settings + download history (JSON files in app storage). */
public final class Store {
    private Store() {}
    private static JSONObject settings;
    private static JSONArray history;
    private static final int MAX_HISTORY = 300;

    static String read(File f) {
        if (!f.exists()) return null;
        try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder(); char[] b = new char[8192]; int n;
            while ((n = r.read(b)) > 0) sb.append(b, 0, n);
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    static void write(File f, String s) {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) { w.write(s); }
        catch (Exception e) { return; }
        tmp.renameTo(f);
    }

    public static synchronized JSONObject settings(Context c) {
        if (settings == null) {
            try { String s = read(new File(c.getFilesDir(), "settings.json")); settings = s == null ? new JSONObject() : new JSONObject(s); }
            catch (Exception e) { settings = new JSONObject(); }
            try {
                if (!settings.has("folder")) settings.put("folder", "Danav");
                if (!settings.has("quality")) settings.put("quality", "best");
                if (!settings.has("preferMp4")) settings.put("preferMp4", true);
                if (!settings.has("clipboard")) settings.put("clipboard", true);
                if (!settings.has("notify")) settings.put("notify", true);
            } catch (Exception ignored) {}
        }
        return settings;
    }

    public static synchronized void setSettings(Context c, JSONObject patch) {
        JSONObject s = settings(c);
        try {
            JSONArray names = patch.names();
            if (names != null) for (int i = 0; i < names.length(); i++) { String k = names.getString(i); s.put(k, patch.get(k)); }
            String f = s.optString("folder", "").replaceAll("[\\\\:*?\"<>|]", "").replaceAll("\\.\\.", "").replaceAll("^/+|/+$", "").trim();
            s.put("folder", f);
        } catch (Exception ignored) {}
        write(new File(c.getFilesDir(), "settings.json"), s.toString());
    }

    public static String folder(Context c) { return settings(c).optString("folder", "Danav"); }

    public static synchronized JSONArray history(Context c) {
        if (history == null) {
            try { String s = read(new File(c.getFilesDir(), "history.json")); history = s == null ? new JSONArray() : new JSONArray(s); }
            catch (Exception e) { history = new JSONArray(); }
        }
        return history;
    }

    private static void saveHistory(Context c) { write(new File(c.getFilesDir(), "history.json"), history.toString()); }

    public static synchronized void addHistory(Context c, JSONObject item) {
        JSONArray h = history(c), n = new JSONArray();
        n.put(item);
        for (int i = 0; i < h.length() && n.length() < MAX_HISTORY; i++) n.put(h.opt(i));
        history = n; saveHistory(c);
    }

    public static synchronized JSONObject findHistory(Context c, String id) {
        JSONArray h = history(c);
        for (int i = 0; i < h.length(); i++) { JSONObject o = h.optJSONObject(i); if (o != null && id.equals(o.optString("id"))) return o; }
        return null;
    }

    public static synchronized void removeHistory(Context c, String id) {
        JSONArray h = history(c), n = new JSONArray();
        for (int i = 0; i < h.length(); i++) {
            JSONObject o = h.optJSONObject(i);
            if (o == null) continue;
            if (id.equals(o.optString("id"))) { String t = o.optString("thumbFile"); if (!t.isEmpty()) new File(t).delete(); continue; }
            n.put(o);
        }
        history = n; saveHistory(c);
    }

    public static synchronized void clearHistory(Context c) {
        JSONArray h = history(c);
        for (int i = 0; i < h.length(); i++) { JSONObject o = h.optJSONObject(i); if (o != null && !o.optString("thumbFile").isEmpty()) new File(o.optString("thumbFile")).delete(); }
        history = new JSONArray(); saveHistory(c);
    }
}
