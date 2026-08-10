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

import java.util.Locale;
import java.util.concurrent.ThreadFactory;

import subtitleengine.core.model.SubtitleFile;
import subtitleengine.resync.AutoSyncProgress;
import subtitleengine.resync.AutoSyncSession;
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
    // already heard dialogue at the current position — a much smaller window is enough (faster
    // extraction, especially over HTTP: MediaExtractorAudioProvider seeks + decodes only the
    // requested duration) AND a much smaller search range is enough (the user is presumably already
    // roughly in sync). The two must NOT share maxOffsetSeconds: SubtitleResyncer's evidence gate
    // (matchedEvents) scales with maxOffsetSeconds — at 120s the bar is ~11 matched cues regardless
    // of window size, which a 20s window frequently can't supply even for a correct match (confirmed
    // against real audio, see LESSONS.md). A smaller search range for From Here lowers that bar to
    // match what a small window can actually provide.
    // Starting "From Start" at a literal 0s is often the worst sampling position: many episodes open
    // with a recap or cold intro the subtitle file doesn't cover, so the window can contain zero
    // matchable cues for minutes. Starting at a fraction of the media duration instead lands inside
    // the body of the content far more often. 0.0 reproduces the old literal-0s behavior exactly.
    private static final double AUTO_START_FRACTION = 0.3;
    private static final double FROM_START_ANALYSIS_SECONDS = 120.0;
    private static final double FROM_START_MAX_OFFSET_SECONDS = 120.0;
    private static final double FROM_HERE_ANALYSIS_SECONDS = 20.0;
    private static final double FROM_HERE_MAX_OFFSET_SECONDS = 20.0;
    private static final int BIN_MS = 100;

    private final Context context;
    private final SubtitlePanel panel;
    private final Handler mainHandler;
    private final TextView indicator;

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

        String text = showTerminal
                ? indicatorTerminalText
                : "Auto-sync: " + (lastProgress != null ? formatRunning(lastProgress) : "Starting…");
        if (!text.contentEquals(indicator.getText())) indicator.setText(text);
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
     * From Start: extraction begins at {@link #AUTO_START_FRACTION} of the media duration (0.0 =
     * literal file start), with a generous window and search range — long intros/credits, no prior
     * on the true offset. {@code durationMs <= 0} (unknown, e.g. not yet loaded) falls back to 0s.
     */
    public void startFromBeginning(long durationMs) {
        double startSeconds = durationMs > 0 ? (durationMs / 1000.0) * AUTO_START_FRACTION : 0.0;
        start(startSeconds, FROM_START_ANALYSIS_SECONDS, FROM_START_MAX_OFFSET_SECONDS);
    }

    /** From Here: extraction begins at the current playback position with a much smaller window and
     *  search range (the user is presumably already roughly in sync). */
    public void startFromHere(long positionMs) {
        start(positionMs / 1000.0, FROM_HERE_ANALYSIS_SECONDS, FROM_HERE_MAX_OFFSET_SECONDS);
    }

    private void start(double startSeconds, double analysisSeconds, double maxOffsetSeconds) {
        if (!canStart()) return;
        indicatorTerminalText = null;
        try {
            ensureSession().start(startSeconds, analysisSeconds, maxOffsetSeconds, BIN_MS);
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
                return AutoSyncUiState.running(formatRunning(p));
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
        int pct = (int) Math.round(Math.max(0.0, Math.min(1.0, p.getFraction())) * 100);
        String verb;
        switch (p.getPhase()) {
            case EXTRACTING: verb = "Extracting audio"; break;
            case ANALYZING:  verb = "Analyzing speech"; break;
            case MATCHING:   verb = "Matching subtitles"; break;
            default:         verb = "Working"; break;
        }
        return verb + "… " + pct + "%";
    }

    private static String formatDone(ResyncResult result) {
        return String.format(Locale.US, "Proposed shift %+.1fs (uniqueness %.1f)",
                result.getOffsetSeconds(), result.getUniqueness());
    }
}
