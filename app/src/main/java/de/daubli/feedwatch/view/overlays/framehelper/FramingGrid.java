package de.daubli.feedwatch.view.overlays.framehelper;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import de.daubli.feedwatch.R;

/**
 * Composition guides drawn over the frame.
 */
public enum FramingGrid {

    NONE(R.string.framing_off, R.drawable.ic_guide_off),
    THIRDS(R.string.framing_grid_thirds, R.drawable.ic_guide_thirds),
    CENTER(R.string.framing_grid_center, R.drawable.ic_guide_center);

    @StringRes
    private final int label;

    @DrawableRes
    private final int icon;

    FramingGrid(@StringRes int label, @DrawableRes int icon) {
        this.label = label;
        this.icon = icon;
    }

    @StringRes
    public int getLabel() {
        return label;
    }

    @DrawableRes
    public int getIcon() {
        return icon;
    }
}
