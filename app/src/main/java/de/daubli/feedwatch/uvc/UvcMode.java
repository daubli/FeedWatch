package de.daubli.feedwatch.uvc;

public class UvcMode {
    final int formatIndex;
    final int frameIndex;
    final int width;
    final int height;
    final int frameInterval;
    final int maxFrameSize;

    UvcMode(int formatIndex, int frameIndex, int width, int height,
            int frameInterval, int maxFrameSize) {
        this.formatIndex = formatIndex;
        this.frameIndex = frameIndex;
        this.width = width;
        this.height = height;
        this.frameInterval = frameInterval;
        this.maxFrameSize = maxFrameSize;
    }
}
