package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** The dossier (0.6.6): a view over everything the shelves hold on a subject; and telling the library, a told claim whose leads join the open questions. */
class DossierTest {
    @org.junit.jupiter.api.Test
    void thePatternOfLifeIsComputedFromTheClaims() {
        java.util.List<Finding> claims = new java.util.ArrayList<>();
        String[][] rows = {{"Ada Example, 2019: keynote at the Widget Summit, Austin, TX", "She gave the keynote at the Widget Summit in Austin, TX."}, {"Ada Example, 2019: joined Acme", "Joined Acme Corp. in Boston, MA."}, {"Ada Example, 2021: moved to Needham", "Lives in Needham, MA since 2021."}, {"Ada Example, 2021: a podcast", "Guest on the Gears podcast."}, {"Ada Example: an undated note", "A note with no year, Chestnut Hill, MA."}, {"Ada Example, 2007: a consultant", "Part-time Consultant, IV Labs; Chapter IV of the memoir."}};
        int n = 0;
        for (String[] r : rows) claims.add(new Finding("F-000" + (++n) + "-x", r[0], java.util.List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.medium, "patron:test", "2026-10-26T00:00:00Z", "2026-10-26", Finding.Volatility.stable, "", java.util.List.of(new Finding.Source("https://a.example/" + n, "n/a", "x")), java.util.List.of(), null, r[1] + "\n"));
        PatternOfLife.Pattern p = PatternOfLife.of(claims);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Map.of("2007", 1, "2019", 2, "2021", 2), p.byYear());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("2019"), p.places().get("Austin")); org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("2021"), p.places().get("Needham")); org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("undated"), p.places().get("Chestnut Hill"));
        org.junit.jupiter.api.Assertions.assertFalse(p.places().containsKey("Boston"), "the big places every dossier names are left out");
        org.junit.jupiter.api.Assertions.assertFalse(p.places().containsKey("Consultant") || p.places().containsKey("Chapter"), "a Roman numeral is not a state: " + p.places());
        claims.add(new Finding("F-0009-x", "Ada Example, 2022: 東京へ", java.util.List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.medium, "patron:test", "2026-10-26T00:00:00Z", "2022", Finding.Volatility.stable, "", java.util.List.of(new Finding.Source("https://a.example/9", "n/a", "x")), java.util.List.of(), null, "2022年に東京都千代田区の本社を訪れた。\n"));
        PatternOfLife.Pattern pj = PatternOfLife.of(claims);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("2022"), pj.places().get("千代田区")); org.junit.jupiter.api.Assertions.assertFalse(pj.places().containsKey("東京都"), "the capital is every dossier's; the ward is the place: " + pj.places());
        org.junit.jupiter.api.Assertions.assertEquals(2, p.appearances().size(), p.appearances().toString()); org.junit.jupiter.api.Assertions.assertTrue(p.appearances().get(0).startsWith("2019: Ada Example, 2019: keynote"));
        String md = PatternOfLife.markdown(p); org.junit.jupiter.api.Assertions.assertTrue(md.startsWith("## Pattern of life") && md.contains("- 2019 ████████████████████ 2") && md.contains("- Austin (2019)") && md.contains("### Appearances"), md);
        String html = PatternOfLife.html(p); org.junit.jupiter.api.Assertions.assertTrue(html.contains("<ul class=\"bars\">") && html.contains("style=\"width:100%\"") && html.contains("Needham <span class=\"date\">2021</span>"), html);
        org.junit.jupiter.api.Assertions.assertTrue(PatternOfLife.markdown(PatternOfLife.of(java.util.List.of())).isEmpty());
    }

    private static final ObjectMapper J = new ObjectMapper();

    private static ObjectNode patron() { ObjectNode a = J.createObjectNode(); a.putObject("patron").put("did", "did:key:tester").put("name", "Tester").put("runtime", "test"); return a; }

    @Test
    void theDossierGathersRelationsClaimsByClassATimelineTheReportsAndTheOpenQuestions(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String f1 = store.nextFindingId("Ada Example joined Acme");
        store.write(new Finding(f1, "Ada Example joined Acme", List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high, "patron:test",
                Instant.now().toString(), "2026-10-24", Finding.Volatility.stable, "", List.of(new Finding.Source("https://www.sec.gov/Archives/edgar/data/1/acme-8k.htm", "n/a", "the filing")), List.of(), null,
                "In 1999 Ada Example joined Acme Corp. as a director.\n", new Finding.Triple("Ada Example", "worked at", "Acme Corp."), List.of()));
        String f2 = store.nextFindingId("Ada Example on the record");
        store.write(new Finding(f2, "Ada Example on the record", List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium, "patron:test",
                Instant.now().toString(), "2026-10-24", Finding.Volatility.slow, "", List.of(new Finding.Source("https://www.nytimes.com/2004/x.html", "n/a", "press")), List.of(), null,
                "A 2004 profile describes Ada Example as the engineer behind the product.\n"));
        String i1 = store.nextInvestigationId("Who is Ada Example?");
        store.write(new Investigation(i1, "Who is Ada Example?", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(f1, f2), List.of(),
                "## Question\n\nWho is Ada Example?\n\n## Answer\n\nAda Example is an engineer who joined Acme in 1999.\n\n## Record collections searched\n\n- Wikidata: 1 search, 0 found records\n\n### Searched and not found\n\n- Wikidata: Ada Example\n\n## Sources\n\n[1] x\n"));
        LibraryProtocol p = new LibraryProtocol(store);
        // what the person tells the library, and the questions it raises, listed by a stand-in for the model
        LibraryProtocol.LEADS = (subject, text) -> List.of("Where did the Acme engineering team go after the 2001 layoffs that Ada Example describes?");
        try {
            ObjectNode a = patron(); a.put("subject", "Ada Example"); a.put("text", "I worked beside Ada at Acme in 2000; after the 2001 layoffs the whole engineering team scattered within a year.");
            ObjectNode told = p.tell(a);
            assertEquals("told", told.path("class").asText());
            assertTrue(told.path("locator").asText().startsWith("told://interview/ada-example/"), told.path("locator").asText());
            assertEquals(1, told.path("questions").size(), told.toString());
            assertTrue(told.path("summary").asText().startsWith("Noted, in your words, as F-"), told.path("summary").asText());
            Finding t = store.finding(told.path("id").asText());
            assertEquals(SourceClass.told, SourceClass.of(t.sources()));
            assertEquals("patron:did:key:tester", t.writer(), "the person who told it is the writer");
        } finally { LibraryProtocol.LEADS = (s, t) -> List.of(); }
        ObjectNode d = p.dossier(patron().put("subject", "Ada Example"));
        assertTrue(d.path("found").asBoolean());
        assertEquals(3, d.path("claims").asInt(), d.toString());
        assertEquals(1, d.path("reports").asInt());
        assertEquals(1, d.path("questions").asInt(), "the told claim's lead is an open question on the subject");
        assertEquals(1, d.path("by_class").path("public record").asInt()); assertEquals(1, d.path("by_class").path("told").asInt()); assertEquals(1, d.path("by_class").path("third-party").asInt());
        String md = d.path("markdown").asText();
        assertTrue(md.startsWith("# Dossier: Ada Example\n"), md);
        assertTrue(md.contains("## Relations\n") && md.contains("Acme Corp.") && md.contains("[" + f1 + "]"), md);
        assertTrue(md.contains("## Timeline\n") && md.contains("- 1999: Ada Example joined Acme [public record, 2026-10-24] (" + f1 + ")") && md.contains("- 2004: Ada Example on the record [third-party"), md);
        assertTrue(md.contains("## Told\n") && md.contains("I worked beside Ada at Acme in 2000"), md);
        assertTrue(md.contains("## Reports to dive into\n") && md.contains(i1 + " — Who is Ada Example?") && md.contains("Ada Example is an engineer who joined Acme in 1999."), md);
        assertTrue(md.contains("## Looked for and not found\n\n- Wikidata: Ada Example"), md);
        assertTrue(md.contains("## Open questions\n\n- Where did the Acme engineering team go"), md);
        assertTrue(md.contains("## Linked\n") && md.contains("Acme Corp."), md);
        assertEquals("The record shows a founder.", Dossier.answerOf("## Question\n\nq\n\n## Answer (as submitted by the run — draft until reviewed)\n\ndraft\n\n## Reading\n\nThe record shows a founder.\n\n## Identity\n\nx\n", 300), "a dossier report opens with its Reading");
        assertEquals("draft…", Dossier.answerOf("## Question\n\nq\n\n## Answer (as submitted by the run — draft until reviewed)\n\ndraft text\n", 5));
        // the run's own evidence: what the shelves hold on the subject goes before the workers; nothing for a subject the library has nothing on
        String ev = Profiles.named("person").runEvidence(store, new Researcher.Ask("Who is Ada Example? A full dossier.", "broad", 0, List.of()), null);
        assertTrue(ev.startsWith("WHAT THE LIBRARY ALREADY HOLDS ON Ada Example — 3 claims, 1 reports."), ev);
        assertTrue(ev.contains("# Dossier: Ada Example") && ev.contains("## Told"), ev);
        assertEquals("", Profiles.named("person").runEvidence(store, new Researcher.Ask("Who is Nobody Known?", "broad", 0, List.of()), null));
        assertEquals("", Profiles.named("organization").runEvidence(store, new Researcher.Ask("How were the gears cut?", "broad", 0, List.of()), null), "no subject in the words, no block");
        assertEquals("Ada Example", TimelineClaims.subjectOf("Who is Ada Example, the one whose profile is on these shelves? A full dossier."));
        assertEquals("Cisco Systems", TimelineClaims.subjectOf("Corporate history of Cisco Systems, Inc., 1984 to 1995: the founders."));
        // op=claims files the reports' dated events onto the shelves for the reports already there; a second pass files nothing
        String i9 = store.nextInvestigationId("Who is Ada Example? Her life.");
        store.write(new Investigation(i9, "Who is Ada Example? Her life.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(), List.of(), "## Timeline\n\n1981: B.A., Boston University [1]. 1984: The Big U [1].\n\n## References\n\n[1] https://a.example/bib\n"));
        ObjectNode c = p.dossier(patron().put("subject", "Ada Example").put("op", "claims"));
        assertEquals(2, c.path("filed").asInt(), c.toString()); assertEquals(2, c.path("reports").asInt());
        assertTrue(c.path("summary").asText().startsWith("2 dated events filed as claims from 2 reports on Ada Example."), c.path("summary").asText());
        assertEquals(0, p.dossier(patron().put("subject", "Ada Example").put("op", "claims")).path("filed").asInt());
        // a subject the library holds nothing on says so, and how to start
        String none = p.dossier(patron().put("subject", "Nobody Known")).path("markdown").asText();
        assertTrue(none.contains("holds nothing on this subject yet"), none);
        assertFalse(p.dossier(patron().put("subject", "Nobody Known")).path("found").asBoolean());
    }

    @Test
    void aToldClaimNeedsASubjectAndASentence(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibraryProtocol p = new LibraryProtocol(store);
        assertThrows(ProtocolError.class, () -> p.tell(patron().put("subject", "X").put("text", "short")));
        assertThrows(ProtocolError.class, () -> p.tell(patron().put("subject", " ").put("text", "a sentence long enough to count")));
        assertEquals(SourceClass.told, SourceClass.of("told://interview/x/2026-10-24"));
        assertEquals("told", SourceClass.told.label());
    }

    @Test
    void aDossiersKindIsWhatItsRunWasAskedForElseTheGraphs() {
        Investigation inv = new Investigation("I-0001-x", "高市早苗について", Finding.State.accepted, "run", Instant.now().toString(), List.of(), List.of(), "# x\n");
        assertEquals("person", Dossier.kindOf(List.of(inv), Map.of("I-0001-x", Set.of("person")), null));
        assertEquals("organization", Dossier.kindOf(List.of(inv), Map.of("I-0001-x", Set.of("organization")), null));
        assertEquals("", Dossier.kindOf(List.of(inv), Map.of(), null), "no run in a field and no node: nothing to say");
    }

    @Test
    void aUniversityBeforeACommaIsNotATown() {
        assertTrue(CrossLinks.isATown("Chestnut Hill") && CrossLinks.isATown("Needham"));
        assertFalse(CrossLinks.isATown("Columbia University") || CrossLinks.isATown("Acme Corp"));
    }

    @Test
    void anAmountOfMoneyIsASpanNotALinkedSubject() {
        assertTrue(Dossier.isASpan("34") && Dossier.isASpan("1999 to 2006") && Dossier.isASpan("$68 billion") && Dossier.isASpan("$6.5 billion") && Dossier.isASpan("27.1%"));
        assertFalse(Dossier.isASpan("Garber") || Dossier.isASpan("1636 Forum"));
    }
}
