package com.dilhan.vidra;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** App-side handle to MusicService: queue, transport, sleep timer; pushes state to the UI. */
final class MusicHub {
    interface Listener { void onMusic(String type, String json); }
    private MusicHub() {}

    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static MediaController ctl;
    private static ListenableFuture<MediaController> future;
    private static final List<Consumer<MediaController>> waiting = new ArrayList<>();
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static JSONArray queue = new JSONArray();
    private static long sleepAt;          // elapsedRealtime, 0 = off
    private static boolean sleepEnd;      // stop at end of the current song
    private static String lastError = "";
    private static Context app;

    static void addListener(Listener l) { listeners.add(l); }
    static void removeListener(Listener l) { listeners.remove(l); }

    private static void emit(String type, String json) { for (Listener l : listeners) l.onMusic(type, json); }

    /** Run f with a connected controller (connects/starts the service on first use). Main thread. */
    static void with(Context c, Consumer<MediaController> f) {
        ui.post(() -> {
            if (ctl != null && ctl.isConnected()) { f.accept(ctl); return; }
            waiting.add(f);
            if (future != null) return;
            app = c.getApplicationContext();
            SessionToken token = new SessionToken(app, new ComponentName(app, MusicService.class));
            future = new MediaController.Builder(app, token).buildAsync();
            future.addListener(() -> {
                try {
                    ctl = future.get();
                    ctl.addListener(listener);
                    List<Consumer<MediaController>> w = new ArrayList<>(waiting);
                    waiting.clear();
                    for (Consumer<MediaController> x : w) x.accept(ctl);
                    push();
                } catch (Exception e) {
                    waiting.clear();
                    lastError = "Music player couldn't start";
                }
                future = null;
            }, ui::post);
        });
    }

    static boolean active() { return ctl != null && ctl.isConnected() && ctl.getMediaItemCount() > 0; }

    private static final Player.Listener listener = new Player.Listener() {
        @Override public void onEvents(@NonNull Player p, @NonNull Player.Events e) { push(); }
        @Override public void onMediaItemTransition(MediaItem item, int reason) {
            lastError = "";
            if (sleepEnd && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && ctl != null) { ctl.pause(); sleepEnd = false; }
        }
        @Override public void onPlayerError(@NonNull PlaybackException error) {
            lastError = PlayerActivity.describe(error);
            push();
            // skip the broken file and keep going
            ui.postDelayed(() -> { if (ctl != null && ctl.hasNextMediaItem()) { ctl.seekToNextMediaItem(); ctl.prepare(); ctl.play(); } }, 1200);
        }
    };

