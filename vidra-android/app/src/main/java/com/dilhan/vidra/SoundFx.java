package com.dilhan.vidra;

import android.content.Context;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.PresetReverb;

import androidx.media3.common.AuxEffectInfo;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Music sound effects: Slowed + Reverb, Bass boost, Nightcore, Reverb, Vocal boost, Lo-fi.
 * Uses the phone's own audio effects (reverb as an aux send, bass boost and equalizer on the player's audio session)
 * plus speed + pitch from the player, so nothing is re-encoded and it works on any song.
 * All calls must happen on the main thread.
 */
final class SoundFx {
    static final String[] IDS = {"off", "slowed", "bass", "nightcore", "reverb", "vocal", "lofi"};
    static String cur = "off";
    static ExoPlayer player;
    private static PresetReverb reverb;
    private static BassBoost bass;
    private static Equalizer eq;
    private static boolean loaded;

    static float speed(String id) { return "slowed".equals(id) ? 0.85f : "nightcore".equals(id) ? 1.22f : "lofi".equals(id) ? 0.93f : 1f; }
    static float pitch() { return speed(cur); }

    static void load(Context c) {
        if (loaded) return; loaded = true;
        cur = c.getSharedPreferences("music", 0).getString("fx", "off");
    }

    /** Called by MusicService when its player exists. */
    static void attach(Context c, ExoPlayer p) {
        load(c);
        player = p;
        if (!"off".equals(cur)) apply(c, cur);
    }

    static void detach() { release(); player = null; }

    private static void release() {
        try { if (player != null) player.setAuxEffectInfo(new AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f)); } catch (Exception ignored) {}
        if (reverb != null) { try { reverb.release(); } catch (Exception ignored) {} reverb = null; }
        if (bass != null) { try { bass.release(); } catch (Exception ignored) {} bass = null; }
        if (eq != null) { try { eq.release(); } catch (Exception ignored) {} eq = null; }
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
        release();
        String warn = "";
        float sp = speed(id);
        p.setPlaybackParameters(new PlaybackParameters(sp, sp));
        if ("off".equals(id)) return "";
        int session = p.getAudioSessionId();

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
        if (session != 0 && ("bass".equals(id) || "vocal".equals(id) || "lofi".equals(id) || "slowed".equals(id))) {
            try {
                eq = new Equalizer(1, session);
                short[] range = eq.getBandLevelRange();
                for (short b = 0; b < eq.getNumberOfBands(); b++) {
                    float hz = eq.getCenterFreq(b) / 1000f;
                    int mb = gain(id, hz);
                    eq.setBandLevel(b, (short) Math.max(range[0], Math.min(range[1], mb)));
                }
                eq.setEnabled(true);
            } catch (Throwable e) { eq = null; if (warn.isEmpty() && !"slowed".equals(id)) warn = "The equalizer isn't available on this phone"; }
        }
        return warn;
    }

    /** Equalizer curve in millibels for a band centred at hz. */
    private static int gain(String id, float hz) {
        switch (id) {
            case "bass": return hz < 90 ? 900 : hz < 300 ? 600 : hz < 1000 ? 0 : hz < 4000 ? -100 : 100;
            case "vocal": return hz < 150 ? -400 : hz < 500 ? -100 : hz < 4500 ? 500 : hz < 9000 ? 250 : 0;
            case "lofi": return hz < 90 ? -300 : hz < 400 ? 200 : hz < 2500 ? 0 : hz < 6000 ? -600 : -1200;
            case "slowed": return hz < 150 ? 300 : hz < 600 ? 150 : hz < 5000 ? 0 : -300;
            default: return 0;
        }
    }
}
