package de.daubli.feedwatch.uvc;

import static de.daubli.feedwatch.uvc.UvcConstants.*;
import static de.daubli.feedwatch.uvc.utils.ByteOrderUtils.putLe32;

import android.content.Context;
import android.hardware.usb.*;
import android.util.Log;

import java.io.ByteArrayOutputStream;

public class UvcCaptureManager {
    private static final String TAG = "UvcCaptureManager";

    private final UsbManager usbManager;
    private final UVCSource uvcSource;

    private UsbDeviceConnection connection;
    private UsbInterface intf;
    private UsbEndpoint endpointIn;

    private volatile boolean capturing = false;
    private Thread captureThread;
    private FrameCallback callback;

    public interface FrameCallback {
        void onFrame(byte[] jpegFrame);
    }

    public UvcCaptureManager(UVCSource source, Context context) {
        this.uvcSource = source;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public void start(FrameCallback callback) {
        this.callback = callback;
        openDevice(uvcSource.getUsbDevice());
    }

    private void openDevice(UsbDevice device) {
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
            connection.close();
            return;
        }

        if (!startUvcStreaming(device, connection, intf.getId())) {
            Log.e(TAG, "Failed to start UVC stream");
            stop();
            return;
        }

        endpointIn = null;

        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getDirection() == UsbConstants.USB_DIR_IN &&
                    ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                endpointIn = ep;
                break;
            }
        }

        if (endpointIn == null) {
            Log.e(TAG, "No BULK IN endpoint found");
            stop();
            return;
        }

        capturing = true;
        captureThread = new Thread(this::captureLoop, "UVC-Capture-Thread");
        captureThread.start();
    }

    private void captureLoop() {
        byte[] packet = new byte[1024 * 1024];

        ByteArrayOutputStream currentFrame = new ByteArrayOutputStream(1024 * 1024);
        int lastFid = -1;

        while (capturing && connection != null && endpointIn != null) {
            int read = connection.bulkTransfer(endpointIn, packet, packet.length, 1000);

            if (read <= 0) {
                if (read < 0 && capturing) {
                    Log.w(TAG, "bulkTransfer failed: " + read);
                }
                continue;
            }

            if (read < 2) {
                continue;
            }

            int payloadOffset = packet[0] & 0xff;
            if (payloadOffset == 0 || payloadOffset >= read) {
                continue;
            }

            int flags = packet[1] & 0xff;

            boolean fid = (flags & 0x01) != 0;
            boolean eof = (flags & 0x02) != 0;
            boolean error = (flags & 0x40) != 0;

            if (error) {
                currentFrame.reset();
                continue;
            }

            int fidValue = fid ? 1 : 0;

            if (lastFid != -1 && fidValue != lastFid && currentFrame.size() > 0) {
                emitFrameIfJpeg(currentFrame);
                currentFrame.reset();
            }

            lastFid = fidValue;

            int payloadLength = read - payloadOffset;
            currentFrame.write(packet, payloadOffset, payloadLength);

            if (eof && currentFrame.size() > 0) {
                emitFrameIfJpeg(currentFrame);
                currentFrame.reset();
            }
        }
    }

    private void emitFrameIfJpeg(ByteArrayOutputStream frameBuffer) {
        byte[] frame = frameBuffer.toByteArray();

        if (frame.length < 4) {
            return;
        }

        boolean startsWithSoi =
                (frame[0] & 0xff) == 0xff &&
                        (frame[1] & 0xff) == 0xd8;

        boolean endsWithEoi =
                (frame[frame.length - 2] & 0xff) == 0xff &&
                        (frame[frame.length - 1] & 0xff) == 0xd9;

        if (!startsWithSoi || !endsWithEoi) {
            Log.w(TAG, "Dropping incomplete MJPEG frame, size=" + frame.length);
            return;
        }

        if (callback != null) {
            callback.onFrame(frame);
        }
    }

    private boolean startUvcStreaming(
            UsbDevice device,
            UsbDeviceConnection connection,
            int streamingInterfaceId
    ) {
        UvcMode mode = UvcModeFinder.findBestMjpegMode(connection);

        if (mode == null) {
            Log.e(TAG, "No MJPEG mode found");
            return false;
        }

        Log.i(TAG, "Using MJPEG mode: " +
                mode.width + "x" + mode.height +
                ", formatIndex=" + mode.formatIndex +
                ", frameIndex=" + mode.frameIndex +
                ", interval=" + mode.frameInterval +
                ", maxFrameSize=" + mode.maxFrameSize);

        byte[] probe = new byte[26];

        // bmHint: 0 means let the device adjust fields if needed.
        // 1 means dwFrameInterval is important.
        probe[0] = 0x01;
        probe[1] = 0x00;

        // Use real descriptor values, not hardcoded 1/1.
        probe[2] = (byte) mode.formatIndex;
        probe[3] = (byte) mode.frameIndex;

        putLe32(probe, 4, mode.frameInterval);

        // bytes 8-17 are optional bitrate/clock fields; leave as 0.
        putLe32(probe, 18, mode.maxFrameSize);

        int maxPayload;
        if (endpointIn != null) {
            maxPayload = endpointIn.getMaxPacketSize();
        } else {
            maxPayload = 1024;
        }

        putLe32(probe, 22, maxPayload);

        int requestType = USB_DIR_OUT | USB_TYPE_CLASS | USB_RECIP_INTERFACE;

        int result = connection.controlTransfer(
                requestType,
                SET_CUR,
                VS_PROBE_CONTROL << 8,
                streamingInterfaceId,
                probe,
                probe.length,
                1000
        );

        if (result < 0) {
            Log.e(TAG, "SET_CUR PROBE failed");
            return false;
        }

        result = connection.controlTransfer(
                requestType,
                SET_CUR,
                VS_COMMIT_CONTROL << 8,
                streamingInterfaceId,
                probe,
                probe.length,
                1000
        );

        if (result < 0) {
            Log.e(TAG, "SET_CUR COMMIT failed");
            return false;
        }

        UsbInterface altInterface = findBestStreamingAltInterface(device, streamingInterfaceId);

        if (altInterface != null && altInterface != intf) {
            connection.releaseInterface(intf);

            if (!connection.claimInterface(altInterface, true)) {
                Log.e(TAG, "Failed to claim alternate interface " +
                        altInterface.getAlternateSetting());
                return false;
            }

            intf = altInterface;
        }

        Log.i(TAG, "UVC streaming started");
        return true;
    }

    private UsbInterface findBestStreamingAltInterface(
            UsbDevice device,
            int interfaceId
    ) {
        UsbInterface best = null;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);

            if (iface.getId() != interfaceId) {
                continue;
            }

            if (iface.getAlternateSetting() == 0) {
                continue;
            }

            if (best == null ||
                    iface.getAlternateSetting() > best.getAlternateSetting()) {
                best = iface;
            }
        }

        return best;
    }

    public void stop() {
        capturing = false;

        if (captureThread != null) {
            captureThread.interrupt();
            captureThread = null;
        }

        if (connection != null) {
            try {
                if (intf != null) {
                    connection.releaseInterface(intf);
                }
            } catch (Exception ignored) {
            }

            connection.close();
            connection = null;
        }

        endpointIn = null;
        intf = null;
    }
}
