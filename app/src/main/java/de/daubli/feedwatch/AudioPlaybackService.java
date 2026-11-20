package de.daubli.feedwatch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Keeps the audio of a source without video playing while the screen is off. The stream itself stays with
 * StreamVideoActivity; as a foreground service this keeps Android from freezing the app or cutting it off the
 * network, and a Wi-Fi lock keeps Wi-Fi out of power saving, which would hold packets back even longer.
 */
public class AudioPlaybackService extends Service {

    private static final String CHANNEL_ID = "audio_playback";

    private static final int NOTIFICATION_ID = 1;

    private static final String EXTRA_SOURCE_NAME = "sourceName";

    private WifiManager.WifiLock wifiLock;

    public static void start(Context context, String sourceName) {
        Intent intent = new Intent(context, AudioPlaybackService.class).putExtra(EXTRA_SOURCE_NAME, sourceName);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, AudioPlaybackService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String sourceName = intent != null ? intent.getStringExtra(EXTRA_SOURCE_NAME) : null;
        Notification notification = buildNotification(sourceName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (wifiLock == null) {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            //noinspection deprecation: the low latency mode only applies while the screen is on
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "feedwatch_audio");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
        // the stream belongs to the activity, a restarted service would have nothing to play
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wifiLock != null) {
            wifiLock.release();
            wifiLock = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification(String sourceName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.audio_playback_channel), NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }

        // brings the app's task back to the front with the stream still open
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tool_audio_on)
                .setContentTitle(getString(R.string.audio_playback_title))
                .setContentText(sourceName)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }
}
