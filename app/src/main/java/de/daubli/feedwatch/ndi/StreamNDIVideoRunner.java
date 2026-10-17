package de.daubli.feedwatch.ndi;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;
import android.widget.Toast;
import de.daubli.feedwatch.R;
import de.daubli.feedwatch.StreamVideoActivity;
import de.daubli.feedwatch.StreamVideoRunner;
import de.daubli.feedwatch.databinding.StreamVideoActivityBinding;
import de.daubli.feedwatch.settings.SettingsStore;
import de.daubli.feedwatch.stats.RateMeter;
import de.daubli.feedwatch.stats.StreamStats;
import de.daubli.feedwatch.view.base.OpenGLVideoView;
import de.daubli.feedwatch.view.overlays.framehelper.FramingHelperOverlayView;
import de.daubli.feedwatch.view.overlays.tally.TallyOverlayView;

public class StreamNDIVideoRunner extends Thread implements StreamVideoRunner {

    private static final String TAG = "StreamNDI";

    // Frames wait in pendingFrames until their audio is heard: enough for ~250 ms of delay at 50 fps (the audio jitter
    // buffer at its limit plus the output latency), plus the frames in flight to GL. They are only wrappers, the
    // buffers belong to the frame sync.
    private static final int VIDEO_FRAME_POOL_SIZE = 16;

    // From the Choreographer frame time to the frame being on screen: GL draws it in this frame and SurfaceFlinger
    // shows it with the next composition. Measured with a flash/beep source on an SM-M315F.
    private static final int VIDEO_OUTPUT_LATENCY_FRAMES = 2;

    // smoothing of the measured audio latency, in display frames
    private static final int AUDIO_LATENCY_AVERAGE_FRAMES = 32;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final NdiSource ndiSource;

    private final StreamVideoActivity activity;

    private final OpenGLVideoView videoView;

    private final FramingHelperOverlayView framingHelperOverlayView;

    private final TallyOverlayView tallyOverlayView;

    // read by the statistics on the main thread
    private volatile NdiReceiver receiver;

    private volatile boolean lowBandwidth;

    // when the receiver connected, 0 before
    private volatile long connectedAtMillis;

    // statistics, only touched on the main thread
    private long shownFrames;

    // frames replaced by a newer one before they were due
    private long skippedFrames;

    private final RateMeter receivedRate = new RateMeter();

    private final RateMeter shownRate = new RateMeter();

    private final long[] performance = new long[4];

    private final long[] audioBuffer = new long[4];

    private NdiFrameSync frameSync;

    // only touched on the main thread
    private NdiAudioPlayer audioPlayer;

    private boolean audioEnabled;

    // time from audio arriving to hearing it, -1 while unknown
    private long audioLatencyNanos = -1;

    private final float[] audioPeaks = new float[2];

    private Choreographer choreographer;

    private volatile boolean running = true;

    private boolean firstFramePending = true;

    private Thread videoCaptureThread;

    private Thread tallyCaptureThread;

    // captured frames in order, waiting until their audio is due
    private final ConcurrentLinkedQueue<PendingFrame> pendingFrames = new ConcurrentLinkedQueue<>();

    // Frames are only reused once the GL thread has uploaded them
    private final ArrayBlockingQueue<NdiVideoFrame> freeFrames = new ArrayBlockingQueue<>(VIDEO_FRAME_POOL_SIZE);

    private volatile boolean videoFramesReleased = false;

    // Released once per display frame. The frame sync always returns a frame immediately, so capture is paced to
    // the display instead of spinning.
    private final Semaphore captureRequest = new Semaphore(0);

    private NdiVideoFrame captureFrame;

    // Ensure we fail only once and don't spam UI/logs
    private final AtomicBoolean renderFailed = new AtomicBoolean(false);

    public StreamNDIVideoRunner(NdiSource ndiSource, StreamVideoActivityBinding binding, StreamVideoActivity activity) {
        this.ndiSource = ndiSource;
        this.activity = activity;
        this.videoView = binding.openGLVideoView;
        this.framingHelperOverlayView = binding.framingHelperOverlayView;
        this.tallyOverlayView = binding.tallyOverlayView;
    }

