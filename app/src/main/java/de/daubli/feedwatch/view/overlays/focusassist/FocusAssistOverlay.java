package de.daubli.feedwatch.view.overlays.focusassist;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import android.graphics.Color;
import android.opengl.GLES20;
import de.daubli.feedwatch.view.overlays.Overlay;

/**
 * Marks sharp edges. The edge strength alone (Sobel over the blurred luma) depends on the contrast as much as on the
 * sharpness: a black and white edge stayed marked long after it went out of focus. So the strength is divided by
 * the local contrast, the brightest minus the darkest luma within {@link #RANGE_RADIUS} pixels. What is left depends
 * on how narrow the edge is, and that is what the threshold compares.
 * <p>
 * Two passes, across and then down, write the blurred luma and the local maximum and minimum into framebuffers the
 * size of the video rect: 13 + 13 + 10 texture reads a pixel instead of the 9 * 25 it took with the blur inside the
 * Sobel loop, which was too much for phone GPUs. Distances are counted in pixels of the video, so a stream shown
 * larger than its size, like the low bandwidth proxy, is judged by its own sharpness.
 */
public class FocusAssistOverlay extends Overlay {

    public static final int DEFAULT_SENSITIVITY = 50;

    // sharpness thresholds for 0 % and 100 % sensitivity: a crisp edge comes to about 2.5, a strongly blurred one to
    // about 0.7, whatever its contrast
    private static final float STRICTEST_THRESHOLD = 1.9f;

    private static final float MOST_SENSITIVE_THRESHOLD = 0.85f;

    // below this contrast the luma is too flat to tell an edge from noise
    private static final float MIN_CONTRAST = 0.08f;

    // in pixels of the video, wider than a sharp edge, the shaders unroll it
    private static final int RANGE_RADIUS = 6;

    // crops the video a bit, so the border of the video is not detected as an edge
    private static final float BORDER_TEXELS = 3.5f;

    // quad over the whole viewport: (left,top), (left,bottom), (right,top), (right,bottom)
    private static final float[] FULL_VERTICES = { -1f, 1f, -1f, -1f, 1f, 1f, 1f, -1f };

    private static final float[] FULL_TEX_COORDS = { 0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f };

    private final FloatBuffer fullVertexBuffer;

    private final FloatBuffer fullTexCoordBuffer;

    private final FloatBuffer vertexBuffer;

    private final FloatBuffer texCoordBuffer;

    private final float[] dynTex = new float[8];

    private final float[] dynVertices = new float[8]; // reuse to avoid per-frame allocation

    private boolean initialized = false;

    // across: luma of the video, down: the framebuffer of the pass across. Both write blurred luma, max and min.
    private final int[] rangePrograms = new int[2];

    private final int[] rangePositions = new int[2];

    private final int[] rangeTexCoords = new int[2];

    private final int[] rangeTextures = new int[2];

    private final int[] rangeSteps = new int[2];

    private int edgeProgram = 0;

    private int edgePosition = -1;

    private int edgeTexCoord = -1;

    private int edgeTexture = -1;

    private int edgeTexelSize = -1;

    private int edgeThreshold = -1;

    private int edgeMinContrast = -1;

    private int edgeColorLocation = -1;

    // [0] across, [1] across and down
    private final int[] framebuffers = new int[2];

    private final int[] framebufferTextures = new int[2];

    private int framebufferWidth = 0;

    private int framebufferHeight = 0;

    // configurable
    private float threshold = thresholdForSensitivity(DEFAULT_SENSITIVITY);

    private final float[] edgeColor = {1.0f, 0.0f, 0.0f};

    // size of the video frame, 0 while unknown
    private int frameWidth = 0;

    private int frameHeight = 0;

    public FocusAssistOverlay() {
        fullVertexBuffer = floatBuffer(FULL_VERTICES);
        fullTexCoordBuffer = floatBuffer(FULL_TEX_COORDS);
        vertexBuffer = floatBuffer(new float[8]);
        texCoordBuffer = floatBuffer(new float[8]);
    }

    /**
     * The sharpness from which edges are marked: 0 % only marks crisp edges, 100 % also softer ones. The steps in
     * between are even on a log scale, so each feels alike.
     */
    public static float thresholdForSensitivity(int percent) {
        float share = Math.max(0, Math.min(100, percent)) / 100f;
        return (float) (STRICTEST_THRESHOLD * Math.pow(MOST_SENSITIVE_THRESHOLD / STRICTEST_THRESHOLD, share));
    }

