package de.daubli.feedwatch.view.overlays.framehelper;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.VisibleForTesting;

/**
 * Framing guides over the video: a composition grid, frame lines for an aspect ratio and a safe area. With frame
 * lines, the grid and the safe area are drawn inside them, in the part of the picture that is delivered.
 */
public class FramingHelperOverlayView extends View {

    // guides are drawn slightly see-through, so they do not hide the picture
    private static final int GUIDE_ALPHA = 0xCC;

    private static final float GUIDE_WIDTH_DP = 1.5f;

    // arm length of the center cross, as a share of the shorter frame side
    private static final float CENTER_CROSS_SIZE = 0.04f;

    private Paint linePaint;

    private Paint maskPaint;

    private Rect visibleRect = null;

    private final RectF videoBounds = new RectF();

    private final RectF frameBounds = new RectF();

    private FramingGrid grid = FramingGrid.THIRDS;

    private FrameLines frameLines = FrameLines.NONE;

    private SafeArea safeArea = SafeArea.NONE;

    public FramingHelperOverlayView(Context context) {
        super(context);
        init();
    }

    public FramingHelperOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public FramingHelperOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        linePaint = new Paint();
        linePaint.setColor((GuideColor.WHITE.getColor() & 0x00FFFFFF) | (GUIDE_ALPHA << 24));
        linePaint.setStrokeWidth(GUIDE_WIDTH_DP * getResources().getDisplayMetrics().density);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setAntiAlias(true);

        maskPaint = new Paint();
        maskPaint.setColor(0x99000000);
        maskPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * Set a specific area to draw the grid in (e.g. actual video bounds).
     */
    public void setFramingRect(Rect rect) {
        this.visibleRect = rect;
        invalidate();
    }

    public void setGuides(FramingGrid grid, FrameLines frameLines, SafeArea safeArea, GuideColor color) {
        this.grid = grid;
        this.frameLines = frameLines;
        this.safeArea = safeArea;
        linePaint.setColor((color.getColor() & 0x00FFFFFF) | (GUIDE_ALPHA << 24));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (visibleRect != null) {
            videoBounds.set(visibleRect);
        } else {
            videoBounds.set(0, 0, getWidth(), getHeight());
        }
        if (videoBounds.isEmpty()) {
            return;
        }

        frameBounds.set(videoBounds);
        if (frameLines != FrameLines.NONE) {
            fitAspectRatio(videoBounds, frameLines.getAspectRatio(), frameBounds);
            drawMask(canvas);
            canvas.drawRect(frameBounds, linePaint);
        }

        drawGrid(canvas);

        if (safeArea != SafeArea.NONE) {
            float insetX = frameBounds.width() * (1f - safeArea.getSize()) / 2f;
            float insetY = frameBounds.height() * (1f - safeArea.getSize()) / 2f;
            canvas.drawRect(frameBounds.left + insetX, frameBounds.top + insetY, frameBounds.right - insetX,
                    frameBounds.bottom - insetY, linePaint);
        }
    }

    /**
     * The largest rect with the given aspect ratio, centered in the bounds.
     */
    @VisibleForTesting
    static void fitAspectRatio(RectF bounds, float aspectRatio, RectF out) {
        float width = bounds.width();
        float height = width / aspectRatio;
        if (height > bounds.height()) {
            height = bounds.height();
            width = height * aspectRatio;
        }
        float left = bounds.centerX() - width / 2f;
        float top = bounds.centerY() - height / 2f;
        out.set(left, top, left + width, top + height);
    }

    private void drawMask(Canvas canvas) {
        RectF v = videoBounds;
        RectF f = frameBounds;
        canvas.drawRect(v.left, v.top, v.right, f.top, maskPaint);
        canvas.drawRect(v.left, f.bottom, v.right, v.bottom, maskPaint);
        canvas.drawRect(v.left, f.top, f.left, f.bottom, maskPaint);
        canvas.drawRect(f.right, f.top, v.right, f.bottom, maskPaint);
    }

    private void drawGrid(Canvas canvas) {
        switch (grid) {
            case THIRDS:
                drawLinesAt(canvas, 1f / 3f, 2f / 3f);
                break;
            case CENTER:
                float arm = Math.min(frameBounds.width(), frameBounds.height()) * CENTER_CROSS_SIZE;
                float cx = frameBounds.centerX();
                float cy = frameBounds.centerY();
                canvas.drawLine(cx - arm, cy, cx + arm, cy, linePaint);
                canvas.drawLine(cx, cy - arm, cx, cy + arm, linePaint);
                break;
            case NONE:
            default:
                break;
        }
    }

    /**
     * Draws a vertical and a horizontal line at each of the given shares of the frame.
     */
    private void drawLinesAt(Canvas canvas, float... positions) {
        RectF f = frameBounds;
        for (float position : positions) {
            float x = f.left + f.width() * position;
            float y = f.top + f.height() * position;
            canvas.drawLine(x, f.top, x, f.bottom, linePaint);
            canvas.drawLine(f.left, y, f.right, y, linePaint);
        }
    }
}
