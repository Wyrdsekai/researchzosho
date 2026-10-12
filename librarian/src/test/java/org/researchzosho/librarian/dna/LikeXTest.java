package org.researchzosho.librarian.dna;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Like X" as settled: subject first, then kind, audience, demand, mood and the scales at a small weight; the same author set aside into
 * a side list; the preferences file's weights, exclusions and prefer lines moving the list in the stated direction; a dislike leaving a
 * row out and a seen marking it; one well-known title in every list unless the person says otherwise.
 */
class LikeXTest {

    Path dir;

    @BeforeEach void dirs(@TempDir Path tmp) { dir = tmp; Dna.useDir(tmp.resolve("dna")); Prefs.useDir(tmp.resolve("prefs")); }
    @AfterEach void reset() { Dna.useDir(null); Prefs.useDir(null); }

    static ObjectNode doc(String key, String title, String authors, String kind, String audience, String demand, List<String> themes, List<String> topics, Map<String, Double> moods, Double pace, int editions) throws Exception {
        ObjectNode r = Dna.M.createObjectNode(); r.put("title", title); r.put("authors", authors); r.put("year", "2020"); r.putArray("tags"); if (editions > 0) r.put("edition_count", editions);
        ObjectNode d = Dna.fresh(Dna.BOOK, key, r);
        ObjectNode l = d.putObject("labelled"); l.put("version", Labels.VERSION); l.put("windows", 12); l.put("kind", kind); l.put("fiction", Labels.fiction(kind));
        ObjectNode book = l.putObject("book"); book.put("kind", kind); book.put("audience", audience); book.put("demand", demand); ArrayNode th = book.putArray("themes"); themes.forEach(th::add);
        ArrayNode tp = l.putArray("topics"); topics.forEach(tp::add);
        ObjectNode m = l.putObject("mood"); moods.forEach(m::put);
        if (pace != null) { ObjectNode s = l.putObject("scales").putObject("pace"); s.put("mean", pace); s.put("sd", 0.3); s.put("agreement", 0.7); }
        l.putObject("signature");
        Dna.write(d);
        return d;
    }

    ObjectNode x, a, b, c, d, e, f;

    void pool() throws Exception {
        x = doc("x", "The Harbour Ledger", "Tamsin Vorle", "novel", "adult", "easy", List.of("harbours", "ledgers", "small towns"), List.of("harbour", "ledgers", "boats"), Map.of("gentle", 0.8, "nostalgic", 0.5), -0.5, 0);
        a = doc("a", "The Quiet Quay", "Idris Quell", "novel", "adult", "easy", List.of("harbours", "fishing", "small towns"), List.of("harbour", "quay"), Map.of("gentle", 0.7, "whimsical", 0.4), -0.3, 0);
        b = doc("b", "Ledger of Steel", "Pell Marrow", "business", "specialist", "demanding", List.of("accounting", "ledgers", "finance"), List.of(), Map.of("dry", 0.8, "technical", 0.6), null, 0);
        c = doc("c", "Blood Harbour", "Rook Vance", "novel", "adult", "moderate", List.of("harbours", "murder", "police"), List.of("harbour", "crime"), Map.of("menacing", 0.9, "suspenseful", 0.7), 0.8, 0);
        d = doc("d", "The Second Ledger", "Tamsin Vorle", "novel", "adult", "easy", List.of("harbours", "ledgers", "small towns"), List.of("harbour"), Map.of("gentle", 0.9), -0.5, 0);
        e = doc("e", "Famous Harbours", "Ada Lorne", "history", "adult", "moderate", List.of("harbours", "ports", "history"), List.of(), Map.of("reflective", 0.6, "scholarly", 0.5), 0.0, 50);
        f = doc("f", "Nothing Alike", "Ivo Strand", "science", "specialist", "demanding", List.of("quantum", "physics"), List.of(), Map.of("technical", 0.9, "dry", 0.5), 0.2, 0);
    }

    static List<String> titles(LikeX.Result r) { return r.rows().stream().map(LikeX.Match::title).toList(); }

