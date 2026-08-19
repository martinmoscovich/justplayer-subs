package com.brouken.player.subs;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import subtitleengine.core.model.SubtitleEntry;
import subtitleengine.core.model.SubtitleFile;
import subtitleengine.pipeline.SubtitlePipelineSession;
import subtitleengine.translation.ChunkPriority;
import subtitleengine.translation.ChunkProgress;
import subtitleengine.translation.ChunkingConfig;
import subtitleengine.translation.RunStatus;
import subtitleengine.translation.SubtitleChunk;
import subtitleengine.translation.SubtitleChunker;
import subtitleengine.translation.TranslationProgress;

/**
 * Builds {@link ChunkProgressBarView.Model} snapshots from {@link TranslationProgress} events plus
 * the growing {@link SubtitleFile} — the adapter between engine state and the dumb renderer. All of
 * this is presentation logic: it reshapes state the engine already computed into drawable segments,
 * it never decides anything about chunking or translation itself (see the design plan). One instance
 * per {@link TranslationController}, reset at the start of every run.
 */
final class ChunkTimelineTracker {

    private static final class Boundary {
        long ms;
        boolean estimated;
        Boundary(long ms, boolean estimated) { this.ms = ms; this.estimated = estimated; }
    }

    private final ChunkingConfig chunkingConfig;

    /** Lower-priority pass over [0, startAtMs) — only non-empty for a position-priority in-memory run. */
    private List<Boundary> backgroundBoundaries = List.of();
    /** The pass actually being watched, over [startAtMs, totalDurationMs). */
    private List<Boundary> priorityBoundaries = List.of();
    private long startAtMs;
    private long totalDurationMs;
    @Nullable private SubtitleFile currentFile;

    // Soft translation-progress heuristic: keyed by "firstEntry-lastEntry" (unique within a run).
    private final Map<String, Long> chunkStartedAtRealMs = new LinkedHashMap<>();
    private final List<Long> completedChunkDurationsMs = new ArrayList<>();

    // Failed-range detection: diffing activeChunks tick to tick — the engine never exposes a
    // per-chunk-index failed flag directly, only "the most recently closed chunk failed".
    private Map<Integer, ChunkProgress> previousActiveChunks = Map.of();
    private final List<long[]> failedRangesMs = new ArrayList<>();

    ChunkTimelineTracker(ChunkingConfig chunkingConfig) {
        this.chunkingConfig = chunkingConfig;
    }

    /**
     * Called once when a run starts, before any progress event. {@code exactEntries} non-null means
     * the source is already fully known — used for the exact pre-pass instead of an estimate; only
     * applies when {@code startAtMs <= 0} (see the design plan's "accepted limitation" for
     * position-priority runs, which always get the estimated-and-corrected treatment).
     */
    void reset(long totalDurationMs, long startAtMs, @Nullable List<SubtitleEntry> exactEntries) {
        this.totalDurationMs = totalDurationMs;
        this.startAtMs = Math.max(0L, startAtMs);
        this.currentFile = null;
        chunkStartedAtRealMs.clear();
        completedChunkDurationsMs.clear();
        previousActiveChunks = Map.of();
        failedRangesMs.clear();
        backgroundBoundaries = List.of();
        priorityBoundaries = List.of();

        if (totalDurationMs <= 0) return;

        if (exactEntries != null && this.startAtMs <= 0) {
            List<Boundary> exact = new ArrayList<>();
            for (SubtitleChunk c : new SubtitleChunker(chunkingConfig).chunk(exactEntries, ChunkPriority.URGENT)) {
                List<SubtitleEntry> payload = c.getEntries();
                exact.add(new Boundary(payload.get(payload.size() - 1).getEndMs(), false));
            }
            if (!exact.isEmpty()) exact.get(exact.size() - 1).ms = totalDurationMs;
            priorityBoundaries = exact;
            return;
        }

        if (this.startAtMs > 0) {
            List<Boundary> bg = new ArrayList<>();
            for (long ms : chunkingConfig.estimateBoundariesMs(this.startAtMs, 0L)) bg.add(new Boundary(ms, true));
            backgroundBoundaries = bg;
        }
        List<Boundary> pr = new ArrayList<>();
        for (long ms : chunkingConfig.estimateBoundariesMs(totalDurationMs, this.startAtMs)) pr.add(new Boundary(ms, true));
        priorityBoundaries = pr;
    }

    void onSubtitleUpdated(SubtitleFile current) {
        this.currentFile = current;
    }

