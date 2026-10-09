package com.baru.player;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TrackSelectionDialogBuilder;

import java.io.File;

public class PlayerActivity extends AppCompatActivity {

    // --- KONSTANTA INTENT UNTUK MAINACTIVITY ---
    public static final String EXTRA_OPTIONS = "EXTRA_OPTIONS";
    public static final String EXTRA_INDEX = "EXTRA_INDEX";
    public static final String EXTRA_TITLE = "EXTRA_TITLE";

    private ExoPlayer player;
    private PlayerView playerView;
    private boolean isLocked = false;

    // Status Resize Mode
    private int currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;

    // Tombol UI Control
    private ImageButton btnVideoTracks;
    private ImageButton btnAudioTracks;
    private ImageButton btnSubtitleTracks;
    private ImageButton btnResizeMode;
    private ImageButton btnLock;
    private ImageButton btnUnlock;
    private ImageButton btnPip;
    private ImageButton btnRotate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        playerView = findViewById(R.id.player_view);

        // 1. Inisialisasi Player Media3
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        // 2. Jalankan LocalServer Menggunakan Singleton bawaan
        File serverRoot = getCacheDir(); // Folder penyimpanan master .m3u8
        boolean serverRunning = LocalServer.ensureStarted(serverRoot);
        if (!serverRunning) {
            Toast.makeText(this, "Gagal menjalankan LocalServer internal", Toast.LENGTH_SHORT).show();
        }

        // 3. Inisialisasi Tombol Kontrol
        initControls();

        // 4. Tangani Intent yang Masuk
        handleIncomingIntent(getIntent());
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;

        Uri videoUri = null;

        // Intent dari Aplikasi Luar (File Manager/Browser)
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            videoUri = intent.getData();
        } 
        // Intent Internal dari MainActivity
        else if (intent.hasExtra("VIDEO_URL")) {
            String urlString = intent.getStringExtra("VIDEO_URL");
            if (urlString != null && !urlString.isEmpty()) {
                videoUri = Uri.parse(urlString);
            }
        }

        if (videoUri != null) {
            MediaItem mediaItem = MediaItem.fromUri(videoUri);
            player.setMediaItem(mediaItem);
            player.prepare();
            player.play();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void initControls() {
        // --- DIALOG TRACK MEDIA3 ---
        btnVideoTracks = findViewById(R.id.btn_video_tracks);
        if (btnVideoTracks != null) {
            btnVideoTracks.setOnClickListener(v -> showTrackSelectionDialog(C.TRACK_TYPE_VIDEO, "Pilih Kualitas Video"));
        }

        btnAudioTracks = findViewById(R.id.btn_audio_tracks);
        if (btnAudioTracks != null) {
            btnAudioTracks.setOnClickListener(v -> showTrackSelectionDialog(C.TRACK_TYPE_AUDIO, "Pilih Trek Audio"));
        }

        btnSubtitleTracks = findViewById(R.id.btn_subtitle_tracks);
        if (btnSubtitleTracks != null) {
            btnSubtitleTracks.setOnClickListener(v -> showTrackSelectionDialog(C.TRACK_TYPE_TEXT, "Pilih Subtitle"));
        }

        // --- RESIZE MODE (FIT -> FILL -> ZOOM) ---
        btnResizeMode = findViewById(R.id.btn_resize_mode);
        if (btnResizeMode != null) {
            btnResizeMode.setOnClickListener(v -> toggleResizeMode());
        }

        // --- CUSTOM LOCK SCREEN ---
        btnLock = findViewById(R.id.btn_lock);
        btnUnlock = findViewById(R.id.btn_unlock);

        if (btnLock != null) {
            btnLock.setOnClickListener(v -> setScreenLocked(true));
        }
        if (btnUnlock != null) {
            btnUnlock.setOnClickListener(v -> setScreenLocked(false));
        }

        // --- PIP & ROTASI LAYAR (Null-Safe) ---
        btnPip = findViewById(R.id.btn_pip);
        if (btnPip != null) {
            btnPip.setOnClickListener(v -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    enterPictureInPictureMode();
                }
            });
        }

        btnRotate = findViewById(R.id.btn_rotate);
        if (btnRotate != null) {
            btnRotate.setOnClickListener(v -> {
                int orientation = getRequestedOrientation();
                if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                } else {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                }
            });
        }
    }

    private void showTrackSelectionDialog(int trackType, String title) {
        if (player == null) return;

        new TrackSelectionDialogBuilder(this, title, player, trackType)
                .setAllowAdaptiveSelections(true)
                .setShowDisableOption(true)
                .setTheme(R.style.TrackSelectionDialogTheme)
                .build()
                .show();
    }

    private void toggleResizeMode() {
        if (playerView == null) return;

        switch (currentResizeMode) {
            case AspectRatioFrameLayout.RESIZE_MODE_FIT:
                currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL;
                Toast.makeText(this, "Mode: Fill", Toast.LENGTH_SHORT).show();
                break;
            case AspectRatioFrameLayout.RESIZE_MODE_FILL:
                currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
                Toast.makeText(this, "Mode: Zoom", Toast.LENGTH_SHORT).show();
                break;
            case AspectRatioFrameLayout.RESIZE_MODE_ZOOM:
            default:
                currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
                Toast.makeText(this, "Mode: Fit", Toast.LENGTH_SHORT).show();
                break;
        }

        playerView.setResizeMode(currentResizeMode);
    }

    private void setScreenLocked(boolean locked) {
        isLocked = locked;
        if (isLocked) {
            playerView.setUseController(false);
            if (btnUnlock != null) btnUnlock.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Layar Dikunci", Toast.LENGTH_SHORT).show();
        } else {
            playerView.setUseController(true);
            playerView.showController();
            if (btnUnlock != null) btnUnlock.setVisibility(View.GONE);
            Toast.makeText(this, "Layar Terbuka", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
