package com.brouken.player;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.C;

/**
 * Sound panel: boost, balance, voices, sync shortcut, Dolby Vision / HDR10 choice and a
 * diagnostics line. Narrow column on the right so the video stays visible.
 * Built for Android TV: every control is focusable and reachable with the D-pad.
 */
final class AudioPanel {

    private static final String PREFS = "audio_adjust";
    private static final String KEY_GAIN = "gain";
    private static final String KEY_BALANCE = "balance";
    private static final String KEY_DELAY = "delay";
    private static final String KEY_VOICE = "voice";

    private AudioPanel() {
    }

    /** Restores saved settings. Call before the player is created. */
    static void load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AudioAdjustProcessor.gainDb = p.getFloat(KEY_GAIN, 0f);
        AudioAdjustProcessor.balance = p.getFloat(KEY_BALANCE, 0f);
        AudioAdjustProcessor.voiceDb = p.getInt(KEY_VOICE, 3);
        int d = p.getInt(KEY_DELAY, 0);
        AudioAdjustProcessor.delayMs = Math.max(-AudioAdjustProcessor.MAX_DELAY_MS,
                Math.min(AudioAdjustProcessor.MAX_DELAY_MS, d));
    }

    static void persist(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_GAIN, AudioAdjustProcessor.gainDb)
                .putFloat(KEY_BALANCE, AudioAdjustProcessor.balance)
                .putInt(KEY_VOICE, AudioAdjustProcessor.voiceDb)
                .putInt(KEY_DELAY, AudioAdjustProcessor.delayMs)
                .apply();
    }

    /**
     * @param onHdrModeChanged called when the user picks another HDR mode; the player
     *                         must be rebuilt for the new decoder choice to apply.
     * @param onOpenSync       called after the panel closes, to show the floating sync strip.
     */
    static void show(Context context, Runnable onHdrModeChanged, Runnable onOpenSync) {
        View v = LayoutInflater.from(context).inflate(R.layout.dialog_audio, null);

        SeekBar gain = v.findViewById(R.id.audio_gain);
        TextView gainValue = v.findViewById(R.id.audio_gain_value);
        SeekBar balance = v.findViewById(R.id.audio_balance);
        TextView balanceValue = v.findViewById(R.id.audio_balance_value);
        SeekBar voice = v.findViewById(R.id.audio_voice);
        TextView voiceValue = v.findViewById(R.id.audio_voice_value);
        TextView syncValue = v.findViewById(R.id.audio_sync_value);
        Button syncOpen = v.findViewById(R.id.audio_sync_open);
        Button reset = v.findViewById(R.id.audio_reset);
        RadioGroup hdrGroup = v.findViewById(R.id.video_hdr_mode);
        RadioButton hdrDv = v.findViewById(R.id.video_hdr_dv);
        RadioButton hdr10 = v.findViewById(R.id.video_hdr_10);
        TextView diag = v.findViewById(R.id.audio_diag);
        ((TextView) v.findViewById(R.id.audio_version)).setText(versionText(context));

        gain.setMax(AudioAdjustProcessor.MAX_GAIN_DB);
        gain.setKeyProgressIncrement(1);
        gain.setProgress(Math.round(AudioAdjustProcessor.gainDb));

        // Balance SeekBar runs 0..20; 10 is center.
        balance.setMax(20);
        balance.setKeyProgressIncrement(1);
        balance.setProgress(Math.round((AudioAdjustProcessor.balance + 1f) * 10f));

        voice.setMax(AudioAdjustProcessor.MAX_VOICE_DB);
        voice.setKeyProgressIncrement(1);
        voice.setProgress(AudioAdjustProcessor.voiceDb);

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
            voiceValue.setText("+" + AudioAdjustProcessor.voiceDb + " dB");
            int d = AudioAdjustProcessor.delayMs;
            syncValue.setText((d > 0 ? "+" : "") + d + " ms");
        };
        refresh.run();

        gain.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                AudioAdjustProcessor.gainDb = progress;
                persist(context);
                refresh.run();
            }
        });

        balance.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                AudioAdjustProcessor.balance = progress / 10f - 1f;
                persist(context);
                refresh.run();
            }
        });

        voice.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                AudioAdjustProcessor.voiceDb = progress;
                persist(context);
                refresh.run();
            }
        });

        reset.setOnClickListener(view -> {
            AudioAdjustProcessor.gainDb = 0f;
            AudioAdjustProcessor.balance = 0f;
            AudioAdjustProcessor.voiceDb = 3;
            AudioAdjustProcessor.delayMs = 0;
            gain.setProgress(0);
            balance.setProgress(10);
            voice.setProgress(3);
            persist(context);
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
                .setView(v)
                .create();

        syncOpen.setOnClickListener(view -> {
            dialog.dismiss();
            if (onOpenSync != null) onOpenSync.run();
        });

        // Diagnostics, refreshed while the panel is open.
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] open = {true};
        final Runnable updateDiag = new Runnable() {
            @Override
            public void run() {
                if (!open[0]) return;
                diag.setText(diagText(context));
                handler.postDelayed(this, 500);
            }
        };
        updateDiag.run();
        dialog.setOnDismissListener(d -> open[0] = false);

        dialog.setOnShowListener(d -> gain.requestFocus());
        dialog.show();

        Window w = dialog.getWindow();
        if (w != null) {
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            int width = Math.min(Math.round(340 * dm.density), Math.round(dm.widthPixels * 0.45f));
            w.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setBackgroundDrawable(new ColorDrawable(0xE6181818));
            w.setLayout(width, WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    private static String versionText(Context context) {
        String version = "?";
        try {
            version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        return "JustAnotherPlayer " + version;
    }

    private static String diagText(Context context) {
        if (!AudioAdjustProcessor.diagConfigured) {
            return context.getString(R.string.audio_diag_idle);
        }
        String enc = AudioAdjustProcessor.diagEncoding == C.ENCODING_PCM_FLOAT ? "PCM float" : "PCM 16";
        return context.getString(R.string.audio_diag_active,
                AudioAdjustProcessor.diagInChannels,
                AudioAdjustProcessor.diagOutChannels,
                AudioAdjustProcessor.diagSampleRate,
                enc,
                AudioAdjustProcessor.diagFrames);
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
