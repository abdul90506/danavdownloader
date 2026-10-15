package com.dilhan.vidra;

import android.content.Context;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.PresetReverb;

import androidx.media3.common.AuxEffectInfo;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Music sound: the effect presets (Slowed + Reverb, Bass boost, Nightcore, Reverb, Vocal boost, Lo-fi) and the
 * realtime mixer — Vocal, Guitar, Piano, Bass and Drums, each one louder or quieter while the song plays.
 *
 * Both live on the phone's own equalizer for the player's audio session, so every change is instant and nothing is
 * re-encoded. The presets shape the curve, the mixer adds to it (or takes from it).
 * All calls happen on the main thread.
 */
final class SoundFx {
    static final String[] IDS = {"off", "slowed", "bass", "nightcore", "reverb", "vocal", "lofi"};
    /** The mixer faders, in the order the UI shows them. */
    static final String[] MIX = {"vocal", "guitar", "piano", "bass", "drums"};
    private static final float MAX_MB = 900f;              // a fader at +100 % moves its own bands this far (mB)

    static String cur = "off";
    private static final int[] mix = new int[MIX.length];
    static ExoPlayer player;
    private static PresetReverb reverb;
    private static BassBoost bass;
    private static Equalizer eq;
    private static boolean loaded;
    private static String warn = "";

    static float speed(String id) { return "slowed".equals(id) ? 0.85f : "nightcore".equals(id) ? 1.22f : "lofi".equals(id) ? 0.93f : 1f; }
    static float pitch() { return speed(cur); }

    static void load(Context c) {
        if (loaded) return; loaded = true;
        android.content.SharedPreferences p = c.getSharedPreferences("music", 0);
        cur = p.getString("fx", "off");
        for (int i = 0; i < MIX.length; i++) mix[i] = p.getInt("mix_" + MIX[i], 0);
    }

    /** Called by MusicService when its player exists. */
    static void attach(Context c, ExoPlayer p) {
        load(c);
        player = p;
        applyNow(c);
    }

    static void detach() { release(); player = null; }

