package de.daubli.feedwatch.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
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

@RunWith(RobolectricTestRunner.class)
public class SettingsStoreTest {

    private final SettingsStore store = new SettingsStore();

    @Test
    public void defaultsOfAFreshInstall() {
        assertNull(store.getAdditionalSources());
        assertTrue(store.getAdditionalSourceList().isEmpty());

        assertFalse(store.isFramingHelperOverlayEnabled());
        assertEquals(FramingGrid.THIRDS, store.getFramingGrid());
        assertEquals(FrameLines.NONE, store.getFrameLines());
        assertEquals(SafeArea.NONE, store.getSafeArea());
        assertEquals(GuideColor.WHITE, store.getGuideColor());

        assertFalse(store.isFocusAssistEnabled());
        assertEquals(FocusPeakingColor.RED, store.getFocusPeakingColor());
        assertFalse(store.isZebraEnabled());
        assertEquals(95, store.getZebraThreshold());

        assertFalse(store.isWaveformEnabled());
        assertEquals(WaveformMode.LUMA, store.getWaveformMode());
        assertEquals(WaveformMonitorView.DEFAULT_X_FRACTION, store.getWaveformX(), 0f);
        assertEquals(WaveformMonitorView.DEFAULT_Y_FRACTION, store.getWaveformY(), 0f);
        assertEquals(WaveformMonitorView.DEFAULT_WIDTH_DP, store.getWaveformWidthDp(), 0f);

        assertFalse(store.isVectorscopeEnabled());
        assertEquals(VectorscopeMode.COLOR, store.getVectorscopeMode());
        assertEquals(VectorscopeZoom.NORMAL, store.getVectorscopeZoom());
        assertEquals(VectorscopeView.DEFAULT_X_FRACTION, store.getVectorscopeX(), 0f);
        assertEquals(VectorscopeView.DEFAULT_Y_FRACTION, store.getVectorscopeY(), 0f);
        assertEquals(VectorscopeView.DEFAULT_WIDTH_DP, store.getVectorscopeWidthDp(), 0f);

        assertFalse(store.isTallyEnabled());
        assertFalse(store.isLowBandwidthModeEnabled());
        assertTrue(store.isAudioEnabled());
        assertFalse(store.isViewerModeEnabled());
        assertNull(store.getDefaultSourceKey());
        assertNull(store.getDefaultSourceLabel());
    }

    @Test
    public void additionalSourcesDropWhitespace() {
        store.setAdditionalSources(" 192.168.1.10 , 10.0.0.2\n");

        assertEquals("192.168.1.10,10.0.0.2", store.getAdditionalSources());
        assertEquals(Arrays.asList("192.168.1.10", "10.0.0.2"), store.getAdditionalSourceList());
    }

    @Test
    public void additionalSourceListSkipsEmptyEntries() {
        store.setAdditionalSources(",192.168.1.10,,10.0.0.2,");

        assertEquals(Arrays.asList("192.168.1.10", "10.0.0.2"), store.getAdditionalSourceList());
    }

    @Test
    public void additionalSourceListRoundTrip() {
        store.setAdditionalSourceList(Arrays.asList("192.168.1.10", "10.0.0.2"));

        assertEquals("192.168.1.10,10.0.0.2", store.getAdditionalSources());
        assertEquals(Arrays.asList("192.168.1.10", "10.0.0.2"), new SettingsStore().getAdditionalSourceList());
    }

    @Test
    public void emptyAdditionalSourcesAreNone() {
        store.setAdditionalSourceList(Arrays.asList("192.168.1.10"));
        store.setAdditionalSourceList(Collections.emptyList());

        assertNull(store.getAdditionalSources());
        assertTrue(store.getAdditionalSourceList().isEmpty());
    }

    @Test
    public void switchesAreStored() {
        store.setFramingHelperOverlayEnabled(true);
        store.setFocusAssistEnabled(true);
        store.setZebraEnabled(true);
        store.setWaveformEnabled(true);
        store.setVectorscopeEnabled(true);
        store.setTallyEnabled(true);
        store.setLowBandwidthModeEnabled(true);
        store.setAudioEnabled(false);
        store.setViewerModeEnabled(true);

        SettingsStore reloaded = new SettingsStore();
        assertTrue(reloaded.isFramingHelperOverlayEnabled());
        assertTrue(reloaded.isFocusAssistEnabled());
        assertTrue(reloaded.isZebraEnabled());
        assertTrue(reloaded.isWaveformEnabled());
        assertTrue(reloaded.isVectorscopeEnabled());
        assertTrue(reloaded.isTallyEnabled());
        assertTrue(reloaded.isLowBandwidthModeEnabled());
        assertFalse(reloaded.isAudioEnabled());
        assertTrue(reloaded.isViewerModeEnabled());
    }

