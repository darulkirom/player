package com.baru.player;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceDataStore;
import androidx.preference.PreferenceFragmentCompat;

/**
 * Layar Appearance (PreferenceFragmentCompat): Theme Mode dan Theme Style.
 * Nilai tetap disimpan oleh ThemeStore (lewat PreferenceDataStore), jadi
 * logika penerapan tema yang lama tidak berubah.
 */
public class AppearanceFragment extends PreferenceFragmentCompat {

    private final PreferenceDataStore store = new PreferenceDataStore() {
        @Nullable
        @Override
        public String getString(String key, @Nullable String defValue) {
            if ("theme_mode".equals(key)) return String.valueOf(ThemeStore.getMode(requireContext()));
            if ("theme_style".equals(key)) return String.valueOf(ThemeStore.getStyle(requireContext()));
            return defValue;
        }

        @Override
        public void putString(String key, @Nullable String value) {
            if (value == null) return;
            int v = Integer.parseInt(value);
            if ("theme_mode".equals(key)) {
                if (v != ThemeStore.getMode(requireContext())) ThemeStore.setMode(requireContext(), v);
            } else if ("theme_style".equals(key)) {
                if (v != ThemeStore.getStyle(requireContext())) ThemeStore.setStyle(requireActivity(), v);
            }
        }
    };

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        getPreferenceManager().setPreferenceDataStore(store);
        setPreferencesFromResource(R.xml.preferences_appearance, rootKey);
        PrefIcons.tint(requireContext(), getPreferenceScreen());
    }
}
