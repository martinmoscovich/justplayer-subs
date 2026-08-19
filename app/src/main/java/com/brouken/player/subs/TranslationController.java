package com.brouken.player.subs;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import okhttp3.OkHttpClient;
import subtitleengine.core.model.SubtitleFile;
import subtitleengine.pipeline.EntrySource;
import subtitleengine.pipeline.SubtitlePipelineSession;
import subtitleengine.translation.ChunkProgress;
import subtitleengine.translation.ChunkingConfig;
import subtitleengine.translation.GeminiClient;
import subtitleengine.translation.OpenRouterClient;
import subtitleengine.translation.RunStatus;
import subtitleengine.translation.SubtitleTranslator;
import subtitleengine.translation.TranslationClient;
import subtitleengine.translation.TranslationProgress;
import subtitleengine.translation.TranslationStats;

/**
 * Thin adapter between Android and the engine's {@link SubtitlePipelineSession}: builds the LLM
 * client from settings, resolves the target language, formats all user-facing strings, and pushes
 * state into {@link SubtitleSyncController} / {@link SubtitlePanel}. Mirrors
 * {@link SubtitleSyncController} / {@link SubtitleSelectionController} in shape — the merge loop,
 * worker thread and state machine all live in the engine session; this class only translates engine
 * events into Android calls.
 *
 * <p>For an embedded track that hasn't been extracted yet, {@link #start} asks
 * {@link EmbeddedSubtitleController#entrySourceFor} for a source that streams cues out of the
 * container while chunks translate as they close — instead of the old "wait for the whole container
 * read, then translate" two-step. Every other source (external, provider, an embedded track already
 * fully cached) already has its {@link SubtitleFile}, so it behaves exactly as before: several
 * chunks translating at once, priority pass from the current playback position.
 */
public class TranslationController implements SubtitlePipelineSession.Listener {

    private static final String TAG = "TranslationController";

    private final Context context;
    private final SubtitleSyncController sync;
    private final SubtitlePanel panel;
    private final Runnable renderOverlay;
    private final EmbeddedSubtitleController embedded;
    private final Consumer<SubtitleFile> promoteToOverlay;
    private final SubtitlePipelineSession session;
    private final TextView indicator;

    private static final long TERMINAL_FLASH_MS = 3000;

    @Nullable private SubtitleFile source;
    @Nullable private String movieTitle;
    /** The current selection, so {@link #start} can ask {@link #embedded} for an entry source when
     *  it's an embedded track with no {@link #source} of its own yet. */
    @Nullable private SubtitleOption selectedOption;
    /** True for the run currently starting/running when it began from {@link #embedded}'s entry
     *  source rather than an already-known {@link #source} — see {@link #onSubtitleUpdated}. */
    private boolean pendingSourcePromotion;
    @Nullable private TranslationProgress lastProgress;
    private boolean toastedForRun; // one Toast per run, not per chunk
    @Nullable private String indicatorTerminalText; // non-null while the post-run flash is live
    private long indicatorTerminalUntilMs;           // System.currentTimeMillis() deadline for the flash

    public TranslationController(Context context, SubtitleSyncController sync, SubtitlePanel panel,
                                 EmbeddedSubtitleController embedded, Handler mainHandler,
                                 Runnable renderOverlay, Consumer<SubtitleFile> promoteToOverlay) {
        this.context = context;
        this.sync = sync;
        this.panel = panel;
        this.embedded = embedded;
        this.renderOverlay = renderOverlay;
        this.promoteToOverlay = promoteToOverlay;
        // The engine's pricing cache defaults to ~/.subtitle-engine, which doesn't resolve to
        // anything writable on Android (see LESSONS.md) — point it at real app storage instead.
        SubtitleTranslator.setPricingCacheDir(context.getFilesDir());
        SubtitleTranslator translator = new SubtitleTranslator(buildClient(context));
        this.session = new SubtitlePipelineSession(translator, ChunkingConfig.defaults(), 3,
                mainHandler::post, this);

        indicator = new TextView(context);
        indicator.setTextColor(Color.WHITE);
        indicator.setShadowLayer(4f, 0f, 0f, Color.BLACK);
        indicator.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        indicator.setBackgroundColor(0x99000000);
        int pad = Math.round(8 * context.getResources().getDisplayMetrics().density);
        indicator.setPadding(pad, Math.round(pad * 0.6f), pad, Math.round(pad * 0.6f));
        indicator.setVisibility(View.GONE);
    }

