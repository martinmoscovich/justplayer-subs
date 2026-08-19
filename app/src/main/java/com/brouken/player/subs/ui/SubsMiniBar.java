package com.brouken.player.subs.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The postage-stamp version of the chunk bar, for the pills that float over the video: a row of
 * equal cells, 2dp apart, coloured with the same seven semantics. It says "roughly how far along,
 * and is anything wrong" from three metres, and nothing else — the real bar with its boundaries and
 * time marks lives inside the panel.
 *
 * <p>Cells are equal width on purpose. Chunk durations vary wildly, and a proportional strip 120dp
 * wide turns the short ones into invisible slivers; equal cells at least keep a failure visible.
 */
public class SubsMiniBar extends View {

    private static final float WIDTH_DP = 120f;
    private static final float HEIGHT_DP = 8f;
    private static final float GAP_DP = 2f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int[] colors = new int[0];
    private final float widthDp;

    public SubsMiniBar(Context c) {
        this(c, WIDTH_DP);
    }

    public SubsMiniBar(Context c, float widthDp) {
        super(c);
        this.widthDp = widthDp;
    }

    public void setColors(int[] colors) {
        this.colors = colors != null ? colors : new int[0];
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(SubsTheme.dp(getContext(), widthDp), SubsTheme.dp(getContext(), HEIGHT_DP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = colors.length;
        if (n == 0) return;
        float gap = SubsTheme.dpF(getResources(), GAP_DP);
        float r = SubsTheme.dpF(getResources(), 1f);
        float cell = (getWidth() - gap * (n - 1)) / n;
        if (cell <= 0) return;
        for (int i = 0; i < n; i++) {
            float left = i * (cell + gap);
            rect.set(left, 0, left + cell, getHeight());
            paint.setColor(colors[i]);
            canvas.drawRoundRect(rect, r, r, paint);
        }
    }
}
