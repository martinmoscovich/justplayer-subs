package com.brouken.player.subs.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * One component for the five "the list is not the point right now" states across the three screens:
 * nothing to sync, reading the embedded track, auto-sync running, auto-sync/translation unavailable,
 * translation idle. They all used to be phrased differently in different places; they are the same
 * shape — an optional glyph, a headline, a subline, and sometimes a bar.
 *
 * <p>Lives centred in the <em>content</em> area, never over the sidebar: the sidebar keeps working
 * while one of these is up.
 */
public class SubsCenteredBlock extends LinearLayout {

    private final ImageView glyph;
    private final TextView headline;
    private final TextView subline;
    private final SubsProgressLine bar;
    /** Holds one {@link ProbeColumn} per sampled window; empty and hidden for the single-job states. */
    private final LinearLayout probeRow;

    public SubsCenteredBlock(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        int padH = SubsTheme.dp(c, 80);
        setPadding(padH, 0, padH, 0);

        glyph = SubsIcons.icon(c, 0, SubsTheme.INK_3, 44f);
        glyph.setVisibility(GONE);
        LayoutParams glp = new LayoutParams(SubsTheme.dp(c, 44), SubsTheme.dp(c, 44));
        glp.bottomMargin = SubsTheme.dp(c, 6);
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        addView(glyph, glp);

        headline = SubsTheme.headlineMd(new TextView(c));
        headline.setTextColor(SubsTheme.ON_SURFACE);
        headline.setGravity(Gravity.CENTER);
        addView(headline, wrap(c, 0));

        subline = SubsTheme.bodyMd(new TextView(c));
        subline.setTextColor(SubsTheme.INK_2);
        subline.setGravity(Gravity.CENTER);
        subline.setLineSpacing(0f, 1.45f);
        addView(subline, wrap(c, 8));

        bar = new SubsProgressLine(c);
        bar.setVisibility(GONE);
        LayoutParams blp = new LayoutParams(SubsTheme.dp(c, 280), SubsTheme.dp(c, 6));
        blp.topMargin = SubsTheme.dp(c, 10);
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        addView(bar, blp);

        probeRow = new LinearLayout(c);
        probeRow.setOrientation(HORIZONTAL);
        probeRow.setVisibility(GONE);
        LayoutParams rlp = new LayoutParams(SubsTheme.dp(c, 620), LayoutParams.WRAP_CONTENT);
        rlp.topMargin = SubsTheme.dp(c, 14);
        rlp.gravity = Gravity.CENTER_HORIZONTAL;
        addView(probeRow, rlp);
    }

    private static LayoutParams wrap(Context c, int topMarginDp) {
        LayoutParams p = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        p.topMargin = SubsTheme.dp(c, topMarginDp);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        return p;
    }

    /**
     * @param iconRes  0 for no glyph
     * @param spinning rotate the glyph — for the states that are actually waiting on something
     * @param sub      null or empty hides the subline entirely rather than leaving a blank row
     * @param fraction 0..1 to show the bar, negative to hide it
     */
    public void show(@DrawableRes int iconRes, boolean spinning, String title, @Nullable String sub,
                     float fraction) {
        if (iconRes != 0) {
            glyph.setImageResource(iconRes);
            glyph.setVisibility(VISIBLE);
            if (spinning) SubsIcons.spin(glyph); else SubsIcons.stopSpin(glyph);
        } else {
            SubsIcons.stopSpin(glyph);
            glyph.setVisibility(GONE);
        }
        headline.setText(title != null ? title : "");
        headline.setVisibility(title == null || title.isEmpty() ? GONE : VISIBLE);
        subline.setText(sub != null ? sub : "");
        subline.setVisibility(sub == null || sub.isEmpty() ? GONE : VISIBLE);
        probeRow.setVisibility(GONE); // the single-job states never show columns
        if (fraction >= 0f) {
            bar.setVisibility(VISIBLE);
            bar.setFraction(fraction);
        } else {
            bar.setVisibility(GONE);
        }
        setVisibility(VISIBLE);
    }

