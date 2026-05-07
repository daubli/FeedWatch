package de.daubli.feedwatch;

import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.*;
import androidx.appcompat.app.AppCompatActivity;
import de.daubli.feedwatch.databinding.StreamVideoActivityBinding;
import de.daubli.feedwatch.ndi.NdiSource;
import de.daubli.feedwatch.ndi.StreamNDIVideoRunner;
import de.daubli.feedwatch.settings.SettingsStore;
import de.daubli.feedwatch.sources.VideoSource;
import de.daubli.feedwatch.uvc.StreamUvcVideoRunner;
import de.daubli.feedwatch.uvc.UVCSource;

public class StreamVideoActivity extends AppCompatActivity {

    private static final String TAG = "StreamVideoActivity";

    private static final long MENU_HIDE_DELAY_MS = 5000;

    private StreamVideoActivityBinding viewBinding;

    private VideoSource videoSource;

    private StreamVideoRunner runner;

    private Handler menuHandler;

    private Runnable hideMenuCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewBinding = StreamVideoActivityBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());

        videoSource = resolveVideoSource(getIntent());

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        initMenu();
        setFullScreen();
        initSavedState();
    }

    private VideoSource resolveVideoSource(Intent intent) {
        UsbDevice usbDevice = null;

        if (intent != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                usbDevice = intent.getParcelableExtra(
                        UsbManager.EXTRA_DEVICE,
                        UsbDevice.class
                );
            } else {
                usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            }
        }

        if (usbDevice != null) {
            Log.d(TAG, "Starting from USB device: "
                    + usbDevice.getDeviceName()
                    + ", vendorId=" + usbDevice.getVendorId()
                    + ", productId=" + usbDevice.getProductId());
            return new UVCSource(usbDevice);
        }

        VideoSource source = MainActivity.getSource();

        if (source == null) {
            Log.e(TAG, "No VideoSource available");
        }

        return source;
    }

    private void initMenu() {
        initializeZebraButton();
        initializeToggleFocusAssistButton();
        initializeToggleGridButton();
        initializeTallyButton();
        initializeCloseButton();
        initShowHideMenu();
    }

    private void initSavedState() {
        SettingsStore settingsStore = new SettingsStore();
        if (settingsStore.isFramingHelperOverlayEnabled()) {
            viewBinding.framingHelperOverlayView.setVisibility(View.VISIBLE);
            viewBinding.gridButton.setImageResource(R.drawable.grid_icon_3x3_selected);
        }
        if (settingsStore.isFocusAssistEnabled()) {
            viewBinding.openGLVideoView.setFocusAssistEnabled(true);
            viewBinding.focusAssistButton.setImageResource(R.drawable.focus_assist_selected);
        }
        if (settingsStore.isZebraEnabled()) {
            viewBinding.openGLVideoView.setZebraOverlayEnabled(true);
            viewBinding.zebraButton.setImageResource(R.drawable.zebra_selected);
        }
        if (settingsStore.isTallyEnabled()) {
            viewBinding.tallyOverlayView.setVisibility(View.VISIBLE);
            viewBinding.tallyOverlayView.setTallyEnabled(true);
            viewBinding.tallyButton.setImageResource(R.drawable.tally_selected);
            viewBinding.tallyOverlayView.bringToFront();
            viewBinding.menuLayout.bringToFront();
        }
    }

    private void showMenuAndScheduleHide() {
        viewBinding.menuLayout.setVisibility(View.VISIBLE);
        viewBinding.menuLayout.bringToFront();

        menuHandler.removeCallbacks(hideMenuCallback);
        menuHandler.postDelayed(hideMenuCallback, MENU_HIDE_DELAY_MS);
    }

    private void hideMenuNow() {
        menuHandler.removeCallbacks(hideMenuCallback);
        viewBinding.menuLayout.setVisibility(View.GONE);
    }

    private void extendMenuTimeout() {
        menuHandler.removeCallbacks(hideMenuCallback);
        menuHandler.postDelayed(hideMenuCallback, MENU_HIDE_DELAY_MS);
    }

    private void initShowHideMenu() {
        this.menuHandler = new Handler(Looper.getMainLooper());
        this.hideMenuCallback = () -> viewBinding.menuLayout.setVisibility(View.GONE);

        viewBinding.menuLayout.setOnClickListener(v -> extendMenuTimeout());

        viewBinding.openGLVideoView.setOnClickListener(v -> {
            if (viewBinding.menuLayout.getVisibility() == View.VISIBLE) {
                hideMenuNow();
            } else {
                showMenuAndScheduleHide();
            }
        });
    }

    private void initializeZebraButton() {
        viewBinding.zebraButton.setOnClickListener(view -> {
            SettingsStore settingsStore = new SettingsStore();
            if (settingsStore.isZebraEnabled()) {
                viewBinding.openGLVideoView.setZebraOverlayEnabled(false);
                viewBinding.zebraButton.setImageResource(R.drawable.zebra);
                settingsStore.setZebraEnabled(false);
            } else {
                viewBinding.openGLVideoView.setZebraOverlayEnabled(true);
                viewBinding.zebraButton.setImageResource(R.drawable.zebra_selected);
                settingsStore.setZebraEnabled(true);
            }
        });
    }

    private void initializeToggleFocusAssistButton() {
        viewBinding.focusAssistButton.setOnClickListener(view -> {
            SettingsStore settingsStore = new SettingsStore();
            if (settingsStore.isFocusAssistEnabled()) {
                viewBinding.openGLVideoView.setFocusAssistEnabled(false);
                viewBinding.focusAssistButton.setImageResource(R.drawable.focus_assist);
                settingsStore.setFocusAssistEnabled(false);
            } else {
                viewBinding.openGLVideoView.setFocusAssistEnabled(true);
                viewBinding.focusAssistButton.setImageResource(R.drawable.focus_assist_selected);
                settingsStore.setFocusAssistEnabled(true);
            }
        });
    }

    private void initializeToggleGridButton() {
        viewBinding.gridButton.setOnClickListener(view -> {
            SettingsStore settingsStore = new SettingsStore();
            if (viewBinding.framingHelperOverlayView.getVisibility() == View.VISIBLE) {
                viewBinding.framingHelperOverlayView.setVisibility(View.GONE);
                viewBinding.gridButton.setImageResource(R.drawable.grid_icon_3x3);
                settingsStore.setFramingHelperOverlayEnabled(false);
            } else {
                viewBinding.framingHelperOverlayView.setVisibility(View.VISIBLE);
                viewBinding.gridButton.setImageResource(R.drawable.grid_icon_3x3_selected);
                settingsStore.setFramingHelperOverlayEnabled(true);
                viewBinding.framingHelperOverlayView.bringToFront();
                viewBinding.menuLayout.bringToFront();
            }
        });
    }

    private void initializeTallyButton() {
        if (videoSource instanceof UVCSource) {
            viewBinding.gridButton.setVisibility(View.GONE);
            return;
        }

        viewBinding.tallyButton.setOnClickListener(view -> {
            SettingsStore settingsStore = new SettingsStore();

            boolean enabled = !viewBinding.tallyOverlayView.isTallyEnabled();

            viewBinding.tallyOverlayView.setTallyEnabled(enabled);
            viewBinding.tallyOverlayView.setVisibility(enabled ? View.VISIBLE : View.GONE);
            viewBinding.tallyButton.setImageResource(enabled ? R.drawable.tally_selected : R.drawable.tally);
            settingsStore.setTallyEnabled(enabled);

            if (enabled) {
                viewBinding.tallyOverlayView.bringToFront();
                viewBinding.menuLayout.bringToFront();
            }
        });
    }

    private void initializeCloseButton() {
        viewBinding.closeButton.setOnClickListener(view -> closeVideoActivity());
    }

    private void closeVideoActivity() {
        if (runner != null) {
            runner.shutdown();
        } else {
            finish();
        }
    }

    private void setFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (videoSource == null) {
            finish();
            return;
        }

        if (videoSource instanceof NdiSource) {
            runner = new StreamNDIVideoRunner((NdiSource) videoSource, viewBinding, this);
            runner.start();
        } else if (videoSource instanceof UVCSource) {
            runner = new StreamUvcVideoRunner((UVCSource) videoSource, viewBinding, this);
            runner.start();
        } else {
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (runner != null) {
            runner.shutdown();
            runner = null;
        }
    }
}
