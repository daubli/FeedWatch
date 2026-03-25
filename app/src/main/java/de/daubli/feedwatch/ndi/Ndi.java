// Based on code from https://github.com/WalkerKnapp/devolay (Apache 2.0).

package de.daubli.feedwatch.ndi;

public class Ndi {
    static {
        System.loadLibrary("ndi-wrapper");
    }

    public static void initialize() {
        boolean initialized = nInitializeNDI();

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


    public static native boolean nInitializeNDI();

    public static native String nGetNdiVersion();

    private static native boolean nIsSupportedCpu();
}
