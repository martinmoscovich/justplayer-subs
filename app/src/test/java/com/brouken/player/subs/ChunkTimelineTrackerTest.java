package com.brouken.player.subs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import subtitleengine.core.model.SubtitleEntry;
import subtitleengine.core.model.SubtitleFile;
import subtitleengine.pipeline.SubtitlePipelineSession;
import subtitleengine.translation.ChunkProgress;
import subtitleengine.translation.ChunkingConfig;
import subtitleengine.translation.CompletionResult;
import subtitleengine.translation.RunStatus;
import subtitleengine.translation.SubtitleChunker;
import subtitleengine.translation.SubtitleTranslator;
import subtitleengine.translation.TranslationClient;
import subtitleengine.translation.TranslationProgress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChunkTimelineTracker} exercised directly against hand-built engine events — no live
 * translation. {@code session} is never {@link SubtitlePipelineSession#start started}: every scenario
 * below either sets {@code status} to {@link RunStatus#DONE} directly (checked before
 * {@code session.isRangeReady} in {@code buildSegment}) or doesn't need DONE detection at all, so a
 * never-started session's default {@code isRangeReady() == false} (nothing merged into {@code current}
 * yet) is exactly what every scenario needs — isolating this class's own boundary/identity logic from
 * the engine's chunking/translation runtime, which has its own test suite. {@link #active} carries a
 * chunk's real timing directly (mirroring what the engine now reads off the real
 * {@code SubtitleChunk} at dispatch time — see {@code ChunkProgress}'s javadoc), so no test needs to
 * fake a growing {@link SubtitleFile} to make a chunk's range discoverable.
 */
class ChunkTimelineTrackerTest {

    /**
     * For the streaming/estimate-focused tests, which never invoke the real chunker — only
     * {@code chunkingConfig.estimateBoundariesMs} matters, driven by the two target fields. Kept
     * distinct from {@link #oneEntryPerChunkConfig()} because the two hard-cap fields that force a
     * real one-entry-per-chunk split there (see its javadoc) are irrelevant here.
     */
    private static ChunkingConfig config(long urgentTargetMs, long targetMs) {
        return ChunkingConfig.builder()
                .urgentChunkTargetMs(urgentTargetMs).urgentChunkMaxMs(Long.MAX_VALUE).urgentChunkMinMs(0)
                .targetChunkMs(targetMs).maxChunkMs(Long.MAX_VALUE).minChunkMs(0)
                .overlapMs(0)
                .build();
    }

    /**
     * For the in-memory (exact) tests, which chunk real entries via {@link SubtitleChunker}: the hard
     * {@code maxEntriesPerChunk=1} cap (both urgent and normal) forces exactly one entry per chunk
     * regardless of duration, and {@code minEntriesPerChunk=0}/{@code minChunkMs=0} keep the
     * count-based floor from blocking that cut — the same pattern
     * {@code SubtitlePipelineSessionTest.fixedCountConfig} already uses in the engine. Target durations
     * are a small nonzero value (not 0): {@code estimateBoundariesMs} can still fire even for an exact
     * run when the real content falls short of the zone's true end (see {@code buildBoundariesFor}'s
     * clamp), and a {@code 0} target there would loop forever instead of terminating.
     */
    private static ChunkingConfig oneEntryPerChunkConfig() {
        return ChunkingConfig.builder()
                .maxEntriesPerChunk(1).urgentChunkMaxEntries(1)
                .minEntriesPerChunk(0).minTokens(0)
                .minChunkMs(0).urgentChunkMinMs(0)
                .targetChunkMs(500L).urgentChunkTargetMs(500L)
                .maxChunkMs(Long.MAX_VALUE).urgentChunkMaxMs(Long.MAX_VALUE)
                .maxTokens(Integer.MAX_VALUE)
                .overlapMs(0)
                .build();
    }

    /** A session that is never started — see the class javadoc for why that's sufficient here. */
    private static SubtitlePipelineSession neverStartedSession(ChunkingConfig config) {
        TranslationClient client = new TranslationClient() {
            @Override public String getName() { return "fake"; }
            @Override public String getModelId() { return "fake"; }
            @Override public CompletionResult complete(String systemPrompt, String userPrompt) {
                throw new AssertionError("should never be called — session is never started");
            }
        };
        SubtitleTranslator translator = new SubtitleTranslator(client, new SubtitleChunker(), 1, 0);
        Executor sameThread = Runnable::run;
        SubtitlePipelineSession.Listener noopListener = new SubtitlePipelineSession.Listener() {
            @Override public void onSubtitleUpdated(SubtitleFile current) { }
            @Override public void onProgress(TranslationProgress progress) { }
        };
        return new SubtitlePipelineSession(translator, config, 1, sameThread, noopListener);
    }

