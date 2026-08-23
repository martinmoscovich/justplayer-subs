package com.brouken.player.subs;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

import subtitleengine.concurrent.WindowedChunkFetcher;

/**
 * A Media3 {@link DataSource} that fetches its bytes through several concurrent Range-GET connections
 * instead of one sequential stream, for the same reason {@link MediaHasher} already parallelizes its
 * head/tail fetch: measured against a real debrid mirror, a single connection only reaches its real
 * throughput once past TCP's slow-start ramp-up (short, few-second bursts badly underestimate it), and
 * the mirror sends {@code Connection: close} on every response — there is no keep-alive connection to
 * warm up ahead of time, only running requests concurrently actually helps.
 *
 * <p>All the pipeline bookkeeping (window depth, chunk ordering, cancellation) lives in the
 * platform-agnostic {@link WindowedChunkFetcher}; this class is only the Media3/network glue —
 * learning the range's length, and fetching one chunk the same way {@link MediaHasher} fetches its
 * head/tail (a fresh {@link Media3ExtractorSource#createDataSource} per chunk, read fully, closed).
 *
 * <p>Implements {@link DataSource} directly (not a narrower wrapper) so that
 * {@link Media3ExtractorSource#openAt} — which already treats its {@code DataSource} purely through
 * the interface, doing {@code close()} then a fresh {@code open()} at a new position — drives a seek
 * here for free: abandon the window, restart clean at the new position, exactly what a seek already
 * means for this class.
 */
final class ParallelChunkedDataSource implements DataSource {

    private static final String TAG = "ParallelChunkedSource";

    // ~5MB/~2.4s bursts measured against a real debrid mirror stayed in TCP slow-start and badly
    // underestimated a connection's real throughput; ~30MB/~3-5s bursts reached the true steady-state
    // rate. A chunk has to be big enough that its own transfer clears slow-start, or "N concurrent
    // connections" degenerates into "N connections each measured mid-ramp-up".
    static final int DEFAULT_CHUNK_SIZE_BYTES = 4 * 1024 * 1024; // 4 MiB
    // Matches today's measured configuration (4 connections, 2.6-3.3x on a real mirror). K * chunk
    // size is the memory ceiling (16 MiB default) — bounded regardless of how large the range is.
    // Provisional: measured from a dev PC, not the target device — retune once verified on-device.
    static final int DEFAULT_WINDOW_SIZE = 4;

    // Deliberately much shorter than Media3ExtractorSource's shared 30s default. A seek abandons the
    // whole window (frequent in Matroska -- SeekHead/Cues jumps happen throughout, not just at the
    // start), and a chunk stuck on a blocking network read at that moment can't safely be closed from
    // outside its own thread: tried exactly that (tracking each chunk's DataSource and force-closing it
    // from the window's close()) and it crashed live with "IllegalStateException: Unbalanced enter/exit"
    // -- OkHttp's AsyncTimeout, which the underlying DefaultHttpDataSource uses, isn't safe to enter()
    // from two threads (the reader mid-read, the closer) on the same tracker at once. So instead of
    // reaching in from outside, a stuck chunk is left to time out **on its own thread**, just much
    // sooner: real chunk fetches measured 1.8-4.3s end to end, so 10s leaves real margin while bounding
    // how long an abandoned chunk (holding a 4MB buffer and an open socket) can outlive the window that
    // abandoned it -- was 30s, confirmed live to pile up faster than that across frequent seeks and end
    // in an OutOfMemoryError.
    private static final int CHUNK_TIMEOUT_MS = 10_000;

    private final Context context;
    @Nullable private final Map<String, String> headers;
    private final Uri uri;

    @Nullable private WindowedChunkFetcher window;
    @Nullable private DataSource passthrough; // used only when the source has no reliable Content-Length

