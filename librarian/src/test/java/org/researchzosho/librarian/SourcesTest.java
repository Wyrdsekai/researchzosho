package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
/** The sources the library mines: kept once by address, read with what they yield, dry when nothing comes, off when the person says so; the same over the protocol, the page and the chat. */
class SourcesTest {
    static final ObjectMapper M = new ObjectMapper();

    @Test void aSourceIsKeptOnceReadWithItsYieldAndGoesDryOrOff(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        assertTrue(Sources.all(store).isEmpty());
        assertEquals("feed", Sources.kindOf("https://example.org/jobs.rss")); assertEquals("board", Sources.kindOf("https://boards.greenhouse.io/acme")); assertEquals("list page", Sources.kindOf("https://example.org/members")); assertEquals("saved search", Sources.kindOf("nurses Lyon hospital"));

        Sources.Source s = Sources.add(store, "", "https://www.example.org/members/", "employers", "career", "Lyon", "nurses", "", "you", "the regional hospitals");
        assertEquals("S-0001", s.id()); assertEquals("list page", s.kind()); assertEquals("active", s.state()); assertEquals("you", s.foundBy());
        Sources.Source again = Sources.add(store, "directory", "http://example.org/members", "", "", "", "", "nurses in Lyon", "the career search", "");
        assertEquals("S-0001", again.id(), "the same address, with or without www, scheme or a trailing slash, is the same source");
        assertEquals("list page", again.kind(), "what was kept stays; blanks fill in"); assertEquals("nurses in Lyon", again.forWhom()); assertEquals("you", again.foundBy());
        assertEquals(1, Sources.all(store).size());

        Sources.Source r = Sources.read(store, "https://example.org/members", "", "", "career", "", "", "the career search", 12);
        assertEquals(12, r.yieldTotal()); assertEquals(12, r.yieldLast()); assertEquals(LocalDate.now().toString(), r.lastYield()); assertEquals("active", r.state());
        r = Sources.read(store, "https://example.org/members", "", "", "career", "", "", "the career search", 0);
        assertEquals(12, r.yieldTotal()); assertEquals(0, r.yieldLast()); assertEquals("active", r.state(), "nothing new today is not dry yet");

        Sources.Source b = Sources.add(store, "", "https://jobs.example.org/feed.rss", "seats", "career", "", "", "", "run J-0001", "");
        assertEquals("S-0002", b.id()); assertEquals("feed", b.kind());
        assertEquals(List.of("S-0001"), Sources.active(store, "career", "Lyon", "nurses").stream().map(Sources.Source::id).toList(), "the feed a run found for no place is not read for Lyon");
        assertTrue(Sources.active(store, "career", "Berlin", "welders").isEmpty(), "a source for Lyon's nurses is not read for Berlin's welders; one a run found for no place is not read for a place either");
        assertEquals(List.of("S-0001", "S-0002"), Sources.active(store, "career", "", "").stream().map(Sources.Source::id).toList(), "a search with no place reads what was found for no place, and what the person added, which is read wherever they look");
        assertTrue(Sources.active(store, "books", "", "").isEmpty(), "a source of one field is not another's");

        assertTrue(Sources.remove(store, "S-0001")); assertEquals("off", Sources.get(store, "S-0001").state());
        assertTrue(Sources.active(store, "career", "Lyon", "nurses").isEmpty(), "taken off: not read");
        Sources.Source still = Sources.read(store, "https://example.org/members", "", "", "career", "", "", "the career search", 5);
        assertEquals("off", still.state(), "a search that finds it again does not bring it back"); assertEquals(12, still.yieldTotal());
        assertTrue(Sources.restore(store, "s-0001")); assertEquals("active", Sources.get(store, "S-0001").state());
        assertFalse(Sources.remove(store, "S-9999"));
        assertThrows(IllegalArgumentException.class, () -> Sources.add(store, "", "  ", "", "", "", "", "", "you", ""));

        String text = Sources.text(Sources.all(store), false, "researchzosho sources");
        assertTrue(text.startsWith("The sources the library mines, 2 in all:\n- S-0001: https://www.example.org/members/, a list page that yields employers, for career, nurses, Lyon, nurses in Lyon; found by you; yielded 12 things in all, 0 the last time"), text);
        assertTrue(text.contains("Your note: the regional hospitals.") && text.contains("- S-0002: https://jobs.example.org/feed.rss, a feed that yields seats, for career; found by run J-0001; nothing yielded yet; active."), text);
        assertTrue(Files.isRegularFile(tmp.resolve("lib/catalog/sources.json")));

        // trust by survival: a source whose yield keeps falling at the first checks goes doubtful; a read with a yield does not bring it back; one survivor does
        Sources.Source d = Sources.score(store, "S-0002", 0, 5);
        assertEquals("active", d.state(), "five fell, none survived: not doubtful yet");
        d = Sources.score(store, "S-0002", 0, 3);
        assertEquals("doubtful", d.state()); assertEquals(8, d.fell()); assertTrue(d.line().contains("of what it yielded, 0 survived the first checks and 8 fell; doubtful: not read first until something it yields survives"), d.line());
        assertTrue(Sources.active(store, "career", "", "").stream().noneMatch(x -> x.id().equals("S-0002")), "a doubtful source is not read first");
        assertEquals("doubtful", Sources.read(store, "https://jobs.example.org/feed.rss", "", "", "career", "", "", "run J-0001", 40).state(), "volume never promotes");
        assertEquals("active", Sources.score(store, "S-0002", 1, 0).state(), "one survivor brings it back");

        // a host the person refused is never kept, whatever a search found there
        SourceRules.set(store, "refuse", "spam.example", "a content farm");
        assertNull(Sources.read(store, "https://spam.example/best-employers", "", "employers", "career", "", "", "the career search", 50));
        assertEquals(2, Sources.all(store).size());
    }

