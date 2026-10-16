package de.daubli.feedwatch.ndi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import android.media.*;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;
import android.widget.Toast;
import de.daubli.feedwatch.StreamVideoActivity;
import de.daubli.feedwatch.StreamVideoRunner;
import de.daubli.feedwatch.databinding.StreamVideoActivityBinding;
import de.daubli.feedwatch.settings.SettingsStore;
import de.daubli.feedwatch.view.base.OpenGLVideoView;
import de.daubli.feedwatch.view.overlays.framehelper.FramingHelperOverlayView;
import de.daubli.feedwatch.view.overlays.tally.TallyOverlayView;

public class StreamNDIVideoRunner extends Thread implements StreamVideoRunner {

    private static final String TAG = "StreamNDI";

    private static final int VIDEO_FRAME_POOL_SIZE = 4;

    private static final int AUDIO_BYTES_PER_FRAME = 4; // 16-bit stereo

    private static final int AUDIO_CAPTURE_TIMEOUT_MS = 100;

    // AudioTrack buffer: absorbs network jitter, so it is generous
    private static final int AUDIO_TRACK_BUFFER_MS = 500;

    // buffered before playback starts, enough to ride out gaps between NDI audio packets on Wi-Fi (seen up to
    // ~185 ms); together with the output pipeline this is the audio latency
    private static final int AUDIO_PREFILL_MS = 200;

    // playback time after which the buffer level is taken as the level to hold
    private static final int AUDIO_SETTLE_MS = 3000;

    // time constant of the buffer level average; arrival and playback both come in bursts
    private static final double AUDIO_FILL_AVERAGE_SECONDS = 1.0;

    // Sender and DAC clocks differ by some ppm. The playback rate is trimmed by at most this much to hold the buffer
    // level; 0.1 % is far below an audible pitch change.
    private static final double AUDIO_MAX_RATE_CORRECTION = 0.001;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final NdiSource ndiSource;

    private final StreamVideoActivity activity;

    private final OpenGLVideoView videoView;

    private final FramingHelperOverlayView framingHelperOverlayView;

    private final TallyOverlayView tallyOverlayView;

    private NdiReceiver receiver;

    private NdiFrameSync frameSync;

    // separate audio-only connection, see startAudioThread()
    private NdiReceiver audioReceiver;

    // created by the audio thread, released after it has exited
    private volatile AudioTrack audioTrack;

    private Choreographer choreographer;

    private volatile boolean running = true;

    private boolean firstFramePending = true;

    private Thread audioThread;

    private Thread videoCaptureThread;

    private Thread tallyCaptureThread;

    private final AtomicReference<NdiVideoFrame> renderFrame = new AtomicReference<>();

    // Frames are only reused once the GL thread has uploaded them
    private final ArrayBlockingQueue<NdiVideoFrame> freeFrames = new ArrayBlockingQueue<>(VIDEO_FRAME_POOL_SIZE);

    private volatile boolean videoFramesReleased = false;

    private NdiVideoFrame captureFrame;

    // Peak levels since the last display frame, for the HUD audio meters
    private volatile float audioPeakLeft;

    private volatile float audioPeakRight;

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
            receiver = new NdiReceiver(settingsStore.isLowBandwidthModeEnabled());
            receiver.connect(ndiSource);

            frameSync = new NdiFrameSync(receiver);

            for (int i = 0; i < VIDEO_FRAME_POOL_SIZE; i++) {
                freeFrames.offer(new NdiVideoFrame());
            }
            captureFrame = freeFrames.poll();

            audioReceiver = NdiReceiver.audioOnly();
            audioReceiver.connect(ndiSource);

            startAudioThread();
            startVideoCaptureThread();
            startTallyCaptureThread();