    private static final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (sleepAt > 0 && SystemClock.elapsedRealtime() >= sleepAt) { sleepAt = 0; if (ctl != null) ctl.pause(); }
            push();
            if (ctl != null && (ctl.isPlaying() || sleepAt > 0)) ui.postDelayed(this, 500);
        }
    };

    static JSONObject state() {
        JSONObject o = new JSONObject();
        try {
            MediaController c = ctl;
            boolean ok = c != null && c.isConnected() && c.getMediaItemCount() > 0;
            o.put("active", ok);
            if (!ok) return o;
            int idx = c.getCurrentMediaItemIndex();
            JSONObject meta = idx >= 0 && idx < queue.length() ? queue.optJSONObject(idx) : null;
            MediaItem mi = c.getCurrentMediaItem();
            String id = mi != null ? mi.mediaId : "";
            if (meta == null || !id.equals(meta.optString("uri"))) {
                meta = new JSONObject();
                if (mi != null) meta.put("uri", id).put("title", String.valueOf(mi.mediaMetadata.title)).put("artist", mi.mediaMetadata.artist == null ? "" : String.valueOf(mi.mediaMetadata.artist));
            }
            long dur = c.getDuration();
            o.put("playing", c.isPlaying()).put("want", c.getPlayWhenReady()).put("buffering", c.getPlaybackState() == Player.STATE_BUFFERING)
                    .put("ended", c.getPlaybackState() == Player.STATE_ENDED)
                    .put("index", idx).put("count", c.getMediaItemCount()).put("hasNext", c.hasNextMediaItem()).put("hasPrev", c.hasPreviousMediaItem()).put("item", meta)
                    .put("pos", Math.max(0, c.getCurrentPosition())).put("buf", Math.max(0, c.getBufferedPosition()))
                    .put("dur", dur > 0 ? dur : meta.optLong("dur", 0))
                    .put("shuffle", c.getShuffleModeEnabled()).put("repeat", c.getRepeatMode())
                    .put("speed", c.getPlaybackParameters().speed).put("fx", SoundFx.cur)
                    .put("mix", SoundFx.mixJson()).put("spec", Spectrum.running())
                    .put("sleep", sleepEnd ? -1 : sleepAt > 0 ? Math.max(0, sleepAt - SystemClock.elapsedRealtime()) : 0)
                    .put("error", lastError);
        } catch (Exception ignored) {}
        return o;
    }

    private static long lastPush;
    static void push() {
        emit("music", state().toString());
        ui.removeCallbacks(ticker);
        if (ctl != null && (ctl.isPlaying() || sleepAt > 0)) ui.postDelayed(ticker, 500);
        lastPush = SystemClock.elapsedRealtime();
    }

    static String queueJson() { return queue.toString(); }

    private static MediaItem item(JSONObject o) {
        String uri = o.optString("uri");
        String art = o.optString("art");
        MediaMetadata.Builder m = new MediaMetadata.Builder()
                .setTitle(o.optString("title"))
                .setArtist(o.optString("artist", ""))
                .setAlbumTitle(o.optString("album", ""))
                .setIsPlayable(true);
        if (!art.isEmpty()) m.setArtworkUri(art.startsWith("/") ? Uri.fromFile(new File(art)) : Uri.parse(art));
        return new MediaItem.Builder()
                .setMediaId(uri)
                .setUri(Uri.parse(uri))
                .setRequestMetadata(new MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(uri)).build())
                .setMediaMetadata(m.build())
                .build();
    }

    /** Commands from the UI. */
    static void command(Context c, String cmd, JSONObject a) {
        with(c, ctl -> {
            switch (cmd) {
                case "queue": {
                    JSONArray items = a.optJSONArray("items");
                    if (items == null || items.length() == 0) return;
                    queue = items;
                    List<MediaItem> list = new ArrayList<>();
                    for (int i = 0; i < items.length(); i++) list.add(item(items.optJSONObject(i)));
                    int start = Math.max(0, Math.min(items.length() - 1, a.optInt("index", 0)));
                    ctl.setShuffleModeEnabled(a.optBoolean("shuffle", false));
                    ctl.setMediaItems(list, start, a.optLong("pos", 0));
                    ctl.prepare();
                    ctl.play();
                    lastError = "";
                    emit("musicQueue", queue.toString());
                    break;
                }
                case "add": {   // play next / add to queue
                    JSONObject o = a.optJSONObject("item");
                    if (o == null) return;
                    if (ctl.getMediaItemCount() == 0) { JSONArray q = new JSONArray(); q.put(o); try { command(c, "queue", new JSONObject().put("items", q)); } catch (Exception ignored) {} return; }
                    int at = a.optBoolean("next") ? ctl.getCurrentMediaItemIndex() + 1 : ctl.getMediaItemCount();
                    ctl.addMediaItem(at, item(o));
                    JSONArray q = new JSONArray();
                    for (int i = 0; i < queue.length(); i++) { if (i == at) q.put(o); q.put(queue.opt(i)); }
                    if (at >= queue.length()) q.put(o);
                    queue = q;
                    emit("musicQueue", queue.toString());
                    break;
                }
                case "remove": {
                    int i = a.optInt("index", -1);
                    if (i < 0 || i >= ctl.getMediaItemCount()) return;
                    ctl.removeMediaItem(i);
                    queue.remove(i);
                    emit("musicQueue", queue.toString());
                    break;
                }
                case "toggle": if (ctl.isPlaying()) ctl.pause(); else { if (ctl.getPlaybackState() == Player.STATE_ENDED) ctl.seekTo(0, 0); if (ctl.getPlaybackState() == Player.STATE_IDLE) ctl.prepare(); ctl.play(); } break;
                case "play": ctl.play(); break;
                case "pause": ctl.pause(); break;
                case "next": if (ctl.hasNextMediaItem()) ctl.seekToNextMediaItem(); break;
                case "prev": if (ctl.getCurrentPosition() > 4000 || !ctl.hasPreviousMediaItem()) ctl.seekTo(0); else ctl.seekToPreviousMediaItem(); break;
                case "seek": ctl.seekTo(Math.max(0, a.optLong("ms"))); break;
                case "skip": ctl.seekTo(Math.max(0, ctl.getCurrentPosition() + a.optLong("ms"))); break;
                case "jump": { int i = a.optInt("index"); if (i >= 0 && i < ctl.getMediaItemCount()) { ctl.seekTo(i, 0); ctl.play(); } break; }
                case "shuffle": ctl.setShuffleModeEnabled(a.optBoolean("on")); break;
                case "repeat": ctl.setRepeatMode(a.optInt("mode") % 3); break;
                case "speed": SoundFx.load(c); ctl.setPlaybackParameters(new PlaybackParameters((float) a.optDouble("v", 1), SoundFx.pitch())); break;
                case "fx": { String w = SoundFx.apply(c, a.optString("id", "off")); if (!w.isEmpty()) emit("musicFx", "{\"warn\":" + JSONObject.quote(w) + "}"); break; }
                case "mix": { String w = SoundFx.setMix(c, a.optString("name"), a.optInt("v")); push(); if (!w.isEmpty()) emit("musicFx", "{\"warn\":" + JSONObject.quote(w) + "}"); break; }
                case "mixReset": SoundFx.resetMix(c); push(); break;
                case "sleep": {
                    long min = a.optLong("min");
                    sleepEnd = min < 0;
                    sleepAt = min > 0 ? SystemClock.elapsedRealtime() + min * 60000 : 0;
                    break;
                }
                case "stop": ctl.stop(); ctl.clearMediaItems(); queue = new JSONArray(); sleepAt = 0; sleepEnd = false; emit("musicQueue", "[]"); break;
            }
            push();
        });
    }

    /** One FFT frame of the music that is playing right now (48 bars, 0…1) — drawn by the player's spectrum. */
    static void spectrum(JSONArray bands, float peak) {
        try { emit("spectrum", new JSONObject().put("b", bands).put("p", peak).toString()); } catch (Exception ignored) {}
    }

    static void spectrumFailed(String why) {
        try { emit("musicFx", new JSONObject().put("warn", "The spectrum isn't available here (" + why + ")").toString()); } catch (Exception ignored) {}
        emit("spectrumOff", "{}");
    }

    /** Pause music when the video player starts. */
    static void pauseIfPlaying() { ui.post(() -> { if (ctl != null && ctl.isPlaying()) ctl.pause(); }); }
}