    @Test
    public void choicesAreStored() {
        store.setFramingGrid(FramingGrid.CENTER);
        store.setFrameLines(FrameLines.SCOPE);
        store.setSafeArea(SafeArea.TITLE);
        store.setGuideColor(GuideColor.YELLOW);
        store.setFocusPeakingColor(FocusPeakingColor.BLUE);
        store.setZebraThreshold(70);
        store.setWaveformMode(WaveformMode.RGB);
        store.setVectorscopeMode(VectorscopeMode.WHITE);
        store.setVectorscopeZoom(VectorscopeZoom.DOUBLE);

        SettingsStore reloaded = new SettingsStore();
        assertEquals(FramingGrid.CENTER, reloaded.getFramingGrid());
        assertEquals(FrameLines.SCOPE, reloaded.getFrameLines());
        assertEquals(SafeArea.TITLE, reloaded.getSafeArea());
        assertEquals(GuideColor.YELLOW, reloaded.getGuideColor());
        assertEquals(FocusPeakingColor.BLUE, reloaded.getFocusPeakingColor());
        assertEquals(70, reloaded.getZebraThreshold());
        assertEquals(WaveformMode.RGB, reloaded.getWaveformMode());
        assertEquals(VectorscopeMode.WHITE, reloaded.getVectorscopeMode());
        assertEquals(VectorscopeZoom.DOUBLE, reloaded.getVectorscopeZoom());
    }

    @Test
    public void scopePlacementsAreStoredSeparately() {
        store.setWaveformPlacement(0.1f, 0.2f, 300f);
        store.setVectorscopePlacement(0.7f, 0.8f, 180f);

        SettingsStore reloaded = new SettingsStore();
        assertEquals(0.1f, reloaded.getWaveformX(), 0f);
        assertEquals(0.2f, reloaded.getWaveformY(), 0f);
        assertEquals(300f, reloaded.getWaveformWidthDp(), 0f);
        assertEquals(0.7f, reloaded.getVectorscopeX(), 0f);
        assertEquals(0.8f, reloaded.getVectorscopeY(), 0f);
        assertEquals(180f, reloaded.getVectorscopeWidthDp(), 0f);
    }

    @Test
    public void unknownChoicesFallBackToTheirDefault() {
        // values a newer version of the app could have written
        preferences().edit()
                .putString("de.daubli.feedwatch.view.overlays.framing-helper.grid", "GOLDEN_RATIO")
                .putString("de.daubli.feedwatch.view.overlays.framing-helper.frame-lines", "CINEMA")
                .putString("de.daubli.feedwatch.view.overlays.focusassist.color", "GREEN")
                .putString("de.daubli.feedwatch.view.overlays.waveform.mode", "PARADE")
                .putString("de.daubli.feedwatch.view.overlays.vectorscope.zoom", "QUADRUPLE")
                .commit();

        assertEquals(FramingGrid.THIRDS, store.getFramingGrid());
        assertEquals(FrameLines.NONE, store.getFrameLines());
        assertEquals(FocusPeakingColor.RED, store.getFocusPeakingColor());
        assertEquals(WaveformMode.LUMA, store.getWaveformMode());
        assertEquals(VectorscopeZoom.NORMAL, store.getVectorscopeZoom());
    }

    @Test
    public void settingsOfEarlierVersionsAreRead() {
        // the keys are persisted on the devices, renaming one would lose the setting on update
        preferences().edit()
                .putString("de.daubli.feedwatch.additionalsources", "192.168.1.10")
                .putBoolean("de.daubli.feedwatch.view.overlays.framing-helper.enabled", true)
                .putBoolean("de.daubli.feedwatch.view.overlays.focusassist.enabled", true)
                .putBoolean("de.daubli.feedwatch.view.overlays.zebra.enabled", true)
                .putInt("de.daubli.feedwatch.view.overlays.zebra.threshold", 80)
                .putBoolean("de.daubli.feedwatch.view.overlays.tally.enabled", true)
                .putBoolean("de.daubli.feedwatch.ndi.lowbandwidthmode.enabled", true)
                .putString("de.daubli.feedwatch.sources.default.key", "ndi:STUDIO (Cam 1)")
                .commit();

        assertEquals("192.168.1.10", store.getAdditionalSources());
        assertTrue(store.isFramingHelperOverlayEnabled());
        assertTrue(store.isFocusAssistEnabled());
        assertTrue(store.isZebraEnabled());
        assertEquals(80, store.getZebraThreshold());
        assertTrue(store.isTallyEnabled());
        assertTrue(store.isLowBandwidthModeEnabled());
        assertEquals("ndi:STUDIO (Cam 1)", store.getDefaultSourceKey());
    }

    @Test
    public void defaultSourceCanBeSetAndCleared() {
        store.setDefaultSource("uvc:1133:2085", "HD Pro Webcam C920");

        assertEquals("uvc:1133:2085", store.getDefaultSourceKey());
        assertEquals("HD Pro Webcam C920", store.getDefaultSourceLabel());

        store.clearDefaultSource();

        assertNull(store.getDefaultSourceKey());
        assertNull(store.getDefaultSourceLabel());
    }

    private static SharedPreferences preferences() {
        return ApplicationProvider.getApplicationContext()
                .getSharedPreferences("de.daubli.feedwatch_preferences", Context.MODE_PRIVATE);
    }
}
