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

    private boolean isUvcDevice(UsbDevice device) {
        return device.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO
                || (device.getInterfaceCount() > 0 &&
                device.getInterface(0).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO);
    }
}
