package de.daubli.feedwatch;

import android.content.Context;
import androidx.annotation.Nullable;
import de.daubli.feedwatch.ndi.NdiRemoteReceiver;
import de.daubli.feedwatch.ndi.NdiSource;

/**
 * Shows the source a controller connected the advertised NDI receiver to: the open stream switches to it, otherwise
 * a stream opens for it.
 */
final class RemoteSourceHandler implements NdiRemoteReceiver.Listener {

    // the newest stream, in front or playing audio with the screen off; cleared when it is destroyed
    @Nullable
    private static StreamVideoActivity streamActivity;

    private final Context context;

    RemoteSourceHandler(Context context) {
        this.context = context.getApplicationContext();
    }

    static void setStreamActivity(StreamVideoActivity activity) {
        streamActivity = activity;
    }

    static void clearStreamActivity(StreamVideoActivity activity) {
        if (streamActivity == activity) {
            streamActivity = null;
        }
    }

    @Override
    public void onRemoteSource(@Nullable String sourceName) {
        StreamVideoActivity activity = streamActivity;
        if (activity != null && !activity.isFinishing() && activity.isShowingStream()) {
            activity.onRemoteSource(sourceName);
            return;
        }
        if (sourceName == null) {
            // nothing shown that the controller could disconnect
            return;
        }
        if (activity != null && !activity.isFinishing()) {
            // a stream left behind another screen, e.g. the source list opened for a USB camera on top of it, would
            // only take the source where nobody sees it; the new stream replaces it
            activity.finish();
        }
        MainActivity.openRemoteSource(context, new NdiSource(sourceName, null));
    }
}
