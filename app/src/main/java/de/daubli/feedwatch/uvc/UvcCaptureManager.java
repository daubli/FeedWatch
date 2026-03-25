package de.daubli.feedwatch.uvc;

import android.content.*;
import android.hardware.usb.*;
import android.util.Log;

import java.util.Arrays;

public class UvcCaptureManager {
    private static final String TAG = "UvcCaptureManager";
    private static final int USB_TYPE_CLASS = (0x01 << 5);
    private static final int USB_RECIP_INTERFACE = 0x01;
    private static final int USB_DIR_OUT = 0x00;

    private static final int SET_CUR = 0x01;
    private static final int VS_PROBE_CONTROL = 0x01;
    private static final int VS_COMMIT_CONTROL = 0x02;
    private final UsbManager usbManager;
    private final UVCSource uvcSource;
    private UsbDeviceConnection connection;
    private UsbInterface intf;
    private UsbEndpoint endpointIn;
    private boolean capturing = false;
    private FrameCallback callback;

    public interface FrameCallback {
        void onFrame(byte[] data);
    }

    public UvcCaptureManager(UVCSource source, Context context) {
        this.uvcSource = source;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public void start(FrameCallback callback) {
        this.callback = callback;

        UsbDevice device = uvcSource.getUsbDevice();
        openDevice(device);
    }

    private void openDevice(UsbDevice device) {
        // Search Interface with Class = Video (14), Subclass = Streaming (2)
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            if (candidate.getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO &&
                    candidate.getInterfaceSubclass() == 2) {
                intf = candidate;
                break;
            }
        }

        if (intf == null) {
            Log.e(TAG, "No video streaming interface found");
            return;
        }

        connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "Could not open device");
            return;
        }

        if (!connection.claimInterface(intf, true)) {
            Log.e(TAG, "Could not claim interface");
            return;
        }

        if (!startUvcStreaming(device, connection, intf.getId())) {
            Log.e(TAG, "Failed to start UVC stream");
            return;
        }

        // search BULK IN Endpoint
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getDirection() == UsbConstants.USB_DIR_IN &&
                    ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                endpointIn = ep;
                break;
            }
        }

        if (endpointIn == null) {
            Log.e(TAG, "No input endpoint found");
            return;
        }

        capturing = true;
        new Thread(this::captureLoop).start();
    }


    private void captureLoop() {
        final int bufferSize = 1024 * 1024;
        byte[] buffer = new byte[bufferSize];

        while (capturing) {
            int read = connection.bulkTransfer(endpointIn, buffer, buffer.length, 1000);

            if (read > 0) {
                if (callback != null) {
                    byte[] frame = Arrays.copyOf(buffer, read);
                    callback.onFrame(frame);
                }
            } else if (read == 0) {
                Log.w(TAG, "bulkTransfer returned 0 (no data)");
            } else {
                Log.e(TAG, "bulkTransfer failed (read=" + read + ")");

                // prevent busy waiting
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private boolean startUvcStreaming(UsbDevice device, UsbDeviceConnection connection, int streamingInterfaceIndex) {
        UsbInterface vsInterface = device.getInterface(streamingInterfaceIndex);
        connection.claimInterface(vsInterface, true);

        int maxPayload = 1024; // z.B. 1024 Bytes für MJPEG/YUY2

        // UVC 1.1 probe control (26 bytes for USB 2.0 devices)
        byte[] probe = new byte[26];

        // Fill PROBE structure
        probe[0] = 0x01; // bmHint
        probe[2] = 0x01; // bFormatIndex (e.g., YUY2 or MJPEG format)
        probe[3] = 0x01; // bFrameIndex (first frame type)
        // dwFrameInterval (e.g., 333333 for 30 fps) little endian
        probe[4] = (byte) (0x15);
        probe[5] = (byte) (0x16);
        probe[6] = (byte) (0x05);
        probe[7] = (byte) (0x00);
        // dwMaxVideoFrameSize: (e.g., 640*480*2 = 614400 bytes)
        probe[18] = (byte) (0x00);
        probe[19] = (byte) (0x64);
        probe[20] = (byte) (0x09);
        probe[21] = (byte) (0x00);
        // dwMaxPayloadTransferSize
        probe[22] = (byte) (maxPayload & 0xff);
        probe[23] = (byte) ((maxPayload >> 8) & 0xff);

        int requestType = USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_INTERFACE;

        int result = connection.controlTransfer(
                requestType,
                SET_CUR,
                (VS_PROBE_CONTROL << 8),
                vsInterface.getId(),
                probe,
                probe.length,
                1000
        );

        if (result < 0) {
            Log.e(TAG, "SET_CUR PROBE failed");
            return false;
        }

        // Repeat same PROBE buffer for COMMIT
        result = connection.controlTransfer(
                requestType,
                SET_CUR,
                (VS_COMMIT_CONTROL << 8),
                vsInterface.getId(),
                probe,
                probe.length,
                1000
        );

        if (result < 0) {
            Log.e(TAG, "SET_CUR COMMIT failed");
            return false;
        }

        UsbInterface altInterface = findStreamingAltInterface(device, streamingInterfaceIndex, 1);

        if (altInterface == null) {
            Log.w(TAG, "Alternate setting 1 not found, falling back to default interface");
            altInterface = findStreamingAltInterface(device, streamingInterfaceIndex, 0);
            if (altInterface == null) {
                Log.e(TAG, "Alternate setting 0 not found either");
                return false;
            }
        }

        connection.releaseInterface(intf);

        boolean success = connection.claimInterface(altInterface, true);
        if (!success) {
            Log.e(TAG, "Failed to claim interface (alt setting " + altInterface.getAlternateSetting() + ")");
            return false;
        }

        Log.i(TAG, "UVC streaming started using alternate setting " + altInterface.getAlternateSetting());
        return true;
    }

    private UsbInterface findStreamingAltInterface(UsbDevice device, int baseInterfaceIndex, int alternateSetting) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getId() == baseInterfaceIndex && iface.getAlternateSetting() == alternateSetting) {
                return iface;
            }
        }
        return null;
    }

    public void stop() {
        capturing = false;
        if (connection != null && intf != null) {
            connection.releaseInterface(intf);
            connection.close();
        }
    }
}
