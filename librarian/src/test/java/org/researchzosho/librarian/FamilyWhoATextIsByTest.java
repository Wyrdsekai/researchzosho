package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Who a text is by, and so whose "I" it is: the name alone that the family's notes give a file's writer, a translator who is not the writer,
 * a memoir that is the writer's own, and, where the notes say nothing, the book's own first pages, asked once, whose words must be in the text.
 */
class FamilyWhoATextIsByTest {

    @AfterEach void restore() { GenealogyProfile.useReader(null); }

    private static LibraryStore store(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    @Test
    void theNotesNameTheWriterByTheNameAloneAndATranslatorIsNotTheWriter(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path dir = Files.createDirectories(tmp.resolve("family"));
        Files.writeString(dir.resolve("notes.txt"), String.join("\n",
                "i am Ann Hale, born 1970",
                "Kimie Hale - my father's cousin - wrote community.pdf",
                "Tom Hart - wrote the book - Village Life in Leeds.epub",
                "Isamu Morita - is the one the book - farm.epub is about. it is his own memoir.",
                "Rose Hart (Rose Mary Hart) - translated farm.epub. she is not family - her parents were Ned and Mary Hart",
                "letters.pdf is Haru Endō's diary",
                "sermons.pdf is the memoir of Ken Ellis.",
                "i wrote story.pdf",
                "Ann Ellis wrote recipes.pdf, from her mother's diary"), StandardCharsets.UTF_8);
        for (String f : List.of("recipes.pdf", "community.pdf", "Village Life in Leeds.epub", "farm.epub", "letters.pdf", "sermons.pdf", "story.pdf", "other.pdf")) Files.writeString(dir.resolve(f), "x");
        List<FamilyFolder.Item> plan = FamilyFolder.plan(store, dir);
        Map<String, String> writers = FamilyFolder.writers(plan), translators = FamilyFolder.translators(plan);
        assertEquals("Kimie Hale", writers.get("community.pdf"), "the owner's words about her between the dashes are no part of her name");
        assertEquals("Tom Hart", writers.get("Village Life in Leeds.epub"));
        assertEquals("Isamu Morita", writers.get("farm.epub"), "his own memoir is his, not its translator's");
        assertEquals("Rose Hart", translators.get("farm.epub"));
        assertEquals("Haru Endō", writers.get("letters.pdf"));
        assertEquals("Ken Ellis", writers.get("sermons.pdf"));
        assertEquals("the writer of notes.txt", writers.get("story.pdf"), "\"i\" in the notes is whoever wrote the notes");
        assertNull(writers.get("other.pdf"));
        assertEquals(1, translators.size(), translators.toString());
        assertEquals(Set.of("farm.epub", "letters.pdf", "sermons.pdf"), FamilyFolder.memoirs(plan), "the texts the notes call the writer's own memoir or diary");
        assertEquals("Kimie Hale", FamilyFolder.nameOnly("Kimie Hale - my father's cousin -"));
        assertEquals("Rose Hart", FamilyFolder.nameOnly("Rose Hart (Rose Mary Hart)"));
    }

    static final String BOOK = "THE HALE FARM\n\nA Memoir\n\nIsamu Morita\n\nTranslated from the Japanese by Rose Hart\n\n"
            + "Translator's Note\n\nI met Isamu Morita's daughter in 1990, and my mother Mary Hart encouraged me to translate this book.\n\n"
            + "Chapter One\n\nI was born in Sendai in 1901. My father Genzaburo Morita kept silkworms.\n\n" + "The farm grew year by year. ".repeat(1600);

    @Test
    void aBookNobodyNamedTheWriterOfIsAskedOnceAndItsFirstPagesMustSayIt(@TempDir Path tmp) throws Exception {
        List<String> prompts = new ArrayList<>();
        FamilyAccount.Byline b = FamilyAccount.byline(BOOK, p -> { prompts.add(p); return "{\"writer\": \"Isamu Morita\", \"translator\": \"Rose Hart\", \"quote\": \"Isamu Morita\\n\\nTranslated from the Japanese by Rose Hart\"}"; });
        assertNotNull(b);
        assertEquals("Isamu Morita", b.writer());
        assertEquals("Rose Hart", b.translator());
        assertEquals(1, prompts.size());
        assertTrue(prompts.get(0).contains("THE FIRST PAGES:\nTHE HALE FARM") && prompts.get(0).length() < FamilyAccount.FIRST_PAGES + 2000, "only the first pages go to the model");
        assertNull(FamilyAccount.byline(BOOK, p -> "{\"writer\": \"Tom Hale\", \"translator\": \"\", \"quote\": \"by Tom Hale\"}"), "words the text does not have name nobody");
        assertNull(FamilyAccount.byline(BOOK, p -> "{\"writer\": \"Tom Hale\", \"translator\": \"\", \"quote\": \"A Memoir\"}"), "a line that does not carry the name names nobody");
        assertNull(FamilyAccount.byline(BOOK, p -> "no idea"));
        FamilyAccount.Byline same = FamilyAccount.byline(BOOK, p -> "{\"writer\": \"Rose Hart\", \"translator\": \"Rose Hart\", \"quote\": \"Translated from the Japanese by Rose Hart\"}");
        assertTrue(same != null && same.writer().isEmpty() && same.translator().equals("Rose Hart"), "the translator is not taken for the writer");
    }

    @Test
    void theBooksIIsItsWriterAndTheTranslatorsNoteIsTheTranslators(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path book = tmp.resolve("farm-memoir.txt");
        Files.writeString(book, BOOK, StandardCharsets.UTF_8);
        List<String> prompts = new ArrayList<>();
        GenealogyProfile.useReader(p -> {
            prompts.add(p);
            if (p.startsWith("Below are the first pages")) return "{\"writer\": \"Isamu Morita\", \"translator\": \"Rose Hart\", \"quote\": \"A Memoir Isamu Morita Translated from the Japanese by Rose Hart\"}";
            if (p.contains("Answer with one word")) return "no";
            if (!p.contains("Translator's Note")) return "{\"people\": [], \"facts\": []}";
            return """
                    {"people": [{"name": "Isamu Morita", "sex": "male"}], "facts": [
                     {"subject": "Mary Hart", "relation": "parent-of", "object": "Isamu Morita", "date": "", "quote": "my mother Mary Hart encouraged me to translate this book."},
                     {"subject": "Genzaburo Morita", "relation": "parent-of", "object": "Isamu Morita", "date": "", "quote": "My father Genzaburo Morita kept silkworms."},
                     {"subject": "Isamu Morita", "relation": "born-in", "object": "Sendai", "date": "1901", "quote": "I was born in Sendai in 1901."}]}""";
        });
        String out = run(store, "read", book.toString());
        assertTrue(prompts.stream().filter(p -> p.startsWith("Below are the first pages")).count() == 1, "asked once: " + prompts.size());
        assertTrue(out.contains("Its first pages say it is by Isamu Morita") && out.contains("Rose Hart translated it"), out);
        assertTrue(prompts.stream().anyMatch(p -> p.contains("told by Isamu Morita. List the people")), "the book is read as his account");
        List<String> triples = store.scanFindings().findings().stream().filter(f -> f.triple() != null).map(f -> f.triple().subject() + " | " + f.triple().predicate() + " | " + f.triple().object()).toList();
        assertTrue(triples.contains("Mary Hart | parent-of | Rose Hart"), "the translator's note speaks as the translator: " + triples);
        assertFalse(triples.contains("Mary Hart | parent-of | Isamu Morita"), triples.toString());
        assertTrue(store.scanFindings().findings().stream().anyMatch(f -> f.sources().stream().anyMatch(s -> s.edition().equals("as told by Isamu Morita"))), "the claims are his account");
    }

    @Test
    void aMemoirsIIsItsWritersUnlessThePartIsSomebodyElsesAndTheJudgeIsToldTheNameAsWritten() {
        FamilyAccount.Voice memoir = FamilyAccount.Voice.of("森田勇", "file:///family/farm.epub", "Rose Hart").as("Isamu Morita", true);
        String q = FamilyAccount.voiceQuestion(memoir, "My father kept silkworms.", "Chapter One. I was born in Sendai. My father kept silkworms.");
        assertTrue(q.contains("the memoir 森田勇 (its first pages write the name Isamu Morita) wrote of their own life, translated by Rose Hart"), q);
        assertTrue(q.contains("In the memoir's own text the one who says I is 森田勇") && q.contains("an editor's introduction or a translator's note"), q);
        FamilyAccount.Voice plain = FamilyAccount.Voice.of("Kimie Hale", "file:///family/community.pdf").as("Kimie Hale", false);
        assertEquals("", plain.writtenAs(), "the same way of writing is not said twice");
        assertTrue(FamilyAccount.voiceQuestion(plain, "my father", "my father").contains("a text written by Kimie Hale."));
    }

    static final String FRONT = "THE HALE FARM\n\nAcknowledgments\nwith Editor's and Translator's Remarks\n\nWith my grades and diploma I came back to Leeds in 1950, and then on to further studies in York. I thank my daughter, Nell.\n\n"
            + "THE MEMOIR\n\nI was born in Sendai in 1901. My father Genzaburo Morita kept silkworms.\n\n" + "The farm grew year by year. ".repeat(1600);

    @Test
    void wordsInAnEditorsPartAreNotTheWritersWhateverTheyTellOf() {
        FamilyAccount.Voice voice = FamilyAccount.Voice.of("Isamu Morita", "file:///family/farm.epub", "Rose Hart").as("", true);
        assertEquals("editor", FamilyAccount.partOf(FRONT, "With my grades and diploma I came back to Leeds in 1950"));
        assertEquals("", FamilyAccount.partOf(FRONT, "I was born in Sendai in 1901."), "the memoir's own text");
        FamilyAccount.Read r = FamilyAccount.read(FRONT, "Isamu Morita", new GenealogyProfile().predicates(), p -> p.contains("THE HALE FARM") ? """
                {"people": [], "facts": [
                 {"subject": "Isamu Morita", "relation": "life-event", "object": "came back to Leeds", "date": "1950", "quote": "With my grades and diploma I came back to Leeds in 1950"},
                 {"subject": "Isamu Morita", "relation": "life-event", "object": "studied in York", "date": "", "quote": "and then on to further studies in York."},
                 {"subject": "Isamu Morita", "relation": "parent-of", "object": "Nell", "date": "", "quote": "I thank my daughter, Nell."},
                 {"subject": "Isamu Morita", "relation": "born-in", "object": "Sendai", "date": "1901", "quote": "I was born in Sendai in 1901."}]}""" : "{\"people\": [], \"facts\": []}",
                List.of(), null, q -> 0.9, voice);
        List<String> triples = r.facts().stream().map(f -> f.subject() + " | " + f.relation() + " | " + f.object()).toList();
        assertTrue(triples.contains("Isamu Morita | born-in | Sendai"), triples.toString());
        assertTrue(triples.stream().noneMatch(t -> t.startsWith("Isamu Morita | life-event") || t.startsWith("Isamu Morita | parent-of")), "the editor's and the translator's words are not his: " + triples);
        assertTrue(triples.contains("the speaker in farm.epub, part 1 | parent-of | Nell"), triples.toString());
    }

    @Test
    void aNameTheWordsCreditWithWorkOnTheTextIsNoNameOfTheWriter() {
        String q = "memoir / Isamu Morita ; introduced and edited by Ken Ellis ; from a translation by Rose Hart";
        assertTrue(FamilyAccount.credited(new FamilyAccount.NameRead("Isamu Morita", "Ken Ellis", "", "", List.of(), "", "", "", q)));
        assertTrue(FamilyAccount.credited(new FamilyAccount.NameRead("Isamu Morita", "Rose Hart", "", "", List.of(), "", "", "", q)));
        assertFalse(FamilyAccount.credited(new FamilyAccount.NameRead("Isamu Morita", "Isamu Morita", "", "", List.of(), "", "", "", q)));
        assertTrue(FamilyAccount.credited(new FamilyAccount.NameRead("森田勇", "遠藤ハル", "", "", List.of(), "", "", "", "森田勇 著・遠藤ハル 訳")));
        List<String> dropped = new ArrayList<>();
        assertNull(FamilyAccount.checkName(new FamilyAccount.NameRead("Isamu Morita", "Ken Ellis", "", "", List.of(), "", "", "", q), q, FamilyAccount.flat(q), p -> "no", null, dropped));
        assertTrue(dropped.get(0).contains("as somebody who worked on the text"), dropped.toString());
        // a name the words give alone, sharing no word with the person's names in Latin letters: a signature under a foreword, nobody's other name
        FamilyAccount.Person isamu = new FamilyAccount.Person("森田勇", "", List.of("Isamu Morita"));
        List<String> said = new ArrayList<>();
        assertTrue(FamilyAccount.nameAlone(new FamilyAccount.NameRead("森田勇", "Ken Ellis", "", "", List.of(), "", "", "", "Ken Ellis"), isamu, List.of(), said));
        assertTrue(said.get(0).contains("the words given for it are the name alone"), said.toString());
        assertFalse(FamilyAccount.nameAlone(new FamilyAccount.NameRead("森田勇", "Father Morita", "", "", List.of(), "", "", "", "Father Morita"), isamu, List.of(), said), "a word of his own name");
        assertFalse(FamilyAccount.nameAlone(new FamilyAccount.NameRead("森田勇", "Amos", "", "", List.of(), "", "", "", "my Christian name is Amos"), isamu, List.of(), said), "words that say the name came");
        assertFalse(FamilyAccount.nameAlone(new FamilyAccount.NameRead("森田勇", "Ken Ellis", "", "", List.of(), "", "", "", "Ken Ellis"), new FamilyAccount.Person("森田勇", "", List.of()), List.of(), said), "no Latin form to compare");
        assertTrue(FamilyAccount.nameAlone(new FamilyAccount.NameRead("森田勇", "Ken Ellis", "", "", List.of(), "", "", "", "Ken Ellis"), null, List.of("Isamu Morita"), said),
                "the writer's name as the book's first pages write it is a form to compare");
        assertTrue(FamilyAccount.nameAlone(new FamilyAccount.NameRead("森田勇", "Ken Ellis", "", "", List.of(), "", "", "", "Ken Ellis"), new FamilyAccount.Person("森田勇", "", List.of("Ken Ellis")),
                List.of("Isamu Morita"), said), "a signature the reading also gave the writer as a spelling does not tie it to him");
    }

    @Test
    void aBookTheNotesNameTheWriterOfIsNotAskedAndShortNotesNever(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path notes = tmp.resolve("notes.txt");
        Files.writeString(notes, "My grandfather Tom Hale was born in Leeds.", StandardCharsets.UTF_8);
        Path book = tmp.resolve("farm-memoir.txt");
        Files.writeString(book, BOOK, StandardCharsets.UTF_8);
        List<String> prompts = new ArrayList<>();
        GenealogyProfile.useReader(p -> { prompts.add(p); return "{\"people\": [], \"facts\": []}"; });
        run(store, "read", notes.toString());
        run(store, "read", book.toString(), "--by", "Isamu Morita");
        assertTrue(prompts.stream().noneMatch(p -> p.startsWith("Below are the first pages")), "the notes are the owner's, and the book's writer was given");
        run(store, "read", book.toString());
        assertEquals(1, prompts.stream().filter(p -> p.startsWith("Below are the first pages")).count(), "a book with no writer given is asked");
    }

    static String run(LibraryStore store, String... rest) throws Exception {
        String[] args = new String[rest.length + 2];
        args[0] = "researchzosho"; args[1] = "genealogy";
        System.arraycopy(rest, 0, args, 2, rest.length);
        PrintStream was = System.out, wasErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream both = new PrintStream(out, true, StandardCharsets.UTF_8);
        System.setOut(both); System.setErr(both);
        try { new GenealogyProfile().cli(store, args); } finally { System.setOut(was); System.setErr(wasErr); }
        return out.toString(StandardCharsets.UTF_8);
    }
}
