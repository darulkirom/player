package com.baru.player;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/** Wadah: toolbar + NavHost (Home / Local / Samples / Playlist / Settings) + navigasi bawah. */
public class MainActivity extends AppCompatActivity {

    private NavController nav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setSupportActionBar(findViewById(R.id.main_toolbar));
        NavHostFragment host = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.main_nav_host);
        nav = host.getNavController();
        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        NavigationUI.setupWithNavController(bottomNav, nav);
        NavigationUI.setupActionBarWithNavController(this, nav,
                new AppBarConfiguration.Builder(R.id.homeFragment, R.id.localFragment,
                        R.id.samplesFragment, R.id.playlistFragment, R.id.settingsFragment).build());
    }

    /**
     * Tanda panah kembali di toolbar hanya muncul saat sebuah folder di tab Local terbuka;
     * tombol itu diteruskan ke penanganan "kembali" milik fragmen (menutup folder).
     */
    @Override
    public boolean onSupportNavigateUp() {
        getOnBackPressedDispatcher().onBackPressed();
        return true;
    }
}
