package com.dilhan.vidra;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Rational;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.ScaleGestureDetector;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Vidra's video player: ExoPlayer + FFmpeg audio, custom boxy controls and gestures. */
public class PlayerActivity extends Activity {
    static final int ACCENT = 0xFFE50914;
    static final int GLASS = 0xD91A1B24;
    private static final int REQ_SUB = 21;
    private static final float[] SPEEDS = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f};

    private Player player;            // ExoPlayer normally, VlcPlayer when the phone can't decode the file
    private Player.Listener listener;
    private PlayerView pv;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<JSONObject> items = new ArrayList<>();
    private final List<Uri> subs = new ArrayList<>();      // external subtitle per item (null = none)
    private SharedPreferences prefs;

    // views
    private FrameLayout root, controls, panel, preview, lockOverlay;
    private View scrim;
    private LinearLayout topBar, centerRow, bottomBox, infoBox, resumeChip;
    private TextView title, subtitle, posText, durText, infoText, speedPill, errText, previewTime, speedBtnText;
    private ImageView playBtn, prevBtn, nextBtn, rewBtn, ffBtn, infoIcon, repeatBtn, previewImg, lockBtn;
    private View infoBar, infoBarFill;
    private TimelineView timeline;
    private ProgressBar spinner;
    private SkipRipple skip;
    private float ui = 1f;
    private FrameLayout pframe;
    private android.view.TextureView previewTex;
    private LinearLayout errBox;

    // state
    private boolean controlsShown = true, locked, showRemaining, userRotated, autoOriented, inPip, wasInPip, stopAfterThis, pendingResumeCheck;
    private int resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
    private float speed = 1f, heldSpeedBefore = 1f;
    private boolean longPressing;
    private ImageView bigPlay;
    private boolean awaitFrame;
    private float flingVx;
    private final Runnable revealVideo = () -> { awaitFrame = false; pv.animate().cancel(); pv.animate().translationX(0).alpha(1f).setDuration(300).setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start(); };
    private long sleepAt;
    private LoudnessEnhancer boost;
    private int boostPct;                // 0..100 extra
    private AudioManager am;
    private final ExecutorService frames = Executors.newSingleThreadExecutor();
    private final AtomicLong wantFrame = new AtomicLong(-1);
    private MediaMetadataRetriever retriever;
    private String retrieverUri;
    private int seekStreakSide;          // -1 left, 1 right, 0 none
    private long seekStreakUntil, seekStreakTotal;

    // ------------------------------------------------------------------ start

    static void start(Activity from, JSONArray list, int index) {
        if (list == null || list.length() == 0) return;
        // keep the intent small: at most 400 items around the chosen one
        int s = Math.max(0, index - 200), e = Math.min(list.length(), s + 400);
        JSONArray cut = new JSONArray();
        for (int i = s; i < e; i++) cut.put(list.opt(i));
        Intent i = new Intent(from, PlayerActivity.class).putExtra("items", cut.toString()).putExtra("index", index - s);
        from.startActivity(i);
    }

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("player", MODE_PRIVATE);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        resizeMode = prefs.getInt("resize", AspectRatioFrameLayout.RESIZE_MODE_FIT);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) w.getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);
        buildUi();
        hideSystemUi();
        MusicHub.pauseIfPlaying();
        load(getIntent());
        registerPipReceiver();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        saveResume();
        userRotated = false; autoOriented = false;
        load(i);
    }

    private void load(Intent in) {
        items.clear(); subs.clear();
        int index = in.getIntExtra("index", 0);
        try {
            if (in.hasExtra("items")) {
                JSONArray arr = new JSONArray(in.getStringExtra("items"));
                for (int i = 0; i < arr.length(); i++) items.add(arr.getJSONObject(i));
            } else if (in.getData() != null) {      // "Open with Danav" from a file manager
                items.add(new JSONObject().put("uri", in.getData().toString()).put("title", displayName(in.getData())));
            }
        } catch (Exception ignored) {}
        if (items.isEmpty()) { finish(); return; }
        for (int i = 0; i < items.size(); i++) subs.add(null);
        // private-folder videos: no screenshots, no preview in the recent-apps screen
        boolean priv = false;
        for (JSONObject o : items) if (o.optString("uri").startsWith("vault:")) priv = true;
        if (priv) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        index = Math.max(0, Math.min(items.size() - 1, index));
        if (player instanceof VlcPlayer) { player.removeListener(listener); player.release(); player = null; }
        if (player == null) createPlayer();
        List<MediaItem> list = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) list.add(mediaItem(i));
        long start = resumePos(items.get(index).optString("uri"));
        player.setMediaItems(list, index, Math.max(0, start));
        player.prepare();
        player.play();
        if (start > 0) showResumeChip(start);
        updateTitle();
        updateNav();
        showControls(true);
    }

    private MediaItem mediaItem(int i) {
        JSONObject o = items.get(i);
        Uri u = Uri.parse(o.optString("uri"));
        MediaItem.Builder b = new MediaItem.Builder().setUri(u).setMediaId(o.optString("uri"))
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(o.optString("title")).build());
        Uri s = subs.get(i);
        if (s != null) {
            String n = String.valueOf(displayName(s)).toLowerCase(Locale.ROOT);
            String mime = n.endsWith(".vtt") ? MimeTypes.TEXT_VTT : n.endsWith(".ass") || n.endsWith(".ssa") ? MimeTypes.TEXT_SSA
                    : n.endsWith(".ttml") || n.endsWith(".xml") || n.endsWith(".dfxp") ? MimeTypes.APPLICATION_TTML : MimeTypes.APPLICATION_SUBRIP;
            b.setSubtitleConfigurations(Collections.singletonList(new MediaItem.SubtitleConfiguration.Builder(s)
                    .setMimeType(mime).setLabel(displayName(s)).setSelectionFlags(C.SELECTION_FLAG_DEFAULT | C.SELECTION_FLAG_FORCED).build()));
        }
        return b.build();
    }

    private void createPlayer() {
        DefaultExtractorsFactory ex = new DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true);
        ExoPlayer exo = new ExoPlayer.Builder(this, MusicService.renderers(this))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new VaultDataSource.Factory(this), ex))
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000)
                .build();
        player = exo;
        pv.setShutterBackgroundColor(Color.BLACK);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguage(Locale.getDefault().getLanguage()).setPreferredTextLanguage(Locale.getDefault().getLanguage())
                .build());
        pv.setPlayer(player);
        if (listener == null) listener = makeListener();
        player.addListener(listener);
        pv.setResizeMode(resizeMode);
        applySubtitleStyle();
    }

    private static boolean decoderProblem(PlaybackException e) {
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED:
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED:
            case PlaybackException.ERROR_CODE_UNSPECIFIED:
            case PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK:
                return true;
        }
        return false;
    }

    /** Re-opens the same queue/position with libVLC (FFmpeg software decoders). */
    private boolean switchToVlc() { return switchToVlc(-1, "Switched to the compatibility decoder"); }

    private boolean switchToVlc(long at, String msg) {
        if (player == null || player instanceof VlcPlayer) return false;
        if (currentUri().startsWith("vault:")) return false;      // private files are decrypted by the system player only
        int idx = Math.max(0, player.getCurrentMediaItemIndex());
        long pos = at >= 0 ? at : Math.max(0, player.getCurrentPosition());
        boolean play = player.getPlayWhenReady() || player.getPlaybackState() == Player.STATE_IDLE;
        int rep = player.getRepeatMode();
        player.removeListener(listener);
        player.release();
        VlcPlayer v;
        try { v = new VlcPlayer(this); } catch (Throwable t) { player = null; createPlayer(); return false; }
        player = v;
        pv.setShutterBackgroundColor(Color.TRANSPARENT);
        pv.setPlayer(v);
        v.addListener(listener);
        v.setRepeatMode(rep);
        v.setPlaybackParameters(new PlaybackParameters(speed));
        List<MediaItem> list = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) list.add(mediaItem(i));
        v.setMediaItems(list, idx, pos);
        v.prepare();
        v.setPlayWhenReady(play);
        Uri s = idx < subs.size() ? subs.get(idx) : null;
        if (s != null) h.postDelayed(() -> { if (player == v) v.addSubtitle(s); }, 800);
        errBox.setVisibility(View.GONE);
        setBuffering(true);
        if (msg != null) flash(msg);
        return true;
    }

    /**
     * Every seek goes through here. Some MKV/AVI files have no seek index, so the system player can only
     * restart them from 0 (the timeline jumped back). Those are re-opened with libVLC at the wanted time.
     */
    private void seekTo(long pos) {
        if (player == null) return;
        if (player instanceof ExoPlayer && !player.isCurrentMediaItemLive()) {
            androidx.media3.common.Timeline tl = player.getCurrentTimeline();
            int i = player.getCurrentMediaItemIndex();
            if (!tl.isEmpty() && i >= 0 && i < tl.getWindowCount()) {
                androidx.media3.common.Timeline.Window w = tl.getWindow(i, new androidx.media3.common.Timeline.Window());
                if (!w.isPlaceholder && !w.isSeekable && switchToVlc(pos, null)) { timeline.setPosition(pos); return; }
            }
        }
        player.seekTo(pos);
    }

    private Player.Listener makeListener() {
        return new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean playing) {
                updatePlay();
                if (playing) { scheduleHide(); tick.run(); } else { h.removeCallbacks(hide); saveResume(); }
                pv.setKeepScreenOn(playing);
                updatePipActions();
            }
            @Override public void onPlaybackStateChanged(int state) {
                setBuffering(state == Player.STATE_BUFFERING);
                if (state == Player.STATE_READY) { errBox.setVisibility(View.GONE); h.postDelayed(() -> { if (player != null) ensureStrip(); }, 1500); timeline.setDuration(Math.max(0, player.getDuration())); updateTimes(); }
                if (state == Player.STATE_ENDED) { clearResume(currentUri()); showControls(true); updatePlay(); if (stopAfterThis) { stopAfterThis = false; } }
            }
            @Override public void onMediaItemTransition(MediaItem item, int reason) {
                updateTitle(); updateNav(); audioWarned = false; errBox.setVisibility(View.GONE);   // orientation is kept when moving to another video
                // hide the picture until the new video's first frame is drawn — no stretched / wrong-ratio flash
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && !awaitFrame) { awaitFrame = true; pv.animate().cancel(); pv.setAlpha(0f); h.removeCallbacks(revealVideo); h.postDelayed(revealVideo, 900); }
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && stopAfterThis) { stopAfterThis = false; player.pause(); }
                retrieverUri = null;
                releasePreviewPlayer.run();
                stripGen.incrementAndGet(); stripUri = null; strip.clear();
            }
            @Override public void onVideoSizeChanged(@NonNull VideoSize vs) {
                // the video's shape never rotates the screen — only turning the phone (or the rotate button) does
                updatePipParams();
            }
            @Override public void onPlayerError(@NonNull PlaybackException e) {
                if (decoderProblem(e) && switchToVlc()) return;
                showError(describe(e));
            }
            @Override public void onTracksChanged(@NonNull Tracks t) { checkTracks(t); }
            @Override public void onRenderedFirstFrame() { if (awaitFrame) { h.removeCallbacks(revealVideo); h.postDelayed(revealVideo, 30); } }
            @Override public void onAudioSessionIdChanged(int id) { setupBoost(id); }
            @Override public void onRepeatModeChanged(int m) { updateRepeat(); }
        };
    }

    // ------------------------------------------------------------------ UI

    private ImageView icon(int res, int sizeDp, String desc) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setContentDescription(desc);
        int pad = dp((48 - sizeDp) / 2f);
        if (sizeDp > 30) pad = dp(10);
        v.setPadding(pad, pad, pad, pad);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setBackground(pressBg());
        v.setColorFilter(Color.WHITE);
        return v;
    }

    /** Round, borderless-style ripple for icon buttons. */
    private android.graphics.drawable.Drawable pressBg() {
        GradientDrawable mask = new GradientDrawable(); mask.setShape(GradientDrawable.OVAL); mask.setColor(Color.WHITE);
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x38FFFFFF), null, mask);
    }

    /** Rectangular ripple for list rows. */
    private android.graphics.drawable.Drawable rowBg() {
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x24FFFFFF), null, new android.graphics.drawable.ColorDrawable(Color.WHITE));
    }

    /** Rounded rectangle (radius in dp). */
    private GradientDrawable rr(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radiusDp)); return g;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(color); return g;
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    private FrameLayout.LayoutParams flp(int w, int hgt, int gravity) { FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, hgt); p.gravity = gravity; return p; }

    @SuppressLint("ClickableViewAccessibility")
    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        pv = new PlayerView(this);
        pv.setUseController(false);
        pv.setShutterBackgroundColor(Color.BLACK);
        pv.setKeepContentOnPlayerReset(true);
        root.addView(pv, new FrameLayout.LayoutParams(-1, -1));

        // gestures on a transparent layer under the controls
        View gestures = new View(this);
        root.addView(gestures, new FrameLayout.LayoutParams(-1, -1));
        setupGestures(gestures);

        // double-tap seek feedback (arc + chevrons + counter)
        skip = new SkipRipple(this);
        root.addView(skip, new FrameLayout.LayoutParams(-1, -1));

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        spinner.setVisibility(View.GONE);

        // ---------- controls
        controls = new FrameLayout(this);
        scrim = new View(this);
        scrim.setBackgroundColor(0x52000000);
        controls.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        View tg = new View(this);
        tg.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99000000, 0x00000000}));
        controls.addView(tg, flp(-1, dp(120), Gravity.TOP));
        View bg = new View(this);
        bg.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB3000000, 0x00000000}));
        controls.addView(bg, flp(-1, dp(170), Gravity.BOTTOM));

        // ---- top: back · title · rotate · pip · more   (Netflix-style: big, simple, white icons)
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float minDp = Math.min(dm.widthPixels, dm.heightPixels) / dm.density;
        ui = Math.max(0.85f, Math.min(1.2f, minDp / 390f));
        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(4), dp(8), dp(4), dp(4));
        ImageView back = icon(R.drawable.ic_back, 26, "Back");
        back.setOnClickListener(v -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.setPadding(dp(4), 0, dp(8), 0);
        title = text(17, Color.WHITE, true);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        subtitle = text(12.5f, 0xB3FFFFFF, false);
        subtitle.setSingleLine(true); subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setPadding(0, dp(4), 0, 0);
        tcol.addView(title); tcol.addView(subtitle);
        topBar.addView(tcol, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView rotB = icon(R.drawable.ic_rotate, 26, "Rotate");
        rotB.setOnClickListener(v -> rotate());
        ImageView pipB = icon(R.drawable.ic_pip, 26, "Picture in picture");
        pipB.setOnClickListener(v -> enterPip());
        if (Build.VERSION.SDK_INT < 26 || !getPackageManager().hasSystemFeature("android.software.picture_in_picture")) pipB.setVisibility(View.GONE);
        ImageView moreBtn = icon(R.drawable.ic_more, 26, "More");
        moreBtn.setOnClickListener(v -> morePanel());
        for (View v : new View[]{rotB, pipB, moreBtn}) topBar.addView(v, new LinearLayout.LayoutParams(dp(52), dp(52)));
        controls.addView(topBar, flp(-1, -2, Gravity.TOP));

        // ---- centre (landscape only): previous · play/pause · next   (portrait: just a play icon while paused)
        centerRow = new LinearLayout(this);
        centerRow.setGravity(Gravity.CENTER);
        rewBtn = icon(R.drawable.ic_prev, 40, "Previous video");
        ffBtn = icon(R.drawable.ic_next, 40, "Next video");
        playBtn = icon(R.drawable.ic_pause, 64, "Play / pause");
        for (ImageView iv : new ImageView[]{rewBtn, ffBtn}) iv.setPadding(dp(12 * ui), dp(12 * ui), dp(12 * ui), dp(12 * ui));
        playBtn.setPadding(dp(6 * ui), dp(6 * ui), dp(6 * ui), dp(6 * ui));
        rewBtn.setOnClickListener(v -> { pulse(rewBtn); if (player.hasPreviousMediaItem()) changeVideo(false); else { seekTo(0); scheduleHide(); } });
        ffBtn.setOnClickListener(v -> { pulse(ffBtn); if (player.hasNextMediaItem()) changeVideo(true); });
        playBtn.setOnClickListener(v -> { togglePlay(); pulse(playBtn); });
        int gap = dp(56 * ui);
        centerRow.addView(rewBtn, lp(Math.round(64 * ui), Math.round(64 * ui), gap));
        centerRow.addView(playBtn, lp(Math.round(84 * ui), Math.round(84 * ui), gap));
        centerRow.addView(ffBtn, lp(Math.round(64 * ui), Math.round(64 * ui), gap));
        controls.addView(centerRow, flp(-2, -2, Gravity.CENTER));
        // kept off-screen: the queue / swipe / media keys handle previous; repeat lives in More
        prevBtn = new ImageView(this);
        repeatBtn = new ImageView(this);

        // ---- bottom: red timeline + time left, then labelled actions
        bottomBox = new LinearLayout(this);
        bottomBox.setOrientation(LinearLayout.VERTICAL);
        bottomBox.setPadding(dp(12), 0, dp(12), dp(6));
        LinearLayout trow = new LinearLayout(this);
        trow.setGravity(Gravity.CENTER_VERTICAL);
        posText = text(13, Color.WHITE, true);
        posText.setVisibility(View.GONE);
        durText = text(13.5f, Color.WHITE, true);
        durText.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        durText.setMinWidth(dp(52));
        durText.setPadding(dp(10), dp(10), dp(4), dp(10));
        durText.setOnClickListener(v -> { showRemaining = !showRemaining; updateTimes(); });
        showRemaining = true;
        timeline = new TimelineView(this);
        timeline.setListener(new TimelineView.Listener() {
            @Override public void onScrubStart() {
                h.removeCallbacks(hide);
                sizePreview();
                scrubWasPlaying = player.getPlayWhenReady();
                h.removeCallbacks(releasePreviewPlayer);
                ensurePreviewPlayer();
                preview.setVisibility(View.VISIBLE);
                preview.setAlpha(0f); preview.setScaleX(0.92f); preview.setScaleY(0.92f);
                preview.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).setInterpolator(new DecelerateInterpolator()).start();
                centerRow.animate().alpha(0f).setDuration(120).start();
                updateBigPlay();
                ensureStrip();
            }
            @Override public void onScrub(long pos) {
                // the video itself stays put while dragging — only the preview box follows the finger
                posText.setText(fmt(pos)); showPreview(pos);
                long d = Math.max(0, player.getDuration());
                if (d > 0) durText.setText(showRemaining ? fmt(d - pos) : fmt(pos) + " / " + fmt(d));
            }
            @Override public void onScrubStop(long pos, boolean canceled) {
                h.removeCallbacks(exactFrame);
                preview.animate().alpha(0f).setDuration(120).withEndAction(() -> preview.setVisibility(View.GONE)).start();
                centerRow.animate().alpha(1f).setDuration(160).start();
                h.post(() -> updateBigPlay());
                if (player instanceof ExoPlayer) ((ExoPlayer) player).setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT);
                if (!canceled) seekTo(pos);
                h.removeCallbacks(ppTrail);
                h.postDelayed(releasePreviewPlayer, 8000);
                scheduleHide();
            }
        });
        trow.addView(posText, new LinearLayout.LayoutParams(-2, -2));
        trow.addView(timeline, new LinearLayout.LayoutParams(0, dp(40), 1));
        trow.addView(durText, new LinearLayout.LayoutParams(-2, -2));
        bottomBox.addView(trow);

        actionRow = new LinearLayout(this);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout speedB = action(R.drawable.ic_speed, "Speed (1×)", v -> speedPanel());
        speedBtnText = (TextView) speedB.getChildAt(1);
        LinearLayout lockB = action(R.drawable.ic_lock, "Lock", v -> setLocked(true));
        LinearLayout fitB = action(R.drawable.ic_aspect, "Fit", v -> cycleResize());
        fitText = (TextView) fitB.getChildAt(1);
        LinearLayout audB = action(R.drawable.ic_audio, "Audio", v -> audioPanel());
        LinearLayout subB = action(R.drawable.ic_subs, "Subtitles", v -> subsPanel());
        LinearLayout queueB = action(R.drawable.ic_queue, "Playlist", v -> queuePanel());
        queueB.setTag("queue");
        nextAction = action(R.drawable.ic_next, "Next", v -> { if (player.hasNextMediaItem()) changeVideo(true); });
        nextBtn = (ImageView) nextAction.getChildAt(0);
        for (View v : new View[]{speedB, lockB, fitB, audB, subB, queueB, nextAction}) actionRow.addView(v, new LinearLayout.LayoutParams(0, dp(52), 1));
        bottomBox.addView(actionRow);
        // labels only when there's room (landscape / tablets); icons alone on narrow portrait screens
        actionRow.addOnLayoutChangeListener((v, l, t, r, b2, ol, ot, or, ob) -> {
            if (r - l == or - ol) return;
            final boolean show = (r - l) / getResources().getDisplayMetrics().density >= 520;
            h.post(() -> setActionLabels(show));
        });
        controls.addView(bottomBox, flp(-1, -2, Gravity.BOTTOM));
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));
        root.addView(spinner, flp(dp(56), dp(56), Gravity.CENTER));
        // portrait: tap the middle of the video = pause, and only this play icon appears; tap it = play, it goes away
        bigPlay = new ImageView(this);
        bigPlay.setImageResource(R.drawable.ic_play);
        bigPlay.setColorFilter(Color.WHITE);
        bigPlay.setContentDescription("Play");
        bigPlay.setPadding(dp(18), dp(18), dp(18), dp(18));
        bigPlay.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40FFFFFF), oval(0x59000000), null));
        bigPlay.setVisibility(View.GONE);
        bigPlay.setOnClickListener(v -> { togglePlay(false); });
        root.addView(bigPlay, flp(dp(84), dp(84), Gravity.CENTER));

        // seek preview (frame + time) above the timeline
        preview = new FrameLayout(this);
        LinearLayout pcol = new LinearLayout(this);
        pcol.setOrientation(LinearLayout.VERTICAL);
        pcol.setGravity(Gravity.CENTER_HORIZONTAL);
        pframe = new FrameLayout(this);
        pframe.setBackgroundColor(0xFF000000);          // just the frame — no border, no rounded box
        previewImg = new ImageView(this);
        previewImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pframe.addView(previewImg, new FrameLayout.LayoutParams(-1, -1));
        previewTex = new android.view.TextureView(this);
        previewTex.setAlpha(0f);
        pframe.addView(previewTex, new FrameLayout.LayoutParams(-1, -1));
        previewImg.setElevation(0);
        previewTime = text(12, Color.WHITE, false);
        previewTime.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));   // thin time under the frame
        previewTime.setLetterSpacing(0.04f);
        previewTime.setShadowLayer(dp(3), 0, 0, 0x99000000);
        previewTime.setPadding(0, 0, 0, 0);
        pcol.addView(pframe, new LinearLayout.LayoutParams(dp(176), dp(99)));
        LinearLayout.LayoutParams ptl = new LinearLayout.LayoutParams(-2, -2); ptl.topMargin = dp(4);
        pcol.addView(previewTime, ptl);
        preview.addView(pcol);
        preview.setVisibility(View.GONE);
        root.addView(preview, flp(-2, -2, Gravity.BOTTOM | Gravity.START));

        // centre info (brightness / volume / seek / zoom)
        infoBox = new LinearLayout(this);
        infoBox.setGravity(Gravity.CENTER_VERTICAL);
        infoBox.setPadding(dp(18), dp(12), dp(20), dp(12));
        infoBox.setBackground(rr(GLASS, 8));
        infoBox.setElevation(0);
        infoIcon = new ImageView(this);
        infoIcon.setColorFilter(Color.WHITE);
        infoBox.addView(infoIcon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        FrameLayout barWrap = new FrameLayout(this);
        infoBar = barWrap;
        barWrap.setBackground(rr(0x40FFFFFF, 3));
        barWrap.setClipToOutline(true);
        infoBarFill = new View(this);
        infoBarFill.setBackground(rr(Color.WHITE, 3));
        barWrap.addView(infoBarFill, new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams bwp = new LinearLayout.LayoutParams(dp(130), dp(5)); bwp.setMargins(dp(12), 0, dp(12), 0);
        infoBox.addView(barWrap, bwp);
        infoText = text(15, Color.WHITE, true);
        infoText.setMinWidth(dp(44));
        infoBox.addView(infoText);
        infoBox.setVisibility(View.GONE);
        root.addView(infoBox, flp(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP));

        speedPill = text(14, Color.WHITE, true);
        speedPill.setText("2×  ▶▶");
        speedPill.setPadding(dp(16), dp(9), dp(16), dp(9));
        speedPill.setBackground(rr(GLASS, 6));
        speedPill.setElevation(0);
        speedPill.setVisibility(View.GONE);
        root.addView(speedPill, flp(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP));

        // resume chip
        resumeChip = new LinearLayout(this);
        resumeChip.setGravity(Gravity.CENTER_VERTICAL);
        resumeChip.setBackground(rr(0xEB1A1B24, 8));
        resumeChip.setPadding(dp(16), dp(4), dp(4), dp(4));
        resumeChip.setElevation(0);
        resumeChip.setVisibility(View.GONE);
        root.addView(resumeChip, flp(-2, -2, Gravity.BOTTOM | Gravity.START));

        // error
        errBox = new LinearLayout(this);
        errBox.setOrientation(LinearLayout.VERTICAL);
        errBox.setGravity(Gravity.CENTER_HORIZONTAL);
        errBox.setPadding(dp(24), dp(22), dp(24), dp(18));
        errBox.setBackground(rr(0xF21A1B24, 10));
        errBox.setElevation(0);
        errText = text(15, Color.WHITE, false);
        errText.setGravity(Gravity.CENTER);
        errText.setLineSpacing(0, 1.25f);
        errBox.addView(errText, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout ebtns = new LinearLayout(this);
        ebtns.setPadding(0, dp(16), 0, 0);
        TextView ow = button("Open with…", true);
        ow.setOnClickListener(v -> openWith());
        TextView skip = button("Next", false);
        skip.setOnClickListener(v -> { if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); player.prepare(); player.play(); } else finish(); });
        ebtns.addView(ow); ebtns.addView(skip);
        errBox.addView(ebtns);
        errBox.setVisibility(View.GONE);
        root.addView(errBox, flp(dp(340), -2, Gravity.CENTER));

        // lock overlay
        lockOverlay = new FrameLayout(this);
        lockBtn = icon(R.drawable.ic_lock, 24, "Unlock");
        lockBtn.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x40FFFFFF), oval(0xB31A1B24), null));
        lockBtn.setOnClickListener(v -> setLocked(false));
        LinearLayout lcol = new LinearLayout(this);
        lcol.setOrientation(LinearLayout.VERTICAL);
        lcol.setGravity(Gravity.CENTER_HORIZONTAL);
        lcol.addView(lockBtn, new LinearLayout.LayoutParams(dp(52), dp(52)));
        lockOverlay.addView(lcol, flp(-2, -2, Gravity.CENTER_VERTICAL | Gravity.START));
        lockOverlay.setPadding(dp(24), 0, 0, 0);
        lockOverlay.setVisibility(View.GONE);
        root.addView(lockOverlay, new FrameLayout.LayoutParams(-1, -1));
        lockOverlay.setClickable(false);

        // side / bottom panel (tracks, speed, more)
        panel = new FrameLayout(this);
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l = 0, t = 0, r = 0, btm = 0;
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                l = insets.getDisplayCutout().getSafeInsetLeft(); r = insets.getDisplayCutout().getSafeInsetRight();
                t = insets.getDisplayCutout().getSafeInsetTop(); btm = insets.getDisplayCutout().getSafeInsetBottom();
            }
            topBar.setPadding(dp(4) + l, dp(8) + t, dp(4) + r, dp(4));
            bottomBox.setPadding(dp(12) + l, 0, dp(12) + r, dp(6) + btm);
            return insets;
        });
        updateRepeat();
        applyOrientationUi();
    }

    private LinearLayout actionRow, nextAction;
    private TextView fitText;
    private boolean labelsShown = true;

    /** Labelled action (icon + text) in the bottom row. */
    private LinearLayout action(int iconRes, String label, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(label);
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x30FFFFFF), null, rr(Color.WHITE, 6)));
        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        ic.setColorFilter(Color.WHITE);
        b.addView(ic, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = text(13.5f, Color.WHITE, true);
        t.setText(label);
        t.setSingleLine(true);
        t.setPadding(dp(8), 0, 0, 0);
        b.addView(t);
        b.setOnClickListener(v -> { click.onClick(v); pulse(ic); });
        return b;
    }

    private void setActionLabels(boolean show) {
        if (actionRow == null || show == labelsShown) return;
        labelsShown = show;
        for (int i = 0; i < actionRow.getChildCount(); i++) {
            View c = actionRow.getChildAt(i);
            if (c instanceof LinearLayout && ((LinearLayout) c).getChildCount() > 1) ((LinearLayout) c).getChildAt(1).setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    /** Quarter-turn nudge on the ±10 buttons. */
    private void spin(View v, int dir) {
        v.animate().cancel();
        v.setRotation(0);
        v.animate().rotation(dir * 30f).setDuration(110).withEndAction(() -> v.animate().rotation(0).setDuration(170).setInterpolator(new DecelerateInterpolator()).start()).start();
    }

    private LinearLayout.LayoutParams lp(int w, int hh, int margin) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(w), dp(hh)); p.setMargins(margin / 2, 0, margin / 2, 0); return p; }

    private TextView button(String label, boolean primary) {
        TextView t = text(14, primary ? Color.BLACK : Color.WHITE, true);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(11), dp(18), dp(11));
        if (primary) t.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), rr(Color.WHITE, 6), null));
        else { GradientDrawable g = rr(0x1AFFFFFF, 6); g.setStroke(dp(1), 0x40FFFFFF); t.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), g, null)); }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.setMargins(dp(5), 0, dp(5), 0);
        t.setLayoutParams(p);
        return t;
    }

    private TextView rippleView(String label, int iconRes) {
        TextView t = text(15, Color.WHITE, true);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setCompoundDrawablesWithIntrinsicBounds(0, iconRes, 0, 0);
        t.setCompoundDrawablePadding(dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{0x33FFFFFF, 0x14FFFFFF, 0x00FFFFFF});
        g.setGradientRadius(dp(300));
        g.setGradientCenter(label.startsWith("+") ? 1f : 0f, 0.5f);
        t.setBackground(g);
        t.setShadowLayer(dp(6), 0, 0, 0x66000000);
        t.setAlpha(0f);
        return t;
    }

    // small spinner that takes the play icon's place, only if loading lasts longer than 300 ms
    private boolean buffering;
    private final Runnable showSpin = () -> { if (buffering) { spinner.setVisibility(View.VISIBLE); playBtn.setImageAlpha(0); } };
    private void setBuffering(boolean b) {
        if (b == buffering) return;
        buffering = b;
        h.removeCallbacks(showSpin);
        if (b) h.postDelayed(showSpin, 300);
        else { spinner.setVisibility(View.GONE); playBtn.setImageAlpha(255); }
    }

    private void pulse(View v) {
        v.animate().cancel();
        v.setScaleX(0.86f); v.setScaleY(0.86f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
    }

    // ------------------------------------------------------------------ controls visibility

    private final Runnable hide = () -> { if (player != null && player.isPlaying() && panel.getVisibility() != View.VISIBLE && !timeline.isScrubbing()) showControls(false); };

    private void scheduleHide() { h.removeCallbacks(hide); h.postDelayed(hide, 3500); }

    private void showControls(boolean show) {
        if (locked || inPip) show = false;
        controlsShown = show;
        controls.animate().cancel(); topBar.animate().cancel(); bottomBox.animate().cancel();
        android.view.animation.Interpolator ease = new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);
        if (show) {
            if (controls.getVisibility() != View.VISIBLE) {
                controls.setAlpha(0f);
                topBar.setTranslationY(-dp(10)); bottomBox.setTranslationY(dp(10));
                controls.setVisibility(View.VISIBLE);
            }
            controls.animate().alpha(1f).setDuration(200).setInterpolator(ease).start();
            topBar.animate().translationY(0).setDuration(240).setInterpolator(ease).start();
            bottomBox.animate().translationY(0).setDuration(240).setInterpolator(ease).start();
            scheduleHide();
            updateTimes();
            tick.run();
        } else {
            controls.animate().alpha(0f).setDuration(220).setInterpolator(ease).withEndAction(() -> { if (!controlsShown) controls.setVisibility(View.GONE); }).start();
            topBar.animate().translationY(-dp(8)).setDuration(220).setInterpolator(ease).start();
            bottomBox.animate().translationY(dp(8)).setDuration(220).setInterpolator(ease).start();
        }
    }

    private void toggleControls() { showControls(!controlsShown); }

    private int orientBeforeLock = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    private void setLocked(boolean l) {
        locked = l;
        if (l) {
            orientBeforeLock = getRequestedOrientation();
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            showControls(false); flashLock(); updateBigPlay();
        } else {
            setRequestedOrientation(orientBeforeLock);
            lockOverlay.setVisibility(View.GONE); showControls(true); updateBigPlay();
        }
    }

    private void flashLock() {
        lockOverlay.setVisibility(View.VISIBLE);
        lockOverlay.setAlpha(1f);
        h.removeCallbacks(hideLock);
        h.postDelayed(hideLock, 2500);
    }

    private final Runnable hideLock = () -> lockOverlay.animate().alpha(0f).setDuration(250).withEndAction(() -> { if (locked) lockOverlay.setVisibility(View.GONE); }).start();

    private void hideSystemUi() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideSystemUi(); }

    // ------------------------------------------------------------------ playback helpers

    private void togglePlay() { togglePlay(true); }

    private void togglePlay(boolean showUi) {
        if (player.isPlaying()) player.pause();
        else {
            if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(player.getCurrentMediaItemIndex(), 0);
            if (player.getPlaybackState() == Player.STATE_IDLE) player.prepare();
            player.play();
        }
        if (showUi) showControls(true);
        updateBigPlay();
    }

    private boolean portrait() { return getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE; }

    /** Portrait has no centre buttons; landscape shows previous · play · next. */
    private void applyOrientationUi() {
        if (centerRow == null) return;
        centerRow.setVisibility(portrait() ? View.GONE : View.VISIBLE);
        updateBigPlay();
    }

    private void updateBigPlay() {
        if (bigPlay == null) return;
        boolean paused = player != null && (!player.getPlayWhenReady() || player.getPlaybackState() == Player.STATE_ENDED);
        boolean show = portrait() && paused && !locked && !inPip && errBox.getVisibility() != View.VISIBLE
                && (timeline == null || !timeline.isScrubbing()) && panel.getVisibility() != View.VISIBLE;
        bigPlay.setImageResource(player != null && player.getPlaybackState() == Player.STATE_ENDED ? R.drawable.ic_retry : R.drawable.ic_play);
        boolean vis = bigPlay.getVisibility() == View.VISIBLE && bigPlay.getAlpha() > 0.5f;
        if (show == vis) return;
        bigPlay.animate().cancel();
        if (show) {
            bigPlay.setVisibility(View.VISIBLE); bigPlay.setAlpha(0f); bigPlay.setScaleX(0.8f); bigPlay.setScaleY(0.8f);
            bigPlay.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
        } else {
            bigPlay.animate().alpha(0f).scaleX(1.15f).scaleY(1.15f).setDuration(160).withEndAction(() -> bigPlay.setVisibility(View.GONE)).start();
        }
    }

    @Override public void onConfigurationChanged(@NonNull Configuration c) {
        super.onConfigurationChanged(c);
        applyOrientationUi();
    }

    private void seekBy(long ms) {
        long d = player.getDuration();
        long p = Math.max(0, player.getCurrentPosition() + ms);
        if (d > 0) p = Math.min(p, d - 500);
        seekTo(p);
        updateTimes();
    }

    private void updatePlay() {
        updateBigPlay();
        boolean playing = player != null && player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED;
        playBtn.setImageResource(player != null && player.getPlaybackState() == Player.STATE_ENDED ? R.drawable.ic_retry : playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void updateNav() {
        boolean many = items.size() > 1;
        nextAction.setVisibility(many ? View.VISIBLE : View.GONE);
        View q = root.findViewWithTag("queue");
        if (q != null) q.setVisibility(many ? View.VISIBLE : View.GONE);
        if (many) { boolean n = player.hasNextMediaItem(); nextAction.setAlpha(n ? 1f : 0.4f); nextAction.setEnabled(n); }
        boolean hn = player.hasNextMediaItem(), hp = player.hasPreviousMediaItem();
        ffBtn.setAlpha(hn ? 1f : 0.35f); ffBtn.setEnabled(hn);
        rewBtn.setAlpha(many || hp ? 1f : 0.35f);
    }

    private void updateRepeat() {
        int m = player == null ? 0 : player.getRepeatMode();
        repeatBtn.setImageResource(m == Player.REPEAT_MODE_ONE ? R.drawable.ic_repeat1 : R.drawable.ic_repeat);
        repeatBtn.setColorFilter(m == 0 ? 0x99FFFFFF : ACCENT);
    }

    private JSONObject current() { int i = player == null ? 0 : player.getCurrentMediaItemIndex(); return i >= 0 && i < items.size() ? items.get(i) : new JSONObject(); }
    private String currentUri() { return current().optString("uri"); }

    private void updateTitle() {
        JSONObject o = current();
        String t = o.optString("title");
        if (t.isEmpty()) t = displayName(Uri.parse(o.optString("uri")));
        title.setText(t);
        String sub = o.optString("sub");
        if (items.size() > 1) sub = (player.getCurrentMediaItemIndex() + 1) + " of " + items.size() + (sub.isEmpty() ? "" : " · " + sub);
        subtitle.setText(sub);
        subtitle.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            h.removeCallbacks(this);
            if (player == null) return;
            updateTimes();
            if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) { sleepAt = 0; player.pause(); flash("Sleep timer: paused"); }
            if (player.isPlaying() || sleepAt > 0) h.postDelayed(this, controlsShown ? 250 : 1000);
        }
    };

    private void updateTimes() {
        if (player == null) return;
        long d = Math.max(0, player.getDuration()), p = Math.max(0, player.getCurrentPosition());
        if (player.getDuration() == C.TIME_UNSET) d = 0;
        timeline.setDuration(d);
        timeline.setPosition(p);
        timeline.setBuffered(player.getBufferedPosition());
        if (!timeline.isScrubbing()) posText.setText(fmt(p));
        durText.setText(d <= 0 ? "--:--" : showRemaining ? fmt(d - p) : fmt(p) + " / " + fmt(d));
        if (player.isCurrentMediaItemLive()) durText.setText("LIVE");
    }

    static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        long hh = s / 3600, m = (s % 3600) / 60, x = s % 60;
        return hh > 0 ? String.format(Locale.US, "%d:%02d:%02d", hh, m, x) : String.format(Locale.US, "%d:%02d", m, x);
    }

    private void flash(String msg) { showInfo(0, msg, -1); h.removeCallbacks(hideInfo); h.postDelayed(hideInfo, 1200); }

    private void showInfo(int iconRes, String t, float frac) {
        infoBox.setVisibility(View.VISIBLE);
        infoBox.animate().cancel();
        infoBox.setAlpha(1f);
        FrameLayout.LayoutParams ilp = (FrameLayout.LayoutParams) infoBox.getLayoutParams();
        int top = Math.max(dp(64), root.getHeight() / 6);
        if (ilp.topMargin != top) { ilp.topMargin = top; infoBox.setLayoutParams(ilp); }
        infoIcon.setVisibility(iconRes == 0 ? View.GONE : View.VISIBLE);
        if (iconRes != 0) infoIcon.setImageResource(iconRes);
        infoBar.setVisibility(frac < 0 ? View.GONE : View.VISIBLE);
        if (frac >= 0) {
            ViewGroup.LayoutParams lp = infoBarFill.getLayoutParams();
            lp.width = Math.round(dp(130) * Math.min(1f, frac));
            infoBarFill.setLayoutParams(lp);
            ((GradientDrawable) infoBarFill.getBackground()).setColor(frac > 1f ? ACCENT : Color.WHITE);
        }
        infoText.setText(t);
    }

    private final Runnable hideInfo = () -> infoBox.animate().alpha(0f).setDuration(250).withEndAction(() -> infoBox.setVisibility(View.GONE)).start();

    // ------------------------------------------------------------------ gestures

    private int gestureMode;     // 0 none, 1 brightness, 2 volume, 4 change video
    private float startBright, startVol;
    private long seekStartPos, seekTarget;
    private float scaleAcc = 1f;

    @SuppressLint("ClickableViewAccessibility")
    private void setupGestures(View layer) {
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(@NonNull MotionEvent e) { flingVx = 0; return true; }
            @Override public boolean onSingleTapUp(@NonNull MotionEvent e) {
                // portrait: the middle of the video is a play / pause button (answers at once)
                if (!locked && portrait() && inCenter(e) && panel.getVisibility() != View.VISIBLE && errBox.getVisibility() != View.VISIBLE) { togglePlay(false); return true; }
                return false;
            }
            @Override public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2, float vx, float vy) { flingVx = vx; return false; }
            @Override public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                if (locked) { flashLock(); return true; }
                int side = sideOf(e.getX());
                if (portrait() && inCenter(e) && panel.getVisibility() != View.VISIBLE && errBox.getVisibility() != View.VISIBLE) return true;   // handled in onSingleTapUp
                if (System.currentTimeMillis() < seekStreakUntil && side == seekStreakSide && side != 0) { doubleTapSeek(side, e.getX(), e.getY()); return true; }
                if (panel.getVisibility() == View.VISIBLE) { closePanel(); return true; }
                toggleControls();
                return true;
            }
            @Override public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (locked) return true;
                int side = sideOf(e.getX());
                if (side == 0) { if (!portrait()) { togglePlay(); pulse(playBtn); } return true; }
                doubleTapSeek(side, e.getX(), e.getY());
                return true;
            }
            @Override public void onLongPress(@NonNull MotionEvent e) {
                if (locked || player == null || !player.isPlaying() || gestureMode != 0) return;
                longPressing = true;
                heldSpeedBefore = speed;
                player.setPlaybackParameters(new PlaybackParameters(Math.max(2f, speed)));
                speedPill.setText(fmtSpeed(Math.max(2f, speed)) + "  ▶▶");
                speedPill.setVisibility(View.VISIBLE);
                FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) speedPill.getLayoutParams();
                if (slp.topMargin != dp(28)) { slp.topMargin = dp(28); speedPill.setLayoutParams(slp); }
                speedPill.setAlpha(0f); speedPill.animate().alpha(1f).setDuration(150).start();
                root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
            @Override public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float dx, float dy) {
                if (locked || e1 == null || longPressing || scaling) return false;
                float tdx = e2.getX() - e1.getX(), tdy = e2.getY() - e1.getY();
                int W = root.getWidth(), H = root.getHeight();
                if (gestureMode == 0) {
                    if (e1.getY() < dp(36) || e1.getY() > H - dp(36)) return false;   // system gesture zones
                    if (Math.abs(tdy) > dp(18) && Math.abs(tdy) > Math.abs(tdx) * 1.3f) {
                        float fx = e1.getX() / W;
                        gestureMode = fx < 0.5f ? 1 : 2;
                        startBright = brightness();
                        startVol = volumePct();
                    } else if (Math.abs(tdx) > dp(24) && Math.abs(tdx) > Math.abs(tdy) * 1.5f) {
                        gestureMode = 4; swipeDx = 0; swipeX0 = tdx;      // horizontal slide = next / previous video
                    } else return false;
                }
                if (gestureMode == 1) {
                    float v = Math.max(0.01f, Math.min(1f, startBright - tdy / (H * 0.7f)));
                    setBrightness(v);
                    showInfo(R.drawable.ic_sun, Math.round(v * 100) + "%", v);
                } else if (gestureMode == 2) {
                    float v = Math.max(0f, Math.min(200f, startVol - tdy / (H * 0.7f) * 100f));
                    setVolumePct(v);
                    showInfo(v == 0 ? R.drawable.ic_mute : R.drawable.ic_volume, Math.round(v) + "%" + (v > 100 ? " boost" : ""), v / 100f);
                } else if (gestureMode == 4) {
                    float x = tdx - swipeX0;
                    swipeDx = x;
                    boolean can = x < 0 ? player.hasNextMediaItem() : player.hasPreviousMediaItem();
                    float tx = can ? x : x * 0.22f;                       // rubber band at the ends of the list
                    pv.setTranslationX(tx);
                    pv.setAlpha(1f - Math.min(0.35f, Math.abs(tx) / W * 0.6f));
                }
                return true;
            }
        });
        gd.setIsLongpressEnabled(true);
        ScaleGestureDetector sg = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(@NonNull ScaleGestureDetector d) { if (locked) return false; scaling = true; scaleAcc = 1f; return true; }
            @Override public boolean onScale(@NonNull ScaleGestureDetector d) {
                scaleAcc *= d.getScaleFactor();
                if (scaleAcc > 1.18f && resizeMode != AspectRatioFrameLayout.RESIZE_MODE_ZOOM) { setResize(AspectRatioFrameLayout.RESIZE_MODE_ZOOM); scaleAcc = 1f; }
                else if (scaleAcc < 0.85f && resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT) { setResize(AspectRatioFrameLayout.RESIZE_MODE_FIT); scaleAcc = 1f; }
                return true;
            }
        });
        layer.setOnTouchListener((v, e) -> {
            sg.onTouchEvent(e);
            if (e.getPointerCount() > 1) { gestureMode = 0; return true; }
            gd.onTouchEvent(e);
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                if (gestureMode == 4) finishSwipe(a == MotionEvent.ACTION_CANCEL ? 0 : swipeDx);
                if (gestureMode != 0) { h.removeCallbacks(hideInfo); h.postDelayed(hideInfo, 450); }
                gestureMode = 0;
                if (longPressing) {
                    longPressing = false;
                    player.setPlaybackParameters(new PlaybackParameters(heldSpeedBefore));
                    speedPill.animate().alpha(0f).setDuration(150).withEndAction(() -> speedPill.setVisibility(View.GONE)).start();
                }
                if (scaling && a == MotionEvent.ACTION_UP) scaling = false;
            }
            return true;
        });
    }

    private boolean scaling;
    private float swipeDx, swipeX0;

    /** Slide left = next video, slide right = previous. The picture follows the finger. */
    private void finishSwipe(float dx) {
        int W = Math.max(1, root.getWidth());
        boolean next = dx < 0;
        // a real slide (about a third of the screen) or a quick flick — a small accidental move snaps back
        boolean far = Math.abs(dx) > W * 0.30f || (Math.abs(flingVx) > dp(900) && Math.signum(flingVx) == Math.signum(dx) && Math.abs(dx) > dp(48));
        boolean can = player != null && (next ? player.hasNextMediaItem() : player.hasPreviousMediaItem());
        if (far && can) { changeVideo(next); return; }
        if (far) flash(items.size() < 2 ? "No other videos in this list" : next ? "This is the last video" : "This is the first video");
        pv.animate().cancel();
        pv.animate().translationX(0).alpha(1f).setDuration(280).setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
    }

    /** Animated move to the next / previous video (swipe and the Next button). */
    private void changeVideo(boolean next) {
        if (player == null || !(next ? player.hasNextMediaItem() : player.hasPreviousMediaItem())) return;
        int W = Math.max(1, root.getWidth());
        pv.animate().cancel();
        // keep the finger's momentum: slide the rest of the way out, then the next video glides in from the other side
        float left = W - Math.abs(pv.getTranslationX());
        long dur = Math.max(120, Math.min(260, Math.round(260 * left / W)));
        pv.animate().translationX(next ? -W : W).alpha(0f).setDuration(dur).setInterpolator(new android.view.animation.PathInterpolator(0.3f, 0f, 0.6f, 1f))
                .withEndAction(() -> {
                    if (player == null) return;
                    awaitFrame = true;                       // keep it hidden until the new video's first frame (right size & ratio)
                    pv.setTranslationX(next ? W * 0.35f : -W * 0.35f);
                    if (next) player.seekToNextMediaItem(); else player.seekToPreviousMediaItem();
                    if (player.getPlaybackState() == Player.STATE_IDLE) player.prepare();
                    player.play();
                    h.removeCallbacks(revealVideo); h.postDelayed(revealVideo, 900);
                    updateBigPlay();
                }).start();
    }

    /** The play / pause spot: the middle of the picture only — taps above or below it just show the controls. */
    private boolean inCenter(MotionEvent e) {
        float W = root.getWidth(), H = root.getHeight();
        float vh = Math.min(H, W * 9f / 16f);
        return Math.abs(e.getX() - W / 2f) < W * 0.17f && Math.abs(e.getY() - H / 2f) < Math.max(dp(56), vh * 0.32f);
    }

    private int sideOf(float x) {
        float W = root.getWidth();
        if (x < W * 0.35f) return -1;
        if (x > W * 0.65f) return 1;
        return 0;
    }

    private void doubleTapSeek(int side, float x, float y) {
        long now = System.currentTimeMillis();
        if (now < seekStreakUntil && side == seekStreakSide) seekStreakTotal += 10; else seekStreakTotal = 10;
        seekStreakSide = side;
        seekStreakUntil = now + 750;
        seekBy(side * 10000L);
        skip.show(side, (int) seekStreakTotal, x, y);
        root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private float brightness() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b >= 0) return b;
        try { return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255f; } catch (Exception e) { return 0.5f; }
    }

    private void setBrightness(float v) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = v;
        getWindow().setAttributes(lp);
    }

    private float volumePct() {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / Math.max(1, max) + boostPct;
    }

    private void setVolumePct(float pct) {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int vol = Math.round(Math.min(100, pct) / 100f * max);
        if (vol != am.getStreamVolume(AudioManager.STREAM_MUSIC)) am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
        int b = pct > 100 ? Math.round(pct - 100) : 0;
        if (b != boostPct) {
            boostPct = b;
            try { if (boost != null) { boost.setTargetGain(boostPct * 12); boost.setEnabled(boostPct > 0); } } catch (Exception ignored) {}
        }
    }

    private void setupBoost(int session) {
        try { if (boost != null) boost.release(); } catch (Exception ignored) {}
        boost = null;
        if (session == C.AUDIO_SESSION_ID_UNSET) return;
        try { boost = new LoudnessEnhancer(session); boost.setTargetGain(boostPct * 12); boost.setEnabled(boostPct > 0); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ resize / rotate / pip

    private void cycleResize() {
        int next = resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM ? AspectRatioFrameLayout.RESIZE_MODE_FILL : AspectRatioFrameLayout.RESIZE_MODE_FIT;
        setResize(next);
        showControls(true);
    }

    private void setResize(int mode) {
        resizeMode = mode;
        pv.setResizeMode(mode);
        prefs.edit().putInt("resize", mode).apply();
        if (fitText != null) fitText.setText(mode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM ? "Zoom" : mode == AspectRatioFrameLayout.RESIZE_MODE_FILL ? "Stretch" : "Fit");
        showInfo(R.drawable.ic_aspect, mode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM ? "Zoom to fill" : mode == AspectRatioFrameLayout.RESIZE_MODE_FILL ? "Stretch" : "Fit to screen", -1);
        h.removeCallbacks(hideInfo); h.postDelayed(hideInfo, 900);
    }

    private void rotate() {
        userRotated = true;
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        lockOrientation(!land);
        showControls(true);
    }

    // ---- auto-rotate: open in the video's orientation, then follow the phone once the user physically turns it
    // (only when the system auto-rotate switch is on — otherwise we stay locked like the system expects)
    private android.view.OrientationEventListener orient;
    private int lockedTo;              // 1 landscape, 2 portrait, 0 free (follows the sensor)
    private boolean matchedOnce;
    private long otherSince;

    private boolean autoRotateOn() {
        try { return android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1; }
        catch (Exception e) { return false; }
    }

    private void lockOrientation(boolean land) {
        lockedTo = land ? 1 : 2; matchedOnce = false; otherSince = 0;
        setRequestedOrientation(land ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
    }

    private void setupOrientationListener() {
        orient = new android.view.OrientationEventListener(this) {
            @Override public void onOrientationChanged(int deg) {
                if (deg == ORIENTATION_UNKNOWN || lockedTo == 0 || locked || inPip) return;
                boolean devLand = (deg > 65 && deg < 115) || (deg > 245 && deg < 295);
                boolean devPort = deg < 25 || deg > 335;
                boolean matches = lockedTo == 1 ? devLand : devPort, other = lockedTo == 1 ? devPort : devLand;
                if (matches) { matchedOnce = true; otherSince = 0; }
                else if (other && matchedOnce) {
                    // the phone was turned (e.g. back upright after a landscape video): follow it, then keep following
                    long now = SystemClock.uptimeMillis();
                    if (otherSince == 0) otherSince = now;
                    else if (now - otherSince > 400) {
                        boolean toLand = lockedTo == 2;
                        lockedTo = toLand ? 1 : 2; matchedOnce = true; otherSince = 0;
                        setRequestedOrientation(toLand ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                    }
                } else otherSince = 0;
            }
        };
    }

    private PictureInPictureParams pipParams() {
        if (Build.VERSION.SDK_INT < 26) return null;
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
        VideoSize vs = player == null ? VideoSize.UNKNOWN : player.getVideoSize();
        if (vs.width > 0 && vs.height > 0) {
            float r = vs.width * vs.pixelWidthHeightRatio / vs.height;
            r = Math.max(0.42f, Math.min(2.39f, r));
            b.setAspectRatio(new Rational(Math.round(r * 1000), 1000));
        }
        List<RemoteAction> acts = new ArrayList<>();
        acts.add(remote(R.drawable.ic_rew10, "Back 10s", "rew", 1));
        boolean playing = player != null && player.isPlaying();
        acts.add(remote(playing ? R.drawable.ic_pause : R.drawable.ic_play, playing ? "Pause" : "Play", "toggle", 2));
        acts.add(remote(R.drawable.ic_ff10, "Forward 10s", "ff", 3));
        b.setActions(acts);
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(playing && prefs.getBoolean("autoPip", true)).setSeamlessResizeEnabled(true);
        return b.build();
    }

    private RemoteAction remote(int icon, String label, String act, int code) {
        Intent i = new Intent("vidra.pip").setPackage(getPackageName()).putExtra("a", act);
        PendingIntent pi = PendingIntent.getBroadcast(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new RemoteAction(Icon.createWithResource(this, icon), label, label, pi);
    }

    private void updatePipParams() { if (Build.VERSION.SDK_INT >= 26) try { setPictureInPictureParams(pipParams()); } catch (Exception ignored) {} }
    private void updatePipActions() { updatePipParams(); }

    private final BroadcastReceiver pipReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (player == null) return;
            String a = i.getStringExtra("a");
            if ("toggle".equals(a)) togglePlay(); else if ("rew".equals(a)) seekBy(-10000); else if ("ff".equals(a)) seekBy(10000);
        }
    };

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerPipReceiver() {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pipReceiver, new IntentFilter("vidra.pip"), Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(pipReceiver, new IntentFilter("vidra.pip"));
    }

    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26) return;
        try { enterPictureInPictureMode(pipParams()); } catch (Exception e) { Toast.makeText(this, "Picture-in-picture is turned off for Danav in system settings", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= 26 && Build.VERSION.SDK_INT < 31 && player != null && player.isPlaying() && prefs.getBoolean("autoPip", true)) enterPip();
    }

    @Override public void onPictureInPictureModeChanged(boolean pip, Configuration c) {
        super.onPictureInPictureModeChanged(pip, c);
        inPip = pip;
        if (pip) { wasInPip = true; closePanel(); showControls(false); infoBox.setVisibility(View.GONE); resumeChip.setVisibility(View.GONE); }
        else showControls(true);
        updateBigPlay();
    }

    // ------------------------------------------------------------------ preview frames

    private boolean scrubWasPlaying;
    private long lastLiveSeek;
    private int previewW, previewH;

    /** Preview box follows the video's aspect ratio (portrait videos get a tall preview). */
    private void sizePreview() {
        VideoSize vs = player.getVideoSize();
        float ratio = vs.width > 0 && vs.height > 0 ? vs.width * vs.pixelWidthHeightRatio / vs.height : 16f / 9f;
        int w = dp(Math.min(200, 168 * ui)), hh = Math.round(w / ratio);
        if (hh > dp(160)) { hh = dp(160); w = Math.round(hh * ratio); }
        previewW = w; previewH = hh;
        ViewGroup.LayoutParams lp = pframe.getLayoutParams();
        lp.width = w; lp.height = hh;
        pframe.setLayoutParams(lp);
    }

    private void showPreview(long pos) {
        previewTime.setText(fmt(pos));
        float x = timeline.getLeft() + ((View) timeline.getParent()).getLeft() + bottomBox.getLeft() + timeline.thumbX();
        int w = Math.max(previewW, dp(80));
        float left = Math.max(dp(8), Math.min(root.getWidth() - w - dp(8), x - w / 2f));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) preview.getLayoutParams();
        lp.leftMargin = Math.round(left);
        lp.bottomMargin = root.getHeight() - (bottomBox.getTop() + ((View) timeline.getParent()).getTop()) + dp(6);
        preview.setLayoutParams(lp);
        String uri = currentUri();
        boolean local = uri.startsWith("content:") || uri.startsWith("file:") || uri.startsWith("/");
        boolean live = pp != null;
        pframe.setVisibility(local || live ? View.VISIBLE : View.GONE);
        if (live) ppSeek(pos);                       // real video frames from the muted preview player
        if (!local) return;
        // 1) instant: nearest frame from the pre-built strip (shown under the live preview until it renders)
        Bitmap near = nearestStripFrame(uri, pos);
        if (near != null) previewImg.setImageBitmap(near);
        if (live && ppRendered) return;
        // 2) fast: the key-frame at this position
        wantFrame.set(pos);
        frames.execute(() -> {
            long p = wantFrame.get();
            if (p != pos) return;                      // a newer request is queued
            Bitmap b = frameAt(uri, p, false);
            if (b != null && wantFrame.get() == p) h.post(() -> { if (preview.getVisibility() == View.VISIBLE && wantFrame.get() == p) previewImg.setImageBitmap(b); });
        });
        // 3) exact: when the finger rests for a moment, decode the precise frame
        exactPos = pos;
        h.removeCallbacks(exactFrame);
        h.postDelayed(exactFrame, 260);
    }

    // ---- muted second player that renders real frames into the preview box while scrubbing
    private ExoPlayer pp;
    private String ppUri;
    private boolean ppRendered;
    private long ppLastSeek, ppWant = -1;

    private void ensurePreviewPlayer() {
        String uri = currentUri();
        if (pp != null && uri.equals(ppUri)) return;
        releasePreviewPlayer.run();
        if (uri.isEmpty()) return;
        try {
            ExoPlayer x = new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(new VaultDataSource.Factory(this))).build();
            x.setVolume(0f);
            x.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);
            x.setTrackSelectionParameters(x.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .setMaxVideoSize(854, 854).build());
            x.setVideoTextureView(previewTex);
            x.addListener(new Player.Listener() {
                @Override public void onRenderedFirstFrame() { if (pp == x) { ppRendered = true; previewTex.animate().alpha(1f).setDuration(90).start(); } }
                @Override public void onPlayerError(@NonNull PlaybackException e) { if (pp == x) releasePreviewPlayer.run(); }
            });
            x.setMediaItem(MediaItem.fromUri(uri));
            x.setPlayWhenReady(false);
            x.prepare();
            long p = Math.max(0, player.getCurrentPosition());
            x.seekTo(p);
            pp = x; ppUri = uri; ppRendered = false; ppWant = -1;
            previewTex.setAlpha(0f);
        } catch (Throwable t) { pp = null; }
    }

    /** Throttled fast (key-frame) seeks while moving; one exact seek once the finger rests. */
    private void ppSeek(long pos) {
        if (pp == null) return;
        ppWant = pos;
        long now = SystemClock.uptimeMillis();
        if (now - ppLastSeek > 70) {
            ppLastSeek = now;
            pp.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);
            pp.seekTo(pos);
        }
        h.removeCallbacks(ppTrail);
        h.postDelayed(ppTrail, 180);
    }

    private final Runnable ppTrail = () -> {
        if (pp == null || ppWant < 0) return;
        pp.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT);
        pp.seekTo(ppWant);
    };

    private final Runnable releasePreviewPlayer = () -> {
        h.removeCallbacks(ppTrail);
        if (pp != null) { try { pp.clearVideoTextureView(previewTex); pp.release(); } catch (Throwable ignored) {} }
        pp = null; ppUri = null; ppRendered = false;
        if (previewTex != null) previewTex.setAlpha(0f);
    };

    private long exactPos;
    private final Runnable exactFrame = () -> {
        final long p = exactPos; final String uri = currentUri();
        frames.execute(() -> {
            if (wantFrame.get() != p) return;
            Bitmap b = frameAt(uri, p, true);
            if (b != null && wantFrame.get() == p) h.post(() -> { if (preview.getVisibility() == View.VISIBLE && wantFrame.get() == p) previewImg.setImageBitmap(b); });
        });
    };

    // ---- frame strip: up to 120 small frames, filled coarse-to-fine (bit-reversal order) in the background
    private final ExecutorService stripExec = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "strip"); t.setPriority(Thread.MIN_PRIORITY); return t; });
    private final java.util.concurrent.ConcurrentSkipListMap<Long, Bitmap> strip = new java.util.concurrent.ConcurrentSkipListMap<>();
    private volatile String stripUri;
    private final java.util.concurrent.atomic.AtomicInteger stripGen = new java.util.concurrent.atomic.AtomicInteger();

    private Bitmap nearestStripFrame(String uri, long pos) {
        if (!uri.equals(stripUri) || strip.isEmpty()) return null;
        java.util.Map.Entry<Long, Bitmap> a = strip.floorEntry(pos), b = strip.ceilingEntry(pos);
        if (a == null) return b == null ? null : b.getValue();
        if (b == null) return a.getValue();
        return pos - a.getKey() <= b.getKey() - pos ? a.getValue() : b.getValue();
    }

    private void ensureStrip() {
        String uri = currentUri();
        long dur = player.getDuration();
        if (uri.equals(stripUri) || dur <= 0) return;
        if (!(uri.startsWith("content:") || uri.startsWith("file:") || uri.startsWith("/"))) return;
        stripUri = uri;
        strip.clear();
        final int gen = stripGen.incrementAndGet();
        final int n = (int) Math.max(8, Math.min(120, dur / 2000));
        stripExec.execute(() -> {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                Uri u = Uri.parse(uri);
                if (Vault.isVault(u)) r.setDataSource(Vault.mediaSource(this, u.getHost())); else if ("file".equals(u.getScheme())) r.setDataSource(u.getPath()); else r.setDataSource(this, u);
                int bits = 32 - Integer.numberOfLeadingZeros(n - 1);
                for (int k = 0; k < (1 << bits); k++) {
                    if (stripGen.get() != gen) return;
                    int i = Integer.reverse(k) >>> (32 - bits);
                    if (i >= n) continue;
                    long t = (dur * i) / n + dur / (2L * n);
                    Bitmap b = Build.VERSION.SDK_INT >= 27
                            ? r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240)
                            : scaled(r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC), 240);
                    if (b != null && b.getConfig() != Bitmap.Config.RGB_565) { Bitmap c = b.copy(Bitmap.Config.RGB_565, false); if (c != null) { b.recycle(); b = c; } }
                    if (b != null && stripGen.get() == gen) strip.put(t, b);
                }
            } catch (Throwable ignored) {
            } finally { try { r.release(); } catch (Exception ignored) {} }
        });
    }

    private static Bitmap scaled(Bitmap b, int max) {
        if (b == null) return null;
        float s = Math.min(1f, max / (float) Math.max(b.getWidth(), b.getHeight()));
        return s >= 1f ? b : Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)), Math.max(1, Math.round(b.getHeight() * s)), true);
    }

    private Bitmap frameAt(String uri, long ms, boolean exact) {
        try {
            if (retriever == null || !uri.equals(retrieverUri)) {
                if (retriever != null) try { retriever.release(); } catch (Exception ignored) {}
                retriever = new MediaMetadataRetriever();
                Uri u = Uri.parse(uri);
                if (Vault.isVault(u)) retriever.setDataSource(Vault.mediaSource(this, u.getHost())); else if ("file".equals(u.getScheme())) retriever.setDataSource(u.getPath()); else retriever.setDataSource(this, u);
                retrieverUri = uri;
            }
            int opt = exact ? MediaMetadataRetriever.OPTION_CLOSEST : MediaMetadataRetriever.OPTION_CLOSEST_SYNC;
            if (Build.VERSION.SDK_INT >= 27) return retriever.getScaledFrameAtTime(ms * 1000, opt, 400, 400);
            return scaled(retriever.getFrameAtTime(ms * 1000, opt), 400);
        } catch (Throwable e) { return null; }
    }

    // ------------------------------------------------------------------ panels

    private LinearLayout panelList;

    private LinearLayout openPanel(String heading) {
        h.removeCallbacks(hide);
        panel.removeAllViews();
        boolean land = root.getWidth() > root.getHeight();
        View shade = new View(this);
        shade.setBackgroundColor(0x80000000);
        shade.setOnClickListener(v -> closePanel());
        panel.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable boxBg = new GradientDrawable(); boxBg.setColor(0xFA17181F);
        float rad = dp(24);
        boxBg.setCornerRadii(land ? new float[]{rad, rad, 0, 0, 0, 0, rad, rad} : new float[]{rad, rad, rad, rad, 0, 0, 0, 0});
        box.setBackground(boxBg);
        box.setElevation(0);
        box.setClickable(true);
        if (!land) {
            View grip = new View(this);
            grip.setBackground(rr(0x40FFFFFF, 3));
            LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(36), dp(4)); gl.gravity = Gravity.CENTER_HORIZONTAL; gl.topMargin = dp(10);
            box.addView(grip, gl);
        }
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), dp(10), dp(4), dp(6));
        TextView ht = text(13, 0x99FFFFFF, true);
        ht.setText(heading);
        ht.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        ht.setTextColor(Color.WHITE);
        head.addView(ht, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView x = icon(R.drawable.ic_close, 20, "Close");
        x.setOnClickListener(v -> closePanel());
        head.addView(x, new LinearLayout.LayoutParams(dp(44), dp(44)));
        box.addView(head);
        ScrollView sv = new ScrollView(this);
        panelList = new LinearLayout(this);
        panelList.setOrientation(LinearLayout.VERTICAL);
        panelList.setPadding(0, 0, 0, dp(12));
        sv.addView(panelList);
        box.addView(sv, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams bp;
        if (land) {
            bp = flp(Math.min(dp(360), root.getWidth() / 2), -1, Gravity.END);
            int r = root.getRootWindowInsets() != null && Build.VERSION.SDK_INT >= 28 && root.getRootWindowInsets().getDisplayCutout() != null ? root.getRootWindowInsets().getDisplayCutout().getSafeInsetRight() : 0;
            box.setPadding(0, dp(8), r, 0);
        } else bp = flp(-1, -2, Gravity.BOTTOM);
        panel.addView(box, bp);
        if (!land) { box.post(() -> { int max = (int) (root.getHeight() * 0.62f); if (box.getHeight() > max) { box.getLayoutParams().height = max; box.requestLayout(); } }); }
        panel.setVisibility(View.VISIBLE);
        updateBigPlay();
        shade.setAlpha(0f); shade.animate().alpha(1f).setDuration(180).start();
        if (land) { box.setTranslationX(dp(360)); box.animate().translationX(0).setDuration(220).setInterpolator(new DecelerateInterpolator()).start(); }
        else { box.setTranslationY(dp(400)); box.animate().translationY(0).setDuration(240).setInterpolator(new DecelerateInterpolator()).start(); }
        return panelList;
    }

    private void closePanel() {
        if (panel.getVisibility() != View.VISIBLE) return;
        panel.animate().alpha(0f).setDuration(160).withEndAction(() -> { panel.setVisibility(View.GONE); panel.setAlpha(1f); panel.removeAllViews(); updateBigPlay(); }).start();
        scheduleHide();
    }

    private View row(String label, String sub, boolean checked, int iconRes, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(12), dp(20), dp(12));
        r.setBackground(rowBg());
        r.setMinimumHeight(dp(52));
        ImageView ic = new ImageView(this);
        ic.setColorFilter(checked ? ACCENT : 0xCCFFFFFF);
        if (iconRes != 0) ic.setImageResource(iconRes); else if (checked) ic.setImageResource(R.drawable.ic_check);
        r.addView(ic, new LinearLayout.LayoutParams(dp(20), dp(20)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), 0, 0, 0);
        TextView t = text(15, checked ? Color.WHITE : 0xE6FFFFFF, checked);
        t.setText(label);
        col.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(12, 0x8CFFFFFF, false);
            s.setText(sub);
            s.setPadding(0, dp(4), 0, 0);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        r.setOnClickListener(click);
        panelList.addView(r);
        return r;
    }

    private void sep() {
        View v = new View(this);
        v.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, 1); p.setMargins(dp(20), dp(6), dp(20), dp(6));
        panelList.addView(v, p);
    }

    private void speedPanel() {
        openPanel("Playback speed");
        for (float s : SPEEDS) {
            final float v = s;
            row(fmtSpeed(s) + (s == 1f ? "  Normal" : ""), null, Math.abs(speed - s) < 0.01f, 0, x -> { setSpeed(v); closePanel(); });
        }
    }

    private void setSpeed(float s) {
        speed = s;
        player.setPlaybackParameters(new PlaybackParameters(s));
        speedBtnText.setText(fmtSpeed(s));
        speedBtnText.setTextColor(s == 1f ? Color.WHITE : ACCENT);
    }

    static String fmtSpeed(float s) {
        String t = s == (int) s ? String.valueOf((int) s) : String.valueOf(s);
        return t.replaceAll("0+$", "") + "×";
    }

    private static String lang(String code) {
        if (code == null || code.isEmpty() || code.equals("und")) return "";
        try { String n = new Locale(code).getDisplayLanguage(); return n.isEmpty() ? code : n; } catch (Exception e) { return code; }
    }

    static String codecName(String mime) {
        if (mime == null) return "";
        switch (mime) {
            case MimeTypes.AUDIO_AAC: return "AAC";
            case MimeTypes.AUDIO_AC3: return "Dolby Digital";
            case MimeTypes.AUDIO_E_AC3: case MimeTypes.AUDIO_E_AC3_JOC: return "Dolby Digital+";
            case MimeTypes.AUDIO_TRUEHD: return "Dolby TrueHD";
            case MimeTypes.AUDIO_DTS: case MimeTypes.AUDIO_DTS_HD: case MimeTypes.AUDIO_DTS_EXPRESS: return "DTS";
            case MimeTypes.AUDIO_OPUS: return "Opus";
            case MimeTypes.AUDIO_VORBIS: return "Vorbis";
            case MimeTypes.AUDIO_FLAC: return "FLAC";
            case MimeTypes.AUDIO_MPEG: return "MP3";
            case MimeTypes.AUDIO_ALAC: return "ALAC";
            case MimeTypes.AUDIO_RAW: return "PCM";
            case MimeTypes.VIDEO_H264: return "H.264";
            case MimeTypes.VIDEO_H265: return "HEVC";
            case MimeTypes.VIDEO_VP9: return "VP9";
            case MimeTypes.VIDEO_VP8: return "VP8";
            case MimeTypes.VIDEO_AV1: return "AV1";
            case MimeTypes.VIDEO_MP4V: return "MPEG-4";
            case MimeTypes.VIDEO_MPEG2: return "MPEG-2";
            case MimeTypes.TEXT_VTT: return "WebVTT";
            case MimeTypes.APPLICATION_SUBRIP: return "SRT";
            case MimeTypes.TEXT_SSA: return "SSA/ASS";
            case MimeTypes.APPLICATION_PGS: return "PGS";
            case MimeTypes.APPLICATION_TX3G: return "TX3G";
            default: { int i = mime.indexOf('/'); return i >= 0 ? mime.substring(i + 1).toUpperCase(Locale.ROOT) : mime; }
        }
    }

    private static String channels(int c) {
        if (c <= 0) return "";
        if (c == 1) return "Mono"; if (c == 2) return "Stereo"; if (c == 6) return "5.1"; if (c == 8) return "7.1";
        return c + " ch";
    }

    private void trackPanel(int type) {
        boolean audio = type == C.TRACK_TYPE_AUDIO;
        openPanel(audio ? "Audio" : "Subtitles");
        if (player instanceof VlcPlayer) { vlcTrackRows((VlcPlayer) player, audio); return; }
        Tracks tracks = player.getCurrentTracks();
        boolean any = false, anySelected = false;
        for (Tracks.Group g : tracks.getGroups()) if (g.getType() == type && g.isSelected()) anySelected = true;
        boolean textOff = player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || !anySelected;
        if (!audio) row("Off", null, textOff, 0, v -> {
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
            closePanel();
        });
        int n = 0;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                any = true; n++;
                Format f = g.getTrackFormat(i);
                String name = f.label != null ? f.label : lang(f.language);
                if (name.isEmpty()) name = (audio ? "Track " : "Subtitle ") + n;
                List<String> meta = new ArrayList<>();
                if (f.label != null && !lang(f.language).isEmpty()) meta.add(lang(f.language));
                meta.add(codecName(f.sampleMimeType));
                if (audio) { if (!channels(f.channelCount).isEmpty()) meta.add(channels(f.channelCount)); if (f.bitrate > 0) meta.add(f.bitrate / 1000 + " kbps"); }
                if ((f.selectionFlags & C.SELECTION_FLAG_FORCED) != 0 && !audio) meta.add("Forced");
                boolean supported = g.isTrackSupported(i, true);
                if (!supported) meta.add("not supported on this phone");
                boolean sel = g.isTrackSelected(i) && !(type == C.TRACK_TYPE_TEXT && textOff);
                final Tracks.Group gg = g; final int idx = i;
                View r = row(name, TextUtils.join(" · ", meta), sel, 0, v -> {
                    TrackSelectionParameters p = player.getTrackSelectionParameters().buildUpon()
                            .setTrackTypeDisabled(type, false)
                            .setOverrideForType(new TrackSelectionOverride(gg.getMediaTrackGroup(), idx)).build();
                    player.setTrackSelectionParameters(p);
                    closePanel();
                });
                if (!supported) r.setAlpha(0.5f);
            }
        }
        if (!any && audio) row("No audio tracks", null, false, R.drawable.ic_mute, v -> closePanel());
        if (!audio) {
            sep();
            row("Load subtitle file…", "SRT, VTT, ASS/SSA, TTML", false, R.drawable.ic_folder, v -> pickSubtitle());
            row("Subtitle size", subSizeName(), false, R.drawable.ic_subs, v -> subSizePanel());
        }
    }

    private void vlcTrackRows(VlcPlayer v, boolean audio) {
        org.videolan.libvlc.MediaPlayer.TrackDescription[] list = audio ? v.audioTracks() : v.spuTracks();
        int cur = audio ? v.audioTrack() : v.spuTrack();
        int n = 0;
        for (org.videolan.libvlc.MediaPlayer.TrackDescription t : list) {
            final int id = t.id;
            String name = t.id < 0 ? (audio ? "Disabled" : "Off") : (t.name == null || t.name.isEmpty() ? (audio ? "Track " : "Subtitle ") + (++n) : t.name);
            row(name, null, id == cur, 0, x -> { if (audio) v.setAudioTrack(id); else v.setSpuTrack(id); closePanel(); });
        }
        if (list.length == 0) row(audio ? "No audio tracks" : "Off", null, !audio, audio ? R.drawable.ic_mute : 0, x -> closePanel());
        if (!audio) {
            sep();
            row("Load subtitle file…", "SRT, VTT, ASS/SSA, TTML", false, R.drawable.ic_folder, x -> pickSubtitle());
        }
    }

    private void audioPanel() { trackPanel(C.TRACK_TYPE_AUDIO); }
    private void subsPanel() { trackPanel(C.TRACK_TYPE_TEXT); }

    private static final float[] SUB_SIZES = {0.042f, 0.053f, 0.066f, 0.08f};
    private static final String[] SUB_NAMES = {"Small", "Normal", "Large", "Extra large"};
    private String subSizeName() { return SUB_NAMES[Math.max(0, Math.min(3, prefs.getInt("subSize", 1)))]; }

    private void subSizePanel() {
        openPanel("Subtitle size");
        int cur = prefs.getInt("subSize", 1);
        for (int i = 0; i < SUB_SIZES.length; i++) {
            final int k = i;
            row(SUB_NAMES[i], null, i == cur, 0, v -> { prefs.edit().putInt("subSize", k).apply(); applySubtitleStyle(); closePanel(); });
        }
        sep();
        boolean bgOn = prefs.getBoolean("subBg", true);
        row("Background box", bgOn ? "On" : "Off", bgOn, 0, v -> { prefs.edit().putBoolean("subBg", !bgOn).apply(); applySubtitleStyle(); closePanel(); });
    }

    private void applySubtitleStyle() {
        if (pv.getSubtitleView() == null) return;
        boolean bgOn = prefs.getBoolean("subBg", true);
        pv.getSubtitleView().setStyle(new CaptionStyleCompat(Color.WHITE, bgOn ? 0x99000000 : Color.TRANSPARENT, Color.TRANSPARENT,
                bgOn ? CaptionStyleCompat.EDGE_TYPE_NONE : CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, Typeface.create("sans-serif-medium", Typeface.NORMAL)));
        pv.getSubtitleView().setFractionalTextSize(SUB_SIZES[Math.max(0, Math.min(3, prefs.getInt("subSize", 1)))]);
        pv.getSubtitleView().setApplyEmbeddedStyles(true);
        pv.getSubtitleView().setBottomPaddingFraction(0.06f);
    }

    private void pickSubtitle() {
        closePanel();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(i, REQ_SUB); } catch (Exception e) { Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_SUB && res == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            int idx = player.getCurrentMediaItemIndex();
            long pos = player.getCurrentPosition();
            boolean play = player.getPlayWhenReady();
            subs.set(idx, u);
            if (player instanceof VlcPlayer) { ((VlcPlayer) player).addSubtitle(u); flash("Subtitles loaded"); return; }
            List<MediaItem> list = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) list.add(mediaItem(i));
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build());
            player.setMediaItems(list, idx, pos);
            player.prepare();
            player.setPlayWhenReady(play);
            flash("Subtitles loaded");
        }
    }

    private void morePanel() {
        openPanel("More");
        int m = player.getRepeatMode();
        row("Repeat", m == 0 ? "Off" : m == 1 ? "This video" : "All videos", m != 0, R.drawable.ic_repeat, v -> { player.setRepeatMode((m + 1) % 3); morePanel(); });
        row("Sleep timer", sleepAt > 0 ? "Pauses in " + Math.max(1, (sleepAt - System.currentTimeMillis()) / 60000) + " min" : stopAfterThis ? "End of this video" : "Off", sleepAt > 0 || stopAfterThis, R.drawable.ic_moon, v -> sleepPanel());
        boolean priv = currentUri().startsWith("vault:");
        if (!priv) row("Play as audio", "Keep listening with the screen off", false, R.drawable.ic_headphones, v -> playAsAudio());
        row("Capture frame", "Save this frame to Pictures/Danav", false, R.drawable.ic_camera, v -> { closePanel(); captureFrame(); });
        boolean auto = prefs.getBoolean("autoPip", true);
        if (Build.VERSION.SDK_INT >= 26) row("Auto picture-in-picture", auto ? "On — when you leave while playing" : "Off", auto, R.drawable.ic_pip, v -> { prefs.edit().putBoolean("autoPip", !auto).apply(); updatePipParams(); morePanel(); });
        row("Video info", null, false, R.drawable.ic_info, v -> infoPanel());
        sep();
        if (!priv) {
            row("Open with another app", null, false, R.drawable.ic_external, v -> openWith());
            row("Share", null, false, R.drawable.ic_share, v -> share());
        }
    }

    private void sleepPanel() {
        openPanel("Sleep timer");
        row("Off", null, sleepAt == 0 && !stopAfterThis, 0, v -> { sleepAt = 0; stopAfterThis = false; closePanel(); });
        row("End of this video", null, stopAfterThis, 0, v -> { stopAfterThis = true; sleepAt = 0; closePanel(); flash("Stops at the end of this video"); });
        for (int min : new int[]{15, 30, 45, 60, 90}) {
            final int mm = min;
            row(min + " minutes", null, false, 0, v -> { sleepAt = System.currentTimeMillis() + mm * 60000L; stopAfterThis = false; tick.run(); closePanel(); flash("Pauses in " + mm + " min"); });
        }
    }

    private void infoPanel() {
        openPanel("Video info");
        JSONObject o = current();
        Uri u = Uri.parse(o.optString("uri"));
        addInfo("File", displayName(u));
        long size = fileSize(u);
        if (size > 0) addInfo("Size", DownloadService.bytes(size));
        long d = player.getDuration();
        if (d > 0) addInfo("Duration", fmt(d));
        Format vf = player instanceof ExoPlayer ? ((ExoPlayer) player).getVideoFormat() : null;
        Format af = player instanceof ExoPlayer ? ((ExoPlayer) player).getAudioFormat() : null;
        if (player instanceof VlcPlayer) {
            VideoSize vs = player.getVideoSize();
            if (vs.width > 0) addInfo("Resolution", vs.width + " × " + vs.height);
            addInfo("Decoder", "Compatibility (libVLC / FFmpeg)");
        }
        if (vf != null) {
            addInfo("Resolution", vf.width + " × " + vf.height + (vf.frameRate > 0 ? String.format(Locale.US, " · %.3g fps", vf.frameRate) : ""));
            addInfo("Video", codecName(vf.sampleMimeType) + (vf.codecs != null ? " (" + vf.codecs + ")" : "") + (vf.bitrate > 0 ? " · " + vf.bitrate / 1000 + " kbps" : ""));
            if (vf.colorInfo != null && vf.colorInfo.colorTransfer >= 6) addInfo("HDR", vf.colorInfo.colorTransfer == C.COLOR_TRANSFER_ST2084 ? "HDR10 / PQ" : "HLG");
        }
        if (af != null) {
            List<String> a = new ArrayList<>();
            a.add(codecName(af.sampleMimeType));
            if (!channels(af.channelCount).isEmpty()) a.add(channels(af.channelCount));
            if (af.sampleRate > 0) a.add(af.sampleRate / 1000f + " kHz");
            addInfo("Audio", TextUtils.join(" · ", a));
        }
        String path = o.optString("path");
        if (!path.isEmpty()) addInfo("Location", path);
    }

    private void addInfo(String k, String v) {
        if (v == null || v.isEmpty()) return;
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(20), dp(9), dp(20), dp(9));
        TextView a = text(12, 0x8CFFFFFF, false); a.setText(k);
        TextView b = text(15, Color.WHITE, false); b.setText(v); b.setPadding(0, dp(4), 0, 0); b.setTextIsSelectable(true);
        r.addView(a); r.addView(b);
        panelList.addView(r);
    }

    private void queuePanel() {
        openPanel("Playlist · " + items.size());
        int cur = player.getCurrentMediaItemIndex();
        for (int i = 0; i < items.size(); i++) {
            final int k = i;
            JSONObject o = items.get(i);
            String t = o.optString("title");
            if (t.isEmpty()) t = displayName(Uri.parse(o.optString("uri")));
            row(t, o.optString("sub"), i == cur, i == cur ? R.drawable.ic_play : 0, v -> { player.seekTo(k, resumePos(items.get(k).optString("uri"))); player.play(); closePanel(); });
        }
        if (cur > 3) panelList.post(() -> { View c = panelList.getChildAt(cur); if (c != null && panelList.getParent() instanceof ScrollView) ((ScrollView) panelList.getParent()).scrollTo(0, Math.max(0, c.getTop() - dp(100))); });
    }

    // ------------------------------------------------------------------ actions

    private void playAsAudio() {
        try {
            JSONObject o = current();
            JSONObject it = new JSONObject().put("uri", o.optString("uri")).put("title", title.getText().toString()).put("artist", o.optString("sub")).put("art", o.optString("art"));
            long pos = player.getCurrentPosition();
            player.pause();
            saveResume();
            MusicHub.command(this, "queue", new JSONObject().put("items", new JSONArray().put(it)).put("pos", pos));
            Toast.makeText(this, "Playing as audio — controls are in the notification", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception ignored) {}
    }

    private void captureFrame() {
        if (Build.VERSION.SDK_INT < 24 || !(pv.getVideoSurfaceView() instanceof SurfaceView)) return;
        VideoSize vs = player.getVideoSize();
        if (vs.width <= 0) { flash("Nothing to capture yet"); return; }
        int w = vs.width, hh = vs.height;
        float sc = Math.min(1f, 1920f / Math.max(w, hh));
        w = Math.max(1, Math.round(w * sc)); hh = Math.max(1, Math.round(hh * sc));
        final Bitmap bmp = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request((SurfaceView) pv.getVideoSurfaceView(), bmp, r -> {
                if (r != PixelCopy.SUCCESS) { flash("This video can't be captured"); return; }
                new Thread(() -> {
                    String where = saveImage(bmp);
                    h.post(() -> flash(where != null ? "Frame saved to " + where : "Couldn't save the frame"));
                }).start();
            }, h);
            View flashV = new View(this);
            flashV.setBackgroundColor(Color.WHITE);
            root.addView(flashV, new FrameLayout.LayoutParams(-1, -1));
            flashV.animate().alpha(0f).setDuration(280).withEndAction(() -> root.removeView(flashV)).start();
        } catch (Exception e) { flash("This video can't be captured"); }
    }

    private String saveImage(Bitmap b) {
        String name = "Vidra_" + System.currentTimeMillis() + ".jpg";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Danav");
                Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (u == null) return null;
                try (OutputStream out = getContentResolver().openOutputStream(u)) { b.compress(Bitmap.CompressFormat.JPEG, 92, out); }
                return "Pictures/Danav";
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Danav");
            if (!dir.mkdirs() && !dir.isDirectory()) dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            File f = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.JPEG, 92, out); }
            android.media.MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            return f.getParentFile() != null ? f.getParentFile().getName() : "Pictures";
        } catch (Exception e) { return null; }
    }

    private String mimeOf(Uri u) {
        String m = null;
        try { m = getContentResolver().getType(u); } catch (Exception ignored) {}
        return m != null ? m : "video/*";
    }

    private void openWith() {
        Uri u = Uri.parse(currentUri());
        if ("file".equals(u.getScheme())) return;
        try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW).setDataAndType(u, mimeOf(u)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with")); }
        catch (Exception e) { Toast.makeText(this, "No other app can open this file", Toast.LENGTH_SHORT).show(); }
    }

    private void share() {
        Uri u = Uri.parse(currentUri());
        try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType(mimeOf(u)).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share video")); }
        catch (Exception ignored) {}
    }

    private String displayName(Uri u) {
        if (u == null) return "";
        if ("content".equals(u.getScheme())) {
            try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
            } catch (Exception ignored) {}
        }
        String p = u.getLastPathSegment();
        return p == null ? "Video" : p;
    }

    private long fileSize(Uri u) {
        if ("content".equals(u.getScheme())) {
            try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
            } catch (Exception ignored) {}
        } else if (u.getPath() != null) return new File(u.getPath()).length();
        return 0;
    }

    // ------------------------------------------------------------------ resume position

    private long resumePos(String uri) {
        long p = prefs.getLong("pos:" + uri, 0);
        return p > 5000 ? p : 0;
    }

    private void saveResume() {
        if (player == null) return;
        String uri = currentUri();
        long d = player.getDuration(), p = player.getCurrentPosition();
        if (uri.isEmpty() || d <= 0) return;
        if (d < 60_000 || p < 5000 || p > d - 8000) { clearResume(uri); return; }
        prefs.edit().putLong("pos:" + uri, p).apply();
    }

    private void clearResume(String uri) { prefs.edit().remove("pos:" + uri).apply(); }

    private void showResumeChip(long at) {
        resumeChip.removeAllViews();
        TextView t = text(13, Color.WHITE, false);
        t.setText("Resumed at " + fmt(at));
        TextView b = text(13, ACCENT, true);
        b.setText("Start over");
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, rr(Color.WHITE, 6)));
        b.setOnClickListener(v -> { player.seekTo(0); clearResume(currentUri()); resumeChip.setVisibility(View.GONE); });
        resumeChip.addView(t); resumeChip.addView(b);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) resumeChip.getLayoutParams();
        lp.leftMargin = dp(16); lp.bottomMargin = dp(120);
        resumeChip.setVisibility(View.VISIBLE);
        resumeChip.setAlpha(0f);
        resumeChip.animate().alpha(1f).setDuration(200).start();
        h.postDelayed(() -> resumeChip.animate().alpha(0f).setDuration(300).withEndAction(() -> resumeChip.setVisibility(View.GONE)).start(), 6000);
    }

    // ------------------------------------------------------------------ errors & tracks

    static String describe(PlaybackException e) {
        int c = e.errorCode;
        switch (c) {
            case PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND:
            case PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE: return "The file was moved or deleted.";
            case PlaybackException.ERROR_CODE_IO_NO_PERMISSION: return "Danav isn't allowed to read this file.";
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT: return "No connection. Check your internet and try again.";
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED: return "This file is damaged or incomplete.";
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED: return "This file format isn't supported.";
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES:
                return "This phone's hardware can't decode this video (for example HEVC 10-bit, AV1 or a very high resolution). Try another app, or download a lower quality / MP4 (H.264) version.";
            case PlaybackException.ERROR_CODE_DRM_UNSPECIFIED:
            case PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED: return "This video is DRM-protected.";
            default: return "This video can't be played (" + e.getErrorCodeName().replace("ERROR_CODE_", "").toLowerCase(Locale.ROOT).replace('_', ' ') + ").";
        }
    }

    private void showError(String msg) {
        setBuffering(false);
        errText.setText(msg);
        errBox.setVisibility(View.VISIBLE);
        errBox.setAlpha(0f);
        errBox.animate().alpha(1f).setDuration(200).start();
        showControls(true);
    }

    private boolean audioWarned;
    private void checkTracks(Tracks t) {
        boolean hasAudio = false, audioOk = false, hasVideo = false, videoOk = false;
        for (Tracks.Group g : t.getGroups()) {
            if (g.getType() == C.TRACK_TYPE_VIDEO) {
                hasVideo = true;
                for (int i = 0; i < g.length; i++) if (g.isTrackSupported(i, false)) videoOk = true;
            }
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            hasAudio = true;
            for (int i = 0; i < g.length; i++) if (g.isTrackSupported(i, true)) audioOk = true;
        }
        // ExoPlayer would silently play without picture/sound here: hand the file to libVLC instead
        if (((hasVideo && !videoOk) || (hasAudio && !audioOk)) && !(player instanceof VlcPlayer)) { h.post(this::switchToVlc); return; }
        if (hasAudio && !audioOk && !audioWarned) { audioWarned = true; flash("This audio format isn't supported — playing without sound"); }
    }

    // ------------------------------------------------------------------ keys & lifecycle

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (player == null) return super.onKeyDown(code, e);
        switch (code) {
            case KeyEvent.KEYCODE_SPACE: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_DPAD_CENTER: togglePlay(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MEDIA_REWIND: seekBy(-10000); showControls(true); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: seekBy(10000); showControls(true); return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT: if (player.hasNextMediaItem()) player.seekToNextMediaItem(); return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem(); return true;
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (boostPct > 0 && code == KeyEvent.KEYCODE_VOLUME_DOWN) { setVolumePct(Math.max(100, volumePct() - 10)); showInfo(R.drawable.ic_volume, Math.round(volumePct()) + "%", volumePct() / 100f); h.removeCallbacks(hideInfo); h.postDelayed(hideInfo, 800); return true; }
                return super.onKeyDown(code, e);
        }
        return super.onKeyDown(code, e);
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (panel.getVisibility() == View.VISIBLE) { closePanel(); return; }
        if (locked) { flashLock(); return; }
        super.onBackPressed();
    }

    @Override protected void onPause() { super.onPause(); saveResume(); if (orient != null) orient.disable(); }

    @Override protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
        releasePreviewPlayer.run();
        saveResume();
        if (wasInPip) { wasInPip = false; finishAndRemoveTask(); }   // PiP window was dismissed
    }

    @Override protected void onResume() {
        super.onResume(); wasInPip = false; hideSystemUi();
        if (orient == null) setupOrientationListener();
        if (lockedTo == 0) lockOrientation(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);   // open as the phone is held
        if (orient.canDetectOrientation()) orient.enable();
    }

    @Override protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        releasePreviewPlayer.run();
        try { unregisterReceiver(pipReceiver); } catch (Exception ignored) {}
        saveResume();
        if (player != null) { player.release(); player = null; }
        try { if (boost != null) boost.release(); } catch (Exception ignored) {}
        frames.execute(() -> { try { if (retriever != null) retriever.release(); } catch (Exception ignored) {} });
        frames.shutdown();
        stripGen.incrementAndGet();
        stripExec.shutdownNow();
        super.onDestroy();
    }
}
