// Based on code from https://github.com/WalkerKnapp/devolay (Apache 2.0).

package de.daubli.feedwatch.ndi;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

public class NdiVideoFrame implements AutoCloseable {

    // NDIlib_recv_timestamp_undefined
    public static final long TIMESTAMP_UNDEFINED = Long.MAX_VALUE;

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

    /**
     * Timecode of the frame in 100 ns units, as set by the sender (or synthesized).
     */
    public long getTimecode() {
        return getTimecode(instancePointer);
    }

    /**
     * Time the frame was submitted by the sender in 100 ns units, or {@link #TIMESTAMP_UNDEFINED} if the sender
     * does not provide it.
     */
    public long getTimestamp() {
        return getTimestamp(instancePointer);
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

    private static native long getTimecode(long pointer);

    private static native long getTimestamp(long pointer);

    private static native ByteBuffer getData(long pointer);
}
