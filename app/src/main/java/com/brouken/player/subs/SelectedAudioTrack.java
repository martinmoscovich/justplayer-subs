package com.brouken.player.subs;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;

/**
 * Which audio track the user is actually listening to, so audio extraction can analyze that one
 * instead of whichever comes first in the container.
 *
 * <p>Why this exists: {@link Media3AudioProvider} runs its own extractor, independent of playback,
 * and used to capture the first audio track it found. On a single-audio file that is the same track;
 * on a release with a dub it is not. Found live on a {@code Vikings} release with four audio tracks,
 * where playback was on English and extraction correlated the subtitles against the Russian dub. The
 * failure is silent by construction — the VAD accepted with high uniqueness because that particular
 * dub happens to be time-aligned — so a lip-synced dub would instead produce a confident, wrong
 * offset that nothing in the result could distinguish from a good one.
 *
 * <p>Matching is by track id, with the language as a fallback. Both are needed because the two sides
 * name the same track differently: the player exposes ids as {@code "<sourceIndex>:<trackId>"} (e.g.
 * {@code "0:2"}) while a standalone extractor reading the same container reports the bare
 * {@code "2"} — the same prefixing {@code SubtitleSelectionController.EXTERNAL_TRACK_ID_PREFIX}
 * already has to account for on the subtitle side.
 */
public final class SelectedAudioTrack {

    @Nullable public final String id;
    @Nullable public final String language;

    private SelectedAudioTrack(@Nullable String id, @Nullable String language) {
        this.id = id;
        this.language = language;
    }

    /** Nothing known — extraction falls back to the first audio track, as it always did. */
    public static final SelectedAudioTrack UNKNOWN = new SelectedAudioTrack(null, null);

    /** Reads the currently selected audio track, or {@link #UNKNOWN} when there is none. */
    public static SelectedAudioTrack from(@Nullable Player player) {
        if (player == null) return UNKNOWN;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO || !group.isSelected()) continue;
            Format format = group.getMediaTrackGroup().getFormat(0);
            return new SelectedAudioTrack(format.id, format.language);
        }
        return UNKNOWN;
    }

    public boolean isKnown() {
        return id != null || language != null;
    }

    /**
     * Whether an extractor-side track is the one being listened to. Kept pure over strings (no
     * {@code Format}, no player) so the id-shape rule above is unit-testable off-device — it is the
     * part most likely to be wrong, and the consequence of it being wrong is invisible.
     *
     * <p>Id wins when both sides have one; language is only consulted when the ids cannot be compared
     * at all. A language match is weaker evidence (two tracks can share a language — a stereo and a
     * 5.1 mix of the same dub) but still far better than position in the container.
     */
    public boolean matches(@Nullable String candidateId, @Nullable String candidateLanguage) {
        if (id != null && candidateId != null) return sameTrackId(id, candidateId);
        return language != null && language.equals(candidateLanguage);
    }

    /** Equal ignoring the {@code "<sourceIndex>:"} prefix the player adds — see the class javadoc. */
    static boolean sameTrackId(String a, String b) {
        return bareId(a).equals(bareId(b));
    }

    private static String bareId(String id) {
        int colon = id.lastIndexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }
}
