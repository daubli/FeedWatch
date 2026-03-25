package de.daubli.feedwatch.view.base;

import static de.daubli.feedwatch.view.base.OpenGLVideoShader.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import android.graphics.Rect;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import de.daubli.feedwatch.ndi.FourCCType;

public class GLVideoRenderer implements GLSurfaceView.Renderer {

    // ------------------------------------------------------------
    // Constants / static data
    // ------------------------------------------------------------

    /** Interleaved? No: separate buffers (vec2 position + vec2 texcoord). */
    private static final int VERTEX_FLOATS = 8; // 4 vertices * 2 components (x,y)

    private static final int BYTES_PER_FLOAT = 4;

    /** (s,t) for TRIANGLE_STRIP in the same vertex order as positions. */
    private static final float[] TEXCOORDS = { 0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f };

    // ------------------------------------------------------------
    // Synchronization / frame state (written by producer thread)
    // ------------------------------------------------------------

    private final Object lock = new Object();

    private ByteBuffer pendingFrame;

    private int frameWidth;

    private int frameHeight;

    private FourCCType frameType;

    private boolean frameAvailable;

    // ------------------------------------------------------------
    // Surface state
    // ------------------------------------------------------------

    protected int surfaceWidth;

    protected int surfaceHeight;

    /** Rect in surface pixel coords where the video is actually drawn (for overlays). */
    private Rect videoRect = new Rect();

    // ------------------------------------------------------------
    // GL resources
    // ------------------------------------------------------------

    // Programs
    private int programRgba;

    private int programBgra;

    private int programUyvy;

    // Single texture used for all formats
    protected int textureId;

    // Attributes (shared names across programs; but locations can differ per program)
    private int posLocRgba, texLocRgba;

    private int posLocBgra, texLocBgra;

    private int posLocUyvy, texLocUyvy;

    // Uniforms
    private int rgbaTexSamplerLoc;

    private int bgraTexSamplerLoc;

    private int uyvyTexSamplerLoc;

    private int uyvyFrameWidthLoc;

    // ------------------------------------------------------------
    // CPU-side buffers
    // ------------------------------------------------------------

    private final FloatBuffer vertexBuffer;

    private final FloatBuffer texBuffer;

    // ------------------------------------------------------------
    // Texture upload bookkeeping (to avoid reallocating storage)
    // ------------------------------------------------------------

    private int texW = -1;

    private int texH = -1;

    private FourCCType texType = null;

    public GLVideoRenderer() {
        vertexBuffer = ByteBuffer.allocateDirect(VERTEX_FLOATS * BYTES_PER_FLOAT).order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        texBuffer = ByteBuffer.allocateDirect(TEXCOORDS.length * BYTES_PER_FLOAT).order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        texBuffer.put(TEXCOORDS).position(0);
    }

    /** Thread-safe frame submission */
    public void render(ByteBuffer buffer, int width, int height, FourCCType type) {
        synchronized (lock) {
            pendingFrame = buffer;
            frameWidth = width;
            frameHeight = height;
            frameType = type;
            frameAvailable = true;

            if (surfaceWidth > 0 && surfaceHeight > 0) {
                videoRect = updateVerticesAndGetVideoRect(surfaceWidth, surfaceHeight, frameWidth, frameHeight);
            }
        }
    }

    public Rect getVideoRect() {
        synchronized (lock) {
            return new Rect(videoRect);
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // Blending (leave enabled only if you actually render alpha / overlays; otherwise can be disabled)
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        programRgba = createProgram(VERTEX_SHADER, FRAGMENT_RGBA);
        programBgra = createProgram(VERTEX_SHADER, FRAGMENT_BGRA);
        programUyvy = createProgram(VERTEX_SHADER, FRAGMENT_UYVY);

        // RGBA program
        posLocRgba = GLES20.glGetAttribLocation(programRgba, "aPosition");
        texLocRgba = GLES20.glGetAttribLocation(programRgba, "aTexCoord");
        rgbaTexSamplerLoc = GLES20.glGetUniformLocation(programRgba, "uTex");

        // BGRA program
        posLocBgra = GLES20.glGetAttribLocation(programBgra, "aPosition");
        texLocBgra = GLES20.glGetAttribLocation(programBgra, "aTexCoord");
        bgraTexSamplerLoc = GLES20.glGetUniformLocation(programBgra, "uTex");

        // UYVY program
        posLocUyvy = GLES20.glGetAttribLocation(programUyvy, "aPosition");
        texLocUyvy = GLES20.glGetAttribLocation(programUyvy, "aTexCoord");
        uyvyTexSamplerLoc = GLES20.glGetUniformLocation(programUyvy, "uTex");
        uyvyFrameWidthLoc = GLES20.glGetUniformLocation(programUyvy, "uFrameWidth");

        // Texture setup
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        textureId = tex[0];

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        surfaceWidth = width;
        surfaceHeight = height;
        GLES20.glViewport(0, 0, width, height);

        synchronized (lock) {
            if (frameWidth > 0 && frameHeight > 0) {
                videoRect = updateVerticesAndGetVideoRect(surfaceWidth, surfaceHeight, frameWidth, frameHeight);
            }
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // Keep viewport consistent even if someone else changes it.
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);

        final ByteBuffer frame;
        final int w, h;
        final FourCCType type;

        synchronized (lock) {
            if (!frameAvailable) {
                return;
            }
            frame = pendingFrame;
            w = frameWidth;
            h = frameHeight;
            type = frameType;
            frameAvailable = false;
        }

        uploadFrame(frame, type, w, h);
        draw(type, w);
    }

    private void uploadFrame(ByteBuffer frame, FourCCType type, int w, int h) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);

