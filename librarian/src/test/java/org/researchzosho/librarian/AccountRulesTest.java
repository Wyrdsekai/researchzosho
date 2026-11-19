package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who comes into a library, who opens the operator's page, what an invitation makes, and whose deletion is whose to undo (round F: the second
 * review's S10 and S11, proved on c8e057ed, and the owner's two decisions of 2026-11-18).
 */
class AccountRulesTest {
    AccountStore store; AccountService svc;

    void start(Path tmp, String signup) throws Exception {
        Config.set(AccountService.ROOT, tmp.resolve("accounts").toString());
        store = AccountStore.open(tmp.resolve("accounts/accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
        AccountService.SIGNUP_OVERRIDE = signup;
    }

    @AfterEach void stop() throws Exception { AccountService.SIGNUP_OVERRIDE = null; if (svc != null) svc.stop(); if (store != null) store.close(); Config.set(AccountService.ROOT, ""); }

    @Test
    void aKeeperChangesOnlyHerPeoplesLevelAndADeletedAdminHasNoOperatorsPage(@TempDir Path tmp) throws Exception {
        start(tmp, "invited");
        AccountService.Made ann = svc.newAccount("Ann Example", "", true, "the test");
        AccountServiceTest.Caller a = AccountServiceTest.join(svc, ann.code(), "ann's long password");
        AccountService.Made bo = svc.newAccount("Bo Example", "", true, "the test");
        HttpResponse<String> r = a.form("/account", "op=member-level&library=" + ann.library().id() + "&account=" + bo.account().id() + "&level=write");
        assertEquals(303, r.statusCode(), r.body());
        assertNull(store.level(store.library(ann.library().id()), bo.account().id()), "nobody comes in by a level set on them: only by taking an invitation (S10)");
        // an admin whose account is being deleted does not open the operator's page (S11)
        AccountServiceTest.join(svc, bo.code(), "bo's long password");
        AccountActs.delete(store, store.account(bo.account().id()), 30, AccountActs.By.of(null, null));
        AccountServiceTest.Caller b = new AccountServiceTest.Caller(svc.url());
        assertEquals(303, b.form("/login", "name=" + AccountServiceTest.enc("Bo Example") + "&password=" + AccountServiceTest.enc("bo's long password")).statusCode());
        assertEquals(403, b.get("/operator").statusCode());
    }

    @Test
    void anInvitationAndTheOperatorsActsAskForProofAndABrowserLetsNobodyInThroughALibrary(@TempDir Path tmp) throws Exception {
        // round F, S17: inviting oneself was the way around the proof a key asks for, from a library's page in the same browser
        start(tmp, "invited");
        AccountService.Made ann = svc.newAccount("Ann Example", "", true, "the test"), bo = svc.newAccount("Bo Example", "", false, "the test");
        AccountServiceTest.Caller a = AccountServiceTest.join(svc, ann.code(), "ann's long password");
        long was = AccountPages.PROVEN_MS;
        AccountPages.PROVEN_MS = 0;   // her sign-in was longer ago than the proof lasts
        try {
            HttpResponse<String> let = a.form("/account", "op=member-let-in&library=" + ann.library().id() + "&name=" + AccountServiceTest.enc("Cat New") + "&level=write");
            assertTrue(let.body().contains("show it is you") && !let.body().contains("/join?code="), "asked to show it is her, and no link made: " + let.body());
            assertEquals(0, store.invitations(ann.library().id()).size());
            HttpResponse<String> op = a.form("/operator", "op=account-suspend&account=" + bo.account().id() + "&confirm=yes");
            assertTrue(op.body().contains("show it is you"), op.body());
            assertEquals("active", store.account(bo.account().id()).state(), "nothing done before the proof");
            HttpResponse<String> proven = a.form("/operator", "op=account-suspend&account=" + bo.account().id() + "&confirm=yes&prove_password=" + AccountServiceTest.enc("ann's long password"));
            assertEquals(303, proven.statusCode(), proven.body());
            assertEquals("suspended", store.account(bo.account().id()).state(), "done with it");
        } finally { AccountPages.PROVEN_MS = was; }
        assertThrows(ProtocolError.class, () -> AccountPages.access(svc, ann.library(), ann.account(), Statement.KEEPER, Statement.SESSION, Statement.M.createObjectNode().put("op", "invite").put("name", "Cat New").put("level", "write")));
    }

    @Test
    void aMemberTakenOutOrSuspendedIsForgottenByTheLibraryAtTheNextHeartbeat(@TempDir Path tmp) throws Exception {
        // round F, S15: a removed member's webhook went on getting the library's changes until its process ended
        start(tmp, "invited");
        AccountService.Made ann = svc.newAccount("Ann Example", "", false, "the test"), ben = svc.newAccount("Ben Example", "", false, "the test"), cat = svc.newAccount("Cat Example", "", false, "the test");
        String lib = ann.library().id();
        store.setMember(lib, ben.account().id(), "read", ann.account().id());
        store.setMember(lib, cat.account().id(), "write", ann.account().id());
        AccountActs.suspend(store, store.account(cat.account().id()), AccountActs.By.cli());
        var people = Node.people(store, lib).path("members");
        assertEquals("keeper", people.path(Hosted.DID + ann.account().id()).asText());
        assertEquals("read", people.path(Hosted.DID + ben.account().id()).asText());
        assertTrue(people.path(Hosted.DID + cat.account().id()).isMissingNode(), "a suspended account is not among them: " + people);
        LibraryStore own = new LibraryStore(tmp.resolve("own")); own.init();
        Hosted.begin(own);
        try {
            for (AccountService.Made m : new AccountService.Made[]{ben, cat}) Hosted.note(own, Hosted.DID + m.account().id(), new Hosted.Vouch(Patrons.Level.read, false, m.account().id(), m.account().name()));
            Hosted.keepOnly(own, people);
            assertTrue(Patrons.mayRead(own, new Patrons.Patron(Hosted.DID + ben.account().id(), "", "")));
            assertFalse(Patrons.mayRead(own, new Patrons.Patron(Hosted.DID + cat.account().id(), "", "")), "forgotten: no change goes to her webhook");
        } finally { Hosted.end(own); }
    }

    @Test
    void anInvitationMakesNoLibraryOfItsOwnWhereSignUpWouldNot(@TempDir Path tmp) throws Exception {
        for (String signup : new String[]{"invited", "domain", "anyone"}) {
            start(tmp.resolve(signup), signup);
            AccountService.Made ann = svc.newAccount("Ann Example", "", false, "the test");
            AccountServiceTest.Caller a = AccountServiceTest.join(svc, ann.code(), "ann's long password");
            HttpResponse<String> let = a.form("/account", "op=member-let-in&library=" + ann.library().id() + "&name=" + AccountServiceTest.enc("Cat New") + "&level=read");
            AccountServiceTest.joinAsNew(svc, AccountServiceTest.found(let.body(), "/join\\?code=([A-Za-z0-9_-]+)"), "Cat New", "cat's long password");
            AccountStore.Account cat = store.accountByLogin("Cat New");
            assertEquals("read", store.level(store.library(ann.library().id()), cat.id()), signup + ": let into the library she was invited to");
            assertEquals(signup.equals("anyone"), !store.librariesKeptBy(cat.id()).isEmpty(), signup + ": a library of her own only where anyone may sign up");
            svc.stop(); store.close(); svc = null; store = null;
        }
    }

    @Test
    void theOperatorsDeletionIsAtOnceUnlessGivenGraceAndOnlyTheOperatorUndoesIt(@TempDir Path tmp) throws Exception {
        start(tmp, "invited");
        AccountService.Made one = svc.newAccount("Dee Example", "", false, "the test"), two = svc.newAccount("Eve Example", "", false, "the test"), three = svc.newAccount("Fay Example", "", false, "the test");
        // at the command line: at once by default
        assertEquals(0, AccountsCliTest.run("remove", "Dee Example").code());
        assertEquals("removed", store.account(one.account().id()).state());
        // with --grace: frozen for its days, and its person cannot keep it
        AccountServiceTest.join(svc, two.code(), "eve's long password");
        assertEquals(0, AccountsCliTest.run("remove", "Eve Example", "--grace").code());
        assertEquals("deleting", store.account(two.account().id()).state());
        AccountServiceTest.Caller eve = new AccountServiceTest.Caller(svc.url());
        assertEquals(303, eve.form("/login", "name=" + AccountServiceTest.enc("Eve Example") + "&password=" + AccountServiceTest.enc("eve's long password")).statusCode(), "a deleted account signs in to see it");
        eve.form("/account", "op=cancel-deletion");
        assertEquals("deleting", store.account(two.account().id()).state(), "she cannot keep what the operator deleted");
        assertFalse(eve.get("/account").body().contains("Keep my account"), "and is not offered it");
        assertEquals(0, AccountsCliTest.run("keep", "Eve Example").code());
        assertEquals("active", store.account(two.account().id()).state(), "the operator can");
        // a person's own deletion keeps its grace, and they may keep their account in it
        AccountServiceTest.Caller fay = AccountServiceTest.join(svc, three.code(), "fay's long password");
        assertEquals(303, fay.form("/account", "op=delete-account&confirm=" + AccountServiceTest.enc("Fay Example")).statusCode());
        assertEquals("deleting", store.account(three.account().id()).state());
        AccountServiceTest.Caller fay2 = new AccountServiceTest.Caller(svc.url());
        assertEquals(303, fay2.form("/login", "name=" + AccountServiceTest.enc("Fay Example") + "&password=" + AccountServiceTest.enc("fay's long password")).statusCode());
        assertTrue(fay2.get("/account").body().contains("Keep my account"));
        fay2.form("/account", "op=cancel-deletion");
        assertEquals("active", store.account(three.account().id()).state());
    }

    @Test
    void aLibraryMovedWhileItsAccountWasRemovedStaysRemoved(@TempDir Path tmp) throws Exception {
        start(tmp, "invited");
        AccountService.Made gus = svc.newAccount("Gus Example", "", false, "the test");
        store.forgetAccount(gus.account().id());   // removed while its library was being moved
        Path to = tmp.resolve("moved-here");
        AccountsCli.places(store, store.library(gus.library().id()), "move", to, false, new PrintStream(OutputStream.nullOutputStream()));
        assertEquals("removed", store.library(gus.library().id()).state(), "it is not served again");
        assertTrue(store.asides(gus.library().id()).stream().allMatch(a -> a.until() <= System.currentTimeMillis()), "and what it left is due to go: " + store.asides(gus.library().id()));
        new LibraryPlaces(store).sweep();
        assertTrue(store.asides(gus.library().id()).isEmpty(), "gone at the sweep");
        try (var left = Files.exists(to) ? Files.walk(to) : Stream.<Path>empty()) { List<Path> l = left.filter(p -> p.getFileName().toString().equals("library.md")).toList(); assertTrue(l.isEmpty(), "nothing of the library where it was moved to: " + l); }
    }
}
