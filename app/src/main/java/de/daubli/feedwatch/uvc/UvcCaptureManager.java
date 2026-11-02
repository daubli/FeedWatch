package de.daubli.feedwatch.uvc;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.usb.UsbDevice;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * UVC capture through libuvc (UVCAndroid).
 *
 * <p>libuvc also drives isochronous endpoints, which is what most HDMI capture sticks
 * (for example the MACROSILICON MS2109) use. The original bulk-only implementation could
 * not read those devices at all.</p>
 *
 * <p>Frames are delivered as packed RGBX. libuvc decodes MJPEG or YUYV natively, so the
 * app does not need to decode JPEG in Java and the frame rate stays usable on older SoCs.</p>
 */
public class UvcCaptureManager {
    private static final String TAG = "UvcCaptureManager";

    private static final long OPEN_TIMEOUT_MS = 15_000;
    private static final long CLOSE_TIMEOUT_MS = 3_000;

    /** A field monitor does not need more than this, and it keeps the old tablet responsive. */
    private static final int PREFERRED_WIDTH = 1280;
    private static final int PREFERRED_HEIGHT = 720;
    private static final int MAX_WIDTH = 1920;
    private static final int MAX_HEIGHT = 1080;

    private final UVCSource uvcSource;
    private final Context context;

    private USBMonitor usbMonitor;
    private USBMonitor.UsbControlBlock ctrlBlock;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private UVCCamera camera;
    private ImageReader previewSink;

    private volatile boolean capturing = false;
    private FrameCallback callback;

    private final CountDownLatch deviceLatch = new CountDownLatch(1);
    private final AtomicReference<Exception> openError = new AtomicReference<>();

    public interface FrameCallback {
        /**
         * @param frame  packed RGBX pixels, only valid during the call
         * @param width  frame width
         * @param height frame height
         */
        void onFrame(ByteBuffer frame, int width, int height);
    }

    public UvcCaptureManager(UVCSource source, Context context) {
        this.uvcSource = source;
        this.context = context.getApplicationContext();
    }

    public void start(FrameCallback callback) {
        this.callback = callback;
        final UsbDevice device = uvcSource.getUsbDevice();

        cameraThread = new HandlerThread("UVC-Camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        usbMonitor = new USBMonitor(context, new USBMonitor.OnDeviceConnectListener() {
            @Override
            public void onAttach(UsbDevice d) {
            }

            @Override
            public void onDetach(UsbDevice d) {
            }

            @Override
            public void onDeviceOpen(UsbDevice d, USBMonitor.UsbControlBlock block, boolean createNew) {
                ctrlBlock = block;
                deviceLatch.countDown();
            }

            @Override
            public void onDeviceClose(UsbDevice d, USBMonitor.UsbControlBlock block) {
            }

            @Override
            public void onCancel(UsbDevice d) {
                openError.compareAndSet(null,
                        new Exception("USB permission was not granted for " + d.getDeviceName()));
                deviceLatch.countDown();
            }

            @Override
            public void onError(UsbDevice d, USBMonitor.USBException e) {
                openError.compareAndSet(null, e);
                deviceLatch.countDown();
            }
        }, new Handler(Looper.getMainLooper()));

        usbMonitor.register();
        usbMonitor.requestPermission(device);

        if (!await(deviceLatch, OPEN_TIMEOUT_MS)) {
            openError.compareAndSet(null, new Exception("Timed out waiting for the USB device"));
        }

        Exception error = openError.get();
        if (error != null) {
            Log.e(TAG, "Could not open the UVC device", error);
            stop();
            return;
        }

        if (ctrlBlock == null) {
            Log.e(TAG, "USB device opened without a control block");
            stop();
            return;
        }

        final CountDownLatch cameraLatch = new CountDownLatch(1);
        cameraHandler.post(() -> {
            try {
                openCamera(ctrlBlock);
            } catch (Exception e) {
                openError.compareAndSet(null, e);
            } finally {
                cameraLatch.countDown();
            }
        });

        if (!await(cameraLatch, OPEN_TIMEOUT_MS)) {
            openError.compareAndSet(null, new Exception("Timed out while opening the camera"));
        }

