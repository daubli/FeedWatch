package de.daubli.feedwatch.settings;

import static de.daubli.feedwatch.FeedWatchApplication.getAppContext;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import android.content.Context;
import android.content.SharedPreferences;
import de.daubli.feedwatch.view.overlays.focusassist.FocusPeakingColor;
import de.daubli.feedwatch.view.overlays.framehelper.FrameLines;
import de.daubli.feedwatch.view.overlays.framehelper.FramingGrid;
import de.daubli.feedwatch.view.overlays.framehelper.GuideColor;
import de.daubli.feedwatch.view.overlays.framehelper.SafeArea;
import de.daubli.feedwatch.view.overlays.vectorscope.VectorscopeMode;
import de.daubli.feedwatch.view.overlays.vectorscope.VectorscopeView;
import de.daubli.feedwatch.view.overlays.vectorscope.VectorscopeZoom;
import de.daubli.feedwatch.view.overlays.waveform.WaveformMode;
import de.daubli.feedwatch.view.overlays.waveform.WaveformMonitorView;
import de.daubli.feedwatch.view.overlays.zebra.ZebraOverlay;
import androidx.annotation.Nullable;

public class SettingsStore {

    private final SharedPreferences sharedPreferences;

    private static final String ADDITIONAL_SOURCES_KEY = "de.daubli.feedwatch.additionalsources";

    private static final String FRAMING_HELPER_ENABLED_KEY = "de.daubli.feedwatch.view.overlays.framing-helper.enabled";

    private static final String FRAMING_GRID_KEY = "de.daubli.feedwatch.view.overlays.framing-helper.grid";

    private static final String FRAME_LINES_KEY = "de.daubli.feedwatch.view.overlays.framing-helper.frame-lines";

    private static final String SAFE_AREA_KEY = "de.daubli.feedwatch.view.overlays.framing-helper.safe-area";

    private static final String GUIDE_COLOR_KEY = "de.daubli.feedwatch.view.overlays.framing-helper.color";

    private static final String FOCUS_ASSIST_ENABLED_KEY = "de.daubli.feedwatch.view.overlays.focusassist.enabled";

    private static final String FOCUS_PEAKING_COLOR_KEY = "de.daubli.feedwatch.view.overlays.focusassist.color";

    private static final String ZEBRA_ENABLED_KEY = "de.daubli.feedwatch.view.overlays.zebra.enabled";

    private static final String ZEBRA_THRESHOLD_KEY = "de.daubli.feedwatch.view.overlays.zebra.threshold";

    private static final String WAVEFORM_ENABLED_KEY = "de.daubli.feedwatch.view.overlays.waveform.enabled";

    private static final String WAVEFORM_MODE_KEY = "de.daubli.feedwatch.view.overlays.waveform.mode";

    private static final String WAVEFORM_X_KEY = "de.daubli.feedwatch.view.overlays.waveform.x";

    private static final String WAVEFORM_Y_KEY = "de.daubli.feedwatch.view.overlays.waveform.y";

    private static final String WAVEFORM_WIDTH_KEY = "de.daubli.feedwatch.view.overlays.waveform.width";

    private static final String VECTORSCOPE_ENABLED_KEY = "de.daubli.feedwatch.view.overlays.vectorscope.enabled";

    private static final String VECTORSCOPE_MODE_KEY = "de.daubli.feedwatch.view.overlays.vectorscope.mode";

    private static final String VECTORSCOPE_ZOOM_KEY = "de.daubli.feedwatch.view.overlays.vectorscope.zoom";

    private static final String VECTORSCOPE_X_KEY = "de.daubli.feedwatch.view.overlays.vectorscope.x";

    private static final String VECTORSCOPE_Y_KEY = "de.daubli.feedwatch.view.overlays.vectorscope.y";

    private static final String VECTORSCOPE_WIDTH_KEY = "de.daubli.feedwatch.view.overlays.vectorscope.width";

    private static final String TALLY_ENABLED_KEY = "de.daubli.feedwatch.view.overlays.tally.enabled";

    private static final String LOW_BANDWIDTH_MODE_ENABLED_KEY = "de.daubli.feedwatch.ndi.lowbandwidthmode.enabled";

    private static final String AUDIO_ENABLED_KEY = "de.daubli.feedwatch.ndi.audio.enabled";

    private static final String VIEWER_MODE_ENABLED_KEY = "de.daubli.feedwatch.view.viewermode.enabled";

    private static final String DEFAULT_SOURCE_KEY = "de.daubli.feedwatch.sources.default.key";

    private static final String DEFAULT_SOURCE_LABEL_KEY = "de.daubli.feedwatch.sources.default.label";