    private static void release() {
        try { if (player != null) player.setAuxEffectInfo(new AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f)); } catch (Exception ignored) {}
        if (reverb != null) { try { reverb.release(); } catch (Exception ignored) {} reverb = null; }
        if (bass != null) { try { bass.release(); } catch (Exception ignored) {} bass = null; }
        if (eq != null) { try { eq.release(); } catch (Exception ignored) {} eq = null; }
    }

    // ------------------------------------------------------------------ mixer (realtime)

    static JSONObject mixJson() {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i < MIX.length; i++) o.put(MIX[i], mix[i]); } catch (Exception ignored) {}
        return o;
    }

    static int mixAt(String name) {
        int i = index(name);
        return i < 0 ? 0 : mix[i];
    }

    private static int index(String name) {
        for (int i = 0; i < MIX.length; i++) if (MIX[i].equals(name)) return i;
        return -1;
    }

    /** Moves one fader (-100 … +100). Applied at once: the equalizer is already on the live audio session. */
    static String setMix(Context c, String name, int v) {
        load(c);
        int i = index(name);
        if (i < 0) return "no";
        mix[i] = Math.max(-100, Math.min(100, v));
        c.getSharedPreferences("music", 0).edit().putInt("mix_" + name, mix[i]).apply();
        applyBands();
        return "";
    }

    /** Every fader back to 0 (the song exactly as it was recorded). */
    static void resetMix(Context c) {
        load(c);
        for (int i = 0; i < MIX.length; i++) mix[i] = 0;
        android.content.SharedPreferences.Editor e = c.getSharedPreferences("music", 0).edit();
        for (String n : MIX) e.putInt("mix_" + n, 0);
        e.apply();
        applyBands();
    }

    static boolean mixTouched() {
        for (int v : mix) if (v != 0) return true;
        return false;
    }

    // ------------------------------------------------------------------ effects

    private static void applyNow(Context c) {
        load(c);
        ExoPlayer p = player;
        if (p == null) return;
        float sp = speed(cur);
        p.setPlaybackParameters(new PlaybackParameters(sp, sp));
        applyBands();
    }

    /** Switches the effect. Returns "" or a short message when part of it isn't supported on this phone. */
    static String apply(Context c, String id) {
        load(c);
        boolean known = false;
        for (String s : IDS) if (s.equals(id)) known = true;
        if (!known) id = "off";
        cur = id;
        c.getSharedPreferences("music", 0).edit().putString("fx", id).apply();
        ExoPlayer p = player;
        if (p == null) return "";
        warn = "";
        if (reverb != null) { try { reverb.release(); } catch (Exception ignored) {} reverb = null; }
        if (bass != null) { try { bass.release(); } catch (Exception ignored) {} bass = null; }
        try { p.setAuxEffectInfo(new AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f)); } catch (Exception ignored) {}
        float sp = speed(id);
        p.setPlaybackParameters(new PlaybackParameters(sp, sp));
        int session = p.getAudioSessionId();
        if (!"off".equals(id)) {
            short preset = "slowed".equals(id) ? PresetReverb.PRESET_LARGEHALL : "reverb".equals(id) ? PresetReverb.PRESET_MEDIUMHALL
                    : "lofi".equals(id) ? PresetReverb.PRESET_SMALLROOM : -1;
            if (preset >= 0) {
                try {
                    reverb = new PresetReverb(1, 0);
                    reverb.setPreset(preset);
                    reverb.setEnabled(true);
                    p.setAuxEffectInfo(new AuxEffectInfo(reverb.getId(), "lofi".equals(id) ? 0.5f : 1f));
                } catch (Throwable e) { reverb = null; warn = "Reverb isn't supported on this phone"; }
            }
            int boost = "bass".equals(id) ? 1000 : "slowed".equals(id) ? 450 : "lofi".equals(id) ? 250 : 0;
            if (boost > 0 && session != 0) {
                try {
                    bass = new BassBoost(1, session);
                    if (bass.getStrengthSupported()) bass.setStrength((short) boost);
                    bass.setEnabled(true);
                } catch (Throwable e) { bass = null; }
            }
        }
        applyBands();
        return warn;
    }

    /**
     * Writes the whole curve (preset + mixer) into the phone's equalizer. Called on every fader move, so it is
     * kept small: five bands at most, one call each.
     */
    private static void applyBands() {
        ExoPlayer p = player;
        if (p == null) return;
        int session = p.getAudioSessionId();
        boolean wantEq = session != 0 && (!"off".equals(cur) || mixTouched());
        if (!wantEq) {
            if (eq != null) { try { eq.setEnabled(false); } catch (Exception ignored) {} }
            return;
        }
        if (eq == null) {
            try {
                eq = new Equalizer(1, session);
            } catch (Throwable e) {
                eq = null;
                if (warn.isEmpty() && mixTouched()) warn = "The equalizer isn't available on this phone, so the mixer stays off";
                return;
            }
        }
        try {
            short[] range = eq.getBandLevelRange();
            for (short b = 0; b < eq.getNumberOfBands(); b++) {
                float hz = eq.getCenterFreq(b) / 1000f;
                int mb = Math.round(presetMb(hz) + mixMb(hz));
                eq.setBandLevel(b, (short) Math.max(range[0], Math.min(range[1], mb)));
            }
            eq.setEnabled(true);
        } catch (Throwable e) {
            if (warn.isEmpty() && mixTouched()) warn = "The mixer couldn't be applied on this phone";
        }
    }

    /** Curve of the chosen effect preset, in millibels. */
    private static int presetMb(float hz) {
        switch (cur) {
            case "bass": return hz < 90 ? 900 : hz < 300 ? 600 : hz < 1000 ? 0 : hz < 4000 ? -100 : 100;
            case "vocal": return hz < 150 ? -400 : hz < 500 ? -100 : hz < 4500 ? 500 : hz < 9000 ? 250 : 0;
            case "lofi": return hz < 90 ? -300 : hz < 400 ? 200 : hz < 2500 ? 0 : hz < 6000 ? -600 : -1200;
            case "slowed": return hz < 150 ? 300 : hz < 600 ? 150 : hz < 5000 ? 0 : -300;
            default: return 0;
        }
    }

    /**
     * The mixer's contribution: every fader is a small frequency shape of its own — where that instrument or voice
     * actually lives — multiplied by how far the fader was moved. Positive faders lift their own band, negative
     * ones take it away, so "Vocals -60 %" really quietens the voice and leaves the rest alone.
     */
    private static float mixMb(float hz) {
        float out = 0f;
        for (int i = 0; i < MIX.length; i++) if (mix[i] != 0) out += mix[i] / 100f * MAX_MB * weight(MIX[i], hz);
        return out;
    }

    private static float weight(String name, float hz) {
        boolean low = hz < 90, lowMid = hz >= 90 && hz < 300, mid = hz >= 300 && hz < 1000, hi = hz >= 1000 && hz < 4000;
        switch (name) {
            case "bass":   return low ? 1.0f : lowMid ? 0.6f : mid ? 0.15f : hi ? 0f : -0.15f;
            case "drums":  return low ? 0.55f : lowMid ? 0.6f : mid ? 0.3f : hi ? 0.3f : 0.55f;      // kick, snare, cymbals
            case "guitar": return low ? 0f : lowMid ? 0.35f : mid ? 0.75f : hi ? 0.6f : 0.3f;
            case "piano":  return low ? 0.2f : lowMid ? 0.35f : mid ? 0.6f : hi ? 0.7f : 0.45f;
            case "vocal":  return low ? -0.25f : lowMid ? -0.1f : mid ? 0.35f : hi ? 1.0f : 0.55f;
            default: return 0f;
        }
    }
}
