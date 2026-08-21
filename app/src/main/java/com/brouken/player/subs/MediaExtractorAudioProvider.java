package com.brouken.player.subs;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder;
import androidx.media3.decoder.ffmpeg.FfmpegDecoderException;
import androidx.media3.decoder.ffmpeg.FfmpegLibrary;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;

import subtitleengine.audio.AudioProvider;
import subtitleengine.audio.PcmDownmixResampler;
import subtitleengine.vad.ResyncProgressListener;

/**
 * Extracts a mono 16kHz float32 PCM segment from a media source using Android's
 * MediaExtractor + MediaCodec, with its own extractor/decoder instances independent of the active
 * ExoPlayer session so it never interferes with playback.
 *
 * <p>Ported from OwnTV's {@code LibmpvAudioProvider.kt} — a reference {@link AudioProvider}
 * implementation for a sibling player, designated player-agnostic and portable "as-is" by
 * PLAN.md &sect;3 / SPEC.md &sect;5.1 (see FEATURE-B-AUTOSYNC-PLAN-V2.md, "Qu&eacute; cambia respecto
 * del plan original", point 1). Porting it surfaced five real defects, fixed here:
 * <ul>
 *   <li>The end-of-window check compared an absolute decode time to a bare duration instead of
 *       {@code startUs + durationUs}, so any {@code startSeconds > 0} cut the extraction almost
 *       immediately.</li>
 *   <li>{@code SEEK_TO_CLOSEST_SYNC} can land on a sync point <em>after</em> the requested start
 *       with nothing discarding the gap, biasing the extracted PCM (and the resulting offset) by
 *       that difference. This uses {@code SEEK_TO_PREVIOUS_SYNC} and discards decoded frames whose
 *       {@code presentationTimeUs < startUs}.</li>
 *   <li>The resample step used the container's <em>declared</em> sample rate instead of the
 *       decoder's actual output format — wrong for HE-AAC/SBR, where the decoder emits audio at
 *       2&times; the declared rate, doubling the whole timeline and making any offset meaningless.
 *       This reads the real rate from {@code INFO_OUTPUT_FORMAT_CHANGED} / {@code getOutputFormat()}.</li>
 *   <li>The decode loop had no interruption check and no bound on stalled iterations, so a decoder
 *       that never signals EOS would hang the worker thread forever (and ignore {@code cancel()}).
 *       This checks {@link Thread#isInterrupted()} every iteration and aborts after a run of
 *       iterations with no decode progress.</li>
 *   <li>The whole segment's raw 16-bit PCM was buffered in a growing byte stream before conversion
 *       (~57&ndash;115MB peak for a 300s stereo window) instead of converting incrementally. This
 *       feeds decoded buffers to {@link PcmDownmixResampler} inside the decode loop, so only the
 *       (much smaller) 16kHz mono float output accumulates — that's also what makes per-fraction
 *       {@link ResyncProgressListener.Phase#EXTRACTING} progress possible.</li>
 * </ul>
 *
 * <p>Channel downmix and resampling live in {@link PcmDownmixResampler} (engine, pure Java) rather
 * than here — that math has zero Android dependency, so per the project's golden rule it's shared
 * with the JVM CLI/test path instead of reimplemented against a platform API (see
 * {@code LESSONS.md}: a prior version duplicated a naive per-channel average here and in
 * {@code WavAudioProvider}; against real 5.1 movie audio that diluted center-channel dialogue enough
 * to make the VAD lock onto the wrong offset. Sharing one implementation means the JVM CLI now
 * exercises the exact downmix the device uses).
 *
 * <p>{@code headers} plumbs through to {@link MediaExtractor#setDataSource} for a future debrid
 * integration, but nothing fills it yet — there's no header plumbing from the launching intent
 * anywhere else in the player either (tracked as a separate, pre-existing gap; see the plan's
 * "Fuera de este slice").
 *
 * <p><b>MediaCodec-less codecs fallback.</b> Some devices have no {@link MediaCodec} decoder
 * installed for a codec at all (confirmed live on a Chromecast for AC3:
 * {@code MediaCodec.createDecoderByType("audio/ac3")} throws {@code IllegalArgumentException:
 * NAME_NOT_FOUND} — real playback still works because it passes the bitstream through to the TV/AVR
 * over HDMI, which never touches this class). When that happens and {@link FfmpegLibrary#supportsFormat}
 * says the FFmpeg decoder already bundled with this fork ({@code app/libs/lib-decoder-ffmpeg-release.aar}
 * — see its {@code README.md} for the exact decoder list: vorbis/opus/flac/alac/mp3/aac/ac3/eac3/dca
 * (DTS)/mlp/truehd/amrnb/amrwb/pcm_mulaw/pcm_alaw) covers this MIME type, this falls back to
 * {@link FfmpegAudioDecoder} — same {@link MediaExtractor} demux as always, just a different decode
 * backend. This does <em>not</em> help DTS specifically: on affected devices {@code MediaExtractor}
 * itself never exposes a DTS track at all (see the {@code UnsupportedAudioTrackException} thrown
 * below), a demuxing gap no decoder swap can fix — {@code PENDING.md} tracks that as separate, larger
 * follow-up work (swapping the demux for Media3's own {@code MatroskaExtractor}, which does recognize
 * DTS's codec IDs).
 */
