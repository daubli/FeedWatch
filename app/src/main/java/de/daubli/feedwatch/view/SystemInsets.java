package de.daubli.feedwatch.view;

import android.os.Build;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Since Android 15 apps targeting API 35 are drawn behind the system bars and into the display cutout. These pad a
 * screen's root view so it stays where older versions put it.
 */
public final class SystemInsets {

    private SystemInsets() {
    }

    /**
     * Keeps a screen with a toolbar clear of the status bar, the navigation bar and the keyboard.
     */
    public static void padForSystemBars(View root) {
        pad(root, WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                | WindowInsetsCompat.Type.ime());
    }

    /**
     * Keeps a fullscreen screen out of the display cutout; the hidden bars only show on top of it when swiped in.
     */
    public static void padForDisplayCutout(View root) {
        pad(root, WindowInsetsCompat.Type.displayCutout());
    }

    private static void pad(View root, int types) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // the window still keeps the content clear of bars and cutout
            return;
        }
        int left = root.getPaddingLeft();
        int top = root.getPaddingTop();
        int right = root.getPaddingRight();
        int bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(types);
            view.setPadding(left + insets.left, top + insets.top, right + insets.right, bottom + insets.bottom);
            return windowInsets;
        });
    }
}
