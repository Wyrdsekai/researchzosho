package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The science affordances (2026-09-07): citation records, preprint versions, captions and rows, dataset sources, BibTeX. */
class ScienceAffordancesTest {

    /** A canned record source: the three APIs' shapes, no network. */
    static final Citations.Source CANNED = url -> {
        if (url.contains("/works/10.1038/nature05357")) return "{\"message\":{\"title\":[\"Decoding the ancient Greek astronomical calculator known as the Antikythera Mechanism\"],\"author\":[{\"family\":\"Freeth\",\"given\":\"Tony\"},{\"family\":\"Bitsakis\",\"given\":\"Yanis\"},{\"family\":\"Moussas\",\"given\":\"Xenophon\"}],\"container-title\":[\"Nature\"],\"issued\":{\"date-parts\":[[2006,11,30]]},\"volume\":\"444\",\"page\":\"587-591\"}}";
        if (url.contains("id_list=2504.00327")) return "<feed><title>ArXiv Query</title><entry><id>http://arxiv.org/abs/2504.00327v2</id><updated>2025-06-01T00:00:00Z</updated><published>2025-04-01T00:00:00Z</published><title>The Impact of Triangular-Toothed Gears on the Functionality of the Antikythera Mechanism</title><author><name>Esteban Guillermo Szigety</name></author><author><name>Gustavo Arenas</name></author></entry></feed>";
        if (url.contains("esummary") && url.contains("id=12345678")) return "{\"result\":{\"12345678\":{\"title\":\"A pubmed paper.\",\"authors\":[{\"name\":\"Doe J\"},{\"name\":\"Roe R\"}],\"source\":\"J Test\",\"pubdate\":\"2021 Mar\",\"volume\":\"12\",\"pages\":\"1-9\",\"articleids\":[{\"idtype\":\"doi\",\"value\":\"10.1000/xyz\"}]}}}";
        throw new java.io.IOException("no canned record for " + url);
    };

    @Test
    void identifiersAreRecognisedAndRecordsResolvedThroughTheCache(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        assertEquals("doi:10.1038/nature05357", Citations.identify("https://doi.org/10.1038/nature05357"));
        assertEquals("arxiv:2504.00327v1", Citations.identify("https://arxiv.org/pdf/2504.00327v1"));
        assertEquals("arxiv:2504.00327", Citations.identify("see arXiv:2504.00327 for details"));
        assertEquals("pmid:12345678", Citations.identify("https://pubmed.ncbi.nlm.nih.gov/12345678/"));
        assertNull(Citations.identify("https://example.org/page"));

        Citations.Meta doi = Citations.resolve(store, "https://doi.org/10.1038/nature05357", CANNED);
        assertEquals("Freeth T et al., Nature 444:587-591 (2006)", doi.edition());
        Citations.Meta ax = Citations.resolve(store, "https://arxiv.org/abs/2504.00327", CANNED);
        assertEquals("Szigety EG, Arenas G, arXiv:2504.00327v2 (2025)", ax.edition());
        assertEquals("v2", ax.version());
        Citations.Meta pm = Citations.resolve(store, "https://pubmed.ncbi.nlm.nih.gov/12345678/", CANNED);
        assertEquals("Doe J, Roe R, J Test 12:1-9 (2021); doi:10.1000/xyz", pm.edition());
        // cached: a second resolve never calls the source
        Citations.Meta again = Citations.resolve(store, "https://doi.org/10.1038/nature05357", url -> { throw new IllegalStateException("network"); });
        assertEquals(doi.edition(), again.edition());
        assertEquals(3, Citations.readCache(store).size());
        // a failed lookup caches nothing and resolves to null
        assertNull(Citations.resolve(store, "https://doi.org/10.9999/unknown", CANNED));
        assertEquals(3, Citations.readCache(store).size());
        // enrich fills only the n/a editions
        List<Finding.Source> in = List.of(new Finding.Source("https://doi.org/10.1038/nature05357", "n/a", "cited"), new Finding.Source("cite:Hosaka 2016, p. 47", "Hosaka 2016, p. 47", "cited"), new Finding.Source("https://example.org/p", "n/a", "cited"));
        List<Finding.Source> out = Citations.enrich(store, in, CANNED);
        assertEquals("Freeth T et al., Nature 444:587-591 (2006)", out.get(0).edition());
        assertEquals("Hosaka 2016, p. 47", out.get(1).edition());
        assertEquals("n/a", out.get(2).edition());
    }

