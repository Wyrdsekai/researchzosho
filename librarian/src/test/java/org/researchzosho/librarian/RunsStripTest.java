package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** The strip beside the chat (0.7.0): the library's runs, named for what they are for, running first, finished within the day after, with a bar and a stop. */
class RunsStripTest {

    static final ObjectMapper M = new ObjectMapper();

    @Test
    void theStripNamesEveryRunForWhatItIsFor(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibraryProtocol p = new LibraryProtocol(store);
        Patrons.Patron patron = Patrons.Patron.PERSON;
        String empty = Pages.runsStrip(store, p, patron);
        assertTrue(empty.startsWith("<div class=\"striphead\" data-active=\"0\"><span>Runs</span><span>none</span></div>") && empty.contains("Nothing running. The housekeeping runs at "), empty);
        Jobs jobs = new Jobs(store, j -> "");
        String titled = jobs.submit("research", "person", M.createObjectNode().put("question", "What is Halden Optics, the company, today?").put("title", "Company card: Halden Optics"));
        String plain = jobs.submit("research", "person", M.createObjectNode().put("question", "Why do eclipses repeat, and what is the saros cycle that the ancients knew?"));
        String nightly = jobs.submit("crews", "", M.createObjectNode());
        // a run finished an hour ago, written as the runner leaves it
        Path month = jobs.dir().resolve("done").resolve("2026-11"); Files.createDirectories(month);
        ObjectNode done = M.createObjectNode(); done.put("job_id", "J-0001"); done.put("kind", "research"); done.put("state", "done"); done.put("patron", "person");
        long now = System.currentTimeMillis();
        done.put("queued_at", Instant.ofEpochMilli(now - 7_200_000).toString()); done.put("started_at", Instant.ofEpochMilli(now - 4_200_000).toString()); done.put("ended_at", Instant.ofEpochMilli(now - 3_600_000).toString());
        done.putObject("args").put("question", "Who funds instrument makers in Greater Boston?"); done.put("result", "investigation I-0001-who-funds"); done.put("investigation", "I-0001-who-funds");
        Files.writeString(month.resolve("J-0001.json"), M.writeValueAsString(done));
        ObjectNode old = done.deepCopy(); old.put("job_id", "J-0000"); old.put("ended_at", Instant.ofEpochMilli(now - 3 * 86_400_000L).toString()); old.putObject("args").put("question", "An old one");
        Files.writeString(month.resolve("J-0000.json"), M.writeValueAsString(old));
        String s = Pages.runsStrip(store, p, patron);
        assertTrue(s.startsWith("<div class=\"striphead\" data-active=\"1\"><span>Runs</span><span>3 waiting</span></div>"), s);
        assertTrue(s.contains("title=\"" + titled + "\">Company card: Halden Optics</a>"), "a run filed with a title is named by it, the id in the tooltip: " + s);
        assertTrue(s.contains("title=\"" + plain + "\">Why do eclipses repeat, and what is the saros cycle that the…</a>") || s.contains("title=\"" + plain + "\">Why do eclipses repeat"), "a run without one by its question, cut short: " + s);
        assertTrue(s.contains("title=\"" + nightly + "\">The nightly housekeeping</a>"), s);
        assertTrue(s.contains("<div class=\"rline queued\">") && s.contains("<button class=\"stop\">remove</button>") && s.contains("<span class=\"ph\">Waiting to start</span>"), "a queued run has a dashed bar and a remove: " + s);
        assertTrue(s.contains("<div class=\"rline done\"><span class=\"nm\"><a href=\"/jobs/J-0001\" title=\"J-0001\">Who funds instrument makers in Greater Boston?</a></span><span class=\"el\">10 min</span><span class=\"ph\">Done · <a href=\"/entry/I-0001-who-funds\">the report</a></span></div>"), "a run finished within the day stays, with its report: " + s);
        assertFalse(s.contains("J-0000"), "one finished days ago is not in the strip");
        assertTrue(s.indexOf("rline queued") < s.indexOf("rline done"), "going first, finished after");
    }
}
