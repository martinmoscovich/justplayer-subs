package com.brouken.player.subs;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import com.brouken.player.R;
import com.brouken.player.subs.ui.SubsButton;
import com.brouken.player.subs.ui.SubsCenteredBlock;
import com.brouken.player.subs.ui.SubsClock;
import com.brouken.player.subs.ui.SubsFigurePanel;
import com.brouken.player.subs.ui.SubsIcons;
import com.brouken.player.subs.ui.SubsShapes;
import com.brouken.player.subs.ui.SubsSwatch;
import com.brouken.player.subs.ui.SubsTheme;

/**
 * The Translate UI. Everything it shows is precomputed by {@link TranslationController} into a
 * {@link TranslateUiState}; this view only lays it out and turns key presses into {@link Listener}
 * calls. Mirrors {@link SyncView}'s contract (setListener / setFocused / handleKey / reset).
 *
 * <p>Its governing rule is subtractive: <b>only what the bar and the chips cannot say gets written
 * down</b>. "2 in progress" is two amber segments, "1 failed" is a red one, "translating now" is the
 * pulse — all of that used to be prose and is now simply drawn. What is left is what a picture
 * genuinely cannot carry: how far you can watch, how far along the run is, and the money.
 */
public class TranslateView extends FrameLayout {

    public interface Listener {
        void onStartTranslate();
        void onCancelTranslate();
        /** Freeze the run without discarding what it already produced. */
        void onPauseTranslate();
        void onResumeTranslate();
        void onRestoreOriginal();
        /** Finished row: drop the cached translation and pay for a fresh one. */
        void onTranslateAgain();
        /** Partial result: re-request only the chunks that failed, keeping the ones already paid for. */
        void onRetryMissing();
        /** ◄ past the leftmost button, or Back: move focus to the sidebar. */
        void onOpenMenu();
        /** Done button: close the whole panel. */
        void onRequestClose();
    }

    private static final class Btn {
        final String label;
        final int icon;
        final Runnable action;
        Btn(String label, int icon, Runnable action) {
            this.label = label;
            this.icon = icon;
            this.action = action;
        }
    }

    private final LinearLayout column;
    private final LinearLayout resultHeader;
    private final ImageView resultRing;
    private final TextView resultTitle;
    private final TextView resultDetail;
    private final LinearLayout panelRow;
    private final List<SubsFigurePanel> panelViews = new ArrayList<>();
    private final LinearLayout legendRow;
    private final FrameLayout chunkBarHost;
    private final LinearLayout pillRow;
    private final SubsCenteredBlock centeredBlock;
    private final SubsClock clock;
    private final LinearLayout buttonRow;

    private Listener listener;
    private final List<Btn> buttons = new ArrayList<>();
    private final List<SubsButton> buttonViews = new ArrayList<>();
    private int buttonIndex = 0;
    private boolean hasFocus = true;
    private ButtonState buttonState = ButtonState.IDLE;
    private boolean canRetryMissing;
    /** What a blocking step (reading an embedded track) says over this screen's own state. */
    @Nullable private String busyTitle;
    private float busyFraction = -1f;
    @Nullable private TranslateUiState state;

