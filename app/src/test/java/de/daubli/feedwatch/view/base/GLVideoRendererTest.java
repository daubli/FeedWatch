package de.daubli.feedwatch.view.base;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import de.daubli.feedwatch.ndi.FourCCType;
import org.junit.Test;

public class GLVideoRendererTest {

    @Test
    public void discardingHandsBackTheFrameNotDrawnYet() {
        GLVideoRenderer renderer = new GLVideoRenderer();
        AtomicInteger consumed = new AtomicInteger();

        renderer.render(ByteBuffer.allocate(16), 2, 2, FourCCType.RGBA, consumed::incrementAndGet);
        renderer.discardPendingFrame();

        assertEquals(1, consumed.get());
    }

    @Test
    public void aDiscardedFrameIsNotHandedBackAgain() {
        GLVideoRenderer renderer = new GLVideoRenderer();
        AtomicInteger consumed = new AtomicInteger();

        renderer.render(ByteBuffer.allocate(16), 2, 2, FourCCType.RGBA, consumed::incrementAndGet);
        renderer.discardPendingFrame();
        renderer.discardPendingFrame();
        // the next frame replaces nothing
        renderer.render(ByteBuffer.allocate(16), 2, 2, FourCCType.RGBA, null);

        assertEquals(1, consumed.get());
    }

    @Test
    public void framesUpTo1440pAreConvertedAtTheirOwnSize() {
        assertEquals(1, GLVideoRenderer.frameTextureDivisor(1280));
        assertEquals(1, GLVideoRenderer.frameTextureDivisor(1920));
        assertEquals(1, GLVideoRenderer.frameTextureDivisor(2560));
    }

    @Test
    public void largerFramesAreShrunkByAPowerOfTwo() {
        assertEquals(2, GLVideoRenderer.frameTextureDivisor(3840));
        assertEquals(2, GLVideoRenderer.frameTextureDivisor(4096));
        assertEquals(4, GLVideoRenderer.frameTextureDivisor(7680));
    }

    @Test
    public void paddedRowsArePackedForTheUpload() {
        // 3 rows of 4 bytes, each followed by 2 bytes of padding
        ByteBuffer padded = ByteBuffer.wrap(new byte[] { 1, 2, 3, 4, 9, 9, 5, 6, 7, 8, 9, 9, 10, 11, 12, 13, 9, 9 });
        ByteBuffer packed = ByteBuffer.allocateDirect(16);

        GLVideoRenderer.packRows(padded, 4, 6, 3, packed);

        assertEquals(12, packed.remaining());
        byte[] rows = new byte[12];
        packed.get(rows);
        assertArrayEquals(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13 }, rows);
        // the frame itself is left as it was
        assertEquals(0, padded.position());
        assertEquals(18, padded.limit());
    }
}
