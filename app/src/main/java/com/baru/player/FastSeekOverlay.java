package com.baru.player;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

/**
 * Efek ketuk dua kali untuk maju/mundur (gaya YouTube):
 * - panel terang di setengah layar yang diketuk,
 * - tiga segitiga yang menyala bergantian + teks "N seconds",
 * - ring yang menyapu di tengah layar selama jendela ketukan berlangsung.
 * Tidak menangkap sentuhan, jadi tetap tembus ke PlayerView.
 */
public class FastSeekOverlay extends View {

    public static final long DURATION_MS = 800;

    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint triPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tintPath = new Path();
    private final Path triPath = new Path();
    private final RectF arcRect = new RectF();
    private final float dp;

    private boolean forward = true;
    private int seconds;
    private float t;                 // 0..1 sepanjang animasi
    private ValueAnimator anim;

    public FastSeekOverlay(Context c) { this(c, null); }

    public FastSeekOverlay(Context c, @Nullable AttributeSet a) {
        super(c, a);
        dp = c.getResources().getDisplayMetrics().density;

        tintPaint.setStyle(Paint.Style.FILL);
        tintPaint.setColor(0x33FFFFFF);

        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(3 * dp);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
        ringPaint.setColor(0xFFFFFFFF);

        triPaint.setStyle(Paint.Style.FILL);
        triPaint.setColor(0xFFFFFFFF);

        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(12 * dp);
        textPaint.setTextAlign(Paint.Align.CENTER);

        setVisibility(GONE);
    }

    /** Tampilkan / perbarui efek. Tiap ketukan memanggil ini lagi (animasi diulang dari awal). */
    public void show(boolean forward, int seconds) {
        this.forward = forward;
        this.seconds = seconds;

        animate().cancel();
        setAlpha(1f);
        setVisibility(VISIBLE);

        if (anim != null) anim.cancel();
        final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(DURATION_MS);
        va.setInterpolator(new LinearInterpolator());
        va.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        va.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (!cancelled) fadeAway();
            }
        });
        anim = va;
        t = 0f;
        va.start();
    }

    public void hideNow() {
        if (anim != null) anim.cancel();
        animate().cancel();
        setVisibility(GONE);
    }

    private void fadeAway() {
        animate().alpha(0f).setDuration(150)
                .withEndAction(() -> setVisibility(GONE)).start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        final float cy = h / 2f;

        // --- panel + segitiga digambar dalam orientasi "maju" (sisi kanan);
        //     untuk mundur seluruh kanvas dicerminkan terhadap garis tengah.
        canvas.save();
        if (!forward) canvas.scale(-1f, 1f, w / 2f, 0f);

        float bulge = w * 0.08f;
        tintPath.reset();
        tintPath.moveTo(w, 0);
        tintPath.lineTo(w * 0.5f + bulge, 0);
        tintPath.quadTo(w * 0.5f - bulge, cy, w * 0.5f + bulge, h);
        tintPath.lineTo(w, h);
        tintPath.close();
        canvas.drawPath(tintPath, tintPaint);

        float sideX = w * 0.75f;
        float tri = 9 * dp;              // lebar segitiga
        float gap = 3 * dp;
        float startX = sideX - (3 * tri + 2 * gap) / 2f;
        float iconY = cy - 8 * dp;
        float phase = t * 3f;
        for (int i = 0; i < 3; i++) {
            float a = Math.max(0.25f, Math.min(1f, phase - i));
            triPaint.setAlpha((int) (255 * a));
            float x = startX + i * (tri + gap);
            triPath.reset();
            triPath.moveTo(x, iconY - 6 * dp);
            triPath.lineTo(x + tri, iconY);
            triPath.lineTo(x, iconY + 6 * dp);
            triPath.close();
            canvas.drawPath(triPath, triPaint);
        }
        canvas.restore();

        // --- teks (tidak dicerminkan)
        float textX = forward ? w * 0.75f : w * 0.25f;
        canvas.drawText(getContext().getString(R.string.fast_seek_seconds, seconds),
                textX, cy + 16 * dp, textPaint);

        // --- ring di tengah layar: tumbuh di paruh pertama, menyusut di paruh kedua
        float r = 26 * dp;
        arcRect.set(w / 2f - r, cy - r, w / 2f + r, cy + r);
        float startFrac = Math.max(0f, 2f * t - 1f);
        float endFrac = Math.min(1f, 2f * t);
        float startAngle = -90f + 360f * startFrac;
        float sweep = 360f * (endFrac - startFrac);
        if (sweep > 0.5f) canvas.drawArc(arcRect, startAngle, sweep, false, ringPaint);
    }
}
