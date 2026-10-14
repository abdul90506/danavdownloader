package com.dilhan.vidra;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * media3 {@link Player} backed by libVLC (software FFmpeg decoders + hardware when possible).
 * Used automatically when ExoPlayer can't decode a file, so the same UI keeps working.
 */
@UnstableApi
public final class VlcPlayer extends SimpleBasePlayer {
    private static LibVLC lib;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MediaPlayer mp;
    private List<MediaItem> items = new ArrayList<>();
    private int index;
    private boolean playWhenReady;
    private int state = Player.STATE_IDLE;
    private long length, time, pendingSeek = -1, seekTarget = -1, seekAt;
    private int repeat = Player.REPEAT_MODE_OFF;
    private float speed = 1f;
    private VideoSize videoSize = VideoSize.UNKNOWN;
    private PlaybackException error;
    private boolean seekable = true, firstFrame;
    private SurfaceView surface;
    private ParcelFileDescriptor pfd;
    private final View.OnLayoutChangeListener sizer = (v, l, t, r, b, ol, ot, or, ob) -> {
        if (r - l > 0 && b - t > 0) mpVout().setWindowSize(r - l, b - t);
    };

    static synchronized LibVLC lib(Context c) {
        if (lib == null) lib = new LibVLC(c.getApplicationContext(), new ArrayList<>(Arrays.asList(
                "--audio-time-stretch", "--avcodec-skiploopfilter=1", "--avcodec-skip-frame=0", "--avcodec-skip-idct=0",
                "--subsdec-encoding=UTF-8", "--android-display-chroma=RV32", "--no-stats")));
        return lib;
    }

    public VlcPlayer(Context c) {
        super(Looper.getMainLooper());
        ctx = c;
        mp = new MediaPlayer(lib(c));
        mp.setEventListener(ev -> main.post(() -> onEvent(ev)));
    }

    private IVLCVout mpVout() { return mp.getVLCVout(); }

    private void onEvent(MediaPlayer.Event e) {
        switch (e.type) {
            case MediaPlayer.Event.Opening: state = Player.STATE_BUFFERING; break;
            case MediaPlayer.Event.Buffering: if (e.getBuffering() >= 100f) { if (state == Player.STATE_BUFFERING) state = Player.STATE_READY; } else if (state != Player.STATE_READY) state = Player.STATE_BUFFERING; break;
            case MediaPlayer.Event.Playing:
                state = Player.STATE_READY;
                if (pendingSeek >= 0) { mp.setTime(pendingSeek); time = pendingSeek; pendingSeek = -1; }
                if (!playWhenReady) mp.pause();
                break;
            case MediaPlayer.Event.Paused: if (state == Player.STATE_BUFFERING) state = Player.STATE_READY; break;
            case MediaPlayer.Event.TimeChanged: {
                long t = e.getTimeChanged();
                // right after a seek VLC still reports a few old times: ignore them so the timeline doesn't jump back
                if (seekTarget >= 0) {
                    if (Math.abs(t - seekTarget) < 3000 || android.os.SystemClock.uptimeMillis() - seekAt > 2500) seekTarget = -1;
                    else { if (state == Player.STATE_BUFFERING) state = Player.STATE_READY; break; }
                }
                time = t;
                if (state == Player.STATE_BUFFERING) state = Player.STATE_READY;
                break;
            }
            case MediaPlayer.Event.LengthChanged: length = e.getLengthChanged(); break;
            case MediaPlayer.Event.SeekableChanged: seekable = e.getSeekable(); break;
            case MediaPlayer.Event.EndReached: onEnded(); break;
            case MediaPlayer.Event.EncounteredError:
                error = new PlaybackException("This file can't be played, even with the software decoder.", null, PlaybackException.ERROR_CODE_DECODING_FAILED);
                state = Player.STATE_IDLE;
                break;
            case MediaPlayer.Event.Vout: if (e.getVoutCount() > 0) firstFrame = true; break;
            case MediaPlayer.Event.ESAdded: case MediaPlayer.Event.ESSelected: break;
            default: return;
        }
        invalidateState();
    }

    private void onEnded() {
        if (repeat == Player.REPEAT_MODE_ONE) { load(index, 0); return; }
        if (index + 1 < items.size()) { load(index + 1, 0); return; }
        if (repeat == Player.REPEAT_MODE_ALL && !items.isEmpty()) { load(0, 0); return; }
        state = Player.STATE_ENDED; time = length;
    }

