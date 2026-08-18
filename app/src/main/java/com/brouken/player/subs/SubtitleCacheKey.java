package com.brouken.player.subs;

import android.net.Uri;

import androidx.annotation.Nullable;

import subtitleengine.cache.CacheKeys;

/**
 * The single place that turns a {@link SubtitleOption} into a {@link subtitleengine.cache.SubtitleCache}
 * key, for all three sources. Previously duplicated between {@code SubtitleSelectionController} and
 * {@code EmbeddedSubtitleController} — same EXTERNAL/PROVIDER branches, hand-copied in both.
 */
final class SubtitleCacheKey {

    private SubtitleCacheKey() {
    }

    /** EXTERNAL/PROVIDER — these never carry an EMBEDDED option, so there is no video identity to pass. */
    @Nullable
    static String of(SubtitleOption opt) {
        return of(opt, null, null);
    }

    /**
     * EMBEDDED too, given the video's identity (hash, or its URI-based fallback — see
     * {@link CacheKeys#embeddedByUri}). Only {@code EmbeddedSubtitleController} knows either of these.
     */
    @Nullable
    static String of(SubtitleOption opt, @Nullable String videoHash, @Nullable Uri mediaUri) {
        switch (opt.source) {
            case EMBEDDED:
                if (videoHash != null) return CacheKeys.embedded(videoHash, opt.embeddedTextIndex);
                return mediaUri == null ? null
                        : CacheKeys.embeddedByUri(mediaUri.toString(), opt.embeddedTextIndex);
            case PROVIDER:
                return opt.providerRef == null ? null : CacheKeys.provider(opt.providerRef);
            default: // EXTERNAL
                return opt.uri == null ? null : CacheKeys.external(opt.uri.toString());
        }
    }
}
