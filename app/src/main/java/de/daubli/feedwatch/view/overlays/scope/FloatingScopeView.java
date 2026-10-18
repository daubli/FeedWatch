package de.daubli.feedwatch.view.overlays.scope;

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

/**
 * Small scope floating over the picture, like the waveform monitor or the vectorscope. It can be dragged with one
 * finger and resized by pinching with two, a tap is passed on as a click. Its place is kept as fractions of the free
 * space, so it stays in the same corner when the screen size changes.
 * <p>
 * Subclasses compute their image from the {@link ScopeCapture} samples on the GL thread, hand it over with
 * {@link #publishPixels} and draw it in {@link #onDrawScope}.
 * <p>
 * Must be a child of a {@link FrameLayout}, it positions itself with margins.
 */
public abstract class FloatingScopeView extends View implements ScopeCapture.Listener {

    public interface OnPlacementChangedListener {

        /**
         * Called once a drag or pinch ends.
         */
        void onPlacementChanged(float xFraction, float yFraction, float widthDp);
    }

    private static final float MIN_WIDTH_DP = 140f;

    private static final float EDGE_MARGIN_DP = 8f;

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF bounds = new RectF();

    // height / width
    private final float aspect;

    private final float cornerRadius;

    private final float edgeMargin;

    private final float touchSlop;

    private final ScaleGestureDetector scaleDetector;

    // shared between the GL and UI thread, guarded by pixelLock
    private final Object pixelLock = new Object();

    private int[] pendingPixels;

    private int pendingWidth;

    private int pendingHeight;

    private boolean pixelsChanged;

    @Nullable
    private Bitmap scope;

    // placement, as saved
    private float xFraction;

    private float yFraction;

    private float widthDp;

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

    /**
     * @param aspect height / width
     */
    protected FloatingScopeView(Context context, @Nullable AttributeSet attrs, int defStyleAttr, float aspect,
            float defaultXFraction, float defaultYFraction, float defaultWidthDp) {
        super(context, attrs, defStyleAttr);
        this.aspect = aspect;
        this.xFraction = defaultXFraction;
        this.yFraction = defaultYFraction;
        this.widthDp = defaultWidthDp;
        cornerRadius = dp(6);
        edgeMargin = dp(EDGE_MARGIN_DP);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        backgroundPaint.setColor(0xB3000000);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleBy(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        // a double tap and drag would fight with moving the scope
        scaleDetector.setQuickScaleEnabled(false);
    }

    public void setOnPlacementChangedListener(@Nullable OnPlacementChangedListener listener) {
        this.placementListener = listener;
    }

    /**
     * Places the scope in the free space of its parent: 0 is the left or top edge, 1 the right or bottom one.
     */
    public void setPlacement(float xFraction, float yFraction, float widthDp) {
        this.xFraction = clamp(xFraction, 0f, 1f);
        this.yFraction = clamp(yFraction, 0f, 1f);
        this.widthDp = widthDp;
        applyPlacement();
    }

    // ------------------------------------------------------------
    // Image
    // ------------------------------------------------------------

    /**
     * Hands a finished image to the UI thread. Called on the GL thread.
     *
     * @return an array of the same size to draw the next image into
     */
    protected int[] publishPixels(int[] pixels, int imageWidth, int imageHeight) {
        int[] next;
        synchronized (pixelLock) {
            int[] swap = pendingPixels;
            pendingPixels = pixels;
            pendingWidth = imageWidth;
            pendingHeight = imageHeight;
            pixelsChanged = true;
            next = swap != null && swap.length == pixels.length ? swap : new int[pixels.length];
        }
        postInvalidateOnAnimation();
        return next;
    }

    /**
     * Draws the scope's graticule and image into the view, over its background. Called on the UI thread.
     *
     * @param image the latest published image, null before the first one
     */
    protected abstract void onDrawScope(Canvas canvas, @Nullable Bitmap image);

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        bounds.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, backgroundPaint);

        synchronized (pixelLock) {
            if (pixelsChanged) {
                pixelsChanged = false;
                if (scope == null || scope.getWidth() != pendingWidth || scope.getHeight() != pendingHeight) {
                    scope = Bitmap.createBitmap(pendingWidth, pendingHeight, Bitmap.Config.ARGB_8888);
                }
                scope.setPixels(pendingPixels, 0, pendingWidth, 0, 0, pendingWidth, pendingHeight);
            }
        }
        onDrawScope(canvas, scope);
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

        // the focus is the middle of all fingers still down, so lifting one of two does not make the scope jump
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
        return parent.getHeight() - 2 * edgeMargin - width * aspect;
    }

    private float clampWidth(float requested) {
        View parent = (View) getParent();
        if (parent == null || parent.getWidth() == 0) {
            return requested;
        }
        float max = Math.min(parent.getWidth() - 2 * edgeMargin, (parent.getHeight() - 2 * edgeMargin) / aspect);
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
        params.height = Math.round(width * aspect);
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

    /**
     * Brightness 0..255 for each number of samples on one point: a single sample is already visible, many saturate.
     */
    protected static int[] intensityTable(int maxCount, double gain) {
        int[] table = new int[maxCount + 1];
        for (int c = 1; c <= maxCount; c++) {
            table[c] = (int) Math.round(255 * (1 - Math.exp(-gain * c)));
        }
        return table;
    }

    protected static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    protected float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
