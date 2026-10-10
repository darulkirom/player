package com.baru.player;

import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.navigation.fragment.NavHostFragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

/**
 * Daftar menu pengaturan (PreferenceFragmentCompat, lihat res/xml/preferences_main.xml).
 * Item yang punya layar rincian dibuka lewat Navigation; sisanya masih "belum tersedia".
 */
public class SettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_main, rootKey);
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if ("appearance".equals(preference.getKey())) {
            NavHostFragment.findNavController(this).navigate(R.id.appearanceFragment);
            return true;
        }
        Toast.makeText(requireContext(), R.string.segera_hadir, Toast.LENGTH_SHORT).show();
        return true;
    }
}
