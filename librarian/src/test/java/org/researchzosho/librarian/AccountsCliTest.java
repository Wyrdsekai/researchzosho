package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The account service at the command line, in the words a person reads: the first account and its join link, the list, a new link, a
 * second library, a person let into a library, a key, an account closed; and what {@code serve} says when the service starts, with the
 * sentence that the computer's own library is safe and how to get back to it.
 */
class AccountsCliTest {

    /**
     * The service's settings whose defaults the lines read below are written for: set to their defaults here, never taken from the home as
     * it was found. A run stopped in the middle of another class leaves what that class had set in the fork's home (2026-11-26: the
     * nights off, which turned this class's two tests red in the next run).
     */
    static final List<String> DEFAULTED = List.of(Scheduler.NIGHTS, Scheduler.AT_ONCE, LibraryPlaces.COPIES, AccountService.SIGNUP, Oidc.ISSUER);

    @BeforeEach void defaults() throws Exception { for (String k : DEFAULTED) Config.set(k, ""); }

    @AfterEach void reset() throws Exception { Config.set(AccountService.ROOT, ""); Config.set(AccountService.SETTING, ""); Config.set("RESEARCHZOSHO_LIBRARY", ""); for (String k : DEFAULTED) Config.set(k, ""); }

    record Ran(int code, String out, String err) { }

