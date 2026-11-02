package de.daubli.feedwatch.ndi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class NdiFinderTest {

    @Test
    public void knownHostsFollowTheAdditionalAddresses() {
        assertEquals("10.0.0.5,192.168.1.146",
                NdiFinder.combineIps("10.0.0.5", Collections.singletonList("192.168.1.146")));
    }

    @Test
    public void anAddressIsSearchedOnce() {
        assertEquals("192.168.1.146,10.0.0.5",
                NdiFinder.combineIps(" 192.168.1.146 ,", Arrays.asList("10.0.0.5", "192.168.1.146")));
    }

    @Test
    public void knownHostsAloneAreSearched() {
        assertEquals("192.168.1.146", NdiFinder.combineIps(null, Collections.singletonList("192.168.1.146")));
    }

    @Test
    public void nothingToSearchIsNull() {
        assertNull(NdiFinder.combineIps(null, Collections.emptyList()));
        assertNull(NdiFinder.combineIps("", Collections.emptyList()));
    }
}
