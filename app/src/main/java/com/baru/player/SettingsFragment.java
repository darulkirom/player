package com.baru.player;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/** Daftar menu pengaturan: ikon + judul + deskripsi. */
public class SettingsFragment extends Fragment {

    private static final class Item {
        final int icon;
        final int title;
        final int desc;

        Item(int icon, int title, int desc) {
            this.icon = icon;
            this.title = title;
            this.desc = desc;
        }
    }

    private static final Item[] ITEMS = {
            new Item(R.drawable.ic_palette, R.string.set_appearance, R.string.set_appearance_desc),
            new Item(R.drawable.ic_playlist_play, R.string.set_playlist, R.string.set_playlist_desc),
            new Item(R.drawable.ic_settings_row, R.string.set_general, R.string.set_general_desc),
            new Item(R.drawable.ic_video_settings_row, R.string.set_advance, R.string.set_advance_desc),
            new Item(R.drawable.ic_shortcut, R.string.set_shortcuts, R.string.set_shortcuts_desc)
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_settings, container, false);
        LinearLayout list = root.findViewById(R.id.settings_list);
        for (final Item item : ITEMS) {
            View row = inflater.inflate(R.layout.item_setting, list, false);
            ((ImageView) row.findViewById(R.id.setting_icon)).setImageResource(item.icon);
            ((TextView) row.findViewById(R.id.setting_title)).setText(item.title);
            ((TextView) row.findViewById(R.id.setting_desc)).setText(item.desc);
            row.setOnClickListener(v -> onItemClick(item));
            list.addView(row);
        }
        return root;
    }

    /** Isi aksi tiap menu di sini (buka layar rincian, dialog, dan sebagainya). */
    private void onItemClick(Item item) {
        Toast.makeText(requireContext(), R.string.segera_hadir, Toast.LENGTH_SHORT).show();
    }
}
