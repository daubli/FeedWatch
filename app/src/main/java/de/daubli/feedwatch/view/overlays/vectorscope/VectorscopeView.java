package de.daubli.feedwatch.view.overlays.vectorscope;

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
 * Small vectorscope floating over the picture: every sample is plotted by its color difference, Cb to the right and
 * Cr upwards (Rec. 709), so the angle shows the hue and the distance from the middle the saturation. Targets mark the
 * 75 % color bars and a line the usual hue of skin.
 */
public class VectorscopeView extends FloatingScopeView {

    public static final float DEFAULT_X_FRACTION = 0f;

    public static final float DEFAULT_Y_FRACTION = 0.15f;

    public static final float DEFAULT_WIDTH_DP = 180f;

    // one cell per code value step of Cb and Cr
    private static final int GRID = 256;

    // how quickly a point gets bright with more samples on it
    private static final double INTENSITY_GAIN = 0.25;

    private static final int WHITE_COLOR = 0xF2F3F5;

    // the skin tone line, counterclockwise from the Cb axis
    private static final double SKIN_TONE_DEGREES = 123;

    private static final float BAR_LEVEL = 0.75f;

    // the 75 % bars: label, red, green, blue
    private static final String[] TARGET_LABELS = { "R", "MG", "B", "CY", "G", "YL" };

    private static final float[][] TARGET_RGB = {
            { 1, 0, 0 }, { 1, 0, 1 }, { 0, 0, 1 }, { 0, 1, 1 }, { 0, 1, 0 }, { 1, 1, 0 } };

    private final Paint graticulePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint skinTonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private final RectF plot = new RectF();

    private final RectF target = new RectF();

    private final float plotInset;

    private final float targetSize;

    private volatile VectorscopeMode mode = VectorscopeMode.COLOR;

    private volatile VectorscopeZoom zoom = VectorscopeZoom.NORMAL;

    // GL thread only
    private byte[] samples;

    private final int[] counts = new int[GRID * GRID];

    private int[] intensity;

    private int[] backPixels = new int[GRID * GRID];

    // the color each cell stands for, at full brightness
    private final int[] cellColors = new int[GRID * GRID];

    @Nullable
    private VectorscopeZoom cellColorsZoom;

    public VectorscopeView(Context context) {
        this(context, null);
    }

