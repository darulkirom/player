package com.baru.player;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/** FrameLayout dengan tinggi = lebar x 9/16 (kotak thumbnail video). */
public class RatioFrameLayout extends FrameLayout {
    public RatioFrameLayout(Context c) { super(c); }

    public RatioFrameLayout(Context c, @Nullable AttributeSet a) { super(c, a); }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = Math.round(w * 9f / 16f);
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }
}
