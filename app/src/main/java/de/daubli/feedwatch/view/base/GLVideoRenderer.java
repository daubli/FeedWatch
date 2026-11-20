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
import android.util.Log;
import de.daubli.feedwatch.ndi.FourCCType;

public class GLVideoRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "GLVideoRenderer";

    /**
     * Told when the rect the video is drawn in changes: with the first frame, a new frame size or a new surface size.
     */
    public interface VideoRectListener {

        /** Called on the GL thread or the thread submitting frames. */
        void onVideoRectChanged(Rect videoRect);
    }

    // ------------------------------------------------------------
    // Constants / static data
    // ------------------------------------------------------------

    /** Interleaved? No: separate buffers (vec2 position + vec2 texcoord). */
    private static final int VERTEX_FLOATS = 8; // 4 vertices * 2 components (x,y)

    private static final int BYTES_PER_FLOAT = 4;

    /** (s,t) for TRIANGLE_STRIP in the same vertex order as positions. */
    private static final float[] TEXCOORDS = { 0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f };

    /** Wider frames are converted for overlays at a half or a quarter of their size, the cost of 1440p at most. */
    static final int MAX_FRAME_TEXTURE_WIDTH = 2560;

    /** The whole viewport, in the vertex order of TEXCOORDS: the first row of the frame at the top. */
    private static final float[] FULL_VERTICES = { -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f };

    // ------------------------------------------------------------
    // Synchronization / frame state (written by producer thread)
    // ------------------------------------------------------------

    private final Object lock = new Object();

    private ByteBuffer pendingFrame;

    private int frameWidth;

    private int frameHeight;

    // bytes from one row to the next, 0 for rows without padding
    private int frameLineStride;

    private FourCCType frameType;

    private boolean frameAvailable;

    private Runnable pendingConsumedCallback;

    // ------------------------------------------------------------
    // Surface state
    // ------------------------------------------------------------

    protected int surfaceWidth;

    protected int surfaceHeight;

    /** Rect in surface pixel coords where the video is actually drawn (for overlays). */
    private Rect videoRect = new Rect();

    private volatile VideoRectListener videoRectListener;

    // the frame in the texture, drawn again when a render is requested without a new frame (GL thread only)
    private FourCCType drawnType;

    private int drawnWidth;

    private int drawnHeight;

    // counts the uploads, so the frame texture is converted once a frame (GL thread only)
    private int uploadCount;

    // ------------------------------------------------------------
    // The drawn frame in RGB at its own size, for overlays that measure the video (GL thread only)
    // ------------------------------------------------------------

    private int frameFramebuffer;

    private int frameTexture;

    private int frameTextureWidth;

    private int frameTextureHeight;

    // the upload in frameTexture, -1 for none
    private int convertedUpload = -1;

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

    private int uyvyYuvToRgbLoc;

    private int uyvyMeanOfPairLoc;

    // ------------------------------------------------------------
    // CPU-side buffers
    // ------------------------------------------------------------

    private final FloatBuffer vertexBuffer;

    private final FloatBuffer texBuffer;

    private final FloatBuffer fullVertexBuffer;

    // ------------------------------------------------------------
    // Texture upload bookkeeping (to avoid reallocating storage)
    // ------------------------------------------------------------

    private int texW = -1;

    private int texH = -1;

    private FourCCType texType = null;

    // the rows of a padded frame without their padding, reused (GL thread only)
    private ByteBuffer packedFrame;

    public GLVideoRenderer() {
        vertexBuffer = ByteBuffer.allocateDirect(VERTEX_FLOATS * BYTES_PER_FLOAT).order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        texBuffer = ByteBuffer.allocateDirect(TEXCOORDS.length * BYTES_PER_FLOAT).order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        texBuffer.put(TEXCOORDS).position(0);

        fullVertexBuffer = ByteBuffer.allocateDirect(VERTEX_FLOATS * BYTES_PER_FLOAT).order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        fullVertexBuffer.put(FULL_VERTICES).position(0);
    }

    /** Thread-safe frame submission */
    public void render(ByteBuffer buffer, int width, int height, FourCCType type) {
        render(buffer, width, height, type, null);
    }

    /** onConsumed runs once the buffer was uploaded or replaced and may be reused */
    public void render(ByteBuffer buffer, int width, int height, FourCCType type, Runnable onConsumed) {
        render(buffer, width, height, 0, type, onConsumed);
    }

    /**
     * lineStride: bytes from the start of one row to the next, 0 for rows without padding. NDI pads the rows of
     * frames whose width is not a multiple of its alignment, e.g. 1080 pixels.
     */
    public void render(ByteBuffer buffer, int width, int height, int lineStride, FourCCType type,
            Runnable onConsumed) {
        synchronized (lock) {
            if (pendingConsumedCallback != null) {
                // previous frame was never uploaded
                pendingConsumedCallback.run();
            }
            pendingFrame = buffer;
            frameWidth = width;
            frameHeight = height;
            frameLineStride = lineStride;
            frameType = type;
            pendingConsumedCallback = onConsumed;
            frameAvailable = true;

            if (surfaceWidth > 0 && surfaceHeight > 0) {
                setVideoRect(updateVerticesAndGetVideoRect(surfaceWidth, surfaceHeight, frameWidth, frameHeight));
            }
        }
    }

    /** Drops the frame handed in but not uploaded yet; its onConsumed runs. The last drawn frame stays. */
    public void discardPendingFrame() {
        final Runnable consumedCallback;
        synchronized (lock) {
            consumedCallback = pendingConsumedCallback;
            pendingConsumedCallback = null;
            pendingFrame = null;
            frameAvailable = false;
        }
        if (consumedCallback != null) {
            consumedCallback.run();
        }
    }

    public Rect getVideoRect() {
        synchronized (lock) {
            return new Rect(videoRect);
        }
    }

    public void setVideoRectListener(VideoRectListener listener) {
        videoRectListener = listener;
    }

    // caller holds the lock
    private void setVideoRect(Rect rect) {
        if (rect.equals(videoRect)) {
            return;
        }
        videoRect = rect;
        VideoRectListener listener = videoRectListener;
        if (listener != null) {
            listener.onVideoRectChanged(new Rect(rect));
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // a new surface has a new, empty texture
        drawnType = null;
        texW = -1;
        texH = -1;
        texType = null;
        // the frame texture went with the old context
        frameFramebuffer = 0;
        frameTexture = 0;
        frameTextureWidth = 0;
        frameTextureHeight = 0;
        convertedUpload = -1;

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
        uyvyYuvToRgbLoc = GLES20.glGetUniformLocation(programUyvy, "uYuvToRgb");
        uyvyMeanOfPairLoc = GLES20.glGetUniformLocation(programUyvy, "uMeanOfPair");

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
                setVideoRect(updateVerticesAndGetVideoRect(surfaceWidth, surfaceHeight, frameWidth, frameHeight));
            }
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // Keep viewport consistent even if someone else changes it.
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight);

        final ByteBuffer frame;
        final int w, h, lineStride;
        final FourCCType type;
        final Runnable consumedCallback;

        // frame and its callback are taken together, the producer may replace and release a frame at any time
        synchronized (lock) {
            if (frameAvailable) {
                frame = pendingFrame;
                w = frameWidth;
                h = frameHeight;
                lineStride = frameLineStride;
                type = frameType;
                consumedCallback = pendingConsumedCallback;
                pendingConsumedCallback = null;
                frameAvailable = false;
            } else {
                frame = null;
                w = 0;
                h = 0;
                lineStride = 0;
                type = null;
                consumedCallback = null;
            }
        }

        if (frame == null) {
            // an overlay changed: the caller cleared the target, so the last frame has to be drawn again
            if (drawnType != null) {
                draw(drawnType, drawnWidth, drawnHeight, vertexBuffer, false);
            }
            return;
        }

        uploadFrame(frame, type, w, h, lineStride);
        uploadCount++;
        if (consumedCallback != null) {
            consumedCallback.run();
        }
        drawnType = type;
        drawnWidth = w;
        drawnHeight = h;
        draw(type, w, h, vertexBuffer, false);
    }

    /**
     * By how much a frame of this width is shrunk for overlays: 1 up to {@link #MAX_FRAME_TEXTURE_WIDTH}, 2 for 4K, 4
     * for 8K. Only powers of two: a pixel of the shrunk frame is then read between the rows of the frame, the linear
     * filter gives their mean, and UYVY takes the mean of a pixel pair, together the mean of 2 x 2 pixels for 4K.
     */
    static int frameTextureDivisor(int frameWidth) {
        int divisor = 1;
        while (frameWidth / divisor > MAX_FRAME_TEXTURE_WIDTH) {
            divisor *= 2;
        }
        return divisor;
    }

    /**
     * Converts the drawn frame to RGB at its own size, shrunk by {@link #frameTextureDivisor}, unless that frame is
     * converted already. It renders into a framebuffer of its own and leaves it bound, the caller binds its target
     * and viewport again. GL thread only.
     */
    public void updateFrameTexture() {
        if (drawnType == null || convertedUpload == uploadCount) {
            return;
        }
        int divisor = frameTextureDivisor(drawnWidth);
        int width = drawnWidth / divisor;
        int height = drawnHeight / divisor;
        if (width <= 0 || height <= 0 || !ensureFrameFramebuffer(width, height)) {
            return;
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameFramebuffer);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glDisable(GLES20.GL_BLEND);
        draw(drawnType, drawnWidth, drawnHeight, fullVertexBuffer, divisor > 1);
        convertedUpload = uploadCount;
    }

    /**
     * The frame converted by {@link #updateFrameTexture()}, 0 before the first. Its first row is at t = 1, like in a
     * framebuffer the video is drawn into. Filtered linearly. GL thread only.
     */
    public int getFrameTexture() {
        return convertedUpload >= 0 ? frameTexture : 0;
    }

    public int getFrameTextureWidth() {
        return frameTextureWidth;
    }

    public int getFrameTextureHeight() {
        return frameTextureHeight;
    }

    /** False if the GPU cannot render a frame of this size. */
    private boolean ensureFrameFramebuffer(int width, int height) {
        if (frameTexture != 0 && width == frameTextureWidth && height == frameTextureHeight) {
            return true;
        }
        if (frameTexture != 0) {
            GLES20.glDeleteFramebuffers(1, new int[] { frameFramebuffer }, 0);
            GLES20.glDeleteTextures(1, new int[] { frameTexture }, 0);
            frameFramebuffer = 0;
            frameTexture = 0;
            frameTextureWidth = 0;
            frameTextureHeight = 0;
            convertedUpload = -1;
        }

        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        int texture = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glGenFramebuffers(1, ids, 0);
        int framebuffer = ids[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D,
                texture, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "No frame texture of " + width + "x" + height + ", framebuffer status " + status);
            GLES20.glDeleteFramebuffers(1, new int[] { framebuffer }, 0);
            GLES20.glDeleteTextures(1, new int[] { texture }, 0);
            return false;
        }

        frameFramebuffer = framebuffer;
        frameTexture = texture;
        frameTextureWidth = width;
        frameTextureHeight = height;
        return true;
    }

    private void uploadFrame(ByteBuffer frame, FourCCType type, int w, int h, int lineStride) {
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

        ByteBuffer pixels = frame;
        int rowBytes = texW * 4;
        if (lineStride > rowBytes) {
            // GLES 2 cannot skip the padding at the end of each row, so the rows are packed first
            if (packedFrame == null || packedFrame.capacity() < rowBytes * texH) {
                packedFrame = ByteBuffer.allocateDirect(rowBytes * texH);
            }
            packRows(frame, rowBytes, lineStride, texH, packedFrame);
            pixels = packedFrame;
        } else {
            frame.rewind(); // ensure position=0 for upload
        }
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, texW, texH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                pixels);
    }

    /**
     * Copies {@code rows} rows of {@code rowBytes} each, which start {@code lineStride} bytes apart in
     * {@code source}, into {@code target} without the padding between them. Leaves {@code target} ready to be read
     * from its start, {@code source} as it was.
     */
    static void packRows(ByteBuffer source, int rowBytes, int lineStride, int rows, ByteBuffer target) {
        ByteBuffer row = source.duplicate();
        target.clear();
        for (int y = 0; y < rows; y++) {
            row.limit(y * lineStride + rowBytes).position(y * lineStride);
            target.put(row);
        }
        target.flip();
    }

    /** meanOfPair: for a frame drawn at half its width or less, UYVY takes the mean luma of a pixel pair */
    private void draw(FourCCType type, int frameW, int frameH, FloatBuffer vertices, boolean meanOfPair) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Bind texture unit 0 (samplers set to 0). Binding is cheap; do it explicitly.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);

        switch (type) {
            case RGBA:
                GLES20.glUseProgram(programRgba);
                setCommonAttribs(posLocRgba, texLocRgba, vertices);
                GLES20.glUniform1i(rgbaTexSamplerLoc, 0);
                break;

            case BGRA:
                GLES20.glUseProgram(programBgra);
                setCommonAttribs(posLocBgra, texLocBgra, vertices);
                GLES20.glUniform1i(bgraTexSamplerLoc, 0);
                break;

            case UYVY:
            default:
                GLES20.glUseProgram(programUyvy);
                setCommonAttribs(posLocUyvy, texLocUyvy, vertices);
                GLES20.glUniform1i(uyvyTexSamplerLoc, 0);
                GLES20.glUniform1f(uyvyFrameWidthLoc, (float) frameW);
                GLES20.glUniformMatrix3fv(uyvyYuvToRgbLoc, 1, false, yuvToRgbMatrix(frameW, frameH), 0);
                GLES20.glUniform1f(uyvyMeanOfPairLoc, meanOfPair ? 1f : 0f);
                break;
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void setCommonAttribs(int posLoc, int texLoc, FloatBuffer vertices) {
        GLES20.glEnableVertexAttribArray(posLoc);
        GLES20.glEnableVertexAttribArray(texLoc);

        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vertices);
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
