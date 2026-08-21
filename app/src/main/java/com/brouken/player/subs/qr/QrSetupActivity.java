package com.brouken.player.subs.qr;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.brouken.player.subs.ui.SubsButton;
import com.brouken.player.subs.ui.SubsTheme;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.brouken.player.subs.SubtitleSettings;

/**
 * "Scan this to set up the player" screen. Starts a single-use {@link QrSetupServer} on the local
 * network, shows a QR code pointing at it, and writes back whatever the phone submits.
 *
 * <p>No layout XML, built in code — same convention as {@link com.brouken.player.subs.LanguageOrderActivity},
 * the other standalone settings screen in this package.
 */
public class QrSetupActivity extends AppCompatActivity implements QrSetupServer.Listener {

    private static final String TAG = "QrSetup";
    private static final long SESSION_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long TICK_INTERVAL_MS = 1000L;
    private static final int QR_SIZE_PX = 600;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = this::onTick;

    @Nullable private QrSetupServer server;
    private TextView countdownView;
    private TextView statusView;
    private SubsButton cancelButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String ip = discoverLocalIPv4();
        if (ip == null) {
            setContentView(buildErrorView("Could not find a network address — "
                    + "check the TV is connected to Wi-Fi or Ethernet."));
            return;
        }

        String token = UUID.randomUUID().toString();
        String html = loadHtml();

        AtomicReference<QrSetupServer> serverRef = new AtomicReference<>();
        server = new QrSetupServer(token, html,
                () -> QrSetupConfigJson.build(this, serverRef.get().deadlineMs()),
                this, SESSION_TTL_MS, System::currentTimeMillis);
        serverRef.set(server);

        try {
            server.start();
        } catch (IOException e) {
            Log.e(TAG, "could not start the QR setup server", e);
            setContentView(buildErrorView("Could not start the setup server."));
            server = null;
            return;
        }

        String url = "http://" + ip + ":" + server.getListeningPort() + "/?t=" + token;
        Log.i(TAG, "QR setup URL: " + url);

