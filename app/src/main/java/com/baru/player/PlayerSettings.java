package com.baru.player;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

/** Pembaca pengaturan layar General (kunci sama dengan res/xml/preferences_general.xml). */
final class PlayerSettings {
    private final SharedPreferences sp;

    PlayerSettings(Context c) {
        sp = PreferenceManager.getDefaultSharedPreferences(c);
    }

    boolean autoPip()          { return sp.getBoolean("pip_auto", false); }
    boolean skipSilence()      { return sp.getBoolean("skip_silence", false); }
    boolean forceLandscape()   { return sp.getBoolean("force_landscape", false); }
    boolean volumeGesture()    { return sp.getBoolean("gesture_volume", true); }
    boolean brightnessGesture(){ return sp.getBoolean("gesture_brightness", true); }
    boolean resumePlaying()    { return sp.getBoolean("resume_playing", true); }

    /** Durasi maju/mundur dalam milidetik (5/10/20/30 detik, bawaan 30). */
    long seekStepMs() {
        try {
            return Long.parseLong(sp.getString("seek_duration", "30")) * 1000L;
        } catch (NumberFormatException e) {
            return 30_000L;
        }
    }
}
