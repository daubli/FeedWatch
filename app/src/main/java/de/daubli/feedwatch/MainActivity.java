package de.daubli.feedwatch;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.google.android.material.snackbar.Snackbar;

import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import androidx.annotation.MainThread;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import de.daubli.feedwatch.ndi.Ndi;
import de.daubli.feedwatch.ndi.NdiDiscoveryManager;
import de.daubli.feedwatch.ndi.NdiFinder;
import de.daubli.feedwatch.ndi.NdiSource;
import de.daubli.feedwatch.settings.SettingsStore;
import de.daubli.feedwatch.sources.VideoSource;
import de.daubli.feedwatch.usb.UsbDevicePermissionHandler;
import de.daubli.feedwatch.uvc.UVCSource;
import de.daubli.feedwatch.uvc.UVCSourceFinder;

public class MainActivity extends AppCompatActivity {

    private static final int SCAN_TIMEOUT_MS = 2000;

    private static final long SCAN_PERIOD_MS = 2000;

    private NdiFinder finder;

    private UVCSourceFinder uvcSourceFinder;

    private NdiDiscoveryManager ndiDiscoveryManager;

    private ListView sourceListView;

    private TextView refreshHint;

    private SwipeRefreshLayout swipeRefreshLayout;

    private final List<VideoSource> currentSources = new ArrayList<>();

    private final List<String> currentSourceNames = new ArrayList<>();

    private ArrayAdapter<String> adapter;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ScheduledExecutorService scheduler;

    private ExecutorService scanExecutor;

    private final AtomicBoolean scanInProgress = new AtomicBoolean(false);

    private static volatile VideoSource selectedSource;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sourceListView = findViewById(R.id.sourceListView);
        refreshHint = findViewById(R.id.refreshHint);
        swipeRefreshLayout = findViewById(R.id.swipeToRefresh);

        Ndi.initialize();

        SettingsStore settingsStore = new SettingsStore();
        ndiDiscoveryManager = new NdiDiscoveryManager(getApplicationContext());

        finder = new NdiFinder(false, null, settingsStore.getAdditionalSources());
        uvcSourceFinder = new UVCSourceFinder(this);

        adapter = new ArrayAdapter<>(this, R.layout.support_simple_spinner_dropdown_item, currentSourceNames);
        sourceListView.setAdapter(adapter);

        sourceListView.setOnItemClickListener(this::onSourceClicked);

        swipeRefreshLayout.setColorSchemeResources(R.color.cardview_shadow_end_color);
        swipeRefreshLayout.setOnRefreshListener(this::triggerManualRefresh);
    }

    @Override
    protected void onStart() {
        super.onStart();
        startBackgroundScanning();
    }

    @Override
    protected void onStop() {
        super.onStop();
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
            nextByKey.put("ndi:" + s.getSourceName(), s);
        }
        for (VideoSource s : uvcSources) {
            nextByKey.put("uvc:" + s.getSourceName(), s);
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

        currentSourceNames.clear();
        currentSourceNames.addAll(currentSources.stream().map(VideoSource::getSourceName).collect(Collectors.toList()));

        adapter.notifyDataSetChanged();

        refreshHint.setVisibility(currentSourceNames.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void onSourceClicked(AdapterView<?> parent, View view, int position, long id) {
        if (position < 0 || position >= currentSources.size()) {
            return;
        }

        VideoSource chosen = currentSources.get(position);

        if (chosen instanceof NdiSource) {
            try {
                // Re-resolve from queried sources to ensure it is still valid in the finder
                VideoSource resolved = finder.getFromQueriedSources(chosen.getSourceName());
                selectVideoSource(resolved);
                beginStreaming();
            } catch (IllegalArgumentException ex) {
                Snackbar.make(sourceListView, "Source unavailable. Please refresh.", Snackbar.LENGTH_LONG).show();
            }
            return;
        }

        selectVideoSource(chosen);

        UsbDevicePermissionHandler permissionHandler = new UsbDevicePermissionHandler(this);
        permissionHandler.requestPermission(((UVCSource) chosen).getUsbDevice(),
                new UsbDevicePermissionHandler.Callback() {

                    @Override
                    public void onPermissionGranted(UsbDevice device) {
                        beginStreaming();
                    }

                    @Override
                    public void onPermissionDenied(UsbDevice device) {
                        Snackbar.make(sourceListView, "USB permission denied.", Snackbar.LENGTH_LONG).show();
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
        inflater.inflate(R.menu.menu_settings, menu);
        inflater.inflate(R.menu.menu_about, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if ("Settings".contentEquals(item.getTitle())) {
            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            return true;
        } else if ("About".contentEquals(item.getTitle())) {
            startActivity(new Intent(MainActivity.this, AboutActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
