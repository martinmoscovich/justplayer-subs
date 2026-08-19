package com.brouken.player.subs;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Draws the chunk-progress timeline described in the design plan: one horizontal segment per
 * translation chunk, colored by state, with optional time ticks and a "watchable from here" band
 * in {@link #setDetailed detailed} mode. Pure renderer — never touches the engine or
 * {@link TranslationController}'s state directly; it only draws whatever {@link Model} it's handed.
 * All state tracking (which segments exist, their real vs. estimated boundaries, live corrections)
 * lives in {@link ChunkTimelineTracker}.
 *
 * <p>Self-driving pulse animation: {@link #onDraw} computes its own phase from
 * {@link SystemClock#uptimeMillis()} and re-schedules itself via {@link #postInvalidateDelayed} only
 * while a pulsing segment (CLOSING or TRANSLATING) is present — a static model (e.g. all DONE) never
 * redraws on its own.
 */
public class ChunkProgressBarView extends View {

    public enum SegmentState { PENDING, EXTRACTING, CLOSING, CLOSED, TRANSLATING, DONE, FAILED }

    public static final class Segment {
        final long startMs;
        final long endMs;
        final SegmentState state;
        /** Whether {@code endMs} is an estimate (dashed boundary) rather than a confirmed real one. */
        final boolean endEstimated;
        /** Belongs to a lower-priority background pass (position-priority runs) — hatched texture. */
        final boolean backgroundPass;
        /** 0..1, meaningful only for {@link SegmentState#EXTRACTING}. */
        final float extractingFill;
        /** Short label for detailed mode only (e.g. "~55%"), or {@code null}. */
        @Nullable final String label;

        public Segment(long startMs, long endMs, SegmentState state, boolean endEstimated,
                       boolean backgroundPass, float extractingFill, @Nullable String label) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.state = state;
            this.endEstimated = endEstimated;
            this.backgroundPass = backgroundPass;
            this.extractingFill = extractingFill;
            this.label = label;
        }
    }

    public static final class Model {
        static final Model EMPTY = new Model(List.of(), 0L, -1L, -1L);

        final List<Segment> segments;
        final long totalDurationMs;
        /** -1 to hide the watch band entirely. */
        final long watchFromMs;
        final long watchUntilMs;

        public Model(List<Segment> segments, long totalDurationMs, long watchFromMs, long watchUntilMs) {
            this.segments = segments;
            this.totalDurationMs = totalDurationMs;
            this.watchFromMs = watchFromMs;
            this.watchUntilMs = watchUntilMs;
        }
    }

    private static final long PULSE_PERIOD_MS = 1400L;

    // TV-oriented sizing: viewed from a couch, not held in a hand — thick bar, big type.
    private static final float COMPACT_WIDTH_DP = 130f;
    private static final float BAR_THICK_COMPACT_DP = 12f;
    private static final float TICK_AREA_DP = 32f;
    private static final float BAR_THICK_DETAILED_DP = 32f;
    private static final float GAP_DP = 6f;
    private static final float WATCH_AREA_DP = 32f;
    /** Below this pixel width, a segment sharing its neighbor's state visually merges into it
     *  (no divider, no label) instead of reading as its own sliver — see the design plan. */
    private static final float MERGE_THRESHOLD_DP = 10f;

    private boolean detailed = false;
    private Model model = Model.EMPTY;

    private final Paint segPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint hatchPaint = new Paint();
    private final Paint tickTextPaint = new Paint();
    private final Paint tickLinePaint = new Paint();
    private final Paint watchPaint = new Paint();
    private final Paint watchTextPaint = new Paint();
    private final Paint labelPaint = new Paint();
    private final RectF rect = new RectF();

    // Fixed semantic colors — not theme-dependent, see the design plan's legend.
    private static final int COLOR_PENDING = 0xFF22303F;
    private static final int COLOR_EXTRACTING = 0xFF52626F;
    private static final int COLOR_CLOSED = 0xFFF4F7F9;
    private static final int COLOR_TRANSLATING = 0xFFE8B93F;
    private static final int COLOR_DONE = 0xFF4FBF7A;
    private static final int COLOR_FAILED = 0xFFD9584F;
    private static final int COLOR_ACCENT = 0xFF4FB8C9;

    public ChunkProgressBarView(Context context) {
        this(context, null);
    }

    public ChunkProgressBarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        segPaint.setAntiAlias(true);
        borderPaint.setAntiAlias(true);
        hatchPaint.setAntiAlias(true);
        tickTextPaint.setAntiAlias(true);
        tickLinePaint.setAntiAlias(true);
        watchPaint.setAntiAlias(true);
        watchTextPaint.setAntiAlias(true);
        labelPaint.setAntiAlias(true);
        borderPaint.setColor(0x33000000);
        borderPaint.setStrokeWidth(dp(1));
        hatchPaint.setColor(0x33FFFFFF);
        hatchPaint.setStrokeWidth(dp(1));
        tickTextPaint.setColor(0xFFB0BEC5);
        tickTextPaint.setTextSize(spToPx(15));
        tickLinePaint.setColor(0xFF223040);
        tickLinePaint.setStrokeWidth(dp(1));
        watchPaint.setColor(COLOR_ACCENT);
        watchPaint.setStrokeWidth(dp(3));
        watchTextPaint.setColor(COLOR_ACCENT);
        watchTextPaint.setTextSize(spToPx(15));
        labelPaint.setColor(0xFF10151A);
        labelPaint.setTextSize(spToPx(13));
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setDetailed(boolean detailed) {
        if (this.detailed == detailed) return;
        this.detailed = detailed;
        requestLayout();
        invalidate();
    }

    public void setModel(Model model) {
        this.model = model != null ? model : Model.EMPTY;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = detailed
                ? MeasureSpec.getSize(widthMeasureSpec)
                : Math.min(MeasureSpec.getSize(widthMeasureSpec), dp(COMPACT_WIDTH_DP));
        int height = detailed
                ? dp(TICK_AREA_DP) + dp(BAR_THICK_DETAILED_DP) + dp(GAP_DP) + dp(WATCH_AREA_DP)
                : dp(BAR_THICK_COMPACT_DP);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        List<Segment> segments = model.segments;
        long total = model.totalDurationMs;
        int w = getWidth();
        if (segments.isEmpty() || total <= 0 || w <= 0) return;

        float barTop = detailed ? dp(TICK_AREA_DP) : 0f;
        float barThick = detailed ? dp(BAR_THICK_DETAILED_DP) : dp(BAR_THICK_COMPACT_DP);
        float barBottom = barTop + barThick;

        long phase = SystemClock.uptimeMillis() % PULSE_PERIOD_MS;
        float pulse = pulseFactor(phase); // 0..1..0 triangular wave
        boolean anyPulsing = false;
        float mergeThresholdPx = dp(MERGE_THRESHOLD_DP);

        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            float left = xFor(seg.startMs, total, w);
            float right = xFor(seg.endMs, total, w);
            if (right <= left) continue;
            rect.set(left, barTop, right, barBottom);

            int color;
            switch (seg.state) {
                case DONE: color = COLOR_DONE; break;
                case TRANSLATING: color = blend(COLOR_TRANSLATING, darken(COLOR_TRANSLATING), pulse * 0.35f); anyPulsing = true; break;
                case CLOSED: color = COLOR_CLOSED; break;
                case CLOSING: color = blend(COLOR_EXTRACTING, COLOR_CLOSED, pulse); anyPulsing = true; break;
                case FAILED: color = COLOR_FAILED; break;
                case EXTRACTING:
                case PENDING:
                default: color = COLOR_PENDING; break;
            }
            segPaint.setColor(color);
            canvas.drawRect(rect, segPaint);

            if (seg.state == SegmentState.EXTRACTING && seg.extractingFill > 0f) {
                float fillRight = left + (right - left) * Math.min(1f, seg.extractingFill);
                segPaint.setColor(COLOR_EXTRACTING);
                canvas.drawRect(left, barTop, fillRight, barBottom, segPaint);
            }

            if (seg.backgroundPass) {
                drawHatch(canvas, left, barTop, right, barBottom);
            }

            // A short segment sharing its neighbor's state reads as one continuous block instead of
            // a sliver with its own divider — EXTRACTING is excluded, its fill amount differs
            // segment to segment even when the base state matches, so the divider still matters there.
            Segment next = (i + 1 < segments.size()) ? segments.get(i + 1) : null;
            boolean mergesWithNext = next != null && next.state == seg.state && seg.state != SegmentState.EXTRACTING
                    && (xFor(next.endMs, total, w) - right) < mergeThresholdPx;
            if (!mergesWithNext && right - left > dp(1)) {
                canvas.drawLine(right, barTop, right, barBottom,
                        seg.endEstimated ? dashedBorder() : borderPaint);
            }

            if (detailed && seg.label != null) {
                float textWidth = labelPaint.measureText(seg.label);
                if (right - left > textWidth + dp(8)) {
                    canvas.drawText(seg.label, (left + right) / 2f, barBottom - dp(9), labelPaint);
                }
            }
        }

        // Left edge of the bar (0:00-equivalent start) — always a plain divider, drawn once.
        canvas.drawLine(0, barTop, 0, barBottom, borderPaint);

        if (detailed) {
            drawTicks(canvas, segments, total, w, barTop);
            if (model.watchFromMs >= 0 && model.watchUntilMs >= model.watchFromMs) {
                drawWatchBand(canvas, total, w, barBottom);
            }
        }

        if (anyPulsing) postInvalidateDelayed(50L);
    }

    private void drawTicks(Canvas canvas, List<Segment> segments, long total, int w, float barTop) {
        float textBaseline = barTop - dp(8);
        float lastLabelRight = Float.NEGATIVE_INFINITY;

        String startLabel = formatDuration(0);
        canvas.drawText(startLabel, 2, textBaseline, tickTextPaint);
        lastLabelRight = 2 + tickTextPaint.measureText(startLabel);

        String endLabel = formatDuration(total);
        float endWidth = tickTextPaint.measureText(endLabel);
        float endLabelLeft = w - endWidth - 2;

        for (int i = 0; i < segments.size() - 1; i++) {
            // A boundary between two consecutive segments; skip if they don't actually touch (gap
            // between passes — the caller never produces one today, but drawing defensively costs nothing).
            Segment a = segments.get(i);
            Segment b = segments.get(i + 1);
            if (a.endMs != b.startMs) continue;
            float x = xFor(a.endMs, total, w);
            canvas.drawLine(x, barTop, x, barTop + dp(10), tickLinePaint);

            String label = (a.endEstimated ? "~" : "") + formatDuration(a.endMs);
            float tw = tickTextPaint.measureText(label);
            float tx = Math.max(2, Math.min(w - tw - 2, x - tw / 2f));
            // Crowded boundaries (many short chunks): the tick mark always draws, the number only
            // draws if it clears the previous label and won't run into the fixed end-of-bar label —
            // an omitted number beats an unreadable pile of overlapping ones.
            boolean clearsPrevious = tx > lastLabelRight + dp(6);
            boolean clearsEnd = tx + tw < endLabelLeft - dp(6);
            if (clearsPrevious && clearsEnd) {
                canvas.drawText(label, tx, textBaseline, tickTextPaint);
                lastLabelRight = tx + tw;
            }
        }

        canvas.drawText(endLabel, endLabelLeft, textBaseline, tickTextPaint);
    }

    private void drawWatchBand(Canvas canvas, long total, int w, float barBottom) {
        float x1 = xFor(model.watchFromMs, total, w);
        float x2 = xFor(model.watchUntilMs, total, w);
        float y = barBottom + dp(GAP_DP) + dp(10);
        canvas.drawLine(x1, y, x2, y, watchPaint);
        canvas.drawCircle(x1, y, dp(3.5f), watchPaint);
        canvas.drawCircle(x2, y, dp(3.5f), watchPaint);
        String label = "watchable until " + formatDuration(model.watchUntilMs);
        canvas.drawText(label, Math.min(x2 + dp(8), w - watchTextPaint.measureText(label) - 2),
                y + dp(6), watchTextPaint);
    }

    private void drawHatch(Canvas canvas, float left, float top, float right, float bottom) {
        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        float step = dp(6);
        for (float x = left - (bottom - top); x < right; x += step) {
            canvas.drawLine(x, bottom, x + (bottom - top), top, hatchPaint);
        }
        canvas.restore();
    }

    private Paint dashedBorder() {
        borderPaint.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(3)}, 0));
        return borderPaint;
    }

    private static float xFor(long ms, long total, int w) {
        return (float) ((double) ms / (double) total * w);
    }

    /** Triangular 0→1→0 wave over one {@link #PULSE_PERIOD_MS} period. */
    private static float pulseFactor(long phaseMs) {
        float t = phaseMs / (float) PULSE_PERIOD_MS;
        return t < 0.5f ? t * 2f : (1f - t) * 2f;
    }

    private static int blend(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = lerp(Color.alpha(from), Color.alpha(to), t);
        int r = lerp(Color.red(from), Color.red(to), t);
        int g = lerp(Color.green(from), Color.green(to), t);
        int b = lerp(Color.blue(from), Color.blue(to), t);
        return Color.argb(a, r, g, b);
    }

    private static int lerp(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }

    private static int darken(int color) {
        return blend(color, Color.BLACK, 0.25f);
    }

    /** {@code m:ss}, or {@code h:mm:ss} past the hour mark — mirrors {@code TranslationController}'s. */
    static String formatDuration(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%d:%02d", m, s);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private float spToPx(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics());
    }
}
