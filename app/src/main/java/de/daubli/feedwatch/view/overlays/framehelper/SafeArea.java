package de.daubli.feedwatch.view.overlays.framehelper;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import de.daubli.feedwatch.R;

/**
 * Broadcast title safe area (EBU R 95): titles are kept inside 90 % of the frame.
 */
public enum SafeArea {

    NONE(1f, R.string.framing_off, R.drawable.ic_guide_off),
    TITLE(0.90f, R.string.framing_safe_title, R.drawable.ic_guide_safe_area);

    // share of the frame width and height inside the safe area
    private final float size;

    @StringRes
    private final int label;

    @DrawableRes
    private final int icon;

    SafeArea(float size, @StringRes int label, @DrawableRes int icon) {
        this.size = size;
        this.label = label;
        this.icon = icon;
    }

    public float getSize() {
        return size;
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