    @Override
    public void run() {
        try {
            SettingsStore settingsStore = new SettingsStore();
            lowBandwidth = settingsStore.isLowBandwidthModeEnabled();
            NdiReceiver receiver = new NdiReceiver(lowBandwidth);
            receiver.connect(ndiSource);
            this.receiver = receiver;
            connectedAtMillis = SystemClock.elapsedRealtime();

            frameSync = new NdiFrameSync(receiver);

            for (int i = 0; i < VIDEO_FRAME_POOL_SIZE; i++) {
                freeFrames.offer(new NdiVideoFrame());
            }
            captureFrame = freeFrames.poll();

            startVideoCaptureThread();
            startTallyCaptureThread();

            mainHandler.post(() -> {
                if (!running) {
                    return;
                }
                audioPlayer = new NdiAudioPlayer(ndiSource);
                // read now: the HUD button may have changed it while the stream was starting
                setAudioEnabled(new SettingsStore().isAudioEnabled());
                choreographer = Choreographer.getInstance();
                choreographer.postFrameCallback(frameCallback);
            });
        } catch (Throwable t) {
            Log.e(TAG, "Fatal error starting stream", t);
            failWithMessage("Failed to start stream.");
        }
    }

    @Override
    public void shutdown() {
        // idempotent stop
        running = false;

        // Interrupt threads so they can exit quickly (especially if sleeping)
        interruptThread(videoCaptureThread);
        interruptThread(tallyCaptureThread);

        // Stop UI rendering first
        mainHandler.post(() -> {
            if (choreographer != null) {
                choreographer.removeFrameCallback(frameCallback);
            }
        });

        // Wait for all threads using the native receiver to exit before destroying it
        new Thread(() -> {
            joinThread(tallyCaptureThread);
            joinThread(videoCaptureThread);

            mainHandler.post(() -> {
                // the frames use the frame sync, so they go first
                safeCloseAudioPlayer();
                safeReleaseVideoFrames();
                safeCloseFrameSync();
                safeCloseReceiver();
                activity.finish();
            });
        }, "NDI-Shutdown").start();
    }

    /**
     * Switches audio playback on or off. With audio on, video is held back until the matching audio is heard;
     * with audio off, every frame is shown as soon as possible. Must be called on the main thread.
     */
    @Override
    public void setAudioEnabled(boolean enabled) {
        audioEnabled = enabled;
        if (audioPlayer == null) {
            // not started yet, run() applies the setting
            return;
        }
        audioLatencyNanos = -1;
        if (enabled) {
            if (!audioPlayer.start()) {
                Toast.makeText(activity, "Audio output not available.", Toast.LENGTH_LONG).show();
            }
        } else {
            audioPlayer.stop();
        }
    }

    @Override
    public void collectStats(StreamStats stats) {
        long connectedAt = connectedAtMillis;
        stats.add(R.string.stats_duration,
                connectedAt > 0 ? StreamStats.duration(SystemClock.elapsedRealtime() - connectedAt) : StreamStats.UNKNOWN);
        stats.add(R.string.stats_bandwidth,
                activity.getString(lowBandwidth ? R.string.stats_bandwidth_low : R.string.stats_bandwidth_high));

        // the receiver is closed on the main thread too, so it stays valid while it is read here
        NdiReceiver receiver = this.receiver;
        if (receiver != null) {
            receiver.getPerformance(performance);
            stats.add(R.string.stats_connections, String.valueOf(receiver.getConnectionCount()));
            stats.add(R.string.stats_received, StreamStats.frameRate(receivedRate.update(performance[0])));
        }
        stats.add(R.string.stats_shown, StreamStats.frameRate(shownRate.update(shownFrames)));
        if (receiver != null) {
            stats.add(R.string.stats_frames, StreamStats.count(performance[0]));
            stats.add(R.string.stats_dropped, StreamStats.countOf(performance[1], performance[0]));
        }
        stats.add(R.string.stats_skipped, StreamStats.count(skippedFrames));

        if (audioEnabled) {
            stats.add(R.string.stats_audio_delay,
                    audioLatencyNanos >= 0 ? StreamStats.millis(audioLatencyNanos / 1e6f) : StreamStats.UNKNOWN);
            // the player is created once the stream started
            if (audioPlayer != null) {
                audioPlayer.getBufferStats(audioBuffer);
                stats.add(R.string.stats_audio_buffer,
                        activity.getString(R.string.stats_audio_buffer_value, audioBuffer[1], audioBuffer[0]));
                stats.add(R.string.stats_audio_underruns, StreamStats.count(audioBuffer[2]));
                stats.add(R.string.stats_audio_dropped, StreamStats.count(audioBuffer[3]));
            }
        } else {
            stats.add(R.string.stats_audio_delay, activity.getString(R.string.stats_audio_off));
        }
    }

