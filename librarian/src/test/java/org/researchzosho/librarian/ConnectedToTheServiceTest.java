package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command line of a person's own machine, connected to an account service with a key ({@code connect <address> <key>}): the
 * connected verbs reach the key's library through the service, and {@code access} shows and changes the people of the library and the
 * caller's own keys for it, in sentences. The library is a real process the service starts, so this process stays a person's machine.
 */
class ConnectedToTheServiceTest {

    record Ran(int code, String out, String err) { }

    static Ran run(String... words) {
        String[] args = new String[words.length + 1];
        args[0] = "librarian";
        System.arraycopy(words, 0, args, 1, words.length);
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        int code = Remote.run(args, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Ran(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    @Test
    void connectedWithAKeyThePeopleAndTheKeysAreAtTheCommandLine(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("accounts");
        AccountStore store = AccountStore.open(root.resolve("accounts.db").toString());
        AccountService svc = AccountService.start(store, "127.0.0.1", 0, self -> new ChildLibraries(store, self));
        String was = Config.stored("RESEARCHZOSHO_LIBRARY"), wasToken = Config.stored(Remote.TOKEN);
        try {
            AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
            String key = store.mintKey(ann.account().id(), ann.library().id(), "write", "her laptop").secret();
            ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
            assertEquals(0, Remote.connect(new String[]{"librarian", "connect", svc.url(), key}, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8)), e.toString(StandardCharsets.UTF_8));
            assertTrue(o.toString(StandardCharsets.UTF_8).startsWith("Connected to Ann Example at " + svc.url() + ": 0 claims, 0 reports, 0 saved pages.\n"), o.toString(StandardCharsets.UTF_8));
            assertTrue(Remote.connected());
            assertTrue(run("status").out().startsWith("library Ann Example at " + svc.url()), "the connected verbs reach the key's library");
            assertEquals("Filed as an open question at " + svc.url() + ".\n", run("questions", "add", "What did the first reader ask for at the desk on the day the library opened?").out());
            // the people of the library
            assertEquals("Ann Example keeps this library.\nNobody else opens it.\n", run("access").out());
            Ran invited = run("access", "invite", "Ben", "Example", "write");
            List<String> lines = invited.out().lines().toList();
            assertEquals("Send Ben Example this link. It works once, for 7 days.", lines.get(0));
            assertTrue(lines.get(1).startsWith(svc.url() + "/join?code="), lines.get(1));
            assertEquals("Ann Example keeps this library.\nNobody else opens it.\n", run("access").out(), "nobody is let in by a name");
            AccountServiceTest.joinAsNew(svc, lines.get(1).substring(lines.get(1).indexOf("code=") + 5), "Ben Example", "ben's long password");
            assertEquals("Ann Example keeps this library.\n  Ben Example  reads and writes\n", run("access").out(), "let in once he took the link");
            List<String> again = run("access", "invite", "ben example", "read").out().lines().toList();
            assertEquals("Send ben example this link. It works once, for 7 days.", again.get(0), "a name of someone here is a link all the same");
            assertEquals("Ann Example keeps this library.\n  Ben Example  reads and writes\n", run("access").out(), "and changes nothing until it is taken");
            // the caller's own keys: a key is made on the account page, never through a library (round F, S8); the command line says so
            Ran made = run("access", "key", "new", "a", "script", "--read");
            assertTrue(made.code() != 0, "refused: " + made.out());
            assertEquals("", made.out(), "no key is shown");
            assertTrue(made.err().contains("A key is made on your account page, where you show it is you first."), made.err());
            assertEquals(1, run("access", "keys").out().lines().count(), "no key was made");
            store.mintKey(ann.account().id(), ann.library().id(), "read", "a script");   // made on her account page instead
            Ran keys = run("access", "keys");
            assertEquals(2, keys.out().lines().count(), keys.out());
            String line = keys.out().lines().filter(l -> l.endsWith("  a script")).findFirst().orElseThrow();
            assertTrue(line.matches("K-\\w+  rz_[\\w-]+…  reads             never used  a script"), line);   // a key is base64url: its first letters may hold - and _
            assertEquals("The key is revoked; a program that used it is refused from now on.\n", run("access", "key", "revoke", line.substring(0, line.indexOf(' '))).out());
            assertEquals(1, run("access", "keys").out().lines().count());
            // someone taken out; nobody of that name
            assertEquals("Ben Example is removed. Their next request to this library is refused, and their keys for it are gone.\n", run("access", "remove", "Ben Example").out());
            Ran nobody = run("access", "remove", "Nobody Here");
            assertEquals(1, nobody.code()); assertTrue(nobody.err().contains("Nobody of that name is let into this library."), nobody.err());
            assertEquals(2, run("access", "key").code()); assertTrue(run("access", "no-such").err().startsWith("usage: researchzosho access"));
            // the verbs of the library's own folder stay where they were
            assertEquals(2, run("reader", "list").code());
            // a key that reads changes nothing
            String reads = store.mintKey(ann.account().id(), ann.library().id(), "read", "reads").secret();
            Config.set(Remote.TOKEN, reads);
            assertEquals("Ann Example keeps this library.\nNobody else opens it.\n", run("access").out());
            Ran refused = run("access", "invite", "Cat New");
            assertEquals(1, refused.code()); assertTrue(refused.err().contains("This key reads only."), refused.err());
            assertFalse(run("access").out().contains("Cat New"));
        } finally {
            Config.set("RESEARCHZOSHO_LIBRARY", was == null ? "" : was); Config.set(Remote.TOKEN, wasToken == null ? "" : wasToken);
            svc.stop(); store.close();
        }
    }
}