    static Ran run(String... words) {
        String[] args = new String[words.length + 2];
        args[0] = "librarian"; args[1] = "accounts";
        System.arraycopy(words, 0, args, 2, words.length);
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        int code = AccountsCli.run(args, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Ran(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    /** The whole command, as a person types it, with what it printed. */
    static Ran cli(String... words) {
        String[] args = new String[words.length + 1];
        args[0] = "librarian";
        System.arraycopy(words, 0, args, 1, words.length);
        PrintStream o = System.out, e = System.err;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(), be = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8)); System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
        try { return new Ran(LibrarianCli.run(args, "http://127.0.0.1:1", "m"), bo.toString(StandardCharsets.UTF_8), be.toString(StandardCharsets.UTF_8)); }
        finally { System.setOut(o); System.setErr(e); }
    }

    /**
     * A machine set up only to serve accounts has no library of its own, and needs none: the setting is turned on and off, every
     * setting is set, every accounts command runs and the service starts, and no library is made by any of it. With accounts off, a
     * machine with no library is told to make one, as it always was.
     */
    @Test
    void aMachineWithNoLibraryOfItsOwnServesAccounts(@TempDir Path tmp) throws Exception {
        Path none = tmp.resolve("no-library-here");
        Config.set("RESEARCHZOSHO_LIBRARY", none.toString());
        Config.set(AccountService.ROOT, tmp.resolve("accounts").toString());
        try {
            Ran before = cli("settings", "set", "drive", "http://127.0.0.1:9");
            assertEquals("no library at " + none + " — run `researchzosho init` to create one\n", before.out(), "accounts off, no library: as it always was");
            assertEquals(1, before.code());
            Ran on = cli("settings", "set", "accounts", "on");
            assertEquals("accounts is set. (on)\n", on.out()); assertEquals("", on.err()); assertEquals(0, on.code());
            assertTrue(AccountService.on());
            assertEquals("accounts_signup is set. (anyone)\n", cli("settings", "set", "accounts_signup", "anyone").out());
            assertEquals("accounts_signup is unset.\n", cli("settings", "unset", "accounts_signup").out());
            assertEquals("drive is set. (http://127.0.0.1:9)\n", cli("settings", "set", "drive", "http://127.0.0.1:9").out(), "with accounts on, every setting of the service is set with no library");
            assertEquals("drive is unset.\n", cli("settings", "unset", "drive").out());
            assertEquals("accounts_signup is one of invited, anyone, domain.\n", cli("settings", "set", "accounts_signup", "everybody").err());
            Ran list = cli("settings");
            assertTrue(list.out().startsWith("Settings of this service (") && list.out().contains("\n  accounts  = on\n") && list.out().endsWith("researchzosho settings set <name> <value> changes one, settings set <name> --file <path> reads it from a file (for a key); unset <name> removes it.\n"), list.out().substring(0, Math.min(300, list.out().length())));
            assertEquals("No account yet. Make the first one with: researchzosho accounts add \"<your name>\" --admin\n", cli("accounts").out());
            Ran ann = cli("accounts", "add", "Ann Example", "--admin");
            assertTrue(ann.out().startsWith("An account is made for Ann Example, with a library of its own (ann-example).\nSend them this link. It works once, for 7 days; there they choose their password:\nhttp://127.0.0.1:4649/join?code="), ann.out());
            assertEquals("Ann Example  (runs the service)  (has not chosen a password yet)\n    ann-example  \"Ann Example\"  keeps it\n", cli("accounts").out());
            assertTrue(cli("accounts", "link", "Ann Example").out().startsWith("A new link for Ann Example."));
            assertTrue(cli("accounts", "key", "Ann Example", "--read", "a script").out().startsWith("A key of Ann Example for \"Ann Example\", which reads."));
            assertEquals("A library \"Second\" is made (ann-example-2), kept by Ann Example. They open it from their account page.\n", cli("accounts", "library", "Ann Example", "Second").out());
            try (AccountStore store = AccountService.openStore()) {
                AccountService svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
                try {
                    List<String> said = AccountsCli.started(svc, "http://127.0.0.1:1");
                    assertEquals(List.of("The account service is at " + svc.url() + ". People sign in there, and each account has a library of its own.",
                            "  1 account, 2 libraries, kept under " + AccountService.root() + ".",
                            "  New accounts: you make them (researchzosho accounts add \"<name>\" prints the link to send).",
                            "  Sign-in: with a name and a password. No identity provider is set (RESEARCHZOSHO_OIDC_ISSUER names one).",
                            "  Libraries: one with nothing to do for 15 minutes is stopped, and the next request starts it again. Their nights begin from 01:00, each at a minute of its own, 2 at once."), said.subList(0, 5));
                    assertEquals("  missing: No folder for the libraries' copies is set, so a disk lost is its libraries lost. Name one, best on another disk: researchzosho settings set accounts_copies <folder>", said.get(5));
                    assertEquals(7, said.size(), "and the line of a model that does not answer; nothing about a library of the machine's own: " + said);
                    assertFalse(String.join("\n", said).contains("Your own library"));
                } finally { svc.stop(); }
            }
            assertEquals("accounts is set. (off)\n", cli("settings", "set", "accounts", "off").out());
            assertFalse(AccountService.on());
            assertFalse(Files.exists(none), "no library was made by any of it");
        } finally { Config.set("RESEARCHZOSHO_DRIVE", ""); Config.set(AccountService.SIGNUP, ""); }
    }

    @Test
    void whatThePersonWhoRunsTheServiceReads(@TempDir Path tmp) throws Exception {
        Config.set(AccountService.ROOT, tmp.resolve("accounts").toString());
        Config.set(AccountService.SETTING, "on");
        assertEquals("No account yet. Make the first one with: researchzosho accounts add \"<your name>\" --admin\n", run().out());
        Ran ann = run("add", "Ann", "Example", "--admin");
        assertEquals(0, ann.code(), ann.err());
        List<String> lines = ann.out().lines().toList();
        assertEquals("An account is made for Ann Example, with a library of its own (ann-example).", lines.get(0));
        assertEquals("Send them this link. It works once, for 7 days; there they choose their password:", lines.get(1));
        assertTrue(lines.get(2).matches("http://127\\.0\\.0\\.1:4649/join\\?code=[A-Za-z0-9_-]{20,}"), lines.get(2));
        assertEquals(0, run("add", "Ben Example", "--mail", "ben@example.org").code());
        assertEquals(1, run("add", "ann example").code(), "a name is taken once");
        assertEquals("Ann Example  (runs the service)  (has not chosen a password yet)\n    ann-example  \"Ann Example\"  keeps it\nBen Example <ben@example.org>  (has not chosen a password yet)\n    ben-example  \"Ben Example\"  keeps it\n", run("list").out());
        Ran link = run("link", "Ben Example");
        assertTrue(link.out().startsWith("A new link for Ben Example. It works once, for 7 days, and the one before it no longer opens:\nhttp://127.0.0.1:4649/join?code="), link.out());
        assertEquals("A library \"Harbour history\" is made (ann-example-2), kept by Ann Example. They open it from their account page.\n", run("library", "Ann Example", "Harbour", "history").out());
        assertEquals("Ben Example reads \"Harbour history\" from now on.\n", run("member", "ann-example-2", "Ben Example", "read").out());
        Ran readKey = run("key", "Ben Example", "--write", "--library", "ann-example-2", "his agent");
        assertEquals(1, readKey.code()); assertEquals("Ben Example reads \"Harbour history\" and does not write to it, so a key of theirs for it reads only.\n", readKey.err());
        Ran key = run("key", "Ann Example", "--write", "her agent");
        List<String> k = key.out().lines().toList();
        assertEquals("A key of Ann Example for \"Ann Example\", which reads and writes. It is shown this once; the service keeps only its hash:", k.get(0));
        assertTrue(k.get(1).startsWith("rz_") && k.get(1).length() > 40, k.get(1));
        assertEquals("A program sends it as Authorization: Bearer <key> to http://127.0.0.1:4649.", k.get(2));
        assertEquals("Ben Example is out of \"Harbour history\"; their keys for it are revoked.\n", run("member", "ann-example-2", "Ben Example", "remove").out());
        assertEquals("No account is named Nobody Here. researchzosho accounts lists them.\n", run("link", "Nobody Here").err());
        Ran gone = run("remove", "Ben Example", "--grace");   // with --grace: deleted after its grace, frozen until then; its folder stays until that day (at once without, the owner, 2026-11-18)
        assertTrue(gone.out().startsWith("The account of Ben Example is frozen now: it signs in only to keep itself, its keys are refused, its work is stopped, and the libraries it keeps open for nobody.\n  It is removed on "), gone.out());
        assertTrue(gone.out().contains("Until then it is kept, as it was, with: researchzosho accounts keep \"Ben Example\""), gone.out());
        assertTrue(Files.isDirectory(tmp.resolve("accounts").resolve("libraries").resolve("ben-example").resolve("library")), "the folder stays until its day");
        assertEquals(2, run("no-such-verb").code());
        // what serve says
        Path own = tmp.resolve("own-library");
        new LibraryStore(own).init();
        Config.set("RESEARCHZOSHO_LIBRARY", own.toString());
        try (AccountStore store = AccountService.openStore()) {
            AccountService svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
            try {
                List<String> said = AccountsCli.started(svc, "http://127.0.0.1:1");
                assertEquals("The account service is at " + svc.url() + ". People sign in there, and each account has a library of its own.", said.get(0));
                assertEquals("  2 accounts, 3 libraries, kept under " + AccountService.root() + ".", said.get(1));   // Ben's account waits for its day, with its library
                assertEquals("  New accounts: you make them (researchzosho accounts add \"<name>\" prints the link to send).", said.get(2));
                assertEquals("  Sign-in: with a name and a password. No identity provider is set (RESEARCHZOSHO_OIDC_ISSUER names one).", said.get(3));
                assertEquals("  Libraries: one with nothing to do for 15 minutes is stopped, and the next request starts it again. Their nights begin from 01:00, each at a minute of its own, 2 at once.", said.get(4));
                assertEquals("  Your own library at " + own + " is not served while accounts are on. Nothing in it is changed, and it is safe. To go back to it, turn accounts off (researchzosho settings set accounts off) and start the service again.", said.get(6));
                assertEquals("  missing: No folder for the libraries' copies is set, so a disk lost is its libraries lost. Name one, best on another disk: researchzosho settings set accounts_copies <folder>", said.get(5));
                assertTrue(said.get(7).startsWith("  missing: No model answers at http://127.0.0.1:1."), said.get(7));
                assertEquals(8, said.size(), said.toString());
                Config.set(LibraryPlaces.COPIES, tmp.resolve("copies").toString());
                try { assertEquals("  Copies: in " + tmp.resolve("copies").toAbsolutePath().normalize() + ", brought up to date when a library goes to sleep, after each job, and every 10 minutes when something changed.", AccountsCli.started(svc, "http://127.0.0.1:1").get(5)); }
                finally { Config.set(LibraryPlaces.COPIES, ""); }
                Node.SLEEP_AFTER_MS = 60_000L;
                try { assertTrue(AccountsCli.started(svc, "http://127.0.0.1:1").get(4).startsWith("  Libraries: one with nothing to do for a minute is stopped, and the next request starts it again."), "one minute is a minute"); }
                finally { Node.SLEEP_AFTER_MS = null; }
                Node.SLEEP_AFTER_MS = 0L;
                try { assertTrue(AccountsCli.started(svc, "http://127.0.0.1:1").get(4).startsWith("  Libraries: none is ever stopped for having nothing to do (accounts_sleep_after is off)."), "and off is said as off"); }
                finally { Node.SLEEP_AFTER_MS = null; }
            } finally { svc.stop(); }
        }
        assertFalse(Files.exists(own.resolve("catalog").resolve("serve.lock")), "the computer's own library was not opened as a service");
    }
}
