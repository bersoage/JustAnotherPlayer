package com.brouken.player;

import android.content.Context;

import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;

import java.util.Collections;
import java.util.List;

/**
 * Lets the user choose Dolby Vision or HDR10 when a file carries both
 * (Dolby Vision profile 7 or 8.1 with an HDR10 base layer).
 *
 * In HDR10 mode the Dolby Vision decoders are hidden, so ExoPlayer falls back to
 * the HEVC decoder and plays the HDR10 base layer. Files that are Dolby Vision
 * only (profile 5) have no HDR10 layer; PlayerActivity detects that and switches
 * back to Dolby Vision automatically.
 */
public final class HdrModeCodecSelector implements MediaCodecSelector {

    public static final int MODE_DOLBY_VISION = 0;
    public static final int MODE_HDR10 = 1;

    private static final String PREFS = "audio_adjust";
    private static final String KEY_HDR_MODE = "hdr_mode";

    /** Current mode; read when the player builds decoders. */
    public static volatile int mode = MODE_DOLBY_VISION;

    public static void load(Context context) {
        mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_HDR_MODE, MODE_DOLBY_VISION);
    }

    public static void save(Context context, int newMode) {
        mode = newMode;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_HDR_MODE, newMode).apply();
    }

    @Override
    public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder)
            throws MediaCodecUtil.DecoderQueryException {
        if (mode == MODE_HDR10 && MimeTypes.VIDEO_DOLBY_VISION.equals(mimeType)) {
            return Collections.emptyList();
        }
        return MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
    }
}
