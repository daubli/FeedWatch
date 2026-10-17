package de.daubli.feedwatch.ndi;

/**
 * Plays the audio of an NDI source on the device's audio output. It has a receiver of its own for the audio, which
 * fills a jitter buffer: audio held back by Wi-Fi for a moment only changes how full the buffer is. The buffer grows
 * each time it runs empty anyway. Playback is resampled slightly to hold the buffer level, which absorbs the clock
 * difference between sender and device.
 */
public class NdiAudioPlayer implements AutoCloseable {

    private final long instancePointer;

    public NdiAudioPlayer(NdiSource source) {
        instancePointer = create(source.getSourceName(), source.getUrlAddress());
    }

    /**
     * Connects to the source and starts playback once the jitter buffer is filled.
     *
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
     * @return the time from audio arriving in the frame sync to hearing it, jitter buffer included, or -1 if not
     *         known (yet)
     */
    public long getOutputLatencyNanos() {
        return outputLatencyNanos(instancePointer);
    }

    /**
     * Writes the jitter buffer's target and what is buffered now (both in milliseconds), how often it ran empty and
     * how many audio frames were dropped on the way into {@code stats}.
     */
    public void getBufferStats(long[] stats) {
        bufferStats(instancePointer, stats);
    }

    @Override
    public void close() {
        destroy(instancePointer);
    }

    private static native long create(String sourceName, String urlAddress);

    private static native void destroy(long pPlayer);

    private static native boolean start(long pPlayer);

    private static native void stop(long pPlayer);

    private static native void takePeaks(long pPlayer, float[] peaks);

    private static native long outputLatencyNanos(long pPlayer);

    private static native void bufferStats(long pPlayer, long[] stats);
}
