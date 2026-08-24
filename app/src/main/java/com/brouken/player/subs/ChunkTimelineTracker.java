package com.brouken.player.subs;

import androidx.annotation.Nullable;

import com.brouken.player.subs.ui.SubsTheme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import subtitleengine.core.model.SubtitleEntry;
import subtitleengine.pipeline.SubtitlePipelineSession;
import subtitleengine.translation.ChunkPriority;
import subtitleengine.translation.ChunkProgress;
import subtitleengine.translation.ChunkingConfig;
import subtitleengine.translation.RunStatus;
import subtitleengine.translation.SubtitleChunk;
import subtitleengine.translation.SubtitleChunker;
import subtitleengine.translation.TranslationProgress;

/**
 * Builds {@link ChunkProgressBarView.Model} snapshots from {@link TranslationProgress} events — the
 * adapter between engine state and the dumb renderer. All of this is presentation logic: it reshapes
 * state the engine already computed into drawable segments, it never decides anything about chunking
 * or translation itself (see the design plan). One instance per {@link TranslationController}, reset
 * at the start of every run.
 *
 * <p><b>Identity, not position.</b> A chunk is identified by {@code (zone, pass-local index)} — the
 * same index {@link ChunkProgress#getIndex()}/{@link SubtitleChunk#getIndex()} already assign,
 * sequentially in content order, never reused within a pass. Known chunks live in a
 * {@link Map} keyed by that index ({@link #backgroundReal}/{@link #priorityReal}), never in a flat
 * list mutated by position — so the drawable segment list (built fresh in {@link #buildBoundariesFor}
 * on every {@link #buildModel} call, never persisted across ticks) can grow or shrink from tick to
 * tick as real chunks arrive, without anything holding a stale reference to "segment N". A real
 * chunk's range comes straight off {@link ChunkProgress#getStartMs()}/{@link ChunkProgress#getEndMs()}
 * — read by the engine off the actual chunk at dispatch time — never by cross-referencing SRT indices
 * against a locally-held copy of the subtitle file, which used to lag for as long as a chunk was in
 * flight (see {@link #recordReal}'s javadoc).
 */
final class ChunkTimelineTracker {

    // Plain classes, not records: this module targets Java 8 (no android.javaCompile toolchain bump),
    // unlike subtitle-engine, which is plain JVM and can use records freely (see SubtitleChunker's
    // ChunkLimits).
    private static final class Boundary {
        private final long ms;
        private final boolean estimated;
        private final boolean failed;
        Boundary(long ms, boolean estimated, boolean failed) {
            this.ms = ms;
            this.estimated = estimated;
            this.failed = failed;
        }
        long ms() { return ms; }
        boolean estimated() { return estimated; }
        boolean failed() { return failed; }
    }

