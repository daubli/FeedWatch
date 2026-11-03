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
 * size of the video rect: 13 + 13 + 14 texture reads a pixel instead of the 9 * 25 it took with the blur inside the
 * Sobel loop, which was too much for phone GPUs. Distances are counted in pixels of the video, so a stream shown
 * larger than its size, like the low bandwidth proxy, is judged by its own sharpness.
 * <p>
 * The blur spreads the slope of an edge over several pixels, all of them as sharp. Only the steepest of them, along
 * the direction of the gradient, is marked, so the edges are drawn as thin lines and cover little of the picture.
 * <p>
 * With a mono background the video rect is painted in its luma before the edges are drawn, so they stand out on
 * pictures in any color.
 */
public class FocusAssistOverlay extends Overlay {

    public static final int DEFAULT_SENSITIVITY = 50;

    // sharpness thresholds for 0 % and 100 % sensitivity: a crisp edge comes to about 2.5, a strongly blurred one to
    // about 0.7, whatever its contrast
    private static final float STRICTEST_THRESHOLD = 1.9f;

    private static final float MOST_SENSITIVE_THRESHOLD = 0.85f;

    // opacity of an edge right at the threshold, more above it
    private static final float MIN_EDGE_ALPHA = 0.8f;

    // below this contrast the luma is too flat to tell an edge from noise
    private static final float MIN_CONTRAST = 0.08f;

    // in pixels of the video, wider than a sharp edge, the shaders unroll it
    private static final int RANGE_RADIUS = 6;

    // in pixels of the screen, crops the video a bit, so the border of the video is not detected as an edge
    private static final int BORDER_PIXELS = 4;

    // quad over the whole viewport: (left,top), (left,bottom), (right,top), (right,bottom)
    private static final float[] FULL_VERTICES = { -1f, 1f, -1f, -1f, 1f, 1f, 1f, -1f };

    private static final float[] FULL_TEX_COORDS = { 0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f };

    private final FloatBuffer fullVertexBuffer;

    private final FloatBuffer fullTexCoordBuffer;

    private final FloatBuffer vertexBuffer;

    private final FloatBuffer texCoordBuffer;

    private final FloatBuffer monoTexCoordBuffer;

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

    private int edgeMinAlpha = -1;

    private int edgeColorLocation = -1;

    private int monoProgram = 0;

    private int monoPosition = -1;

    private int monoTexCoord = -1;

    private int monoTexture = -1;

    // [0] across, [1] across and down
    private final int[] framebuffers = new int[2];

    private final int[] framebufferTextures = new int[2];

    private int framebufferWidth = 0;

    private int framebufferHeight = 0;

    // configurable
    private float threshold = thresholdForSensitivity(DEFAULT_SENSITIVITY);

    private final float[] edgeColor = {1.0f, 0.0f, 0.0f};

    private boolean monoBackground = false;

    // size of the video frame, 0 while unknown
    private int frameWidth = 0;

    private int frameHeight = 0;