        // For UYVY: we upload as RGBA where each RGBA pixel packs 2 original pixels (UYVY = 4 bytes per 2 pixels)
        final int uploadW = (type == FourCCType.UYVY) ? (w / 2) : w;
        final int uploadH = h;

        // (Re)allocate texture storage only when needed
        if (uploadW != texW || uploadH != texH || type != texType) {
            texW = uploadW;
            texH = uploadH;
            texType = type;

            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, texW, texH, 0, GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE, null);
        }

        frame.rewind(); // ensure position=0 for upload
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, texW, texH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                frame);
    }

    private void draw(FourCCType type, int frameW) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Bind texture unit 0 (samplers set to 0). Binding is cheap; do it explicitly.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);

        switch (type) {
            case RGBA:
                GLES20.glUseProgram(programRgba);
                setCommonAttribs(posLocRgba, texLocRgba);
                GLES20.glUniform1i(rgbaTexSamplerLoc, 0);
                break;

            case BGRA:
                GLES20.glUseProgram(programBgra);
                setCommonAttribs(posLocBgra, texLocBgra);
                GLES20.glUniform1i(bgraTexSamplerLoc, 0);
                break;

            case UYVY:
            default:
                GLES20.glUseProgram(programUyvy);
                setCommonAttribs(posLocUyvy, texLocUyvy);
                GLES20.glUniform1i(uyvyTexSamplerLoc, 0);
                GLES20.glUniform1f(uyvyFrameWidthLoc, (float) frameW);
                break;
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void setCommonAttribs(int posLoc, int texLoc) {
        GLES20.glEnableVertexAttribArray(posLoc);
        GLES20.glEnableVertexAttribArray(texLoc);

        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, texBuffer);
    }

    /**
     * Updates the vertexBuffer to preserve aspect ratio and returns the resulting video rect in surface coords.
     */
    private Rect updateVerticesAndGetVideoRect(int surfaceW, int surfaceH, int frameW, int frameH) {
        final float viewAspect = (float) surfaceW / (float) surfaceH;
        final float frameAspect = (float) frameW / (float) frameH;

        float scaleX = 1f;
        float scaleY = 1f;

        if (frameAspect > viewAspect) {
            // letterbox (black bars top/bottom)
            scaleY = viewAspect / frameAspect;
        } else {
            // pillarbox (black bars left/right)
            scaleX = frameAspect / viewAspect;
        }

        // TRIANGLE_STRIP order must match TEXCOORDS
        final float[] vertices = { -scaleX, -scaleY, scaleX, -scaleY, -scaleX, scaleY, scaleX, scaleY };

        vertexBuffer.clear();
        vertexBuffer.put(vertices).position(0);

        // return rect where the video appears in surface pixels
        final int videoW = (int) (surfaceW * scaleX);
        final int videoH = (int) (surfaceH * scaleY);
        final int left = (surfaceW - videoW) / 2;
        final int top = (surfaceH - videoH) / 2;
        return new Rect(left, top, left + videoW, top + videoH);
    }

    private int createProgram(String vs, String fs) {
        final int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        final int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);

        final int p = GLES20.glCreateProgram();
        if (p == 0) {
            throw new RuntimeException("Could not create program");
        }

        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);

        final int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            final String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException("Could not link program: " + log);
        }

        // Optional: shaders can be deleted after linking; program keeps the compiled binaries.
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);

        return p;
    }

    private int compile(int type, String src) {
        final int shader = GLES20.glCreateShader(type);
        if (shader == 0) {
            throw new RuntimeException("Could not create shader " + type);
        }

        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);

        final int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            final String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Could not compile shader " + type + ": " + log);
        }
        return shader;
    }
}
