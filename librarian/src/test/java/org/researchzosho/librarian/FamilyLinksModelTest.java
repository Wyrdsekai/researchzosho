package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the link pass leaves to the model ({@link FamilyLinks}), with invented families and a model that answers from a script: the readings
 * of a name in characters the relations pair with a name in Latin letters (L7), and which of several people a mention is, with the words of
 * the passage that show it (L5). Then the deterministic rules of the same step: a name before and after a marriage (L11), the writer of a
 * file, and a given name alone among a person's names.
 */
class FamilyLinksModelTest {

    private static final String BOOK = "file:///family/morita-book.txt";
    private static final String LETTERS = "file:///family/letters.txt";
    private static final String TREE = "file:///family/tree-notes.txt";

    private static LibraryStore family(Path tmp) throws Exception {
        LibraryStore s = new LibraryStore(tmp.resolve("lib"));
        s.init();
        FamilyPeople.holdsAFamily(s);
        return s;
    }

    private static Finding claim(LibraryStore store, String s, String p, String o, String source) throws Exception { return claim(store, s, p, o, source, s + " " + p.replace('-', ' ') + " " + o + "."); }

    private static Finding claim(LibraryStore store, String s, String p, String o, String source, String quote) throws Exception {
        String sentence = s + " " + p.replace('-', ' ') + " " + o + ".";
        Finding f = new Finding(store.nextFindingId(sentence), sentence, List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.low, "family-account", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source(source, "as told by an aunt", "")), List.of(), null, sentence + "\n\nThe account says: \"" + quote + "\"\n",
                new Finding.Triple(s, p, o), List.of());
        store.write(f);
        return f;
    }

    /** A model that gives each prompt the answer of the first rule whose words it contains, and counts the calls. */
    private static final class Scripted implements FamilyLinks.Model {
        final Map<String, String> answers;
        final AtomicInteger calls = new AtomicInteger();
        final List<String> prompts = new ArrayList<>();

        Scripted(Map<String, String> answers) { this.answers = answers; }

        @Override public String ask(String prompt) {
            calls.incrementAndGet();
            prompts.add(prompt);
            for (Map.Entry<String, String> e : answers.entrySet()) if (prompt.contains(e.getKey())) return numbered(prompt, e.getValue());
            return "{}";
        }

        /** "#Morita Kenji" in an answer is the number the prompt gives Morita Kenji among the people it could be. */
        static String numbered(String prompt, String answer) {
            Matcher m = Pattern.compile("#([^\"#,}]+?)(?=[,}])").matcher(answer);
            StringBuilder b = new StringBuilder();
            while (m.find()) {
                Matcher n = Pattern.compile("(?m)^(\\d+)\\. " + Pattern.quote(m.group(1).strip()) + "[:.]").matcher(prompt);
                m.appendReplacement(b, n.find() ? n.group(1) : "0");
            }
            m.appendTail(b);
            return b.toString();
        }
    }

    private static String entry(LibraryStore store, String name) throws IOException { return FamilyPeople.view(store).nodeIdOf(name); }

    private static String side(LibraryStore store, Finding f, boolean subject) throws IOException { return FamilyPeople.view(store).nodeOf(f, subject); }

    /**
     * A father both texts give, born the same year in each: one man by the evidence, whom two people's names can share as a relative. His
     * name claim says 森田 is a family name.
     */
    private static void father(LibraryStore store) throws Exception {
        FamilyNameHistoryTest.file(store, TREE, List.of(), List.of(FamilyNameHistoryTest.name("森田一郎", "森田一郎", "森田", "一郎", "birth", "1870", "森田一郎、1870年生。")));
        claim(store, "森田一郎", "born-on", "1870", BOOK);
        claim(store, "森田一郎", "born-on", "1870", LETTERS);
    }

    // ── L7: the readings of a name in characters, from the model ───────────────────────────────────────────────────────

    @Test
    void theModelsReadingOfTheCharactersJoinsANamePairedByItsRelationsAndAnotherReadingDoesNot(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        father(store);
        claim(store, "森田健二", "child-of", "森田一郎", BOOK);
        claim(store, "Kenji Morita", "child-of", "森田一郎", LETTERS);
        Scripted model = new Scripted(Map.of("family name 森田", "{\"readings\": [\"もりた\"]}", "given name 健二", "{\"readings\": [\"けんじ\", \"たけじ\"]}"));
        FamilyLinks.update(store, model, x -> { });
        assertEquals(entry(store, "森田健二"), entry(store, "Kenji Morita"), "the model reads 森田 もりた and 健二 けんじ: Morita Kenji, and both are children of 森田一郎");
        assertEquals(2, model.calls.get(), "one question for the family name, one for the given name: " + model.prompts);
        assertTrue(FamilyLinks.current(store).links().stream().anyMatch(l -> l.rule().equals("L7") && l.grade() == FamilyLinks.Grade.probable), "probable, never proved on the model's word");
        int calls = model.calls.get();
        FamilyLinks.update(store, model, x -> { });
        assertEquals(calls, model.calls.get(), "the readings are kept by the characters and never asked again");
        claim(store, "森田健二", "occupation", "shopkeeper", BOOK);
        assertEquals(entry(store, "森田健二"), entry(store, "Kenji Morita"), "a view worked out again reads the kept readings, and asks nobody");

        LibraryStore other = family(tmp.resolve("other"));
        father(other);
        claim(other, "森田健二", "child-of", "森田一郎", BOOK);
        claim(other, "Kenzo Morita", "child-of", "森田一郎", LETTERS);
        Graph.alias(other, "森田一郎", List.of("もりた いちろう"));
        Scripted own = new Scripted(Map.of("given name 健二", "{\"readings\": [\"けんじ\"]}"));
        FamilyLinks.update(other, own, x -> { });
        assertEquals(2, own.calls.get(), "the family name is read as the library's own reading of 森田一郎 reads it: the given name is asked, then, as けんじ is not Kenzo, whether 健二 can be read so: " + own.prompts);
        assertTrue(own.prompts.get(1).startsWith("Can the characters 健二, a Japanese given name, be read Kenzo (けんぞ)?"), own.prompts.get(1));
        assertNotEquals(entry(other, "森田健二"), entry(other, "Kenzo Morita"), "a brother of another name: the reading does not match, and the model does not say 健二 reads Kenzo");
    }

    @Test
    void nothingPairsANameInCharactersWithoutASharedRelativeAndNoServerLeavesTheQuestionsWaiting(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "森田健二", "born-on", "1905", BOOK);
        claim(store, "Kenji Morita", "born-on", "1905", LETTERS);
        Scripted model = new Scripted(Map.of("given name 健二", "{\"readings\": [\"けんじ\"]}"));
        FamilyLinks.update(store, model, x -> { });
        assertEquals(0, model.calls.get(), "no relative pairs them: the model is not asked");
        assertNotEquals(entry(store, "森田健二"), entry(store, "Kenji Morita"));

        LibraryStore none = family(tmp.resolve("none"));
        father(none);
        claim(none, "森田健二", "child-of", "森田一郎", BOOK);
        claim(none, "Kenji Morita", "child-of", "森田一郎", LETTERS);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        FamilyLinks.cli(none, new PrintStream(buf, true, StandardCharsets.UTF_8), false, false, prompt -> { throw new IOException("no server"); });
        String said = buf.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("No model server answered, so 2 questions wait for one"), said);
        assertNotEquals(entry(none, "森田健二"), entry(none, "Kenji Morita"), "everything else is done, and the choice waits");
    }

    // ── L5: the model's choice among the people a mention could be ────────────────────────────────────────────────────

    private static LibraryStore twoMoritas(Path tmp, String[] shop) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "Morita Kenji", "born-on", "1905", BOOK);
        claim(store, "Morita Ichiro", "born-on", "1931", BOOK);
        claim(store, "Morita Kenji", "sex", "male", BOOK);
        claim(store, "Morita Ichiro", "sex", "male", BOOK);
        Finding f = claim(store, "Mr. Morita", "occupation", "shopkeeper", BOOK, "In 1930 Mr. Morita, then twenty-five, opened the silk shop on the corner.");
        shop[0] = f.id();
        return store;
    }

    @Test
    void theModelsChoiceLinksAMentionWhenItsQuoteIsInThePassageAndNothingElse(@TempDir Path tmp) throws Exception {
        String[] shop = new String[1];
        LibraryStore store = twoMoritas(tmp.resolve("chose"), shop);
        Scripted model = new Scripted(Map.of("Mr. Morita", "{\"choice\": #Morita Kenji, \"quote\": \"In 1930 Mr. Morita, then  twenty-five\"}"));
        FamilyLinks.update(store, model, x -> { });
        Finding f = store.finding(shop[0]);
        assertEquals(entry(store, "Morita Kenji"), side(store, f, true), "twenty-five in 1930: the man born in 1905, and the quote is in the passage");
        assertTrue(model.prompts.get(0).contains("Morita Kenji: a man; born 1905") && model.prompts.get(0).contains("Morita Ichiro: a man; born 1931"), "each candidate with what is known of them: " + model.prompts.get(0));
        FamilyLinks.Choice c = FamilyLinks.current(store).choices().values().iterator().next();
        assertEquals("chose", c.outcome());
        FamilyLinks.update(store, model, x -> { });
        assertEquals(1, model.calls.get(), "a choice is never asked twice");

        LibraryStore made = twoMoritas(tmp.resolve("made"), shop);
        FamilyLinks.update(made, new Scripted(Map.of("Mr. Morita", "{\"choice\": 1, \"quote\": \"Mr. Morita was born in 1905\"}")), x -> { });
        assertEquals(Vocabulary.norm("Mr. Morita"), side(made, made.finding(shop[0]), true), "words the passage does not have: the choice is dropped");
        assertEquals("quote not found", FamilyLinks.current(made).choices().values().iterator().next().outcome());

        LibraryStore open = twoMoritas(tmp.resolve("open"), shop);
        FamilyLinks.update(open, new Scripted(Map.of("Mr. Morita", "{\"choice\": 0, \"quote\": \"\"}")), x -> { });
        assertEquals(Vocabulary.norm("Mr. Morita"), side(open, open.finding(shop[0]), true), "cannot tell: it stays as written");
        assertEquals("cannot tell", FamilyLinks.current(open).choices().values().iterator().next().outcome());
        assertTrue(FamilyNameQuestions.open(open).stream().noneMatch(q -> q.text().contains("silk shop")), "and nobody is asked for it by the link pass");
    }

    @Test
    void aChoiceIsAskedAgainOnlyWhenThePeopleItCouldBeChange(@TempDir Path tmp) throws Exception {
        String[] shop = new String[1];
        LibraryStore store = twoMoritas(tmp, shop);
        Scripted model = new Scripted(Map.of("Mr. Morita", "{\"choice\": 0, \"quote\": \"\"}"));
        FamilyLinks.update(store, model, x -> { });
        assertEquals(1, model.calls.get());
        claim(store, "Morita Goro", "born-on", "1935", BOOK);
        claim(store, "Morita Goro", "sex", "male", BOOK);
        FamilyLinks.update(store, model, x -> { });
        assertEquals(2, model.calls.get(), "a third Morita in the book: the question is another one");
    }

    // ── L11: a name before and after a marriage ────────────────────────────────────────────────────────────────────────

    @Test
    void aGivenNameUnderTwoFamilyNamesIsOnePersonWhenTwoFactsAgreeAndNotOnOne(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        FamilyNameHistoryTest.file(store, TREE, List.of(), List.of(
                FamilyNameHistoryTest.name("Haru Endo", "Haru Endo", "Endo", "Haru", "birth", "1910", "Haru Endo was born in 1910."),
                FamilyNameHistoryTest.name("Kenji Morita", "Kenji Morita", "Morita", "Kenji", "birth", "1905", "Kenji Morita was born in 1905.")));
        claim(store, "Haru Endo", "born-on", "1910", TREE);
        claim(store, "Haru Endo", "married-to", "Kenji Morita", TREE);
        claim(store, "Haru Morita", "born-on", "1910", LETTERS);
        assertEquals(entry(store, "Haru Endo"), entry(store, "Haru Morita"), "born the same year, and Haru Endo's husband carries the other entry's family name");
        assertTrue(FamilyLinks.current(store).links().stream().anyMatch(l -> l.rule().equals("L11") && l.grade() == FamilyLinks.Grade.probable));

        LibraryStore one = family(tmp.resolve("one"));
        FamilyNameHistoryTest.file(one, TREE, List.of(), List.of(
                FamilyNameHistoryTest.name("Haru Endo", "Haru Endo", "Endo", "Haru", "birth", "1910", "Haru Endo was born in 1910."),
                FamilyNameHistoryTest.name("Kenji Morita", "Kenji Morita", "Morita", "Kenji", "birth", "1905", "Kenji Morita was born in 1905.")));
        claim(one, "Haru Endo", "born-on", "1910", TREE);
        claim(one, "Haru Morita", "born-on", "1910", LETTERS);
        assertNotEquals(entry(one, "Haru Endo"), entry(one, "Haru Morita"), "one fact is not enough: two Harus of one year are two women");

        LibraryStore variant = family(tmp.resolve("variant"));
        FamilyNameHistoryTest.file(variant, TREE, List.of(), List.of(
                FamilyNameHistoryTest.name("Haruko Endo", "Haruko Endo", "Endo", "Haruko", "birth", "1910", "Haruko Endo was born in 1910."),
                FamilyNameHistoryTest.name("Kenji Morita", "Kenji Morita", "Morita", "Kenji", "birth", "1905", "Kenji Morita was born in 1905.")));
        claim(variant, "Haruko Endo", "born-on", "1910", TREE);
        claim(variant, "Haruko Endo", "married-to", "Kenji Morita", TREE);
        claim(variant, "Haru Morita", "born-on", "1910", LETTERS);
        assertNotEquals(entry(variant, "Haruko Endo"), entry(variant, "Haru Morita"), "Haru and Haruko are two given names");
    }

    @Test
    void aMarriageToTheirOwnBrotherAgreesWithNothingForAChangedName(@TempDir Path tmp) throws Exception {
        // Kenji Morita's wife Haru Endo, and his sister Haru Morita, whom a mistaken fact also marries to him: a marriage between a brother
        // and a sister cannot be, so it is no fact that the two Harus are one woman before and after her marriage
        LibraryStore store = family(tmp);
        FamilyNameHistoryTest.file(store, TREE, List.of(), List.of(
                FamilyNameHistoryTest.name("Haru Endo", "Haru Endo", "Endo", "Haru", "birth", "1910", "Haru Endo was born in 1910."),
                FamilyNameHistoryTest.name("Kenji Morita", "Kenji Morita", "Morita", "Kenji", "birth", "1905", "Kenji Morita was born in 1905."),
                FamilyNameHistoryTest.name("Haru Morita", "Haru Morita", "Morita", "Haru", "birth", "1908", "Haru Morita was born in 1908.")));
        claim(store, "Haru Endo", "married-to", "Kenji Morita", TREE);
        claim(store, "Isamu Morita", "parent-of", "Kenji Morita", LETTERS);
        claim(store, "Isamu Morita", "parent-of", "Haru Morita", LETTERS);
        claim(store, "Haru Morita", "married-to", "Kenji Morita", BOOK);
        assertNotEquals(entry(store, "Haru Endo"), entry(store, "Haru Morita"), "his sister is not his wife under another name");
        assertTrue(FamilyLinks.current(store).links().stream().noneMatch(l -> l.rule().equals("L11") && l.grade() != FamilyLinks.Grade.possible));
    }

    @Test
    void aGivenNameABookGivesTwoPeopleIsNotCarriedIntoARelationToSomebodyItOnlyDescribes(@TempDir Path tmp) throws Exception {
        // the book's "Morita Kenji and his wife, Haru" is the Haru the letters marry to him; its "Haru [older sister] was married" is a
        // sister of somebody the book only describes, and may be another Haru
        LibraryStore store = family(tmp);
        claim(store, "Morita Kenji", "married-to", "Morita Haru", LETTERS);
        Finding wife = claim(store, "Morita Kenji", "married-to", "Haru", BOOK, "Interview with Morita Kenji and his wife, Haru.");
        Finding sister = claim(store, "Haru", "sibling-of", "the speaker in morita-book.txt, part 3", BOOK, "By then, Haru [older sister] was married.");
        Finding born = claim(store, "Haru", "born-in", "Kyoto", BOOK, "Haru was born in Kyoto.");
        assertEquals(entry(store, "Morita Haru"), side(store, wife, false), "his wife Haru is the wife the letters give him");
        assertEquals(Vocabulary.norm("Haru"), side(store, sister, true), "an older sister of somebody the book only describes: left as written");
        assertEquals(entry(store, "Morita Haru"), side(store, born, true), "a fact about her alone goes where the book says who she is");
    }

    @Test
    void aGrandparentTheWordsPlaceOnTheFathersSideIsNotTheMothersMother(@TempDir Path tmp) throws Exception {
        // "my grandparents, my father's parents", read as "Morita Aya's grandmother is a parent of Morita Aya's father": the one grandmother
        // the library knows is her mother's mother, who is not it
        LibraryStore store = family(tmp);
        for (String src : List.of(BOOK, LETTERS)) {
            claim(store, "Morita Aya", "child-of", "Morita Kenji", src);
            claim(store, "Morita Aya", "child-of", "Morita Haru", src);
            claim(store, "Morita Haru", "child-of", "Endo Fumi", src);
        }
        claim(store, "Morita Kenji", "sex", "male", BOOK);
        claim(store, "Morita Haru", "sex", "female", BOOK);
        claim(store, "Endo Fumi", "sex", "female", BOOK);
        claim(store, "Morita Aya's grandmother", "parent-of", "Morita Aya's father", TREE, "I was to stay with my grandparents, my father's parents.");
        assertNotEquals(entry(store, "Endo Fumi"), entry(store, "Morita Aya's grandmother"), "her father's mother is not her mother's mother");

        LibraryStore mothers = family(tmp.resolve("mothers"));
        for (String src : List.of(BOOK, LETTERS)) {
            claim(mothers, "Morita Aya", "child-of", "Morita Kenji", src);
            claim(mothers, "Morita Aya", "child-of", "Morita Haru", src);
            claim(mothers, "Morita Haru", "child-of", "Endo Fumi", src);
        }
        claim(mothers, "Morita Kenji", "sex", "male", BOOK);
        claim(mothers, "Morita Haru", "sex", "female", BOOK);
        claim(mothers, "Endo Fumi", "sex", "female", BOOK);
        claim(mothers, "Morita Aya's grandmother", "parent-of", "Morita Aya's mother", TREE, "I was to stay with my grandmother, my mother's mother.");
        assertEquals(entry(mothers, "Endo Fumi"), entry(mothers, "Morita Aya's grandmother"), "on the mother's side the walk finds her");
    }

    @Test
    void namesOfAJapaneseFamilyMeetAcrossTheWaysRomajiWriteThemAndNamesInLatinLettersAloneOnlyAsWritten(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Kenji Endoh", "", List.of("遠藤健二"), "", ""), new FamilyAccount.Person("Kenji Endō", "", List.of("えんどう けんじ"), "", ""),
                new FamilyAccount.Person("John Lee", "", List.of(), "", ""), new FamilyAccount.Person("John Yi", "", List.of(), "", "")),
                List.of(new FamilyAccount.Fact("Kenji Endoh", "born-on", "1905", "", "q"), new FamilyAccount.Fact("Kenji Endō", "occupation", "carpenter", "", "q"),
                        new FamilyAccount.Fact("John Lee", "born-on", "1905", "", "q"), new FamilyAccount.Fact("John Yi", "occupation", "carpenter", "", "q")), List.of()), BOOK, "a book");
        assertEquals(entry(store, "Kenji Endoh"), entry(store, "Kenji Endō"), "one name as romaji writes it two ways, in a book that names one Kenji Endō");
        assertNotEquals(entry(store, "John Lee"), entry(store, "John Yi"), "names known only in Latin letters meet only as written");
    }

    // ── the writer of a file, and a given name alone among a person's names ──────────────────────────────────────────

    @Test
    void theWriterOfAFileIsThePersonTheNotesSayWroteItEvenUnderAShortenedFileName(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "Morita Aya", "life-event", "wrote the book - The Silk Shop on the Corner: A Family History", "file:///family/notes.txt");
        claim(store, "the writer of The Silk Shop on the Corner A Fam.epub", "occupation", "teacher", "file:///family/The Silk Shop on the Corner A Fam.epub");
        claim(store, "Morita Aya", "born-on", "1950", "file:///family/notes.txt");
        assertEquals(entry(store, "Morita Aya"), entry(store, "the writer of The Silk Shop on the Corner A Fam.epub"));
        claim(store, "the writer of The Silk Shop on the Corner A Fam.epub's mother", "occupation", "seamstress", "file:///family/The Silk Shop on the Corner A Fam.epub");
        assertNotEquals(entry(store, "Morita Aya"), entry(store, "the writer of The Silk Shop on the Corner A Fam.epub's mother"), "the writer's mother is not the writer");
        claim(store, "the writer of The Silk Shop on the Corner A Fam.epub's step-brother", "occupation", "baker", "file:///family/The Silk Shop on the Corner A Fam.epub");
        assertNotEquals(entry(store, "Morita Aya"), entry(store, "the writer of The Silk Shop on the Corner A Fam.epub's step-brother"), "nor her step-brother");

        LibraryStore other = family(tmp.resolve("other"));
        claim(other, "Morita Aya", "life-event", "wrote the book - Letters From Kure", "file:///family/notes.txt");
        claim(other, "the writer of The Silk Shop on the Corner A Fam.epub", "occupation", "teacher", "file:///family/The Silk Shop on the Corner A Fam.epub");
        assertNotEquals(entry(other, "Morita Aya"), entry(other, "the writer of The Silk Shop on the Corner A Fam.epub"), "she wrote another book");
    }

    @Test
    void aGivenNameAloneIsAShortFormOfTheNameWhoseGivenPartItIsAndNoNameAtBirth(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        FamilyNameHistoryTest.file(store, BOOK, List.of(), List.of(
                FamilyNameHistoryTest.name("Kenji Morita", "Kenji Morita", "Morita", "Kenji", "", "", "Kenji Morita ran the shop."),
                FamilyNameHistoryTest.name("Kenji Morita", "Kenji", "", "", "birth", "", "He was named Kenji."),
                FamilyNameHistoryTest.name("Kenji Morita", "Paul", "", "Paul", "", "", "His Christian name was Paul."),
                FamilyNameHistoryTest.name("Kenji Morita", "Kenji Paul Morita", "Morita", "Kenji Paul", "", "", "Kenji Paul Morita signed the lease."),
                FamilyNameHistoryTest.name("Kenji Morita", "Kenji Paul", "Paul", "Kenji", "", "", "Kenji Paul signed it too.")));
        Graph g = FamilyPeople.view(store);
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        List<String> written = idx.names(g.nodeIdOf("Kenji Morita")).stream().map(FamilyNameHistory.Name::written).toList();
        assertFalse(written.contains("Kenji"), "Kenji is how the book writes Kenji Morita short: " + written);
        assertFalse(written.contains("Kenji Paul"), "Kenji Paul is the given part of Kenji Paul Morita, not a name of its own: " + written);
        assertTrue(written.contains("Paul"), "a name a source gives the kind of stays a name of its own: " + written);
        assertFalse(idx.heading(g.nodeIdOf("Kenji Morita")).contains("born Kenji"), idx.heading(g.nodeIdOf("Kenji Morita")));
    }
}
