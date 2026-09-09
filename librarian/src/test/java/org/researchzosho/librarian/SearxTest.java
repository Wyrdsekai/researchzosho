package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** SearXNG through Docker: the settings ResearchZosho writes, and the docker command lines it runs, without docker. */
class SearxTest {

    final List<List<String>> ran = new ArrayList<>();
    final Searx.Runner real = Searx.runner;

    @AfterEach void restore() { Searx.runner = real; }

    @Test
    void theSettingsTurnTheJsonFormatOnAndKeepTheDefaults() {
        String s = Searx.settings();
        assertTrue(s.contains("use_default_settings: true"), s);
        assertTrue(s.contains("- json"), "the search tool needs json in search.formats: " + s);
        assertTrue(s.contains("secret_key: \"") && !s.contains("secret_key: \"\""), "a secret key is generated: " + s);
        assertNotEquals(Searx.settings(), s, "each call makes its own key");
    }

    @Test
    void withoutDockerStartSaysSoAndStopsThere() {
        Searx.runner = cmd -> { ran.add(cmd); throw new java.io.IOException("docker: not found"); };
        String r = Searx.start(8888);
        assertTrue(r.startsWith("!docker is not on this machine"), r);
        assertEquals(1, ran.size(), "only the version probe ran");
        assertFalse(Searx.haveDocker());
        assertEquals("absent", Searx.state());
    }

    @Test
    void theRunCommandBindsLoopbackOnlyAndRestartsWithTheMachine() {
        List<String> run = Searx.plan(8888).get(0);
        assertEquals("docker", run.get(0));
        assertTrue(run.contains("--restart") && run.contains("unless-stopped"), run.toString());
        assertTrue(run.contains("127.0.0.1:8888:8080"), "loopback only, never the whole network: " + run);
        assertTrue(run.contains(Searx.IMAGE) && run.contains(Searx.CONTAINER));
    }

    @Test
    void stateReadsDockerInspect() {
        Searx.runner = cmd -> new Searx.Result(0, cmd.contains("inspect") ? "true\n" : "");
        assertEquals("running", Searx.state());
        Searx.runner = cmd -> new Searx.Result(0, cmd.contains("inspect") ? "false\n" : "");
        assertEquals("stopped", Searx.state());
        Searx.runner = cmd -> new Searx.Result(1, "Error: No such object");
        assertEquals("absent", Searx.state());
    }
}
