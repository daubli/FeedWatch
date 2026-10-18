package de.daubli.feedwatch.view.overlays.waveform;

import java.nio.ByteBuffer;
import java.util.Arrays;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import de.daubli.feedwatch.R;
import de.daubli.feedwatch.view.overlays.scope.FloatingScopeView;

/**
 * Small waveform monitor floating over the picture: one column per column of the picture, one row per code value.
 */
public class WaveformMonitorView extends FloatingScopeView {

    public static final float DEFAULT_X_FRACTION = 1f;

    public static final float DEFAULT_Y_FRACTION = 0.15f;

    public static final float DEFAULT_WIDTH_DP = 260f;

    // height / width
    private static final float ASPECT = 0.6f;

    // 8 bit code values, one row each
    private static final int LEVELS = 256;

    // how quickly a waveform point gets bright with more samples on it
    private static final double INTENSITY_GAIN = 0.35;

    private static final int LUMA_COLOR = 0xF2F3F5;

    private static final int[] GRATICULE_PERCENT = { 0, 25, 50, 75, 100 };

    private final Paint graticulePaint = new Paint();

    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private final RectF plot = new RectF();

    private final float labelGutter;

    private final float plotInset;

    private volatile WaveformMode mode = WaveformMode.LUMA;

    // GL thread only
    private byte[] samples;

    private int[] counts;

    private int[] intensity;

    private int[] backPixels;

    public WaveformMonitorView(Context context) {
        this(context, null);
    }

    public WaveformMonitorView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WaveformMonitorView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr, ASPECT, DEFAULT_X_FRACTION, DEFAULT_Y_FRACTION, DEFAULT_WIDTH_DP);
        labelGutter = dp(20);
        plotInset = dp(6);

        graticulePaint.setColor(0x40FFFFFF);
        graticulePaint.setStrokeWidth(1f);

        labelPaint.setColor(context.getColor(R.color.fw_text_secondary));
        labelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8,
                getResources().getDisplayMetrics()));
        labelPaint.setTypeface(ResourcesCompat.getFont(context, R.font.public_sans_semibold));
        labelPaint.setTextAlign(Paint.Align.RIGHT);

        setContentDescription(context.getString(R.string.waveform_settings_title));
    }

    public void setMode(WaveformMode mode) {
        this.mode = mode;
    }

    /**
     * Counts how many samples of each column have each code value and turns the counts into an image, one column
     * per sample column and one row per code value. Runs on the GL thread.
     */
    @Override
    public void onScopeSamples(ByteBuffer rgba, int sampleWidth, int sampleHeight) {
        int sampleBytes = sampleWidth * sampleHeight * 4;
        int cells = sampleWidth * LEVELS;
        if (samples == null || samples.length != sampleBytes) {
            samples = new byte[sampleBytes];
            counts = new int[cells * 3];
            backPixels = new int[cells];
            intensity = intensityTable(sampleHeight, INTENSITY_GAIN);
        }
        rgba.get(samples);

        WaveformMode currentMode = mode;
        Arrays.fill(counts, 0);
        for (int row = 0; row < sampleHeight; row++) {
            int rowStart = row * sampleWidth * 4;
            for (int x = 0; x < sampleWidth; x++) {
                int i = rowStart + x * 4;
                int r = samples[i] & 0xFF;
                int g = samples[i + 1] & 0xFF;
                int b = samples[i + 2] & 0xFF;
                // row 0 of the waveform is the top, the highest code value
                if (currentMode == WaveformMode.LUMA) {
                    // Rec. 709 weights, scaled to 256
                    int luma = (54 * r + 183 * g + 19 * b) >> 8;
                    counts[(LEVELS - 1 - luma) * sampleWidth + x]++;
                } else {
                    counts[(LEVELS - 1 - r) * sampleWidth + x]++;
                    counts[cells + (LEVELS - 1 - g) * sampleWidth + x]++;
                    counts[2 * cells + (LEVELS - 1 - b) * sampleWidth + x]++;
                }
            }
        }

        for (int p = 0; p < cells; p++) {
            if (currentMode == WaveformMode.LUMA) {
                int a = intensity[counts[p]];
                backPixels[p] = a << 24 | LUMA_COLOR;
            } else {
                backPixels[p] = rgbPixel(intensity[counts[p]], intensity[counts[cells + p]],
                        intensity[counts[2 * cells + p]]);
            }
        }

        backPixels = publishPixels(backPixels, sampleWidth, LEVELS);
    }

    /**
     * Adds up the red, green and blue traces, each slightly tinted so blue stays readable on black, where they
     * overlap they turn white. The color is stored at full strength with the brightness as alpha.
     */
    private static int rgbPixel(int red, int green, int blue) {
        int r = Math.min(255, red + (green * 40 + blue * 60) / 255);
        int g = Math.min(255, green + (red * 40 + blue * 110) / 255);
        int b = Math.min(255, blue + (red * 40 + green * 40) / 255);
        int max = Math.max(r, Math.max(g, b));
        if (max == 0) {
            return 0;
        }
        return max << 24 | (r * 255 / max) << 16 | (g * 255 / max) << 8 | (b * 255 / max);
    }

    @Override
    protected void onDrawScope(Canvas canvas, @Nullable Bitmap image) {
        plot.set(labelGutter, plotInset, getWidth() - plotInset, getHeight() - plotInset);
        if (plot.width() <= 0 || plot.height() <= 0) {
            return;
        }

        float labelOffset = -(labelPaint.descent() + labelPaint.ascent()) / 2f;
        for (int percent : GRATICULE_PERCENT) {
            float y = plot.bottom - percent / 100f * plot.height();
            canvas.drawLine(plot.left, y, plot.right, y, graticulePaint);
            if (percent % 50 == 0) {
                canvas.drawText(Integer.toString(percent), labelGutter - dp(4), y + labelOffset, labelPaint);
            }
        }

        if (image != null) {
            canvas.drawBitmap(image, null, plot, bitmapPaint);
        }
    }
}
