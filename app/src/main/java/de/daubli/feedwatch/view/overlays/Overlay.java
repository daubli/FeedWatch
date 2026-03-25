package de.daubli.feedwatch.view.overlays;

public abstract class Overlay {

    boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public abstract void draw(int videoTextureId, int videoRectLeft, int videoRectTop, int videoRectWidth,
            int videoRectHeight, int surfaceWidth, int surfaceHeight);
}
