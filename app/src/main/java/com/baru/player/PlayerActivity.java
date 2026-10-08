package com.baru.player;

import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
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

    private static final int[] RESIZE_MODES = {
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT,
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
    private ImageButton unlockBtn;
    private ImageButton muteBtn;
    private TextView qualityBtn;
    private TextView resizeText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideUnlock = () -> unlockBtn.setVisibility(View.GONE);
    private boolean locked;
    private boolean muted;
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
        unlockBtn = findViewById(R.id.btn_unlock);
        // tampilan kontrol (player_controls.xml) ada di dalam PlayerView
        View topControls = playerView.findViewById(R.id.top_controls);
        View bottomWrapper = playerView.findViewById(R.id.bottom_wrapper);
        TextView titleView = playerView.findViewById(R.id.titleTextView);
        muteBtn = playerView.findViewById(R.id.btn_mute);
        qualityBtn = playerView.findViewById(R.id.btn_quality);
        resizeText = playerView.findViewById(R.id.resizeTextView);
        View pipBtn = playerView.findViewById(R.id.btn_pip);

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
        qualityBtn.setOnClickListener(v -> showQualityDialog());
        playerView.findViewById(R.id.btn_rotate).setOnClickListener(v -> showOrientationDialog());
        resizeText.setOnClickListener(v -> showResizeDialog());

        boolean pipOk = Build.VERSION.SDK_INT >= 26
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        pipBtn.setVisibility(pipOk ? View.VISIBLE : View.GONE);
        pipBtn.setOnClickListener(v -> enterPip());

        lockLayer.setOnClickListener(v -> showUnlockTemporarily());
        unlockBtn.setOnClickListener(v -> setLocked(false));

        // Beri ruang untuk notch / bar sistem di kontrol atas, bawah, dan tombol buka kunci
        padWithInsets(topControls, true, false);
        padWithInsets(bottomWrapper, false, true);
        padWithInsets(lockLayer, true, false);

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
        handler.removeCallbacks(hideUnlock);
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
        new AlertDialog.Builder(this)
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
        new AlertDialog.Builder(this)
                .setTitle(R.string.rotasi)
                .setSingleChoiceItems(R.array.orientasi, orientIdx, (d, which) -> {
                    orientIdx = which;
                    prefs.edit().putInt("orient", which).apply();
                    applyOrientation();
                    d.dismiss();
                })
                .show();
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
        handler.removeCallbacks(hideUnlock);
        if (lock) {
            playerView.hideController();
            playerView.setUseController(false);
            lockLayer.setVisibility(View.VISIBLE);
            showUnlockTemporarily();
        } else {
            lockLayer.setVisibility(View.GONE);
            unlockBtn.setVisibility(View.GONE);
            playerView.setUseController(true);
            playerView.showController();
        }
    }

    private void showUnlockTemporarily() {
        unlockBtn.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideUnlock);
        handler.postDelayed(hideUnlock, 3000);
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
        new AlertDialog.Builder(this)
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
                        qualityBtn.setText(n + "p");
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Toast.makeText(PlayerActivity.this,
                            getString(R.string.gagal_putar, error.getErrorCodeName()),
                            Toast.LENGTH_LONG).show();
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
