package de.daubli.feedwatch.ndi;

public class NdiReceiver extends NdiFrameCleaner {

    // NDIlib_recv_bandwidth_e
    private static final int BANDWIDTH_LOWEST = 0;

    private static final int BANDWIDTH_HIGHEST = 100;

    final long instancePointer;

    public NdiReceiver(boolean lowBandwidthModeEnabled) {
        this(lowBandwidthModeEnabled ? BANDWIDTH_LOWEST : BANDWIDTH_HIGHEST);
    }

    private NdiReceiver(int bandwidth) {
        this.instancePointer = receiveCreate(bandwidth);
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

    public void close() {
        receiveDestroy(instancePointer);
    }

    @Override
    void freeVideo(NdiVideoFrame videoFrame) {
        // video is only captured through NdiFrameSync
    }

    private static native long receiveCreate(int bandwidth);

    private static native void receiveConnect(long receiverPtr, String name, String urlAddress);

    private static native void receiveDestroy(long instancePointer);

    private static native NdiTally receiveCaptureTally(long receiverPtr, int timeoutMs);
}
