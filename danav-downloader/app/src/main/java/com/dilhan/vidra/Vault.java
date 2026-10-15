package com.dilhan.vidra;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Private folder. Everything inside is encrypted on the phone:
 *
 *  - A random 256-bit data key encrypts every file (AES-256-CTR, a key per file derived with HMAC-SHA256)
 *    and the index with names / types (AES-256-GCM). Nothing readable is ever written to disk.
 *  - The data key is wrapped twice: with a key stretched from the PIN (PBKDF2, 150 000 rounds, random salt)
 *    and then with a non-exportable key inside the phone's hardware keystore (TEE / StrongBox).
 *    The PIN itself — or any hash of it — is never stored. A wrong PIN simply fails to decrypt.
 *  - Because the keystore key can't leave the phone's secure hardware, copying the files to a computer is
 *    useless: there's nothing to brute-force offline. On the phone, wrong PINs lock the vault for longer
 *    and longer periods.
 */
final class Vault {
    private Vault() {}

    private static final String KS_ALIAS = "vidra.vault.v1";
    private static final int ITER = 150_000;
    private static final SecureRandom RNG = new SecureRandom();

    private static volatile byte[] dek;           // data key, only in memory while unlocked
    private static JSONArray index;               // decrypted index while unlocked
    static volatile boolean holdLock;             // a file picker is open — don't auto-lock
    static volatile long lastUse;

    // ------------------------------------------------------------------ paths

    static File root(Context c) { File d = new File(c.getFilesDir(), "vault"); d.mkdirs(); return d; }
    private static File dataDir(Context c) { File d = new File(root(c), "d"); d.mkdirs(); return d; }
    private static File thumbDir(Context c) { File d = new File(root(c), "t"); d.mkdirs(); return d; }
    private static File keyFile(Context c) { return new File(root(c), "key.json"); }
    private static File indexFile(Context c) { return new File(root(c), "index.bin"); }
    static File openDir(Context c) { File d = new File(c.getCacheDir(), "vopen"); d.mkdirs(); return d; }

    static boolean isSetUp(Context c) { return keyFile(c).exists(); }
    static boolean isUnlocked() { return dek != null; }

