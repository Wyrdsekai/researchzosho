package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The second review's low findings (round F): each the smallest check of what was wrong. */
class SecondReviewLowsTest {
    AccountStore store; AccountService svc;

    @AfterEach void stop() throws Exception { if (svc != null) svc.stop(); if (store != null) store.close(); Config.set(AccountService.ROOT, ""); }

    void start(Path tmp) throws Exception {
        Config.set(AccountService.ROOT, tmp.resolve("accounts").toString());
        store = AccountStore.open(tmp.resolve("accounts/accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
    }

    @Test
    void theSignInPageNeverShowsTheProvidersWords(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountServiceTest.Caller someone = new AccountServiceTest.Caller(svc.url());
        someone.cookie = Oidc.SIGNIN + "=" + Oidc.stateMark("anything");
        HttpResponse<String> r = someone.get("/login/oidc/callback?state=anything&error=access_denied&error_description=" + AccountServiceTest.enc("Your account is locked. Sign in at https://evil.example/restore"));
        String to = r.headers().firstValue("Location").orElse("");
        HttpResponse<String> page = new AccountServiceTest.Caller(svc.url()).get(to.isEmpty() ? "/login" : to);
        assertFalse(page.body().contains("evil.example") || page.body().contains("locked"), page.body());
        HttpResponse<String> made = someone.get("/login/oidc/callback?state=anything&error=" + AccountServiceTest.enc("call +1 555 0100"));
        String to2 = made.headers().firstValue("Location").orElse("");
        assertFalse(new AccountServiceTest.Caller(svc.url()).get(to2.isEmpty() ? "/login" : to2).body().contains("555 0100"), "a word outside OAuth's own list is not said either");
    }

    @Test
    void aLibraryCannotSetTheServicesCookies() {
        assertTrue(AccountService.servicesCookie("Set-Cookie", AccountService.COOKIE + "=x; Path=/"));
        assertTrue(AccountService.servicesCookie("set-cookie", Oidc.SIGNIN + "=y"));
        assertTrue(AccountService.servicesCookie("Set-Cookie", AccountPages.JOIN + "=z"));
        assertFalse(AccountService.servicesCookie("Set-Cookie", Chrome.COOKIE + "=ja; Path=/"), "the library's own language cookie passes");
        assertFalse(AccountService.servicesCookie("Content-Type", AccountService.COOKIE + "=x"));
    }

    @Test
    void aPasswordSetThroughAJoinLinkSignsOutTheAccountsOtherBrowsers(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountService.Made ann = svc.newAccount("Ann Example", "", false, "the test");
        AccountServiceTest.Caller first = AccountServiceTest.join(svc, ann.code(), "ann's long password");
        assertEquals(200, first.get("/account").statusCode());
        AccountServiceTest.join(svc, store.newInvite(ann.account().id(), "the test", 7), "ann's other long password");
        HttpResponse<String> after = first.get("/account");
        assertNotEquals(200, after.statusCode(), "the first browser is signed out");
    }

    @Test
    void theOutboxCountsRefusalsOnTheirOwnAndNeverSendsAnOlderWholeAfterANewer(@TempDir Path tmp) throws Exception {
        // round F, money 8
        assertEquals(0, PluginSpending.refusals("java.net.http.HttpTimeoutException: request timed out"));
        assertEquals(3, PluginSpending.refusals("HTTP 400; refused 3"));
        assertEquals(3, PluginSpending.refusals("java.io.IOException: closed; refused 3"), "a timeout between refusals keeps their count");
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        store.outboxAdd("use_a", "{}");
        store.outboxAdd("use_a~2500000", "{}");
        long first = store.outboxHead().seq();
        assertTrue(store.outboxNewerFor("use_a", first), "a newer whole waits behind it");
        long late = Long.MAX_VALUE - 1;
        store.outboxTakenFor("use_a", late);
        assertTrue(store.outboxNewerFor("use_a", first), "one taken later is newer too");
        assertFalse(store.outboxNewerFor("use_a", late));
        assertFalse(store.outboxNewerFor("use_b", first));
        store.outboxTaken(first);
        long next = store.outboxHead().seq();
        store.outboxTaken(next);
        store.outboxAdd("use_c", "{}");
        assertTrue(store.outboxHead().seq() > next, "a number once taken is never given again");
    }

    @Test
    void theMinorCountsOfMoney10(@TempDir Path tmp) throws Exception {
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        String month = AccountStore.month(System.currentTimeMillis());
        // searches made at once see each other: one put aside holds the last of the cap
        String held = store.reserveCount("acct_s", "search", 1, month);
        assertFalse(held.startsWith("!"));
        assertEquals("!", store.reserveCount("acct_s", "search", 1, month), "a second search at once is refused at a cap of one");
        store.unreserve(held);
        assertFalse(store.reserveCount("acct_s", "search", 1, month).startsWith("!"), "and given back when the first has passed");
        // a job's minutes count from its start, not its filing
        long hourAgo = System.currentTimeMillis() - 3_600_000L;
        AccountStore.Use u = new AccountStore.Use(AccountStore.id("use_"), "acct_m", "lib_acct_m", SpendPurse.RESEARCH, "", "operator", "J-1", 0, 0, -1, 30, "open", "", hourAgo, 0, 0);
        store.addUse(u);
        assertTrue(NodeGates.reached(store, u) != null, "thirty minutes from its filing an hour ago");
        assertEquals(null, NodeGates.reached(store, u, System.currentTimeMillis() - 60_000L), "a minute from its start");
        // the month is the same moment's on every machine
        assertEquals("2026-11", AccountStore.month(Instant.parse("2026-11-30T23:30:00Z").toEpochMilli()));
        assertEquals("2026-12", AccountStore.month(Instant.parse("2026-12-01T00:30:00Z").toEpochMilli()));
        // a setting is a secret by its kind, not by a word in a name an operator chose
        assertFalse(ServiceSettings.secret(ModelEntries.key("token-counter", "PRICES")), "an entry's prices are shared, whatever it is named");
        assertTrue(ServiceSettings.secret("RESEARCHZOSHO_API_KEY"));
    }

    @Test
    void aPlugInsTokenIsNoStatementAndAnArchivesIdIsALibrarysAndGroupsDoNotShareAHash(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountStore.Signing k = store.signing();
        ObjectNode claims = Statement.M.createObjectNode().put("iss", svc.issuer).put("aud", "lib_x").put("sub", "acct_x").put("lvl", "keeper").put("via", "session")
                .put("iat", System.currentTimeMillis() / 1000).put("exp", System.currentTimeMillis() / 1000 + 60);
        String token = Statement.signJson(k.key(), k.kid(), claims, Statement.SPEND_TYP);
        assertThrows(IOException.class, () -> Statement.read(token, svc::ownKey, svc.issuer));
        assertNotEquals(ContainerLibraries.libraryGroup("lib_Aa"), ContainerLibraries.libraryGroup("lib_BB"), "two ids String.hashCode sends to one number");
        assertEquals(Map.of().size(), WebGate.named(Map.of("HTTPS_PROXY", "http://proxy.example:3128", "OPENAI_BASE", "http://10.0.0.9:8080")).size(), "only the library's own settings name an address");
        assertTrue(WebGate.named(Map.of("RESEARCHZOSHO_DRIVE", "http://10.0.0.5:8080/v1")).contains("10.0.0.5:8080"));
        // the take-home archive carries none of other people's things
        assertFalse(LibraryArchive.carried("catalog/webhooks.md"));
        assertFalse(LibraryArchive.carried("catalog/chat/readers/0a1b2c3d4e5f6071/2026-11-18.jsonl"));
        assertTrue(LibraryArchive.carried("catalog/chat/2026-11-18-ab12.jsonl"), "the keeper's own conversations go");
    }
}
