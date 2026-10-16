package de.daubli.feedwatch.ndi;

import java.util.Arrays;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NdiFinder {

    private static final Logger LOG = Logger.getLogger(NdiFinder.class.getSimpleName());

    // how long a recreated finder may take to find the sources again
    private static final long REDISCOVERY_TIMEOUT_MS = 3000;

    private final boolean showLocalSources;

    private final String groups;

    private String extraIps;

    private long instancePointer;

    private NdiSource[] previouslyQueriedSources;

    public NdiFinder(boolean showLocalSources, String groups, String extraIps) {
        this.showLocalSources = showLocalSources;
        this.groups = groups;
        this.extraIps = extraIps;
        initNewFinderInstance();
    }

    public void initNewFinderInstance() {
        this.instancePointer = findCreate(showLocalSources, groups, extraIps);
    }

    /**
     * Searches the given additional IP addresses from now on. The native finder only takes them when it is created,
     * so it is created again when they changed. Blocks until it found the known sources again, call it from the scan
     * thread.
     */
    public synchronized void setExtraIps(String extraIps) {
        if (Objects.equals(this.extraIps, extraIps)) {
            return;
        }
        this.extraIps = extraIps;
        LOG.info("Additional sources changed, searching " + extraIps);

        // the queried sources stay, a source opened from the list is resolved from them until the next query
        if (instancePointer != 0) {
            findDestroy(instancePointer);
        }
        initNewFinderInstance();
        awaitRediscovery();
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
            return currentNdiSources;

        } catch (RuntimeException rte) {
            LOG.log(Level.WARNING, rte.getMessage(), rte);
            return new NdiSource[] {};
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