    /** A chunk whose real timing is known — either because it started/closed for real (streaming), or
     *  because the whole source was known upfront and chunked exactly (in-memory). */
    private static final class RealChunk {
        private final long startMs;
        private final long endMs;
        private final boolean failed;
        RealChunk(long startMs, long endMs, boolean failed) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.failed = failed;
        }
        long startMs() { return startMs; }
        long endMs() { return endMs; }
        boolean failed() { return failed; }
    }

    private final ChunkingConfig chunkingConfig;

    /** Known chunks of the lower-priority background/head pass, [0, startAtMs) — keyed by pass-local
     *  chunk index. Only ever populated for a position-priority run. */
    private final Map<Integer, RealChunk> backgroundReal = new TreeMap<>();
    /** Known chunks of the pass actually being watched, [startAtMs, totalDurationMs). */
    private final Map<Integer, RealChunk> priorityReal = new TreeMap<>();
    private long startAtMs;
    private long totalDurationMs;
    /** Whether {@link #reset} got a fully-known entry list — the priority zone's urgent-chunk count
     *  differs between an in-memory position-priority run (always 1, {@link SubtitlePipelineSession
     *  #runInMemory}) and a streaming one ({@link SubtitlePipelineSession#PRIORITY_PASS_URGENT_CHUNK_COUNT}). */
    private boolean isExact;

    // Soft translation-progress heuristic: keyed by "firstEntry-lastEntry" (unique within a run).
    private final Map<String, Long> chunkStartedAtRealMs = new LinkedHashMap<>();
    private final List<Long> completedChunkDurationsMs = new ArrayList<>();

    // Diffing activeChunks tick to tick is how a chunk's real range becomes known — the engine only
    // ever hands out a snapshot of what's active right now, never a per-index history.
    private Map<Integer, ChunkProgress> previousActiveChunks = Map.of();

    ChunkTimelineTracker(ChunkingConfig chunkingConfig) {
        this.chunkingConfig = chunkingConfig;
    }

    /**
     * Called once when a run starts, before any progress event. {@code exactEntries} non-null means
     * the source is already fully known — used to chunk both zones exactly, right now, instead of
     * estimating and correcting later (see {@link #populateExact}).
     */
    void reset(long totalDurationMs, long startAtMs, @Nullable List<SubtitleEntry> exactEntries) {
        this.totalDurationMs = totalDurationMs;
        this.startAtMs = Math.max(0L, startAtMs);
        this.isExact = exactEntries != null;
        chunkStartedAtRealMs.clear();
        completedChunkDurationsMs.clear();
        previousActiveChunks = Map.of();
        backgroundReal.clear();
        priorityReal.clear();

        if (totalDurationMs <= 0 || exactEntries == null || exactEntries.isEmpty()) return;
        populateExact(exactEntries);
    }

    /**
     * Chunks the already-known {@code exactEntries} right now, the same way the engine actually will
     * ({@link SubtitlePipelineSession#runInMemory} mirrored via {@link #entryIndexForMs}/
     * {@link #filterRange} — private there, small enough to duplicate rather than widen that class's
     * visibility, same call the engine itself already makes for its own private mirror of
     * {@code SubtitleTranslator}'s helpers) — so both zones of an in-memory run, position-priority or
     * not, are exact from the very first frame. No estimated tail, ever, for this case.
     */
    private void populateExact(List<SubtitleEntry> exactEntries) {
        int lastBeforeIndex = startAtMs > 0 ? entryIndexForMs(exactEntries, startAtMs) : 0;
        if (lastBeforeIndex <= 0) {
            populateRealFrom(new SubtitleChunker(chunkingConfig).chunk(exactEntries, ChunkPriority.URGENT), priorityReal);
            return;
        }
        List<SubtitleEntry> priority = filterRange(exactEntries, lastBeforeIndex + 1, null);
        List<SubtitleEntry> head = filterRange(exactEntries, null, lastBeforeIndex);
        populateRealFrom(new SubtitleChunker(chunkingConfig).chunk(priority, ChunkPriority.URGENT), priorityReal);
        populateRealFrom(new SubtitleChunker(chunkingConfig).chunk(head, ChunkPriority.BULK), backgroundReal);
    }

    private static void populateRealFrom(List<SubtitleChunk> chunks, Map<Integer, RealChunk> into) {
        for (SubtitleChunk c : chunks) {
            List<SubtitleEntry> payload = c.getEntries();
            into.put(c.getIndex(), new RealChunk(payload.get(0).getStartMs(),
                    payload.get(payload.size() - 1).getEndMs(), false));
        }
    }

    /** Called on every progress event — records real chunk ranges as they become known, detects failures. */
    void onProgress(TranslationProgress progress) {
        Map<Integer, ChunkProgress> active = new LinkedHashMap<>();
        for (ChunkProgress cp : progress.getActiveChunks()) active.put(cp.getIndex(), cp);

        // A chunk that was active last tick and no longer is either succeeded or failed. Index alone
        // can repeat across passes (each pass's chunker restarts at 0), but the two passes never run
        // concurrently (a position-priority run's background pass only starts once the priority pass's
        // runPass — including every in-flight future — has returned), so there is never a live
        // collision between passes at any single tick.
        for (Map.Entry<Integer, ChunkProgress> e : previousActiveChunks.entrySet()) {
            if (active.containsKey(e.getKey())) continue;
            ChunkProgress closed = e.getValue();
            String key = closed.getFirstEntry() + "-" + closed.getLastEntry();
            Long startedAt = chunkStartedAtRealMs.remove(key);
            boolean failed = progress.isLastChunkFailed();
            if (!failed && startedAt != null) completedChunkDurationsMs.add(System.currentTimeMillis() - startedAt);
            recordReal(closed, failed);
        }
        for (ChunkProgress cp : active.values()) {
            String key = cp.getFirstEntry() + "-" + cp.getLastEntry();
            chunkStartedAtRealMs.putIfAbsent(key, System.currentTimeMillis());
            // Its real range is known the instant it starts (see recordReal's javadoc) — recording it
            // now, not just at close, is what lets a segment show TRANSLATING for a chunk's whole time
            // in flight instead of only once it finishes.
            recordReal(cp, false);
        }
        previousActiveChunks = active;
    }

    /**
     * Records {@code chunk}'s real range into whichever zone it geometrically belongs to.
     * {@link ChunkProgress#getStartMs()}/{@link ChunkProgress#getEndMs()} are read by the engine
     * straight off the real {@link subtitleengine.translation.SubtitleChunk} at dispatch time — always
     * present, never dependent on a locally-held copy of the subtitle file catching up (see that
     * javadoc for why cross-referencing entry indices against one used to fail silently for as long as
     * a chunk was in flight).
     */
    private void recordReal(ChunkProgress chunk, boolean failed) {
        RealChunk rc = new RealChunk(chunk.getStartMs(), chunk.getEndMs(), failed);
        // A chunk belongs to the background/head zone iff its own start is < startAtMs — the same axis
        // SubtitlePipelineSession.runPass uses to admit entries into that pass (entry.getStartMs() <
        // endAtMs), so this is exact by construction, not a heuristic. A cue can run long enough that
        // its *end* crosses startAtMs while still unambiguously belonging to the background pass by
        // start — testing the end instead (an earlier version of this class did) misrouted exactly
        // that chunk into the wrong zone's map.
        if (chunk.getStartMs() < startAtMs) backgroundReal.put(chunk.getIndex(), rc);
        else priorityReal.put(chunk.getIndex(), rc);
    }

    /**
     * Builds the drawable model for this tick, for {@code currentPositionMs} (the live playhead).
     * Segments are rebuilt fresh every call from {@link #backgroundReal}/{@link #priorityReal} plus a
     * re-estimated tail for whatever hasn't closed yet — never a persisted list corrected in place, so
     * a real chunk spanning more or less than a naive per-slot estimate simply produces however many
     * real segments the map holds, with no leftover slot to reconcile.
     */
    ChunkProgressBarView.Model buildModel(@Nullable TranslationProgress progress, SubtitlePipelineSession session,
                                          long currentPositionMs) {
        if (totalDurationMs <= 0) return ChunkProgressBarView.Model.EMPTY;

        // The background/head pass is always ChunkPriority.BULK — never urgent — regardless of where
        // its zone starts; the priority pass's urgent count differs by run kind (see isExact's javadoc).
        int priorityUrgentChunkCount = (startAtMs <= 0 || isExact)
                ? 1 : SubtitlePipelineSession.PRIORITY_PASS_URGENT_CHUNK_COUNT;
        List<Boundary> background = buildBoundariesFor(backgroundReal, 0L, startAtMs, 0, isExact);
        List<Boundary> priority = buildBoundariesFor(priorityReal, startAtMs, totalDurationMs, priorityUrgentChunkCount, isExact);
        if (background.isEmpty() && priority.isEmpty()) return ChunkProgressBarView.Model.EMPTY;

        Map<Integer, ChunkProgress> active = new LinkedHashMap<>();
        if (progress != null) {
            for (ChunkProgress cp : progress.getActiveChunks()) active.put(cp.getIndex(), cp);
        }

        List<ChunkProgressBarView.Segment> segments = new ArrayList<>();
        appendSegments(segments, background, 0L, true, active, progress, session);
        appendSegments(segments, priority, startAtMs, false, active, progress, session);

        return new ChunkProgressBarView.Model(segments, totalDurationMs, currentPositionMs);
    }

    /**
     * Boundaries for one zone: a real boundary (solid) for every chunk already known, in index order
     * (a {@link TreeMap} — content order, since a pass's chunk indices are assigned sequentially as
     * chunks close), followed by an estimated tail (dashed) for whatever's left. The tail's urgent
     * count only subtracts chunks already known ({@code real.size()}), which is safe because a chunk
     * is first learned about when it *starts* (the common path in {@link #onProgress}, only falling
     * back to close-time when its entries hadn't reached {@link #currentFile} yet) and starts always
     * arrive in index order: the chunk executor is a fixed-thread-pool with a FIFO queue, so a free
     * worker always picks up the oldest-dispatched (i.e. earliest-content) chunk first, even though
     * *closes* can land out of order once several chunks are translating in parallel.
     *
     * <p>{@code complete} (true only for an exact/in-memory zone, see {@link #isExact}) skips the
     * estimated tail entirely, regardless of {@code cursor}: real content almost never reaches exactly
     * to the zone's true end (trailing silence with no cue), and a streaming zone's naive estimate
     * always ends exactly at {@code zoneEnd} by construction — so for either kind of zone, appending an
     * estimate once every real chunk is already known would only ever add a spurious sliver segment,
     * never real information. The final boundary is instead always clamped to {@code zoneEnd} — a
     * no-op whenever a list already ends there. A {@code complete} zone with no real chunks at all
     * (genuinely no subtitle content in it) still gets a single non-estimated boundary spanning the
     * whole zone: knowing for certain there's nothing there is real information, not a guess, and the
     * bar should still visually cover the full zone either way.
     */
    private List<Boundary> buildBoundariesFor(Map<Integer, RealChunk> real, long zoneStart, long zoneEnd,
                                              int urgentChunksInZone, boolean complete) {
        if (zoneEnd <= zoneStart) return List.of();
        List<Boundary> out = new ArrayList<>();
        long cursor = zoneStart;
        for (RealChunk rc : real.values()) {
            out.add(new Boundary(rc.endMs(), false, rc.failed()));
            cursor = rc.endMs();
        }
        if (!complete && cursor < zoneEnd) {
            int remainingUrgent = Math.max(0, urgentChunksInZone - real.size());
            for (long ms : chunkingConfig.estimateBoundariesMs(zoneEnd, cursor, remainingUrgent)) {
                out.add(new Boundary(ms, true, false));
            }
        }
        if (out.isEmpty()) {
            out.add(new Boundary(zoneEnd, false, false));
        } else {
            Boundary last = out.get(out.size() - 1);
            if (last.ms() != zoneEnd) out.set(out.size() - 1, new Boundary(zoneEnd, last.estimated(), last.failed()));
        }
        return out;
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
                                @Nullable TranslationProgress progress, SubtitlePipelineSession session) {
        long start = zoneStart;
        for (Boundary b : list) {
            long end = b.ms();
            if (end <= start) { start = end; continue; }
            out.add(progress == null
                    ? new ChunkProgressBarView.Segment(start, end, ChunkProgressBarView.SegmentState.PENDING,
                            b.estimated(), backgroundPass, 0f, null)
                    : buildSegment(start, end, b.estimated(), b.failed(), backgroundPass, active, progress, session));
            start = end;
        }
    }

    private ChunkProgressBarView.Segment buildSegment(long start, long end, boolean endEstimated, boolean failed,
                                                       boolean backgroundPass, Map<Integer, ChunkProgress> active,
                                                       TranslationProgress progress, SubtitlePipelineSession session) {
        if (failed) {
            return new ChunkProgressBarView.Segment(start, end, ChunkProgressBarView.SegmentState.FAILED,
                    endEstimated, backgroundPass, 0f, null);
        }
        // A finished run has nothing left pending, no matter what the boundary bookkeeping still
        // thinks — belt-and-suspenders: every real chunk records its own range as soon as it's known
        // (see recordReal), so by DONE the maps should already fully cover both zones with no
        // estimated tail left — but this is the cheap fallback if some edge case ever left one anyway.
        if (progress.getStatus() == RunStatus.DONE) {
            return new ChunkProgressBarView.Segment(start, end, ChunkProgressBarView.SegmentState.DONE,
                    endEstimated, backgroundPass, 0f, null);
        }
        // Translated already? A direct, range-scoped question — session.isRangeReady(start, end) says
        // yes only once every entry actually inside [start, end) is done, regardless of what came
        // before it, after it, or which pass (priority or background) reached it first.
        if (session.isRangeReady(start, end)) {
            return new ChunkProgressBarView.Segment(start, end, ChunkProgressBarView.SegmentState.DONE,
                    endEstimated, backgroundPass, 0f, null);
        }
        for (ChunkProgress cp : active.values()) {
            if (overlaps(start, end, cp.getStartMs(), cp.getEndMs())) {
                return new ChunkProgressBarView.Segment(start, end, ChunkProgressBarView.SegmentState.TRANSLATING,
                        endEstimated, backgroundPass, 0f, softProgressLabel(cp));
            }
        }
        // A known (real, not estimated) boundary that isn't failed/done/translating is a chunk that's
        // already extracted and chunked, just waiting its turn (maxConcurrency, or the executor queue)
        // — CLOSED, not PENDING (see UI-SUBS.html: PENDING is "hasn't been touched", CLOSED is "ready
        // to translate").
        if (!endEstimated) {
            return new ChunkProgressBarView.Segment(start, end, ChunkProgressBarView.SegmentState.CLOSED,
                    endEstimated, backgroundPass, 0f, null);
        }
        if (progress.isStreaming()) {
            // progress.getExtractedContentMs() only moves when a cue arrives (see onEntryExtracted in
            // the engine) — a stretch with no dialogue reports nothing at all, and the bar reads as
            // stalled even though bytes keep coming in. session.currentExtractionFraction() is a live
            // read of the container's byte position instead, cue or no cue; scaled by totalDurationMs it
            // is the same "roughly uniform bitrate" assumption the chunk-duration estimates already
            // make. Only ever raises the estimate — a stale cue-based number from before the container
            // caught up must never make progress look like it went backwards.
            long extracted = Math.max(progress.getExtractedContentMs(),
                    (long) (session.currentExtractionFraction() * totalDurationMs));
            // A single scalar can't unambiguously locate the frontier across two disjoint zones — once
            // a position-priority run's priority pass has read anything, `extracted` sits somewhere at
            // or past startAtMs, which numerically satisfies "start <= extracted" for every earlier
            // background-zone segment too, even though the background pass (which runs strictly after
            // the priority one) hasn't even started yet. Bound to whichever zone `extracted` is
            // actually reporting progress for.
            boolean extractedIsInThisZone = backgroundPass ? extracted <= startAtMs : extracted >= startAtMs;
            if (extractedIsInThisZone && start <= extracted) {
                if (extracted >= end) {
                    // Extraction has read past this segment's *estimated* end (a guess based on target
                    // duration), but no real chunk has closed here yet — the chunker is still extending
                    // past the target, searching for a good natural pause (or the hard maxChunkMs cap)
                    // to actually cut at. That search is what CLOSING means — distinct from CLOSED,
                    // which means the real chunk boundary is already known (see the !endEstimated
                    // branch above) and it's merely waiting its turn to translate.
                    return new ChunkProgressBarView.Segment(start, end, ChunkProgressBarView.SegmentState.CLOSING,
                            endEstimated, backgroundPass, 0f, null);
                }
                float fill = clamp01((float) (extracted - start) / (float) (end - start));
                // The speed factor is only known once a chunk is actively translating (see
                // SubtitlePipelineSession.updateEtaLocked) — before the first chunk starts, extraction
                // is still real progress worth showing, just without the "· 1.2x" half of the label.
                String label = progress.getExtractionSpeedFactor() > 0
                        ? String.format(Locale.US, "%d%% · %.1fx", Math.round(fill * 100), progress.getExtractionSpeedFactor())
                        : String.format(Locale.US, "%d%%", Math.round(fill * 100));
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

    /**
     * The model reduced to at most {@code maxCells} equal-width colour cells, for the over-video
     * pill's mini bar. Each cell takes the colour of whatever segment covers its midpoint, so a
     * single failed chunk in the middle of a long run still shows up as one red cell instead of
     * being averaged away — which is the only thing that strip is there to say.
     */
    static int[] miniCells(ChunkProgressBarView.Model model, int maxCells) {
        int n = Math.min(maxCells, model.segments.size());
        if (n <= 0 || model.totalDurationMs <= 0) return new int[0];
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            long at = (long) ((i + 0.5) / n * model.totalDurationMs);
            out[i] = SubsTheme.STATUS_PENDING;
            for (ChunkProgressBarView.Segment seg : model.segments) {
                if (at >= seg.startMs && at < seg.endMs) {
                    out[i] = colorFor(seg.state);
                    break;
                }
            }
        }
        return out;
    }

    private static int colorFor(ChunkProgressBarView.SegmentState state) {
        switch (state) {
            case DONE: return SubsTheme.STATUS_DONE;
            case TRANSLATING: return SubsTheme.STATUS_TRANSLATING;
            case FAILED: return SubsTheme.STATUS_FAILED;
            case CLOSED: return SubsTheme.STATUS_CLOSED;
            case CLOSING:
            case EXTRACTING: return SubsTheme.STATUS_EXTRACTING;
            case PENDING:
            default: return SubsTheme.STATUS_PENDING;
        }
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

    /** Mirrors {@code SubtitlePipelineSession.entryIndexForMs} (private there) — last SRT index whose
     *  startMs < ms — so the in-memory exact preview splits {@code exactEntries} exactly the way
     *  {@code runInMemory} will. */
    private static int entryIndexForMs(List<SubtitleEntry> entries, long ms) {
        int lastIndex = 0;
        for (SubtitleEntry entry : entries) {
            if (entry.getStartMs() < ms) lastIndex = entry.getIndex();
            else break;
        }
        return lastIndex;
    }

    /** Mirrors {@code SubtitlePipelineSession.filterRange} (private there). */
    private static List<SubtitleEntry> filterRange(List<SubtitleEntry> entries, Integer firstEntry, Integer lastEntry) {
        List<SubtitleEntry> result = new ArrayList<>();
        for (SubtitleEntry e : entries) {
            if (firstEntry != null && e.getIndex() < firstEntry) continue;
            if (lastEntry != null && e.getIndex() > lastEntry) break;
            result.add(e);
        }
        return result;
    }
}
