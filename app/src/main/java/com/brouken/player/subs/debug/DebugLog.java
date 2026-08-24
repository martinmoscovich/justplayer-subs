package com.brouken.player.subs.debug;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.brouken.player.BuildConfig;
import com.brouken.player.subs.SubtitleSettings;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The debug-mode session log: one plain-text file per playback, holding everything needed to
 * reproduce a report after the fact — which video, which audio track, which subtitles existed, which
 * one was chosen, and the full blow-by-blow of the auto-sync and translation runs.
 *
 * <p>This exists because {@code adb logcat} is <em>live</em>: the existing {@code Log.i/w/e} calls
 * say the right things, but the ring buffer eats them, so anything not being watched at the moment it
 * happened is gone. Every call site here sits next to one of those existing log lines rather than
 * replacing it.
 *
 * <p><b>Off means off.</b> With {@code pref_debug_mode} disabled nothing is written <em>and the
 * directory is deleted</em> — {@link #setEnabled} does the deletion on the transition, and
 * {@link #applySetting} sweeps on startup to cover a process that died before the toggle handler ran
 * (or prefs written from somewhere else, e.g. the QR setup). {@link #enabled()} is a plain volatile
 * read, so a disabled build of this feature costs one branch per call site.
 *
 * <p>Thread model: callers never touch the disk. {@link #log} formats on the calling thread (the
 * state being described can change a millisecond later) and hands the finished line to a private
 * {@link HandlerThread}. File state is guarded by {@link #FILE_LOCK} rather than confined to that
 * thread, so {@link #clearAll} and {@link #describeStorage} can be called straight from the settings
 * screen without a round trip.
 */
public final class DebugLog {

    private static final String TAG = "SubsDebugLog";

    /** Under {@code getExternalFilesDir}: reachable with {@code adb pull}, no root needed. */
    private static final String DIR_NAME = "subs-debug";

    /** A single run's file. Past this the file stops growing rather than filling the device. */
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    /** Kept on disk; older ones are pruned when a new session opens. */
    private static final int MAX_FILES = 10;

    // Categories. Fixed width in the file so the eye can skip a column instead of reading it.
    public static final String CAT_SESSION = "SESSION";
    public static final String CAT_SUBS = "SUBS";
    public static final String CAT_AUDIO = "AUDIO";
    public static final String CAT_PLAYBACK = "PLAYBACK";
    public static final String CAT_CACHE = "CACHE";
    public static final String CAT_SYNC = "SYNC";
    public static final String CAT_BAR = "BAR";
    public static final String CAT_AUTOSYNC = "AUTOSYNC";
    public static final String CAT_EXTRACT_AUDIO = "EXTRACT-AUDIO";
    public static final String CAT_EXTRACT_SUBS = "EXTRACT-SUBS";
    public static final String CAT_TRANSLATE = "TRANSLATE";
    public static final String CAT_ENGINE = "ENGINE";
    public static final String CAT_UI = "UI";

    private static final Object FILE_LOCK = new Object();

    private static volatile boolean enabled;
    private static volatile DebugRedactor redactor = DebugRedactor.EMPTY;

    @Nullable private static Handler writer;

    // --- guarded by FILE_LOCK ---
    @Nullable private static BufferedWriter out;
    @Nullable private static File file;
    /** Which media the open file belongs to — {@code onMediaSet} fires twice per playback (see
     *  {@code TESTING.md}), and two files for one playback would be worse than useless. */
    @Nullable private static String sessionKey;
    /**
     * The last session's media and file, kept <em>after</em> the file is closed so the same playback
     * can pick it up again. {@code PlayerActivity.initializePlayer()} tears the whole subtitle
     * controller down and rebuilds it on every {@code onStart()} — including just returning from the
     * Settings screen — which would otherwise split one playback across two files, with the second
     * one missing everything that led up to it.
     */
    @Nullable private static String lastKey;
    @Nullable private static File lastFile;
    private static long written;
    private static boolean truncated;

    private DebugLog() {
    }

    public static boolean enabled() {
        return enabled;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Reads {@code pref_debug_mode} and applies it, including the startup sweep when it is off.
     * Safe to call repeatedly — {@link #setEnabled} only acts on an actual transition.
     */
    public static void applySetting(Context context) {
        setEnabled(context, SubtitleSettings.debugEnabled(context));
    }

    /**
     * Turns the log on or off. Turning it <em>off</em> closes the open file and deletes the whole
     * directory: with debug mode off there must be nothing left on disk.
     */
    public static void setEnabled(Context context, boolean on) {
        boolean was = enabled;
        enabled = on;
        if (on) {
            refreshRedaction(context);
            EngineLogBridge.install();
            if (!was) Log.i(TAG, "debug mode on — logs in " + dir(context));
        } else {
            EngineLogBridge.uninstall();
            // On the writer thread, not here: this also runs from the subtitle controller's
            // constructor as the startup sweep, which is on the main thread at every playback start —
            // and listing plus deleting files there is exactly the kind of I/O that does not belong on
            // it. The settings screen's explicit "Clear debug logs" calls clearAll directly instead,
            // because it needs the storage summary to be accurate the moment it returns.
            Context app = context.getApplicationContext();
            post(() -> clearAll(app));
            if (was) Log.i(TAG, "debug mode off — debug logs deleted");
        }
    }

    /**
     * Rebuilds the credential mask from the current settings. Called whenever the prefs change: the
     * QR setup can rewrite the API keys from inside the player, and a stale mask would mean writing
     * the new key in clear.
     */
    public static void refreshRedaction(Context context) {
        redactor = new DebugRedactor(Arrays.asList(
                SubtitleSettings.getApiKey(context, SubtitleSettings.KEY_OPENSUBTITLES),
                SubtitleSettings.getApiKey(context, SubtitleSettings.KEY_AI_API_KEY)));
    }

    /**
     * Opens the file for a playback and writes the header. A second call for the same media (see
     * {@link #sessionKey}) keeps writing to the file already open instead of starting another one.
     *
     * <p>No duration here on purpose: this runs from {@code initializePlayer()}, before the player has
     * prepared, so the duration is always unknown at this point. The caller reports it as a plain
     * event once playback actually knows it.
     */
    public static void startSession(Context context, @Nullable Uri media, @Nullable String title) {
        if (!enabled) return;
        refreshRedaction(context);
        String key = media != null ? media.toString() : "no-media";
        Context app = context.getApplicationContext();
        long at = System.currentTimeMillis();
        post(() -> {
            synchronized (FILE_LOCK) {
                // The toggle may have gone off between the post and now — never re-create a file
                // clearAll() has just deleted.
                if (!enabled) return;
                if (out != null && key.equals(sessionKey)) {
                    appendLocked(at, CAT_SESSION, "media re-initialised (same source)");
                    return;
                }
                closeLocked();
                boolean resumed = openLocked(app, media, key, at);
                if (out == null) return;
                if (resumed) {
                    appendLocked(at, CAT_SESSION, "playback resumed (player rebuilt — e.g. back from settings)");
                } else {
                    appendHeaderLocked(app, media, title);
                }
            }
        });
    }

    /** Closes the current file. The next {@link #startSession} opens a fresh one. */
    public static void endSession() {
        post(() -> {
            synchronized (FILE_LOCK) {
                if (out != null) appendLocked(System.currentTimeMillis(), CAT_SESSION, "playback released");
                closeLocked();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Writing
    // -------------------------------------------------------------------------

    public static void log(String category, String message) {
        if (!enabled) return;
        long at = System.currentTimeMillis();
        post(() -> {
            synchronized (FILE_LOCK) {
                appendLocked(at, category, message);
            }
        });
    }

    /**
     * Supplier form for the hot paths (extraction/VAD/translation progress, the 100ms render tick):
     * with debug off the message is never built. The supplier runs on the <em>calling</em> thread —
     * it reads live state, which would have moved on by the time the writer thread got to it.
     */
    public static void log(String category, Supplier<String> message) {
        if (!enabled) return;
        log(category, message.get());
    }

    // -------------------------------------------------------------------------
    // Storage
    // -------------------------------------------------------------------------

    /** {@code …/files/subs-debug}, on external app storage when there is any (pullable via adb). */
    public static File dir(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        return new File(base, DIR_NAME);
    }

    /** Deletes every stored log, closing the open one first. Used by the setting and by {@link #setEnabled}. */
    public static void clearAll(Context context) {
        File directory = dir(context);
        synchronized (FILE_LOCK) {
            closeLocked();
            File[] files = directory.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.delete()) Log.w(TAG, "could not delete " + f.getName());
                }
            }
            directory.delete(); // empty now; harmless no-op if something is left behind
            // Nothing to resume into any more: the next startSession must write a fresh header rather
            // than append to a file that no longer exists.
            lastKey = null;
            lastFile = null;
        }
    }

    /** "3 files · 128 KB" / "no logs yet" — the clear-action's summary. */
    public static String describeStorage(Context context) {
        File[] files = dir(context).listFiles();
        if (files == null || files.length == 0) return "No debug logs stored";
        long bytes = 0;
        for (File f : files) bytes += f.length();
        return files.length + (files.length == 1 ? " file · " : " files · ") + (bytes / 1024) + " KB";
    }

    // -------------------------------------------------------------------------
    // Internals — every method below runs holding FILE_LOCK
    // -------------------------------------------------------------------------

    /** @return true when it reopened the previous session's file rather than creating a new one. */
    private static boolean openLocked(Context context, @Nullable Uri media, String key, long at) {
        File directory = dir(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Log.w(TAG, "cannot create " + directory + " — debug logging disabled for this run");
            return false;
        }
        pruneLocked(directory);
        boolean resumed = key.equals(lastKey) && lastFile != null && lastFile.isFile();
        File target = resumed ? lastFile : new File(directory, fileName(media, at));
        try {
            out = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(target, /* append= */ true), "UTF-8"));
            file = target;
            sessionKey = key;
            lastKey = key;
            lastFile = target;
            written = target.length();
            truncated = false;
            return resumed;
        } catch (IOException e) {
            Log.w(TAG, "cannot open " + target, e);
            out = null;
            file = null;
            sessionKey = null;
            return false;
        }
    }

    private static void closeLocked() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
                Log.w(TAG, "close failed", e);
            }
        }
        out = null;
        file = null;
        sessionKey = null;
        written = 0;
        truncated = false;
    }

    /** Keeps the newest {@link #MAX_FILES}; the oldest go. */
    private static void pruneLocked(File directory) {
        File[] files = directory.listFiles();
        if (files == null || files.length < MAX_FILES) return;
        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        Collections.sort(sorted, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i <= sorted.size() - MAX_FILES; i++) {
            sorted.get(i).delete();
        }
    }

    private static void appendHeaderLocked(Context context, @Nullable Uri media, @Nullable String title) {
        writeRawLocked("=== subs debug " + HEADER_STAMP.format(new Date()) + " ===");
        writeRawLocked("app=" + BuildConfig.VERSION_NAME + " device=" + Build.MANUFACTURER + "/" + Build.MODEL
                + " android=" + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        // Redacted, like every other line: a launcher URL turned out to carry a Real-Debrid account
        // key in its path, so what identifies the media is written and what authenticates as its
        // owner is not. See DebugRedactor for the rules and the case that set them.
        writeRawLocked("media=" + (media != null ? media : "(none)"));
        writeRawLocked("title=" + (title != null ? title : "(unknown)"));
        writeRawLocked("settings: " + settingsSummary(context));
        writeRawLocked("(API keys are never written to this file)");
    }

    /** Everything that changes how the run behaves — and no key values. */
    private static String settingsSummary(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("autoSelect=").append(SubtitleSettings.autoSelectEnabled(context) ? "on" : "off");
        sb.append(" targets=").append(SubtitleSettings.getLanguageList(context, SubtitleSettings.KEY_TARGET_LANGS));
        sb.append(" sources=").append(SubtitleSettings.getLanguageList(context, SubtitleSettings.KEY_SOURCE_LANGS));
        sb.append(" provider=").append(SubtitleSettings.getString(context, SubtitleSettings.KEY_AI_PROVIDER, "(unset)"));
        sb.append(" model=").append(SubtitleSettings.getString(context, SubtitleSettings.KEY_AI_MODEL, "(unset)"));
        sb.append(" osKey=").append(has(context, SubtitleSettings.KEY_OPENSUBTITLES));
        sb.append(" aiKey=").append(has(context, SubtitleSettings.KEY_AI_API_KEY));
        sb.append(" sync=").append(SubtitleSettings.syncSettings(context));
        return sb.toString();
    }

    private static String has(Context context, String key) {
        return TextUtils.isEmpty(SubtitleSettings.getApiKey(context, key)) ? "unset" : "set";
    }

    private static void appendLocked(long at, String category, @Nullable String message) {
        if (out == null || message == null) return;
        writeRawLocked(LINE_STAMP.format(new Date(at)) + " [" + category + "] " + message);
    }

    private static void writeRawLocked(String line) {
        if (out == null) return;
        if (written >= MAX_FILE_BYTES) {
            if (!truncated) {
                truncated = true;
                try {
                    out.write("-- truncated: file size limit reached --");
                    out.newLine();
                    out.flush();
                } catch (IOException ignored) {
                    // Nothing useful to do: this is the log.
                }
            }
            return;
        }
        String safe = redactor.redact(line);
        try {
            out.write(safe);
            out.newLine();
            // Flushed per line on purpose: a crash must not take the end of the log with it, and the
            // end is the interesting part.
            out.flush();
            written += safe.length() + 1;
        } catch (IOException e) {
            Log.w(TAG, "write failed, closing " + file, e);
            closeLocked();
        }
    }

    /** {@code 2026-08-24_2137_sintel-mkv.log} — date first so the directory sorts chronologically. */
    private static String fileName(@Nullable Uri media, long at) {
        String slug = slug(media);
        return FILE_STAMP.format(new Date(at)) + (slug.isEmpty() ? "" : "_" + slug) + ".log";
    }

    /** Last path segment of the media URI, reduced to something a file name can hold. */
    private static String slug(@Nullable Uri media) {
        String raw = null;
        if (media != null) {
            try {
                raw = media.getLastPathSegment();
            } catch (UnsupportedOperationException ignored) {
                // Opaque URI (no path) — the timestamp alone identifies the file.
            }
        }
        if (TextUtils.isEmpty(raw)) return "";
        String cleaned = raw.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-+", "-");
        return cleaned.length() > 40 ? cleaned.substring(cleaned.length() - 40) : cleaned;
    }

    private static void post(Runnable task) {
        writerHandler().post(task);
    }

    private static synchronized Handler writerHandler() {
        if (writer == null) {
            HandlerThread thread = new HandlerThread("subs-debug-log", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            writer = new Handler(thread.getLooper());
        }
        return writer;
    }

    // Confined to the writer thread (or to a caller holding FILE_LOCK), never shared concurrently.
    private static final java.text.SimpleDateFormat LINE_STAMP =
            new java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final java.text.SimpleDateFormat FILE_STAMP =
            new java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US);
    private static final java.text.SimpleDateFormat HEADER_STAMP =
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US);
}
