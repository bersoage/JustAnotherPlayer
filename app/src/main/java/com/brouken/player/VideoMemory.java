package com.brouken.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Remembers settings per video: audio/subtitle track, picture mode, speed, audio and subtitle
 * sync, balance, voices, boost and effects. A video is found by its address and, failing that,
 * by its file name (addresses of the same file can differ between file pickers).
 */
final class VideoMemory {

    private static final String PREFS = "video_memory";
    private static final String ORDER = "_order";
    private static final int MAX = 300;

    static volatile Uri currentUri;

    private VideoMemory() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String uriKey(Uri u) {
        return "u:" + u;
    }

    private static String nameKey(Context c, Uri u) {
        try {
            final String n = Utils.getFileName(c, u);
            return n == null || n.isEmpty() ? null : "n:" + n;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject lookup(Context c, Uri u) {
        try {
            final SharedPreferences p = sp(c);
            String s = p.getString(uriKey(u), null);
            if (s == null) {
                final String nk = nameKey(c, u);
                if (nk != null) s = p.getString(nk, null);
            }
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(Context c, Uri u, JSONObject rec) {
        try {
            final SharedPreferences p = sp(c);
            final SharedPreferences.Editor ed = p.edit();
            final String s = rec.toString();
            final String uk = uriKey(u);
            final String nk = nameKey(c, u);
            ed.putString(uk, s);
            if (nk != null) ed.putString(nk, s);
            final JSONArray order = new JSONArray(p.getString(ORDER, "[]"));
            final JSONArray next = new JSONArray();
            for (int i = 0; i < order.length(); i++) {
                final String k = order.getString(i);
                if (!k.equals(uk) && !k.equals(nk)) next.put(k);
            }
            next.put(uk);
            if (nk != null) next.put(nk);
            final JSONArray kept = new JSONArray();
            final int drop = Math.max(0, next.length() - MAX);
            for (int i = 0; i < next.length(); i++) {
                if (i < drop) ed.remove(next.getString(i)); else kept.put(next.getString(i));
            }
            ed.putString(ORDER, kept.toString());
            ed.apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void putAdjust(JSONObject r) throws Exception {
        r.put("delay", AudioAdjustProcessor.delayMs);
        r.put("subDelay", AudioAdjustProcessor.subDelayMs);
        r.put("balance", (double) AudioAdjustProcessor.balance);
        r.put("voice", AudioAdjustProcessor.voiceDb);
        r.put("gain", (double) AudioAdjustProcessor.gainDb);
        r.put("bass", AudioAdjustProcessor.bassDb);
        r.put("treble", AudioAdjustProcessor.trebleDb);
        r.put("echo", AudioAdjustProcessor.echoPct);
        r.put("echoDelay", AudioAdjustProcessor.echoDelayMs);
        r.put("echoPing", AudioAdjustProcessor.echoPingPong);
    }

    /** Loads the saved settings of the video in {@code prefs.mediaUri}, if any. */
    static void apply(Context c, Prefs prefs) {
        try {
            final Uri u = prefs.mediaUri;
            currentUri = u;
            if (u == null || !prefs.persistentMode) return;
            final JSONObject r = lookup(c, u);
            if (r == null) {
                // New video: sync offsets belong to a single video, so start from zero.
                AudioAdjustProcessor.delayMs = 0;
                AudioAdjustProcessor.subDelayMs = 0;
                return;
            }
            prefs.audioTrackId = r.optString("audioTrack", null);
            prefs.subtitleTrackId = r.optString("subtitleTrack", null);
            prefs.resizeMode = r.optInt("resizeMode", prefs.resizeMode);
            prefs.scale = (float) r.optDouble("scale", prefs.scale);
            prefs.speed = (float) r.optDouble("speed", prefs.speed);
            AudioAdjustProcessor.delayMs = Math.max(-AudioAdjustProcessor.MAX_DELAY_MS,
                    Math.min(AudioAdjustProcessor.MAX_DELAY_MS, r.optInt("delay", 0)));
            AudioAdjustProcessor.subDelayMs = Math.max(-AudioAdjustProcessor.MAX_SUB_DELAY_MS,
                    Math.min(AudioAdjustProcessor.MAX_SUB_DELAY_MS, r.optInt("subDelay", 0)));
            AudioAdjustProcessor.balance = (float) r.optDouble("balance", AudioAdjustProcessor.balance);
            AudioAdjustProcessor.voiceDb = r.optInt("voice", AudioAdjustProcessor.voiceDb);
            AudioAdjustProcessor.gainDb = (float) r.optDouble("gain", AudioAdjustProcessor.gainDb);
            AudioAdjustProcessor.bassDb = r.optInt("bass", AudioAdjustProcessor.bassDb);
            AudioAdjustProcessor.trebleDb = r.optInt("treble", AudioAdjustProcessor.trebleDb);
            AudioAdjustProcessor.echoPct = r.optInt("echo", AudioAdjustProcessor.echoPct);
            AudioAdjustProcessor.echoDelayMs = r.optInt("echoDelay", AudioAdjustProcessor.echoDelayMs);
            AudioAdjustProcessor.echoPingPong = r.optBoolean("echoPing", AudioAdjustProcessor.echoPingPong);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (AudioAdjustProcessor.subDelayMs != 0) {
                SubtitleShift.enabled = true;
            }
        }
    }

    /** Saves tracks, picture mode and all sound settings of the current video. */
    static void save(Context c, Prefs prefs) {
        try {
            final Uri u = prefs.mediaUri;
            if (u == null || !prefs.persistentMode) return;
            currentUri = u;
            final JSONObject r = new JSONObject();
            if (prefs.audioTrackId != null) r.put("audioTrack", prefs.audioTrackId);
            if (prefs.subtitleTrackId != null) r.put("subtitleTrack", prefs.subtitleTrackId);
            r.put("resizeMode", prefs.resizeMode);
            r.put("scale", (double) prefs.scale);
            r.put("speed", (double) prefs.speed);
            putAdjust(r);
            write(c, u, r);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Saves only the sound/subtitle-sync settings (called when they change). */
    static void saveAdjust(Context c) {
        try {
            final Uri u = currentUri;
            if (u == null) return;
            JSONObject r = lookup(c, u);
            if (r == null) r = new JSONObject();
            putAdjust(r);
            write(c, u, r);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