            mainHandler.post(() -> {
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
        interruptThread(audioThread);
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
            joinThread(audioThread);

            mainHandler.post(() -> {
                safeReleaseAudioTrack();
                // frames must be freed before the frame sync that owns their buffers
                safeReleaseVideoFrames();
                safeCloseFrameSync();
                safeCloseReceiver();
                safeCloseAudioReceiver();
                activity.finish();
            });
        }, "NDI-Shutdown").start();
    }

    private static AudioTrack createAudioTrack(int sampleRate) {
        int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minBufferSize, sampleRate * AUDIO_TRACK_BUFFER_MS / 1000 * AUDIO_BYTES_PER_FRAME);

        return new AudioTrack.Builder().setAudioAttributes(
                        new AudioAttributes.Builder().setLegacyStreamType(AudioManager.STREAM_MUSIC).build()).setAudioFormat(
                        new AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM).build();
    }

    /**
     * Plays audio from the audio-only receiver. Audio is not taken from the frame sync: its resampler follows both
     * the timing of the calls and the burstiness of network arrival, which is audible as wavering pitch. Instead
     * frames are queued on the AudioTrack as they arrive, so network jitter only changes how full its buffer is,
     * and the playback rate is trimmed by at most 0.1 % to hold the buffer level against clock drift.
     */
    private void startAudioThread() {
        audioThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            NdiAudioFrame audioFrame = new NdiAudioFrame();
            float[] peaks = new float[2];
            ByteBuffer pcm = null;

            AudioTrack track = null;
            int trackSampleRate = 0;
            int playbackRate = 0;
            long framesWritten = 0;
            double fillAverage = 0;
            double targetFill = -1;
            long lastFillUpdate = 0;
            boolean playing = false;

            try {
                while (running && !Thread.currentThread().isInterrupted()) {
                    NdiReceiver source = audioReceiver;
                    if (source == null) {
                        break;
                    }
                    if (!source.captureAudio(audioFrame, AUDIO_CAPTURE_TIMEOUT_MS)) {
                        continue;
                    }

                    int sampleRate = audioFrame.getSampleRate();
                    int samples = audioFrame.getSamples();
                    int channels = audioFrame.getChannels();
                    if (sampleRate <= 0 || samples <= 0 || channels <= 0) {
                        audioFrame.freeBuffer();
                        continue;
                    }

                    if (track == null || sampleRate != trackSampleRate) {
                        // the AudioTrack runs at the sender's sample rate, Android resamples to the device
                        if (track != null) {
                            track.release();
                        }
                        track = createAudioTrack(sampleRate);
                        audioTrack = track;
                        trackSampleRate = sampleRate;
                        playbackRate = sampleRate;
                        framesWritten = 0;
                        targetFill = -1;
                        playing = false;
                    }

                    // converted natively: per-sample work in Java is far too slow here
                    int bytes = samples * AUDIO_BYTES_PER_FRAME;
                    if (pcm == null || pcm.capacity() < bytes) {
                        pcm = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
                    }
                    int size = audioFrame.toStereoPcm16(pcm, peaks);
                    audioFrame.freeBuffer();
                    if (size <= 0) {
                        continue;
                    }
                    pcm.clear();
                    pcm.limit(size);

                    audioPeakLeft = Math.max(audioPeakLeft, peaks[0]);
                    audioPeakRight = Math.max(audioPeakRight, peaks[1]);

                    long prefillFrames = (long) trackSampleRate * AUDIO_PREFILL_MS / 1000;
                    if (playing) {
                        // written minus played (an unsigned 32-bit frame counter); includes the output pipeline's
                        // latency, so only the level it settles at is known, not what it should be
                        int head = track.getPlaybackHeadPosition();
                        long fill = (framesWritten - head) & 0xFFFFFFFFL;
                        long now = System.nanoTime();
                        double alpha = Math.min(1.0, (now - lastFillUpdate) / 1e9 / AUDIO_FILL_AVERAGE_SECONDS);
                        lastFillUpdate = now;
                        fillAverage += alpha * (fill - fillAverage);

                        if (targetFill < 0 && (head & 0xFFFFFFFFL) >= (long) trackSampleRate * AUDIO_SETTLE_MS / 1000) {
                            targetFill = fillAverage;
                        }
                        if (targetFill > 0) {
                            double excess = fillAverage - targetFill;
                            if (excess > prefillFrames) {
                                // a burst left far more buffered than needed: drop a packet rather than keep the
                                // extra latency
                                Log.d(TAG, "Dropping audio packet, buffer " + Math.round(excess) + " frames over target");
                                fillAverage -= samples;
                                continue;
                            }
                            double correction = Math.max(-AUDIO_MAX_RATE_CORRECTION,
                                    Math.min(AUDIO_MAX_RATE_CORRECTION, excess / prefillFrames * AUDIO_MAX_RATE_CORRECTION));
                            int rate = (int) Math.round(trackSampleRate * (1.0 + correction));
                            if (rate != playbackRate) {
                                track.setPlaybackRate(rate);
                                playbackRate = rate;
                            }
                        }
                    }

                    // a full buffer only happens after a long stall; the excess is dropped
                    int written = track.write(pcm, size, AudioTrack.WRITE_NON_BLOCKING);
                    if (written < 0) {
                        Log.e(TAG, "AudioTrack write failed: " + written);
                        failWithMessage("Audio playback failed.");
                        return;
                    }
                    framesWritten += written / AUDIO_BYTES_PER_FRAME;

                    if (!playing && framesWritten >= prefillFrames) {
                        track.play();
                        playing = true;
                        fillAverage = framesWritten;
                        lastFillUpdate = System.nanoTime();
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Audio playback failed", t);
                failWithMessage("Audio playback failed.");
            } finally {
                audioFrame.freeBuffer();
                audioFrame.close();
            }
        }, "NDI-Audio");

        audioThread.start();
    }

    private void startVideoCaptureThread() {
        videoCaptureThread = new Thread(() -> {
            try {
                while (running && !Thread.currentThread().isInterrupted()) {
                    try {
                        if (frameSync == null) {
                            Thread.sleep(2);
                            continue;
                        }

                        if (frameSync.captureVideo(captureFrame)) {
                            NdiVideoFrame skipped = renderFrame.getAndSet(captureFrame);
                            if (skipped != null) {
                                recycleFrame(skipped);
                            }
                            // blocks until the GL thread hands a frame back
                            captureFrame = freeFrames.take();
                        } else {
                            Thread.sleep(2);
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

            activity.onAudioLevels(audioPeakLeft, audioPeakRight);
            audioPeakLeft = 0f;
            audioPeakRight = 0f;

            final NdiVideoFrame frame = renderFrame.getAndSet(null);
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

        private void reschedule() {
            if (choreographer != null && running) {
                choreographer.postFrameCallback(this);
            }
        }
    };

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

    private void safeReleaseAudioTrack() {
        if (audioTrack == null) {
            return;
        }
        try {
            audioTrack.stop();
        } catch (Throwable ignored) {
        }
        try {
            audioTrack.release();
        } catch (Throwable ignored) {
        }
        audioTrack = null;
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
        safeCloseFrame(renderFrame.getAndSet(null));
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

    private void safeCloseAudioReceiver() {
        if (audioReceiver == null) {
            return;
        }
        try {
            audioReceiver.close();
        } catch (Throwable ignored) {
        }
        audioReceiver = null;
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
