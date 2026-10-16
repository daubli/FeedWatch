package de.daubli.feedwatch.view.overlays.framehelper;

import androidx.annotation.ColorInt;
import androidx.annotation.StringRes;
import de.daubli.feedwatch.R;

/**
 * Colors the framing guides can be drawn in, so they stay visible on bright or busy pictures.
 */
public enum GuideColor {

    WHITE(0xFFFFFFFF, R.string.color_white),
    YELLOW(0xFFFFE01A, R.string.color_yellow),
    RED(0xFFFF2D2D, R.string.color_red);

    @ColorInt
    private final int color;

    @StringRes
    private final int label;

    GuideColor(@ColorInt int color, @StringRes int label) {
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
