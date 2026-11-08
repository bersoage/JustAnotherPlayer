package com.brouken.player;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;

/**
 * 1. Shifts the playback clock that the audio renderer reports, so video frames are
 *    shown earlier or later than the audio (sync in both directions).
 *    Clock = real audio position + offset.
 *     - offset > 0: video runs ahead, so the audio is heard later.
 *     - offset < 0: video waits, so the audio is heard earlier.
 *
 * 2. Refuses passthrough (AC3/DTS sent undecoded). The player then always decodes the
 *    audio to PCM, so {@link AudioAdjustProcessor} (balance, boost, downmix) always
 *    gets audio to work on.
 */
public final class SyncAudioSink extends ForwardingAudioSink {

    public SyncAudioSink(AudioSink sink) {
        super(sink);
    }

    @Override
    public boolean supportsFormat(Format format) {
        return MimeTypes.AUDIO_RAW.equals(format.sampleMimeType) && super.supportsFormat(format);
    }

    @Override
    public int getFormatSupport(Format format) {
        if (!MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)) {
            return SINK_FORMAT_UNSUPPORTED;
        }
        return super.getFormatSupport(format);
    }

    @Override
    public long getCurrentPositionUs(boolean sourceEnded) {
        long positionUs = super.getCurrentPositionUs(sourceEnded);
        if (positionUs == CURRENT_POSITION_NOT_SET) {
            return positionUs;
        }
        return positionUs + AudioAdjustProcessor.delayMs * 1000L;
    }
}
