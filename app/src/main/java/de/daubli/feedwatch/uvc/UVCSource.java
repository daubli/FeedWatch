package de.daubli.feedwatch.uvc;

import android.hardware.usb.UsbDevice;
import de.daubli.feedwatch.sources.VideoSource;

public class UVCSource implements VideoSource {

    UsbDevice usbDevice;

    public UVCSource(UsbDevice usbDevice) {
        this.usbDevice = usbDevice;
    }

    public UsbDevice getUsbDevice() {
        return usbDevice;
    }

    @Override
    public String getSourceName() {
        return usbDevice.getProductName();
    }
}
