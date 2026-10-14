package com.dilhan.vidra;

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
    private static final OkHttpClient http = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).connectTimeout(10, TimeUnit.SECONDS).build();

    private final Context ctx;
    private final Bridge.Host host;
    private final String voice;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile WebSocket ws;
    private volatile boolean ready, ended, muted, speaker = true;
    private volatile String handle;
    private int keyIdx, tried, gen;
    private AudioRecord rec;
    private AudioTrack track;
    private Thread micThread, playThread;
    private final LinkedBlockingQueue<byte[]> playQ = new LinkedBlockingQueue<>();
    private final float[] micBands = new float[16], aiBands = new float[16];
    private AudioManager am;
    private int oldMode;
    private boolean speaking;
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
    private volatile long lastAct = SystemClock.elapsedRealtime();
    private volatile boolean endAfterTurn;
    private final java.util.concurrent.ExecutorService tools = java.util.concurrent.Executors.newSingleThreadExecutor();

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
    static void mute(boolean on) { LiveCall l = cur; if (l != null) { l.muted = on; try { l.send(new JSONObject().put("t", "mute").put("on", on)); } catch (Exception ignored) {} CallService.refresh(l.ctx); } }
    static boolean muted() { LiveCall l = cur; return l != null && l.muted; }
    static void speaker(boolean on) { LiveCall l = cur; if (l != null) { l.speakerPref = on; l.route(); } }
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
        route();
        devCb = new android.media.AudioDeviceCallback() {
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] d) { if (isHeadsetChange(d)) { speakerPref = null; route(); } }
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] d) { if (isHeadsetChange(d)) { speakerPref = null; route(); } }
        };
        try { am.registerAudioDeviceCallback(devCb, ui); } catch (Exception ignored) {}
        if (!wake) CallService.start(ctx);           // a wake call runs inside the Hey Danav service (already allowed to use the mic)
        keyIdx = stickyKey % keys.size();
        tried = 0;
        state("connecting", "");
        connect();
        ui.post(levelTick);
    }

    private void connect() {
        List<String> keys = AiKeys.get(ctx);
        if (ended) return;
        final int my = ++gen;
        Request.Builder rb = new Request.Builder().url(WS)
                .header("x-goog-api-key", keys.get(keyIdx))
                .header("X-Android-Package", "com.dilhan.vidra");
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
            + "Write messages exactly as the user wants them (same language; Roman Urdu stays Roman Urdu). Don't ask for confirmation for simple, clear requests; "
            + "only ask when a contact is ambiguous or the request is risky (deleting, paying, posting publicly). For multi-step tasks in other apps: read_screen, act, "
            + "check the new screen in the tool result, and continue until done — tell the user briefly what you're doing. Report results honestly from the tool "
            + "results; never say something was done if the tool says it wasn't. While a tool runs, you may say a few words like 'bhej raha hoon'. "
            + "When the user says bye / bas / band karo, say a very short goodbye and call end_call.";
    private static final String WAKE_RULES = "\n\nIMPORTANT: this call was opened automatically because the phone thought it heard your name. The first audio you hear is what triggered it. "
            + "First decide: did the speaker clearly say your name \"Danav\" (Hey Danav / Danav)? Similar words like Dana, Daane, Dan, Danish, Dhaniya, Diwan do NOT count. "
            + "If your name was NOT said, call dismiss immediately and say nothing at all — don't answer, don't ask anything. "
            + "If your name was said and nothing else, answer with one or two words like 'Ji?' or 'Haan, boliye' and listen. If a request followed your name, just do it. "
            + "Keep this call short: when the task is done and nothing else is asked, say a tiny closing and call end_call.";

    private JSONObject setup() throws Exception {
        JSONObject s = new JSONObject()
                .put("model", "models/" + MODEL)
                .put("generationConfig", new JSONObject()
                        .put("responseModalities", new JSONArray().put("AUDIO"))
                        .put("speechConfig", new JSONObject().put("voiceConfig", new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice)))))
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", Ai.liveIdentity() + PHONE_RULES + (wake ? WAKE_RULES : "")))))
                .put("tools", Phone.tools(wake))
                .put("inputAudioTranscription", new JSONObject())
                .put("outputAudioTranscription", new JSONObject())
                .put("contextWindowCompression", new JSONObject().put("slidingWindow", new JSONObject()))
                .put("sessionResumption", handle == null ? new JSONObject() : new JSONObject().put("handle", handle));
        return new JSONObject().put("setup", s);
    }

    private void dropped(String why) {
        if (ended) return;
        ready = false;
        List<String> keys = AiKeys.get(ctx);
        tried++;
        if (tried >= keys.size() * 2) { state("error", "Couldn't connect to Danav AI. Check your internet and try again."); end(true); return; }
        keyIdx = (keyIdx + 1) % keys.size();           // next key right away
        state(handle == null ? "connecting" : "reconnecting", "");
        ui.postDelayed(this::connect, tried > keys.size() ? 600 : 0);
    }

    private void handle(String text) {
        try {
            JSONObject m = new JSONObject(text);
            if (m.has("setupComplete")) {
                ready = true; tried = 0; stickyKey = keyIdx;
                state("listening", "");
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
            if (m.has("goAway")) { ui.post(() -> { if (!ended) { tried = 0; connect(); } }); return; }   // server will close soon — move on early
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
            if (ot != null || it != null || parts != null) lastAct = SystemClock.elapsedRealtime();
            if (sc.optBoolean("turnComplete")) {
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
            tools.execute(() -> {
                if (ended) return;
                Phone.Out out = Phone.run(ctx, name, args);
                if (ended) return;
                lastAct = SystemClock.elapsedRealtime();
                if (out.dismiss) { ui.post(() -> { synchronized (LiveCall.class) { if (cur == this) cur = null; } end(true); }); return; }
                if (out.look && !ScreenShare.active()) { String img = Phone.shotB64(); if (img != null) video(img); }
                try {
                    JSONObject resp = new JSONObject().put("toolResponse", new JSONObject().put("functionResponses", new JSONArray().put(
                            new JSONObject().put("id", id).put("name", name).put("response", new JSONObject().put("result", out.text)))));
                    WebSocket w = ws;
                    if (w != null) w.send(resp.toString());
                } catch (Exception ignored) {}
                try { send(new JSONObject().put("t", "tool").put("label", "")); } catch (Exception ignored) {}
                if (out.endCall) { endAfterTurn = true; ui.postDelayed(this::finishSoon, 7000); }
            });
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
        if (rec != null || ended) return;
        int minIn = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minIn, 6400));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) { state("error", "The microphone is busy or not allowed."); end(true); return; }
        int sid = rec.getAudioSessionId();
        try { if (AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler e = AcousticEchoCanceler.create(sid); if (e != null) e.setEnabled(true); } } catch (Throwable ignored) {}
        try { if (NoiseSuppressor.isAvailable()) { NoiseSuppressor n = NoiseSuppressor.create(sid); if (n != null) n.setEnabled(true); } } catch (Throwable ignored) {}
        try { if (AutomaticGainControl.isAvailable()) { AutomaticGainControl g = AutomaticGainControl.create(sid); if (g != null) g.setEnabled(true); } } catch (Throwable ignored) {}
        int minOut = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                new AudioFormat.Builder().setSampleRate(OUT_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                Math.max(minOut, OUT_RATE / 5 * 2), AudioTrack.MODE_STREAM, sid);
        track.play();
        rec.startRecording();
        final AudioRecord rec = this.rec;
        final AudioTrack track = this.track;
        micThread = new Thread(() -> {
            short[] buf = new short[IN_RATE / 25];            // 40 ms
            byte[] bytes = new byte[buf.length * 2];
            while (!ended) {
                int n;
                try { n = rec.read(buf, 0, buf.length); } catch (Exception e) { break; }
                if (n <= 0) { if (ended) break; continue; }
                if (muted) { java.util.Arrays.fill(micBands, 0); continue; }
                Spectrum.bands(buf, n, IN_RATE, micBands);
                if (!ready || ws == null) continue;
                for (int i = 0; i < n; i++) { bytes[i * 2] = (byte) buf[i]; bytes[i * 2 + 1] = (byte) (buf[i] >> 8); }
                try {
                    String b64 = Base64.encodeToString(bytes, 0, n * 2, Base64.NO_WRAP);
                    ws.send("{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\"" + b64 + "\"}}}");
                } catch (Exception ignored) {}
            }
        }, "danav-live-mic");
        micThread.start();
        playThread = new Thread(() -> {
            short[] sh = new short[OUT_RATE / 50];
            while (!ended) {
                try {
                    byte[] pcm = playQ.poll(200, TimeUnit.MILLISECONDS);
                    if (pcm == null) continue;
                    if (flushReq) { flushReq = false; synchronized (ring) { written = 0; } }
                    // 20 ms slices; every slice is kept in a ring so the spectrum can read what the speaker plays right now
                    for (int off = 0; off + 1 < pcm.length && !ended && !flushReq; off += sh.length * 2) {
                        int len = Math.min(sh.length * 2, pcm.length - off) & ~1, ns = len / 2;
                        for (int i = 0; i < ns; i++) sh[i] = (short) ((pcm[off + i * 2] & 0xff) | (pcm[off + i * 2 + 1] << 8));
                        int w = track.write(sh, 0, ns);
                        if (w <= 0) break;
                        synchronized (ring) {
                            for (int i = 0; i < w; i++) ring[(int) ((written + i) % ring.length)] = sh[i];
                            written += w;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }, "danav-live-play");
        playThread.start();
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
                else if (!now && speaking && playQ.isEmpty()) { speaking = false; state("listening", ""); }
                JSONArray m = new JSONArray(), a = new JSONArray();
                for (int i = 0; i < 16; i++) { m.put(Math.round(micBands[i] * 100) / 100.0); a.put(Math.round(aiBands[i] * 100) / 100.0); micBands[i] *= 0.82f; aiBands[i] *= 0.82f; }
                send(new JSONObject().put("t", "lv").put("m", m).put("a", a));
                if (wake && !speaking && playQ.isEmpty() && SystemClock.elapsedRealtime() - lastAct > 25_000) { finishSoon(); return; }
            } catch (Exception ignored) {}
            ui.postDelayed(this, 40);
        }
    };

    private void end(boolean notify) {
        if (ended) return;
        ended = true;
        gen++;
        ui.removeCallbacks(levelTick);
        ScreenShare.stop(false);
        try { if (ws != null) ws.close(1000, "bye"); } catch (Exception ignored) {}
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) {}
        try { if (track != null) { track.pause(); track.flush(); track.release(); } } catch (Exception ignored) {}
        rec = null; track = null; playQ.clear();
        try {
            if (am != null) {
                if (devCb != null) am.unregisterAudioDeviceCallback(devCb);
                if (scoOn) { am.setBluetoothScoOn(false); am.stopBluetoothSco(); scoOn = false; }
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice(); else am.setSpeakerphoneOn(false);
                am.setMode(oldMode);
            }
        } catch (Exception ignored) {}
        CallService.stop(ctx);
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
        for (int k = 0; k < keys.size() && !cancel.is(); k++) {
            int ki = (stickyKey + k) % keys.size();
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final Object lock = new Object();
            final boolean[] done = {false, false};
            Request.Builder rb = new Request.Builder().url(WS).header("x-goog-api-key", keys.get(ki)).header("X-Android-Package", "com.dilhan.vidra");
            if (!AiKeys.certSha1.isEmpty()) rb.header("X-Android-Cert", AiKeys.certSha1);
            WebSocket ws = http.newWebSocket(rb.build(), new WebSocketListener() {
                @Override public void onOpen(WebSocket w, Response r) {
                    try {
                        JSONObject setup = new JSONObject().put("model", "models/" + MODEL)
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
