package de.daubli.feedwatch;

import android.app.Application;
import android.content.Context;

public class FeedWatchApplication extends Application {

    private static Context context;

    public void onCreate() {
        super.onCreate();
        FeedWatchApplication.context = getApplicationContext();
    }

    public static Context getAppContext() {
        return FeedWatchApplication.context;
    }
}
