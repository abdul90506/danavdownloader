package com.dilhan.vidra;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Moves finished files into the phone's public Download/<folder> and opens / shares / deletes them. */
public final class Saver {
    private Saver() {}

    public static final class Result { public Uri uri; public String path; public String shown; public String name; public String mime; public long size; }

    public interface Progress { void on(long done, long total); }

    public static String mimeFor(String ext) {
        ext = ext.toLowerCase(Locale.ROOT);
        switch (ext) {
            case "mp4": case "m4v": return "video/mp4";
            case "webm": return "video/webm";
            case "mkv": return "video/x-matroska";
            case "mov": return "video/quicktime";
            case "3gp": return "video/3gpp";
            case "ts": return "video/mp2t";
            case "flv": return "video/x-flv";
            case "m4a": return "audio/mp4";
            case "mp3": return "audio/mpeg";
            case "opus": case "ogg": return "audio/ogg";
            case "wav": return "audio/wav";
        }
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "application/octet-stream";
    }

    public static String safeName(String title) {
        String s = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (s.startsWith(".")) s = s.substring(1);
        if (s.length() > 90) s = s.substring(0, 90).trim();
        return s.isEmpty() ? "video" : s;
    }

    public static Result save(Context c, File src, String baseName, String ext, Progress p) throws Exception {
        String folder = Store.folder(c);
        String rel = Environment.DIRECTORY_DOWNLOADS + (folder.isEmpty() ? "" : "/" + folder);
        Result r = new Result();
        r.mime = mimeFor(ext);
        r.size = src.length();
        String name = baseName + "." + ext;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, r.mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new Exception("Android refused to create the file in " + rel);
            try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new Exception("Could not write to " + rel);
                copy(in, out, r.size, p);
            } catch (Exception e) {
                cr.delete(uri, null, null);
                throw e;
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            r.uri = uri;
            // MediaStore may have renamed it (e.g. "name (1).mp4")
            try (android.database.Cursor cur = cr.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                r.name = cur != null && cur.moveToFirst() ? cur.getString(0) : name;
            }
            r.path = rel + "/" + r.name;
            r.shown = r.path;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folder);
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("Could not create " + dir);
            File dst = new File(dir, name);
            for (int i = 1; dst.exists(); i++) dst = new File(dir, baseName + " (" + i + ")." + ext);
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) { copy(in, out, r.size, p); }
            MediaScannerConnection.scanFile(c, new String[]{dst.getAbsolutePath()}, new String[]{r.mime}, null);
            r.uri = FileProvider.getUriForFile(c, c.getPackageName() + ".files", dst);
            r.name = dst.getName();
            r.shown = Environment.DIRECTORY_DOWNLOADS + "/" + folder + "/" + dst.getName();
            r.path = dst.getAbsolutePath();
        }
        return r;
    }

    private static void copy(InputStream in, OutputStream out, long total, Progress p) throws Exception {
        byte[] buf = new byte[256 * 1024]; int n; long done = 0, last = 0;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n); done += n;
            if (p != null && done - last > 4 * 1024 * 1024) { last = done; p.on(done, total); }
        }
        out.flush();
    }

    /** Real frame from the downloaded file → small JPEG kept for the Downloads list. */
    public static String thumbnail(Context c, File media, String id) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(media.getAbsolutePath());
            Bitmap b = null;
            String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long d = dur == null ? 0 : Long.parseLong(dur);
            b = mmr.getFrameAtTime(Math.min(d * 1000 / 10, 3_000_000L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) { byte[] art = mmr.getEmbeddedPicture(); if (art != null) b = android.graphics.BitmapFactory.decodeByteArray(art, 0, art.length); }
            if (b == null) return null;
            int w = b.getWidth(), h = b.getHeight(); float s = 240f / Math.max(w, h);
            if (s < 1) b = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)), true);
            File dir = new File(c.getFilesDir(), "thumbs"); dir.mkdirs();
            File f = new File(dir, id + ".jpg");
            try (OutputStream o = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.JPEG, 78, o); }
            return f.getAbsolutePath();
        } catch (Throwable e) {
            return null;
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
    }

    public static boolean exists(Context c, Uri uri) {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) { return in != null; } catch (Exception e) { return false; }
    }

    public static boolean delete(Context c, Uri uri, String path) {
        try {
            if (Build.VERSION.SDK_INT >= 29) return c.getContentResolver().delete(uri, null, null) > 0;
            return path != null && new File(path).delete();
        } catch (Exception e) { return false; }
    }

    public static Intent openIntent(Uri uri, String mime) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    public static Intent shareIntent(Uri uri, String mime, String title) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.putExtra(Intent.EXTRA_TITLE, title);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent ch = Intent.createChooser(i, "Share video");
        ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return ch;
    }
}
