// Based on code from https://github.com/WalkerKnapp/devolay (Apache 2.0).

package de.daubli.feedwatch.ndi;

import java.io.File;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import de.daubli.feedwatch.settings.SettingsStore;

public class Ndi {
    static {
        System.loadLibrary("ndi-wrapper");
    }

    public static void initialize(Context context) {
        // a changed discovery server only takes effect in a new process, the library reads the file once
        String discoveryServers = new SettingsStore().getDiscoveryServers();
        // Android's host name is "localhost" on every device. A controller at the discovery server lists the
        // advertised receiver under the device's name instead; without a server the name is not shown anywhere.
        File configDir = NdiConfig.write(new File(context.getNoBackupFilesDir(), "ndi"), discoveryServers,
                discoveryServers != null ? deviceName(context) : null);
        // the library keeps lock files in TMPDIR, without it in /data/local/tmp where apps can't write
        boolean initialized = nInitializeNDI(configDir != null ? configDir.getAbsolutePath() : null,
                context.getCacheDir().getAbsolutePath());

        if (!initialized) {
            throw new RuntimeException("Could not initialize NDI.");
        }
    }

    /**
     * @return the name the user gave the device, e.g. "Living Room TV", or its model if there is none
     */
    static String deviceName(Context context) {
        String deviceName = NdiConfig.machineName(
                Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME));
        return !deviceName.isEmpty() ? deviceName : NdiConfig.machineName(Build.MODEL);
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
