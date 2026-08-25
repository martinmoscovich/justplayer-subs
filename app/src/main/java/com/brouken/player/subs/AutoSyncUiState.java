package com.brouken.player.subs;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * Formatted snapshot of an auto-sync run pushed to {@link SyncView} via
 * {@link SubtitlePanel#setAutoSyncState}. Every field is precomputed by {@link AutoSyncController};
 * this class carries no logic of its own (mirrors how {@link TranslationController} pushes plain
 * strings/{@link ButtonState} into {@code TranslateView} — bundled into one object here because
 * {@link SyncView}'s REVIEW zone needs the numeric offset/uniqueness too, not just text).
 */
public final class AutoSyncUiState {
    public final boolean available;
    @Nullable public final String unavailableReason;
    public final boolean running;
    public final String hint;
    /**
     * RUNNING only: one row per sampled window, in the order they are tried. A run works on several
     * windows at once — all of them download together, and the first one's analysis overlaps the rest
     * — so a single title and bar cannot describe it: collapsing them made the number jump backwards
     * and read as sequential work. Empty when nothing is running.
     */
    public final List<ProbeRow> probes;
    public final boolean hasConfidentResult;

    /** One sampled window's own progress, already formatted for the screen. */
    public static final class ProbeRow {
        /** "12:14" — where in the video this window listens. Deliberately not an ordinal: the engine
         *  tries the windows by dialogue density, not in time order, so a number here would read as a
         *  sequence and contradict the timestamps next to it. */
        public final String label;
        /** "Extracting audio" / "Analyzing speech" / "Matching subtitles", or why it isn't working. */
        public final String title;
        /** 0..1, negative when there is no number to show (queued, skipped). */
        public final float fraction;
        /** Dimmed when this window is no longer working — finished, or never needed. */
        public final boolean active;

        ProbeRow(String label, String title, float fraction, boolean active) {
            this.label = label;
            this.title = title;
            this.fraction = fraction;
            this.active = active;
        }
    }
    public final double offsetSeconds;
    /**
     * Time-scale factor the run proposes, 1.0 when the subtitle only needs shifting. Anything else
     * means the two releases run at different speeds (a PAL speed-up is 1.0427), so the correction is
     * not a constant and {@link #offsetSeconds} alone is only true at the head of the file — the two
     * travel together or neither means anything.
     */
    public final double scale;
    public final double uniqueness;

    /** Whether the proposal stretches the subtitle rather than only shifting it. */
    public boolean hasScale() {
        return Math.abs(scale - 1.0) > 1e-9;
    }
    /**
     * Whether the run ended in a way that owes the user a modal. True for a proposal <em>and</em> for
     * "nothing found" / "it failed": the user waited through the whole run either way, and an outcome
     * that shows up only as a small hint at the edge of a screen is an outcome they can miss. False
     * for a cancellation — they already know, they did it.
     */
    public final boolean showResultModal;

    private AutoSyncUiState(boolean available, @Nullable String unavailableReason, boolean running,
                             String hint, boolean hasConfidentResult, double offsetSeconds, double scale,
                             double uniqueness, boolean showResultModal) {
        this(available, unavailableReason, running, hint, List.of(), hasConfidentResult, offsetSeconds,
                scale, uniqueness, showResultModal);
    }

    private AutoSyncUiState(boolean available, @Nullable String unavailableReason, boolean running,
                             String hint, List<ProbeRow> probes,
                             boolean hasConfidentResult, double offsetSeconds, double scale,
                             double uniqueness, boolean showResultModal) {
        this.available = available;
        this.unavailableReason = unavailableReason;
        this.running = running;
        this.hint = hint;
        this.probes = probes;
        this.hasConfidentResult = hasConfidentResult;
        this.offsetSeconds = offsetSeconds;
        this.scale = scale;
        this.uniqueness = uniqueness;
        this.showResultModal = showResultModal;
    }

    static AutoSyncUiState unavailable(String reason) {
        return new AutoSyncUiState(false, reason, false, reason, false, 0, 1, 0, false);
    }

    static AutoSyncUiState idle() {
        return new AutoSyncUiState(true, null, false, "", false, 0, 1, 0, false);
    }

    static AutoSyncUiState running(String hint, List<ProbeRow> probes) {
        return new AutoSyncUiState(true, null, true, hint, probes, false, 0, 1, 0, false);
    }

    static ProbeRow probeRow(String label, String title, float fraction, boolean active) {
        return new ProbeRow(label, title, fraction, active);
    }

    /** DONE with a confident result — {@link SyncView} shows Zone.REVIEW for this state. */
    static AutoSyncUiState confidentResult(double offsetSeconds, double scale, double uniqueness, String hint) {
        return new AutoSyncUiState(true, null, false, hint, true, offsetSeconds, scale, uniqueness, true);
    }

    /** A run that ended with nothing to propose (no match, or an error) — same shape as {@link #idle}
     *  with a hint, plus the modal that says so. See {@link #showResultModal}. */
    static AutoSyncUiState finishedEmpty(String hint) {
        return new AutoSyncUiState(true, null, false, hint, false, 0, 1, 0, true);
    }

    /** Cancelled: a hint, no modal — the user did it and does not need telling. */
    static AutoSyncUiState terminal(String hint) {
        return new AutoSyncUiState(true, null, false, hint, false, 0, 1, 0, false);
    }
}
