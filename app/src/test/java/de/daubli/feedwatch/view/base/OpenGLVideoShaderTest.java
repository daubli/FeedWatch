package de.daubli.feedwatch.view.base;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OpenGLVideoShaderTest {

    private static final float DELTA = 0.002f;

    @Test
    public void sdFramesUseBt601() {
        assertSame(OpenGLVideoShader.BT601_YUV_TO_RGB, OpenGLVideoShader.yuvToRgbMatrix(720, 576));
        assertSame(OpenGLVideoShader.BT601_YUV_TO_RGB, OpenGLVideoShader.yuvToRgbMatrix(720, 480));
        assertSame(OpenGLVideoShader.BT601_YUV_TO_RGB, OpenGLVideoShader.yuvToRgbMatrix(640, 360));
    }

    @Test
    public void framesWiderOrTallerThanSdUseRec709() {
        assertSame(OpenGLVideoShader.BT709_YUV_TO_RGB, OpenGLVideoShader.yuvToRgbMatrix(960, 540));
        assertSame(OpenGLVideoShader.BT709_YUV_TO_RGB, OpenGLVideoShader.yuvToRgbMatrix(720, 578));
        assertSame(OpenGLVideoShader.BT709_YUV_TO_RGB, OpenGLVideoShader.yuvToRgbMatrix(1280, 720));
        assertSame(OpenGLVideoShader.BT709_YUV_TO_RGB, OpenGLVideoShader.yuvToRgbMatrix(1920, 1080));
    }

    @Test
    public void framesWiderOrTallerThanHdUseRec2020() {
        assertSame(OpenGLVideoShader.BT2020_YUV_TO_RGB, OpenGLVideoShader.yuvToRgbMatrix(2048, 1080));
        assertSame(OpenGLVideoShader.BT2020_YUV_TO_RGB, OpenGLVideoShader.yuvToRgbMatrix(1080, 1920));
        assertSame(OpenGLVideoShader.BT2020_YUV_TO_RGB, OpenGLVideoShader.yuvToRgbMatrix(3840, 2160));
    }

    @Test
    public void limitedRangeBlackAndWhiteBecomeFullRange() {
        assertArrayEquals(new float[] { 0f, 0f, 0f }, toRgb(OpenGLVideoShader.BT709_YUV_TO_RGB, 16, 128, 128), DELTA);
        assertArrayEquals(new float[] { 1f, 1f, 1f }, toRgb(OpenGLVideoShader.BT709_YUV_TO_RGB, 235, 128, 128), DELTA);
    }

    @Test
    public void bt709RedRoundTrips() {
        // 100% red in limited range BT.709: Y 63, Cb 102, Cr 240
        assertArrayEquals(new float[] { 1f, 0f, 0f }, toRgb(OpenGLVideoShader.BT709_YUV_TO_RGB, 63, 102, 240), 0.01f);
    }

    @Test
    public void bt601RedRoundTrips() {
        // 100% red in limited range BT.601: Y 81, Cb 90, Cr 240
        assertArrayEquals(new float[] { 1f, 0f, 0f }, toRgb(OpenGLVideoShader.BT601_YUV_TO_RGB, 81, 90, 240), 0.01f);
    }

    @Test
    public void rec2020RedRoundTrips() {
        // 100% red in limited range Rec. 2020: Y 74, Cb 97, Cr 240
        assertArrayEquals(new float[] { 1f, 0f, 0f }, toRgb(OpenGLVideoShader.BT2020_YUV_TO_RGB, 74, 97, 240), 0.01f);
    }

    @Test
    public void uyvyAsksForHighpWhereTheGpuHasIt() {
        assertTrue(OpenGLVideoShader.FRAGMENT_UYVY.startsWith("#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
                + "precision highp float;\n#else\nprecision mediump float;\n#endif\n"));
    }

    @Test
    public void uyvyLumaIsThePixelAtItsCentreAndBlendedBetweenPixels() {
        int[] y = { 16, 235, 100, 50 };      // pixels 0..3, two pairs
        assertEquals(235f, luma(y, 1.5f), DELTA);
        assertEquals(100f, luma(y, 2.5f), DELTA);
        assertEquals((235f + 100f) / 2f, luma(y, 2f), DELTA);       // between the two pairs
        assertEquals(16f + (235f - 16f) * 0.25f, luma(y, 0.75f), DELTA);
        assertEquals(16f, luma(y, 0.1f), DELTA);                    // outside the first and last centres: the edge
        assertEquals(50f, luma(y, 3.9f), DELTA);
    }

    @Test
    public void uyvyChromaSitsWithTheEvenPixelOfEachPair() {
        int[] c = { 90, 240 };               // Cb of pairs 0 and 1 (pixels 0-1 and 2-3)
        assertEquals(90f, chroma(c, 0.5f), DELTA);                  // pixel 0
        assertEquals((90f + 240f) / 2f, chroma(c, 1.5f), DELTA);    // pixel 1: halfway to pixel 2's
        assertEquals(240f, chroma(c, 2.5f), DELTA);                 // pixel 2
        assertEquals(240f, chroma(c, 3.5f), DELTA);                 // pixel 3, the last pair: its own
    }

    /** Mirrors FRAGMENT_UYVY's luma at a point x of a row (pixel centres at i + 0.5). */
    private static float luma(int[] y, float x) {
        float xs = Math.max(0f, Math.min(x - 0.5f, y.length - 1f));
        int i0 = (int) Math.floor(xs);
        return y[i0] + (y[Math.min(i0 + 1, y.length - 1)] - y[i0]) * (xs - i0);
    }

    /** Mirrors FRAGMENT_UYVY's Cb (or Cr) at a point x, from one value per pair; the texture clamps at the edge. */
    private static float chroma(int[] c, float x) {
        float xs = Math.max(0f, Math.min(x - 0.5f, 2f * c.length - 1f));
        int m0 = (int) Math.floor(Math.floor(xs) / 2f);
        return c[m0] + (c[Math.min(m0 + 1, c.length - 1)] - c[m0]) * (xs - 2f * m0) * 0.5f;
    }

    /** Mirrors FRAGMENT_UYVY: range expansion, then the column-major matrix. */
    private static float[] toRgb(float[] m, int y, int cb, int cr) {
        float[] yuv = { (y - 16f) / 219f, (cb - 128f) / 224f, (cr - 128f) / 224f };
        float[] rgb = new float[3];
        for (int row = 0; row < 3; row++) {
            rgb[row] = m[row] * yuv[0] + m[3 + row] * yuv[1] + m[6 + row] * yuv[2];
        }
        return rgb;
    }
}