    private void startVideoCaptureThread() {
        videoCaptureThread = new Thread(() -> {
            long lastTimestamp = NdiVideoFrame.TIMESTAMP_UNDEFINED;
            try {
                while (running && !Thread.currentThread().isInterrupted()) {
                    try {
                        captureRequest.acquire();
                        // display frames missed while waiting for a free frame are not caught up
                        captureRequest.drainPermits();

                        if (frameSync != null && frameSync.captureVideo(captureFrame)) {
                            // The frame sync repeats the last frame until a new one arrives (60 Hz display, 25 fps
                            // source). A repeat is not handed to GL, uploading it again would only cost CPU; the
                            // frame is kept as captureFrame and its buffer freed by the next capture. Without a
                            // sender timestamp every frame is shown.
                            long timestamp = captureFrame.getTimestamp();
                            if (timestamp != NdiVideoFrame.TIMESTAMP_UNDEFINED && timestamp == lastTimestamp) {
                                continue;
                            }
                            lastTimestamp = timestamp;

                            // the audio arriving now belongs to this frame
                            pendingFrames.offer(new PendingFrame(captureFrame, System.nanoTime()));
                            // blocks until a frame is handed back
                            captureFrame = freeFrames.take();
                        }
                    } catch (InterruptedException ie) {
                        break;
                    } catch (Throwable t) {
                        Log.e(TAG, "Video capture failed", t);
                        failWithMessage("Video capture failed.");
                        return;
                    }
                }
            } finally {
                // nothing special
            }
        }, "NDI-Video-Capture");

        videoCaptureThread.start();
    }

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {

        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) {
                return;
            }

            long delayNanos = 0;
            if (audioEnabled) {
                audioPlayer.takePeaks(audioPeaks);
                activity.onAudioLevels(audioPeaks[0], audioPeaks[1]);
                delayNanos = updateAudioLatency();
            }

            final NdiVideoFrame frame = takeDueFrame(frameTimeNanos, delayNanos);
            if (frame != null) {
                if (firstFramePending) {
                    videoView.setVisibility(View.VISIBLE);
                    firstFramePending = false;
                }

                try {
                    if (!isFrameRenderable(frame)) {
                        recycleFrame(frame);
                        // skip bad frames quietly
                        reschedule();
                        return;
                    }

                    // read everything up front, the frame may be recycled as soon as it is handed to GL
                    int width = frame.getXResolution();
                    int height = frame.getYResolution();
                    FourCCType fourCCType = frame.getFourCCType();
                    float frameRate = frameRate(frame);

                    videoView.updateFrame(frame.getData(), width, height, fourCCType, () -> recycleFrame(frame));
                    shownFrames++;
                    framingHelperOverlayView.setFramingRect(videoView.getVideoRect());
                    activity.onVideoFormat(width, height, frameRate, fourCCType.name());

                } catch (UnsupportedOperationException e) {
                    recycleFrame(frame);
                    failUnsupportedFormat(frame.getFourCCTypeId(), e);
                    return;
                } catch (Throwable t) {
                    recycleFrame(frame);
                    Log.e(TAG, "Rendering failed", t);
                    failWithMessage("Rendering failed.");
                    return;
                }
            }

