package com.baru.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Circular translucent gesture HUD with a value arc, matching the supplied player reference. */
public final class GestureRingView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();
    private float progress = 50f;
    private float density;

    public GestureRingView(Context context) { super(context); init(); }
    public GestureRingView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public GestureRingView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setProgress(int value) {
        progress = Math.max(0, Math.min(100, value));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - 4f * density;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x66000000);
        canvas.drawCircle(cx, cy, radius - 2f * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3.5f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xFFFFFFFF);
        float inset = 4.5f * density;
        arcBounds.set(cx - radius + inset, cy - radius + inset, cx + radius - inset, cy + radius - inset);
        canvas.drawArc(arcBounds, -90f, 360f * progress / 100f, false, paint);
    }
}
