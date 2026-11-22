package de.daubli.feedwatch.ndi;

/**
 * Advertises receivers to NDI Discovery Servers, where controllers see which source each one shows and can connect
 * it to another.
 */
class NdiReceiverAdvertiser implements AutoCloseable {

    private final long instancePointer;

    /**
     * @param discoveryServers comma-separated NDI Discovery Servers, each with an optional port. None needs to be
     *                         reachable yet, receivers are registered once one is.
     */
    NdiReceiverAdvertiser(String discoveryServers) {
        instancePointer = advertiserCreate(discoveryServers);
    }

    boolean isValid() {
        return instancePointer != 0;
    }

    /**
     * @param inputGroup groups receivers that belong together, e.g. one for video and one for audio, under one input
     * @return false if the receiver is advertised already
     */
    boolean add(NdiReceiver receiver, String inputGroup) {
        return advertiserAddReceiver(instancePointer, receiver.instancePointer, inputGroup);
    }

    /**
     * @return false if the receiver was not advertised
     */
    boolean remove(NdiReceiver receiver) {
        return advertiserDelReceiver(instancePointer, receiver.instancePointer);
    }

    @Override
    public void close() {
        advertiserDestroy(instancePointer);
    }

    private static native long advertiserCreate(String discoveryServers);

    private static native void advertiserDestroy(long pAdvertiser);

    private static native boolean advertiserAddReceiver(long pAdvertiser, long pReceiver, String inputGroup);

    private static native boolean advertiserDelReceiver(long pAdvertiser, long pReceiver);
}
