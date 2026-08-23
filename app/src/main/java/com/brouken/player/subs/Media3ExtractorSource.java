package com.brouken.player.subs;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The mechanical half of reading a media container with Media3's extractors: building the
 * {@link DataSource}, picking the {@link Extractor} that recognises the container, and repositioning
 * the source at a byte offset.
 *
 * <p>Shared by {@link Media3AudioProvider} (PCM for auto-sync) and
 * {@link Media3EmbeddedSubtitleProvider} (cues from an embedded track). Both need exactly this
 * sequence and nothing about it is specific to audio or subtitles, so it lives here once. What
 * deliberately stays in each caller is the <em>pump loop</em> — when to stop, what to do with the
 * samples, how to report progress — because those differ in substance, and folding them together
 * would need callbacks that obscure both.
 *
 * <p>This was extracted after the two classes had already drifted: one set 30s HTTP timeouts and the
 * other took Media3's much shorter defaults, purely because the second was written without reading
 * the first. That is the failure mode this exists to prevent.
 */
final class Media3ExtractorSource {

    /** Media3's own default (8s) is tuned for a single request, not walking a remote container
     *  through a slow debrid mirror — real-world timeouts observed at 0% progress well before 8s
     *  worth of data could plausibly have arrived. */
    private static final int HTTP_TIMEOUT_MS = 30_000;

    private Media3ExtractorSource() { }

    /**
     * Data source for any scheme the player accepts — http(s), file, content, asset.
     *
     * <p>Carries everything playback would carry for the same URL, because it opens the same URL:
     * the launching intent's headers, and basic auth derived from the URI's own user info. Playback
     * builds that pair in {@code PlayerActivity}; extraction used to open the media bare, so a source
     * that played fine could still fail to auto-sync or to read its embedded subtitles. User info
     * wins over an intent header of the same name, the same precedence playback uses — it is the more
     * specific of the two for this exact URI.
     *
     * <p>These are the <em>media</em> credentials and go only to media requests. OpenSubtitles and the
     * AI providers authenticate on their own terms and must never see them.
     *
     * <p>{@code User-Agent} is pulled out and set through its own setter rather than left among the
     * request properties: {@link DefaultHttpDataSource} treats it as a first-class field, and how it
     * merges a request property of the same name is not something to depend on.
     */
    static DataSource createDataSource(Context context, @Nullable Map<String, String> headers,
                                       @Nullable Uri mediaUri) {
        return createDataSource(context, headers, mediaUri, HTTP_TIMEOUT_MS);
    }

    /**
     * Same as {@link #createDataSource(Context, Map, Uri)}, with an explicit connect/read timeout
     * instead of the shared 30s default — see {@link ParallelChunkedDataSource} for why a chunk fetch
     * wants a much shorter one than a single long sequential read does.
     */
    static DataSource createDataSource(Context context, @Nullable Map<String, String> headers,
                                       @Nullable Uri mediaUri, int timeoutMs) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(timeoutMs)
                .setReadTimeoutMs(timeoutMs)
                // Debrid links redirect between http and https; without this the read dies on the
                // first redirect while playback (which sets it elsewhere) carries on fine.
                .setAllowCrossProtocolRedirects(true);

        // Copy: the caller's map is theirs, and neither extracting User-Agent nor adding basic auth
        // may mutate it.
        Map<String, String> requestProperties =
                headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);

        String userAgent = removeIgnoreCase(requestProperties, "User-Agent");
        if (userAgent != null) {
            http.setUserAgent(userAgent);
        }

        String userInfo = mediaUri == null ? null : mediaUri.getUserInfo();
        if (userInfo != null && !userInfo.isEmpty() && userInfo.contains(":")) {
            removeIgnoreCase(requestProperties, "Authorization");
            requestProperties.put("Authorization",
                    "Basic " + Base64.encodeToString(userInfo.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        }

        if (!requestProperties.isEmpty()) {
            http.setDefaultRequestProperties(requestProperties);
        }
        return new DefaultDataSource.Factory(context, http).createDataSource();
    }

    /**
     * Same headers/auth/redirect handling as {@link #createDataSource}, but fetches its bytes through
     * several concurrent Range-GET connections instead of one sequential stream — see
     * {@link ParallelChunkedDataSource} for why and the measurements behind it. Drop-in: satisfies the
     * same {@link DataSource} contract, including {@link #openAt}'s close-then-reopen-at-a-new-position
     * seek handling, which this needs unchanged to behave correctly.
     */
    static DataSource createParallelDataSource(Context context, @Nullable Map<String, String> headers,
                                                @Nullable Uri mediaUri) {
        return new ParallelChunkedDataSource(context, headers, mediaUri);
    }

    /** Header names are case-insensitive (RFC 9110 §5.1), so an intent may spell it "user-agent". */
    @Nullable
    private static String removeIgnoreCase(Map<String, String> map, String key) {
        for (Iterator<Map.Entry<String, String>> it = map.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, String> entry = it.next();
            if (key.equalsIgnoreCase(entry.getKey())) {
                String value = entry.getValue();
                it.remove();
                return value;
            }
        }
        return null;
    }

    /**
     * (Re)opens {@code dataSource} at an absolute byte position and wraps it in an input positioned
     * there. Media3 extractors ask for repositioning by byte offset ({@link Extractor#RESULT_SEEK}
     * and {@code SeekMap}), and a {@link DataSource} cannot rewind, so each jump is a close+open.
     * Also serves the initial open, at position 0.
     *
     * <p>{@link ExtractorInput#getLength()} on the result is the <em>absolute</em> stream length
     * (or {@link C#LENGTH_UNSET}), not the bytes remaining from {@code position} — callers tracking
     * total size should read it from there rather than from {@code open()}'s return value.
     */
    static ExtractorInput openAt(DataSource dataSource, Uri uri, long position) throws IOException {
        try { dataSource.close(); } catch (IOException ignored) { }
        long remaining = dataSource.open(
                new DataSpec.Builder().setUri(uri).setPosition(position).build());
        long length = remaining == C.LENGTH_UNSET ? C.LENGTH_UNSET : position + remaining;
        return new DefaultExtractorInput(dataSource, position, length);
    }

    /**
     * Sniffs the container against every known extractor, rewinding the peek position between tries.
     * Returns {@code null} when nothing recognises it; the input is left untouched either way, so the
     * winner can read from the start.
     */
    @Nullable
    static Extractor sniff(ExtractorInput input, Uri uri) throws IOException {
        for (Extractor candidate : new DefaultExtractorsFactory().createExtractors(uri, new HashMap<>())) {
            try {
                if (candidate.sniff(input)) return candidate;
            } catch (EOFException ignored) {
                // Ran out of bytes while peeking: that is a "no", not a read failure. Any other
                // IOException is a real problem and propagates.
            } finally {
                input.resetPeekPosition();
            }
        }
        return null;
    }
}