    public TranslateView(Context context) {
        super(context);

        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), dp(20), dp(24), dp(14));
        addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Bottom-right, overlaid on the root FrameLayout. The button row below is centred, so the
        // corner stays clear of it.
        clock = new SubsClock(context);
        FrameLayout.LayoutParams clockLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clockLp.gravity = Gravity.BOTTOM | Gravity.END;
        clockLp.bottomMargin = dp(14);
        clockLp.rightMargin = dp(24);
        addView(clock, clockLp);

        // --- result header: a ring, a headline, a detail line ---
        resultHeader = new LinearLayout(context);
        resultHeader.setOrientation(LinearLayout.HORIZONTAL);
        resultHeader.setVisibility(GONE);
        resultRing = SubsIcons.icon(context, R.drawable.subtitle_ic_check, SubsTheme.STATUS_DONE, 22f);
        FrameLayout ring = new FrameLayout(context);
        ring.setBackground(SubsShapes.rounded(context, SubsTheme.SURFACE_2, 0x594FBF7A, 22f));
        FrameLayout.LayoutParams ringIconLp = new FrameLayout.LayoutParams(dp(22), dp(22));
        ringIconLp.gravity = Gravity.CENTER;
        ring.addView(resultRing, ringIconLp);
        LinearLayout.LayoutParams ringLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        ringLp.rightMargin = dp(14);
        resultHeader.addView(ring, ringLp);

        LinearLayout resultText = new LinearLayout(context);
        resultText.setOrientation(LinearLayout.VERTICAL);
        resultTitle = SubsTheme.headlineMd(new TextView(context));
        resultTitle.setTextColor(SubsTheme.ON_SURFACE);
        resultText.addView(resultTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        resultDetail = SubsTheme.bodyMd(new TextView(context));
        resultDetail.setTextColor(SubsTheme.INK_2);
        LinearLayout.LayoutParams rdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rdLp.topMargin = dp(4);
        resultText.addView(resultDetail, rdLp);
        resultHeader.addView(resultText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.bottomMargin = dp(16);
        column.addView(resultHeader, headerLp);

        // --- figure panels ---
        panelRow = new LinearLayout(context);
        panelRow.setOrientation(LinearLayout.HORIZONTAL);
        panelRow.setVisibility(GONE);
        column.addView(panelRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- legend, bar, pills: one block, because they are one reading ---
        legendRow = new LinearLayout(context);
        legendRow.setOrientation(LinearLayout.HORIZONTAL);
        legendRow.setVisibility(GONE);
        LinearLayout.LayoutParams legendLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        legendLp.topMargin = dp(44);
        column.addView(legendRow, legendLp);

        chunkBarHost = new FrameLayout(context);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(12);
        column.addView(chunkBarHost, barLp);

        pillRow = new LinearLayout(context);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pillRow.setGravity(Gravity.CENTER);
        pillRow.setVisibility(GONE);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.topMargin = dp(20);
        column.addView(pillRow, pillLp);

        // --- the centred block (idle / unavailable / reading), and the button row ---
        centeredBlock = new SubsCenteredBlock(context);
        centeredBlock.setVisibility(GONE);
        column.addView(centeredBlock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View filler = new View(context);
        column.addView(filler, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        column.addView(buttonRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rebuildButtons(ButtonState.IDLE);
    }

    /** Driven by the panel's playback tick, same as {@link com.brouken.player.subs.SyncView}. */
    public void onTick(long positionMs) {
        clock.setPositionMs(positionMs);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** Hands over {@link TranslationController#getDetailedBarView()} — called once, after this view
     *  and {@code TranslationController} both exist (see {@code CustomSubtitleController}'s constructor). */
    public void setChunkBar(View bar) {
        chunkBarHost.removeAllViews();
        chunkBarHost.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * A blocking step this screen is waiting on before it can do its own work — reading an embedded
     * track's cues out of the container. It takes the whole screen, because until it finishes there is
     * no translation state worth reporting; a {@code null} title gives the screen back.
     */
    public void setBusyStatus(@Nullable String title, float fraction) {
        this.busyTitle = title;
        this.busyFraction = fraction;
        // The blocking step has no ButtonState of its own — without forcing RUNNING here, "Translate"
        // stays on screen (and re-presses as "start another run") with no way to cancel what is
        // already in flight underneath.
        applyButtonState(title != null ? ButtonState.RUNNING
                : (state != null ? state.buttons : ButtonState.IDLE));
        render();
    }

    /** Pushes the current translation state. Buttons are only rebuilt (and focus reset) on a row change. */
    public void setState(TranslateUiState next) {
        this.state = next;
        this.canRetryMissing = next.canRetryMissing;
        if (busyTitle == null) applyButtonState(next.buttons);
        render();
    }

    public void setFocused(boolean f) {
        hasFocus = f;
        updateButtons();
    }

    public void reset() {
        buttonIndex = 0;
        updateButtons();
    }

    public boolean handleKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (buttonIndex == 0) {
                    if (listener != null) listener.onOpenMenu();
                } else {
                    buttonIndex--;
                    updateButtons();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (buttonIndex < buttons.size() - 1) {
                    buttonIndex++;
                    updateButtons();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (buttonIndex >= 0 && buttonIndex < buttons.size()) {
                    buttons.get(buttonIndex).action.run();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (listener != null) listener.onOpenMenu();
                return true;
            default:
                return false;
        }
    }

    // --- rendering ---

    private void render() {
        if (busyTitle != null) {
            showOnlyBlock(R.drawable.subtitle_ic_subtitles, true, busyTitle,
                    busyFraction >= 0f ? Math.round(busyFraction * 100) + "%" : null, busyFraction);
            return;
        }
        TranslateUiState s = state;
        if (s == null) {
            showOnlyBlock(0, false, "", null, -1f);
            return;
        }
        switch (s.mode) {
            case UNAVAILABLE:
                showOnlyBlock(R.drawable.subtitle_ic_unavailable, false, s.blockTitle, s.blockSub, -1f);
                return;
            case IDLE:
                showOnlyBlock(R.drawable.subtitle_ic_translate, false, s.blockTitle, s.blockSub, -1f);
                return;
            default:
                break;
        }
        centeredBlock.hide();
        renderResultHeader(s);
        renderPanels(s);
        renderLegend(s);
        chunkBarHost.setVisibility(s.showBar ? VISIBLE : GONE);
        renderPills(s);
    }

    /** The three "nothing is on the bar" states share one component and differ only in their words. */
    private void showOnlyBlock(int icon, boolean spinning, @Nullable String title, @Nullable String sub,
                               float fraction) {
        resultHeader.setVisibility(GONE);
        panelRow.setVisibility(GONE);
        legendRow.setVisibility(GONE);
        chunkBarHost.setVisibility(GONE);
        pillRow.setVisibility(GONE);
        centeredBlock.show(icon, spinning, title != null ? title : "", sub, fraction);
    }

    private void renderResultHeader(TranslateUiState s) {
        if (s.resultTitle == null) {
            resultHeader.setVisibility(GONE);
            return;
        }
        resultHeader.setVisibility(VISIBLE);
        resultHeader.setAlpha(s.tone == TranslateUiState.Tone.MUTED ? .55f : 1f);
        resultTitle.setText(s.resultTitle);
        resultDetail.setText(s.resultDetail != null ? s.resultDetail : "");
        resultDetail.setVisibility(s.resultDetail == null || s.resultDetail.isEmpty() ? GONE : VISIBLE);

        int tint;
        int icon;
        switch (s.tone) {
            case WARN:
                tint = SubsTheme.TERTIARY;
                icon = R.drawable.subtitle_ic_warning;
                break;
            case ERROR:
                tint = SubsTheme.ERROR;
                icon = R.drawable.subtitle_ic_close;
                break;
            case MUTED:
                tint = SubsTheme.INK_2;
                icon = R.drawable.subtitle_ic_close;
                break;
            case OK:
            default:
                tint = SubsTheme.STATUS_DONE;
                icon = R.drawable.subtitle_ic_check;
                break;
        }
        resultRing.setImageResource(icon);
        SubsIcons.tint(resultRing, tint);
        resultDetail.setTextColor(s.tone == TranslateUiState.Tone.WARN ? SubsTheme.TERTIARY
                : s.tone == TranslateUiState.Tone.ERROR ? SubsTheme.ERROR : SubsTheme.INK_2);
        ((View) resultRing.getParent()).setBackground(SubsShapes.rounded(getContext(),
                SubsTheme.SURFACE_2, (tint & 0x00FFFFFF) | 0x59000000, 22f));
    }

    private void renderPanels(TranslateUiState s) {
        if (s.panels.isEmpty()) {
            panelRow.setVisibility(GONE);
            return;
        }
        panelRow.setVisibility(VISIBLE);
        while (panelViews.size() < s.panels.size()) {
            SubsFigurePanel p = new SubsFigurePanel(getContext());
            panelViews.add(p);
            panelRow.addView(p, p.rowParams(panelRow.getChildCount() == 0));
        }
        for (int i = 0; i < panelViews.size(); i++) {
            SubsFigurePanel view = panelViews.get(i);
            if (i < s.panels.size()) {
                TranslateUiState.Panel p = s.panels.get(i);
                view.set(p.label, p.figure, p.sub, p.accent);
                view.setVisibility(VISIBLE);
            } else {
                view.setVisibility(GONE);
            }
        }
    }

    private void renderLegend(TranslateUiState s) {
        legendRow.removeAllViews();
        if (s.legend.isEmpty()) {
            legendRow.setVisibility(GONE);
            return;
        }
        legendRow.setVisibility(VISIBLE);
        for (TranslateUiState.Legend l : s.legend) {
            LinearLayout item = new LinearLayout(getContext());
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            SubsSwatch sw = new SubsSwatch(getContext(), 9f, 2f);
            sw.set(l.color, l.hatched);
            LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(dp(9), dp(9));
            swLp.rightMargin = dp(7);
            item.addView(sw, swLp);
            TextView tv = SubsTheme.labelSm(new TextView(getContext()));
            tv.setText(l.text);
            tv.setTextColor(SubsTheme.INK_2);
            item.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (legendRow.getChildCount() > 0) itemLp.leftMargin = dp(18);
            legendRow.addView(item, itemLp);
        }
    }

    private void renderPills(TranslateUiState s) {
        pillRow.removeAllViews();
        if (s.pills.isEmpty()) {
            pillRow.setVisibility(GONE);
            return;
        }
        pillRow.setVisibility(VISIBLE);
        for (TranslateUiState.Pill p : s.pills) {
            LinearLayout pill = new LinearLayout(getContext());
            pill.setOrientation(LinearLayout.HORIZONTAL);
            pill.setGravity(Gravity.CENTER_VERTICAL);
            pill.setBackground(SubsShapes.rounded(getContext(), SubsTheme.SURFACE_3, SubsTheme.EDGE, 13f));
            pill.setPadding(dp(12), 0, dp(12), 0);

            SubsSwatch dot = new SubsSwatch(getContext(), 8f, 4f);
            dot.set(p.color, false);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
            dotLp.rightMargin = dp(7);
            pill.addView(dot, dotLp);

            TextView tv = SubsTheme.labelSm(new TextView(getContext()));
            tv.setText(p.text);
            tv.setTextColor(SubsTheme.INK);
            pill.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            if (p.suffix != null) {
                TextView sfx = SubsTheme.labelSm(new TextView(getContext()));
                sfx.setText(" · " + p.suffix);
                sfx.setTextColor(SubsTheme.INK_2);
                pill.addView(sfx, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(26));
            if (pillRow.getChildCount() > 0) lp.leftMargin = dp(8);
            pillRow.addView(pill, lp);
        }
    }

    // --- button row ---

    private void applyButtonState(ButtonState next) {
        if (!rowKind(next).equals(rowKind(buttonState)) || rowNeedsRebuild(next)) {
            buttonState = next;
            rebuildButtons(next);
        } else {
            buttonState = next;
        }
    }

    /** The partial row gains a button the other finished rows don't have, so it must rebuild for it. */
    private boolean rowNeedsRebuild(ButtonState next) {
        boolean wantsRetry = next == ButtonState.FINISHED_WARNING && canRetryMissing;
        boolean hasRetry = !buttons.isEmpty() && buttons.get(0).action == retryMissingAction;
        return wantsRetry != hasRetry;
    }

    /** Coarse row shape: the FINISHED_* variants share one row, so they don't reset focus on switch. */
    private static Object rowKind(ButtonState s) {
        switch (s) {
            case FINISHED_OK:
            case FINISHED_WARNING:
            case FINISHED_ERROR:
            case FINISHED_CANCELLED:
                return "FINISHED";
            default:
                return s;
        }
    }

    private final Runnable retryMissingAction = this::retryMissing;

    private void rebuildButtons(ButtonState state) {
        buttons.clear();
        switch (state) {
            case IDLE:
                buttons.add(new Btn("Translate", 0, this::startTranslate));
                buttons.add(new Btn("Done", 0, this::requestClose));
                break;
            case RUNNING:
                // Pause first and focused: it is the reversible one. Cancel throws away the partial
                // translation the run has already paid for, so it must not be what a stray press hits.
                buttons.add(new Btn("Pause", 0, this::pauseTranslate));
                buttons.add(new Btn("Cancel", 0, this::cancelTranslate));
                buttons.add(new Btn("Done", 0, this::requestClose));
                break;
            case PAUSED:
                buttons.add(new Btn("Resume", 0, this::resumeTranslate));
                buttons.add(new Btn("Cancel", 0, this::cancelTranslate));
                buttons.add(new Btn("Done", 0, this::requestClose));
                break;
            case UNAVAILABLE:
                buttons.add(new Btn("Done", 0, this::requestClose));
                break;
            case FINISHED_WARNING:
                if (canRetryMissing) {
                    // The one documented exception to "the destructive option never starts focused":
                    // this one destroys nothing. It re-requests only the chunks that failed, leaving
                    // every chunk already paid for alone, and it is what the user came here to do.
                    buttons.add(new Btn("Retry missing lines", R.drawable.subtitle_ic_retry, retryMissingAction));
                }
                buttons.add(new Btn("Done", 0, this::requestClose));
                buttons.add(new Btn("Translate again", 0, this::translateAgain));
                buttons.add(new Btn("Restore original", 0, this::restoreOriginal));
                break;
            case FINISHED_OK:
            case FINISHED_ERROR:
            case FINISHED_CANCELLED:
            default:
                // "Restore original" is secondary and must never hold default focus — it throws away
                // a translation the user paid for. Done comes first.
                buttons.add(new Btn("Done", 0, this::requestClose));
                buttons.add(new Btn("Translate again", 0, this::translateAgain));
                buttons.add(new Btn("Restore original", 0, this::restoreOriginal));
                break;
        }
        buttonIndex = 0;

        buttonRow.removeAllViews();
        buttonViews.clear();
        for (Btn b : buttons) {
            SubsButton view = new SubsButton(getContext(), b.label, b.icon);
            buttonRow.addView(view, view.rowParams());
            buttonViews.add(view);
        }
        updateButtons();
    }

    private void updateButtons() {
        for (int i = 0; i < buttonViews.size(); i++) {
            buttonViews.get(i).setFocusedState(hasFocus && i == buttonIndex);
            buttonViews.get(i).setDimmed(!hasFocus);
        }
    }

    // --- actions ---

    private void startTranslate() {
        if (listener != null) listener.onStartTranslate();
    }

    private void cancelTranslate() {
        if (listener != null) listener.onCancelTranslate();
    }

    private void pauseTranslate() {
        if (listener != null) listener.onPauseTranslate();
    }

    private void resumeTranslate() {
        if (listener != null) listener.onResumeTranslate();
    }

    private void translateAgain() {
        if (listener != null) listener.onTranslateAgain();
    }

    private void retryMissing() {
        if (listener != null) listener.onRetryMissing();
    }

    private void restoreOriginal() {
        if (listener != null) listener.onRestoreOriginal();
    }

    private void requestClose() {
        if (listener != null) listener.onRequestClose();
    }

    private int dp(int v) {
        return SubsTheme.dp(getContext(), v);
    }
}
