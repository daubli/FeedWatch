package de.daubli.feedwatch.ndi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class NdiSourceTest {

    @Test
    public void stableKeyIsTheNameNotTheAddress() {
        NdiSource before = new NdiSource("STUDIO (Cam 1)", "192.168.1.10:5961");
        NdiSource after = new NdiSource("STUDIO (Cam 1)", "192.168.1.22:5962");

        assertEquals("ndi:STUDIO (Cam 1)", before.getStableKey());
        assertEquals(before.getStableKey(), after.getStableKey());
    }

    @Test
    public void missingValuesBecomeEmpty() {
        NdiSource source = new NdiSource(null, null);

        assertEquals("", source.getSourceName());
        assertEquals("", source.getUrlAddress());
        assertEquals("ndi:", source.getStableKey());
    }

    @Test
    public void hostIsTheAddressWithoutThePort() {
        assertEquals("192.168.1.146", new NdiSource("MAC (Cam)", "192.168.1.146:5962").getHost());
        assertEquals("fe80::1", new NdiSource("MAC (Cam)", "[fe80::1]:5962").getHost());
        assertEquals("192.168.1.146", new NdiSource("MAC (Cam)", "192.168.1.146").getHost());
    }

    @Test
    public void hostIsNullWithoutAnAddress() {
        assertNull(new NdiSource("MAC (Cam)", null).getHost());
        assertNull(new NdiSource("MAC (Cam)", ":5962").getHost());
        assertNull(new NdiSource("MAC (Cam)", "[]:5962").getHost());
    }
}
