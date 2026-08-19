package com.brouken.player.subs;

/**
 * Which button row (and status tone) {@link TranslateView} should show, driven by
 * {@link TranslationController}. The four {@code FINISHED_*} variants all render the same row
 * ("Done" · "Restore original", Done focused) — they only differ in the status line's color.
 */
public enum ButtonState {
    IDLE,               // "Translate" · "Done" — Translate focused
    RUNNING,            // "Pause" · "Cancel" · "Done" — Pause focused
    PAUSED,             // "Resume" · "Cancel" · "Done" — Resume focused; run frozen, nothing lost
    FINISHED_OK,        // "Done" · "Restore original" — Done focused, normal status color
    FINISHED_WARNING,   // same row — partial result, warning status color
    FINISHED_ERROR,     // same row — run failed outright, error status color
    FINISHED_CANCELLED, // same row — user cancelled, dim status color
    UNAVAILABLE         // reason text · "Done" — Done focused
}
