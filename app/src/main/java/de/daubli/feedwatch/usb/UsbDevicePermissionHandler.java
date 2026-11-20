package de.daubli.feedwatch.usb;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class UsbDevicePermissionHandler {
    private static final String TAG = "UsbDevicePermissionHandler";
    private static final String ACTION_USB_PERMISSION = "de.daubli.feedwatch.USB_PERMISSION";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 101;

    private final Activity activity;
    private final UsbManager usbManager;

    // the device and callback waiting for the answer to the runtime permission dialog
    private UsbDevice pendingDevice;
    private Callback pendingCallback;

    public interface Callback {
        void onPermissionGranted(UsbDevice device);

        void onPermissionDenied(UsbDevice device);
    }

    public UsbDevicePermissionHandler(Activity activity) {
        this.activity = activity;
        this.usbManager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
    }

    /**
     * Asks for the camera permission, the microphone permission for the card's audio and the USB permission, then
     * calls back. Without the microphone permission the card still opens, only without audio. The activity has to
     * pass its onRequestPermissionsResult on to {@link #onRequestPermissionsResult}.
     */
    public void requestPermission(UsbDevice device, Callback callback) {
        List<String> missing = new ArrayList<>();
        for (String permission : new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}) {
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (!missing.isEmpty()) {
            pendingDevice = device;
            pendingCallback = callback;
            ActivityCompat.requestPermissions(activity, missing.toArray(new String[0]), CAMERA_PERMISSION_REQUEST_CODE);
            return;
        }

        requestUsbPermission(device, callback);
    }

    /**
     * Continues a request once the runtime permission dialog was answered.
     *
     * @return false if the result belongs to another request
     */
    public boolean onRequestPermissionsResult(int requestCode) {
        if (requestCode != CAMERA_PERMISSION_REQUEST_CODE) {
            return false;
        }

        UsbDevice device = pendingDevice;
        Callback callback = pendingCallback;
        pendingDevice = null;
        pendingCallback = null;
        if (device == null || callback == null) {
            return true;
        }

        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission denied");
            callback.onPermissionDenied(device);
            return true;
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Microphone permission denied, the card opens without audio");
        }

        requestUsbPermission(device, callback);
        return true;
    }

    private void requestUsbPermission(UsbDevice device, Callback callback) {
        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "Already has permission: " + device.getDeviceName());
            callback.onPermissionGranted(device);
            return;
        }

        Log.d(TAG, "Requesting USB permission for: " + device.getDeviceName());

        Intent permissionIntent = new Intent(ACTION_USB_PERMISSION);
        permissionIntent.setPackage(activity.getPackageName());

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                activity,
                0,
                permissionIntent,
                flags
        );

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;

                UsbDevice receivedDevice;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    receivedDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
                } else {
                    receivedDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                }

                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

                try {
                    activity.unregisterReceiver(this);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Receiver already unregistered");
                }

                if (receivedDevice == null) {
                    Log.e(TAG, "Received permission broadcast, but device is null");
                    return;
                }

                if (granted) {
                    Log.d(TAG, "USB permission granted for: " + receivedDevice.getDeviceName());
                    callback.onPermissionGranted(receivedDevice);
                } else {
                    Log.w(TAG, "USB permission denied for: " + receivedDevice.getDeviceName());
                    callback.onPermissionDenied(receivedDevice);
                }
            }
        };

        // the broadcast comes from our own PendingIntent
        ContextCompat.registerReceiver(activity, receiver, new IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        usbManager.requestPermission(device, pendingIntent);
    }
}