    /**
     * Sets how soft an edge may be to still be marked, in percent. Call it on the GL thread.
     */
    public void setSensitivity(int percent) {
        threshold = thresholdForSensitivity(percent);
    }

    @Override
    public void setFrameSize(int width, int height) {
        frameWidth = width;
        frameHeight = height;
    }

    /**
     * Sets the color edges are drawn in. Call it on the GL thread.
     */
    public void setEdgeColor(int color) {
        edgeColor[0] = Color.red(color) / 255f;
        edgeColor[1] = Color.green(color) / 255f;
        edgeColor[2] = Color.blue(color) / 255f;
    }

    private void initGL() {
        // a new EGL context drops the old programs and framebuffers
        if (initialized && GLES20.glIsProgram(rangePrograms[0])) {
            return;
        }

        //@formatter:off
        final String vertexShader =
                "attribute vec4 aPosition;" +
                        "attribute vec2 aTexCoord;" +
                        "varying vec2 vTexCoord;" +
                        "void main() {" +
                        "  gl_Position = aPosition;" +
                        "  vTexCoord = aTexCoord;" +
                        "}";

        // Along uStep, one pixel of the video: the 5 tap binomial blur of the luma into red, the largest and the
        // smallest luma within RANGE_RADIUS pixels into green and blue. Across it reads the video, down the result
        // of across.
        final String rangeAcrossShader =
                "precision mediump float;" +
                        "uniform sampler2D uTexture;" +
                        "uniform vec2 uStep;" +
                        "varying vec2 vTexCoord;" +

                        "float gray(vec2 uv) {" +
                        "  vec3 c = texture2D(uTexture, uv).rgb;" +
                        "  return (c.r + c.g + c.b) * 0.3333333;" +
                        "}" +

                        "void main() {" +
                        "  float c = gray(vTexCoord);" +
                        "  float a1 = gray(vTexCoord - uStep); float b1 = gray(vTexCoord + uStep);" +
                        "  float a2 = gray(vTexCoord - 2.0 * uStep); float b2 = gray(vTexCoord + 2.0 * uStep);" +
                        "  float hi = max(c, max(max(a1, b1), max(a2, b2)));" +
                        "  float lo = min(c, min(min(a1, b1), min(a2, b2)));" +
                        "  for (int i = 3; i <= " + RANGE_RADIUS + "; i++) {" +
                        "    float a = gray(vTexCoord - float(i) * uStep); float b = gray(vTexCoord + float(i) * uStep);" +
                        "    hi = max(hi, max(a, b)); lo = min(lo, min(a, b));" +
                        "  }" +
                        "  gl_FragColor = vec4((c * 6.0 + (a1 + b1) * 4.0 + a2 + b2) * 0.0625, hi, lo, 1.0);" +
                        "}";

        final String rangeDownShader =
                "precision mediump float;" +
                        "uniform sampler2D uTexture;" +
                        "uniform vec2 uStep;" +
                        "varying vec2 vTexCoord;" +

                        "void main() {" +
                        "  vec4 c = texture2D(uTexture, vTexCoord);" +
                        "  vec4 a1 = texture2D(uTexture, vTexCoord - uStep); vec4 b1 = texture2D(uTexture, vTexCoord + uStep);" +
                        "  vec4 a2 = texture2D(uTexture, vTexCoord - 2.0 * uStep);" +
                        "  vec4 b2 = texture2D(uTexture, vTexCoord + 2.0 * uStep);" +
                        "  float hi = max(c.g, max(max(a1.g, b1.g), max(a2.g, b2.g)));" +
                        "  float lo = min(c.b, min(min(a1.b, b1.b), min(a2.b, b2.b)));" +
                        "  for (int i = 3; i <= " + RANGE_RADIUS + "; i++) {" +
                        "    vec4 a = texture2D(uTexture, vTexCoord - float(i) * uStep);" +
                        "    vec4 b = texture2D(uTexture, vTexCoord + float(i) * uStep);" +
                        "    hi = max(hi, max(a.g, b.g)); lo = min(lo, min(a.b, b.b));" +
                        "  }" +
                        "  gl_FragColor = vec4((c.r * 6.0 + (a1.r + b1.r) * 4.0 + a2.r + b2.r) * 0.0625, hi, lo, 1.0);" +
                        "}";

        // Sobel over the blurred luma, divided by the local contrast: how narrow the edge is
        final String edgeShader =
                "precision mediump float;" +
                        "uniform sampler2D uTexture;" +
                        "uniform vec2 uTexelSize;" +
                        "uniform float uThreshold;" +
                        "uniform float uMinContrast;" +
                        "uniform vec3 uEdgeColor;" +
                        "varying vec2 vTexCoord;" +

                        "float g(float x, float y) {" +
                        "  return texture2D(uTexture, vTexCoord + vec2(x, y) * uTexelSize).r;" +
                        "}" +

                        "void main() {" +
                        "  float tl = g(-1.0, -1.0); float t = g(0.0, -1.0); float tr = g(1.0, -1.0);" +
                        "  float l = g(-1.0, 0.0); float r = g(1.0, 0.0);" +
                        "  float bl = g(-1.0, 1.0); float b = g(0.0, 1.0); float br = g(1.0, 1.0);" +
                        "  float gx = (tl + 2.0 * l + bl) - (tr + 2.0 * r + br);" +
                        "  float gy = (tl + 2.0 * t + tr) - (bl + 2.0 * b + br);" +

                        "  vec4 range = texture2D(uTexture, vTexCoord);" +
                        "  float contrast = range.g - range.b;" +
                        "  float sharpness = length(vec2(gx, gy)) / max(contrast, uMinContrast);" +
                        "  float alpha = (contrast >= uMinContrast && sharpness >= uThreshold)" +
                        "      ? clamp(0.6 + (sharpness - uThreshold) / uThreshold, 0.0, 1.0) : 0.0;" +
                        "  gl_FragColor = vec4(uEdgeColor, alpha);" +
                        "}";
        //@formatter:on

        String[] rangeShaders = { rangeAcrossShader, rangeDownShader };
        for (int i = 0; i < 2; i++) {
            rangePrograms[i] = createProgram(vertexShader, rangeShaders[i]);
            rangePositions[i] = GLES20.glGetAttribLocation(rangePrograms[i], "aPosition");
            rangeTexCoords[i] = GLES20.glGetAttribLocation(rangePrograms[i], "aTexCoord");
            rangeTextures[i] = GLES20.glGetUniformLocation(rangePrograms[i], "uTexture");
            rangeSteps[i] = GLES20.glGetUniformLocation(rangePrograms[i], "uStep");
        }

        edgeProgram = createProgram(vertexShader, edgeShader);
        edgePosition = GLES20.glGetAttribLocation(edgeProgram, "aPosition");
        edgeTexCoord = GLES20.glGetAttribLocation(edgeProgram, "aTexCoord");
        edgeTexture = GLES20.glGetUniformLocation(edgeProgram, "uTexture");
        edgeTexelSize = GLES20.glGetUniformLocation(edgeProgram, "uTexelSize");
        edgeThreshold = GLES20.glGetUniformLocation(edgeProgram, "uThreshold");
        edgeMinContrast = GLES20.glGetUniformLocation(edgeProgram, "uMinContrast");
        edgeColorLocation = GLES20.glGetUniformLocation(edgeProgram, "uEdgeColor");

        // the old framebuffers went with the old context
        framebufferWidth = 0;
        framebufferHeight = 0;

        initialized = true;
    }

