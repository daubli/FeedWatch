package de.daubli.feedwatch.ndi;

/**
 * Plays the audio of a frame sync on the device's audio output. Audio is pulled from the frame sync in the output's
 * callback, so the frame sync's resampler follows the DAC clock.
 */
public class NdiAudioPlayer implements AutoCloseable {

    private final long instancePointer;

    public NdiAudioPlayer(NdiFrameSync frameSync) {
        instancePointer = create(frameSync.instancePointer);
    }

    /**
     * @return false if no output stream could be opened
     */
    public boolean start() {
        return start(instancePointer);
    }

    public void stop() {
        stop(instancePointer);
    }

    /**
     * Writes the left and right peak levels (0..1) since the last call into {@code peaks}.
     */
    public void takePeaks(float[] peaks) {
        takePeaks(instancePointer, peaks);
    }

    /**
     * @return the time from pulling audio out of the frame sync to hearing it, or -1 if not known (yet)
     */
    public long getOutputLatencyNanos() {
        return outputLatencyNanos(instancePointer);
    }

    @Override
    public void close() {
        destroy(instancePointer);
    }

    private static native long create(long pFramesync);

    private static native void destroy(long pPlayer);

    private static native boolean start(long pPlayer);

    private static native void stop(long pPlayer);

    private static native void takePeaks(long pPlayer, float[] peaks);

    private static native long outputLatencyNanos(long pPlayer);
}
