package com.dilhan.vidra;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

/** Background music playback: lock-screen / notification controls, headset buttons, audio focus. */
public class MusicService extends MediaSessionService {
    private MediaSession session;

    static DefaultRenderersFactory renderers(android.content.Context c) {
        // FFmpeg extension first: AC-3 / E-AC-3 / DTS / TrueHD / ALAC… that phones can't decode themselves
        return new DefaultRenderersFactory(c)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true);
    }

    @Override public void onCreate() {
        super.onCreate();
        ExoPlayer player = new ExoPlayer.Builder(this, renderers(this))
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build();
        SoundFx.attach(this, player);
        Spectrum.attach(this, player);      // real FFT of this player's audio session (the music spectrum)
        Intent open = new Intent(this, MainActivity.class).putExtra("view", "player").setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 77, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        session = new MediaSession.Builder(this, player)
                .setSessionActivity(pi)
                .setCallback(new MediaSession.Callback() {
                    @NonNull @Override
                    public ListenableFuture<List<MediaItem>> onAddMediaItems(@NonNull MediaSession s, @NonNull MediaSession.ControllerInfo c, @NonNull List<MediaItem> items) {
                        // controllers may strip the uri; restore it from the request metadata
                        List<MediaItem> out = new ArrayList<>();
                        for (MediaItem m : items) {
                            if (m.localConfiguration != null) { out.add(m); continue; }
                            Uri u = m.requestMetadata.mediaUri;
                            out.add(u == null ? m : m.buildUpon().setUri(u).build());
                        }
                        return Futures.immediateFuture(out);
                    }
                })
                .build();
        DefaultMediaNotificationProvider np = new DefaultMediaNotificationProvider.Builder(this).build();
        np.setSmallIcon(R.drawable.ic_stat_music);
        setMediaNotificationProvider(np);
    }

    @Nullable @Override public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo info) { return session; }

    @Override public void onTaskRemoved(@Nullable Intent rootIntent) {
        Player p = session == null ? null : session.getPlayer();
        if (p == null || !p.getPlayWhenReady() || p.getMediaItemCount() == 0) stopSelf();
    }

    @Override public void onDestroy() {
        SoundFx.detach();
        Spectrum.detach();
        if (session != null) { session.getPlayer().release(); session.release(); session = null; }
        super.onDestroy();
    }
}
