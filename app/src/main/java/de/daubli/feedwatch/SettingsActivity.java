package de.daubli.feedwatch;

import java.util.ArrayList;
import java.util.regex.Pattern;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputLayout;
import de.daubli.feedwatch.settings.SettingsStore;

public class SettingsActivity extends AppCompatActivity {

    private static final String STATE_ADDITIONAL_SOURCES = "additionalSources";

    // four numbers from 0 to 255, separated by dots
    private static final Pattern IPV4_ADDRESS = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

    ViewGroup additionalSourcesList;

    View additionalSourcesEmpty;

    TextInputLayout additionalSourceInputLayout;

    EditText additionalSourceInput;

    // edited here and stored with the other settings on save
    private ArrayList<String> additionalSources;

    CompoundButton lowBandwidthModeEnabledSwitch;

    CompoundButton viewerModeEnabledSwitch;

    TextView defaultSourceSummary;

    View defaultSourceRemoveButton;

    private SettingsStore settingsStore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        additionalSourcesList = findViewById(R.id.additionalSourcesList);
        additionalSourcesEmpty = findViewById(R.id.additionalSourcesEmpty);
        additionalSourceInputLayout = findViewById(R.id.additionalSourceInputLayout);
        additionalSourceInput = findViewById(R.id.additionalSourceInput);
        lowBandwidthModeEnabledSwitch = findViewById(R.id.lowBandwidthMode);
        viewerModeEnabledSwitch = findViewById(R.id.viewerMode);
        defaultSourceSummary = findViewById(R.id.defaultSourceSummary);
        defaultSourceRemoveButton = findViewById(R.id.defaultSourceRemove);

        // the whole row toggles the switch
        findViewById(R.id.lowBandwidthRow).setOnClickListener(v -> lowBandwidthModeEnabledSwitch.toggle());
        findViewById(R.id.viewerModeRow).setOnClickListener(v -> viewerModeEnabledSwitch.toggle());

        settingsStore = new SettingsStore();

        ArrayList<String> restoredSources = savedInstanceState != null
                ? savedInstanceState.getStringArrayList(STATE_ADDITIONAL_SOURCES)
                : null;
        additionalSources = restoredSources != null
                ? restoredSources
                : new ArrayList<>(settingsStore.getAdditionalSourceList());
        bindAdditionalSources();

        findViewById(R.id.additionalSourceAdd).setOnClickListener(v -> addAdditionalSource());
        additionalSourceInput.setOnEditorActionListener((v, actionId, event) -> {
            // Done on the soft keyboard, or Enter on a hardware keyboard
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                addAdditionalSource();
                return true;
            }
            return false;
        });
        additionalSourceInput.addTextChangedListener(new TextWatcher() {

            private boolean typedAtEnd;

            private boolean formatting;

            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                // the error is about the old input
                additionalSourceInputLayout.setError(null);
                typedAtEnd = count > before && start + count == text.length();
            }

            @Override
            public void afterTextChanged(Editable text) {
                // only while typing forward, deleting or editing in the middle stays as it is
                if (formatting || !typedAtEnd) {
                    return;
                }
                formatting = true;
                insertAddressDot(text);
                formatting = false;
            }
        });

        // removed right away, the source may not be in the list to remove it there
        defaultSourceRemoveButton.setOnClickListener(v -> {
            settingsStore.clearDefaultSource();
            bindDefaultSource();
        });

        Button button = findViewById(R.id.saveBtn);
        button.setOnClickListener((View v) -> {
            // an address typed but not added yet is saved too, a wrong one keeps the screen open
            if (additionalSourceInput.length() > 0 && !addAdditionalSource()) {
                return;
            }
            this.settingsStore.setAdditionalSourceList(additionalSources);
            this.settingsStore.setLowBandwidthModeEnabled(lowBandwidthModeEnabledSwitch.isChecked());
            this.settingsStore.setViewerModeEnabled(viewerModeEnabledSwitch.isChecked());
            this.finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.lowBandwidthModeEnabledSwitch.setChecked(settingsStore.isLowBandwidthModeEnabled());
        this.viewerModeEnabledSwitch.setChecked(settingsStore.isViewerModeEnabled());
        bindDefaultSource();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList(STATE_ADDITIONAL_SOURCES, additionalSources);
    }

    /**
     * Adds the dot after a full block of three digits, so only the numbers need to be typed. A dot typed right
     * after it and a fourth digit in the last block are dropped.
     */
    @VisibleForTesting
    static void insertAddressDot(Editable text) {
        String address = text.toString();
        if (address.endsWith("..")) {
            text.delete(address.length() - 1, address.length());
            return;
        }

        String[] blocks = address.split("\\.", -1);
        int lastBlockLength = blocks[blocks.length - 1].length();
        if (lastBlockLength > 3) {
            // the last block has no dot after it, a fourth digit is not part of an address
            text.delete(address.length() - 1, address.length());
        } else if (blocks.length < 4 && lastBlockLength == 3) {
            text.append('.');
        }
    }

    /**
     * Adds the typed address to the list, or shows why it can't be added.
     *
     * @return whether the address was added
     */
    private boolean addAdditionalSource() {
        String address = additionalSourceInput.getText().toString().trim();

        if (!IPV4_ADDRESS.matcher(address).matches()) {
            additionalSourceInputLayout.setError(getString(R.string.settings_additional_source_invalid));
            return false;
        }
        if (additionalSources.contains(address)) {
            additionalSourceInputLayout.setError(getString(R.string.settings_additional_source_duplicate));
            return false;
        }

        additionalSources.add(address);
        additionalSourceInput.setText(null);
        additionalSourceInputLayout.setError(null);
        bindAdditionalSources();
        return true;
    }

    private void bindAdditionalSources() {
        additionalSourcesList.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();

        for (String address : additionalSources) {
            View row = inflater.inflate(R.layout.item_additional_source, additionalSourcesList, false);
            ((TextView) row.findViewById(R.id.additionalSourceAddress)).setText(address);
            row.findViewById(R.id.additionalSourceRemove).setOnClickListener(v -> {
                additionalSources.remove(address);
                bindAdditionalSources();
            });
            additionalSourcesList.addView(row);
        }

        additionalSourcesEmpty.setVisibility(additionalSources.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void bindDefaultSource() {
        boolean hasDefault = settingsStore.getDefaultSourceKey() != null;
        String label = settingsStore.getDefaultSourceLabel();
        defaultSourceSummary.setText(hasDefault
                ? getString(R.string.settings_default_source_summary, label != null ? label : "")
                : getString(R.string.settings_default_source_none));
        defaultSourceRemoveButton.setVisibility(hasDefault ? View.VISIBLE : View.GONE);
    }
}
