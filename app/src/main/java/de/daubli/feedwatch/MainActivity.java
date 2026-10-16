package de.daubli.feedwatch;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.android.material.snackbar.Snackbar;

import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Lifecycle;
import com.google.android.material.appbar.MaterialToolbar;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import de.daubli.feedwatch.ndi.Ndi;
import de.daubli.feedwatch.ndi.NdiDiscoveryManager;
import de.daubli.feedwatch.ndi.NdiFinder;
import de.daubli.feedwatch.ndi.NdiSource;
import de.daubli.feedwatch.settings.SettingsStore;
import de.daubli.feedwatch.sources.SourceListAdapter;
import de.daubli.feedwatch.sources.VideoSource;
import de.daubli.feedwatch.usb.UsbDevicePermissionHandler;
import de.daubli.feedwatch.uvc.UVCSource;
import de.daubli.feedwatch.uvc.UVCSourceFinder;

public class MainActivity extends AppCompatActivity {

    private static final int SCAN_TIMEOUT_MS = 2000;

    private static final long SCAN_PERIOD_MS = 2000;

    // time to cancel opening the default source before it starts
    private static final long AUTO_START_DELAY_MS = 1500;

    private NdiFinder finder;

    private UVCSourceFinder uvcSourceFinder;

    private NdiDiscoveryManager ndiDiscoveryManager;

    private ListView sourceListView;

    private View emptyState;

    private TextView sourceCount;

    private SwipeRefreshLayout swipeRefreshLayout;

    private final List<VideoSource> currentSources = new ArrayList<>();

    private SourceListAdapter adapter;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ScheduledExecutorService scheduler;

    private ExecutorService scanExecutor;

    private final AtomicBoolean scanInProgress = new AtomicBoolean(false);

    private static volatile VideoSource selectedSource;

    // the default source opens once per app start; set when it did, or when the user opened or cancelled a source first
    private static boolean autoStartConsumed;

    private SettingsStore settingsStore;

    @Nullable
    private Runnable pendingAutoStart;

    @Nullable
    private Snackbar autoStartSnackbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        sourceListView = findViewById(R.id.sourceListView);
        emptyState = findViewById(R.id.emptyState);
        sourceCount = findViewById(R.id.sourceCount);
        swipeRefreshLayout = findViewById(R.id.swipeToRefresh);

        Ndi.initialize();

        settingsStore = new SettingsStore();
        ndiDiscoveryManager = new NdiDiscoveryManager(getApplicationContext());

        finder = new NdiFinder(false, null, settingsStore.getAdditionalSources());
        uvcSourceFinder = new UVCSourceFinder(this);

        adapter = new SourceListAdapter(this, currentSources);
        sourceListView.setAdapter(adapter);

        sourceListView.setOnItemClickListener(this::onSourceClicked);
        sourceListView.setOnItemLongClickListener(this::onSourceLongClicked);

        swipeRefreshLayout.setColorSchemeResources(R.color.fw_accent);
        swipeRefreshLayout.setProgressBackgroundColorSchemeResource(R.color.fw_surface_raised);
        swipeRefreshLayout.setOnRefreshListener(this::triggerManualRefresh);

