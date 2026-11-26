package de.daubli.feedwatch.ndi;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.daubli.feedwatch.settings.SettingsStore;

/**
 * The receiver this app advertises to the NDI Discovery Servers from the settings. Controllers see there which source
 * it shows and can connect it to another source or disconnect it; the library does that on its own, the
 * {@link Listener} then shows the new source.
 * <p>
 * It is one receiver for the whole process, the NDI streams take turns using it, so a controller keeps finding the
 * app under the same receiver. It is advertised while the app is in front or a stream plays, e.g. audio with the
 * screen off: an app in the background could not show what a controller connects it to.
 * <p>
 * The settings can turn the advertising off, the streams then still share the receiver, or let controllers only see
 * the source without changing it.
 */
public final class NdiRemoteReceiver {

    public interface Listener {

        /**
         * Called on the main thread when a controller connected the receiver to a source.
         *
         * @param sourceName the source's NDI name, or null if the controller disconnected the receiver
         */
        void onRemoteSource(@Nullable String sourceName);
    }

    private static final String TAG = "NdiRemoteReceiver";

    // A controller lists it after the device's name, e.g. "LIVING ROOM TV (FeedWatch V)": the library adds the V for
    // a receiver without audio. It is also the name of its input, which the audio would belong to as well.
    private static final String RECEIVER_NAME = "FeedWatch";

    // how often the receiver's source is compared with the app's, a controller's command shows up there
    private static final long POLL_INTERVAL_MS = 250;

    // a stream releases the receiver once its threads have stopped, which can take a few seconds
    private static final long ACQUIRE_TIMEOUT_MS = 8000;

    // an activity that replaces another, e.g. when it is recreated, starts after the other one stopped
    private static final long BACKGROUND_DELAY_MS = 1000;

    @Nullable
    private static volatile NdiRemoteReceiver instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final String discoveryServers;

    // created the first time the receiver is advertised, nothing connects to the servers while that is not allowed
    @Nullable
    private NdiReceiverAdvertiser advertiser;

    private boolean advertiserFailed;

    private final Listener listener;

    // guarded by this
    @Nullable
    private NdiReceiver receiver;

    private boolean lowBandwidth;

    // a stream uses the receiver
    private boolean leased;

    private boolean advertised;

    private boolean foreground;

    private boolean advertisingAllowed;

    private boolean controlAllowed;

    // the source the receiver is connected to as far as the app knows, a different one comes from a controller
    @Nullable
    private String sourceName;

    // the started activities, only touched on the main thread
    private final Set<Activity> startedActivities = new HashSet<>();

    private final Runnable goToBackground = () -> setForeground(false);

    private final Runnable poll = this::poll;

    private NdiRemoteReceiver(String discoveryServers, Listener listener, boolean advertisingAllowed,
                              boolean controlAllowed) {
        this.discoveryServers = discoveryServers;
        this.listener = listener;
        this.advertisingAllowed = advertisingAllowed;
        this.controlAllowed = controlAllowed;
    }

    /**
     * Sets up the receiver, once per process, and advertises it if the settings allow that. Call it on the main
     * thread before the first activity starts, e.g. in its onCreate.
     *
     * @param discoveryServers comma-separated NDI Discovery Servers, each with an optional port
     */
    public static void start(Application application, String discoveryServers, Listener listener) {
        if (instance != null) {
            return;
        }
        SettingsStore settings = new SettingsStore();
        NdiRemoteReceiver remoteReceiver = new NdiRemoteReceiver(discoveryServers, listener,
                settings.isReceiverAdvertisingAllowed(), settings.isReceiverControlAllowed());
        application.registerActivityLifecycleCallbacks(remoteReceiver.new ForegroundTracker());
        instance = remoteReceiver;
    }

    /**
     * @return the receiver the streams share, or null if there is no NDI Discovery Server
     */
    @Nullable
    public static NdiRemoteReceiver getInstance() {
        return instance;
    }