    /** Called on every progress event — corrects estimated boundaries as chunks close, detects failures. */
    void onProgress(TranslationProgress progress) {
        Map<Integer, ChunkProgress> active = new LinkedHashMap<>();
        for (ChunkProgress cp : progress.getActiveChunks()) active.put(cp.getIndex(), cp);

        // Diff against last tick: a chunk that was active and no longer is either succeeded or failed.
        // Index alone can repeat across passes (each pass's chunker restarts at 0), but the two passes
        // never run concurrently (runInMemory finishes the priority pass before starting the background
        // one), so there is never a live collision at any single tick.
        for (Map.Entry<Integer, ChunkProgress> e : previousActiveChunks.entrySet()) {
            if (active.containsKey(e.getKey())) continue;
            ChunkProgress closed = e.getValue();
            String key = closed.getFirstEntry() + "-" + closed.getLastEntry();
            Long startedAt = chunkStartedAtRealMs.remove(key);
            if (progress.isLastChunkFailed()) {
                long[] range = realRangeFor(closed.getFirstEntry(), closed.getLastEntry());
                if (range != null) failedRangesMs.add(range);
            } else {
                if (startedAt != null) completedChunkDurationsMs.add(System.currentTimeMillis() - startedAt);
                correctBoundaryFor(closed);
            }
        }
        for (Map.Entry<Integer, ChunkProgress> e : active.entrySet()) {
            String key = e.getValue().getFirstEntry() + "-" + e.getValue().getLastEntry();
            chunkStartedAtRealMs.putIfAbsent(key, System.currentTimeMillis());
        }
        previousActiveChunks = active;
    }

    private void correctBoundaryFor(ChunkProgress closed) {
        long[] range = realRangeFor(closed.getFirstEntry(), closed.getLastEntry());
        if (range == null) return;
        long realEndMs = range[1];
        if (realEndMs < startAtMs) {
            backgroundBoundaries = correctList(backgroundBoundaries, realEndMs, startAtMs);
        } else {
            priorityBoundaries = correctList(priorityBoundaries, realEndMs, totalDurationMs);
        }
    }