    ParallelChunkedDataSource(Context context, @Nullable Map<String, String> headers, Uri uri) {
        this.context = context;
        this.headers = headers;
        this.uri = uri;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        // One lightweight probe: open (learns the remaining length from the response headers) then
        // close WITHOUT ever reading — no body bytes are transferred, so this costs one handshake, not
        // a real chunk fetch. Every chunk (including what would be "chunk 0") is then fetched
        // uniformly through WindowedChunkFetcher, the same as every other chunk — a version that
        // reused this probe connection as chunk 0 saves one handshake but makes chunk 0 asymmetric
        // with the rest for one connection's worth of saving on what is usually a multi-hundred-MB
        // read; not worth it for v1.
        DataSource probe = Media3ExtractorSource.createDataSource(context, headers, uri);
        long remaining;
        try {
            remaining = probe.open(new DataSpec.Builder().setUri(uri).setPosition(dataSpec.position).build());
        } finally {
            probe.close();
        }

        if (remaining == C.LENGTH_UNSET || remaining <= 0) {
            // No reliable Content-Length (a live stream, or a non-conforming server) — degrade to a
            // single plain sequential source instead of failing; behaviorally identical to today.
            passthrough = Media3ExtractorSource.createDataSource(context, headers, uri);
            return passthrough.open(new DataSpec.Builder().setUri(uri).setPosition(dataSpec.position).build());
        }

        Log.i(TAG, "opening parallel window: range=" + remaining + "B  chunk=" + DEFAULT_CHUNK_SIZE_BYTES
                + "B  window=" + DEFAULT_WINDOW_SIZE);
        window = new WindowedChunkFetcher(this::fetchChunk, dataSpec.position, dataSpec.position + remaining,
                DEFAULT_CHUNK_SIZE_BYTES, DEFAULT_WINDOW_SIZE, "subtitle-window");
        return remaining;
    }

    // Writes to `sink` as bytes arrive from the network (not all at once at the end) — that's what
    // lets WindowedChunkFetcher hand a chunk's data to the reader progressively instead of making it
    // wait for the whole chunk. Logged with start/done pairs so overlapping timestamps in logcat are
    // the proof concurrency actually happened, not just that it didn't crash.
    private void fetchChunk(long start, int length, OutputStream sink) throws IOException {
        long t0 = System.currentTimeMillis();
        Log.d(TAG, "chunk start=" + start + " len=" + length + " -- fetch begin");
        DataSource chunkSource = Media3ExtractorSource.createDataSource(context, headers, uri, CHUNK_TIMEOUT_MS);
        try {
            chunkSource.open(new DataSpec.Builder().setUri(uri).setPosition(start).setLength(length).build());
            byte[] buf = new byte[64 * 1024];
            int total = 0;
            while (total < length) {
                int n = chunkSource.read(buf, 0, Math.min(buf.length, length - total));
                if (n == C.RESULT_END_OF_INPUT) break;
                sink.write(buf, 0, n);
                total += n;
            }
            if (total < length) {
                throw new IOException("expected " + length + " bytes, got " + total);
            }
            Log.d(TAG, "chunk start=" + start + " -- fetch done in " + (System.currentTimeMillis() - t0) + "ms");
        } catch (IOException e) {
            Log.w(TAG, "chunk start=" + start + " len=" + length + " -- fetch FAILED after "
                    + (System.currentTimeMillis() - t0) + "ms: " + e);
            throw e;
        } finally {
            // Same-thread close, always safe -- see CHUNK_TIMEOUT_MS's javadoc for why this is
            // deliberately NOT also attempted from close() on another thread.
            try { chunkSource.close(); } catch (IOException ignored) { }
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        return passthrough != null ? passthrough.read(buffer, offset, length) : window.read(buffer, offset, length);
    }

    @Override
    public void close() throws IOException {
        // Deliberately does NOT reach into any still-running chunk fetch's DataSource from here --
        // see CHUNK_TIMEOUT_MS's javadoc for why that crashed live. window.close() still interrupts
        // and cancels every queued/in-flight Future; a chunk that ignores the interrupt (stuck in a
        // blocking read) is bounded by its own short timeout instead.
        if (window != null) {
            window.close();
            window = null;
        }
        if (passthrough != null) {
            passthrough.close();
            passthrough = null;
        }
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        // No existing caller of Media3ExtractorSource.createDataSource attaches one either.
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }
}
