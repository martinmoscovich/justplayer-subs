package com.brouken.player.skip;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Parses the optional "skip_segments" intent extra: a JSON array of objects describing intro,
 * recap and ending ranges.
 *
 * <p>The reference sender (Nuvio) writes {@code [{"type":"intro","start":12.5,"end":97.0}]} with
 * times in seconds. Nothing here is specific to it: the type key, the time keys and the time unit
 * are all accepted in the variants the skip databases use, and anything unparseable is dropped
 * instead of failing the launch. A malformed extra therefore means "no skip buttons", never a
 * broken playback.
 */
public final class SkipSegmentParser {

    private static final String[] TYPE_KEYS = {"type", "skipType", "skip_type"};
    private static final String[] START_SECOND_KEYS = {"start", "start_sec", "startTime", "start_time"};
    private static final String[] END_SECOND_KEYS = {"end", "end_sec", "endTime", "end_time"};
    private static final String[] START_MS_KEYS = {"start_ms", "startMs"};
    private static final String[] END_MS_KEYS = {"end_ms", "endMs"};

    private SkipSegmentParser() {
    }

    /**
     * @param json the raw extra, may be null, blank or malformed
     * @return the segments that could be read, earliest first; never null, possibly empty
     */
    @NonNull
    public static List<SkipSegment> parse(@Nullable String json) {
        final List<SkipSegment> segments = new ArrayList<>();
        if (TextUtils.isEmpty(json)) {
            return segments;
        }

        final JSONArray array;
        try {
            array = new JSONArray(json);
        } catch (Exception e) {
            return segments;
        }

        for (int i = 0; i < array.length(); i++) {
            final JSONObject object = array.optJSONObject(i);
            if (object == null) {
                continue;
            }
            final long startMs = readTimeMs(object, START_MS_KEYS, START_SECOND_KEYS);
            final long endMs = readTimeMs(object, END_MS_KEYS, END_SECOND_KEYS);
            // Zero-length and inverted ranges would produce a button that skips nowhere.
            if (startMs < 0 || endMs <= startMs) {
                continue;
            }
            segments.add(new SkipSegment(SkipSegment.kindOf(readType(object)), startMs, endMs));
        }

        // Not Comparator.comparingLong: that is API 24+ and this module targets minSdk 23.
        Collections.sort(segments, new Comparator<SkipSegment>() {
            @Override
            public int compare(SkipSegment a, SkipSegment b) {
                return Long.compare(a.getStartMs(), b.getStartMs());
            }
        });
        return segments;
    }

    @Nullable
    private static String readType(final JSONObject object) {
        for (String key : TYPE_KEYS) {
            final String value = object.optString(key, null);
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * Reads a time in milliseconds, preferring an explicit millisecond key over a seconds one so a
     * sender that supplies both cannot be misread.
     *
     * @return the time in ms, or -1 when no usable key is present
     */
    private static long readTimeMs(final JSONObject object, final String[] msKeys, final String[] secondKeys) {
        for (String key : msKeys) {
            if (object.has(key) && !object.isNull(key)) {
                final double value = object.optDouble(key, Double.NaN);
                if (!Double.isNaN(value)) {
                    return Math.round(value);
                }
            }
        }
        for (String key : secondKeys) {
            if (object.has(key) && !object.isNull(key)) {
                final double value = object.optDouble(key, Double.NaN);
                if (!Double.isNaN(value)) {
                    return Math.round(value * 1000d);
                }
            }
        }
        return -1;
    }
}
