package de.daubli.feedwatch.settings;

import static de.daubli.feedwatch.FeedWatchApplication.getAppContext;

import org.apache.commons.lang3.StringUtils;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.Nullable;

public class SettingsStore {

    private final SharedPreferences sharedPreferences;

    private static final String ADDITIONAL_SOURCES_KEY = "de.daubli.ndimonitor.additionalsources";

    private static final String FRAMING_HELPER_ENABLED_KEY = "de.daubli.ndimonitor.view.framing-helper.enabled";

    private static final String FOCUS_ASSIST_ENABLED_KEY = "de.daubli.ndimonitor.view.overlays.focusassist.enabled";

    private static final String ZEBRA_ENABLED_KEY = "de.daubli.ndimonitor.view.overlays.zebra.enabled";

    private static final String LOW_BANDWIDTH_MODE_ENABLED_KEY = "de.daubli.ndimonitor.ndi.lowbandwidthmode.enabled";

    public SettingsStore() {
        sharedPreferences = getAppContext().getSharedPreferences("de.daubli.ndimonitor_preferences",
                Context.MODE_PRIVATE);
    }

    @Nullable
    public String getAdditionalSources() {
        if (sharedPreferences.contains(ADDITIONAL_SOURCES_KEY)) {
            String sources = sharedPreferences.getString(ADDITIONAL_SOURCES_KEY, null);
            if (sources.isEmpty()) {
                return null;
            }
            return sources;
        }

        return null;
    }

    public void setAdditionalSources(String additionalSources) {
        String additionalSourcesValue = trimAndFormatAdditionalSourcesValue(additionalSources);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(ADDITIONAL_SOURCES_KEY, additionalSourcesValue);
        editor.apply();
    }

    private String trimAndFormatAdditionalSourcesValue(String additionalSources) {
        return StringUtils.deleteWhitespace(additionalSources);
    }

    public boolean isFramingHelperOverlayEnabled() {
        if (sharedPreferences.contains(FRAMING_HELPER_ENABLED_KEY)) {
            return sharedPreferences.getBoolean(FRAMING_HELPER_ENABLED_KEY, false);
        }
        return false;
    }

    public void setFramingHelperOverlayEnabled(boolean enabled) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(FRAMING_HELPER_ENABLED_KEY, enabled);
        editor.apply();
    }

    public boolean isFocusAssistEnabled() {
        if (sharedPreferences.contains(FOCUS_ASSIST_ENABLED_KEY)) {
            return sharedPreferences.getBoolean(FOCUS_ASSIST_ENABLED_KEY, false);
        }
        return false;
    }

    public void setFocusAssistEnabled(boolean enabled) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(FOCUS_ASSIST_ENABLED_KEY, enabled);
        editor.apply();
    }

    public boolean isZebraEnabled() {
        if (sharedPreferences.contains(ZEBRA_ENABLED_KEY)) {
            return sharedPreferences.getBoolean(ZEBRA_ENABLED_KEY, false);
        }
        return false;
    }

    public void setZebraEnabled(boolean enabled) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(ZEBRA_ENABLED_KEY, enabled);
        editor.apply();
    }

    public boolean isLowBandwidthModeEnabled() {
        if (sharedPreferences.contains(LOW_BANDWIDTH_MODE_ENABLED_KEY)) {
            return sharedPreferences.getBoolean(LOW_BANDWIDTH_MODE_ENABLED_KEY, false);
        }
        return false;
    }

    public void setLowBandwidthModeEnabled(boolean enabled) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(LOW_BANDWIDTH_MODE_ENABLED_KEY, enabled);
        editor.apply();
    }
}
