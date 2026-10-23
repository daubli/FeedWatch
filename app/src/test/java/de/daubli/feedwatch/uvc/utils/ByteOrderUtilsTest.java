package de.daubli.feedwatch.uvc.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ByteOrderUtilsTest {

    @Test
    public void le16ReadsLowByteFirst() {
        byte[] bytes = { 0x00, (byte) 0x80, 0x07 };

        assertEquals(0x0780, ByteOrderUtils.le16(bytes, 1));
    }

    @Test
    public void le16TreatsBytesAsUnsigned() {
        byte[] bytes = { (byte) 0xff, (byte) 0xff };

        assertEquals(0xffff, ByteOrderUtils.le16(bytes, 0));
    }

    @Test
    public void le32ReadsLowByteFirst() {
        byte[] bytes = { 0x15, 0x16, 0x05, 0x00 };

        // 333333, the frame interval of 30 fps in 100 ns units
        assertEquals(333333, ByteOrderUtils.le32(bytes, 0));
    }

    @Test
    public void le32KeepsTheSignBit() {
        byte[] bytes = { (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff };

        assertEquals(-1, ByteOrderUtils.le32(bytes, 0));
    }

    @Test
    public void putLe32WritesLowByteFirst() {
        byte[] bytes = new byte[6];

        ByteOrderUtils.putLe32(bytes, 1, 0x12345678);

        assertArrayEquals(new byte[] { 0x00, 0x78, 0x56, 0x34, 0x12, 0x00 }, bytes);
    }

    @Test
    public void putLe32AndLe32RoundTrip() {
        byte[] bytes = new byte[4];

        for (int value : new int[] { 0, 1, 333333, Integer.MAX_VALUE, Integer.MIN_VALUE, -2 }) {
            ByteOrderUtils.putLe32(bytes, 0, value);
            assertEquals(value, ByteOrderUtils.le32(bytes, 0));
        }
    }
}
