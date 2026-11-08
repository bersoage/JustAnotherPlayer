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
    /** Bass and treble in dB, -MAX_FX_DB..MAX_FX_DB. */
    public static volatile int bassDb = 0;
    public static volatile int trebleDb = 0;
    public static final int MAX_FX_DB = 12;
    /** Echo amount, 0..100 (%). 0 = off. */
    public static volatile int echoPct = 0;
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

    private static final float BASS_HZ = 120f;
    private static final float TREBLE_HZ = 6000f;
    private static final float ECHO_SECONDS = 0.35f;
    private static final float ECHO_FEEDBACK = 0.4f;
    private static final float ECHO_MAX_MIX = 0.6f;

    private int inChannels;
    private int outChannels;
    private int encoding;
    private int sampleRate;

    private float[] frameIn = new float[0];
    private final float[] frameOut = new float[2];

    // Shelving filters (b0, b1, b2, a1, a2 normalized) and their state per channel.
    private final double[] lowC = new double[5];
    private final double[] highC = new double[5];
    private final double[][] lowZ = new double[2][2];
    private final double[][] highZ = new double[2][2];
    private int appliedBass = Integer.MIN_VALUE;
    private int appliedTreble = Integer.MIN_VALUE;

    private float[] echoBuf = new float[0];
    private int echoFrames = 1;
    private int echoPos = 0;

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT
                && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        inChannels = inputAudioFormat.channelCount;
        outChannels = inChannels > 2 ? 2 : inChannels;
        encoding = inputAudioFormat.encoding;
        sampleRate = inputAudioFormat.sampleRate;

        frameIn = new float[inChannels];
        echoFrames = Math.max(1, Math.round(sampleRate * ECHO_SECONDS));
        echoBuf = new float[echoFrames * outChannels];
        echoPos = 0;
        clearFilterState();
        appliedBass = Integer.MIN_VALUE;
        appliedTreble = Integer.MIN_VALUE;

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

        // Bass / treble / echo settings, read once per buffer.
        final int bass = bassDb;
        final int treble = trebleDb;
        if (bass != appliedBass) {
            computeLowShelf(bass);
            appliedBass = bass;
        }
        if (treble != appliedTreble) {
            computeHighShelf(treble);
            appliedTreble = treble;
        }
        final boolean useBass = bass != 0;
        final boolean useTreble = treble != 0;
        final float echoMix = echoPct / 100f * ECHO_MAX_MIX;
        // A little headroom when boosting, so the boosted bands clip less.
        final float headroom = (float) Math.pow(10, -Math.max(0, Math.max(bass, treble)) * 0.25 / 20.0);

        final float[] s = frameIn;
        final float[] x = frameOut;

        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < inChannels; c++) {
                s[c] = isFloat ? in.getFloat() : in.getShort() / 32768f;
            }

            if (inChannels == 1) {
                x[0] = s[0];
            } else if (inChannels == 2) {
                x[0] = s[0];
                x[1] = s[1];
            } else {
                float l = s[0];
                float r = s[1];
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
                x[0] = l * DOWNMIX_SCALE;
                x[1] = r * DOWNMIX_SCALE;
            }

            for (int c = 0; c < outChannels; c++) {
                float v = x[c];
                if (useBass) {
                    v = biquad(lowC, lowZ[c], v);
                }
                if (useTreble) {
                    v = biquad(highC, highZ[c], v);
                }
                v *= headroom;

                final int idx = echoPos * outChannels + c;
                if (echoMix > 0f) {
                    final float delayed = echoBuf[idx];
                    echoBuf[idx] = v + ECHO_FEEDBACK * delayed;
                    v += echoMix * delayed;
                } else {
                    echoBuf[idx] = 0f;
                }

                final float g = outChannels == 1 ? gain : (c == 0 ? leftGain : rightGain);
                writeSample(out, v * g, isFloat);
            }
            echoPos = (echoPos + 1) % echoFrames;
        }

        in.position(in.limit());
        out.flip();
        diagFrames += frames;
    }

    @Override
    protected void onFlush() {
        clearFilterState();
        java.util.Arrays.fill(echoBuf, 0f);
        echoPos = 0;
    }

    @Override
    protected void onReset() {
        clearFilterState();
        echoBuf = new float[0];
        echoFrames = 1;
        echoPos = 0;
    }

    private void clearFilterState() {
        for (int c = 0; c < 2; c++) {
            lowZ[c][0] = lowZ[c][1] = 0;
            highZ[c][0] = highZ[c][1] = 0;
        }
    }

    /** Transposed direct form II biquad. c = {b0, b1, b2, a1, a2}. */
    private static float biquad(double[] c, double[] z, float x) {
        final double y = c[0] * x + z[0];
        z[0] = c[1] * x - c[3] * y + z[1];
        z[1] = c[2] * x - c[4] * y;
        return (float) y;
    }

    // RBJ audio EQ cookbook shelving filters, shelf slope S = 1.
    private void computeLowShelf(int db) {
        final double a = Math.pow(10, db / 40.0);
        final double w0 = 2 * Math.PI * BASS_HZ / sampleRate;
        final double cos = Math.cos(w0);
        final double alpha = Math.sin(w0) / 2 * Math.sqrt(2);
        final double t = 2 * Math.sqrt(a) * alpha;
        final double b0 = a * ((a + 1) - (a - 1) * cos + t);
        final double b1 = 2 * a * ((a - 1) - (a + 1) * cos);
        final double b2 = a * ((a + 1) - (a - 1) * cos - t);
        final double a0 = (a + 1) + (a - 1) * cos + t;
        final double a1 = -2 * ((a - 1) + (a + 1) * cos);
        final double a2 = (a + 1) + (a - 1) * cos - t;
        store(lowC, b0, b1, b2, a0, a1, a2);
    }

    private void computeHighShelf(int db) {
        final double a = Math.pow(10, db / 40.0);
        final double w0 = 2 * Math.PI * TREBLE_HZ / sampleRate;
        final double cos = Math.cos(w0);
        final double alpha = Math.sin(w0) / 2 * Math.sqrt(2);
        final double t = 2 * Math.sqrt(a) * alpha;
        final double b0 = a * ((a + 1) + (a - 1) * cos + t);
        final double b1 = -2 * a * ((a - 1) + (a + 1) * cos);
        final double b2 = a * ((a + 1) + (a - 1) * cos - t);
        final double a0 = (a + 1) - (a - 1) * cos + t;
        final double a1 = 2 * ((a - 1) - (a + 1) * cos);
        final double a2 = (a + 1) - (a - 1) * cos - t;
        store(highC, b0, b1, b2, a0, a1, a2);
    }

    private static void store(double[] c, double b0, double b1, double b2, double a0, double a1, double a2) {
        c[0] = b0 / a0;
        c[1] = b1 / a0;
        c[2] = b2 / a0;
        c[3] = a1 / a0;
        c[4] = a2 / a0;
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
