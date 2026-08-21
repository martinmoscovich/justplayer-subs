package com.brouken.player.subs.qr;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.brouken.player.R;
import com.brouken.player.subs.SubtitleSettings;

import subtitleengine.sync.SyncSettings;

/**
 * Builds the JSON the QR setup page fetches at {@code GET /config.json} — the language catalog and the
 * current, non-secret settings, so the form can prefill instead of starting blank. Deliberately leaves
 * out the two API keys ({@link SubtitleSettings#KEY_OPENSUBTITLES}, {@link SubtitleSettings#KEY_AI_API_KEY}):
 * they are write-only from the phone's side and must never round-trip back to it.
 */
final class QrSetupConfigJson {

    private QrSetupConfigJson() {
    }

    static String build(Context context, long expiresAtMs) {
        try {
            JSONObject root = new JSONObject();
            root.put("expiresAtMs", expiresAtMs);
            root.put("languages", languages(context));
            root.put("current", current(context));
            return root.toString();
        } catch (JSONException e) {
            // Built entirely from known-shape data, so a failure here would be a bug rather than a
            // runtime condition — but a cache is never a reason to fail, and neither is this.
            return "{}";
        }
    }

    private static JSONArray languages(Context context) throws JSONException {
        String[] codes = context.getResources().getStringArray(R.array.subtitle_language_codes);
        String[] names = context.getResources().getStringArray(R.array.subtitle_language_names);
        JSONArray out = new JSONArray();
        for (int i = 0; i < codes.length && i < names.length; i++) {
            JSONObject entry = new JSONObject();
            entry.put("code", codes[i]);
            entry.put("name", names[i]);
            out.put(entry);
        }
        return out;
    }

    /** Mirrors the defaults in res/xml/subtitle_settings.xml — keep the two in sync. */
    private static JSONObject current(Context context) throws JSONException {
        SyncSettings sync = SubtitleSettings.syncSettings(context);
        JSONObject out = new JSONObject();
        out.put("aiProvider", SubtitleSettings.getString(context, SubtitleSettings.KEY_AI_PROVIDER, "openrouter"));
        out.put("aiModel", SubtitleSettings.getString(context, SubtitleSettings.KEY_AI_MODEL,
                context.getString(R.string.subs_default_ai_model)));
        out.put("targetLanguages", new JSONArray(SubtitleSettings.getLanguageList(context, SubtitleSettings.KEY_TARGET_LANGS)));
        out.put("sourceLanguages", new JSONArray(SubtitleSettings.getLanguageList(context, SubtitleSettings.KEY_SOURCE_LANGS)));
        out.put("autoSelect", SubtitleSettings.autoSelectEnabled(context));
        out.put("reactionMs", sync.getReactionMs());
        out.put("nudgeMs", sync.getNudgeMs());
        out.put("seekS", sync.getSeekMs() / 1000);
        out.put("segmentGapS", sync.getSegmentGapMs() / 1000);
        out.put("segmentRestartS", sync.getSegmentRestartMs() / 1000);
        return out;
    }
}
