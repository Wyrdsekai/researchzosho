package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A translation job's units on the record the checks are measured from, through the service: a job that fails or is stopped takes off
 * what it had put there (a resumed job's units from before its crash); a finished job's page shows its units with a button that takes them
 * off and says what left, for whoever may write, and refuses a reader who may only read, and an id that is no translation run.
 */
class TranslationRecordJobsTest {
    @TempDir Path tmp;
    String realHome;
    LibraryStore store;
    LibrarianDaemon d;

    @BeforeEach void up() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(tmp.resolve("home")).toString()); Config.invalidate();
        TranslationScores.SCORERS = () -> new TranslationScores.Scorers(TranslationTest.labseStandIn(), null); Translation.SETUP_SAID = () -> "";
        Translation.ANY_MODEL = () -> true;
        store = new LibraryStore(tmp.resolve("lib")); store.init();
    }

    @AfterEach void down() {
        if (d != null) d.stop();
        Chrome.clear();
        TranslationScores.SCORERS = TranslationScores::configured;
        Translation.SEATS = Translation::seatsFor; Translation.DRIVE_ANSWERS = Crews::driveAnswers; Translation.ANY_MODEL = Translation::anyModelNow;
        System.setProperty("user.home", realHome); Config.invalidate();
    }

    /** A translation filed as the command line files it for the service, of the made-up Japanese document of {@link DocNamesTest}. */
    String file() throws Exception {
        Path in = DocNamesTest.doc(tmp.resolve("docs"));
        return new Jobs(store, j -> "").submit(Translation.JOB_KIND, "person", Translation.args(in, in.getFileName().toString(), "en", "", "checked", tmp.resolve("out").resolve("doc.en.md"), null, "cli"));
    }

    /** The job once the service has run it, in whatever state it ended. */
    ObjectNodeState ended(String id) throws Exception {
        Jobs jobs = new Jobs(store, j -> "");
        long until = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < until) {
            var j = jobs.get(id);
            String state = j == null ? "" : j.path("state").asText();
            if (state.equals("done") || state.equals("failed") || state.equals("stopped")) return new ObjectNodeState(state, j.path("result").asText(""));
            Thread.sleep(50);
        }
        fail(id + " did not end in a minute: " + jobs.get(id));
        return null;
    }

    record ObjectNodeState(String state, String result) { }

    static List<TranslationScores.Row> rows(String job) { return List.of(new TranslationScores.Row(job, "", 3, 0.9, null, 3.0), new TranslationScores.Row(job, "", 4, 0.91, null, 3.1)); }

    @Test void aJobThatFailsTakesItsUnitsOffTheRecord() throws Exception {
        String id = file();
        TranslationScores.accept(store, "ja", "en", rows(id));   // what an earlier process of the same job put there before it was interrupted
        TranslationScores.accept(store, "ja", "en", rows("J-0999"));   // another job's, which stay
        Translation.DRIVE_ANSWERS = url -> false;   // the model does not answer: the job fails
        d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        ObjectNodeState e = ended(id);
        assertEquals("failed", e.state(), e.result());
        assertTrue(TranslationScores.onRecord(store, id).isEmpty(), "the failed job's units are off the record");
        assertEquals(Map.of("ja→en", 2), TranslationScores.onRecord(store, "J-0999"), "another job's stay");
    }

    @Test void aJobThatIsStoppedTakesItsUnitsOffTheRecord() throws Exception {
        String id = file();
        TranslationScores.accept(store, "ja", "en", rows(id));
        AtomicReference<String> running = new AtomicReference<>(id);
        TranslationTest.Script sc = DocNamesTest.script();
        var translate = sc.translate;
        sc.translate = t -> { try { new Jobs(store, j -> "").stopRun(running.get(), "person"); } catch (Exception ignored) { } return translate.apply(t); };   // the person stops it while its units are translated
        Translation.DRIVE_ANSWERS = url -> true;
        Translation.SEATS = (drive, model, judge, back) -> TranslationTest.seats(sc);
        d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        ObjectNodeState e = ended(id);
        assertEquals("stopped", e.state(), e.result());
        assertTrue(TranslationScores.onRecord(store, id).isEmpty(), "the stopped job's units are off the record");
    }

    @Test void theRunsPageShowsTheUnitsAndItsButtonTakesThemOff() throws Exception {
        Translation.DRIVE_ANSWERS = url -> true;
        Translation.SEATS = (drive, model, judge, back) -> TranslationTest.seats(DocNamesTest.script());
        String id = file();
        d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        assertEquals("done", ended(id).state());
        int on = TranslationScores.onRecord(store, id).getOrDefault("ja→en", 0);
        assertTrue(on >= 3, "the finished job's paragraphs are on the record: " + on);
        Patrons.set(store, "did:key:keeper", "the keeper at homebox", Patrons.Level.write);
        Patrons.set(store, "did:key:reader", "a reader at homebox", Patrons.Level.read);
        String writer = Pages.COOKIE + "=" + Patrons.issueToken(store, "did:key:keeper"), reader = Pages.COOKIE + "=" + Patrons.issueToken(store, "did:key:reader");
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        String base = d.url();
        // the run's page: how many units are on the record, and the button
        HttpResponse<String> page = RemovalTest.get(c, base + "/jobs/" + id, writer);
        assertTrue(page.body().contains(on + " units of this translation are on the record of Japanese into English the checks&#39; thresholds and the length band are measured from.")
                || page.body().contains(on + " units of this translation are on the record of Japanese into English the checks' thresholds and the length band are measured from."), page.body());
        assertTrue(page.body().contains("name=\"op\" value=\"withdraw-scores\"") && page.body().contains(">Take this translation off the record</button>"), page.body());
        // a reader who may only read is refused, and nothing leaves
        HttpResponse<String> refused = RemovalTest.post(c, base + "/jobs", "op=withdraw-scores&job_id=" + id, reader);
        assertNotEquals(200, refused.statusCode(), refused.body());
        assertEquals(on, TranslationScores.onRecord(store, id).getOrDefault("ja→en", 0));
        // an id that is no translation run
        HttpResponse<String> none = RemovalTest.post(c, base + "/jobs", "op=withdraw-scores&job_id=J-0404", writer);
        assertTrue(none.statusCode() == 404 && none.body().contains("No translation run has the id J-0404."), none.statusCode() + " " + none.body());
        // whoever may write: the units leave, and the page says how many and the thresholds now
        HttpResponse<String> taken = RemovalTest.post(c, base + "/jobs", "op=withdraw-scores&job_id=" + id, writer);
        assertEquals(200, taken.statusCode(), taken.body());
        assertTrue(taken.body().contains(on + " units of " + id + " left the record of Japanese into English.") && taken.body().contains("The record of Japanese into English now: no distribution yet for ja→en"), taken.body());
        assertTrue(TranslationScores.onRecord(store, id).isEmpty());
        assertFalse(RemovalTest.get(c, base + "/jobs/" + id, writer).body().contains("withdraw-scores"), "nothing left to take off: no button");
        // the same, in the person's own language
        assertTrue(Pages.withdrawPost(store, Patrons.Patron.PERSON, id).contains(id), "a second time: nothing of it is on the record");
    }
}
