package de.daubli.feedwatch.ndi;

public class NdiReceiver extends NdiFrameCleaner {

    // NDIlib_recv_bandwidth_e
    private static final int BANDWIDTH_AUDIO_ONLY = 10;

    private static final int BANDWIDTH_LOWEST = 0;

    private static final int BANDWIDTH_HIGHEST = 100;

    final long instancePointer;

    public NdiReceiver(boolean lowBandwidthModeEnabled) {
        this(lowBandwidthModeEnabled ? BANDWIDTH_LOWEST : BANDWIDTH_HIGHEST);
    }

    private NdiReceiver(int bandwidth) {
        this.instancePointer = receiveCreate(bandwidth);
    }

    /**
     * A receiver that only gets audio (and metadata) from the source, for capturing audio frames as they arrive.
     */
    public static NdiReceiver audioOnly() {
        return new NdiReceiver(BANDWIDTH_AUDIO_ONLY);
    }

    public void connect(NdiSource ndiSource ) {
        if (ndiSource == null) {
            receiveConnect(instancePointer, null, null);
        } else {
            receiveConnect(instancePointer, ndiSource.getSourceName(), ndiSource.getUrlAddress());
        }
    }

    public NdiTally captureTally(int timeoutMs) {
        return receiveCaptureTally(instancePointer, timeoutMs);
    }

    /**
     * Waits up to {@code timeoutMs} for the next audio frame, in the sender's own format and sample rate.
     *
     * @return true if a frame was captured into {@code audioFrame}
     */
    public boolean captureAudio(NdiAudioFrame audioFrame, int timeoutMs) {
        audioFrame.freeBuffer();
        boolean captured = receiveCaptureAudio(instancePointer, audioFrame.instancePointer, timeoutMs);
        if (captured) {
            audioFrame.allocatedBufferSource.set(this);
        }
        return captured;
    }

    public void close() {
        receiveDestroy(instancePointer);
    }

    @Override
    void freeVideo(NdiVideoFrame videoFrame) {
        // video is only captured through NdiFrameSync
    }

    @Override
    void freeAudio(NdiAudioFrame audioFrame) {
        receiveFreeAudio(instancePointer, audioFrame.instancePointer);
    }

    private static native long receiveCreate(int bandwidth);

    private static native void receiveConnect(long receiverPtr, String name, String urlAddress);

    private static native void receiveDestroy(long instancePointer);

    private static native NdiTally receiveCaptureTally(long receiverPtr, int timeoutMs);

    private static native boolean receiveCaptureAudio(long receiverPtr, long framePtr, int timeoutMs);

    private static native void receiveFreeAudio(long receiverPtr, long framePtr);
}
