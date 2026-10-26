package de.daubli.feedwatch.view.overlays.focusassist;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import android.graphics.Color;
import android.opengl.GLES20;
import de.daubli.feedwatch.view.overlays.Overlay;

/**
 * Marks sharp edges: a Sobel filter over the blurred luma. The blur runs in two passes, across and then down, into
 * framebuffers the size of the video rect, so a pixel takes 5 + 5 + 9 texture reads instead of the 9 * 25 it took
 * with the blur inside the Sobel loop. That was too much for phone GPUs and held the picture at a few frames per
 * second.
 */
public class FocusAssistOverlay extends Overlay {

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

    private int blurProgram = 0;

    private int blurPosition = -1;

    private int blurTexCoord = -1;

    private int blurTexture = -1;

    private int blurStep = -1;

    private int edgeProgram = 0;

    private int edgePosition = -1;

    private int edgeTexCoord = -1;

    private int edgeTexture = -1;

    private int edgeTexelSize = -1;

    private int edgeThreshold = -1;

    private int edgeColorLocation = -1;

    // [0] blurred across, [1] blurred across and down
    private final int[] framebuffers = new int[2];

    private final int[] framebufferTextures = new int[2];

    private int framebufferWidth = 0;

    private int framebufferHeight = 0;

    // configurable
    private float threshold = 0.3f;

    private final float[] edgeColor = {1.0f, 0.0f, 0.0f};

    public FocusAssistOverlay() {
        fullVertexBuffer = floatBuffer(FULL_VERTICES);
        fullTexCoordBuffer = floatBuffer(FULL_TEX_COORDS);
        vertexBuffer = floatBuffer(new float[8]);
        texCoordBuffer = floatBuffer(new float[8]);
    }

    /**
     * Sets the edge strength from which edges are marked. Call it on the GL thread.
     */
    public void setThreshold(float threshold) {
        this.threshold = threshold;
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
        if (initialized && GLES20.glIsProgram(blurProgram)) {
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

        // 5 tap binomial blur of the luma along uStep, the luma goes to all channels. The second pass reads the
        // luma of the first, which stays the same.
        final String blurShader =
                "precision mediump float;" +
                        "uniform sampler2D uTexture;" +
                        "uniform vec2 uStep;" +
                        "varying vec2 vTexCoord;" +

                        "float gray(vec2 uv) {" +
                        "  vec3 c = texture2D(uTexture, uv).rgb;" +
                        "  return (c.r + c.g + c.b) * 0.3333333;" +
                        "}" +

                        "void main() {" +
                        "  float v = gray(vTexCoord) * 6.0" +
                        "      + (gray(vTexCoord - uStep) + gray(vTexCoord + uStep)) * 4.0" +
                        "      + gray(vTexCoord - 2.0 * uStep) + gray(vTexCoord + 2.0 * uStep);" +
                        "  v *= 0.0625;" +
                        "  gl_FragColor = vec4(v, v, v, 1.0);" +
                        "}";

        final String edgeShader =
                "precision mediump float;" +
                        "uniform sampler2D uTexture;" +
                        "uniform vec2 uTexelSize;" +
                        "uniform float uThreshold;" +
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

                        "  float edge = length(vec2(gx, gy));" +
                        "  float alpha = (edge >= uThreshold) ? edge : 0.0;" +
                        "  alpha = clamp(alpha, 0.0, 1.0);" +
                        "  gl_FragColor = vec4(uEdgeColor, alpha);" +
                        "}";
        //@formatter:on

        blurProgram = createProgram(vertexShader, blurShader);
        blurPosition = GLES20.glGetAttribLocation(blurProgram, "aPosition");
        blurTexCoord = GLES20.glGetAttribLocation(blurProgram, "aTexCoord");
        blurTexture = GLES20.glGetUniformLocation(blurProgram, "uTexture");
        blurStep = GLES20.glGetUniformLocation(blurProgram, "uStep");

        edgeProgram = createProgram(vertexShader, edgeShader);
        edgePosition = GLES20.glGetAttribLocation(edgeProgram, "aPosition");
        edgeTexCoord = GLES20.glGetAttribLocation(edgeProgram, "aTexCoord");
        edgeTexture = GLES20.glGetUniformLocation(edgeProgram, "uTexture");
        edgeTexelSize = GLES20.glGetUniformLocation(edgeProgram, "uTexelSize");
        edgeThreshold = GLES20.glGetUniformLocation(edgeProgram, "uThreshold");
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

        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glViewport(0, 0, videoRectWidth, videoRectHeight);
        GLES20.glUseProgram(blurProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUniform1i(blurTexture, 0);

        // 1) across: the video rect of the surface-sized FBO texture into framebuffer 0
        updateVideoTexCoords(videoRectLeft, videoRectTop, videoRectWidth, videoRectHeight, surfaceWidth,
                surfaceHeight);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0]);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, videoTextureId);
        GLES20.glUniform2f(blurStep, 1.0f / surfaceWidth, 0f);
        drawQuad(blurPosition, blurTexCoord, fullVertexBuffer, texCoordBuffer);

        // 2) down: framebuffer 0 into framebuffer 1
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[1]);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, framebufferTextures[0]);
        GLES20.glUniform2f(blurStep, 0f, 1.0f / videoRectHeight);
        drawQuad(blurPosition, blurTexCoord, fullVertexBuffer, fullTexCoordBuffer);

        // 3) edges of framebuffer 1, blended over the video rect on screen
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);
        updateVideoVertices(videoRectLeft, videoRectTop, videoRectWidth, videoRectHeight, surfaceWidth,
                surfaceHeight);

        GLES20.glUseProgram(edgeProgram);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, framebufferTextures[1]);
        GLES20.glUniform1i(edgeTexture, 0);
        GLES20.glUniform2f(edgeTexelSize, 1.0f / videoRectWidth, 1.0f / videoRectHeight);
        GLES20.glUniform1f(edgeThreshold, threshold);
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
