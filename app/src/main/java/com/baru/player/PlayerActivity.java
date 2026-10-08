package com.baru.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
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
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
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
 * Pemutar ExoPlayer. Menggantikan proxy localhost + ffmpeg di baru.sh:
 * video-only + audio-only digabung langsung oleh MergingMediaSource.
 *
 * Fitur: pilih kualitas (track HLS/DASH), orientasi layar, resize mode,
 * dan fullscreen penuh (sembunyikan bar sistem, isi area notch).
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_OPTION = "option";
    public static final String EXTRA_TITLE = "title";

    private static final String PREFS = "player_prefs";
    private static final String KEY_ORIENT = "orient";
    private static final String KEY_RESIZE = "resize";

    // Urutan sama dengan string-array orientasi_opsi
    private static final int[] ORIENT_VALUES = {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    };
    // Urutan sama dengan string-array ukuran_opsi
    private static final int[] RESIZE_VALUES = {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
    };

    private PlayerView playerView;
    private View topBar;
    private ExoPlayer player;
    private JSONObject option;
    private SharedPreferences prefs;
    private int orientIdx;
    private int resizeIdx;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerView = findViewById(R.id.player_view);
        topBar = findViewById(R.id.top_bar);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        setupFullscreen();

        try {
            option = new JSONObject(getIntent().getStringExtra(EXTRA_OPTION));
        } catch (Exception e) {
            Toast.makeText(this, R.string.opsi_tidak_valid, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null && !title.isEmpty()) {
            setTitle(title);
            ((TextView) findViewById(R.id.tv_title)).setText(title);
        }

        // Pulihkan pilihan terakhir (default: orientasi otomatis, resize Zoom = penuhi layar)
        orientIdx = clamp(prefs.getInt(KEY_ORIENT, 0), ORIENT_VALUES.length);
        resizeIdx = clamp(prefs.getInt(KEY_RESIZE, 0), RESIZE_VALUES.length);
        applyOrientation();
        applyResize();

        // Bar atas ikut tampil/sembunyi bersama kontrol pemutar
        playerView.setControllerVisibilityListener(
                (PlayerView.ControllerVisibilityListener) visibility -> topBar.setVisibility(visibility));
        topBar.setVisibility(playerView.isControllerFullyVisible() ? View.VISIBLE : View.GONE);

        findViewById(R.id.btn_quality).setOnClickListener(v -> showQualityDialog());
        findViewById(R.id.btn_orientation).setOnClickListener(v -> showOrientationDialog());
        findViewById(R.id.btn_resize).setOnClickListener(v -> showResizeDialog());
    }

    private static int clamp(int v, int size) {
        return (v < 0 || v >= size) ? 0 : v;
    }

    // ------------------------------------------------------------ fullscreen

    private void setupFullscreen() {
        // Gambar sampai ke tepi layar, termasuk area notch / lubang kamera
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        // Supaya tombol di bar atas tidak tertutup notch
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            Insets i = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            topBar.setPadding(i.left + dp(8), i.top, i.right + dp(8), 0);
            return insets;
        });
        hideSystemBars();
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat c =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsetsCompat.Type.systemBars());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars(); // setelah dialog tertutup, bar sistem disembunyikan lagi
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------ orientasi & ukuran

    private void applyOrientation() {
        setRequestedOrientation(ORIENT_VALUES[orientIdx]);
    }

    private void applyResize() {
        playerView.setResizeMode(RESIZE_VALUES[resizeIdx]);
    }

    private void showOrientationDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.judul_orientasi)
                .setSingleChoiceItems(R.array.orientasi_opsi, orientIdx, (d, which) -> {
                    orientIdx = which;
                    prefs.edit().putInt(KEY_ORIENT, which).apply();
                    applyOrientation();
                    d.dismiss();
                })
                .setNegativeButton(R.string.batal, null)
                .show();
    }

    private void showResizeDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.judul_ukuran)
                .setSingleChoiceItems(R.array.ukuran_opsi, resizeIdx, (d, which) -> {
                    resizeIdx = which;
                    prefs.edit().putInt(KEY_RESIZE, which).apply();
                    applyResize();
                    d.dismiss();
                })
                .setNegativeButton(R.string.batal, null)
                .show();
    }

    // --------------------------------------------------------------- kualitas

    private static final class VideoTrack {
        final Tracks.Group group;
        final int index;
        final Format format;

        VideoTrack(Tracks.Group g, int i) {
            group = g;
            index = i;
            format = g.getTrackFormat(i);
        }
    }

    private void showQualityDialog() {
        if (player == null) return;
        Tracks tracks = player.getCurrentTracks();

        List<VideoTrack> list = new ArrayList<>();
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < g.length; i++) {
                if (g.isTrackSupported(i)) list.add(new VideoTrack(g, i));
            }
        }
        if (list.isEmpty()) {
            Toast.makeText(this, R.string.kualitas_belum_siap, Toast.LENGTH_SHORT).show();
            return;
        }
        if (list.size() == 1) {
            Toast.makeText(this, R.string.kualitas_satu, Toast.LENGTH_LONG).show();
            return;
        }
        // Tinggi terbesar dulu, lalu bitrate terbesar
        Collections.sort(list, (a, b) -> {
            if (a.format.height != b.format.height) return b.format.height - a.format.height;
            return b.format.bitrate - a.format.bitrate;
        });

        TrackSelectionParameters params = player.getTrackSelectionParameters();
        final String[] labels = new String[list.size() + 1];
        labels[0] = getString(R.string.kualitas_auto);
        int checked = 0;
        for (int n = 0; n < list.size(); n++) {
            VideoTrack t = list.get(n);
            labels[n + 1] = qualityLabel(t.format);
            TrackSelectionOverride ov = params.overrides.get(t.group.getMediaTrackGroup());
            if (ov != null && ov.trackIndices.contains(t.index)) checked = n + 1;
        }

        final List<VideoTrack> fl = list;
        new AlertDialog.Builder(this)
                .setTitle(R.string.judul_kualitas)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    if (player != null) {
                        TrackSelectionParameters.Builder b = player.getTrackSelectionParameters()
                                .buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_VIDEO);
                        if (which > 0) {
                            VideoTrack t = fl.get(which - 1);
                            b.setOverrideForType(new TrackSelectionOverride(
                                    t.group.getMediaTrackGroup(), t.index));
                        }
                        player.setTrackSelectionParameters(b.build());
                    }
                    d.dismiss();
                })
                .setNegativeButton(R.string.batal, null)
                .show();
    }

    private static String qualityLabel(Format f) {
        StringBuilder sb = new StringBuilder();
        sb.append(f.height > 0 ? f.height + "p" : "?");
        if (f.frameRate > 0 && Math.round(f.frameRate) > 30) {
            sb.append(Math.round(f.frameRate));
        }
        if (f.bitrate > 0) {
            sb.append("  (").append(Math.round(f.bitrate / 1000f)).append(" kbps)");
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------- player

    @Override
    protected void onStart() {
        super.onStart();
        if (player == null && option != null) initPlayer();
    }

    @Override
    protected void onStop() {
        super.onStop();
        releasePlayer();
    }

    private void initPlayer() {
        try {
            MediaSource source = buildSource(option);
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);
            player.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException error) {
                    Toast.makeText(PlayerActivity.this,
                            getString(R.string.gagal_putar, error.getErrorCodeName()),
                            Toast.LENGTH_LONG).show();
                }
            });
            player.setMediaSource(source);
            player.prepare();
            player.setPlayWhenReady(true);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.gagal_putar, e.toString()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
        playerView.setPlayer(null);
    }

    // ------------------------------------------------------------ sumber

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
                // master playlist buatan sendiri (video & audio terpisah), ditulis ke cache
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
