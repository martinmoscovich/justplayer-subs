package com.brouken.player.skip;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.brouken.player.R;

import java.util.Locale;

/**
 * A skippable stretch of the media (intro, recap, ending) as told to us by the launching app.
 *
 * <p>Times are stored in milliseconds regardless of the unit the sender used.
 */
public final class SkipSegment {

    /** What the segment is, which decides the button label. */
    public enum Kind {
        INTRO(R.string.skip_intro),
        RECAP(R.string.skip_recap),
        ENDING(R.string.skip_ending),
        /** Recognised as a segment, but of a type we have no specific label for. */
        OTHER(R.string.skip_generic);

        @StringRes
        private final int label;

        Kind(@StringRes int label) {
            this.label = label;
        }

        @StringRes
        public int getLabel() {
            return label;
        }
    }

    private final Kind kind;
    private final long startMs;
    private final long endMs;

    public SkipSegment(@NonNull Kind kind, long startMs, long endMs) {
        this.kind = kind;
        this.startMs = startMs;
        this.endMs = endMs;
    }

    @NonNull
    public Kind getKind() {
        return kind;
    }

    public long getStartMs() {
        return startMs;
    }

    public long getEndMs() {
        return endMs;
    }

    public boolean contains(long positionMs) {
        return positionMs >= startMs && positionMs < endMs;
    }

    /**
     * Maps a sender's segment type to a {@link Kind}. Covers the vocabularies of the skip
     * databases in circulation (IntroDB, AniSkip, Anime-Skip); anything unknown is {@link
     * Kind#OTHER} rather than dropped, so an unrecognised type still gets a skip button.
     */
    @NonNull
    public static Kind kindOf(String type) {
        if (type == null) {
            return Kind.OTHER;
        }
        switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "intro":
            case "op":
            case "opening":
            case "mixed-op":
                return Kind.INTRO;
            case "recap":
                return Kind.RECAP;
            case "ed":
            case "ending":
            case "mixed-ed":
            case "outro":
            case "credits":
                return Kind.ENDING;
            default:
                return Kind.OTHER;
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "SkipSegment{" + kind + " " + startMs + "-" + endMs + "ms}";
    }
}