    /**
     * The top-right indicator: "Translating N/M" while a run is active, then a 3s result flash
     * (✓ finished / ⚠ partial / ✗ failed) when it ends. Hidden while the panel is open — full
     * detail (elapsed time, cost, which lines failed) lives in the Translate screen instead.
     */
    public View getIndicatorView() {
        return indicator;
    }

    /** Called every tick — mirrors {@link SubtitleSyncController#render}: hidden while the panel is open. */
    public void renderIndicator(boolean panelOpen) {
        if (indicatorTerminalText != null && System.currentTimeMillis() >= indicatorTerminalUntilMs) {
            indicatorTerminalText = null; // flash window elapsed
        }

        boolean showRunning = session.status() == RunStatus.RUNNING;
        boolean showTerminal = indicatorTerminalText != null;

        if (panelOpen || (!showRunning && !showTerminal)) {
            if (indicator.getVisibility() != View.GONE) indicator.setVisibility(View.GONE);
            return;
        }
        if (indicator.getVisibility() != View.VISIBLE) indicator.setVisibility(View.VISIBLE);

        String text;
        if (showTerminal) {
            text = indicatorTerminalText;
        } else {
            TranslationProgress p = lastProgress;
            if (p != null && p.isStreaming()) {
                text = streamingSummary(p);
            } else {
                text = (p != null)
                        ? "Translating " + p.getCompletedChunks() + "/" + p.getTotalChunks() + formatCostSoFar(p)
                        : "Translating…";
            }
        }
        if (!text.contentEquals(indicator.getText())) indicator.setText(text);
    }

    /** Sets the 3s post-run flash text for DONE/ERROR; clears it for RUNNING/CANCELLED/IDLE. */
    private void updateIndicatorTerminalFlash(TranslationProgress p) {
        switch (p.getStatus()) {
            case ERROR:
                indicatorTerminalText = "✗ Translation failed"; // ✗
                indicatorTerminalUntilMs = System.currentTimeMillis() + TERMINAL_FLASH_MS;
                break;
            case DONE:
                indicatorTerminalText = (p.getFailedChunks() > 0 || p.getUntranslatedEntries() > 0)
                        ? "⚠ Partially translated"  // ⚠
                        : "✓ Translation finished";  // ✓
                indicatorTerminalUntilMs = System.currentTimeMillis() + TERMINAL_FLASH_MS;
                break;
            default: // RUNNING, CANCELLED, IDLE — no flash (RUNNING keeps the live counter instead)
                indicatorTerminalText = null;
                break;
        }
    }

    // --- Callbacks entry points (from SubtitlePanel.Callbacks via CustomSubtitleController) ---

    /**
     * Binds the disk cache and the key identifying the current subtitle, so a translation already
     * paid for is never paid for twice. A {@code null} key means this subtitle has no stable
     * identity (unknown media) and simply runs uncached.
     */
    public void setCache(subtitleengine.cache.SubtitleCache cache, @Nullable String subtitleKey) {
        session.setCache(cache, subtitleKey);
        pushState();
    }

    /** "Translate again": drops the stored translation first, or the rerun would just serve it back. */
    public void translateAgain(long positionMs) {
        session.forgetTranslation(targetLanguage());
        session.restoreOriginal();
        start(positionMs);
    }

