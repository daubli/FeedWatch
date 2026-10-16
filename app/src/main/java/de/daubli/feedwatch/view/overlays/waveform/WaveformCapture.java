package de.daubli.feedwatch.view.overlays.waveform;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import android.opengl.GLES20;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import de.daubli.feedwatch.view.overlays.Overlay;

/**
 * Draws nothing on screen: scales the video down into a small framebuffer and reads it back, so the waveform can be
 * computed on the CPU. Samples the composite texture before the other overlays, so zebra stripes and peaking edges
 * do not show up in the waveform.
 */
public class WaveformCapture extends Overlay {

    /**
     * Receives the scaled down picture on the GL thread. The buffer is reused for the next capture.
     */
    public interface Listener {

        void onWaveformSamples(ByteBuffer rgba, int width, int height);
    }

    // one column of the waveform per sample column
    public static final int SAMPLE_WIDTH = 360;

    public static final int SAMPLE_HEIGHT = 180;

    // a waveform is read, not watched frame by frame, so 30 updates per second are plenty
    private static final long MIN_CAPTURE_INTERVAL_MS = 33;

    private static final float[] VERTICES = { -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f };

    private final FloatBuffer vertexBuffer;

    private final FloatBuffer texCoordBuffer;

    private final float[] texCoords = new float[8];

    private final ByteBuffer pixels;

    @Nullable
    private volatile Listener listener;

    private long lastCapture;

    private boolean initialized = false;

    private int program = 0;

    private int aPosition = -1;

    private int aTexCoord = -1;

    private int uTexture = -1;

    private int fbo = 0;

    private int fboTexture = 0;

    public WaveformCapture() {
        vertexBuffer = ByteBuffer.allocateDirect(VERTICES.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(VERTICES).position(0);
        texCoordBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        pixels = ByteBuffer.allocateDirect(SAMPLE_WIDTH * SAMPLE_HEIGHT * 4).order(ByteOrder.nativeOrder());
    }

    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    private void initGL() {
        // a new EGL context drops the old program and framebuffer
        if (initialized && GLES20.glIsProgram(program)) {
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
                        "varying vec2 vTexCoord;" +
                        "void main() {" +
                        "  gl_FragColor = vec4(texture2D(uTexture, vTexCoord).rgb, 1.0);" +
                        "}";
        //@formatter:on

        program = createProgram(vertexShader, fragmentShader);
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        uTexture = GLES20.glGetUniformLocation(program, "uTexture");

        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        fboTexture = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, SAMPLE_WIDTH, SAMPLE_HEIGHT, 0, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        GLES20.glGenFramebuffers(1, ids, 0);
        fbo = ids[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D,
                fboTexture, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Waveform framebuffer not complete: " + status);
        }

        initialized = true;
    }

    @Override
    public void draw(int videoTextureId, int videoRectLeft, int videoRectTop, int videoRectWidth, int videoRectHeight,
            int surfaceWidth, int surfaceHeight) {
        Listener target = listener;
        if (target == null || videoRectWidth <= 0 || videoRectHeight <= 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastCapture < MIN_CAPTURE_INTERVAL_MS) {
            return;
        }
        lastCapture = now;

        initGL();

        // the composite texture is surface sized with its origin at the bottom, the video sits in videoRect
        float u0 = (float) videoRectLeft / surfaceWidth;
        float u1 = (float) (videoRectLeft + videoRectWidth) / surfaceWidth;
        float vBottom = 1f - (float) (videoRectTop + videoRectHeight) / surfaceHeight;
        float vTop = 1f - (float) videoRectTop / surfaceHeight;
        texCoords[0] = u0;
        texCoords[1] = vBottom;
        texCoords[2] = u1;
        texCoords[3] = vBottom;
        texCoords[4] = u0;
        texCoords[5] = vTop;
        texCoords[6] = u1;
        texCoords[7] = vTop;
        texCoordBuffer.clear();
        texCoordBuffer.put(texCoords).position(0);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT);
        GLES20.glDisable(GLES20.GL_BLEND);

        GLES20.glUseProgram(program);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, videoTextureId);
        GLES20.glUniform1i(uTexture, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);

        pixels.clear();
        GLES20.glReadPixels(0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);

        // back to the screen for the overlays drawn after this one
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);

        pixels.rewind();
        target.onWaveformSamples(pixels, SAMPLE_WIDTH, SAMPLE_HEIGHT);
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
