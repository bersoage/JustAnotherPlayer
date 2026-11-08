package com.brouken.player;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Software audio processing done inside the player (works over Bluetooth, where the
 * system has no balance control):
 * - Downmix of any multichannel audio (5.1, 7.1...) to stereo, with the center channel
 *   (voices) mixed in and an adjustable voice boost.
 * - Gain (potenciador) and left/right balance.
 *
 * Audio/video sync is handled by {@link SyncAudioSink}, not here.
 * Settings are static so the sound panel can change them while playback runs.
 */
public final class AudioAdjustProcessor extends BaseAudioProcessor {

    public static final int MAX_DELAY_MS = 3000;
    public static final int MAX_GAIN_DB = 12;
    public static final int MAX_VOICE_DB = 9;

    /** Gain in dB, 0..MAX_GAIN_DB. */
    public static volatile float gainDb = 0f;
    /** Balance from -1 (left only) to 1 (right only). */
    public static volatile float balance = 0f;
    /** Extra level for the center channel (voices) when downmixing, 0..MAX_VOICE_DB. */
    public static volatile int voiceDb = 3;
    /**
     * Audio/video offset in ms, -MAX_DELAY_MS..MAX_DELAY_MS.
     * Positive: audio is heard later. Negative: audio is heard earlier.
     */
    public static volatile int delayMs = 0;

    // Diagnostics shown in the sound panel.
    public static volatile boolean diagConfigured = false;
    public static volatile int diagInChannels = 0;
    public static volatile int diagOutChannels = 0;
    public static volatile int diagSampleRate = 0;
    public static volatile int diagEncoding = 0;
    public static volatile long diagFrames = 0;

    private static final float SURROUND_GAIN = 0.707f;
    private static final float CENTER_GAIN = 0.707f;
    // Keeps summed channels from clipping too often.
    private static final float DOWNMIX_SCALE = 0.7f;

    private int inChannels;
    private int outChannels;
    private int encoding;

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT
                && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        inChannels = inputAudioFormat.channelCount;
        outChannels = inChannels > 2 ? 2 : inChannels;
        encoding = inputAudioFormat.encoding;

        diagConfigured = true;
        diagInChannels = inChannels;
        diagOutChannels = outChannels;
        diagSampleRate = inputAudioFormat.sampleRate;
        diagEncoding = encoding;
        diagFrames = 0;

        return new AudioFormat(inputAudioFormat.sampleRate, outChannels, encoding);
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        final boolean isFloat = encoding == C.ENCODING_PCM_FLOAT;
        final int bytesPerSample = isFloat ? 4 : 2;
        final int frames = inputBuffer.remaining() / (bytesPerSample * inChannels);

        ByteBuffer in = inputBuffer.order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer out = replaceOutputBuffer(frames * bytesPerSample * outChannels).order(ByteOrder.LITTLE_ENDIAN);

        final float gain = (float) Math.pow(10, gainDb / 20f);
        final float bal = balance;
        final float leftGain = gain * (bal > 0 ? 1f - bal : 1f);
        final float rightGain = gain * (bal < 0 ? 1f + bal : 1f);
        final float centerGain = CENTER_GAIN * (float) Math.pow(10, voiceDb / 20f);

        final float[] s = new float[inChannels];

        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < inChannels; c++) {
                s[c] = isFloat ? in.getFloat() : in.getShort() / 32768f;
            }

            float l;
            float r;
            if (inChannels == 1) {
                l = s[0] * gain;
                writeSample(out, l, isFloat);
                continue;
            } else if (inChannels == 2) {
                l = s[0];
                r = s[1];
            } else {
                l = s[0];
                r = s[1];
                // Channel order (Android): FL FR FC LFE BL BR [SL SR]. LFE is dropped.
                switch (inChannels) {
                    case 3: // FL FR FC
                        l += centerGain * s[2];
                        r += centerGain * s[2];
                        break;
                    case 4: // FL FR BL BR
                        l += SURROUND_GAIN * s[2];
                        r += SURROUND_GAIN * s[3];
                        break;
                    case 5: // FL FR FC BL BR
                        l += centerGain * s[2] + SURROUND_GAIN * s[3];
                        r += centerGain * s[2] + SURROUND_GAIN * s[4];
                        break;
                    case 6: // FL FR FC LFE BL BR
                        l += centerGain * s[2] + SURROUND_GAIN * s[4];
                        r += centerGain * s[2] + SURROUND_GAIN * s[5];
                        break;
                    case 7: // FL FR FC LFE BC SL SR
                        l += centerGain * s[2] + SURROUND_GAIN * (SURROUND_GAIN * s[4] + s[5]);
                        r += centerGain * s[2] + SURROUND_GAIN * (SURROUND_GAIN * s[4] + s[6]);
                        break;
                    default: // 8+: FL FR FC LFE BL BR SL SR
                        l += centerGain * s[2] + SURROUND_GAIN * (s[4] + s[6]);
                        r += centerGain * s[2] + SURROUND_GAIN * (s[5] + s[7]);
                        break;
                }
                l *= DOWNMIX_SCALE;
                r *= DOWNMIX_SCALE;
            }

            writeSample(out, l * leftGain, isFloat);
            writeSample(out, r * rightGain, isFloat);
        }

        in.position(in.limit());
        out.flip();
        diagFrames += frames;
    }

    private static void writeSample(ByteBuffer out, float v, boolean isFloat) {
        float clamped = Math.max(-1f, Math.min(1f, v));
        if (isFloat) {
            out.putFloat(clamped);
        } else {
            out.putShort((short) Math.round(clamped * 32767f));
        }
    }
}