    /** Sets/replaces an already fully-known translatable source (external, provider, a fully-cached
     *  embedded track). {@code null} when nothing is loaded — an embedded track with no
     *  {@link SubtitleFile} of its own yet goes through {@link #setSelectedOption} instead. */
    public void setSource(@Nullable SubtitleFile file, @Nullable String movieTitle) {
        this.source = file;
        this.movieTitle = movieTitle;
        this.toastedForRun = false;
        this.lastProgress = null;
        this.indicatorTerminalText = null;
        session.setSource(file, movieTitle);
        pushState();
    }

    /**
     * The current selection and its title — kept so {@link #start} can resolve an
     * {@link EntrySource} for an embedded track that has no {@link #source} of its own yet. Called
     * on every selection change, regardless of source kind (mirrors
     * {@code CustomSubtitleController.needsExtraction()}'s checks, reused here via
     * {@link #isExtractableSelected()} so the two can't drift).
     */
    public void setSelectedOption(@Nullable SubtitleOption option, @Nullable String movieTitle) {
        this.selectedOption = option;
        this.movieTitle = movieTitle;
        pushState();
    }

    /**
     * @param positionMs current playback position — entries at/after it are prioritized so the
     *                   user can keep watching without gaps as soon as possible. Only affects an
     *                   already fully-known source (see {@link SubtitlePipelineSession}'s class
     *                   javadoc) — ignored for a streaming embedded extraction.
     */
    public void start(long positionMs) {
        if (!canTranslate()) return;
        toastedForRun = false;
        lastProgress = null;
        indicatorTerminalText = null;

        if (source == null && isExtractableSelected()) {
            EntrySource entrySource = embedded.entrySourceFor(selectedOption);
            if (entrySource == null) return; // isExtractableSelected() said yes but embedded disagreed
            pendingSourcePromotion = true;
            session.setSource(entrySource, selectedOption.language, movieTitle);
        } else {
            pendingSourcePromotion = false;
        }

        session.start(targetLanguage(), positionMs);
        pushState();
    }

    /**
     * If a complete translation is already cached for the current source and target language, applies
     * it synchronously (no worker thread, no network) and returns it — {@code null} if none, in which
     * case the caller should fall back to {@link #start}. See {@link SubtitlePipelineSession#loadCompleteFromCache}
     * for why this exists as a separate call instead of just always using {@link #start}.
     */
    @Nullable
    public SubtitleFile loadCompleteFromCache() {
        SubtitleFile result = session.loadCompleteFromCache(targetLanguage());
        if (result != null) pushState();
        return result;
    }

    public void cancel() {
        session.cancel();
    }

    public void restoreOriginal() {
        session.restoreOriginal();
    }

    /** Drops the cached translation for the current target language — see the caller
     *  ({@link CustomSubtitleController#onRestoreOriginal}) for why "Restore Original" does this. */
    public void forgetCachedTranslation() {
        session.forgetTranslation(targetLanguage());
    }

    public void release() {
        // Teardown, not a rejection: whatever chunks were already paid for stay cached.
        session.abandon();
    }

    // --- availability ---

    /** Whether the Translate screen can actually do anything right now (key set, source parseable,
     *  not already in the target language). Lets callers avoid offering a dead button. */
    public boolean isAvailable() {
        return canTranslate();
    }

    private boolean canTranslate() {
        return reasonUnavailable() == null;
    }

    /**
     * Whether the current selection could yield cues on demand (an embedded text track). Mirrors
     * {@code CustomSubtitleController.needsExtraction()}'s exact checks — kept here too so
     * {@link #reasonUnavailable} and {@link #start} share one definition instead of trusting a
     * boolean passed in from outside that could drift from what {@link #embedded} actually decides.
     */
    private boolean isExtractableSelected() {
        return selectedOption != null
                && selectedOption.source == SubtitleOption.Source.EMBEDDED
                && !selectedOption.imageFormat
                && selectedOption.trackState == SubtitleOption.TrackState.NATIVE;
    }

