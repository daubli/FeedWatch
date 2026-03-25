package de.daubli.feedwatch.view.overlays.zebra;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import android.opengl.GLES20;
import de.daubli.feedwatch.view.overlays.Overlay;

public class ZebraOverlay extends Overlay {

    private static final float DEFAULT_THRESHOLD_IRE = 95f;

    private static final float Y8_BLACK_LIMITED = 16f;

    private static final float Y8_WHITE_LIMITED = 235f;

    private static final float Y8_RANGE_LIMITED = (Y8_WHITE_LIMITED - Y8_BLACK_LIMITED); // 219

    private final FloatBuffer vertexBuffer;

    private final FloatBuffer texCoordBuffer;

    private final float[] dynTex = new float[8];

    private final float[] dynVertices = new float[8];

    private boolean initialized = false;

    private int program = 0;

    private int aPosition = -1;

    private int aTexCoord = -1;

    private int uTexture = -1;

    private int uThreshold = -1;

    private int uStripePx = -1;

    private int uAlphaWhite = -1;

    private int uAlphaBlack = -1;

    //Settings

    // 8 bit range mode
    private boolean limitedRange = true;

    // normalized 0..1 threshold actually used in shader
    private float thresholdYPrime = 0f;

    // stripe width in screen pixels
    private float stripePx = 6.0f;

    // stripe alphas
    private float alphaWhite = 0.35f;

    private float alphaBlack = 0.65f;

    public ZebraOverlay() {
        vertexBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        texCoordBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        setThresholdIre(DEFAULT_THRESHOLD_IRE); // sets thresholdYPrime using current range mode
    }

    /** Set whether IRE mapping uses limited-range (16..235) or full-range (0..255). */
    public void setLimitedRange(boolean limitedRange) {
        this.limitedRange = limitedRange;
        // Re-apply default threshold semantics to keep the zebra consistent
        setThresholdIre(DEFAULT_THRESHOLD_IRE);
    }

    /**
     * Set threshold in IRE (0..100).
     *
     * LIMITED range (studio swing, typical video):
     *   code = 16 + (IRE/100) * 219
     *   thresholdYPrime = code / 255
     *
     * FULL range:
     *   thresholdYPrime = IRE/100
     */
    public void setThresholdIre(float ire) {
        float clamped = Math.max(0f, Math.min(100f, ire));
        if (limitedRange) {
            float code = Y8_BLACK_LIMITED + (clamped / 100f) * Y8_RANGE_LIMITED; // 16..235
            this.thresholdYPrime = code / 255f;
        } else {
            this.thresholdYPrime = clamped / 100f;
        }
    }

    private void initGL() {
        if (initialized) {
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

        final String fragmentShader =
                "precision mediump float;" +
                        "uniform sampler2D uTexture;" +
                        "uniform float uThreshold;" +
                        "uniform float uStripePx;" +
                        "uniform float uAlphaWhite;" +
                        "uniform float uAlphaBlack;" +
                        "varying vec2 vTexCoord;" +
                        "void main() {" +
                        "  vec3 rgb = texture2D(uTexture, vTexCoord).rgb;" +
                        // Y' approx from RGB; assumes RGB is already in display/video encoding (gamma-ish).
                        "  float yprime = dot(rgb, vec3(0.299, 0.587, 0.114));" +
                        "  if (yprime < uThreshold) {" +
                        "    gl_FragColor = vec4(0.0);" +
                        "    return;" +
                        "  }" +
                        "  float w = max(uStripePx, 1.0);" +
                        "  float t = (gl_FragCoord.x + gl_FragCoord.y) / w;" +
                        "  float stripe = mod(floor(t), 2.0);" + // 0 or 1
                        "  float a = mix(uAlphaBlack, uAlphaWhite, stripe);" +
                        "  gl_FragColor = vec4(vec3(stripe), a);" +
                        "}";
        //@formatter:on

        program = createProgram(vertexShader, fragmentShader);

        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");

        uTexture = GLES20.glGetUniformLocation(program, "uTexture");
        uThreshold = GLES20.glGetUniformLocation(program, "uThreshold");
        uStripePx = GLES20.glGetUniformLocation(program, "uStripePx");
        uAlphaWhite = GLES20.glGetUniformLocation(program, "uAlphaWhite");
        uAlphaBlack = GLES20.glGetUniformLocation(program, "uAlphaBlack");

        if (aPosition < 0 || aTexCoord < 0 || uTexture < 0 || uThreshold < 0 || uStripePx < 0 || uAlphaWhite < 0
                || uAlphaBlack < 0) {
            throw new RuntimeException("Failed to get shader locations (attrib/uniform missing/optimized out).");
        }

        initialized = true;
    }

    @Override
    public void draw(int videoTextureId, int videoRectLeft, int videoRectTop, int videoRectWidth, int videoRectHeight,
            int surfaceWidth, int surfaceHeight) {

        initGL();
        GLES20.glUseProgram(program);

        GLES20.glUniform1f(uThreshold, thresholdYPrime);
        GLES20.glUniform1f(uStripePx, stripePx);
        GLES20.glUniform1f(uAlphaWhite, alphaWhite);
        GLES20.glUniform1f(uAlphaBlack, alphaBlack);

        // Compute NDC quad for the video rect
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

        // UV crop into the surface-sized FBO texture for the same rect
        float u0 = (float) videoRectLeft / (float) surfaceWidth;
        float u1 = (float) (videoRectLeft + videoRectWidth) / (float) surfaceWidth;

        float v0 = (float) videoRectTop / (float) surfaceHeight;
        float v1 = (float) (videoRectTop + videoRectHeight) / (float) surfaceHeight;

        // Flip vertically because FBO texture is flipped compared to video frame
        float tmp = v0;
        v0 = v1;
        v1 = tmp;

        dynTex[0] = u0;
        dynTex[1] = v0;
        dynTex[2] = u0;
        dynTex[3] = v1;
        dynTex[4] = u1;
        dynTex[5] = v0;
        dynTex[6] = u1;
        dynTex[7] = v1;

        texCoordBuffer.clear();
        texCoordBuffer.put(dynTex).position(0);

        // Blend overlay
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        // Attributes
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aPosition);

        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);

        // Texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, videoTextureId);
        GLES20.glUniform1i(uTexture, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
        GLES20.glDisable(GLES20.GL_BLEND);
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

        // Shaders can be deleted after linking
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);

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
