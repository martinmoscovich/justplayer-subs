package com.brouken.player.subs;

import android.os.Bundle;

import androidx.preference.PreferenceFragmentCompat;

import com.brouken.player.R;

/**
 * Subtitle settings (General / Selection / Sync / Translation). Persists to the dedicated
 * {@code subtitle_prefs} file that {@link SubtitleSelectionController} and {@link SyncView} read.
 */
public class SubtitleSettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName(SubtitleSettings.PREFS_FILE);
        setPreferencesFromResource(R.xml.subtitle_settings, rootKey);
    }
}
