package com.brouken.player.subs.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** The 6dp determinate bar the centered block shows while something is being read. Nothing else. */
public class SubsProgressLine extends View {

    private static final float HEIGHT_DP = 6f;
    private static final float WIDTH_DP = 280f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float fraction;

    public SubsProgressLine(Context c) {
        super(c);
    }

    /** 0..1; values outside are clamped, so a bad estimate can never draw past the rail. */
    public void setFraction(float f) {
        float clamped = Math.max(0f, Math.min(1f, f));
        if (clamped == fraction) return;
        fraction = clamped;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(
                Math.min(MeasureSpec.getSize(widthMeasureSpec), SubsTheme.dp(getContext(), WIDTH_DP)),
                SubsTheme.dp(getContext(), HEIGHT_DP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float h = getHeight();
        float r = h / 2f;
        paint.setColor(SubsTheme.SURFACE_4);
        rect.set(0, 0, getWidth(), h);
        canvas.drawRoundRect(rect, r, r, paint);
        if (fraction <= 0f) return;
        paint.setColor(SubsTheme.PRIMARY_CONTAINER);
        // Never narrower than the cap radius: a 1% fill would otherwise draw as a sliver of a
        // rounded rect, which reads as a rendering glitch rather than as "barely started".
        rect.set(0, 0, Math.max(h, getWidth() * fraction), h);
        canvas.drawRoundRect(rect, r, r, paint);
    }
}
