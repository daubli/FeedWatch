package de.daubli.feedwatch;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import de.daubli.feedwatch.settings.SettingsStore;

public class SettingsActivity extends AppCompatActivity {

    EditText additionalSourcesEditText;

    CompoundButton lowBandwidthModeEnabledSwitch;

    CompoundButton viewerModeEnabledSwitch;

    private SettingsStore settingsStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        additionalSourcesEditText = findViewById(R.id.additionalSourcesEditText);
        lowBandwidthModeEnabledSwitch = findViewById(R.id.lowBandwidthMode);
        viewerModeEnabledSwitch = findViewById(R.id.viewerMode);

        // the whole row toggles the switch
        findViewById(R.id.lowBandwidthRow).setOnClickListener(v -> lowBandwidthModeEnabledSwitch.toggle());
        findViewById(R.id.viewerModeRow).setOnClickListener(v -> viewerModeEnabledSwitch.toggle());

        settingsStore = new SettingsStore();

        Button button = findViewById(R.id.saveBtn);
        button.setOnClickListener((View v) -> {
            this.settingsStore.setAdditionalSources(additionalSourcesEditText.getText().toString());
            this.settingsStore.setLowBandwidthModeEnabled(lowBandwidthModeEnabledSwitch.isChecked());
            this.settingsStore.setViewerModeEnabled(viewerModeEnabledSwitch.isChecked());
            this.finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.additionalSourcesEditText.setText(settingsStore.getAdditionalSources());
        this.lowBandwidthModeEnabledSwitch.setChecked(settingsStore.isLowBandwidthModeEnabled());
        this.viewerModeEnabledSwitch.setChecked(settingsStore.isViewerModeEnabled());
    }
}