    /**
     * The multi-front variant: one column per sampled window, side by side, each with its own label,
     * phase and bar.
     *
     * <p>Separate from {@link #show} rather than a generalisation of it because the two describe
     * genuinely different things. {@code show} is for a single job with one thing to say ("reading the
     * embedded track, 40%"); this is for work happening on several windows <em>at once</em>, which a
     * single title and bar cannot represent — collapsing them is what made the number jump backwards
     * and read as sequential work.
     *
     * @param headline shared title above the columns; the per-column phase goes in the columns
     * @param probes   one entry per window, in the order they are tried
     */
    public void showProbes(@DrawableRes int iconRes, String headlineText, List<Probe> probes) {
        if (probes.isEmpty()) {
            hide();
            return;
        }
        if (iconRes != 0) {
            glyph.setImageResource(iconRes);
            glyph.setVisibility(VISIBLE);
            SubsIcons.spin(glyph);
        } else {
            SubsIcons.stopSpin(glyph);
            glyph.setVisibility(GONE);
        }
        headline.setText(headlineText != null ? headlineText : "");
        headline.setVisibility(headlineText == null || headlineText.isEmpty() ? GONE : VISIBLE);
        subline.setVisibility(GONE);
        bar.setVisibility(GONE); // the single-job bar; the columns carry their own

        buildProbeColumns(probes.size());
        for (int i = 0; i < probes.size(); i++) {
            ((ProbeColumn) probeRow.getChildAt(i)).bind(probes.get(i), probes.size());
        }
        probeRow.setVisibility(VISIBLE);
        setVisibility(VISIBLE);
    }

    /** Reuses the existing columns when the count hasn't changed — this is rebuilt on every render
     *  tick, and re-inflating four views ten times a second is work for nothing. */
    private void buildProbeColumns(int count) {
        if (probeRow.getChildCount() == count) return;
        probeRow.removeAllViews();
        Context c = getContext();
        for (int i = 0; i < count; i++) {
            ProbeColumn column = new ProbeColumn(c);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            int gap = SubsTheme.dp(c, 10);
            lp.leftMargin = i == 0 ? 0 : gap;
            probeRow.addView(column, lp);
        }
    }

    /** One window's label, phase and bar. */
    public static final class Probe {
        final String label;
        final String title;
        final float fraction;
        final boolean active;

        public Probe(String label, String title, float fraction, boolean active) {
            this.label = label;
            this.title = title;
            this.fraction = fraction;
            this.active = active;
        }
    }

    private static final class ProbeColumn extends LinearLayout {
        private final TextView label;
        private final TextView phase;
        private final SubsProgressLine bar;

        ProbeColumn(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);

            label = SubsTheme.labelSm(new TextView(c));
            label.setGravity(Gravity.CENTER);
            addView(label, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            phase = SubsTheme.bodyMd(new TextView(c));
            phase.setGravity(Gravity.CENTER);
            LayoutParams plp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            plp.topMargin = SubsTheme.dp(c, 4);
            addView(phase, plp);

            bar = new SubsProgressLine(c);
            LayoutParams blp = new LayoutParams(LayoutParams.MATCH_PARENT, SubsTheme.dp(c, 6));
            blp.topMargin = SubsTheme.dp(c, 8);
            addView(bar, blp);
        }

        /**
         * @param columnCount how many columns share the row. Four of them in the same width leaves
         *                    each about a quarter of what two get, and "Extracting audio" at the
         *                    two-column size wraps or clips there — so the type and the bar shrink to
         *                    fit rather than the text breaking.
         */
        void bind(Probe probe, int columnCount) {
            boolean tight = columnCount >= 3;
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tight ? 11f : 13f);
            phase.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, tight ? 13f : 17f);
            ViewGroup.LayoutParams blp = bar.getLayoutParams();
            int barHeight = SubsTheme.dp(getContext(), tight ? 4 : 6);
            if (blp.height != barHeight) {
                blp.height = barHeight;
                bar.setLayoutParams(blp);
            }
            label.setText(probe.label);
            // Dimmed rather than removed: a window that finished or was never needed still has to
            // hold its place, or the columns would shuffle sideways as the run progresses.
            label.setTextColor(probe.active ? SubsTheme.INK_2 : SubsTheme.INK_3);
            phase.setText(probe.title);
            phase.setTextColor(probe.active ? SubsTheme.ON_SURFACE : SubsTheme.INK_3);
            if (probe.fraction >= 0f) {
                bar.setVisibility(VISIBLE);
                bar.setFraction(probe.fraction);
            } else {
                bar.setVisibility(INVISIBLE); // INVISIBLE, not GONE: keeps every column the same height
            }
        }
    }

    public void hide() {
        SubsIcons.stopSpin(glyph);
        setVisibility(GONE);
    }
}
