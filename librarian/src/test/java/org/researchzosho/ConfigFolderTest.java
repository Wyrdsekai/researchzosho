package org.researchzosho;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A config path that names a folder is said once, and the settings stay as they are without a file, instead of every command ending in an error. */
class ConfigFolderTest {

    @Test
    void aConfigThatIsAFolderIsNoError(@TempDir Path tmp) throws Exception {
        assumeTrue(Config.env("RESEARCHZOSHO_CONFIG") == null, "the config path is set from outside");
        String real = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString());
        try {
            Files.createDirectories(tmp.resolve(".researchzosho").resolve("config"));
            Config.invalidate();
            assertNull(Config.stored("RESEARCHZOSHO_NO_SUCH_SETTING"));
            assertEquals("fallback", Config.get("RESEARCHZOSHO_NO_SUCH_SETTING", "fallback"));
        } finally {
            System.setProperty("user.home", real);
            Config.invalidate();
        }
    }
}