    @Test
    void aNewerPreprintVersionBecomesARevisedNoteAndARecallNotice(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Finding f = new Finding(store.nextFindingId("jamming"), "Jamming under measured errors", List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.high, "test", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://arxiv.org/abs/2504.00327v1", "n/a", "cited")), List.of(), null, "The mechanism would jam.\n");
        store.write(f);
        Preprints.Lookup lookup = id -> new String[]{"v3", "2026-05-01"};
        Preprints.Outcome o = Preprints.check(store, lookup, 20, LocalDate.of(2026, 9, 7));
        assertEquals(1, o.checked()); assertEquals(1, o.revised());
        Finding after = store.finding(f.id());
        assertEquals(Finding.State.accepted, after.state(), "a new version is a reason to re-read, not a verdict");
        assertEquals(1, after.notes().size());
        assertEquals("revised", after.notes().get(0).kind());
        assertTrue(after.notes().get(0).text().contains("arXiv:2504.00327v3 published 2026-05-01"), after.notes().get(0).text());
        assertTrue(Changes.since(store, 0, 10).stream().anyMatch(c -> c.event().equals("revised") && c.id().equals(f.id())));
        // checked again within the window: nothing happens; after the window with the same version: no second note
        Preprints.Outcome again = Preprints.check(store, id -> { throw new AssertionError("not due"); }, 20, LocalDate.of(2026, 9, 10));
        assertEquals(0, again.checked());
        Preprints.Outcome later = Preprints.check(store, lookup, 20, LocalDate.of(2026, 10, 1));
        assertEquals(1, later.checked()); assertEquals(0, later.revised());
        assertEquals(1, store.finding(f.id()).notes().size());
    }

    @Test
    void oneAuthorElementHoldingSeveralNamesIsSplit() {
        assertEquals(List.of("Esteban Guillermo Szigety", "Gustavo Francisco Arenas"), Citations.splitAuthors("Esteban Guillermo Szigety y Gustavo Francisco Arenas"));
        assertEquals(List.of("A. Smith", "B. Jones"), Citations.splitAuthors("A. Smith and B. Jones"));
        assertEquals(List.of("Andrea Ferrand"), Citations.splitAuthors("Andrea Ferrand"), "a name that merely contains 'and' is one name");
        assertEquals("Szigety EG", Citations.shortName("Esteban Guillermo Szigety"));
    }

    @Test
    void captionsAndNumericRowsAreLiftedOutOfPaperText() {
        String text = "Abstract. We measure teeth.\n\nFigure 1: Tooth profile of gear B1, CT slice.\n" +
                "Table 2. Manufacturing errors by gear (degrees).\nGear   Teeth   SD\nB1     223     1.02\nB2     64      0.98\nC1     38      1.10\n" +
                "As shown in table 2 the errors are about 1 degree.\n図 3: 歯車の断面\n";
        Captions.Extract x = Captions.extract(text);
        assertEquals(List.of("Figure 1: Tooth profile of gear B1, CT slice.", "図 3: 歯車の断面"), x.figures());
        assertEquals(List.of("Table 2: Manufacturing errors by gear (degrees)."), x.tables());
        assertEquals(List.of("B2 | 64 | 0.98", "C1 | 38 | 1.10"), x.rows(), "rows count from the second numeric line in a run");
        assertTrue(Captions.extract("just prose, no figures").isEmpty());
    }

    @Test
    void datasetRecordsArePrimarySources() {
        assertEquals(SourceTier.primary, SourceTier.of("https://zenodo.org/records/1234567"));
        assertEquals(SourceTier.primary, SourceTier.of("https://osf.io/abcde/"));
        assertEquals(SourceTier.primary, SourceTier.of("https://huggingface.co/datasets/x/y"));
        assertTrue(SourceTier.primary.autoPromotes());
    }

    @Test
    void bibtexComesFromTheRecordWhereThereIsOne(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        List<Finding.Source> srcs = List.of(
                new Finding.Source("https://doi.org/10.1038/nature05357", "n/a", "cited by I-0014"),
                new Finding.Source("https://arxiv.org/abs/2504.00327", "n/a", "cited by I-0014"),
                new Finding.Source("https://doi.org/10.1038/nature05357", "n/a", "twice"),
                new Finding.Source("cite:Hosaka 2016, p. 47", "Hosaka 2016, p. 47", "cited by I-0009"),
                new Finding.Source("https://example.org/page", "n/a", "cited by I-0014"));
        String bib = Bibliography.bibtex(store, srcs, CANNED);
        assertTrue(bib.contains("@article{doi_10_1038_nature05357,"), bib);
        assertTrue(bib.contains("author = {Freeth T and Bitsakis Y and Moussas X}"), bib);
        assertTrue(bib.contains("journal = {Nature}") && bib.contains("volume = {444}") && bib.contains("pages = {587-591}"), bib);
        assertTrue(bib.contains("@misc{arxiv_2504_00327,") && bib.contains("eprint = {2504.00327v2}"), bib);
        assertTrue(bib.contains("howpublished = {Hosaka 2016, p. 47}"), bib);
        assertTrue(bib.contains("url = {https://example.org/page}"), bib);
        assertEquals(1, bib.split("doi_10_1038_nature05357,").length - 1, "deduplicated by key");
    }
}
