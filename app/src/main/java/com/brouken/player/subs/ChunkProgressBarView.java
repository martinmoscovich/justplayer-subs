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

import com.brouken.player.subs.ui.SubsTheme;

/**
 * Draws the chunk-progress timeline described in the design plan: one horizontal segment per
 * translation chunk, colored by state, plus a playhead marker and (in {@link #setDetailed detailed}
 * mode) alternating time ticks. Pure renderer — never touches the engine or
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
        static final Model EMPTY = new Model(List.of(), 0L, -1L);

        final List<Segment> segments;
        final long totalDurationMs;
        /** Playhead marker; -1 to hide it. "How far can I watch from here" is reported as text
         *  elsewhere — the bar shows where you are, not what is reachable from there. */
        final long currentPositionMs;

        public Model(List<Segment> segments, long totalDurationMs, long currentPositionMs) {
            this.segments = segments;
            this.totalDurationMs = totalDurationMs;
            this.currentPositionMs = currentPositionMs;
        }
    }

    private static final long PULSE_PERIOD_MS = 1400L;

    // TV-oriented sizing: viewed from a couch, not held in a hand — thick bar, big type.
    private static final float COMPACT_WIDTH_DP = 130f;
    private static final float BAR_THICK_COMPACT_DP = 12f;
    private static final float TICK_AREA_DP = 32f;
    private static final float BAR_THICK_DETAILED_DP = 32f;
    private static final float GAP_DP = 6f;
    /** Room under the bar for the lower half of the alternating tick labels. */
    private static final float TICK_AREA_BELOW_DP = 30f;
    private static final float RADIUS_DETAILED_DP = 8f;
    private static final float RADIUS_COMPACT_DP = 4f;

    private boolean detailed = false;
    private Model model = Model.EMPTY;

    private final Paint segPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint hatchPaint = new Paint();
    private final Paint tickTextPaint = new Paint();
    private final Paint tickLinePaint = new Paint();
    private final Paint playheadPaint = new Paint();
    private final Paint playheadHaloPaint = new Paint();
    private final Paint railPaint = new Paint();
    private final Paint labelPaint = new Paint();
    private final RectF rect = new RectF();
    private final RectF barRect = new RectF();
    private final android.graphics.Path clipPath = new android.graphics.Path();
    private final android.graphics.Path playheadTip = new android.graphics.Path();

    // The bar's seven semantic colours (CLOSING has no constant of its own — it blends EXTRACTING and
    // CLOSED). They are the one part of the palette that is never reused
    // anywhere else in the panel, which is what lets the legend above the bar be read as a key.
    private static final int COLOR_PENDING = SubsTheme.STATUS_PENDING;
    private static final int COLOR_EXTRACTING = SubsTheme.STATUS_EXTRACTING;
    private static final int COLOR_CLOSED = SubsTheme.STATUS_CLOSED;
    private static final int COLOR_TRANSLATING = SubsTheme.STATUS_TRANSLATING;
    private static final int COLOR_DONE = SubsTheme.STATUS_DONE;
    private static final int COLOR_FAILED = SubsTheme.STATUS_FAILED;

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
        playheadPaint.setAntiAlias(true);
        labelPaint.setAntiAlias(true);
        // The divider between two segments is a hole punched in the bar, not a line drawn over it:
        // it has to read the same over green, amber and the near-white CLOSED.
        borderPaint.setColor(0xA60E1416);
        borderPaint.setStrokeWidth(dp(1));
        hatchPaint.setColor(0x38FFFFFF); // 22% white, per the design's hatch
        hatchPaint.setStrokeWidth(dp(1));
        tickTextPaint.setColor(SubsTheme.INK_3);
        tickTextPaint.setTextSize(spToPx(SubsTheme.LABEL_SM_SP));
        tickTextPaint.setTypeface(SubsTheme.bold(context));
        tickTextPaint.setLetterSpacing(SubsTheme.LABEL_SM_TRACK);
        tickLinePaint.setColor(SubsTheme.SURFACE_4);
        tickLinePaint.setStrokeWidth(dp(1));
        railPaint.setColor(SubsTheme.SURFACE_4);
        // White core over a black halo: the playhead has to be legible on all seven segment colours,
        // and one of them (CLOSED) is itself near-white. Contrast by luminance in both directions,
        // not by hue.
        playheadPaint.setColor(SubsTheme.INK);
        playheadPaint.setStrokeWidth(dp(3));
        playheadHaloPaint.setColor(0xFF05090A);
        playheadHaloPaint.setStrokeWidth(dp(5));
        playheadHaloPaint.setAntiAlias(true);
        labelPaint.setColor(SubsTheme.ON_SECONDARY);
        labelPaint.setTextSize(spToPx(SubsTheme.LABEL_SM_SP));
        labelPaint.setTypeface(SubsTheme.bold(context));
        labelPaint.setLetterSpacing(SubsTheme.LABEL_SM_TRACK);
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
                ? dp(TICK_AREA_DP) + dp(BAR_THICK_DETAILED_DP) + dp(GAP_DP) + dp(TICK_AREA_BELOW_DP)
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
        float radius = dp(detailed ? RADIUS_DETAILED_DP : RADIUS_COMPACT_DP);

        // The rail shows through wherever the segments do not reach — the same surface a scrollbar
        // track uses, so an unstarted run still reads as a bar rather than as nothing.
        barRect.set(0, barTop, w, barBottom);
        canvas.drawRoundRect(barRect, radius, radius, railPaint);
        canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(barRect, radius, radius, android.graphics.Path.Direction.CW);
        canvas.clipPath(clipPath);

        long phase = SystemClock.uptimeMillis() % PULSE_PERIOD_MS;
        float pulse = pulseFactor(phase); // 0..1..0 triangular wave
        boolean anyPulsing = false;

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

            // Consecutive DONE chunks merge into one continuous green run: once a chunk is finished
            // its boundary carries no information, and a row of dividers through the green reads as
            // structure that still matters. Every other state keeps its divider — there the boundary
            // is exactly what tells two neighbours apart (how far each has extracted, which one is
            // in flight, which one failed), even when they happen to share a state right now.
            Segment next = (i + 1 < segments.size()) ? segments.get(i + 1) : null;
            boolean mergesWithNext = next != null
                    && seg.state == SegmentState.DONE && next.state == SegmentState.DONE;
            if (!mergesWithNext && right - left > dp(1)) {
                canvas.drawLine(right, barTop, right, barBottom, borderPaintFor(seg.endEstimated));
            }

            if (detailed && seg.label != null) {
                float textWidth = labelPaint.measureText(seg.label);
                // Only if it fits: an omitted number beats one bleeding over its own segment's edges.
                if (right - left > textWidth + dp(8)) {
                    labelPaint.setColor(isDarkSegment(seg.state) ? SubsTheme.INK_2 : SubsTheme.ON_SECONDARY);
                    canvas.drawText(seg.label, (left + right) / 2f,
                            (barTop + barBottom) / 2f + dp(4), labelPaint);
                }
            }
        }

        canvas.restore();

        if (detailed) {
            drawTicks(canvas, segments, total, w, barTop, barBottom);
        }
        if (model.currentPositionMs >= 0) {
            drawPlayhead(canvas, total, w, barTop, barBottom);
        }

        if (anyPulsing) postInvalidateDelayed(50L);
    }

    /**
     * Boundary labels alternate between a row above the bar and a row below it, so each row only has
     * to clear its own previous label. Two rows give roughly twice the horizontal room, which is what
     * keeps the numbers readable once a run has many short chunks — before, everything competed for
     * one row and most labels had to be dropped to avoid overlapping.
     *
     * <p>Rows are assigned by boundary index, not by how many labels ended up drawn, so a label never
     * hops rows between frames just because a neighbour became too crowded to print.
     */
    private void drawTicks(Canvas canvas, List<Segment> segments, long total, int w,
                           float barTop, float barBottom) {
        float topBaseline = barTop - dp(8);
        float bottomBaseline = barBottom + dp(GAP_DP) + dp(16);

        // 0:00 and the total anchor the top row; every alternated label yields to them.
        String startLabel = formatDuration(0);
        canvas.drawText(startLabel, 2, topBaseline, tickTextPaint);
        float lastRightTop = 2 + tickTextPaint.measureText(startLabel);
        float lastRightBottom = Float.NEGATIVE_INFINITY;

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
            boolean above = (i % 2 == 0);
            // Same solid-vs-dashed rule as the segment divider this leader belongs to (borderPaintFor)
            // — a solid divider with a dashed leader (or vice versa) reads as the border style
            // changing partway across the bar, exactly where a run's real segments give way to its
            // still-estimated tail.
            Paint leaderPaint = tickLinePaintFor(a.endEstimated);
            if (above) {
                canvas.drawLine(x, barTop, x, barTop + dp(10), leaderPaint);
            } else {
                // Leader below the bar, so a label on the lower row still points at its boundary.
                canvas.drawLine(x, barBottom, x, barBottom + dp(GAP_DP), leaderPaint);
            }

            String label = (a.endEstimated ? "~" : "") + formatDuration(a.endMs);
            float tw = tickTextPaint.measureText(label);
            float tx = Math.max(2, Math.min(w - tw - 2, x - tw / 2f));
            // Crowded boundaries (many short chunks): the tick mark always draws, the number only
            // draws if it clears the previous label on its own row — and, on the top row, won't run
            // into the fixed end-of-bar label. An omitted number beats an unreadable pile.
            float lastRight = above ? lastRightTop : lastRightBottom;
            boolean clearsPrevious = tx > lastRight + dp(6);
            boolean clearsEnd = !above || tx + tw < endLabelLeft - dp(6);
            if (clearsPrevious && clearsEnd) {
                canvas.drawText(label, tx, above ? topBaseline : bottomBaseline, tickTextPaint);
                if (above) lastRightTop = tx + tw;
                else lastRightBottom = tx + tw;
            }
        }

        canvas.drawText(endLabel, endLabelLeft, topBaseline, tickTextPaint);
    }

    /**
     * Where playback currently is. Deliberately just a marker: "how far can I watch from here" is a
     * different question with a different answer, and reporting it as a band on this bar made the two
     * disagree on screen (see LESSONS.md) — it belongs in the status text instead.
     */
    private void drawPlayhead(Canvas canvas, long total, int w, float barTop, float barBottom) {
        float x = xFor(model.currentPositionMs, total, w);
        canvas.drawLine(x, barTop, x, barBottom, playheadHaloPaint);
        canvas.drawLine(x, barTop, x, barBottom, playheadPaint);
        if (!detailed) return;
        // A tip below the bar, so the marker still reads at a glance when the line itself is sitting
        // on a segment of a similar tone.
        float half = dp(6), h = dp(8);
        playheadTip.reset();
        playheadTip.moveTo(x - half, barBottom);
        playheadTip.lineTo(x + half, barBottom);
        playheadTip.lineTo(x, barBottom + h);
        playheadTip.close();
        playheadHaloPaint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(playheadTip, playheadHaloPaint);
        playheadPaint.setStyle(Paint.Style.FILL);
        canvas.drawPath(playheadTip, playheadPaint);
        playheadPaint.setStyle(Paint.Style.STROKE);
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

    /** {@code borderPaint} styled for one boundary — explicitly solid or dashed, never left over from
     *  whatever the previous boundary drawn set it to (a bare {@code borderPaint} reference used to
     *  silently inherit a dashed effect a prior estimated boundary had set and never cleared). */
    private Paint borderPaintFor(boolean estimated) {
        borderPaint.setPathEffect(estimated ? scaledDash() : null);
        return borderPaint;
    }

    /** {@code tickLinePaint} styled the same solid-vs-dashed way as {@link #borderPaintFor} — the short
     *  leader stub outside the bar has to match the segment divider it belongs to, or the boundary
     *  reads as two different styles once the bar has both real and estimated segments on screen. */
    private Paint tickLinePaintFor(boolean estimated) {
        tickLinePaint.setPathEffect(estimated ? scaledDash() : null);
        return tickLinePaint;
    }

    private DashPathEffect scaledDash() {
        float d = dp(3);
        return new DashPathEffect(new float[]{d, d}, 0);
    }

    /** Whether a segment's fill is dark enough that ink on it has to be light rather than near-black. */
    private static boolean isDarkSegment(SegmentState state) {
        return state == SegmentState.PENDING || state == SegmentState.EXTRACTING;
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
