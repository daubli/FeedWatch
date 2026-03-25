package de.daubli.feedwatch;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import de.daubli.feedwatch.settings.SettingsStore;

public class SettingsActivity extends Activity {

    EditText additionalSourcesEditText;
    private SettingsStore settingsStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        additionalSourcesEditText = findViewById(R.id.additionalSourcesEditText);
        settingsStore = new SettingsStore();

        Button button = findViewById(R.id.saveBtn);
        button.setOnClickListener((View v) -> {
            this.settingsStore.setAdditionalSources(additionalSourcesEditText.getText().toString());
            this.finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.additionalSourcesEditText.setText(settingsStore.getAdditionalSources());
    }
}
