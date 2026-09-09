package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The first profile written against the boundary: GEDCOM in and out, living persons private, nothing accepted by import. */
class GenealogyProfileTest {

    static final String GED = "0 HEAD\n1 GEDC\n2 VERS 7.0\n1 CHAR UTF-8\n"
            + "0 @I1@ INDI\n1 NAME Mara /Ellis/\n1 SEX F\n1 FAMC @F1@\n1 BIRT\n2 DATE 18 APR 1992\n2 PLAC Wellington, New Zealand\n"
            + "0 @I2@ INDI\n1 NAME Simon /Ellis/\n1 SEX M\n1 FAMS @F1@\n1 FAMC @F3@\n1 BIRT\n2 DATE 12 SEP 1964\n2 PLAC Dunedin, New Zealand\n"
            + "0 @I3@ INDI\n1 NAME Ana /Rangi/\n1 SEX F\n1 FAMS @F1@\n1 BIRT\n2 DATE 2 FEB 1966\n2 PLAC Rotorua, New Zealand\n"
            + "0 @I4@ INDI\n1 NAME Arthur /Ellis/\n1 SEX M\n1 FAMS @F3@\n1 BIRT\n2 DATE 1934\n2 PLAC Christchurch, New Zealand\n1 IMMI\n2 DATE 1952\n2 PLAC Lyttelton, New Zealand\n1 DEAT\n2 DATE 2011\n2 PLAC Dunedin, New Zealand\n1 OCCU Fitter and turner\n"
            + "0 @I5@ INDI\n1 NAME Rose /Morgan/\n1 SEX F\n1 FAMS @F3@\n1 BIRT\n2 DATE 1938\n2 PLAC Dunedin, New Zealand\n1 DEAT\n2 DATE 2019\n2 PLAC Dunedin, New Zealand\n"
            + "0 @F1@ FAM\n1 HUSB @I2@\n1 WIFE @I3@\n1 CHIL @I1@\n1 MARR\n2 DATE 9 JAN 1988\n2 PLAC Wellington, New Zealand\n"
            + "0 @F3@ FAM\n1 HUSB @I4@\n1 WIFE @I5@\n1 CHIL @I2@\n"
            + "0 TRLR\n";

    @Test
    void theCoreKnowsNothingOfKinshipUntilTheProfileIsEnabled(@TempDir Path home) throws Exception {
        Path tmp = home.resolve("researchzosho-library");
        LibraryStore store = new LibraryStore(tmp); store.init();
        assertEquals(java.util.Set.of("science"), Profiles.enabled(store), "science on by default, nothing else");
        assertTrue(Vocabulary.read(Graph.predicatesFile(store)).isEmpty(), "no predicates seeded by the core");
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());   // the command opens ~/researchzosho-library
        try {
            assertEquals(2, LibrarianCli.run(new String[]{"librarian", "genealogy", "import", "x.ged"}, "http://127.0.0.1:1", "m"), "a disabled profile's verb is refused");
        } finally { System.setProperty("user.home", real); }
        Profiles.enable(store, "genealogy");
        assertTrue(Profiles.isEnabled(store, "genealogy"));
        assertEquals("married-to", Vocabulary.read(Graph.predicatesFile(store)).resolve("spouse of"));
        assertTrue(Files.readString(store.root().resolve("catalog").resolve("library.md")).contains("profiles: science, genealogy"));
        Profiles.disable(store, "genealogy");
        assertFalse(Profiles.isEnabled(store, "genealogy"));
    }

    @Test
    void gedcomImportsAsNodesAndDraftFindingsWithTheFileAsSourceAndExportsBack(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Profiles.enable(store, "genealogy");
        Path ged = tmp.resolve("sample.ged");
        Files.writeString(ged, GED, StandardCharsets.UTF_8);
        Gedcom.Outcome o = Gedcom.importFile(store, ged, false);
        assertEquals(5, o.persons()); assertEquals(2, o.families());
        assertEquals(3, o.privateNodes(), "Mara, Simon, Ana are living: private");
        // born-in ×5, migrated ×1, died ×2, occupation ×1, married ×2 (a FAM with HUSB and WIFE is a marriage, dated or not), parent-of ×4 = 15
        assertEquals(15, o.findings(), "one draft finding per edge");
        for (Finding f : store.scanFindings().findings()) {
            assertEquals(Finding.State.draft, f.state(), "nothing is accepted by import: " + f.id());
            assertEquals("gedcom-import", f.writer());
            assertTrue(f.sources().get(0).locator().startsWith("file://"), "the file is the source");
            assertNotNull(f.triple());
        }
        Graph g = Graph.build(store);
        assertEquals("person", g.node("arthur ellis").kind());
        assertEquals("place", g.node("lyttelton, new zealand").kind());
        assertFalse(g.node("arthur ellis").privateNode(), "died 2011");
        assertTrue(g.node("mara ellis").privateNode());
        Graph.Neighbourhood nb = g.around("Arthur Ellis", 1, 50, false);
        assertTrue(nb.nodes().stream().anyMatch(n -> n.id().equals("rose morgan")));
        assertTrue(nb.nodes().stream().noneMatch(n -> n.id().equals("simon ellis")), "the living son is hidden from a patron");
        assertTrue(g.around("Arthur Ellis", 1, 50, true).nodes().stream().anyMatch(n -> n.id().equals("simon ellis")), "and shown to the person");
        // a second import adds nothing
        assertEquals(0, Gedcom.importFile(store, ged, false).findings());
        // export the subgraph: the dead couple and, with the flag, the living
        String out = Gedcom.export(store, "Arthur Ellis", false);
        assertTrue(out.contains("1 NAME Arthur Ellis") && out.contains("1 NAME Rose Morgan"), out);
        assertFalse(out.contains("Simon Ellis"), "living excluded by default");
        assertTrue(out.contains("1 IMMI\n2 DATE 1952\n2 PLAC Lyttelton, New Zealand"), out);
        assertTrue(out.contains("1 OCCU Fitter and turner"), out);
        String all = Gedcom.export(store, "Arthur Ellis", true);
        assertTrue(all.contains("Simon Ellis") && all.contains("Mara Ellis"), all);
        assertTrue(all.contains("0 @F1@ FAM") && all.contains("1 CHIL"), all);
        // the discovery: a patent on a computer-science shelf meets the family at the person node
        Finding patent = new Finding(store.nextFindingId("patent"), "Arthur Ellis patent", java.util.List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.high, "test", java.time.Instant.now().toString(), java.time.LocalDate.now().toString(), Finding.Volatility.stable, "",
                java.util.List.of(new Finding.Source("https://patents.google.com/patent/NZ000001", "n/a", "cited")), java.util.List.of(), null, "Arthur Ellis holds NZ patent 000001 on a route-mapping method.\n",
                new Finding.Triple("Arthur Ellis", "patented", "route-mapping method"), java.util.List.of());
        store.write(patent);
        Graph.Neighbourhood two = Graph.build(store).around("route-mapping method", 2, 50, false);
        assertTrue(two.nodes().stream().anyMatch(n -> n.id().equals("lyttelton, new zealand")), "from the algorithm to the migration in two hops");
    }
}
