package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opening a report on the phone (2026-11-25, the owner: "clicking any of the reads .. takes a bit before it loads"). Measured on copies
 * of scratch libraries: the protocol's full entry, which looks every reference up among the captured pages and builds each claim's own
 * entry, was 90 ms to 1.7 s of an open, and everything the page shows 1 to 18 ms. The page now reads the record itself: these tests hold
 * that it shows what the protocol would (the same addresses in the same order, the same claims, in both forms of a reference list),
 * and that the page's script has its waiting line. Nothing starts.
 */
class PhoneReadTest {

    static final String REFERENCES = """
            ## Question

            Where is the ferry?

            ## Answer

            It left [1] and came back [2]; the pier [3], the notes [4].

            ## References

            [1] The harbour board's notice — https://example.org/notice (published 2024-03-02)
            [2] raw/2024-03-04-ferry-news.md
            [3] Smith, J. (2019), Piers of the north — pier-survey.pdf
            [4] harbour.example.net/minutes/2024 (same text as [1])
            """;

    static final String SOURCES_CITED = """
            ## Answer

            An older record.

            ## Sources cited

            - https://example.org/old-page
            - raw/2023-01-01-old.md
            """;

    @Test
    void theAddressesAreTheProtocolsInItsOrderInBothFormsOfAReferenceList(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibraryProtocol p = new LibraryProtocol(store);
        for (String body : new String[]{REFERENCES, SOURCES_CITED}) {
            List<String> full = new ArrayList<>(), light = new ArrayList<>();
            for (ObjectNode o : p.investigationSources(body)) full.add(o.path("n").asText() + " " + o.path("locator").asText());
            for (LibraryProtocol.Reference r : LibraryProtocol.references(body)) light.add(r.n() + " " + r.locator());
            assertEquals(full, light, body);
            assertFalse(light.isEmpty());
        }
        assertEquals(List.of("1 https://example.org/notice", "2 raw/2024-03-04-ferry-news.md", "3 pier-survey.pdf", "4 harbour.example.net/minutes/2024"),
                LibraryProtocol.references(REFERENCES).stream().map(r -> r.n() + " " + r.locator()).toList());
        // the full rows still carry what the protocol says of each source
        JsonNode first = p.investigationSources(REFERENCES).get(0);
        assertEquals("2024-03-02", first.path("published").asText());
        assertEquals(1, p.investigationSources(REFERENCES).get(3).path("same_as").asInt());
    }

    @Test
    void theReportOnThePhoneShowsTheSourcesAndClaimsTheProtocolHolds(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Finding claim = new Finding("F-0001-the-ferry-left", "The ferry left in March", List.of("ferries"), Finding.State.accepted,
                Finding.ClaimType.extraction, Finding.Confidence.high, "person", "2026-11-25T00:00:00Z", "2026-11-25", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/notice", "n/a", "the notice")), List.of(), null, "It left in March.\n", null, List.of());
        store.write(claim);
        String id = store.nextInvestigationId("Where is the ferry?");
        store.write(new Investigation(id, "Where is the ferry?", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(claim.id()), List.of(), REFERENCES, "en"));
        String html = PhonePage.read(store, Patrons.Patron.ANONYMOUS, id);
        ObjectNode e = new LibraryProtocol(store).entry(id, "investigation", true);
        assertTrue(html.contains("<h1>Where is the ferry?</h1>"), html);
        for (JsonNode s : e.path("sources")) assertTrue(html.contains(PhonePage.phoneLinks(Pages.locatorLink(s.path("locator").asText()))), "every source the protocol lists, as the page writes it: " + s);
        for (JsonNode f : e.path("findings")) assertTrue(html.contains("href=\"/m/read/" + f.asText() + "\"") && html.contains(Pages.claimTitle(store, f.asText())), "every claim: " + f);
        assertTrue(html.indexOf("example.org/notice") < html.indexOf("pier-survey.pdf"), "in the report's own order");
        // a claim opens the same way, with its own sources
        String c = PhonePage.read(store, Patrons.Patron.ANONYMOUS, claim.id());
        assertTrue(c.contains("<h1>The ferry left in March</h1>") && c.contains(PhonePage.phoneLinks(Pages.locatorLink("https://example.org/notice"))) && !c.contains("Claims that came out of it"), c);
        // what is not there is the plain refusal
        for (String missing : new String[]{"I-9999-nothing", "F-9999-nothing"}) {
            try { PhonePage.read(store, Patrons.Patron.ANONYMOUS, missing); throw new AssertionError(missing); }
            catch (ProtocolError expected) { assertEquals(404, LibrarianDaemon.status(expected.code)); }
        }
    }

    @Test
    void thePagesScriptHasOneWaitingLineThatShowsAtOnceHoldsTheTapAndGivesWayAfterTwentySeconds() {
        String js = PhonePage.JS, css = PhonePage.CSS;
        assertTrue(js.contains("function waitOn(t,word,put,run){if(t&&t.getAttribute('aria-busy')==='true')return;"), "a second tap on a thing that waits asks nothing");
        int show = js.indexOf("if(!n.parentNode)put(n);"), ask = js.indexOf("run().then(", show);
        assertTrue(show > 0 && ask > show, "the line is in the page before the request goes");
        assertTrue(js.contains("setTimeout(function(){if(!end)failed()},20000)") && js.contains("b=el('button','quiet',W.again)"), "after 20 s, a sentence with Try again");
        for (String use : new String[]{"waitOn(inRead?from:null,W.opening", "waitOn(null,W.loading,top($('t-read'))", "waitOn(null,W.loading,top($('t-runs'))", "waitOn(nb,W.loading",
                "waitOn(sb,W.stopping", "waitOn(s,W.stopping", "xpost('/m/ask',fd"})
            assertTrue(js.contains(use), "the waiting line where the page waits on the library: " + use);
        assertTrue(css.contains("animation:rz-turn .8s linear 25") && css.contains("@media (prefers-reduced-motion:reduce){.spin{animation:none}}"), "its wheel turns 20 s at most");
        assertFalse(css.contains("#") && css.replace(Pages.NIGHT, "").replace(Pages.DAY, "").matches("(?s).*#[0-9a-fA-F]{3,8}\\b.*"), "its colours are the palette's");
        String words = PhonePage.words();
        for (String w : new String[]{"\"opening\":\"Opening…\"", "\"loading\":\"Loading…\"", "\"sending\":\"Sending…\"", "\"stopping\":\"stopping…\"", "\"again\":\"Try again\"",
                "\"notBack\":\"Nothing came back yet. The connection may be slow or down.\""})
            assertTrue(words.contains(w), w + " in " + words);
    }
}