    /** Replaces the nearest estimated boundary with {@code realEndMs} and re-estimates everything
     *  after it up to {@code zoneEndMs}, so later estimates stay consistent with the new known point. */
    private List<Boundary> correctList(List<Boundary> list, long realEndMs, long zoneEndMs) {
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).estimated) continue;
            List<Boundary> merged = new ArrayList<>(list.subList(0, i));
            merged.add(new Boundary(realEndMs, false));
            for (long ms : chunkingConfig.estimateBoundariesMs(zoneEndMs, realEndMs)) {
                merged.add(new Boundary(ms, true));
            }
            return merged;
        }
        return list;
    }

    @Nullable
    private long[] realRangeFor(int firstEntry, int lastEntry) {
        if (currentFile == null) return null;
        long startMs = -1, endMs = -1;
        for (SubtitleEntry e : currentFile.getEntries()) {
            if (e.getIndex() == firstEntry) startMs = e.getStartMs();
            if (e.getIndex() == lastEntry) { endMs = e.getEndMs(); break; }
        }
        return (startMs >= 0 && endMs >= 0) ? new long[]{startMs, endMs} : null;
    }

    /** Builds the drawable model for this tick, for {@code currentPositionMs} (the live playhead). */
    ChunkProgressBarView.Model buildModel(TranslationProgress progress, SubtitlePipelineSession session,
                                          long currentPositionMs) {
        if (totalDurationMs <= 0 || (backgroundBoundaries.isEmpty() && priorityBoundaries.isEmpty())) {
            return ChunkProgressBarView.Model.EMPTY;
        }

        Map<Integer, ChunkProgress> active = new LinkedHashMap<>();
        for (ChunkProgress cp : progress.getActiveChunks()) active.put(cp.getIndex(), cp);

        List<ChunkProgressBarView.Segment> segments = new ArrayList<>();
        appendSegments(segments, backgroundBoundaries, 0L, true, active, progress, session);
        appendSegments(segments, priorityBoundaries, startAtMs, false, active, progress, session);

        return new ChunkProgressBarView.Model(segments, totalDurationMs, currentPositionMs);
    }

    /**
     * Rough ETA for the whole run to finish: chunks not yet completed times the average chunk
     * duration measured so far in this run, divided by how many are translating in parallel right
     * now. {@code null} until at least one chunk has completed (no baseline yet) — same honesty rule
     * {@link #softProgressLabel} follows for a single chunk, applied to the whole run.
     */
    @Nullable
    Long estimatedRemainingMs(TranslationProgress progress) {
        if (completedChunkDurationsMs.isEmpty()) return null;
        int remaining = Math.max(0, progress.getTotalChunks() - progress.getCompletedChunks());
        if (remaining == 0) return 0L;
        double avg = completedChunkDurationsMs.stream().mapToLong(Long::longValue).average().orElse(0);
        if (avg <= 0) return null;
        int parallelism = Math.max(1, progress.getActiveChunks().size());
        return (long) Math.ceil(avg * remaining / parallelism);
    }

    private void appendSegments(List<ChunkProgressBarView.Segment> out, List<Boundary> list, long zoneStart,
                                boolean backgroundPass, Map<Integer, ChunkProgress> active,
                                TranslationProgress progress, SubtitlePipelineSession session) {
        long start = zoneStart;
        for (Boundary b : list) {
            long end = b.ms;
            if (end <= start) { start = end; continue; }
            out.add(buildSegment(start, end, b.estimated, backgroundPass, active, progress, session));
            start = end;
        }
    }

    private ChunkProgressBarView.Segment buildSegment(long start, long end, boolean endEstimated,
                                                       boolean backgroundPass, Map<Integer, ChunkProgress> active,
                                                       TranslationProgress progress, SubtitlePipelineSession session) {
        for (long[] failed : failedRangesMs) {
            if (overlaps(start, end, failed[0], failed[1])) {
                return new ChunkProgressBarView.Segment(start, end, ChunkProgressBarView.SegmentState.FAILED,
                        endEstimated, backgroundPass, 0f, null);
            }
        }
        // A finished run has nothing left pending, no matter what the boundary bookkeeping still
        // thinks — the real chunker can (and often does) close a zone in fewer, differently-sized
        // chunks than the naive duration-based estimate projected, leaving trailing estimated
        // segments with no real chunk left to correct them. Once DONE, every non-failed segment is
        // done by definition.
        if (progress.getStatus() == RunStatus.DONE) {
            return new ChunkProgressBarView.Segment(start, end, ChunkProgressBarView.SegmentState.DONE,
                    endEstimated, backgroundPass, 0f, null);
        }
        // Translated already? Ask the session fresh, from this segment's own start — never stale,
        // and correctly handles disjoint translated regions (priority pass vs. background pass).
        if (session.watchableUntilMs(start) >= end) {
            return new ChunkProgressBarView.Segment(start, end, ChunkProgressBarView.SegmentState.DONE,
                    endEstimated, backgroundPass, 0f, null);
        }
        for (ChunkProgress cp : active.values()) {
            long[] range = realRangeFor(cp.getFirstEntry(), cp.getLastEntry());
            if (range != null && overlaps(start, end, range[0], range[1])) {
                return new ChunkProgressBarView.Segment(start, end, ChunkProgressBarView.SegmentState.TRANSLATING,
                        endEstimated, backgroundPass, 0f, softProgressLabel(cp));
            }
        }
        if (progress.isStreaming()) {
            long extracted = progress.getExtractedContentMs();
            if (start <= extracted) {
                if (extracted >= end) {
                    return new ChunkProgressBarView.Segment(start, end, ChunkProgressBarView.SegmentState.CLOSING,
                            endEstimated, backgroundPass, 0f, null);
                }
                float fill = clamp01((float) (extracted - start) / (float) (end - start));
                String label = progress.getExtractionSpeedFactor() > 0
                        ? String.format(Locale.US, "%d%% · %.1fx", Math.round(fill * 100), progress.getExtractionSpeedFactor())
                        : null;
                return new ChunkProgressBarView.Segment(start, end, ChunkProgressBarView.SegmentState.EXTRACTING,
                        endEstimated, backgroundPass, fill, label);
            }
        }
        return new ChunkProgressBarView.Segment(start, end, ChunkProgressBarView.SegmentState.PENDING,
                endEstimated, backgroundPass, 0f, null);
    }

    @Nullable
    private String softProgressLabel(ChunkProgress cp) {
        String key = cp.getFirstEntry() + "-" + cp.getLastEntry();
        Long startedAt = chunkStartedAtRealMs.get(key);
        if (startedAt == null || completedChunkDurationsMs.isEmpty()) return null;
        long elapsed = System.currentTimeMillis() - startedAt;
        double avg = completedChunkDurationsMs.stream().mapToLong(Long::longValue).average().orElse(0);
        if (avg <= 0) return null;
        int pct = (int) Math.round(Math.min(0.95, elapsed / avg) * 100);
        return "~" + pct + "%";
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** Overall completion, 0-100 — the sum of DONE segment durations over the whole timeline, not a
     *  chunk count (chunks vary wildly in duration, so "2/5 chunks" reads nothing like "2/5 of the video"). */
    static int percentDone(ChunkProgressBarView.Model model) {
        if (model.totalDurationMs <= 0) return 0;
        long doneMs = 0;
        for (ChunkProgressBarView.Segment seg : model.segments) {
            if (seg.state == ChunkProgressBarView.SegmentState.DONE) doneMs += (seg.endMs - seg.startMs);
        }
        return (int) Math.round(100.0 * doneMs / model.totalDurationMs);
    }

    private static boolean overlaps(long aStart, long aEnd, long bStart, long bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }
}
