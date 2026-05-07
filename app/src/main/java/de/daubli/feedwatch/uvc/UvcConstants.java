package de.daubli.feedwatch.uvc;

public class UvcConstants {
    //UVC Class-Specific Interface Descriptor
    static final int CS_INTERFACE = 0x24;

    static final int USB_TYPE_CLASS = (0x01 << 5);
    static final int USB_RECIP_INTERFACE = 0x01;
    static final int USB_DIR_OUT = 0x00;

    static final int SET_CUR = 0x01;
    static final int VS_PROBE_CONTROL = 0x01;
    static final int VS_COMMIT_CONTROL = 0x02;

}