    @Test void theSavedSearchesAndTheFollowedPodcastsAreOnTheOneListAndTakingOneOffParksIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Serials.add(store, "keigo", "敬語 字幕 翻訳 研究", 14);
        Serials.add(store, "wine", Serials.PODCAST + "https://feeds.example.org/wine.rss", 7);
        Files.writeString(tmp.resolve("lib/catalog/arrivals.md"), "# Arrival table\n\n- 2026-09-02 [keigo] https://a.example/1 — one\n- 2026-09-02 [keigo] https://a.example/2 — two\n- 2026-09-16 [keigo] https://a.example/3 — three\n- 2026-09-16 [wine] https://feeds.example.org/ep1.mp3 — ep\n", StandardCharsets.UTF_8);
        List<Sources.Source> all = Sources.all(store);
        assertEquals(List.of("shelf-keigo", "shelf-wine"), all.stream().map(Sources.Source::id).toList());
        Sources.Source k = Sources.get(store, "shelf-keigo");
        assertEquals("serial", k.kind()); assertEquals("敬語 字幕 翻訳 研究", k.address()); assertEquals("pages every 14 days", k.yields()); assertEquals("keigo", k.name()); assertEquals("you", k.foundBy());
        assertEquals(3, k.yieldTotal()); assertEquals(1, k.yieldLast()); assertEquals("2026-09-16", k.lastYield()); assertEquals("active", k.state());
        assertTrue(k.line().startsWith("shelf-keigo: keigo, 敬語 字幕 翻訳 研究, a serial that yields pages every 14 days; found by you; yielded 3 things in all, 1 the last time (2026-09-16); active."), k.line());
        Sources.Source w = Sources.get(store, "shelf-wine");
        assertEquals("podcast", w.kind()); assertEquals("https://feeds.example.org/wine.rss", w.address()); assertEquals("episodes every 7 days", w.yields()); assertEquals(1, w.yieldTotal());
        assertTrue(Sources.active(store, "career", "", "").isEmpty(), "a saved search is the nightly check's to run, never a search's to read");
        assertTrue(Sources.remove(store, "shelf-keigo"));
        assertTrue(Serials.shelves(store).get(0).parked(), "taken off is parked"); assertEquals("off", Sources.get(store, "shelf-keigo").state());
        assertTrue(Sources.get(store, "shelf-keigo").line().endsWith("parked by you, not run until you bring it back."));
        assertTrue(Sources.restore(store, "shelf-keigo")); assertFalse(Serials.shelves(store).get(0).parked());
        assertFalse(Sources.remove(store, "shelf-nope"));
        // words added as a source are a search to run again: they go to the shelves and come back as a serial
        Sources.Source n = Sources.add(store, "", "nurses in Lyon", "", "career", "", "", "", "you", "");
        assertEquals("shelf-nurses-in-lyon", n.id()); assertEquals("serial", n.kind()); assertEquals("nurses in Lyon", n.address());
        assertEquals(3, Serials.shelves(store).size()); assertEquals(7, Serials.shelves(store).get(2).everyDays());
        assertEquals("shelf-nurses-in-lyon", Sources.add(store, "", "nurses in Lyon", "", "", "", "", "", "you", "").id(), "the same words again are the same search");
        assertEquals("nurses-in-lyon-2", Serials.slugFor(store, "Nurses, in Lyon!"), "a name is unique in the library");
        LibraryProtocol lp = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "").put("runtime", "cli");
        ObjectNode added = lp.sources(a.put("op", "add").put("address", "welders Berlin").put("for", "").put("field", ""));
        assertEquals("Kept as shelf-welders-berlin, a search the nightly check runs again every 7 days; what it finds new lands on the arrival table for you to admit. researchzosho shelf every welders-berlin <days> changes how often.", added.path("note").asText());
        assertEquals("Parked: shelf-welders-berlin. The nightly check does not run it until you bring it back.", lp.sources(a.put("op", "remove").put("id", "shelf-welders-berlin")).path("note").asText());
        assertTrue(lp.sources(a.put("op", "list").put("off", true)).path("text").asText().contains("- shelf-welders-berlin: welders-berlin, welders Berlin, a serial that yields pages every 7 days; found by you; nothing yielded yet; parked by you"));
    }

    @Test void theProtocolThePageAndTheChatShowAndChangeTheSameList(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibraryProtocol lp = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode(); a.putObject("patron").put("did", "person").put("name", "").put("runtime", "cli");
        String fresh = lp.sources(a.put("op", "list")).path("text").asText();
        assertTrue(fresh.startsWith("The sources the library mines, 4 in all:\n- S-0001: Underscore VC's portfolio board, https://jobs.underscore.vc, a board that yields seats, for career, Boston, Cambridge, Massachusetts, MA; found by the boards table; nothing yielded yet; active."), "the boards the library knows are on the list from the first look: " + fresh);
        ObjectNode added = lp.sources(a.put("op", "add").put("address", "https://example.org/postdocs").put("for", "neuroscience postdocs").put("field", "career"));
        assertEquals("S-0005", added.path("source").path("id").asText()); assertEquals("Kept as S-0005, a list page for neuroscience postdocs. The next search of the career field reads it.", added.path("note").asText());
        assertEquals("S-0005", lp.sources(a.put("op", "show").put("id", "S-0005")).path("source").path("id").asText());
        assertEquals("Taken off: S-0005. No search reads it until you bring it back.", lp.sources(a.put("op", "remove").put("id", "S-0005")).path("note").asText());
        assertEquals(0, lp.sources(a.put("op", "list").put("off", false)).path("sources").size(), "the words of the add still narrow the list to the postdocs page, which is off"); assertEquals(1, lp.sources(a.put("op", "list").put("off", true)).path("sources").size());
        assertEquals("Brought back: S-0005. The next search reads it.", lp.sources(a.put("op", "restore").put("id", "S-0005")).path("note").asText());
        ProtocolError e = assertThrows(ProtocolError.class, () -> lp.sources(a.put("op", "show").put("id", "S-0042")));
        assertTrue(e.getMessage().startsWith("No source has the id S-0042."));

        String chat = SourcesPage.chat(store, Patrons.Patron.PERSON, "/sources add https://example.org/clinics --for nurses in Lyon --field career", "/sources");
        assertEquals("Kept as S-0006, a list page for nurses in Lyon. The next search of the career field reads it.", chat);
        assertTrue(SourcesPage.chat(store, Patrons.Patron.PERSON, "/sources", "/sources").contains("- S-0006: https://example.org/clinics, a list page, for career, nurses in Lyon; found by you; nothing yielded yet; active."));
        assertEquals("Taken off: S-0006. No search reads it until you bring it back.", SourcesPage.chat(store, Patrons.Patron.PERSON, "/sources remove S-0006", "/sources"));
        assertTrue(SourcesPage.chat(store, Patrons.Patron.PERSON, "/sources dance", "/sources").startsWith("Type /sources to see the sources;"));

        String page = SourcesPage.body(store, true, Map.of());
        assertTrue(page.contains("<td>S-0005</td>") && page.contains("neuroscience postdocs") && page.contains("<b>Underscore VC&#39;s portfolio board</b>") || page.contains("<b>Underscore VC's portfolio board</b>"), page);
        assertTrue(page.contains("bring back") && page.contains("take off") && page.contains("name=\"address\""), page);
        assertFalse(SourcesPage.body(store, false, Map.of()).contains("<form"), "a reader sees the list and no forms");
        assertEquals("Brought back: S-0006. The next search reads it.", SourcesPage.post(store, Patrons.Patron.PERSON, Map.of("op", "restore", "id", "S-0006")));
    }
}
