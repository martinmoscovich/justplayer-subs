package com.brouken.player.subs;

import android.net.Uri;

import androidx.annotation.Nullable;

/**
 * One selectable subtitle in the sync panel's selector. Sources:
 * <ul>
 *   <li>{@link Source#EXTERNAL} — passed via intent (Nuvio) or a sidecar; rendered by our overlay
 *       and eligible for sync. Loading its content is async (URLs can be slow).</li>
 *   <li>{@link Source#EMBEDDED} — a text track inside the media; rendered by Media3, assumed in-sync.</li>
 *   <li>{@link Source#PROVIDER} — a result from a service (OpenSubtitles, …). The list itself is
 *       fetched asynchronously; downloading a chosen result is async too ({@link State#LOADING}).</li>
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
    /** Set when this option's cues came off the cache rather than the network — the UI says so. */
    public boolean fromCache;

    /** EXTERNAL: the subtitle URI. */
    @Nullable public final Uri uri;
    /** EMBEDDED: index among the media's text track groups; -1 otherwise. */
    public final int embeddedTextIndex;
    /** PROVIDER: opaque id used to download the result (e.g. OpenSubtitles file id). */
    @Nullable public final String providerRef;
    /** PROVIDER: rating/download count for display, kept out of {@link #label} so the UI can lay
     *  them out separately (e.g. right-aligned); 0 elsewhere. */
    public final float rating;
    public final int downloadCount;
    /** EMBEDDED: bitmap subtitle (PGS/VobSub/DVB) rather than text. Always false elsewhere — we only
     *  ever load text externally. The auto-selector needs it: an image track can be displayed but
     *  never parsed, resynced or translated, so the engine ranks it last. */
    public final boolean imageFormat;

    private SubtitleOption(String id, String label, @Nullable String language, Source source,
                           State state, @Nullable Uri uri, int embeddedTextIndex,
                           @Nullable String providerRef, float rating, int downloadCount,
                           boolean imageFormat) {
        this.id = id;
        this.label = label;
        this.language = language;
        this.source = source;
        this.state = state;
        this.uri = uri;
        this.embeddedTextIndex = embeddedTextIndex;
        this.providerRef = providerRef;
        this.rating = rating;
        this.downloadCount = downloadCount;
        this.imageFormat = imageFormat;
    }

    public static SubtitleOption external(String id, String label, @Nullable String language, Uri uri) {
        return new SubtitleOption(id, label, language, Source.EXTERNAL, State.READY, uri, -1, null, 0f, 0, false);
    }

    public static SubtitleOption embedded(String id, String label, @Nullable String language, int textIndex,
                                          boolean imageFormat) {
        return new SubtitleOption(id, label, language, Source.EMBEDDED, State.READY, null, textIndex, null, 0f, 0,
                imageFormat);
    }

    public static SubtitleOption provider(String id, String label, @Nullable String language, String providerRef,
                                          float rating, int downloadCount) {
        return new SubtitleOption(id, label, language, Source.PROVIDER, State.READY, null, -1, providerRef,
                rating, downloadCount, false);
    }

    public boolean isReady() {
        return state == State.READY;
    }
}
