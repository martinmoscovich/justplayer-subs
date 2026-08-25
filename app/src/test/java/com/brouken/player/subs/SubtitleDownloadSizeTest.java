package com.brouken.player.subs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The download that reads an external subtitle into memory has a ceiling on it.
 *
 * <p>It did not, until a launcher handed over a URL ending in {@code .srt}, naming a real episode,
 * that served hundreds of megabytes of something else. The read grew to a quarter of a gigabyte and
 * died with an {@code OutOfMemoryError} — an {@link Error}, so it went straight past the download
 * thread's {@code catch (Exception)} and killed the whole process one second into playback.
 */
class SubtitleDownloadSizeTest {

    /** A stream that yields {@code size} bytes without ever holding them all — the point is to prove
     *  the reader stops early, so materialising the whole thing here would defeat the test. */
    private static InputStream streamOf(long size) {
        return new InputStream() {
            private long produced = 0;

            @Override public int read() {
                return produced++ < size ? 'x' : -1;
            }

            @Override public int read(byte[] b, int off, int len) {
                if (produced >= size) return -1;
                int n = (int) Math.min(len, size - produced);
                java.util.Arrays.fill(b, off, off + n, (byte) 'x');
                produced += n;
                return n;
            }
        };
    }

    @Test
    void rejectsSomethingFarTooBigToBeASubtitleInsteadOfRunningOutOfMemory() {
        // A tenth of what the real failure tried to allocate — enough to be well past the cap, small
        // enough that a broken cap fails this test rather than the whole JVM.
        Exception thrown = assertThrows(Exception.class,
                () -> SubtitleSelectionController.readAll(streamOf(32L * 1024 * 1024)));

        assertTrue(thrown.getMessage() != null && thrown.getMessage().contains("Not a subtitle"),
                "the failure has to name what is wrong, not talk about memory: " + thrown.getMessage());
    }

    @Test
    void readsAnOrdinarySubtitleUnchanged() throws Exception {
        String srt = "1\n00:00:01,000 --> 00:00:04,000\nHola.\n\n2\n00:00:05,000 --> 00:00:07,000\nAdiós.\n";

        assertEquals(srt, SubtitleSelectionController.readAll(
                new ByteArrayInputStream(srt.getBytes(StandardCharsets.UTF_8))));
    }

    /** The largest real subtitle this project has seen is ~125 KB; a megabyte must still sail through,
     *  or the cap would start rejecting legitimate files to prevent a crash they never caused. */
    @Test
    void aMegabyteIsStillAPlausibleSubtitle() throws Exception {
        String read = SubtitleSelectionController.readAll(streamOf(1024L * 1024));

        assertEquals(1024 * 1024, read.length());
    }
}
