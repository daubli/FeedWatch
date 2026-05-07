package de.daubli.feedwatch.uvc;

import static de.daubli.feedwatch.decoder.MJpegConstants.VS_FORMAT_MJPEG;
import static de.daubli.feedwatch.decoder.MJpegConstants.VS_FRAME_MJPEG;
import static de.daubli.feedwatch.uvc.UvcConstants.CS_INTERFACE;
import static de.daubli.feedwatch.uvc.utils.ByteOrderUtils.le16;
import static de.daubli.feedwatch.uvc.utils.ByteOrderUtils.le32;

import android.hardware.usb.UsbDeviceConnection;

public class UvcModeFinder {

    public static UvcMode findBestMjpegMode(UsbDeviceConnection connection) {
        byte[] raw = connection.getRawDescriptors();

        int currentMjpegFormatIndex = -1;
        UvcMode best = null;

        int pos = 0;
        while (pos + 2 < raw.length) {
            int len = raw[pos] & 0xff;
            if (len <= 0 || pos + len > raw.length) {
                break;
            }

            int descriptorType = raw[pos + 1] & 0xff;

            if (descriptorType == CS_INTERFACE && len >= 3) {
                int descriptorSubType = raw[pos + 2] & 0xff;

                if (descriptorSubType == VS_FORMAT_MJPEG && len >= 11) {
                    currentMjpegFormatIndex = raw[pos + 3] & 0xff;
                }

                else if (descriptorSubType == VS_FRAME_MJPEG &&
                        currentMjpegFormatIndex != -1 &&
                        len >= 26) {

                    UvcMode mode = getUvcMode(raw, pos, currentMjpegFormatIndex);

                    if (best == null || isBetterMode(mode, best)) {
                        best = mode;
                    }
                }
            }

            pos += len;
        }

        return best;
    }

    private static UvcMode getUvcMode(byte[] raw, int pos, int currentMjpegFormatIndex) {
        int frameIndex = raw[pos + 3] & 0xff;

        int width = le16(raw, pos + 5);
        int height = le16(raw, pos + 7);

        int maxFrameSize = le32(raw, pos + 17);
        int defaultInterval = le32(raw, pos + 21);

        return new UvcMode(currentMjpegFormatIndex,
                frameIndex,
                width,
                height,
                defaultInterval,
                maxFrameSize
        );
    }

    // Returns true if the candidate mode is better than the current best mode.
    private static boolean isBetterMode(UvcMode candidate, UvcMode currentBest) {
        boolean candidateIs16x9 = is16x9(candidate.width, candidate.height);
        boolean currentIs16x9 = is16x9(currentBest.width, currentBest.height);

        if (candidateIs16x9 && !currentIs16x9) {
            return true;
        }

        if (!candidateIs16x9 && currentIs16x9) {
            return false;
        }

        // If both are 16:9, prefer larger resolution.
        // If neither is 16:9, also prefer larger resolution.
        int candidateArea = candidate.width * candidate.height;
        int currentArea = currentBest.width * currentBest.height;

        return candidateArea > currentArea;
    }

    private static boolean is16x9(int width, int height) {
        return width * 9 == height * 16;
    }
}
