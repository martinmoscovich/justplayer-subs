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
    private final EmbeddedExtractionSession session;

    @Nullable private Uri mediaUri;
    /** Id of the option being extracted, and the callback waiting for it. */
    @Nullable private String pendingOptionId;
    @Nullable private Consumer<SubtitleFile> pendingCallback;
    /** Last successful extraction, keyed by option id — re-opening Translate must not re-download. */
    @Nullable private String cachedOptionId;
    @Nullable private SubtitleFile cachedFile;
    @Nullable private Listener listener;

    public interface Listener {
        /** Progress text for the panel, or {@code null} when nothing is running. */
        void onExtractionStatus(@Nullable String status);
    }

    public EmbeddedSubtitleController(Context context, Handler mainHandler,
                                      @Nullable java.util.Map<String, String> headers) {
        this.context = context;
        this.session = new EmbeddedExtractionSession(
                new Media3EmbeddedSubtitleProvider(context, headers),
                mainHandler::post,
                THREAD_FACTORY,
                this::onProgress);
    }

    public void setListener(@Nullable Listener l) {
        this.listener = l;
    }

    public void setMedia(@Nullable Uri mediaUri) {
        this.mediaUri = mediaUri;
        cancel();
        cachedOptionId = null;
        cachedFile = null;
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

        pendingOptionId = option.id;
        pendingCallback = onReady;
        Log.i(TAG, "extracting embedded track " + option.embeddedTextIndex + " ('" + option.label + "')");
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

    private void toast(String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
