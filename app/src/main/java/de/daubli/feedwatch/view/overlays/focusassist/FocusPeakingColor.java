package de.daubli.feedwatch.view.overlays.focusassist;

import androidx.annotation.ColorInt;
import androidx.annotation.StringRes;
import de.daubli.feedwatch.R;

/**
 * Colors focus peaking can draw edges in. Offered so the edges stay visible on pictures that are mostly red,
 * yellow or blue themselves.
 */
public enum FocusPeakingColor {

    RED(0xFFFF2D2D, R.string.color_red),
    YELLOW(0xFFFFE01A, R.string.color_yellow),
    BLUE(0xFF2D8CFF, R.string.color_blue);

    @ColorInt
    private final int color;

    @StringRes
    private final int label;

    FocusPeakingColor(@ColorInt int color, @StringRes int label) {
        this.color = color;
        this.label = label;
    }

    @ColorInt
    public int getColor() {
        return color;
    }

    @StringRes
    public int getLabel() {
        return label;
    }
}
