package com.baru.player;

import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Media3 PlayerView dengan controller bawaan Media3. */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_OPTION = "option";
    public static final String EXTRA_TITLE = "title";

    private PlayerView playerView;
    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private JSONObject option;
    private boolean fullscreen = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        playerView = findViewById(R.id.player_view);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setControllerAutoShow(true);
        playerView.setControllerHideOnTouch(true);
        playerView.setControllerShowTimeoutMs(3500);

        try {
            option = new JSONObject(getIntent().getStringExtra(EXTRA_OPTION));
        } catch (Exception e) {
            Toast.makeText(this, R.string.opsi_tidak_valid, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Fullscreen adalah tombol bawaan Media3, bukan tombol buatan sendiri.
        playerView.setFullscreenButtonClickListener(isFullscreen -> {
            if (isFullscreen) enterFullscreen();
            else exitFullscreen();
        });
        playerView.setFullscreenButtonState(true);

        // Gunakan tombol Settings bawaan Media3 untuk menu tambahan kita.
        View settings = playerView.findViewById(androidx.media3.ui.R.id.exo_settings);
        if (settings != null) {
            settings.setOnClickListener(v -> showPlayerSettings());
        }

        enterFullscreen();
    }

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

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullscreen) applyImmersive();
    }

    private void initPlayer() {
        try {
            trackSelector = new DefaultTrackSelector(this);
            player = new ExoPlayer.Builder(this)
                    .setTrackSelector(trackSelector)
                    .build();
            playerView.setPlayer(player);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException error) {
                    Toast.makeText(PlayerActivity.this,
                            getString(R.string.gagal_putar, error.getErrorCodeName()),
                            Toast.LENGTH_LONG).show();
                }
            });

            player.setMediaSource(buildSource(option));
            player.prepare();
            player.play();
        } catch (Exception e) {
            Toast.makeText(this,
                    getString(R.string.gagal_putar, e.toString()),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Menu dari tombol Settings bawaan Media3. */
    private void showPlayerSettings() {
        String[] items = {
                getString(R.string.pilih_resolusi),
                "Ukuran video: " + resizeLabel(),
                getString(R.string.pilih_orientasi)
        };
        new AlertDialog.Builder(this)
                .setTitle("Pengaturan pemutar")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) showQualityDialog();
                    else if (which == 1) toggleResizeMode();
                    else showOrientationDialog();
                })
                .setNegativeButton(R.string.batal, null)
                .show();
    }

    private String resizeLabel() {
        return playerView.getResizeMode() == AspectRatioFrameLayout.RESIZE_MODE_FILL
                ? "FILL" : "FIT";
    }

    private void showQualityDialog() {
        if (player == null) return;
        Tracks tracks = player.getCurrentTracks();
        ArrayList<QualityItem> items = new ArrayList<>();

        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
            TrackGroup tg = group.getMediaTrackGroup();
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format f = tg.getFormat(i);
                if (f.height <= 0) continue;
                String label = f.height + "p";
                if (f.width > 0) label += "  •  " + f.width + "×" + f.height;
                boolean exists = false;
                for (QualityItem q : items) {
                    if (q.height == f.height) { exists = true; break; }
                }
                if (!exists) items.add(new QualityItem(group, i, f.height, label));
            }
        }

        if (items.isEmpty()) {
            Toast.makeText(this, R.string.resolusi_tidak_tersedia, Toast.LENGTH_SHORT).show();
            return;
        }
        Collections.sort(items, (a, b) -> Integer.compare(b.height, a.height));
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) labels[i] = items.get(i).label;

        new AlertDialog.Builder(this)
                .setTitle(R.string.pilih_resolusi)
                .setItems(labels, (d, which) -> selectQuality(items.get(which)))
                .setNegativeButton(R.string.batal, null)
                .show();
    }

    private void selectQuality(QualityItem item) {
        if (trackSelector == null) return;
        DefaultTrackSelector.Parameters.Builder b = trackSelector.buildUponParameters();
        b.clearOverridesOfType(C.TRACK_TYPE_VIDEO);
        b.addOverride(new androidx.media3.common.TrackSelectionOverride(
                item.group.getMediaTrackGroup(), Collections.singletonList(item.trackIndex)));
        trackSelector.setParameters(b);
    }

    private void toggleResizeMode() {
        if (playerView.getResizeMode() == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
        } else {
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
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
                .setItems(choices, (d, which) -> {
                    if (which == 0) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
                    else if (which == 1) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    else setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                })
                .setNegativeButton(R.string.batal, null)
                .show();
    }

    private void enterFullscreen() {
        fullscreen = true;
        playerView.setFullscreenButtonState(true);
        applyImmersive();
    }

    private void exitFullscreen() {
        fullscreen = false;
        playerView.setFullscreenButtonState(false);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void applyImmersive() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
        if (playerView != null) playerView.setPlayer(null);
    }

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
                MediaSource a = new ProgressiveMediaSource.Factory(dataFactory(o.optJSONObject("audio_headers")))
                        .createMediaSource(MediaItem.fromUri(o.getString("audio_url")));
                return new MergingMediaSource(v, a);
            }
            default:
                return new DefaultMediaSourceFactory(dataFactory(headers))
                        .createMediaSource(MediaItem.fromUri(o.getString("url")));
        }
    }

    private static class QualityItem {
        final Tracks.Group group;
        final int trackIndex;
        final int height;
        final String label;
        QualityItem(Tracks.Group group, int trackIndex, int height, String label) {
            this.group = group; this.trackIndex = trackIndex; this.height = height; this.label = label;
        }
    }
}
