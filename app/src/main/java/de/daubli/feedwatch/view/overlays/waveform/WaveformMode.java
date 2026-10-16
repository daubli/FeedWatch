package de.daubli.feedwatch.view.overlays.waveform;

import androidx.annotation.StringRes;
import de.daubli.feedwatch.R;

/**
 * What the waveform monitor plots: the brightness of the picture, or its red, green and blue channels on top of each
 * other.
 */
public enum WaveformMode {

    LUMA(R.string.waveform_mode_luma),
    RGB(R.string.waveform_mode_rgb);

    @StringRes
    private final int label;

    WaveformMode(@StringRes int label) {
        this.label = label;
    }

    @StringRes
    public int getLabel() {
        return label;
    }
}
