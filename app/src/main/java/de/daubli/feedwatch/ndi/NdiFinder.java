package de.daubli.feedwatch.ndi;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NdiFinder {

    private static final Logger LOG = Logger.getLogger(NdiFinder.class.getSimpleName());

    // how long a recreated finder may take to find the sources again
    private static final long REDISCOVERY_TIMEOUT_MS = 3000;

    private final boolean showLocalSources;

    private final String groups;

    // the additional addresses from the settings
    private String extraIps;

    // Machines that sent sources found before. mDNS can lose sources for good, e.g. when its packets get lost on
    // the sender's Wi-Fi and the queries for them have backed off; a finder also asks these machines directly.
    private final Set<String> knownHosts = new TreeSet<>();

    // what the native finder asks directly: the additional addresses and the known machines
    private String searchedIps;

    private long instancePointer;

    private NdiSource[] previouslyQueriedSources;

    public NdiFinder(boolean showLocalSources, String groups, String extraIps) {
        this.showLocalSources = showLocalSources;
        this.groups = groups;
        this.extraIps = extraIps;
        this.searchedIps = extraIps;
        initNewFinderInstance();
    }

    public void initNewFinderInstance() {
        this.instancePointer = findCreate(showLocalSources, groups, searchedIps);
    }

    /**
     * Searches the given additional IP addresses from now on. The native finder only takes them when it is created,
     * so it is created again when they changed. Blocks until it found the known sources again, call it from the scan
     * thread.
     */
    public synchronized void setExtraIps(String extraIps) {
        // a source found behind a removed address should go, not be kept as a known machine
        Set<String> removed = splitIps(this.extraIps);
        removed.removeAll(splitIps(extraIps));
        knownHosts.removeAll(removed);

        this.extraIps = extraIps;
        searchIps();
    }

    /**
     * Creates the native finder again when the addresses it should ask directly changed.
     */
    private void searchIps() {
        String ips = combineIps(extraIps, knownHosts);
        if (Objects.equals(searchedIps, ips)) {
            return;
        }
        searchedIps = ips;
        LOG.info("Addresses to search changed, searching " + ips);

        // the queried sources stay, a source opened from the list is resolved from them until the next query
        if (instancePointer != 0) {
            findDestroy(instancePointer);
        }
        initNewFinderInstance();
        awaitRediscovery();
    }

    /**
     * @return the additional addresses followed by the hosts not among them, comma separated, or null if there are
     *         none
     */
    static String combineIps(String extraIps, Collection<String> hosts) {
        Set<String> ips = splitIps(extraIps);
        ips.addAll(hosts);
        return ips.isEmpty() ? null : String.join(",", ips);
    }

    private static Set<String> splitIps(String ips) {
        Set<String> split = new LinkedHashSet<>();
        if (ips != null) {
            for (String ip : ips.split(",")) {
                if (!ip.trim().isEmpty()) {
                    split.add(ip.trim());
                }
            }
        }
        return split;
    }

    /**
     * Waits until the recreated finder found as many sources as were known before, so the source list does not
     * empty for a moment. Gives up after a while, a source behind a removed address is not found again.
     */
    private void awaitRediscovery() {
        int knownSources = previouslyQueriedSources != null ? previouslyQueriedSources.length : 0;
        long deadline = System.currentTimeMillis() + REDISCOVERY_TIMEOUT_MS;

        long remaining;
        while (countCurrentSources() < knownSources && (remaining = deadline - System.currentTimeMillis()) > 0) {
            findWaitForSources(instancePointer, (int) remaining);
        }
    }

    private int countCurrentSources() {
        String[] packed = findGetCurrentSources(instancePointer);
        return packed != null ? packed.length / 2 : 0;
    }

    public synchronized void waitForSources(int timeout) {
        if (isInstanceUnhealthy()) {
            initNewFinderInstance();
        }
        findWaitForSources(instancePointer, timeout);
    }

    public NdiSource getFromQueriedSources(String name) throws IllegalArgumentException {
        if (previouslyQueriedSources == null) {
            throw new IllegalArgumentException("No previously queried sources available");
        }
        return Arrays.stream(previouslyQueriedSources).filter(ndiSource -> name.equals(ndiSource.getSourceName()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Source not found: " + name));
    }

    public synchronized NdiSource[] getCurrentSources() {
        if (isInstanceUnhealthy()) {
            initNewFinderInstance();
        }

        // IMPORTANT: no native pointers anymore -> no need to close/dealloc per refresh
        // previouslyQueriedSources can just be replaced

        try {
            // packed = [name0, url0, name1, url1, ...]
            String[] packed = findGetCurrentSources(instancePointer);

            if (packed == null || packed.length == 0) {
                this.previouslyQueriedSources = new NdiSource[] {};
                return this.previouslyQueriedSources;
            }

            if ((packed.length & 1) != 0) {
                throw new RuntimeException(
                        "Native returned malformed packed sources array (odd length): " + packed.length);
            }

            int count = packed.length / 2;
            NdiSource[] currentNdiSources = new NdiSource[count];

            for (int i = 0; i < count; i++) {
                String name = packed[2 * i];
                String url = packed[2 * i + 1];
                currentNdiSources[i] = new NdiSource(name, url);
            }

            this.previouslyQueriedSources = currentNdiSources;
            rememberHosts(currentNdiSources);
            return currentNdiSources;

        } catch (RuntimeException rte) {
            LOG.log(Level.WARNING, rte.getMessage(), rte);
            return new NdiSource[] {};
        }
    }

    private void rememberHosts(NdiSource[] sources) {
        boolean added = false;
        for (NdiSource source : sources) {
            String host = source.getHost();
            if (host != null) {
                added |= knownHosts.add(host);
            }
        }
        if (added) {
            searchIps();
        }
    }

    public synchronized void close() {
        // Nothing per-source to close anymore
        previouslyQueriedSources = null;

        if (instancePointer != 0) {
            findDestroy(instancePointer);
            instancePointer = 0;
        }
    }

    private boolean isInstanceUnhealthy() {
        return instancePointer == 0;
    }

    // Native methods
    private native void findDestroy(long finderPtr);

    private native boolean findWaitForSources(long finderPtr, int timeout);

    private native long findCreate(boolean showLocalSources, String groups, String extraIps);

    // CHANGED: was long[]
    private native String[] findGetCurrentSources(long finderPtr);
}
