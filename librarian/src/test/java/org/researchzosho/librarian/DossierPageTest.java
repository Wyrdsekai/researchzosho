package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The dossier page (0.6.6): the target package rendered from the shelves and the latest report, each claim with its class, the sections of the mock in order. */
class DossierPageTest {
    @Test
    void thePageCarriesTheIdentityTheReadingTheCoverageTheTimelineTheClassesTheStoryAndTheGaps(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String f1 = store.nextFindingId("Ada Example joined Acme");
        store.write(new Finding(f1, "Ada Example joined Acme", List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high, "patron:test", Instant.now().toString(), "2026-10-24", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://www.sec.gov/x", "n/a", "x")), List.of(), null, "In 1999 Ada Example joined Acme Corp.\n", new Finding.Triple("Ada Example", "worked at", "Acme Corp."), List.of()));
        String f2 = store.nextFindingId("Ada Example: what it was like");
        store.write(new Finding(f2, "Ada Example: what it was like", List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium, "patron:did:key:tester", Instant.now().toString(), "2026-10-24", Finding.Volatility.slow, "",
                List.of(new Finding.Source("told://interview/ada-example/2026-10-24", "n/a", "told")), List.of(), null, "The team was gone within two years.\n"));
        String i1 = store.nextInvestigationId("Who is Ada Example?");
        store.write(new Investigation(i1, "Who is Ada Example?", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(f1), List.of(),
                "## Question\n\nWho is Ada Example?\n\n## Answer\n\nAda Example is an engineer [1].\n\n## Identity\n\nResolved by two handles.\n\n## Conflicts and uncertainty\n\nHer title differs between sources.\n\n## Cite-check\n\ncite-check: 3 cited sentence(s) read against their source — 3 supported.\n\n## References\n\n[1] x  [public record]\n\n1 source(s), 1 independent. By source class: 1 public record.\n\n## Record collections searched\n\n- GLEIF legal entity register: 2 searches, 2 found records\n- Wikidata: 1 search, 0 found records\n\nNot searched in this run: ORCID; Hacker News.\n\n### Searched and not found\n\n- Wikidata: Ada Example\n"));
        String s1 = store.nextInvestigationId("The story of Ada Example");
        store.write(new Investigation(s1, "The story of Ada Example", Finding.State.draft, "librarian:story", Instant.now().toString(), List.of(f1, f2), List.of(), "# The story of Ada Example\n\n## 1990s\n\nShe joined [" + f1 + "].\n"));
        String html = DossierPage.render(store, "Ada Example");
        assertTrue(html.contains("<h2 id=\"identity\">Identity</h2>") && html.contains("<b>Ada Example</b>") && html.contains("How resolved</b> Resolved by two handles."), html);
        assertTrue(html.contains("Question of the latest run</div>Who is Ada Example?"), html);
        assertTrue(html.contains("<h2 id=\"reading\">") && html.contains("Ada Example is an engineer"), html);
        assertTrue(html.contains("<h2 id=\"work\">What they do</h2>") && html.contains("No report describes the work yet"), "a report without The work or Material says so: " + html.substring(html.indexOf("id=\"work\""), html.indexOf("id=\"work\"") + 300));
        String i2 = store.nextInvestigationId("Who is Ada Example? Her work.");
        store.write(new Investigation(i2, "Who is Ada Example? Her work.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(f1), List.of(),
                "## Question\n\nWho is Ada Example? Her work.\n\n## Answer\n\nAn engineer [1].\n\n## Material\n\n**The Widget Router** (1999): the first multi-protocol widget, built at Acme [1].\n\n## References\n\n[1] https://www.sec.gov/x\n"));
        String again = DossierPage.render(store, "Ada Example");
        assertTrue(again.contains("<h2 id=\"work\">What they do</h2>") && again.contains("The Widget Router"), "the latest report's Material is what they do: " + again.substring(again.indexOf("id=\"work\""), again.indexOf("id=\"work\"") + 400));
        assertTrue(Dossier.of(store, "Ada Example").path("markdown").asText().contains("## What they do\n\n**The Widget Router**"), "and the markdown carries it");
        // a person's own What they do outranks Material
        String i3 = store.nextInvestigationId("Who is Ada Example? Now.");
        store.write(new Investigation(i3, "Who is Ada Example? Now.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(f1), List.of(),
                "## Question\n\nWho is Ada Example? Now.\n\n## Answer\n\nAn engineer [1].\n\n## What they do\n\nNow: leads routing at Acme [1]. Known for: the Widget Router [1].\n\n## Material\n\nThe Widget Router (1999) [1].\n\n## Influences\n\nShe names Lerner as the engineer who taught her [1].\n\n## Holdings and filings\n\nNo filings [1].\n\n## References\n\n[1] https://www.sec.gov/x\n"));
        String now = DossierPage.render(store, "Ada Example");
        assertTrue(now.contains("Now: leads routing at Acme") && now.indexOf("Now: leads routing") < now.indexOf("<h2 id=\"coverage\">"), "What they do, first, from the latest report");
        assertTrue(now.contains("<h2 id=\"facets\">Facets</h2>") && now.contains("<h3>Influences</h3>") && now.contains("<h3>Money</h3>") && now.contains("No filings"), "the facets the report carries: " + now.substring(now.indexOf("id=\"facets\""), now.indexOf("id=\"facets\"") + 600));
        assertTrue(now.contains("<h2 id=\"refresh\">Refresh and watch</h2>") && now.contains("/research?q=Who%20is%20Ada%20Example%3F%20Now.") && now.contains("name=\"watch\""), "refresh and watch");
        assertTrue(Dossier.of(store, "Ada Example").path("markdown").asText().contains("## Influences\n\nShe names Lerner"), "the markdown carries the facets");
        // every [n] links to what it cites, from the report the section came from
        String i4 = store.nextInvestigationId("Who is Ada Example? Cited.");
        store.write(new Investigation(i4, "Who is Ada Example? Cited.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(f1), List.of(),
                "## Question\n\nWho is Ada Example? Cited.\n\n## Answer\n\nAn engineer at Acme [2].\n\n## What they do\n\nLeads routing at Acme [1]; known for the Widget Router [2][9].\n\n## References\n\n[1] Acme Corp, Form 10-K 2025 — https://www.sec.gov/acme  (published 2025-09-01)  [public record]\n[2] Ada Example's own page — https://ada.example/about  [own publication]\n[3] a saved page — file:///home/x/notes.txt  [supplied]\n"));
        String cited = DossierPage.render(store, "Ada Example");
        assertTrue(cited.contains("<a class=\"cite\" href=\"https://www.sec.gov/acme\" title=\"Acme Corp, Form 10-K 2025\">[1]</a>") && cited.contains("href=\"https://ada.example/about\" title=\"Ada Example's own page\">[2]</a>"), "the citations link to their sources: " + cited.substring(cited.indexOf("id=\"work\""), cited.indexOf("id=\"work\"") + 500));
        assertTrue(cited.contains("[9]") && !cited.contains(">[9]</a>"), "a number the list does not carry stays plain");
        assertTrue(cited.indexOf("href=\"https://ada.example/about\" title=\"Ada Example's own page\">[2]</a>") < cited.indexOf("id=\"work\""), "the Reading's citations link too");
        String md = Dossier.of(store, "Ada Example").path("markdown").asText();
        assertTrue(md.contains("## What they do\n\nLeads routing at Acme [1]; known for the Widget Router [2][9].\n\nSources: [1] https://www.sec.gov/acme; [2] https://ada.example/about\n"), "the markdown names the sources it cites: " + md);
        // the people a report names lead to their dossiers, or to the run that starts one
        String i5 = store.nextInvestigationId("Corporate history of Acme Corp.: a full dossier.");
        store.write(new Investigation(i5, "Corporate history of Acme Corp.: a full dossier.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(), List.of(),
                "## Question\n\nAcme\n\n## Answer\n\nx [1]\n\n## People\n\n**Ada Example** — Chief Engineer, since 1999 [1].\n**Joshua Sample, MD, MPH** — Board Chair (elected 2026) [1].\n**Board of Directors** — eight members [1].\n\n## References\n\n[1] https://www.sec.gov/acme\n"));
        String acme = DossierPage.render(store, "Acme Corp");
        assertTrue(acme.contains("<h3>People</h3>") && acme.contains("<b><a href=\"/dossier?subject=Ada%20Example\">Ada Example</a></b>"), "a person with a dossier links to it: " + acme.substring(acme.indexOf("<h3>People</h3>"), acme.indexOf("<h3>People</h3>") + 600));
        assertTrue(acme.contains("<b>Joshua Sample, MD, MPH (<a class=\"k\" href=\"/research?q=Who%20is%20Joshua%20Sample%3F%20A%20full%20dossier.&amp;field=person\">start a dossier</a>)</b>"), "a person without one leads to the run, the credentials dropped from the question: " + acme.substring(acme.indexOf("<h3>People</h3>"), acme.indexOf("<h3>People</h3>") + 700));
        assertFalse(acme.contains("Who%20is%20Board%20of%20Directors"), "a board is not a person");
        assertTrue(html.contains("<b>GLEIF legal entity register</b> 2 searches, 2 found records") && html.contains("not searched: ORCID; Hacker News"), html);
        assertTrue(html.contains("<span class=\"y\">1999</span>") && html.contains("cls c-public\">public record</span>"), html);
        assertTrue(html.contains("<tr><td>worked-at</td><td><a href=\"/dossier?subject=Acme%20Corp.\">Acme Corp.</a>"), "the relation, as the vocabulary writes it, linking to the other dossier: " + html.substring(html.indexOf("<h2 id=\"relations\">"), Math.min(html.length(), html.indexOf("<h2 id=\"relations\">") + 600)));
        assertTrue(html.contains("class=\"told\">The team was gone within two years.") && html.contains("did:key:tester"), html);
        assertTrue(html.contains("<a href=\"/entry/" + s1 + "\">The story of Ada Example</a>"), html);
        assertTrue(html.contains("<a href=\"/entry/" + i1 + "\">Who is Ada Example?</a>") && !html.contains("<a href=\"/entry/" + s1 + "\">Who"), html);
        assertTrue(html.contains("<li>Wikidata: Ada Example</li>"), html);
        assertTrue(html.contains("Her title differs between sources."), html);
        assertTrue(html.contains("<b>cite-check</b> 3 cited sentence(s)") && html.contains("<b>references by class</b> 1 public record"), html);
        assertTrue(html.contains("Not yet on this page"), html);
        String none = DossierPage.render(store, "Nobody Known");
        assertTrue(none.contains("holds nothing on <b>Nobody Known</b> yet") && none.contains("/research?q=Who%20is%20Nobody%20Known%3F"), none);
    }
}
