package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The analysis layer (0.6.6): conjectures and forecasts read with their likelihood, the forecasts on a ledger and scored, the questions on the frontier, none of it on the shelves. */
class AnalysisTest {
    static final String BODY = "## Question\n\nCorporate history of Acme Corp.: a full dossier.\n\n## Answer\n\nAcme makes widgets [1].\n\n## Filings\n\n2020: Founded [1].\n\n"
            + "## Judgments\n\nAcme depends on one customer for most of its revenue [1][2].\n\n"
            + "## Conjectures\n\nC1. Acme will seek a second anchor customer within the year. Likelihood: likely (65%). Confidence: moderate, because the two sources agree but both are the company's own. Basis: [1][2]. Alternatives: the customer takes a stake instead. Would change this: a hiring of a sales lead; a new logo on the site.\n"
            + "C2. The founder intends to sell. Likelihood: unlikely (30%). Confidence: low, because one interview. Basis: [3]. Alternatives: raising a round. Would change this: a banker named in filings.\n\n"
            + "## Forecasts\n\nF1. By 2027-06: Acme announces a second customer worth a fifth of revenue. Likelihood: roughly even chance (50%). Confidence: low, because the pipeline is unknown. Base rate: a third of such suppliers do within two years [4]. Would change this: a job posting for enterprise sales.\n\n"
            + "## Implications and options\n\nA reader weighing a supplier contract has two options: wait for the second customer, or contract now with a volume clause.\n\n"
            + "## What would change this view\n\n- Who is Acme's second-largest customer, and how large?\n- Has the founder taken outside money since 2024?\n- A note that is not a question.\n\n"
            + "## Source summary\n\nFour sources: two own publications, two third-party; the key judgment rests on the company's own figures.\n\n"
            + "## Sources\n\n[1] https://acme.example/about\n[2] https://acme.example/filings\n[3] https://news.example/founder\n[4] https://papers.example/suppliers\n";

    @Test
    void conjecturesAndForecastsAreReadWithTheirLikelihoodConfidenceAndIndicators() {
        List<Analysis.Conjecture> c = Analysis.conjectures(BODY);
        assertEquals(2, c.size(), c.toString());
        assertEquals("Acme will seek a second anchor customer within the year", c.get(0).text());
        assertEquals("likely", c.get(0).likelihood()); assertEquals(65, c.get(0).percent()); assertEquals("moderate", c.get(0).confidence());
        assertEquals("[1][2]", c.get(0).basis()); assertEquals("the customer takes a stake instead", c.get(0).alternatives());
        assertEquals("a hiring of a sales lead; a new logo on the site", c.get(0).indicators());
        assertEquals("unlikely", c.get(1).likelihood()); assertEquals(30, c.get(1).percent()); assertEquals("low", c.get(1).confidence());
        List<Analysis.Forecast> f = Analysis.forecasts(BODY);
        assertEquals(1, f.size(), f.toString());
        assertEquals("2027-06", f.get(0).by()); assertEquals("Acme announces a second customer worth a fifth of revenue", f.get(0).text());
        assertEquals("roughly even chance", f.get(0).likelihood()); assertEquals(50, f.get(0).percent()); assertTrue(f.get(0).baseRate().startsWith("a third of such suppliers"), f.get(0).baseRate());
        assertEquals(List.of("Who is Acme's second-largest customer, and how large?", "Has the founder taken outside money since 2024?"), Analysis.questions(BODY));
        assertEquals(Likelihood.VERY_UNLIKELY, Likelihood.ofWords("very unlikely")); assertEquals(Likelihood.UNLIKELY, Likelihood.ofWords("Likelihood: unlikely (30%)"));
        assertEquals(Likelihood.LIKELY, Likelihood.ofWords("probable")); assertEquals(Likelihood.VERY_UNLIKELY, Likelihood.ofWords("highly improbable")); assertNull(Likelihood.ofWords("no idea"));
        assertEquals(Likelihood.ALMOST_CERTAIN, Likelihood.ofPercent(97)); assertEquals(Likelihood.ALMOST_NO_CHANCE, Likelihood.ofPercent(5)); assertEquals(Likelihood.ROUGHLY_EVEN, Likelihood.ofPercent(50));
        assertTrue(Likelihood.table().startsWith("almost no chance (1–5%), very unlikely (5–20%)") && Likelihood.table().endsWith("almost certain (95–99%)"), Likelihood.table());
        String cut = Analysis.withoutAnalysis(BODY);
        assertFalse(cut.contains("## Conjectures") || cut.contains("Acme will seek") || cut.contains("## Forecasts") || cut.contains("## Judgments"), cut);
        assertTrue(cut.contains("## Filings") && cut.contains("## Sources") && cut.contains("## Answer"), cut);
        assertEquals(0, Analysis.conjectures("## Conjectures\n\nNone the record supports.\n").size(), "the line that says there are none is not one");
    }

