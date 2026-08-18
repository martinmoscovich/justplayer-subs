package com.brouken.player.subs;

import android.os.Handler;

import androidx.annotation.Nullable;

import subtitleengine.cache.CachedSubtitle;
import subtitleengine.cache.SubtitleCache;
import subtitleengine.core.model.SubtitleFile;
import subtitleengine.parser.SubtitleConverter;

/**
 * {@link SubtitleRetriever} for EXTERNAL and PROVIDER — the only difference between the two is how
 * the raw subtitle text is obtained ({@link RawFetcher}); everything else (cache check, parsing,
 * storing, delivering) is identical, so one class serves both via two separately-configured instances.
 */
final class DownloadSubtitleRetriever implements SubtitleRetriever {

    /** Blocking; runs off the main thread inside {@link #fetchAndStore}. */
    interface RawFetcher {
        String fetch(SubtitleOption opt) throws Exception;
    }

    @Nullable private final SubtitleCache cache;
    private final Handler mainHandler;
    private final RawFetcher rawFetcher;

    DownloadSubtitleRetriever(@Nullable SubtitleCache cache, Handler mainHandler, RawFetcher rawFetcher) {
        this.cache = cache;
        this.mainHandler = mainHandler;
        this.rawFetcher = rawFetcher;
    }

    @Nullable
    @Override
    public SubtitleFile peekCache(SubtitleOption opt) {
        if (cache == null) return null;
        String key = SubtitleCacheKey.of(opt);
        if (key == null) return null;
        CachedSubtitle hit = cache.getSubtitle(key, System.currentTimeMillis());
        if (hit == null || !hit.isComplete() || hit.entryCount() == 0) return null;
        return hit.getSubtitle();
    }

    @Override
    public void fetchAndStore(SubtitleOption opt, Callback callback) {
        new Thread(() -> {
            try {
                String content = rawFetcher.fetch(opt);
                SubtitleFile file = SubtitleConverter.convert(content, "srt", opt.language);
                // Stored after it parsed cleanly: caching bytes that turn out to be unparseable would
                // just make the failure permanent.
                String key = cache != null ? SubtitleCacheKey.of(opt) : null;
                if (key != null && !file.getEntries().isEmpty()) {
                    cache.putSubtitle(key, new CachedSubtitle(file, true, 0L, System.currentTimeMillis()));
                }
                mainHandler.post(() -> callback.onLoaded(file, false));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        }, "subtitle-download").start();
    }
}