    public FocusAssistOverlay() {
        fullVertexBuffer = floatBuffer(FULL_VERTICES);
        fullTexCoordBuffer = floatBuffer(FULL_TEX_COORDS);
        vertexBuffer = floatBuffer(new float[8]);
        texCoordBuffer = floatBuffer(new float[8]);
        monoTexCoordBuffer = floatBuffer(new float[8]);
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

    /**
     * Shows the video in gray behind the edges, or in color. Call it on the GL thread.
     */
    public void setMonoBackground(boolean mono) {
        monoBackground = mono;
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

                        // Rec. 709 luma: the mean of R, G and B made edges between green and blue look weak
                        "float gray(vec2 uv) {" +
                        "  return dot(texture2D(uTexture, uv).rgb, vec3(0.2126, 0.7152, 0.0722));" +
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

        // Sobel over the blurred luma, divided by the local contrast: how narrow the edge is. Marked only where the
        // slope along the gradient is steeper than one pixel further and at least as steep as one pixel back, so of
        // two equally steep pixels one is marked. The samples along the gradient fall between texels and are
        // filtered linearly, which needs exact texture coordinates: highp where the GPU has it.
        final String edgeShader =
                "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
                        "precision highp float;\n" +
                        "#else\n" +
                        "precision mediump float;\n" +
                        "#endif\n" +
                        "uniform sampler2D uTexture;" +
                        "uniform vec2 uTexelSize;" +
                        "uniform float uThreshold;" +
                        "uniform float uMinContrast;" +
                        "uniform float uMinAlpha;" +
                        "uniform vec3 uEdgeColor;" +
                        "varying vec2 vTexCoord;" +

                        "float g(float x, float y) {" +
                        "  return texture2D(uTexture, vTexCoord + vec2(x, y) * uTexelSize).r;" +
                        "}" +

                        "float at(vec2 offset) {" +
                        "  return texture2D(uTexture, vTexCoord + offset).r;" +
                        "}" +

                        "void main() {" +
                        "  float tl = g(-1.0, -1.0); float t = g(0.0, -1.0); float tr = g(1.0, -1.0);" +
                        "  float l = g(-1.0, 0.0); float r = g(1.0, 0.0);" +
                        "  float bl = g(-1.0, 1.0); float b = g(0.0, 1.0); float br = g(1.0, 1.0);" +
                        "  float gx = (tl + 2.0 * l + bl) - (tr + 2.0 * r + br);" +
                        "  float gy = (tl + 2.0 * t + tr) - (bl + 2.0 * b + br);" +

                        "  vec4 range = texture2D(uTexture, vTexCoord);" +
                        "  float contrast = range.g - range.b;" +
                        "  float strength = length(vec2(gx, gy));" +
                        "  float sharpness = strength / max(contrast, uMinContrast);" +

                        // one pixel of the video along the gradient, its sign does not matter
                        "  vec2 n = vec2(gx, gy) / max(strength, 0.0001) * uTexelSize;" +
                        "  float slope = abs(at(n) - at(-n));" +
                        "  float slopeAhead = abs(at(2.0 * n) - range.r);" +
                        "  float slopeBehind = abs(range.r - at(-2.0 * n));" +
                        "  bool steepest = slope > slopeAhead && slope >= slopeBehind;" +

                        "  float alpha = (steepest && contrast >= uMinContrast && sharpness >= uThreshold)" +
                        "      ? clamp(uMinAlpha + (sharpness - uThreshold) / uThreshold, 0.0, 1.0) : 0.0;" +
                        "  gl_FragColor = vec4(uEdgeColor, alpha);" +
                        "}";

        // the video in its Rec. 709 luma, as background for the edges
        final String monoShader =
                "precision mediump float;" +
                        "uniform sampler2D uTexture;" +
                        "varying vec2 vTexCoord;" +

                        "void main() {" +
                        "  float y = dot(texture2D(uTexture, vTexCoord).rgb, vec3(0.2126, 0.7152, 0.0722));" +
                        "  gl_FragColor = vec4(y, y, y, 1.0);" +
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
        edgeMinAlpha = GLES20.glGetUniformLocation(edgeProgram, "uMinAlpha");
        edgeColorLocation = GLES20.glGetUniformLocation(edgeProgram, "uEdgeColor");

        monoProgram = createProgram(vertexShader, monoShader);
        monoPosition = GLES20.glGetAttribLocation(monoProgram, "aPosition");
        monoTexCoord = GLES20.glGetAttribLocation(monoProgram, "aTexCoord");
        monoTexture = GLES20.glGetUniformLocation(monoProgram, "uTexture");

        // the old framebuffers went with the old context
        framebufferWidth = 0;
        framebufferHeight = 0;

        initialized = true;
    }

    @Override
    public void draw(int videoTextureId, int videoRectLeft, int videoRectTop, int videoRectWidth, int videoRectHeight,
            int surfaceWidth, int surfaceHeight) {
        // the measured rect, the video rect without its border. Measured and marked pixel for pixel, so the thin
        // edges lie exactly on the picture.
        int left = videoRectLeft + BORDER_PIXELS;
        int top = videoRectTop + BORDER_PIXELS;
        int width = videoRectWidth - 2 * BORDER_PIXELS;
        int height = videoRectHeight - 2 * BORDER_PIXELS;
        if (width <= 0 || height <= 0) {
            return;
        }

        initGL();
        ensureFramebuffers(width, height);

        // screen pixels per pixel of the video: a video shown larger is judged at its own size
        float pixelScale = frameWidth > 0 ? Math.max(1f, (float) videoRectWidth / frameWidth) : 1f;

        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        // 1) across: the measured rect of the surface-sized FBO texture into framebuffer 0
        updateVideoTexCoords(texCoordBuffer, left, top, width, height, surfaceWidth, surfaceHeight);
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
        GLES20.glUniform2f(rangeSteps[1], 0f, pixelScale / height);
        drawQuad(rangePositions[1], rangeTexCoords[1], fullVertexBuffer, fullTexCoordBuffer);

        // 3) edges of framebuffer 1, blended over the measured rect on screen
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);

        if (monoBackground) {
            // the gray video covers the colored one exactly, so it is not cropped
            updateVideoVertices(videoRectLeft, videoRectTop, videoRectWidth, videoRectHeight, surfaceWidth,
                    surfaceHeight);
            updateVideoTexCoords(monoTexCoordBuffer, videoRectLeft, videoRectTop, videoRectWidth, videoRectHeight,
                    surfaceWidth, surfaceHeight);
            GLES20.glUseProgram(monoProgram);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, videoTextureId);
            GLES20.glUniform1i(monoTexture, 0);
            drawQuad(monoPosition, monoTexCoord, vertexBuffer, monoTexCoordBuffer);
        }

