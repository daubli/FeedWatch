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
 */
public final class NdiConfig {

    private static final Logger LOG = Logger.getLogger(NdiConfig.class.getSimpleName());

    static final String FILE_NAME = "ndi-config.v1.json";

    static final String SETTINGS = "{\"ndi\":{"
            + "\"rudp\":{\"recv\":{\"enable\":false}},"
            + "\"tcp\":{\"recv\":{\"enable\":false}},"
            + "\"unicast\":{\"recv\":{\"enable\":false}}"
            + "}}";

    private NdiConfig() {
    }

    /**
     * Writes the settings into the folder, unless they are there already.
     *
     * @return the folder, or null if the settings could not be written there
     */
    @Nullable
    public static File write(File dir) {
        File file = new File(dir, FILE_NAME);
        try {
            if (file.isFile() && SETTINGS.equals(new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8))) {
                return dir;
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("Could not create " + dir);
            }
            Files.write(file.toPath(), SETTINGS.getBytes(StandardCharsets.UTF_8));
            return dir;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "NDI settings not written, the library uses its defaults", e);
            return null;
        }
    }
}
