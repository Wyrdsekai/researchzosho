package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Words of a chat ask are never dropped without a word. The phrase tables are the fast path; the words they and the rest of the ask leave
 * over go to the chat's model in one question, which copies out the part that says how the translation should read, or answers NONE. Its
 * answer counts only when it is the person's own words; NONE says nothing; a model that cannot be asked has the reply say which words were
 * not used; and an ask with nothing left over asks no model.
 */
class TranslationLeftoverTest {
    String home;
    final List<String[]> asked = new ArrayList<>();

    @BeforeEach void standIn(@TempDir Path tmp) throws IOException {
        home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        TranslationScores.SCORERS = () -> new TranslationScores.Scorers(TranslationTest.labseStandIn(), null);
        Translation.SETUP_SAID = () -> "";
        Translation.ANY_MODEL = () -> true;
        Translation.DRIVE_ANSWERS = url -> true;
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b");
    }

    @AfterEach void restore() {
        TranslateTurn.DIRECTION_READER = TranslateTurn::askTheChatModel;
        TranslationScores.SCORERS = TranslationScores::configured;
        Translation.SETUP_SAID = () -> { String a = TranslationScores.labseSetupSaid(), b = TranslationScores.metricxSetupSaid(); return a.isEmpty() ? b : b.isEmpty() ? a : a + "; " + b; };
        Translation.ANY_MODEL = Translation::anyModelNow;
        Translation.DRIVE_ANSWERS = Crews::driveAnswers;
        System.setProperty("user.home", home); Config.invalidate();
    }

    /** The chat's model stood in: it answers {@code answer} and keeps what it was asked. */
    void model(String answer) { TranslateTurn.DIRECTION_READER = (entry, system, message) -> { asked.add(new String[]{system, message}); return answer; }; }

    static int count(String s, String part) { return s.split(Pattern.quote(part), -1).length - 1; }

    @Test void whatAnAskLeavesOverIsWhatNothingTook() {
        assertEquals("for a ten-year-old", TranslateTurn.parse("translate this file into English for a ten-year-old").leftover());
        assertEquals("for a ten-year-old", TranslateTurn.parse("translate this file into English for a ten-year-old, reviewed, with the notes at the end").leftover(), "the tier and the notes are the ask's");
        assertEquals("as a British Victorian would", TranslateTurn.parse("translate this file into English as a British Victorian would").leftover());
        assertEquals("make it sound like a pirate", TranslateTurn.parse("translate this file into English, make it sound like a pirate").direction(), "the tables' fast path");
        assertEquals("", TranslateTurn.parse("translate this file into English, make it sound like a pirate").leftover());
        assertEquals("子供向けに", TranslateTurn.parse("このファイルを英語に子供向けに訳して").leftover(), "between the language and the verb");
        assertEquals("子供向けに", TranslateTurn.parse("このファイルを英語に訳して、子供向けに").leftover(), "after the verb");
        assertEquals("このファイル", TranslateTurn.parse("このファイルを英語に子供向けに訳して").what());
        assertEquals("für Kinder im Grundschulalter", TranslateTurn.parse("übersetze diese Datei ins Englische, für Kinder im Grundschulalter").leftover());
        assertEquals("For my daughter", TranslateTurn.parse("For my daughter, I would like this file translated into English").leftover(), "before the ask");
        for (String nothing : List.of("translate this file into Japanese", "please translate it into French, quick", "このファイルを日本語に翻訳して、確認付きで",
                "このファイルを日本語に翻訳して、確認付きで、訳注は末尾に", "translate this file into English and have kimi check it", "please give me a French translation of saros notes.md",
                "i would like this epub translated into english using DeepSeek V4.1 Flash and having kimi k3 do a thorough check", "übersetze diese Datei ins Englische, bitte",
                "translate the notes into German as a PDF, translated by kimi, reviewed by the strongest other model"))
            assertEquals("", TranslateTurn.parse(nothing).leftover(), "nothing of substance left in: " + nothing);
    }

