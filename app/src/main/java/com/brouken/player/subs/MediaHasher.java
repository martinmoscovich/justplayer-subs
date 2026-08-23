package com.brouken.player.subs;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import subtitleengine.concurrent.ParallelTasks;
import subtitleengine.provider.OpenSubtitlesHash;

/**
 * Identifies the media being played, without reading it.
 *
 * <p>The OpenSubtitles hash is the file size plus the first and last 64 KB, so identifying a
 * multi-gigabyte movie costs <b>128 KB</b> — two range requests on a remote source. That is what
 * makes it usable as a cache key: the key has to be cheap enough to compute before deciding whether
 * the expensive work can be skipped.
 *
 * <p>The same hash is what OpenSubtitles matches on, so it is also the way out of today's
 * title-only, fuzzy provider search.
 */
public final class MediaHasher {

    private static final String TAG = "MediaHasher";
    // Media3's default (8s) is too short for slow debrid mirrors — same fix as
    // Media3EmbeddedSubtitleProvider's HTTP_TIMEOUT_MS, and the same failure mode: a timeout here
    // doesn't just fail silently, it drops the cache key entirely (hash() returns null), so every
    // later extraction/translation for this media runs uncached even when a prior run already
    // cached its result under the real hash.
    private static final int HTTP_TIMEOUT_MS = 30_000;

    private MediaHasher() {
    }

    /**
     * @return the hex hash of the media, or {@code null} when it cannot be determined (unknown
     *         length, unreadable source) — the caller then goes on without a cache rather than fail.
     */
    @Nullable
    public static String hash(Context context, Uri uri, @Nullable Map<String, String> headers) {
        String[] both = hashAndSize(context, uri, headers);
        return both == null ? null : both[0];
    }

    /** @return {@code [hexHash, sizeBytes]}, or {@code null} — OpenSubtitles matches on both. */
    @Nullable
    public static String[] hashAndSize(Context context, Uri uri, @Nullable Map<String, String> headers) {
        DataSource headSource = Media3ExtractorSource.createDataSource(context, headers, uri);
        DataSource tailSource = null;
        try {
            long size = headSource.open(new DataSpec.Builder().setUri(uri).build());
            if (size == C.LENGTH_UNSET || size <= 0) {
                // A live stream or a server without Content-Length has no stable identity anyway.
                Log.i(TAG, "media length unknown — no hash, so no cache for this source");
                return null;
            }
            int chunk = (int) Math.min(OpenSubtitlesHash.CHUNK_BYTES, size);

            byte[] head;
            byte[] tail = null;
            if (size <= OpenSubtitlesHash.CHUNK_BYTES) {
                head = readFully(headSource, chunk);
            } else {
                // Head's body and the tail range are independent once `size` is known, so they run
                // concurrently instead of head-then-tail. On the debrid mirrors this reads from, every
                // response comes back "Connection: close" (confirmed with curl -v) — there is no
                // keep-alive connection for a sequential fetch to reuse, so each is its own TCP+TLS
                // handshake regardless of order. Running them together turns
                // time(head) + time(tail) into max(time(head), time(tail)).
                tailSource = Media3ExtractorSource.createDataSource(context, headers, uri);
                DataSource finalTailSource = tailSource;
                List<Callable<byte[]>> tasks = List.of(
                        () -> readFully(headSource, chunk),
                        () -> {
                            finalTailSource.open(new DataSpec.Builder().setUri(uri)
                                    .setPosition(size - OpenSubtitlesHash.CHUNK_BYTES)
                                    .setLength(OpenSubtitlesHash.CHUNK_BYTES)
                                    .build());
                            return readFully(finalTailSource, OpenSubtitlesHash.CHUNK_BYTES);
                        });
                try {
                    List<byte[]> chunks = ParallelTasks.run("media-hasher", tasks);
                    head = chunks.get(0);
                    tail = chunks.get(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("hash interrupted", e);
                } catch (ExecutionException e) {
                    throw new IOException("head/tail fetch failed", e.getCause());
                }
            }
            String[] hashAndSize = OpenSubtitlesHash.fromChunks(size, head, tail);
            Log.i(TAG, "media hash " + hashAndSize[0] + " (size " + size + ")");
            return hashAndSize;
        } catch (Exception e) {
            Log.w(TAG, "could not hash the media — continuing without cache", e);
            return null;
        } finally {
            try { headSource.close(); } catch (Exception ignored) { }
            if (tailSource != null) {
                try { tailSource.close(); } catch (Exception ignored) { }
            }
        }
    }

    private static byte[] readFully(DataSource source, int length) throws IOException {
        byte[] buf = new byte[length];
        int read = 0;
        while (read < length) {
            int n = source.read(buf, read, length - read);
            if (n == C.RESULT_END_OF_INPUT) break;
            read += n;
        }
        if (read < length) {
            // Short read: hashing a partial chunk would produce a stable but wrong id, and a wrong
            // id is worse than none — it would collide across different files.
            throw new IOException("expected " + length + " bytes, got " + read);
        }
        return buf;
    }
}
