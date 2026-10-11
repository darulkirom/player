package com.baru.player;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/** Wadah: toolbar + NavHost (Home / Local / Samples / Playlist / Settings) + navigasi bawah. */
public class MainActivity extends AppCompatActivity {

    private NavController nav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeStore.applyMode(this);
        super.onCreate(savedInstanceState);
        ThemeStore.applyStyle(this);
        setContentView(R.layout.activity_main);

        setSupportActionBar(findViewById(R.id.main_toolbar));
        NavHostFragment host = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.main_nav_host);
        nav = host.getNavController();
        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        setupBottomNav(bottomNav);
        NavigationUI.setupActionBarWithNavController(this, nav,
                new AppBarConfiguration.Builder(R.id.homeFragment, R.id.localFragment,
                        R.id.samplesFragment, R.id.playlistFragment, R.id.settingsFragment).build());
    }

    /**
     * Navigasi bawah diatur manual (bukan NavigationUI.setupWithNavController) supaya:
     * - layar rincian seperti Appearance tetap menyalakan tab Settings, dan
     * - mengetuk tab lain benar-benar pindah layar (tanpa simpan/pulihkan back stack per tab,
     *   yang membuat isi layar tidak berganti sementara tab sudah menyala).
     */
    private void setupBottomNav(BottomNavigationView bottomNav) {
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.homeFragment) {
                return nav.popBackStack(R.id.homeFragment, false);
            }
            nav.navigate(id, null, new NavOptions.Builder()
                    .setLaunchSingleTop(true)
                    .setPopUpTo(R.id.homeFragment, false)
                    .build());
            return true;
        });
        // ketuk tab yang sedang aktif = kembali ke layar utama tab itu (mis. dari Appearance ke Settings)
        bottomNav.setOnItemReselectedListener(item -> nav.popBackStack(item.getItemId(), false));

        nav.addOnDestinationChangedListener((controller, dest, args) -> {
            boolean detail = dest.getId() == R.id.appearanceFragment || dest.getId() == R.id.generalFragment;
            int tab = detail ? R.id.settingsFragment : dest.getId();
            if (bottomNav.getMenu().findItem(tab) != null) {
                bottomNav.getMenu().findItem(tab).setChecked(true);
            }
        });
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
