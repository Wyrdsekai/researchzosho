package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The directed reading question ({@link FamilyLinks}, L7): a pair the relations nominate whose listed readings do not match the name in Latin
 * letters is put to the model once more, as one yes-or-no question with the romaji and its kana. A yes joins the pair as L7 does and the
 * reading is kept; a no leaves the pair and is kept too, so nothing is asked twice. Invented families.
 */
class FamilyLinksDirectedReadingTest {

    private static final String BOOK = "file:///family/morita-book.txt";
    private static final String LETTERS = "file:///family/letters.txt";
    private static final String TREE = "file:///family/tree-notes.txt";

    /** A model that answers each prompt from the first rule whose words it contains, and keeps the prompts: those it was given room to think on apart. */
    private static final class Scripted implements FamilyLinks.Model {
        final Map<String, String> answers;
        final List<String> prompts = new ArrayList<>(), thought = new ArrayList<>();
        Scripted(Map<String, String> answers) { this.answers = answers; }
        @Override public String ask(String prompt) {
            prompts.add(prompt);
            for (Map.Entry<String, String> e : answers.entrySet()) if (prompt.contains(e.getKey())) return e.getValue();
            return "{}";
        }
        @Override public String think(String prompt) { thought.add(prompt); return ask(prompt); }
    }

    private static LibraryStore family(Path tmp) throws Exception {
        LibraryStore s = new LibraryStore(tmp.resolve("lib"));
        s.init();
        FamilyPeople.holdsAFamily(s);
        return s;
    }

