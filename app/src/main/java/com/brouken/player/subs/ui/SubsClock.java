package com.brouken.player.subs.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.brouken.player.R;

import java.util.Locale;

/**
 * The current playback position, large enough to read from a couch (~3m) while the Sync or Translate
 * screen is open over the video, with a glyph saying whether the video is actually moving.
 *
 * <p>The glyph is not decoration. Both screens can pause playback on their own — the Translate screen
 * does it while a run is in flight, and auto-sync does it for the whole run — so a frozen number is
 * ambiguous: it reads the same as a stalled player. The icon is the difference between "paused, on
 * purpose" and "something is stuck".
 *
 * <p>Fed from the panel's 100ms tick, but only touches the views when what they render actually
 * changes — a second's worth of ticks is ten calls, and re-setting identical text would ask for a
 * layout pass each time for nothing.
 */
public class SubsClock extends LinearLayout {

    private final ImageView state;
    private final TextView time;

    private String shown = "";
    private Boolean shownPlaying;

    public SubsClock(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        state = SubsIcons.icon(context, R.drawable.subtitle_ic_play, SubsTheme.INK_2, 22f);
        LayoutParams slp = new LayoutParams(SubsTheme.dp(context, 22), SubsTheme.dp(context, 22));
        slp.rightMargin = SubsTheme.dp(context, 10);
        addView(state, slp);

        time = SubsTheme.display(new TextView(context));
        time.setTextColor(SubsTheme.INK);
        time.setSingleLine(true);
        // Something to occupy the corner before the first tick arrives, so the layout does not jump
        // once playback reports in.
        time.setText("0:00");
        shown = "0:00";
        addView(time, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    public void setPositionMs(long positionMs) {
        String next = format(positionMs);
        if (next.equals(shown)) return;
        shown = next;
        time.setText(next);
    }

    /** Swaps the glyph between play and pause — see the class javadoc for why the clock carries one. */
    public void setPlaying(boolean playing) {
        if (shownPlaying != null && shownPlaying == playing) return;
        shownPlaying = playing;
        state.setImageResource(playing ? R.drawable.subtitle_ic_play : R.drawable.subtitle_ic_pause);
        // Dimmer while paused: the number stops moving, and the glyph should read as the quieter
        // state rather than competing with it.
        Drawable d = state.getDrawable();
        if (d != null) d.setTint(playing ? SubsTheme.INK_2 : SubsTheme.INK_3);
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
