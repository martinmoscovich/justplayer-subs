package com.brouken.player.updater;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.brouken.player.R;

/** UI and hand-off to the system/browser for an already-detected update. */
final class AppUpdatePrompt {

    private static final String TAG = "AppUpdatePrompt";

    private AppUpdatePrompt() {
    }

    static void show(Activity activity, String versionName) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_available_title)
                .setMessage(activity.getString(R.string.update_available_message, versionName))
                .setNegativeButton(R.string.update_later, null)
                .setPositiveButton(R.string.update_download, (dialog, which) -> openDownload(activity))
                .show();
    }

    private static void openDownload(Activity activity) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdateChecker.APK_URL)));
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to open APK download", error);
            Toast.makeText(activity, R.string.update_download_unavailable, Toast.LENGTH_SHORT).show();
        }
    }
}
