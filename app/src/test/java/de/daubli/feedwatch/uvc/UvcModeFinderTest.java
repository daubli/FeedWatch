package de.daubli.feedwatch.uvc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import org.junit.Test;

import android.hardware.usb.UsbDeviceConnection;
import de.daubli.feedwatch.uvc.utils.ByteOrderUtils;

public class UvcModeFinderTest {

    private static final int VS_FORMAT_UNCOMPRESSED = 0x04;

    private static final int VS_FRAME_UNCOMPRESSED = 0x05;

    private static final int INTERVAL_30_FPS = 333333;

    @Test
    public void prefers16x9OverALargerOtherAspectRatio() {
        UvcMode mode = findBest(new Descriptors()
                .mjpegFormat(1)
                .mjpegFrame(1, 1280, 720)
                .mjpegFrame(2, 2592, 1944)
                .mjpegFrame(3, 640, 480));

        assertSize(1280, 720, mode);
    }

    @Test
    public void prefersTheLargest16x9Mode() {
        UvcMode mode = findBest(new Descriptors()
                .mjpegFormat(1)
                .mjpegFrame(1, 1280, 720)
                .mjpegFrame(2, 1920, 1080)
                .mjpegFrame(3, 640, 360));

        assertSize(1920, 1080, mode);
        assertEquals(2, mode.frameIndex);
    }

    @Test
    public void prefersTheLargestModeWithout16x9() {
        UvcMode mode = findBest(new Descriptors()
                .mjpegFormat(1)
                .mjpegFrame(1, 640, 480)
                .mjpegFrame(2, 1600, 1200)
                .mjpegFrame(3, 800, 600));

        assertSize(1600, 1200, mode);
    }

    @Test
    public void keepsTheFirstOfTwoEqualModes() {
        UvcMode mode = findBest(new Descriptors()
                .mjpegFormat(1)
                .mjpegFrame(4, 1920, 1080)
                .mjpegFrame(5, 1920, 1080));

        assertEquals(4, mode.frameIndex);
    }

    @Test
    public void readsTheFieldsOfTheFrameDescriptor() {
        UvcMode mode = findBest(new Descriptors()
                .mjpegFormat(2)
                .mjpegFrame(3, 1920, 1080, 4_147_200, INTERVAL_30_FPS));

        assertNotNull(mode);
        assertEquals(2, mode.formatIndex);
        assertEquals(3, mode.frameIndex);
        assertEquals(1920, mode.width);
        assertEquals(1080, mode.height);
        assertEquals(4_147_200, mode.maxFrameSize);
        assertEquals(INTERVAL_30_FPS, mode.frameInterval);
    }

    @Test
    public void ignoresFramesOfUncompressedFormats() {
        UvcMode mode = findBest(new Descriptors()
                .format(VS_FORMAT_UNCOMPRESSED, 1)
                .frame(VS_FRAME_UNCOMPRESSED, 1, 3840, 2160)
                .mjpegFormat(2)
                .mjpegFrame(1, 1280, 720));

        assertSize(1280, 720, mode);
        assertEquals(2, mode.formatIndex);
    }

    @Test
    public void ignoresMjpegFramesBeforeTheirFormat() {
        UvcMode mode = findBest(new Descriptors()
                .mjpegFrame(1, 1920, 1080)
                .mjpegFormat(1)
                .mjpegFrame(2, 1280, 720));

        assertSize(1280, 720, mode);
    }

    @Test
    public void ignoresDescriptorsThatAreNotClassSpecific() {
        // a standard interface descriptor (type 0x04) whose third byte happens to equal VS_FRAME_MJPEG
        UvcMode mode = findBest(new Descriptors()
                .mjpegFormat(1)
                .raw(new byte[] { 9, 0x04, 0x07, 0, 0, 0x0e, 0x02, 0, 0 })
                .mjpegFrame(1, 640, 360));

        assertSize(640, 360, mode);
    }

    @Test
    public void returnsNullWithoutMjpeg() {
        assertNull(findBest(new Descriptors()
                .format(VS_FORMAT_UNCOMPRESSED, 1)
                .frame(VS_FRAME_UNCOMPRESSED, 1, 1920, 1080)));
        assertNull(findBest(new Descriptors()));
    }

    @Test
    public void stopsAtADescriptorWithoutLength() {
        UvcMode mode = findBest(new Descriptors()
                .mjpegFormat(1)
                .mjpegFrame(1, 640, 360)
                .raw(new byte[] { 0, 0x24, 0x07 })
                .mjpegFrame(2, 1920, 1080));

        assertSize(640, 360, mode);
    }

    @Test
    public void stopsAtADescriptorLongerThanTheData() {
        Descriptors descriptors = new Descriptors()
                .mjpegFormat(1)
                .mjpegFrame(1, 640, 360);
        byte[] complete = new Descriptors().mjpegFrame(2, 1920, 1080).toByteArray();
        // the second frame is cut off after 20 of its 30 bytes
        descriptors.raw(Arrays.copyOf(complete, 20));

        assertSize(640, 360, findBest(descriptors));
    }

    private static UvcMode findBest(Descriptors descriptors) {
        UsbDeviceConnection connection = mock(UsbDeviceConnection.class);
        when(connection.getRawDescriptors()).thenReturn(descriptors.toByteArray());
        return UvcModeFinder.findBestMjpegMode(connection);
    }

    private static void assertSize(int width, int height, UvcMode mode) {
        assertNotNull(mode);
        assertEquals(width, mode.width);
        assertEquals(height, mode.height);
    }

    /**
     * Builds the raw configuration descriptors of a UVC camera, see the USB Video Class 1.5 payload specification
     * for MJPEG.
     */
    private static final class Descriptors {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Descriptors mjpegFormat(int formatIndex) {
            return format(0x06, formatIndex);
        }

        Descriptors format(int subType, int formatIndex) {
            byte[] d = new byte[11];
            d[0] = (byte) d.length;
            d[1] = 0x24;
            d[2] = (byte) subType;
            d[3] = (byte) formatIndex;
            d[4] = 1; // number of frame descriptors, not read
            return raw(d);
        }

        Descriptors mjpegFrame(int frameIndex, int width, int height) {
            return mjpegFrame(frameIndex, width, height, width * height * 2, INTERVAL_30_FPS);
        }

        Descriptors mjpegFrame(int frameIndex, int width, int height, int maxFrameSize, int interval) {
            return frame(0x07, frameIndex, width, height, maxFrameSize, interval);
        }

        Descriptors frame(int subType, int frameIndex, int width, int height) {
            return frame(subType, frameIndex, width, height, width * height * 2, INTERVAL_30_FPS);
        }

        Descriptors frame(int subType, int frameIndex, int width, int height, int maxFrameSize, int interval) {
            // 26 bytes plus one discrete frame interval
            byte[] d = new byte[30];
            d[0] = (byte) d.length;
            d[1] = 0x24;
            d[2] = (byte) subType;
            d[3] = (byte) frameIndex;
            d[5] = (byte) width;
            d[6] = (byte) (width >> 8);
            d[7] = (byte) height;
            d[8] = (byte) (height >> 8);
            ByteOrderUtils.putLe32(d, 17, maxFrameSize);
            ByteOrderUtils.putLe32(d, 21, interval);
            d[25] = 1;
            ByteOrderUtils.putLe32(d, 26, interval);
            return raw(d);
        }

        Descriptors raw(byte[] bytes) {
            out.write(bytes, 0, bytes.length);
            return this;
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }
}
