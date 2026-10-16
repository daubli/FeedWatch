package de.daubli.feedwatch.sources;

public interface VideoSource {
    String getSourceName();

    /**
     * Identifies the source across scans and app starts, e.g. to remember it as the default source.
     */
    String getStableKey();
}
