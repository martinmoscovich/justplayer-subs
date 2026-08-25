package com.brouken.player.subs;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.brouken.player.R;
import com.brouken.player.subs.debug.DebugLog;
import com.brouken.player.subs.ui.SubsPill;
import com.brouken.player.subs.ui.SubsTheme;

import subtitleengine.core.model.SubtitleEntry;
import subtitleengine.core.model.SubtitleFile;
import subtitleengine.pipeline.EntrySource;
import subtitleengine.pipeline.InMemoryEntrySource;
import subtitleengine.pipeline.SubtitlePipelineSession;
import subtitleengine.translation.ChunkProgress;
import subtitleengine.translation.ChunkingConfig;
import subtitleengine.translation.CostProjection;
import subtitleengine.translation.RunStatus;
import subtitleengine.translation.SubtitleTranslator;
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
    private final SettingsTranslationClient client;
    private final SubtitlePipelineSession session;
    private final TextView indicator;
    private final ChunkTimelineTracker timeline;
    private final SubsPill compactRow;
    private final ChunkProgressBarView detailedBar;

    /**
     * Switches the corner indicator between the segmented chunk bar and the plain text it replaces.
     * Kept as a flag (not a setting) so the old {@link #indicator}/{@link #renderIndicator} text path
     * stays fully intact behind it — an easy revert if the new design doesn't hold up, with nothing to
     * re-implement.
     */
    private static final boolean USE_CHUNK_BAR_INDICATOR = true;

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
        ChunkingConfig chunkingConfig = ChunkingConfig.defaults();
        SubtitleTranslator.setPricingCacheDir(context.getFilesDir());
        this.client = new SettingsTranslationClient(context);
        SubtitleTranslator translator = new SubtitleTranslator(client);
        this.session = new SubtitlePipelineSession(translator, chunkingConfig, 3,
                mainHandler::post, this);
        this.timeline = new ChunkTimelineTracker(chunkingConfig);

        indicator = new TextView(context);
        indicator.setTextColor(Color.WHITE);
        indicator.setShadowLayer(4f, 0f, 0f, Color.BLACK);
        indicator.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        indicator.setBackgroundColor(0x99000000);
        int pad = Math.round(8 * context.getResources().getDisplayMetrics().density);
        indicator.setPadding(pad, Math.round(pad * 0.6f), pad, Math.round(pad * 0.6f));
        indicator.setVisibility(View.GONE);

        // The glyph sits after the text: this pill lives at the top-right of the screen, and an
        // icon on the outer edge reads as pointing off it.
        compactRow = new SubsPill(context, R.drawable.subtitle_ic_translate, SubsTheme.PRIMARY, true);

        detailedBar = new ChunkProgressBarView(context);
        detailedBar.setDetailed(true);
    }

    /**
     * The top-right indicator: the segmented chunk bar + a "42% · ETA 1:23" label (see
     * {@link #USE_CHUNK_BAR_INDICATOR}) or the text it replaces — "Translating N/M" while a run is
     * active, then a 3s result flash (✓ finished / ⚠ partial / ✗ failed) when it ends. Hidden while
     * the panel is open — full detail (elapsed time, cost, which lines failed, time markers) lives in
     * the Translate screen instead.
     */
    public View getIndicatorView() {
        return USE_CHUNK_BAR_INDICATOR ? compactRow : indicator;
    }

    /** The full-detail bar for the Translate screen — same data, bigger, with time ticks and labels. */
    public View getDetailedBarView() {
        return detailedBar;
    }

    /**
     * The last playhead position and the bar model built for it. The Translate screen's headline
     * figure — how far you can keep watching <em>from where you are</em> — moves with playback, not
     * just with the run, so it has to be recomputed on the tick as well as on every chunk that
     * closes. Both are captured in {@link #renderIndicator}, which is the one call that already
     * happens on every tick and already knows the position.
     */
    private long lastPositionMs;
    private ChunkProgressBarView.Model lastModel = ChunkProgressBarView.Model.EMPTY;

    /** Called every tick — mirrors {@link SubtitleSyncController#render}: hidden while the panel is open. */
    public void renderIndicator(boolean panelOpen, long currentPositionMs) {
        if (indicatorTerminalText != null && System.currentTimeMillis() >= indicatorTerminalUntilMs) {
            indicatorTerminalText = null; // flash window elapsed
        }

        // A paused run keeps its indicator: it is frozen, not finished, and the only cue that work is
        // still parked (and still costing nothing) once the panel is closed.
        RunStatus runStatus = session.status();
        boolean showRunning = runStatus == RunStatus.RUNNING || runStatus == RunStatus.PAUSED;
        boolean showTerminal = indicatorTerminalText != null;
        boolean show = !panelOpen && (showRunning || showTerminal);

        TranslationProgress p = lastProgress;
        // buildModel handles p == null itself (draws the estimate as freshly PENDING) — needed right
        // after start(), before the engine's first callback has posted back to lastProgress yet.
        ChunkProgressBarView.Model model = timeline.buildModel(p, session, currentPositionMs);
        detailedBar.setModel(model);
        lastPositionMs = currentPositionMs;
        lastModel = model;
        // The one call that already builds the model on every tick, so the one place that can see the
        // bar change — including estimated boundaries becoming real ones.
        logBarModel(model);
        // While the panel is up and something is running, the second line of WATCHABLE UP TO counts
        // down in real time — that is the whole point of measuring it from the playhead.
        if (panelOpen && showRunning) pushState();

        if (USE_CHUNK_BAR_INDICATOR) {
            compactRow.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                compactRow.set(indicatorTerminalText != null ? null : "TRANSLATING",
                        indicatorTerminalText != null ? indicatorTerminalText : compactSummary(p, model),
                        ChunkTimelineTracker.miniCells(model, 6));
            }
            return;
        }

        if (panelOpen || (!showRunning && !showTerminal)) {
            if (indicator.getVisibility() != View.GONE) indicator.setVisibility(View.GONE);
            return;
        }
        if (indicator.getVisibility() != View.VISIBLE) indicator.setVisibility(View.VISIBLE);

        String text;
        if (showTerminal) {
            text = indicatorTerminalText;
        } else if (p != null && p.isStreaming()) {
            text = streamingSummary(p);
        } else {
            text = (p != null)
                    ? "Translating " + p.getCompletedChunks() + "/" + p.getTotalChunks() + formatCostSoFar(p)
                    : "Translating…";
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
        // Which key a run reads and writes under decides whether work already paid for is found. A
        // translation that "started over from scratch" is usually this key having changed, not the
        // cache having lost anything — see EmbeddedSubtitleController.HASH_CACHE's javadoc.
        if (!java.util.Objects.equals(subtitleKey, debugCacheKey)) {
            debugCacheKey = subtitleKey;
            DebugLog.log(DebugLog.CAT_CACHE, "translation cache key -> " + subtitleKey);
        }
        session.setCache(cache, subtitleKey);
        pushState();
    }

    @Nullable private String debugCacheKey;

    /**
     * "Retry missing lines": start again <em>without</em> dropping the stored translation, so the
     * session's cache pass reuses every chunk that already succeeded and only the ones that failed
     * are sent (and paid for) a second time. The whole difference from {@link #translateAgain} is the
     * missing {@code forgetTranslation} — which is also why it destroys nothing and is allowed to
     * hold the default focus on the partial-result screen.
     */
    public void retryMissing(long positionMs, long videoDurationMs) {
        start(positionMs, videoDurationMs);
    }

    /** "Translate again": drops the stored translation first, or the rerun would just serve it back. */
    public void translateAgain(long positionMs, long videoDurationMs) {
        session.forgetTranslation(targetLanguage());
        session.restoreOriginal();
        start(positionMs, videoDurationMs);
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
        if (file == null) {
            detailedBar.setModel(ChunkProgressBarView.Model.EMPTY);
        }
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
     * @param positionMs     current playback position — entries at/after it are prioritized so the
     *                       user can keep watching without gaps as soon as possible. Applies both to an
     *                       already fully-known source and to a not-yet-extracted embedded track (see
     *                       {@link SubtitlePipelineSession}'s class javadoc and
     *                       {@link SubtitlePipelineSession#runStreamingWithPriority}) — the only case
     *                       it's ignored for is a complete-cache-hit embedded track, which never reaches
     *                       the streaming path at all (see the cache check in this method's body).
     * @param videoDurationMs the video's total duration ({@code player.getDuration()}) — used only to
     *                       lay out the chunk progress bar's estimated boundaries before any entry is
     *                       known; the translation itself doesn't need it.
     */
    public void start(long positionMs, long videoDurationMs) {
        if (!canTranslate()) {
            DebugLog.log(DebugLog.CAT_TRANSLATE, () -> "start refused: " + reasonUnavailable());
            return;
        }
        DebugLog.log(DebugLog.CAT_TRANSLATE, () -> "start target=" + targetLanguage()
                + " provider=" + SubtitleSettings.getString(context, SubtitleSettings.KEY_AI_PROVIDER, "(unset)")
                + " model=" + SubtitleSettings.getString(context, SubtitleSettings.KEY_AI_MODEL, "(unset)")
                + " source=" + (source != null ? "known(" + source.getEntries().size() + " lines)"
                        : "embedded track, not extracted yet")
                + " from=" + positionMs + "ms duration=" + videoDurationMs + "ms");
        // Pick up a provider/model/key edited since the last run. Here and not per completion: a run
        // must not change model halfway through. See SettingsTranslationClient.
        client.refresh();
        toastedForRun = false;
        lastProgress = null;
        indicatorTerminalText = null;
        debugActiveChunks.clear();
        debugChunkErrors.clear();
        debugLastStatus = null;
        debugSourceState = null;
        debugSourceStep = -1;
        debugBarModel = null;

        List<SubtitleEntry> exactEntries = null;
        if (!hasUsableSource() && isExtractableSelected()) {
            pendingSourcePromotion = true;
            // A complete cache hit stays on the cheap in-memory path (also picks up today's existing
            // in-memory priority-pass split via session.start's positionMs below) — running an
            // already-fully-known list through the position-priority streaming factory would translate
            // it twice (once per pass). Only a track that still needs reading from the container goes
            // through the factory, so its priority pass can start at positionMs instead of always 0.
            SubtitleFile cachedComplete = embedded.peekCache(selectedOption);
            if (cachedComplete != null) {
                session.setSource(new InMemoryEntrySource(cachedComplete.getEntries()),
                        selectedOption.language, movieTitle);
            } else {
                SubtitlePipelineSession.StreamingSourceFactory factory =
                        embedded.streamingFactoryFor(selectedOption, positionMs);
                if (factory == null) return; // isExtractableSelected() said yes but embedded disagreed
                session.setSource(factory, selectedOption.language, movieTitle);
            }
        } else {
            pendingSourcePromotion = false;
            if (source != null) exactEntries = source.getEntries();
        }
        timeline.reset(videoDurationMs, positionMs, exactEntries);
        // Draw the freshly-reset estimate right now — session.start() reports back to lastProgress
        // from a worker thread, so without this the bar (and pushState() below, which reads lastModel
        // for the Progress %/chunk-count text) would sit on whatever it last showed until the next
        // tick's renderIndicator() picks up that first callback. Ordinarily that's ~100ms
        // (CustomSubtitleController.POLL_MS); visibly worse if the main thread is busy right around
        // when a run starts (media hashing, subtitle search, etc.).
        lastModel = timeline.buildModel(null, session, positionMs);
        detailedBar.setModel(lastModel);
        lastPositionMs = positionMs;

        session.start(targetLanguage(), positionMs);
        pushState();
    }

    /**
     * If a complete translation is already cached for the current source and target language, applies
     * it synchronously (no worker thread, no network) and returns it — {@code null} if none, in which
     * case the caller should fall back to {@link #start}. See {@link SubtitlePipelineSession#loadCompleteFromCache}
     * for why this exists as a separate call instead of just always using {@link #start}.
     *
     * <p>This path never goes through {@link #onProgress} (the session applies the cache hit directly,
     * with no listener callback), so the chunk bar would otherwise stay on whatever it last showed —
     * empty on a fresh launch. Feeding the timeline a minimal synthetic DONE snapshot here is enough:
     * every segment renders green for a DONE run regardless of its other fields (see
     * {@code ChunkTimelineTracker}), so the exact preview from {@code exactEntries} is all it needs.
     */
    @Nullable
    public SubtitleFile loadCompleteFromCache(long videoDurationMs) {
        SubtitleFile result = session.loadCompleteFromCache(targetLanguage());
        DebugLog.log(DebugLog.CAT_TRANSLATE, () -> result != null
                ? "complete cache hit for " + targetLanguage() + ": " + result.getEntries().size()
                        + " lines applied without a run"
                : "no complete cached translation for " + targetLanguage());
        if (result != null) {
            timeline.reset(videoDurationMs, 0L, result.getEntries());
            lastProgress = new TranslationProgress(RunStatus.DONE, 0, 0, 0, 0, 0, 0L, List.of(),
                    null, null, null, false, null, 1.0, -1L, false, java.util.Map.of(), -1, null, 0L, -1.0);
            pushState();
        }
        return result;
    }

    public void cancel() {
        session.cancel();
    }

    /** Freezes the run, keeping everything it already produced — see
     *  {@link SubtitlePipelineSession#pause()}. */
    public void pause() {
        session.pause();
        pushState();
    }

    public void resume() {
        session.resume();
        pushState();
    }

    public void restoreOriginal() {
        session.restoreOriginal();
    }

    /** Drops the cached translation for the current target language — see the caller
     *  ({@link CustomSubtitleController#onRestoreOriginal}) for why "Restore Original" does this. */
    public void forgetCachedTranslation() {
        DebugLog.log(DebugLog.CAT_TRANSLATE, () -> "forgetting the cached translation for " + targetLanguage());
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

    /** Whether a translation run is under way — RUNNING or frozen by {@link #pause()}. Used to decide
     *  whether opening the Translate screen should pause playback (see
     *  {@code CustomSubtitleController#onEnteringTranslate}) — a finished/idle/failed run has nothing
     *  competing for attention. */
    public boolean isActive() {
        RunStatus status = session.status();
        return status == RunStatus.RUNNING || status == RunStatus.PAUSED;
    }

    private boolean canTranslate() {
        return reasonUnavailable() == null;
    }

    /**
     * Whether {@link #source} is something a run can actually translate.
     *
     * <p>An <em>empty</em> file is not: an extraction that yielded nothing still promotes a
     * zero-entry {@link SubtitleFile} through {@link #onSubtitleUpdated}, and from then on
     * {@code source != null} was enough to make this class treat the track as already read. Pressing
     * Translate again then started a run over nothing — seen in a real log as
     * {@code source=known(0 lines)}, which cannot produce anything and says so to nobody. Treating
     * empty as absent sends the retry back through extraction, which is what the user was asking for.
     */
    /**
     * A run that has finished having read nothing at all. Distinguished from an ordinary empty result
     * by {@link #pendingSourcePromotion}: it is only ever true for a run that started from a
     * not-yet-extracted track, and it is cleared the moment the first entry arrives — so still being
     * set at the end means no entry ever did.
     */
    private boolean finishedWithNothingExtracted() {
        return pendingSourcePromotion && !hasUsableSource();
    }

    private boolean hasUsableSource() {
        return source != null && !source.getEntries().isEmpty();
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
        if (!hasUsableSource()) {
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
        return !TextUtils.isEmpty(SubtitleSettings.getApiKey(context, SubtitleSettings.KEY_AI_API_KEY));
    }

    /** Package-visible: {@link CustomSubtitleController} needs this to check the translation cache
     *  for the "Translated" chip without duplicating the target-language fallback logic. */
    String targetLanguage() {
        List<String> targets = SubtitleSettings.getLanguageList(context, SubtitleSettings.KEY_TARGET_LANGS);
        if (!targets.isEmpty()) return targets.get(0);
        String dev = Locale.getDefault().getLanguage();
        return TextUtils.isEmpty(dev) ? "en" : dev;
    }


    // --- SubtitlePipelineSession.Listener ---

    @Override
    public void onSubtitleUpdated(SubtitleFile current) {
        // An empty file is not "the first entries have streamed in" — it is the run reporting that it
        // has nothing. Promoting it took Media3's own subtitle off the screen in exchange for an empty
        // overlay, and left this class believing the track had been read: the next Translate then ran
        // over `source=known(0 lines)`, which can produce nothing and said so to nobody. Ignoring it
        // keeps the run in its not-yet-extracted state, which is the truth.
        if (current == null || current.getEntries().isEmpty()) return;
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
        timeline.onProgress(progress);
        logProgress(progress);
        logDebugProgress(progress);
        pushState();
        maybeToast(progress);
        updateIndicatorTerminalFlash(progress);
    }

    // --- debug log ---

    /** Chunk indices in flight and the retry count last reported for each, so a chunk is logged once
     *  when it starts, again whenever it retries, and once when it leaves. */
    private final java.util.Map<Integer, Integer> debugActiveChunks = new java.util.LinkedHashMap<>();
    /** Chunk errors already reported, keyed by chunk — the engine's map keeps a failure until that
     *  same chunk succeeds, so without this every callback would repeat it. */
    private final java.util.Map<Integer, String> debugChunkErrors = new java.util.HashMap<>();
    @Nullable private RunStatus debugLastStatus;

    /**
     * The run as a sequence of events rather than a state to render: status transitions, each chunk
     * entering and leaving flight, each new failure with its reason, and the totals at the end.
     *
     * <p>Complements the engine's own per-chunk lines (captured through {@code EngineLogBridge}) — the
     * engine reports what a chunk cost, this reports where it sits on the video timeline, which is
     * what a "the subtitles go untranslated around 40 minutes in" report is checked against.
     */
    private void logDebugProgress(TranslationProgress p) {
        if (!DebugLog.enabled()) return;

        if (p.getStatus() != debugLastStatus) {
            debugLastStatus = p.getStatus();
            DebugLog.log(DebugLog.CAT_TRANSLATE, "status " + p.getStatus()
                    + (p.isStreaming() ? " (streaming: source still being extracted)" : ""));
        }

        logStreamingSource(p);

        java.util.Set<Integer> current = new java.util.HashSet<>();
        for (ChunkProgress c : p.getActiveChunks()) {
            current.add(c.getIndex());
            Integer knownRetries = debugActiveChunks.put(c.getIndex(), c.getRetries());
            if (knownRetries == null) {
                DebugLog.log(DebugLog.CAT_TRANSLATE, String.format(Locale.US,
                        "chunk=%d start lines=%d-%d t=%s-%s retries=%d",
                        c.getIndex(), c.getFirstEntry(), c.getLastEntry(),
                        formatDuration(c.getStartMs()), formatDuration(c.getEndMs()), c.getRetries()));
            } else if (knownRetries != c.getRetries()) {
                // A climbing count while the chunk is still in flight is the difference between "this
                // is slow" and "this one is fighting the model" — invisible if only read at start.
                DebugLog.log(DebugLog.CAT_TRANSLATE, "chunk=" + c.getIndex() + " retry " + c.getRetries());
            }
        }
        // Leaving the in-flight set is the only signal a chunk finished. Reported here rather than
        // left to the engine's own "chunk[i/n] ok" line: that line is the engine's to change, and
        // without this the log would show every chunk starting and none ending.
        for (java.util.Iterator<Integer> it = debugActiveChunks.keySet().iterator(); it.hasNext(); ) {
            Integer index = it.next();
            if (current.contains(index)) continue;
            it.remove();
            DebugLog.log(DebugLog.CAT_TRANSLATE, "chunk=" + index + " done"
                    + (p.getChunkErrors().containsKey(index) ? " (failed)" : "")
                    + " — completed=" + p.getCompletedChunks() + " failed=" + p.getFailedChunks()
                    + " " + describeCushion(p));
        }

        for (java.util.Map.Entry<Integer, String> e : p.getChunkErrors().entrySet()) {
            if (!e.getValue().equals(debugChunkErrors.put(e.getKey(), e.getValue()))) {
                DebugLog.log(DebugLog.CAT_TRANSLATE,
                        "chunk=" + e.getKey() + " FAILED reason=\"" + e.getValue() + "\"");
            }
        }
        debugChunkErrors.keySet().retainAll(p.getChunkErrors().keySet()); // a retry that succeeded

        if (p.getStatus() == RunStatus.DONE || p.getStatus() == RunStatus.ERROR) {
            DebugLog.log(DebugLog.CAT_TRANSLATE, p.getStatus() + " chunks=" + p.getCompletedChunks()
                    + "/" + p.getTotalChunks() + " failed=" + p.getFailedChunks()
                    + " untranslated=" + p.getUntranslatedEntries()
                    + " reused=" + session.reusedEntries()
                    + " " + describeStats(p.getStats())
                    + (p.getErrorMessage() != null ? " error=\"" + p.getErrorMessage() + "\"" : ""));
        }
    }

    @Nullable private TranslationProgress.SourceState debugSourceState;
    private int debugSourceStep = -1;

    /**
     * How much translated subtitle is still ahead of the playhead — the quantity that decides whether
     * the user is about to run into untranslated lines, and the one this whole pipeline exists to keep
     * positive.
     *
     * <p>Logged as its own figure rather than left to be reconstructed: {@code readyUntilMs} alone is
     * an absolute timestamp, so working out the margin from it means cross-referencing every
     * {@code [PLAYBACK]} line by hand, and the answer is only as good as the last position that
     * happened to be logged. Measured directly, a single run tells you whether the margin ever gets
     * thin and how fast it moves — which is what any threshold for reacting to it has to be set from.
     */
    private String describeCushion(TranslationProgress p) {
        long until = watchableUntilMs();
        long ahead = until - lastPositionMs;
        return "readyUntil=" + formatDuration(p.getReadyUntilMs())
                + " watchableUntil=" + formatDuration(until)
                + " playhead=" + formatDuration(lastPositionMs)
                + " cushion=" + (ahead > 0 ? formatDuration(ahead) : "0:00 (caught up)");
    }

    /**
     * The extraction half of a streaming run — a track being read out of the container while chunks
     * translate behind it.
     *
     * <p>It has to be reported from here and not from {@link EmbeddedSubtitleController}, which is
     * where the rest of the {@code EXTRACT-SUBS} lines come from: {@link #start} routes a
     * not-yet-extracted track through {@code embedded.streamingFactoryFor(...)} into <em>this</em>
     * class's own session, so that controller's listener never fires for this path and its logging
     * sits idle. Found exactly that way — a full streaming translate produced not one extraction line.
     *
     * <p>Throttled to deciles of the container read plus every {@code sourceState} flip: the source
     * reports far more often than that, and the point of these lines is the shape of the read (is it
     * keeping ahead of translation, at what speed, with what ETA), not its every increment.
     */
    private void logStreamingSource(TranslationProgress p) {
        if (!p.isStreaming()) return;
        if (p.getSourceState() != debugSourceState) {
            debugSourceState = p.getSourceState();
            // WAITING means translation has caught up with the read and is idling for the next chunk's
            // worth of entries — the thing to look for when a run is slower than the API alone explains.
            DebugLog.log(DebugLog.CAT_EXTRACT_SUBS, "source " + debugSourceState);
        }
        int step = (int) (Math.max(0.0, Math.min(1.0, p.getSourceFraction())) * 10);
        if (step == debugSourceStep) return;
        debugSourceStep = step;
        DebugLog.log(DebugLog.CAT_EXTRACT_SUBS, String.format(Locale.US,
                "reading %d%% of the container · content up to %s · %s · next chunk %s · %s",
                step * 10, formatDuration(p.getExtractedContentMs()),
                p.getExtractionSpeedFactor() >= 0
                        ? String.format(Locale.US, "%.1fx real time", p.getExtractionSpeedFactor())
                        : "speed not measurable yet",
                p.getEtaMs() >= 0 ? "in ~" + formatDuration(p.getEtaMs()) : "ETA unknown",
                // The margin, next to the rate that governs it: a speed above 1x grows the cushion and
                // a speed below 1x drains it, and seeing both on one line is what makes a run
                // diagnosable without cross-referencing anything.
                describeCushion(p)));
    }

    /** Last bar rendering written, so a bar that is not changing writes nothing on the 100ms tick. */
    @Nullable private String debugBarModel;

    /**
     * The chunk bar as it is actually drawn, written only when the drawing changes. This is the one
     * place the estimated→real transition is recorded: {@link ChunkTimelineTracker} rebuilds the model
     * from scratch every tick and persists nothing, so a bar that guessed twelve segments and settled
     * on seven leaves no trace anywhere else.
     */
    private void logBarModel(ChunkProgressBarView.Model model) {
        if (!DebugLog.enabled()) return;
        String rendered = ChunkTimelineTracker.describe(model);
        if (rendered.equals(debugBarModel)) return;
        debugBarModel = rendered;
        DebugLog.log(DebugLog.CAT_BAR, rendered);
    }

    /** Tokens and money are per <em>run</em>, not per chunk — {@link TranslationStats} is cumulative,
     *  so a chunk's own share is the delta between two of these lines, not a field. */
    private static String describeStats(@Nullable TranslationStats st) {
        if (st == null) return "stats=none";
        return String.format(Locale.US, "tokens=%d/%d cost=%s elapsed=%ds",
                st.getInputTokens(), st.getOutputTokens(),
                st.getCostUsd() != null ? formatCost(st.getCostUsd()) : "?", st.getElapsedMs() / 1000);
    }

    // --- logging / toasts (§4: all three failure cases must reach the user and the logs) ---

    private void logProgress(TranslationProgress p) {
        switch (p.getStatus()) {
            case ERROR:
                Log.e(TAG, "translation failed: " + p.getErrorMessage(), p.getError());
                break;
            case DONE:
                if (p.getFailedChunks() > 0 || p.getUntranslatedEntries() > 0) {
                    // The throwable, not just its message: a partial failure is still a failure, and
                    // the stack trace is the only place the real cause survives.
                    Log.w(TAG, "translation finished with warnings: failedChunks=" + p.getFailedChunks()
                            + " untranslatedEntries=" + p.getUntranslatedEntries()
                            + (chunkErrorNote(p) != null ? " lastChunkError=" + chunkErrorNote(p) : ""),
                            p.getLastChunkError());
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
        } else if (p.getStatus() == RunStatus.DONE && finishedWithNothingExtracted()) {
            // A read that yields no cues ends as a perfectly clean DONE — nothing failed, nothing was
            // left untranslated, because nothing existed. Without this it reports as success and the
            // screen says "Done" over an empty result. The non-streaming path has always said so
            // (EmbeddedSubtitleController's "no readable subtitles"); the streaming path never did.
            toastedForRun = true;
            Toast.makeText(context, "No subtitles could be read from that track", Toast.LENGTH_LONG).show();
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
        ButtonState buttons = buttonStateFor(available, status, lastProgress);
        panel.setTranslateState(available, buildUiState(status, buttons, lastProgress));
    }

    /**
     * Turns the run's numbers into the three or four things the screen says. Everything the chunk bar
     * can already show — how many are in flight, which failed, that something is translating right
     * now — is deliberately absent: it is drawn, not written.
     */
    private TranslateUiState buildUiState(RunStatus status, ButtonState buttons,
                                          @Nullable TranslationProgress p) {
        switch (buttons) {
            case UNAVAILABLE:
                String reason = reasonUnavailable();
                return TranslateUiState.of(TranslateUiState.Mode.UNAVAILABLE, buttons)
                        .block("Translation unavailable", reason)
                        .build();
            case IDLE:
                return TranslateUiState.of(TranslateUiState.Mode.IDLE, buttons)
                        .block("Translate to " + targetLanguageName(), idleSubline())
                        .build();
            case RUNNING:
                return runningState(buttons, p);
            case PAUSED:
                return pausedState(buttons, p);
            default:
                return finishedState(buttons, p);
        }
    }

    private TranslateUiState runningState(ButtonState buttons, @Nullable TranslationProgress p) {
        TranslateUiState.Builder b = TranslateUiState.of(TranslateUiState.Mode.RUNNING, buttons).bar(true);
        addWatchablePanel(b, "Watchable up to", watchableSubline(p));
        b.panel("Progress", ChunkTimelineTracker.percentDone(lastModel) + "%", progressSubline(p), false);
        addCostPanel(b, "Spent so far", p, true);
        addLegend(b);
        addPills(b, p);
        b.errorNote(chunkErrorNote(p));
        return b.build();
    }

    private TranslateUiState pausedState(ButtonState buttons, @Nullable TranslationProgress p) {
        // Both sublines say the same thing in two registers: the run is frozen, not thrown away.
        // That is the entire difference between Pause and Cancel, and it belongs where the eye
        // already is rather than in a sentence somewhere else on the screen.
        TranslateUiState.Builder b = TranslateUiState.of(TranslateUiState.Mode.PAUSED, buttons)
                .bar(true)
                .panel("Paused at", formatDuration(watchableUntilMs()), "nothing is lost", true)
                .panel("Spent so far", costFigure(p), "resume to continue", false);
        addLegend(b);
        b.errorNote(chunkErrorNote(p));
        return b.build();
    }

    /**
     * The explanation for the newest red chunk, or null when nothing is failed. Invariant the UI
     * relies on: a red segment always has a reason, and when there is more than one only the most
     * recent is shown — one line is all there is room for, and it is the one still worth acting on.
     */
    @Nullable
    private static String chunkErrorNote(@Nullable TranslationProgress p) {
        if (p == null || p.getChunkErrors() == null || p.getChunkErrors().isEmpty()) return null;
        return p.getChunkErrors().get(p.getLastFailedChunkIndex());
    }

    private TranslateUiState finishedState(ButtonState buttons, @Nullable TranslationProgress p) {
        int reused = session.reusedEntries();
        boolean partial = buttons == ButtonState.FINISHED_WARNING;
        TranslateUiState.Builder b;
        switch (buttons) {
            case FINISHED_ERROR:
                String msg = (p != null && p.getErrorMessage() != null) ? p.getErrorMessage() : "unknown error";
                // No panels: nothing was produced, so there is nothing to put a number on.
                return TranslateUiState.of(TranslateUiState.Mode.FINISHED, buttons)
                        .result(TranslateUiState.Tone.ERROR, "Translation failed", msg)
                        .build();
            case FINISHED_CANCELLED:
                return TranslateUiState.of(TranslateUiState.Mode.FINISHED, buttons)
                        .result(TranslateUiState.Tone.MUTED, "Cancelled", "Nothing was kept")
                        .build();
            case FINISHED_WARNING:
                int kept = p != null ? p.getUntranslatedEntries() : 0;
                b = TranslateUiState.of(TranslateUiState.Mode.FINISHED, buttons)
                        .result(TranslateUiState.Tone.WARN, "Partially translated",
                                kept > 0 ? kept + " lines kept in the original language" : null)
                        .errorNote(chunkErrorNote(p))
                        .retryMissing(true);
                break;
            case FINISHED_OK:
            default:
                if (finishedWithNothingExtracted()) {
                    // Not an error and not a success: the track had nothing readable in it. Saying
                    // "Done" here was the screen's own version of the silent failure this run was.
                    return TranslateUiState.of(TranslateUiState.Mode.FINISHED, buttons)
                            .result(TranslateUiState.Tone.WARN, "Nothing to translate",
                                    "No subtitles could be read from that track")
                            .build();
                }
                boolean freeRun = p == null || p.getStats() == null || p.getStats().getCostUsd() == null
                        || p.getStats().getCostUsd() <= 0;
                b = TranslateUiState.of(TranslateUiState.Mode.FINISHED, buttons)
                        .result(TranslateUiState.Tone.OK,
                                freeRun && reused > 0 ? "Done, nothing to pay for" : "Done",
                                reused > 0 ? reused + " lines reused from cache" : null);
                break;
        }
        b.bar(true);
        addWatchablePanel(b, "Watchable up to", chunkCountSubline(p));
        addCostPanel(b, "Cost", p, false);
        addLegend(b);
        b.errorNote(chunkErrorNote(p));
        return b.build();
    }

    // --- the pieces each state is assembled from ---

    private void addWatchablePanel(TranslateUiState.Builder b, String label, @Nullable String sub) {
        // watchableUntilMs() floors at the playhead itself (see its javadoc) — reads fine once
        // something is actually ready ahead of you, but showing that same floor value as if it were a
        // real boundary reads as "everything up to right now is watchable" before anything is. "-"
        // says plainly that nothing is ready yet, same moment watchableSubline() already says so.
        long until = watchableUntilMs();
        String value = until > lastPositionMs ? formatDuration(until) : "-";
        b.panel(label, value, sub, true);
    }

    /**
     * How far the user can keep watching without running into untranslated lines, measured
     * <em>from where they are</em> rather than from the start of the file — a gap they have already
     * driven past is no longer their problem.
     */
    private long watchableUntilMs() {
        return session.watchableUntilMs(lastPositionMs);
    }

    /**
     * How much of that is still ahead of the playhead. Hits zero exactly when playback catches up —
     * but that reads two different ways depending on whether anything has been produced at all yet:
     * fresh off {@code start()}, "caught up" implies something was ahead and got left behind, which
     * isn't true the first few seconds of a run. {@link TranslationProgress#getCompletedChunks()} is
     * 0 only until the very first chunk (of either pass, for a position-priority run) closes anywhere
     * — by the time one has, the priority pass (always first) has necessarily produced something at
     * or ahead of wherever it started, so "caught up" becomes an honest description again.
     */
    @Nullable
    private String watchableSubline(@Nullable TranslationProgress p) {
        long left = watchableUntilMs() - lastPositionMs;
        if (left <= 0) {
            return (p == null || p.getCompletedChunks() == 0) ? "nothing translated yet" : "playback has caught up";
        }
        return formatDuration(left) + " left from here";
    }

    @Nullable
    private String progressSubline(@Nullable TranslationProgress p) {
        if (p == null) return null;
        StringBuilder sb = new StringBuilder();
        int total = estimatedTotalChunks(p);
        if (total > 0) {
            sb.append(p.getCompletedChunks()).append(" of ")
                    .append(p.isStreaming() ? "~" : "").append(total).append(" chunks");
        }
        // Not a ternary: mixing a primitive long with a nullable Long makes javac unbox the null
        // branch, so "no ETA yet" crashed instead of simply not being shown.
        Long eta;
        if (p.isStreaming() && p.getEtaMs() >= 0) {
            eta = p.getEtaMs();
        } else {
            eta = timeline.estimatedRemainingMs(p);
        }
        // Absent until the first chunk closes: before that there is no average to extrapolate from,
        // and the screen has to be able to live without it.
        if (eta != null && eta >= 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append("ETA ").append(formatDuration(eta));
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    @Nullable
    private String chunkCountSubline(@Nullable TranslationProgress p) {
        if (p == null) return null;
        int total = estimatedTotalChunks(p);
        if (total <= 0) return null;
        return p.getCompletedChunks() + " of " + (p.isStreaming() ? "~" : "") + total + " chunks";
    }

    /**
     * {@link TranslationProgress#getTotalChunks()} is a live count of chunks actually dispatched so
     * far in a streaming run — not a projection — so early on it reads as "that's everything" (e.g.
     * "1 of ~1") while the segmented bar next to it already draws several pending segments from
     * {@link ChunkTimelineTracker}'s duration-based estimate. Reconciles the two: for a streaming run,
     * never show a total lower than however many segments {@link #lastModel} (kept fresh every tick by
     * {@link #renderIndicator}) currently draws.
     */
    private int estimatedTotalChunks(TranslationProgress p) {
        if (!p.isStreaming()) return p.getTotalChunks();
        return Math.max(p.getTotalChunks(), lastModel.segments.size());
    }

    private void addCostPanel(TranslateUiState.Builder b, String label, @Nullable TranslationProgress p,
                              boolean project) {
        b.panel(label, costFigure(p), project ? projectionSubline(p) : tokensSubline(p), false);
    }

    private static String costFigure(@Nullable TranslationProgress p) {
        Double cost = (p != null && p.getStats() != null) ? p.getStats().getCostUsd() : null;
        return cost != null ? formatCost(cost) : "—";
    }

    /**
     * What it is going to cost, not just what it has cost. Mid-run that is the number that decides
     * whether to let it finish. {@code null} when there is nothing honest to extrapolate from — see
     * {@link CostProjection} — and then the panel simply shows the figure on its own.
     */
    @Nullable
    private String projectionSubline(@Nullable TranslationProgress p) {
        if (p == null || p.getStats() == null) return null;
        Double projected = CostProjection.projectUsd(p.getStats().getCostUsd(),
                p.getCompletedChunks(), p.isStreaming() ? 0 : p.getTotalChunks());
        if (projected == null) return tokensSubline(p);
        return "≈ " + formatCost(projected) + " when done";
    }

    @Nullable
    private String tokensSubline(@Nullable TranslationProgress p) {
        if (p == null || p.getStats() == null) return null;
        TranslationStats st = p.getStats();
        StringBuilder sb = new StringBuilder(String.format(Locale.US, "%,d tokens", st.getTotalTokens()));
        if (st.getElapsedMs() > 0) sb.append(" · ").append(st.getElapsedMs() / 1000).append("s");
        return sb.toString();
    }

    /**
     * A key to the colours that are <em>actually on the bar right now</em> — never a running
     * commentary. With nothing extracting there is no EXTRACTING entry, and with everything green
     * there is no legend at all.
     */
    private void addLegend(TranslateUiState.Builder b) {
        boolean extracting = false, translating = false, failed = false, background = false;
        boolean noDialogue = false;
        for (ChunkProgressBarView.Segment seg : lastModel.segments) {
            switch (seg.state) {
                case EXTRACTING:
                case CLOSING: extracting = true; break;
                case TRANSLATING: translating = true; break;
                case FAILED: failed = true; break;
                case NO_DIALOGUE: noDialogue = true; break;
                default: break;
            }
            if (seg.backgroundPass) background = true;
        }
        if (extracting) b.legend(SubsTheme.STATUS_EXTRACTING, "EXTRACTING", false);
        if (translating) b.legend(SubsTheme.STATUS_TRANSLATING, "TRANSLATING", false);
        if (failed) b.legend(SubsTheme.STATUS_FAILED, "FAILED", false);
        // Worth a key of its own: without one, a stretch drawn in neither green nor grey reads as an
        // unexplained third thing. "Nothing to translate here" is the whole meaning.
        if (noDialogue) b.legend(SubsTheme.STATUS_NO_DIALOGUE, "NO DIALOGUE", false);
        if (background) b.legend(SubsTheme.STATUS_PENDING, "BACKFILLING", true);
    }

    /** One pill per chunk in flight, replaced as they close — never a growing history. */
    private void addPills(TranslateUiState.Builder b, @Nullable TranslationProgress p) {
        if (p == null) return;
        List<ChunkProgress> active = p.getActiveChunks();
        if (active == null) return;
        for (ChunkProgress c : active) {
            // One-based: the engine counts chunks from 0 for its logs, and "Chunk 0" on screen
            // reads as a bug rather than as the first one.
            b.pill(SubsTheme.STATUS_TRANSLATING, "Chunk " + (c.getIndex() + 1),
                    c.getRetries() > 0 ? "retry " + c.getRetries() : null);
        }
    }

    /** The target language as the user would name it, for the idle screen's headline. */
    private String targetLanguageName() {
        String code = targetLanguage();
        String name = new Locale(code.split("-")[0]).getDisplayLanguage(Locale.getDefault());
        return (name == null || name.isEmpty()) ? code : name;
    }

    @Nullable
    private String idleSubline() {
        // Only what is actually known: an embedded track that has not been read yet has no line count
        // to report, and inventing one would be worse than saying nothing.
        if (!hasUsableSource()) return null;
        return String.format(Locale.US, "%,d lines", source.getEntries().size());
    }

    private static ButtonState buttonStateFor(boolean available, RunStatus status,
                                              @Nullable TranslationProgress p) {
        switch (status) {
            case RUNNING:
                return ButtonState.RUNNING;
            case PAUSED:
                return ButtonState.PAUSED;
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

    /**
     * "42% · ETA 1:23" for the compact indicator's label, next to the bar — the bar alone (no text,
     * see the design plan) doesn't answer "how much longer", so this fills that gap.
     */
    private String compactSummary(@Nullable TranslationProgress p, ChunkProgressBarView.Model model) {
        if (p == null) return "";
        StringBuilder sb = new StringBuilder().append(ChunkTimelineTracker.percentDone(model)).append("%");
        long etaMs = -1L;
        if (p.isStreaming() && p.getEtaMs() >= 0) {
            etaMs = p.getEtaMs();
        } else {
            Long remaining = timeline.estimatedRemainingMs(p);
            if (remaining != null) etaMs = remaining;
        }
        if (etaMs >= 0) sb.append(" · ETA ").append(formatDuration(etaMs));
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
