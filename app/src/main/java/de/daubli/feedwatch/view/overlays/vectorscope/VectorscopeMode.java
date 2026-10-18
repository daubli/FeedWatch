package de.daubli.feedwatch.view.overlays.vectorscope;

import androidx.annotation.StringRes;
import de.daubli.feedwatch.R;

/**
 * How the vectorscope draws its trace: in the color each point stands for, or white.
 */
public enum VectorscopeMode {

    COLOR(R.string.vectorscope_mode_color),
    WHITE(R.string.vectorscope_mode_white);

    @StringRes
    private final int label;

    VectorscopeMode(@StringRes int label) {
        this.label = label;
    }

    @StringRes
    public int getLabel() {
        return label;
    }
}
