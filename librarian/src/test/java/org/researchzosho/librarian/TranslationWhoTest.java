package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.librarian.Translation.Seats;
import org.researchzosho.librarian.TranslationTest.Script;
import org.researchzosho.mcp.McpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A translation job says who does what in it, the same on every surface: who translates (the entry, its model, the machine it runs
 * on), who reads at the reviewed tier (a hosted reader as a paid call with its estimate, and once the job ended what it cost), where
 * the scores are made, the tier, the languages, the format and where the doubts go. The facts are kept in the job's record when it is
 * filed, so a restart, or settings changed after it, say nothing else; a job filed before they were kept is said as far as its args go.
 */
class TranslationWhoTest {
    static final ObjectMapper M = new ObjectMapper();

    String home;

    @BeforeEach void standIn(@TempDir Path tmp) throws IOException {
        home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        TranslationScores.SCORERS = () -> new TranslationScores.Scorers(TranslationTest.labseStandIn(), null);
        Translation.SETUP_SAID = () -> "";
        Translation.ANY_MODEL = () -> true;
        Translation.DRIVE_ANSWERS = url -> true;
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b");
        ModelEntries.put("writer-a", "http://homebox:8213", "gemma-4-26b-a4b", null, null, null, null);
        ModelEntries.put("reader-b", "openrouter", "moonshotai/kimi-k3", "0.192/16/0.182", null, null, null);
        Config.set(DriveClient.hostKeySetting("openrouter.ai"), "sk-or-test");
    }

    @AfterEach void restore() {
        TranslationScores.SCORERS = TranslationScores::configured;
        Translation.SETUP_SAID = () -> { String a = TranslationScores.labseSetupSaid(), b = TranslationScores.metricxSetupSaid(); return a.isEmpty() ? b : b.isEmpty() ? a : a + "; " + b; };
        Translation.ANY_MODEL = Translation::anyModelNow;
        Translation.DRIVE_ANSWERS = Crews::driveAnswers;
        Translation.SEATS = Translation::seatsFor;
        System.setProperty("user.home", home); Config.invalidate();
    }

    static String cli(DocNamesTest.Run r) throws Exception { return DocNamesTest.cli(r); }

    /** What the job's view says of it, as library_job gives it. */
    static JsonNode view(LibraryStore store, String id) throws IOException {
        ObjectNode a = M.createObjectNode(); a.put("job_id", id); a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        return new LibraryProtocol(store).job(a).path("job");
    }