    @Nullable
    private String reasonUnavailable() {
        if (!hasApiKey()) return "No AI API key — set one in Settings > Translation";
        String target = targetLanguage();
        if (source == null) {
            if (!isExtractableSelected()) return "Nothing to translate — pick a subtitle first";
            // Available on purpose: pressing Translate reads the track first, then translates it —
            // but only if the track's own declared language actually needs translating. Without this
            // check, a not-yet-extracted embedded track always looked available regardless of its
            // language, even when it already matches the target (found live: a Spanish embedded
            // track with target=es showed as translatable and then had nothing to actually do).
            String optionLang = selectedOption.language;
            if (optionLang != null && primary(optionLang).equals(primary(target))) {
                return "Already in " + target;
            }
            return null;
        }
        if (!session.canTranslate(target)) return "Already in " + target;
        return null;
    }

    private static String primary(String lang) {
        return lang.split("-")[0];
    }

    private boolean hasApiKey() {
        return !TextUtils.isEmpty(SubtitleSettings.getString(context, SubtitleSettings.KEY_AI_API_KEY, null));
    }

    /** Package-visible: {@link CustomSubtitleController} needs this to check the translation cache
     *  for the "Translated" chip without duplicating the target-language fallback logic. */
    String targetLanguage() {
        List<String> targets = SubtitleSettings.getLanguageList(context, SubtitleSettings.KEY_TARGET_LANGS);
        if (!targets.isEmpty()) return targets.get(0);
        String dev = Locale.getDefault().getLanguage();
        return TextUtils.isEmpty(dev) ? "en" : dev;
    }

    private static TranslationClient buildClient(Context context) {
        String provider = SubtitleSettings.getString(context, SubtitleSettings.KEY_AI_PROVIDER, "openrouter");
        String model = SubtitleSettings.getString(context, SubtitleSettings.KEY_AI_MODEL, "google/gemini-2.5-flash");
        String apiKey = SubtitleSettings.getString(context, SubtitleSettings.KEY_AI_API_KEY, "");
        OkHttpClient http = new OkHttpClient();
        if ("gemini".equals(provider)) {
            return new GeminiClient(apiKey, model, http);
        }
        return new OpenRouterClient(apiKey, model, http);
    }

    // --- SubtitlePipelineSession.Listener ---

    @Override
    public void onSubtitleUpdated(SubtitleFile current) {
        if (pendingSourcePromotion) {
            pendingSourcePromotion = false;
            source = current;
            // The very first entries have streamed in — hand the screen over to them: Media3's native
            // track goes off and our overlay activates on this file, the same two steps (composed
            // there, not duplicated here) an on-demand extraction for Sync/Auto-sync already does.
            promoteToOverlay.accept(current);
        } else {
            // Every call after promotion just swaps the cue text in an already-active sync session —
            // promoteToOverlay() (via CustomSubtitleController#activateOverlay) already did the
            // one-time activation above.
            sync.updateSubtitle(current);
            panel.setSyncSession(sync.getSession());
            if (renderOverlay != null) renderOverlay.run();
        }
    }

    @Override
    public void onProgress(TranslationProgress progress) {
        lastProgress = progress;
        logProgress(progress);
        pushState();
        maybeToast(progress);
        updateIndicatorTerminalFlash(progress);
    }

    // --- logging / toasts (§4: all three failure cases must reach the user and the logs) ---

    private void logProgress(TranslationProgress p) {
        switch (p.getStatus()) {
            case ERROR:
                Log.e(TAG, "translation failed: " + p.getErrorMessage(), p.getError());
                break;
            case DONE:
                if (p.getFailedChunks() > 0 || p.getUntranslatedEntries() > 0) {
                    Log.w(TAG, "translation finished with warnings: failedChunks=" + p.getFailedChunks()
                            + " untranslatedEntries=" + p.getUntranslatedEntries());
                }
                break;
            default:
                break;
        }
    }