public class MediaExtractorAudioProvider implements AudioProvider {

    private static final String TAG = "MediaExtractorAudioProvider";
    private static final int TARGET_RATE = 16_000;
    // ~20s of no decode progress at the 10ms dequeue timeouts below — generous enough to ride out
    // real network stalls without hanging the worker thread forever on a decoder that never EOSes.
    private static final int MAX_STALLED_ITERATIONS = 2_000;

    private final Context context;
    @Nullable private final Map<String, String> headers;

    /** See {@link AudioProvider#extractAudioSegment} — thrown instead of returning null when we can
     *  name the reason. Unchecked: {@link AudioProvider}'s signature declares no checked exceptions. */
    private static final class UnsupportedAudioTrackException extends RuntimeException {
        UnsupportedAudioTrackException(String message) {
            super(message);
        }
    }

    public MediaExtractorAudioProvider(Context context, @Nullable Map<String, String> headers) {
        this.context = context.getApplicationContext();
        this.headers = headers;
    }

    @Override
    public float[] extractAudioSegment(String source, double startSeconds, double durationSeconds,
                                        @Nullable ResyncProgressListener onProgress) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec mediaCodecDecoder = null;
        FfmpegAudioDecoder ffmpegDecoder = null;
        try {
            setDataSource(extractor, source);

            int audioTrackIndex = -1;
            Log.w(TAG, "DIAG: extractor.getTrackCount()=" + extractor.getTrackCount());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String trackMime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                Log.w(TAG, "DIAG: track " + i + " mime=" + trackMime);
                if (trackMime != null && trackMime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    break;
                }
            }
            if (audioTrackIndex < 0) {
                Log.w(TAG, "extractAudioSegment: no audio track found in " + describe(source));
                // Most commonly a codec Android's native MediaExtractor can't demux at all (DTS,
                // TrueHD, …) — it drops the track silently rather than reporting it as unsupported,
                // so this is our best diagnosis, not a certainty. Playback itself can still work
                // because Media3 decodes via its own extractor + FFmpeg extension instead of this API.
                throw new UnsupportedAudioTrackException(
                        "No supported audio track found for auto-sync — the audio codec may not be "
                                + "supported for extraction (e.g. DTS, TrueHD)");
            }