    public VectorscopeView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public VectorscopeView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr, 1f, DEFAULT_X_FRACTION, DEFAULT_Y_FRACTION, DEFAULT_WIDTH_DP);
        plotInset = dp(8);
        targetSize = dp(7);

        graticulePaint.setColor(0x40FFFFFF);
        graticulePaint.setStrokeWidth(1f);
        graticulePaint.setStyle(Paint.Style.STROKE);

        skinTonePaint.setColor(0x66F5A623);
        skinTonePaint.setStrokeWidth(dp(1));

        labelPaint.setColor(context.getColor(R.color.fw_text_secondary));
        labelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8,
                getResources().getDisplayMetrics()));
        labelPaint.setTypeface(ResourcesCompat.getFont(context, R.font.public_sans_semibold));
        labelPaint.setTextAlign(Paint.Align.CENTER);

        setContentDescription(context.getString(R.string.vectorscope_settings_title));
    }

    public void setMode(VectorscopeMode mode) {
        this.mode = mode;
    }

    public void setZoom(VectorscopeZoom zoom) {
        this.zoom = zoom;
        invalidate();
    }

    /**
     * Counts how many samples fall on each Cb/Cr cell and turns the counts into an image. Runs on the GL thread.
     */
    @Override
    public void onScopeSamples(ByteBuffer rgba, int sampleWidth, int sampleHeight) {
        int sampleBytes = sampleWidth * sampleHeight * 4;
        if (samples == null || samples.length != sampleBytes) {
            samples = new byte[sampleBytes];
            intensity = intensityTable(sampleWidth * sampleHeight, INTENSITY_GAIN);
        }
        rgba.get(samples);

        VectorscopeMode currentMode = mode;
        VectorscopeZoom currentZoom = zoom;
        int factor = currentZoom.getFactor();
        if (currentMode == VectorscopeMode.COLOR && cellColorsZoom != currentZoom) {
            fillCellColors(factor);
            cellColorsZoom = currentZoom;
        }

        Arrays.fill(counts, 0);
        for (int i = 0; i < sampleBytes; i += 4) {
            int r = samples[i] & 0xFF;
            int g = samples[i + 1] & 0xFF;
            int b = samples[i + 2] & 0xFF;
            // Rec. 709 color difference, scaled by 256: -0.5..0.5 of the code value range
            int cb = -29 * r - 99 * g + 128 * b;
            int cr = 128 * r - 116 * g - 12 * b;
            int x = GRID / 2 + ((cb * factor) >> 8);
            int y = GRID / 2 - ((cr * factor) >> 8);
            // zoomed in, strong colors fall off the edge
            if (x >= 0 && x < GRID && y >= 0 && y < GRID) {
                counts[y * GRID + x]++;
            }
        }

        for (int p = 0; p < counts.length; p++) {
            int a = intensity[counts[p]];
            backPixels[p] = a == 0 ? 0 : a << 24 | (currentMode == VectorscopeMode.COLOR ? cellColors[p] : WHITE_COLOR);
        }

        backPixels = publishPixels(backPixels, GRID, GRID);
    }

    /**
     * The hue of each cell at full brightness: the color with mid luma and that color difference, scaled up until
     * its strongest channel is full. Near the middle it turns white.
     */
    private void fillCellColors(int factor) {
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                float cb = (x + 0.5f - GRID / 2f) / (GRID - 1) / factor;
                float cr = (GRID / 2f - y - 0.5f) / (GRID - 1) / factor;
                float luma = 0.5f;
                float r = luma + 1.5748f * cr;
                float b = luma + 1.8556f * cb;
                float g = (luma - 0.2126f * r - 0.0722f * b) / 0.7152f;
                r = Math.max(0f, r);
                g = Math.max(0f, g);
                b = Math.max(0f, b);
                float max = Math.max(r, Math.max(g, b));
                if (max <= 0) {
                    cellColors[y * GRID + x] = WHITE_COLOR;
                    continue;
                }
                cellColors[y * GRID + x] = Math.round(r / max * 255) << 16 | Math.round(g / max * 255) << 8
                        | Math.round(b / max * 255);
            }
        }
    }

    @Override
    protected void onDrawScope(Canvas canvas, @Nullable Bitmap image) {
        float size = Math.min(getWidth(), getHeight()) - 2 * plotInset;
        if (size <= 0) {
            return;
        }
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float radius = size / 2f;
        plot.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        int factor = zoom.getFactor();

        canvas.drawCircle(centerX, centerY, radius, graticulePaint);
        canvas.drawLine(plot.left, centerY, plot.right, centerY, graticulePaint);
        canvas.drawLine(centerX, plot.top, centerX, plot.bottom, graticulePaint);

        double skinTone = Math.toRadians(SKIN_TONE_DEGREES);
        canvas.drawLine(centerX, centerY, centerX + (float) Math.cos(skinTone) * radius,
                centerY - (float) Math.sin(skinTone) * radius, skinTonePaint);

        float labelOffset = -(labelPaint.descent() + labelPaint.ascent()) / 2f;
        for (int i = 0; i < TARGET_RGB.length; i++) {
            float r = TARGET_RGB[i][0] * BAR_LEVEL;
            float g = TARGET_RGB[i][1] * BAR_LEVEL;
            float b = TARGET_RGB[i][2] * BAR_LEVEL;
            float cb = -0.1146f * r - 0.3854f * g + 0.5f * b;
            float cr = 0.5f * r - 0.4542f * g - 0.0458f * b;
            // the plot spans -0.5..0.5 of color difference
            float x = centerX + cb * factor * size;
            float y = centerY - cr * factor * size;
            if (Math.hypot(x - centerX, y - centerY) > radius) {
                continue;
            }
            float half = targetSize / 2f;
            target.set(x - half, y - half, x + half, y + half);
            canvas.drawRect(target, graticulePaint);
            // the label sits further out, away from the trace
            float distance = (float) Math.hypot(x - centerX, y - centerY);
            float labelX = x + (x - centerX) / distance * targetSize * 1.4f;
            float labelY = y + (y - centerY) / distance * targetSize * 1.4f;
            canvas.drawText(TARGET_LABELS[i], labelX, labelY + labelOffset, labelPaint);
        }

        if (image != null) {
            canvas.drawBitmap(image, null, plot, bitmapPaint);
        }
    }
}
