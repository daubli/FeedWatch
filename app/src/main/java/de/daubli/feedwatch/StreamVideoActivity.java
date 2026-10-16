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
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import de.daubli.feedwatch.databinding.StreamVideoActivityBinding;
import de.daubli.feedwatch.ndi.NdiDiscoveryManager;
import de.daubli.feedwatch.ndi.NdiSource;
import de.daubli.feedwatch.ndi.StreamNDIVideoRunner;
import de.daubli.feedwatch.settings.SettingsStore;
import de.daubli.feedwatch.sources.VideoSource;
import de.daubli.feedwatch.uvc.StreamUvcVideoRunner;
import de.daubli.feedwatch.uvc.UVCSource;
import de.daubli.feedwatch.view.hud.ToolSettingsPopup;
import de.daubli.feedwatch.view.overlays.focusassist.FocusPeakingColor;

public class StreamVideoActivity extends AppCompatActivity {

    private static final String TAG = "StreamVideoActivity";

    private static final long MENU_HIDE_DELAY_MS = 5000;

    // zebra thresholds below this mark mid-tones, not highlights
    private static final int ZEBRA_THRESHOLD_MIN = 50;

    private static final int ZEBRA_THRESHOLD_MAX = 100;

    private StreamVideoActivityBinding viewBinding;

    private VideoSource videoSource;

    private StreamVideoRunner runner;

    private NdiDiscoveryManager ndiDiscoveryManager;

    // only the picture, plus the close and audio buttons
    private boolean viewerMode;

    private Handler menuHandler;

    private Runnable hideMenuCallback;

    private boolean hudShown;

    @Nullable
    private ToolSettingsPopup toolSettingsPopup;

    private int lastVideoWidth;

    private int lastVideoHeight;

    private float lastFrameRate = -1f;

