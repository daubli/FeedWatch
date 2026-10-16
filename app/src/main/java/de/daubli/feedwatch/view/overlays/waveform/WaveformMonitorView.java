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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.FrameLayout;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import de.daubli.feedwatch.R;

/**
 * Small waveform monitor floating over the picture. It can be dragged with one finger and resized by pinching with
 * two, a tap is passed on as a click. Its place is kept as fractions of the free space, so it stays in the same
 * corner when the screen size changes.
 * <p>
 * Must be a child of a {@link FrameLayout}, it positions itself with margins.
 */
public class WaveformMonitorView extends View implements WaveformCapture.Listener {

    public interface OnPlacementChangedListener {

        /**
         * Called once a drag or pinch ends.
         */
        void onPlacementChanged(float xFraction, float yFraction, float widthDp);
    }

    public static final float DEFAULT_X_FRACTION = 1f;

    public static final float DEFAULT_Y_FRACTION = 0.15f;

    public static final float DEFAULT_WIDTH_DP = 260f;

    private static final float MIN_WIDTH_DP = 140f;

    // height / width
    private static final float ASPECT = 0.6f;

    private static final float EDGE_MARGIN_DP = 8f;

    // 8 bit code values, one row each
    private static final int LEVELS = 256;

    // how quickly a waveform point gets bright with more samples on it
    private static final double INTENSITY_GAIN = 0.35;

    private static final int LUMA_COLOR = 0xF2F3F5;

    private static final int[] GRATICULE_PERCENT = { 0, 25, 50, 75, 100 };

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint graticulePaint = new Paint();

    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private final RectF bounds = new RectF();

    private final RectF plot = new RectF();

    private final float cornerRadius;

    private final float labelGutter;

    private final float plotInset;

    private final float edgeMargin;

    private final float touchSlop;

    private final ScaleGestureDetector scaleDetector;

    private volatile WaveformMode mode = WaveformMode.LUMA;

    // GL thread only
    private byte[] samples;

    private int[] counts;

    private int[] intensity;

    private int[] backPixels;

    // shared between the GL and UI thread, guarded by pixelLock
    private final Object pixelLock = new Object();

    private int[] pendingPixels;

    private int pendingWidth;

    private boolean pixelsChanged;

    @Nullable
    private Bitmap waveform;

    // placement, as saved
    private float xFraction = DEFAULT_X_FRACTION;

    private float yFraction = DEFAULT_Y_FRACTION;

    private float widthDp = DEFAULT_WIDTH_DP;

    // placement in parent pixels
    private float left;

    private float top;

    private float width;

    @Nullable
    private OnPlacementChangedListener placementListener;

    private final OnLayoutChangeListener parentLayoutListener =
            (v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
                // listeners are called on every layout, the placement only depends on the size
                if (r - l != oldR - oldL || b - t != oldB - oldT) {
                    post(this::applyPlacement);
                }
            };

    private float lastFocusX;

    private float lastFocusY;

    private float downX;

    private float downY;

    private boolean moved;

    public WaveformMonitorView(Context context) {
        this(context, null);
    }

    public WaveformMonitorView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WaveformMonitorView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        cornerRadius = dp(6);
        labelGutter = dp(20);
        plotInset = dp(6);
        edgeMargin = dp(EDGE_MARGIN_DP);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        backgroundPaint.setColor(0xB3000000);

        graticulePaint.setColor(0x40FFFFFF);
        graticulePaint.setStrokeWidth(1f);