    @Test void subjectFirstTheMakerAsideThePopularOneShown() throws Exception {
        pool();
        LikeX.Result r = LikeX.like(x, Dna.labelled(Dna.BOOK), Prefs.load(Dna.BOOK), 4);
        assertEquals(List.of("The Quiet Quay", "Blood Harbour", "Famous Harbours", "Ledger of Steel"), titles(r));
        assertEquals(List.of("The Second Ledger"), r.side().stream().map(LikeX::title).toList(), "the same author is the old mechanism: a side list, never a row");
        assertEquals(6, r.compared());
        LikeX.Match first = r.rows().get(0);
        assertTrue(first.alike().get(0).startsWith("subject (harbours, small, towns, harbour"), first.alike().toString());
        assertTrue(first.alike().contains("kind (both novels)")); assertTrue(first.alike().contains("mood (gentle)")); assertTrue(first.alike().contains("pace and scale"));
        LikeX.Match third = r.rows().get(2);
        assertTrue(third.notes().contains("well known: 50 editions in the catalogue"), third.notes().toString());
        assertTrue(third.unlike().contains("kind (history, not novel)"));
        String text = LikeX.text(r);
        assertTrue(text.startsWith("Books like \"The Harbour Ledger — Tamsin Vorle\" among the 6 labelled on your shelf, nearest first by subject"), text);
        assertTrue(text.contains("- The Quiet Quay — Idris Quell (2020): alike on subject (harbours, small, towns, harbour), kind (both novels), audience (adult), demand (easy), mood (gentle), pace and scale"), text);
        assertTrue(text.contains("Set aside, from the same maker or series (the old way of finding more): The Second Ledger."), text);
        assertTrue(text.contains("Only 6 labelled works are in the store so far besides this one"), text);
        // the popularity line: with two rows and no well-known one among them, the nearest well-known title takes the last seat; with the line off, it does not
        assertEquals(List.of("The Quiet Quay", "Famous Harbours"), titles(LikeX.like(x, Dna.labelled(Dna.BOOK), Prefs.load(Dna.BOOK), 2)));
        Prefs p = Prefs.load(Dna.BOOK); p.set("popularity", "none", "you");
        assertEquals(List.of("The Quiet Quay", "Blood Harbour"), titles(LikeX.like(x, Dna.labelled(Dna.BOOK), Prefs.load(Dna.BOOK), 2)));
        p.set("popularity", "known", "you");
        assertEquals("Famous Harbours", titles(LikeX.like(x, Dna.labelled(Dna.BOOK), Prefs.load(Dna.BOOK), 3)).get(0), "known first when the person asks for that");
    }

    @Test void theSameAskUnderTwoPreferencesFilesDiffersInTheStatedDirection() throws Exception {
        pool();
        assertEquals(List.of("The Quiet Quay", "Blood Harbour", "Famous Harbours", "Ledger of Steel"), titles(LikeX.like(x, Dna.labelled(Dna.BOOK), Prefs.load(Dna.BOOK), 4)));
        Prefs p = Prefs.load(Dna.BOOK);
        p.set("weight.kind", "0", "you"); p.set("weight.demand", "0", "you"); p.set("weight.audience", "0", "you");
        assertEquals("Preferred from now on: subject ledgers.", p.set("prefer.subject", "ledgers", "you"));
        LikeX.Result r = LikeX.like(x, Dna.labelled(Dna.BOOK), Prefs.load(Dna.BOOK), 4);
        assertEquals(List.of("The Quiet Quay", "Blood Harbour", "Ledger of Steel", "Famous Harbours"), titles(r), "the ledgers book moves above the harbour history");
        assertTrue(r.rows().get(2).notes().contains("you prefer subject ledgers"));
        assertEquals("Left out from now on: subject harbours.", p.set("exclude.subject", "harbours", "you"));
        r = LikeX.like(x, Dna.labelled(Dna.BOOK), Prefs.load(Dna.BOOK), 4);
        assertEquals(List.of("Ledger of Steel", "Nothing Alike"), titles(r));
        assertEquals(3, r.leftOut());
        assertEquals("Left out from now on: author Ivo Strand.", p.set("exclude.author", "Ivo Strand", "you"));
        assertEquals(List.of("Ledger of Steel"), titles(LikeX.like(x, Dna.labelled(Dna.BOOK), Prefs.load(Dna.BOOK), 4)));
        assertEquals("Avoided from now on: kind business.", p.set("avoid.kind", "business", "you"));
        assertTrue(LikeX.like(x, Dna.labelled(Dna.BOOK), Prefs.load(Dna.BOOK), 4).rows().get(0).notes().contains("you tend to avoid kind business"));
        assertEquals("1 line(s) dropped.", p.unset("exclude.subject", ""));
    }

