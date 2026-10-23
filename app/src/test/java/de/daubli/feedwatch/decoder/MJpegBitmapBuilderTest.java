package de.daubli.feedwatch.decoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;

import android.graphics.Bitmap;
import android.graphics.Color;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MJpegBitmapBuilderTest {

    private static final int WIDTH = 64;

    private static final int HEIGHT = 36;

    @Test
    public void decodesAFrame() {
        Bitmap bitmap = decode(ByteBuffer.wrap(jpeg()));

        assertSize(bitmap);
    }

    @Test
    public void decodesAFrameFromADirectBuffer() {
        byte[] jpeg = jpeg();
        ByteBuffer buffer = ByteBuffer.allocateDirect(jpeg.length);
        buffer.put(jpeg).flip();

        assertSize(decode(buffer));
    }

    @Test
    public void decodesOnlyTheRemainingBytes() {
        byte[] jpeg = jpeg();
        byte[] padded = concat(new byte[] { 1, 2, 3 }, jpeg, new byte[] { 4, 5 });
        ByteBuffer buffer = ByteBuffer.wrap(padded, 3, jpeg.length).slice();

        assertSize(decode(buffer));
    }

    @Test
    public void findsTheFrameInsideOtherBytes() {
        // some cameras put a header in front of the JPEG data or padding after it
        byte[] header = { 0x0c, (byte) 0x8d, 0x12, 0x34, 0x56, 0x78, (byte) 0xff, 0x00 };
        byte[] padding = new byte[32];

        assertSize(decode(ByteBuffer.wrap(concat(header, jpeg(), padding))));
    }

    @Test
    public void findsTheFrameInsideOtherBytesOfADirectBuffer() {
        byte[] data = concat(new byte[] { 0x0c, 0x0d, 0x0e }, jpeg(), new byte[16]);
        ByteBuffer buffer = ByteBuffer.allocateDirect(data.length);
        buffer.put(data).flip();

        assertSize(decode(buffer));
    }

    @Test
    public void leavesThePositionOfTheBuffer() {
        ByteBuffer buffer = ByteBuffer.wrap(jpeg());

        decode(buffer);

        assertEquals(0, buffer.position());
    }

    @Test
    public void returnsNullForAnEmptyBuffer() {
        assertNull(decode(ByteBuffer.allocate(0)));
        assertNull(decode(ByteBuffer.allocateDirect(0)));
    }

    @Test
    public void returnsNullWithoutAFrame() {
        assertNull(decode(ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 })));
    }

    @Test
    public void returnsNullForACutOffFrame() {
        byte[] jpeg = jpeg();
        byte[] start = new byte[jpeg.length / 3];
        System.arraycopy(jpeg, 0, start, 0, start.length);

        assertNull(decode(ByteBuffer.wrap(start)));
    }

    @Test
    public void needsData() {
        assertThrows(IllegalStateException.class, () -> MJpegBitmapBuilder.builder().build());
    }

    private static Bitmap decode(ByteBuffer buffer) {
        return MJpegBitmapBuilder.builder().withRawData(buffer).build();
    }

    private static void assertSize(Bitmap bitmap) {
        assertNotNull(bitmap);
        assertEquals(WIDTH, bitmap.getWidth());
        assertEquals(HEIGHT, bitmap.getHeight());
    }

    private static byte[] jpeg() {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.rgb(200, 40, 40));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }
}
