package com.brouken.player;

import android.content.Context;

import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;

/** Network buffer levels. "normal" keeps the default behaviour (returns null). */
final class BufferSettings {

    private BufferSettings() {
    }

    static String level(Context context) {
        try {
            return android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                    .getString("bufferLevel", "normal");
        } catch (Exception e) {
            return "normal";
        }
    }

    static LoadControl build(Context context) {
        try {
            final String level = level(context);
            final long heap = Runtime.getRuntime().maxMemory();
            final int min, max, playback, rebuffer;
            final long byteCap;
            if ("large".equals(level)) {
                min = 60_000;
                max = 120_000;
                playback = 2_500;
                rebuffer = 15_000;
                byteCap = Math.min(256L * 1024 * 1024, (long) (heap * 0.35));
            } else if ("max".equals(level)) {
                min = 120_000;
                max = 300_000;
                playback = 5_000;
                rebuffer = 30_000;
                byteCap = Math.min(512L * 1024 * 1024, (long) (heap * 0.40));
            } else {
                return null;
            }
            // Memory is the real limit for very high bitrate files, so size wins over time.
            return new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(min, max, playback, rebuffer)
                    .setTargetBufferBytes((int) Math.max(32L * 1024 * 1024, byteCap))
                    .setPrioritizeTimeOverSizeThresholds(false)
                    .build();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
