package de.daubli.feedwatch.uvc.utils;

public class ByteOrderUtils {

    // little-endian 16-bit integer
    public static int le16(byte[] b, int off) {
        return (b[off] & 0xff) |
                ((b[off + 1] & 0xff) << 8);
    }

    // little-endian 32-bit integer
    public static int le32(byte[] b, int off) {
        return (b[off] & 0xff) |
                ((b[off + 1] & 0xff) << 8) |
                ((b[off + 2] & 0xff) << 16) |
                ((b[off + 3] & 0xff) << 24);
    }

    // Writes a 32-bit integer into a byte array using little-endian byte order.
    public static void putLe32(byte[] b, int offset, int value) {
        b[offset] = (byte) (value & 0xff);
        b[offset + 1] = (byte) ((value >> 8) & 0xff);
        b[offset + 2] = (byte) ((value >> 16) & 0xff);
        b[offset + 3] = (byte) ((value >> 24) & 0xff);
    }
}
