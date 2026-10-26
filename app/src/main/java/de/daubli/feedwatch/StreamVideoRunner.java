package de.daubli.feedwatch;

import de.daubli.feedwatch.stats.StreamStats;

public interface StreamVideoRunner {

    void start();

    /**
     * Stops the stream and closes the activity once everything is released.
     */
    void shutdown();

    /**
     * Stops the stream and keeps the activity open, so it can start a new stream for the same source, e.g. after
     * the screen was off.
     */
    void stopStream();

    /**
     * Switches audio playback on or off, for sources that have audio. Called on the main thread.
     */
    default void setAudioEnabled(boolean enabled) {
    }

    /**
     * Adds the current statistics of the stream. Called on the main thread while they are shown.
     */
    default void collectStats(StreamStats stats) {
    }
}
