package de.daubli.feedwatch;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import de.daubli.feedwatch.settings.SettingsStore;

public class SettingsActivity extends Activity {

    EditText additionalSourcesEditText;

    CheckBox lowBandwidthModeEnabledCheckBox;

    private SettingsStore settingsStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        additionalSourcesEditText = findViewById(R.id.additionalSourcesEditText);
        lowBandwidthModeEnabledCheckBox = findViewById(R.id.lowBandwidthMode);

        settingsStore = new SettingsStore();

        Button button = findViewById(R.id.saveBtn);
        button.setOnClickListener((View v) -> {
            this.settingsStore.setAdditionalSources(additionalSourcesEditText.getText().toString());
            this.settingsStore.setLowBandwidthModeEnabled(lowBandwidthModeEnabledCheckBox.isChecked());
            this.finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.additionalSourcesEditText.setText(settingsStore.getAdditionalSources());
        this.lowBandwidthModeEnabledCheckBox.setChecked(settingsStore.isLowBandwidthModeEnabled());
    }
}