    // ------------------------------------------------------------------ PIN / keys

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("vault", 0); }

    /** Seconds until another PIN attempt is allowed (0 = now). */
    static long waitSeconds(Context c) {
        long until = prefs(c).getLong("until", 0), now = System.currentTimeMillis();
        // clock moved backwards? keep the lock based on elapsed time since boot as well
        long untilUp = prefs(c).getLong("untilUp", 0), up = SystemClock.elapsedRealtime();
        long a = until > now ? (until - now) : 0, b = untilUp > up && untilUp - up < 3_600_000L ? untilUp - up : 0;
        return (Math.max(a, b) + 999) / 1000;
    }

    private static void failed(Context c) {
        SharedPreferences p = prefs(c);
        int f = p.getInt("fails", 0) + 1;
        long wait = f < 5 ? 0 : Math.min(3_600_000L, 30_000L << Math.min(7, f - 5));   // 30 s, 1 min, 2 min … 1 h
        p.edit().putInt("fails", f).putLong("until", System.currentTimeMillis() + wait).putLong("untilUp", SystemClock.elapsedRealtime() + wait).apply();
    }

    private static boolean validPin(String pin) { return pin != null && pin.matches("\\d{4,8}"); }

    private static byte[] stretch(String pin, byte[] salt, String alg, int iter) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iter, 256);
        try { return SecretKeyFactory.getInstance(alg).generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    private static String pbkdfAlg() { return Build.VERSION.SDK_INT >= 26 ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1"; }

    private static SecretKey keystoreKey(boolean create) {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(KS_ALIAS)) return (SecretKey) ks.getKey(KS_ALIAS, null);
            if (!create) return null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256);
                    if (attempt == 0 && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true);
                    else if (attempt == 0) continue;
                    KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                    g.init(b.build());
                    return g.generateKey();
                } catch (Exception e) { /* no StrongBox: fall back to the TEE */ }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static byte[] gcm(int mode, byte[] key, byte[] iv, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return c.doFinal(data);
    }

    private static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }
    private static byte[] unb64(String s) { return Base64.decode(s, Base64.NO_WRAP); }

    private static void writeKey(Context c, String pin, byte[] key) throws Exception {
        byte[] salt = new byte[16], iv1 = new byte[12];
        RNG.nextBytes(salt); RNG.nextBytes(iv1);
        String alg = pbkdfAlg();
        byte[] kp = stretch(pin, salt, alg, ITER);
        byte[] inner = gcm(Cipher.ENCRYPT_MODE, kp, iv1, key);
        Arrays.fill(kp, (byte) 0);
        JSONObject o = new JSONObject().put("v", 1).put("alg", alg).put("iter", ITER).put("salt", b64(salt)).put("iv1", b64(iv1));
        SecretKey hw = keystoreKey(true);
        if (hw != null) {
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.ENCRYPT_MODE, hw);
            byte[] outer = ci.doFinal(inner);
            o.put("ks", true).put("iv2", b64(ci.getIV())).put("data", b64(outer));
        } else {
            o.put("ks", false).put("data", b64(inner));
        }
        File tmp = new File(root(c), "key.tmp");
        try (FileOutputStream f = new FileOutputStream(tmp)) { f.write(o.toString().getBytes(StandardCharsets.UTF_8)); f.getFD().sync(); }
        if (!tmp.renameTo(keyFile(c))) throw new IOException("could not save the key");
    }

    /** @return the data key, or null when the PIN is wrong. */
    private static byte[] readKey(Context c, String pin) throws Exception {
        JSONObject o = new JSONObject(new String(readAll(keyFile(c)), StandardCharsets.UTF_8));
        byte[] data = unb64(o.getString("data"));
        if (o.optBoolean("ks")) {
            SecretKey hw = keystoreKey(false);
            if (hw == null) throw new IllegalStateException("The phone's secure key for the private folder is gone (the phone was reset or the app data was restored).");
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, hw, new GCMParameterSpec(128, unb64(o.getString("iv2"))));
            data = ci.doFinal(data);
        }
        byte[] kp = stretch(pin, unb64(o.getString("salt")), o.optString("alg", "PBKDF2WithHmacSHA1"), o.optInt("iter", ITER));
        try { return gcm(Cipher.DECRYPT_MODE, kp, unb64(o.getString("iv1")), data); }
        catch (javax.crypto.AEADBadTagException e) { return null; }
        finally { Arrays.fill(kp, (byte) 0); }
    }

    static synchronized JSONObject setup(Context c, String pin) {
        JSONObject r = new JSONObject();
        try {
            if (!validPin(pin)) return r.put("ok", false).put("error", "The PIN must be 4 digits.");
            if (isSetUp(c)) return r.put("ok", false).put("error", "The private folder already has a PIN.");
            byte[] key = new byte[32]; RNG.nextBytes(key);
            writeKey(c, pin, key);
            dek = key; index = new JSONArray(); saveIndex(c);
            prefs(c).edit().putInt("fails", 0).putLong("until", 0).putLong("untilUp", 0).apply();
            lastUse = System.currentTimeMillis();
            return r.put("ok", true);
        } catch (Exception e) { return err(r, e); }
    }

    static synchronized JSONObject unlock(Context c, String pin) {
        JSONObject r = new JSONObject();
        try {
            long w = waitSeconds(c);
            if (w > 0) return r.put("ok", false).put("wait", w).put("error", "Too many wrong PINs. Try again in " + human(w) + ".");
            if (!validPin(pin)) return r.put("ok", false).put("error", "Enter your 4-digit PIN.");
            byte[] key = readKey(c, pin);
            if (key == null) {
                failed(c);
                int f = prefs(c).getInt("fails", 0);
                long ww = waitSeconds(c);
                return r.put("ok", false).put("wait", ww).put("error", ww > 0 ? "Wrong PIN. Locked for " + human(ww) + "." : "Wrong PIN" + (f >= 3 ? " — " + (5 - f) + " tries left before a timeout." : "."));
            }
            dek = key;
            index = loadIndex(c);
            prefs(c).edit().putInt("fails", 0).putLong("until", 0).putLong("untilUp", 0).apply();
            lastUse = System.currentTimeMillis();
            return r.put("ok", true);
        } catch (Exception e) { return err(r, e); }
    }

    static synchronized JSONObject changePin(Context c, String old, String pin) {
        JSONObject r = new JSONObject();
        try {
            long w = waitSeconds(c);
            if (w > 0) return r.put("ok", false).put("error", "Too many wrong PINs. Try again in " + human(w) + ".");
            if (!validPin(pin)) return r.put("ok", false).put("error", "The new PIN must be 4 digits.");
            byte[] key = readKey(c, old);
            if (key == null) { failed(c); return r.put("ok", false).put("error", "The current PIN is wrong."); }
            writeKey(c, pin, key);
            Arrays.fill(key, (byte) 0);
            prefs(c).edit().putInt("fails", 0).putLong("until", 0).putLong("untilUp", 0).apply();
            return r.put("ok", true);
        } catch (Exception e) { return err(r, e); }
    }

    static synchronized void lock(Context c) {
        byte[] k = dek;
        dek = null; index = null;
        if (k != null) Arrays.fill(k, (byte) 0);
        if (c != null) clearOpen(c);
    }

    /** Deletes everything in the private folder (used when the PIN is forgotten). */
    static synchronized JSONObject erase(Context c) {
        lock(c);
        Jobs.deleteTree(root(c));
        try { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); ks.deleteEntry(KS_ALIAS); } catch (Exception ignored) {}
        prefs(c).edit().clear().apply();
        try { return new JSONObject().put("ok", true); } catch (Exception e) { return new JSONObject(); }
    }

    static void clearOpen(Context c) {
        File[] fs = openDir(c).listFiles();
        if (fs != null) for (File f : fs) Jobs.deleteTree(f);
    }

    private static String human(long s) { return s >= 60 ? ((s + 59) / 60) + " min" : s + " s"; }

    private static JSONObject err(JSONObject r, Exception e) {
        try { return r.put("ok", false).put("error", e.getMessage() == null ? e.toString() : e.getMessage()); } catch (Exception x) { return r; }
    }

    // ------------------------------------------------------------------ index

    private static JSONArray loadIndex(Context c) throws Exception {
        File f = indexFile(c);
        if (!f.exists()) return new JSONArray();
        byte[] all = readAll(f);
        byte[] plain = gcm(Cipher.DECRYPT_MODE, subKey("index"), Arrays.copyOf(all, 12), Arrays.copyOfRange(all, 12, all.length));
        return new JSONArray(new String(plain, StandardCharsets.UTF_8));
    }

    private static void saveIndex(Context c) throws Exception {
        byte[] iv = new byte[12]; RNG.nextBytes(iv);
        byte[] ct = gcm(Cipher.ENCRYPT_MODE, subKey("index"), iv, index.toString().getBytes(StandardCharsets.UTF_8));
        File tmp = new File(root(c), "index.tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(iv); o.write(ct); o.getFD().sync(); }
        if (!tmp.renameTo(indexFile(c))) throw new IOException("could not save the index");
    }

    static synchronized JSONArray list() {
        lastUse = System.currentTimeMillis();
        return index == null ? new JSONArray() : index;
    }

    static synchronized JSONObject item(String id) {
        if (index == null || id == null) return null;
        for (int i = 0; i < index.length(); i++) { JSONObject o = index.optJSONObject(i); if (o != null && id.equals(o.optString("id"))) return o; }
        return null;
    }

    // ------------------------------------------------------------------ crypto for files

    private static byte[] subKey(String label) throws Exception {
        byte[] k = dek;
        if (k == null) throw new IllegalStateException("locked");
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(k, "HmacSHA256"));
        return m.doFinal(("vidra-vault/" + label).getBytes(StandardCharsets.UTF_8));
    }

    private static Cipher ctr(byte[] key, byte[] iv, long offset) throws Exception {
        BigInteger ctr = new BigInteger(1, iv).add(BigInteger.valueOf(offset / 16));
        byte[] b = ctr.toByteArray(), iv2 = new byte[16];
        int n = Math.min(16, b.length);
        System.arraycopy(b, b.length - n, iv2, 16 - n, n);
        Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv2));
        int skip = (int) (offset % 16);
        if (skip > 0) c.update(new byte[skip]);
        return c;
    }

    private static long encryptTo(InputStream in, File out, byte[] key, Runnable tick) throws Exception {
        byte[] iv = new byte[16]; RNG.nextBytes(iv);
        Cipher c = ctr(key, iv, 0);
        long n = 0;
        try (OutputStream o = new java.io.BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            o.write(iv);
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) {
                o.write(c.update(buf, 0, r));
                n += r;
                if (tick != null && (n & ((1 << 22) - 1)) < r) tick.run();
            }
        }
        return n;
    }

    /** Plain bytes of a stored file, starting at `offset`. */
    static InputStream open(Context c, String id, boolean thumb, long offset) throws Exception {
        byte[] key = subKey((thumb ? "t:" : "f:") + id);
        File f = new File(thumb ? thumbDir(c) : dataDir(c), safeId(id) + ".bin");
        final RandomAccessFile raf = new RandomAccessFile(f, "r");
        byte[] iv = new byte[16];
        raf.readFully(iv);
        raf.seek(16 + offset);
        final Cipher ci = ctr(key, iv, offset);
        InputStream raw = new InputStream() {
            @Override public int read() throws IOException { byte[] b = new byte[1]; int n = read(b, 0, 1); return n <= 0 ? -1 : b[0] & 0xff; }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                byte[] tmp = new byte[Math.min(len, 1 << 16)];
                int n = raf.read(tmp, 0, tmp.length);
                if (n <= 0) return -1;
                byte[] p = ci.update(tmp, 0, n);
                System.arraycopy(p, 0, b, off, p.length);
                return p.length;
            }
            @Override public void close() throws IOException { raf.close(); }
        };
        lastUse = System.currentTimeMillis();
        return raw;
    }

    /** Random-access reader for MediaMetadataRetriever (seek previews of private videos). */
    static android.media.MediaDataSource mediaSource(Context c, String id) {
        final Context ctx = c.getApplicationContext();
        final long size = plainSize(ctx, id);
        return new android.media.MediaDataSource() {
            InputStream in; long at = -1;
            @Override public synchronized int readAt(long pos, byte[] buf, int off, int len) throws IOException {
                if (pos >= size) return -1;
                try {
                    if (in == null || pos != at) { if (in != null) in.close(); in = open(ctx, id, false, pos); at = pos; }
                    int n = in.read(buf, off, (int) Math.min(len, size - pos));
                    if (n > 0) at += n;
                    return n;
                } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException(e); }
            }
            @Override public long getSize() { return size; }
            @Override public synchronized void close() throws IOException { if (in != null) in.close(); in = null; }
        };
    }

    static long plainSize(Context c, String id) { File f = new File(dataDir(c), safeId(id) + ".bin"); return Math.max(0, f.length() - 16); }

    private static String safeId(String id) { return id == null ? "x" : id.replaceAll("[^a-f0-9]", ""); }

    // ------------------------------------------------------------------ import / export

    interface Progress { void on(int done, int total, String name); }

    static final class Src {
        final Uri uri; final String folder; final boolean media;
        Src(Uri u, String f, boolean m) { uri = u; folder = f; media = m; }
    }

    /** Lists every file inside a folder picked with the system picker. */
    static List<Src> walkTree(Context c, Uri tree) {
        List<Src> out = new ArrayList<>();
        String rootDoc = DocumentsContract.getTreeDocumentId(tree);
        String name = displayName(c, DocumentsContract.buildDocumentUriUsingTree(tree, rootDoc));
        walk(c, tree, rootDoc, name == null ? "Folder" : name, out, 0);
        return out;
    }

    private static void walk(Context c, Uri tree, String doc, String folder, List<Src> out, int depth) {
        if (depth > 6 || out.size() > 2000) return;
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc);
        try (Cursor cur = c.getContentResolver().query(kids, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                String id = cur.getString(0), mime = cur.getString(1);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) walk(c, tree, id, folder, out, depth + 1);
                else out.add(new Src(DocumentsContract.buildDocumentUriUsingTree(tree, id), folder, false));
            }
        } catch (Exception ignored) {}
    }

    /**
     * Encrypts the files into the private folder. Originals are removed afterwards when `remove` is set
     * (system picker files directly; gallery items through the system's delete confirmation).
     * @return {ok, added, failed, mediaToDelete:[uris], kept}
     */
    static JSONObject importAll(Context c, List<Src> srcs, boolean remove, Progress p) {
        JSONObject r = new JSONObject();
        int added = 0, failed = 0, kept = 0;
        JSONArray mediaDel = new JSONArray();
        ContentResolver cr = c.getContentResolver();
        try {
            for (int i = 0; i < srcs.size(); i++) {
                Src s = srcs.get(i);
                String name = displayName(c, s.uri);
                if (name == null) name = "file";
                if (p != null) p.on(i, srcs.size(), name);
                String id = newId();
                File out = new File(dataDir(c), id + ".bin"), tmp = new File(dataDir(c), id + ".tmp");
                try {
                    String mime = cr.getType(s.uri);
                    if (mime == null || mime.equals("application/octet-stream")) mime = mimeOf(name);
                    long size;
                    final int fi = i; final String fn = name;
                    try (InputStream in = cr.openInputStream(s.uri)) {
                        if (in == null) throw new IOException("can't read");
                        size = encryptTo(in, tmp, subKey("f:" + id), p == null ? null : () -> p.on(fi, srcs.size(), fn));
                    }
                    if (!tmp.renameTo(out)) throw new IOException("can't save");
                    String kind = mime.startsWith("image/") ? "photo" : mime.startsWith("video/") ? "video" : mime.startsWith("audio/") ? "audio" : "file";
                    JSONObject o = new JSONObject().put("id", id).put("name", name).put("mime", mime).put("size", size).put("kind", kind)
                            .put("folder", s.folder == null ? "" : s.folder).put("added", System.currentTimeMillis());
                    makeThumb(c, s.uri, id, kind, o);
                    synchronized (Vault.class) {
                        if (index == null) throw new IllegalStateException("locked");
                        index.put(o);
                        saveIndex(c);
                    }
                    added++;
                    if (remove) {
                        boolean gone = false;
                        if (s.media || "media".equals(s.uri.getAuthority())) { mediaDel.put(s.uri.toString()); gone = true; }
                        else {
                            try { gone = DocumentsContract.deleteDocument(cr, s.uri); } catch (Exception ignored) {}
                            if (!gone) {
                                // picker returned a gallery item: ask MediaStore instead
                                Uri m = mediaUriOf(c, s.uri);
                                if (m != null) { mediaDel.put(m.toString()); gone = true; }
                            }
                        }
                        if (!gone) kept++;
                    }
                } catch (Exception e) {
                    tmp.delete(); out.delete(); new File(thumbDir(c), id + ".bin").delete();
                    failed++;
                    if (e instanceof IllegalStateException) break;
                }
            }
            if (p != null) p.on(srcs.size(), srcs.size(), "");
            return r.put("ok", true).put("added", added).put("failed", failed).put("kept", kept).put("mediaToDelete", mediaDel);
        } catch (Exception e) { return err(r, e); }
    }

    private static Uri mediaUriOf(Context c, Uri doc) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                Uri m = android.provider.MediaStore.getMediaUri(c, doc);
                if (m != null) return m;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void makeThumb(Context c, Uri src, String id, String kind, JSONObject o) {
        Bitmap bm = null;
        try {
            if (kind.equals("photo")) {
                BitmapFactory.Options b = new BitmapFactory.Options();
                b.inJustDecodeBounds = true;
                try (InputStream in = c.getContentResolver().openInputStream(src)) { BitmapFactory.decodeStream(in, null, b); }
                o.put("w", b.outWidth).put("h", b.outHeight);
                int s = 1; while (Math.max(b.outWidth, b.outHeight) / (s * 2) >= 420) s *= 2;
                BitmapFactory.Options d = new BitmapFactory.Options(); d.inSampleSize = s;
                try (InputStream in = c.getContentResolver().openInputStream(src)) { bm = BitmapFactory.decodeStream(in, null, d); }
                bm = rotateByExif(c, src, bm);
            } else if (kind.equals("video") || kind.equals("audio")) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(c, src);
                    String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (d != null) o.put("dur", Long.parseLong(d));
                    if (kind.equals("video")) {
                        String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                        if (w != null && h != null) o.put("w", Integer.parseInt(w)).put("h", Integer.parseInt(h));
                        long at = o.optLong("dur", 0) > 4000 ? 1_500_000L : 0;
                        bm = Build.VERSION.SDK_INT >= 27 ? r.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480) : r.getFrameAtTime(at);
                    } else {
                        byte[] art = r.getEmbeddedPicture();
                        if (art != null) { BitmapFactory.Options op = new BitmapFactory.Options(); op.inSampleSize = 2; bm = BitmapFactory.decodeByteArray(art, 0, art.length, op); }
                    }
                } finally { try { r.release(); } catch (Exception ignored) {} }
            }
            if (bm == null) return;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 82, bo);
            try (InputStream in = new java.io.ByteArrayInputStream(bo.toByteArray())) { encryptTo(in, new File(thumbDir(c), id + ".bin"), subKey("t:" + id), null); }
            o.put("thumb", true);
        } catch (Throwable ignored) {
        } finally { if (bm != null) bm.recycle(); }
    }

    private static Bitmap rotateByExif(Context c, Uri src, Bitmap bm) {
        if (bm == null || Build.VERSION.SDK_INT < 24) return bm;
        try (InputStream in = c.getContentResolver().openInputStream(src)) {
            android.media.ExifInterface e = new android.media.ExifInterface(in);
            int or = e.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
            int deg = or == 6 ? 90 : or == 3 ? 180 : or == 8 ? 270 : 0;
            if (deg == 0) return bm;
            android.graphics.Matrix m = new android.graphics.Matrix(); m.postRotate(deg);
            Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (r != bm) bm.recycle();
            return r;
        } catch (Exception e) { return bm; }
    }

    /** Decrypts into a temporary file (deleted when the folder locks). */
    static File decryptToTemp(Context c, String id) throws Exception {
        JSONObject o = item(id);
        if (o == null) throw new IOException("not found");
        File d = new File(openDir(c), safeId(id)); d.mkdirs();
        File out = new File(d, Saver.safeName(o.optString("name", "file")));
        if (out.exists() && out.length() == o.optLong("size", -1)) return out;
        try (InputStream in = open(c, id, false, 0); OutputStream os = new FileOutputStream(out)) {
            byte[] b = new byte[1 << 16]; int n;
            while ((n = in.read(b)) > 0) os.write(b, 0, n);
        }
        return out;
    }

    /** Moves an item back to the phone (Download/<folder>) and removes it from the private folder. */
    static JSONObject restore(Context c, String id) {
        JSONObject r = new JSONObject();
        try {
            JSONObject o = item(id);
            if (o == null) return r.put("ok", false).put("error", "Not found");
            File tmp = decryptToTemp(c, id);
            String name = o.optString("name", "file"), ext = name.contains(".") ? Jobs.extOf(name) : "bin";
            String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            Saver.Result res = Saver.save(c, tmp, Saver.safeName(base), ext, null);
            Jobs.deleteTree(tmp.getParentFile());
            delete(c, id);
            return r.put("ok", true).put("shown", res.shown);
        } catch (Exception e) { return err(r, e); }
    }

    static synchronized void delete(Context c, String id) throws Exception {
        if (index == null) throw new IllegalStateException("locked");
        JSONArray n = new JSONArray();
        for (int i = 0; i < index.length(); i++) { JSONObject o = index.optJSONObject(i); if (o != null && !id.equals(o.optString("id"))) n.put(o); }
        index = n;
        saveIndex(c);
        shred(new File(dataDir(c), safeId(id) + ".bin"));
        shred(new File(thumbDir(c), safeId(id) + ".bin"));
        Jobs.deleteTree(new File(openDir(c), safeId(id)));
    }

    static synchronized void setFolder(Context c, String id, String folder) throws Exception {
        JSONObject o = item(id);
        if (o == null) return;
        o.put("folder", folder == null ? "" : folder);
        saveIndex(c);
    }

    /** Overwrites the start of the file before deleting it (the key per file is gone with the index entry anyway). */
    private static void shred(File f) {
        if (!f.exists()) return;
        try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
            byte[] z = new byte[(int) Math.min(f.length(), 1 << 16)];
            RNG.nextBytes(z);
            r.write(z);
        } catch (Exception ignored) {}
        f.delete();
    }

    // ------------------------------------------------------------------ helpers

    private static String newId() { byte[] b = new byte[12]; RNG.nextBytes(b); return String.format(Locale.US, "%024x", new BigInteger(1, b)); }

    static String displayName(Context c, Uri u) {
        try (Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {}
        String p = u.getLastPathSegment();
        return p == null ? null : p.substring(p.lastIndexOf('/') + 1);
    }

    static String mimeOf(String name) {
        String ext = Jobs.extOf(name).toLowerCase(Locale.ROOT);
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (m == null && ext.equals("mkv")) m = "video/x-matroska";
        return m == null ? "application/octet-stream" : m;
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        }
    }

    /** {set, unlocked, wait, count} */
    static JSONObject state(Context c) {
        try {
            return new JSONObject().put("set", isSetUp(c)).put("unlocked", isUnlocked()).put("wait", waitSeconds(c))
                    .put("count", index == null ? 0 : index.length());
        } catch (Exception e) { return new JSONObject(); }
    }

    static boolean isVault(Uri u) { return u != null && "vault".equals(u.getScheme()); }
}
