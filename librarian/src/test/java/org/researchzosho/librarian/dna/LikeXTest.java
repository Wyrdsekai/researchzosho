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
 * "Like X" as settled and as cut on the first judged lists: subject first, as phrases, the back matter's words no subject; then kind,
 * audience, demand, setting and era; mood and the scales at a small weight; the same kind first and another kind only on a shared
 * subject; at most two rows an author, the rest set aside; the same author as X set aside into a side list; the preferences file's
 * weights, exclusions and prefer lines moving the list in the stated direction; a dislike leaving a row out and a seen marking it; one
 * well-known title in every list unless the person says otherwise.
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

    /** Where and when a document's book is set: the labelled setting, the catalogue's places and times. */
    static ObjectNode set(ObjectNode d, String setting, List<String> places, List<String> times) throws Exception {
        ((ObjectNode) d.path("labelled").path("book")).put("setting", setting);
        ObjectNode r = (ObjectNode) d.path("record");
        ArrayNode p = r.putArray("places"); places.forEach(p::add); ArrayNode t = r.putArray("times"); times.forEach(t::add);
        Dna.write(d); return d;
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
    static LikeX.Result like(ObjectNode x, int limit) { return LikeX.like(x, Dna.labelled(Dna.BOOK), Prefs.load(Dna.BOOK), limit); }

    @Test void subjectFirstTheMakerAsideThePopularOneShown() throws Exception {
        pool();
        LikeX.Result r = like(x, 4);
        assertEquals(List.of("The Quiet Quay", "Blood Harbour", "Famous Harbours", "Ledger of Steel"), titles(r));
        assertEquals(List.of("The Second Ledger"), r.side().stream().map(LikeX::title).toList(), "the same author is the old mechanism: a side list, never a row");
        assertEquals(6, r.compared());
        LikeX.Match first = r.rows().get(0);
        assertTrue(first.alike().get(0).startsWith("subject (harbours, small towns, harbour"), first.alike().toString());
        assertTrue(first.alike().contains("kind (both novels)")); assertTrue(first.alike().contains("mood (gentle)")); assertTrue(first.alike().contains("pace and scale"));
        LikeX.Match third = r.rows().get(2);
        assertTrue(third.notes().contains("well known: 50 editions in the catalogue"), third.notes().toString());
        assertTrue(third.unlike().contains("kind (history, not novel)"));
        String text = LikeX.text(r);
        assertTrue(text.startsWith("Books like \"The Harbour Ledger — Tamsin Vorle\" among the 6 labelled on your shelf, nearest first by subject"), text);
        assertTrue(text.contains("- The Quiet Quay — Idris Quell (2020): alike on subject (harbours, small towns, harbour), kind (both novels), audience (adult), demand (easy), mood (gentle), pace and scale"), text);
        assertTrue(text.contains("Set aside, from the same maker or series (the old way of finding more): The Second Ledger."), text);
        assertTrue(text.contains("Only 6 labelled works are in the store so far besides this one"), text);
        assertTrue(r.more().isEmpty(), "no author has more than two rows here");
        // the popularity line: with two rows and no well-known one among them, the nearest well-known title takes the last seat; with the line off, it does not
        assertEquals(List.of("The Quiet Quay", "Famous Harbours"), titles(like(x, 2)));
        Prefs p = Prefs.load(Dna.BOOK); p.set("popularity", "none", "you");
        assertEquals(List.of("The Quiet Quay", "Blood Harbour"), titles(like(x, 2)));
        // known first when the person asks for that — within the same kind: a well-known history never climbs above the novels
        p.set("popularity", "known", "you");
        doc("h", "Harbour Lights", "Mira Sund", "novel", "adult", "easy", List.of("harbours", "lighthouses"), List.of(), Map.of("gentle", 0.6), -0.4, 80);
        List<String> known = titles(like(x, 3));
        assertEquals("Harbour Lights", known.get(0), known.toString());
        assertFalse(known.contains("Famous Harbours"), "the history stays below every novel: " + known);
    }

    @Test void theSameAskUnderTwoPreferencesFilesDiffersInTheStatedDirection() throws Exception {
        pool();
        assertEquals(List.of("The Quiet Quay", "Blood Harbour", "Famous Harbours", "Ledger of Steel"), titles(like(x, 4)));
        Prefs p = Prefs.load(Dna.BOOK);
        p.set("weight.kind", "0", "you"); p.set("weight.demand", "0", "you"); p.set("weight.audience", "0", "you");
        assertEquals("Preferred from now on: subject ledgers.", p.set("prefer.subject", "ledgers", "you"));
        LikeX.Result r = like(x, 4);
        assertEquals(List.of("The Quiet Quay", "Blood Harbour", "Ledger of Steel", "Famous Harbours"), titles(r), "the ledgers book moves above the harbour history");
        assertTrue(r.rows().get(2).notes().contains("you prefer subject ledgers"));
        assertEquals("Left out from now on: subject harbours.", p.set("exclude.subject", "harbours", "you"));
        r = like(x, 4);
        assertEquals(List.of("Ledger of Steel"), titles(r), "the science book of another kind shares no subject: never a row, so the list ends short");
        assertEquals(3, r.leftOut());
        assertEquals("Left out from now on: author Ivo Strand.", p.set("exclude.author", "Ivo Strand", "you"));
        assertEquals(List.of("Ledger of Steel"), titles(like(x, 4)));
        assertEquals("Avoided from now on: kind business.", p.set("avoid.kind", "business", "you"));
        assertTrue(like(x, 4).rows().get(0).notes().contains("you tend to avoid kind business"));
        assertEquals("1 line(s) dropped.", p.unset("exclude.subject", ""));
    }

    @Test void reactionsReCutTheList() throws Exception {
        pool();
        Reactions.add(Dna.BOOK, "dislike", c, "Blood Harbour", "Rook Vance", x);
        Reactions.add(Dna.BOOK, "seen", a, "The Quiet Quay", "Idris Quell", null);
        LikeX.Result r = like(x, 4);
        assertEquals(List.of("The Quiet Quay", "Famous Harbours", "Ledger of Steel"), titles(r));
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
        for (int i = 0; i < 20; i++) doc("g" + i, "Far Book " + i, "Nobody " + i, "novel", "specialist", "demanding", List.of("stars", "orbit" + i), List.of(), Map.of("technical", 0.9), 0.0, 0);
        Prefs p = Prefs.load(Dna.BOOK); p.set("popularity", "none", "you");
        List<String> near = titles(like(x, 4));
        assertEquals(List.of("The Quiet Quay", "Blood Harbour", "Far Book 0", "Far Book 1"), near, "the same kind fills the list before any other kind");
        p.set("roam", "1", "you");
        List<String> roamed = titles(like(x, 4));
        assertEquals("The Quiet Quay", roamed.get(0), "the nearest stays");
        assertTrue(roamed.stream().anyMatch(t -> t.startsWith("Far Book") && !t.endsWith(" 0") && !t.endsWith(" 1")), "some rows come from farther down: " + roamed);
        assertEquals("Back to the default.", p.unset("roam", ""));
        assertEquals(0.2, Prefs.load(Dna.BOOK).roam());
    }

    @Test void subjectsArePhrasesAndTheBackMatterIsNoSubject() throws Exception {
        ObjectNode x2 = doc("x2", "Our Last Machine", "Ione Barr", "science", "adult", "moderate", List.of("artificial intelligence", "existential risk"), List.of("index", "bibliography", "artificial intelligence"), Map.of(), null, 0);
        ObjectNode p = doc("p", "Stars for Everyone", "Noor Vale", "science", "adult", "moderate", List.of("science", "stars"), List.of("index", "bibliography", "science"), Map.of(), null, 0);
        ObjectNode q = doc("q", "Minds of Metal", "Teo Rask", "science", "adult", "moderate", List.of("artificial intelligence", "robots"), List.of("machine learning"), Map.of(), null, 0);
        assertEquals(Set.of("artificial intelligence", "existential risk"), LikeX.subjects(x2), "the index and the bibliography are parts of a book, not subjects; a phrase stays one");
        assertFalse(LikeX.subjects(p).contains("index"));
        LikeX.Result r = like(x2, 5);
        assertEquals(List.of("Minds of Metal", "Stars for Everyone"), titles(r), "the book that shares the phrase, not the one that shares the back matter");
        assertEquals("subject (artificial intelligence)", r.rows().get(0).alike().get(0));
        assertTrue(r.rows().get(1).unlike().contains("subject"), r.rows().get(1).unlike().toString());
        assertEquals(List.of("artificial intelligence", "moral and ethical aspects"), LikeX.phrases("Artificial intelligence -- Moral and ethical aspects"), "a catalogue heading's parts");
        assertEquals(List.of(), LikeX.phrases("Fiction, general"), "form words are no subject");
        assertEquals("", LikeX.phrase("Reading Level-Grade 7"));
        assertEquals(Set.of("artificial", "intelligence", "existential", "risk"), LikeX.subjectTokens(x2), "the preferences still match on words");
    }

    @Test void anotherKindStandsBelowEveryRowOfTheSameKindAndNeverOnMoodAlone() throws Exception {
        ObjectNode x3 = doc("x3", "The Long Trench", "Ewan Sorrel", "novel", "adult", "moderate", List.of("war", "soldiers"), List.of(), Map.of("menacing", 0.8), 0.2, 0);
        doc("r1", "Mud and Wire", "Pia Lund", "novel", "adult", "moderate", List.of("war", "trenches"), List.of(), Map.of("menacing", 0.8), 0.2, 0);
        doc("r2", "The Hedge Walk", "Ola Finch", "novel", "adult", "moderate", List.of("gardens", "hedges"), List.of(), Map.of("gentle", 0.8), -0.5, 0);
        doc("h1", "Generals of the Somme", "Rab Mure", "history", "adult", "moderate", List.of("war", "generals"), List.of(), Map.of("reflective", 0.7), 0.0, 0);
        doc("m1", "Letters at Dusk", "Vi Marsh", "stories", "adult", "moderate", List.of("love", "letters"), List.of(), Map.of("menacing", 0.9), 0.2, 0);
        LikeX.Result r = like(x3, 10);
        assertEquals(List.of("Mud and Wire", "The Hedge Walk", "Generals of the Somme"), titles(r), "the novels first, the history below them on its shared subject, the stories alike on mood alone never");
        assertTrue(r.rows().get(2).unlike().contains("kind (history, not novel)"));
        assertEquals(List.of("Mud and Wire", "The Hedge Walk"), titles(like(x3, 2)), "a short list is the same kind only");
        // a list ends short when the shelf has no more alike works, instead of filling the rows asked for from another kind
        ObjectNode x5 = doc("x5", "River Psalms", "Ana Teal", "poetry", "adult", "easy", List.of("rivers"), List.of(), Map.of("lyrical", 0.9), null, 0);
        doc("p1", "Bridges at Dawn", "Kit Rowe", "poetry", "adult", "easy", List.of("rivers", "bridges"), List.of(), Map.of("lyrical", 0.8), null, 0);
        doc("n1", "The High Pass", "Dov Lang", "novel", "adult", "easy", List.of("mountains"), List.of(), Map.of("lyrical", 0.8), null, 0);
        assertEquals(List.of("Bridges at Dawn"), titles(like(x5, 5)));
    }

    @Test void atMostTwoRowsAnAuthorTheRestSetAside() throws Exception {
        ObjectNode x6 = doc("x6", "Leading Quietly", "Hal Orne", "business", "adult", "easy", List.of("leadership", "coaching"), List.of(), Map.of("warm", 0.8), null, 0);
        for (int i = 1; i <= 4; i++) doc("w" + i, "Coaching Workbook " + i, "Coach Pell", "business", "adult", "easy", List.of("leadership", "coaching", "workbook " + i), List.of(), Map.of("warm", 0.8), null, 0);
        doc("z", "The Patient Manager", "Someone Else", "business", "adult", "easy", List.of("leadership"), List.of(), Map.of("warm", 0.7), null, 0);
        LikeX.Result r = like(x6, 10);
        assertEquals(2, titles(r).stream().filter(t -> t.startsWith("Coaching Workbook")).count(), "two rows an author, no more: " + titles(r));
        assertTrue(titles(r).contains("The Patient Manager"), titles(r).toString());
        assertEquals(List.of("Coach Pell"), List.copyOf(r.more().keySet()));
        assertEquals(List.of("Coaching Workbook 3", "Coaching Workbook 4"), r.more().get("Coach Pell").stream().map(LikeX::title).toList());
        assertTrue(LikeX.text(r).contains("More by the makers above, set aside so the list spreads: Coach Pell: Coaching Workbook 3, Coaching Workbook 4."), LikeX.text(r));
    }

    @Test void theSettingAndTheEraCountWhenBothSidesKnowThem() throws Exception {
        ObjectNode x7 = set(doc("x7", "Suspect Y", "Kei Hono", "novel", "adult", "moderate", List.of("investigation", "mystery", "identity"), List.of(), Map.of(), null, 0), "Tokyo, 1990s", List.of("Japan"), List.of("20th century"));
        set(doc("c1", "The Osaka Alibi", "Rin Sato", "novel", "adult", "moderate", List.of("investigation", "mystery"), List.of(), Map.of(), null, 0), "Osaka, 1990s", List.of("Japan"), List.of());
        set(doc("c2", "Cold Stockholm", "Per Dahl", "novel", "adult", "moderate", List.of("investigation", "mystery", "identity"), List.of(), Map.of(), null, 0), "Stockholm, 2000s", List.of("Sweden"), List.of());
        assertEquals(Set.of("japan", "tokyo"), LikeX.places(x7)); assertEquals(Set.of("20th century", "1990s"), LikeX.eras(x7));
        LikeX.Result r = like(x7, 5);
        assertEquals(List.of("Cold Stockholm", "The Osaka Alibi"), titles(r), "at the defaults the subject alone orders: the setting and the era weigh 0 (judged 2026-10-13, they pushed subject-right rows out)");
        assertTrue(r.rows().get(1).alike().contains("setting (japan)") && r.rows().get(1).alike().contains("era (20th century, 1990s)"), r.rows().get(1).alike().toString());
        assertTrue(r.rows().get(0).unlike().contains("setting (sweden, stockholm)") && r.rows().get(0).unlike().contains("era (2000s, 21st century)"), r.rows().get(0).unlike().toString());
        assertTrue(LikeX.text(r).contains("nearest first by subject, then kind, audience, demand and mood, then pace and scale;"), LikeX.text(r));
        Prefs p = Prefs.load(Dna.BOOK); p.set("weight.setting", "0.5", "you"); p.set("weight.era", "0.5", "you");
        LikeX.Result w = like(x7, 5);
        assertEquals(List.of("The Osaka Alibi", "Cold Stockholm"), titles(w), "with a weight on them, the same country and decade outweigh one more shared subject word");
        assertTrue(LikeX.text(w).contains("nearest first by subject, then kind, audience, demand, setting, era and mood, then pace and scale;"), LikeX.text(w));
        assertEquals("20th century", LikeX.century(1990)); assertEquals("21st century", LikeX.century(2005)); assertEquals("19th century", LikeX.century(1868));
    }

    @Test void theListSaysWhenNothingSharesASubject() throws Exception {
        ObjectNode x8 = doc("x8", "The Clock in the Wall", "Mae Tully", "novel", "children", "easy", List.of("time slip"), List.of(), Map.of("whimsical", 0.8), null, 0);
        doc("n2", "The Hedge Walk II", "Ola Finch", "novel", "adult", "moderate", List.of("gardens"), List.of(), Map.of("gentle", 0.8), null, 0);
        doc("n3", "The High Pass II", "Dov Lang", "novel", "adult", "easy", List.of("mountains"), List.of(), Map.of("lyrical", 0.8), null, 0);
        LikeX.Result r = like(x8, 5);
        assertEquals(2, r.rows().size());
        assertTrue(r.note().contains("None of these shares a subject with it: the store holds nothing nearer yet."), r.note());
        assertTrue(LikeX.text(r).contains("None of these shares a subject"));
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
