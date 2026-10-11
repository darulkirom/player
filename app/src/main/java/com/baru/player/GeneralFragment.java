package com.baru.player;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceFragmentCompat;

/**
 * Layar General: pengaturan pemutar (PiP, skip silence, landscape, gesture, resume, durasi seek).
 * Disimpan di SharedPreferences bawaan (PreferenceManager); PlayerActivity membacanya
 * lewat PlayerSettings.
 */
public class GeneralFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_general, rootKey);
        PrefIcons.tint(requireContext(), getPreferenceScreen());
    }
}
