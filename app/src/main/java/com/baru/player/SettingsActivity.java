package com.baru.player;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/** Wadah untuk SettingsFragment. */
public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Ui.applySystemBarPadding(findViewById(R.id.settings_container));
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container, new SettingsFragment())
                    .commit();
        }
    }
}
