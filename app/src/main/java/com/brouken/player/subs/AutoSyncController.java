package com.brouken.player.subs;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Process;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.brouken.player.R;
import com.brouken.player.subs.ui.SubsPill;
import com.brouken.player.subs.ui.SubsTheme;

import java.util.Locale;
import java.util.concurrent.ThreadFactory;

import subtitleengine.core.model.SubtitleFile;
import subtitleengine.resync.AutoSyncEvidencePolicy;
import subtitleengine.resync.AutoSyncProbeLocator;
import subtitleengine.resync.AutoSyncProgress;
import subtitleengine.resync.AutoSyncSession;
import subtitleengine.resync.DialogueDensityProbeLocator;
import subtitleengine.resync.DialoguePacingEvidencePolicy;
import subtitleengine.resync.FractionalProbeLocator;
import subtitleengine.resync.ProbePlacement;
import subtitleengine.vad.ResyncResult;
import subtitleengine.vad.SileroVadEngine;
import subtitleengine.vad.SubtitleResyncer;

/**
 * Thin adapter between Android and the engine's {@link AutoSyncSession}: builds the VAD pipeline,
 * resolves the media source, formats all user-facing strings, and pushes state into
 * {@link SubtitlePanel} / {@link SyncView}. Mirrors {@link TranslationController} in shape — this
 * class only translates engine events into Android calls — with two deliberate differences forced
 * by the VAD workload (not present in {@link TranslationController}, which builds its client eagerly
 * and has no engine resource to close):
 * <ul>
 *   <li>The {@link SubtitleResyncer} (which owns the ONNX runtime) is built lazily on the first
 *       {@link #start()} rather than in the constructor — loading the ONNX model isn't free.</li>
 *   <li>It is kept alive across runs and only closed in {@link #release()}: {@link SubtitleResyncer#close()}
 *       tears down the process-global ONNX {@code OrtEnvironment}, so creating/closing it per run
 *       would break the second run.</li>
 * </ul>
 *
 * <p>Accepting or rejecting a proposed offset is <em>not</em> this class's job: {@link SyncView}
 * already holds the {@code ManualSyncSession} and applies {@code applyVadOffset} directly (same
 * shape as its existing manual-anchor flow) — this class only ever reports numbers.
 */
public class AutoSyncController implements AutoSyncSession.Listener {

    private static final String TAG = "AutoSyncController";
    private static final long TERMINAL_FLASH_MS = 3000;

    // From Start keeps a generous window to survive long dialogue-free intros/credits, and searches
    // widely since a fixed 0s start has no prior on the true offset. From Here assumes the user
    // already heard dialogue at the current position — a smaller window is enough (faster extraction,
    // especially over HTTP: MediaExtractorAudioProvider seeks + decodes only the requested duration)
    // AND a much smaller search range is enough (the user is presumably already roughly in sync). The
    // two must NOT share maxOffsetSeconds: SubtitleResyncer's evidence gate (matchedEvents) scales
    // with maxOffsetSeconds — at 120s the bar is ~11 matched cues regardless of window size, which a
    // short window frequently can't supply even for a correct match. A smaller search range for From
    // Here lowers that bar to match what a small window can actually provide.
    //
    // 20s was the original From Here window, but it's too small on its own: tested against real audio
    // (Sintel), the *densest* possible 20s window anywhere in the file supplies at most 5 matched
    // cues — permanently below the matchedEvents bar (10 at maxOffsetSeconds=20), so From Here could
    // never succeed there regardless of where the probe landed. 40s was the measured threshold where
    // the same dense region clears the gate (matched=10). The bar barely moves between
    // maxOffsetSeconds=20 and =40 (evidence scales with log(candidateLags)), so the fix is a bigger
    // window at the *same* search range, not a wider search too — see LESSONS.md.
    //
    // Where to place the "From Start" probe(s) is now engine policy (subtitleengine.resync.
    // AutoSyncProbeLocator) instead of arithmetic inline here — see probeLocator below.
    private static final double FROM_START_ANALYSIS_SECONDS = 120.0;
    private static final double FROM_START_MAX_OFFSET_SECONDS = 120.0;
    private static final double FROM_HERE_ANALYSIS_SECONDS = 40.0;
    private static final double FROM_HERE_MAX_OFFSET_SECONDS = 20.0;
    private static final int BIN_MS = 100;

