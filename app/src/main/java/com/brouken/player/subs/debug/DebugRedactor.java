package com.brouken.player.subs.debug;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks credentials out of every line before it reaches a debug log file.
 *
 * <p>The media URL was originally written verbatim, on the reasoning that a tokenised stream URL is
 * what makes a playback problem reproducible. A real session disproved that trade: a launcher URL
 * turned out to carry the user's <b>Real-Debrid API key</b> — not a short-lived playback token, but a
 * long-lived account credential — inside a base64 config blob in the <em>path</em>, where no
 * query-parameter rule would have caught it. Reproducibility is not worth writing an account
 * credential to a file whose whole purpose is to be sent to someone else, so URLs are now redacted
 * down to what identifies the media without carrying the means to authenticate as its owner.
 *
 * <p>What survives redaction: scheme, host, the shape of the path, and any query parameter that is
 * not credential-shaped. What does not: the configured API keys, {@code Bearer} tokens,
 * credential-named query parameters, long base64 path segments (the config-blob case above), and
 * userinfo in the authority. See the tests for the exact expectations.
 *
 * <p>Secrets shorter than {@link #MIN_SECRET_LENGTH} are ignored: a one-or-two-character value (a
 * half-typed key, a placeholder) would otherwise match everywhere and shred the whole file, which is
 * worse than not masking a string that isn't a real key anyway.
 */
public final class DebugRedactor {

    static final String MASK = "***";

    /** Below this, a "secret" is more likely a typo than a key — see the class javadoc. */
    private static final int MIN_SECRET_LENGTH = 8;

    /**
     * How long a path segment has to be before it is treated as an opaque blob rather than a name.
     * The observed config blob was ~900 characters; ordinary path segments (a title, a file name, a
     * hash, a UUID) are well under this.
     */
    private static final int BLOB_SEGMENT_LENGTH = 64;

    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)([A-Za-z0-9._~+/=-]{8,})");

    /** Query parameters whose <em>value</em> is a credential, whatever the service calls it. */
    private static final Pattern CREDENTIAL_PARAM = Pattern.compile(
            "(?i)([?&](?:[a-z0-9_-]*(?:api[_-]?key|apikey|access[_-]?token|auth[_-]?token|token|secret"
                    + "|password|passwd|signature|sig|session|credential)[a-z0-9_-]*)=)([^&\\s]+)");

    /** {@code scheme://user:pass@host} — the password is a credential, the host is not. */
    private static final Pattern USERINFO = Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)([^/@\\s:]+):([^/@\\s]+)@");

    /**
     * A long, opaque path segment. Deliberately not "looks like base64": whatever such a segment
     * holds, it is not something a human named, and the one real example carried an account key.
     *
     * <p>{@code /} is excluded from the character class on purpose, so a match can never run past the
     * end of its own segment and swallow the readable path that follows (which it did — the whole
     * {@code /play/<id>/1} tail disappeared into one mask). A standard-base64 blob containing
     * {@code /} therefore masks as several adjacent segments rather than one, which is fine: each
     * piece of a blob that long clears the threshold on its own.
     */
    private static final Pattern BLOB_SEGMENT = Pattern.compile("/([A-Za-z0-9+=_-]{" + BLOB_SEGMENT_LENGTH + ",})");

    /** Empty instance for the window before any key has been read (URL rules still apply). */
    public static final DebugRedactor EMPTY = new DebugRedactor(java.util.Collections.emptyList());

    private final List<String> secrets;

    public DebugRedactor(Collection<String> secrets) {
        List<String> kept = new ArrayList<>();
        for (String s : secrets) {
            if (s != null && s.trim().length() >= MIN_SECRET_LENGTH) kept.add(s.trim());
        }
        this.secrets = kept;
    }

    @Nullable
    public String redact(@Nullable String line) {
        if (line == null || line.isEmpty()) return line;
        String out = line;

        // The configured keys first and by value: the strong guarantee, independent of shape or
        // position — it catches the key however it got into the string, including GeminiClient's
        // ?key=… URL, which no header-shaped rule would match.
        for (String secret : secrets) {
            if (out.contains(secret)) out = out.replace(secret, MASK);
        }

        Matcher bearer = BEARER.matcher(out);
        if (bearer.find()) out = bearer.replaceAll("$1" + MASK);

        Matcher userinfo = USERINFO.matcher(out);
        if (userinfo.find()) out = userinfo.replaceAll("$1$2:" + MASK + "@");

        Matcher param = CREDENTIAL_PARAM.matcher(out);
        if (param.find()) out = param.replaceAll("$1" + MASK);

        Matcher blob = BLOB_SEGMENT.matcher(out);
        if (blob.find()) {
            StringBuffer sb = new StringBuffer();
            do {
                // Keep the length: how big the blob was is occasionally the clue, and it identifies
                // two different URLs as different without revealing either.
                blob.appendReplacement(sb, "/" + MASK + "(" + blob.group(1).length() + " chars)");
            } while (blob.find());
            blob.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }
}
