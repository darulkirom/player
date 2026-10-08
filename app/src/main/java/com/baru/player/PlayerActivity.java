package com.baru.player;

import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
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
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Player Media3 bawaan.
 *
 * Fitur:
 * - pemilihan resolusi video dari track yang tersedia
 * - FIT / FILL
 * - Portrait / Landscape / Sensor
 * - fullscreen immersive untuk memenuhi layar HP
 * - HLS, DASH, progressive, dan video+audio terpisah
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_OPTION = "option";
    public static final String EXTRA_TITLE = "title";

    private PlayerView playerView;
    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private JSONObject option;

    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private TextView titleView;
    private Button qualityButton;
    private Button resizeButton;
    private Button orientationButton;
    private Button fullscreenButton;
    private Button fullscreenTopButton;

    private boolean fullscreen = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        setContentView(R.layout.activity_player);

        playerView = findViewById(R.id.player_view);
        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        titleView = findViewById(R.id.player_title);
        qualityButton = findViewById(R.id.btn_quality);
        resizeButton = findViewById(R.id.btn_resize);
        orientationButton = findViewById(R.id.btn_orientation);
        fullscreenButton = findViewById(R.id.btn_fullscreen);
        fullscreenTopButton = findViewById(R.id.btn_fullscreen_top);

        try {
            option = new JSONObject(getIntent().getStringExtra(EXTRA_OPTION));
        } catch (Exception e) {
            Toast.makeText(this, R.string.opsi_tidak_valid, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null && !title.isEmpty()) {
            titleView.setText(title);
        }

        resizeButton.setText("FIT");
        orientationButton.setText("ROT");
        fullscreenButton.setText("FULL");
        fullscreenTopButton.setText("FULL");

        qualityButton.setOnClickListener(v -> showQualityDialog());
        resizeButton.setOnClickListener(v -> toggleResizeMode());
        orientationButton.setOnClickListener(v -> showOrientationDialog());
        fullscreenButton.setOnClickListener(v -> toggleFullscreen());
        fullscreenTopButton.setOnClickListener(v -> toggleFullscreen());

        // Mulai dalam fullscreen agar video benar-benar menggunakan layar HP.
        enterFullscreen();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player == null && option != null) {
            initPlayer();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        releasePlayer();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullscreen) {
            applyImmersive();
        }
    }

    private void initPlayer() {
        try {
            trackSelector = new DefaultTrackSelector(this);
            trackSelector.setParameters(
                    trackSelector.buildUponParameters()
                            .setTunnelingEnabled(false)
                            .build()
            );

            MediaSource source = buildSource(option);

            player = new ExoPlayer.Builder(this)
                    .setTrackSelector(trackSelector)
                    .build();

            playerView.setPlayer(player);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException error) {
                    Toast.makeText(
                            PlayerActivity.this,
                            getString(R.string.gagal_putar, error.getErrorCodeName()),
                            Toast.LENGTH_LONG
                    ).show();
                }

                @Override
                public void onTracksChanged(Tracks tracks) {
                    qualityButton.setEnabled(hasVideoTracks(tracks));
                }
            });

            player.setMediaSource(source);
            player.prepare();
            player.setPlayWhenReady(true);

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    getString(R.string.gagal_putar, e.toString()),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private boolean hasVideoTracks(Tracks tracks) {
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_VIDEO && group.length > 0) {
                return true;
            }
        }
        return false;
    }

    private void showQualityDialog() {
        if (player == null) return;

        Tracks tracks = player.getCurrentTracks();
        final java.util.ArrayList<QualityItem> items = new java.util.ArrayList<>();

        // Kumpulkan semua resolusi video unik.
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;

            TrackGroup tg = group.getMediaTrackGroup();
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;

                Format f = tg.getFormat(i);
                if (f.height <= 0) continue;

                String label = f.height + "p";
                if (f.width > 0) {
                    label += " (" + f.width + "x" + f.height + ")";
                }

                boolean exists = false;
                for (QualityItem q : items) {
                    if (q.height == f.height) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    items.add(new QualityItem(group, i, f.height, label));
                }
            }
        }

        if (items.isEmpty()) {
            Toast.makeText(this, R.string.resolusi_tidak_tersedia, Toast.LENGTH_SHORT).show();
            return;
        }

        items.sort((a, b) -> Integer.compare(b.height, a.height));

        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            labels[i] = items.get(i).label;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.pilih_resolusi)
                .setItems(labels, (dialog, which) -> selectQuality(items.get(which)))
                .setNegativeButton(R.string.batal, null)
                .show();
    }

    private void selectQuality(QualityItem item) {
        if (trackSelector == null) return;

        DefaultTrackSelector.Parameters.Builder builder =
                trackSelector.buildUponParameters();

        builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO);
        builder.addOverride(
                new androidx.media3.common.TrackSelectionOverride(
                        item.group.getMediaTrackGroup(),
                        java.util.Collections.singletonList(item.trackIndex)
                )
        );

        trackSelector.setParameters(builder);
        Toast.makeText(this, item.label, Toast.LENGTH_SHORT).show();
    }

    private void toggleResizeMode() {
        int mode = playerView.getResizeMode();

        if (mode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
            resizeButton.setText("FILL");
        } else {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            resizeButton.setText("FIT");
        }
    }

    private void showOrientationDialog() {
        String[] choices = {
                getString(R.string.orientasi_sensor),
                getString(R.string.orientasi_portrait),
                getString(R.string.orientasi_landscape)
        };

        new AlertDialog.Builder(this)
                .setTitle(R.string.pilih_orientasi)
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) {
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
                    } else if (which == 1) {
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    } else {
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    }
                })
                .setNegativeButton(R.string.batal, null)
                .show();
    }

    private void toggleFullscreen() {
        if (fullscreen) {
            exitFullscreen();
        } else {
            enterFullscreen();
        }
    }

    private void enterFullscreen() {
        fullscreen = true;
        applyImmersive();
        fullscreenButton.setText("EXIT");
        fullscreenTopButton.setText("EXIT");
        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.VISIBLE);
    }

    private void exitFullscreen() {
        fullscreen = false;

        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }

        fullscreenButton.setText("FULL");
        fullscreenTopButton.setText("FULL");
        topBar.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
    }

    private void applyImmersive() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
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

        return new DefaultDataSource.Factory(this, http);
    }

    private MediaSource buildSource(JSONObject o) throws Exception {
        String kind = o.optString("kind", "single");
        JSONObject headers = o.optJSONObject("headers");

        switch (kind) {
            case "hls":
            case "hls_master":
                return new HlsMediaSource.Factory(dataFactory(headers))
                        .createMediaSource(
                                MediaItem.fromUri(o.getString("url"))
                        );

            case "hls_split": {
                File f = new File(getCacheDir(), "master_split.m3u8");

                try (Writer w = new OutputStreamWriter(
                        new FileOutputStream(f),
                        StandardCharsets.UTF_8
                )) {
                    w.write(o.getString("master_text"));
                }

                return new HlsMediaSource.Factory(dataFactory(headers))
                        .createMediaSource(MediaItem.fromUri(Uri.fromFile(f)));
            }

            case "dash":
                return new DashMediaSource.Factory(dataFactory(headers))
                        .createMediaSource(
                                MediaItem.fromUri(o.getString("url"))
                        );

            case "merge": {
                MediaSource v = new ProgressiveMediaSource.Factory(
                        dataFactory(headers)
                ).createMediaSource(
                        MediaItem.fromUri(o.getString("url"))
                );

                MediaSource a = new ProgressiveMediaSource.Factory(
                        dataFactory(o.optJSONObject("audio_headers"))
                ).createMediaSource(
                        MediaItem.fromUri(o.getString("audio_url"))
                );

                return new MergingMediaSource(v, a);
            }

            default:
                return new DefaultMediaSourceFactory(dataFactory(headers))
                        .createMediaSource(
                                MediaItem.fromUri(o.getString("url"))
                        );
        }
    }

    private static class QualityItem {
        final Tracks.Group group;
        final int trackIndex;
        final int height;
        final String label;

        QualityItem(Tracks.Group group, int trackIndex, int height, String label) {
            this.group = group;
            this.trackIndex = trackIndex;
            this.height = height;
            this.label = label;
        }
    }
}
