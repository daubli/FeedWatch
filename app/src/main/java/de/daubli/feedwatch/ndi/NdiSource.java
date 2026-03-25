package de.daubli.feedwatch.ndi;

import de.daubli.feedwatch.sources.VideoSource;

public class NdiSource implements VideoSource {

    private final String name;

    private final String urlAddress;

    public NdiSource(String name, String urlAddress) {
        this.name = name == null ? "" : name;
        this.urlAddress = urlAddress == null ? "" : urlAddress;
    }

    @Override
    public String getSourceName() {
        return name;
    }

    public String getUrlAddress() {
        return urlAddress;
    }

    // No native close needed anymore
    public void close() {
        // no-op
    }
}
