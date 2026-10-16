package de.daubli.feedwatch;

public interface StreamVideoRunner {

    void start();

    void shutdown();

    /**
     * Switches audio playback on or off, for sources that have audio. Called on the main thread.
     */
    default void setAudioEnabled(boolean enabled) {
    }
}
