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
}
