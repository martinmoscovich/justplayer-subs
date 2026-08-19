package com.brouken.player.subs;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

import subtitleengine.cache.CachePolicy;
import subtitleengine.cache.CachedSubtitle;
import subtitleengine.cache.SubtitleCache;
import subtitleengine.core.model.SubtitleEntry;
import subtitleengine.core.model.SubtitleFile;
import subtitleengine.pipeline.EntrySource;
import subtitleengine.pipeline.ExtractingEntrySource;
import subtitleengine.pipeline.InMemoryEntrySource;
import subtitleengine.pipeline.SubtitlePipelineSession;
import subtitleengine.translation.ChunkingConfig;
import subtitleengine.translation.CompletionResult;
import subtitleengine.translation.RunStatus;
import subtitleengine.translation.SubtitleTranslator;
import subtitleengine.translation.TranslationClient;
import subtitleengine.translation.TranslationProgress;

/**
 * Adapts the engine's {@link SubtitlePipelineSession} to Android: builds the Media3-backed
 * provider, runs the read on a low-priority thread, formats progress for the panel, and hands the
 * finished {@link SubtitleFile} back to whoever asked for it.
 *
 * <p>Mirrors {@link AutoSyncController} in shape — the session, its thread and its cancellation all
 * live in the engine; this only translates engine events into Android calls.
 *
 * <p>Extraction is deliberately <b>on demand</b>: selecting an embedded track stays instant (Media3
 * renders it, as always), and only asking to translate or sync it pays for reading the container.
 * The result is cached per track, so a second request after a completed extraction is free.
 *
 * <p>Runs {@link SubtitlePipelineSession} in its no-translation mode ({@code start(null)}) — the same
 * session type {@link TranslationController} drives when it needs to translate a track that hasn't
 * been extracted yet (see {@link #entrySourceFor}). A translator is still required by the session's
 * constructor even though this controller never asks it to translate anything; {@link #NEVER_CALLED}
 * documents and enforces that.
 */
public class EmbeddedSubtitleController implements SubtitleRetriever, SubtitlePipelineSession.Listener {

    private static final String TAG = "EmbeddedSubtitles";

    /** Background priority: this competes with playback for bandwidth and CPU, and playback wins. */
    private static final ThreadFactory THREAD_FACTORY = r -> {
        Thread t = new Thread(r, "embedded-subtitle-extraction");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    };

    /** Extraction-only mode never translates a single entry — a call here is a bug, not a runtime path. */
    private static final TranslationClient NEVER_CALLED = new TranslationClient() {
        @Override public String getName() { return "never-called"; }
        @Override public String getModelId() { return "never-called"; }
        @Override public CompletionResult complete(String systemPrompt, String userPrompt) {
            throw new UnsupportedOperationException(
                    "EmbeddedSubtitleController's session never translates — this should be unreachable");
        }
    };

    private final Context context;
    private final Handler mainHandler;
    private final SubtitlePipelineSession session;
    private final SubtitleCache cache;
    @Nullable private final java.util.Map<String, String> headers;
    /** Identity of the media, computed once from 128 KB — null when it has none (a live stream). */
    @Nullable private String videoHash;

    @Nullable private Uri mediaUri;
    /** The callback waiting for the extraction in flight, if any. */
    @Nullable private Callback pendingCallback;
    /** Latest subtitle the running session has reported, via onSubtitleUpdated — what a DONE result hands back. */
    @Nullable private SubtitleFile lastEmittedFile;
    /** Last successful extraction, keyed by option id — re-opening Translate must not re-download. */
    @Nullable private String cachedOptionId;
    @Nullable private SubtitleFile cachedFile;
    /** Whether the last satisfied request was served from disk — the UI says so. */
    private boolean lastWasFromCache;
    @Nullable private Listener listener;
    @Nullable private Consumer<String> onHashReady;

    public interface Listener {
        /** Progress text for the panel, or {@code null} when nothing is running. */
        void onExtractionStatus(@Nullable String status);
    }

    public EmbeddedSubtitleController(Context context, Handler mainHandler,
                                      @Nullable java.util.Map<String, String> headers) {
        this.context = context;
        this.mainHandler = mainHandler;
        this.headers = headers;
        this.cache = new SubtitleCache(new FileCacheStore(context), CachePolicy.defaults(),
                () -> SubtitleSettings.preferredLanguages(context).getTargets());
        this.session = new SubtitlePipelineSession(
                new SubtitleTranslator(NEVER_CALLED), ChunkingConfig.defaults(), 1,
                mainHandler::post, THREAD_FACTORY, this);
    }

    public void setListener(@Nullable Listener l) {
        this.listener = l;
    }

    /** Called once per media with the size in bytes (null when unhashable); read {@link #videoHash()} with it. */
    public void setOnHashReady(@Nullable Consumer<String> onHashReady) {
        this.onHashReady = onHashReady;
    }

    @Nullable
    public String videoHash() {
        return videoHash;
    }

