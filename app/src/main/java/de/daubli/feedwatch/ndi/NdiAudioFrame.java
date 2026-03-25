// Based on code from https://github.com/WalkerKnapp/devolay (Apache 2.0).

package de.daubli.feedwatch.ndi;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

public class NdiAudioFrame implements AutoCloseable {

    final long instancePointer;
    AtomicReference<NdiFrameCleaner> allocatedBufferSource = new AtomicReference<>();

    public NdiAudioFrame() {
        this.instancePointer = createNewAudioFrameDefaultSettings();
    }

    public int getSamples() {
        return getNoSamples(instancePointer);
    }

    public int getChannels() {
        return getNoChannels(instancePointer);
    }

    public ByteBuffer getData() {
        return getData(instancePointer);
    }

    public void freeBuffer() {
        if (allocatedBufferSource.get() != null) {
            allocatedBufferSource.getAndSet(null).freeAudio(this);
        }
    }

    @Override
    public void close() {
        destroyAudioFrame(instancePointer);
    }

    private static native long createNewAudioFrameDefaultSettings();

    private static native void destroyAudioFrame(long instancePointer);


    private static native int getNoSamples(long instancePointer);

    private static native int getNoChannels(long instancePointer);

    private static native ByteBuffer getData(long structPointer);

}
