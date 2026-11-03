package de.daubli.feedwatch.view.overlays.focusassist;

import androidx.annotation.ColorInt;
import androidx.annotation.StringRes;
import de.daubli.feedwatch.R;

/**
 * Colors focus peaking can draw edges in. Offered so the edges stay visible on pictures that are mostly red,
 * yellow, green or blue themselves. White stands out best on the mono background.
 */
public enum FocusPeakingColor {

    RED(0xFFFF2D2D, R.string.color_red),
    YELLOW(0xFFFFE01A, R.string.color_yellow),
    GREEN(0xFF2DFF5A, R.string.color_green),
    BLUE(0xFF2D8CFF, R.string.color_blue),
    WHITE(0xFFFFFFFF, R.string.color_white);

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
