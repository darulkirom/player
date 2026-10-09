package com.baru.player;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
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

    public static final String EXTRA_OPTIONS = "EXTRA_OPTIONS";
    public static final String EXTRA_INDEX = "EXTRA_INDEX";
    public static final String EXTRA_TITLE = "EXTRA_TITLE";

    private ExoPlayer player;
    private PlayerView playerView;
    private boolean isLocked = false;

    private int currentResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;

    // View & Button dari layout player_controls.xml berbasis merge
    private TextView tvTitle;
    private ImageButton btnBack;
    private ImageButton btnLockPlayer;
    private TextView tvResizeMode;
    private ImageButton btnSubtitle;
    private ImageButton btnSelectTrack;
    private ImageButton btnPip;

    // Gestur Audio & Kecerahan
    private AudioManager audioManager;
    private GestureDetector gestureDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        playerView = findViewById(R.id.player_view);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        File serverRoot = getCacheDir();
        boolean serverRunning = LocalServer.ensureStarted(serverRoot);
        if (!serverRunning) {
            Toast.makeText(this, "Gagal menjalankan LocalServer internal", Toast.LENGTH_SHORT).show();
        }

        initControls();
        initGestures();
        handleIncomingIntent(getIntent());
    }

    private void initControls() {
        // Top Bar Controls
        btnBack = findViewById(R.id.back_button);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

        tvTitle = findViewById(R.id.titleTextView);

        btnLockPlayer = findViewById(R.id.lock_player);
        if (btnLockPlayer != null) {
            btnLockPlayer.setOnClickListener(v -> toggleScreenLock());
        }

        // Bottom Controls
        tvResizeMode = findViewById(R.id.exo_resizeTextView);
        if (tvResizeMode != null) {
            tvResizeMode.setOnClickListener(v -> toggleResizeMode());
        }

        btnSubtitle = findViewById(R.id.exo_subtitle);
        if (btnSubtitle != null) {
            btnSubtitle.setOnClickListener(v -> showTrackSelectionDialog(C.TRACK_TYPE_TEXT, "Pilih Subtitle"));
        }

        btnSelectTrack = findViewById(R.id.exo_select_track);
        if (btnSelectTrack != null) {
            btnSelectTrack.setOnClickListener(v -> showTrackSelectionDialog(C.TRACK_TYPE_VIDEO, "Pilih Kualitas Video"));
        }

        btnPip = findViewById(R.id.exo_pip);
        if (btnPip != null) {
            btnPip.setOnClickListener(v -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    enterPictureInPictureMode();
                }
            });
        }
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;

        // Set Judul jika dikirim via Extra
        if (intent.hasExtra(EXTRA_TITLE) && tvTitle != null) {
            tvTitle.setText(intent.getStringExtra(EXTRA_TITLE));
        }

        Uri videoUri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            videoUri = intent.getData();
        } else if (intent.hasExtra("VIDEO_URL")) {
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

    private void initGestures() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (isLocked || e1 == null || e2 == null) return false;

                float deltaY = e1.getY() - e2.getY();
                float deltaX = e1.getX() - e2.getX();

                if (Math.abs(deltaY) > Math.abs(deltaX)) {
                    int screenWidth = getResources().getDisplayMetrics().widthPixels;
                    if (e1.getX() < (float) screenWidth / 2) {
                        adjustBrightness(deltaY);
                    } else {
                        adjustVolume(deltaY);
                    }
                    return true;
                }
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (isLocked || player == null || e == null) return false;
                int screenWidth = getResources().getDisplayMetrics().widthPixels;

                if (e.getX() > (float) screenWidth / 2) {
                    player.seekTo(player.getCurrentPosition() + 10000);
                    Toast.makeText(PlayerActivity.this, "+10 dtk", Toast.LENGTH_SHORT).show();
                } else {
                    player.seekTo(Math.max(0, player.getCurrentPosition() - 10000));
                    Toast.makeText(PlayerActivity.this, "-10 dtk", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        playerView.setOnTouchListener((v, event) -> {
            gestureDetector.onTouchEvent(event);
            return false;
        });
    }

    private void adjustVolume(float deltaY) {
        if (audioManager == null) return;
        int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

        int step = deltaY > 0 ? 1 : -1;
        int newVol = Math.max(0, Math.min(maxVol, currentVol + step));
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0);

        Toast.makeText(this, "Volume: " + (newVol * 100 / maxVol) + "%", Toast.LENGTH_SHORT).show();
    }

    private void adjustBrightness(float deltaY) {
        Window window = getWindow();
        WindowManager.LayoutParams lp = window.getAttributes();
        float brightness = lp.screenBrightness;

        if (brightness < 0) brightness = 0.5f;

        brightness += (deltaY > 0 ? 0.05f : -0.05f);
        brightness = Math.max(0.01f, Math.min(1.0f, brightness));

        lp.screenBrightness = brightness;
        window.setAttributes(lp);

        Toast.makeText(this, "Kecerahan: " + (int) (brightness * 100) + "%", Toast.LENGTH_SHORT).show();
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

    private void toggleScreenLock() {
        isLocked = !isLocked;
        if (isLocked) {
            playerView.setUseController(false);
            if (btnLockPlayer != null) {
                btnLockPlayer.setImageResource(R.drawable.ic_lock);
            }
            Toast.makeText(this, "Layar Dikunci", Toast.LENGTH_SHORT).show();
        } else {
            playerView.setUseController(true);
            playerView.showController();
            if (btnLockPlayer != null) {
                btnLockPlayer.setImageResource(R.drawable.ic_unlocked);
            }
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
