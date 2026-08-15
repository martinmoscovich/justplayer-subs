package com.brouken.player.subs.qr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Real sockets against 127.0.0.1, no mocking — {@link QrSetupServer} is plain Java on purpose so this
 * can run as a fast local unit test instead of needing an emulator or Robolectric.
 */
class QrSetupServerTest {

    private static final String TOKEN = "test-token";

    private QrSetupServer server;

    @AfterEach void tearDown() {
        if (server != null) server.stop();
    }

    private static QrSetupServer newServer(QrSetupServer.Clock clock, long ttlMs, QrSetupServer.Listener listener) {
        return new QrSetupServer(TOKEN, "<html>form</html>", () -> "{\"languages\":[]}", listener, ttlMs, clock);
    }

    @Test void aSuccessfulPostRespondsAndEventuallyStopsTheServer() throws Exception {
        MutableClock clock = new MutableClock(0L);
        AtomicReference<Map<String, String>> received = new AtomicReference<>();
        server = newServer(clock, TimeUnit.MINUTES.toMillis(5), received::set);
        server.start();
        int port = server.getListeningPort();

        assertEquals(200, post(port, "?t=" + TOKEN, "opensubtitles_api_key=abc123"));
        assertEquals("abc123", received.get().get("opensubtitles_api_key"));

        assertTrue(waitUntil(() -> !server.isAlive()), "server must stop itself after a successful POST");
        assertThrows(ConnectException.class, () -> get(port, "?t=" + TOKEN),
                "the socket must actually close, not just start rejecting the used token");
    }

    @Test void aRequestWithTheWrongTokenIsRejectedAndNothingIsStopped() throws Exception {
        MutableClock clock = new MutableClock(0L);
        server = newServer(clock, TimeUnit.MINUTES.toMillis(5), fields -> fail("must not be called"));
        server.start();

        assertEquals(403, get(server.getListeningPort(), "?t=wrong"));
        assertTrue(server.isAlive());
    }

    @Test void aRequestWithNoTokenAtAllIsRejected() throws Exception {
        MutableClock clock = new MutableClock(0L);
        server = newServer(clock, TimeUnit.MINUTES.toMillis(5), fields -> fail("must not be called"));
        server.start();

        assertEquals(403, get(server.getListeningPort(), ""));
    }

    @Test void tickPastTheDeadlineStopsTheServerWithoutSleeping() throws Exception {
        MutableClock clock = new MutableClock(0L);
        server = newServer(clock, TimeUnit.MINUTES.toMillis(5), fields -> {});
        server.start();
        assertTrue(server.isAlive());

        clock.set(TimeUnit.MINUTES.toMillis(5) + 1);
        server.tick();

        assertFalse(server.isAlive());
    }

    /** Belt-and-suspenders: serve() itself rejects a stale request even before tick() has run. */
    @Test void aRequestPastTheDeadlineIsRejectedEvenBeforeTick() throws Exception {
        MutableClock clock = new MutableClock(0L);
        server = newServer(clock, TimeUnit.MINUTES.toMillis(5), fields -> fail("must not be called"));
        server.start();

        clock.set(TimeUnit.MINUTES.toMillis(5) + 1);
        assertEquals(403, get(server.getListeningPort(), "?t=" + TOKEN));
        assertTrue(server.isAlive(), "serve() rejects the request, tick() is what actually stops it");
    }

    // --- test double ---

    private static final class MutableClock implements QrSetupServer.Clock {
        private volatile long now;
        MutableClock(long start) { this.now = start; }
        void set(long v) { now = v; }
        @Override public long nowMs() { return now; }
    }

    // --- plain HTTP helpers ---

    private static int get(int port, String query) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/" + query).openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(2000);
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    private static int post(int port, String query, String formBody) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/" + query).openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(2000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] payload = formBody.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(payload.length);
        try (OutputStream out = c.getOutputStream()) {
            out.write(payload);
        }
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    /** Polls instead of a fixed sleep — the server's self-stop runs on its own short-lived thread. */
    private static boolean waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }
}
