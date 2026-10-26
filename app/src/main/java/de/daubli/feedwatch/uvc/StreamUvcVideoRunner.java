package de.daubli.feedwatch.uvc;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import de.daubli.feedwatch.R;
import de.daubli.feedwatch.StreamVideoActivity;
import de.daubli.feedwatch.StreamVideoRunner;
import de.daubli.feedwatch.databinding.StreamVideoActivityBinding;
import de.daubli.feedwatch.decoder.MJpegBitmapBuilder;
import de.daubli.feedwatch.ndi.FourCCType;
import de.daubli.feedwatch.stats.RateMeter;
import de.daubli.feedwatch.stats.StreamStats;
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

    private long frameRateWindowStart;

    private int frameRateWindowCount;

    private float measuredFrameRate;

    // statistics: the counters are written by the USB and decode threads, the rest only on the main thread
    private final long startedAtMillis = SystemClock.elapsedRealtime();

    private final AtomicLong usbFrames = new AtomicLong();

    private final AtomicLong usbBytes = new AtomicLong();

    // frames that were not decoded or not shown because a newer one replaced them
    private final AtomicLong droppedFrames = new AtomicLong();

    // average decode time, -1 before the first frame
    private volatile float decodeMillis = -1;

    private long shownFrames;

    private final RateMeter usbFrameRate = new RateMeter();

    private final RateMeter usbDataRate = new RateMeter();

    private final RateMeter shownRate = new RateMeter();

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
        openGLVideoView.setOnVideoRectChangedListener(framingHelperOverlayView::setFramingRect);
    }

    @Override
    public void run() {
        captureManager.start(data -> {
            usbFrames.incrementAndGet();
            usbBytes.addAndGet(data.length);
            if (!decodeInProgress.compareAndSet(false, true)) {
                droppedFrames.incrementAndGet();
                latestCompressedFrame.set(data.clone());
                return;
            }

            // Copy MJPEG bytes because capture buffer might be reused by the producer
            byte[] mjpeg = data.clone();

            decodeExecutor.execute(() -> {
                try {
                    long decodeStart = SystemClock.elapsedRealtimeNanos();
                    // Decode MJPEG -> Bitmap
                    Bitmap bmp = MJpegBitmapBuilder.builder().withRawData(ByteBuffer.wrap(mjpeg)).build();

                    if (bmp == null) {
                        return;
                    }

                    int w = bmp.getWidth();
                    int h = bmp.getHeight();

                    ByteBuffer rgba = bitmapToRgbaBuffer(bmp);
                    bmp.recycle();

                    if (latestFrame.getAndSet(new Frame(rgba, w, h)) != null) {
                        droppedFrames.incrementAndGet();
                    }
                    updateDecodeTime((SystemClock.elapsedRealtimeNanos() - decodeStart) / 1e6f);

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
                shownFrames++;
                activity.onVideoFormat(f.width, f.height, measureFrameRate(), "MJPEG");
            }

            openGLVideoView.postOnAnimation(this);
        }
    };

    // only the decode thread writes it
    private void updateDecodeTime(float millis) {
        float average = decodeMillis;
        decodeMillis = average < 0 ? millis : average + (millis - average) / 16;
    }

    @Override
    public void collectStats(StreamStats stats) {
        stats.add(R.string.stats_duration, StreamStats.duration(SystemClock.elapsedRealtime() - startedAtMillis));
        stats.add(R.string.stats_received, StreamStats.frameRate(usbFrameRate.update(usbFrames.get())));
        stats.add(R.string.stats_shown, StreamStats.frameRate(shownRate.update(shownFrames)));
        stats.add(R.string.stats_data_rate, StreamStats.dataRate(usbDataRate.update(usbBytes.get())));
        long frames = usbFrames.get();
        stats.add(R.string.stats_frames, StreamStats.count(frames));
        stats.add(R.string.stats_dropped, StreamStats.countOf(droppedFrames.get(), frames));
        stats.add(R.string.stats_decode_time, StreamStats.millis(decodeMillis));
    }

    // UVC frames carry no frame rate, so count rendered frames per second
    private float measureFrameRate() {
        long now = SystemClock.uptimeMillis();
        if (frameRateWindowStart == 0) {
            frameRateWindowStart = now;
        }
        frameRateWindowCount++;

        long elapsed = now - frameRateWindowStart;
        if (elapsed >= 1000) {
            measuredFrameRate = Math.round(frameRateWindowCount * 1000f / elapsed);
            frameRateWindowStart = now;
            frameRateWindowCount = 0;
        }
        return measuredFrameRate;
    }

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
        stopStream();
        activity.finish();
    }

    @Override
    public void stopStream() {
        running = false;
        captureManager.stop();

        mainHandler.removeCallbacks(renderRunnable);
        decodeExecutor.shutdownNow();
    }
}
