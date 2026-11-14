package org.researchzosho.librarian;

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a translation's plan says is said once in each answer: on the command line the plan's sentence says it and the tiers' line the
 * times alone; in the chat the sentence above the question says the plan and the question the time asked, the direction and what a
 * hosted model costs; on the Translate form's page the tiers' line the times alone. A scorer's place (here a helpers address that does
 * not answer) and a hosted reader's cost are the clauses that were said twice.
 */
class TranslationSaidOnceTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String PLACED = "LaBSE at http://127.0.0.1:1/labse does not answer now", COST = "is a hosted model: about", WHERE = "http://127.0.0.1:1/labse";
    String home;

    @BeforeEach void standIn(@TempDir Path tmp) throws IOException {
        home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        TranslationScores.SCORERS = () -> new TranslationScores.Scorers(TranslationTest.labseStandIn(), null);
        Translation.SETUP_SAID = () -> "";
        Translation.ANY_MODEL = () -> true;
        Translation.DRIVE_ANSWERS = url -> true;
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b");
        Config.set("RESEARCHZOSHO_HELPERS", "http://127.0.0.1:1");   // named, and nothing answers there: the plan's clause for it
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

    static int count(String s, String part) { return s.split(Pattern.quote(part), -1).length - 1; }

    @Test void theCommandLineSaysThePlanOnceAndTheTiersLineTheTimes(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path f = TranslationTest.fixture(Files.createDirectories(tmp.resolve("docs")));
        Script sc = new Script();
        Translation.SEATS = (drive, model, judge, back) -> new Seats(TranslationTest.standIn(sc, "qwen3.8-27b"), "qwen3.8-27b", "qwen3.8-27b", null,
                TranslationTest.standIn(sc, "moonshotai/kimi-k3"), "moonshotai/kimi-k3", "reader-b", ModelEntries.get("reader-b"), false, false, "", new long[3], new long[3]);
        int[] rc = {-1};
        String out = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", f.toString(), "--to", "ja", "--reviewed", "--judge", "reader-b", "--out", tmp.resolve("out.md").toString(), "--direction", "as if you were Shakespeare"}), false);
        assertEquals(0, rc[0], out);
        String before = out.substring(0, out.indexOf("\n  translating"));
        assertEquals(1, count(before, PLACED), "the scorers' place, once: " + before);
        assertEquals(1, count(before, WHERE), "and no second sentence about it: " + before);
        assertEquals(1, count(before, COST), "the hosted reader's cost, once: " + before);
        assertEquals(1, count(before, "in the direction you gave"), before);
        String tiers = before.lines().filter(l -> l.startsWith("  (")).findFirst().orElse("");
        assertTrue(tiers.matches("  \\(.* checked, the default; quick .*, reviewed .*, thorough .*\\)") && count(tiers, ";") == 1 && !tiers.contains("hosted") && !tiers.contains("answer"), "the tiers' line holds the times alone: " + tiers);
    }

    @Test void theChatsQuestionCarriesTheTimeTheDirectionAndTheCostAndTheSentenceAboveTheRest(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path f = TranslationTest.fixture(Files.createDirectories(tmp.resolve("docs")));
        Librarian lib = new Librarian(store, TranslateTurnTest.noModel(), Librarian.person(), Librarian.Session.open(store));
        String reply = lib.say("translate " + f + " into German, reviewed by reader-b, as if you were Shakespeare");
        String question = reply.substring(reply.indexOf("That is "), reply.indexOf("Go ahead? (yes/no)"));
        String above = reply.substring(0, reply.indexOf("That is "));
        assertEquals(1, count(reply, PLACED), reply);
        assertEquals(1, count(reply, WHERE), "said once, by the plan: " + reply);
        assertEquals(1, count(reply, COST), reply);
        assertEquals(1, count(reply, "in the direction you gave: \"as if you were Shakespeare\""), reply);
        assertTrue(question.contains(" reviewed") && question.contains("in the direction you gave") && question.contains(COST) && !question.contains(PLACED) && !question.contains("thorough"),
                "the question: the time asked, the direction, the cost: " + question);
        assertTrue(above.contains(PLACED) && above.contains("thorough") && !above.contains(COST) && !above.contains("list prices"), "the sentence above: the four times and the rest, no cost: " + above);
        // the chat's model filing through the tool: its question is the same three things
        ObjectNode a = M.createObjectNode(); a.put("path", f.toString()); a.put("to", "de"); a.put("quality", "reviewed"); a.put("judge", "reader-b"); a.put("direction", "as if you were Shakespeare");
        a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        String toolQuestion = new LibraryProtocol(store).translate(a, "chat").path("question").asText();
        assertTrue(toolQuestion.contains(COST) && toolQuestion.contains("in the direction you gave") && !toolQuestion.contains(PLACED), toolQuestion);
    }

    @Test void theFormsPageSaysThePlanOnceAndTheTiersTheTimes(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path up = Files.createTempDirectory(Uploads.DIR);
        Path posted = TranslateTurnTest.notes(up, "posted notes.md");
        String notice = Pages.translatePost(store, Patrons.Patron.PERSON, Map.of("file.path", posted.toString(), "file.name", "posted notes.md", "to", "ja", "quality", "reviewed", "judge", "reader-b"));
        assertEquals(1, count(notice, PLACED), notice);
        assertEquals(1, count(notice, WHERE), notice);
        assertEquals(1, count(notice, COST), notice);
        assertTrue(notice.matches("(?s).*All four tiers: [^;]* checked, the default; quick [^;]*, reviewed [^;]*, thorough [^.;]*(?: \\(a first guess: nothing has been timed on this model yet\\))?\\..*"), "the times alone: " + notice);
    }
}
