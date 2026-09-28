package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L5b across scripts ({@link FamilyLinks}), with an invented family: a page writes a mother or a son by the given name alone in characters
 * (花子, 一郎), another source writes them in full in Latin letters, and both stand in the same relation to a man who is one person by the
 * evidence in both scripts. The relation and the model's reading of the given name say who the mention is.
 */
class FamilyLinksAcrossScriptsTest {

    private static final String PAGE = "file:///family/register-page.txt";
    private static final String BOOK = "file:///family/morita-memoir.txt";

    private static LibraryStore family(Path tmp) throws Exception {
        LibraryStore s = new LibraryStore(tmp.resolve("lib"));
        s.init();
        FamilyPeople.holdsAFamily(s);
        return s;
    }

    private static Finding claim(LibraryStore store, String s, String p, String o, String source) throws Exception {
        String sentence = s + " " + p.replace('-', ' ') + " " + o + ".";
        Finding f = new Finding(store.nextFindingId(sentence), sentence, List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.low, "family-account", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source(source, "a family paper", "")), List.of(), null, sentence + "\n\nThe account says: \"" + sentence + "\"\n",
                new Finding.Triple(s, p, o), List.of());
        store.write(f);
        return f;
    }

    /** A model that reads the given names it is given, and counts what it is asked. */
    private static final class Reads implements FamilyLinks.Model {
        final Map<String, String> r;
        final List<String> asked = new ArrayList<>();
        Reads(Map<String, String> r) { this.r = r; }
        @Override public String ask(String prompt) {
            asked.add(prompt);
            for (Map.Entry<String, String> e : r.entrySet()) if (prompt.contains("given name " + e.getKey())) return "{\"readings\": [\"" + e.getValue() + "\"]}";
            return "{}";
        }
    }

    /**
     * 森田健二 in the register and Kenji Morita in the memoir: one man by the evidence (the register's reading of his name, and the same birth
     * year in both). The memoir names his parents and his son in full; the register names his mother and his son by the given name alone.
     */
    private static LibraryStore kenji(Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "森田健二", "born-on", "1905", PAGE);
        claim(store, "Kenji Morita", "born-on", "1905", BOOK);
        Graph.alias(store, "森田健二", List.of("もりた けんじ"), PAGE);
        claim(store, "Kenji Morita", "child-of", "Taro Morita", BOOK);
        claim(store, "Kenji Morita", "child-of", "Hanako Morita", BOOK);
        claim(store, "Ichiro Morita", "child-of", "Kenji Morita", BOOK);
        claim(store, "Osamu Morita", "child-of", "Kenji Morita", BOOK);
        return store;
    }

    private static String entry(LibraryStore store, String name) throws IOException { return FamilyPeople.view(store).nodeIdOf(name); }

    private static String side(LibraryStore store, Finding f, boolean subject) throws IOException { return FamilyPeople.view(store).nodeOf(f, subject); }

    @Test
    void aMotherAndASonWrittenByTheGivenNameAloneInCharactersAreThePeopleTheRelationAndTheReadingSay(@TempDir Path tmp) throws Exception {
        LibraryStore store = kenji(tmp);
        Finding mother = claim(store, "森田健二", "child-of", "花子", PAGE);
        Finding son = claim(store, "一郎", "child-of", "森田健二", PAGE);
        Reads model = new Reads(Map.of("花子", "はなこ", "一郎", "いちろう"));
        FamilyLinks.update(store, model, x -> { });
        assertEquals(entry(store, "森田健二"), entry(store, "Kenji Morita"), "the man both are related to is one person by the evidence: " + FamilyLinks.current(store).links().stream().map(FamilyLinks::sentence).toList());
        assertEquals(entry(store, "Hanako Morita"), side(store, store.finding(mother.id()), false), "花子, his mother, reads Hanako: Hanako Morita, his mother in the memoir");
        assertEquals(entry(store, "Ichiro Morita"), side(store, store.finding(son.id()), true), "一郎, his son, reads Ichiro, and his other son is Osamu");
        FamilyLinks.Link l = FamilyLinks.current(store).links().stream().filter(x -> x.claim().equals(mother.id())).findFirst().orElseThrow();
        assertEquals(FamilyLinks.Grade.probable, l.grade());
        assertTrue(l.why().contains("as the model reads it, はなこ"), l.why());
        assertEquals(3, model.asked.size(), "the two given names are asked, and 健二, whose entry carries the spelling Kenji Morita, for the names over a life: " + model.asked);
    }

    @Test
    void aReadingThatFitsNobodyOrTwoPeopleLinksNothing(@TempDir Path tmp) throws Exception {
        LibraryStore store = kenji(tmp.resolve("other"));
        Finding mother = claim(store, "森田健二", "child-of", "花子", PAGE);
        FamilyLinks.update(store, new Reads(Map.of("花子", "かこ")), x -> { });
        assertEquals(entry(store, "花子"), side(store, store.finding(mother.id()), false), "read Kako, she is neither of his parents in the memoir");

        LibraryStore two = kenji(tmp.resolve("two"));
        claim(two, "Ichiro Morita (born 1935)", "child-of", "Kenji Morita", BOOK);
        Finding son = claim(two, "一郎", "child-of", "森田健二", PAGE);
        FamilyLinks.update(two, new Reads(Map.of("一郎", "いちろう")), x -> { });
        assertEquals(entry(two, "一郎"), side(two, two.finding(son.id()), true), "two sons of his read Ichiro: the relation does not say which");
    }

    @Test
    void aBlockKeepsTheMentionAsItIsWritten(@TempDir Path tmp) throws Exception {
        LibraryStore store = kenji(tmp);
        claim(store, "Hanako Morita", "died-on", "1890", BOOK);
        Finding mother = claim(store, "森田健二", "child-of", "花子", PAGE);
        FamilyLinks.update(store, new Reads(Map.of("花子", "はなこ")), x -> { });
        assertEquals(entry(store, "花子"), side(store, store.finding(mother.id()), false), "a mother dead fifteen years before his birth is not his mother");
    }

    @Test
    void nobodyIsAskedWhenThePersonTheyAreRelatedToIsNotOnePersonByTheEvidence(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "Kenji Morita", "child-of", "Hanako Morita", BOOK);
        Finding mother = claim(store, "森田健二", "child-of", "花子", PAGE);
        Reads model = new Reads(Map.of("花子", "はなこ"));
        FamilyLinks.update(store, model, x -> { });
        assertEquals(entry(store, "花子"), side(store, store.finding(mother.id()), false));
        assertTrue(model.asked.stream().noneMatch(p -> p.contains("given name 花子")), "no established person relates them: " + model.asked);
    }
}
