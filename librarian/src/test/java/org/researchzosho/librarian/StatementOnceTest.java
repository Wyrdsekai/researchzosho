package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A statement a library was given is good once for a change it asks the service to make for its person; a key is never made through a
 * library; and a library ends only its own keys (round F, the second review, S8: a library could reuse a visitor's statement for 70
 * seconds at the service's internal endpoints, mint a key, or revoke the visitor's keys for their other libraries).
 */
class StatementOnceTest {
    AccountStore store; AccountService svc;

    @AfterEach void stop() throws Exception { if (svc != null) svc.stop(); if (store != null) store.close(); Config.set(AccountService.ROOT, ""); }

    @Test
    void oneStatementStartsAtMostTheUsesOneRequestCan(@TempDir Path tmp) throws Exception {
        assertEquals(202, AccountService.USES_PER_STATEMENT, "a chat turn's own use, 8 calls of a list's items as runs (25 each), and one more");
        Config.set(AccountService.ROOT, tmp.resolve("accounts").toString());
        store = AccountStore.open(tmp.resolve("accounts/accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
        AccountService.Made ann = svc.newAccount("Ann Example", "", false, "the test");
        AccountStore.Signing signing = store.signing();
        String statement = Statement.sign(signing.key(), signing.kid(), Statement.Claims.now(svc.issuer, ann.library().id(), ann.account().id(), ann.account().name(), Statement.KEEPER, Statement.SESSION));
        HttpClient c = HttpClient.newHttpClient();
        String at = "http://127.0.0.1:" + svc.port() + Uses.START;
        int was = AccountService.USES_PER_STATEMENT;
        AccountService.USES_PER_STATEMENT = 3;
        try {
            for (int i = 1; i <= 4; i++) {
                HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(at)).header(Statement.HEADER, statement).POST(HttpRequest.BodyPublishers.ofString("{\"kind\":\"chat\",\"entry\":\"\"}")).build(), HttpResponse.BodyHandlers.ofString());
                if (i <= 3) assertTrue(r.statusCode() == 200 && r.body().contains("\"use\""), i + ": " + r.body());
                else assertEquals(403, r.statusCode(), "the cap's next start with one statement is refused: " + r.body());
            }
            String fresh = Statement.sign(signing.key(), signing.kid(), Statement.Claims.now(svc.issuer, ann.library().id(), ann.account().id(), ann.account().name(), Statement.KEEPER, Statement.SESSION));
            assertEquals(200, c.send(HttpRequest.newBuilder(URI.create(at)).header(Statement.HEADER, fresh).POST(HttpRequest.BodyPublishers.ofString("{\"kind\":\"chat\",\"entry\":\"\"}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "the next request's statement starts its own");
        } finally { AccountService.USES_PER_STATEMENT = was; }
    }

    @Test
    void aChangeIsMadeOnceForEachStatement(@TempDir Path tmp) throws Exception {
        Config.set(AccountService.ROOT, tmp.resolve("accounts").toString());
        store = AccountStore.open(tmp.resolve("accounts/accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
        AccountService.Made ann = svc.newAccount("Ann Example", "", false, "the test");
        AccountStore.Signing signing = store.signing();
        String statement = Statement.sign(signing.key(), signing.kid(), Statement.Claims.now(svc.issuer, ann.library().id(), ann.account().id(), ann.account().name(), Statement.KEEPER, Statement.KEY));
        HttpClient c = HttpClient.newHttpClient();
        String at = "http://127.0.0.1:" + svc.port() + ServiceAccess.PATH;
        HttpResponse<String> first = c.send(HttpRequest.newBuilder(URI.create(at)).header(Statement.HEADER, statement).POST(HttpRequest.BodyPublishers.ofString("{\"op\":\"invite\",\"name\":\"Bo Example\",\"level\":\"read\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, first.statusCode(), first.body());
        HttpResponse<String> again = c.send(HttpRequest.newBuilder(URI.create(at)).header(Statement.HEADER, statement).POST(HttpRequest.BodyPublishers.ofString("{\"op\":\"invite\",\"name\":\"Cy Example\",\"level\":\"write\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, again.statusCode(), "the same statement does not make a second change: " + again.body());
        // looking is not a change: the same statement lists as often as it is asked
        for (int i = 0; i < 2; i++) assertEquals(200, c.send(HttpRequest.newBuilder(URI.create(at)).header(Statement.HEADER, statement).POST(HttpRequest.BodyPublishers.ofString("{\"op\":\"list\"}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        // a key is not made through a library, by any way in; and a library ends only the keys for itself
        ObjectNode make = Statement.M.createObjectNode().put("op", "key_new").put("level", "write");
        assertThrows(ProtocolError.class, () -> AccountPages.access(svc, ann.library(), ann.account(), Statement.KEEPER, Statement.KEY, make));
        AccountStore.Library other = AccountService.newLibrary(store, tmp.resolve("accounts"), ann.account(), "Ann Example's notes");
        AccountStore.Minted elsewhere = store.mintKey(ann.account().id(), other.id(), "write", "notes key");
        ObjectNode end = Statement.M.createObjectNode().put("op", "key_revoke").put("id", elsewhere.key().id());
        assertThrows(ProtocolError.class, () -> AccountPages.access(svc, ann.library(), ann.account(), Statement.KEEPER, Statement.KEY, end));
        assertTrue(store.keys(ann.account().id()).stream().anyMatch(k -> k.id().equals(elsewhere.key().id())), "the key for the other library stands");
        AccountStore.Minted here = store.mintKey(ann.account().id(), ann.library().id(), "read", "here key");
        AccountPages.access(svc, ann.library(), ann.account(), Statement.KEEPER, Statement.KEY, Statement.M.createObjectNode().put("op", "key_revoke").put("label", "here key"));
        assertTrue(store.keys(ann.account().id()).stream().noneMatch(k -> k.id().equals(here.key().id())), "its own key is ended");
        // the store's word: once, then not
        assertTrue(store.once("abc", store.clock() + 60_000));
        assertTrue(!store.once("abc", store.clock() + 60_000));
    }
}
