package com.baru.player;

import androidx.lifecycle.ViewModel;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Menyimpan hasil analisis supaya tidak hilang saat pindah tab. */
public class HomeViewModel extends ViewModel {
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final List<JSONObject> options = new ArrayList<>();
    String videoTitle = "";
    String status = "";
    boolean busy;

    @Override
    protected void onCleared() {
        io.shutdownNow();
    }
}
