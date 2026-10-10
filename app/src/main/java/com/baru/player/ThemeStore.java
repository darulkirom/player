package com.baru.player;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;

/** Menyimpan & menerapkan pilihan Theme Mode (terang/gelap/ikut sistem) dan Theme Style. */
final class ThemeStore {
    private ThemeStore() {}

    private static final String PREFS = "theme";
    private static final String KEY_MODE = "mode";
    private static final String KEY_STYLE = "style";

    /** Urutan sama dengan array string theme_modes. */
    static final int[] NIGHT_MODES = {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES};

    /** Urutan sama dengan array string theme_styles; indeks terakhir = Dynamic Theme. */
    static final int[] STYLES = {
            R.style.Theme_App_IgniteBlaze,
            R.style.Theme_App_FrostyAura,
            R.style.Theme_App_EarthenElegance,
            R.style.Theme_App_EnchantedGrove,
            R.style.Theme_App_RedRose,
            0};
    static final int DYNAMIC = STYLES.length - 1;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int getMode(Context c) { return sp(c).getInt(KEY_MODE, 0); }

    static int getStyle(Context c) {
        int s = sp(c).getInt(KEY_STYLE, DYNAMIC);
        return s >= 0 && s < STYLES.length ? s : DYNAMIC;
    }

    static void setMode(Context c, int mode) {
        sp(c).edit().putInt(KEY_MODE, mode).apply();
        AppCompatDelegate.setDefaultNightMode(NIGHT_MODES[mode]); // activity dibuat ulang otomatis
    }

    static void setStyle(Activity a, int style) {
        sp(a).edit().putInt(KEY_STYLE, style).apply();
        a.recreate();
    }

    /** Panggil di onCreate SEBELUM super.onCreate. */
    static void applyMode(Context c) {
        AppCompatDelegate.setDefaultNightMode(NIGHT_MODES[getMode(c)]);
    }

    /** Panggil di onCreate setelah super.onCreate, sebelum setContentView. */
    static void applyStyle(Activity a) {
        int s = getStyle(a);
        if (s == DYNAMIC) DynamicColors.applyToActivityIfAvailable(a);
        else a.getTheme().applyStyle(STYLES[s], true);
    }
}
