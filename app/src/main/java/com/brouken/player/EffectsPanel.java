package com.brouken.player;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

/** Effects screen: bass, treble and echo (no reverb, to keep 4K playback light). */
final class EffectsPanel {

    private static final int MAX = AudioAdjustProcessor.MAX_FX_DB;
    private static final int ECHO_STEP = 5;

    private EffectsPanel() {
    }

    static void show(Context context, Runnable onClose) {
        View v = LayoutInflater.from(context).inflate(R.layout.dialog_effects, null);
        SeekBar bass = v.findViewById(R.id.fx_bass);
        SeekBar treble = v.findViewById(R.id.fx_treble);
        SeekBar echo = v.findViewById(R.id.fx_echo);
        TextView bassValue = v.findViewById(R.id.fx_bass_value);
        TextView trebleValue = v.findViewById(R.id.fx_treble_value);
        TextView echoValue = v.findViewById(R.id.fx_echo_value);
        Button reset = v.findViewById(R.id.fx_reset);
        Button back = v.findViewById(R.id.fx_back);

        bass.setMax(MAX * 2);
        bass.setKeyProgressIncrement(1);
        bass.setProgress(AudioAdjustProcessor.bassDb + MAX);
        treble.setMax(MAX * 2);
        treble.setKeyProgressIncrement(1);
        treble.setProgress(AudioAdjustProcessor.trebleDb + MAX);
        echo.setMax(100 / ECHO_STEP);
        echo.setKeyProgressIncrement(1);
        echo.setProgress(AudioAdjustProcessor.echoPct / ECHO_STEP);

        Runnable refresh = () -> {
            bassValue.setText(db(AudioAdjustProcessor.bassDb));
            trebleValue.setText(db(AudioAdjustProcessor.trebleDb));
            echoValue.setText(AudioAdjustProcessor.echoPct == 0
                    ? context.getString(R.string.effects_echo_off)
                    : AudioAdjustProcessor.echoPct + " %");
        };
        refresh.run();

        bass.setOnSeekBarChangeListener(new Listener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                AudioAdjustProcessor.bassDb = p - MAX;
                AudioPanel.persist(context);
                refresh.run();
            }
        });
        treble.setOnSeekBarChangeListener(new Listener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                AudioAdjustProcessor.trebleDb = p - MAX;
                AudioPanel.persist(context);
                refresh.run();
            }
        });
        echo.setOnSeekBarChangeListener(new Listener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                AudioAdjustProcessor.echoPct = p * ECHO_STEP;
                AudioPanel.persist(context);
                refresh.run();
            }
        });

        reset.setOnClickListener(view -> {
            AudioAdjustProcessor.bassDb = 0;
            AudioAdjustProcessor.trebleDb = 0;
            AudioAdjustProcessor.echoPct = 0;
            bass.setProgress(MAX);
            treble.setProgress(MAX);
            echo.setProgress(0);
            AudioPanel.persist(context);
            refresh.run();
        });

        AlertDialog dialog = new AlertDialog.Builder(context).setView(v).create();
        back.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnDismissListener(d -> {
            if (onClose != null) onClose.run();
        });
        dialog.setOnShowListener(d -> bass.requestFocus());
        dialog.show();
        AudioPanel.styleSidePanel(context, dialog);
    }

    private static String db(int v) {
        return (v > 0 ? "+" : "") + v + " dB";
    }

    private abstract static class Listener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar s) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar s) {
        }
    }
}
