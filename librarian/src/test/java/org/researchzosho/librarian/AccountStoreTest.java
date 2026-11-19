package org.researchzosho.librarian;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The account store: its tables are made by steps that only ever add, each applied once however many processes start together; a
 * released step is never edited; and what it keeps behaves the same on the file of one machine (SQLite, always tested) and on
 * PostgreSQL (tested where {@code RESEARCHZOSHO_TEST_POSTGRES} names a database to use, such as postgresql://user:password@host/db).
 */
class AccountStoreTest {

    /** Each released step's statements, hashed: a step that was released is never edited, a change is a new step. */
    static final Map<Integer, String> RELEASED = Map.of(1, "5f289b9d6478e70c5dbcd080ae5787c0b28aa6ea5fc5f2b41a832d272067bb18");

    static String hash(AccountSchema.Step s) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n", s.sql()).getBytes(StandardCharsets.UTF_8))); }

    @Test
    void aStepOnlyAddsAndAReleasedStepIsNeverEdited() throws Exception {
        int n = 0;
        for (AccountSchema.Step s : AccountSchema.STEPS) {
            assertEquals(++n, s.number(), "the steps are numbered in order, with none left out");
            for (String sql : s.sql()) {
                String u = sql.strip().toUpperCase();
                assertTrue(u.startsWith("CREATE TABLE ") || u.startsWith("CREATE INDEX ") || u.startsWith("CREATE UNIQUE INDEX ") || u.matches("ALTER TABLE \\w+ ADD COLUMN .*"), "a step creates a table or an index, or adds a column: " + sql);
                for (String never : List.of("DROP ", "RENAME ", "DELETE ", "UPDATE ", " TYPE ", "TRUNCATE ", "SERIAL", "AUTOINCREMENT", "JSON")) assertFalse(u.contains(never), "a step never has " + never.strip() + ": " + sql);
            }
            String pinned = RELEASED.get(s.number());
            if (pinned != null) assertEquals(pinned, hash(s), "step " + s.number() + " was released and is never edited: a change is a new step at the end");
        }
    }

    @Test
    void theStepsAreAppliedOnceHoweverManyOpenTheStoreTogether(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("accounts.db");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<AccountStore>> opened = new ArrayList<>();
        for (int i = 0; i < 6; i++) opened.add(pool.submit((Callable<AccountStore>) () -> AccountStore.open(file.toString())));
        for (Future<AccountStore> f : opened) f.get().close();
        pool.shutdown();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertEquals(0, AccountSchema.apply(c), "nothing left to apply");
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM schema_steps")) { r.next(); assertEquals(AccountSchema.STEPS.size(), r.getInt(1)); }
        }
    }

    @Test
    void theStoreOnAFile(@TempDir Path tmp) throws Exception { try (AccountStore s = AccountStore.open(tmp.resolve("accounts.db").toString())) { assertTrue(s.sqlite()); everything(s); } }

    @Test
    void theStoreOnPostgres() throws Exception {
        String where = System.getenv("RESEARCHZOSHO_TEST_POSTGRES");
        Assumptions.assumeTrue(where != null && !where.isBlank(), "no test database is named (RESEARCHZOSHO_TEST_POSTGRES)");
        try (AccountStore s = AccountStore.open(where)) { assertFalse(s.sqlite()); everything(s); }
    }

    /** The same on either database; the names carry a random tail, so a test database that is not emptied takes it again. */
    static void everything(AccountStore s) throws Exception {
        String tail = AccountStore.id("");
        AccountStore.Account ann = s.createAccount("Ann  Example " + tail, "Ann" + tail + "@Example.org", true), ben = s.createAccount("Ben Example " + tail, "", false);
        assertEquals("Ann Example " + tail, ann.name()); assertEquals("ann" + tail + "@example.org", ann.mail()); assertTrue(ann.admin() && ann.active() && !ann.hasPassword());
        assertEquals(ann.id(), s.accountByLogin("ann   example " + tail.toUpperCase()).id(), "case and spacing do not matter to the name a person signs in with");
        assertEquals(ann.id(), s.accountByMail("ANN" + tail + "@example.org").id());
        assertThrows(IOException.class, () -> s.createAccount("ann example " + tail, "", false), "a name is taken once");
        s.setPassword(ann.id(), Passwords.hash("a long password 1"));
        assertTrue(Passwords.check(s.account(ann.id()).passwordHash(), "a long password 1"));
        // a library, its keeper and its people
        String lib = "lib_" + tail, lib2 = "lib2_" + tail;
        s.addLibrary(lib, "ann-" + tail, "Ann's library", ann.id(), "/tmp/x");
        s.addLibrary(lib2, "ben-" + tail, "Ben's library", ben.id(), "/tmp/y");
        AccountStore.Library l = s.library(lib);
        assertEquals(Statement.KEEPER, s.level(l, ann.id())); assertNull(s.level(l, ben.id()));
        s.setMember(lib, ben.id(), "read", ann.id());
        assertEquals("read", s.level(l, ben.id()));
        s.setMember(lib, ben.id(), "write", ann.id());
        assertEquals("write", s.level(l, ben.id())); assertEquals(1, s.members(lib).size());
        assertEquals(List.of(lib2, lib), s.librariesOf(ben.id()).stream().map(AccountStore.Library::id).toList(), "the one they keep, then the ones they are let into");
        assertThrows(IOException.class, () -> s.setMember(lib, ben.id(), "keeper", ann.id()));
        // sessions: a secret shown once, its hash kept; ended, it is nobody
        String secret = s.newSession(ben.id(), "", "a browser", 30);
        assertEquals(ben.id(), s.session(secret).account()); assertNull(s.session(secret + "x")); assertEquals(1, s.sessions(ben.id()));
        s.sessionLibrary(secret, lib); assertEquals(lib, s.session(secret).library());
        assertNull(s.session(s.newSession(ben.id(), "", "expired", -1)), "a session past its days is nobody");
        // keys: for one account and one library; resolved by the secret; revoked, gone
        AccountStore.Minted key = s.mintKey(ben.id(), lib, "read", "an agent");
        assertTrue(key.secret().startsWith(ApiKeys.PREFIX) && key.key().reads() && key.key().library().equals(lib));
        assertEquals(key.key().id(), s.resolveKey(key.secret()).id()); assertNull(s.resolveKey(key.secret() + "x"));
        assertFalse(s.resolveKey(key.secret()).usedDay().isEmpty(), "the day it was last used is noted");
        // a member taken out: their keys for that library go with them, and their browser is no longer in it
        assertTrue(s.removeMember(lib, ben.id()));
        assertNull(s.level(l, ben.id())); assertNull(s.resolveKey(key.secret())); assertEquals("", s.session(secret).library());
        // a join link: once, within its days; a newer one withdraws the one before
        String first = s.newInvite(ben.id(), "test", 7), second = s.newInvite(ben.id(), "test", 7);
        assertNull(s.invited(first, false), "a newer link replaced it");
        assertEquals(ben.id(), s.invited(second, false)); assertEquals(ben.id(), s.invited(second, true)); assertNull(s.invited(second, false), "used once");
        assertNull(s.invited(s.newInvite(ben.id(), "test", -1), false), "past its days");
        // the provider's sign-in, bound once
        s.bind("https://issuer.example", "sub-" + tail, ann.id(), ann.mail());
        assertEquals(ann.id(), s.boundAccount("https://issuer.example", "sub-" + tail)); assertNull(s.boundAccount("https://issuer.example", "another-" + tail));
        // the signing key: made once, the same from then on, published
        AccountStore.Signing k = s.signing();
        assertEquals(k.kid(), s.signing().kid());
        assertTrue(s.publicJwks().stream().anyMatch(j -> j.contains(k.kid())));
        String token = Statement.sign(k.key(), k.kid(), Statement.Claims.now("svc", lib, ann.id(), ann.name(), Statement.KEEPER, Statement.SESSION));
        assertEquals(ann.id(), Statement.verify(token, kid -> Statement.fromJwk(Statement.M.readTree(s.publicJwks().stream().filter(j -> j.contains(kid)).findFirst().orElseThrow())), "svc", lib).sub());
        // an account closed signs in nowhere and is out of everything; its library is no longer served
        s.endSessions(ben.id());
        s.removeAccount(ben.id());
        assertNull(s.accountByLogin("Ben Example " + tail)); assertFalse(s.account(ben.id()).active()); assertFalse(s.library(lib2).active());
        assertNotNull(s.createAccount("Ben Example " + tail, "", false), "the name is free again");
    }
}