    // Optional second probe (AutoSyncSession only runs it if the first finds nothing — no extra cost
    // in the common case): a single bad sample shouldn't be the whole story. From Start's second probe
    // is the locator's job (see probeLocator). From Here's second probe jumps forward past its own
    // (small, 20s) window rather than to a fixed fraction — the user's chosen position is already the
    // best available sample, so the fallback should stay close to it, just far enough to see fresh
    // dialogue if the first 20s were sparse.
    private static final double FROM_HERE_SECOND_PROBE_JUMP_SECONDS = 60.0;

    private final Context context;
    private final SubtitlePanel panel;
    private final Handler mainHandler;
    private final SubsPill indicator;

    // From Start placement: density-based (subtitleengine.resync.DialogueDensityProbeLocator) with the
    // old blind-fraction heuristic (FractionalProbeLocator) as fallback for subtitles with no usable
    // dialogue cues to measure density from. Stateless — safe to share across runs.
    private final AutoSyncProbeLocator probeLocator = new DialogueDensityProbeLocator(new FractionalProbeLocator());

    // From Start evidence gate: discounts matchedEvents for sparse/continuous content (see
    // DialoguePacingEvidencePolicy's javadoc for the real-audio measurement behind this) — standard
    // TV/movie pacing, the common case, is left exactly as before. Stateless — safe to share across runs.
    private final AutoSyncEvidencePolicy evidencePolicy = new DialoguePacingEvidencePolicy();

    @Nullable private SubtitleResyncer resyncer; // lazy — ONNX init isn't free; kept alive until release()
    @Nullable private AutoSyncSession session;

    @Nullable private SubtitleFile subtitle;
    @Nullable private Uri mediaUri;
    @Nullable private AutoSyncProgress lastProgress;
    @Nullable private String indicatorTerminalText; // non-null while the post-run flash is live
    private long indicatorTerminalUntilMs;           // System.currentTimeMillis() deadline for the flash

    public AutoSyncController(Context context, SubtitlePanel panel, Handler mainHandler) {
        this.context = context.getApplicationContext();
        this.panel = panel;
        this.mainHandler = mainHandler;

        indicator = new SubsPill(context, R.drawable.subtitle_ic_sync,
                SubsTheme.STATUS_TRANSLATING, false);
    }

    /**
     * The top-left indicator: live phase progress while a run is active, then a 3s result flash when
     * it ends — mirrors {@link TranslationController#getIndicatorView()} (top-right), so both can be
     * visible at once without overlapping. Needed because {@link SyncView}'s own progress hint is only
     * visible while the panel is open on the Sync screen; a run keeps going after the panel closes.
     */
    public View getIndicatorView() {
        return indicator;
    }

    /** Called every tick — mirrors {@link TranslationController#renderIndicator}: hidden while the panel is open. */
    public void renderIndicator(boolean panelOpen) {
        if (indicatorTerminalText != null && System.currentTimeMillis() >= indicatorTerminalUntilMs) {
            indicatorTerminalText = null; // flash window elapsed
        }

        boolean showRunning = session != null && session.status() == AutoSyncSession.Status.RUNNING;
        boolean showTerminal = indicatorTerminalText != null;

        if (panelOpen || (!showRunning && !showTerminal)) {
            if (indicator.getVisibility() != View.GONE) indicator.setVisibility(View.GONE);
            return;
        }
        if (indicator.getVisibility() != View.VISIBLE) indicator.setVisibility(View.VISIBLE);

        if (showTerminal) {
            indicator.set(null, indicatorTerminalText, null);
            indicator.setIconTint(SubsTheme.INK_2);
            return;
        }
        indicator.setIconTint(SubsTheme.STATUS_TRANSLATING);
        float fraction = lastProgress != null
                ? (float) Math.max(0.0, Math.min(1.0, lastProgress.getFraction())) : 0f;
        indicator.set("SYNCING AUDIO", lastProgress != null ? formatRunning(lastProgress) : "Starting…",
                phaseCells(fraction));
    }

    /**
     * The phase's own progress as a strip of cells. Auto-sync has no chunks to colour, so the strip
     * simply fills left to right — same shape as the translation pill's, which is the point: both
     * say "something is running and this is how far it has got" without needing a legend.
     */
    private static int[] phaseCells(float fraction) {
        int n = 6;
        int[] cells = new int[n];
        int filled = Math.round(fraction * n);
        for (int i = 0; i < n; i++) {
            cells[i] = i < filled ? SubsTheme.STATUS_DONE
                    : (i == filled ? SubsTheme.STATUS_TRANSLATING : SubsTheme.STATUS_PENDING);
        }
        return cells;
    }

