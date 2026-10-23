package de.daubli.feedwatch.stats;

import static org.junit.Assert.assertEquals;

import java.time.Duration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
public class RateMeterTest {

    private static final float DELTA = 0.01f;

    private final RateMeter meter = new RateMeter();

    @Test
    public void rateIsUnknownUntilAWholeWindowWasMeasured() {
        assertEquals(-1f, meter.update(0), DELTA);

        advance(500);
        assertEquals(-1f, meter.update(15), DELTA);
    }

    @Test
    public void rateIsTheCountPerSecondOverTheWindow() {
        meter.update(100);

        advance(1000);
        assertEquals(30f, meter.update(130), DELTA);
    }

    @Test
    public void rateUsesTheTimeActuallyElapsed() {
        meter.update(0);

        advance(2000);
        assertEquals(25f, meter.update(50), DELTA);
    }

    @Test
    public void rateIsKeptWithinTheNextWindow() {
        meter.update(0);
        advance(1000);
        meter.update(60);

        advance(400);
        assertEquals(60f, meter.update(80), DELTA);

        // the new window started at the last measurement, not at the update in between
        advance(600);
        assertEquals(40f, meter.update(100), DELTA);
    }

    @Test
    public void staleWindowStartsOver() {
        meter.update(0);
        advance(1000);
        meter.update(30);

        advance(3500);
        assertEquals(-1f, meter.update(200), DELTA);

        advance(1000);
        assertEquals(25f, meter.update(225), DELTA);
    }

    @Test
    public void countGoingBackStartsOver() {
        meter.update(0);
        advance(1000);
        meter.update(500);

        // e.g. the stream was reconnected and its counters reset
        advance(1000);
        assertEquals(-1f, meter.update(10), DELTA);

        advance(1000);
        assertEquals(20f, meter.update(30), DELTA);
    }

    private static void advance(long millis) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(millis));
    }
}
