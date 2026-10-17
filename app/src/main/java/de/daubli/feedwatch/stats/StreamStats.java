package de.daubli.feedwatch.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import androidx.annotation.StringRes;

/**
 * Rows of the stream statistics popup, a label and its current value each. Filled by the stream runner on the main
 * thread.
 */
public final class StreamStats {

    // shown while a value is not known yet
    public static final String UNKNOWN = "–";

    public static final class Row {

        @StringRes
        public final int label;

        public final String value;

        Row(@StringRes int label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    private final List<Row> rows = new ArrayList<>();

    public StreamStats add(@StringRes int label, String value) {
        rows.add(new Row(label, value));
        return this;
    }

    public List<Row> getRows() {
        return rows;
    }

    public static String duration(long millis) {
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60);
        }
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    /**
     * @param frameRate frames per second, negative while unknown
     */
    public static String frameRate(float frameRate) {
        return frameRate < 0 ? UNKNOWN : String.format(Locale.US, "%.1f fps", frameRate);
    }

    public static String count(long count) {
        return String.format(Locale.US, "%,d", count);
    }

    /**
     * @return the count with its share of the total, e.g. "12 (0.3%)"
     */
    public static String countOf(long count, long total) {
        if (total <= 0) {
            return count(count);
        }
        return String.format(Locale.US, "%,d (%.1f%%)", count, count * 100f / total);
    }

    public static String millis(float millis) {
        return millis < 0 ? UNKNOWN : String.format(Locale.US, "%.1f ms", millis);
    }

    /**
     * @param bytesPerSecond negative while unknown
     */
    public static String dataRate(float bytesPerSecond) {
        return bytesPerSecond < 0 ? UNKNOWN : String.format(Locale.US, "%.1f Mbit/s", bytesPerSecond * 8 / 1_000_000f);
    }
}
