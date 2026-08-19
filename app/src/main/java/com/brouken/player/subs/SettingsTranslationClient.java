package com.brouken.player.subs;

import android.content.Context;

import okhttp3.OkHttpClient;
import subtitleengine.core.model.SubtitleError;
import subtitleengine.translation.CompletionResult;
import subtitleengine.translation.GeminiClient;
import subtitleengine.translation.OpenRouterClient;
import subtitleengine.translation.TranslationClient;

/**
 * A {@link TranslationClient} that resolves provider/model/API key from {@link SubtitleSettings}
 * instead of capturing them once.
 *
 * <p>The engine's {@code SubtitleTranslator} takes its client at construction and the player builds
 * the translator once per {@code PlayerActivity}, so a client built from a snapshot of the prefs
 * froze the API key for the life of the activity: editing it in Settings changed nothing until the
 * activity was recreated, and a stale key showed up as a 401 on every chunk.
 *
 * <p>Resolution happens at {@link #refresh()} — called at the start of each run — rather than per
 * completion: a run that switched model or key between chunks would produce a translation nobody
 * asked for, half in each model. So "the next run uses the current settings", not "the next HTTP
 * call does".
 */
class SettingsTranslationClient implements TranslationClient {

    private final Context context;
    /** One connection pool for every delegate; only the credentials/model change between them. */
    private final OkHttpClient http = new OkHttpClient();

    /** Written on the UI thread by {@link #refresh()}, read by the translator's worker threads. */
    private volatile TranslationClient delegate;

    SettingsTranslationClient(Context context) {
        this.context = context;
        this.delegate = build();
    }

    /** Re-reads the settings. Call between runs, never during one. */
    void refresh() {
        delegate = build();
    }

    private TranslationClient build() {
        String provider = SubtitleSettings.getString(context, SubtitleSettings.KEY_AI_PROVIDER, "openrouter");
        String model = SubtitleSettings.getString(context, SubtitleSettings.KEY_AI_MODEL, "google/gemini-2.5-flash");
        String apiKey = SubtitleSettings.getApiKey(context, SubtitleSettings.KEY_AI_API_KEY);
        if ("gemini".equals(provider)) {
            return new GeminiClient(apiKey, model, http);
        }
        return new OpenRouterClient(apiKey, model, http);
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public String getModelId() {
        return delegate.getModelId();
    }

    @Override
    public CompletionResult complete(String systemPrompt, String userPrompt)
            throws SubtitleError.TranslationError {
        return delegate.complete(systemPrompt, userPrompt);
    }
}