    /** Sets the 3s post-run flash text for DONE/ERROR; clears it for RUNNING/CANCELLED/IDLE. */
    private void updateIndicatorTerminalFlash(AutoSyncProgress p) {
        switch (p.getStatus()) {
            case ERROR:
                indicatorTerminalText = "✗ Auto-sync failed";
                indicatorTerminalUntilMs = System.currentTimeMillis() + TERMINAL_FLASH_MS;
                break;
            case DONE:
                indicatorTerminalText = p.getResult() != null
                        ? "✓ Auto-sync: shift proposed — review in Sync"
                        : "Auto-sync: no confident match";
                indicatorTerminalUntilMs = System.currentTimeMillis() + TERMINAL_FLASH_MS;
                break;
            default: // RUNNING, CANCELLED, IDLE — no flash (RUNNING keeps the live progress instead)
                indicatorTerminalText = null;
                break;
        }
    }

    // --- Callback entry points (from SubtitlePanel.Callbacks via CustomSubtitleController) ---

    /** Sets/replaces what to analyze. {@code null} subtitle/mediaUri when nothing is loaded/selected. */
    public void setSource(@Nullable SubtitleFile file, @Nullable Uri mediaUri) {
        this.subtitle = file;
        this.mediaUri = mediaUri;
        this.lastProgress = null;
        this.indicatorTerminalText = null;
        if (session != null) {
            session.setSource(mediaUri != null ? mediaUri.toString() : null, file);
        }
        pushState();
    }

    /**
     * From Start: extraction begins wherever {@link #probeLocator} places it — the subtitle's
     * densest dialogue region by default, with a generous window and search range (long
     * intros/credits, no prior on the true offset). If the first probe finds nothing, a second probe
     * from the locator's placement is tried. {@code durationMs <= 0} (unknown, e.g. not yet loaded)
     * falls back to 0s with no second probe — same as the locator's own unknown-duration behavior.
     */
    public void startFromBeginning(long durationMs) {
        if (durationMs > 0) {
            double durationSeconds = durationMs / 1000.0;
            ProbePlacement placement = probeLocator.locate(subtitle, durationSeconds, FROM_START_ANALYSIS_SECONDS);
            int slack = evidencePolicy.matchedEventsSlack(subtitle, durationSeconds);
            start(placement.startSeconds(), FROM_START_ANALYSIS_SECONDS,
                    FROM_START_MAX_OFFSET_SECONDS, placement.secondProbeStartSeconds(), slack);
        } else {
            start(0.0, FROM_START_ANALYSIS_SECONDS, FROM_START_MAX_OFFSET_SECONDS, null, 0);
        }
    }

    /**
     * From Here: extraction begins at the current playback position with a much smaller window and
     * search range (the user is presumably already roughly in sync). If the first probe finds
     * nothing, a second {@link #FROM_HERE_SECOND_PROBE_JUMP_SECONDS} further ahead is tried.
     */
    public void startFromHere(long positionMs) {
        double startSeconds = positionMs / 1000.0;
        start(startSeconds, FROM_HERE_ANALYSIS_SECONDS, FROM_HERE_MAX_OFFSET_SECONDS,
                startSeconds + FROM_HERE_SECOND_PROBE_JUMP_SECONDS);
    }

    private void start(double startSeconds, double analysisSeconds, double maxOffsetSeconds,
                        @Nullable Double secondProbeStartSeconds) {
        start(startSeconds, analysisSeconds, maxOffsetSeconds, secondProbeStartSeconds, 0);
    }

    private void start(double startSeconds, double analysisSeconds, double maxOffsetSeconds,
                        @Nullable Double secondProbeStartSeconds, int matchedEventsSlack) {
        if (!canStart()) return;
        indicatorTerminalText = null;
        try {
            ensureSession().start(startSeconds, analysisSeconds, maxOffsetSeconds, BIN_MS, secondProbeStartSeconds,
                    matchedEventsSlack);
        } catch (Throwable t) {
            // Most likely SileroVadEngine's ONNX init on first start() — never fail silently.
            Log.e(TAG, "start: failed to initialize auto-sync engine", t);
            lastProgress = null;
            panel.setAutoSyncState(AutoSyncUiState.terminal(
                    "Auto-sync failed: " + (t.getMessage() != null ? t.getMessage() : "engine init failed")));
            return;
        }
        pushState();
    }