            reschedule();
        }

        /**
         * Returns the newest frame that should be on screen when this display frame is, recycling the older ones
         * it replaces. A frame is due once its audio is heard, {@code delayNanos} after it was captured.
         */
        private NdiVideoFrame takeDueFrame(long frameTimeNanos, long delayNanos) {
            long period = choreographerPeriodNanos();
            // shown about VIDEO_OUTPUT_LATENCY_FRAMES after this frame time; round to the nearest display frame
            long shownAt = frameTimeNanos + VIDEO_OUTPUT_LATENCY_FRAMES * period + period / 2;

            NdiVideoFrame due = null;
            PendingFrame pending;
            while ((pending = pendingFrames.peek()) != null && pending.capturedAt + delayNanos <= shownAt) {
                pendingFrames.poll();
                if (due != null) {
                    recycleFrame(due);
                    skippedFrames++;
                }
                due = pending.frame;
            }
            return due;
        }

        private void reschedule() {
            if (choreographer != null && running) {
                // the frame captured now is shown on the next display frame
                captureRequest.release();
                choreographer.postFrameCallback(this);
            }
        }
    };

    /**
     * @return how long video has to wait for its audio: the audio latency minus the video output latency, which
     *         the frame time already accounts for
     */
    private long updateAudioLatency() {
        long measured = audioPlayer.getOutputLatencyNanos();
        if (measured > 0) {
            audioLatencyNanos = audioLatencyNanos < 0 ? measured
                    : audioLatencyNanos + (measured - audioLatencyNanos) / AUDIO_LATENCY_AVERAGE_FRAMES;
        }
        // until the output reports its latency, frames are shown right away
        return Math.max(0, audioLatencyNanos);
    }

    private long choreographerPeriodNanos() {
        float refreshRate = videoView.getDisplay() != null ? videoView.getDisplay().getRefreshRate() : 60f;
        return (long) (1_000_000_000L / (refreshRate > 0 ? refreshRate : 60f));
    }

    private static final class PendingFrame {

        final NdiVideoFrame frame;

        final long capturedAt;

        PendingFrame(NdiVideoFrame frame, long capturedAt) {
            this.frame = frame;
            this.capturedAt = capturedAt;
        }
    }

    private static float frameRate(NdiVideoFrame frame) {
        int d = frame.getFrameRateD();
        return d > 0 ? (float) frame.getFrameRateN() / d : 0f;
    }

    private boolean isFrameRenderable(NdiVideoFrame frame) {
        int w = frame.getXResolution();
        int h = frame.getYResolution();
        if (w <= 0 || h <= 0) {
            return false;
        }

        ByteBuffer data = frame.getData();
        if (data == null) {
            return false;
        }
        return data.remaining() > 0;
    }

    private void startTallyCaptureThread() {
        tallyCaptureThread = new Thread(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    if (receiver == null) {
                        Thread.sleep(20);
                    } else {
                        NdiTally tally = receiver.captureTally(500);

                        if (tally != null) {
                            mainHandler.post(() -> {
                                tallyOverlayView.updateTally(tally.onProgram, tally.onPreview);
                                tallyOverlayView.setVideoRect(videoView.getVideoRect());
                                activity.onTallyState(tally.onProgram, tally.onPreview);
                            });
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    Log.e(TAG, "Tally capture failed", t);

                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "NDI-Tally-Capture");

        tallyCaptureThread.start();
    }

    private void failUnsupportedFormat(int fourCC, UnsupportedOperationException e) {
        if (!renderFailed.compareAndSet(false, true)) {
            return;
        }

        Log.e(TAG, "Unsupported video format: " + FourCCType.fourCCToString(fourCC), e);
        running = false;

        mainHandler.post(() -> {
            if (choreographer != null) {
                choreographer.removeFrameCallback(frameCallback);
            }
            videoView.setVisibility(View.INVISIBLE);

            Toast.makeText(activity,
                    "Can't display video: unsupported format (" + FourCCType.fourCCToString(fourCC) + ")",
                    Toast.LENGTH_LONG).show();

            // clean shutdown
            shutdown();
        });
    }

    private void failWithMessage(String message) {
        if (!renderFailed.compareAndSet(false, true)) {
            return;
        }

        running = false;
        mainHandler.post(() -> {
            if (choreographer != null) {
                choreographer.removeFrameCallback(frameCallback);
            }
            videoView.setVisibility(View.INVISIBLE);

            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            shutdown();
        });
    }

    private void interruptThread(Thread t) {
        if (t != null) {
            t.interrupt();
        }
    }

    private void joinThread(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void recycleFrame(NdiVideoFrame frame) {
        // frames handed back by the GL thread after teardown are dropped
        if (!videoFramesReleased) {
            freeFrames.offer(frame);
        }
    }

    private void safeReleaseVideoFrames() {
        if (videoFramesReleased) {
            return;
        }
        videoFramesReleased = true;

        NdiVideoFrame frame;
        while ((frame = freeFrames.poll()) != null) {
            safeCloseFrame(frame);
        }
        safeCloseFrame(captureFrame);
        captureFrame = null;
        PendingFrame pending;
        while ((pending = pendingFrames.poll()) != null) {
            safeCloseFrame(pending.frame);
        }
    }

    private void safeCloseAudioPlayer() {
        if (audioPlayer == null) {
            return;
        }
        try {
            audioPlayer.close();
        } catch (Throwable ignored) {
        }
        audioPlayer = null;
    }

    private void safeCloseFrame(NdiVideoFrame frame) {
        if (frame == null) {
            return;
        }
        try {
            frame.close();
        } catch (Throwable ignored) {
        }
    }

    private void safeCloseFrameSync() {
        if (frameSync == null) {
            return;
        }
        try {
            frameSync.close();
        } catch (Throwable ignored) {
        }
        frameSync = null;
    }

    private void safeCloseReceiver() {
        if (receiver == null) {
            return;
        }
        try {
            receiver.close();
        } catch (Throwable ignored) {
        }
        receiver = null;
    }
}
