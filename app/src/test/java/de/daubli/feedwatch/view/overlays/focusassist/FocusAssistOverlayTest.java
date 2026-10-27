package de.daubli.feedwatch.view.overlays.focusassist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The sensitivity slider of focus peaking: the sharpness an edge needs to be marked.
 */
@RunWith(RobolectricTestRunner.class)
public class FocusAssistOverlayTest {

    @Test
    public void endsOfTheSlider() {
        assertEquals(1.9f, FocusAssistOverlay.thresholdForSensitivity(0), 1e-4f);
        assertEquals(0.85f, FocusAssistOverlay.thresholdForSensitivity(100), 1e-4f);
    }

    @Test
    public void defaultIsBetweenTheEnds() {
        // the geometric middle of 1.9 and 0.85
        assertEquals(1.27f, FocusAssistOverlay.thresholdForSensitivity(FocusAssistOverlay.DEFAULT_SENSITIVITY), 0.01f);
    }

    @Test
    public void moreSensitivityNeedsLessSharpness() {
        float previous = Float.MAX_VALUE;
        for (int percent = 0; percent <= 100; percent += 5) {
            float threshold = FocusAssistOverlay.thresholdForSensitivity(percent);
            assertTrue("threshold at " + percent + " %", threshold < previous);
            previous = threshold;
        }
    }

    @Test
    public void valuesOutsideTheSliderAreClamped() {
        assertEquals(FocusAssistOverlay.thresholdForSensitivity(0), FocusAssistOverlay.thresholdForSensitivity(-20), 0f);
        assertEquals(FocusAssistOverlay.thresholdForSensitivity(100), FocusAssistOverlay.thresholdForSensitivity(150),
                0f);
    }
}
