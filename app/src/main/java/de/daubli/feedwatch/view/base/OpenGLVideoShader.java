package de.daubli.feedwatch.view.base;

public class OpenGLVideoShader {

    // NDI sends UYVY in the YUV standard of its resolution (NDI SDK documentation, "Video frames"), and those
    // standards code 8 bit video in limited range (Y 16..235, Cb/Cr 16..240).
    // Column-major Y'CbCr -> R'G'B' matrices (columns: Y, Cb, Cr) for uYuvToRgb, applied after range expansion.
    static final float[] BT601_YUV_TO_RGB = {
            1f, 1f, 1f,
            0f, -0.344136f, 1.772f,
            1.402f, -0.714136f, 0f
    };

    static final float[] BT709_YUV_TO_RGB = {
            1f, 1f, 1f,
            0f, -0.187324f, 1.8556f,
            1.5748f, -0.468124f, 0f
    };

    static final float[] BT2020_YUV_TO_RGB = {
            1f, 1f, 1f,
            0f, -0.164553f, 1.8814f,
            1.4746f, -0.571353f, 0f
    };

    /**
     * The matrix NDI applies at this resolution: BT.601 for SD, Rec. 709 for HD (xres > 720 || yres > 576) and
     * Rec. 2020 for UHD (xres > 1920 || yres > 1080).
     */
    public static float[] yuvToRgbMatrix(int frameWidth, int frameHeight) {
        if (frameWidth > 1920 || frameHeight > 1080) {
            return BT2020_YUV_TO_RGB;
        }
        if (frameWidth > 720 || frameHeight > 576) {
            return BT709_YUV_TO_RGB;
        }
        return BT601_YUV_TO_RGB;
    }

    //@formatter:off
    public static final String VERTEX_SHADER =
            "attribute vec4 aPosition;" +
                    "attribute vec2 aTexCoord;" +
                    "varying vec2 vTexCoord;" +
                    "void main() {" +
                    "    gl_Position = aPosition;" +
                    "    vTexCoord = aTexCoord;" +
                    "}";

    public static final String FRAGMENT_RGBA =
            "precision mediump float;" +
                    "uniform sampler2D uTex;" +
                    "varying vec2 vTexCoord;" +
                    "void main() {" +
                    "  vec4 c = texture2D(uTex, vTexCoord);" +
                    "  float a = c.a;" +
                    "  if (a < 0.001) {" +
                    "    gl_FragColor = vec4(c.rgb, 1.0);" +
                    "  } else {" +
                    "    gl_FragColor = vec4(c.rgb / a, 1.0);" +
                    "  }" +
                    "}";

    public static final String FRAGMENT_BGRA =
            "precision mediump float;" +
                    "uniform sampler2D uTex;" +
                    "varying vec2 vTexCoord;" +
                    "void main() {" +
                    "    vec4 c = texture2D(uTex, vTexCoord);" +
                    "    gl_FragColor = vec4(c.b, c.g, c.r, c.a);" +
                    "}";

    public static final String FRAGMENT_UYVY =
            "precision mediump float;" +
                    "uniform sampler2D uTex;" +
                    "uniform float uFrameWidth;" +   // full frame width in pixels
                    "uniform mat3 uYuvToRgb;" +      // BT.601, Rec. 709 or Rec. 2020, see yuvToRgbMatrix
                    "varying vec2 vTexCoord;" +
                    "void main() {" +
                    "    float x = vTexCoord.x * uFrameWidth;" +      // pixel x in [0, frameWidth)
                    "    float macroX = floor(x / 2.0);" +           // macro-pixel index
                    "    float isOdd = mod(floor(x), 2.0);" +        // 0 for even, 1 for odd
                    "    float texWidth = uFrameWidth / 2.0;" +
                    "    float texX = (macroX + 0.5) / texWidth;" +  // center of texel
                    "    vec4 t = texture2D(uTex, vec2(texX, vTexCoord.y));" +
                    "    float Y = (isOdd < 0.5) ? t.g : t.a;" +
                    // limited range to full range: Y 16..235 -> 0..1, Cb/Cr 16..240 -> -0.5..0.5
                    "    vec3 yuv = vec3(" +
                    "        (Y * 255.0 - 16.0) / 219.0," +
                    "        (t.r * 255.0 - 128.0) / 224.0," +
                    "        (t.b * 255.0 - 128.0) / 224.0);" +
                    "    gl_FragColor = vec4(clamp(uYuvToRgb * yuv, 0.0, 1.0), 1.0);" +
                    "}";
    //@formatter:on
}