    private void load(int i, long pos) {
        if (items.isEmpty()) return;
        index = Math.max(0, Math.min(items.size() - 1, i));
        error = null; length = 0; time = Math.max(0, pos); seekTarget = pos > 0 ? pos : -1; seekAt = android.os.SystemClock.uptimeMillis(); videoSize = VideoSize.UNKNOWN; seekable = true;
        closePfd();
        Uri u = items.get(index).localConfiguration != null ? items.get(index).localConfiguration.uri : Uri.EMPTY;
        Media m;
        try {
            if ("content".equals(u.getScheme())) {
                pfd = ctx.getContentResolver().openFileDescriptor(u, "r");
                m = new Media(lib(ctx), pfd.getFileDescriptor());
            } else if ("file".equals(u.getScheme()) || u.getScheme() == null) {
                m = new Media(lib(ctx), u.getPath());
            } else m = new Media(lib(ctx), u);
        } catch (Exception ex) {
            error = new PlaybackException("The file couldn't be opened.", ex, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND);
            state = Player.STATE_IDLE; invalidateState(); return;
        }
        m.setHWDecoderEnabled(true, false);
        if (pos > 0) m.addOption(":start-time=" + (pos / 1000.0));
        mp.setMedia(m);
        m.release();
        state = Player.STATE_BUFFERING;
        mp.setRate(speed);
        mp.play();
        invalidateState();
    }

    private void closePfd() { if (pfd != null) { try { pfd.close(); } catch (Exception ignored) {} pfd = null; } }

    // ------------------------------------------------------------------ tracks (VLC ids)

    public MediaPlayer.TrackDescription[] audioTracks() { MediaPlayer.TrackDescription[] t = mp.getAudioTracks(); return t == null ? new MediaPlayer.TrackDescription[0] : t; }
    public MediaPlayer.TrackDescription[] spuTracks() { MediaPlayer.TrackDescription[] t = mp.getSpuTracks(); return t == null ? new MediaPlayer.TrackDescription[0] : t; }
    public int audioTrack() { return mp.getAudioTrack(); }
    public int spuTrack() { return mp.getSpuTrack(); }
    public void setAudioTrack(int id) { mp.setAudioTrack(id); }
    public void setSpuTrack(int id) { mp.setSpuTrack(id); }
    public void addSubtitle(Uri u) { mp.addSlave(IMedia.Slave.Type.Subtitle, u, true); }

    // ------------------------------------------------------------------ SimpleBasePlayer

