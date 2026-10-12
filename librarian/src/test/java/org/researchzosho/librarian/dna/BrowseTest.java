package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Browsing: a spread by construction, the preferences as the prior, one well-known title, the reactions out. */
class BrowseTest {

    @BeforeEach void dirs(@TempDir Path tmp) { Dna.useDir(tmp.resolve("dna")); Prefs.useDir(tmp.resolve("prefs")); }
    @AfterEach void reset() { Dna.useDir(null); Prefs.useDir(null); }

    static ObjectNode doc(String key, String title, String kind, String mood, double pace, List<String> themes, int editions) throws Exception {
        ObjectNode r = Dna.M.createObjectNode(); r.put("title", title); r.put("authors", "Author " + key); r.put("year", "2020"); r.putArray("tags"); if (editions > 0) r.put("edition_count", editions);
        ObjectNode d = Dna.fresh(Dna.BOOK, key, r);
        ObjectNode l = d.putObject("labelled"); l.put("version", Labels.VERSION); l.put("windows", 12); l.put("kind", kind); l.put("fiction", Labels.fiction(kind));
        ObjectNode book = l.putObject("book"); book.put("audience", "adult"); book.put("demand", "moderate"); ArrayNode th = book.putArray("themes"); themes.forEach(th::add);
        l.putArray("topics"); l.putObject("mood").put(mood, 0.8); ObjectNode s = l.putObject("scales").putObject("pace"); s.put("mean", pace); l.putObject("signature");
        Dna.write(d); return d;
    }

    void pool() throws Exception {
        doc("n1", "Quiet Novel", "novel", "gentle", -0.6, List.of("harbours"), 0); doc("n2", "Quiet Novel Two", "novel", "gentle", -0.5, List.of("harbours", "boats"), 0); doc("n3", "Dark Novel", "novel", "menacing", 0.7, List.of("murder"), 0);
        doc("h1", "Harbour History", "history", "scholarly", 0.0, List.of("ports"), 40); doc("b1", "Ledger Business", "business", "dry", 0.2, List.of("finance"), 0);
        doc("s1", "Stars Science", "science", "technical", 0.1, List.of("stars"), 0); doc("m1", "A Memoir", "memoir", "warm", -0.2, List.of("childhood"), 0); doc("p1", "Poems", "poetry", "lyrical", -0.8, List.of("rivers"), 0);
    }

    @Test void theListSpreadsAcrossTheDimensionsAndCarriesOneWellKnownTitle() throws Exception {
        pool();
        List<Browse.Pick> picks = Browse.browse(Dna.labelled(Dna.BOOK), Prefs.load(Dna.BOOK), 4, 7);
        assertEquals(4, picks.size());
        Set<String> kinds = new HashSet<>(); for (Browse.Pick p : picks) kinds.add(LikeX.kind(p.doc()));
        assertEquals(4, kinds.size(), "four picks, four kinds: " + picks.stream().map(Browse.Pick::title).toList());
        assertTrue(picks.stream().anyMatch(p -> p.title().equals("Harbour History")), "the well-known one is in: " + picks.stream().map(Browse.Pick::title).toList());
        String text = Browse.text(picks, 8, "book");
        assertTrue(text.startsWith("A spread across the 8 labelled books, each as different from the last as the store allows"), text);
        assertTrue(text.contains("Harbour History — Author h1 (2020): a history, scholarly, about ports (well known)"), text);
        assertTrue(text.contains("Poems — Author p1 (2020): a poetry, lyrical, slower than most, about rivers") || text.contains("Quiet Novel"), text);
    }

    @Test void thePreferencesAreThePriorAndTheReactionsCutTheList() throws Exception {
        pool();
        Prefs p = Prefs.load(Dna.BOOK); p.set("popularity", "none", "you"); p.set("prefer.mood", "gentle", "you");
        List<Browse.Pick> picks = Browse.browse(Dna.labelled(Dna.BOOK), Prefs.load(Dna.BOOK), 3, 1);
        assertTrue(picks.get(0).title().startsWith("Quiet Novel"), "the preferred mood leads: " + picks.stream().map(Browse.Pick::title).toList());
        assertTrue(picks.get(0).why().endsWith("; you prefer mood gentle"), picks.get(0).why());
        assertFalse(picks.stream().anyMatch(x -> x.title().equals("Harbour History")), "no popularity rule, no forced well-known title");
        Reactions.add(Dna.BOOK, "seen", Dna.read(Dna.BOOK, "n1"), "Quiet Novel", "", null);
        Reactions.add(Dna.BOOK, "seen", Dna.read(Dna.BOOK, "n2"), "Quiet Novel Two", "", null);
        picks = Browse.browse(Dna.labelled(Dna.BOOK), Prefs.load(Dna.BOOK), 3, 1);
        assertFalse(picks.stream().anyMatch(x -> x.title().startsWith("Quiet Novel")), "what was seen does not come back");
        p.set("exclude.kind", "poetry", "you");
        assertFalse(Browse.browse(Dna.labelled(Dna.BOOK), Prefs.load(Dna.BOOK), 8, 1).stream().anyMatch(x -> x.title().equals("Poems")));
        assertEquals("Nothing to browse yet: no book is labelled, or every labelled one has had a reaction or is excluded. The night housekeeping labels more.", Browse.text(List.of(), 0, "book"));
    }
}
