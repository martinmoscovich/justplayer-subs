package com.brouken.player.subs.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The little coloured square (or dot) that stands in for a chunk-bar state — in the legend above the
 * bar and inside each chunk pill below it. Carries the 45° hatch too, because "background pass" is
 * an overlay on a state rather than a state of its own, and the key has to say so the same way the
 * bar does.
 */
public class SubsSwatch extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float sizeDp;
    private final float radiusDp;
    private int color;
    private boolean hatched;

    public SubsSwatch(Context c, float sizeDp, float radiusDp) {
        super(c);
        this.sizeDp = sizeDp;
        this.radiusDp = radiusDp;
    }

    public void set(int color, boolean hatched) {
        this.color = color;
        this.hatched = hatched;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int px = SubsTheme.dp(getContext(), sizeDp);
        setMeasuredDimension(px, px);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float r = SubsTheme.dpF(getResources(), radiusDp);
        rect.set(0, 0, getWidth(), getHeight());
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        canvas.drawRoundRect(rect, r, r, paint);
        if (!hatched) return;
        canvas.save();
        canvas.clipRect(rect);
        paint.setColor(0x38FFFFFF); // the bar's 22% white, same texture at a smaller scale
        paint.setStrokeWidth(SubsTheme.dpF(getResources(), 1f));
        float step = SubsTheme.dpF(getResources(), 3f);
        for (float x = -getHeight(); x < getWidth(); x += step) {
            canvas.drawLine(x, getHeight(), x + getHeight(), 0, paint);
        }
        canvas.restore();
    }
}