    @NonNull @Override protected State getState() {
        Commands cmds = new Commands.Builder().addAll(
                COMMAND_PLAY_PAUSE, COMMAND_PREPARE, COMMAND_STOP, COMMAND_SEEK_TO_DEFAULT_POSITION, COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_MEDIA_ITEM, COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD, COMMAND_SET_SPEED_AND_PITCH, COMMAND_SET_REPEAT_MODE,
                COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE, COMMAND_GET_METADATA, COMMAND_SET_VIDEO_SURFACE,
                COMMAND_SET_MEDIA_ITEM, COMMAND_CHANGE_MEDIA_ITEMS, COMMAND_RELEASE).build();
        List<MediaItemData> list = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            MediaItemData.Builder b = new MediaItemData.Builder(i + ":" + items.get(i).mediaId).setMediaItem(items.get(i)).setIsSeekable(i != index || seekable);
            if (i == index && length > 0) b.setDurationUs(length * 1000);
            list.add(b.build());
        }
        int st = items.isEmpty() && state != Player.STATE_ENDED ? Player.STATE_IDLE : state;
        State.Builder s = new State.Builder()
                .setAvailableCommands(cmds)
                .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(st)
                .setPlayerError(error)
                .setRepeatMode(repeat)
                .setPlaybackParameters(new PlaybackParameters(speed))
                .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000)
                .setVideoSize(videoSize)
                .setPlaylist(list);
        if (firstFrame) { firstFrame = false; s.setNewlyRenderedFirstFrame(true); }
        if (!items.isEmpty()) {
            final long t = time;
            s.setCurrentMediaItemIndex(index)
                    .setContentPositionMs(st == Player.STATE_READY && playWhenReady ? PositionSupplier.getExtrapolating(t, speed) : PositionSupplier.getConstant(t))
                    .setContentBufferedPositionMs(PositionSupplier.getConstant(Math.max(t, length)));
        }
        return s.build();
    }

    @NonNull @Override protected ListenableFuture<?> handleSetPlayWhenReady(boolean pwr) {
        playWhenReady = pwr;
        if (state == Player.STATE_ENDED && pwr) { load(index, 0); return Futures.immediateVoidFuture(); }
        if (pwr) { if (!mp.isPlaying()) mp.play(); } else if (mp.isPlaying()) mp.pause();
        return Futures.immediateVoidFuture();
    }

    @NonNull @Override protected ListenableFuture<?> handlePrepare() {
        if (state == Player.STATE_IDLE && !items.isEmpty()) load(index, time);
        return Futures.immediateVoidFuture();
    }

    @NonNull @Override protected ListenableFuture<?> handleStop() { mp.stop(); state = Player.STATE_IDLE; return Futures.immediateVoidFuture(); }

    @NonNull @Override protected ListenableFuture<?> handleRelease() {
        mp.setEventListener(null);
        try { mp.stop(); } catch (Exception ignored) {}
        try { if (mpVout().areViewsAttached()) mpVout().detachViews(); } catch (Exception ignored) {}
        if (surface != null) surface.removeOnLayoutChangeListener(sizer);
        mp.release();
        closePfd();
        return Futures.immediateVoidFuture();
    }

    @NonNull @Override protected ListenableFuture<?> handleSetRepeatMode(int mode) { repeat = mode; return Futures.immediateVoidFuture(); }

    @NonNull @Override protected ListenableFuture<?> handleSetPlaybackParameters(@NonNull PlaybackParameters p) {
        speed = p.speed; mp.setRate(speed); return Futures.immediateVoidFuture();
    }

    @NonNull @Override protected ListenableFuture<?> handleSetTrackSelectionParameters(@NonNull TrackSelectionParameters p) {
        if (p.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) mp.setSpuTrack(-1);
        return Futures.immediateVoidFuture();
    }

    @NonNull @Override protected ListenableFuture<?> handleSetVideoOutput(@NonNull Object out) {
        if (!(out instanceof SurfaceView)) return Futures.immediateVoidFuture();
        IVLCVout vo = mpVout();
        if (vo.areViewsAttached()) vo.detachViews();
        if (surface != null) surface.removeOnLayoutChangeListener(sizer);
        surface = (SurfaceView) out;
        vo.setVideoView(surface);
        if (surface.getWidth() > 0) vo.setWindowSize(surface.getWidth(), surface.getHeight());
        surface.addOnLayoutChangeListener(sizer);
        vo.attachViews((v, w, hgt, vw, vh, sarNum, sarDen) -> main.post(() -> {
            if (vw <= 0 || vh <= 0) return;
            float par = sarNum > 0 && sarDen > 0 ? sarNum / (float) sarDen : 1f;
            videoSize = new VideoSize(vw, vh, 0, par);
            invalidateState();
        }));
        return Futures.immediateVoidFuture();
    }

    @NonNull @Override protected ListenableFuture<?> handleClearVideoOutput(@Nullable Object out) {
        try { if (mpVout().areViewsAttached()) mpVout().detachViews(); } catch (Exception ignored) {}
        if (surface != null) { surface.removeOnLayoutChangeListener(sizer); surface = null; }
        return Futures.immediateVoidFuture();
    }

    @NonNull @Override protected ListenableFuture<?> handleSetMediaItems(@NonNull List<MediaItem> list, int start, long pos) {
        items = new ArrayList<>(list);
        index = start == C.INDEX_UNSET ? 0 : start;
        time = pos == C.TIME_UNSET ? 0 : pos;
        state = Player.STATE_IDLE;
        return Futures.immediateVoidFuture();
    }

    @NonNull @Override protected ListenableFuture<?> handleSeek(int i, long pos, int cmd) {
        long p = pos == C.TIME_UNSET ? 0 : Math.max(0, pos);
        if (i != index || state == Player.STATE_IDLE || state == Player.STATE_ENDED) { load(i == C.INDEX_UNSET ? index : i, p); return Futures.immediateVoidFuture(); }
        time = p;
        seekTarget = p; seekAt = android.os.SystemClock.uptimeMillis();
        if (state == Player.STATE_BUFFERING && length == 0) pendingSeek = p; else mp.setTime(p, false);
        return Futures.immediateVoidFuture();
    }

}