    @Test void aJobWithANamedTranslatorAndAHostedReaderIsSaidTheSameOnEverySurfaceBeforeDuringAndAfter(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(Path.of(System.getProperty("user.home")).resolve("researchzosho-library")); store.init();   // where the MCP server's LibraryStore.open() looks
        Path f = TranslationTest.fixture(Files.createDirectories(tmp.resolve("docs")));
        // filed through the API, as the web's form and the chat file it: the translator and the reader named
        ObjectNode ask = M.createObjectNode(); ask.put("path", f.toString()); ask.put("to", "Japanese"); ask.put("quality", "reviewed"); ask.put("model", "writer-a"); ask.put("judge", "reader-b");
        ask.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        ObjectNode filed = new LibraryProtocol(store).translate(ask);
        String id = filed.path("job_id").asText();
        String seats = "Translated by writer-a (gemma-4-26b-a4b on homebox); read by reader-b (moonshotai/kimi-k3, hosted at openrouter.ai), a paid call: about $";
        String rest = " at its list prices. Scores: LaBSE on this machine; MetricX-24 left out: it is not installed. Reviewed, from English into Japanese, as md. "
                + "The passages the checks still doubt are listed in the note beside the translation only, not in the document.";
        Pattern before = Pattern.compile(Pattern.quote(seats) + "\\d+\\.\\d\\d" + Pattern.quote(rest));
        String planned = filed.path("translation").path("said").asText();
        assertTrue(before.matcher(planned).matches(), "the API's answer says who does what as soon as the job is filed: " + planned);
        JsonNode facts = filed.path("translation");
        assertEquals("writer-a", facts.path("translator").path("entry").asText()); assertEquals("homebox", facts.path("translator").path("machine").asText());
        assertTrue(facts.path("reader").path("hosted").asBoolean() && facts.path("reader").path("priced").asBoolean() && facts.path("reader").path("estimate_dollars").asDouble() > 0, facts.toString());
        assertEquals("reviewed", facts.path("quality").asText()); assertEquals(2, facts.path("scorers").size());
        String estimate = planned.substring(seats.length() - 1, planned.indexOf(" at its list prices"));

        // before it runs: the API and MCP, the command line, the job's page and the Runs page say the same
        assertEquals(planned, view(store, id).path("translation").path("said").asText());
        ObjectNode call = M.createObjectNode(); call.put("name", "library_job"); call.putObject("arguments").put("job_id", id);
        assertTrue(McpServer.handle("tools/call", call).toString().contains(planned.replace("\"", "\\\"")), "the MCP tool gives the same job");
        String detail = cli(() -> LibrarianCli.jobs(store, new String[]{"researchzosho", "jobs", id}));
        assertTrue(detail.contains("  " + planned + "\n"), detail);
        String list = cli(() -> LibrarianCli.jobs(store, new String[]{"researchzosho", "jobs"}));
        assertTrue(list.contains(id + " [queued] translate-doc") && list.contains(" · by writer-a, read by reader-b"), "the list's line stays short: " + list);
        String page = Pages.job(store, new LibraryProtocol(store), Patrons.Patron.PERSON, id, Map.of());
        assertTrue(page.contains("<p>" + Pages.esc(planned) + "</p>"), page);
        assertTrue(Pages.jobs(store, new LibraryProtocol(store), Patrons.Patron.PERSON).contains("Translating saros notes.md into Japanese · by writer-a, read by reader-b"), "the Runs page's row");

        // settings that change after the job was filed, as a restart reads them, do not change what it says
        Config.set("RESEARCHZOSHO_MODEL", "another-model"); ModelEntries.put("writer-a", "http://elsewhere:9000", "other-model", null, null, null, null);
        assertEquals(planned, view(store, id).path("translation").path("said").asText(), "the record's facts, not today's settings");
        ModelEntries.put("writer-a", "http://homebox:8213", "gemma-4-26b-a4b", null, null, null, null);

        // during: the run records the seats as it made them, and every surface still says the same
        Script sc = new Script();
        Translation.SEATS = (drive, model, judge, back) -> new Seats(TranslationTest.standIn(sc, "gemma-4-26b-a4b"), "gemma-4-26b-a4b", "writer-a", ModelEntries.get("writer-a"),
                TranslationTest.standIn(sc, "moonshotai/kimi-k3"), "moonshotai/kimi-k3", "reader-b", ModelEntries.get("reader-b"), false, false, "", new long[3], new long[]{20_000, 3_000, 0});
        CountDownLatch started = new CountDownLatch(1), go = new CountDownLatch(1);
        Jobs[] held = new Jobs[1];
        Jobs jobs = new Jobs(store, (j, d) -> Translation.runJob(store, j, "http://homebox:8213", "gemma-4-26b-a4b", p -> { }, () -> false, Checkpoint.NONE, x -> {
            try { held[0].facts(j.path("job_id").asText(), x); } catch (IOException e) { throw new UncheckedIOException(e); }
            if (x.has("ran")) { started.countDown(); try { go.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        }), List.of(""), 1);
        held[0] = jobs;
        jobs.start();
        try {
            assertTrue(started.await(30, TimeUnit.SECONDS), "the run started");
            JsonNode running = view(store, id);
            assertEquals("running", running.path("state").asText());
            assertEquals(planned, running.path("translation").path("said").asText(), "the seats the run made are the ones filed: said the same while it runs");
            assertTrue(cli(() -> LibrarianCli.jobs(store, new String[]{"researchzosho", "jobs", id})).contains("  " + planned + "\n"));
            assertTrue(Pages.job(store, new LibraryProtocol(store), Patrons.Patron.PERSON, id, Map.of()).contains(Pages.esc(planned)));
            assertEquals(planned, view(store, id).path("translation").path("said").asText(), "a fresh read of the record, as after a restart");
            go.countDown();
            long until = System.currentTimeMillis() + 60_000;
            while (!view(store, id).path("state").asText().equals("done") && System.currentTimeMillis() < until) Thread.sleep(100);
        } finally { go.countDown(); jobs.stop(); }

        // after: the hosted reader's cost is said beside its estimate, on every surface, and in the result and the chat's notice
        JsonNode done = view(store, id);
        assertEquals("done", done.path("state").asText(), done.toString());
        String after = done.path("translation").path("said").asText();
        assertEquals(planned.replace(" at its list prices.", " at its list prices; it cost $0.05."), after, "20,000 tokens sent and 3,000 written at the entry's prices");
        assertTrue(done.path("result").asText().contains(after), "the result line says it: " + done.path("result").asText());
        assertTrue(cli(() -> LibrarianCli.jobs(store, new String[]{"researchzosho", "jobs", id})).contains("  " + after + "\n"));
        assertTrue(Pages.job(store, new LibraryProtocol(store), Patrons.Patron.PERSON, id, Map.of()).contains(Pages.esc(after)));
        Librarian.Session session = Librarian.Session.open(store);
        session.watch(id);
        String told = session.told(id, RunProgress.of(done));
        assertTrue(told.startsWith("The translation " + id + " is done after ") && told.contains("Translated by writer-a (gemma-4-26b-a4b on homebox); read by reader-b (moonshotai/kimi-k3, hosted at openrouter.ai), a paid call: about " + estimate + " at its list prices; it cost $0.05.")
                && told.contains("The run's page has the file, its note and where both are on the shelves."), "the chat's notice names a translation as one, with who did it: " + told);
        assertFalse(told.contains("Research run"), told);
        assertEquals("library", session.messages.get(session.messages.size() - 1).path("by").asText(), "the library's own line, labelled \"From the library\", not the model's words");
        // a research run's notice is the library's line too
        session.watch("J-0099");
        session.told("J-0099", new RunProgress.View("J-0099", "done", "Done", 60, 100, "How were the gears cut?", "", ""));
        assertEquals("library", session.messages.get(session.messages.size() - 1).path("by").asText());
    }

    @Test void aJobFiledBeforeTheFactsWereKeptIsSaidAsFarAsItsArgsAllow(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path f = TranslationTest.fixture(Files.createDirectories(tmp.resolve("docs")));
        // the args a job of an earlier release held: the entries named for the translator and the reader, no facts beside them
        ObjectNode old = Translation.args(f, "saros notes.md", "ja", "md", "reviewed", null, null, "web", "reader-b", "writer-a", "", "off");
        assertFalse(old.has("who"));
        String id = new Jobs(store, j -> "").submit(Translation.JOB_KIND, "person", old);
        String said = view(store, id).path("translation").path("said").asText();
        assertEquals("Translated by writer-a; read by reader-b. Reviewed, into Japanese, as md. The passages the checks still doubt are listed in the note beside the translation only, not in the document.", said);
        assertTrue(cli(() -> LibrarianCli.jobs(store, new String[]{"researchzosho", "jobs"})).contains(" · by writer-a, read by reader-b"));
        // with nothing named: the library's model, and no reader at the checked tier
        String plain = new Jobs(store, j -> "").submit(Translation.JOB_KIND, "person", Translation.args(f, "saros notes.md", "de", "md", "checked", null, null, "cli"));
        assertEquals("Translated by the library's model. Checked, into German, as md. The passages the checks still doubt are listed in the note beside the translation only, not in the document.",
                view(store, plain).path("translation").path("said").asText());
    }

    @Test void theChatSaysWhoTranslatesAndAQuickRunNamesNoReader(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path f = TranslationTest.fixture(Files.createDirectories(tmp.resolve("docs")));
        Librarian.Session session = Librarian.Session.open(store);
        Librarian lib = new Librarian(store, TranslateTurnTest.noModel(), Librarian.person(), session);
        String quick = lib.say("translate " + f + " into German, quick, translated by writer-a, checked by reader-b");
        assertTrue(quick.contains("Translated by writer-a (gemma-4-26b-a4b on homebox).") && !quick.contains("reader-b ("), "the quick tier reads nothing, so no reader is named: " + quick);
        String reviewed = lib.say("translate " + f + " into German, reviewed, translated by writer-a, checked by reader-b");
        assertTrue(reviewed.contains("Translated by writer-a (gemma-4-26b-a4b on homebox); read by reader-b (moonshotai/kimi-k3, hosted at openrouter.ai).") && reviewed.contains("the reader reader-b is a hosted model: about $")
                && !reviewed.contains("a paid call") && reviewed.contains("Scores: LaBSE on this machine; MetricX-24 left out: it is not installed."), "who reads, with its cost said once by the plan: " + reviewed);
    }
}
