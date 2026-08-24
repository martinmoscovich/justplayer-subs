package com.brouken.player.subs.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

class DebugRedactorTest {

    private static final String OS_KEY = "aBcDeF1234567890osKey";
    private static final String AI_KEY = "sk-or-v1-9f8e7d6c5b4a3210";

    private static DebugRedactor withKeys() {
        return new DebugRedactor(Arrays.asList(OS_KEY, AI_KEY));
    }

    @Test
    void masksAConfiguredKeyWhereverItAppears() {
        String line = "download: Api-Key: " + OS_KEY + " failed";
        String out = withKeys().redact(line);
        assertFalse(out.contains(OS_KEY), out);
        assertEquals("download: Api-Key: *** failed", out);
    }

    /**
     * The case a header-shaped pattern would miss: {@code GeminiClient} builds the key into the URL,
     * so it can surface inside any message that happens to carry that URL.
     */
    @Test
    void masksAKeyCarriedInAUrlQuery() {
        String out = withKeys().redact(
                "network error — https://generativelanguage.googleapis.com/v1beta/models/x:generateContent?key=" + AI_KEY);
        assertFalse(out.contains(AI_KEY), out);
        assertTrue(out.endsWith("?key=***"), out);
    }

    @Test
    void masksBearerTokensEvenWithNoConfiguredKey() {
        String out = new DebugRedactor(Collections.emptyList())
                .redact("Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");
        assertEquals("Authorization: Bearer ***", out);
    }

    /**
     * The case that changed this class's policy: a launcher URL carrying the user's Real-Debrid
     * account key inside a base64 config blob in the <em>path</em>. No query-parameter rule would
     * have caught it, and it is an account credential, not a playback token.
     */
    @Test
    void masksAnOpaqueConfigBlobInThePath() {
        String blob = "eyJkZWJyaWRTZXJ2aWNlIjoicmVhbGRlYnJpZCIsImRlYnJpZEFwaUtleSI6IlNFQ1JFVEtFWVZBTFVFIiwiY2FjaGVkT25seSI6ZmFsc2V9AAAA";
        String out = withKeys().redact("https://host.example/" + blob + "/play/abc123/1?e=2&s=2");
        assertFalse(out.contains(blob), out);
        assertFalse(out.contains("ZGVicmlkQXBpS2V5"), out);
        // Host, the rest of the path and the harmless params survive — still enough to tell two
        // different streams apart and to see the shape of the request.
        assertTrue(out.startsWith("https://host.example/***("), out);
        assertTrue(out.endsWith("/play/abc123/1?e=2&s=2"), out);
    }

    @Test
    void masksCredentialShapedQueryParametersButKeepsTheRest() {
        String out = withKeys().redact("https://cdn.example.com/movie.mkv?e=1724698800&token=abc123def456&q=hd");
        assertEquals("https://cdn.example.com/movie.mkv?e=1724698800&token=***&q=hd", out);
    }

    @Test
    void masksAPasswordInTheAuthorityButKeepsTheHost() {
        String out = withKeys().redact("http://someone:hunter2pass@media.example.com/x.mkv");
        assertEquals("http://someone:***@media.example.com/x.mkv", out);
    }

    /** An ordinary media URL has nothing credential-shaped in it and must survive untouched — the
     *  redaction has to leave the common case readable. */
    @Test
    void leavesAPlainMediaUrlIntact() {
        String url = "http://10.0.2.2:8000/Everybody%20Loves%20Raymond%20S01E01%20Pilot.mp4";
        assertEquals(url, withKeys().redact(url));
    }

    /**
     * A one-or-two-character "key" (half-typed, a placeholder) would otherwise match everywhere and
     * shred the file — worse than not masking something that is not a real key anyway.
     */
    @Test
    void ignoresSecretsTooShortToBeReal() {
        DebugRedactor redactor = new DebugRedactor(Collections.singletonList("ab"));
        assertEquals("a blob of ordinary text", redactor.redact("a blob of ordinary text"));
    }

    @Test
    void passesNullAndEmptyThrough() {
        assertEquals(null, withKeys().redact(null));
        assertEquals("", withKeys().redact(""));
    }
}
