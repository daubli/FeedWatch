package de.daubli.feedwatch.view.overlays.tally;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

public class TallyOverlayView extends View {

    private final Paint paint = new Paint();
    private boolean onProgram = false;
    private boolean onPreview = false;
    private boolean tallyEnabled = false;
    private Rect videoRect = null;

    private static final float BORDER_WIDTH = 12f;

    public TallyOverlayView(Context context) {
        super(context);
        init();
    }

    public TallyOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TallyOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(BORDER_WIDTH);
    }

    public void setTallyEnabled(boolean enabled) {
        this.tallyEnabled = enabled;
        invalidate();
    }

    public boolean isTallyEnabled() {
        return this.tallyEnabled;
    }

    public void updateTally(boolean onProgram, boolean onPreview) {
        this.onProgram = onProgram;
        this.onPreview = onPreview;
        invalidate();
    }

    public void setVideoRect(Rect rect) {
        this.videoRect = rect;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (!tallyEnabled || (!onProgram && !onPreview)) {
            return;
        }

        paint.setColor(onProgram ? 0xFFFF0000 : 0xFF00FF00);

        float left = videoRect != null ? videoRect.left : 0;
        float top = videoRect != null ? videoRect.top : 0;
        float right = videoRect != null ? videoRect.right : getWidth();
        float bottom = videoRect != null ? videoRect.bottom : getHeight();

        float half = BORDER_WIDTH / 2f;
        canvas.drawRect(left + half, top + half, right - half, bottom - half, paint);
    }
}