        updateVideoVertices(left, top, width, height, surfaceWidth, surfaceHeight);
        GLES20.glUseProgram(edgeProgram);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, framebufferTextures[1]);
        GLES20.glUniform1i(edgeTexture, 0);
        GLES20.glUniform2f(edgeTexelSize, pixelScale / width, pixelScale / height);
        GLES20.glUniform1f(edgeThreshold, threshold);
        GLES20.glUniform1f(edgeMinContrast, MIN_CONTRAST);
        GLES20.glUniform1f(edgeMinAlpha, MIN_EDGE_ALPHA);
        GLES20.glUniform3f(edgeColorLocation, edgeColor[0], edgeColor[1], edgeColor[2]);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        drawQuad(edgePosition, edgeTexCoord, vertexBuffer, fullTexCoordBuffer);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /**
     * The part of the surface-sized FBO texture under a rect of the surface. The FBO texture is flipped compared to
     * the video frame.
     */
    private void updateVideoTexCoords(FloatBuffer texCoords, int rectLeft, int rectTop, int rectWidth,
            int rectHeight, int surfaceWidth, int surfaceHeight) {
        float u0 = (float) rectLeft / (float) surfaceWidth;
        float u1 = (float) (rectLeft + rectWidth) / (float) surfaceWidth;
        float vTop = 1f - (float) rectTop / (float) surfaceHeight;
        float vBottom = 1f - (float) (rectTop + rectHeight) / (float) surfaceHeight;

        // the framebuffer keeps the FBO's orientation, so its top row is at the top of the viewport
        dynTex[0] = u0;
        dynTex[1] = vTop;
        dynTex[2] = u0;
        dynTex[3] = vBottom;
        dynTex[4] = u1;
        dynTex[5] = vTop;
        dynTex[6] = u1;
        dynTex[7] = vBottom;

        texCoords.clear();
        texCoords.put(dynTex).position(0);
    }

    /**
     * NDC quad for a rect of the surface (no heap alloc)
     */
    private void updateVideoVertices(int rectLeft, int rectTop, int rectWidth, int rectHeight, int surfaceWidth,
            int surfaceHeight) {
        float left = 2f * rectLeft / surfaceWidth - 1f;
        float right = 2f * (rectLeft + rectWidth) / surfaceWidth - 1f;
        float top = 1f - 2f * rectTop / surfaceHeight;
        float bottom = 1f - 2f * (rectTop + rectHeight) / surfaceHeight;

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
            // one texel per pixel, but the edges sample framebuffer 1 between texels along the gradient
            int filter = i == 1 ? GLES20.GL_LINEAR : GLES20.GL_NEAREST;
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
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
