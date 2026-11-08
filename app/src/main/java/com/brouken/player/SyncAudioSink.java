package com.brouken.player;

import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;

/**
 * Shifts the playback clock that the audio renderer reports, so video frames are
 * shown earlier or later than the audio. This moves audio relative to video in
 * both directions without buffering samples, and also works for passthrough
 * (AC3/DTS sent undecoded to the TV or receiver).
 *
 * Clock = real audio position + offset.
 *  - offset > 0: video runs ahead, so the audio is heard later.
 *  - offset < 0: video waits, so the audio is heard earlier.
 */
public final class SyncAudioSink extends ForwardingAudioSink {

    public SyncAudioSink(AudioSink sink) {
        super(sink);
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
