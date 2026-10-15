package com.dilhan.danavai;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Danav AI voice call: microphone → live model → speaker, all on the native side (keys never reach the page).
 * If a key fails to connect, the next one is tried at once; a dropped call resumes on the next key
 * with the session handle, so the conversation continues.
 */
final class LiveCall {
    static final String MODEL = "gemini-3.8-live";
    private static final String WS = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
    private static final int IN_RATE = 16000, OUT_RATE = 24000;
    static final String[] VOICES = {"Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe", "Enceladus", "Iapetus",
            "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux", "Pulcherrima",
            "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"};

    private static LiveCall cur;
    private static int stickyKey;
    // .dns(Net.dns()): IPv4 addresses first, so a phone network that mis-locates IPv6 can't turn the
    // whole call into "couldn't connect" (the service refuses those requests as an unsupported location).
    private static final OkHttpClient http = new OkHttpClient.Builder().dns(Net.dns()).pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).connectTimeout(10, TimeUnit.SECONDS).build();

    private final Context ctx;
    private final Bridge.Host host;
    private final String voice;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile WebSocket ws;
    private volatile boolean ready, ended, muted, speaker = true;
    private volatile String handle;
    private int keyIdx, tried, gen, mIdx, mTries;
    private boolean rich = true;                       // speech tuning on; turned off automatically if the model rejects it
    private long failSince;                            // first failed connection of the current outage
    private AudioRecord rec;
    private AudioTrack track;
    private boolean toolsOn = true; // cleared if the service refuses the tool list, so the call still works
    private boolean bridge;        // Danav is talking on the user's call to somebody else
    private boolean micReleased;   // that call owns the microphone right now
    private volatile boolean micForBridge;   // capture is on the plain MIC source because Danav is on somebody's call
    private volatile boolean bridgeDeaf;     // the phone is not giving Danav the call audio
    private volatile long ttsUntil;          // the filler voice is speaking until then (mic stays quiet for the model)
    private String bridgeName;               // who Danav is talking to on that call
    private long bridgeSilentSince;          // since when nothing but silence has come in on the bridge
    private Thread micThread, playThread;
    private final LinkedBlockingQueue<byte[]> playQ = new LinkedBlockingQueue<>();
    private final float[] micBands = new float[16], aiBands = new float[16];
    private AudioManager am;
    private int oldMode;
    private volatile boolean speaking;
    private Boolean speakerPref;                       // null = automatic (headset when connected, otherwise loudspeaker)
    private boolean scoOn;
    private android.media.AudioDeviceCallback devCb;
    // what has been handed to the AudioTrack, so the AI spectrum follows the sound that is actually playing
    private final short[] ring = new short[OUT_RATE * 8];
    private long written;
    private volatile boolean flushReq;
    private final short[] win = new short[512];

    private final boolean wake;                        // started by "Hey Danav" (app may be closed)
    private byte[] preRoll;                            // the words that woke us, replayed to the model first
    private static final long WAKE_IDLE_MS = 10 * 60_000L;   // only a 10-minute total silence (they walked away) closes it
    private volatile long lastAct = SystemClock.elapsedRealtime();
    private volatile long lastServer = SystemClock.elapsedRealtime();   // last message of any kind from the service
    private volatile long lastUtter;                                    // when the user's voice last went out
    private volatile long lastSpoke;                                    // when Danav's own voice last stopped
    private volatile boolean waiting;                                   // the user spoke; the service owes us a reply
    private int resumes;                                                // sessions put back up during this call
    private long lastResumeAt;                                          // when the last one happened (so a healthy gap resets the count)
    private boolean hadTurn;                                            // the model has answered at least once (continuity)
    private int micFails;                                               // recorder rebuild attempts that failed in a row
    private final java.util.Map<String, Pend> toolPending = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean endAfterTurn;
    private static final java.util.concurrent.ExecutorService tools = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Object busyt = new Object();
    private int busyTools;                       // how many steps are running right now
    private final Runnable busyTick = new Runnable() {     // a short spoken filler while the work goes on
        @Override public void run() {
            if (ended) return;
            int running;
            synchronized (busyt) { running = busyTools; }
            if (running <= 0) return;
            long nowMs = SystemClock.elapsedRealtime();
            if (!bridge && !speaking && !muted && nowMs - lastUtter > 2500) {
                String line = busytick > 1 ? "Still working on it." : "One moment.";
                busytick++;
                ttsUntil = SystemClock.elapsedRealtime() + 2400;      // the filler is heard through the same microphone
                Talk.say(ctx, line);
            }
            ui.postDelayed(this, 6000);
        }
    };
    private int busytick;

    private LiveCall(Context c, Bridge.Host h, String voice, boolean wake) { this.ctx = c.getApplicationContext(); this.host = h; this.voice = voice; this.wake = wake; }

    /** Events go to whichever Danav screen is open right now (none while the app is closed). */
    static final Bridge.Host RELAY = new Bridge.Host() {
        @Override public android.app.Activity activity() { return MainActivity.inst; }
        @Override public void send(String type, String json) { MainActivity m = MainActivity.inst; if (m != null) m.send(type, json); }
        @Override public void resolve(String reqId, String json) { MainActivity m = MainActivity.inst; if (m != null) m.resolve(reqId, json); }
        @Override public void onPageReady() {}
    };

    // ------------------------------------------------------------------ public

    static synchronized void start(Context c, Bridge.Host h, String voice) {
        if (cur != null) cur.end(false);
        boolean ok = false;
        for (String v : VOICES) if (v.equals(voice)) ok = true;
        if (ok) c.getSharedPreferences("danav", Context.MODE_PRIVATE).edit().putString("voice", voice).apply();
        cur = new LiveCall(c, RELAY, ok ? voice : "Zephyr", false);
        cur.begin();
    }

    /** "Hey Danav": a call without the app on screen, shown as the floating bubble. preRoll = the last seconds of mic audio. */
    static synchronized boolean startWake(Context c, byte[] preRoll) {
        if (cur != null && !cur.ended) return false;
        String v = c.getSharedPreferences("danav", Context.MODE_PRIVATE).getString("voice", "Zephyr");
        cur = new LiveCall(c, RELAY, v, true);
        cur.preRoll = preRoll;
        cur.begin();
        RELAY.send("callExternal", "{}");
        new Handler(Looper.getMainLooper()).post(() -> { MainActivity m = MainActivity.inst; if (m == null || !m.isVisible()) CallBubble.show(c); });
        return true;
    }
    static boolean isWake() { LiveCall l = cur; return l != null && !l.ended && l.wake; }

    static synchronized void stop() { if (cur != null) { cur.end(true); cur = null; } }
    static void mute(boolean on) { LiveCall l = cur; if (l != null) { l.muted = on; try { l.send(new JSONObject().put("t", "mute").put("on", on)); } catch (Exception ignored) {} if (!l.wake) CallService.refresh(l.ctx); } }
    static boolean muted() { LiveCall l = cur; return l != null && l.muted; }
    static void speaker(boolean on) { LiveCall l = cur; if (l != null) { l.speakerPref = on; l.route(); } }

    // ------------------------------------------------------------------ talking on someone else's call

    /** Danav stays on the line while the user's call to somebody else is up (WhatsApp, or a normal call). */
    static void bridge(String who, String task) { LiveCall l = cur; if (l != null) l.enterBridge(who, task); }
    static boolean bridging() { LiveCall l = cur; return l != null && l.bridge; }

    /** That call is over: take the microphone back and listen to the user again. */
    static void endBridge() { LiveCall l = cur; if (l != null) l.leaveBridge(); }

    /** Types something for Danav to say out loud on that call — used from the Danav chat while bridging. */
    static boolean say(String text) {
        LiveCall l = cur;
        if (l == null || text == null || text.trim().isEmpty()) return false;
        l.textTurn("Say this out loud on the call right now, same meaning and same language, nothing else: \"" + text.trim() + "\"", true);
        l.lastAct = SystemClock.elapsedRealtime();
        return true;
    }

    /** Everything the model needs to know to hold up its half of the conversation by itself. */
    private void enterBridge(String who, String task) {
        if (ended) return;
        bridge = true;
        bridgeDeaf = false;
        bridgeSilentSince = 0;
        speakerPref = true;
        route();                                       // loudspeaker: the other person's voice must reach the microphone
        try {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            if (max > 0) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, max, 0);       // as loud as the phone goes
        } catch (Throwable ignored) {}
        // The microphone is NOT given up: Danav has to hear the other person to hold a conversation.
        // Their voice comes out of the loudspeaker and into the microphone, so a second capture is opened
        // on a non-private source (MIC) — with the accessibility service running, Android lets an app keep
        // capturing while a call is on. See switchMic() and the silence check in the microphone loop.
        switchMic(true);
        String name = who == null || who.isEmpty() ? "them" : who;
        bridgeName = name;
        textTurn("We are now on a live call with " + name + ", placed for the user. "
                + (task == null || task.isEmpty() ? "" : "What the user wants you to talk about: " + task + ". ")
                + "Their voice comes out of the loudspeaker and you hear it a moment after they finish a sentence, so this is a real two-way conversation — "
                + "answer them out loud in short, natural sentences in the user's language, like a person on the phone. Ask one question at a time and let them answer. "
                + "Never repeat a sentence. When the user tells you what to say or ask, say it out loud at once, exactly that meaning, nothing more. "
                + "If the user asks you to talk on your own, keep it going with short lines about the topic. "
                + "The user is in the room too: if something you hear is an instruction for you (\"ise kehna…\", \"poocho…\"), do exactly that and nothing more; "
                + "if it is a person talking to you, answer them. If you hear nothing from them for a while, just wait quietly instead of talking to yourself.", false);
        state("bridge", "");
        ui.post(() -> CallService.refresh(ctx));
    }

    /** Back with the user: the microphone comes home and the talk goes on as before. */
    private void leaveBridge() {
        if (!bridge) return;
        bridge = false;
        bridgeDeaf = false;
        switchMic(false);                              // back to the normal microphone for talking with the user
        textTurn("That call is finished and you are back with the user — listen and answer normally again.", false);
        state("listening", "");
        lastAct = SystemClock.elapsedRealtime();
    }

    /**
     * Swaps the microphone for a different source without dropping the call.
     * While Danav is talking on somebody else's call, the call app owns the privacy-sensitive
     * VOICE_COMMUNICATION source; a plain MIC capture is what an accessibility service may keep
     * running during a call, so the far side can still be heard through the loudspeaker.
     */
    private synchronized void switchMic(boolean forBridge) {
        if (ended) return;
        micForBridge = forBridge;
        try { if (rec != null) { try { rec.stop(); } catch (Throwable ignored) {} rec.release(); } } catch (Throwable ignored) {}
        rec = null;
        if (!forBridge) { try { startAudio(); } catch (Exception ignored) {} return; }
        // rebuild with the bridge source and keep the microphone thread running
        if (openMic(forBridge)) return;
        // couldn't get the second capture: stay on the old one so at least the user is still heard
        micForBridge = false;
        try { openMic(false); } catch (Throwable ignored) {}
    }

    /** A text turn for the live model (instructions, or the exact words to say). */
    private void textTurn(String text, boolean complete) {
        try {
            if (ws == null || !ready) return;
            JSONObject t = new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", text)));
            ws.send(new JSONObject().put("clientContent", new JSONObject().put("turns", new JSONArray().put(t)).put("turnComplete", complete)).toString());
        } catch (Exception ignored) {}
    }
    static boolean active() { LiveCall l = cur; return l != null && !l.ended; }
    /** One camera / screen frame (base64 JPEG) for the live session — roughly one per second. */
    static void video(String b64) {
        LiveCall l = cur;
        if (l == null || l.ended || !l.ready || l.ws == null || b64 == null || b64.isEmpty()) return;
        l.ws.send("{\"realtimeInput\":{\"video\":{\"mimeType\":\"image/jpeg\",\"data\":\"" + b64 + "\"}}}");
    }
    static void emit(String t, boolean on) { LiveCall l = cur; if (l != null) try { l.send(new JSONObject().put("t", t).put("on", on)); } catch (Exception ignored) {} }
    static void toast(String msg) { LiveCall l = cur; if (l != null) try { l.send(new JSONObject().put("t", "toast").put("msg", msg)); } catch (Exception ignored) {} }
    /** Current spectrum (16 bands each) for the floating call bubble. */
    static void levels(float[] mic, float[] ai) {
        LiveCall l = cur;
        for (int i = 0; i < 16; i++) { mic[i] = l == null ? 0 : l.micBands[i]; ai[i] = l == null ? 0 : l.aiBands[i]; }
    }

    // ------------------------------------------------------------------ session

    private void begin() {
        List<String> keys = AiKeys.get(ctx);
        if (keys.isEmpty()) { state("error", "Danav AI isn't available in this copy of the app."); return; }
        am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        oldMode = am.getMode();
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        grabFocus();                                  // music / video step back so Danav can hear the user
        route();
        devCb = new android.media.AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] d) { if (isHeadsetChange(d)) { speakerPref = null; route(); } }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] d) { if (isHeadsetChange(d)) { speakerPref = null; route(); } }
        };
        try { am.registerAudioDeviceCallback(devCb, ui); } catch (Exception ignored) {}
        if (!wake) CallService.start(ctx);           // a wake call runs inside the Hey Danav service (already allowed to use the mic)
        keyIdx = stickyKey % keys.size();
        tried = 0;
        if (mIdx <= 0) mIdx = 0;
        Ai.warm(ctx);                                  // the real model list, fetched out of the way
        state("connecting", "");
        connect();
        Talk.prepare(ctx);                             // ready for the short "one moment" lines
        ui.post(levelTick);
        ui.removeCallbacks(watchdog);
        ui.postDelayed(watchdog, 3000);
    }

    private void connect() {
        List<String> keys = AiKeys.get(ctx);
        if (ended) return;
        final int my = ++gen;
        Request.Builder rb = new Request.Builder().url(WS)
                .header("x-goog-api-key", keys.get(keyIdx))
                .header("X-Android-Package", "com.dilhan.danavai");
        if (!AiKeys.certSha1.isEmpty()) rb.header("X-Android-Cert", AiKeys.certSha1);
        ready = false;
        ws = http.newWebSocket(rb.build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket w, Response r) {
                if (my != gen) { w.cancel(); return; }
                try { w.send(setup().toString()); } catch (Exception e) { w.cancel(); }
            }
            @Override public void onMessage(WebSocket w, String text) { if (my == gen) handle(text); }
            @Override public void onMessage(WebSocket w, ByteString bytes) { if (my == gen) handle(bytes.utf8()); }
            @Override public void onClosing(WebSocket w, int code, String reason) { w.close(1000, null); if (my == gen) dropped(code + " " + reason); }
            @Override public void onFailure(WebSocket w, Throwable t, Response r) { if (my == gen) dropped(t == null ? "" : String.valueOf(t.getMessage())); }
        });
    }

    private static final String PHONE_RULES = "\n\nYou can control this phone with your tools. When the user asks for something on the phone, do it right away with the tools "
            + "instead of explaining how: WhatsApp messages (just the contact name is enough — call whatsapp_message directly, don't ask for the number), calls, SMS, email, "
            + "YouTube, opening apps, settings, alarms, timers, volume, flashlight, maps, and anything else in any app using read_screen, tap, type_text, scroll and system_action. "
            + "Write messages exactly as the user wants them (same language; Roman Urdu stays Roman Urdu) and send them without asking again. "
            + "Don't ask for confirmation for simple, clear requests; only ask when a contact is ambiguous or the request is risky (deleting, paying, posting publicly). "
            + "For multi-step tasks in other apps: read_screen, act, check the new screen in the tool result, and continue until it is done. "
            + "Never invent a contact, a number or a chat: if a contact isn't found or two people share a name, ask one very short question and stop — do not guess. "
            + "If you did not clearly hear a name, a number or a message, ask once (\"kis ko?\" / \"kya likhun?\") instead of acting on a guess. "
            + "Before anything that leaves the phone (a message, a call, an email) make sure the tool result says it really went through; if it doesn't say so, tell the user plainly what happened. "
            + "For lists and pages use scroll; to move a whole page, a story or a carousel use swipe; if a tap lands on the wrong thing, read_screen again and retry once. "
            + "Never repeat a sentence you have already said on this call. "
            + "While you are doing something, keep the user posted with ONE short line as you start (\\\"dekhta hoon\\\", \\\"ruko, WhatsApp khol raha hoon\\\", \\\"check kar raha hoon\\\") — "
            + "screen work can take a few seconds and silence makes the user think the call died. Never say a result before the tool has given it to you. "
            + "Once a message is sent, a call is started, an app is opened, a song is played or a timer is set, say nothing further about it at all — "
            + "no \"message send kar diya\", no \"done\", no report. Answer only what the user actually asked, in one short sentence. Speak about a task only if it failed, if you need "
            + "a decision, or if the user asks about it. Report results honestly from the tool results; never claim something worked when the tool says it didn't. "
            + "Memory: silently call remember whenever the user tells you something durable about themselves (name, city, language, family, work, routine, likes, dislikes, how they want "
            + "answers), and use what you already remember from earlier talks without telling them you remember it. "
            + "When the user says bye / bas / band karo, say a very short goodbye and call end_call. Otherwise never end the call yourself — stay on the line and wait, even when the "
            + "user goes quiet for a long time.";
    private static final String WAKE_RULES = "\n\nIMPORTANT: the user woke you up on purpose by pressing the volume-up button three times — the Danav circle is on screen now. "
            + "If they start talking straight away, just do what they ask. If you hear nothing yet, greet them with one short word or line (\"Ji?\" / \"Haan, boliye\") and wait. "
            + "Never call dismiss — the user opened this themselves. "
            + "Stay on this call until the user says bye / bas / band karo (then a tiny goodbye and end_call), or until they press volume-up three times again. "
            + "Never end it yourself because of a silence or because a task finished.";
    /** Live models this account really has, best first (never a guessed name). */
    private String[] liveModels() {
        String[] m = null;
        try { m = Ai.models(ctx, true); } catch (Throwable ignored) {}
        return m == null || m.length == 0 ? new String[]{MODEL} : m;
    }

    private String curModel() {
        String[] m = liveModels();
        if (mIdx < 0 || mIdx >= m.length) mIdx = 0;
        return m[mIdx];
    }

    /** Setup failed for this model name → move to the next one and keep going. */
    private void nextModel() {
        mTries++;
        if (mTries > Math.max(2, liveModels().length) * 2) {   // every name tried twice over → give up honestly
            dropped("no usable live model");
            return;
        }
        mIdx++;
        tried = 0;
        failSince = 0;
        ui.postDelayed(() -> { if (!ended) connect(); }, 120);
    }

    private JSONObject setup() throws Exception {
        JSONObject s = new JSONObject()
                .put("model", "models/" + curModel())
                .put("generationConfig", new JSONObject()
                        .put("responseModalities", new JSONArray().put("AUDIO"))
                        .put("speechConfig", new JSONObject().put("voiceConfig", new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice)))))
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", Ai.liveIdentity(ctx) + PHONE_RULES + (wake ? WAKE_RULES : "") + Phone.contextLine()))))
                .put("tools", toolsOn ? Phone.tools(wake) : new JSONArray())
                .put("inputAudioTranscription", new JSONObject())
                .put("outputAudioTranscription", new JSONObject())
                .put("contextWindowCompression", new JSONObject().put("slidingWindow", new JSONObject()))
                .put("sessionResumption", handle == null ? new JSONObject() : new JSONObject().put("handle", handle));
        // The service's own activity detection is left at its defaults on purpose: it is tuned for the
        // model, and the app now sends a continuous stream (silence as silence) which is what it needs.
        // Interruptions are handled locally — the microphone is muted for the model while Danav speaks,
        // so room noise or his own voice can no longer cut him off mid-sentence.
        return new JSONObject().put("setup", s);
    }

    private void dropped(String why) {
        if (ended) return;
        ready = false;
        List<String> keys = AiKeys.get(ctx);
        tried++;
        if (failSince == 0) failSince = SystemClock.elapsedRealtime();
        long outage = SystemClock.elapsedRealtime() - failSince;
        if (outage > 150_000) {                        // a real, long outage (about 2.5 minutes) — only then give up
            state("error", "Couldn't reach Danav AI. Check your internet and try again.");
            end(true);
            return;
        }
        keyIdx = (keyIdx + 1) % keys.size();           // next key right away
        state(handle == null ? "connecting" : "reconnecting", "");
        long delay = tried > keys.size() ? Math.min(4000, 250L * (tried - keys.size())) : 0;
        ui.postDelayed(this::connect, delay);
    }

    private void handle(String text) {
        try {
            JSONObject m = new JSONObject(text);
            lastServer = SystemClock.elapsedRealtime();
            waiting = false;
            JSONObject err = m.optJSONObject("error");
            if (err != null) {
                String em = err.optString("message", "setup failed");
                int ec = err.optInt("code", 0);
                android.util.Log.w("DanavAI", "live setup/stream error: " + em);
                boolean modelProblem = !ready && (em.toLowerCase(java.util.Locale.ROOT).contains("not found")
                        || em.toLowerCase(java.util.Locale.ROOT).contains("does not exist")
                        || em.toLowerCase(java.util.Locale.ROOT).contains("not supported")
                        || em.toLowerCase(java.util.Locale.ROOT).contains("unsupported")
                        || em.toLowerCase(java.util.Locale.ROOT).contains("thinking level"));
                if (modelProblem && liveModels().length > 1) {   // dead name → next one, first try, before anything else
                    Ai.markDead(curModel());
                    nextModel();
                } else if (!ready && rich) {               // the model refused the speech tuning → plain setup again
                    rich = false;
                    ui.post(() -> { if (!ended) { tried = 0; connect(); } });
                } else if (!ready && toolsOn) {            // something in the tool list was refused → keep the call usable
                    toolsOn = false;
                    ui.post(() -> { if (!ended) { tried = 0; connect(); } });
                } else if (ready) {
                    // The session was live and the service has just refused something. Before, this message was
                    // swallowed and the call simply stopped answering. Now: a limit says so, anything else is
                    // repaired right away (the conversation goes on through the session handle).
                    String low = em.toLowerCase(java.util.Locale.ROOT);
                    android.util.Log.w("DanavAI", "live error while ready: " + em);
                    if (low.contains("quota") || low.contains("rate limit") || low.contains("resource_exhausted") || low.contains("billing")) {
                        state("error", "Danav AI has used up its limit for now. Try again in a little while.");
                        end(true);
                    } else if (low.contains("api key") || low.contains("permission") || low.contains("unauthenticated") || low.contains("credential")) {
                        state("error", "Danav AI isn't set up right in this copy of the app — it needs a fresh build.");
                        end(true);
                    } else if (low.contains("goaway") || low.contains("clos") || low.contains("expired") || low.contains("deadline")
                            || low.contains("unavailable") || low.contains("internal") || ec == 1011 || ec == 1013 || ec == 1007) {
                        resume();
                    } else {
                        resume();
                    }
                } else if (!ready) {
                    String low = em.toLowerCase(java.util.Locale.ROOT);
                    if (low.contains("quota") || low.contains("rate limit") || low.contains("resource_exhausted") || low.contains("billing"))
                        state("error", "Danav AI has used up its limit for now. Try again in a little while.");
                    else if (low.contains("api key") || low.contains("permission") || low.contains("unauthenticated") || low.contains("credential"))
                        state("error", "Danav AI isn't set up right in this copy of the app — it needs a fresh build.");
                    else if (low.contains("not found") || low.contains("does not exist") || low.contains("not supported")
                            || low.contains("unsupported") || low.contains("invalid argument") || low.contains("thinking level")) {
                        // that name isn't usable for this account → try the next model from the real list
                        Ai.markDead(curModel());
                        nextModel();
                    }
                    else dropped(em);
                }
                return;
            }
            if (m.has("setupComplete")) {
                ready = true; tried = 0; failSince = 0; mTries = 0; waiting = false; lastServer = SystemClock.elapsedRealtime();
                if (SystemClock.elapsedRealtime() - lastResumeAt > 60_000) resumes = 0;      // a minute of real talk resets it
                stickyKey = keyIdx;
                state("listening", "");
                if (hadTurn) textTurn("This is the same conversation continued after a short network break — never greet again and never repeat anything you already said.", false);
                byte[] pr = preRoll; preRoll = null;
                if (pr != null && ws != null) {                 // what woke us, sent first so the model hears "Hey Danav, …"
                    for (int off = 0; off < pr.length; off += 6400) {
                        String b64 = Base64.encodeToString(pr, off, Math.min(6400, pr.length - off), Base64.NO_WRAP);
                        ws.send("{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\"" + b64 + "\"}}}");
                    }
                }
                startAudio();
                return;
            }
            JSONObject sru = m.optJSONObject("sessionResumptionUpdate");
            if (sru != null && sru.optBoolean("resumable") && sru.has("newHandle")) handle = sru.optString("newHandle");
            JSONObject tc = m.optJSONObject("toolCall");
            if (tc != null) { lastAct = SystemClock.elapsedRealtime(); runTools(tc.optJSONArray("functionCalls")); return; }
            if (m.has("goAway")) { lastServer = SystemClock.elapsedRealtime(); ui.postDelayed(this::resume, 300); return; }   // server will close soon — move on early
            JSONObject sc = m.optJSONObject("serverContent");
            if (sc == null) return;
            if (sc.optBoolean("interrupted")) {
                playQ.clear(); flushReq = true;
                AudioTrack t = track;
                if (t != null) { try { t.pause(); t.flush(); t.play(); } catch (Exception ignored) {} }
                synchronized (ring) { written = 0; }
            }
            JSONObject turn = sc.optJSONObject("modelTurn");
            JSONArray parts = turn == null ? null : turn.optJSONArray("parts");
            if (parts != null) for (int i = 0; i < parts.length(); i++) {
                JSONObject inl = parts.getJSONObject(i).optJSONObject("inlineData");
                if (inl != null && inl.optString("mimeType").startsWith("audio/pcm")) {
                    byte[] pcm = Base64.decode(inl.optString("data"), Base64.DEFAULT);
                    playQ.offer(pcm);
                }
            }
            JSONObject ot = sc.optJSONObject("outputTranscription");
            if (ot != null && !ot.optString("text").isEmpty()) send(new JSONObject().put("t", "cap").put("who", "ai").put("text", ot.optString("text")));
            JSONObject it = sc.optJSONObject("inputTranscription");
            if (it != null && !it.optString("text").isEmpty()) send(new JSONObject().put("t", "cap").put("who", "you").put("text", it.optString("text")));
            // the words being heard right now, before the finished sentence — visible proof the call hears the user
            JSONObject ii = sc.optJSONObject("interimInputTranscription");
            if (ii != null && !ii.optString("text").isEmpty()) send(new JSONObject().put("t", "hear").put("text", ii.optString("text")));
            if (ot != null || it != null || parts != null) lastAct = SystemClock.elapsedRealtime();
            if (sc.optBoolean("turnComplete")) {
                hadTurn = true;
                send(new JSONObject().put("t", "turn"));
                if (endAfterTurn) ui.postDelayed(this::finishSoon, 300);
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ phone tools

    private void runTools(JSONArray calls) {
        if (calls == null) return;
        for (int i = 0; i < calls.length(); i++) {
            JSONObject fc = calls.optJSONObject(i);
            if (fc == null) continue;
            final String name = fc.optString("name"), id = fc.optString("id");
            final JSONObject args = fc.optJSONObject("args") == null ? new JSONObject() : fc.optJSONObject("args");
            String lab = Phone.label(name, args);
            if (!lab.isEmpty()) try { send(new JSONObject().put("t", "tool").put("label", lab)); } catch (Exception ignored) {}
            toolPending.put(id, new Pend(name, SystemClock.elapsedRealtime()));
            startBusy();
            tools.execute(() -> {
                if (ended) { endBusy(); return; }
                Phone.Out out;
                try { out = Phone.run(ctx, name, args); }
                catch (Throwable t) { out = new Phone.Out("That step failed on the phone: " + t.getClass().getSimpleName(), false); }
                endBusy();
                if (ended) return;
                boolean answeredEarly = toolPending.remove(id) == null;   // the watchdog already told the model not to wait
                if (answeredEarly) {
                    // the real result arrived late — hand it over as a note instead of a second tool response
                    try { send(new JSONObject().put("t", "tool").put("label", "")); } catch (Exception ignored) {}
                    textTurn("Result of the step you were told not to wait for: " + out.text, false);
                    return;
                }
                lastAct = SystemClock.elapsedRealtime();
                if (out.dismiss) { ui.post(() -> { WakeService.cooldown(ctx, 90_000); synchronized (LiveCall.class) { if (cur == this) cur = null; } end(true); }); return; }
                // eyes when the model asked for them, and always after a failed step (that is when it needs to look)
                if ((out.look || !out.ok) && !ScreenShare.active()) { String img = Phone.shotB64(); if (img != null) video(img); }
                if (id == null || id.isEmpty()) {
                    textTurn("The step \"" + name + "\" finished. Result: " + out.text, false);
                } else {
                    try {
                        String res = out.ok ? out.text
                                : "FAILED — " + out.text + " Say plainly that it did not work; never claim it did. Then try another way if you can.";
                        JSONObject resp = new JSONObject().put("toolResponse", new JSONObject().put("functionResponses", new JSONArray().put(
                                new JSONObject().put("id", id).put("name", name).put("response",
                                        new JSONObject().put("result", res).put("success", out.ok)))));
                        WebSocket w = ws;
                        if (w != null) w.send(resp.toString());
                    } catch (Exception ignored) {}
                }
                try { send(new JSONObject().put("t", "tool").put("label", "")); } catch (Exception ignored) {}
                if (out.endCall) { endAfterTurn = true; ui.postDelayed(this::finishSoon, 7000); }
            });
        }
    }

    /** A tool call the model is waiting for. */
    private static final class Pend { final String name; final long at; Pend(String n, long a) { name = n; at = a; } }

    /**
     * Keeps the call alive: a live session can go quiet for reasons only the service knows (a session that
     * expired, a half-open socket, an error message, a tool that never came back). Before this, any of those
     * left the call sitting in "listening" while the user spoke into nothing. Now the session is put back up
     * (with the conversation handle, so nothing is lost), and a stuck tool is answered honestly.
     */
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (ended) return;
            long now = SystemClock.elapsedRealtime();
            try {
                if (ready && waiting && lastUtter > 0 && now - lastUtter > 7000 && now - lastServer > 9000) {
                    android.util.Log.w("DanavAI", "live session went quiet — resuming");
                    waiting = false;
                    resume();
                }
                for (java.util.Map.Entry<String, Pend> e : toolPending.entrySet()) {
                    Pend pd = e.getValue();
                    if (pd == null || now - pd.at < 20_000) continue;
                    if (toolPending.remove(e.getKey()) == null) continue;      // somebody else answered it
                    try {
                        android.util.Log.w("DanavAI", "tool " + pd.name + " is slow — telling the model not to wait");
                        WebSocket w = ws;
                        if (w != null) w.send(new JSONObject().put("toolResponse", new JSONObject().put("functionResponses",
                                new JSONArray().put(new JSONObject().put("id", e.getKey()).put("name", pd.name)
                                        .put("response", new JSONObject().put("result", "still working on it — do not wait for this step"))))).toString());
                    } catch (Exception ignored) {}
                }
            } catch (Throwable ignored) {}
            ui.postDelayed(this, 2000);
        }
    };

    /** Puts a live session that stopped answering back up, keeping the same conversation. */
    private void resume() {
        if (ended) return;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { ui.post(this::resume); return; }
        lastResumeAt = SystemClock.elapsedRealtime();
        if (++resumes > 6) {
            state("error", "The call kept dropping. Please start it again.");
            end(true);
            return;
        }
        state(hadTurn ? "reconnecting" : "connecting", "");
        WebSocket w = ws;
        if (w != null) { try { w.close(1000, null); } catch (Throwable ignored) {} }
        ws = null;
        ready = false;
        waiting = false;
        playQ.clear();
        flushReq = true;
        tried = 0;
        List<String> keys = AiKeys.get(ctx);
        if (!keys.isEmpty()) keyIdx = (keyIdx + 1) % keys.size();     // a fresh key for the fresh socket
        ui.postDelayed(this::connect, 250);
    }

    /** One step started / finished: while any step runs, a short spoken filler keeps the user company. */
    private void startBusy() {
        synchronized (busyt) {
            if (busyTools++ == 0) { busytick = 1; ui.postDelayed(busyTick, 5000); }
        }
    }

    private void endBusy() {
        synchronized (busyt) {
            if (busyTools > 0 && --busyTools == 0) { ui.removeCallbacks(busyTick); Talk.hush(); }
        }
    }

    /** Ends after the goodbye has been played (or at once if nothing is playing). */
    private void finishSoon() {
        if (ended) return;
        if (speaking || !playQ.isEmpty()) { ui.postDelayed(this::finishSoon, 250); return; }
        synchronized (LiveCall.class) { if (cur == this) cur = null; }
        end(true);
    }

    // ------------------------------------------------------------------ audio

    @SuppressLint("MissingPermission")
    private synchronized void startAudio() {
        if (rec != null || ended || micReleased) return;
        if (!openMic(micForBridge)) { state("error", "The microphone is busy or not allowed."); end(true); return; }
        int sid = rec.getAudioSessionId();
        try { if (!micForBridge && AutomaticGainControl.isAvailable()) { AutomaticGainControl g = AutomaticGainControl.create(sid); if (g != null) g.setEnabled(true); } } catch (Throwable ignored) {}
        int minOut = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                new AudioFormat.Builder().setSampleRate(OUT_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                Math.max(minOut, OUT_RATE / 8 * 2), AudioTrack.MODE_STREAM, sid);
        track.play();
        rec.startRecording();
        micThread = new Thread(() -> {
            short[] buf = new short[IN_RATE / 50];            // 20 ms — smaller chunks reach the model sooner
            byte[] bytes = new byte[buf.length * 2];
            while (!ended) {
              try {
                final AudioRecord rd = rec;
                if (rd == null) { SystemClock.sleep(50); continue; }        // a phone call owns the microphone right now
                int n;
                try { n = rd.read(buf, 0, buf.length); }
                catch (Exception e) {
                    if (ended) break;
                    // never go deaf silently: rebuild the recorder (another app may have taken the microphone)
                    android.util.Log.w("DanavAI", "mic read failed: " + Net.describe(e));
                    if (!reopenMic(rd) && ++micFails > 25) {
                        state("error", "The microphone stopped working — the call can't hear you. Please start it again.");
                        end(true);
                        break;
                    }
                    SystemClock.sleep(60);
                    continue;
                }
                if (n <= 0) { if (ended) break; SystemClock.sleep(20); continue; }
                // ---------------------------------------------------------------------------------
                // A frame is ALWAYS sent, every 20 ms, for the whole call. Quiet audio goes out as real
                // digital silence instead of being skipped.
                // Why: the service's own activity detection measures silence in the audio it *receives*.
                // While quiet frames were dropped, it heard the start of a sentence but never the end, so
                // the turn never closed and the model answered nothing at all — every time.
                // (Verified against the live API with real speech: continuous frames → the user's words were
                // transcribed and answered; the same words with the quiet parts dropped → ACTIVITY_START
                // with no ACTIVITY_END, no transcription, no reply.)
                // Half-duplex: while Danav's voice is coming out of the speaker the microphone is muted
                // for the model, so he never hears himself and never cuts himself off mid-sentence.
                // ---------------------------------------------------------------------------------
                boolean quiet = muted || speaking || SystemClock.elapsedRealtime() - lastSpoke < 250 || SystemClock.elapsedRealtime() < ttsUntil;
                boolean voice = false;
                if (!quiet) {
                    double sum = 0;
                    int peak = 0;
                    for (int i = 0; i < n; i++) {
                        sum += (double) buf[i] * buf[i];
                        int v = buf[i] < 0 ? -buf[i] : buf[i];
                        if (v > peak) peak = v;
                    }
                    voice = Math.sqrt(sum / n) >= 110 || peak >= 1500;      // speech, not hiss or a fan
                }
                if (voice) {
                    for (int i = 0; i < n; i++) { bytes[i * 2] = (byte) buf[i]; bytes[i * 2 + 1] = (byte) (buf[i] >> 8); }
                    Spectrum.bands(buf, n, IN_RATE, micBands);
                    bridgeSilentSince = 0;
                    lastAct = SystemClock.elapsedRealtime();                // the user is talking → the call stays open
                    lastUtter = lastAct;
                    waiting = true;                                         // the service now owes us an answer
                } else {
                    // While Danav is talking on somebody else's call, pure digital silence means the phone is not
                    // letting this app hear the call at all (the capture was silenced by the system). Say so once,
                    // honestly, instead of quietly pretending to listen.
                    if (bridge && !muted && !speaking) {
                        long nowMs = SystemClock.elapsedRealtime();
                        boolean digitalSilence = true;
                        for (int i = 0; i < n && digitalSilence; i++) if (buf[i] != 0) digitalSilence = false;
                        if (digitalSilence && bridgeSilentSince == 0) bridgeSilentSince = nowMs;
                        if (bridgeSilentSince != 0 && nowMs - bridgeSilentSince > 3500 && !bridgeDeaf) {
                            bridgeDeaf = true;
                            state("bridge", "Danav can't hear the call — see the note on screen");
                            send(new JSONObject().put("t", "toast").put("msg", "Danav can't hear the call: turn on Danav AI in Accessibility, or put the call on speaker"));
                            textTurn("Important: you cannot hear " + (bridgeName == null ? "them" : bridgeName) + " right now — no sound is reaching you. "
                                    + "Tell the user in one short line that Danav AI must be enabled in Accessibility (and the call on speaker) to hear the other side, then keep saying "
                                    + "whatever the user asks you to say on the call. Do not stay silent and do not pretend to hear.", false);
                        }
                    } else if (!bridge) {
                        bridgeSilentSince = 0;
                    }
                    java.util.Arrays.fill(bytes, 0, n * 2, (byte) 0);
                    java.util.Arrays.fill(micBands, 0);
                }
                final WebSocket w = ws;
                if (!ready || w == null) continue;
                try {
                    String b64 = Base64.encodeToString(bytes, 0, n * 2, Base64.NO_WRAP);
                    // send() returns false when the socket is closed — that used to mean "silence forever"
                    if (!w.send("{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\"" + b64 + "\"}}}") && !ended) {
                        android.util.Log.w("DanavAI", "live socket is gone — putting the session back up");
                        waiting = false;
                        resume();
                    }
                } catch (Exception ignored) {}
              } catch (Throwable t) {                    // one bad frame must never kill the microphone thread
                android.util.Log.w("DanavAI", "mic loop: " + Net.describe(t));
                SystemClock.sleep(40);
              }
            }
        }, "danav-live-mic");
        micThread.start();
        playThread = new Thread(() -> {
            short[] sh = new short[OUT_RATE / 50];
            while (!ended) {
                try {
                    byte[] pcm = playQ.poll(200, TimeUnit.MILLISECONDS);
                    if (pcm == null) continue;
                    // 20 ms slices; every slice is kept in a ring so the spectrum can read what the speaker plays right now
                    for (int off = 0; off + 1 < pcm.length && !ended && !flushReq; off += sh.length * 2) {
                        AudioTrack tr = track;
                        if (tr == null) { fixTrack(); tr = track; if (tr == null) break; }
                        if (flushReq) { flushReq = false; synchronized (ring) { written = 0; } }
                        int len = Math.min(sh.length * 2, pcm.length - off) & ~1, ns = len / 2;
                        for (int i = 0; i < ns; i++) sh[i] = (short) ((pcm[off + i * 2] & 0xff) | (pcm[off + i * 2 + 1] << 8));
                        int w = tr.write(sh, 0, ns);
                        if (w <= 0) { fixTrack(); break; }               // the speaker object died → rebuild it, keep talking
                        synchronized (ring) {
                            for (int i = 0; i < w; i++) ring[(int) ((written + i) % ring.length)] = sh[i];
                            written += w;
                        }
                    }
                } catch (Throwable e) {
                    if (ended) break;
                    android.util.Log.w("DanavAI", "playback failed: " + Net.describe(e));
                    fixTrack();
                    SystemClock.sleep(80);
                }
            }
        }, "danav-live-play");
        playThread.start();
    }

    /** Builds a new microphone if the current one died (another app took it, or the audio session was rebuilt). */
    @SuppressLint("MissingPermission")
    private synchronized boolean reopenMic(AudioRecord old) {
        if (ended || micReleased) return false;
        if (rec != null && rec != old) { micFails = 0; return true; }     // somebody already replaced it
        try { if (old != null) old.release(); } catch (Throwable ignored) {}
        rec = null;
        return openMic(micForBridge);
    }

    /** Opens the microphone: plain MIC while Danav is on somebody else's call, VOICE_COMMUNICATION otherwise. */
    @SuppressLint("MissingPermission")
    private synchronized boolean openMic(boolean plain) {
        if (ended || micReleased) return false;
        try {
            int src = plain ? MediaRecorder.AudioSource.MIC : MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            int minIn = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord r = new AudioRecord(src, IN_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minIn, 6400));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) { try { r.release(); } catch (Throwable ignored) {} return false; }
            int sid = r.getAudioSessionId();
            // While bridging, no echo canceller: the other person's voice comes out of the loudspeaker and must
            // be heard, not cancelled (AEC would treat it as our own echo and delete it).
            if (!plain) {
                try { if (AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler e = AcousticEchoCanceler.create(sid); if (e != null) e.setEnabled(true); } } catch (Throwable ignored) {}
                try { if (NoiseSuppressor.isAvailable()) { NoiseSuppressor n = NoiseSuppressor.create(sid); if (n != null) n.setEnabled(true); } } catch (Throwable ignored) {}
            }
            r.startRecording();
            rec = r;
            micFails = 0;
            return true;
        } catch (Throwable t) { return false; }
    }

    /** Rebuilds the speaker object if it is dead, so a reply is never swallowed silently. */
    private synchronized void fixTrack() {
        if (ended) return;
        try {
            AudioTrack t = track;
            if (t != null && t.getState() == AudioTrack.STATE_INITIALIZED) return;
        } catch (Throwable ignored) {}
        try { if (track != null) { track.pause(); track.flush(); track.release(); } } catch (Throwable ignored) {}
        track = null;
        try {
            int minOut = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord r = rec;
            AudioTrack t = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    new AudioFormat.Builder().setSampleRate(OUT_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    Math.max(minOut, OUT_RATE / 8 * 2), AudioTrack.MODE_STREAM, r == null ? 0 : r.getAudioSessionId());
            if (t.getState() != AudioTrack.STATE_INITIALIZED) { try { t.release(); } catch (Throwable ignored) {} return; }
            t.play();
            track = t;
            route();
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ audio focus, ducking and the way back to the speaker

    private Object focusReq;
    private boolean focusHeld;
    private int savedMusicVol = -1;

    /** Asks for the audio briefly so anything playing (video, music) ducks instead of drowning the microphone. */
    private void grabFocus() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                AudioAttributes at = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
                android.media.AudioFocusRequest r = new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(at).setWillPauseWhenDucked(false).build();
                focusReq = r;
                focusHeld = am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            } else {
                focusHeld = am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
        } catch (Throwable ignored) {}
        try {                                            // and, if it is still playing, turn it right down
            if (savedMusicVol < 0 && am.isMusicActive()) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int soft = Math.max(1, Math.round(max * 0.16f));
                int was = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (was > soft) { savedMusicVol = was; am.setStreamVolume(AudioManager.STREAM_MUSIC, soft, 0); }
            }
        } catch (Throwable ignored) {}
    }

    private void dropFocus() {
        try { if (savedMusicVol >= 0) { am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVol, 0); savedMusicVol = -1; } } catch (Throwable ignored) {}
        try {
            if (Build.VERSION.SDK_INT >= 26) { if (focusReq instanceof android.media.AudioFocusRequest) am.abandonAudioFocusRequest((android.media.AudioFocusRequest) focusReq); }
            else if (focusHeld) am.abandonAudioFocus(null);
        } catch (Throwable ignored) {}
        focusHeld = false; focusReq = null;
    }

    private static final int[] HEADSETS = Build.VERSION.SDK_INT >= 31
            ? new int[]{26 /* BLE headset */, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADSET, 22 /* USB headset */, AudioDeviceInfo.TYPE_WIRED_HEADPHONES}
            : new int[]{AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_WIRED_HEADSET, 22, AudioDeviceInfo.TYPE_WIRED_HEADPHONES};

    private static boolean isHeadsetChange(AudioDeviceInfo[] list) {
        for (AudioDeviceInfo d : list) { if (!d.isSink()) continue; for (int t : HEADSETS) if (d.getType() == t) return true; }
        return false;
    }

    private AudioDeviceInfo headset() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                List<AudioDeviceInfo> avail = am.getAvailableCommunicationDevices();
                for (int t : HEADSETS) for (AudioDeviceInfo d : avail) if (d.getType() == t) return d;
            } else {
                AudioDeviceInfo[] outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                for (int t : HEADSETS) for (AudioDeviceInfo d : outs) if (d.getType() == t) return d;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Hands-free / headphones first, automatically; the speaker button forces the loudspeaker (or the earpiece when off). */
    private void route() {
        if (am == null || ended) return;
        AudioDeviceInfo head = headset();
        boolean loud = speakerPref != null ? speakerPref : head == null;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo target = null;
                int want = loud ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
                if (!loud && head != null) target = head;
                else for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) if (d.getType() == want) { target = d; break; }
                if (target != null) am.setCommunicationDevice(target);
            } else {
                boolean bt = head != null && head.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
                if (!loud && bt) { am.setSpeakerphoneOn(false); if (!scoOn) { am.startBluetoothSco(); am.setBluetoothScoOn(true); scoOn = true; } }
                else {
                    if (scoOn) { am.setBluetoothScoOn(false); am.stopBluetoothSco(); scoOn = false; }
                    am.setSpeakerphoneOn(loud);
                }
            }
        } catch (Exception ignored) {}
        speaker = loud;
        String out = loud ? "speaker" : head == null ? "earpiece" : (head.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || head.getType() == 26) ? "bluetooth" : "headset";
        try { send(new JSONObject().put("t", "route").put("out", out).put("headset", head != null)); } catch (Exception ignored) {}
    }

    private final Runnable levelTick = new Runnable() {
        @Override public void run() {
            if (ended) return;
            try {
                AudioTrack t = track;
                long pending = 0;
                if (t != null) {
                    long head = t.getPlaybackHeadPosition() & 0xffffffffL;
                    boolean got = false;
                    synchronized (ring) {
                        pending = written - head;
                        if (pending > 0 && pending < ring.length) {
                            for (int i = 0; i < win.length; i++) win[i] = head + i < written ? ring[(int) ((head + i) % ring.length)] : 0;
                            got = true;
                        }
                    }
                    if (got) Spectrum.bands(win, win.length, OUT_RATE, aiBands);
                }
                boolean now = pending > OUT_RATE / 25;          // more than 40 ms still to play
                if (now && !speaking) { speaking = true; state("speaking", ""); }
                else if (!now && speaking && playQ.isEmpty()) { speaking = false; lastSpoke = SystemClock.elapsedRealtime(); state("listening", ""); }
                JSONArray m = new JSONArray(), a = new JSONArray();
                for (int i = 0; i < 16; i++) { m.put(Math.round(micBands[i] * 100) / 100.0); a.put(Math.round(aiBands[i] * 100) / 100.0); micBands[i] *= 0.82f; aiBands[i] *= 0.82f; }
                send(new JSONObject().put("t", "lv").put("m", m).put("a", a));
                // "Hey Danav" stays on the line: only a very long silence (5 min with nobody talking) closes it quietly
                if (wake && !bridge && !speaking && playQ.isEmpty() && SystemClock.elapsedRealtime() - lastAct > WAKE_IDLE_MS) { finishSoon(); return; }
            } catch (Exception ignored) {}
            ui.postDelayed(this, 40);
        }
    };

    private void end(boolean notify) {
        if (ended) return;
        ended = true;
        gen++;
        ui.removeCallbacks(levelTick);
        ui.removeCallbacks(watchdog);
        toolPending.clear();
        ScreenShare.stop(false);
        try { if (ws != null) ws.close(1000, "bye"); } catch (Exception ignored) {}
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) {}
        try { if (track != null) { track.pause(); track.flush(); track.release(); } } catch (Exception ignored) {}
        rec = null; track = null; playQ.clear();
        try {
            if (am != null) {
                if (devCb != null) am.unregisterAudioDeviceCallback(devCb);
                if (scoOn) { am.setBluetoothScoOn(false); am.stopBluetoothSco(); scoOn = false; }
                dropFocus();
                // The earpiece must never stay as the last device: that is why music and videos used to come out
                // of the top speaker after a Danav call. Hand the route back to the loudspeaker, then release it.
                if (Build.VERSION.SDK_INT >= 31) {
                    AudioDeviceInfo sp = null;
                    try { for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { sp = d; break; } } catch (Throwable ignored) {}
                    try { if (sp != null) am.setCommunicationDevice(sp); } catch (Throwable ignored) {}
                    try { am.clearCommunicationDevice(); } catch (Throwable ignored) {}
                } else {
                    try { am.setSpeakerphoneOn(true); } catch (Throwable ignored) {}
                    final AudioManager a2 = am;
                    ui.postDelayed(() -> { try { a2.setSpeakerphoneOn(false); } catch (Throwable ignored) {} }, 600);
                }
                am.setMode(oldMode == AudioManager.MODE_IN_CALL ? AudioManager.MODE_NORMAL : oldMode);
            }
        } catch (Exception ignored) {}
        CallService.stop(ctx);
        ui.removeCallbacks(busyTick);
        Talk.shut();
        tools.shutdownNow();
        ui.post(CallBubble::hide);
        WakeService.callEnded(ctx);
        java.util.Arrays.fill(micBands, 0); java.util.Arrays.fill(aiBands, 0);
        if (notify) state("ended", "");
    }

    private void state(String s, String msg) {
        try { send(new JSONObject().put("t", "state").put("s", s).put("msg", msg)); } catch (Exception ignored) {}
    }

    private void send(JSONObject o) { host.send("live", o.toString()); }

    // ------------------------------------------------------------------ voice preview

    private static volatile int previewGen;
    private static AudioTrack previewTrack;

    /**
     * Plays a short sample of a voice. The first time it is generated by the live model and starts playing as soon as the first
     * audio arrives (and is cached); later it plays from the cache. Sends 16-band levels while playing, for the preview orb.
     */
    static void preview(Context c, Bridge.Host host, String voice) {
        final int my = ++previewGen;
        stopPreviewTrack();
        new Thread(() -> {
            File f = new File(c.getCacheDir(), "voices/" + voice + ".pcm");
            AudioTrack t = null;
            try {
                int minOut = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                t = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                        new AudioFormat.Builder().setSampleRate(OUT_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                        Math.max(minOut, OUT_RATE / 5 * 2), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
                synchronized (LiveCall.class) { if (my != previewGen) { t.release(); return; } previewTrack = t; }
                final AudioTrack tr = t;
                final short[] ringP = new short[OUT_RATE * 30];
                final long[] wr = {0};
                final boolean[] started = {false};
                LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>();
                final boolean[] srcDone = {false}, srcOk = {false};
                if (f.exists() && f.length() > 4800) {
                    try (FileInputStream in = new FileInputStream(f)) { q.offer(Ai.readAll(in)); }
                    srcDone[0] = true; srcOk[0] = true;
                } else {
                    pstate(host, voice, "loading", null);
                    new Thread(() -> {
                        byte[] all = fetchPreview(c, voice, q, () -> my != previewGen);
                        if (all != null) {
                            try { f.getParentFile().mkdirs(); try (FileOutputStream o = new FileOutputStream(f)) { o.write(all); } } catch (Exception ignored) {}
                        }
                        synchronized (srcDone) { srcOk[0] = all != null; srcDone[0] = true; }
                    }, "danav-voice-fetch").start();
                }
                // levels for the preview orb
                final float[] bands = new float[16];
                final short[] w = new short[512];
                Thread lv = new Thread(() -> {
                    while (my == previewGen) {
                        try {
                            if (started[0]) {
                                long head = tr.getPlaybackHeadPosition() & 0xffffffffL;
                                boolean got = false;
                                synchronized (ringP) {
                                    long pend = wr[0] - head;
                                    if (pend > 0 && pend < ringP.length) { for (int i = 0; i < w.length; i++) w[i] = head + i < wr[0] ? ringP[(int) ((head + i) % ringP.length)] : 0; got = true; }
                                }
                                if (got) Spectrum.bands(w, w.length, OUT_RATE, bands); else for (int i = 0; i < 16; i++) bands[i] *= 0.8f;
                                JSONArray a = new JSONArray();
                                for (int i = 0; i < 16; i++) a.put(Math.round(bands[i] * 100) / 100.0);
                                pstate(host, voice, "lv", a);
                            }
                            Thread.sleep(40);
                        } catch (Exception e) { break; }
                    }
                }, "danav-voice-lv");
                lv.start();
                short[] sh = new short[OUT_RATE / 50];
                long deadline = System.currentTimeMillis() + 25000;
                while (my == previewGen) {
                    byte[] pcm = q.poll(100, TimeUnit.MILLISECONDS);
                    if (pcm == null) {
                        boolean d; synchronized (srcDone) { d = srcDone[0]; }
                        if (d && q.isEmpty()) break;
                        if (System.currentTimeMillis() > deadline) break;
                        continue;
                    }
                    if (!started[0]) { started[0] = true; tr.play(); pstate(host, voice, "playing", null); }
                    for (int off = 0; off + 1 < pcm.length && my == previewGen; off += sh.length * 2) {
                        int len = Math.min(sh.length * 2, pcm.length - off) & ~1, ns = len / 2;
                        for (int i = 0; i < ns; i++) sh[i] = (short) ((pcm[off + i * 2] & 0xff) | (pcm[off + i * 2 + 1] << 8));
                        int n = tr.write(sh, 0, ns);
                        if (n <= 0) break;
                        synchronized (ringP) { for (int i = 0; i < n; i++) ringP[(int) ((wr[0] + i) % ringP.length)] = sh[i]; wr[0] += n; }
                    }
                }
                if (!started[0]) { if (my == previewGen) pstate(host, voice, "error", null); return; }
                // let the tail play out
                while (my == previewGen && (tr.getPlaybackHeadPosition() & 0xffffffffL) < wr[0] - 200) Thread.sleep(40);
                if (my == previewGen) { previewGen++; pstate(host, voice, "done", null); }
            } catch (Exception e) {
                if (my == previewGen) pstate(host, voice, "error", null);
            } finally {
                synchronized (LiveCall.class) { if (previewTrack == t) previewTrack = null; }
                if (t != null) { try { t.pause(); t.flush(); t.release(); } catch (Exception ignored) {} }
            }
        }, "danav-voice-preview").start();
    }

    static void stopPreview() { previewGen++; stopPreviewTrack(); }

    private static synchronized void stopPreviewTrack() {
        if (previewTrack != null) { try { previewTrack.pause(); previewTrack.flush(); } catch (Exception ignored) {} }
    }

    private static void pstate(Bridge.Host host, String voice, String s, JSONArray a) {
        try {
            JSONObject o = new JSONObject().put("voice", voice).put("state", s);
            if (a != null) o.put("a", a);
            host.send("voicePreview", o.toString());
        } catch (Exception ignored) {}
    }

    interface Cancel { boolean is(); }

    /** Asks the live model (with that voice) to say one line; audio chunks go to {@code q} as they arrive. Tries every key. */
    private static byte[] fetchPreview(Context c, String voice, LinkedBlockingQueue<byte[]> q, Cancel cancel) {
        List<String> keys = AiKeys.get(c);
        String[] lm = null;
        try { lm = Ai.models(c, true); } catch (Throwable ignored) {}
        String mdl = lm != null && lm.length > 0 ? lm[0] : MODEL;
        for (int k = 0; k < keys.size() && !cancel.is(); k++) {
            int ki = (stickyKey + k) % keys.size();
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final Object lock = new Object();
            final boolean[] done = {false, false};
            Request.Builder rb = new Request.Builder().url(WS).header("x-goog-api-key", keys.get(ki)).header("X-Android-Package", "com.dilhan.danavai");
            if (!AiKeys.certSha1.isEmpty()) rb.header("X-Android-Cert", AiKeys.certSha1);
            WebSocket ws = http.newWebSocket(rb.build(), new WebSocketListener() {
                @Override public void onOpen(WebSocket w, Response r) {
                    try {
                        JSONObject setup = new JSONObject().put("model", "models/" + mdl)
                                .put("generationConfig", new JSONObject().put("responseModalities", new JSONArray().put("AUDIO"))
                                        .put("speechConfig", new JSONObject().put("voiceConfig", new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice)))))
                                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text",
                                        "You are a voice sample. When asked, say the given line exactly and nothing else, in a natural, warm, friendly tone."))));
                        w.send(new JSONObject().put("setup", setup).toString());
                    } catch (Exception e) { w.cancel(); }
                }
                @Override public void onMessage(WebSocket w, ByteString b) { got(w, b.utf8()); }
                @Override public void onMessage(WebSocket w, String text) { got(w, text); }
                private void got(WebSocket w, String text) {
                    try {
                        JSONObject m = new JSONObject(text);
                        if (m.has("setupComplete")) {
                            w.send(new JSONObject().put("realtimeInput", new JSONObject().put("text", "Say exactly: Hi, I'm " + voice + ". This is how I sound when we talk in Danav.")).toString());
                            return;
                        }
                        JSONObject sc = m.optJSONObject("serverContent");
                        if (sc == null) return;
                        JSONObject turn = sc.optJSONObject("modelTurn");
                        JSONArray parts = turn == null ? null : turn.optJSONArray("parts");
                        if (parts != null) for (int i = 0; i < parts.length(); i++) {
                            JSONObject inl = parts.getJSONObject(i).optJSONObject("inlineData");
                            if (inl != null) {
                                byte[] pcm = Base64.decode(inl.optString("data"), Base64.DEFAULT);
                                synchronized (out) { out.write(pcm, 0, pcm.length); }
                                q.offer(pcm);
                            }
                        }
                        if (sc.optBoolean("generationComplete") || sc.optBoolean("turnComplete")) { synchronized (lock) { done[0] = true; done[1] = true; lock.notifyAll(); } w.close(1000, null); }
                    } catch (Exception ignored) {}
                }
                @Override public void onFailure(WebSocket w, Throwable t, Response r) { synchronized (lock) { done[0] = true; lock.notifyAll(); } }
                @Override public void onClosed(WebSocket w, int code, String reason) { synchronized (lock) { done[0] = true; lock.notifyAll(); } }
            });
            synchronized (lock) {
                long until = System.currentTimeMillis() + 20000;
                while (!done[0] && System.currentTimeMillis() < until && !cancel.is()) { try { lock.wait(300); } catch (InterruptedException ignored) {} }
            }
            ws.cancel();
            synchronized (out) {
                if (out.size() > 4800 && done[1]) { stickyKey = ki; return out.toByteArray(); }
                if (out.size() > 0) return null;          // something already played: don't play a second sample on top
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ spectrum

    /** 16 log-spaced bands (90 Hz – 7.5 kHz), 0..1, from a short PCM slice. */
    static final class Spectrum {
        private static final int N = 512;
        static synchronized void bands(short[] pcm, int n, int rate, float[] out) {
            float[] re = new float[N], im = new float[N];
            int len = Math.min(n, N);
            for (int i = 0; i < len; i++) re[i] = pcm[i] / 32768f * (0.5f - 0.5f * (float) Math.cos(2 * Math.PI * i / (len - 1)));
            fft(re, im);
            double lo = Math.log(90), hi = Math.log(7500);
            for (int b = 0; b < 16; b++) {
                int a = (int) (Math.exp(lo + (hi - lo) * b / 16) * N / rate), z = (int) (Math.exp(lo + (hi - lo) * (b + 1) / 16) * N / rate);
                a = Math.max(1, a); z = Math.min(N / 2, Math.max(a + 1, z));
                float s = 0;
                for (int k = a; k < z; k++) s += (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
                s /= (z - a);
                float db = (float) (20 * Math.log10(s + 1e-6));            // ≈ -100 … 0
                float v = Math.max(0f, Math.min(1f, (db + 62f) / 46f));
                out[b] = Math.max(out[b] * 0.7f, v);                        // fast attack, smooth release
            }
        }
        private static void fft(float[] re, float[] im) {
            int n = re.length;
            for (int i = 1, j = 0; i < n; i++) {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) j ^= bit;
                j ^= bit;
                if (i < j) { float t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
            }
            for (int len = 2; len <= n; len <<= 1) {
                double ang = -2 * Math.PI / len;
                float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
                for (int i = 0; i < n; i += len) {
                    float cr = 1, ci = 0;
                    for (int j = 0; j < len / 2; j++) {
                        int u = i + j, v = i + j + len / 2;
                        float xr = re[v] * cr - im[v] * ci, xi = re[v] * ci + im[v] * cr;
                        re[v] = re[u] - xr; im[v] = im[u] - xi; re[u] += xr; im[u] += xi;
                        float t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                    }
                }
            }
        }
    }
}
