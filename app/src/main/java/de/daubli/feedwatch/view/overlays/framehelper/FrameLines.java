package de.daubli.feedwatch.view.overlays.framehelper;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import de.daubli.feedwatch.R;

/**
 * Frame lines for a delivery aspect ratio, the picture outside of them is darkened.
 */
public enum FrameLines {

    NONE(0f, R.string.framing_off, R.drawable.ic_guide_off),
    SCOPE(2.39f, R.string.framing_lines_scope, R.drawable.ic_frame_scope),
    FOUR_THREE(4f / 3f, R.string.framing_lines_four_three, R.drawable.ic_frame_four_three),
    VERTICAL(9f / 16f, R.string.framing_lines_vertical, R.drawable.ic_frame_vertical);

    // width / height, 0 for none
    private final float aspectRatio;

    @StringRes
    private final int label;

    @DrawableRes
    private final int icon;

    FrameLines(float aspectRatio, @StringRes int label, @DrawableRes int icon) {
        this.aspectRatio = aspectRatio;
        this.label = label;
        this.icon = icon;
    }

    public float getAspectRatio() {
        return aspectRatio;
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
