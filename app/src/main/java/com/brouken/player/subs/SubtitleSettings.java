package com.brouken.player.subs;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import subtitleengine.core.model.SearchLanguages;

/**
 * Central access to the subtitle settings (dedicated {@code subtitle_prefs} file, separate from
 * Just Player's own prefs). Keys mirror {@code res/xml/subtitle_settings.xml}. Secrets are stored
 * here, never hardcoded.
 */
public final class SubtitleSettings {

    public static final String PREFS_FILE = "subtitle_prefs";

    // General
    public static final String KEY_SOURCE_LANGS = "pref_source_languages";
    public static final String KEY_TARGET_LANGS = "pref_target_languages";
    // Selection
    public static final String KEY_OPENSUBTITLES = "opensubtitles_api_key";
    public static final String KEY_AUTO_SELECT = "pref_auto_select";
    // Sync
    public static final String KEY_REACTION_MS = "pref_reaction_ms";
    public static final String KEY_NUDGE_MS = "pref_nudge_ms";
    public static final String KEY_SEEK_S = "pref_seek_s";
    public static final String KEY_SEGMENT_GAP_S = "pref_segment_gap_s";
    public static final String KEY_SEGMENT_RESTART_S = "pref_segment_restart_s";
    // Translation (reserved — wired when Feature C lands)
    public static final String KEY_AI_PROVIDER = "pref_ai_provider";
    public static final String KEY_AI_MODEL = "pref_ai_model";
    public static final String KEY_AI_API_KEY = "pref_ai_api_key";

    private SubtitleSettings() {
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    public static String getString(Context c, String key, String def) {
        try {
            return prefs(c).getString(key, def);
        } catch (ClassCastException e) {
            // Leftover value of an incompatible type (e.g. from an older pref schema). Drop it
            // instead of crashing every launch.
            prefs(c).edit().remove(key).apply();
            return def;
        }
    }

    public static Set<String> getStringSet(Context c, String key) {
        return prefs(c).getStringSet(key, Collections.emptySet());
    }

    /** Reads an ordered language list (CSV of codes, priority order). */
    public static List<String> getLanguageList(Context c, String key) {
        List<String> out = new ArrayList<>();
        String csv = getString(c, key, "");
        if (csv != null) {
            for (String s : csv.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    public static void setLanguageList(Context c, String key, List<String> langs) {
        prefs(c).edit().putString(key, TextUtils.join(",", langs)).apply();
    }

    /**
     * Whether the player picks a subtitle by itself (engine priority resolver). Off = the pre-existing
     * behaviour: nothing is selected automatically and Media3's own default track selection applies.
     */
    public static boolean autoSelectEnabled(Context c) {
        try {
            return prefs(c).getBoolean(KEY_AUTO_SELECT, true);
        } catch (ClassCastException e) {
            prefs(c).edit().remove(KEY_AUTO_SELECT).apply();
            return true;
        }
    }

    /** Ordered preferred languages the user wants to see (target order, then source as fallback). */
    public static SearchLanguages preferredLanguages(Context c) {
        List<String> targets = new ArrayList<>(getLanguageList(c, KEY_TARGET_LANGS));
        List<String> sources = new ArrayList<>();
        for (String s : getLanguageList(c, KEY_SOURCE_LANGS)) if (!targets.contains(s)) sources.add(s);
        return new SearchLanguages(targets, sources);
    }

    /** Builds the engine's {@link subtitleengine.sync.SyncSettings} from the stored prefs. */
    public static subtitleengine.sync.SyncSettings syncSettings(Context c) {
        subtitleengine.sync.SyncSettings d = subtitleengine.sync.SyncSettings.defaults();
        return new subtitleengine.sync.SyncSettings(
                getLong(c, KEY_REACTION_MS, d.getReactionMs()),
                getLong(c, KEY_NUDGE_MS, d.getNudgeMs()),
                getLong(c, KEY_SEEK_S, d.getSeekMs() / 1000) * 1000,
                getLong(c, KEY_SEGMENT_GAP_S, d.getSegmentGapMs() / 1000) * 1000,
                getLong(c, KEY_SEGMENT_RESTART_S, d.getSegmentRestartMs() / 1000) * 1000,
                d.getSegmentLeadMs());
    }

    /** Reads a numeric string preference as a long, falling back to {@code def} when unset/invalid. */
    public static long getLong(Context c, String key, long def) {
        String s = prefs(c).getString(key, null);
        if (s == null || s.trim().isEmpty()) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
