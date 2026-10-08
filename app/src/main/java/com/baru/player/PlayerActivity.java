package com.baru.player;

import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
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
 * Pemutar ExoPlayer. Menggantikan proxy localhost + ffmpeg di baru.sh:
 * video-only + audio-only digabung langsung oleh MergingMediaSource.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_OPTION = "option";
    public static final String EXTRA_TITLE = "title";

    private PlayerView playerView;
    private ExoPlayer player;
    private JSONObject option;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        playerView = findViewById(R.id.player_view);

        try {
            option = new JSONObject(getIntent().getStringExtra(EXTRA_OPTION));
        } catch (Exception e) {
            Toast.makeText(this, R.string.opsi_tidak_valid, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null && !title.isEmpty()) setTitle(title);
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
