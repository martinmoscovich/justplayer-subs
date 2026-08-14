package com.brouken.player.subs;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

import subtitleengine.cache.CacheKeys;
import subtitleengine.cache.CachePolicy;
import subtitleengine.cache.CachedSubtitle;
import subtitleengine.cache.SubtitleCache;
import subtitleengine.core.model.SubtitleFile;
import subtitleengine.embedded.EmbeddedExtractionSession;
import subtitleengine.embedded.ExtractionProgress;

/**
 * Adapts the engine's {@link EmbeddedExtractionSession} to Android: builds the Media3-backed
 * provider, runs the read on a low-priority thread, formats progress for the panel, and hands the
 * finished {@link SubtitleFile} back to whoever asked for it.
 *
 * <p>Mirrors {@link AutoSyncController} in shape — the session, its thread and its cancellation all
 * live in the engine; this only translates engine events into Android calls.
 *
 * <p>Extraction is deliberately <b>on demand</b>: selecting an embedded track stays instant (Media3
 * renders it, as always), and only asking to translate or sync it pays for reading the container.
 * The result is cached per track, so a second request after a completed extraction is free.
 */
public class EmbeddedSubtitleController {

    private static final String TAG = "EmbeddedSubtitles";

    /** Background priority: this competes with playback for bandwidth and CPU, and playback wins. */
    private static final ThreadFactory THREAD_FACTORY = r -> {
        Thread t = new Thread(r, "embedded-subtitle-extraction");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    };

    private final Context context;
    private final Handler mainHandler;
    private final EmbeddedExtractionSession session;
    private final SubtitleCache cache;
    @Nullable private final java.util.Map<String, String> headers;
    /** Identity of the media, computed once from 128 KB — null when it has none (a live stream). */
    @Nullable private String videoHash;

    @Nullable private Uri mediaUri;
    /** Id of the option being extracted, and the callback waiting for it. */
    @Nullable private String pendingOptionId;
    @Nullable private Consumer<SubtitleFile> pendingCallback;
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
        this.cache = new SubtitleCache(new FileCacheStore(context), CachePolicy.defaults());
        this.session = new EmbeddedExtractionSession(
                new Media3EmbeddedSubtitleProvider(context, headers),
                mainHandler::post,
                THREAD_FACTORY,
                this::onProgress);
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
            String[] hashAndSize = MediaHasher.hashAndSize(context, mediaUri, headers);
            mainHandler.post(() -> {
                if (!mediaUri.equals(this.mediaUri)) return; // media changed while we hashed
                videoHash = hashAndSize == null ? null : hashAndSize[0];
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
        if (option == null) return null;
        switch (option.source) {
            case EMBEDDED:
                return videoHash == null ? null : CacheKeys.embedded(videoHash, option.embeddedTextIndex);
            case PROVIDER:
                return option.providerRef == null ? null : CacheKeys.provider(option.providerRef);
            default:
                return option.uri == null ? null : CacheKeys.external(option.uri.toString());
        }
    }

    public boolean isRunning() {
        return session.status() == ExtractionProgress.Status.RUNNING;
    }

    /**
     * Makes {@code option}'s cues available, reading the container if this is the first request.
     * {@code onReady} runs on the main thread; it is not called if the read fails or is cancelled.
     *
     * @return false if the request could not even be started (no media, wrong kind of track)
     */
    public boolean ensureExtracted(SubtitleOption option, Consumer<SubtitleFile> onReady) {
        if (option.source != SubtitleOption.Source.EMBEDDED) return false;
        if (option.imageFormat) return false; // bitmap subtitles have no text to extract
        if (mediaUri == null) return false;

        if (option.id.equals(cachedOptionId) && cachedFile != null) {
            onReady.accept(cachedFile);
            return true;
        }
        if (isRunning()) return true; // already working on it; the running request wins

        String key = keyFor(option);
        session.setCache(cache, key);

        // A finished read on disk costs nothing to reuse: no thread, no progress, no download.
        CachedSubtitle hit = session.cached();
        if (hit != null && hit.entryCount() > 0) {
            Log.i(TAG, "cache hit for track " + option.embeddedTextIndex + ": "
                    + hit.entryCount() + " cues, stored " + ageDescription(hit.getStoredAtMs()));
            cachedOptionId = option.id;
            cachedFile = hit.getSubtitle();
            lastWasFromCache = true;
            onReady.accept(cachedFile);
            return true;
        }

        lastWasFromCache = false;
        pendingOptionId = option.id;
        pendingCallback = onReady;
        Log.i(TAG, "extracting embedded track " + option.embeddedTextIndex + " ('" + option.label
                + "') · cacheKey=" + key);
        session.start(mediaUri.toString(), option.embeddedTextIndex, option.language);
        return true;
    }

    public void cancel() {
        if (isRunning()) session.cancel();
        pendingOptionId = null;
        pendingCallback = null;
    }

    public void release() {
        cancel();
        listener = null;
    }

    /** Called on the main thread — the session dispatches through the player's handler. */
    private void onProgress(ExtractionProgress progress) {
        switch (progress.getStatus()) {
            case RUNNING:
                notifyStatus(String.format(Locale.US, "Reading subtitles from the video… %d%%",
                        Math.round(progress.getFraction() * 100)));
                break;
            case DONE:
                SubtitleFile file = progress.getResult();
                notifyStatus(null);
                if (file == null || file.getEntries().isEmpty()) {
                    // Not an exception, but not usable either — and silence here would look like a hang.
                    Log.w(TAG, "extraction finished with no cues");
                    toast("That embedded track has no readable subtitles");
                    clearPending();
                    return;
                }
                Log.i(TAG, "extraction done: " + file.getEntries().size() + " cues");
                cachedOptionId = pendingOptionId;
                cachedFile = file;
                Consumer<SubtitleFile> callback = pendingCallback;
                clearPending();
                if (callback != null) callback.accept(file);
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
                clearPending();
                break;
            default:
                break;
        }
    }

    private void clearPending() {
        pendingOptionId = null;
        pendingCallback = null;
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
