package de.daubli.feedwatch.uvc;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import de.daubli.feedwatch.R;
import de.daubli.feedwatch.StreamVideoActivity;
import de.daubli.feedwatch.StreamVideoRunner;
import de.daubli.feedwatch.databinding.StreamVideoActivityBinding;
import de.daubli.feedwatch.ndi.FourCCType;
import de.daubli.feedwatch.settings.SettingsStore;
import de.daubli.feedwatch.stats.RateMeter;
import de.daubli.feedwatch.stats.StreamStats;
import de.daubli.feedwatch.view.base.OpenGLVideoView;
import de.daubli.feedwatch.view.overlays.framehelper.FramingHelperOverlayView;

public class StreamUvcVideoRunner extends Thread implements StreamVideoRunner {

    private static final int FRAME_BUFFERS = 4;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final OpenGLVideoView openGLVideoView;

    private final StreamVideoActivity activity;

    private final UvcCaptureManager captureManager;

    private final UvcAudioPlayer audioPlayer;

    private final AtomicReference<Frame> latestFrame = new AtomicReference<>();

    private final FramingHelperOverlayView framingHelperOverlayView;

    private volatile boolean running = true;

    private volatile FramePool framePool;

    private long frameRateWindowStart;

    private int frameRateWindowCount;

    private float measuredFrameRate;

    // statistics: the counters are written by the USB and render threads, the rest only on the main thread
    private final long startedAtMillis = SystemClock.elapsedRealtime();

    private final AtomicLong usbFrames = new AtomicLong();

    private final AtomicLong usbBytes = new AtomicLong();

    // frames that were not shown because a newer one replaced them
    private final AtomicLong droppedFrames = new AtomicLong();

    // time spent copying the frame into one of our buffers, -1 before the first frame
    private volatile float copyMillis = -1;

    private long shownFrames;

    private final RateMeter usbFrameRate = new RateMeter();

    private final RateMeter usbDataRate = new RateMeter();

    private final RateMeter shownRate = new RateMeter();

    private static final class Frame {

        final ByteBuffer rgba;

        final int width;

        final int height;

        final FramePool pool;

        Frame(ByteBuffer rgba, int width, int height, FramePool pool) {
            this.rgba = rgba;
            this.width = width;
            this.height = height;
            this.pool = pool;
        }
    }

    /**
     * A few direct buffers that are recycled once the GL thread has uploaded them. Without this
     * every frame allocated a 3.7 MB direct buffer and copied the pixels twice.
     */
    private static final class FramePool {

        private final ByteBuffer[] buffers = new ByteBuffer[FRAME_BUFFERS];

        private final boolean[] inUse = new boolean[FRAME_BUFFERS];

        private final int size;

        FramePool(int size) {
            this.size = size;
            for (int i = 0; i < FRAME_BUFFERS; i++) {
                buffers[i] = ByteBuffer.allocateDirect(size);
            }
        }

        int getSize() {
            return size;
        }

        synchronized ByteBuffer acquire() {
            for (int i = 0; i < FRAME_BUFFERS; i++) {
                if (!inUse[i]) {
                    inUse[i] = true;
                    return buffers[i];
                }
            }
            return null;
        }

        synchronized void release(ByteBuffer buffer) {
            for (int i = 0; i < FRAME_BUFFERS; i++) {
                if (buffers[i] == buffer) {
                    inUse[i] = false;
                    return;
                }
            }
        }
    }

    public StreamUvcVideoRunner(UVCSource uvcSource, StreamVideoActivityBinding binding, StreamVideoActivity activity) {
        this.captureManager = new UvcCaptureManager(uvcSource, activity,
                new SettingsStore().getUvcResolutionHeight());
        this.openGLVideoView = binding.openGLVideoView;
        this.framingHelperOverlayView = binding.framingHelperOverlayView;
        this.activity = activity;
        this.audioPlayer = new UvcAudioPlayer(activity,
                (left, right) -> mainHandler.post(() -> activity.onAudioLevels(left, right, true)));
        openGLVideoView.setOnVideoRectChangedListener(framingHelperOverlayView::setFramingRect);
    }

    @Override
    public void run() {
        if (new SettingsStore().isAudioEnabled()) {
            audioPlayer.start();
        }

        captureManager.start(this::onFrame);

        mainHandler.post(renderRunnable);
    }

    private void onFrame(ByteBuffer frame, int width, int height) {
        // the card is closed in the background after stopStream
        if (!running) {
            return;
        }
        usbFrames.incrementAndGet();
        usbBytes.addAndGet(frame.remaining());

        int size = width * height * 4;
        FramePool pool = framePool;
        if (pool == null || pool.getSize() != size) {
            pool = new FramePool(size);
            framePool = pool;
        }

        ByteBuffer target = pool.acquire();
        if (target == null) {
            // all buffers are still with the renderer
            droppedFrames.incrementAndGet();
            return;
        }

        long copyStart = SystemClock.elapsedRealtimeNanos();
        target.clear();
        int length = Math.min(frame.remaining(), target.capacity());
        ByteBuffer source = frame.duplicate();
        source.limit(source.position() + length);
        target.put(source);
        target.rewind();
        updateCopyTime((SystemClock.elapsedRealtimeNanos() - copyStart) / 1e6f);

        Frame previous = latestFrame.getAndSet(new Frame(target, width, height, pool));
        if (previous != null) {
            previous.pool.release(previous.rgba);
            droppedFrames.incrementAndGet();
        }
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

                // The pool buffer is free again once the GL thread uploaded it
                openGLVideoView.updateFrame(f.rgba, f.width, f.height, FourCCType.RGBA,
                        () -> f.pool.release(f.rgba));
                shownFrames++;
                activity.onVideoFormat(f.width, f.height, measureFrameRate(), "MJPEG");
            }

            openGLVideoView.postOnAnimation(this);
        }
    };

    // only the capture thread writes it
    private void updateCopyTime(float millis) {
        float average = copyMillis;
        copyMillis = average < 0 ? millis : average + (millis - average) / 16;
    }

    @Override
    public void setAudioEnabled(boolean enabled) {
        if (enabled) {
            audioPlayer.start();
        } else {
            audioPlayer.stop();
        }
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
        stats.add(R.string.stats_decode_time, StreamStats.millis(copyMillis));
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

    @Override
    public void shutdown() {
        stopStream();
        activity.finish();
    }

    @Override
    public void stopStream() {
        running = false;
        audioPlayer.stop();
        captureManager.stop();

        mainHandler.removeCallbacks(renderRunnable);

        Frame f = latestFrame.getAndSet(null);
        if (f != null) {
            f.pool.release(f.rgba);
        }
    }
}
