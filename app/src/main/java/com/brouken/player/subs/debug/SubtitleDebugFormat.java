package com.brouken.player.subs.debug;

import androidx.annotation.Nullable;

import com.brouken.player.subs.SubtitleOption;

import java.util.List;
import java.util.Locale;

/**
 * How a {@link SubtitleOption} is written into the debug log. Kept here, and not inline in the three
 * controllers that log options, so "which subtitle" always reads the same way in the file — a list
 * line and a selection line that describe the same option must be comparable at a glance.
 *
 * <p>What identifies an option depends on where it came from, which is exactly what a report needs:
 * a URL for an external one, the track index for an embedded one, the provider's file id for a
 * search result.
 */
public final class SubtitleDebugFormat {

    private SubtitleDebugFormat() {
    }

    /** The whole selector, as one line per option would be too noisy for a list that gets rebuilt often. */
    public static String options(List<SubtitleOption> options, @Nullable String selectedId) {
        StringBuilder sb = new StringBuilder("options(").append(options.size()).append(")");
        for (SubtitleOption o : options) {
            sb.append("\n    ").append(o.id.equals(selectedId) ? "* " : "  ").append(option(o));
        }
        return sb.toString();
    }

    /** One option: what it is, where it comes from, and what state it is in. */
    public static String option(SubtitleOption o) {
        StringBuilder sb = new StringBuilder();
        sb.append(o.id).append(' ').append(o.source).append(" lang=").append(o.language)
                .append(" label='").append(o.label).append('\'');
        sb.append(' ').append(origin(o));
        if (o.state != SubtitleOption.State.READY) sb.append(" state=").append(o.state);
        if (o.source == SubtitleOption.Source.EMBEDDED) sb.append(" track=").append(o.trackState);
        if (o.imageFormat) sb.append(" image");
        if (o.format != null) sb.append(" format=").append(o.format);
        if (o.matchStrategy != null) sb.append(" match=").append(o.matchStrategy);
        if (o.rating > 0 || o.downloadCount > 0) {
            sb.append(String.format(Locale.US, " rating=%.1f downloads=%d", o.rating, o.downloadCount));
        }
        if (o.extracted) sb.append(" [extracted]");
        if (o.translated) sb.append(" [translated]");
        if (o.synced) sb.append(" [synced]");
        if (o.fromCache) sb.append(" [fromCache]");
        return sb.toString();
    }

    /**
     * The identifying half on its own — the URL for external, the index for embedded, the provider
     * reference for a search result. This is the "which subtitle exactly" a report is built on.
     */
    public static String origin(SubtitleOption o) {
        switch (o.source) {
            case EXTERNAL:
                return "uri=" + o.uri;
            case EMBEDDED:
                return "embeddedIndex=" + o.embeddedTextIndex;
            case PROVIDER:
            default:
                return "providerRef=" + o.providerRef;
        }
    }
}
