package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.mcp.McpServer;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hosted library leaves the machine's tools out of the list its MCP server gives, while it runs; once no hosted library runs in the
 * process, the list is the library's again. The leave-out outlived the hosted library it was for: a later test in the same process found
 * library_models "unknown" (the full suite of 2026-11-18, McpParityTest, run after the hosted tests).
 */
class HostedToolListTest {
    static final ObjectMapper M = new ObjectMapper();
    AccountStore store; AccountService svc;

    @AfterEach void stop() throws Exception {
        if (svc != null) svc.stop();
        if (store != null) store.close();
        Config.set(AccountService.ROOT, "");
    }

    static Set<String> listed() {
        Set<String> out = new HashSet<>();
        for (JsonNode t : McpServer.handle("tools/list", M.createObjectNode()).path("tools")) out.add(t.path("name").asText());
        return out;
    }

    @Test
    void theMachinesToolsComeBackToTheListOnceNoHostedLibraryRuns(@TempDir Path tmp) throws Exception {
        Config.set(AccountService.ROOT, tmp.resolve("accounts").toString());
        store = AccountStore.open(AccountService.root().resolve("accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new AccountServiceTest.Here(self));
        AccountService.Made ann = svc.newAccount("Ann Example", "", true, "the test");
        AccountServiceTest.Caller a = AccountServiceTest.join(svc, ann.code(), "ann's long password");
        assertEquals(200, a.get("/").statusCode(), "her library runs");
        assertTrue(Hosted.any());
        assertFalse(listed().contains("library_models"), "a hosted library lists no tool of the machine's");
        svc.stop(); svc = null;
        assertFalse(Hosted.any(), "no hosted library runs now");
        assertTrue(listed().contains("library_models"), "and the list is the library's again: " + listed());
    }
}