    public SettingsStore() {
        sharedPreferences = getAppContext().getSharedPreferences("de.daubli.feedwatch_preferences",
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

    /**
     * The additional sources as a list, one IP address per entry.
     */
    public List<String> getAdditionalSourceList() {
        List<String> sources = new ArrayList<>();
        String value = getAdditionalSources();
        if (value != null) {
            for (String source : value.split(",")) {
                if (!source.isEmpty()) {
                    sources.add(source);
                }
            }
        }
        return sources;
    }

    public void setAdditionalSourceList(List<String> sources) {
        setAdditionalSources(String.join(",", sources));
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

    /**
     * Composition grid of the framing helper, the rule of thirds by default.
     */
    public FramingGrid getFramingGrid() {
        return getEnum(FRAMING_GRID_KEY, FramingGrid.class, FramingGrid.THIRDS);
    }

    public void setFramingGrid(FramingGrid grid) {
        putEnum(FRAMING_GRID_KEY, grid);
    }

    public FrameLines getFrameLines() {
        return getEnum(FRAME_LINES_KEY, FrameLines.class, FrameLines.NONE);
    }

    public void setFrameLines(FrameLines frameLines) {
        putEnum(FRAME_LINES_KEY, frameLines);
    }

    public SafeArea getSafeArea() {
        return getEnum(SAFE_AREA_KEY, SafeArea.class, SafeArea.NONE);
    }

    public void setSafeArea(SafeArea safeArea) {
        putEnum(SAFE_AREA_KEY, safeArea);
    }

    public GuideColor getGuideColor() {
        return getEnum(GUIDE_COLOR_KEY, GuideColor.class, GuideColor.WHITE);
    }

    public void setGuideColor(GuideColor color) {
        putEnum(GUIDE_COLOR_KEY, color);
    }

    private <E extends Enum<E>> E getEnum(String key, Class<E> type, E defaultValue) {
        String name = sharedPreferences.getString(key, null);
        if (name != null) {
            try {
                return Enum.valueOf(type, name);
            } catch (IllegalArgumentException e) {
                // a value from a newer version, fall back to the default
            }
        }
        return defaultValue;
    }

    private void putEnum(String key, Enum<?> value) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(key, value.name());
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

    /**
     * Color of the focus peaking edges, red by default.
     */
    public FocusPeakingColor getFocusPeakingColor() {
        String name = sharedPreferences.getString(FOCUS_PEAKING_COLOR_KEY, null);
        if (name != null) {
            try {
                return FocusPeakingColor.valueOf(name);
            } catch (IllegalArgumentException e) {
                // a color from a newer version, fall back to the default
            }
        }
        return FocusPeakingColor.RED;
    }

    public void setFocusPeakingColor(FocusPeakingColor color) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(FOCUS_PEAKING_COLOR_KEY, color.name());
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

    /**
     * Brightness from which zebra stripes are shown, in percent (IRE).
     */
    public int getZebraThreshold() {
        return sharedPreferences.getInt(ZEBRA_THRESHOLD_KEY, Math.round(ZebraOverlay.DEFAULT_THRESHOLD_IRE));
    }

    public void setZebraThreshold(int percent) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putInt(ZEBRA_THRESHOLD_KEY, percent);
        editor.apply();
    }

    public boolean isWaveformEnabled() {
        return sharedPreferences.getBoolean(WAVEFORM_ENABLED_KEY, false);
    }

    public void setWaveformEnabled(boolean enabled) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(WAVEFORM_ENABLED_KEY, enabled);
        editor.apply();
    }

    /**
     * What the waveform monitor plots, luma by default.
     */
    public WaveformMode getWaveformMode() {
        return getEnum(WAVEFORM_MODE_KEY, WaveformMode.class, WaveformMode.LUMA);
    }

    public void setWaveformMode(WaveformMode mode) {
        putEnum(WAVEFORM_MODE_KEY, mode);
    }

    /**
     * Horizontal place of the waveform monitor in the free space of the screen, 0 is the left edge.
     */
    public float getWaveformX() {
        return sharedPreferences.getFloat(WAVEFORM_X_KEY, WaveformMonitorView.DEFAULT_X_FRACTION);
    }

    /**
     * Vertical place of the waveform monitor in the free space of the screen, 0 is the top edge.
     */
    public float getWaveformY() {
        return sharedPreferences.getFloat(WAVEFORM_Y_KEY, WaveformMonitorView.DEFAULT_Y_FRACTION);
    }

    public float getWaveformWidthDp() {
        return sharedPreferences.getFloat(WAVEFORM_WIDTH_KEY, WaveformMonitorView.DEFAULT_WIDTH_DP);
    }

    public void setWaveformPlacement(float x, float y, float widthDp) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putFloat(WAVEFORM_X_KEY, x);
        editor.putFloat(WAVEFORM_Y_KEY, y);
        editor.putFloat(WAVEFORM_WIDTH_KEY, widthDp);
        editor.apply();
    }

