package com.dilhan.vidra;

import android.content.Context;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;

/**
 * The real spectrum: Android's own Visualizer reads the music the player is producing (a proper FFT of the audio
 * output of this app's session) and Danav draws those numbers. Nothing is invented, nothing is sampled from the
 * microphone — it is the analyser Android gives the audio session, so what you see is what you hear.
 *
 * Android needs the microphone permission for an audio analyser, so the spectrum asks for it the first time it is
 * switched on. The permission is used for the FFT only; Danav never records anything.
 */
final class Spectrum {
    private static final int BANDS = 48;
    private static final float LOW_HZ = 45f, HIGH_HZ = 16000f;
    private static final long SESSION_CHECK_MS = 1500;

    private static Context app;
    private static ExoPlayer player;
    private static Visualizer vis;
    private static int visSession;
    private static boolean on;
    private static final float[] smooth = new float[BANDS];
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static long lastEmit;

    private Spectrum() {}

    static boolean enabled(Context c) { return c.getSharedPreferences("music", 0).getBoolean("spec", false); }
    static boolean running() { return on && vis != null; }

    private static boolean hasMic(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Called by MusicService as soon as its player exists. */
    static void attach(Context c, ExoPlayer p) {
        app = c.getApplicationContext();
        player = p;
        if (enabled(app)) start();
    }

    static void detach() { stop(); app = null; player = null; }

    /** on / off. Returns "on", "mic" (Android needs the microphone permission) or "no" (not supported here). */
    static String enable(Context c, boolean want) {
        app = c.getApplicationContext();
        c.getSharedPreferences("music", 0).edit().putBoolean("spec", want).apply();
        if (!want) { stop(); return "off"; }
        if (!hasMic(c)) { stop(); return "mic"; }
        return start() ? "on" : "no";
    }

    private static final Runnable sessionWatch = new Runnable() {
        @Override public void run() {
            if (!on || player == null) return;
            int s = player.getAudioSessionId();
            if (s != 0 && s != visSession) startVis(s);          // the player opened a new audio session
            h.postDelayed(this, SESSION_CHECK_MS);
        }
    };

    private static boolean start() {
        ExoPlayer p = player;
        if (app == null || p == null) { on = false; return false; }
        on = true;
        int s = p.getAudioSessionId();
        if (s != 0) startVis(s);
        h.removeCallbacks(sessionWatch);
        h.postDelayed(sessionWatch, SESSION_CHECK_MS);
        return true;
    }

    private static void stop() {
        on = false;
        h.removeCallbacks(sessionWatch);
        stopVis();
        for (int i = 0; i < BANDS; i++) smooth[i] = 0f;
    }

    private static void stopVis() {
        Visualizer v = vis;
        vis = null; visSession = 0;
        if (v != null) {
            try { v.setEnabled(false); } catch (Exception ignored) {}
            try { v.release(); } catch (Exception ignored) {}
        }
    }

    private static void startVis(int session) {
        stopVis();
        try {
            Visualizer v = new Visualizer(session);
            int[] range = Visualizer.getCaptureSizeRange();
            int size = Math.min(1024, range[1]);
            if (size < range[0]) size = range[0];
            v.setCaptureSize(size);
            // ~30 frames a second is plenty: the bars glide between them, and it stays cheap
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {}
                @Override public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) { push(fft, samplingRate); }
            }, 33000, false, true);
            v.setEnabled(true);
            vis = v; visSession = session;
        } catch (Throwable e) {
            vis = null; visSession = 0;
            com.dilhan.vidra.MusicHub.spectrumFailed(e.getMessage() == null ? "unsupported" : e.getMessage());
        }
    }

    /** One FFT frame → 48 log-spaced bars (0…1), smoothed so they move like sound and not like noise. */
    private static void push(byte[] fft, int rate) {
        if (fft == null || fft.length < 4 || rate <= 0) return;
        int n = fft.length;
        float nyq = rate / 2f;
        JSONArray out = new JSONArray();
        float peak = 0f;
        float[] raw = new float[BANDS];
        for (int b = 0; b < BANDS; b++) {
            float lo = hz(b), hi = hz(b + 1);
            int k0 = Math.max(1, (int) Math.floor(lo / nyq * n / 2f));
            int k1 = Math.min(n / 2 - 1, Math.max(k0, (int) Math.ceil(hi / nyq * n / 2f)));
            float best = 0f;
            for (int k = k0; k <= k1; k++) {
                float re = fft[2 * k], im = fft[2 * k + 1];
                float mag = (float) Math.sqrt(re * re + im * im);
                if (mag > best) best = mag;
            }
            // magnitude → 0…1 (a 1024-point FFT of 16-bit audio lands around 0…9000; log scale reads like hearing)
            float v = (float) (Math.log10(1f + best) / Math.log10(6000f));
            raw[b] = Math.max(0f, Math.min(1f, v));
        }
        for (int b = 0; b < BANDS; b++) {
            // rise fast, fall slowly: the classic analyser feel
            smooth[b] = raw[b] > smooth[b] ? raw[b] : smooth[b] * 0.75f + raw[b] * 0.25f;
            if (smooth[b] > peak) peak = smooth[b];
            try { out.put(Math.round(smooth[b] * 1000f) / 1000f); } catch (Exception ignored) {}
        }
        long now = System.currentTimeMillis();
        if (now - lastEmit < 28) return;                        // never more than ~35 pushes a second
        lastEmit = now;
        com.dilhan.vidra.MusicHub.spectrum(out, Math.round(peak * 1000f) / 1000f);
    }

    /** The band edges in Hz: 45 Hz … 16 kHz, spaced like the octaves the ear hears. */
    private static float hz(int band) {
        return (float) (LOW_HZ * Math.pow(HIGH_HZ / LOW_HZ, band / (double) BANDS));
    }
}
