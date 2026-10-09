package com.baru.player;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

final class Ui {
    private Ui() {}

    /** Tambahkan inset bar sistem/notch ke padding yang sudah ada (Android 15 memaksa edge-to-edge). */
    static void applySystemBarPadding(View v) {
        final int l = v.getPaddingLeft();
        final int t = v.getPaddingTop();
        final int r = v.getPaddingRight();
        final int b = v.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            Insets i = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(l + i.left, t + i.top, r + i.right, b + i.bottom);
            return insets;
        });
    }
}
