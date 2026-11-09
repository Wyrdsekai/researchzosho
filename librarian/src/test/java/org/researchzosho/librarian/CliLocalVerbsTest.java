package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The command line on the library on this computer: search, get, read, established, changes, submit and help answer through the same
 * protocol calls as when connected to a library elsewhere (until 2026-11-07 they answered only then); jobs --choose answers a run that
 * waits for one of its named choices; the usage names seeks, the video verbs and the research flags.
 */
class CliLocalVerbsTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;
    String realHome;

    @BeforeEach void scratch() throws Exception {
        QuietHome.quiet(tmp.resolve("home"));
        realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(new Finding("F-0001-gears", "The gears were cut by hand", List.of("gears--cutting"), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.now().toString(), "2026-09-05", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://museum.example/gears", "n/a", "the museum's own page")), List.of(), null, "The museum states the gears were cut with files.\n"));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Config.set("RESEARCHZOSHO_LIBRARY", tmp.resolve("lib").toString());
    }
    @AfterEach void back() { System.setProperty("user.home", realHome); Config.invalidate(); }

    record Ran(int code, String out, String err) { }

    static Ran run(String... words) {
        String[] args = new String[words.length + 1]; args[0] = "researchzosho"; System.arraycopy(words, 0, args, 1, words.length);
        PrintStream o = System.out, e = System.err;
        ByteArrayOutputStream bo = new ByteArrayOutputStream(), be = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bo, true, StandardCharsets.UTF_8)); System.setErr(new PrintStream(be, true, StandardCharsets.UTF_8));
        try { return new Ran(LibrarianCli.run(args, "http://127.0.0.1:1", "m"), bo.toString(StandardCharsets.UTF_8), be.toString(StandardCharsets.UTF_8)); }
        finally { System.setOut(o); System.setErr(e); }
    }

    @Test
    void theReadingVerbsWorkOnTheLocalLibrary() {
        assertFalse(Remote.connected());
        Ran search = run("search", "gears");
        assertEquals(0, search.code(), search.err());
        assertTrue(search.out().contains("- [F-0001-gears] The gears were cut by hand"), search.out());
        Ran status = run("search", "status");
        assertTrue(status.out().contains("SearXNG"), "the backend's own words keep their meaning: " + status.out());
        Ran get = run("get", "F-0001-gears");
        assertEquals(0, get.code(), get.err());
        assertTrue(get.out().startsWith("The gears were cut by hand  [F-0001-gears]") && get.out().contains("The museum states the gears were cut with files."), get.out());
        Ran est = run("established", "the", "gears", "were", "cut", "by", "hand");
        assertEquals(0, est.code(), est.err());
        assertTrue(est.out().startsWith("verdict: established"), est.out());
        Ran changes = run("changes");
        assertEquals(0, changes.code(), changes.err());
        assertTrue(changes.out().startsWith("No changes") || changes.out().contains("F-0001-gears"), changes.out());
        Ran read = run("read", "https://nowhere.example/page");
        assertEquals(1, read.code(), "a page the library never saved: " + read.out());
        assertTrue(read.err().contains("No saved page matches"), read.err());
        Ran help = run("help", "how", "do", "I", "update");
        assertEquals(0, help.code(), help.err());
        assertTrue(help.out().contains("ResearchZosho") && help.out().contains("From "), help.out());
        Ran usage = run("help");
        assertTrue(usage.out().startsWith("usage: researchzosho"), usage.out());
    }

    @Test
    void aClaimOfThePersonsOwnIsFiledAsADraft() throws Exception {
        Ran noSource = run("submit", "The dials show the Saros cycle of 223 months.");
        assertEquals(2, noSource.code(), "a claim without a source is refused: " + noSource.out());
        Ran filed = run("submit", "The dials show the Saros cycle of 223 months.", "--source", "https://museum.example/dials", "--why", "the museum's own page says so");
        assertEquals(0, filed.code(), filed.err());
        assertTrue(filed.out().startsWith("Filed as a draft: [F-"), filed.out());
        String id = filed.out().substring("Filed as a draft: [".length(), filed.out().indexOf(']'));
        Finding f = new LibraryStore(tmp.resolve("lib")).finding(id);
        assertNotNull(f); assertEquals(Finding.State.draft, f.state()); assertEquals("https://museum.example/dials", f.sources().get(0).locator());
    }

    @Test
    void aRunWaitingForAChoiceIsAnsweredFromTheCommandLine() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib"));
        ObjectNode ask = M.createObjectNode().put("question", "Which spectrometer should the lab buy?").put("choice_question", "Which kind?");
        ask.putArray("choices").add("Raman").add("FTIR");
        ask.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        String id = new LibraryProtocol(store).research(ask).path("job_id").asText();
        Ran wrong = run("jobs", id, "--choose", "Lidar");
        assertEquals(1, wrong.code(), wrong.out());
        assertTrue(wrong.err().contains("Say which, by number or by name: 1 Raman, 2 FTIR."), wrong.err());
        Ran chosen = run("jobs", id, "--choose", "2");
        assertEquals(0, chosen.code(), chosen.err());
        assertTrue(chosen.out().startsWith("Taken: FTIR. Research run " + id + " is queued."), chosen.out());
    }

    @Test
    void theUsageNamesWhatTheCommandTakes() {
        String u = LibrarianCli.USAGE;
        for (String line : List.of("seeks [list | read <group> [<J-…>] | merge <group> | drop <group>]", "video saw <what you remember…>", "video candidates <the thing, once per language",
                "research ask \"<question…>\" [--goal <name>] [--model <name>] [--seek <group>] [--allow explicit,howto]", "jobs <J-…> --choose <n|name>",
                "search <words…>", "get <id>", "read <address or id> [--in <language>]", "established <claim…>", "changes [--since <cursor>] [--limit N]", "submit \"<claim…>\" --source <url>", "help <words…>"))
            assertTrue(u.contains(line), line);
    }
}