    public void setMedia(@Nullable Uri mediaUri) {
        this.mediaUri = mediaUri;
        cancel();
        cachedOptionId = null;
        cachedFile = null;
        videoHash = null;
        if (mediaUri == null) return;
        // Hashing costs two range requests, so it happens off the main thread — and its absence
        // simply means this media runs uncached rather than failing.
        new Thread(() -> {
            long startMs = System.currentTimeMillis();
            String[] hashAndSize = MediaHasher.hashAndSize(context, mediaUri, headers);
            long elapsedMs = System.currentTimeMillis() - startMs;
            mainHandler.post(() -> {
                if (!mediaUri.equals(this.mediaUri)) return; // media changed while we hashed
                videoHash = hashAndSize == null ? null : hashAndSize[0];
                Log.i(TAG, "media hash " + (videoHash != null ? "ready" : "unavailable — falling back to URI-based cache key")
                        + " after " + elapsedMs + "ms");
                if (onHashReady != null) {
                    onHashReady.accept(hashAndSize == null ? null : hashAndSize[1]);
                }
            });
        }, "media-hash").start();
    }

    /** The disk cache, exposed so the panel can offer "read again" by invalidating an entry. */
    public void forget(SubtitleOption option) {
        String key = keyFor(option);
        if (key != null) cache.removeSubtitleAndTranslations(key);
        if (option.id.equals(cachedOptionId)) {
            cachedOptionId = null;
            cachedFile = null;
        }
    }

    /** Whether the cues for {@code option} are already on disk — drives the "cached" marker. */
    public boolean isCached(SubtitleOption option) {
        String key = keyFor(option);
        if (key == null) return false;
        CachedSubtitle hit = cache.getSubtitle(key, System.currentTimeMillis());
        return hit != null && hit.isComplete();
    }

    /** The shared disk cache — the translation side keys into the same store. */
    public SubtitleCache cache() {
        return cache;
    }

    /**
     * Stable identity of a subtitle, for both halves of the cache. Every form is derivable before
     * the expensive work: a provider result by its id, an external one by its URI, an embedded track
     * by the media hash plus which track.
     */
    @Nullable
    public String keyFor(SubtitleOption option) {
        return option == null ? null : SubtitleCacheKey.of(option, videoHash, mediaUri);
    }

    public boolean isRunning() {
        return session.status() == RunStatus.RUNNING;
    }

    /**
     * Makes {@code option}'s cues available, reading the container if this is the first request.
     * {@code onReady} runs on the main thread; it is not called if the read fails or is cancelled.
     * Thin wrapper over {@link SubtitleRetriever#retrieve}: this class implements {@link SubtitleRetriever}
     * itself (see {@link #peekCache}/{@link #fetchAndStore}), same shape external/provider use in
     * {@code SubtitleSelectionController} — the only thing specific to embedded here is the dedup
     * against an extraction already in flight ({@link #isRunning()}), since a second request for it
     * has nowhere useful to go until the first finishes.
     *
     * @return false if the request could not even be started (no media, wrong kind of track)
     */
    public boolean ensureExtracted(SubtitleOption option, Consumer<SubtitleFile> onReady) {
        if (option.source != SubtitleOption.Source.EMBEDDED) return false;
        if (option.imageFormat) return false; // bitmap subtitles have no text to extract
        if (mediaUri == null) return false;
        if (isRunning()) return true; // already working on it; the running request wins

        retrieve(option, () -> {
            lastWasFromCache = false;
            Log.i(TAG, "extracting embedded track " + option.embeddedTextIndex + " ('" + option.label
                    + "') · cacheKey=" + keyFor(option));
        }, new Callback() {
            @Override public void onLoaded(SubtitleFile file, boolean fromCache) {
                lastWasFromCache = fromCache;
                cachedOptionId = option.id;
                cachedFile = file;
                onReady.accept(file);
            }
            @Override public void onError(Exception e) {
                // Already logged and toasted inside onPipelineProgress()'s DONE(no-cues)/ERROR branches.
            }
        });
        return true;
    }

    /**
     * A cache-only peek: never reads the container, never starts a background extraction. Used to
     * opportunistically adopt an already-cached track the moment it's selected — a disk cache hit is
     * effectively free, so the Sync screen shouldn't sit empty until the user happens to press
     * Translate/Auto-sync, which is the only thing that otherwise calls {@link #ensureExtracted}.
     */
    @Nullable
    public SubtitleFile tryCached(SubtitleOption option) {
        if (option == null || option.source != SubtitleOption.Source.EMBEDDED) return null;
        if (option.imageFormat) return null;
        if (mediaUri == null) return null;
        return peekCache(option);
    }

    @Nullable
    @Override
    public SubtitleFile peekCache(SubtitleOption option) {
        if (option.id.equals(cachedOptionId) && cachedFile != null) {
            return cachedFile;
        }
        String key = keyFor(option);
        CachedSubtitle hit = (key != null) ? cache.getSubtitle(key, System.currentTimeMillis()) : null;
        if (hit == null || hit.entryCount() == 0 || !hit.isComplete()) return null;
        Log.i(TAG, "cache hit for track " + option.embeddedTextIndex + ": "
                + hit.entryCount() + " cues, stored " + ageDescription(hit.getStoredAtMs()));
        cachedOptionId = option.id;
        cachedFile = hit.getSubtitle();
        lastWasFromCache = true;
        return cachedFile;
    }