        setContentView(buildMainView(url));
        mainHandler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(ticker);
        if (server != null) server.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(ticker);
        if (server != null) server.stop();
    }

    /** Called on the server's own request-handling thread — never assume the main thread here. */
    @Override
    public void onSubmitted(Map<String, String> fields) {
        mainHandler.post(() -> {
            // Cancel first: the socket doesn't actually close for ~250ms after this (see
            // QrSetupServer.scheduleStop), and a stray tick in that window must not read the still-open
            // server as a timeout instead of the success it actually is.
            mainHandler.removeCallbacks(ticker);
            applySettings(fields);
            Toast.makeText(this, "Update successful", Toast.LENGTH_LONG).show();
            finish();
        });
    }

    private void applySettings(Map<String, String> fields) {
        SharedPreferences.Editor editor = SubtitleSettings.prefs(this).edit();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (SubtitleSettings.KEY_AUTO_SELECT.equals(e.getKey())) {
                editor.putBoolean(SubtitleSettings.KEY_AUTO_SELECT, "true".equals(e.getValue()));
            } else {
                // Every other field — including the two ordered-language lists, already CSV-joined by
                // the page's JS — is stored exactly as SubtitleSettings itself stores it: a raw string
                // under the matching key.
                editor.putString(e.getKey(), e.getValue());
            }
        }
        editor.apply();
    }

    private void onTick() {
        if (server == null) return;
        server.tick();
        long remainingMs = Math.max(0, server.deadlineMs() - System.currentTimeMillis());
        updateCountdown(remainingMs);
        if (server.isAlive()) {
            mainHandler.postDelayed(ticker, TICK_INTERVAL_MS);
        } else {
            // A successful POST also stops the server, but that path cancels this ticker before it can
            // run again (see onSubmitted) — reaching here always means the timeout fired first.
            Toast.makeText(this, "QR code expired", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void updateCountdown(long remainingMs) {
        if (countdownView == null) return;
        long totalSec = (remainingMs + 999) / 1000;
        long m = totalSec / 60;
        long s = totalSec % 60;
        countdownView.setText(String.format(Locale.US, "%d:%02d", m, s));
    }

    // --- server plumbing ---

    private String loadHtml() {
        try (InputStream in = getAssets().open("qr_setup.html")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (IOException e) {
            Log.e(TAG, "could not load qr_setup.html", e);
            return "<html><body>Setup page missing.</body></html>";
        }
    }

    /**
     * First non-loopback IPv4 address of an "up" interface. Deliberately not {@code WifiManager}: an
     * Android TV box is as likely to be on Ethernet, which that API does not cover.
     */
    @Nullable
    private static String discoverLocalIPv4() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            Log.w(TAG, "could not enumerate network interfaces", e);
        }
        return null;
    }

    @Nullable
    private Bitmap generateQrBitmap(String content) {
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX);
            Bitmap bmp = Bitmap.createBitmap(QR_SIZE_PX, QR_SIZE_PX, Bitmap.Config.RGB_565);
            for (int x = 0; x < QR_SIZE_PX; x++) {
                for (int y = 0; y < QR_SIZE_PX; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bmp;
        } catch (WriterException e) {
            Log.w(TAG, "failed to render the QR code", e);
            return null;
        }
    }

    // --- view building (no layout XML — see LanguageOrderActivity for the same convention) ---

    private View buildMainView(String url) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(SubsTheme.SURFACE_1);
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(24), dp(20), dp(24), dp(20));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView heading = SubsTheme.headlineMd(new TextView(this));
        heading.setText("Set up via QR");
        heading.setTextColor(SubsTheme.INK);
        heading.setGravity(Gravity.CENTER_HORIZONTAL);
        heading.setPadding(0, 0, 0, dp(8));
        column.addView(heading);

        TextView hint = SubsTheme.bodyMd(new TextView(this));
        hint.setText("Scan this with your phone on the same Wi-Fi/network to fill in API keys and languages.");
        hint.setTextColor(SubsTheme.INK_2);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setPadding(0, 0, 0, dp(16));
        column.addView(hint);

        ImageView qrView = new ImageView(this);
        int qrDp = dp(240);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(qrDp, qrDp);
        qrView.setLayoutParams(qrParams);
        qrView.setBackgroundColor(0xFFFFFFFF); // the code itself has to stay black-on-white to scan
        qrView.setImageBitmap(generateQrBitmap(url));
        column.addView(qrView);

        // The countdown used to be a bare "5:00" with nothing saying what it counted down to. The
        // label above carries that now; a sentence spelling it out was tried too and cut, because it
        // pushed the screen into scrolling — and a setup screen you have to scroll to see the button
        // on is worse than one that explains less.
        TextView countdownLabel = SubsTheme.labelSm(new TextView(this));
        countdownLabel.setText("EXPIRES IN");
        countdownLabel.setTextColor(SubsTheme.INK_3);
        countdownLabel.setGravity(Gravity.CENTER_HORIZONTAL);
        countdownLabel.setPadding(0, dp(16), 0, dp(2));
        column.addView(countdownLabel);

        countdownView = SubsTheme.display(new TextView(this));
        countdownView.setText("5:00");
        countdownView.setTextColor(SubsTheme.PRIMARY);
        countdownView.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(countdownView);

        statusView = SubsTheme.labelSm(new TextView(this));
        statusView.setTextColor(SubsTheme.INK_2);
        statusView.setGravity(Gravity.CENTER_HORIZONTAL);
        statusView.setPadding(0, dp(10), 0, 0);
        column.addView(statusView);

        // Sole control on the screen, so it holds the focus look from the start — there is nowhere
        // else for the D-pad to go, and Back does the same thing.
        cancelButton = new SubsButton(this, "Cancel");
        cancelButton.setFocusedState(true);
        cancelButton.setOnClickListener(v -> finish());
        // rowParams(), not hand-rolled params: that is where the standard button height lives, and
        // WRAP_CONTENT made this one visibly shorter than every other button in the app.
        LinearLayout.LayoutParams cancelLp = cancelButton.rowParams();
        cancelLp.topMargin = dp(16);
        column.addView(cancelButton, cancelLp);

        return scroll;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                finish(); // Cancel is the only control here
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private View buildErrorView(String message) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setBackgroundColor(SubsTheme.SURFACE_1);
        column.setPadding(dp(32), dp(32), dp(32), dp(32));

        TextView text = SubsTheme.bodyLg(new TextView(this));
        text.setText(message);
        text.setTextColor(SubsTheme.ERROR);
        text.setGravity(Gravity.CENTER);
        column.addView(text);
        return column;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Launches the QR setup screen from a settings preference. */
    public static void start(Context c) {
        c.startActivity(new Intent(c, QrSetupActivity.class));
    }
}
