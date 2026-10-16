// Based on code from https://github.com/WalkerKnapp/devolay (Apache 2.0).

package de.daubli.feedwatch.ndi;

public class NdiFrameSync extends NdiFrameCleaner implements AutoCloseable {

    final long instancePointer;

    public NdiFrameSync(NdiReceiver receiver) {
        instancePointer = framesyncCreate(receiver.instancePointer);
    }

    public boolean captureVideo(NdiVideoFrame videoFrame) {
        videoFrame.freeBuffer();
        //frame format 1 stands for PROGRESSIVE
        boolean started = framesyncCaptureVideo(instancePointer, videoFrame.instancePointer, 1);

        if (started) {
            videoFrame.allocatedBufferSource.set(this);
        }

        return started;
    }

    @Override
    public void close() {
        framesyncDestroy(instancePointer);
    }

    @Override
    void freeVideo(NdiVideoFrame videoFrame) {
        framesyncFreeVideo(instancePointer, videoFrame.instancePointer);
    }

    private static native long framesyncCreate(long pReceiver);

    private static native void framesyncDestroy(long pFramesync);

    private static native boolean framesyncCaptureVideo(long pFramesync, long pFrame, int frameFormat);

    private static native void framesyncFreeVideo(long pFramesync, long pFrame);
}
