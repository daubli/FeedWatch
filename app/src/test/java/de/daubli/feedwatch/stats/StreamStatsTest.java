package de.daubli.feedwatch.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.List;

import org.junit.Test;

public class StreamStatsTest {

    @Test
    public void durationShowsMinutesAndSeconds() {
        assertEquals("0:00", StreamStats.duration(0));
        assertEquals("0:59", StreamStats.duration(59_999));
        assertEquals("1:05", StreamStats.duration(65_000));
        assertEquals("59:59", StreamStats.duration(3_599_000));
    }

    @Test
    public void durationAddsHoursFromOneHour() {
        assertEquals("1:00:00", StreamStats.duration(3_600_000));
        assertEquals("2:03:04", StreamStats.duration((2 * 3600 + 3 * 60 + 4) * 1000L));
        assertEquals("100:00:00", StreamStats.duration(100 * 3_600_000L));
    }

    @Test
    public void frameRateIsUnknownWhileNegative() {
        assertEquals(StreamStats.UNKNOWN, StreamStats.frameRate(-1));
        assertEquals("0.0 fps", StreamStats.frameRate(0));
        assertEquals("30.0 fps", StreamStats.frameRate(29.97f));
        assertEquals("25.0 fps", StreamStats.frameRate(25));
        assertEquals("60.0 fps", StreamStats.frameRate(60));
    }

    @Test
    public void countUsesThousandsSeparators() {
        assertEquals("0", StreamStats.count(0));
        assertEquals("999", StreamStats.count(999));
        assertEquals("1,234,567", StreamStats.count(1_234_567));
    }

    @Test
    public void countOfAddsTheShareOfTheTotal() {
        assertEquals("12 (0.3%)", StreamStats.countOf(12, 4000));
        assertEquals("1,000 (100.0%)", StreamStats.countOf(1000, 1000));
        assertEquals("0 (0.0%)", StreamStats.countOf(0, 50));
    }

    @Test
    public void countOfLeavesOutTheShareWithoutATotal() {
        assertEquals("12", StreamStats.countOf(12, 0));
        assertEquals("12", StreamStats.countOf(12, -1));
    }

    @Test
    public void millisIsUnknownWhileNegative() {
        assertEquals(StreamStats.UNKNOWN, StreamStats.millis(-0.5f));
        assertEquals("16.7 ms", StreamStats.millis(16.67f));
    }

    @Test
    public void dataRateConvertsBytesToMegabits() {
        assertEquals(StreamStats.UNKNOWN, StreamStats.dataRate(-1));
        assertEquals("0.0 Mbit/s", StreamStats.dataRate(0));
        assertEquals("100.0 Mbit/s", StreamStats.dataRate(12_500_000));
    }

    @Test
    public void rowsKeepTheirOrder() {
        StreamStats stats = new StreamStats();

        assertSame(stats, stats.add(1, "a").add(2, "b"));

        List<StreamStats.Row> rows = stats.getRows();
        assertEquals(2, rows.size());
        assertEquals(1, rows.get(0).label);
        assertEquals("a", rows.get(0).value);
        assertEquals(2, rows.get(1).label);
        assertEquals("b", rows.get(1).value);
    }
}
