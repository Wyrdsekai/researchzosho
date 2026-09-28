package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.NoLiveChecks;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.PageCheck;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A page the person gives (an address to add, a reading list, bookmarks, a url read into a list) is their own act: when no model answers
 * its check, it is saved all the same and marked not checked yet; the always-dropped question is asked of it when a model answers, and a
 * page the check then finds is removed from the shelves and the index, and the person is told. Placeholder pages and a stub judge only: a
 * marker word stands for what the check looks for.
 */
class UncheckedPagesTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String ORDINARY = "https://example.org/placeholder-ordinary", MARKED = "https://example.org/placeholder-marked",
            OTHER = "https://example.org/placeholder-other", LIST = "https://example.org/placeholder-list";

    static final Map<String, String> PAGES = Map.of(
            ORDINARY, page("An ordinary placeholder page", "The placeholder gears were cut by hand, says the page about quillwheels."),
            MARKED, page("A placeholder page", "MARKER-CHILD placeholder text about zanderbolts that stands for a page of that kind."),
            OTHER, page("Another placeholder page", "A second ordinary placeholder page about the tidemill."),
            LIST, "Placeholder item one\nPlaceholder item two\n");

    static String page(String title, String body) {
        return "<html><head><title>" + title + "</title></head><body><main><p>" + body + " " + "More placeholder words to make it a page. ".repeat(8) + "</p></main></body></html>";
    }

    /** The stub judge once a model answers: yes to the always-dropped question when the text carries the marker. */
    static ContentJudge markers() {
        return new ContentJudge(null, messages -> {
            String c = messages.get(0).path("content").asText();
            return c.contains(PageCheck.Category.CHILD.question()) && c.contains("MARKER-CHILD") ? "yes" : "no";
        });
    }

    @TempDir Path home;
    LibraryStore store;

    @BeforeEach void pages() throws Exception {
        store = new LibraryStore(home.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        PageCheck.useGetter((url, timeout, lists) -> {
            String body = PAGES.get(url);
            if (body == null) return new Fetch.Result(url, 404, new byte[0], "text/html");
            return new Fetch.Result(url, 200, body.getBytes(StandardCharsets.UTF_8), url.equals(LIST) ? "text/plain" : "text/html");
        });
        ContentJudge.use(NoLiveChecks.NOTHING);   // no model answers the check
    }

    @AfterEach void back() { PageCheck.useGetter(null); ContentJudge.use(null); }

    static ObjectNode person(ObjectNode a) { a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli"); return a; }

    boolean indexed(String word) throws Exception { return !new LibrarianIndex(store, Embeddings.none()).search(word, 5).isEmpty(); }

    @Test
    void aPageThePersonGivesIsSavedWhenNoModelAnswersAndCheckedWhenOneDoes() throws Exception {
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode added = p.add(person(M.createObjectNode().put("url", MARKED)));
        assertEquals(1, added.path("added").asInt(), added.toString());
        assertFalse(added.path("checked").asBoolean(true), added.toString());
        assertTrue(added.path("note").asText().startsWith("Saved, and not checked yet: no model answered."), added.toString());
        assertNotNull(RawCapture.find(store, MARKED), "saved");
        assertTrue(indexed("zanderbolts"), "and in the index");
        // a reading list's page, the same way: saved and marked
        Shelving.Got got = Shelving.fetch(store, ORDINARY, "", "a placeholder reading list");
        assertTrue(got.shelved() && got.unchecked(), got.toString());
        // a url read into a list: the list is saved, and its address waits for the check too
        ObjectNode items = p.items(person(M.createObjectNode().put("url", LIST).put("as", "none")));
        assertFalse(items.path("checked").asBoolean(true), items.toString());
        assertEquals(3, UncheckedPages.pending(store).size(), UncheckedPages.pending(store).toString());
        assertTrue(UncheckedPages.tell(store).get(0).startsWith("3 pages you added are saved but not checked yet: no model answered."), UncheckedPages.tell(store).toString());

        // no model answers yet: nothing changes
        UncheckedPages.Outcome none = UncheckedPages.recheck(store, null, 50);
        assertEquals(0, none.checked());
        assertEquals(3, none.waiting());

        // a model answers: the page the check finds is removed from the shelves and the index, the others stay, and the person is told
        ContentJudge.use(markers());
        UncheckedPages.Outcome o = UncheckedPages.recheck(store, null, 50, System.currentTimeMillis());
        assertEquals(List.of(MARKED), o.removed());
        assertEquals(3, o.checked());
        assertEquals(0, o.waiting());
        assertNull(RawCapture.find(store, MARKED), "removed from the shelves");
        assertFalse(indexed("zanderbolts"), "and from the index");
        assertNotNull(RawCapture.find(store, ORDINARY), "the ordinary page stays");
        assertTrue(indexed("quillwheels"));
        assertEquals("The library removed a page you added after checking it: it had sexual content involving a child. Nothing of it is kept: " + MARKED + ".", o.sentence());
        assertEquals(List.of(o.sentence()), UncheckedPages.tell(store));
        try (Stream<Path> files = Files.walk(store.root())) {
            for (Path f : files.filter(Files::isRegularFile).toList())
                assertFalse(new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1).contains("MARKER-CHILD")
                        || new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1).contains("zanderbolts"), "nothing of the removed page is anywhere in the library, the index's files included: " + f);
        }
    }

    @Test
    void theNextCommandThatHasAModelChecksThePagesWaiting() throws Exception {
        Corpus.addUrl(store, MARKED, "");
        assertEquals(1, UncheckedPages.pending(store).size());
        // the next add is checked: a model answers, so the page that waited is checked with it
        ContentJudge.use(markers());
        Object[] got = Corpus.addUrl(store, OTHER, "");
        assertNotNull(got[0]);
        assertEquals(Boolean.FALSE, got[3], "this one was checked");
        assertTrue(UncheckedPages.pending(store).isEmpty());
        assertNull(RawCapture.find(store, MARKED), "the page that waited was checked, and removed");
    }

    @Test
    void theCommandLineSaysSoWhenItAddsAndWhenItRemoves() throws Exception {
        Path h = home.resolve("h");
        LibraryStore lib = new LibraryStore(h.resolve("researchzosho-library")); lib.init();
        String first = cli(h, "add", MARKED);
        assertTrue(first.contains("This page is not checked yet: no model answered. The library checks it when a model answers, at the next housekeeping or the next command that has a model, and removes it then if the check finds sexual content involving a child."), first);
        String status = cli(h, "status");
        assertTrue(status.contains("One page you added is saved but not checked yet: no model answered."), status);
        ContentJudge.use(markers());
        String second = cli(h, "add", OTHER);
        assertTrue(second.contains("The library removed a page you added after checking it: it had sexual content involving a child. Nothing of it is kept: " + MARKED + "."), second);
        assertTrue(cli(h, "status").contains("The library removed a page you added after checking it"), "status says so for thirty days");
    }

    private String cli(Path h, String... words) throws Exception {
        String realHome = System.getProperty("user.home");
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setProperty("user.home", h.toString());
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            String[] args = new String[words.length + 1];
            args[0] = "librarian";
            System.arraycopy(words, 0, args, 1, words.length);
            LibrarianCli.run(args, "http://127.0.0.1:1", "m");
        } finally { System.setOut(was); System.setProperty("user.home", realHome); }
        return out.toString(StandardCharsets.UTF_8);
    }
}