    private void maybeToast(TranslationProgress p) {
        if (toastedForRun) return;
        if (p.getStatus() == RunStatus.ERROR) {
            toastedForRun = true;
            String msg = p.getErrorMessage() != null ? p.getErrorMessage() : "unknown error";
            Toast.makeText(context, "Translation failed: " + msg, Toast.LENGTH_LONG).show();
        } else if (p.getStatus() == RunStatus.DONE && p.getUntranslatedEntries() > 0) {
            toastedForRun = true;
            Toast.makeText(context, "Translated with warnings — " + p.getUntranslatedEntries()
                    + " lines kept in the original language", Toast.LENGTH_LONG).show();
        }
    }

    // --- panel state push ---

    private void pushState() {
        RunStatus status = session.status();
        boolean available = canTranslate();
        String reason = reasonUnavailable();
        String statusText = formatStatus(status, lastProgress);
        ButtonState buttons = buttonStateFor(available, status, lastProgress);
        panel.setTranslateState(available, reason, statusText, buttons);
    }

    private static ButtonState buttonStateFor(boolean available, RunStatus status,
                                              @Nullable TranslationProgress p) {
        switch (status) {
            case RUNNING:
                return ButtonState.RUNNING;
            case DONE:
                return (p != null && (p.getFailedChunks() > 0 || p.getUntranslatedEntries() > 0))
                        ? ButtonState.FINISHED_WARNING : ButtonState.FINISHED_OK;
            case CANCELLED:
                return ButtonState.FINISHED_CANCELLED;
            case ERROR:
                return ButtonState.FINISHED_ERROR;
            case IDLE:
            default:
                return available ? ButtonState.IDLE : ButtonState.UNAVAILABLE;
        }
    }

    private String formatStatus(RunStatus status, @Nullable TranslationProgress p) {
        switch (status) {
            case RUNNING:
                if (p == null) return "Translating…";
                if (p.isStreaming()) {
                    StringBuilder streaming = new StringBuilder(streamingSummary(p));
                    streaming.append(formatCostSoFar(p));
                    appendActiveChunks(streaming, p);
                    return streaming.toString();
                }
                StringBuilder running = new StringBuilder("Translating… ")
                        .append(p.getCompletedChunks()).append("/").append(p.getTotalChunks());
                int inProgress = Math.max(0, p.getStartedChunks() - p.getCompletedChunks());
                if (inProgress > 0) running.append(" · ").append(inProgress).append(" in progress");
                if (p.getFailedChunks() > 0) running.append(" · ").append(p.getFailedChunks()).append(" failed");
                if (p.isLastChunkFailed()) running.append(" · ⚠ last chunk failed to translate");
                if (p.getReadyUntilMs() > 0) running.append(" · ready up to ").append(formatDuration(p.getReadyUntilMs()));
                running.append(formatCostSoFar(p));
                appendActiveChunks(running, p);
                return running.toString();
            case DONE:
                return formatDone(p);
            case CANCELLED:
                return "Cancelled";
            case ERROR:
                String msg = (p != null && p.getErrorMessage() != null) ? p.getErrorMessage() : "unknown error";
                return "Translation failed: " + msg;
            case IDLE:
            default:
                return "";
        }
    }

    /**
     * The narrative a streaming run (an embedded track being extracted and translated at once)
     * actually needs to answer for the user: can I start watching, and for how long, and if not yet,
     * how much longer. Three independent facts, all can be true at once — extraction of what comes
     * after the active chunk never stops just because that chunk is translating — so they're joined,
     * not chosen between:
     * <ul>
     *   <li>{@link TranslationProgress#getReadyUntilMs()} &gt; 0 — "watchable up to" so far.</li>
     *   <li>{@link TranslationProgress#getActiveChunks()} non-empty — a chunk is translating right now.</li>
     *   <li>{@link TranslationProgress#getSourceFraction()} &lt; 1.0 — still extracting, with
     *       {@link TranslationProgress#getEtaMs()} (once measurable) saying how much longer until the
     *       chunk currently being built closes.</li>
     *   <li>{@link TranslationProgress#isLastChunkFailed()} — the chunk just before this one never made
     *       it, so the wait for it wasn't for nothing but the content it covered is still untranslated.
     *       Without this the run silently goes back to "extracting" and looks like nothing happened.</li>
     * </ul>
     */
    private static String streamingSummary(TranslationProgress p) {
        StringBuilder sb = new StringBuilder();
        if (p.getReadyUntilMs() > 0) {
            sb.append("Watchable up to ").append(formatDuration(p.getReadyUntilMs()));
        }
        if (!p.getActiveChunks().isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("translating now");
        }
        if (p.getSourceFraction() < 1.0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("extracting");
            if (p.getEtaMs() >= 0) sb.append(" — next chunk in ~").append(formatDuration(p.getEtaMs()));
        }
        if (p.isLastChunkFailed()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("⚠ last chunk failed to translate");
        }
        return sb.length() > 0 ? sb.toString() : "Preparing subtitles…";
    }

