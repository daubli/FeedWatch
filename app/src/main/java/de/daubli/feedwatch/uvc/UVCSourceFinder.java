package de.daubli.feedwatch.uvc;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.util.HashMap;

public class UVCSourceFinder {
    private final Context context;
    private final UsbManager usbManager;

    public UVCSourceFinder(Context context) {
        this.context = context.getApplicationContext();
        this.usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
    }

    public UVCSource[] getUvcSources() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        return deviceList.values().stream().filter(this::isUvcDevice).map(UVCSource::new).toArray(UVCSource[]::new);
    }

    // Composite devices (e.g. DJI Osmo Action: vendor DIAG interface first, then UAC2, then UVC)
    // don't put the video interface at index 0, so look at every interface.
    private boolean isUvcDevice(UsbDevice device) {
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO) {
            return true;
        }
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) {
                return true;
            }
        }
        return false;
    }
}