    private static SubtitleEntry entry(int index, long startMs, long endMs) {
        return new SubtitleEntry(index, startMs, endMs, List.of("line " + index));
    }

    private static List<SubtitleEntry> entries(int count, long stepMs) {
        List<SubtitleEntry> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(entry(i, i * stepMs, i * stepMs + stepMs - 200));
        return out;
    }

    private static ChunkProgress active(int index, int firstEntry, int lastEntry, long startMs, long endMs) {
        return new ChunkProgress(index, -1, firstEntry, lastEntry, 0, startMs, endMs);
    }

    private static TranslationProgress running(List<ChunkProgress> active, boolean lastChunkFailed) {
        return new TranslationProgress(RunStatus.RUNNING, active.size(), 0, active.size(), 0, 0, 0L,
                active, null, null, null, false, null, 1.0, -1L, lastChunkFailed, Map.of(), -1, null,
                0L, -1.0);
    }

    private static TranslationProgress streaming(List<ChunkProgress> active, long extractedContentMs) {
        return new TranslationProgress(RunStatus.RUNNING, active.size(), 0, active.size(), 0, 0, 0L,
                active, null, null, null, true, TranslationProgress.SourceState.READY, 0.5, -1L, false,
                Map.of(), -1, null, extractedContentMs, 1.0);
    }

    /** Same as {@link #streaming}, but with {@code extractionSpeedFactor} at its real pre-first-chunk
     *  value ({@code -1}, "not measured yet") instead of a fixed {@code 1.0} — the case that matters for
     *  whether the EXTRACTING segment's label degrades to a plain percentage instead of disappearing. */
    private static TranslationProgress streamingBeforeAnyChunkStarted(long extractedContentMs) {
        return new TranslationProgress(RunStatus.RUNNING, 0, 0, 0, 0, 0, 0L,
                List.of(), null, null, null, true, TranslationProgress.SourceState.WAITING, 0.5, -1L, false,
                Map.of(), -1, null, extractedContentMs, -1.0);
    }

    private static TranslationProgress done() {
        return new TranslationProgress(RunStatus.DONE, 1, 1, 1, 0, 0, 0L, List.of(), null, null, null,
                false, null, 1.0, -1L, false, Map.of(), -1, null, 0L, -1.0);
    }

    // -------------------------------------------------------------------------------------------

    @Test
    void inMemoryFromStart_isExactImmediately_andTracksStateTransitions() {
        ChunkingConfig config = oneEntryPerChunkConfig();
        List<SubtitleEntry> all = entries(3, 2_000L); // 3 chunks of 1 entry each: [0,1800) [2000,3800) [4000,5800)
        ChunkTimelineTracker tracker = new ChunkTimelineTracker(config);
        SubtitlePipelineSession session = neverStartedSession(config);

        tracker.reset(6_000L, 0L, all);

        // Nothing active yet: the two not-yet-translating chunks are CLOSED (known, waiting their
        // turn), never PENDING — the whole point of the state existing.
        ChunkProgressBarView.Model idle = tracker.buildModel(running(List.of(), false), session, 0L);
        assertEquals(3, idle.segments.size());
        for (ChunkProgressBarView.Segment seg : idle.segments) {
            assertFalse(seg.endEstimated, "in-memory boundaries are exact from the first frame");
            assertEquals(ChunkProgressBarView.SegmentState.CLOSED, seg.state);
        }

        // Chunk 0 starts translating.
        tracker.onProgress(running(List.of(active(0, 0, 0, 0L, 1_800L)), false));
        ChunkProgressBarView.Model mid = tracker.buildModel(
                running(List.of(active(0, 0, 0, 0L, 1_800L)), false), session, 0L);
        assertEquals(ChunkProgressBarView.SegmentState.TRANSLATING, mid.segments.get(0).state);
        assertEquals(ChunkProgressBarView.SegmentState.CLOSED, mid.segments.get(1).state);

        // Run finishes: everything settles to DONE regardless of per-chunk bookkeeping.
        ChunkProgressBarView.Model finished = tracker.buildModel(done(), session, 6_000L);
        for (ChunkProgressBarView.Segment seg : finished.segments) {
            assertEquals(ChunkProgressBarView.SegmentState.DONE, seg.state);
        }
    }