    /**
     * One line per chunk currently in flight, with the retries it has already cost. Panel only: the
     * overlay indicator sits over the video and has to stay a single line.
     *
     * <p>Lines are replaced as chunks finish and new ones start, so the block always shows what is
     * being worked on right now rather than a growing history.
     */
    private static void appendActiveChunks(StringBuilder sb, TranslationProgress p) {
        List<ChunkProgress> active = p.getActiveChunks();
        if (active == null || active.isEmpty()) return;
        for (ChunkProgress c : active) {
            sb.append("\n   chunk ").append(c.getIndex())
                    .append(" · lines ").append(c.getFirstEntry()).append("-").append(c.getLastEntry());
            if (c.getRetries() > 0) {
                sb.append(" · retry ").append(c.getRetries());
            }
        }
    }

    /**
     * Cost accrued by the chunks that have finished, as " · $0.0123", or empty until the provider
     * has reported one. Shown live because a long run otherwise gives no hint of what it is spending
     * until it ends — and by then the money is gone.
     */
    private static String formatCostSoFar(@Nullable TranslationProgress p) {
        if (p == null || p.getStats() == null || p.getStats().getCostUsd() == null) return "";
        return " · " + formatCost(p.getStats().getCostUsd());
    }

    /**
     * Money below a dime reads as cents: a running total spends most of its life under $0.10, and
     * "$0.0123" is harder to compare at a glance than "1.23¢".
     */
    private static String formatCost(double costUsd) {
        return costUsd < 0.10
                ? String.format(Locale.US, "%.2f¢", costUsd * 100)
                : String.format(Locale.US, "$%.2f", costUsd);
    }

    private String formatDone(@Nullable TranslationProgress p) {
        int reused = session.reusedEntries();
        if (p == null || p.getStats() == null) {
            return reused > 0 ? "Done — " + reused + " lines from cache, nothing to pay for" : "Done";
        }
        boolean partial = p.getFailedChunks() > 0 || p.getUntranslatedEntries() > 0;
        TranslationStats stats = p.getStats();
        StringBuilder sb = new StringBuilder(partial ? "Partially translated — " : "Done — ")
                .append(stats.getElapsedMs() / 1000).append("s · ")
                .append(String.format(Locale.US, "%,d", stats.getTotalTokens())).append(" tokens");
        if (stats.getCostUsd() != null) {
            sb.append(" · ").append(formatCost(stats.getCostUsd()));
        }
        if (reused > 0) {
            // The cost above is what this run actually spent; saying how much came free is the only
            // way the number makes sense next to a subtitle that is fully translated.
            sb.append(" · ").append(reused).append(" lines reused from cache (not charged)");
        }
        if (p.getUntranslatedEntries() > 0) {
            sb.append(" — ").append(p.getUntranslatedEntries()).append(" lines kept in the original language");
            if (p.getReadyUntilMs() > 0) sb.append(" (ready up to ").append(formatDuration(p.getReadyUntilMs())).append(")");
        }
        return sb.toString();
    }

    /** {@code m:ss}, or {@code h:mm:ss} past the hour mark. */
    private static String formatDuration(long ms) {
        long totalSeconds = ms / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%d:%02d", m, s);
    }
}
