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
     * Whether the overlay measures the video at its own size, it then gets {@link #setFrameTexture} before each draw.
     */
    public boolean needsFrameTexture() {
        return false;
    }

    /**
     * The drawn frame in RGB at its own size, shrunk for frames wider than 1440p, 0 before the first frame. Like the
     * texture handed to draw it has its top row at t = 1. Before each draw, on the GL thread.
     */
    public void setFrameTexture(int textureId, int width, int height) {
    }

    public abstract void draw(int videoTextureId, int videoRectLeft, int videoRectTop, int videoRectWidth,
            int videoRectHeight, int surfaceWidth, int surfaceHeight);
}