    @Test
    void forecastsGoToTheLedgerOnceAndAreScoredAndTheQuestionsToTheFrontier(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String i1 = store.nextInvestigationId("Corporate history of Acme Corp.: a full dossier.");
        Investigation inv = new Investigation(i1, "Corporate history of Acme Corp.: a full dossier.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(), List.of(), BODY);
        store.write(inv);
        List<String> did = Analysis.file(store, inv, "Acme Corp");
        assertEquals(2, did.size(), did.toString());
        List<Forecasts.Entry> ledger = Forecasts.of(store, "Acme Corp");
        assertEquals(1, ledger.size()); assertEquals("P-0001", ledger.get(0).id()); assertEquals("2027-06", ledger.get(0).by()); assertEquals(50, ledger.get(0).percent()); assertNull(ledger.get(0).outcome());
        assertEquals(0, Analysis.file(store, inv, "Acme Corp").size(), "a second landing files nothing");
        assertEquals(1, Forecasts.all(store).size());
        assertTrue(Frontier.read(store).stream().anyMatch(l -> l.text().equals("Who is Acme's second-largest customer, and how large?")), "the question that would change the view is on the frontier");
        assertNull(Forecasts.brier(ledger), "nothing scored yet");
        Forecasts.Entry scored = Forecasts.score(store, "P-0001", "happened", "announced in May 2027");
        assertEquals("happened", scored.outcome()); assertEquals("announced in May 2027", scored.note());
        assertEquals(0.25, Forecasts.brier(Forecasts.of(store, "Acme Corp")), 1e-9, "(0.5 - 1)^2");
        assertThrows(IllegalArgumentException.class, () -> Forecasts.score(store, "P-0001", "maybe", ""));
        assertNull(Forecasts.score(store, "P-0099", "happened", ""));
        assertTrue(Forecasts.text(Forecasts.all(store)).contains("P-0001 — Acme Corp, by 2027-06: Acme announces a second customer worth a fifth of revenue — roughly even chance (50%), confidence low — happened"), Forecasts.text(Forecasts.all(store)));
        // the dossier and its page carry the layer
        String md = Dossier.of(store, "Acme Corp").path("markdown").asText();
        assertTrue(md.contains("## Analysis") && md.contains("### Judgments") && md.contains("- Acme will seek a second anchor customer within the year — likely (65%), confidence moderate") && md.contains("P-0001 — by 2027-06") && md.contains("Brier score"), md);
        String html = DossierPage.render(store, "Acme Corp");
        assertTrue(html.contains("<h2 id=\"analysis\">Analysis</h2>") && html.contains("<b>likely</b>, 65%") && html.contains("<td>P-0001</td>") && html.contains("Brier score over the scored forecasts: 0.250"), html);
        // the protocol
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode a = new ObjectMapper().createObjectNode(); a.put("op", "forecasts"); a.put("subject", "Acme Corp");
        assertEquals(1, p.dossier(a).path("count").asInt()); assertEquals(0.25, p.dossier(a).path("brier").asDouble(), 1e-9);
        // the extractor reads the report without the layer
        assertFalse(LibrarianReview.forExtraction(BODY, 100000).contains("Conjectures"), "no conjecture reaches the extractor");
        // the dossier lists the questions filed for it, whoever they name
        assertEquals(2, Dossier.of(store, "Acme Corp").path("questions").asInt(), "both questions, though the second does not name Acme");
        // the writer's own numbers in the layer are not numbers a source must state
        assertTrue(Analysis.inLayer(BODY, "Likelihood: likely (65%)") && Analysis.inLayer(BODY, "a third of such suppliers do within two years [4]"));
        assertFalse(Analysis.inLayer(BODY, "Acme makes widgets [1]."));
        // the library's source summary when the writer skipped it
        String refs = "## Answer\n\nAcme makes widgets [1][2].\n\n## Looked for and not found\n\nGLEIF: Acme\nCourtListener: Acme\n\n## Cite-check\n\ncite-check: 12 cited sentence(s) read against their source — 10 supported, 1 not supported (marked), 1 undecidable from the excerpt; 3 parenthetical(s) named no source; 2 reference(s).\n\n"
                + "## References\n\n[1] Acme Corp, Form 10-K 2025 — https://www.sec.gov/acme  (published 2025-09-01)  [public record]\n[2] Acme Corp | Cause IQ — https://www.causeiq.com/acme  (dated 2025)  [third-party]  (cites [1])\n";
        String sum = Analysis.sourceSummary(refs);
        assertTrue(sum.startsWith("2 references (1 public record, 1 third-party). The Reading rests on [1] Acme Corp, Form 10-K 2025; [2] Acme Corp | Cause IQ. Of 12 cited sentences, 10 were supported by their source and 1 were not. Looked for and not found: 2 searches."), sum);
        assertEquals("", Analysis.sourceSummary("## Answer\n\nx\n"), "no classed references, no summary");
        // watch: a standing search on the subject
        ObjectNode w = new ObjectMapper().createObjectNode(); w.put("op", "watch"); w.put("subject", "Acme Corp");
        assertEquals("dossier-acme-corp", p.dossier(w).path("slug").asText());
        assertTrue(Serials.shelves(store).stream().anyMatch(sh -> sh.slug().equals("dossier-acme-corp") && sh.query().equals("Acme Corp") && sh.everyDays() == 7));
        assertTrue(DossierPage.render(store, "Acme Corp").contains("Watched: a search for the subject every 7 days"), "the page says it is watched");
    }

    @Test
    void aForecastNamesAnEventThatWillOrWillNotHaveHappenedByItsDate() {
        assertTrue(Analysis.WRITER_RULES.contains("will or will not have happened by that date") && Analysis.WRITER_RULES.contains("is no forecast"));
    }
}
