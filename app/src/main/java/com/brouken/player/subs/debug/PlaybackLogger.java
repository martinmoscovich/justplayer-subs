package com.brouken.player.subs.debug;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;

import java.util.Locale;

/**
 * Everything the player itself did during the session: which audio track is playing, and every
 * pause, resume, seek, stall, speed change and error.
 *
 * <p>This is what turns the rest of the log from a list of subtitle events into a reconstruction of
 * the session. Half of the other categories only mean something against the playhead — an auto-sync
 * "from here" is defined by where "here" was, a translation's priority pass starts at the current
 * position, and a "the subtitles drift after I skip ahead" report is unreadable without the seeks
 * that preceded it.
 *
 * <p>Audio-track selection lives here too, rather than in a listener of its own: it is a playback
 * fact, it arrives through the same callback, and it answers a question no other line can —
 * {@code Media3AudioProvider} extracts the <em>first</em> audio track in the container, not the
 * selected one (see its {@code AudioSink.endTracks}), so on a multi-audio file the gap between this
 * line and the {@code EXTRACT-AUDIO} one is the first thing to check when auto-sync lands on a
 * confident but wrong offset.
 */
public class PlaybackLogger implements Player.Listener {

    private final Player player;

    @Nullable private String lastTracks;
    @Nullable private Boolean lastPlaying;
    private int lastState = Player.STATE_IDLE;

    public PlaybackLogger(Player player) {
        this.player = player;
    }

    /** Forgets the dedup state so the next media reports its tracks and play state from scratch. */
    public void reset() {
        lastTracks = null;
        lastPlaying = null;
        lastState = Player.STATE_IDLE;
    }

    @Override
    public void onTracksChanged(@NonNull Tracks tracks) {
        if (!DebugLog.enabled()) {
            // Forget what was seen while off, so turning debug on mid-playback still records the
            // track on the next change instead of deduping it away against an unlogged value.
            lastTracks = null;
            return;
        }
        String current = describeSelectedAudio(tracks);
        if (current.equals(lastTracks)) return; // fires on every track change, text included
        lastTracks = current;
        DebugLog.log(DebugLog.CAT_AUDIO, current);
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (!DebugLog.enabled()) {
            lastPlaying = null;
            return;
        }
        if (lastPlaying != null && lastPlaying == isPlaying) return;
        lastPlaying = isPlaying;
        DebugLog.log(DebugLog.CAT_PLAYBACK, (isPlaying ? "playing" : "paused") + " at " + position());
    }

    /**
     * Buffering matters as much as pausing: a stall is indistinguishable from a slow translation in
     * every other category, and both look like "it froze" in a report.
     */
    @Override
    public void onPlaybackStateChanged(int state) {
        if (!DebugLog.enabled()) return;
        if (state == lastState) return;
        lastState = state;
        DebugLog.log(DebugLog.CAT_PLAYBACK, "state " + stateName(state) + " at " + position());
    }

    /**
     * Seeks only. Media3 reports every discontinuity here, including the automatic ones as playback
     * rolls between periods — those are not something the user did and would just be noise.
     */
    @Override
    public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                        @NonNull Player.PositionInfo newPosition, int reason) {
        if (!DebugLog.enabled()) return;
        if (reason != Player.DISCONTINUITY_REASON_SEEK
                && reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) return;
        DebugLog.log(DebugLog.CAT_PLAYBACK, "seek " + format(oldPosition.positionMs) + " -> "
                + format(newPosition.positionMs)
                + (reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT ? " (adjusted to a sync point)" : ""));
    }

    /** Speed changes the relationship between wall time and content time, which every ETA depends on. */
    @Override
    public void onPlaybackParametersChanged(@NonNull PlaybackParameters parameters) {
        if (!DebugLog.enabled()) return;
        DebugLog.log(DebugLog.CAT_PLAYBACK, String.format(Locale.US, "speed %.2fx", parameters.speed));
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException error) {
        DebugLog.log(DebugLog.CAT_PLAYBACK, "ERROR " + error.getErrorCodeName() + " — " + error);
    }

    private String position() {
        return format(player.getCurrentPosition());
    }

    private static String format(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d:%02d.%03d", totalSeconds / 3600,
                (totalSeconds % 3600) / 60, totalSeconds % 60, Math.max(0, ms) % 1000);
    }

    private static String stateName(int state) {
        switch (state) {
            case Player.STATE_IDLE: return "IDLE";
            case Player.STATE_BUFFERING: return "BUFFERING";
            case Player.STATE_READY: return "READY";
            case Player.STATE_ENDED: return "ENDED";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    private static String describeSelectedAudio(Tracks tracks) {
        int available = 0;
        Format selected = null;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            available++;
            if (group.isSelected() && selected == null) selected = group.getMediaTrackGroup().getFormat(0);
        }
        if (selected == null) {
            return available == 0 ? "no audio track" : "no audio track selected (of " + available + ")";
        }
        return String.format(Locale.US,
                "selected id=%s lang=%s label=%s codec=%s ch=%s rate=%s bitrate=%s (of %d)",
                selected.id, selected.language, selected.label, selected.sampleMimeType,
                value(selected.channelCount), value(selected.sampleRate), value(selected.bitrate), available);
    }

    /** Media3 reports "unknown" as {@link Format#NO_VALUE}, which prints as a confusing -1. */
    private static String value(int v) {
        return v == Format.NO_VALUE ? "?" : String.valueOf(v);
    }
}
