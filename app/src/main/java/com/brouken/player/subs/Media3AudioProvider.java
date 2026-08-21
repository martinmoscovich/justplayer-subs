package com.brouken.player.subs;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.DataReader;
import androidx.media3.datasource.DataSource;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder;
import androidx.media3.decoder.ffmpeg.FfmpegDecoderException;
import androidx.media3.decoder.ffmpeg.FfmpegLibrary;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import subtitleengine.audio.AudioProvider;
import subtitleengine.audio.PcmDownmixResampler;
import subtitleengine.vad.ResyncProgressListener;

/**
 * Extracts a mono 16kHz float32 PCM segment from a media source using Media3's own extractors and
 * the bundled FFmpeg audio decoder, with instances entirely independent of the active ExoPlayer
 * session so it never interferes with playback.
 *
 * <p><b>Why not {@code android.media.MediaExtractor}.</b> The previous implementation used the
 * platform's {@code MediaExtractor}/{@code MediaCodec} pair. Its native Matroska demuxer keeps a
 * {@code CodecID} whitelist that includes neither {@code A_AC3} nor {@code A_DTS}: it drops those
 * tracks while parsing {@code Tracks}, so they never appeared in {@code getTrackCount()} and
 * extraction failed before any decoder was even chosen. That is AOSP platform behaviour, not a
 * device gap — reproduced on an Android 14 x86 emulator reading a local file, where the very same
 * AC3 stream remuxed into MP4 demuxed fine (see {@code PENDING.md} &sect;1 for the full matrix, and
 * {@code LESSONS.md} "Addendum 2" for how that was misdiagnosed as an HTTP transport problem for a
 * while). Since most of this project's target content is AC3/DTS inside MKV, that whitelist blocked
 * auto-sync on nearly everything.
 *
 * <p>Media3's {@code MatroskaExtractor} keeps its own table, which does list {@code A_AC3} /
 * {@code A_DTS} / {@code A_TRUEHD} — it is the demuxer already feeding the player's track selector,
 * which is exactly why that selector could show audio tracks the old provider swore did not exist.
 * Using it here makes both halves of the app agree on what a file contains.
 *
 * <p><b>One decode path, on purpose.</b> {@code MediaCodec} is gone too, not just the demuxer. Its
 * only real advantage was hardware acceleration, and extraction is not real-time bound: measured on
 * AC3 5.1, this path delivered 40s of audio in 1.87s cold and 0.57s warm (~21x / ~70x real time) —
 * on an x86 emulator, so treat those as an order of magnitude rather than a device figure. Keeping a
 * second backend for a speed-up nothing needs would mean maintaining a fallback that only runs when
 * the primary fails: one that is never exercised, and rots silently. The cost of dropping it is that
 * audio codec coverage is now exactly the bundled FFmpeg build's list (see {@code app/libs/README.md}:
 * vorbis/opus/flac/alac/mp3/aac/ac3/eac3/dca/mlp/truehd/amrnb/amrwb/pcm_mulaw/pcm_alaw), plus the
 * raw-PCM passthrough below. Anything outside that gets a specific error naming the codec instead of
 * a generic failure.
 *
 * <p>Channel downmix and resampling live in {@link PcmDownmixResampler} (engine, pure Java) rather
 * than here — that math has zero Android dependency, so per the project's golden rule it is shared
 * with the JVM CLI/test path instead of reimplemented against a platform API.
 */
public class Media3AudioProvider implements AudioProvider {

    private static final String TAG = "Media3AudioProvider";
    private static final int TARGET_RATE = 16_000;
    // ~20s at the 10ms sleep in the stalled branch below — generous enough to ride out real network
    // stalls without hanging the worker thread forever on a decoder that never signals EOS.
    private static final int MAX_STALLED_ITERATIONS = 2_000;
    // A container whose header parse never completes is a broken or unsupported file, not a slow one:
    // every read() here is synchronous, so this bounds a malformed input rather than a slow network.
    private static final int MAX_HEADER_READS = 10_000;

    private final Context context;
    @Nullable private final Map<String, String> headers;

