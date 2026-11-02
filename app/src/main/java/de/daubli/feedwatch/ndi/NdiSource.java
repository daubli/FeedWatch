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

    @Override
    public String getStableKey() {
        return "ndi:" + name;
    }

    public String getUrlAddress() {
        return urlAddress;
    }

    /**
     * @return the address of the machine sending the source, without the port, or null if it is not known
     */
    public String getHost() {
        if (urlAddress.startsWith("[")) {
            // IPv6: [address]:port
            int end = urlAddress.indexOf(']');
            return end > 1 ? urlAddress.substring(1, end) : null;
        }
        int port = urlAddress.lastIndexOf(':');
        String host = port >= 0 ? urlAddress.substring(0, port) : urlAddress;
        return host.isEmpty() ? null : host;
    }

    // No native close needed anymore
    public void close() {
        // no-op
    }
}
