package de.daubli.feedwatch.ndi;

import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NdiFinder {

    private static final Logger LOG = Logger.getLogger(NdiFinder.class.getSimpleName());

    private final boolean showLocalSources;

    private final String groups;

    private final String extraIps;

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
