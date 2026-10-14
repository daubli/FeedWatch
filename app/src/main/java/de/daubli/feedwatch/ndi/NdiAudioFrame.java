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

    public int getSampleRate() {
        return getSampleRate(instancePointer);
    }

    /**
     * Writes the frame as interleaved 16-bit stereo PCM in native byte order into the direct buffer {@code out}
     * and the peak level (0..1) of both channels into {@code peaks}. Mono is duplicated onto both channels.
     *
     * @return the number of bytes written, or -1 if {@code out} is too small
     */
    public int toStereoPcm16(ByteBuffer out, float[] peaks) {
        return toStereoPcm16(instancePointer, out, peaks);
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

    private static native int toStereoPcm16(long instancePointer, ByteBuffer out, float[] peaks);

    private static native int getSampleRate(long instancePointer);

    private static native ByteBuffer getData(long structPointer);

}
