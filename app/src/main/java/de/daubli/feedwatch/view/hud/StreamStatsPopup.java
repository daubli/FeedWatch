package de.daubli.feedwatch.view.hud;

import java.util.List;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;
import androidx.annotation.Nullable;
import de.daubli.feedwatch.R;
import de.daubli.feedwatch.stats.StreamStats;

/**
 * Small popup with the statistics of the running stream, opened below the stats button in the stream HUD. The values
 * are refreshed twice a second while it is shown.
 * <p>
 * Like {@link ToolSettingsPopup} it does not take focus, touches outside of it reach the screen below.
 */
public class StreamStatsPopup {

    private static final long REFRESH_INTERVAL_MS = 500;

    public interface StatsSource {

        void collectStats(StreamStats stats);
    }

    private final LayoutInflater inflater;

    private final View content;

    private final ViewGroup rows;

    private final PopupWindow window;

    private final StatsSource source;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {

        @Override
        public void run() {
            update();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Nullable
    private PopupWindow.OnDismissListener onDismissListener;

    public StreamStatsPopup(Context context, StatsSource source) {
        this.source = source;
        inflater = LayoutInflater.from(context);
        content = inflater.inflate(R.layout.popup_stream_stats, null);
        rows = content.findViewById(R.id.streamStatsRows);

        window = new PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                false);
        // the content draws its own rounded background
        window.setBackgroundDrawable(new ColorDrawable(0));
        window.setElevation(content.getElevation());
        window.setOnDismissListener(() -> {
            handler.removeCallbacks(refresh);
            if (onDismissListener != null) {
                onDismissListener.onDismiss();
            }
        });
    }

    public void setOnDismissListener(@Nullable PopupWindow.OnDismissListener listener) {
        onDismissListener = listener;
    }

    private void update() {
        StreamStats stats = new StreamStats();
        source.collectStats(stats);
        List<StreamStats.Row> statRows = stats.getRows();

        // the rows only change when the audio is switched, otherwise the views are reused
        while (rows.getChildCount() > statRows.size()) {
            rows.removeViewAt(rows.getChildCount() - 1);
        }
        while (rows.getChildCount() < statRows.size()) {
            rows.addView(inflater.inflate(R.layout.item_stream_stats_row, rows, false));
        }

        for (int i = 0; i < statRows.size(); i++) {
            StreamStats.Row row = statRows.get(i);
            View rowView = rows.getChildAt(i);
            ((TextView) rowView.findViewById(R.id.streamStatsLabel)).setText(row.label);
            ((TextView) rowView.findViewById(R.id.streamStatsValue)).setText(row.value);
        }
    }

    /**
     * Shows the popup below the anchor, aligned to its right edge and kept inside the window. It grows to the left
     * when a value gets longer.
     */
    public void showBelow(View anchor) {
        update();

        float density = anchor.getResources().getDisplayMetrics().density;
        int gap = Math.round(8 * density);
        int margin = Math.round(8 * density);

        int[] location = new int[2];
        anchor.getLocationInWindow(location);
        int windowWidth = anchor.getRootView().getWidth();

        int x = Math.max(margin, windowWidth - location[0] - anchor.getWidth());
        int y = location[1] + anchor.getHeight() + gap;
        window.showAtLocation(anchor, Gravity.TOP | Gravity.END, x, y);

        handler.postDelayed(refresh, REFRESH_INTERVAL_MS);
    }

    public boolean isShowing() {
        return window.isShowing();
    }

    public void dismiss() {
        window.dismiss();
    }
}
