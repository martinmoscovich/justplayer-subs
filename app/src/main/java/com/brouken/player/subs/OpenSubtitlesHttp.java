package com.brouken.player.subs;

import android.util.Log;

import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The one {@link OkHttpClient} every OpenSubtitles request goes through, plus a way to open its
 * connection before anything needs it.
 *
 * <p>Each search used to build its own client, which meant its own connection pool, thrown away
 * immediately after. Measured on the emulator: with a fresh client the first language group took
 * <b>851ms</b> and the second, reusing the pooled connection, took <b>163ms</b> — for identical work.
 * Roughly 85% of that first group was DNS plus the TLS handshake, and it was paid again on every
 * video opened.
 *
 * <p>{@link #warmUp()} pays it earlier instead, in a window that is already idle for this host: the
 * search cannot start until the media hash is ready, and the hash comes from downloading 128 KB of
 * the <em>media</em>, from a different server. Measured 1455ms between the activity starting and the
 * first OpenSubtitles request — the handshake fits inside it with room to spare, and on a slow link
 * that window only gets wider. It is fire-and-forget on purpose: if it fails there is nothing to
 * report, because the real request will open its own connection exactly as it always did.
 */
final class OpenSubtitlesHttp {

    private static final String TAG = "OpenSubtitlesHttp";

    /** Host to open the connection against — the API's own, so the pooled connection is the one the
     *  searches will actually reuse. Kept in step with {@code OpenSubtitlesProvider}'s default. */
    private static final String WARM_UP_URL = "https://api.opensubtitles.com/api/v1/infos/languages";

    /** Two: a search runs at most two strategies at once (see {@code OpenSubtitlesProvider.search}),
     *  and the two language groups run one after the other, so nothing ever needs a third. */
    private static final int MAX_IDLE_CONNECTIONS = 2;
    private static final long KEEP_ALIVE_MINUTES = 5;

    private static volatile OkHttpClient client;

    private OpenSubtitlesHttp() { }

    static OkHttpClient client() {
        OkHttpClient local = client;
        if (local == null) {
            synchronized (OpenSubtitlesHttp.class) {
                local = client;
                if (local == null) {
                    local = new OkHttpClient.Builder()
                            .connectionPool(new ConnectionPool(
                                    MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
                            .build();
                    client = local;
                }
            }
        }
        return local;
    }

    /**
     * Opens the connection to the API now, so the first real search finds it in the pool. Safe to
     * call more than once and from any thread; a call that fails, times out, or gets a 4xx has still
     * done its job, because what is being kept is the socket, not the answer.
     */
    static void warmUp() {
        try {
            client().newCall(new Request.Builder().url(WARM_UP_URL).build())
                    .enqueue(new Callback() {
                        @Override public void onFailure(Call call, java.io.IOException e) {
                            // Not worth surfacing: the search will open its own connection.
                            Log.d(TAG, "warm-up failed, search will connect on its own: " + e.getMessage());
                        }

                        @Override public void onResponse(Call call, Response response) {
                            // The body is irrelevant — closing it is what returns the connection to
                            // the pool instead of leaking it.
                            response.close();
                        }
                    });
        } catch (Exception e) {
            Log.d(TAG, "warm-up could not start: " + e);
        }
    }
}
