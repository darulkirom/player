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

public class PlayerActivity extends AppCompatActivity {

    private ExoPlayer player;
    private PlayerView playerView;
    
    // Server internal & gesture view bawaan proyek Anda
    private LocalServer localServer;
    private GestureRingView gestureView;
    private boolean isLocked = false;

    // Status Resize Mode
    private int currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;

    // Tombol-tombol kontrol
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

        // Inisialisasi Player Media3
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        // Inisialisasi LocalServer bawaan proyek Anda
        localServer = new LocalServer(this);
        localServer.start();

        // Inisialisasi tombol & fitur UI
        initControls();

        // Tangani Intent (Internal dari MainActivity & Eksternal dari File Manager/Browser)
        handleIncomingIntent(getIntent());
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;

        Uri videoUri = null;

        // 1. Dari aplikasi luar (ACTION_VIEW)
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            videoUri = intent.getData();
        } 
        // 2. Dari internal MainActivity
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
        // --- DIALOG MEDIA3 (Track Selection) ---
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

        // --- FITUR RESIZE MODE (FIT -> FILL -> ZOOM) ---
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

        // --- FITUR PIP & ROTASI LAYAR ---
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

        new TrackSelectionDialogBuilder(
                this,
                title,
                player,
                trackType
        )
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
        if (localServer != null) {
            localServer.stop();
        }
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
