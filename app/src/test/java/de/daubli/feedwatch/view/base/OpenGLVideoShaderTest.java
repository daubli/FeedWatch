package de.daubli.feedwatch.view.base;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;

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
