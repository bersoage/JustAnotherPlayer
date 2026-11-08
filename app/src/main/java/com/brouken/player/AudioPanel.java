package com.brouken.player;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Sound panel (potenciador, balance, sincronía) plus the Dolby Vision / HDR10 choice.
 * Built for Android TV: every control is focusable and reachable with the D-pad.
 */
final class AudioPanel {

    private static final String PREFS = "audio_adjust";
    private static final String KEY_GAIN = "gain";
    private static final String KEY_BALANCE = "balance";
    private static final String KEY_DELAY = "delay";

    private AudioPanel() {
    }

    /** Restores saved settings. Call before the player is created. */
    static void load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AudioAdjustProcessor.gainDb = p.getFloat(KEY_GAIN, 0f);
        AudioAdjustProcessor.balance = p.getFloat(KEY_BALANCE, 0f);
        AudioAdjustProcessor.delayMs = p.getInt(KEY_DELAY, 0);
    }

    private static void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_GAIN, AudioAdjustProcessor.gainDb)
                .putFloat(KEY_BALANCE, AudioAdjustProcessor.balance)
                .putInt(KEY_DELAY, AudioAdjustProcessor.delayMs)
                .apply();
    }

    /**
     * @param onHdrModeChanged called when the user picks another HDR mode; the player
     *                         must be rebuilt for the new decoder choice to apply.
     */
    static void show(Context context, Runnable onHdrModeChanged) {
        View v = LayoutInflater.from(context).inflate(R.layout.dialog_audio, null);

        SeekBar gain = v.findViewById(R.id.audio_gain);
        TextView gainValue = v.findViewById(R.id.audio_gain_value);
        SeekBar balance = v.findViewById(R.id.audio_balance);
        TextView balanceValue = v.findViewById(R.id.audio_balance_value);
        TextView delayValue = v.findViewById(R.id.audio_delay_value);
        Button delayMinus = v.findViewById(R.id.audio_delay_minus);
        Button delayPlus = v.findViewById(R.id.audio_delay_plus);
        Button reset = v.findViewById(R.id.audio_reset);
        RadioGroup hdrGroup = v.findViewById(R.id.video_hdr_mode);
        RadioButton hdrDv = v.findViewById(R.id.video_hdr_dv);
        RadioButton hdr10 = v.findViewById(R.id.video_hdr_10);

        gain.setMax(AudioAdjustProcessor.MAX_GAIN_DB);
        gain.setKeyProgressIncrement(1);
        gain.setProgress(Math.round(AudioAdjustProcessor.gainDb));

        // Balance SeekBar runs 0..20; 10 is center.
        balance.setMax(20);
        balance.setKeyProgressIncrement(1);
        balance.setProgress(Math.round((AudioAdjustProcessor.balance + 1f) * 10f));

        Runnable refresh = () -> {
            gainValue.setText("+" + Math.round(AudioAdjustProcessor.gainDb) + " dB");
            int b = Math.round(AudioAdjustProcessor.balance * 10f);
            if (b == 0) {
                balanceValue.setText(context.getString(R.string.audio_balance_center));
            } else if (b < 0) {
                balanceValue.setText(context.getString(R.string.audio_balance_left, -b));
            } else {
                balanceValue.setText(context.getString(R.string.audio_balance_right, b));
            }
            int d = AudioAdjustProcessor.delayMs;
            delayValue.setText((d > 0 ? "+" : "") + d + " ms");
        };
        refresh.run();

        gain.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                AudioAdjustProcessor.gainDb = progress;
                save(context);
                refresh.run();
            }
        });

        balance.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                AudioAdjustProcessor.balance = progress / 10f - 1f;
                save(context);
                refresh.run();
            }
        });

        delayMinus.setOnClickListener(view -> {
            AudioAdjustProcessor.delayMs = Math.max(-AudioAdjustProcessor.MAX_DELAY_MS,
                    AudioAdjustProcessor.delayMs - AudioAdjustProcessor.DELAY_STEP_MS);
            save(context);
            refresh.run();
        });

        delayPlus.setOnClickListener(view -> {
            AudioAdjustProcessor.delayMs = Math.min(AudioAdjustProcessor.MAX_DELAY_MS,
                    AudioAdjustProcessor.delayMs + AudioAdjustProcessor.DELAY_STEP_MS);
            save(context);
            refresh.run();
        });

        reset.setOnClickListener(view -> {
            AudioAdjustProcessor.gainDb = 0f;
            AudioAdjustProcessor.balance = 0f;
            AudioAdjustProcessor.delayMs = 0;
            gain.setProgress(0);
            balance.setProgress(10);
            save(context);
            refresh.run();
        });

        if (HdrModeCodecSelector.mode == HdrModeCodecSelector.MODE_HDR10) {
            hdr10.setChecked(true);
        } else {
            hdrDv.setChecked(true);
        }
        hdrGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int newMode = checkedId == R.id.video_hdr_10
                    ? HdrModeCodecSelector.MODE_HDR10
                    : HdrModeCodecSelector.MODE_DOLBY_VISION;
            if (newMode == HdrModeCodecSelector.mode) return;
            HdrModeCodecSelector.save(context, newMode);
            if (onHdrModeChanged != null) onHdrModeChanged.run();
        });

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.audio_panel_title)
                .setView(v)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(d -> gain.requestFocus());
        dialog.show();
    }

    private abstract static class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar s) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar s) {
        }
    }
}
