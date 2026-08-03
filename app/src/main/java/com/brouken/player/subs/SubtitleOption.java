package com.brouken.player.subs;

import android.net.Uri;

import androidx.annotation.Nullable;

/**
 * One selectable subtitle in the sync panel's selector. Sources:
 * <ul>
 *   <li>{@link Source#EXTERNAL} — passed via intent (Nuvio) or a sidecar; rendered by our overlay
 *       and eligible for sync.</li>
 *   <li>{@link Source#EMBEDDED} — a text track inside the media; rendered by Media3, assumed in-sync.</li>
 *   <li>{@link Source#PROVIDER} — fetched from a service (OpenSubtitles, …); starts {@link State#LOADING}
 *       and resolves to {@link State#READY} or {@link State#ERROR}. (Provider fetching is future work;
 *       the state machine is here so the UI already handles it.)</li>
 * </ul>
 */
public class SubtitleOption {

    public enum Source { EXTERNAL, EMBEDDED, PROVIDER }

    public enum State { READY, LOADING, ERROR }

    public final String id;
    public final String label;
    @Nullable public final String language;
    public final Source source;
    public State state;

    /** EXTERNAL/PROVIDER: the subtitle URI once ready (nullable while loading). */
    @Nullable public final Uri uri;
    /** EMBEDDED: index among the media's text track groups; -1 otherwise. */
    public final int embeddedTextIndex;

    private SubtitleOption(String id, String label, @Nullable String language, Source source,
                           State state, @Nullable Uri uri, int embeddedTextIndex) {
        this.id = id;
        this.label = label;
        this.language = language;
        this.source = source;
        this.state = state;
        this.uri = uri;
        this.embeddedTextIndex = embeddedTextIndex;
    }

    public static SubtitleOption external(String id, String label, @Nullable String language, Uri uri) {
        return new SubtitleOption(id, label, language, Source.EXTERNAL, State.READY, uri, -1);
    }

    public static SubtitleOption embedded(String id, String label, @Nullable String language, int textIndex) {
        return new SubtitleOption(id, label, language, Source.EMBEDDED, State.READY, null, textIndex);
    }

    public static SubtitleOption provider(String id, String label, @Nullable String language, State state) {
        return new SubtitleOption(id, label, language, Source.PROVIDER, state, null, -1);
    }

    public boolean isReady() {
        return state == State.READY;
    }
}