    public void cancel() {
        if (session != null) session.cancel();
    }

    public void release() {
        if (session != null) session.cancel();
        if (resyncer != null) {
            try { resyncer.close(); } catch (Exception e) { Log.w(TAG, "release: resyncer close failed", e); }
        }
        resyncer = null;
        session = null;
    }

    // --- availability ---

    private boolean canStart() {
        return reasonUnavailable() == null;
    }

    @Nullable
    private String reasonUnavailable() {
        if (subtitle == null) return "Load an external subtitle first";
        if (mediaUri == null) return "No media source";
        String path = mediaUri.getPath();
        // MediaExtractor doesn't support HLS (SPEC.md §5.1) — never fail silently on this later.
        if (path != null && path.toLowerCase(Locale.ROOT).endsWith(".m3u8")) {
            return "Auto-sync unavailable for HLS streams";
        }
        return null;
    }

    private AutoSyncSession ensureSession() {
        if (session == null) {
            resyncer = new SubtitleResyncer(new SileroVadEngine(), new MediaExtractorAudioProvider(context, null));
            session = new AutoSyncSession(resyncer, mainHandler::post, backgroundPriorityThreadFactory(), this);
            session.setSource(mediaUri != null ? mediaUri.toString() : null, subtitle);
        }
        return session;
    }

    /** ~9k ONNX inferences at normal thread priority while video plays is a stutter risk on weak boxes. */
    private static ThreadFactory backgroundPriorityThreadFactory() {
        return r -> {
            Thread t = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "auto-sync-session");
            t.setDaemon(true);
            return t;
        };
    }

    // --- AutoSyncSession.Listener ---

    @Override
    public void onProgress(AutoSyncProgress progress) {
        lastProgress = progress;
        if (progress.getStatus() == AutoSyncSession.Status.ERROR) {
            Log.e(TAG, "auto-sync failed: " + progress.getErrorMessage(), progress.getError());
        }
        updateIndicatorTerminalFlash(progress);
        pushState();
    }

    // --- panel state push ---

    private void pushState() {
        panel.setAutoSyncState(buildUiState());
    }

    private AutoSyncUiState buildUiState() {
        String reason = reasonUnavailable();
        if (reason != null) return AutoSyncUiState.unavailable(reason);

        AutoSyncProgress p = lastProgress;
        if (p == null) return AutoSyncUiState.idle();

        switch (p.getStatus()) {
            case RUNNING:
                return AutoSyncUiState.running(formatRunning(p), runVerb(p), runFraction(p));
            case DONE:
                ResyncResult result = p.getResult();
                return result != null
                        ? AutoSyncUiState.confidentResult(result.getOffsetSeconds(), result.getUniqueness(), formatDone(result))
                        : AutoSyncUiState.terminal("No confident match");
            case CANCELLED:
                return AutoSyncUiState.terminal("Cancelled");
            case ERROR:
                String msg = p.getErrorMessage() != null ? p.getErrorMessage() : "unknown error";
                return AutoSyncUiState.terminal("Auto-sync failed: " + msg);
            case IDLE:
            default:
                return AutoSyncUiState.idle();
        }
    }

    private static String formatRunning(AutoSyncProgress p) {
        if (p.getPhase() == null) return "Starting…";
        return runVerb(p) + "… " + Math.round(runFraction(p) * 100) + "%";
    }

    /** The phase as a headline on its own — see {@link AutoSyncUiState#runTitle}. */
    private static String runVerb(AutoSyncProgress p) {
        if (p.getPhase() == null) return "Starting";
        switch (p.getPhase()) {
            case EXTRACTING: return "Extracting audio";
            case ANALYZING:  return "Analyzing speech";
            case MATCHING:   return "Matching subtitles";
            default:         return "Working";
        }
    }

    private static float runFraction(AutoSyncProgress p) {
        return (float) Math.max(0.0, Math.min(1.0, p.getFraction()));
    }

    private static String formatDone(ResyncResult result) {
        return String.format(Locale.US, "Proposed shift %+.1fs (uniqueness %.1f)",
                result.getOffsetSeconds(), result.getUniqueness());
    }
}