        handleUsbIntent(getIntent());
    }

    private void handleUsbIntent(Intent intent) {
        if (intent == null) return;

        if (!UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            return;
        }

        UsbDevice device;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            device = intent.getParcelableExtra(
                    UsbManager.EXTRA_DEVICE,
                    UsbDevice.class
            );
        } else {
            device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        }

        if (device == null) {
            Log.e("MainActivity", "USB attach intent without device");
            return;
        }

        autoStartConsumed = true;

        Log.d("MainActivity", "USB attached: "
                + device.getDeviceName()
                + ", vendorId=" + device.getVendorId()
                + ", productId=" + device.getProductId());

        UsbDevicePermissionHandler permissionHandler =
                new UsbDevicePermissionHandler(this);

        permissionHandler.requestPermission(device, new UsbDevicePermissionHandler.Callback() {
            @Override
            public void onPermissionGranted(UsbDevice grantedDevice) {
                // Add it to your source list, or directly open StreamVideoActivity.
                Intent streamIntent = new Intent(MainActivity.this, StreamVideoActivity.class);
                streamIntent.putExtra(UsbManager.EXTRA_DEVICE, grantedDevice);
                startActivity(streamIntent);
            }

            @Override
            public void onPermissionDenied(UsbDevice deniedDevice) {
                Log.w("MainActivity", "USB permission denied: "
                        + deniedDevice.getDeviceName());
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        startBackgroundScanning();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // the default may have been removed in the settings
        adapter.setDefaultSourceKey(settingsStore.getDefaultSourceKey());
        maybeAutoStartDefault();
    }

    @Override
    protected void onStop() {
        super.onStop();
        cancelPendingAutoStart();
        stopBackgroundScanning();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBackgroundScanning();

        if (ndiDiscoveryManager != null) {
            ndiDiscoveryManager.stopDiscovery();
        }
        if (finder != null) {
            finder.close();
        }
    }

    private void startBackgroundScanning() {
        if (scheduler != null && !scheduler.isShutdown()) {
            return;
        }

        scanExecutor = Executors.newSingleThreadExecutor();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        // shutdownNow() in stopBackgroundScanning() drops a queued scan before its finally block runs
        scanInProgress.set(false);

        ndiDiscoveryManager.startDiscovery();

        scheduler.scheduleWithFixedDelay(() -> submitScan(false), 0, SCAN_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private void stopBackgroundScanning() {
        ndiDiscoveryManager.stopDiscovery();

        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (scanExecutor != null) {
            scanExecutor.shutdownNow();
            scanExecutor = null;
        }
    }

    private void triggerManualRefresh() {
        // Show spinner immediately, then run a scan now (does not wait for next periodic tick)
        swipeRefreshLayout.setRefreshing(true);
        submitScan(true);
    }

    private void submitScan(boolean isManual) {
        if (scanExecutor == null) {
            return;
        }

        if (!scanInProgress.compareAndSet(false, true)) {
            if (isManual) {
                mainHandler.post(() -> swipeRefreshLayout.setRefreshing(false));
            }
            return;
        }

        scanExecutor.submit(() -> {
            try {
                scanOnceAndPublish(isManual);
            } finally {
                scanInProgress.set(false);
            }
        });
    }

    private void scanOnceAndPublish(boolean isManual) {
        finder.waitForSources(SCAN_TIMEOUT_MS);

        VideoSource[] ndiSources = finder.getCurrentSources();
        UVCSource[] uvcSources = uvcSourceFinder.getUvcSources();

        Map<String, VideoSource> nextByKey = new LinkedHashMap<>();

        for (VideoSource s : ndiSources) {
            nextByKey.put(s.getStableKey(), s);
        }
        for (VideoSource s : uvcSources) {
            nextByKey.put(s.getStableKey(), s);
        }

        List<VideoSource> nextList = new ArrayList<>(nextByKey.values());

        boolean changed = sourcesChanged(currentSources, nextList);
        if (!changed && !isManual) {
            return;
        }

        mainHandler.post(() -> {
            applyNewSources(nextList);
            swipeRefreshLayout.setRefreshing(false);
        });
    }

    private boolean sourcesChanged(List<VideoSource> oldList, List<VideoSource> newList) {
        if (oldList.size() != newList.size()) {
            return true;
        }

        for (int i = 0; i < oldList.size(); i++) {
            VideoSource a = oldList.get(i);
            VideoSource b = newList.get(i);
            if (!Objects.equals(a.getClass(), b.getClass())) {
                return true;
            }
            if (!Objects.equals(a.getSourceName(), b.getSourceName())) {
                return true;
            }
        }
        return false;
    }

    @MainThread
    private void applyNewSources(List<VideoSource> nextList) {
        currentSources.clear();
        currentSources.addAll(nextList);

        adapter.notifyDataSetChanged();

        sourceCount.setText(String.valueOf(currentSources.size()));
        emptyState.setVisibility(currentSources.isEmpty() ? View.VISIBLE : View.GONE);

        maybeAutoStartDefault();
    }

    @MainThread
    private void maybeAutoStartDefault() {
        if (autoStartConsumed || pendingAutoStart != null
                || !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            return;
        }

        VideoSource defaultSource = findDefaultSource();
        if (defaultSource == null) {
            return;
        }

        pendingAutoStart = () -> {
            pendingAutoStart = null;
            autoStartSnackbar = null;
            // it may have disappeared during the delay, then the next scan that finds it tries again
            VideoSource stillAvailable = findDefaultSource();
            if (stillAvailable != null) {
                openSource(stillAvailable);
            }
        };

        autoStartSnackbar = Snackbar.make(sourceListView,
                        getString(R.string.default_source_opening, sourceLabel(defaultSource)),
                        Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.default_source_cancel, v -> {
                    cancelPendingAutoStart();
                    autoStartConsumed = true;
                });
        autoStartSnackbar.show();
        mainHandler.postDelayed(pendingAutoStart, AUTO_START_DELAY_MS);
    }

    private void cancelPendingAutoStart() {
        if (pendingAutoStart != null) {
            mainHandler.removeCallbacks(pendingAutoStart);
            pendingAutoStart = null;
        }
        if (autoStartSnackbar != null) {
            autoStartSnackbar.dismiss();
            autoStartSnackbar = null;
        }
    }

    @Nullable
    private VideoSource findDefaultSource() {
        String defaultKey = settingsStore.getDefaultSourceKey();
        if (defaultKey == null) {
            return null;
        }
        for (VideoSource source : currentSources) {
            if (defaultKey.equals(source.getStableKey())) {
                return source;
            }
        }
        return null;
    }

    private String sourceLabel(VideoSource source) {
        String name = source.getSourceName();
        return name != null && !name.isEmpty() ? name : getString(R.string.badge_usb);
    }

    private void onSourceClicked(AdapterView<?> parent, View view, int position, long id) {
        if (position < 0 || position >= currentSources.size()) {
            return;
        }

        openSource(currentSources.get(position));
    }

    private boolean onSourceLongClicked(AdapterView<?> parent, View view, int position, long id) {
        if (position < 0 || position >= currentSources.size()) {
            return false;
        }

        VideoSource source = currentSources.get(position);
        boolean isDefault = source.getStableKey().equals(settingsStore.getDefaultSourceKey());

        PopupMenu menu = new PopupMenu(this, view);
        menu.getMenu().add(isDefault ? R.string.default_source_clear : R.string.default_source_set);
        menu.setOnMenuItemClickListener(item -> {
            if (isDefault) {
                settingsStore.clearDefaultSource();
            } else {
                settingsStore.setDefaultSource(source.getStableKey(), sourceLabel(source));
                // the user is choosing right now, so do not jump into the stream
                cancelPendingAutoStart();
                autoStartConsumed = true;
            }
            adapter.setDefaultSourceKey(settingsStore.getDefaultSourceKey());
            return true;
        });
        menu.show();
        return true;
    }

    private void openSource(VideoSource chosen) {
        cancelPendingAutoStart();
        autoStartConsumed = true;

        if (chosen instanceof NdiSource) {
            try {
                // Re-resolve from queried sources to ensure it is still valid in the finder
                VideoSource resolved = finder.getFromQueriedSources(chosen.getSourceName());
                selectVideoSource(resolved);
                beginStreaming();
            } catch (IllegalArgumentException ex) {
                Snackbar.make(sourceListView, R.string.source_unavailable, Snackbar.LENGTH_LONG).show();
            }
            return;
        }

        selectVideoSource(chosen);

        UsbDevicePermissionHandler permissionHandler = new UsbDevicePermissionHandler(this);
        permissionHandler.requestPermission(((UVCSource) chosen).getUsbDevice(),
                new UsbDevicePermissionHandler.Callback() {

                    @Override
                    public void onPermissionGranted(UsbDevice device) {
                        selectVideoSource(new UVCSource(device));
                        beginStreaming();
                    }

                    @Override
                    public void onPermissionDenied(UsbDevice device) {
                        Snackbar.make(sourceListView, R.string.usb_permission_denied, Snackbar.LENGTH_LONG).show();
                    }
                });
    }

    public void beginStreaming() {
        Intent streamingIntent = new Intent(this, StreamVideoActivity.class);
        startActivity(streamingIntent);
    }

    public static VideoSource getSource() {
        return selectedSource;
    }

    public static void selectVideoSource(VideoSource videoSource) {
        selectedSource = videoSource;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_settings) {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            return true;
        } else if (item.getItemId() == R.id.menu_about) {
            startActivity(new Intent(MainActivity.this, AboutActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
