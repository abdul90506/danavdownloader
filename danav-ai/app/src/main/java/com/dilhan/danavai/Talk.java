package com.dilhan.danavai;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;

/**
 * Short spoken "I'm on it" lines while a task is running.
 *
 * The model always says one short line as it starts a task, but screen work can take ten or twenty
 * seconds, and silence for that long made it look as if the call had died. So the phone speaks a very
 * short, honest filler of its own while the work is still going on (never a result, never a claim).
 *
 * The voice is the phone's own text-to-speech, played on the call's audio path so it is heard in the
 * same place as Danav's voice. If the phone has no usable speech engine, nothing happens — the call
 * simply stays as it was.
 */
final class Talk {
    private static TextToSpeech tts;
    private static boolean starting, ready, dead;
    private static final Queue<String> pending = new ArrayDeque<>();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private Talk() {}

    /** Warms the engine up (call it when a call starts, so the first filler is instant). */
    static void prepare(Context c) {
        if (ready || starting || dead) return;
        starting = true;
        try {
            tts = new TextToSpeech(c.getApplicationContext(), status -> {
                starting = false;
                if (status != TextToSpeech.SUCCESS || tts == null) { dead = true; return; }
                try {
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                    Locale l = Locale.getDefault();
                    int r = tts.isLanguageAvailable(l);
                    tts.setLanguage(r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED ? Locale.US : l);
                } catch (Throwable ignored) {}
                ready = true;
                String q;
                while ((q = pending.poll()) != null) try { tts.speak(q, TextToSpeech.QUEUE_ADD, null, "danav-fill"); } catch (Throwable ignored) {}
            });
        } catch (Throwable t) { starting = false; dead = true; }
    }

    /** Says one short line (does nothing if there is no speech engine). */
    static void say(Context c, String line) {
        if (dead || line == null || line.isEmpty()) return;
        if (!ready) {
            pending.add(line);
            if (pending.size() > 3) pending.poll();
            prepare(c);
            return;
        }
        try { tts.speak(line, TextToSpeech.QUEUE_ADD, null, "danav-fill"); } catch (Throwable ignored) {}
    }

    /** Stops anything it is saying right now (used when a call ends or Danav himself starts talking). */
    static void hush() {
        try { if (ready && tts != null) tts.stop(); } catch (Throwable ignored) {}
        pending.clear();
    }

    static void shut() {
        hush();
        try { if (tts != null) { tts.shutdown(); } } catch (Throwable ignored) {}
        tts = null; ready = false; starting = false;
        ui.removeCallbacksAndMessages(null);
    }
}
