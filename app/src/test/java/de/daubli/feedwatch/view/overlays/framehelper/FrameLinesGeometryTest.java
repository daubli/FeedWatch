package de.daubli.feedwatch.view.overlays.framehelper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import android.graphics.RectF;

@RunWith(RobolectricTestRunner.class)
public class FrameLinesGeometryTest {

    private static final float DELTA = 0.01f;

    @Test
    public void widerFrameLinesUseTheFullWidth() {
        RectF frame = fit(new RectF(0, 0, 1920, 1080), FrameLines.SCOPE);

        assertEquals(0f, frame.left, DELTA);
        assertEquals(1920f, frame.right, DELTA);
        assertEquals(1920f / 2.39f, frame.height(), DELTA);
        assertEquals(540f, frame.centerY(), DELTA);
    }

    @Test
    public void narrowerFrameLinesUseTheFullHeight() {
        RectF frame = fit(new RectF(0, 0, 1920, 1080), FrameLines.FOUR_THREE);

        assertEquals(0f, frame.top, DELTA);
        assertEquals(1080f, frame.bottom, DELTA);
        assertEquals(1440f, frame.width(), DELTA);
        assertEquals(240f, frame.left, DELTA);
    }

    @Test
    public void verticalFrameLinesInALandscapePicture() {
        RectF frame = fit(new RectF(0, 0, 1920, 1080), FrameLines.VERTICAL);

        assertEquals(1080f, frame.height(), DELTA);
        assertEquals(607.5f, frame.width(), DELTA);
        assertEquals(960f, frame.centerX(), DELTA);
    }

    @Test
    public void staysCenteredInOffsetBounds() {
        // the video letterboxed on a taller screen
        RectF bounds = new RectF(0, 300, 1080, 907.5f);

        RectF frame = fit(bounds, FrameLines.FOUR_THREE);

        assertEquals(bounds.top, frame.top, DELTA);
        assertEquals(bounds.bottom, frame.bottom, DELTA);
        assertEquals(bounds.centerX(), frame.centerX(), DELTA);
        assertEquals(frame.height() * 4f / 3f, frame.width(), DELTA);
    }

    @Test
    public void sameAspectRatioFillsTheBounds() {
        RectF frame = fit(new RectF(10, 20, 410, 320), FrameLines.FOUR_THREE);

        assertEquals(10f, frame.left, DELTA);
        assertEquals(20f, frame.top, DELTA);
        assertEquals(410f, frame.right, DELTA);
        assertEquals(320f, frame.bottom, DELTA);
    }

    private static RectF fit(RectF bounds, FrameLines frameLines) {
        RectF out = new RectF();
        FramingHelperOverlayView.fitAspectRatio(bounds, frameLines.getAspectRatio(), out);
        return out;
    }
}
