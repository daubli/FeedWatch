// Based on code from https://github.com/WalkerKnapp/devolay (Apache 2.0).

package de.daubli.feedwatch.ndi;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

public class NdiVideoFrame implements AutoCloseable {

    final long instancePointer;

    AtomicReference<NdiFrameCleaner> allocatedBufferSource = new AtomicReference<>();

    public NdiVideoFrame() {
        this.instancePointer = createNewVideoFrameDefaultSettings();
    }

    public int getXResolution() {
        return getXRes(instancePointer);
    }

    public int getYResolution() {
        return getYRes(instancePointer);
    }

    public ByteBuffer getData() {
        return getData(instancePointer);
    }

    public FourCCType getFourCCType() throws UnsupportedOperationException {
        try {
            return FourCCType.valueOf(getFourCCTypeId());
        } catch (IllegalArgumentException e) {
            throw new UnsupportedOperationException(
                    "Unsupported FourCC type with id: " + getFourCCType(instancePointer));
        }
    }

    public int getFourCCTypeId() {
        return getFourCCType(instancePointer);
    }

    public int getFrameRateN() {
        return getFrameRateN(instancePointer);
    }

    public int getFrameRateD() {
        return getFrameRateD(instancePointer);
    }

    public void freeBuffer() {
        if (allocatedBufferSource.get() != null) {
            allocatedBufferSource.getAndSet(null).freeVideo(this);
        }
    }

    @Override
    public void close() {
        freeBuffer();
        destroyVideoFrame(instancePointer);
    }

    private static native long createNewVideoFrameDefaultSettings();

    private static native void destroyVideoFrame(long pointer);

    private static native int getXRes(long pointer);

    private static native int getYRes(long pointer);

    private static native int getFourCCType(long pointer);

    private static native int getFrameRateN(long pointer);

    private static native int getFrameRateD(long pointer);

    private static native ByteBuffer getData(long pointer);
}
