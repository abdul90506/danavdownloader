package com.dilhan.danavai;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/** Tiny text file helper (used by the memory store): UTF-8 JSON files inside the app's own storage. */
public final class Store {
    private Store() {}

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
}
