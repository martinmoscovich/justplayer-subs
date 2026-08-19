package com.brouken.player.subs.ui;

import android.os.Build;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * The API-safe face of {@link SubsBackBridge}. Callers ask for Back while a view is up and give it
 * back when it goes away; on anything below API 33 both calls are no-ops and the plain
 * {@code KEYCODE_BACK} path the panel already has keeps working.
 */
public final class SubsBack {

    private SubsBack() {}

    /** True when Back arrives through the dispatcher rather than as a key event — API 33+. */
    public static boolean isDispatcherPath() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    @Nullable
    public static Object claim(View host, Runnable onBack) {
        return isDispatcherPath() ? SubsBackBridge.register(host, onBack) : null;
    }

    public static void release(View host, @Nullable Object token) {
        if (token != null && isDispatcherPath()) SubsBackBridge.unregister(host, token);
    }
}
