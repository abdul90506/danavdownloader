package com.dilhan.vidra;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The phone's own videos and music (MediaStore) + real frame thumbnails. */
public final class Library {
    private Library() {}
    static final ExecutorService thumbPool = Executors.newFixedThreadPool(2);

    static String[] permissions() {
        if (Build.VERSION.SDK_INT >= 33) return new String[]{Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO};
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    /** "granted" | "partial" (Android 14 selected photos/videos) | "denied" */
    static String permState(Context c) {
        boolean all = true;
        for (String p : permissions()) if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) all = false;
        if (all) return "granted";
        if (Build.VERSION.SDK_INT >= 34 && c.checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED) return "partial";
        return "denied";
    }

    static Uri videoUri(long id) { return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id); }
    static Uri audioUri(long id) { return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id); }

    private static String str(Cursor c, String col) { int i = c.getColumnIndex(col); return i < 0 || c.isNull(i) ? "" : c.getString(i); }
    private static long lng(Cursor c, String col) { int i = c.getColumnIndex(col); return i < 0 || c.isNull(i) ? 0 : c.getLong(i); }

    @SuppressWarnings("deprecation")
    static JSONArray videos(Context ctx) {
        JSONArray out = new JSONArray();
        ContentResolver cr = ctx.getContentResolver();
        List<String> cols = new ArrayList<>();
        String[] base = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.MIME_TYPE, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.BUCKET_ID};
        for (String s : base) cols.add(s);
        if (Build.VERSION.SDK_INT >= 29) { cols.add(MediaStore.Video.Media.RELATIVE_PATH); cols.add(MediaStore.Video.Media.ORIENTATION); }
        else cols.add(MediaStore.Video.Media.DATA);
        try (Cursor c = cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols.toArray(new String[0]), null, null,
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (c == null) return out;
            while (c.moveToNext()) {
                long id = lng(c, MediaStore.Video.Media._ID);
                JSONObject o = new JSONObject();
                String path = Build.VERSION.SDK_INT >= 29 ? str(c, MediaStore.Video.Media.RELATIVE_PATH) + str(c, MediaStore.Video.Media.DISPLAY_NAME) : str(c, MediaStore.Video.Media.DATA);
                long w = lng(c, MediaStore.Video.Media.WIDTH), h = lng(c, MediaStore.Video.Media.HEIGHT);
                long rot = Build.VERSION.SDK_INT >= 29 ? lng(c, MediaStore.Video.Media.ORIENTATION) : 0;
                if (rot == 90 || rot == 270) { long t = w; w = h; h = t; }
                o.put("id", id).put("uri", videoUri(id).toString()).put("name", str(c, MediaStore.Video.Media.DISPLAY_NAME))
                        .put("title", str(c, MediaStore.Video.Media.TITLE)).put("dur", lng(c, MediaStore.Video.Media.DURATION))
                        .put("size", lng(c, MediaStore.Video.Media.SIZE)).put("w", w).put("h", h)
                        .put("mime", str(c, MediaStore.Video.Media.MIME_TYPE)).put("added", lng(c, MediaStore.Video.Media.DATE_ADDED))
                        .put("mod", lng(c, MediaStore.Video.Media.DATE_MODIFIED)).put("folder", str(c, MediaStore.Video.Media.BUCKET_DISPLAY_NAME))
                        .put("bucket", str(c, MediaStore.Video.Media.BUCKET_ID)).put("path", path);
                out.put(o);
            }
        } catch (Exception ignored) {}
        return out;
    }

    @SuppressWarnings("deprecation")
    static JSONArray audio(Context ctx) {
        JSONArray out = new JSONArray();
        List<String> cols = new ArrayList<>();
        String[] base = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.TRACK};
        for (String s : base) cols.add(s);
        if (Build.VERSION.SDK_INT >= 29) { cols.add(MediaStore.Audio.Media.RELATIVE_PATH); cols.add(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME); }
        else cols.add(MediaStore.Audio.Media.DATA);
        String sel = MediaStore.Audio.Media.DURATION + ">=15000 AND " + MediaStore.Audio.Media.IS_RINGTONE + "=0 AND "
                + MediaStore.Audio.Media.IS_NOTIFICATION + "=0 AND " + MediaStore.Audio.Media.IS_ALARM + "=0";
        try (Cursor c = ctx.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols.toArray(new String[0]), sel, null,
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (c == null) return out;
            while (c.moveToNext()) {
                long id = lng(c, MediaStore.Audio.Media._ID);
                String artist = str(c, MediaStore.Audio.Media.ARTIST);
                if ("<unknown>".equals(artist)) artist = "";
                String path = Build.VERSION.SDK_INT >= 29 ? str(c, MediaStore.Audio.Media.RELATIVE_PATH) + str(c, MediaStore.Audio.Media.DISPLAY_NAME) : str(c, MediaStore.Audio.Media.DATA);
                String folder = Build.VERSION.SDK_INT >= 29 ? str(c, MediaStore.Audio.Media.BUCKET_DISPLAY_NAME) : new File(path).getParentFile() != null ? new File(path).getParentFile().getName() : "";
                JSONObject o = new JSONObject();
                o.put("id", id).put("uri", audioUri(id).toString()).put("name", str(c, MediaStore.Audio.Media.DISPLAY_NAME))
                        .put("title", str(c, MediaStore.Audio.Media.TITLE)).put("artist", artist)
                        .put("album", str(c, MediaStore.Audio.Media.ALBUM)).put("albumId", lng(c, MediaStore.Audio.Media.ALBUM_ID))
                        .put("dur", lng(c, MediaStore.Audio.Media.DURATION)).put("size", lng(c, MediaStore.Audio.Media.SIZE))
                        .put("mime", str(c, MediaStore.Audio.Media.MIME_TYPE)).put("added", lng(c, MediaStore.Audio.Media.DATE_ADDED))
                        .put("mod", lng(c, MediaStore.Audio.Media.DATE_MODIFIED)).put("track", lng(c, MediaStore.Audio.Media.TRACK) % 1000)
                        .put("folder", folder).put("path", path);
                out.put(o);
            }
        } catch (Exception ignored) {}
        return out;
    }

    static File thumbDir(Context c) { File d = new File(c.getCacheDir(), "thumbs"); d.mkdirs(); return d; }

    /** Returns a JPEG path for a video frame / album cover, or "" if there is none. Blocking. */
    @SuppressWarnings("deprecation")
    static String thumb(Context ctx, String kind, long id, long mod, String uriStr) {
        File dir = thumbDir(ctx);
        String key = kind + "_" + id + "_" + mod;
        File f = new File(dir, key + ".jpg");
        if (f.length() > 0) return f.getAbsolutePath();
        File none = new File(dir, key + ".none");
        if (none.exists()) return "";
        Bitmap bmp = null;
        Uri uri = uriStr != null && !uriStr.isEmpty() ? Uri.parse(uriStr) : kind.equals("a") ? audioUri(id) : videoUri(id);
        try {
            if (kind.equals("v")) {
                if (Build.VERSION.SDK_INT >= 29 && "content".equals(uri.getScheme())) bmp = ctx.getContentResolver().loadThumbnail(uri, new Size(640, 360), null);
                else if ("content".equals(uri.getScheme()) && id > 0) bmp = MediaStore.Video.Thumbnails.getThumbnail(ctx.getContentResolver(), id, MediaStore.Video.Thumbnails.MINI_KIND, null);
                if (bmp == null) bmp = frame(ctx, uri);
            } else {
                byte[] pic = null;
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try { r.setDataSource(ctx, uri); pic = r.getEmbeddedPicture(); } catch (Exception ignored) {} finally { try { r.release(); } catch (Exception ignored) {} }
                if (pic != null) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                    int s = 1; while (o.outWidth / (s * 2) >= 400) s *= 2;
                    o = new BitmapFactory.Options(); o.inSampleSize = s;
                    bmp = BitmapFactory.decodeByteArray(pic, 0, pic.length, o);
                }
            }
        } catch (Throwable ignored) {}
        if (bmp == null) { try { none.createNewFile(); } catch (Exception ignored) {} return ""; }
        try (FileOutputStream out = new FileOutputStream(f)) {
            int max = kind.equals("v") ? 640 : 480;
            if (bmp.getWidth() > max) bmp = Bitmap.createScaledBitmap(bmp, max, Math.max(1, bmp.getHeight() * max / bmp.getWidth()), true);
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out);
        } catch (Exception e) { return ""; }
        return f.getAbsolutePath();
    }

    /** A frame ~10% into the video (skips black intros). */
    static Bitmap frame(Context ctx, Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            if ("file".equals(uri.getScheme()) || uri.getScheme() == null) r.setDataSource(uri.getPath()); else r.setDataSource(ctx, uri);
            long dur = 0;
            try { dur = Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)); } catch (Exception ignored) {}
            long at = Math.min(dur / 10, 15000) * 1000;
            Bitmap b = Build.VERSION.SDK_INT >= 27 ? r.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640) : r.getFrameAtTime(at);
            return b != null ? b : r.getFrameAtTime();
        } catch (Throwable e) { return null; } finally { try { r.release(); } catch (Exception ignored) {} }
    }

    /** Deletes media. Returns null when done, or a PendingIntent the user must confirm (Android 10+). */
    static PendingIntent delete(Activity a, List<Uri> uris) throws Exception {
        if (Build.VERSION.SDK_INT >= 30) return MediaStore.createDeleteRequest(a.getContentResolver(), uris);
        for (Uri u : uris) {
            try { a.getContentResolver().delete(u, null, null); }
            catch (SecurityException e) {
                if (Build.VERSION.SDK_INT >= 29 && e instanceof RecoverableSecurityException)
                    return ((RecoverableSecurityException) e).getUserAction().getActionIntent();
                throw e;
            }
        }
        return null;
    }

    static void clearThumbs(Context c) { File[] fs = thumbDir(c).listFiles(); if (fs != null) for (File f : fs) f.delete(); }
}
