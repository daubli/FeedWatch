package de.daubli.feedwatch.view.base;

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
}
