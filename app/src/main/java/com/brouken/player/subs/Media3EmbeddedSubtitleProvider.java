package com.brouken.player.subs;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.datasource.DataSource;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.text.CueDecoder;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import subtitleengine.embedded.EmbeddedSubtitleProvider;
import subtitleengine.embedded.RawCue;

/**
 * Reads an embedded subtitle track out of a container by driving Media3's extractors directly,
 * with its own {@link DataSource} independent of the ExoPlayer session so it never interferes with
 * playback.
 *
 * <p><b>Why a second pass over the container at all.</b> While playing, the same cues already flow
 * through the player — the extractor demuxes them alongside audio and video and the text renderer
 * pulls the one for the current instant. But that only advances at playback speed, and consumed
 * samples are released from the sample queue, so at no point does a complete subtitle exist
 * anywhere. Translating needs every cue up front, and the only way to have the last one is to read
 * the container through to the end. On a remote source that is the whole download — which is why
 * this is user-initiated, reports progress, and honours interruption.
 *
 * <p>Parsing is Media3's, not ours: the output is wrapped in a
 * {@link SubtitleTranscodingExtractorOutput}, so each container sample arrives already decoded into
 * {@link CuesWithTiming} by the same parsers that render these tracks during playback (SubRip, ASS,
 * WebVTT, TTML…). This class only routes bytes and converts the result into the engine's
 * {@link RawCue}; ordering, missing durations and simultaneous cues are the engine's job.
 */
public class Media3EmbeddedSubtitleProvider implements EmbeddedSubtitleProvider {

    private static final String TAG = "EmbeddedSubtitles";

    private final Context context;
    @Nullable private final Map<String, String> headers;

    public Media3EmbeddedSubtitleProvider(Context context, @Nullable Map<String, String> headers) {
        this.context = context.getApplicationContext();
        this.headers = headers;
    }

