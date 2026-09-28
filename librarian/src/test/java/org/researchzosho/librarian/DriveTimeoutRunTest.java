package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.HangingServer;
import org.researchzosho.drive.DriveClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A research run whose model server sends the head of each answer and then nothing: each call ends at its time limit, and the run does not
 * ask such a call again with the same limit, where it used to wait three seconds and then the whole limit a second time.
 */
class DriveTimeoutRunTest {

    @Test
    void aRunDoesNotAskAgainACallThatRanOutOfTime(@TempDir Path home) throws Exception {
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        try (HangingServer s = new HangingServer(HangingServer.Mode.HEAD_ONLY)) {
            Config.set("ctx", "32768");   // the window, so that nothing asks the hanging server for it
            List<String> said = new CopyOnWriteArrayList<>();
            DriveClient c = new DriveClient(s.url(), "placeholder-model").timeLimit(Duration.ofSeconds(1));
            Researcher r = new Researcher(Researcher.drive(c), new ResearcherTest.FakeTools(), said::add);
            Researcher.Ask ask = new Researcher.Ask("How were the placeholder gears cut?", "depth", 6, List.of("how were the gears cut?"));
            assertTimeoutPreemptively(Duration.ofSeconds(120), () -> { try { r.run(ask, ""); } catch (RuntimeException ended) { } });
            assertTrue(said.stream().noneMatch(l -> l.contains("retrying once")), "a call that ran out of time is not asked again: " + said);
            assertTrue(said.stream().anyMatch(l -> l.contains("not asked again with the same limit")), said.toString());
        } finally {
            System.setProperty("user.home", realHome);
            Config.invalidate();
        }
    }
}
