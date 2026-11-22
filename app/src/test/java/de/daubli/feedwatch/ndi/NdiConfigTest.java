package de.daubli.feedwatch.ndi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NdiConfigTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void writesTheSettingsIntoANewFolder() throws IOException {
        File dir = new File(temp.getRoot(), "ndi");

        assertEquals(dir, NdiConfig.write(dir, null));
        assertEquals(NdiConfig.SETTINGS, read(new File(dir, NdiConfig.FILE_NAME)));
    }

    @Test
    public void receivesOverOneTcpConnectionWithoutReliableUdp() {
        assertTrue(NdiConfig.SETTINGS.contains("\"rudp\":{\"recv\":{\"enable\":false}}"));
        assertTrue(NdiConfig.SETTINGS.contains("\"tcp\":{\"recv\":{\"enable\":false}}"));
        assertTrue(NdiConfig.SETTINGS.contains("\"unicast\":{\"recv\":{\"enable\":false}}"));
    }

    @Test
    public void replacesOtherSettings() throws IOException {
        File dir = temp.newFolder("ndi");
        File file = new File(dir, NdiConfig.FILE_NAME);
        Files.write(file.toPath(), "{}".getBytes(StandardCharsets.UTF_8));

        assertEquals(dir, NdiConfig.write(dir, null));
        assertEquals(NdiConfig.SETTINGS, read(file));
    }

    @Test
    public void addsTheDiscoveryServers() {
        assertEquals("{\"ndi\":{"
                + "\"rudp\":{\"recv\":{\"enable\":false}},"
                + "\"tcp\":{\"recv\":{\"enable\":false}},"
                + "\"unicast\":{\"recv\":{\"enable\":false}},"
                + "\"networks\":{\"discovery\":\"192.168.1.10,ndi-server:5960\"}"
                + "}}", NdiConfig.settings("192.168.1.10,ndi-server:5960"));
    }

    @Test
    public void noDiscoveryServerWhenEmpty() {
        assertEquals(NdiConfig.SETTINGS, NdiConfig.settings(""));
        assertFalse(NdiConfig.SETTINGS.contains("discovery"));
    }

    @Test
    public void escapesTheDiscoveryServers() {
        assertTrue(NdiConfig.settings("a\"b\\c").contains("\"discovery\":\"a\\\"b\\\\c\""));
    }

    @Test
    public void rewritesTheFileWhenTheDiscoveryServerChanges() throws IOException {
        File dir = temp.newFolder("ndi");
        NdiConfig.write(dir, null);

        assertEquals(dir, NdiConfig.write(dir, "192.168.1.10"));
        assertEquals(NdiConfig.settings("192.168.1.10"), read(new File(dir, NdiConfig.FILE_NAME)));

        assertEquals(dir, NdiConfig.write(dir, null));
        assertEquals(NdiConfig.SETTINGS, read(new File(dir, NdiConfig.FILE_NAME)));
    }

    @Test
    public void nullWhenTheFolderCannotBeCreated() throws IOException {
        File blocked = temp.newFile("ndi");

        assertNull(NdiConfig.write(blocked, null));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
