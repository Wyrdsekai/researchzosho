package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The reader commands as a person types them. The sign-in page shows two commands for making the owner's token; they are run here
 * word for word, so the page and the command cannot drift apart again (the page once said `reader add did:key:me "Me" write`, which
 * the command did not take, and the token's refusal named a command from before the rename).
 */
class ReaderCommandsTest {

    /** A typed command line as argv: quoted words stay one word; the program's own name becomes the CLI's leading "librarian". */
    static String[] typed(String line) {
        List<String> out = new ArrayList<>();
        out.add("librarian");
        Matcher m = Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(line);
        boolean first = true;
        while (m.find()) {
            String w = m.group(1) != null ? m.group(1) : m.group(2);
            if (first) { first = false; assertEquals("researchzosho", w, "the page shows the command by its name"); continue; }
            out.add(w);
        }
        return out.toArray(String[]::new);
    }

    private record Ran(int code, String out, String err) { }

    private static Ran run(LibraryStore store, String line) throws IOException {
        PrintStream o = System.out, e = System.err;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(), be = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8)); System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
        try { return new Ran(LibrarianCli.patron(store, typed(line)), bo.toString(StandardCharsets.UTF_8), be.toString(StandardCharsets.UTF_8)); }
        finally { System.setOut(o); System.setErr(e); }
    }

    private static Patrons.Entry listed(LibraryStore store, String did) throws IOException {
        return Patrons.load(store).listed().stream().filter(x -> x.did().equals(did)).findFirst().orElse(null);
    }

    @Test
    void theSignInPagesCommandsMakeAToken(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        String[] lines = Patrons.TOKEN_HOWTO.split("\n");
        assertEquals(2, lines.length);
        Ran allow = run(store, lines[0]);
        assertEquals(0, allow.code(), allow.err());
        Patrons.Entry me = listed(store, "did:key:me");
        assertNotNull(me, "did:key:me is on the list");
        assertEquals(Patrons.Level.write, me.level());
        assertEquals("Me", me.name(), "the quoted name is the name");
        assertTrue(allow.out().contains("researchzosho reader token did:key:me"), "it says how the token is made next: " + allow.out());
        Ran token = run(store, lines[1]);
        assertEquals(0, token.code(), token.err());
        String[] printed = token.out().strip().split("\n");
        assertTrue(printed[0].contains("did:key:me"), token.out());
        assertTrue(printed[printed.length - 1].matches("[A-Za-z0-9_-]{20,}"), "the token is the last line: " + token.out());
    }

    @Test
    void theOldPagesOrderWorksToo(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Ran r = run(store, "researchzosho reader add did:key:you \"A Friend\" read");
        assertEquals(0, r.code(), r.err());
        Patrons.Entry you = listed(store, "did:key:you");
        assertNotNull(you);
        assertEquals(Patrons.Level.read, you.level());
        assertEquals("A Friend", you.name());
        assertTrue(r.out().contains("did:key:you (A Friend) may read"), r.out());
    }

    @Test
    void aMissingLevelIsExplainedWithTheRealCommand(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Ran r = run(store, "researchzosho reader allow did:key:x \"Someone\"");
        assertEquals(2, r.code());
        assertTrue(r.err().contains("deny, read or write") && r.err().contains("researchzosho reader allow did:key:x write"), r.err());
        assertNull(listed(store, "did:key:x"), "nothing is written for a command that was not understood");
        Ran usage = run(store, "researchzosho reader nonsense");
        assertEquals(2, usage.code());
        assertTrue(usage.err().contains("researchzosho reader list") && !usage.err().contains("patron"), "the usage names the command as it is spelled now: " + usage.err());
    }

    @Test
    void aTokenForSomeoneNotListedSaysWhatToDoFirst(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        IOException e = assertThrows(IOException.class, () -> run(store, "researchzosho reader token did:key:nobody"));
        assertTrue(e.getMessage().contains("researchzosho reader allow did:key:nobody write"), e.getMessage());
        assertFalse(e.getMessage().contains("librarian patron"), "no command from before the rename: " + e.getMessage());
    }
}