        labelPaint.setColor(context.getColor(R.color.fw_text_secondary));
        labelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8,
                getResources().getDisplayMetrics()));
        labelPaint.setTypeface(ResourcesCompat.getFont(context, R.font.public_sans_semibold));
        labelPaint.setTextAlign(Paint.Align.RIGHT);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleBy(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        // a double tap and drag would fight with moving the monitor
        scaleDetector.setQuickScaleEnabled(false);

        setContentDescription(context.getString(R.string.waveform_settings_title));
    }

    public void setMode(WaveformMode mode) {
        this.mode = mode;
    }

    public void setOnPlacementChangedListener(@Nullable OnPlacementChangedListener listener) {
        this.placementListener = listener;
    }

    /**
     * Places the monitor in the free space of its parent: 0 is the left or top edge, 1 the right or bottom one.
     */
    public void setPlacement(float xFraction, float yFraction, float widthDp) {
        this.xFraction = clamp(xFraction, 0f, 1f);
        this.yFraction = clamp(yFraction, 0f, 1f);
        this.widthDp = widthDp;
        applyPlacement();
    }

    // ------------------------------------------------------------
    // Waveform
    // ------------------------------------------------------------

    /**
     * Counts how many samples of each column have each code value and turns the counts into an image, one column
     * per sample column and one row per code value. Runs on the GL thread.
     */
    @Override
    public void onWaveformSamples(ByteBuffer rgba, int sampleWidth, int sampleHeight) {
        int sampleBytes = sampleWidth * sampleHeight * 4;
        int cells = sampleWidth * LEVELS;
        if (samples == null || samples.length != sampleBytes) {
            samples = new byte[sampleBytes];
            counts = new int[cells * 3];
            backPixels = new int[cells];
            intensity = intensityTable(sampleHeight);
        }
        rgba.get(samples);

        WaveformMode currentMode = mode;
        Arrays.fill(counts, 0);
        for (int i = 0, x = 0; i < sampleBytes; i += 4) {
            int r = samples[i] & 0xFF;
            int g = samples[i + 1] & 0xFF;
            int b = samples[i + 2] & 0xFF;
            // row 0 is the top, the highest code value
            if (currentMode == WaveformMode.LUMA) {
                // Rec. 709 weights, scaled to 256
                int luma = (54 * r + 183 * g + 19 * b) >> 8;
                counts[(LEVELS - 1 - luma) * sampleWidth + x]++;
            } else {
                counts[(LEVELS - 1 - r) * sampleWidth + x]++;
                counts[cells + (LEVELS - 1 - g) * sampleWidth + x]++;
                counts[2 * cells + (LEVELS - 1 - b) * sampleWidth + x]++;
            }
            if (++x == sampleWidth) {
                x = 0;
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

        synchronized (pixelLock) {
            int[] swap = pendingPixels;
            pendingPixels = backPixels;
            pendingWidth = sampleWidth;
            pixelsChanged = true;
            backPixels = swap != null && swap.length == cells ? swap : new int[cells];
        }
        postInvalidateOnAnimation();
    }

    /**
     * Brightness 0..255 for each number of samples on one point: a single sample is already visible, many saturate.
     */
    private static int[] intensityTable(int maxCount) {
        int[] table = new int[maxCount + 1];
        for (int c = 1; c <= maxCount; c++) {
            table[c] = (int) Math.round(255 * (1 - Math.exp(-INTENSITY_GAIN * c)));
        }
        return table;
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
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        bounds.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, backgroundPaint);

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

        synchronized (pixelLock) {
            if (pixelsChanged) {
                pixelsChanged = false;
                if (waveform == null || waveform.getWidth() != pendingWidth) {
                    waveform = Bitmap.createBitmap(pendingWidth, LEVELS, Bitmap.Config.ARGB_8888);
                }
                waveform.setPixels(pendingPixels, 0, pendingWidth, 0, 0, pendingWidth, LEVELS);
            }
        }
        if (waveform != null) {
            canvas.drawBitmap(waveform, null, plot, bitmapPaint);
        }
    }

    // ------------------------------------------------------------
    // Moving and resizing
    // ------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // work in parent coordinates, the view itself moves under the fingers
        MotionEvent parentEvent = MotionEvent.obtain(event);
        parentEvent.offsetLocation(getLeft(), getTop());
        scaleDetector.onTouchEvent(parentEvent);
        handleDrag(parentEvent);
        parentEvent.recycle();
        return true;
    }

    private void handleDrag(MotionEvent event) {
        int action = event.getActionMasked();

        // the focus is the middle of all fingers still down, so lifting one of two does not make the monitor jump
        int liftedIndex = action == MotionEvent.ACTION_POINTER_UP ? event.getActionIndex() : -1;
        float sumX = 0;
        float sumY = 0;
        int fingers = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (i != liftedIndex) {
                sumX += event.getX(i);
                sumY += event.getY(i);
                fingers++;
            }
        }
        if (fingers == 0) {
            return;
        }
        float focusX = sumX / fingers;
        float focusY = sumY / fingers;

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = focusX;
                downY = focusY;
                moved = false;
                ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                // a pinch is never a tap
                moved = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!moved && Math.hypot(focusX - downX, focusY - downY) > touchSlop) {
                    moved = true;
                }
                if (moved) {
                    moveBy(focusX - lastFocusX, focusY - lastFocusY);
                }
                break;
            case MotionEvent.ACTION_UP:
                if (moved) {
                    savePlacement();
                } else {
                    performClick();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (moved) {
                    savePlacement();
                }
                break;
            default:
                break;
        }
        lastFocusX = focusX;
        lastFocusY = focusY;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void moveBy(float dx, float dy) {
        left += dx;
        top += dy;
        clampAndLayout();
    }

    private void scaleBy(float factor, float focusX, float focusY) {
        float newWidth = clampWidth(width * factor);
        // keep the point between the fingers in place
        float applied = newWidth / width;
        left = focusX - (focusX - left) * applied;
        top = focusY - (focusY - top) * applied;
        width = newWidth;
        clampAndLayout();
    }

    private void applyPlacement() {
        View parent = (View) getParent();
        if (parent == null || parent.getWidth() == 0 || parent.getHeight() == 0) {
            return;
        }
        width = clampWidth(dp(widthDp));
        left = edgeMargin + xFraction * freeWidth(parent);
        top = edgeMargin + yFraction * freeHeight(parent);
        clampAndLayout();
    }

    private void savePlacement() {
        View parent = (View) getParent();
        if (parent == null) {
            return;
        }
        float freeWidth = freeWidth(parent);
        float freeHeight = freeHeight(parent);
        xFraction = freeWidth > 0 ? clamp((left - edgeMargin) / freeWidth, 0f, 1f) : 0f;
        yFraction = freeHeight > 0 ? clamp((top - edgeMargin) / freeHeight, 0f, 1f) : 0f;
        widthDp = width / getResources().getDisplayMetrics().density;
        if (placementListener != null) {
            placementListener.onPlacementChanged(xFraction, yFraction, widthDp);
        }
    }

    private float freeWidth(View parent) {
        return parent.getWidth() - 2 * edgeMargin - width;
    }

    private float freeHeight(View parent) {
        return parent.getHeight() - 2 * edgeMargin - width * ASPECT;
    }

    private float clampWidth(float requested) {
        View parent = (View) getParent();
        if (parent == null || parent.getWidth() == 0) {
            return requested;
        }
        float max = Math.min(parent.getWidth() - 2 * edgeMargin, (parent.getHeight() - 2 * edgeMargin) / ASPECT);
        float min = Math.min(dp(MIN_WIDTH_DP), max);
        return clamp(requested, min, max);
    }

    private void clampAndLayout() {
        View parent = (View) getParent();
        if (parent == null) {
            return;
        }
        left = clamp(left, edgeMargin, Math.max(edgeMargin, edgeMargin + freeWidth(parent)));
        top = clamp(top, edgeMargin, Math.max(edgeMargin, edgeMargin + freeHeight(parent)));

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) getLayoutParams();
        params.gravity = Gravity.TOP | Gravity.START;
        params.width = Math.round(width);
        params.height = Math.round(width * ASPECT);
        params.leftMargin = Math.round(left);
        params.topMargin = Math.round(top);
        setLayoutParams(params);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ((View) getParent()).addOnLayoutChangeListener(parentLayoutListener);
        applyPlacement();
    }

    @Override
    protected void onDetachedFromWindow() {
        ((View) getParent()).removeOnLayoutChangeListener(parentLayoutListener);
        super.onDetachedFromWindow();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
