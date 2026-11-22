package de.daubli.feedwatch.ndi;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

import androidx.annotation.Nullable;

/**
 * The NDI library's settings for this app, in a folder the library reads through NDI_CONFIG_DIR.
 * <p>
 * Receivers get a source over one TCP connection instead of one per stream type (multi-TCP) or UDP unicast. On a busy
 * link, e.g. 70 Mbit/s of video into the 100 Mbit port of a TV, multi-TCP lost so many packets that the thin audio
 * connection waited for TCP timeouts: gaps of 300 to 600 ms, about 17 dropouts a minute, and the picture stuttered
 * too. Over one connection the losses are repaired within the stream.
 * <p>
 * Reliable UDP (QUIC) stays off, also should a library update turn it on by default: it costs too much CPU. Under the
 * same load it carried only 46 to 59 Mbit/s on that TV, about 15 audio dropouts a minute, and on a phone over Wi-Fi it
 * fell to 10 fps after a minute while TCP held 25 fps.
 * <p>
 * An NDI Discovery Server from the settings goes in here as well. The library reads the file once per process, neither
 * a new finder nor a new initialization picks up a change (tried with SDK 6.3.2), so a new server needs an app restart.
 */
public final class NdiConfig {

    private static final Logger LOG = Logger.getLogger(NdiConfig.class.getSimpleName());

    static final String FILE_NAME = "ndi-config.v1.json";

    static final String SETTINGS = settings(null);

    private NdiConfig() {
    }

    /**
     * @param discoveryServers comma-separated NDI Discovery Servers, each with an optional port, or null for none
     */
    static String settings(@Nullable String discoveryServers) {
        return settings(discoveryServers, null);
    }

    /**
     * @param machineName how the device is named on the network instead of its host name, or null for that
     */
    static String settings(@Nullable String discoveryServers, @Nullable String machineName) {
        StringBuilder json = new StringBuilder("{\"ndi\":{");
        if (machineName != null && !machineName.isEmpty()) {
            json.append("\"machinename\":\"").append(escape(machineName)).append("\",");
        }
        json.append("\"rudp\":{\"recv\":{\"enable\":false}},")
                .append("\"tcp\":{\"recv\":{\"enable\":false}},")
                .append("\"unicast\":{\"recv\":{\"enable\":false}}");
        if (discoveryServers != null && !discoveryServers.isEmpty()) {
            json.append(",\"networks\":{\"discovery\":\"")
                    .append(escape(discoveryServers))
                    .append("\"}");
        }
        return json.append("}}").toString();
    }

    /**
     * Makes a device name usable as NDI machine name. Controllers read a name up to its first parenthesis as the
     * machine, "TV (Kitchen) (FeedWatch)" would be split wrongly, so parentheses become brackets. Control
     * characters, which the configuration file can't hold as they are, become spaces.
     */
    static String machineName(@Nullable String deviceName) {
        if (deviceName == null) {
            return "";
        }
        return deviceName.replace('(', '[').replace(')', ']')
                .replaceAll("\\p{Cntrl}", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Writes the settings into the folder, unless they are there already.
     *
     * @param discoveryServers comma-separated NDI Discovery Servers, each with an optional port, or null for none
     * @return the folder, or null if the settings could not be written there
     */
    @Nullable
    public static File write(File dir, @Nullable String discoveryServers) {
        return write(dir, discoveryServers, null);
    }

    /**
     * Writes the settings into the folder, unless they are there already.
     *
     * @param discoveryServers comma-separated NDI Discovery Servers, each with an optional port, or null for none
     * @param machineName      how the device is named on the network instead of its host name, or null for that
     * @return the folder, or null if the settings could not be written there
     */
    @Nullable
    public static File write(File dir, @Nullable String discoveryServers, @Nullable String machineName) {
        File file = new File(dir, FILE_NAME);
        String settings = settings(discoveryServers, machineName);
        try {
            if (file.isFile() && settings.equals(new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8))) {
                return dir;
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("Could not create " + dir);
            }
            Files.write(file.toPath(), settings.getBytes(StandardCharsets.UTF_8));
            return dir;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "NDI settings not written, the library uses its defaults", e);
            return null;
        }
    }
}
