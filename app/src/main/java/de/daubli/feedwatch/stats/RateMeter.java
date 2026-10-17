package de.daubli.feedwatch.stats;

import android.os.SystemClock;

/**
 * Turns a growing count into a rate per second, averaged over about a second. Not thread-safe, it is meant to be
 * updated each time the statistics are shown.
 */
public final class RateMeter {

    private static final long WINDOW_NANOS = 1_000_000_000L;

    // a window this old belongs to an earlier look at the statistics, its average would hide what happens now
    private static final long STALE_NANOS = 3 * WINDOW_NANOS;

    private long windowStartNanos = -1;

    private long windowStartCount;

    private float rate = -1;

    /**
     * @return the count per second, or -1 until a whole window was measured
     */
    public float update(long count) {
        long now = SystemClock.elapsedRealtimeNanos();
        long elapsed = now - windowStartNanos;

        if (windowStartNanos < 0 || elapsed > STALE_NANOS || count < windowStartCount) {
            windowStartNanos = now;
            windowStartCount = count;
            rate = -1;
        } else if (elapsed >= WINDOW_NANOS) {
            rate = (count - windowStartCount) * 1e9f / elapsed;
            windowStartNanos = now;
            windowStartCount = count;
        }
        return rate;
    }
}
