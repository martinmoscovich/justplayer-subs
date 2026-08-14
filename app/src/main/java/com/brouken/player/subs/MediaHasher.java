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
import java.util.Map;

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
        DataSource dataSource = build(context, headers);
        try {
            long size = dataSource.open(new DataSpec.Builder().setUri(uri).build());
            if (size == C.LENGTH_UNSET || size <= 0) {
                // A live stream or a server without Content-Length has no stable identity anyway.
                Log.i(TAG, "media length unknown — no hash, so no cache for this source");
                return null;
            }
            int chunk = (int) Math.min(OpenSubtitlesHash.CHUNK_BYTES, size);
            byte[] head = readFully(dataSource, chunk);
            byte[] tail = null;
            if (size > OpenSubtitlesHash.CHUNK_BYTES) {
                dataSource.close();
                dataSource.open(new DataSpec.Builder().setUri(uri)
                        .setPosition(size - OpenSubtitlesHash.CHUNK_BYTES)
                        .setLength(OpenSubtitlesHash.CHUNK_BYTES)
                        .build());
                tail = readFully(dataSource, OpenSubtitlesHash.CHUNK_BYTES);
            }
            String[] hashAndSize = OpenSubtitlesHash.fromChunks(size, head, tail);
            Log.i(TAG, "media hash " + hashAndSize[0] + " (size " + size + ")");
            return hashAndSize;
        } catch (Exception e) {
            Log.w(TAG, "could not hash the media — continuing without cache", e);
            return null;
        } finally {
            try { dataSource.close(); } catch (Exception ignored) { }
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

    private static DataSource build(Context context, @Nullable Map<String, String> headers) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true);
        if (headers != null && !headers.isEmpty()) http.setDefaultRequestProperties(headers);
        return new DefaultDataSource.Factory(context, http).createDataSource();
    }
}
