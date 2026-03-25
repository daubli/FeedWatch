package de.daubli.feedwatch.decoder;

import java.nio.ByteBuffer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import androidx.annotation.Nullable;

public class MJpegBitmapBuilder {

    private static final String TAG = "UvcCapture";

    private ByteBuffer rawData;

    // Reusable scratch for direct buffers (avoids per-frame byte[])
    private static final ThreadLocal<byte[]> SCRATCH = new ThreadLocal<>();

    public static MJpegBitmapBuilder builder() {
        return new MJpegBitmapBuilder();
    }

    public MJpegBitmapBuilder withRawData(ByteBuffer buffer) {
        this.rawData = buffer;
        return this;
    }

    @Nullable
    public Bitmap build() {
        if (rawData == null) {
            throw new IllegalStateException("No input data provided.");
        }
        return decodeMjpeg(rawData);
    }

    private Bitmap decodeMjpeg(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            return null;
        }

        // Don't consume caller's position
        ByteBuffer b = buffer.duplicate();

        if (b.hasArray()) {
            int base = b.arrayOffset();
            int off = base + b.position();
            int len = b.remaining();

            Bitmap bmp = tryDecode(b.array(), off, len);
            if (bmp != null) {
                return bmp;
            }

            int start = findJpegSOI(b.array(), off, len);
            int end = findJpegEOI(b.array(), off, len);
            if (start >= 0 && end > start) {
                return tryDecode(b.array(), start, (end - start) + 2);
            }

            Log.e(TAG, "Could not find valid JPEG segment in array-backed buffer");
            return null;
        }

        // Direct / non-array buffer: copy into reusable scratch
        int len = b.remaining();
        byte[] data = getScratch(len);
        b.get(data, 0, len);

        Bitmap bmp = tryDecode(data, 0, len);
        if (bmp != null) {
            return bmp;
        }

        int start = findJpegSOI(data, 0, len);
        int end = findJpegEOI(data, 0, len);
        if (start >= 0 && end > start) {
            return tryDecode(data, start, (end - start) + 2);
        }

        Log.e(TAG, "Could not find valid JPEG segment in direct buffer");
        return null;
    }

    private static Bitmap tryDecode(byte[] data, int off, int len) {
        try {
            // If you want downscaling, set inSampleSize here via OPTS.get().
            // For now, keep defaults.
            return BitmapFactory.decodeByteArray(data, off, len);
        } catch (Throwable t) {
            // decodeByteArray usually returns null on failure, but be defensive.
            Log.e(TAG, "JPEG decode failed", t);
            return null;
        }
    }

    private static byte[] getScratch(int needed) {
        byte[] s = SCRATCH.get();
        if (s == null || s.length < needed) {
            int newSize = roundUpPow2(needed);
            s = new byte[newSize];
            SCRATCH.set(s);
        }
        return s;
    }

    private static int roundUpPow2(int x) {
        int v = 1;
        while (v < x) {
            v <<= 1;
        }
        return v;
    }

    private static int findJpegSOI(byte[] data, int off, int len) {
        int end = off + len - 1;
        for (int i = off; i < end; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0xD8) {
                return i;
            }
        }
        return -1;
    }

    private static int findJpegEOI(byte[] data, int off, int len) {
        for (int i = off + len - 2; i >= off; i--) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0xD9) {
                return i;
            }
        }
        return -1;
    }
}
