package com.baru.player;

import android.app.Dialog;
import androidx.annotation.OptIn;
import androidx.media3.common.TrackGroup;
import androidx.media3.ui.TrackSelectionView;
import android.app.PictureInPictureParams;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import androidx.core.content.ContextCompat;
import androidx.media3.common.TrackSelectionParameters;
import java.util.Locale;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Rational;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Pemutar bawaan: fullscreen (sampai area notch), pilih kualitas,
 * rotasi, dan mode ukuran (resize_mode). Pilihan rotasi/ukuran diingat.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_OPTIONS = "options"; // JSON array semua pilihan
    public static final String EXTRA_INDEX = "index";     // pilihan yang diputar
    public static final String EXTRA_TITLE = "title";

    // urutan: 0 = Fit, 1 = Fill, 2 = Zoom (sesuai array resize_modes / resize_short)
    private static final int[] RESIZE_MODES = {
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    };
    private static final int[] ORIENTATIONS = {
            ActivityInfo.SCREEN_ORIENTATION_USER,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    };

    private PlayerView playerView;
    private View lockLayer;
    private TextView pill;
    private ImageButton muteBtn;
    private View qualityBtn;
    private TextView resizeText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean locked;
    private boolean muted;

    // gesture volume / kecerahan (lingkaran di tengah layar)
    private View volumeLayout;
    private View brightnessLayout;
    private ProgressBar volumeBar;
    private ProgressBar brightnessBar;
    private ImageView volumeImage;
    private ImageView brightnessImage;
    private AudioManager audio;
    private final Runnable hideIndicator = () -> {
        fadeOut(volumeLayout);
        fadeOut(brightnessLayout);
    };
    private float gStartX;
    private float gStartY;
    private int gMode;          // 0 belum ditentukan, 1 kecerahan, 2 volume, -1 diabaikan
    private boolean gActive;
    private float gStartBrightness;
    private int gStartVolume;
    private int gMaxVolume = 1;
    private long gStartPosition;
    private long gPreviewPosition;
    private long gSeekDuration;
    private ExoPlayer player;
    private SharedPreferences prefs;

    private final List<JSONObject> options = new ArrayList<>();
    private int current;
    private long resumePos;
    private int resizeIdx;
    private int orientIdx;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = getSharedPreferences("player", MODE_PRIVATE);
        resizeIdx = clamp(prefs.getInt("resize", 0), RESIZE_MODES.length);
        orientIdx = clamp(prefs.getInt("orient", 0), ORIENTATIONS.length);

        playerView = findViewById(R.id.player_view);
        lockLayer = findViewById(R.id.lock_layer);
        volumeLayout = findViewById(R.id.volumeRelativeLayout);
        brightnessLayout = findViewById(R.id.brightnessRelativeLayout);
        volumeBar = findViewById(R.id.volumeProgressBar);
        brightnessBar = findViewById(R.id.brightnessProgressBar);
        volumeImage = findViewById(R.id.volumeImageView);
        brightnessImage = findViewById(R.id.brightnessImageView);
        pill = findViewById(R.id.pill);
        // tampilan kontrol (player_controls.xml) ada di dalam PlayerView
        View topControls = playerView.findViewById(R.id.topControls);
        View bottomBar = playerView.findViewById(R.id.exo_bottom_bar);
        TextView titleView = playerView.findViewById(R.id.titleTextView);
        muteBtn = playerView.findViewById(R.id.exo_mute);
        qualityBtn = playerView.findViewById(R.id.exo_select_track);
        resizeText = playerView.findViewById(R.id.exo_resizeTextView);
        View pipBtn = playerView.findViewById(R.id.exo_pip);

        try {
            JSONArray arr = new JSONArray(getIntent().getStringExtra(EXTRA_OPTIONS));
            for (int i = 0; i < arr.length(); i++) options.add(arr.getJSONObject(i));
            current = getIntent().getIntExtra(EXTRA_INDEX, 0);
            if (current < 0 || current >= options.size()) current = 0;
            if (options.isEmpty()) throw new IllegalStateException();
        } catch (Exception e) {
            Toast.makeText(this, R.string.opsi_tidak_valid, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        titleView.setText(title == null ? "" : title);
        titleView.setSelected(true); // supaya judul panjang berjalan (marquee)

        playerView.findViewById(R.id.back_button).setOnClickListener(v -> finish());
        playerView.findViewById(R.id.lock_player).setOnClickListener(v -> setLocked(true));
        muteBtn.setOnClickListener(v -> toggleMute());
        qualityBtn.setOnClickListener(v -> showTrackDialog());
        playerView.findViewById(R.id.exo_screen_rotate).setOnClickListener(v -> showOrientationDialog());
        resizeText.setOnClickListener(v -> toggleFitZoom());
        resizeText.setOnLongClickListener(v -> { showResizeDialog(); return true; });
        pill.setOnClickListener(v -> { if (locked) setLocked(false); });

        boolean pipOk = Build.VERSION.SDK_INT >= 26
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        pipBtn.setVisibility(pipOk ? View.VISIBLE : View.GONE);
        pipBtn.setOnClickListener(v -> enterPip());

        lockLayer.setOnClickListener(v -> showUnlockTemporarily());

        // Beri ruang untuk notch / bar sistem di kontrol atas, bawah, dan tombol buka kunci
        padWithInsets(topControls, true, false);
        padWithInsets(bottomBar, false, false); // tinggi bar tetap, cukup sisi kiri/kanan
        padWithInsets(lockLayer, true, false);
        setupGestures();

        applyFullscreen();
        applyResize();
        applyOrientation();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player == null && !options.isEmpty()) initPlayer();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(hideIndicator);
        releasePlayer();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyFullscreen(); // sembunyikan lagi bar sistem setelah dialog
    }

    private static int clamp(int v, int size) {
        return v < 0 || v >= size ? 0 : v;
    }

    // ----------------------------------------------- fullscreen / resize / rotasi

    private void applyFullscreen() {
        Window w = getWindow();
        WindowCompat.setDecorFitsSystemWindows(w, false);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsetsCompat.Type.systemBars());
        ViewCompat.requestApplyInsets(playerView);
    }

    private void applyResize() {
        playerView.setResizeMode(RESIZE_MODES[resizeIdx]);
        resizeText.setText(getResources().getStringArray(R.array.resize_short)[resizeIdx]);
    }

    private void applyOrientation() {
        setRequestedOrientation(ORIENTATIONS[orientIdx]);
    }

    private void showResizeDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ukuran)
                .setSingleChoiceItems(R.array.resize_modes, resizeIdx, (d, which) -> {
                    resizeIdx = which;
                    prefs.edit().putInt("resize", which).apply();
                    applyResize();
                    d.dismiss();
                })
                .show();
    }

    private void showOrientationDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rotasi)
                .setSingleChoiceItems(R.array.orientasi, orientIdx, (d, which) -> {
                    orientIdx = which;
                    prefs.edit().putInt("orient", which).apply();
                    applyOrientation();
                    d.dismiss();
                })
                .show();
    }

    // ------------------------------------------- gesture volume & kecerahan

    /**
     * Geser vertikal di setengah kiri layar = kecerahan, setengah kanan = volume.
     * Ketukan biasa tetap menampilkan/menyembunyikan kontrol.
     */
    private void setupGestures() {
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        final float edge = 32 * getResources().getDisplayMetrics().density; // zona gesture sistem

        playerView.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    gStartX = e.getX();
                    gStartY = e.getY();
                    gActive = false;
                    gMode = (gStartX < edge || gStartX > v.getWidth() - edge) ? -1 : 0;
                    return false; // biarkan PlayerView mencatat sentuhan (untuk ketukan)

                case MotionEvent.ACTION_MOVE: {
                    if (gMode == -1 || e.getPointerCount() > 1) return gActive;
                    float dx = e.getX() - gStartX;
                    float dy = e.getY() - gStartY;
                    if (!gActive) {
                        if (Math.max(Math.abs(dx), Math.abs(dy)) <= slop) return false;
                        if (Math.abs(dx) > Math.abs(dy) * 1.25f) {
                            // Geser horizontal = preview seek. Hindari area tepi untuk gesture sistem.
                            if (gStartX < edge || gStartX > v.getWidth() - edge) {
                                gMode = -1;
                                return false;
                            }
                            gMode = 3;
                        } else if (Math.abs(dy) > Math.abs(dx) * 1.25f) {
                            gMode = gStartX < v.getWidth() / 2f ? 1 : 2;
                        } else {
                            return false;
                        }
                        gActive = true;
                        beginGesture();
                    }
                    if (gMode == 3) {
                        updateSeekGesture(dx / Math.max(1f, v.getWidth()));
                    } else {
                        updateGesture(-dy / Math.max(1f, v.getHeight()));
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean was = gActive;
                    int finishedMode = gMode;
                    gActive = false;
                    gMode = 0;
                    if (was) {
                        playerView.setControllerAutoShow(true);
                        if (e.getActionMasked() == MotionEvent.ACTION_UP
                                && finishedMode == 3 && player != null && gSeekDuration > 0) {
                            player.seekTo(Math.max(0L, Math.min(gPreviewPosition, gSeekDuration)));
                        }
                        handler.removeCallbacks(hideIndicator);
                        handler.postDelayed(hideIndicator, 700);
                    }
                    return was; // kalau tadi geser, jangan dihitung sebagai ketukan
                }
                default:
                    return false;
            }
        });
    }

    private void beginGesture() {
        // Sembunyikan kontrol lain supaya hanya lingkaran indikator yang terlihat
        playerView.hideController();
        playerView.setControllerAutoShow(false);
        if (gMode == 3) {
            if (player == null) {
                gSeekDuration = 0;
                return;
            }
            gStartPosition = Math.max(0L, player.getCurrentPosition());
            gPreviewPosition = gStartPosition;
            gSeekDuration = player.getDuration();
            if (gSeekDuration == C.TIME_UNSET || gSeekDuration <= 0) gSeekDuration = 0;
        } else if (gMode == 1) {
            float cur = getWindow().getAttributes().screenBrightness;
            if (cur < 0) { // masih ikut sistem: baca kecerahan sistem
                try {
                    cur = Settings.System.getInt(getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS) / 255f;
                } catch (Settings.SettingNotFoundException ex) {
                    cur = 0.5f;
                }
            }
            gStartBrightness = cur;
        } else {
            gMaxVolume = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            gStartVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (muted) toggleMute(); // menggeser volume = suarakan lagi
        }
    }

    private void updateSeekGesture(float fraction) {
        if (player == null || gSeekDuration <= 0) return;
        // Satu lebar layar menggeser sampai 2 menit; bisa maju maupun mundur.
        long deltaMs = (long) (fraction * 120_000L);
        gPreviewPosition = Math.max(0L, Math.min(gSeekDuration, gStartPosition + deltaMs));
        long diff = gPreviewPosition - gStartPosition;
        String sign = diff >= 0 ? "+" : "\u2212";
        showPill(sign + formatGestureTime(Math.abs(diff)) + "  \u2022  "
                + formatGestureTime(gPreviewPosition) + " / " + formatGestureTime(gSeekDuration),
                diff >= 0 ? R.drawable.ic_forward : R.drawable.ic_replay, false, 900);
    }

    private String formatGestureTime(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void updateGesture(float delta) {
        if (gMode == 1) {
            float b = Math.max(0.02f, Math.min(1f, gStartBrightness + delta));
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = b;
            getWindow().setAttributes(lp);
            showIndicator(true, b < 0.33f ? R.drawable.ic_brightness_low
                    : b < 0.66f ? R.drawable.ic_brightness_medium
                    : R.drawable.ic_brightness_high,
                    Math.round((b - 0.02f) / 0.98f * 100)); // 0% tepat di batas minimum
        } else if (gMode == 2) {
            float vf = Math.max(0f, Math.min(gMaxVolume, gStartVolume + delta * gMaxVolume));
            int idx = Math.round(vf);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0);
            float lv = idx / (float) gMaxVolume;
            showIndicator(false, idx == 0 ? R.drawable.ic_volume_off
                    : lv <= 0.33f ? R.drawable.ic_volume_mute
                    : lv <= 0.66f ? R.drawable.ic_volume_down
                    : R.drawable.ic_volume_up, Math.round(lv * 100));
        }
    }

    private void showIndicator(boolean brightness, int iconRes, int percent) {
        View show = brightness ? brightnessLayout : volumeLayout;
        View other = brightness ? volumeLayout : brightnessLayout;
        ProgressBar bar = brightness ? brightnessBar : volumeBar;
        ImageView img = brightness ? brightnessImage : volumeImage;
        other.animate().cancel();
        other.setVisibility(View.GONE);
        img.setImageResource(iconRes);
        bar.setProgress(Math.max(0, Math.min(100, percent)));
        show.animate().cancel();
        show.setAlpha(1f);
        show.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideIndicator);
    }

    private static void fadeOut(final View v) {
        if (v.getVisibility() != View.VISIBLE) return;
        v.animate().alpha(0f).setDuration(250)
                .withEndAction(() -> v.setVisibility(View.GONE)).start();
    }

    // ------------------------------------------------- kunci / bisu / PiP / insets

    private void padWithInsets(View v, final boolean top, final boolean bottom) {
        final int l = v.getPaddingLeft();
        final int t = v.getPaddingTop();
        final int r = v.getPaddingRight();
        final int b = v.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            Insets i = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(l + i.left, t + (top ? i.top : 0), r + i.right, b + (bottom ? i.bottom : 0));
            return insets;
        });
    }

    private void setLocked(boolean lock) {
        locked = lock;
        if (lock) {
            playerView.hideController();
            playerView.setUseController(false);
            lockLayer.setVisibility(View.VISIBLE);
            showPill(getString(R.string.pill_locked), R.drawable.ic_lock, false, 1500);
        } else {
            lockLayer.setVisibility(View.GONE);
            playerView.setUseController(true);
            playerView.showController();
            showPill(getString(R.string.pill_unlocked), R.drawable.ic_lock_open, false, 1500);
        }
    }

    private void showUnlockTemporarily() {
        // layar terkunci: ketuk layar -> pill "Tap to Unlock"; ketuk pill untuk membuka
        showPill(getString(R.string.pill_tap_unlock), R.drawable.ic_lock, true, 3000);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (locked) {
            showUnlockTemporarily(); // layar terkunci: back tidak keluar
            return;
        }
        super.onBackPressed();
    }

    private void toggleMute() {
        muted = !muted;
        if (player != null) player.setVolume(muted ? 0f : 1f);
        muteBtn.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_up);
    }

    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26 || player == null) return;
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
        VideoSize vs = player.getVideoSize();
        if (vs.width > 0 && vs.height > 0) {
            double r = Math.max(0.42, Math.min(2.39, (double) vs.width / vs.height));
            b.setAspectRatio(new Rational((int) (r * 1000), 1000));
        }
        try {
            enterPictureInPictureMode(b.build());
        } catch (IllegalStateException ignored) {
            // perangkat/ROM tidak mengizinkan PiP
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPip, newConfig);
        playerView.setUseController(!isInPip && !locked);
    }

    // -------------------------------------------------------------- kualitas

    private static final class Q {
        final Tracks.Group group;
        final int index;
        final int height;
        final int bitrate;

        Q(Tracks.Group g, int i, Format f) {
            group = g;
            index = i;
            height = f.height > 0 ? Math.min(f.height, f.width > 0 ? f.width : f.height) : 0;
            bitrate = f.bitrate > 0 ? f.bitrate : f.averageBitrate;
        }
    }

    private boolean hasVideoOverride() {
        for (TrackSelectionOverride ov : player.getTrackSelectionParameters().overrides.values()) {
            if (ov.getType() == C.TRACK_TYPE_VIDEO) return true;
        }
        return false;
    }

    private void showQualityDialog() {
        if (player == null) return;
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        // 1. Kualitas di dalam stream adaptif (HLS / DASH)
        List<Q> qs = new ArrayList<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO || g.length <= 1) continue;
            for (int i = 0; i < g.length; i++) {
                if (g.isTrackSupported(i)) qs.add(new Q(g, i, g.getTrackFormat(i)));
            }
        }
        if (!qs.isEmpty()) {
            Collections.sort(qs, (a, b) -> a.height != b.height
                    ? Integer.compare(b.height, a.height)
                    : Integer.compare(b.bitrate, a.bitrate));

            labels.add((hasVideoOverride() ? "" : "✓ ") + getString(R.string.otomatis));
            actions.add(() -> player.setTrackSelectionParameters(
                    player.getTrackSelectionParameters().buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()));

            for (final Q q : qs) {
                String l = (q.height > 0 ? q.height + "p" : "?");
                if (q.bitrate > 0) l += " (" + (q.bitrate / 1000) + " kbps)";
                if (hasVideoOverride() && q.group.isTrackSelected(q.index)) l = "✓ " + l;
                labels.add(l);
                actions.add(() -> player.setTrackSelectionParameters(
                        player.getTrackSelectionParameters().buildUpon()
                                .setOverrideForType(new TrackSelectionOverride(
                                        q.group.getMediaTrackGroup(), q.index))
                                .build()));
            }
        }

        // 2. Pindah ke sumber/kualitas lain dari daftar
        for (int i = 0; i < options.size(); i++) {
            if (i == current) continue;
            final int idx = i;
            labels.add(getString(R.string.sumber_lain) + ": " + options.get(i).optString("label", "?"));
            actions.add(() -> switchTo(idx));
        }

        if (labels.isEmpty()) {
            Toast.makeText(this, R.string.tidak_ada_kualitas, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.kualitas)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void switchTo(int idx) {
        if (player == null) return;
        long pos = player.getCurrentPosition();
        try {
            MediaSource src = buildSource(options.get(idx));
            current = idx;
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build());
            player.setMediaSource(src, pos);
            player.prepare();
            player.play();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.gagal_putar, e.toString()),
                    Toast.LENGTH_LONG).show();
        }
    }


    // ------------------------------------------------ pill info & dialog trek

    private final Runnable hidePill = () -> pill.animate().alpha(0f).setDuration(200)
            .withEndAction(() -> pill.setVisibility(View.GONE)).start();

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void showPill(String text, int iconRes, boolean clickable, long durationMs) {
        pill.animate().cancel();
        pill.setText(text);
        if (iconRes != 0) {
            Drawable d = ContextCompat.getDrawable(this, iconRes);
            if (d != null) {
                d = d.mutate();
                d.setBounds(0, 0, dp(14), dp(14));
                pill.setCompoundDrawables(d, null, null, null);
            }
        } else {
            pill.setCompoundDrawables(null, null, null, null);
        }
        pill.setClickable(clickable);
        pill.setAlpha(1f);
        pill.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hidePill);
        handler.postDelayed(hidePill, durationMs);
    }

    /** Ketuk teks: Fit -> Fill -> Zoom -> Fit ... Tahan lama: daftar pilihan. */
    private void toggleFitZoom() {
        resizeIdx = (resizeIdx + 1) % RESIZE_MODES.length;
        prefs.edit().putInt("resize", resizeIdx).apply();
        applyResize();
        showPill(getResources().getStringArray(R.array.resize_short)[resizeIdx], 0, false, 1000);
    }

    /**
     * Dialog pilih trek memakai TrackSelectionView bawaan Media3, satu dialog dengan tab
     * Video dan Audio (pola sama dengan app demo ExoPlayer). Pilihan diterapkan saat OKE.
     */
    @OptIn(markerClass = UnstableApi.class)
    private void showTrackDialog() {
        if (player == null) return;
        final int[] types = {C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO};
        final TrackSelectionParameters params = player.getTrackSelectionParameters();
        final Tracks tracks = player.getCurrentTracks();

        final Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        View root = getLayoutInflater().inflate(R.layout.dialog_tracks, null);
        dlg.setContentView(root);

        final TrackSelectionView[] views = {
                root.findViewById(R.id.track_view_video), root.findViewById(R.id.track_view_audio)};
        final View[] panels = {
                root.findViewById(R.id.track_panel_video), root.findViewById(R.id.track_panel_audio)};
        final TextView[] tabs = {root.findViewById(R.id.tab_video), root.findViewById(R.id.tab_audio)};

        for (int k = 0; k < 2; k++) {
            List<Tracks.Group> groups = new ArrayList<>();
            for (Tracks.Group g : tracks.getGroups()) {
                if (g.getType() == types[k]) groups.add(g);
            }
            Map<TrackGroup, TrackSelectionOverride> overrides = new HashMap<>();
            for (TrackSelectionOverride o : params.overrides.values()) {
                if (o.getType() == types[k]) overrides.put(o.mediaTrackGroup, o);
            }
            views[k].setShowDisableOption(true);        // None
            views[k].setAllowAdaptiveSelections(true);  // Auto
            views[k].init(groups, params.disabledTrackTypes.contains(types[k]),
                    overrides, null, null);
        }

        final Runnable[] showTab = new Runnable[1];
        final int[] cur = {0};
        showTab[0] = () -> {
            for (int t = 0; t < 2; t++) {
                panels[t].setVisibility(t == cur[0] ? View.VISIBLE : View.GONE);
                tabs[t].setTextColor(t == cur[0] ? 0xFFFFFFFF : 0x99FFFFFF);
                int flags = tabs[t].getPaintFlags();
                tabs[t].setPaintFlags(t == cur[0] ? flags | Paint.UNDERLINE_TEXT_FLAG
                        : flags & ~Paint.UNDERLINE_TEXT_FLAG);
            }
        };
        tabs[0].setOnClickListener(v -> { cur[0] = 0; showTab[0].run(); });
        tabs[1].setOnClickListener(v -> { cur[0] = 1; showTab[0].run(); });
        showTab[0].run();

        root.findViewById(R.id.track_cancel).setOnClickListener(v -> dlg.dismiss());
        root.findViewById(R.id.track_ok).setOnClickListener(v -> {
            TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
            for (int k = 0; k < 2; k++) {
                b.setTrackTypeDisabled(types[k], views[k].getIsDisabled());
                b.clearOverridesOfType(types[k]);
                for (TrackSelectionOverride o : views[k].getOverrides().values()) b.addOverride(o);
            }
            player.setTrackSelectionParameters(b.build());
            dlg.dismiss();
        });

        dlg.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dlg.show();
        int w = Math.min(dp(420), (int) (getResources().getDisplayMetrics().widthPixels * 0.9f));
        dlg.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // ---------------------------------------------------------------- player

    private void initPlayer() {
        try {
            MediaSource source = buildSource(options.get(current));
            player = new ExoPlayer.Builder(this)
                    .setSeekBackIncrementMs(10000)
                    .setSeekForwardIncrementMs(10000)
                    .build();
            player.setVolume(muted ? 0f : 1f);
            playerView.setPlayer(player);
            player.addListener(new Player.Listener() {
                @Override
                public void onVideoSizeChanged(VideoSize size) {
                    if (size.height > 0) {
                        int n = size.width > 0 ? Math.min(size.width, size.height) : size.height;
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    String detail = error.getErrorCodeName();
                    Throwable cause = error;
                    for (int depth = 0; depth < 6 && cause != null; depth++, cause = cause.getCause()) {
                        String message = cause.getMessage();
                        if (message != null && !message.trim().isEmpty()) {
                            detail += "\n" + message;
                            break;
                        }
                    }
                    Toast.makeText(PlayerActivity.this,
                            getString(R.string.gagal_putar, detail), Toast.LENGTH_LONG).show();
                }
            });
            if (resumePos > 0) player.setMediaSource(source, resumePos);
            else player.setMediaSource(source);
            player.prepare();
            player.setPlayWhenReady(true);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.gagal_putar, e.toString()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void releasePlayer() {
        if (player != null) {
            resumePos = player.getCurrentPosition();
            player.release();
            player = null;
        }
        playerView.setPlayer(null);
    }

    // ---------------------------------------------------------------- sumber

    private static Map<String, String> toMap(JSONObject h) {
        Map<String, String> m = new HashMap<>();
        if (h == null) return m;
        Iterator<String> it = h.keys();
        while (it.hasNext()) {
            String k = it.next();
            m.put(k, h.optString(k));
        }
        return m;
    }

    private DataSource.Factory dataFactory(JSONObject headers) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setDefaultRequestProperties(toMap(headers));
        // DefaultDataSource: http(s) lewat factory di atas, file:// untuk master .m3u8 lokal
        return new DefaultDataSource.Factory(this, http);
    }

    private MediaSource buildSource(JSONObject o) throws Exception {
        String kind = o.optString("kind", "single");
        JSONObject headers = o.optJSONObject("headers");

        switch (kind) {
            case "hls":
            case "hls_master":
                return new HlsMediaSource.Factory(dataFactory(headers))
                        .createMediaSource(MediaItem.fromUri(o.getString("url")));

            case "hls_split": {
                File f = new File(getCacheDir(), "master_split.m3u8");
                try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                    w.write(o.getString("master_text"));
                }
                return new HlsMediaSource.Factory(dataFactory(headers))
                        .createMediaSource(MediaItem.fromUri(Uri.fromFile(f)));
            }

            case "dash":
                return new DashMediaSource.Factory(dataFactory(headers))
                        .createMediaSource(MediaItem.fromUri(o.getString("url")));

            case "merge": {
                MediaSource v = new ProgressiveMediaSource.Factory(dataFactory(headers))
                        .createMediaSource(MediaItem.fromUri(o.getString("url")));
                MediaSource a = new ProgressiveMediaSource.Factory(
                        dataFactory(o.optJSONObject("audio_headers")))
                        .createMediaSource(MediaItem.fromUri(o.getString("audio_url")));
                return new MergingMediaSource(v, a);
            }

            default: // "single"
                return new DefaultMediaSourceFactory(dataFactory(headers))
                        .createMediaSource(MediaItem.fromUri(o.getString("url")));
        }
    }
}