    @Test
    void inMemoryPositionPriority_bothZonesExact_splitAtStartAtMs() {
        ChunkingConfig config = oneEntryPerChunkConfig();
        List<SubtitleEntry> all = entries(6, 1_000L); // entries 0..5, 1000ms apart, 800ms long each
        long startAtMs = 3_000L; // splits after entry index 2 (starts at 2000 < 3000)
        ChunkTimelineTracker tracker = new ChunkTimelineTracker(config);
        SubtitlePipelineSession session = neverStartedSession(config);

        tracker.reset(6_000L, startAtMs, all);

        ChunkProgressBarView.Model model = tracker.buildModel(running(List.of(), false), session, startAtMs);
        for (ChunkProgressBarView.Segment seg : model.segments) {
            assertFalse(seg.endEstimated, "in-memory position-priority is exact in both zones");
        }
        // Background zone covers [0, startAtMs): entries 0,1,2 (indices whose start < 3000).
        List<ChunkProgressBarView.Segment> background = model.segments.stream()
                .filter(s -> s.backgroundPass).toList();
        List<ChunkProgressBarView.Segment> priority = model.segments.stream()
                .filter(s -> !s.backgroundPass).toList();
        assertEquals(3, background.size());
        assertEquals(3, priority.size());
        assertEquals(0L, background.get(0).startMs);
        assertEquals(startAtMs, background.get(background.size() - 1).endMs);
        assertEquals(startAtMs, priority.get(0).startMs);
        assertEquals(6_000L, priority.get(priority.size() - 1).endMs);
    }

    @Test
    void streaming_realChunkSpanningTwoEstimatedSlots_leavesNoOrphanSegment() {
        ChunkingConfig config = config(1_000L, 1_000L); // naive estimate: boundaries every 1000ms
        ChunkTimelineTracker tracker = new ChunkTimelineTracker(config);
        SubtitlePipelineSession session = neverStartedSession(config);

        tracker.reset(5_000L, 0L, null); // streaming: no exactEntries
        ChunkProgressBarView.Model estimate = tracker.buildModel(running(List.of(), false), session, 0L);
        // Naive estimate: boundaries at 1000,2000,3000,4000,5000 -> 5 segments, all estimated.
        assertEquals(5, estimate.segments.size());
        assertTrue(estimate.segments.stream().allMatch(s -> s.endEstimated));

        // The real chunk turns out much bigger than any single estimated slot — content ends at 2800ms
        // (a single chunk spanning what the estimate thought would be 3 separate slots).
        ChunkProgress chunk0 = active(0, 0, 0, 0L, 2_800L);
        tracker.onProgress(running(List.of(chunk0), false));

        ChunkProgressBarView.Model corrected = tracker.buildModel(running(List.of(chunk0), false), session, 0L);
        // Exactly one real (non-estimated) segment for the real chunk, then a re-estimated tail from
        // its real end (2800) to the total duration (5000) — never three leftover slots from the old
        // per-slot estimate, and never a duplicate/overlapping segment.
        long realCount = corrected.segments.stream().filter(s -> !s.endEstimated).count();
        assertEquals(1, realCount);
        ChunkProgressBarView.Segment real = corrected.segments.get(0);
        assertEquals(0L, real.startMs);
        assertEquals(2_800L, real.endMs);
        assertFalse(real.endEstimated);
        // No gap and no overlap: the next segment picks up exactly where the real one ended.
        assertEquals(2_800L, corrected.segments.get(1).startMs);
        long last = corrected.segments.get(corrected.segments.size() - 1).endMs;
        assertEquals(5_000L, last);
    }

