package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** The preferences file: the defaults when there is none, every line with its source, the person's settings checked and said back in a sentence, export and import. */
class PrefsTest {

    Path tmp;
    @BeforeEach void dirs(@TempDir Path t) { tmp = t; Prefs.useDir(t.resolve("prefs")); Dna.useDir(t.resolve("dna")); }
    @AfterEach void reset() { Prefs.useDir(null); Dna.useDir(null); }

    @Test void theDefaultsAreTheSettledWeightsAndTheFileIsWrittenOnTheFirstChange() throws Exception {
        Prefs p = Prefs.load(Dna.BOOK);
        assertFalse(p.exists());
        assertEquals(2.0, p.weight("subject")); assertEquals(0.0, p.weight("form")); assertEquals(0.5, p.weight("mood")); assertEquals(0.2, p.roam()); assertEquals("one", p.popularity());
        assertTrue(p.text().startsWith("No preferences file yet; these are the defaults"), p.text());
        assertTrue(p.text().contains("Weights in \"like X\" and browsing: subject 2, kind 0.5, audience 0.5, demand 0.5, setting 0, era 0, mood 0.5, scale 0.25, form 0 (form stays at 0"), p.text());
        assertEquals("The weight of subject is now 3.0.", p.set("weight.subject", "3", "you"));
        assertTrue(p.exists());
        assertEquals(3.0, Prefs.load(Dna.BOOK).weight("subject"));
        assertEquals("you, " + LocalDate.now(), Prefs.load(Dna.BOOK).of("weight").stream().filter(l -> l.dimension().equals("subject")).findFirst().orElseThrow().from());
        assertEquals("In your words: quiet books about places", p.set("words", "quiet books about places", "you"));
        assertTrue(Prefs.load(Dna.BOOK).text().contains("In your words: quiet books about places"));
    }

    @Test void aWrongSettingIsSaidBackPlainly() {
        Prefs p = Prefs.load(Dna.BOOK);
        assertEquals("a weight is a number, such as 2 or 0.5.", assertThrows(IOException.class, () -> p.set("weight.subject", "lots", "you")).getMessage());
        assertEquals("the dimensions with a weight are subject, kind, audience, demand, setting, era, mood, scale, form.", assertThrows(IOException.class, () -> p.set("weight.colour", "1", "you")).getMessage());
        assertEquals("roam is between 0 and 1.", assertThrows(IOException.class, () -> p.set("roam", "3", "you")).getMessage());
        assertEquals("popularity is one (one well-known title in every list), none (no such rule) or known (well-known titles preferred).", assertThrows(IOException.class, () -> p.set("popularity", "lots", "you")).getMessage());
        assertEquals("exclude takes author, subject, kind, series.", assertThrows(IOException.class, () -> p.set("exclude.colour", "red", "you")).getMessage());
        assertEquals("prefer takes mood, subject, kind, pace, author.", assertThrows(IOException.class, () -> p.set("prefer.series", "x", "you")).getMessage());
        assertEquals("the settings are weight.<dimension>, roam, popularity, words, exclude.<author|subject|kind|series>, prefer.<mood|subject|kind|pace|author> and avoid.<the same>.", assertThrows(IOException.class, () -> p.set("colour", "red", "you")).getMessage());
        assertEquals("no such line.", assertThrows(IOException.class, () -> p.unset("exclude.author", "nobody")).getMessage());
    }

    @Test void exportAndImportCarryTheFile() throws Exception {
        Prefs p = Prefs.load(Dna.BOOK);
        p.set("prefer.mood", "gentle", "you"); p.set("exclude.author", "Nobody Much", "you"); p.set("roam", "0.6", "you");
        Path out = tmp.resolve("out").resolve("books-prefs.json");
        p.export(out);
        assertTrue(Files.isRegularFile(out));
        Prefs.useDir(tmp.resolve("prefs2"));
        Prefs q = Prefs.load(Dna.BOOK);
        assertEquals(0.2, q.roam());
        int added = q.importFrom(out);
        assertTrue(added >= 2, "the prefer and exclude lines came in: " + added);
        assertEquals(0.6, q.roam());
        assertTrue(q.of("prefer").get(0).from().endsWith("(imported from books-prefs.json)"), q.of("prefer").get(0).from());
        assertEquals("That line is there already.", q.set("prefer.mood", "gentle", "you"));
        Files.writeString(tmp.resolve("junk.json"), "{\"nope\": 1}");
        assertEquals("not a preferences file: it has no lines", assertThrows(IOException.class, () -> q.importFrom(tmp.resolve("junk.json"))).getMessage());
    }

    @Test void theReactionsTeachTheFile() throws Exception {
        ObjectNodeHolder h = new ObjectNodeHolder();
        for (int i = 0; i < 3; i++) Reactions.add(Dna.BOOK, "like", h.doc("l" + i, "novel", "gentle", "harbours"), "Liked " + i, "", null);
        for (int i = 0; i < 3; i++) Reactions.add(Dna.BOOK, "dislike", h.doc("d" + i, "business", "dry", "finance"), "Disliked " + i, "", null);
        Prefs p = Prefs.load(Dna.BOOK);
        p.learn();
        assertTrue(p.text().contains("- prefer mood gentle — from learned from your reactions: 3 books you liked carry it"), p.text());
        assertTrue(p.text().contains("- prefer subject harbours — from learned from your reactions: 3 books you liked are about it"), p.text());
        assertTrue(p.text().contains("- avoid kind business — from learned from your reactions: 3 disliked, none liked"), p.text());
        p.learn();
        assertEquals(1, p.of("avoid").size(), "learning again replaces the learned lines rather than doubling them");
    }

    /** Documents with just enough of a labelled layer for a reaction's snapshot. */
    static final class ObjectNodeHolder {
        ObjectNode doc(String key, String kind, String mood, String theme) {
            ObjectNode d = Dna.fresh(Dna.BOOK, key, Dna.M.createObjectNode().put("title", key));
            ObjectNode l = d.putObject("labelled"); l.put("version", Labels.VERSION); l.put("windows", 12); l.put("kind", kind);
            l.putObject("mood").put(mood, 0.8); l.putObject("book").putArray("themes").add(theme); l.putArray("topics"); l.putObject("scales").putObject("pace").put("mean", -0.5);
            return d;
        }
    }
}
