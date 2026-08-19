package com.brouken.player.subs.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Collections;

/**
 * A 34dp pill in the tab row. Same three-way distinction as everything else in the panel — focus is
 * white, the tab you are on is cyan — plus a fourth state the tab row needs and buttons do not:
 * <b>dead</b>, for a tab whose group has no options at all. A dead tab keeps its label (so the user
 * can see the group exists and is empty) but loses its fill and nearly all of its border, and the
 * ◄ ► walk refuses to enter it.
 */
public class SubsTab extends TextView {

    private static final float HEIGHT_DP = 34f;

    private final GradientDrawable bg;
    private boolean focused;
    private boolean active;
    private boolean dead;
    @Nullable private SubsFocus.Skin painted;

    public SubsTab(Context c, String text) {
        super(c);
        SubsTheme.labelLg(this);
        setText(text);
        setSingleLine(true);
        setGravity(Gravity.CENTER);
        int padH = SubsTheme.dp(c, 22);
        setPadding(padH, 0, padH, 0);
        bg = SubsShapes.capsule(c, SubsTheme.SURFACE_2, SubsTheme.EDGE, HEIGHT_DP);
        restyle(false);
    }

    public LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, SubsTheme.dp(getContext(), HEIGHT_DP));
        p.rightMargin = SubsTheme.dp(getContext(), 10);
        return p;
    }

    public void setState(boolean focused, boolean active, boolean dead, boolean animate) {
        if (this.focused == focused && this.active == active && this.dead == dead) return;
        this.focused = focused;
        this.active = active;
        this.dead = dead;
        restyle(animate);
    }

    private void restyle(boolean animate) {
        SubsFocus.Skin to;
        if (dead) {
            to = new SubsFocus.Skin(0x00000000, 0x0DFFFFFF, SubsTheme.DISABLED);
        } else if (focused) {
            to = new SubsFocus.Skin(SubsTheme.INK, SubsTheme.INK, SubsTheme.ON_SECONDARY);
        } else if (active) {
            to = new SubsFocus.Skin(SubsTheme.PRIMARY_20, SubsTheme.PRIMARY_55, SubsTheme.PRIMARY);
        } else {
            to = new SubsFocus.Skin(SubsTheme.SURFACE_2, SubsTheme.EDGE, SubsTheme.INK_2);
        }
        SubsFocus.apply(this, bg, painted, to, Collections.<TextView>singletonList(this), animate, 1f);
        painted = to;
    }
}
