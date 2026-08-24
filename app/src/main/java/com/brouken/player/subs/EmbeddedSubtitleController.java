package com.brouken.player.subs;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

import com.brouken.player.subs.debug.DebugLog;

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

    /**
     * Caches the last few hash results by media URI, for the lifetime of the process rather than this
     * controller — {@code PlayerActivity.initializePlayer()} tears down and recreates a fresh
     * {@code CustomSubtitleController} (and so this class) on every {@code onStart()}, including just
     * returning from the Settings screen. Without this, coming back re-hashed from scratch (~4s) and,
     * worse, raced the cache-key lookup that resumes a translation in progress: re-selecting the same
     * track before the hash was ready fell back to the URI-based key (see {@link SubtitleCacheKey}),
     * found nothing under it — the real progress sat cached under the hash-based key from before — and
     * started over from scratch. Reported live as "lost my whole translation." Small and bounded: a
     * convenience for the same video coming right back, not a general-purpose cache, so a stale entry
     * for a since-changed file at the same URI is an accepted, unlikely edge case rather than something
     * worth invalidating for.
     */
    private static final int HASH_CACHE_CAPACITY = 4;
    private static final Map<String, String[]> HASH_CACHE =
            new LinkedHashMap<String, String[]>(HASH_CACHE_CAPACITY, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, String[]> eldest) {
                    return size() > HASH_CACHE_CAPACITY;
                }
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
    /** Resolved per use, never captured — see AutoSyncController's constructor for why. */
    private final java.util.function.Supplier<java.util.Map<String, String>> headers;
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
        /**
         * The read of an embedded track, as a headline plus a fraction — {@code null} title when
         * nothing is running. Kept apart rather than pre-joined into one string because the panel
         * draws them as a title, a percentage and a bar, and re-splitting a formatted string to get
         * there would be parsing our own output.
         *
         * @param fraction 0..1, negative when unknown
         */
        void onExtractionStatus(@Nullable String title, float fraction);
    }

    public EmbeddedSubtitleController(Context context, Handler mainHandler,
                                      java.util.function.Supplier<java.util.Map<String, String>> headers) {
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

        String[] cachedHashAndSize = HASH_CACHE.get(mediaUri.toString());
        if (cachedHashAndSize != null) {
            videoHash = cachedHashAndSize[0];
            Log.i(TAG, "media hash reused from an earlier hash of this URI this process — no re-read");
            DebugLog.log(DebugLog.CAT_SESSION, "media hash reused (no re-read): " + videoHash);
            if (onHashReady != null) onHashReady.accept(cachedHashAndSize[1]);
            return;
        }

        // Hashing costs two range requests, so it happens off the main thread — and its absence
        // simply means this media runs uncached rather than failing.
        new Thread(() -> {
            long startMs = System.currentTimeMillis();
            String[] hashAndSize = MediaHasher.hashAndSize(context, mediaUri, headers.get());
            long elapsedMs = System.currentTimeMillis() - startMs;
            mainHandler.post(() -> {
                if (!mediaUri.equals(this.mediaUri)) return; // media changed while we hashed
                videoHash = hashAndSize == null ? null : hashAndSize[0];
                if (hashAndSize != null) HASH_CACHE.put(mediaUri.toString(), hashAndSize);
                Log.i(TAG, "media hash " + (videoHash != null ? "ready" : "unavailable — falling back to URI-based cache key")
                        + " after " + elapsedMs + "ms");
                DebugLog.log(DebugLog.CAT_SESSION, "media hash "
                        + (videoHash != null ? videoHash : "unavailable (URI-based cache key)")
                        + " size=" + (hashAndSize != null ? hashAndSize[1] : "?") + " in " + elapsedMs + "ms");
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
            DebugLog.log(DebugLog.CAT_EXTRACT_SUBS, () -> "start track=" + option.embeddedTextIndex
                    + " label='" + option.label + "' lang=" + option.language + " cacheKey=" + keyFor(option));
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
        DebugLog.log(DebugLog.CAT_EXTRACT_SUBS, "cache hit track=" + option.embeddedTextIndex
                + " cues=" + hit.entryCount() + " stored=" + ageDescription(hit.getStoredAtMs())
                + " key=" + key);
        cachedOptionId = option.id;
        cachedFile = hit.getSubtitle();
        lastWasFromCache = true;
        return cachedFile;
    }

    @Override
    public void fetchAndStore(SubtitleOption option, Callback callback) {
        pendingCallback = callback;
        lastEmittedFile = null;
        lastLoggedSourceStep = -1;
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
        if (resumeFromMs > 0) {
            DebugLog.log(DebugLog.CAT_EXTRACT_SUBS, "resuming from a cached partial: "
                    + cachedPrefix.size() + " cues covering up to " + resumeFromMs + "ms");
        }
        return new ExtractingEntrySource(new Media3EmbeddedSubtitleProvider(context, headers.get()),
                mediaUri.toString(), option.embeddedTextIndex, option.language,
                cachedPrefix, resumeFromMs, THREAD_FACTORY);
    }

    /**
     * Large enough that either pass of a position-priority run stays well clear of it — any real
     * track has orders of magnitude fewer entries. See {@link ExtractingEntrySource}'s {@code startIndex}
     * javadoc for why the priority pass needs an index range that sorts after the head pass's.
     *
     * <p><b>Must stay under 1,000,000.</b> An index round-trips through the LLM as a bare line in the
     * translation prompt/response, and {@code ChunkValidator}'s {@code INDEX_LINE} pattern only matches
     * 1-6 digits (confirmed live: a 7-digit offset made every chunk parse as "got=0" and fail, no
     * matter how much it got split — the response was fine, the validator just never recognized a
     * single index line in it). 500,000 keeps both halves of the 1-6 digit range roughly symmetric.
     */
    private static final int PRIORITY_PASS_INDEX_OFFSET = 500_000;

    /**
     * A {@link SubtitlePipelineSession.StreamingSourceFactory} for {@code option}'s embedded track,
     * for a run that will start at {@code runStartAtMs} — used by {@link TranslationController} so a
     * track that still needs extracting gets translated with position priority (see
     * {@link SubtitlePipelineSession#runStreamingWithPriority}) instead of always from the start.
     *
     * <p>Only ever called after the caller has already ruled out a complete cache hit (that still goes
     * through the cheap {@link #entrySourceFor}/{@link InMemoryEntrySource} path — running a fully
     * known list through this two-pass factory would translate it twice). When {@code runStartAtMs > 0}
     * neither pass resumes from a cached partial (see the accepted v1 limitation on disk persistence
     * for a position-priority run, {@code PENDING.md}) — the priority pass ({@code fromMs > 0}) is
     * always a cold read at {@link #PRIORITY_PASS_INDEX_OFFSET}, and the head pass
     * ({@code fromMs == 0} called as part of that same run) is also cold, at the default index range.
     * A plain, position-0 run's single {@code create(0L)} call keeps resuming from a cached partial
     * exactly like {@link #extractingSourceFor} always has.
     *
     * @return {@code null} if {@code option} isn't a (non-image) embedded track, or there's no media
     */
    @Nullable
    public SubtitlePipelineSession.StreamingSourceFactory streamingFactoryFor(SubtitleOption option, long runStartAtMs) {
        if (option == null || option.source != SubtitleOption.Source.EMBEDDED) return null;
        if (option.imageFormat) return null;
        if (mediaUri == null) return null;

        boolean positionPriority = runStartAtMs > 0;
        String uri = mediaUri.toString();
        int trackIndex = option.embeddedTextIndex;
        String language = option.language;
        return fromMs -> {
            if (fromMs > 0) {
                return new ExtractingEntrySource(new Media3EmbeddedSubtitleProvider(context, headers.get()),
                        uri, trackIndex, language, List.of(), fromMs, PRIORITY_PASS_INDEX_OFFSET, THREAD_FACTORY);
            }
            if (positionPriority) {
                return new ExtractingEntrySource(new Media3EmbeddedSubtitleProvider(context, headers.get()),
                        uri, trackIndex, language, List.of(), 0L, THREAD_FACTORY);
            }
            return extractingSourceFor(option);
        };
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
                notifyStatus("Reading subtitles from the video", (float) progress.getSourceFraction());
                logRunning(progress);
                break;
            case DONE:
                SubtitleFile file = lastEmittedFile;
                notifyStatus(null);
                if (file == null || file.getEntries().isEmpty()) {
                    // Not an exception, but not usable either — and silence here would look like a hang.
                    Log.w(TAG, "extraction finished with no cues");
                    DebugLog.log(DebugLog.CAT_EXTRACT_SUBS, "done but no readable cues");
                    toast("That embedded track has no readable subtitles");
                    failPending("no readable subtitles");
                    return;
                }
                Log.i(TAG, "extraction done: " + file.getEntries().size() + " cues");
                DebugLog.log(DebugLog.CAT_EXTRACT_SUBS, () -> "done cues=" + file.getEntries().size()
                        + " contentUpTo=" + progress.getExtractedContentMs() + "ms");
                Callback loadedCallback = pendingCallback;
                clearPending();
                if (loadedCallback != null) loadedCallback.onLoaded(file, false);
                break;
            case CANCELLED:
                notifyStatus(null);
                DebugLog.log(DebugLog.CAT_EXTRACT_SUBS, "cancelled");
                clearPending();
                break;
            case ERROR:
                notifyStatus(null);
                // The throwable, not just its message: this is the only place the cause survives.
                Log.w(TAG, "extraction failed", progress.getError());
                DebugLog.log(DebugLog.CAT_EXTRACT_SUBS, "ERROR " + progress.getErrorMessage()
                        + (progress.getError() != null ? " (" + progress.getError() + ")" : ""));
                toast("Could not read the embedded subtitle: " + progress.getErrorMessage());
                failPending(progress.getErrorMessage());
                break;
            default:
                break;
        }
    }

    /** Last decile of the container read that was written, so a long read leaves a trail without
     *  one line per progress callback (the provider reports every 0.5%). */
    private int lastLoggedSourceStep = -1;

    private void logRunning(TranslationProgress progress) {
        if (!DebugLog.enabled()) return;
        int step = (int) (progress.getSourceFraction() * 10);
        if (step == lastLoggedSourceStep) return;
        lastLoggedSourceStep = step;
        DebugLog.log(DebugLog.CAT_EXTRACT_SUBS, "reading " + (step * 10) + "% (content up to "
                + progress.getExtractedContentMs() + "ms, "
                + String.format(Locale.US, "%.1fx", progress.getExtractionSpeedFactor()) + " real time)");
    }

    private void clearPending() {
        pendingCallback = null;
    }

    private void failPending(String message) {
        Callback callback = pendingCallback;
        clearPending();
        if (callback != null) callback.onError(new RuntimeException(message));
    }

    private void notifyStatus(@Nullable String title, float fraction) {
        if (listener != null) listener.onExtractionStatus(title, fraction);
    }

    private void notifyStatus(@Nullable String title) {
        notifyStatus(title, -1f);
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
