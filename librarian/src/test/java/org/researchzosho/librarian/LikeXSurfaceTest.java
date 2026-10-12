package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.dna.Dna;
import org.researchzosho.librarian.dna.Labels;
import org.researchzosho.librarian.dna.Prefs;
import org.researchzosho.librarian.dna.Reactions;
import org.researchzosho.librarian.dna.ShelfDna;
import org.researchzosho.librarian.profiles.BooksProfile;
import org.researchzosho.tools.MyShelfTool;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** "Like X", the preferences and the reactions through the protocol, the CLI's tool and the books profile's push. */
class LikeXSurfaceTest {

    static final ObjectMapper M = new ObjectMapper();

    @AfterEach void reset() { Shelf.override = null; Dna.useDir(null); Prefs.useDir(null); }

    static ObjectNode patron(String op) { ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "p").put("runtime", "cli"); a.put("op", op); return a; }

    /** The shelf with the sample book labelled by the stub, and a second labelled document that is not the same author. */
    void labelledShelf(Path tmp) throws Exception {
        Shelf.override = ShelfDnaTest.shelfWithAFile(tmp.resolve("calibre"));
        Dna.useDir(tmp.resolve("dna")); Prefs.useDir(tmp.resolve("prefs"));
        Shelf.Book ledger = Shelf.books().stream().filter(b -> b.id() == 7).findFirst().orElseThrow();
        ShelfDna.label(ledger, ShelfDnaTest.stub(new AtomicInteger()), null);
        ObjectNode r = M.createObjectNode(); r.put("title", "The Quiet Quay"); r.put("authors", "Idris Quell"); r.put("year", "2019"); r.putArray("tags").add("Travel");
        ObjectNode d = Dna.fresh(Dna.BOOK, "quay0000quay0000", r);
        ObjectNode l = d.putObject("labelled"); l.put("version", Labels.VERSION); l.put("windows", 12); l.put("kind", "novel"); l.put("fiction", true);
        l.putObject("book").put("audience", "adult").put("demand", "easy").putArray("themes").add("harbours").add("fishing");
        l.putArray("topics").add("harbour").add("quay"); l.putObject("mood").put("gentle", 0.7); l.putObject("scales").putObject("pace").put("mean", -0.3); l.putObject("signature");
        Dna.write(d);
    }

    @Test void theProtocolListsTheBooksLikeOne(@TempDir Path tmp) throws Exception {
        labelledShelf(tmp);
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ObjectNode a = patron("like"); a.put("title", "The Harbour Ledger"); a.put("label", false);
        ObjectNode r = new LibraryProtocol(store).myShelf(a);
        assertTrue(r.path("found").asBoolean());
        assertEquals("1 book(s) like \"The Harbour Ledger\" among the 1 labelled on your shelf, the author's own works set aside.", r.path("summary").asText());
        assertEquals("The Quiet Quay", r.path("rows").get(0).path("title").asText());
        assertTrue(r.path("rows").get(0).path("line").asText().contains("alike on subject (harbour"), r.path("rows").get(0).path("line").asText());
        assertTrue(r.path("text").asText().startsWith("Books like \"The Harbour Ledger — Tamsin Vorle\" among the 1 labelled"));
        a.put("title", "The Salt Road");
        assertEquals("\"The Salt Road: A Walk Across Hokkaido\" is not labelled yet: the shelf has no EPUB file for it, so there is no text to read.", new LibraryProtocol(store).myShelf(a).path("summary").asText());
        a.put("title", "No Such");
        assertEquals("No book on the shelf has the title \"No Such\".", new LibraryProtocol(store).myShelf(a).path("summary").asText());
        // the run's tool says the same
        ObjectNode t = M.createObjectNode(); t.put("kind", "like"); t.put("title", "The Harbour Ledger");
        String said = new MyShelfTool(store).execute(t);
        assertTrue(said.startsWith("Books like \"The Harbour Ledger — Tamsin Vorle\""), said);
        assertEquals("ERROR: title is needed: a book on the shelf.", new MyShelfTool(store).execute(M.createObjectNode().put("kind", "like")));
    }

    @Test void thePreferencesAndTheReactionsThroughTheProtocol(@TempDir Path tmp) throws Exception {
        labelledShelf(tmp);
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibraryProtocol p = new LibraryProtocol(store);
        assertEquals("No preferences file yet; the defaults apply.", p.prefs(patron("show")).path("summary").asText());
        ObjectNode set = patron("set"); set.put("name", "prefer.mood"); set.put("value", "gentle");
        assertEquals("Preferred from now on: mood gentle.", p.prefs(set).path("summary").asText());
        ObjectNode r = p.prefs(patron("draft"));
        assertTrue(r.path("summary").asText().startsWith("A preferences file drafted from your shelf:"), r.path("summary").asText());
        assertTrue(r.path("text").asText().contains("- prefer mood gentle — from you (p), "), r.path("text").asText());
        ObjectNode ex = patron("export"); ex.put("path", tmp.resolve("prefs-out.json").toString());
        assertTrue(p.prefs(ex).path("summary").asText().startsWith("The preferences file is written to "));
        ObjectNode im = patron("import"); im.put("path", tmp.resolve("prefs-out.json").toString());
        assertTrue(p.prefs(im).path("summary").asText().contains("came in from prefs-out.json"));
        ObjectNode react = patron(""); react.put("reaction", "like"); react.put("title", "The Quiet Quay"); react.put("about", "The Harbour Ledger");
        String said = p.react(react).path("summary").asText();
        assertTrue(said.startsWith("Written down: like — The Quiet Quay by Idris Quell, on the list like \"The Harbour Ledger\". 1 reaction(s) so far"), said);
        assertEquals("quay0000quay0000", Reactions.all(Dna.BOOK).get(0).key(), "a row of the store that is not on the shelf is found by its title");
        react.put("title", "The Harbour Ledger"); react.remove("about"); react.put("reaction", "seen");
        assertTrue(p.react(react).path("summary").asText().startsWith("Written down: seen — The Harbour Ledger by Tamsin Vorle. 2 reaction(s) so far"));
        assertEquals(1, Reactions.pairs(Dna.BOOK).size());
        react.put("reaction", "meh");
        assertThrows(ProtocolError.class, () -> p.react(react));
        // a reader may look, only the owner may write
        ObjectNode reader = patron("set"); reader.putObject("patron").put("did", "did:key:z6MkReader").put("name", "r").put("runtime", "mcp"); reader.put("name", "roam"); reader.put("value", "0.5");
        assertThrows(ProtocolError.class, () -> p.prefs(reader));
    }

    @Test void theBooksProfilePushesTheListWhenTheAskNamesABookOnTheShelf(@TempDir Path tmp) throws Exception {
        assertEquals("The Harbour Ledger", BooksProfile.likeTitle("what should I read next, like The Harbour Ledger?"));
        assertEquals("The Harbour Ledger", BooksProfile.likeTitle("books similar to \"The Harbour Ledger\" by Tamsin Vorle, but shorter"));
        assertEquals("Anathem", BooksProfile.likeTitle("more like Anathem."));
        assertEquals("", BooksProfile.likeTitle("what should I read next in space opera"));
        assertEquals("", BooksProfile.likeTitle("something like that one"));
        labelledShelf(tmp);
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Prefs.load(Dna.BOOK).set("words", "quiet books about places", "you");
        String ev = new BooksProfile().runEvidence(store, new Researcher.Ask("books like The Harbour Ledger", "", 0, List.of(), "", List.of(), 0, List.of(), List.of()), null);
        assertTrue(ev.startsWith("THE PERSON'S PREFERENCES FILE (their own lines; the table honours them): In their words: quiet books about places."), ev);
        assertTrue(ev.contains("BOOKS ON THE SHELF LIKE \"The Harbour Ledger\""), ev);
        assertTrue(ev.contains("- The Quiet Quay — Idris Quell (2019): alike on subject (harbour"), ev);
    }
}
