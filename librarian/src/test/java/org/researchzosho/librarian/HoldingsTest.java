package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** A big list is shelved whole, selected by match and sample, and answers "do I own this" exactly. */
class HoldingsTest {

    static final ObjectMapper M = new ObjectMapper();
    static Supplier<Researcher.Drive> drives;
    @BeforeAll static void noModel() { drives = Explain.DRIVES; Explain.DRIVES = () -> null; }
    @AfterAll static void restore() { Explain.DRIVES = drives; }

    static ObjectNode patron(ObjectNode a) { a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli"); return a; }

    @Test
    void theWholeListIsShelvedTheSelectionIsLookedAtAndHoldingsAnswersExactly(@TempDir Path home) throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        StringBuilder csv = new StringBuilder("title,authors,year,tags\n");
        csv.append("\"A Wizard of Earthsea\",Ursula K. Le Guin,1968,Fantasy\n");
        csv.append("The Spy Who Came in from the Cold,John Le Carré,1963,Spy\n");
        for (int i = 1; i <= 300; i++) csv.append("Filler volume ").append(i).append(",Someone,").append(1900 + (i % 100)).append(",").append(i % 2 == 0 ? "Sci-Fi" : "Poetry").append('\n');
        Path list = home.resolve("shelf.csv"); Files.writeString(list, csv.toString());
        LibraryProtocol p = new LibraryProtocol(store);
        // as=none: all 302 go on the shelves; 200 are looked at
        ObjectNode r = p.items(patron(M.createObjectNode().put("path", list.toString()).put("as", "none")));
        assertEquals(302, r.path("items_found").asInt()); assertEquals(200, r.path("taken").asInt()); assertEquals(102, r.path("remaining").asInt());
        assertTrue(r.path("summary").asText().contains("(all 302 are saved in the library)"), r.path("summary").asText());
        String[] raw = RawCapture.read(store.rawDir().resolve(r.path("raw").asText()));
        assertTrue(raw[2].contains("302 entries; the whole list is lists/"), raw[2].substring(0, 120));
        String listFile = Items.listFileName(store, store.rawDir().resolve(r.path("raw").asText()));
        assertFalse(listFile.isEmpty());
        assertEquals(302, Items.parse(Files.readString(store.listsDir().resolve(listFile)), null).size(), "the list in lists/ holds every entry, not the first 200; raw would cut a long one at 400k characters");
        // the same title in two editions is two entries
        assertEquals(2, Items.parse("title,authors\nUlysses,James Joyce\nUlysses,Someone Else\nUlysses,James Joyce\n", null).size());
        // match: only the lines holding the text; sample: that many of them at random; then the limit
        ObjectNode m = p.items(patron(M.createObjectNode().put("path", list.toString()).put("as", "none").put("match", "Sci-Fi")));
        assertEquals(150, m.path("matched").asInt()); assertEquals(150, m.path("taken").asInt());
        assertTrue(m.path("items").get(0).path("item").asText().startsWith("Filler volume 2"), m.path("items").get(0).toString());
        ObjectNode two = M.createObjectNode().put("path", list.toString()).put("as", "none").put("sample", 5).put("seed", 7);
        two.putArray("match").add("Sci-Fi").add("199");
        ObjectNode s = p.items(patron(two));
        assertEquals(5, s.path("taken").asInt()); assertTrue(s.path("matched").asInt() < 150 && s.path("matched").asInt() > 5, "matched counts before the sample: " + s.path("matched"));
        for (var it : s.path("items")) assertTrue(it.path("note").asText().contains("Sci-Fi") && it.path("note").asText().contains("199"), it.toString());
        assertTrue(s.path("summary").asText().contains("5 item(s) of " + s.path("matched").asInt() + " matched, a sample"), s.path("summary").asText());
        // holdings: every word must appear; accents and case do not matter; the name-only match ranks first
        List<Holdings.Match> h = Holdings.find(store, "wizard earthsea le guin", 10, null);
        assertEquals(1, h.size()); assertEquals("A Wizard of Earthsea", h.get(0).item()); assertTrue(h.get(0).list().startsWith("shelf"), h.get(0).list());
        assertEquals(1, Holdings.find(store, "le carre spy", 10, null).size(), "Le Carré matches le carre");
        assertEquals(1, Holdings.find(store, "Spy Who Came Cold", 10, null).size());
        assertTrue(Holdings.find(store, "dune herbert", 10, null).isEmpty());
        assertTrue(Holdings.find(store, "wizard dune", 10, null).isEmpty(), "every word, not any word");
        assertEquals(302, Holdings.size(store));
        // through the protocol, the chat and the runs' tool
        ObjectNode q = p.holdings(patron(M.createObjectNode().put("query", "earthsea")));
        assertEquals(1, q.path("count").asInt()); assertEquals("A Wizard of Earthsea", q.path("matches").get(0).path("item").asText());
        assertTrue(q.path("summary").asText().startsWith("1 entry matches"), q.path("summary").asText());
        ObjectNode none = p.holdings(patron(M.createObjectNode().put("query", "dune")));
        assertTrue(none.path("summary").asText().contains("No list entry matches \"dune\" (302 entries in all)."), none.path("summary").asText());
        assertThrows(ProtocolError.class, () -> p.holdings(patron(M.createObjectNode().put("query", "  "))));
        var tool = new Researcher.HoldingsTool(store);
        String out = tool.execute(M.createObjectNode().put("query", "earthsea le guin"));
        assertTrue(out.contains("1. A Wizard of Earthsea — Ursula K. Le Guin, 1968, Fantasy") && out.contains("<<<HOLDINGS"), out);
        assertTrue(tool.execute(M.createObjectNode().put("query", "dune")).startsWith("the person's lists hold nothing matching: dune"));
        // a second list: an item that names a book of the first is held, found by the exact lookup, not a search guess
        Path second = home.resolve("wish.txt"); Files.writeString(second, "- A Wizard of Earthsea\n- Dune\n");
        ObjectNode w = p.items(patron(M.createObjectNode().put("path", second.toString()).put("as", "none")));
        assertEquals(1, w.path("held").asInt(), w.toString());
        assertEquals("lists/" + listFile, w.path("items").get(0).path("held").asText(), "held by the shelf list");
        assertTrue(w.path("items").get(1).path("held").isNull());
        assertEquals(304, Holdings.size(store));
    }
}
