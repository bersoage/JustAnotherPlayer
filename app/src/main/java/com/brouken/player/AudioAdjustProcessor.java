package com.brouken.player;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Software audio adjustments: gain (potenciador) and left/right balance.
 * Audio/video sync is handled by {@link SyncAudioSink}, not here.
 * Settings are shared statically so the audio panel can change them while playback runs.
 */
public final class AudioAdjustProcessor extends BaseAudioProcessor {

    public static final int MAX_DELAY_MS = 1000;
    public static final int DELAY_STEP_MS = 50;
    public static final int MAX_GAIN_DB = 12;

    /** Gain in dB, 0..MAX_GAIN_DB. */
    public static volatile float gainDb = 0f;
    /** Balance from -1 (left only) to 1 (right only). */
    public static volatile float balance = 0f;
    /**
     * Audio/video offset in ms, -MAX_DELAY_MS..MAX_DELAY_MS.
     * Positive: audio is heard later. Negative: audio is heard earlier.
     */
    public static volatile int delayMs = 0;

    private int channels;
    private int encoding;

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT
                && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        channels = inputAudioFormat.channelCount;
        encoding = inputAudioFormat.encoding;
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int bytesPerSample = encoding == C.ENCODING_PCM_FLOAT ? 4 : 2;
        int frameBytes = bytesPerSample * channels;
        int frames = inputBuffer.remaining() / frameBytes;

        ByteBuffer in = inputBuffer.order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer out = replaceOutputBuffer(frames * frameBytes).order(ByteOrder.LITTLE_ENDIAN);

        float gain = (float) Math.pow(10, gainDb / 20f);
        float bal = balance;
        // Balance only applies to stereo-style front L/R (channels 0 and 1).
        float leftGain = gain * (channels >= 2 && bal > 0 ? 1f - bal : 1f);
        float rightGain = gain * (channels >= 2 && bal < 0 ? 1f + bal : 1f);
        boolean isFloat = encoding == C.ENCODING_PCM_FLOAT;

        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                float g = c == 0 ? leftGain : (c == 1 ? rightGain : gain);
                if (isFloat) {
                    float o = in.getFloat() * g;
                    out.putFloat(Math.max(-1f, Math.min(1f, o)));
                } else {
                    int v = Math.round(in.getShort() * g);
                    out.putShort((short) Math.max(-32768, Math.min(32767, v)));
                }
            }
        }
        // Skip any trailing partial frame so the input buffer is fully consumed.
        in.position(in.limit());
        out.flip();
    }
}
