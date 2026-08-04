package com.brouken.player.subs;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Set;

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
        return prefs(c).getString(key, def);
    }

    public static Set<String> getStringSet(Context c, String key) {
        return prefs(c).getStringSet(key, Collections.emptySet());
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