        error = openError.get();
        if (error != null) {
            Log.e(TAG, "Could not start the UVC stream", error);
            stop();
        }
    }

    /** Runs on the camera thread, which has a Looper as required by UVCCamera. */
    private void openCamera(USBMonitor.UsbControlBlock block) throws Exception {
        UVCCamera opened = new UVCCamera(null);
        camera = opened;

        int result = opened.open(block);
        if (result != 0) {
            throw new IllegalStateException("UVCCamera.open returned " + result);
        }

        List<Size> sizes = opened.getSupportedSizeList();
        logSupportedModes(sizes);

        Size mode = chooseMode(sizes);
        if (mode == null) {
            throw new IllegalStateException("The device reports no video mode");
        }

        Log.i(TAG, String.format(Locale.US, "Using %dx%d @ %d fps, type=0x%02x",
                mode.width, mode.height, mode.fps, mode.type));

        opened.setPreviewSize(mode);

        // libuvc only starts its capture thread when a preview window is set, even when the
        // frames are consumed through the callback. Drain the window so it never blocks.
        previewSink = ImageReader.newInstance(mode.width, mode.height, PixelFormat.RGBA_8888, 2);
        previewSink.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
            } catch (Exception e) {
                Log.w(TAG, "Could not acquire the preview buffer", e);
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }, cameraHandler);

        Surface surface = previewSink.getSurface();
        opened.setPreviewDisplay(surface);

        final int width = mode.width;
        final int height = mode.height;

        opened.setFrameCallback(new IFrameCallback() {
            @Override
            public void onFrame(ByteBuffer frame) {
                if (!capturing || callback == null || frame == null || frame.remaining() <= 0) {
                    return;
                }
                callback.onFrame(frame, width, height);
            }
        }, UVCCamera.PIXEL_FORMAT_RGBX);

        capturing = true;
        opened.startPreview();
        Log.i(TAG, "UVC stream started");
    }

    /**
     * Prefers MJPEG, a 16:9 frame, 720p when the device offers it, and never more than 1080p.
     */
    private Size chooseMode(List<Size> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }

        Size mjpeg720 = null;
        Size mjpegFhd = null;
        Size mjpegAny = null;
        Size any = null;

        for (Size size : sizes) {
            if (any == null) {
                any = size;
            }

            boolean isMjpeg = size.type == UVCCamera.UVC_VS_FRAME_MJPEG;
            if (!isMjpeg) {
                continue;
            }
            if (mjpegAny == null) {
                mjpegAny = size;
            }

            boolean is16x9 = size.width * 9 == size.height * 16;
            if (!is16x9) {
                continue;
            }

            if (size.width == PREFERRED_WIDTH && size.height == PREFERRED_HEIGHT && mjpeg720 == null) {
                mjpeg720 = size;
            }
            if (size.width <= MAX_WIDTH && size.height <= MAX_HEIGHT
                    && (mjpegFhd == null
                    || (long) size.width * size.height > (long) mjpegFhd.width * mjpegFhd.height)) {
                mjpegFhd = size;
            }
        }

        if (mjpeg720 != null) {
            return mjpeg720;
        }
        if (mjpegFhd != null) {
            return mjpegFhd;
        }
        if (mjpegAny != null) {
            return mjpegAny;
        }
        return any;
    }

    private void logSupportedModes(List<Size> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            Log.w(TAG, "The device reports no supported video modes");
            return;
        }
        StringBuilder builder = new StringBuilder("Supported modes:");
        for (Size size : sizes) {
            builder.append(String.format(Locale.US, " %dx%d@%d(type=0x%02x)",
                    size.width, size.height, size.fps, size.type));
        }
        Log.i(TAG, builder.toString());
    }

    private static boolean await(CountDownLatch latch, long timeoutMs) {
        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void stop() {
        capturing = false;

        final UVCCamera current = camera;
        camera = null;

        if (cameraHandler != null && current != null) {
            final CountDownLatch closed = new CountDownLatch(1);
            cameraHandler.post(() -> {
                try {
                    current.stopPreview();
                } catch (Exception ignored) {
                }
                try {
                    current.close();
                } catch (Exception ignored) {
                }
                try {
                    current.destroy();
                } catch (Exception ignored) {
                }
                closed.countDown();
            });
            await(closed, CLOSE_TIMEOUT_MS);
        }

        if (previewSink != null) {
            try {
                previewSink.close();
            } catch (Exception ignored) {
            }
            previewSink = null;
        }

        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }

        if (usbMonitor != null) {
            try {
                usbMonitor.destroy();
            } catch (Exception ignored) {
            }
            usbMonitor = null;
        }

        ctrlBlock = null;
    }
}
