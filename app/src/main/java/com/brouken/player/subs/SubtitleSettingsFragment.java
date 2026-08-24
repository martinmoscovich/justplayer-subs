package com.brouken.player.subs;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.brouken.player.R;
import com.brouken.player.subs.debug.DebugLog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import subtitleengine.sync.SyncSettings;

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
        wireQrSetup();
        wireDebug();
    }

    /**
     * The debug switch and the action that clears what it wrote. The switch applies immediately
     * rather than on the next playback: turning it <em>off</em> must delete the stored logs right
     * there ({@link DebugLog#setEnabled}), and the player may not even be alive to notice the pref
     * change from here.
     */
    private void wireDebug() {
        Preference toggle = findPreference(SubtitleSettings.KEY_DEBUG_MODE);
        if (toggle != null) {
            toggle.setOnPreferenceChangeListener((pref, value) -> {
                boolean on = Boolean.TRUE.equals(value);
                DebugLog.setEnabled(requireContext(), on);
                // From the new value, not from storage: this runs before the value is persisted.
                applyDebugRow(on);
                return true;
            });
        }
        Preference clear = findPreference(SubtitleSettings.KEY_DEBUG_CLEAR_LOGS);
        if (clear != null) {
            clear.setOnPreferenceClickListener(pref -> {
                DebugLog.clearAll(requireContext());
                applyDebugRow(true); // only reachable while debug is on
                return true;
            });
        }
    }

    /** Keeps "Clear debug logs" honest: disabled while debug is off, and its summary shows what is stored. */
    private void applyDebugRow(boolean debugOn) {
        Context context = getContext();
        if (context == null) return;
        Preference clear = findPreference(SubtitleSettings.KEY_DEBUG_CLEAR_LOGS);
        if (clear == null) return;
        clear.setEnabled(debugOn);
        clear.setSummary(debugOn ? DebugLog.describeStorage(context) : "Enable debug mode to record logs");
    }

    private void wireQrSetup() {
        Preference p = findPreference("pref_qr_setup");
        if (p == null) return;
        p.setOnPreferenceClickListener(pref -> {
            com.brouken.player.subs.qr.QrSetupActivity.start(requireContext());
            return true;
        });
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
        refreshFromStorage();
    }

    /**
     * These Preference widgets cache their value in memory once and never re-read SharedPreferences
     * on their own — fine as long as this screen is the only writer, but "Set up via QR" can write
     * these same keys while this fragment is merely paused underneath it, not recreated. Without this,
     * reopening a field after a QR submit shows the stale in-memory value, not what actually got saved.
     */
    private void refreshFromStorage() {
        Context context = requireContext();

        setTextIfPresent(SubtitleSettings.KEY_OPENSUBTITLES,
                SubtitleSettings.getString(context, SubtitleSettings.KEY_OPENSUBTITLES, ""));
        setTextIfPresent(SubtitleSettings.KEY_AI_MODEL,
                SubtitleSettings.getString(context, SubtitleSettings.KEY_AI_MODEL, ""));
        setTextIfPresent(SubtitleSettings.KEY_AI_API_KEY,
                SubtitleSettings.getString(context, SubtitleSettings.KEY_AI_API_KEY, ""));
        // Defaults straight from the engine, the same source SubtitleSettings.syncSettings() feeds
        // the sync session from — so what this screen shows as the default cannot drift from what the
        // sync actually uses. The preference XML deliberately carries no app:defaultValue for these.
        SyncSettings d = SyncSettings.defaults();
        setTextIfPresent(SubtitleSettings.KEY_REACTION_MS,
                String.valueOf(SubtitleSettings.getLong(context, SubtitleSettings.KEY_REACTION_MS, d.getReactionMs())));
        setTextIfPresent(SubtitleSettings.KEY_NUDGE_MS,
                String.valueOf(SubtitleSettings.getLong(context, SubtitleSettings.KEY_NUDGE_MS, d.getNudgeMs())));
        setTextIfPresent(SubtitleSettings.KEY_SEEK_S,
                String.valueOf(SubtitleSettings.getLong(context, SubtitleSettings.KEY_SEEK_S, d.getSeekMs() / 1000)));
        setTextIfPresent(SubtitleSettings.KEY_SEGMENT_GAP_S,
                String.valueOf(SubtitleSettings.getLong(context, SubtitleSettings.KEY_SEGMENT_GAP_S, d.getSegmentGapMs() / 1000)));
        setTextIfPresent(SubtitleSettings.KEY_SEGMENT_RESTART_S,
                String.valueOf(SubtitleSettings.getLong(context, SubtitleSettings.KEY_SEGMENT_RESTART_S, d.getSegmentRestartMs() / 1000)));

        Preference provider = findPreference(SubtitleSettings.KEY_AI_PROVIDER);
        if (provider instanceof ListPreference) {
            ((ListPreference) provider).setValue(
                    SubtitleSettings.getString(context, SubtitleSettings.KEY_AI_PROVIDER,
                            context.getString(R.string.subs_default_ai_provider)));
        }
        Preference autoSelect = findPreference(SubtitleSettings.KEY_AUTO_SELECT);
        if (autoSelect instanceof SwitchPreferenceCompat) {
            ((SwitchPreferenceCompat) autoSelect).setChecked(SubtitleSettings.autoSelectEnabled(context));
        }
        boolean debugOn = SubtitleSettings.debugEnabled(context);
        Preference debug = findPreference(SubtitleSettings.KEY_DEBUG_MODE);
        if (debug instanceof SwitchPreferenceCompat) {
            ((SwitchPreferenceCompat) debug).setChecked(debugOn);
        }
        applyDebugRow(debugOn);
    }

    private void setTextIfPresent(String key, String value) {
        Preference p = findPreference(key);
        if (p instanceof EditTextPreference) ((EditTextPreference) p).setText(value);
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
