package de.daubli.feedwatch.uvc;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import de.daubli.feedwatch.StreamVideoActivity;
import de.daubli.feedwatch.StreamVideoRunner;
import de.daubli.feedwatch.databinding.StreamVideoActivityBinding;
import de.daubli.feedwatch.decoder.MJpegBitmapBuilder;
import de.daubli.feedwatch.ndi.FourCCType;
import de.daubli.feedwatch.view.base.OpenGLVideoView;
import de.daubli.feedwatch.view.overlays.framehelper.FramingHelperOverlayView;

public class StreamUvcVideoRunner extends Thread implements StreamVideoRunner {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final OpenGLVideoView openGLVideoView;

    private final StreamVideoActivity activity;

    private final UvcCaptureManager captureManager;

    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();

    private final AtomicBoolean decodeInProgress = new AtomicBoolean(false);

    private final AtomicReference<Frame> latestFrame = new AtomicReference<>();

    private final AtomicReference<byte[]> latestCompressedFrame = new AtomicReference<>();

    private final FramingHelperOverlayView framingHelperOverlayView;

    private volatile boolean running = true;

    private static final class Frame {

        final ByteBuffer rgba;

        final int width;

        final int height;

        Frame(ByteBuffer rgba, int width, int height) {
            this.rgba = rgba;
            this.width = width;
            this.height = height;
        }
    }

    public StreamUvcVideoRunner(UVCSource uvcSource, StreamVideoActivityBinding binding, StreamVideoActivity activity) {
        this.captureManager = new UvcCaptureManager(uvcSource, activity);
        this.openGLVideoView = binding.openGLVideoView;
        this.framingHelperOverlayView = binding.framingHelperOverlayView;
        this.activity = activity;
    }

    @Override
    public void run() {
        captureManager.start(data -> {
            if (!decodeInProgress.compareAndSet(false, true)) {
                latestCompressedFrame.set(data.clone());
                return;
            }

            // Copy MJPEG bytes because capture buffer might be reused by the producer
            byte[] mjpeg = data.clone();

            decodeExecutor.execute(() -> {
                try {
                    // Decode MJPEG -> Bitmap
                    Bitmap bmp = MJpegBitmapBuilder.builder().withRawData(ByteBuffer.wrap(mjpeg)).build();

                    if (bmp == null) {
                        return;
                    }

                    int w = bmp.getWidth();
                    int h = bmp.getHeight();

                    ByteBuffer rgba = bitmapToRgbaBuffer(bmp);
                    bmp.recycle();

                    latestFrame.set(new Frame(rgba, w, h));

                } finally {
                    decodeInProgress.set(false);
                }
            });
        });

        mainHandler.post(renderRunnable);
    }

    private final Runnable renderRunnable = new Runnable() {

        @Override
        public void run() {
            if (!running) {
                return;
            }

            Frame f = latestFrame.getAndSet(null);
            if (f != null) {
                openGLVideoView.setVisibility(View.VISIBLE);

                // Push to GL thread via OpenGLVideoView queueEvent()
                openGLVideoView.updateFrame(f.rgba, f.width, f.height, FourCCType.RGBA);
                framingHelperOverlayView.setFramingRect(openGLVideoView.getVideoRect());
            }

            openGLVideoView.postOnAnimation(this);
        }
    };

    private static ByteBuffer bitmapToRgbaBuffer(Bitmap bmp) {
        Bitmap argb = bmp.getConfig() == Bitmap.Config.ARGB_8888 ? bmp : bmp.copy(Bitmap.Config.ARGB_8888, false);

        ByteBuffer buf = ByteBuffer.allocateDirect(argb.getByteCount());
        buf.rewind();
        argb.copyPixelsToBuffer(buf);
        buf.rewind();

        if (argb != bmp) {
            argb.recycle();
        }
        return buf;
    }

    @Override
    public void shutdown() {
        running = false;
        captureManager.stop();

        mainHandler.removeCallbacks(renderRunnable);
        decodeExecutor.shutdownNow();

        activity.finish();
    }
}
