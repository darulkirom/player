package com.baru.player;

import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.core.graphics.drawable.DrawableCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;

import com.google.android.material.color.MaterialColors;

/** Mewarnai ikon preferensi dengan warna teks tema (ikon vektor kita berwarna putih). */
final class PrefIcons {
    private PrefIcons() {}

    static void tint(Context c, PreferenceGroup group) {
        int color = MaterialColors.getColor(c,
                com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF);
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference p = group.getPreference(i);
            Drawable d = p.getIcon();
            if (d != null) {
                d = DrawableCompat.wrap(d.mutate());
                DrawableCompat.setTint(d, color);
                p.setIcon(d);
            }
            if (p instanceof PreferenceGroup) tint(c, (PreferenceGroup) p);
        }
    }
}
