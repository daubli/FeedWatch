package de.daubli.feedwatch.view.base;

public class OpenGLVideoShader {

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
                    "varying vec2 vTexCoord;" +
                    "void main() {" +
                    "    float x = vTexCoord.x * uFrameWidth;" +      // pixel x in [0, frameWidth)
                    "    float macroX = floor(x / 2.0);" +           // macro-pixel index
                    "    float isOdd = mod(floor(x), 2.0);" +        // 0 for even, 1 for odd
                    "    float texWidth = uFrameWidth / 2.0;" +
                    "    float texX = (macroX + 0.5) / texWidth;" +  // center of texel
                    "    vec4 t = texture2D(uTex, vec2(texX, vTexCoord.y));" +
                    "    float U = t.r - 0.5;" +
                    "    float Y0 = t.g;" +
                    "    float V = t.b - 0.5;" +
                    "    float Y1 = t.a;" +
                    "    float Y = (isOdd < 0.5) ? Y0 : Y1;" +
                    "    float r = Y + 1.402 * V;" +
                    "    float g = Y - 0.344136 * U - 0.714136 * V;" +
                    "    float b = Y + 1.772 * U;" +
                    "    gl_FragColor = vec4(r, g, b, 1.0);" +
                    "}";
    //@formatter:on
}
