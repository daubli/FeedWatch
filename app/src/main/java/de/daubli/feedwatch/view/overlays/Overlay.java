package de.daubli.feedwatch.view.overlays;

public abstract class Overlay {

    boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Size of the video frame in pixels, before each draw. On the GL thread.
     */
    public void setFrameSize(int width, int height) {
    }

    public abstract void draw(int videoTextureId, int videoRectLeft, int videoRectTop, int videoRectWidth,
            int videoRectHeight, int surfaceWidth, int surfaceHeight);
}
