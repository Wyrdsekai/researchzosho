package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.tools.Fetch;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The suite never asks the model at the box's default address. The test task offers every test a drive at a closed port
 * ({@code RESEARCHZOSHO_DRIVE_DEFAULT=http://localhost:2}, read only when nothing else sets one), so a test that sets no drive gets
 * "nothing answers" on every box, as on a CI runner (a 9B on the Mac mini read a fixture book for one test and judged a short
 * question for another, 2026-10-11). An offer and not a line in the scratch home's config, because the tests swap user.home for
 * homes of their own; an offer and not RESEARCHZOSHO_DRIVE itself, because the tests set their stub drives through the config
 * file, which the environment outranks; and localhost, not 127.0.0.1, because the fetcher refuses the drive's host as one of the
 * library's own services and the fixtures are served at 127.0.0.1 (39 failures when a pin at 127.0.0.1 was tried).
 */
class SuiteHomeTest {

    @Test
    void theTestTaskOffersAClosedPortAsTheDrive(@TempDir Path tmp) throws Exception {
        String offered = System.getenv("RESEARCHZOSHO_DRIVE_DEFAULT");
        assumeTrue(offered != null, "under the Gradle test task");
        assertEquals("http://localhost:2", offered);
        assertEquals("localhost", URI.create(offered).getHost(), "localhost, not 127.0.0.1: the fetcher refuses the drive's host as one of the library's own services, and the fixtures are served at 127.0.0.1");
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString());   // a home with no settings at all
        Config.invalidate();
        try {
            assertEquals("http://localhost:2", Config.get("RESEARCHZOSHO_DRIVE"), "offered when nothing is set");
            assertNull(Config.stored("RESEARCHZOSHO_DRIVE"), "written nowhere");
            assertEquals("http://localhost:2", Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200"), "the box's default address is never the fallback");
            assertTrue(Fetch.ownServiceHosts().contains("localhost") && !Fetch.ownServiceHosts().contains("127.0.0.1"), Fetch.ownServiceHosts().toString());
            String why = Fetch.refusal(URI.create("http://127.0.0.1:65000/fixture"));
            assertFalse(why != null && why.contains("own services"), "a fixture served at 127.0.0.1 is not refused as the drive's host: " + why);
            Config.set("RESEARCHZOSHO_DRIVE", "http://127.0.0.1:9");
            assertEquals("http://127.0.0.1:9", Config.get("RESEARCHZOSHO_DRIVE"), "a test's own setting wins over the offer");
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }

    @Test
    void aDaemonKeepsTheDriveItStartedWithUntilTheSettingMoves(@TempDir Path tmp) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString());
        Config.invalidate();
        try {
            LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
            LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:9", "stub", -1);
            try {
                assertEquals("http://127.0.0.1:9", d.driveNow(), "a test's stub, or an address given on the command line, is not swapped for the offer or the config's");
                Config.set("RESEARCHZOSHO_DRIVE", "http://elsewhere:8211");   // `model use` while the service runs
                assertEquals("http://elsewhere:8211", d.driveNow(), "the setting moved: the daemon follows it");
                Config.set("RESEARCHZOSHO_DRIVE", "");
                assertEquals("http://127.0.0.1:9", d.driveNow(), "unset again: the one it started with");
            } finally { d.stop(); }
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }
}
