package com.brouken.player.subs.qr;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import fi.iki.elonen.NanoHTTPD;

/**
 * The local HTTP server behind the QR setup flow. Pure Java, no Android imports, so it can be driven
 * with real sockets against {@code localhost} in a plain JUnit test — everything it needs from the
 * Android side (the HTML, the dynamic config JSON, and what to do with a submitted form) is injected
 * rather than read directly, the same way {@code SubtitleCache} takes a language {@code Supplier}
 * instead of reading {@code SubtitleSettings} itself.
 *
 * <p>Single-use and time-boxed on purpose: a successful POST schedules a shutdown, and {@link #tick()}
 * stops it once the deadline passes. There is no background timer thread driving the timeout — {@link
 * #tick()} is meant to be called from whatever is already polling once a second to update a countdown
 * on screen, the same "check lazily when someone asks" idiom {@code SubtitleCache} uses for its own
 * expiry instead of a self-waking sweep.
 */
public class QrSetupServer extends NanoHTTPD {

    /** Fields the phone submitted, already stripped of blanks. */
    public interface Listener {
        void onSubmitted(Map<String, String> fields);
    }

    /** Injected so tests can move time without sleeping. */
    public interface Clock {
        long nowMs();
    }

    /** Grace period between answering a POST and actually closing the socket — see {@link #handlePost}. */
    private static final long STOP_GRACE_MS = 250L;

    private final String token;
    private final String html;
    private final Supplier<String> configJsonSupplier;
    private final Listener listener;
    private final Clock clock;
    private final long deadlineMs;

    private final Object lock = new Object();
    private boolean used;

    public QrSetupServer(String token, String html, Supplier<String> configJsonSupplier,
                         Listener listener, long sessionTtlMs, Clock clock) {
        super(0);
        this.token = token;
        this.html = html;
        this.configJsonSupplier = configJsonSupplier;
        this.listener = listener;
        this.clock = clock;
        this.deadlineMs = clock.nowMs() + sessionTtlMs;
    }

    public long deadlineMs() {
        return deadlineMs;
    }

    /**
     * Stops the server if the deadline has passed. The only trigger for a timeout shutdown — call it
     * from whatever is already ticking once a second to show a countdown; a request that slips in
     * between the deadline passing and the next call is still caught by {@link #serve} directly.
     */
    public void tick() {
        synchronized (lock) {
            if (used || !isAlive()) return;
            if (clock.nowMs() >= deadlineMs) stop();
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        if (clock.nowMs() >= deadlineMs) {
            return forbidden();
        }
        if (!token.equals(tokenFrom(session))) {
            return forbidden();
        }

        String uri = session.getUri();
        Method method = session.getMethod();
        if (Method.GET.equals(method) && "/".equals(uri)) {
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
        }
        if (Method.GET.equals(method) && "/config.json".equals(uri)) {
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
                    configJsonSupplier.get());
        }
        if (Method.POST.equals(method) && "/".equals(uri)) {
            return handlePost(session);
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "not found");
    }

    private Response handlePost(IHTTPSession session) {
        synchronized (lock) {
            if (used) return forbidden(); // single-use: a second POST is not a retry, it's a replay
            Map<String, String> body = new HashMap<>();
            try {
                session.parseBody(body);
            } catch (IOException | ResponseException e) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT,
                        "bad request");
            }
            Map<String, String> fields = new HashMap<>();
            for (Map.Entry<String, List<String>> e : session.getParameters().entrySet()) {
                if ("t".equals(e.getKey())) continue; // the token, not a setting
                String value = e.getValue().isEmpty() ? "" : e.getValue().get(0);
                if (!value.isEmpty()) fields.put(e.getKey(), value);
            }
            used = true;
            listener.onSubmitted(fields);
            scheduleStop();
            return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
                    "{\"ok\":true}");
        }
    }

    /**
     * Stopping from inside the request thread that is about to write this very response back would
     * risk NanoHTTPD's thread pool interrupting itself mid-write. A short-lived daemon thread with a
     * small grace period avoids that footgun — the response has plenty of time to flush first.
     */
    private void scheduleStop() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(STOP_GRACE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            stop();
        }, "qr-setup-server-stop");
        t.setDaemon(true);
        t.start();
    }

    private Response forbidden() {
        return newFixedLengthResponse(Response.Status.FORBIDDEN, NanoHTTPD.MIME_PLAINTEXT, "forbidden");
    }

    private static String tokenFrom(IHTTPSession session) {
        List<String> values = session.getParameters().get("t");
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }
}
