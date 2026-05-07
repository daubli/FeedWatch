package de.daubli.feedwatch.ndi;

import java.nio.ByteBuffer;
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
import de.daubli.feedwatch.audio.AudioRingBuffer;
import de.daubli.feedwatch.audio.AudioUtils;
import de.daubli.feedwatch.databinding.StreamVideoActivityBinding;
import de.daubli.feedwatch.settings.SettingsStore;
import de.daubli.feedwatch.view.base.OpenGLVideoView;
import de.daubli.feedwatch.view.overlays.framehelper.FramingHelperOverlayView;
import de.daubli.feedwatch.view.overlays.tally.TallyOverlayView;

public class StreamNDIVideoRunner extends Thread implements StreamVideoRunner {

    private static final String TAG = "StreamNDI";

    private static final int SAMPLE_RATE = 48_000;

    private static final int CHANNEL_COUNT = 2;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final NdiSource ndiSource;

    private final StreamVideoActivity activity;

    private final OpenGLVideoView videoView;

    private final FramingHelperOverlayView framingHelperOverlayView;

    private final TallyOverlayView tallyOverlayView;

    private NdiReceiver receiver;

    private NdiFrameSync frameSync;

    private AudioTrack audioTrack;

    private Choreographer choreographer;

    private volatile boolean running = true;

    private volatile boolean firstAudioFrameReceived = false;

    private boolean firstFramePending = true;

    private Thread audioThread;

    private Thread audioCaptureThread;

    private Thread videoCaptureThread;

    private Thread tallyCaptureThread;

    private final AtomicReference<NdiVideoFrame> renderFrame = new AtomicReference<>();

    private NdiVideoFrame captureFrame;

    private final AudioRingBuffer ringBuffer = new AudioRingBuffer(20, 4000 * 2 * CHANNEL_COUNT);

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

            captureFrame = new NdiVideoFrame();
            renderFrame.set(new NdiVideoFrame());

            initAudio();
            startAudioCaptureThread();
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
        interruptThread(audioCaptureThread);
        interruptThread(videoCaptureThread);
        interruptThread(tallyCaptureThread);

        // Stop UI rendering first
        mainHandler.post(() -> {
            if (choreographer != null) {
                choreographer.removeFrameCallback(frameCallback);
            }
        });

        // Release resources (AudioTrack should be handled on main thread to avoid UI races in some implementations)
        mainHandler.post(() -> {
            safeReleaseAudioTrack();
            safeCloseFrameSync();
            safeCloseReceiver();
            activity.finish();
        });
    }

    private void initAudio() {
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);

        audioTrack = new AudioTrack.Builder().setAudioAttributes(
                        new AudioAttributes.Builder().setLegacyStreamType(AudioManager.STREAM_MUSIC).build()).setAudioFormat(
                        new AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).setBufferSizeInBytes(minBufferSize * 8)
                .setTransferMode(AudioTrack.MODE_STREAM).build();

        audioTrack.play();
    }

    private void startAudioThread() {
        audioThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            try {
                while (running && !Thread.currentThread().isInterrupted()) {
                    byte[] frameData = ringBuffer.read();
                    if (frameData != null) {
                        try {
                            if (audioTrack != null) {
                                audioTrack.write(frameData, 0, frameData.length);
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "AudioTrack write failed", t);
                            // Audio failure shouldn't be "unsupported format", just stop cleanly
                            failWithMessage("Audio playback failed.");
                            return;
                        }
                    } else {
                        // avoid tight spin
                        Thread.sleep(2);
                    }
                }
            } catch (InterruptedException ignored) {
                // exit
            }
        }, "NDI-AudioTrack");

        audioThread.start();
    }

    private void startAudioCaptureThread() {
        audioCaptureThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            NdiAudioFrame audioFrame = new NdiAudioFrame();

            try {
                while (running && !Thread.currentThread().isInterrupted()) {
                    try {
                        if (frameSync == null) {
                            Thread.sleep(2);
                            continue;
                        }

                        frameSync.captureAudio(audioFrame, SAMPLE_RATE, CHANNEL_COUNT, 4000);

                        ByteBuffer pcm = AudioUtils.convertPlanarFloatToInterleavedPCM16(audioFrame.getData(),
                                audioFrame.getChannels());

                        if (pcm != null && pcm.remaining() > 0) {
                            firstAudioFrameReceived = true;

                            byte[] data = new byte[pcm.remaining()];
                            pcm.get(data);

                            if (!AudioUtils.isSilentFast(data)) {
                                ringBuffer.write(data);
                            }
                        }

                        Thread.sleep(2);
                    } catch (InterruptedException ie) {
                        break;
                    } catch (Throwable t) {
                        Log.e(TAG, "Audio capture failed", t);
                        failWithMessage("Audio capture failed.");
                        return;
                    }
                }
            } finally {
                // nothing special
            }
        }, "NDI-Audio-Capture");

        audioCaptureThread.start();
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
                            NdiVideoFrame old = renderFrame.getAndSet(captureFrame);
                            captureFrame = old;
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

            NdiVideoFrame frame = renderFrame.get();
            if (frame != null) {
                if (firstFramePending && firstAudioFrameReceived) {
                    videoView.setVisibility(View.VISIBLE);
                    firstFramePending = false;
                }

                try {
                    if (!isFrameRenderable(frame)) {
                        // skip bad frames quietly
                        reschedule();
                        return;
                    }

                    videoView.updateFrame(frame.getData(), frame.getXResolution(), frame.getYResolution(),
                            frame.getFourCCType());
                    framingHelperOverlayView.setFramingRect(videoView.getVideoRect());

                } catch (UnsupportedOperationException e) {
                    failUnsupportedFormat(frame.getFourCCTypeId(), e);
                    return;
                } catch (Throwable t) {
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