    private static void claim(LibraryStore store, String s, String p, String o, String source) throws Exception {
        String sentence = s + " " + p.replace('-', ' ') + " " + o + ".";
        store.write(new Finding(store.nextFindingId(sentence), sentence, List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.low, "family-account", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source(source, "as told by an aunt", "")), List.of(), null, sentence + "\n\nThe account says: \"" + sentence + "\"\n",
                new Finding.Triple(s, p, o), List.of()));
    }

    /** Two sons of one father, one written in characters and one in Latin letters; the father's name claim says 森田 is a family name. */
    private static LibraryStore brothers(Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        FamilyNameHistoryTest.file(store, TREE, List.of(), List.of(FamilyNameHistoryTest.name("森田一郎", "森田一郎", "森田", "一郎", "birth", "1870", "森田一郎、1870年生。")));
        claim(store, "森田一郎", "born-on", "1870", BOOK);
        claim(store, "森田一郎", "born-on", "1870", LETTERS);
        claim(store, "森田健吉", "child-of", "森田一郎", BOOK);
        claim(store, "Kenkichi Morita", "child-of", "森田一郎", LETTERS);
        return store;
    }

    private static String entry(LibraryStore store, String name) throws IOException { return FamilyPeople.view(store).nodeIdOf(name); }

    private static String directed(Scripted model) { return model.prompts.stream().filter(p -> p.startsWith("Can the characters")).findFirst().orElse(""); }

    @Test
    void aYesToTheDirectedQuestionJoinsThePairTheListedReadingsMissed(@TempDir Path tmp) throws Exception {
        LibraryStore store = brothers(tmp);
        Scripted model = new Scripted(Map.of("family name 森田", "{\"readings\": [\"もりた\"]}", "given name 健吉", "{\"readings\": [\"たけよし\", \"けんよし\"]}",
                "be read Kenkichi (けんきち)", "{\"answer\": \"yes\", \"reading\": \"けんきち\"}"));
        FamilyLinks.update(store, model, x -> { });
        assertEquals("Can the characters 健吉, a Japanese given name, be read Kenkichi (けんきち)? Answer yes or no, as one JSON object: {\"answer\": \"yes\", \"reading\": \"けんきち\"} or {\"answer\": \"no\"}. "
                + "When the name is read so but the kana differ, give the kana as they are.", directed(model));
        assertEquals(3, model.prompts.size(), "the family name, the given name, then the one directed question: " + model.prompts);
        assertEquals(List.of(directed(model)), model.thought, "the directed question alone is put with room to think; the readings are asked at once");
        assertEquals(entry(store, "森田健吉"), entry(store, "Kenkichi Morita"), "the listed readings たけよし and けんよし miss Kenkichi; the model says 健吉 can be read けんきち");
        FamilyLinks.Link l = FamilyLinks.current(store).links().stream().filter(x -> x.rule().equals("L7")).findFirst().orElseThrow();
        assertEquals(FamilyLinks.Grade.probable, l.grade(), "probable, never proved on the model's word");
        assertTrue(l.why().contains("the model, asked whether 健吉 can be read けんきち, says yes"), l.why());
        Map<String, List<String>> kept = FamilyLinks.cachedReadings(store);
        assertEquals(List.of("yes", "said: {\"answer\": \"yes\", \"reading\": \"けんきち\"}"), kept.get("健吉 (given name) as けんきち"), "the answer is kept with the readings, with the model's words: " + kept);
        assertEquals(List.of("たけよし", "けんよし", "けんきち"), kept.get("健吉 (given name)"), "and the reading is one of the characters' now");
        FamilyLinks.update(store, model, x -> { });
        assertEquals(3, model.prompts.size(), "nothing is asked twice");
    }

    @Test
    void aNoLeavesThePairAndIsNotAskedAgain(@TempDir Path tmp) throws Exception {
        LibraryStore store = brothers(tmp);
        Scripted model = new Scripted(Map.of("family name 森田", "{\"readings\": [\"もりた\"]}", "given name 健吉", "{\"readings\": [\"たけよし\"]}", "be read Kenkichi (けんきち)", "No."));
        FamilyLinks.update(store, model, x -> { });
        assertNotEquals(entry(store, "森田健吉"), entry(store, "Kenkichi Morita"), "a no leaves them two people");
        assertEquals(List.of("no", "said: No."), FamilyLinks.cachedReadings(store).get("健吉 (given name) as けんきち"));
        assertEquals(List.of("たけよし"), FamilyLinks.cachedReadings(store).get("健吉 (given name)"), "no reading is added on a no");
        int asked = model.prompts.size();
        FamilyLinks.update(store, model, x -> { });
        assertEquals(asked, model.prompts.size(), "the no is kept, so the question is not put again");
    }

    @Test
    void aYesWithKanaThatRomaniseOtherwiseIsNoYesAndAYesWithTheSameSoundInOtherKanaIsTakenWithThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = brothers(tmp);
        Scripted model = new Scripted(Map.of("family name 森田", "{\"readings\": [\"もりた\"]}", "given name 健吉", "{\"readings\": [\"たけよし\"]}",
                "be read Kenkichi (けんきち)", "{\"answer\": \"yes\", \"reading\": \"たけよし\"}"));
        FamilyLinks.update(store, model, x -> { });
        assertNotEquals(entry(store, "森田健吉"), entry(store, "Kenkichi Morita"), "a yes whose kana read Takeyoshi is no yes to Kenkichi");
        assertEquals("no", FamilyLinks.cachedReadings(store).get("健吉 (given name) as けんきち").get(0));

        Map<String, List<String>> readings = new LinkedHashMap<>(Map.of("健一 (given name)", List.of("たけかず")));
        FamilyLinks.directedAnswer(readings, "健一 (given name) as けにち", "{\"answer\": \"yes\", \"reading\": \"けんいち\"}");
        assertEquals("yes", readings.get("健一 (given name) as けにち").get(0));
        assertEquals(List.of("たけかず", "けんいち"), readings.get("健一 (given name)"), "the model's own kana, which romanise as the question's do, are the reading kept");
        FamilyLinks.directedAnswer(readings, "健一 (given name) as けんかず", "Yes, 健一 is read Kenkazu.");
        assertEquals(List.of("たけかず", "けんいち", "けんかず"), readings.get("健一 (given name)"), "a plain yes takes the kana the question gave");
    }

    @Test
    void romajiAsHiragana() {
        assertEquals("けんきち", FamilyForms.hiragana("Kenkichi"));
        assertEquals("えんどう", FamilyForms.hiragana("Endoh"));
        assertEquals("しょういち", FamilyForms.hiragana("Shōichi"));
        assertEquals("けんいち", FamilyForms.hiragana("Ken'ichi"));
        assertEquals("はっとり", FamilyForms.hiragana("Hattori"));
        assertEquals("なんば", FamilyForms.hiragana("Namba"));
        assertEquals("じゅんこ", FamilyForms.hiragana("Junko"));
        assertEquals("まっちゃ", FamilyForms.hiragana("matcha"));
        assertEquals("ふじこ", FamilyForms.hiragana("Fujiko"));
        assertNull(FamilyForms.hiragana("Smith"), "no romaji syllable: nothing to ask");
        assertNull(FamilyForms.hiragana(""));
        assertEquals("kenichi", FamilyForms.latinKey(FamilyForms.hepburn(FamilyForms.hiragana("Kenichi"))), "read けにち, but compared as Kenichi all the same");
    }
}
