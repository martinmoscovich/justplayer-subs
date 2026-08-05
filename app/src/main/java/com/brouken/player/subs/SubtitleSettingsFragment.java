package com.brouken.player.subs;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.brouken.player.R;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Subtitle settings (General / Selection / Sync / Translation). Persists to the dedicated
 * {@code subtitle_prefs} file that {@link SubtitleSelectionController} and {@link SubtitleSyncController}
 * read. The two language preferences open {@link LanguageOrderActivity} (ordered, priority-based)
 * instead of an unordered multi-select.
 */
public class SubtitleSettingsFragment extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setSharedPreferencesName(SubtitleSettings.PREFS_FILE);
        setPreferencesFromResource(R.xml.subtitle_settings, rootKey);
        wireLanguageEditor(SubtitleSettings.KEY_SOURCE_LANGS);
        wireLanguageEditor(SubtitleSettings.KEY_TARGET_LANGS);
    }

    private void wireLanguageEditor(String key) {
        Preference p = findPreference(key);
        if (p == null) return;
        String title = p.getTitle() != null ? p.getTitle().toString() : "Languages";
        p.setOnPreferenceClickListener(pref -> {
            LanguageOrderActivity.start(requireContext(), key, title);
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // Reflect the ordered selection back into the summaries after editing.
        updateLanguageSummary(SubtitleSettings.KEY_SOURCE_LANGS,
                "Languages to search and translate from, in priority order");
        updateLanguageSummary(SubtitleSettings.KEY_TARGET_LANGS,
                "Preferred subtitle languages to show / translate to, in priority order");
    }

    private void updateLanguageSummary(String key, String emptySummary) {
        Preference p = findPreference(key);
        if (p == null) return;
        List<String> codes = SubtitleSettings.getLanguageList(requireContext(), key);
        if (codes.isEmpty()) {
            p.setSummary(emptySummary);
            return;
        }
        Map<String, String> names = languageNames();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) sb.append(" · ");
            String n = names.get(codes.get(i));
            sb.append(n != null ? n : codes.get(i));
        }
        p.setSummary(sb.toString());
    }

    @Nullable
    private Map<String, String> languageNamesCache;

    private Map<String, String> languageNames() {
        if (languageNamesCache != null) return languageNamesCache;
        Map<String, String> map = new HashMap<>();
        String[] c = getResources().getStringArray(R.array.subtitle_language_codes);
        String[] n = getResources().getStringArray(R.array.subtitle_language_names);
        for (int i = 0; i < c.length && i < n.length; i++) map.put(c[i], n[i]);
        languageNamesCache = map;
        return map;
    }
}
