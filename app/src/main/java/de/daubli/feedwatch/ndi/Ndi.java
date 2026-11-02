// Based on code from https://github.com/WalkerKnapp/devolay (Apache 2.0).

package de.daubli.feedwatch.ndi;

import java.io.File;

import android.content.Context;

public class Ndi {
    static {
        System.loadLibrary("ndi-wrapper");
    }

    public static void initialize(Context context) {
        File configDir = NdiConfig.write(new File(context.getNoBackupFilesDir(), "ndi"));
        // the library keeps lock files in TMPDIR, without it in /data/local/tmp where apps can't write
        boolean initialized = nInitializeNDI(configDir != null ? configDir.getAbsolutePath() : null,
                context.getCacheDir().getAbsolutePath());

        if (!initialized) {
            throw new RuntimeException("Could not initialize NDI.");
        }
    }

    public static String getNdiVersion() {
        return nGetNdiVersion();
    }

    public static boolean isCPUSupported() {
        return nIsSupportedCpu();
    }


    /**
     * @param configDir folder with the library's settings, or null for its defaults
     * @param tmpDir    writable folder for the library's lock files
     */
    public static native boolean nInitializeNDI(String configDir, String tmpDir);

    public static native String nGetNdiVersion();

    private static native boolean nIsSupportedCpu();
}
