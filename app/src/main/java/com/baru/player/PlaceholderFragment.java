package com.baru.player;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;

/** Tab yang belum punya isi (Local, Samples, Playlist). Judulnya diambil dari label tujuan. */
public class PlaceholderFragment extends Fragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        TextView tv = new TextView(requireContext());
        tv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(16);
        NavDestination d = NavHostFragment.findNavController(this).getCurrentDestination();
        CharSequence label = d != null && d.getLabel() != null ? d.getLabel() : "";
        tv.setText(getString(R.string.placeholder_text, label));
        return tv;
    }
}
