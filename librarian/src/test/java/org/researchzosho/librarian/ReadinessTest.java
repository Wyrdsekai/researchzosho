package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What serve says at start about what is missing, one sentence each, and nothing when nothing is. */
class ReadinessTest {

    @Test
    void eachMissingSettingIsOneSentenceWithTheWayToMakeIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        WebAccess.OVERRIDE = Boolean.FALSE;
        try {
            List<String> m = Readiness.missing(store, "0.0.0.0", 4649, "http://127.0.0.1:1", false, k -> null);
            assertEquals(6, m.size(), m.toString());
            assertTrue(m.get(0).startsWith("No model answers at http://127.0.0.1:1.") && m.get(0).contains("researchzosho models add"), m.get(0));
            assertTrue(m.get(1).contains("researchzosho account set"), m.get(1));
            assertTrue(m.get(2).contains("0.0.0.0:4649") && m.get(2).contains("researchzosho web signin on"), m.get(2));
            assertTrue(m.get(3).contains("RESEARCHZOSHO_TRUSTED_PROXY"), m.get(3));
            assertTrue(m.get(4).contains("RESEARCHZOSHO_EMBED"), m.get(4));
            assertTrue(m.get(5).contains("SearXNG"), m.get(5));
            for (String s : m) assertTrue(s.endsWith(".") || s.endsWith("set") || s.endsWith("on"), "a sentence: " + s);
        } finally { WebAccess.OVERRIDE = null; }
    }

    @Test
    void nothingIsSaidWhenNothingIsMissing(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Accounts.create(store, Accounts.OWNER, "The Keeper", "a long enough password");
        Map<String, String> set = Map.of("RESEARCHZOSHO_EMBED", "http://homebox:30001", "RESEARCHZOSHO_BRAVE_KEY", "not-a-real-key", Proxy.SETTING, "10.0.0.0/8");
        assertEquals(List.of(), Readiness.missing(store, "127.0.0.1", 4649, "http://homebox:8200", true, set::get));
        // open on the network with the sign-in on: nothing to say about the pages either
        WebAccess.OVERRIDE = Boolean.TRUE;
        try { assertEquals(List.of(), Readiness.missing(store, "0.0.0.0", 4649, "http://homebox:8200", true, set::get)); }
        finally { WebAccess.OVERRIDE = null; }
    }

    @Test
    void theLogGoesToStdoutWhereNoTerminalIsAttachedOrWhenTheSettingSaysSo() {
        assertTrue(Logs.wantsStdout(null, false, false), "a container: no terminal");
        assertTrue(!Logs.wantsStdout(null, false, true), "a terminal: stderr, as every command");
        assertTrue(Logs.wantsStdout("stdout", false, true), "said outright");
        assertTrue(!Logs.wantsStdout("stderr", false, false), "said outright the other way");
        assertTrue(!Logs.wantsStdout("stdout", true, false), "a log file takes both streams");
    }
}
