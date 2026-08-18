package com.brouken.player.subs;

import androidx.annotation.Nullable;

import subtitleengine.core.model.SubtitleFile;

/**
 * The shape shared by every way of getting a {@link SubtitleFile} out of a {@link SubtitleOption}:
 * check the disk cache first (free), and only do the real work — network fetch, container extraction,
 * whichever the source needs — on a miss. Before this, that shape was hand-rolled once for
 * external/provider (inside {@code SubtitleSelectionController}) and once more for embedded (inside
 * {@code EmbeddedSubtitleController}), and the two drifted: an ordering fix applied to one did not
 * apply to the other, which is what caused this session's recurring "translated chip right, content
 * wrong" bug. One template method now, so that class of divergence isn't possible by construction.
 *
 * <p>Deliberately does <b>not</b> decide <em>when</em> to call {@link #retrieve} — that stays
 * source-specific on purpose. External/provider have no fallback renderer, so selecting one means
 * acquiring it right away; an embedded track already renders natively via Media3 the moment it's
 * selected, so acquiring our own {@link SubtitleFile} for it stays deliberately deferred until
 * Translate/Auto-sync actually needs one (see {@code EmbeddedSubtitleController}'s class javadoc).
 */
interface SubtitleRetriever {

    interface Callback {
        void onLoaded(SubtitleFile file, boolean fromCache);
        void onError(Exception e);
    }

    /** @return the cached cues, or {@code null} on a miss. Synchronous and free — never triggers the
     *  real work. */
    @Nullable
    SubtitleFile peekCache(SubtitleOption opt);

    /** Called only after {@link #peekCache} missed. Does whatever this source needs (network,
     *  container decode, ...), stores the result in the cache on success, and calls exactly one of
     *  {@code callback}'s methods — on the main thread, since the real work may run elsewhere. */
    void fetchAndStore(SubtitleOption opt, Callback callback);

    /**
     * The template method: cache first, else do the real work. {@code onFetchStarting} runs right
     * before {@link #fetchAndStore} does — the one hook each caller needs differently (external/
     * provider mark the option LOADING and refresh the list there; embedded needs nothing, since its
     * own extraction progress already drives its status text).
     */
    default void retrieve(SubtitleOption opt, Runnable onFetchStarting, Callback callback) {
        SubtitleFile cached = peekCache(opt);
        if (cached != null) {
            callback.onLoaded(cached, true);
            return;
        }
        onFetchStarting.run();
        fetchAndStore(opt, callback);
    }
}