    @Test void reactionsReCutTheList() throws Exception {
        pool();
        Reactions.add(Dna.BOOK, "dislike", c, "Blood Harbour", "Rook Vance", x);
        Reactions.add(Dna.BOOK, "seen", a, "The Quiet Quay", "Idris Quell", null);
        LikeX.Result r = LikeX.like(x, Dna.labelled(Dna.BOOK), Prefs.load(Dna.BOOK), 4);
        assertEquals(List.of("The Quiet Quay", "Famous Harbours", "Ledger of Steel", "Nothing Alike"), titles(r));
        assertTrue(r.rows().get(0).notes().contains("you marked this seen"));
        assertEquals(1, r.leftOut());
        assertEquals(1, Reactions.pairs(Dna.BOOK).size(), "a reaction on a row of a like list is a judged pair");
        assertEquals("x", Reactions.pairs(Dna.BOOK).get(0).aboutKey());
        assertEquals("novel", Reactions.latest(Dna.BOOK).get("c").dna().path("kind").asText());
        assertTrue(Reactions.text(Dna.BOOK).startsWith("2 reaction(s): 0 liked, 1 disliked, 1 seen, 0 owned; 1 given on rows of \"like\" lists (the judged pairs)."));
        assertThrows(Exception.class, () -> Reactions.add(Dna.BOOK, "meh", a, "", "", null));
    }

    @Test void theRoamKnobBringsSomeFromFartherDown() throws Exception {
        pool();
        for (int i = 0; i < 20; i++) doc("g" + i, "Far Book " + i, "Nobody " + i, "science", "specialist", "demanding", List.of("stars", "orbit" + i), List.of(), Map.of("technical", 0.9), 0.0, 0);
        Prefs p = Prefs.load(Dna.BOOK); p.set("popularity", "none", "you");
        List<String> near = titles(LikeX.like(x, Dna.labelled(Dna.BOOK), Prefs.load(Dna.BOOK), 4));
        assertEquals(List.of("The Quiet Quay", "Blood Harbour", "Famous Harbours", "Ledger of Steel"), near);
        p.set("roam", "1", "you");
        List<String> roamed = titles(LikeX.like(x, Dna.labelled(Dna.BOOK), Prefs.load(Dna.BOOK), 4));
        assertEquals("The Quiet Quay", roamed.get(0), "the nearest stays");
        assertTrue(roamed.stream().anyMatch(t -> t.startsWith("Far Book")), "some rows come from farther down: " + roamed);
        assertEquals("Back to the default.", p.unset("roam", ""));
        assertEquals(0.2, Prefs.load(Dna.BOOK).roam());
    }

    @Test void authorsAreMatchedBySurnameWithTheInitial() {
        assertTrue(LikeX.sameAuthor("tamsin vorle", "Tamsin Vorle & Idris Quell"));
        assertTrue(LikeX.sameAuthor("vorle", "T. Vorle"));
        assertTrue(LikeX.sameAuthor("t vorle", "Tamsin Vorle"));
        assertFalse(LikeX.sameAuthor("quentin vorle", "Tamsin Vorle"), "a different first initial is another person");
        assertFalse(LikeX.sameAuthor("li", "Li Bai"), "a two-letter surname needs the whole name");
        assertEquals(Set.of("colonial", "virginia"), LikeX.tokens("Colonial Virginia"));
    }
}
