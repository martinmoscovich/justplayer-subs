package com.brouken.player.subs.ui;

import android.os.Build;
import android.view.View;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * Delivers the Back gesture to a view that draws its own navigation.
 *
 * <p>The app's manifest declares {@code android:enableOnBackInvokedCallback="true"} (upstream's
 * choice), which on API 33+ routes Back through {@link OnBackInvokedDispatcher} and stops dispatching
 * {@code KEYCODE_BACK} as a key event altogether. Every {@code case KEYCODE_BACK} in the subtitle
 * panel was therefore dead on a modern device: pressing Back with the panel open finished the
 * Activity instead of closing the modal, leaving the list mode, or closing the panel — and the
 * redesign leans on Back meaning "step out of this" in three separate places.
 *
 * <p>Registered at {@code PRIORITY_OVERLAY} so it wins over the Activity's own handler, and only
 * while the panel is actually up. Below API 33 nothing here runs and the key path still works.
 *
 * <p>Deliberately a separate class rather than a guarded block inside the panel: the platform types
 * it names do not exist before API 33, and keeping them out of the panel's own constant pool is what
 * makes it certain they are never resolved on an older device.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
final class SubsBackBridge {

    private SubsBackBridge() {}

    /** @return the registered callback, to hand back to {@link #unregister}, or null if there was no dispatcher. */
    @Nullable
    static Object register(View host, Runnable onBack) {
        OnBackInvokedDispatcher dispatcher = host.findOnBackInvokedDispatcher();
        if (dispatcher == null) return null;
        OnBackInvokedCallback cb = onBack::run;
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb);
        return cb;
    }

    static void unregister(View host, @Nullable Object token) {
        if (!(token instanceof OnBackInvokedCallback)) return;
        OnBackInvokedDispatcher dispatcher = host.findOnBackInvokedDispatcher();
        if (dispatcher != null) dispatcher.unregisterOnBackInvokedCallback((OnBackInvokedCallback) token);
    }
}
