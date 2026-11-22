package de.daubli.feedwatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Entering NDI Discovery Servers: host names or IPv4 addresses with an optional port, separated by commas.
 */
public class SettingsActivityDiscoveryServerTest {

    @Test
    public void acceptsAddressesAndHostNames() {
        assertEquals("192.168.1.10", SettingsActivity.normalizeDiscoveryServers("192.168.1.10"));
        assertEquals("ndi-server.local", SettingsActivity.normalizeDiscoveryServers("ndi-server.local"));
        assertEquals("192.168.1.10:5959", SettingsActivity.normalizeDiscoveryServers("192.168.1.10:5959"));
        assertEquals("ds:65535", SettingsActivity.normalizeDiscoveryServers("ds:65535"));
    }

    @Test
    public void joinsSeveralServersWithoutBlanksAndEmptyEntries() {
        assertEquals("192.168.1.10,192.168.1.11:5960",
                SettingsActivity.normalizeDiscoveryServers(" 192.168.1.10 , ,192.168.1.11:5960, "));
    }

    @Test
    public void emptyForNoServer() {
        assertEquals("", SettingsActivity.normalizeDiscoveryServers(""));
        assertEquals("", SettingsActivity.normalizeDiscoveryServers("  , "));
    }

    @Test
    public void rejectsInvalidEntries() {
        assertNull(SettingsActivity.normalizeDiscoveryServers("192.168.1.10:"));
        assertNull(SettingsActivity.normalizeDiscoveryServers("192.168.1.10:0"));
        assertNull(SettingsActivity.normalizeDiscoveryServers("192.168.1.10:65536"));
        assertNull(SettingsActivity.normalizeDiscoveryServers("http://192.168.1.10"));
        assertNull(SettingsActivity.normalizeDiscoveryServers("ndi server"));
        assertNull(SettingsActivity.normalizeDiscoveryServers("-ds"));
        assertNull(SettingsActivity.normalizeDiscoveryServers("192.168.1.10,bad host"));
    }
}
