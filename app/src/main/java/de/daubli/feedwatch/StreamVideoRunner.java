package de.daubli.feedwatch;

import de.daubli.feedwatch.stats.StreamStats;

public interface StreamVideoRunner {

    void start();

    void shutdown();

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