    @Test
    void streaming_positionPriority_backgroundChunkCrossingSeam_classifiedIntoBackgroundZone() {
        ChunkingConfig config = config(1_000L, 1_000L);
        ChunkTimelineTracker tracker = new ChunkTimelineTracker(config);
        SubtitlePipelineSession session = neverStartedSession(config);

        long startAtMs = 5_000L;
        tracker.reset(10_000L, startAtMs, null); // streaming position-priority

        // A background-pass chunk whose entries all start before startAtMs, but whose last cue's END
        // crosses it (a cue lasting past 5000ms) — this is the seam case that a naive end-based
        // classification used to misroute into the priority zone's map.
        ChunkProgress chunk0 = active(0, 0, 0, 4_000L, 5_400L);
        tracker.onProgress(running(List.of(chunk0), false));

        ChunkProgressBarView.Model model = tracker.buildModel(running(List.of(chunk0), false), session, 0L);
        List<ChunkProgressBarView.Segment> background = model.segments.stream()
                .filter(s -> s.backgroundPass).toList();
        List<ChunkProgressBarView.Segment> priority = model.segments.stream()
                .filter(s -> !s.backgroundPass).toList();

        // The chunk's real range corrected the background zone's own boundary — real (not estimated),
        // and visually pinned at the zone seam (startAtMs) so the background zone never overdraws into
        // the priority zone's drawing area, but still detected TRANSLATING via time-range overlap
        // (which isn't affected by the visual pin: it tests against the chunk's true [4000,5400) range).
        ChunkProgressBarView.Segment lastBackground = background.get(background.size() - 1);
        assertFalse(lastBackground.endEstimated, "the seam chunk must correct the background zone's own boundary");
        assertEquals(startAtMs, lastBackground.endMs);
        assertEquals(ChunkProgressBarView.SegmentState.TRANSLATING, lastBackground.state);

        // The priority zone is untouched by the misrouting this regression guards against — the old
        // realEnd-based classification would have routed this chunk's boundary here instead (finding
        // no close estimated boundary near 5400, discarding the correction silently). Still a plain
        // estimated tail starting fresh at startAtMs, no real boundary bled in from the background chunk.
        assertEquals(startAtMs, priority.get(0).startMs);
        assertTrue(priority.get(0).endEstimated);
    }

    @Test
    void failedChunk_usesItsOwnRealRange_neighborsStayAligned() {
        ChunkingConfig config = config(1_000L, 1_000L);
        ChunkTimelineTracker tracker = new ChunkTimelineTracker(config);
        SubtitlePipelineSession session = neverStartedSession(config);

        tracker.reset(4_000L, 0L, null);
        ChunkProgress chunk0 = active(0, 0, 0, 0L, 1_700L);
        tracker.onProgress(running(List.of(chunk0), false));
        // Chunk 0 closes, failed.
        tracker.onProgress(running(List.of(), true));

        ChunkProgressBarView.Model model = tracker.buildModel(running(List.of(), true), session, 0L);
        ChunkProgressBarView.Segment first = model.segments.get(0);
        assertEquals(ChunkProgressBarView.SegmentState.FAILED, first.state);
        assertEquals(0L, first.startMs);
        assertEquals(1_700L, first.endMs, "FAILED must use the chunk's real end, not a stale estimate");
        // The next segment starts exactly where the failed one ended — no gap, no overlap.
        assertEquals(1_700L, model.segments.get(1).startMs);
    }

    @Test
    void outOfOrderClose_higherIndexClosesBeforeLower_bothKeepTheirOwnRealRange() {
        ChunkingConfig config = config(1_000L, 1_000L);
        ChunkTimelineTracker tracker = new ChunkTimelineTracker(config);
        SubtitlePipelineSession session = neverStartedSession(config);

        tracker.reset(6_000L, 0L, null);

        // Both chunks are dispatched (start in index order, as a FIFO thread pool guarantees) and both
        // become active in the same tick.
        ChunkProgress chunk0 = active(0, 0, 0, 0L, 2_000L);
        ChunkProgress chunk1 = active(1, 1, 1, 2_000L, 4_500L);
        tracker.onProgress(running(List.of(chunk0, chunk1), false));

        // Chunk 1 (higher index, later content) closes FIRST — the out-of-order case that broke the
        // old "correct the first still-estimated boundary" design.
        tracker.onProgress(running(List.of(chunk0), false));
        // Then chunk 0 closes.
        tracker.onProgress(running(List.of(), false));

        ChunkProgressBarView.Model model = tracker.buildModel(running(List.of(), false), session, 0L);
        List<ChunkProgressBarView.Segment> real = model.segments.stream().filter(s -> !s.endEstimated).toList();
        assertEquals(2, real.size(), "both chunks must keep their own boundary, none overwritten by the other");
        assertEquals(2_000L, real.get(0).endMs, "chunk 0's own end, unaffected by chunk 1 closing first");
        assertEquals(4_500L, real.get(1).endMs, "chunk 1's own end");
    }

