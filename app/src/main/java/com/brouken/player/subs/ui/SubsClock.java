package com.brouken.player.subs.ui;

import android.content.Context;
import android.widget.TextView;

import java.util.Locale;

/**
 * The current playback position, large enough to read from a couch (~3m) while the Sync or Translate
 * screen is open over the still-playing video.
 *
 * <p>Fed from the panel's 100ms tick, but only touches the {@link TextView} when the rendered string
 * actually changes — a second's worth of ticks is ten calls, and re-setting identical text would ask
 * for a layout pass each time for nothing.
 */
public class SubsClock extends TextView {

    private String shown = "";

    public SubsClock(Context context) {
        super(context);
        SubsTheme.display(this);
        setTextColor(SubsTheme.INK);
        setSingleLine(true);
        // Something to occupy the corner before the first tick arrives, so the layout does not jump
        // once playback reports in.
        setText("0:00");
        shown = "0:00";
    }

    public void setPositionMs(long positionMs) {
        String next = format(positionMs);
        if (next.equals(shown)) return;
        shown = next;
        setText(next);
    }

    /** {@code m:ss}, or {@code h:mm:ss} past the hour — the same convention the cue times, the chunk
     *  bar and the translation controller already use, so the screen reads consistently. */
    private static String format(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0
                ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%d:%02d", m, s);
    }
}
