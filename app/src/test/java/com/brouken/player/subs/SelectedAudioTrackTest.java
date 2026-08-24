package com.brouken.player.subs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The id-shape rule is the part of audio-track matching most likely to be wrong, and the part whose
 * being wrong is invisible: a mismatch silently falls back to the first audio track and produces a
 * confident, wrong offset. Tested off-device, over plain strings.
 */
class SelectedAudioTrackTest {

    @Test
    void matchesAcrossThePlayersSourceIndexPrefix() {
        // Observed live: the player reports "0:2" for the very track a standalone extractor reading
        // the same container calls "2".
        assertTrue(SelectedAudioTrack.sameTrackId("0:2", "2"));
        assertTrue(SelectedAudioTrack.sameTrackId("2", "0:2"));
        assertTrue(SelectedAudioTrack.sameTrackId("1:5", "0:5"));
    }

    @Test
    void doesNotMatchDifferentTracks() {
        assertFalse(SelectedAudioTrack.sameTrackId("0:2", "5"));
        assertFalse(SelectedAudioTrack.sameTrackId("0:2", "0:5"));
        // A prefix of another id is not the same track — "12" must not match "2".
        assertFalse(SelectedAudioTrack.sameTrackId("0:12", "2"));
    }

    @Test
    void unknownMatchesNothingSoExtractionFallsBack() {
        assertFalse(SelectedAudioTrack.UNKNOWN.isKnown());
        assertFalse(SelectedAudioTrack.UNKNOWN.matches("2", "en"));
    }
}