    @Test
    void streaming_extractionPastEstimatedTarget_isClosing_untilTheRealChunkActuallyCloses() {
        ChunkingConfig config = config(1_000L, 1_000L);
        ChunkTimelineTracker tracker = new ChunkTimelineTracker(config);
        SubtitlePipelineSession session = neverStartedSession(config);

        tracker.reset(3_000L, 0L, null); // estimated boundaries at 1000, 2000, 3000
        ChunkProgressBarView.Model model = tracker.buildModel(streaming(List.of(), 2_500L), session, 0L);

        assertEquals(3, model.segments.size());
        // Extraction has read past the first two segments' *estimated* ends, but no real chunk has
        // closed there yet — the chunker is still extending past its target, searching for a natural
        // pause (or the hard max-duration cap). That's CLOSING, not CLOSED: CLOSED means the real
        // boundary is already known (see the next test), which isn't the case here — the boundary is
        // still a guess (endEstimated stays true).
        assertEquals(ChunkProgressBarView.SegmentState.CLOSING, model.segments.get(0).state);
        assertTrue(model.segments.get(0).endEstimated);
        assertEquals(ChunkProgressBarView.SegmentState.CLOSING, model.segments.get(1).state);
        // The third segment is still being read — a growing fill, not yet at its target.
        ChunkProgressBarView.Segment third = model.segments.get(2);
        assertEquals(ChunkProgressBarView.SegmentState.EXTRACTING, third.state);
        assertEquals(0.5f, third.extractingFill, 0.001f);
    }

    @Test
    void streaming_extractingBeforeFirstChunkStarted_labelDegradesToPlainPercent_notNull() {
        // Regression: the EXTRACTING segment's fill/label are driven by TranslationProgress, whose
        // extractionSpeedFactor stays -1 (unmeasured) until a chunk is actively translating — which
        // for a run's very first segment means the whole run-up to the first chunk closing. The old
        // "label = null unless speedFactor > 0" left this window's segment fill-only, with no percent
        // ever shown next to it, even though real extraction progress was there the whole time.
        ChunkingConfig config = config(1_000L, 1_000L);
        ChunkTimelineTracker tracker = new ChunkTimelineTracker(config);
        SubtitlePipelineSession session = neverStartedSession(config);

        tracker.reset(1_000L, 0L, null); // one estimated segment, [0, 1000)
        ChunkProgressBarView.Model model = tracker.buildModel(streamingBeforeAnyChunkStarted(400L), session, 0L);

        assertEquals(1, model.segments.size());
        ChunkProgressBarView.Segment seg = model.segments.get(0);
        assertEquals(ChunkProgressBarView.SegmentState.EXTRACTING, seg.state);
        assertEquals(0.4f, seg.extractingFill, 0.001f);
        assertEquals("40%", seg.label, "no speed factor yet must still show a plain percentage, not null");
    }

    @Test
    void streaming_realChunkClosedAndNoLongerActive_isClosed_notPendingOrClosingForever() {
        ChunkingConfig config = config(1_000L, 1_000L);
        ChunkTimelineTracker tracker = new ChunkTimelineTracker(config);
        SubtitlePipelineSession session = neverStartedSession(config);

        tracker.reset(3_000L, 0L, null);
        ChunkProgress chunk0 = active(0, 0, 0, 0L, 1_200L);
        // Chunk 0 starts, then closes — no longer active, not failed, not yet picked up for translation
        // (e.g. queued behind maxConcurrency).
        tracker.onProgress(running(List.of(chunk0), false));
        tracker.onProgress(running(List.of(), false));

        ChunkProgressBarView.Model model = tracker.buildModel(running(List.of(), false), session, 0L);
        ChunkProgressBarView.Segment first = model.segments.get(0);
        // The real boundary is known (endEstimated=false) — CLOSED, not stuck pulsing CLOSING forever
        // just because progress.getExtractedContentMs() also happens to have passed this point by now.
        assertFalse(first.endEstimated);
        assertEquals(1_200L, first.endMs);
        assertEquals(ChunkProgressBarView.SegmentState.CLOSED, first.state);
    }

    @Test
    void runStatusDone_paintsEverythingDone_evenWithNoRealChunksRecorded() {
        ChunkingConfig config = config(1_000L, 1_000L);
        ChunkTimelineTracker tracker = new ChunkTimelineTracker(config);
        SubtitlePipelineSession session = neverStartedSession(config);

        tracker.reset(5_000L, 0L, null); // streaming estimate, nothing ever corrected
        ChunkProgressBarView.Model model = tracker.buildModel(done(), session, 5_000L);
        assertFalse(model.segments.isEmpty());
        for (ChunkProgressBarView.Segment seg : model.segments) {
            assertEquals(ChunkProgressBarView.SegmentState.DONE, seg.state);
        }
    }
}