    public boolean isVectorscopeEnabled() {
        return sharedPreferences.getBoolean(VECTORSCOPE_ENABLED_KEY, false);
    }

    public void setVectorscopeEnabled(boolean enabled) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(VECTORSCOPE_ENABLED_KEY, enabled);
        editor.apply();
    }

    /**
     * How the vectorscope draws its trace, in color by default.
     */
    public VectorscopeMode getVectorscopeMode() {
        return getEnum(VECTORSCOPE_MODE_KEY, VectorscopeMode.class, VectorscopeMode.COLOR);
    }

    public void setVectorscopeMode(VectorscopeMode mode) {
        putEnum(VECTORSCOPE_MODE_KEY, mode);
    }

    public VectorscopeZoom getVectorscopeZoom() {
        return getEnum(VECTORSCOPE_ZOOM_KEY, VectorscopeZoom.class, VectorscopeZoom.NORMAL);
    }

    public void setVectorscopeZoom(VectorscopeZoom zoom) {
        putEnum(VECTORSCOPE_ZOOM_KEY, zoom);
    }

    /**
     * Horizontal place of the vectorscope in the free space of the screen, 0 is the left edge.
     */
    public float getVectorscopeX() {
        return sharedPreferences.getFloat(VECTORSCOPE_X_KEY, VectorscopeView.DEFAULT_X_FRACTION);
    }

    /**
     * Vertical place of the vectorscope in the free space of the screen, 0 is the top edge.
     */
    public float getVectorscopeY() {
        return sharedPreferences.getFloat(VECTORSCOPE_Y_KEY, VectorscopeView.DEFAULT_Y_FRACTION);
    }

    public float getVectorscopeWidthDp() {
        return sharedPreferences.getFloat(VECTORSCOPE_WIDTH_KEY, VectorscopeView.DEFAULT_WIDTH_DP);
    }

    public void setVectorscopePlacement(float x, float y, float widthDp) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putFloat(VECTORSCOPE_X_KEY, x);
        editor.putFloat(VECTORSCOPE_Y_KEY, y);
        editor.putFloat(VECTORSCOPE_WIDTH_KEY, widthDp);
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

    public boolean isTallyEnabled() {
        if (sharedPreferences.contains(TALLY_ENABLED_KEY)) {
            return sharedPreferences.getBoolean(TALLY_ENABLED_KEY, false);
        }
        return false;
    }

    public void setTallyEnabled(boolean enabled) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(TALLY_ENABLED_KEY, enabled);
        editor.apply();
    }

    /**
     * Audio playback for NDI sources, on by default. Without audio, video is shown with the lowest latency instead
     * of waiting for its audio.
     */
    public boolean isAudioEnabled() {
        return sharedPreferences.getBoolean(AUDIO_ENABLED_KEY, true);
    }

    public void setAudioEnabled(boolean enabled) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(AUDIO_ENABLED_KEY, enabled);
        editor.apply();
    }

    /**
     * Shows only the picture while streaming: monitoring tools, overlays and stream info are hidden, only the close
     * and audio buttons remain.
     */
    public boolean isViewerModeEnabled() {
        return sharedPreferences.getBoolean(VIEWER_MODE_ENABLED_KEY, false);
    }

    public void setViewerModeEnabled(boolean enabled) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(VIEWER_MODE_ENABLED_KEY, enabled);
        editor.apply();
    }

    /**
     * Stable key of the source that is opened automatically once per app start, or null if there is none.
     */
    @Nullable
    public String getDefaultSourceKey() {
        return sharedPreferences.getString(DEFAULT_SOURCE_KEY, null);
    }

    /**
     * Name of the default source, so it can be shown while the source is unavailable.
     */
    @Nullable
    public String getDefaultSourceLabel() {
        return sharedPreferences.getString(DEFAULT_SOURCE_LABEL_KEY, null);
    }

    public void setDefaultSource(String key, String label) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(DEFAULT_SOURCE_KEY, key);
        editor.putString(DEFAULT_SOURCE_LABEL_KEY, label);
        editor.apply();
    }

    public void clearDefaultSource() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.remove(DEFAULT_SOURCE_KEY);
        editor.remove(DEFAULT_SOURCE_LABEL_KEY);
        editor.apply();
    }
}
