package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A list of things as a starting point: shelved, checked against the shelves, a question per item or a lane per item. */
class ItemsTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void listsInTheirUsualShapesParse() {
        List<Items.Item> plain = Items.parse("""
                # Books to look into
                - The Antikythera Mechanism — Jo Marchant
                2. Longitude - Dava Sobel
                * "Cosmos"
                [ ] Sextant
                - The Antikythera Mechanism — again
                """, null);
        assertEquals(List.of("The Antikythera Mechanism", "Longitude", "Cosmos", "Sextant"), plain.stream().map(Items.Item::name).toList());
        assertEquals("Jo Marchant", plain.get(0).note()); assertEquals("Dava Sobel", plain.get(1).note()); assertEquals("", plain.get(2).note());

        List<Items.Item> table = Items.parse("""
                | Title | Author |
                |---|---|
                | Longitude | Dava Sobel |
                | Cosmos | Carl Sagan |
                """, null);
        assertEquals(List.of("Longitude", "Cosmos"), table.stream().map(Items.Item::name).toList());
        assertEquals("Carl Sagan", table.get(1).note());

        List<Items.Item> csv = Items.parse("isbn,title,year\n978-1,\"Longitude, a story\",1995\n978-2,Cosmos,1980\n", "title");
        assertEquals(List.of("Longitude, a story", "Cosmos"), csv.stream().map(Items.Item::name).toList());
        assertEquals("978-2, 1980", csv.get(1).note());
        assertEquals("Longitude", Items.parse("isbn,title\n978-1,Longitude\n", null).get(0).name(), "no column named: a header column called title is the one");
        assertEquals("978-1", Items.parse("isbn,thing\n978-1,Longitude\n", null).get(0).name(), "no column named and no title column: the first");

        assertEquals("Longitude: what it is, who made or wrote it, what it is for, and what is known about it (Dava Sobel)", Items.question(new Items.Item("Longitude", "Dava Sobel"), ""));
        assertEquals("Is Cosmos still in print?", Items.question(new Items.Item("Cosmos", ""), "Is {item} still in print?"));
        assertEquals("Cosmos: first edition price", Items.question(new Items.Item("Cosmos", ""), "first edition price"));
    }

    @Test
    void aListIsShelvedCheckedAndFiled(@TempDir Path home) throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        // the shelves already hold something on one of the items
        RawCapture.capture(store, "https://example.org/sobel", "Longitude by Dava Sobel tells how John Harrison built the marine chronometer.", "Longitude — Dava Sobel", "test", "");
        Path f = home.resolve("books.md"); Files.writeString(f, "- Longitude — Dava Sobel\n- Cosmos — Carl Sagan\n- The Antikythera Mechanism\n");
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode().put("path", f.toString()).put("lens", "{item}: its main argument and its reception");
        a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        ObjectNode r = p.items(a);
        assertEquals(3, r.path("items_found").asInt()); assertEquals("books", r.path("title").asText());
        assertEquals(1, r.path("held").asInt(), r.toString()); assertEquals(2, r.path("not_held").asInt());
        assertFalse(r.path("items").get(0).path("held").isNull(), "Longitude is held: " + r.path("items").get(0));
        assertTrue(r.path("items").get(1).path("held").isNull());
        assertEquals(3, r.path("questions_filed").asInt());
        assertEquals("Cosmos: its main argument and its reception (Carl Sagan)", r.path("items").get(1).path("question").asText());
        // the list is on the shelves, as a list
        Path raw = store.rawDir().resolve(r.path("raw").asText());
        String[] rr = RawCapture.read(raw);
        assertTrue(rr[0].startsWith("list://") && rr[2].contains("- Cosmos — Carl Sagan"), rr[0] + rr[2]);
        assertEquals("lists", RawCapture.collectionOf(raw));
        // the questions are the person's, from the list; twice files nothing new
        List<Frontier.Line> open = Frontier.read(store);
        assertEquals(3, open.size());
        assertEquals("person", Frontier.typeOf(open.get(0))); assertTrue(open.get(0).kind().contains("from a list"), open.get(0).kind());
        assertEquals(0, p.items(a.deepCopy()).path("questions_filed").asInt());
        assertEquals(3, p.items(a.deepCopy()).path("questions_already_open").asInt());
        assertEquals(0, store.scanFindings().findings().size(), "nothing became a finding");
    }

    @Test
    void runsGoOutInBatchesWithALanePerItemAndNoneOnlyLooks(@TempDir Path home) throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        StringBuilder sb = new StringBuilder(); for (int i = 1; i <= 11; i++) sb.append("- Item number ").append(i).append('\n');
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode none = M.createObjectNode().put("text", sb.toString()).put("as", "none").put("title", "Eleven");
        none.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        ObjectNode rn = p.items(none);
        assertEquals(11, rn.path("taken").asInt()); assertEquals(0, rn.path("questions_filed").asInt()); assertEquals(0, rn.path("jobs").size());
        assertEquals(0, Frontier.read(store).size(), "as=none files nothing");
        assertTrue(rn.path("summary").asText().contains("nothing filed"), rn.path("summary").asText());
        ObjectNode runs = none.deepCopy().put("as", "runs");
        ObjectNode rr = p.items(runs);
        assertEquals(2, rr.path("jobs").size(), "11 items, batches of 8: " + rr.path("jobs"));
        var jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        var first = jobs.active().stream().filter(j -> j.path("job_id").asText().equals(rr.path("jobs").get(0).asText())).findFirst().orElseThrow();
        assertEquals(8, first.path("args").path("sub_questions").size(), "a lane per item");
        assertTrue(first.path("args").path("sub_questions").get(0).asText().startsWith("Item number 1:"));
        assertTrue(first.path("args").path("question").asText().contains("Eleven"));
        assertEquals(0, Frontier.read(store).size(), "runs do not also file questions");
        // a limit takes the first few and says how many remain
        ObjectNode lim = none.deepCopy().put("limit", 4);
        assertEquals(4, p.items(lim).path("taken").asInt()); assertEquals(7, p.items(lim).path("remaining").asInt());
        // a stranger may paste a list but not name a path; an empty list is refused
        Patrons.setDefault(store, Patrons.Level.write);
        ObjectNode stranger = M.createObjectNode().put("path", home.resolve("x.txt").toString());
        stranger.putObject("patron").put("did", "did:key:zFriend").put("name", "f").put("runtime", "mcp");
        assertTrue(assertThrows(ProtocolError.class, () -> p.items(stranger)).getMessage().contains("keeper"));
        ObjectNode empty = M.createObjectNode().put("text", "# nothing here\n\n");
        empty.putObject("patron").put("did", "did:key:zFriend").put("name", "f").put("runtime", "mcp");
        assertTrue(assertThrows(ProtocolError.class, () -> p.items(empty)).getMessage().contains("No items"));
        assertTrue(Librarian.TOOLS.contains("library_items"));
        boolean served = false; for (var t : org.researchzosho.mcp.McpServer.allTools()) if (t.path("name").asText().equals("library_items")) served = true;
        assertTrue(served);
    }
}
