package de.daubli.feedwatch.ndi;

public class NdiReceiver {

    final long instancePointer;

    public NdiReceiver(boolean lowBandwidthModeEnabled) {
        this.instancePointer = receiveCreate(lowBandwidthModeEnabled);
    }

    public void connect(NdiSource ndiSource ) {
        if (ndiSource == null) {
            receiveConnect(instancePointer, null, null);
        } else {
            receiveConnect(instancePointer, ndiSource.getSourceName(), ndiSource.getUrlAddress());
        }
    }

    public void close() {
        receiveDestroy(instancePointer);
    }

    private static native long receiveCreate(boolean useLowBandwidth);

    private static native void receiveConnect(long receiverPtr, String name, String urlAddress);

    private static native void receiveDestroy(long instancePointer);
}