    @Override
    public void fetchAndStore(SubtitleOption option, Callback callback) {
        pendingCallback = callback;
        lastEmittedFile = null;
        EntrySource source = extractingSourceFor(option);
        session.setSource(source, option.language, null);
        session.setCache(cache, keyFor(option));
        session.start(null);
    }

    /**
     * An {@link EntrySource} for {@code option}'s embedded track — an in-memory one when a complete
     * extraction is already cached, otherwise one that reads the container live, resuming from a
     * cached partial if there is one. Used by {@link TranslationController} to translate a track
     * that hasn't been extracted yet, without going through {@link #ensureExtracted}'s atomic
     * "wait for the whole read, then call back" contract — the whole point of the pipeline session
     * is that translation can start on the first chunk while extraction is still going.
     *
     * @return {@code null} if {@code option} isn't a (non-image) embedded track, or there's no media
     */
    @Nullable
    public EntrySource entrySourceFor(SubtitleOption option) {
        if (option == null || option.source != SubtitleOption.Source.EMBEDDED) return null;
        if (option.imageFormat) return null;
        if (mediaUri == null) return null;

        SubtitleFile cachedComplete = peekCache(option);
        if (cachedComplete != null) {
            return new InMemoryEntrySource(cachedComplete.getEntries());
        }
        return extractingSourceFor(option);
    }

    private EntrySource extractingSourceFor(SubtitleOption option) {
        String key = keyFor(option);
        CachedSubtitle partial = (key != null) ? cache.getSubtitle(key, System.currentTimeMillis()) : null;
        List<SubtitleEntry> cachedPrefix = (partial != null && !partial.isComplete()
                && partial.getCoveredUpToMs() > 0 && partial.entryCount() > 0)
                ? partial.getSubtitle().getEntries() : List.of();
        long resumeFromMs = (partial != null && !partial.isComplete()) ? partial.getCoveredUpToMs() : 0L;
        return new ExtractingEntrySource(new Media3EmbeddedSubtitleProvider(context, headers),
                mediaUri.toString(), option.embeddedTextIndex, option.language,
                cachedPrefix, resumeFromMs, THREAD_FACTORY);
    }

    public void cancel() {
        if (isRunning()) session.cancel();
        pendingCallback = null;
    }

    public void release() {
        cancel();
        listener = null;
    }

    // --- SubtitlePipelineSession.Listener ---

    @Override
    public void onSubtitleUpdated(SubtitleFile current) {
        lastEmittedFile = current;
    }

    @Override
    public void onProgress(TranslationProgress progress) {
        switch (progress.getStatus()) {
            case RUNNING:
                notifyStatus(String.format(Locale.US, "Reading subtitles from the video… %d%%",
                        Math.round(progress.getSourceFraction() * 100)));
                break;
            case DONE:
                SubtitleFile file = lastEmittedFile;
                notifyStatus(null);
                if (file == null || file.getEntries().isEmpty()) {
                    // Not an exception, but not usable either — and silence here would look like a hang.
                    Log.w(TAG, "extraction finished with no cues");
                    toast("That embedded track has no readable subtitles");
                    failPending("no readable subtitles");
                    return;
                }
                Log.i(TAG, "extraction done: " + file.getEntries().size() + " cues");
                Callback loadedCallback = pendingCallback;
                clearPending();
                if (loadedCallback != null) loadedCallback.onLoaded(file, false);
                break;
            case CANCELLED:
                notifyStatus(null);
                clearPending();
                break;
            case ERROR:
                notifyStatus(null);
                // The throwable, not just its message: this is the only place the cause survives.
                Log.w(TAG, "extraction failed", progress.getError());
                toast("Could not read the embedded subtitle: " + progress.getErrorMessage());
                failPending(progress.getErrorMessage());
                break;
            default:
                break;
        }
    }

    private void clearPending() {
        pendingCallback = null;
    }

    private void failPending(String message) {
        Callback callback = pendingCallback;
        clearPending();
        if (callback != null) callback.onError(new RuntimeException(message));
    }

    private void notifyStatus(@Nullable String status) {
        if (listener != null) listener.onExtractionStatus(status);
    }

    /** True when what is on screen right now came from the cache rather than a fresh read. */
    public boolean lastWasFromCache() {
        return lastWasFromCache;
    }

    /** "today" / "3 days ago" — enough for a user to judge whether to re-read it. */
    static String ageDescription(long storedAtMs) {
        long days = (System.currentTimeMillis() - storedAtMs) / (24L * 60 * 60 * 1000);
        if (days <= 0) return "today";
        return days == 1 ? "yesterday" : days + " days ago";
    }

    private void toast(String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
