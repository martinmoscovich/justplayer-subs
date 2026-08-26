package com.brouken.player.updater;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import com.brouken.player.BuildConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Checks the GitHub Release metadata; downloading and installation stay with Android. */
public final class AppUpdateChecker {

    private static final String TAG = "AppUpdateChecker";
    private static final long DEBUG_VERSION_MULTIPLIER = BuildConfig.OTA_DEBUG_VERSION_MULTIPLIER;
    static final String APK_URL = BuildConfig.OTA_RELEASE_BASE_URL + "/" + BuildConfig.OTA_APK_FILE_NAME;
    private static final String VERSION_URL =
            BuildConfig.OTA_RELEASE_BASE_URL + "/" + BuildConfig.OTA_VERSION_FILE_NAME;

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build();

    private AppUpdateChecker() {
    }

    public static void check(Activity activity) {
        Request request = new Request.Builder()
                .url(VERSION_URL)
                .header("Cache-Control", "no-cache")
                .build();

        HTTP_CLIENT.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                Log.w(TAG, "Unable to check for an app update", error);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response ignored = response) {
                    if (!response.isSuccessful() || response.body() == null) {
                        Log.w(TAG, "Update metadata request failed: HTTP " + response.code());
                        return;
                    }

                    UpdateInfo update = parse(response.body().string());
                    if (isNewer(update.versionCode, installedVersionCode(activity))) {
                        activity.runOnUiThread(() -> AppUpdatePrompt.show(activity, update.versionName));
                    }
                } catch (IOException | JSONException error) {
                    Log.w(TAG, "Invalid update metadata", error);
                }
            }
        });
    }

    private static UpdateInfo parse(String json) throws JSONException {
        JSONObject object = new JSONObject(json);
        if (!object.has("versionName") || !object.has("versionCode")) {
            throw new JSONException("versionName and versionCode are required");
        }

        String versionName = object.getString("versionName").trim();
        long versionCode = object.getLong("versionCode");
        if (versionName.isEmpty() || versionCode < 1) {
            throw new JSONException("Invalid version metadata");
        }
        return new UpdateInfo(versionName, versionCode);
    }

    private static long installedVersionCode(Activity activity) {
        try {
            PackageInfo packageInfo = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? packageInfo.getLongVersionCode()
                    : packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException error) {
            Log.w(TAG, "Unable to read installed app version", error);
            return BuildConfig.VERSION_CODE;
        }
    }

    /** Compares semantic version codes while retaining DEBUG timestamp ordering for equal versions. */
    private static boolean isNewer(long remoteVersionCode, long installedVersionCode) {
        long remoteBase = remoteVersionCode >= DEBUG_VERSION_MULTIPLIER
                ? remoteVersionCode / DEBUG_VERSION_MULTIPLIER : remoteVersionCode;
        long installedBase = installedVersionCode >= DEBUG_VERSION_MULTIPLIER
                ? installedVersionCode / DEBUG_VERSION_MULTIPLIER : installedVersionCode;
        return remoteBase > installedBase
                || (remoteBase == installedBase && remoteVersionCode > installedVersionCode);
    }

    private static final class UpdateInfo {
        final String versionName;
        final long versionCode;

        UpdateInfo(String versionName, long versionCode) {
            this.versionName = versionName;
            this.versionCode = versionCode;
        }
    }
}
