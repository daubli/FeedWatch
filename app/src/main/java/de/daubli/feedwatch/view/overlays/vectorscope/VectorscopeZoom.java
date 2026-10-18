package de.daubli.feedwatch.view.overlays.vectorscope;

import androidx.annotation.StringRes;
import de.daubli.feedwatch.R;

/**
 * How far the vectorscope enlarges the color difference: twice shows the low saturation of skin and log footage
 * better, strong colors then fall off the edge.
 */
public enum VectorscopeZoom {

    NORMAL(1, R.string.vectorscope_zoom_normal),
    DOUBLE(2, R.string.vectorscope_zoom_double);

    private final int factor;

    @StringRes
    private final int label;

    VectorscopeZoom(int factor, @StringRes int label) {
        this.factor = factor;
        this.label = label;
    }

    public int getFactor() {
        return factor;
    }

    @StringRes
    public int getLabel() {
        return label;
    }
}
