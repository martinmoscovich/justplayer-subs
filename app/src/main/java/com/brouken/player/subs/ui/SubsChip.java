package com.brouken.player.subs.ui;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The small labels that replace the double-space-concatenated meta text the rows used to carry.
 * Fixed 20dp height, {@code label-sm}, and three shapes that each encode a different kind of fact —
 * so the shape alone says what sort of thing you are reading before you read it:
 *
 * <ul>
 *   <li><b>Outlined, 4dp</b> ({@link Kind#FORMAT}) — a property of the file.</li>
 *   <li><b>Amber fill, 4dp</b> ({@link Kind#MATCH}) — where the match came from.</li>
 *   <li><b>Capsule</b> ({@link Kind#STAT}, {@link Kind#STATE}, {@link Kind#BUSY}, {@link Kind#FAIL})
 *       — reputation or state.</li>
 * </ul>
 *
 * <p>Each kind carries a second palette for when it sits on the white focus fill. Without it the
 * chips would wash out exactly when the user is looking at that row.
 */
public class SubsChip extends TextView {

    public enum Kind { FORMAT, MATCH, STAT, STATE, BUSY, FAIL }

    private static final float HEIGHT_DP = 20f;

    private final Kind kind;
    private boolean onLight;

    public SubsChip(Context c, Kind kind, String text) {
        super(c);
        this.kind = kind;
        SubsTheme.labelSm(this);
        setText(text);
        setSingleLine(true);
        setGravity(Gravity.CENTER);
        int padH = SubsTheme.dp(c, capsule() ? 9 : 8);
        setPadding(padH, 0, padH, 0);
        setHeight(SubsTheme.dp(c, HEIGHT_DP));
        restyle();
    }

    /** Layout params with the 8dp gap the chip rows use between neighbours. */
    public LinearLayout.LayoutParams gapParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, SubsTheme.dp(getContext(), HEIGHT_DP));
        p.leftMargin = SubsTheme.dp(getContext(), 8);
        p.gravity = Gravity.CENTER_VERTICAL;
        return p;
    }

    /** Switches to the dark-on-light palette — call when the containing row takes the focus fill. */
    public void setOnLight(boolean onLight) {
        if (this.onLight == onLight) return;
        this.onLight = onLight;
        restyle();
    }

    private boolean capsule() {
        return kind != Kind.FORMAT && kind != Kind.MATCH;
    }

    private void restyle() {
        int fill, stroke, ink;
        switch (kind) {
            case FORMAT:
                fill = 0x00000000;
                stroke = onLight ? SubsTheme.ON_LIGHT_BORDER : 0x33FFFFFF;
                ink = onLight ? SubsTheme.ON_LIGHT_MUTED : SubsTheme.INK_3;
                break;
            case MATCH:
                fill = onLight ? SubsTheme.ON_LIGHT_MATCH_FILL : SubsTheme.TERTIARY_10;
                stroke = 0;
                ink = onLight ? SubsTheme.ON_LIGHT_MATCH : SubsTheme.TERTIARY;
                break;
            case STAT:
                fill = onLight ? SubsTheme.ON_LIGHT_FILL : 0x1AFFFFFF;
                stroke = 0;
                ink = onLight ? SubsTheme.ON_LIGHT_MUTED : SubsTheme.INK_2;
                break;
            case STATE:
                fill = onLight ? SubsTheme.ON_LIGHT_STATE_FILL : SubsTheme.PRIMARY_20;
                stroke = onLight ? SubsTheme.ON_LIGHT_STATE_BORDER : SubsTheme.PRIMARY_30;
                ink = onLight ? SubsTheme.ON_LIGHT_STATE : SubsTheme.PRIMARY;
                break;
            case BUSY:
                fill = onLight ? SubsTheme.ON_LIGHT_MATCH_FILL : SubsTheme.TERTIARY_10;
                stroke = 0;
                ink = onLight ? SubsTheme.ON_LIGHT_MATCH : SubsTheme.TERTIARY;
                break;
            case FAIL:
            default:
                // Deliberately unchanged on the focus fill: dark red on white already reads, and a
                // failure is the one chip that must not soften when the cursor lands on it.
                fill = SubsTheme.ERROR_CONTAINER;
                stroke = 0;
                ink = SubsTheme.ON_ERROR_CONTAINER;
                break;
        }
        setBackground(SubsShapes.rounded(getContext(), fill, stroke,
                capsule() ? SubsTheme.RADIUS_PILL_DP : SubsTheme.RADIUS_CHIP_DP));
        setTextColor(ink);
    }
}