    @Override
    public void draw(int videoTextureId, int videoRectLeft, int videoRectTop, int videoRectWidth, int videoRectHeight,
            int surfaceWidth, int surfaceHeight) {
        if (videoRectWidth <= 0 || videoRectHeight <= 0) {
            return;
        }

        initGL();
        ensureFramebuffers(videoRectWidth, videoRectHeight);

        // screen pixels per pixel of the video: a video shown larger is judged at its own size
        float pixelScale = frameWidth > 0 ? Math.max(1f, (float) videoRectWidth / frameWidth) : 1f;

        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glViewport(0, 0, videoRectWidth, videoRectHeight);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        // 1) across: the video rect of the surface-sized FBO texture into framebuffer 0
        updateVideoTexCoords(videoRectLeft, videoRectTop, videoRectWidth, videoRectHeight, surfaceWidth,
                surfaceHeight);
        GLES20.glUseProgram(rangePrograms[0]);
        GLES20.glUniform1i(rangeTextures[0], 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0]);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, videoTextureId);
        GLES20.glUniform2f(rangeSteps[0], pixelScale / surfaceWidth, 0f);
        drawQuad(rangePositions[0], rangeTexCoords[0], fullVertexBuffer, texCoordBuffer);

        // 2) down: framebuffer 0 into framebuffer 1
        GLES20.glUseProgram(rangePrograms[1]);
        GLES20.glUniform1i(rangeTextures[1], 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[1]);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, framebufferTextures[0]);
        GLES20.glUniform2f(rangeSteps[1], 0f, pixelScale / videoRectHeight);
        drawQuad(rangePositions[1], rangeTexCoords[1], fullVertexBuffer, fullTexCoordBuffer);

        // 3) edges of framebuffer 1, blended over the video rect on screen
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        updateVideoVertices(videoRectLeft, videoRectTop, videoRectWidth, videoRectHeight, surfaceWidth,
                surfaceHeight);

        GLES20.glUseProgram(edgeProgram);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, framebufferTextures[1]);
        GLES20.glUniform1i(edgeTexture, 0);
        GLES20.glUniform2f(edgeTexelSize, pixelScale / videoRectWidth, pixelScale / videoRectHeight);
        GLES20.glUniform1f(edgeThreshold, threshold);
        GLES20.glUniform1f(edgeMinContrast, MIN_CONTRAST);
        GLES20.glUniform3f(edgeColorLocation, edgeColor[0], edgeColor[1], edgeColor[2]);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        drawQuad(edgePosition, edgeTexCoord, vertexBuffer, fullTexCoordBuffer);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /**
     * The part of the surface-sized FBO texture under the video rect, cropped by {@link #BORDER_TEXELS}. The FBO
     * texture is flipped compared to the video frame.
     */
    private void updateVideoTexCoords(int videoRectLeft, int videoRectTop, int videoRectWidth, int videoRectHeight,
            int surfaceWidth, int surfaceHeight) {
        float du = BORDER_TEXELS / (float) surfaceWidth;
        float dv = BORDER_TEXELS / (float) surfaceHeight;
        float u0 = (float) videoRectLeft / (float) surfaceWidth + du;
        float u1 = (float) (videoRectLeft + videoRectWidth) / (float) surfaceWidth - du;
        float vTop = 1f - (float) videoRectTop / (float) surfaceHeight - dv;
        float vBottom = 1f - (float) (videoRectTop + videoRectHeight) / (float) surfaceHeight + dv;

        // the framebuffer keeps the FBO's orientation, so its top row is at the top of the viewport
        dynTex[0] = u0;
        dynTex[1] = vTop;
        dynTex[2] = u0;
        dynTex[3] = vBottom;
        dynTex[4] = u1;
        dynTex[5] = vTop;
        dynTex[6] = u1;
        dynTex[7] = vBottom;

        texCoordBuffer.clear();
        texCoordBuffer.put(dynTex).position(0);
    }

    /**
     * NDC quad for the video rect (no heap alloc)
     */
    private void updateVideoVertices(int videoRectLeft, int videoRectTop, int videoRectWidth, int videoRectHeight,
            int surfaceWidth, int surfaceHeight) {
        float left = 2f * videoRectLeft / surfaceWidth - 1f;
        float right = 2f * (videoRectLeft + videoRectWidth) / surfaceWidth - 1f;
        float top = 1f - 2f * videoRectTop / surfaceHeight;
        float bottom = 1f - 2f * (videoRectTop + videoRectHeight) / surfaceHeight;

        dynVertices[0] = left;
        dynVertices[1] = top;
        dynVertices[2] = left;
        dynVertices[3] = bottom;
        dynVertices[4] = right;
        dynVertices[5] = top;
        dynVertices[6] = right;
        dynVertices[7] = bottom;

        vertexBuffer.clear();
        vertexBuffer.put(dynVertices).position(0);
    }

    private static void drawQuad(int position, int texCoord, FloatBuffer vertices, FloatBuffer texCoords) {
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices);
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords);
        GLES20.glEnableVertexAttribArray(texCoord);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(position);
        GLES20.glDisableVertexAttribArray(texCoord);
    }

    private void ensureFramebuffers(int width, int height) {
        if (width == framebufferWidth && height == framebufferHeight) {
            return;
        }
        if (framebufferWidth > 0) {
            GLES20.glDeleteFramebuffers(2, framebuffers, 0);
            GLES20.glDeleteTextures(2, framebufferTextures, 0);
        }

        GLES20.glGenTextures(2, framebufferTextures, 0);
        GLES20.glGenFramebuffers(2, framebuffers, 0);
        for (int i = 0; i < 2; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, framebufferTextures[i]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE, null);
            // one texel per pixel, the filter only matters at the clamped edges
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D,
                    framebufferTextures[i], 0);
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                throw new RuntimeException("Focus assist framebuffer not complete: " + status);
            }
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        framebufferWidth = width;
        framebufferHeight = height;
    }

    private static FloatBuffer floatBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }

    private int createProgram(String vsCode, String fsCode) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vsCode);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsCode);

        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(prog);
            GLES20.glDeleteProgram(prog);
            throw new RuntimeException("Program link failed: " + log);
        }
        return prog;
    }

    private int compileShader(int type, String code) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return shader;
    }
}
