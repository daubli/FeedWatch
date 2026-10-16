package de.daubli.feedwatch.view.base;

import java.nio.ByteBuffer;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.daubli.feedwatch.ndi.FourCCType;
import de.daubli.feedwatch.view.overlays.focusassist.FocusAssistOverlay;
import de.daubli.feedwatch.view.overlays.waveform.WaveformCapture;
import de.daubli.feedwatch.view.overlays.zebra.ZebraOverlay;

public class OpenGLVideoView extends GLSurfaceView {

    private GLVideoRenderer videoRenderer;

    private FocusAssistOverlay focusAssistOverlay;

    private ZebraOverlay zebraOverlay;

    private WaveformCapture waveformCapture;

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

        waveformCapture = new WaveformCapture();
        compositeRenderer.addOverlay(waveformCapture);

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

    public Rect getVideoRect() {
        return videoRenderer.getVideoRect();
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
     * Turns the waveform capture on or off, the listener gets the scaled down picture on the GL thread.
     */
    public void setWaveformListener(@Nullable WaveformCapture.Listener listener) {
        if (waveformCapture == null) {
            return;
        }

        queueEvent(() -> {
            waveformCapture.setListener(listener);
            waveformCapture.setEnabled(listener != null);
        });
        // a still picture gets a waveform right away
        requestRender();
    }
}
