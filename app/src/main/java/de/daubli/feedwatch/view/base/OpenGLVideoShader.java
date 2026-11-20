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

    // A texel of the UYVY texture holds two pixels, so its linear filter cannot blend neighbouring pixels: the
    // shader does it across a row (the filter still blends the rows). Without that, a frame drawn larger or smaller
    // than its size took the nearest pixel, and text came out with uneven strokes. highp where the GPU has it: in a
    // 16 bit float (mediump on many phones) a pixel position past 1024 keeps no fraction.
    public static final String FRAGMENT_UYVY =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
                    "precision highp float;\n" +
                    "#else\n" +
                    "precision mediump float;\n" +
                    "#endif\n" +
                    "uniform sampler2D uTex;" +
                    "uniform float uFrameWidth;" +   // full frame width in pixels
                    "uniform mat3 uYuvToRgb;" +      // BT.601, Rec. 709 or Rec. 2020, see yuvToRgbMatrix
                    "uniform float uMeanOfPair;" +   // 1 to take the mean luma of both pixels, for shrunk frames
                    "varying vec2 vTexCoord;" +
                    "void main() {" +
                    "    float texWidth = uFrameWidth / 2.0;" +
                    "    float x = vTexCoord.x * uFrameWidth;" +
                    // the pixel centre left of this point (i0), its pair (m0) and how far the point is to the next
                    "    float xs = clamp(x - 0.5, 0.0, uFrameWidth - 1.0);" +
                    "    float i0 = floor(xs);" +
                    "    float m0 = floor(i0 / 2.0);" +
                    "    float odd = i0 - 2.0 * m0;" +               // 0 for an even i0, 1 for odd
                    "    vec4 a = texture2D(uTex, vec2((m0 + 0.5) / texWidth, vTexCoord.y));" +
                    "    vec4 b = texture2D(uTex, vec2((m0 + 1.5) / texWidth, vTexCoord.y));" +  // clamps at the edge
                    "    float Y = mix(mix(a.g, a.a, odd), mix(a.a, b.g, odd), xs - i0);" +
                    "    vec2 c = mix(a.rb, b.rb, (xs - 2.0 * m0) * 0.5);" +  // Cb/Cr sit with the even pixel
                    // drawn at half its width or less: the mean luma of the pair the point is in (a or b) and its
                    // own Cb/Cr, as before
                    "    vec4 t = mix(a, b, clamp(floor(x / 2.0) - m0, 0.0, 1.0));" +
                    "    Y = mix(Y, (t.g + t.a) * 0.5, uMeanOfPair);" +
                    "    c = mix(c, t.rb, uMeanOfPair);" +
                    // limited range to full range: Y 16..235 -> 0..1, Cb/Cr 16..240 -> -0.5..0.5
                    "    vec3 yuv = vec3(" +
                    "        (Y * 255.0 - 16.0) / 219.0," +
                    "        (c.x * 255.0 - 128.0) / 224.0," +
                    "        (c.y * 255.0 - 128.0) / 224.0);" +
                    "    gl_FragColor = vec4(clamp(uYuvToRgb * yuv, 0.0, 1.0), 1.0);" +
                    "}";
    //@formatter:on
}
