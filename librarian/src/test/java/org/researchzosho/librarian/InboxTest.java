package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The inbox over the protocol: its facets, its filters, and the decisions a program may take. */
class InboxTest {

    static final ObjectMapper M = new ObjectMapper();
    static ObjectNode args(String json) throws Exception { return (ObjectNode) M.readTree(json); }

    static Finding claim(String id, String title, Finding.State state, Finding.ClaimType kind, Finding.Confidence conf, String writer, String when, String locator, String... subjects) {
        return new Finding(id, title, List.of(subjects), state, kind, conf, writer, when, "2026-09-01", Finding.Volatility.stable, "",
                List.of(new Finding.Source(locator, "n/a", "src")), List.of(), null, title + ". " + locator + "\n");
    }

    @Test
    void theInboxCarriesFacetsFiltersAndDecisions(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(claim("F-0001-ban", "Lead paint was banned in 1978", Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.high, "crew:explorer", "2026-09-08T10:00:00Z", "https://www.cpsc.gov/ban", "lead-paint"));
        store.write(claim("F-0002-effect", "The ban reduced childhood exposure slowly", Finding.State.draft, Finding.ClaimType.synthesis, Finding.Confidence.medium, "crew:explorer", "2026-09-08T11:00:00Z", "https://blog.example/lead", "lead-paint", "public-health"));
        store.write(claim("F-0003-kl", "KL項は潜在空間を正則化する", Finding.State.draft, Finding.ClaimType.interpretation, Finding.Confidence.low, "person", "2026-09-09T10:00:00Z", "https://arxiv.org/abs/1312.6114", "vae"));
        store.write(claim("F-0004-old", "An accepted claim, current", Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high, "person", "2026-09-01T10:00:00Z", "https://example.org", "vae"));
        store.write(new Investigation("I-0025-lead", "when and why was lead paint banned", Finding.State.accepted, "crew:explorer", "2026-09-08T12:00:00Z", List.of("F-0001-ban", "F-0002-effect"), List.of(), ""));
        Patrons.set(store, "did:key:zA", "A", Patrons.Level.write);
        Patrons.set(store, "did:key:zR", "R", Patrons.Level.read);
        LibraryProtocol p = new LibraryProtocol(store);

        List<ObjectNode> rows = p.inboxList();
        assertEquals(List.of("F-0001-ban", "F-0002-effect", "F-0003-kl"), rows.stream().map(o -> o.path("id").asText()).toList(), "drafts, oldest first; the accepted one is not waiting");
        ObjectNode ban = rows.get(0);
        assertEquals("I-0025-lead", ban.path("report").asText());
        assertEquals("when and why was lead paint banned", ban.path("report_title").asText());
        assertEquals("extraction", ban.path("kind").asText()); assertEquals("reference", ban.path("tier").asText()); assertEquals("high", ban.path("confidence").asText());
        assertEquals("crew:explorer", ban.path("writer").asText()); assertEquals("2026-09-08", ban.path("date").asText());
        assertEquals("lead-paint", ban.path("subjects").get(0).asText()); assertEquals("english", ban.path("language").asText());
        assertFalse(rows.get(2).has("report"), "a claim a person filed came from no report");
        assertEquals("japanese", rows.get(2).path("language").asText());

        assertTrue(LibraryProtocol.inboxMatches(ban, Map.of("report", "I-0025", "subject", "lead-paint", "kind", "extraction", "tier", "reference", "confidence", "high", "writer", "explorer", "state", "draft", "language", "english", "q", "lead 1978")));
        assertFalse(LibraryProtocol.inboxMatches(ban, Map.of("state", "stale")));
        assertFalse(LibraryProtocol.inboxMatches(ban, Map.of("q", "lead tokyo")));
        assertEquals(2, p.inbox(args("{\"op\":\"list\",\"report\":\"I-0025\"}")).get("items").size());
        assertEquals(1, p.inbox(args("{\"op\":\"list\",\"language\":\"japanese\"}")).get("items").size());
        assertEquals(1, p.inbox(args("{\"op\":\"list\",\"tier\":\"blog\"}")).get("items").size());

        // decisions: a reader may not; a writer may, by ids, by one id, or by report; a dispute needs why
        assertThrows(ProtocolError.class, () -> p.inbox(args("{\"op\":\"accept\",\"ids\":[\"F-0001-ban\"],\"patron\":{\"did\":\"did:key:zR\"}}")));
        assertThrows(ProtocolError.class, () -> p.inbox(args("{\"op\":\"dispute\",\"id\":\"F-0002-effect\",\"patron\":{\"did\":\"did:key:zA\"}}")), "no why");
        assertThrows(ProtocolError.class, () -> p.inbox(args("{\"op\":\"accept\",\"patron\":{\"did\":\"did:key:zA\"}}")), "nothing named");
        var accepted = p.inbox(args("{\"op\":\"accept\",\"report\":\"I-0025\",\"patron\":{\"did\":\"did:key:zA\"}}"));
        assertEquals(2, accepted.get("decided").size());
        assertEquals("accepted", accepted.path("decision").asText());
        assertEquals(Finding.State.accepted, store.finding("F-0001-ban").state());
        assertEquals(Finding.State.accepted, store.finding("F-0002-effect").state());
        var disputed = p.inbox(args("{\"op\":\"dispute\",\"id\":\"F-0003-kl\",\"why\":\"the paper says the opposite\",\"patron\":{\"did\":\"did:key:zA\"}}"));
        assertEquals("disputed", disputed.get("decided").get(0).path("state").asText());
        assertTrue(store.finding("F-0003-kl").body().contains("the paper says the opposite"));
        assertEquals(0, p.inbox(args("{\"op\":\"list\"}")).get("items").size(), "nothing waits now");
        assertThrows(ProtocolError.class, () -> p.inbox(args("{\"op\":\"retire\",\"id\":\"F-9999-none\",\"patron\":{\"did\":\"did:key:zA\"}}")), "unknown claim");
        // the question queue's fate reads kept now
        assertEquals("kept", p.fate(store.investigation("I-0025-lead")));
    }
}