    /** See {@link AudioProvider#extractAudioSegment} — thrown instead of returning null when we can
     *  name the reason. Unchecked: {@link AudioProvider}'s signature declares no checked exceptions. */
    private static final class UnsupportedAudioTrackException extends RuntimeException {
        UnsupportedAudioTrackException(String message) {
            super(message);
        }
    }

    public Media3AudioProvider(Context context, @Nullable Map<String, String> headers) {
        this.context = context.getApplicationContext();
        this.headers = headers;
    }

    @Override
    @Nullable
    public float[] extractAudioSegment(String source, double startSeconds, double durationSeconds,
                                       @Nullable ResyncProgressListener onProgress) {
        long startUs = (long) (startSeconds * 1_000_000);
        long endUs = startUs + (long) (durationSeconds * 1_000_000);
        Uri uri = Uri.parse(source);
        DataSource dataSource = Media3ExtractorSource.createDataSource(context, headers);
        Extractor extractor = null;
        FfmpegAudioDecoder decoder = null;
        try {
            ExtractorInput input = Media3ExtractorSource.openAt(dataSource, uri, 0);
            extractor = Media3ExtractorSource.sniff(input, uri);
            if (extractor == null) {
                throw new UnsupportedAudioTrackException(
                        "Unrecognised media container for auto-sync — no extractor could read this file");
            }

            AudioSink sink = new AudioSink();
            extractor.init(sink);
            PositionHolder seekPosition = new PositionHolder();

            // Phase 1: read just far enough for the container to declare its tracks (and, ideally, its
            // seek map). Anything buffered during this phase is dropped — we have not seeked yet.
            int headerReads = 0;
            boolean inputExhausted = false;
            while (!sink.tracksEnded && !inputExhausted) {
                if (++headerReads > MAX_HEADER_READS) {
                    throw new UnsupportedAudioTrackException(
                            "Could not read the media header for auto-sync — the file may be corrupt "
                                    + "or in an unsupported format");
                }
                int result = extractor.read(input, seekPosition);
                if (result == Extractor.RESULT_SEEK) {
                    input = Media3ExtractorSource.openAt(dataSource, uri, seekPosition.position);
                } else if (result == Extractor.RESULT_END_OF_INPUT) {
                    inputExhausted = true;
                }
            }

            AudioTrack audio = sink.audioTrack;
            if (audio == null || audio.format == null) {
                Log.w(TAG, "extractAudioSegment: no audio track found in " + describe(source));
                throw new UnsupportedAudioTrackException(
                        "No audio track found for auto-sync in this file");
            }
            Format format = audio.format;
            Log.i(TAG, "extractAudioSegment: audio track " + format.sampleMimeType + " "
                    + format.channelCount + "ch @" + format.sampleRate + "Hz in " + describe(source));

            if (MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)) {
                // Nothing to decode — but PcmDownmixResampler consumes interleaved 16-bit LE only, so
                // any other width would need a conversion step that does not exist yet.
                if (format.pcmEncoding != C.ENCODING_PCM_16BIT) {
                    throw new UnsupportedAudioTrackException(
                            "Unsupported raw PCM format for auto-sync (only 16-bit is supported)");
                }
            } else {
                if (!FfmpegLibrary.isAvailable() || !FfmpegLibrary.supportsFormat(format.sampleMimeType)) {
                    throw new UnsupportedAudioTrackException(
                            "No decoder available for " + format.sampleMimeType + " — auto-sync cannot "
                                    + "extract audio from this file");
                }
                decoder = new FfmpegAudioDecoder(
                        format, /* numInputBuffers= */ 2, /* numOutputBuffers= */ 2,
                        /* initialInputBufferSize= */ 256 * 1024, /* outputFloat= */ false);
            }

            // Phase 2: jump to the requested window. SEEK_TO_PREVIOUS_SYNC semantics come for free —
            // getSeekPoints().first is the sync point at or before startUs — and the decode loop drops
            // everything before startUs, so the extra lead-in never biases the result.
            SeekMap seekMap = sink.seekMap;
            if (startUs > 0 && seekMap != null && seekMap.isSeekable()) {
                SeekMap.SeekPoints points = seekMap.getSeekPoints(startUs);
                input = Media3ExtractorSource.openAt(dataSource, uri, points.first.position);
                extractor.seek(points.first.position, points.first.timeUs);
                audio.reset();
            } else if (startUs > 0) {
                // Not seekable: the loop below still lands on the right window, it just has to read
                // (and drop) everything before it.
                Log.w(TAG, "extractAudioSegment: source is not seekable, reading forward to "
                        + startSeconds + "s");
            }

            float[] result = decodeAndResample(extractor, input, dataSource, uri, seekPosition, audio,
                    decoder, startUs, endUs, durationSeconds, onProgress);

            // Requested vs delivered: a short window silently starves the resyncer's evidence gate,
            // which then reports "no confident match" with no hint that it was fed less than it asked
            // for. Decode can end early (EOS, stall timeout) and the seek lands on the previous sync
            // sample, so neither the length nor the start is guaranteed to be what we asked for.
            double deliveredSeconds = result == null ? 0.0 : result.length / (double) TARGET_RATE;
            Log.i(TAG, String.format(Locale.US,
                    "extract: requested start=%.1fs duration=%.1fs -> delivered %.1fs (%d samples)",
                    startSeconds, durationSeconds, deliveredSeconds, result == null ? 0 : result.length));
            if (result != null && onProgress != null) {
                onProgress.onProgress(ResyncProgressListener.Phase.EXTRACTING, 1.0);
            }
            return result;
        } catch (UnsupportedAudioTrackException e) {
            throw e; // has a specific, user-facing reason — let it propagate instead of collapsing to null
        } catch (Exception e) {
            Log.e(TAG, "extractAudioSegment: failed for " + describe(source), e);
            return null;
        } finally {
            if (decoder != null) {
                try { decoder.release(); } catch (Exception ignored) { }
            }
            if (extractor != null) {
                try { extractor.release(); } catch (Exception ignored) { }
            }
            try { dataSource.close(); } catch (Exception ignored) { }
        }
    }

    // --- decode ---

    /**
     * Pulls samples out of {@code audio} (refilling it from the extractor as needed), feeds them to
     * the decoder, and downmixes+resamples to mono 16kHz incrementally. Returns {@code null} on
     * interruption or a stalled decoder; an empty array is a valid "no audio in range" result, not a
     * failure (the caller's PCM-core check rejects it).
     */
    @Nullable
    private float[] decodeAndResample(Extractor extractor, ExtractorInput input, DataSource dataSource,
                                      Uri uri, PositionHolder seekPosition, AudioTrack audio,
                                      @Nullable FfmpegAudioDecoder decoder, long startUs, long endUs,
                                      double durationSeconds, @Nullable ResyncProgressListener onProgress)
            throws IOException, FfmpegDecoderException {
        boolean inputDone = false;
        boolean outputDone = false;
        boolean readerFinished = false;
        int staleIterations = 0;
        PcmDownmixResampler acc = null;

        while (!outputDone) {
            if (Thread.currentThread().isInterrupted()) {
                Log.w(TAG, "decodeAndResample: interrupted, aborting extraction");
                return null;
            }
            boolean progressed = false;

            // Keep at least one sample queued so the decoder is never handed an empty buffer.
            while (!readerFinished && audio.isEmpty()) {
                int result = extractor.read(input, seekPosition);
                if (result == Extractor.RESULT_SEEK) {
                    input = Media3ExtractorSource.openAt(dataSource, uri, seekPosition.position);
                } else if (result == Extractor.RESULT_END_OF_INPUT) {
                    readerFinished = true;
                }
            }

            if (!inputDone) {
                Sample sample = audio.peek();
                boolean pastWindow = sample != null && sample.timeUs > endUs;
                if (decoder == null) {
                    // Raw PCM: no decoder in the loop at all, the samples already are the output.
                    progressed = true;
                    if (sample == null || pastWindow) {
                        inputDone = true;
                        outputDone = true;
                    } else {
                        audio.poll();
                        if (sample.timeUs >= startUs) {
                            if (acc == null) acc = newResampler(audio.format);
                            acc.append(sample.data, 0, sample.size);
                            reportProgress(onProgress, sample.timeUs, startUs, durationSeconds);
                        }
                    }
                } else {
                    DecoderInputBuffer inputBuffer = decoder.dequeueInputBuffer();
                    if (inputBuffer != null) {
                        progressed = true;
                        if (sample == null || pastWindow) {
                            inputBuffer.timeUs = 0;
                            inputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
                            decoder.queueInputBuffer(inputBuffer);
                            inputDone = true;
                        } else {
                            audio.poll();
                            inputBuffer.clear();
                            inputBuffer.ensureSpaceForWrite(sample.size);
                            // ensureSpaceForWrite can swap in a larger buffer — read .data after it.
                            ByteBuffer data = inputBuffer.data;
                            data.put(sample.data, 0, sample.size);
                            data.flip();
                            inputBuffer.timeUs = sample.timeUs;
                            decoder.queueInputBuffer(inputBuffer);
                        }
                    }
                }
            }

            if (decoder != null) {
                SimpleDecoderOutputBuffer outputBuffer = decoder.dequeueOutputBuffer();
                if (outputBuffer != null) {
                    progressed = true;
                    if (outputBuffer.isEndOfStream()) {
                        outputBuffer.release();
                        outputDone = true;
                    } else {
                        if (!outputBuffer.shouldBeSkipped && outputBuffer.data != null
                                && outputBuffer.timeUs >= startUs) {
                            if (acc == null) {
                                acc = new PcmDownmixResampler(
                                        decoder.getSampleRate(), decoder.getChannelCount(), TARGET_RATE);
                            }
                            acc.append(outputBuffer.data, outputBuffer.data.remaining());
                            reportProgress(onProgress, outputBuffer.timeUs, startUs, durationSeconds);
                        }
                        outputBuffer.release();
                    }
                }
            }

            if (progressed) {
                staleIterations = 0;
            } else if (++staleIterations > MAX_STALLED_ITERATIONS) {
                Log.w(TAG, "decodeAndResample: no progress for " + MAX_STALLED_ITERATIONS
                        + " iterations, aborting instead of hanging forever");
                return null;
            } else {
                // FfmpegAudioDecoder's dequeue calls never block (unlike MediaCodec's timeout-based
                // ones) — without this, an iteration waiting on the decode thread would spin the CPU
                // and burn MAX_STALLED_ITERATIONS in microseconds instead of the ~20s it allows for.
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        return acc != null ? acc.finish() : new float[0];
    }

    private static PcmDownmixResampler newResampler(@Nullable Format format) {
        int rate = format != null && format.sampleRate != Format.NO_VALUE ? format.sampleRate : TARGET_RATE;
        int channels = format != null && format.channelCount != Format.NO_VALUE ? format.channelCount : 1;
        return new PcmDownmixResampler(rate, channels, TARGET_RATE);
    }

    private static void reportProgress(@Nullable ResyncProgressListener onProgress, long timeUs,
                                       long startUs, double durationSeconds) {
        if (onProgress == null || durationSeconds <= 0) return;
        double coveredUs = Math.max(0, timeUs - startUs);
        double fraction = Math.max(0.0, Math.min(1.0, coveredUs / (durationSeconds * 1_000_000)));
        onProgress.onProgress(ResyncProgressListener.Phase.EXTRACTING, fraction);
    }

    /** Truncated, never the full source string — media URLs can carry auth tokens in the query. */
    private static String describe(String source) {
        return source.length() <= 60 ? source : "…" + source.substring(source.length() - 60);
    }

    // --- extractor plumbing ---

    /** One demuxed sample: the bytes the extractor pushed, plus when they play. */
    private static final class Sample {
        final byte[] data;
        final int size;
        final long timeUs;

        Sample(byte[] data, int size, long timeUs) {
            this.data = data;
            this.size = size;
            this.timeUs = timeUs;
        }
    }

    /**
     * Media3 extractors <em>push</em> into a {@link TrackOutput} instead of being polled for the next
     * sample the way {@code android.media.MediaExtractor} was, so this is the buffer that turns that
     * push back into a pull for the decode loop. Only the chosen audio track captures anything; every
     * other track still has its bytes consumed (the extractor requires it) but immediately dropped.
     */
    private static final class AudioTrack implements TrackOutput {
        @Nullable Format format;
        boolean capturing;

        private final ArrayDeque<Sample> pending = new ArrayDeque<>();
        private byte[] buffer = new byte[64 * 1024];
        private int bufferLength;
        private final byte[] scratch = new byte[16 * 1024];

        @Override
        public void format(Format format) {
            this.format = format;
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput,
                              @SampleDataPart int sampleDataPart) throws IOException {
            if (!capturing || sampleDataPart != SAMPLE_DATA_PART_MAIN) {
                int read = input.read(scratch, 0, Math.min(length, scratch.length));
                if (read == C.RESULT_END_OF_INPUT && !allowEndOfInput) {
                    throw new EOFException();
                }
                return read;
            }
            ensureCapacity(bufferLength + length);
            int read = input.read(buffer, bufferLength, length);
            if (read == C.RESULT_END_OF_INPUT) {
                if (!allowEndOfInput) throw new EOFException();
                return read;
            }
            bufferLength += read;
            return read;
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, @SampleDataPart int sampleDataPart) {
            if (!capturing || sampleDataPart != SAMPLE_DATA_PART_MAIN) {
                data.skipBytes(length);
                return;
            }
            ensureCapacity(bufferLength + length);
            data.readBytes(buffer, bufferLength, length);
            bufferLength += length;
        }

        @Override
        public void sampleMetadata(long timeUs, @C.BufferFlags int flags, int size, int offset,
                                   @Nullable CryptoData cryptoData) {
            if (!capturing) return;
            // Per TrackOutput's contract, `offset` counts the bytes pushed *after* this sample's last
            // byte, so the sample ends at bufferLength - offset.
            int end = bufferLength - offset;
            int start = end - size;
            if (start < 0 || end > bufferLength) {
                // Would mean bytes we were supposed to keep got dropped; better to skip the sample
                // than to hand the decoder a misaligned frame.
                Log.w(TAG, "sampleMetadata: sample outside buffered range, dropping (size=" + size
                        + " offset=" + offset + " buffered=" + bufferLength + ")");
                bufferLength = 0;
                return;
            }
            byte[] data = new byte[size];
            System.arraycopy(buffer, start, data, 0, size);
            pending.add(new Sample(data, size, timeUs));
            // Keep only the trailing bytes belonging to a sample we have not been told about yet.
            System.arraycopy(buffer, end, buffer, 0, offset);
            bufferLength = offset;
        }

        private void ensureCapacity(int required) {
            if (buffer.length >= required) return;
            int size = buffer.length;
            while (size < required) size *= 2;
            byte[] grown = new byte[size];
            System.arraycopy(buffer, 0, grown, 0, bufferLength);
            buffer = grown;
        }

        boolean isEmpty() {
            return pending.isEmpty();
        }

        @Nullable
        Sample peek() {
            return pending.peek();
        }

        @Nullable
        Sample poll() {
            return pending.poll();
        }

        /** Drops everything buffered before a seek — those samples belong to the old position. */
        void reset() {
            pending.clear();
            bufferLength = 0;
        }
    }

    /** Collects the extractor's tracks and picks the first audio one to capture. */
    private static final class AudioSink implements ExtractorOutput {
        @Nullable AudioTrack audioTrack;
        @Nullable SeekMap seekMap;
        boolean tracksEnded;

        private final List<AudioTrack> tracks = new ArrayList<>();

        @Override
        public TrackOutput track(int id, @C.TrackType int type) {
            AudioTrack track = new AudioTrack();
            tracks.add(track);
            return track;
        }

        @Override
        public void endTracks() {
            for (AudioTrack track : tracks) {
                Format format = track.format;
                if (format != null && format.sampleMimeType != null
                        && format.sampleMimeType.startsWith("audio/")) {
                    // First audio track wins. Which track the user is actually *listening to* would be
                    // the better choice for a multi-audio file (a dub's dialogue does not land on the
                    // same milliseconds as the original), but that needs the player's selected track
                    // plumbed in — tracked in PENDING.md.
                    audioTrack = track;
                    track.capturing = true;
                    break;
                }
            }
            tracksEnded = true;
        }

        @Override
        public void seekMap(SeekMap seekMap) {
            this.seekMap = seekMap;
        }
    }
}