    @Test void aLeftoverTheTablesMissBecomesTheDirectionWhenTheModelCopiesItOut(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path f = TranslationTest.fixture(Files.createDirectories(tmp.resolve("docs")));
        Librarian lib = new Librarian(store, TranslateTurnTest.noModel(), Librarian.person(), Librarian.Session.open(store));
        String[][] asks = {
                {"translate " + f + " into German for a ten-year-old, reviewed", "for a ten-year-old, reviewed", "for a ten-year-old"},   // copied with the tier, as a real model did: the tier stays the ask's
                {f + "をドイツ語に子供向けに訳して、簡易で", "子供向けに", "子供向けに"},
                {f + "をドイツ語に訳して、小学生にも分かるように。簡易で", "小学生にも分かるように。簡易で", "小学生にも分かるように"},   // copied across the stop with the tier, as a real model did
                {"übersetze " + f + " ins Japanische, für Kinder im Grundschulalter, schnell", "für Kinder im Grundschulalter", "für Kinder im Grundschulalter"},
                {"translate " + f + " into French as if you were Shakespeare. Make sure a ten-year-old can follow it.", "Make sure a ten-year-old can follow it", "as if you were Shakespeare; Make sure a ten-year-old can follow it"},
        };
        int n = 0;
        for (String[] a : asks) {
            model(a[1]);
            int before = asked.size();
            String reply = lib.say(a[0]);
            assertEquals(before + 1, asked.size(), "one question to the model: " + reply);
            assertEquals(a[0], asked.get(asked.size() - 1)[1], "the model is shown the person's whole message");
            assertEquals(TranslateTurn.DIRECTION_QUESTION, asked.get(asked.size() - 1)[0]);
            assertEquals(1, count(reply, "the direction you gave: \"" + a[2] + "\""), "shown in the question before the job starts: " + reply);
            ObjectNode job = new Jobs(store, j -> "").get("J-000" + (++n));
            assertEquals(Jobs.OFFERED, job.path("state").asText(), reply);
            assertEquals(a[2], job.path("args").path("direction").asText());
            lib.say(reply.contains("Go ahead?") ? "yes" : "quick");
        }
    }

    @Test void anAnswerThatIsNotThePersonsOwnWordsIsRefusedAndNoneSaysNothing(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path f = TranslationTest.fixture(Files.createDirectories(tmp.resolve("docs")));
        Librarian lib = new Librarian(store, TranslateTurnTest.noModel(), Librarian.person(), Librarian.Session.open(store));
        String ask = "translate " + f + " into German for a ten-year-old, quick";
        int n = 0;
        for (String answer : List.of("Write it simply, for children", "\"" + ask + "\"", "German", "NONE", "None.")) {
            model(answer);
            String reply = lib.say(ask);
            ObjectNode job = new Jobs(store, j -> "").get("J-000" + (++n));
            assertFalse(job.path("args").has("direction"), answer + " → " + job.path("args"));
            assertTrue(reply.contains("Translating it now, quick (J-000" + n + ")"), "filed as asked, with no question: " + reply);
            assertFalse(reply.contains("not used") || reply.contains("direction"), "and nothing said of it: " + reply);
        }
        assertEquals(5, asked.size());
        assertNull(TranslateTurn.readDirection("", ask, TranslateTurn.parse(ask)).span(), "a span that is none of the leftover words is none");
    }

    @Test void aModelThatCannotBeAskedHasTheReplySayWhichWordsWereNotUsed(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path f = TranslationTest.fixture(Files.createDirectories(tmp.resolve("docs")));
        Librarian lib = new Librarian(store, TranslateTurnTest.noModel(), Librarian.person(), Librarian.Session.open(store));
        TranslateTurn.DIRECTION_READER = (entry, system, message) -> { asked.add(new String[]{system, message}); throw new IllegalStateException("no model answers"); };
        String reply = lib.say("translate " + f + " into German for a ten-year-old, quick");
        assertTrue(reply.endsWith(" These words of yours were not used: \"for a ten-year-old\". To have them guide the translation, ask again with them after \"direction:\"."), reply);
        assertFalse(new Jobs(store, j -> "").get("J-0001").path("args").has("direction"));
        model("");   // an empty answer is no answer: said the same way, never taken as NONE
        String empty = lib.say(f + "をドイツ語に子供向けに訳して、簡易で");
        assertTrue(empty.contains("These words of yours were not used: \"子供向けに\"."), empty);
        // the sentence the person reads tells them how to say it again, and that way is read as a direction
        assertEquals("for a ten-year-old", TranslateTurn.parse("translate this file into German, direction: for a ten-year-old").direction());
    }

    @Test void anAskWithNothingLeftOverAsksNoModel(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path f = TranslationTest.fixture(Files.createDirectories(tmp.resolve("docs")));
        Librarian lib = new Librarian(store, TranslateTurnTest.noModel(), Librarian.person(), Librarian.Session.open(store));
        model("for a ten-year-old");
        for (String ask : List.of("translate " + f + " into German, quick", "translate " + f + " into German as if you were Shakespeare, quick", "please translate " + f + " into French, reviewed, with the notes at the end",
                f + "をドイツ語に訳して、確認付きで", "übersetze " + f + " ins Japanische, bitte, schnell"))
            lib.say(ask);
        assertEquals(0, asked.size(), "the common ask stays as fast as it was: " + asked.stream().map(a -> a[1]).toList());
        assertEquals(5, new Jobs(store, j -> "").active().size());
    }
}