    private String lastFormat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewBinding = StreamVideoActivityBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());

        videoSource = resolveVideoSource(getIntent());
        viewerMode = new SettingsStore().isViewerModeEnabled();

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        initMenu();
        initHud();
        setFullScreen();
        if (viewerMode) {
            hideMonitoringUi();
        } else {
            initSavedState();
        }
        showMenuAndScheduleHide();
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
        initializeAudioButton();
        initializeCloseButton();
        initShowHideMenu();
    }

    private void initSavedState() {
        SettingsStore settingsStore = new SettingsStore();
        viewBinding.openGLVideoView.setFocusPeakingColor(settingsStore.getFocusPeakingColor().getColor());
        viewBinding.openGLVideoView.setZebraThreshold(settingsStore.getZebraThreshold());
        if (settingsStore.isFramingHelperOverlayEnabled()) {
            viewBinding.framingHelperOverlayView.setVisibility(View.VISIBLE);
            viewBinding.gridButton.setActivated(true);
        }
        if (settingsStore.isFocusAssistEnabled()) {
            viewBinding.openGLVideoView.setFocusAssistEnabled(true);
            viewBinding.focusAssistButton.setActivated(true);
        }
        if (settingsStore.isZebraEnabled()) {
            viewBinding.openGLVideoView.setZebraOverlayEnabled(true);
            viewBinding.zebraButton.setActivated(true);
        }
        if (settingsStore.isTallyEnabled() && !(videoSource instanceof UVCSource)) {
            viewBinding.tallyOverlayView.setVisibility(View.VISIBLE);
            viewBinding.tallyOverlayView.setTallyEnabled(true);
            viewBinding.tallyButton.setActivated(true);
        }
    }

    private void hideMonitoringUi() {
        View[] views = {viewBinding.sourceTypeBadge, viewBinding.sourceNameText, viewBinding.lowBandwidthChip,
                viewBinding.tallyChip, viewBinding.resolutionChip, viewBinding.fpsChip, viewBinding.formatChip,
                viewBinding.gridButton, viewBinding.zebraButton, viewBinding.focusAssistButton,
                viewBinding.tallyButton, viewBinding.audioMeterView};
        for (View view : views) {
            view.setVisibility(View.GONE);
        }
    }

    private void showMenuAndScheduleHide() {
        hudShown = true;
        View hud = viewBinding.hudLayout;
        // cancelling also drops a pending fade-out end action
        hud.animate().cancel();
        if (hud.getVisibility() != View.VISIBLE) {
            hud.setAlpha(0f);
            hud.setVisibility(View.VISIBLE);
        }
        hud.animate().alpha(1f).setDuration(150);

        menuHandler.removeCallbacks(hideMenuCallback);
        menuHandler.postDelayed(hideMenuCallback, MENU_HIDE_DELAY_MS);
    }

    private void hideMenuNow() {
        menuHandler.removeCallbacks(hideMenuCallback);
        fadeOutHud();
    }

    private void fadeOutHud() {
        hudShown = false;
        View hud = viewBinding.hudLayout;
        hud.animate().cancel();
        hud.animate().alpha(0f).setDuration(200).withEndAction(() -> hud.setVisibility(View.GONE));
    }

    private void extendMenuTimeout() {
        menuHandler.removeCallbacks(hideMenuCallback);
        menuHandler.postDelayed(hideMenuCallback, MENU_HIDE_DELAY_MS);
    }

    private void initShowHideMenu() {
        this.menuHandler = new Handler(Looper.getMainLooper());
        this.hideMenuCallback = this::fadeOutHud;

        // taps on the bars keep the HUD open instead of falling through to the video view
        viewBinding.topBar.setOnClickListener(v -> extendMenuTimeout());
        viewBinding.bottomBar.setOnClickListener(v -> extendMenuTimeout());

        // on the root rather than the video view, which stays invisible until the first frame arrives
        viewBinding.getRoot().setOnClickListener(v -> {
            // a tap next to an open tool settings popup only closes it
            if (dismissToolSettings()) {
                return;
            }
            if (hudShown) {
                hideMenuNow();
            } else {
                showMenuAndScheduleHide();
            }
        });
    }

    private void initializeZebraButton() {
        viewBinding.zebraButton.setOnClickListener(view -> {
            extendMenuTimeout();
            setZebraEnabled(!new SettingsStore().isZebraEnabled());
        });
        viewBinding.zebraButton.setOnLongClickListener(view -> {
            showZebraSettings();
            return true;
        });
    }

    private void setZebraEnabled(boolean enabled) {
        viewBinding.openGLVideoView.setZebraOverlayEnabled(enabled);
        viewBinding.zebraButton.setActivated(enabled);
        new SettingsStore().setZebraEnabled(enabled);
    }

    private void showZebraSettings() {
        SettingsStore settingsStore = new SettingsStore();

        ToolSettingsPopup popup = new ToolSettingsPopup(this, getString(R.string.zebra_settings_title))
                .addSlider(getString(R.string.zebra_settings_threshold), ZEBRA_THRESHOLD_MIN, ZEBRA_THRESHOLD_MAX,
                        settingsStore.getZebraThreshold(), value -> getString(R.string.percent_value, value),
                        value -> {
                            settingsStore.setZebraThreshold(value);
                            viewBinding.openGLVideoView.setZebraThreshold(value);
                            // the stripes show where the threshold lies, so they are needed while setting it
                            if (!settingsStore.isZebraEnabled()) {
                                setZebraEnabled(true);
                            }
                        });
        showToolSettings(popup, viewBinding.zebraButton);
    }

    private void initializeToggleFocusAssistButton() {
        viewBinding.focusAssistButton.setOnClickListener(view -> {
            extendMenuTimeout();
            setFocusAssistEnabled(!new SettingsStore().isFocusAssistEnabled());
        });
        viewBinding.focusAssistButton.setOnLongClickListener(view -> {
            showFocusPeakingSettings();
            return true;
        });
    }

    private void setFocusAssistEnabled(boolean enabled) {
        viewBinding.openGLVideoView.setFocusAssistEnabled(enabled);
        viewBinding.focusAssistButton.setActivated(enabled);
        new SettingsStore().setFocusAssistEnabled(enabled);
    }

    private void showFocusPeakingSettings() {
        SettingsStore settingsStore = new SettingsStore();
        FocusPeakingColor[] colors = FocusPeakingColor.values();

        List<ToolSettingsPopup.Choice> choices = new ArrayList<>();
        for (FocusPeakingColor color : colors) {
            choices.add(new ToolSettingsPopup.Choice(getString(color.getLabel()), color.getColor()));
        }

        ToolSettingsPopup popup = new ToolSettingsPopup(this, getString(R.string.peaking_settings_title))
                .addChoices(getString(R.string.peaking_settings_color), choices,
                        settingsStore.getFocusPeakingColor().ordinal(), index -> {
                            settingsStore.setFocusPeakingColor(colors[index]);
                            viewBinding.openGLVideoView.setFocusPeakingColor(colors[index].getColor());
                            // the new color is only visible with peaking on
                            if (!settingsStore.isFocusAssistEnabled()) {
                                setFocusAssistEnabled(true);
                            }
                        });
        showToolSettings(popup, viewBinding.focusAssistButton);
    }

    /**
     * Opens a tool's settings above its button. The HUD stays open while they are shown.
     */
    private void showToolSettings(ToolSettingsPopup popup, View toolButton) {
        dismissToolSettings();
        menuHandler.removeCallbacks(hideMenuCallback);

        popup.setOnDismissListener(this::extendMenuTimeout);
        toolSettingsPopup = popup;
        popup.showAbove(toolButton);
    }

    /**
     * @return whether tool settings were open
     */
    private boolean dismissToolSettings() {
        ToolSettingsPopup popup = toolSettingsPopup;
        toolSettingsPopup = null;
        if (popup != null && popup.isShowing()) {
            popup.dismiss();
            return true;
        }
        return false;
    }

    private void initializeToggleGridButton() {
        viewBinding.gridButton.setOnClickListener(view -> {
            extendMenuTimeout();
            SettingsStore settingsStore = new SettingsStore();
            if (viewBinding.framingHelperOverlayView.getVisibility() == View.VISIBLE) {
                viewBinding.framingHelperOverlayView.setVisibility(View.GONE);
                viewBinding.gridButton.setActivated(false);
                settingsStore.setFramingHelperOverlayEnabled(false);
            } else {
                viewBinding.framingHelperOverlayView.setVisibility(View.VISIBLE);
                viewBinding.gridButton.setActivated(true);
                settingsStore.setFramingHelperOverlayEnabled(true);
            }
        });
    }

    private void initializeTallyButton() {
        // tally is NDI-only
        if (videoSource instanceof UVCSource) {
            viewBinding.tallyButton.setVisibility(View.GONE);
            return;
        }

        viewBinding.tallyButton.setOnClickListener(view -> {
            extendMenuTimeout();
            SettingsStore settingsStore = new SettingsStore();

            boolean enabled = !viewBinding.tallyOverlayView.isTallyEnabled();

            viewBinding.tallyOverlayView.setTallyEnabled(enabled);
            viewBinding.tallyOverlayView.setVisibility(enabled ? View.VISIBLE : View.GONE);
            viewBinding.tallyButton.setActivated(enabled);
            settingsStore.setTallyEnabled(enabled);
        });
    }

    private void initializeAudioButton() {
        // audio is NDI-only
        if (videoSource instanceof UVCSource) {
            viewBinding.audioButton.setVisibility(View.GONE);
            return;
        }

        showAudioEnabled(new SettingsStore().isAudioEnabled());
        viewBinding.audioButton.setOnClickListener(view -> {
            extendMenuTimeout();
            SettingsStore settingsStore = new SettingsStore();

            boolean enabled = !settingsStore.isAudioEnabled();
            settingsStore.setAudioEnabled(enabled);
            showAudioEnabled(enabled);
            if (runner != null) {
                runner.setAudioEnabled(enabled);
            }
        });
    }

    private void showAudioEnabled(boolean enabled) {
        viewBinding.audioButton.setActivated(enabled);
        if (viewerMode) {
            return;
        }
        // without audio there are no levels to show
        viewBinding.audioMeterView.setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
    }

    private void initHud() {
        boolean isUvc = videoSource instanceof UVCSource;

        viewBinding.sourceTypeBadge.setText(isUvc ? R.string.badge_usb : R.string.badge_ndi);
        viewBinding.sourceTypeBadge.setBackgroundResource(isUvc ? R.drawable.bg_badge_usb : R.drawable.bg_badge_ndi);
        viewBinding.sourceNameText.setText(videoSource != null ? videoSource.getSourceName() : null);

        // tally and audio only exist for NDI sources
        viewBinding.tallyChip.setVisibility(isUvc ? View.GONE : View.VISIBLE);
        if (isUvc) {
            viewBinding.audioMeterView.setVisibility(View.GONE);
        }
        viewBinding.lowBandwidthChip.setVisibility(
                !isUvc && new SettingsStore().isLowBandwidthModeEnabled() ? View.VISIBLE : View.GONE);
    }

    /**
     * Called on the main thread by the stream runner for every rendered frame.
     *
     * @param frameRate nominal or measured frames per second, or 0 if unknown
     */
    public void onVideoFormat(int width, int height, float frameRate, String format) {
        if (viewerMode) {
            return;
        }
        if (width != lastVideoWidth || height != lastVideoHeight) {
            lastVideoWidth = width;
            lastVideoHeight = height;
            viewBinding.resolutionChip.setText(String.format(Locale.US, "%d×%d", width, height));
        }
        if (frameRate != lastFrameRate) {
            lastFrameRate = frameRate;
            setChip(viewBinding.fpsChip, frameRate > 0 ? formatFrameRate(frameRate) : null);
        }
        if (!format.equals(lastFormat)) {
            lastFormat = format;
            setChip(viewBinding.formatChip, format);
        }
    }

    private static String formatFrameRate(float frameRate) {
        if (Math.abs(frameRate - Math.round(frameRate)) < 0.01f) {
            return String.format(Locale.US, "%d fps", Math.round(frameRate));
        }
        return String.format(Locale.US, "%.2f fps", frameRate);
    }

    private static void setChip(TextView chip, String text) {
        chip.setText(text);
        chip.setVisibility(text != null ? View.VISIBLE : View.GONE);
    }

    /**
     * Called on the main thread whenever the NDI tally state is received.
     */
    public void onTallyState(boolean onProgram, boolean onPreview) {
        TextView chip = viewBinding.tallyChip;
        if (onProgram) {
            chip.setText(R.string.hud_tally_program);
            chip.setBackgroundResource(R.drawable.bg_tally_program);
            chip.setTextColor(getColor(R.color.white));
        } else if (onPreview) {
            chip.setText(R.string.hud_tally_preview);
            chip.setBackgroundResource(R.drawable.bg_tally_preview);
            chip.setTextColor(getColor(R.color.white));
        } else {
            chip.setText(R.string.hud_tally_idle);
            chip.setBackgroundResource(R.drawable.bg_hud_chip);
            chip.setTextColor(getColor(R.color.fw_text_secondary));
        }
    }

    /**
     * Called on the main thread once per display frame with linear peak levels (0..1).
     */
    public void onAudioLevels(float left, float right) {
        if (hudShown) {
            viewBinding.audioMeterView.setLevels(left, right);
        }
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
    protected void onStart() {
        super.onStart();

        // MainActivity releases its multicast lock once it is hidden. Holding our own keeps mDNS
        // discovery alive, so the NDI finder still knows the sources when the stream ends.
        if (videoSource instanceof NdiSource) {
            if (ndiDiscoveryManager == null) {
                ndiDiscoveryManager = new NdiDiscoveryManager(getApplicationContext());
            }
            ndiDiscoveryManager.startDiscovery();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (ndiDiscoveryManager != null) {
            ndiDiscoveryManager.stopDiscovery();
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
        dismissToolSettings();

        if (runner != null) {
            runner.shutdown();
            runner = null;
        }
    }
}
