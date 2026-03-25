package de.daubli.feedwatch.view.base;

import java.util.ArrayList;
import java.util.List;

import javax.microedition.khronos.opengles.GL10;

import android.graphics.Rect;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import de.daubli.feedwatch.view.overlays.Overlay;

public class CompositeGLRenderer implements GLSurfaceView.Renderer {

    private final GLVideoRenderer baseRenderer;

    private final List<Overlay> overlays = new ArrayList<>();

    private int fbo = 0;

    private int fboTexture = 0;

    private int fboWidth = 0;

    private int fboHeight = 0;

    public CompositeGLRenderer(GLVideoRenderer baseRenderer) {
        this.baseRenderer = baseRenderer;
    }

    public void addOverlay(Overlay overlay) {
        overlays.add(overlay);
    }

    public void removeOverlay(Overlay overlay) {
        overlays.remove(overlay);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
        baseRenderer.onSurfaceCreated(gl, config);
        FullscreenTextureRenderer.init();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        baseRenderer.onSurfaceChanged(gl, width, height);

        fboWidth = width;
        fboHeight = height;

        releaseFBO();
        initFBO(width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // 1) Render base video into FBO (writes into fboTexture)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, fboWidth, fboHeight);

        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        baseRenderer.onDrawFrame(gl);

        // 2) Present FBO texture to screen
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, fboWidth, fboHeight);

        // Make sure default FB is in a predictable state
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glDisable(GLES20.GL_STENCIL_TEST);
        GLES20.glDisable(GLES20.GL_DITHER);
        GLES20.glDisable(GLES20.GL_BLEND);

        FullscreenTextureRenderer.drawTexture(fboTexture);

        // 3) Draw overlays ON SCREEN, sampling fboTexture (no feedback loop)
        Rect videoRect = baseRenderer.getVideoRect();
        for (Overlay overlay : overlays) {
            if (!overlay.isEnabled()) {
                continue;
            }
            overlay.draw(fboTexture, videoRect.left, videoRect.top, videoRect.width(), videoRect.height(),
                    baseRenderer.surfaceWidth, baseRenderer.surfaceHeight);
        }
    }

    private void initFBO(int width, int height) {
        int[] texIds = new int[1];
        int[] fboIds = new int[1];

        // Texture
        GLES20.glGenTextures(1, texIds, 0);
        fboTexture = texIds[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        // FBO
        GLES20.glGenFramebuffers(1, fboIds, 0);
        fbo = fboIds[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D,
                fboTexture, 0);

        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            throw new RuntimeException("Framebuffer not complete: " + status);
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void releaseFBO() {
        if (fboTexture != 0) {
            GLES20.glDeleteTextures(1, new int[] { fboTexture }, 0);
            fboTexture = 0;
        }
        if (fbo != 0) {
            GLES20.glDeleteFramebuffers(1, new int[] { fbo }, 0);
            fbo = 0;
        }
    }
}
