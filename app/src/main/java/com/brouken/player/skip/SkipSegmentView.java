package com.brouken.player.skip;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

/**
 * The "Skip intro" style button. Kept deliberately plain: a rounded, translucent pill that stays
 * out of the way of the transport controls and reads the same on a phone and on a TV.
 */
class SkipSegmentView extends TextView {

    private static final int COLOR_BACKGROUND = 0xB3000000;
    private static final int COLOR_BACKGROUND_FOCUSED = 0xF2FFFFFF;
    private static final int COLOR_BORDER = 0x80FFFFFF;
    private static final int COLOR_TEXT_FOCUSED = 0xFF000000;

    SkipSegmentView(@NonNull Context context) {
        super(context);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        setTextColor(Color.WHITE);
        setGravity(Gravity.CENTER);
        setPadding(dp(20), dp(10), dp(20), dp(10));
        setAllCaps(false);
        // Reachable with a D-pad on TV, but never grabs focus on its own: it appears mid-playback
        // and stealing focus there would hijack the remote.
        setFocusable(true);
        setFocusableInTouchMode(false);
        setClickable(true);
        applyBackground(false);
    }

    void setLabel(@StringRes int label) {
        setText(label);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        applyBackground(gainFocus);
    }

    private void applyBackground(boolean focused) {
        final GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(22));
        background.setColor(focused ? COLOR_BACKGROUND_FOCUSED : COLOR_BACKGROUND);
        background.setStroke(dp(1), COLOR_BORDER);
        setBackground(background);
        setTextColor(focused ? COLOR_TEXT_FOCUSED : Color.WHITE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
