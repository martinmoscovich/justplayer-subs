package com.brouken.player.subs;

import androidx.annotation.Nullable;

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
    /** RUNNING only: the phase as a headline ("Extracting audio"), with no percentage glued on —
     *  the screen shows the number as its own line and as a bar, so it needs them apart. */
    @Nullable public final String runTitle;
    /** RUNNING only: 0..1, negative when the phase can't say. */
    public final float runFraction;
    public final boolean hasConfidentResult;
    public final double offsetSeconds;
    public final double uniqueness;

    private AutoSyncUiState(boolean available, @Nullable String unavailableReason, boolean running,
                             String hint, boolean hasConfidentResult, double offsetSeconds, double uniqueness) {
        this(available, unavailableReason, running, hint, null, -1f, hasConfidentResult, offsetSeconds, uniqueness);
    }

    private AutoSyncUiState(boolean available, @Nullable String unavailableReason, boolean running,
                             String hint, @Nullable String runTitle, float runFraction,
                             boolean hasConfidentResult, double offsetSeconds, double uniqueness) {
        this.available = available;
        this.unavailableReason = unavailableReason;
        this.running = running;
        this.hint = hint;
        this.runTitle = runTitle;
        this.runFraction = runFraction;
        this.hasConfidentResult = hasConfidentResult;
        this.offsetSeconds = offsetSeconds;
        this.uniqueness = uniqueness;
    }

    static AutoSyncUiState unavailable(String reason) {
        return new AutoSyncUiState(false, reason, false, reason, false, 0, 0);
    }

    static AutoSyncUiState idle() {
        return new AutoSyncUiState(true, null, false, "", false, 0, 0);
    }

    static AutoSyncUiState running(String hint, String title, float fraction) {
        return new AutoSyncUiState(true, null, true, hint, title, fraction, false, 0, 0);
    }

    /** DONE with a confident result — {@link SyncView} shows Zone.REVIEW for this state. */
    static AutoSyncUiState confidentResult(double offsetSeconds, double uniqueness, String hint) {
        return new AutoSyncUiState(true, null, false, hint, true, offsetSeconds, uniqueness);
    }

    /** DONE with no confident match, CANCELLED, or ERROR — same shape as {@link #idle}, different hint. */
    static AutoSyncUiState terminal(String hint) {
        return new AutoSyncUiState(true, null, false, hint, false, 0, 0);
    }
}
