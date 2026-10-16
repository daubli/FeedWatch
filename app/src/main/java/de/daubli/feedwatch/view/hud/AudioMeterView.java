package de.daubli.feedwatch.view.hud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import androidx.core.content.res.ResourcesCompat;
import de.daubli.feedwatch.R;

/**
 * Horizontal stereo peak meter with peak hold, styled like the segmented meters on field monitors.
 */
public class AudioMeterView extends View {

    private static final float MIN_DB = -48f;

    private static final float WARN_DB = -18f;

    private static final float CLIP_DB = -6f;

    private static final float DECAY_DB_PER_SEC = 24f;

    private static final float PEAK_FALL_DB_PER_SEC = 18f;

    private static final long PEAK_HOLD_MS = 1200;

    private static final int COLOR_OK = 0xFF2DBE4E;

    private static final int COLOR_WARN = 0xFFF5C623;

    private static final int COLOR_CLIP = 0xFFE5302A;

    private static final int COLOR_UNLIT = 0x26FFFFFF;

    private static final String[] LABELS = {"L", "R"};

    private final float[] levelDb = {MIN_DB, MIN_DB};

    private final float[] peakDb = {MIN_DB, MIN_DB};

    private final long[] peakTime = new long[2];

    private long lastUpdate;

    private final Paint segmentPaint = new Paint();

    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float segmentWidth;

    private final float segmentGap;

    private final float labelWidth;

    private final float rowGap;

    public AudioMeterView(Context context) {
        this(context, null);
    }

    public AudioMeterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AudioMeterView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        segmentWidth = dp(3);
        segmentGap = dp(1);
        labelWidth = dp(14);
        rowGap = dp(4);

        labelPaint.setColor(0xFF9AA0AA);
        labelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10,
                getResources().getDisplayMetrics()));
        labelPaint.setTypeface(ResourcesCompat.getFont(context, R.font.public_sans_semibold));
    }

    /**
     * Feeds the latest linear peak levels (0..1). Call once per display frame.
     */
    public void setLevels(float left, float right) {
        long now = SystemClock.uptimeMillis();
        float dt = lastUpdate == 0 ? 0 : (now - lastUpdate) / 1000f;
        lastUpdate = now;

        applyLevel(0, left, now, dt);
        applyLevel(1, right, now, dt);
        invalidate();
    }

    private void applyLevel(int channel, float linear, long now, float dt) {
        float db = toDb(linear);
        levelDb[channel] = Math.max(db, levelDb[channel] - DECAY_DB_PER_SEC * dt);

        if (db >= peakDb[channel]) {
            peakDb[channel] = db;
            peakTime[channel] = now;
        } else if (now - peakTime[channel] > PEAK_HOLD_MS) {
            peakDb[channel] = Math.max(MIN_DB, peakDb[channel] - PEAK_FALL_DB_PER_SEC * dt);
        }
    }

    private static float toDb(float linear) {
        if (linear <= 0f) {
            return MIN_DB;
        }
        return Math.max(MIN_DB, Math.min(0f, (float) (20.0 * Math.log10(linear))));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float barLeft = labelWidth;
        float barWidth = getWidth() - barLeft;
        float rowHeight = (getHeight() - rowGap) / 2f;
        int segments = (int) ((barWidth + segmentGap) / (segmentWidth + segmentGap));
        if (segments <= 0 || rowHeight <= 0) {
            return;
        }

        for (int channel = 0; channel < 2; channel++) {
            float top = channel * (rowHeight + rowGap);
            float textY = top + rowHeight / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f;
            canvas.drawText(LABELS[channel], 0, textY, labelPaint);

            int lit = Math.round(fraction(levelDb[channel]) * segments);
            int peak = Math.round(fraction(peakDb[channel]) * segments) - 1;

            for (int i = 0; i < segments; i++) {
                boolean on = i < lit || (i == peak && peak > 0);
                segmentPaint.setColor(on ? colorFor(i, segments) : COLOR_UNLIT);
                float left = barLeft + i * (segmentWidth + segmentGap);
                canvas.drawRect(left, top, left + segmentWidth, top + rowHeight, segmentPaint);
            }
        }
    }

    private static float fraction(float db) {
        return (db - MIN_DB) / -MIN_DB;
    }

    private static int colorFor(int segment, int segments) {
        float db = MIN_DB + (segment + 1f) / segments * -MIN_DB;
        if (db > CLIP_DB) {
            return COLOR_CLIP;
        }
        if (db > WARN_DB) {
            return COLOR_WARN;
        }
        return COLOR_OK;
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
