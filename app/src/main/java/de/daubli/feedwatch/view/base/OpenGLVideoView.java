package de.daubli.feedwatch.view.base;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.daubli.feedwatch.ndi.FourCCType;
import de.daubli.feedwatch.view.overlays.focusassist.FocusAssistOverlay;
import de.daubli.feedwatch.view.overlays.scope.ScopeCapture;
import de.daubli.feedwatch.view.overlays.zebra.ZebraOverlay;

public class OpenGLVideoView extends GLSurfaceView {

    private GLVideoRenderer videoRenderer;

    private FocusAssistOverlay focusAssistOverlay;

    private ZebraOverlay zebraOverlay;

    private ScopeCapture scopeCapture;

    public OpenGLVideoView(@NonNull Context context) {
        super(context);
        init();
    }

    public OpenGLVideoView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setEGLContextClientVersion(2);

        videoRenderer = new GLVideoRenderer();
        CompositeGLRenderer compositeRenderer = new CompositeGLRenderer(videoRenderer);

        focusAssistOverlay = new FocusAssistOverlay();
        compositeRenderer.addOverlay(focusAssistOverlay);

        zebraOverlay = new ZebraOverlay();
        compositeRenderer.addOverlay(zebraOverlay);

        scopeCapture = new ScopeCapture();
        compositeRenderer.addOverlay(scopeCapture);

        setRenderer(compositeRenderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        setZOrderOnTop(false);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
    }

    public void updateFrame(final ByteBuffer buffer, final int frameWidth, final int frameHeight,
            final FourCCType fourCCType) {
        updateFrame(buffer, frameWidth, frameHeight, fourCCType, null);
    }

    public void updateFrame(final ByteBuffer buffer, final int frameWidth, final int frameHeight,
            final FourCCType fourCCType, final Runnable onConsumed) {
        if (videoRenderer == null || buffer == null) {
            if (onConsumed != null) {
                onConsumed.run();
            }
            return;
        }

        queueEvent(() -> videoRenderer.render(buffer, frameWidth, frameHeight, fourCCType, onConsumed));
        requestRender();
    }

    /**
     * Drops a frame that was handed in but not drawn yet, running its onConsumed. Done on the GL thread, so no
     * upload of it is in progress afterwards. Blocks, not to be called on the main thread.
     *
     * @return false if the GL thread did not get to it in time, e.g. because it already ended
     */
    public boolean discardPendingFrame(long timeoutMillis) throws InterruptedException {
        CountDownLatch discarded = new CountDownLatch(1);
        queueEvent(() -> {
            videoRenderer.discardPendingFrame();
            discarded.countDown();
        });
        return discarded.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public Rect getVideoRect() {
        return videoRenderer.getVideoRect();
    }

    /**
     * Listens for changes of the rect the video is drawn in, on the main thread. Overlays drawn over the video
     * follow it this way, also when no new frame arrives, e.g. for a still image or after the surface was resized.
     */
    public void setOnVideoRectChangedListener(@Nullable GLVideoRenderer.VideoRectListener listener) {
        videoRenderer.setVideoRectListener(listener == null ? null
                : videoRect -> post(() -> listener.onVideoRectChanged(videoRect)));
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    public void setFocusAssistEnabled(boolean enabled) {
        if (focusAssistOverlay == null) {
            return;
        }

        queueEvent(() -> focusAssistOverlay.setEnabled(enabled));
        requestRender();
    }

    public void setFocusPeakingColor(int color) {
        if (focusAssistOverlay == null) {
            return;
        }

        queueEvent(() -> focusAssistOverlay.setEdgeColor(color));
        requestRender();
    }

    public void setZebraThreshold(int percent) {
        if (zebraOverlay == null) {
            return;
        }

        queueEvent(() -> zebraOverlay.setThresholdIre(percent));
        requestRender();
    }

    public void setZebraOverlayEnabled(boolean enabled) {
        if (zebraOverlay == null) {
            return;
        }

        queueEvent(() -> zebraOverlay.setEnabled(enabled));
        requestRender();
    }

    /**
     * Adds or removes a scope, it gets the scaled down picture on the GL thread. The capture runs while any scope
     * listens.
     */
    public void setScopeListening(ScopeCapture.Listener listener, boolean listening) {
        if (scopeCapture == null) {
            return;
        }

        queueEvent(() -> scopeCapture.setEnabled(scopeCapture.setListening(listener, listening)));
        // a still picture gets its scope right away
        requestRender();
    }
}
