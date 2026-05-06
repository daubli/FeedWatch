package de.daubli.feedwatch.view.overlays.framehelper; // Replace with your actual package name

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

public class FramingHelperOverlayView extends View {

    private Paint linePaint;

    private Rect visibleRect = null;

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
        linePaint.setColor(0x80FFFFFF); // semi-transparent white
        linePaint.setStrokeWidth(2f);
        linePaint.setAntiAlias(true);
    }

    /**
     * Set a specific area to draw the grid in (e.g. actual video bounds).
     */
    public void setFramingRect(Rect rect) {
        this.visibleRect = rect;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float left, top, right, bottom;

        if (visibleRect != null) {
            left = visibleRect.left;
            top = visibleRect.top;
            right = visibleRect.right;
            bottom = visibleRect.bottom;
        } else {
            left = 0;
            top = 0;
            right = getWidth();
            bottom = getHeight();
        }

        float width = right - left;
        float height = bottom - top;

        float thirdWidth = width / 3f;
        float thirdHeight = height / 3f;

        // Vertical lines
        canvas.drawLine(left + thirdWidth, top, left + thirdWidth, bottom, linePaint);
        canvas.drawLine(left + 2 * thirdWidth, top, left + 2 * thirdWidth, bottom, linePaint);

        // Horizontal lines
        canvas.drawLine(left, top + thirdHeight, right, top + thirdHeight, linePaint);
        canvas.drawLine(left, top + 2 * thirdHeight, right, top + 2 * thirdHeight, linePaint);
    }
}
