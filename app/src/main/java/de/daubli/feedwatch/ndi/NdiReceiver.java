package de.daubli.feedwatch.ndi;

import androidx.annotation.Nullable;

public class NdiReceiver extends NdiFrameCleaner {

    // NDIlib_recv_bandwidth_e, video and metadata only: the audio comes from a receiver of its own (NdiAudioPlayer),
    // so with audio off no audio is received at all
    private static final int BANDWIDTH_LOWEST = 1;

    private static final int BANDWIDTH_HIGHEST = 101;

    final long instancePointer;

    public NdiReceiver(boolean lowBandwidthModeEnabled) {
        this(lowBandwidthModeEnabled, null);
    }

    /**
     * @param name how the receiver is listed by an NDI Discovery Server, or null for the library's default
     */
    public NdiReceiver(boolean lowBandwidthModeEnabled, @Nullable String name) {
        this.instancePointer = receiveCreate(lowBandwidthModeEnabled ? BANDWIDTH_LOWEST : BANDWIDTH_HIGHEST, name);
    }

    public void connect(NdiSource ndiSource ) {
        if (ndiSource == null) {
            receiveConnect(instancePointer, null, null);
        } else {
            receiveConnect(instancePointer, ndiSource.getSourceName(), ndiSource.getUrlAddress());
        }
    }

    /**
     * @return the name of the source the receiver is connected to now, also when a controller connected it, or null
     *         if it is not connected to one
     */
    @Nullable
    public String getSourceName() {
        return receiveGetSourceName(instancePointer);
    }

    public NdiTally captureTally(int timeoutMs) {
        return receiveCaptureTally(instancePointer, timeoutMs);
    }

    /**
     * Fills {@code counts} with the frames received since connecting: video, dropped video, audio and dropped audio.
     */
    public void getPerformance(long[] counts) {
        receiveGetPerformance(instancePointer, counts);
    }

    public int getConnectionCount() {
        return receiveGetConnectionCount(instancePointer);
    }

    public void close() {
        receiveDestroy(instancePointer);
    }

    @Override
    void freeVideo(NdiVideoFrame videoFrame) {
        // video is only captured through NdiFrameSync
    }

    private static native long receiveCreate(int bandwidth, String name);

    private static native String receiveGetSourceName(long receiverPtr);

    private static native void receiveConnect(long receiverPtr, String name, String urlAddress);

    private static native void receiveDestroy(long instancePointer);

    private static native NdiTally receiveCaptureTally(long receiverPtr, int timeoutMs);

    private static native void receiveGetPerformance(long receiverPtr, long[] counts);

    private static native int receiveGetConnectionCount(long receiverPtr);
}
