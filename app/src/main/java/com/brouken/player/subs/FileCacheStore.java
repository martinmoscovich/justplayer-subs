package com.brouken.player.subs;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import com.brouken.player.subs.debug.DebugLog;

import subtitleengine.cache.CacheStore;

/**
 * {@link CacheStore} over one directory in the app's cache dir — the dumb half of the cache, as the
 * engine's split intends. Entries are small (parsed cues and translated lines, a few hundred KB at
 * most), so plain files beat a database here: no schema, no migrations, and the contents can be
 * inspected with {@code adb} when something looks wrong.
 *
 * <p>Living under {@code cacheDir} means Android may reclaim it under storage pressure, which is the
 * correct semantics: everything in here is regenerable, and the engine already treats a miss as
 * normal.
 */
public class FileCacheStore implements CacheStore {

    private static final String DIR_NAME = "subtitles";

    private final File dir;

    public FileCacheStore(Context context) {
        this.dir = new File(context.getCacheDir(), DIR_NAME);
    }

    /**
     * Every cache read, write and removal passes through here, which makes this the one place that can
     * answer "what was served from disk and what was paid for again" without the engine knowing debug
     * mode exists — the engine's own {@code SubtitleCache} only logs failures. Reconstructing a session
     * needs the hits as much as the misses: a run that looks free was a hit, and a run that repeats
     * work someone already paid for is a miss that should not have been one.
     */
    @Override
    public byte[] read(String key) throws IOException {
        File f = fileFor(key);
        if (!f.isFile()) {
            DebugLog.log(DebugLog.CAT_CACHE, "miss " + key);
            return null;
        }
        byte[] data = Files.readAllBytes(f.toPath());
        DebugLog.log(DebugLog.CAT_CACHE, () -> "hit " + key + " (" + data.length + " bytes, stored "
                + EmbeddedSubtitleController.ageDescription(f.lastModified()) + ")");
        return data;
    }

    @Override
    public void write(String key, byte[] data) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        // Write to a temp file and rename: a process killed mid-write would otherwise leave a
        // truncated entry that reads back as corrupt on every future launch.
        File target = fileFor(key);
        File tmp = new File(dir, target.getName() + ".tmp");
        try {
            Files.write(tmp.toPath(), data);
            if (!tmp.renameTo(target)) {
                Files.deleteIfExists(target.toPath());
                if (!tmp.renameTo(target)) throw new IOException("cannot replace " + target);
            }
        } finally {
            // Never leave a .tmp behind: keys() ignores them, so they would accumulate invisibly
            // and no sweep would ever reclaim them.
            Files.deleteIfExists(tmp.toPath());
        }
        DebugLog.log(DebugLog.CAT_CACHE, () -> "write " + key + " (" + data.length + " bytes)");
    }

    @Override
    public void remove(String key) throws IOException {
        boolean existed = Files.deleteIfExists(fileFor(key).toPath());
        DebugLog.log(DebugLog.CAT_CACHE, "remove " + key + (existed ? "" : " (was not stored)"));
    }

    @Override
    public List<String> keys() {
        List<String> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            String name = f.getName();
            if (f.isFile() && name.endsWith(".json")) {
                out.add(name.substring(0, name.length() - ".json".length()));
            }
        }
        return out;
    }

    private File fileFor(String key) {
        return new File(dir, key + ".json");
    }
}
