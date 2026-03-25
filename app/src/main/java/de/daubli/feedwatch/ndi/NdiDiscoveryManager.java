package de.daubli.feedwatch.ndi;

import android.content.Context;
import android.net.wifi.WifiManager;

public class NdiDiscoveryManager {

    private final WifiManager.MulticastLock multicastLock;

    public NdiDiscoveryManager(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        multicastLock = wifiManager.createMulticastLock("ndi_multicast_lock");

        // Optional but recommended:
        multicastLock.setReferenceCounted(false);
    }

    public void startDiscovery() {
        if (!multicastLock.isHeld()) {
            multicastLock.acquire();
        }
    }

    public void stopDiscovery() {
        if (multicastLock.isHeld()) {
            multicastLock.release();
        }
    }
}