    @Override
    public void readCues(String source, int trackIndex, long resumeFromMs,
                         @Nullable ProgressListener onProgress, CueSink sink) throws Exception {
        Uri uri = Uri.parse(source);
        // Parallel fetch (ParallelChunkedDataSource) reverted to sequential for now: confirmed real on
        // device (up to 4 overlapping connections, real speedup) but also confirmed two real bugs under
        // frequent seeks -- an OutOfMemoryError from abandoned chunks outliving their window (fixed with
        // a shorter per-chunk timeout) and, still open, an HTTP 416 whose chunk-range math checks out on
        // this end, so it looks like the seek-churn itself (a window torn down and rebuilt on every
        // Matroska SeekHead/Cues jump) hitting a proxy that tolerates concurrent/rapid connections worse
        // than a plain debrid mirror does. See PENDING.md for the full trail before re-enabling.
        DataSource dataSource = Media3ExtractorSource.createDataSource(context, headers, uri);
        Extractor extractor = null;
        try {
            ExtractorInput input = Media3ExtractorSource.openAt(dataSource, uri, 0);
            long length = input.getLength();

            extractor = Media3ExtractorSource.sniff(input, uri);
            if (extractor == null) {
                throw new IOException("no Media3 extractor recognised this container");
            }

            CueCollector collector = new CueCollector(trackIndex, sink);
            // The transcoding output is what makes the samples arrive as parsed cues instead of
            // raw codec payloads — the same wrapper DefaultExtractorsFactory uses for playback.
            extractor.init(new SubtitleTranscodingExtractorOutput(collector, new DefaultSubtitleParserFactory()));

            try {
                readToEnd(extractor, input, dataSource, uri, length, resumeFromMs, collector, onProgress);
            } catch (NoMatchingTextTrackException e) {
                throw new IOException(e.getMessage());
            }

            Log.i(TAG, "read " + collector.delivered + " cues from text track " + trackIndex
                    + " (" + collector.textTracksSeen + " text track(s) in the container)");
            if (collector.textTracksSeen == 0) {
                throw new IOException("the container has no text tracks");
            }
        } finally {
            if (extractor != null) {
                try { extractor.release(); } catch (Exception ignored) { }
            }
            try { dataSource.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * Pumps the extractor until the container ends, honouring its seek requests and reporting how
     * much of the file has been consumed.
     */
    private void readToEnd(Extractor extractor, ExtractorInput input, DataSource dataSource, Uri uri,
                           long length, long resumeFromMs, CueCollector collector,
                           @Nullable ProgressListener onProgress)
            throws IOException, InterruptedException {
        PositionHolder positionHolder = new PositionHolder();
        long total = length;
        ExtractorInput currentInput = input;
        // extractor.read() returns after every parsed element, so this loop spins thousands of times
        // a second. Reporting each one floods the main thread's queue with progress posts and janks
        // playback (measured: ~950 skipped frames, a 15s stall). Report in 0.5% steps instead —
        // ~200 updates for a whole file, which is more than a progress line can show anyway.
        int lastReportedBucket = -1;
        // The SeekMap only exists once the container's headers have been parsed, so a resume cannot
        // happen before the read starts — it happens as soon as the map shows up.
        boolean resumePending = resumeFromMs > 0;
        int result;
        do {
            // Cancellation: this loop can be minutes of network reads, and nobody is waiting for a
            // download the user already called off.
            if (Thread.interrupted()) throw new InterruptedException();

            if (resumePending && collector.seekMap != null) {
                resumePending = false;
                SeekMap map = collector.seekMap;
                if (map.isSeekable()) {
                    // Land on the sync point at or before the resume time: the cues between it and
                    // the resume point are replayed, and the caller drops them as overlap. Seeking
                    // past it would lose cues outright, which is the one outcome worth avoiding.
                    SeekMap.SeekPoints points = map.getSeekPoints(resumeFromMs * 1000L);
                    long position = points.first.position;
                    extractor.seek(position, points.first.timeUs);
                    currentInput = Media3ExtractorSource.openAt(dataSource, uri, position);
                    Log.i(TAG, "resuming from " + resumeFromMs + "ms → byte " + position
                            + " of " + total);
                } else {
                    Log.i(TAG, "container is not seekable — reading from the start instead of "
                            + "skipping, since a gap would be worse than the wait");
                }
            }

            result = extractor.read(currentInput, positionHolder);
            if (result == Extractor.RESULT_SEEK) {
                // The extractor wants to continue elsewhere (an MP4 moov at the end, a Matroska
                // cluster jump): reopen the source there and hand it a fresh input at that offset.
                currentInput = Media3ExtractorSource.openAt(dataSource, uri, positionHolder.position);
                long reopenedLength = currentInput.getLength();
                if (reopenedLength != C.LENGTH_UNSET && total != C.LENGTH_UNSET) {
                    total = reopenedLength;
                }
            } else if (onProgress != null && total != C.LENGTH_UNSET && total > 0) {
                double fraction = (double) currentInput.getPosition() / total;
                int bucket = (int) (fraction * 200);
                if (bucket != lastReportedBucket) {
                    lastReportedBucket = bucket;
                    onProgress.onProgress(fraction);
                }
            }
        } while (result != Extractor.RESULT_END_OF_INPUT);
    }

    /**
     * Collects the cues of one text track, counted among text tracks only and in the order the
     * container declares them — the same numbering {@code SubtitleSelectionController} uses to list
     * the embedded options, since both come from the same extractor's track order.
     */
    private static final class CueCollector implements ExtractorOutput {

        private final int wantedTextTrackIndex;
        private final CueSink sink;
        int textTracksSeen;
        int delivered;

        CueCollector(int wantedTextTrackIndex, CueSink sink) {
            this.wantedTextTrackIndex = wantedTextTrackIndex;
            this.sink = sink;
        }

        @Override
        public TrackOutput track(int id, int type) {
            if (type != C.TRACK_TYPE_TEXT) return new DiscardingOutput();
            int textIndex = textTracksSeen++;
            if (textIndex != wantedTextTrackIndex) return new DiscardingOutput();
            // Cues are handed over as they decode, not collected and returned at the end: a cancelled
            // read throws, and anything still held here would die with it instead of being kept as a
            // resumable prefix.
            return new CueTrackOutput(batch -> {
                delivered += batch.size();
                sink.accept(batch);
            });
        }

        /**
         * Fired once the extractor has declared every track the container has — for a standard MP4
         * this happens right after parsing {@code moov}, before a single byte of {@code mdat} (the
         * actual, potentially huge, audio/video payload) is touched. Without this check, a container
         * with no text track (or none at {@code wantedTextTrackIndex}) was read all the way to
         * {@code RESULT_END_OF_INPUT} before reporting that — for a large remote file over a throttled
         * connection, that is minutes spent discovering something already knowable the moment the
         * track list is complete. Thrown unchecked because {@link ExtractorOutput#endTracks()} declares
         * no checked exception; {@link #readCues} unwraps it back into the documented
         * {@link IOException}.
         */
        @Override public void endTracks() {
            if (wantedTextTrackIndex >= textTracksSeen) {
                throw new NoMatchingTextTrackException(textTracksSeen == 0
                        ? "the container has no text tracks"
                        : "no text track at index " + wantedTextTrackIndex + " (container has "
                                + textTracksSeen + ")");
            }
        }

        /** Captured so a resumed read can ask it where a given timestamp lives in the file. */
        @Nullable SeekMap seekMap;

        @Override public void seekMap(SeekMap seekMap) {
            this.seekMap = seekMap;
        }
    }

    /** Accumulates one track's sample bytes and turns each completed sample into {@link RawCue}s. */
    private static final class CueTrackOutput implements TrackOutput {

        private final CueSink out;
        private final CueDecoder decoder = new CueDecoder();
        private byte[] buffer = new byte[1024];
        private int bufferedBytes;

        CueTrackOutput(CueSink out) {
            this.out = out;
        }

        @Override public void format(Format format) { }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart)
                throws IOException {
            ensureCapacity(bufferedBytes + length);
            int read = input.read(buffer, bufferedBytes, length);
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT;
                throw new java.io.EOFException();
            }
            bufferedBytes += read;
            return read;
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
            ensureCapacity(bufferedBytes + length);
            data.readBytes(buffer, bufferedBytes, length);
            bufferedBytes += length;
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset, @Nullable CryptoData cryptoData) {
            // The sample is the `size` bytes ending `offset` bytes before what has been buffered so
            // far — that is the contract sampleData/sampleMetadata are written against.
            int end = bufferedBytes - offset;
            int start = end - size;
            if (start < 0 || end > bufferedBytes) {
                bufferedBytes = 0;
                return; // malformed run; drop it rather than decode garbage
            }
            try {
                CuesWithTiming decoded = decoder.decode(timeUs, buffer, start, size);
                append(decoded);
            } catch (RuntimeException e) {
                Log.w(TAG, "skipping an undecodable cue sample at " + timeUs + "us", e);
            }
            // Everything up to `end` is consumed; keep whatever came after it (the next sample's head).
            int leftover = bufferedBytes - end;
            if (leftover > 0) System.arraycopy(buffer, end, buffer, 0, leftover);
            bufferedBytes = Math.max(0, leftover);
        }

        /**
         * One {@link CuesWithTiming} holds every cue on screen for that interval — a styled track
         * emits one per screen position. They go out as separate {@link RawCue}s sharing an interval;
         * merging them into one subtitle is the engine assembler's decision, not this class's.
         */
        private void append(CuesWithTiming decoded) {
            long startMs = decoded.startTimeUs / 1000;
            boolean hasDuration = decoded.durationUs != C.TIME_UNSET && decoded.durationUs > 0;
            long endMs = hasDuration ? (decoded.startTimeUs + decoded.durationUs) / 1000 : RawCue.UNKNOWN_END_MS;
            List<RawCue> batch = new ArrayList<>(decoded.cues.size());
            for (Cue cue : decoded.cues) {
                // Bitmap cues (PGS/VobSub) carry no text — nothing to translate, and the caller
                // should not have offered the track in the first place.
                if (cue.text == null) continue;
                String text = cue.text.toString();
                batch.add(hasDuration ? RawCue.of(startMs, endMs, text) : RawCue.openEnded(startMs, text));
            }
            if (!batch.isEmpty()) out.accept(batch);
        }

        private void ensureCapacity(int needed) {
            if (needed <= buffer.length) return;
            int size = buffer.length;
            while (size < needed) size *= 2;
            byte[] grown = new byte[size];
            System.arraycopy(buffer, 0, grown, 0, bufferedBytes);
            buffer = grown;
        }
    }

    /** Thrown from {@link CueCollector#endTracks()} — see its javadoc. */
    private static final class NoMatchingTextTrackException extends RuntimeException {
        NoMatchingTextTrackException(String message) {
            super(message);
        }
    }

    /** Swallows every track we did not ask for, without buffering a byte of it. */
    private static final class DiscardingOutput implements TrackOutput {

        @Override public void format(Format format) { }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput, int sampleDataPart)
                throws IOException {
            int skipped = input.read(new byte[length], 0, length);
            if (skipped == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw new java.io.EOFException();
            return skipped;
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, int sampleDataPart) {
            data.skipBytes(length);
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset, @Nullable CryptoData cryptoData) { }
    }
}
