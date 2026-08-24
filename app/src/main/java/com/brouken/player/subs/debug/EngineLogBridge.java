package com.brouken.player.subs.debug;

import androidx.annotation.Nullable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Pipes the engine's own {@code java.util.logging} output into the debug log file.
 *
 * <p>The engine already records exactly the internals this feature needs, in lines written for a
 * human to read — {@code SubtitleResyncer}'s {@code resync candidate: … -> ACCEPT|REJECT} (the VAD's
 * parameters and verdict, <em>including the rejected runs</em>, which never reach the player through
 * {@code AutoSyncProgress} because a rejection arrives as a plain {@code null} result),
 * {@code SubtitleTranslator}'s per-chunk lines, and the provider/LLM clients' HTTP failures. Tapping
 * that is what keeps this feature from needing a new engine↔player event channel: the golden rule
 * says the logic stays in the engine, and its diagnostics already do.
 *
 * <p>Two things this has to get right:
 * <ul>
 *   <li>{@link #ENGINE_LOGGER} is held in a static field. {@code LogManager} keeps only weak
 *       references to loggers, so a {@code Logger.getLogger(...)} whose result is dropped can be
 *       collected — taking the level and this handler with it, and the log silently goes quiet.</li>
 *   <li>The level is widened to {@link Level#ALL} so {@code logger.fine} (the LLM clients' token
 *       counts) is not dropped. Parent handlers stay attached, so logcat gets more verbose while
 *       debug mode is on — acceptable: that is what debug mode is.</li>
 * </ul>
 */
final class EngineLogBridge {

    /** Root package of the engine — one logger covers every class under it. */
    private static final String ENGINE_PACKAGE = "subtitleengine";

    /** Strong reference on purpose — see the class javadoc. */
    private static final Logger ENGINE_LOGGER = Logger.getLogger(ENGINE_PACKAGE);

    @Nullable private static Handler installed;
    @Nullable private static Level previousLevel;

    private EngineLogBridge() {
    }

    static synchronized void install() {
        if (installed != null) return;
        previousLevel = ENGINE_LOGGER.getLevel();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record == null) return;
                DebugLog.log(DebugLog.CAT_ENGINE, format(record));
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        handler.setLevel(Level.ALL);
        ENGINE_LOGGER.addHandler(handler);
        ENGINE_LOGGER.setLevel(Level.ALL);
        installed = handler;
    }

    static synchronized void uninstall() {
        if (installed == null) return;
        ENGINE_LOGGER.removeHandler(installed);
        ENGINE_LOGGER.setLevel(previousLevel); // null restores "inherit from parent"
        installed = null;
        previousLevel = null;
    }

    /**
     * {@code shortClassName: message} plus the stack trace when there is one. The class name is
     * shortened because the engine's loggers are named after fully-qualified classes, and 40
     * characters of package prefix on every line would push the actual message off the screen.
     */
    private static String format(LogRecord record) {
        StringBuilder sb = new StringBuilder();
        String source = record.getLoggerName();
        if (source != null) {
            int dot = source.lastIndexOf('.');
            sb.append(dot >= 0 ? source.substring(dot + 1) : source).append(": ");
        }
        sb.append(record.getMessage());
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            StringWriter sw = new StringWriter();
            thrown.printStackTrace(new PrintWriter(sw));
            sb.append('\n').append(sw);
        }
        return sb.toString();
    }
}