    /**
     * Takes the receiver for a stream and connects it to the source, unless a controller has done that already.
     * Waits until the previous stream released it. Not to be called on the main thread, which releases it.
     *
     * @return the receiver, or null if the previous stream did not release it in time
     */
    @Nullable
    public synchronized NdiReceiver acquire(NdiSource source, boolean lowBandwidth) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + ACQUIRE_TIMEOUT_MS;
        while (leased) {
            long left = deadline - SystemClock.elapsedRealtime();
            if (left <= 0) {
                Log.w(TAG, "The advertised receiver was not released, the stream uses one of its own");
                return null;
            }
            wait(left);
        }
        ensureReceiver(lowBandwidth);
        leased = true;
        updateAdvertising();
        if (!source.getSourceName().equals(sourceName)) {
            connect(source);
        }
        return receiver;
    }

    /**
     * Hands the receiver back once the stream no longer uses it, it disconnects from the source.
     */
    public synchronized void release(NdiReceiver receiver) {
        if (!leased || receiver != this.receiver) {
            return;
        }
        leased = false;
        connect(null);
        updateAdvertising();
        notifyAll();
    }

    private synchronized void setForeground(boolean foreground) {
        this.foreground = foreground;
        updateAdvertising();
    }

    /**
     * Applies the settings right away: the receiver is advertised or withdrawn, or advertised anew with the other
     * control permission. A stream that plays goes on with its source. Call it on the main thread.
     */
    public synchronized void setPermissions(boolean advertisingAllowed, boolean controlAllowed) {
        boolean controlChanged = this.controlAllowed != controlAllowed;
        this.advertisingAllowed = advertisingAllowed;
        this.controlAllowed = controlAllowed;
        if (controlChanged && advertised && advertiser != null && receiver != null) {
            // the permission is only handed over when a receiver is added
            advertiser.remove(receiver);
            advertiser.add(receiver, controlAllowed, RECEIVER_NAME);
            Log.i(TAG, "Controllers " + (controlAllowed ? "may" : "may no longer") + " switch the receiver");
        }
        updateAdvertising();
    }

    // guarded by this
    private void updateAdvertising() {
        boolean advertise = advertisingAllowed && (foreground || leased);
        if (advertise == advertised) {
            return;
        }
        if (advertise) {
            if (!ensureAdvertiser()) {
                return;
            }
            ensureReceiver(new SettingsStore().isLowBandwidthModeEnabled());
            advertiser.add(receiver, controlAllowed, RECEIVER_NAME);
            advertised = true;
            mainHandler.removeCallbacks(poll);
            mainHandler.post(poll);
            Log.i(TAG, "Advertising the receiver to " + discoveryServers);
        } else {
            advertiser.remove(receiver);
            advertised = false;
            mainHandler.removeCallbacks(poll);
            // A controller may have connected it after the app went to the background. A stream that plays, when
            // the advertising was turned off, keeps its source.
            if (!leased) {
                connect(null);
            }
        }
    }

    /**
     * Connects to the discovery servers, the first time the receiver is advertised. Guarded by this.
     *
     * @return false if the library could not create the advertiser
     */
    private boolean ensureAdvertiser() {
        if (advertiser != null) {
            return true;
        }
        if (advertiserFailed) {
            return false;
        }
        NdiReceiverAdvertiser created = new NdiReceiverAdvertiser(discoveryServers);
        if (!created.isValid()) {
            Log.w(TAG, "No receiver advertiser for " + discoveryServers);
            advertiserFailed = true;
            return false;
        }
        advertiser = created;
        return true;
    }

    /**
     * Creates the receiver, or creates it again for another bandwidth, which is only set when a receiver is created.
     * Guarded by this, only while no stream uses it.
     */
    private void ensureReceiver(boolean lowBandwidth) {
        if (receiver != null && this.lowBandwidth == lowBandwidth) {
            return;
        }
        if (receiver != null) {
            if (advertised) {
                advertiser.remove(receiver);
            }
            receiver.close();
        }
        receiver = new NdiReceiver(lowBandwidth, RECEIVER_NAME);
        this.lowBandwidth = lowBandwidth;
        sourceName = null;
        if (advertised) {
            advertiser.add(receiver, controlAllowed, RECEIVER_NAME);
        }
    }

    // guarded by this
    private void connect(@Nullable NdiSource source) {
        if (receiver == null) {
            return;
        }
        sourceName = source != null ? source.getSourceName() : null;
        receiver.connect(source);
    }

    /**
     * Compares the receiver's source with the app's, on the main thread while the receiver is advertised.
     */
    private void poll() {
        String remoteSource;
        synchronized (this) {
            if (!advertised || receiver == null) {
                return;
            }
            mainHandler.postDelayed(poll, POLL_INTERVAL_MS);
            String current = receiver.getSourceName();
            if (Objects.equals(current, sourceName)) {
                return;
            }
            sourceName = current;
            remoteSource = current;
        }
        Log.i(TAG, "A controller connected the receiver to " + remoteSource);
        listener.onRemoteSource(remoteSource);
    }

    /**
     * Tracks the started activities: the app is in front while there is one. An activity that started before the
     * tracker was registered, e.g. one Android restored ahead of MainActivity, is not counted when it stops.
     */
    private final class ForegroundTracker implements Application.ActivityLifecycleCallbacks {

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
            if (startedActivities.add(activity) && startedActivities.size() == 1) {
                mainHandler.removeCallbacks(goToBackground);
                setForeground(true);
            }
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
            if (startedActivities.remove(activity) && startedActivities.isEmpty()) {
                mainHandler.postDelayed(goToBackground, BACKGROUND_DELAY_MS);
            }
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
        }
    }
}