            extractor.selectTrack(audioTrackIndex);
            MediaFormat inputFormat = extractor.getTrackFormat(audioTrackIndex);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                Log.w(TAG, "extractAudioSegment: null MIME type for " + describe(source));
                return null;
            }

            long startUs = (long) (startSeconds * 1_000_000);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            try {
                mediaCodecDecoder = MediaCodec.createDecoderByType(mime);
            } catch (IllegalArgumentException noMediaCodecDecoder) {
                // No hardware/software decoder installed for this codec at all (seen live for AC3 on
                // a Chromecast) — try the bundled FFmpeg decoder before giving up. It only covers a
                // handful of codecs on purpose (see decoder-ffmpeg/README.md), so this still fails
                // for anything else exactly like before.
                if (!FfmpegLibrary.isAvailable() || !FfmpegLibrary.supportsFormat(mime)) {
                    throw new UnsupportedAudioTrackException(
                            "No decoder available for " + mime + " (not covered by MediaCodec or "
                                    + "the bundled FFmpeg decoder)");
                }
                Log.i(TAG, "extractAudioSegment: no MediaCodec decoder for " + mime
                        + ", falling back to FFmpeg for " + describe(source));
                ffmpegDecoder = createFfmpegDecoder(inputFormat, mime);
            }

            float[] result;
            if (mediaCodecDecoder != null) {
                mediaCodecDecoder.configure(inputFormat, null, null, 0);
                mediaCodecDecoder.start();
                result = decodeAndResampleMediaCodec(extractor, mediaCodecDecoder, startUs, durationSeconds, onProgress);
            } else {
                result = decodeAndResampleFfmpeg(extractor, ffmpegDecoder, startUs, durationSeconds, onProgress);
            }
            // Requested vs delivered: a short window silently starves the resyncer's evidence gate,
            // which then reports "no confident match" with no hint that it was fed less than it asked
            // for. Decode can end early (EOS, stall timeout) and the seek lands on the previous sync
            // sample, so neither the length nor the start is guaranteed to be what we asked for.
            double deliveredSeconds = result == null ? 0.0 : result.length / 16_000.0;
            Log.i(TAG, String.format(java.util.Locale.US,
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
            if (mediaCodecDecoder != null) {
                try { mediaCodecDecoder.stop(); } catch (Exception ignored) { }
                try { mediaCodecDecoder.release(); } catch (Exception ignored) { }
            }
            if (ffmpegDecoder != null) {
                try { ffmpegDecoder.release(); } catch (Exception ignored) { }
            }
            try { extractor.release(); } catch (Exception ignored) { }
        }
    }

    /**
     * ac3/eac3/dca (the codecs the Chromecast gap is actually about) need no codec-specific init data
     * — {@code FfmpegAudioDecoder.getExtraData} falls through to {@code null} for them, since the
     * elementary stream self-describes sample rate/channels in each frame's header. Other codecs the
     * bundled decoder also covers (aac, opus, vorbis, alac, flac) do need it, sourced from the
     * container's {@code csd-N} buffers — plumbed through here so the fallback is correct for all of
     * them, not just the two this class was written for, even though MediaCodec lacking a decoder for
     * one of those (near-universal on real devices) would be a very unusual thing to hit in practice.
     */
    private FfmpegAudioDecoder createFfmpegDecoder(MediaFormat inputFormat, String mime) throws FfmpegDecoderException {
        Format format = new Format.Builder()
                .setSampleMimeType(mime)
                .setSampleRate(inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                .setChannelCount(inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                .setInitializationData(codecSpecificData(inputFormat))
                .build();
        return new FfmpegAudioDecoder(
                format, /* numInputBuffers= */ 2, /* numOutputBuffers= */ 2,
                /* initialInputBufferSize= */ 256 * 1024, /* outputFloat= */ false);
    }

    /** Collects {@code csd-0}, {@code csd-1}, ... from the container's format, in order — empty for a
     *  codec (like ac3/eac3/dca) whose format carries none. */
    private static java.util.List<byte[]> codecSpecificData(MediaFormat format) {
        java.util.List<byte[]> data = new java.util.ArrayList<>();
        for (int i = 0; ; i++) {
            ByteBuffer csd = format.getByteBuffer("csd-" + i);
            if (csd == null) break;
            byte[] bytes = new byte[csd.remaining()];
            csd.duplicate().get(bytes); // duplicate: never consume the format's own buffer
            data.add(bytes);
        }
        return data;
    }

    private void setDataSource(MediaExtractor extractor, String source) throws java.io.IOException {
        Uri uri = Uri.parse(source);
        String scheme = uri.getScheme();
        if ("content".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme)) {
            extractor.setDataSource(context, uri, headers);
        } else if (scheme != null && scheme.toLowerCase(Locale.ROOT).startsWith("http")) {
            extractor.setDataSource(source, headers);
        } else {
            extractor.setDataSource(source);
        }
    }

    /**
     * Decodes from the extractor's current (seeked) position up to {@code startUs + durationUs} or
     * EOS, discards output before {@code startUs}, and downmixes+resamples to mono 16kHz
     * incrementally. Returns {@code null} on interruption or a stalled decoder; an empty array is a
     * valid "no audio in range" result, not a failure (the caller's PCM-core check rejects it).
     */
    @Nullable
    private float[] decodeAndResampleMediaCodec(MediaExtractor extractor, MediaCodec decoder, long startUs,
                                       double durationSeconds, @Nullable ResyncProgressListener onProgress) {
        long endUs = startUs + (long) (durationSeconds * 1_000_000);
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        int staleIterations = 0;
        PcmDownmixResampler acc = null; // created once the real decoder output format is known

        while (!outputDone) {
            if (Thread.currentThread().isInterrupted()) {
                Log.w(TAG, "decodeAndResampleMediaCodec: interrupted, aborting extraction");
                return null;
            }
            boolean progressed = false;

            if (!inputDone) {
                int inputIndex = decoder.dequeueInputBuffer(10_000);
                if (inputIndex >= 0) {
                    progressed = true;
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                    int sampleSize = inputBuffer != null ? extractor.readSampleData(inputBuffer, 0) : -1;
                    if (sampleSize < 0 || extractor.getSampleTime() > endUs) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000);
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                progressed = true;
                MediaFormat outFormat = decoder.getOutputFormat();
                acc = new PcmDownmixResampler(
                        outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                        TARGET_RATE);
            } else if (outputIndex >= 0) {
                progressed = true;
                if (bufferInfo.size > 0 && bufferInfo.presentationTimeUs >= startUs) {
                    ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
                    if (outputBuffer != null) {
                        if (acc == null) {
                            // Some decoders never emit INFO_OUTPUT_FORMAT_CHANGED before the first
                            // buffer — fall back to querying the format directly.
                            MediaFormat fallback = decoder.getOutputFormat();
                            acc = new PcmDownmixResampler(
                                    fallback.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                    fallback.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                                    TARGET_RATE);
                        }
                        acc.append(outputBuffer, bufferInfo.size);
                    }
                    if (onProgress != null && durationSeconds > 0) {
                        double coveredUs = Math.max(0, bufferInfo.presentationTimeUs - startUs);
                        double fraction = Math.max(0.0, Math.min(1.0, coveredUs / (durationSeconds * 1_000_000)));
                        onProgress.onProgress(ResyncProgressListener.Phase.EXTRACTING, fraction);
                    }
                }
                decoder.releaseOutputBuffer(outputIndex, false);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
            }

            if (progressed) {
                staleIterations = 0;
            } else if (++staleIterations > MAX_STALLED_ITERATIONS) {
                Log.w(TAG, "decodeAndResampleMediaCodec: decoder made no progress for " + MAX_STALLED_ITERATIONS
                        + " iterations, aborting instead of hanging forever");
                return null;
            }
        }

        return acc != null ? acc.finish() : new float[0];
    }

    /**
     * Same shape as {@link #decodeAndResampleMediaCodec}, against {@link FfmpegAudioDecoder}'s
     * {@link androidx.media3.decoder.Decoder} API instead of {@link MediaCodec}'s buffer-index one —
     * {@code dequeueInputBuffer()}/{@code dequeueOutputBuffer()} return the buffer object directly
     * (or {@code null} if none is available yet) rather than an index, and there's no dequeue
     * timeout to lean on, so the stall/interruption bookkeeping below does the same job the 10ms
     * {@code dequeueOutputBuffer(bufferInfo, 10_000)} timeout does in the MediaCodec path.
     */
    @Nullable
    private float[] decodeAndResampleFfmpeg(MediaExtractor extractor, FfmpegAudioDecoder decoder, long startUs,
                                            double durationSeconds, @Nullable ResyncProgressListener onProgress)
            throws FfmpegDecoderException {
        long endUs = startUs + (long) (durationSeconds * 1_000_000);
        boolean inputDone = false;
        boolean outputDone = false;
        int staleIterations = 0;
        PcmDownmixResampler acc = null; // created once the real decoder output format is known

        while (!outputDone) {
            if (Thread.currentThread().isInterrupted()) {
                Log.w(TAG, "decodeAndResampleFfmpeg: interrupted, aborting extraction");
                return null;
            }
            boolean progressed = false;

            if (!inputDone) {
                DecoderInputBuffer inputBuffer = decoder.dequeueInputBuffer();
                if (inputBuffer != null) {
                    progressed = true;
                    // Direct-mode buffer replacement grows this for us if a frame is ever bigger —
                    // 256KB is already generous for a single AC3/E-AC3 frame, so in practice this
                    // never reallocates past the first call.
                    inputBuffer.ensureSpaceForWrite(256 * 1024);
                    ByteBuffer data = inputBuffer.data;
                    int sampleSize = data != null ? extractor.readSampleData(data, 0) : -1;
                    if (sampleSize < 0 || extractor.getSampleTime() > endUs) {
                        inputBuffer.timeUs = 0;
                        inputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
                        decoder.queueInputBuffer(inputBuffer);
                        inputDone = true;
                    } else {
                        // FfmpegAudioDecoder reads the sample size off data.limit(), not a separate
                        // offset/size pair (unlike MediaCodec.queueInputBuffer) — set both explicitly
                        // rather than relying on whatever position/limit readSampleData happened to
                        // leave behind.
                        data.position(0);
                        data.limit(sampleSize);
                        inputBuffer.timeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputBuffer);
                        extractor.advance();
                    }
                }
            }

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
                        if (onProgress != null && durationSeconds > 0) {
                            double coveredUs = Math.max(0, outputBuffer.timeUs - startUs);
                            double fraction = Math.max(0.0, Math.min(1.0, coveredUs / (durationSeconds * 1_000_000)));
                            onProgress.onProgress(ResyncProgressListener.Phase.EXTRACTING, fraction);
                        }
                    }
                    outputBuffer.release();
                }
            }

            if (progressed) {
                staleIterations = 0;
            } else if (++staleIterations > MAX_STALLED_ITERATIONS) {
                Log.w(TAG, "decodeAndResampleFfmpeg: decoder made no progress for " + MAX_STALLED_ITERATIONS
                        + " iterations, aborting instead of hanging forever");
                return null;
            } else {
                // Unlike MediaCodec.dequeueOutputBuffer(bufferInfo, 10_000), FfmpegAudioDecoder's
                // dequeue calls never block — without this, an iteration with nothing to do yet
                // (waiting on the decode thread) would spin the CPU and burn through
                // MAX_STALLED_ITERATIONS in microseconds instead of the ~20s it's meant to allow.
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

    /** Truncated, never the full source string — media URLs can carry auth tokens in the query. */
    private static String describe(String source) {
        return source.length() <= 60 ? source : "…" + source.substring(source.length() - 60);
    }
}
