package com.brouken.player.subs;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the Translate screen draws, already formatted. Mirrors {@link AutoSyncUiState}'s role
 * for Sync: {@link TranslationController} owns the arithmetic and the wording, {@link TranslateView}
 * owns nothing but the pixels.
 *
 * <p>It is a wide object because the screen genuinely says several unrelated things at once — how far
 * you can watch, how far the run has got, what it has cost, what is in flight — and the alternative,
 * handing the view a {@code TranslationProgress} and letting it work them out, is exactly the split
 * this project keeps insisting on not making.
 */
public final class TranslateUiState {

    /** Which shape the screen takes. Drives what is drawn, not what the buttons are. */
    public enum Mode { UNAVAILABLE, IDLE, RUNNING, PAUSED, FINISHED }

    /** Colour of a finished run's headline and its ring. */
    public enum Tone { OK, WARN, ERROR, MUTED }

    /** A big-figure panel: a caption, the number, and an optional line under it. */
    public static final class Panel {
        public final String label;
        public final String figure;
        @Nullable public final String sub;
        /** Cyan figure — reserved for the one number that is about the video, not about the run. */
        public final boolean accent;

        public Panel(String label, String figure, @Nullable String sub, boolean accent) {
            this.label = label;
            this.figure = figure;
            this.sub = sub;
            this.accent = accent;
        }
    }

    /** A key to a colour that is actually present in the bar right now. Never names a chunk. */
    public static final class Legend {
        public final int color;
        public final String text;
        public final boolean hatched;

        public Legend(int color, String text, boolean hatched) {
            this.color = color;
            this.text = text;
            this.hatched = hatched;
        }
    }

    /** One chunk that is in flight or retrying. Replaced as chunks close; never accumulated. */
    public static final class Pill {
        public final int color;
        public final String text;
        @Nullable public final String suffix;

        public Pill(int color, String text, @Nullable String suffix) {
            this.color = color;
            this.text = text;
            this.suffix = suffix;
        }
    }

    public final Mode mode;
    public final ButtonState buttons;
    /** Centred-block headline + subline, for IDLE and UNAVAILABLE. */
    @Nullable public final String blockTitle;
    @Nullable public final String blockSub;
    /** Result headline + detail, for FINISHED. */
    @Nullable public final String resultTitle;
    @Nullable public final String resultDetail;
    public final Tone tone;
    public final List<Panel> panels;
    public final List<Legend> legend;
    public final List<Pill> pills;
    public final boolean showBar;
    /** Whether this result has holes a retry could fill — the only thing that offers that button. */
    public final boolean canRetryMissing;
    /**
     * Why the most recent chunk failed, in plain language, or {@code null} when nothing has. Shown
     * under the bar and the chunk capsules: those can say <em>that</em> a chunk went red, never why,
     * and a red segment with no reason is not actionable. The last one is enough — it is the one the
     * user can still do something about.
     */
    @Nullable public final String errorNote;

    private TranslateUiState(Builder b) {
        this.mode = b.mode;
        this.buttons = b.buttons;
        this.blockTitle = b.blockTitle;
        this.blockSub = b.blockSub;
        this.resultTitle = b.resultTitle;
        this.resultDetail = b.resultDetail;
        this.tone = b.tone;
        this.panels = List.copyOf(b.panels);
        this.legend = List.copyOf(b.legend);
        this.pills = List.copyOf(b.pills);
        this.showBar = b.showBar;
        this.canRetryMissing = b.canRetryMissing;
        this.errorNote = b.errorNote;
    }

    public static Builder of(Mode mode, ButtonState buttons) {
        return new Builder(mode, buttons);
    }

    public static final class Builder {
        private final Mode mode;
        private final ButtonState buttons;
        private String blockTitle, blockSub, resultTitle, resultDetail;
        private Tone tone = Tone.OK;
        private final List<Panel> panels = new ArrayList<>();
        private final List<Legend> legend = new ArrayList<>();
        private final List<Pill> pills = new ArrayList<>();
        private boolean showBar;
        private boolean canRetryMissing;
        @Nullable private String errorNote;

        private Builder(Mode mode, ButtonState buttons) {
            this.mode = mode;
            this.buttons = buttons;
        }

        public Builder block(String title, @Nullable String sub) {
            this.blockTitle = title;
            this.blockSub = sub;
            return this;
        }

        public Builder result(Tone tone, String title, @Nullable String detail) {
            this.tone = tone;
            this.resultTitle = title;
            this.resultDetail = detail;
            return this;
        }

        public Builder panel(String label, String figure, @Nullable String sub, boolean accent) {
            panels.add(new Panel(label, figure, sub, accent));
            return this;
        }

        public Builder legend(int color, String text, boolean hatched) {
            legend.add(new Legend(color, text, hatched));
            return this;
        }

        public Builder pill(int color, String text, @Nullable String suffix) {
            pills.add(new Pill(color, text, suffix));
            return this;
        }

        public Builder bar(boolean show) {
            this.showBar = show;
            return this;
        }

        public Builder errorNote(@Nullable String note) {
            this.errorNote = (note == null || note.isEmpty()) ? null : note;
            return this;
        }

        public Builder retryMissing(boolean can) {
            this.canRetryMissing = can;
            return this;
        }

        public TranslateUiState build() {
            return new TranslateUiState(this);
        }
    }
}
