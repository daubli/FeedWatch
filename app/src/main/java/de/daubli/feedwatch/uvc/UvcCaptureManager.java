package de.daubli.feedwatch.uvc;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

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
 * UVC capture based on libuvc through the UVCAndroid library.
 *
 * <p>Unlike a plain USB bulk transfer, libuvc also handles isochronous endpoints, which is what
 * most HDMI capture sticks use. It enumerates the formats and frame sizes the device really
 * offers instead of assuming a MJPEG/bulk layout.</p>
 */
public class UvcCaptureManager {
    private static final String TAG = "UvcCaptureManager";

    private static final long OPEN_TIMEOUT_MS = 15_000;
    private static final long CLOSE_TIMEOUT_MS = 3_000;

    private final UVCSource uvcSource;
    private final Context context;

    private USBMonitor usbMonitor;
    private USBMonitor.UsbControlBlock ctrlBlock;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private UVCCamera camera;

    private volatile boolean capturing = false;
    private FrameCallback callback;

    private final CountDownLatch deviceLatch = new CountDownLatch(1);
    private final AtomicReference<Exception> openError = new AtomicReference<>();

    public interface FrameCallback {
        void onFrame(byte[] jpegFrame);
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
            throw new IllegalStateException("The device reports no MJPEG or uncompressed video mode");
        }

        Log.i(TAG, String.format(Locale.US,
                "Using %dx%d @ %d fps, type=0x%02x", mode.width, mode.height, mode.fps, mode.type));

        opened.setPreviewSize(mode);

        opened.setFrameCallback(new IFrameCallback() {
            @Override
            public void onFrame(ByteBuffer frame) {
                if (!capturing || callback == null || frame == null) {
                    return;
                }
                int length = frame.remaining();
                if (length <= 0) {
                    return;
                }
                byte[] data = new byte[length];
                frame.get(data, 0, length);
                callback.onFrame(data);
            }
        }, UVCCamera.PIXEL_FORMAT_RAW);

        capturing = true;
        opened.startPreview();
        Log.i(TAG, "UVC stream started");
    }

    /**
     * Prefers MJPEG (what the rest of the app decodes), then 16:9, then the largest frame.
     */
    private Size chooseMode(List<Size> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }

        Size best = null;
        for (Size size : sizes) {
            if (best == null) {
                best = size;
                continue;
            }

            boolean sizeIsMjpeg = size.type == UVCCamera.UVC_VS_FRAME_MJPEG;
            boolean bestIsMjpeg = best.type == UVCCamera.UVC_VS_FRAME_MJPEG;
            if (sizeIsMjpeg != bestIsMjpeg) {
                if (sizeIsMjpeg) {
                    best = size;
                }
                continue;
            }

            boolean sizeIs16x9 = size.width * 9 == size.height * 16;
            boolean bestIs16x9 = best.width * 9 == best.height * 16;
            if (sizeIs16x9 != bestIs16x9) {
                if (sizeIs16x9) {
                    best = size;
                }
                continue;
            }

            if ((long) size.width * size.height > (long) best.width * best.height) {
                best = size;
            }
        }
        return best;
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
