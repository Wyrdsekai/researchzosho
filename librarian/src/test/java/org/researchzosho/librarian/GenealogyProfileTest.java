package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.researchzosho.librarian.profiles.GenealogyProfile;
/** The first profile written against the boundary: GEDCOM in and out, the living written in full, nothing accepted by import. */
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
    void everyProfileIsOnUntilTheLibrarySaysOtherwise(@TempDir Path home) throws Exception {
        Path tmp = home.resolve("researchzosho-library");
        LibraryStore store = new LibraryStore(tmp); store.init();
        assertEquals(Set.of("science", "genealogy", "software", "youtube", "podcasts"), Profiles.enabled(store), "no profiles: line, every profile on");
        assertTrue(Vocabulary.read(Graph.predicatesFile(store)).isEmpty(), "being on writes nothing");
        assertEquals("married-to", FamilyPeople.view(store).predicateOf("spouse of"), "genealogy's own view resolves a kinship wording with no enable step");
        assertEquals("adopted-by", FamilyPeople.view(store).predicateOf("婿養子"));
        assertEquals("spouse of", Graph.build(store).predicateOf("spouse of"), "and the library's own map keeps an ordinary claim's words as written");
        Profiles.disable(store, "genealogy");
        assertFalse(Profiles.isEnabled(store, "genealogy"));
        assertTrue(Files.readString(store.root().resolve("catalog").resolve("library.md")).contains("profiles: science, software"));
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());   // the command opens ~/researchzosho-library
        try {
            assertEquals(2, LibrarianCli.run(new String[]{"librarian", "genealogy", "import", "x.ged"}, "http://127.0.0.1:1", "m"), "a disabled profile's verb is refused");
        } finally { System.setProperty("user.home", real); }
        Profiles.enable(store, "genealogy");
        assertTrue(Profiles.isEnabled(store, "genealogy"));
        assertNull(Vocabulary.read(Graph.predicatesFile(store)).resolve("spouse of"), "enabling a module writes nothing into the library's own relations");
        assertEquals("married-to", FamilyPeople.view(store).predicateOf("spouse of"));
    }

    @Test
    void theRelationsForNamesAndFamiliesAreGenealogysAndAn046BlockIsStillTakenBackOut(@TempDir Path tmp) throws Exception {
        Vocabulary mine = new Vocabulary();
        for (Vocabulary.Term t : new GenealogyProfile().predicates()) mine.put(t);
        for (String slug : List.of("has-name", "member-of", "parent-in-law-of", "heir-of", "branch-of", "family-seat", "founded-by"))
            assertNotNull(mine.get(slug), "genealogy has the relation " + slug);
        assertEquals("adopted-by", mine.resolve("婿養子"), "婿養子 stays an adoption: the adoption half is one");
        assertEquals("married-to", mine.resolve("入夫"));
        assertEquals("has-name", mine.resolve("maiden name"));
        assertEquals("member-of", mine.resolve("entered the family"));
        assertEquals("parent-in-law-of", mine.resolve("義父"));
        assertTrue(GenealogyBoundaryTest.ownLiterals().containsAll(List.of("has-name", "member-of", "family-seat")), "and the core may not write them");
        // a library 0.4.6 turned genealogy on in: the block it wrote is still recognised, with today's longer list of relations
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Files.createDirectories(Graph.dir(store));
        Files.writeString(Graph.predicatesFile(store), GenealogyModuleTest.PREDICATES_046 + "- funded-by — is funded by | also: sponsored by\n", StandardCharsets.UTF_8);
        List<String> said = new GenealogyProfile().tidy(store);
        assertEquals(1, said.size(), "the 0.4.6 block is taken back out");
        assertTrue(said.get(0).contains("9 of them"), said.toString());
        Vocabulary left = Vocabulary.read(Graph.predicatesFile(store));
        assertNull(left.get("parent-of"));
        assertNotNull(left.get("funded-by"), "the owner's own relation stays");
    }

    @Test
    void aTreeExportedFromAFamilyTreeSiteKeepsItsCitationsNotesDatesAndAdoptions(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path ged = tmp.resolve("aunt.ged");
        Files.writeString(ged, "0 HEAD\n1 SOUR Geni.com\n"
                + "0 @I1@ INDI\n1 NAME 正一 /髙橋/\n1 SEX M\n1 BIRT\n2 DATE 12 MAR 1908\n2 SOUR @S1@\n3 PAGE 本籍 広島県佐伯郡, entry 3\n1 DEAT\n2 DATE 1975\n1 NOTE Worked at the Kure shipyard.\n2 CONT Told by his daughter.\n1 FAMC @F1@\n"
                + "0 @I2@ INDI\n1 NAME 勇 /髙橋/\n1 SEX M\n1 DEAT\n2 DATE 1960\n1 FAMC @F1@\n1 FAMC @F2@\n2 PEDI adopted\n"
                + "0 @I3@ INDI\n1 NAME 源三郎 /髙橋/\n1 SEX M\n1 FAMS @F1@\n1 DEAT\n2 DATE 1945\n2 PLAC 広島\n"
                + "0 @I4@ INDI\n1 NAME 喜平 /渡邊/\n1 SEX M\n1 FAMS @F2@\n1 DEAT\n2 DATE 1930\n"
                + "0 @F1@ FAM\n1 HUSB @I3@\n1 CHIL @I1@\n1 CHIL @I2@\n0 @F2@ FAM\n1 HUSB @I4@\n1 CHIL @I2@\n"
                + "0 @S1@ SOUR\n1 TITL 戸籍謄本 (明治31年式)\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.importFile(store, ged);
        List<Finding> all = store.scanFindings().findings();
        Finding born = all.stream().filter(f -> f.triple().predicate().equals("born-on")).findFirst().orElseThrow();
        assertEquals("12 MAR 1908", born.triple().object(), "a birth with a date and no place used to be dropped");
        assertTrue(born.sources().get(0).edition().contains("cited there: 戸籍謄本 (明治31年式), 本籍 広島県佐伯郡, entry 3"), born.sources().get(0).edition());
        assertTrue(born.body().contains("The file's note on 髙橋正一: Worked at the Kure shipyard. Told by his daughter."), born.body());
        assertTrue(all.stream().anyMatch(f -> f.triple().predicate().equals("adopted-by") && f.triple().subject().equals("髙橋勇") && f.triple().object().equals("渡邊喜平")), "PEDI adopted");
        assertTrue(all.stream().anyMatch(f -> f.triple().predicate().equals("parent-of") && f.triple().object().equals("髙橋勇") && f.triple().subject().equals("髙橋源三郎")), "and he is still his birth father's son");
        assertTrue(all.stream().noneMatch(f -> f.triple().predicate().equals("parent-of") && f.triple().subject().equals("渡邊喜平")));
        assertEquals("髙橋正一", Gedcom.personName("正一 /髙橋/"), "written as the family writes it, whatever order the file's form imposes");
        assertEquals("Arthur Ellis Jr.", Gedcom.personName("Arthur /Ellis/ Jr."));
        FamilyTree.Tree t = FamilyTree.around(store, "髙橋正一", 5, 4);
        assertEquals("1908 1975", t.focus().born() + " " + t.focus().died());
    }

    @Test
    void aFileOfNothingButAddressesIsAListOfPagesToRead(@TempDir Path tmp) throws Exception {
        Path urls = tmp.resolve("pages.txt");
        Files.writeString(urls, "# relatives on Wikipedia\nhttps://ja.wikipedia.org/wiki/A   # this is about my grandfather\n\nhttps://example.org/family-page\n");
        assertEquals(List.of("https://ja.wikipedia.org/wiki/A\tthis is about my grandfather", "https://example.org/family-page"), GenealogyProfile.urlList(urls), "an address may carry a note after a #");
        Path notes = tmp.resolve("notes.txt");
        Files.writeString(notes, "My grandfather was born in Hiroshima.\nSee https://example.org/x for his school.\n");
        assertTrue(GenealogyProfile.urlList(notes).isEmpty(), "notes that mention an address are notes");
    }

    @Test
    void aFamilyQuestionIsRecognisedByItsWordsOrByAPersonInTheTree(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        assertEquals(List.of("genealogy"), Fields.recognised(store, "Who were my great-grandfather's parents?"));
        assertEquals(List.of("genealogy"), Fields.recognised(store, "曾祖父の戸籍はどこで請求できますか"));
        assertTrue(Fields.recognised(store, "How does a transformer's attention scale with sequence length?").isEmpty());
        assertTrue(Fields.recognised(store, "What did Arthur Ellis patent in Dunedin?").isEmpty(), "nobody in the tree yet, no family word");
        Path ged = tmp.resolve("sample.ged");
        Files.writeString(ged, GED, StandardCharsets.UTF_8);
        Gedcom.importFile(store, ged);
        assertEquals(List.of("genealogy"), Fields.recognised(store, "What did Arthur Ellis patent in Dunedin?"), "the tree names him");
        Profiles.disable(store, "genealogy");
        assertTrue(Fields.recognised(store, "Who were my great-grandfather's parents?").isEmpty(), "a disabled profile never applies");
    }

    @Test
    void gedcomImportsAsNodesAndDraftFindingsWithTheFileAsSourceAndExportsBack(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Profiles.enable(store, "genealogy");
        Path ged = tmp.resolve("sample.ged");
        Files.writeString(ged, GED, StandardCharsets.UTF_8);
        Gedcom.Outcome o = Gedcom.importFile(store, ged);
        assertEquals(5, o.persons()); assertEquals(2, o.families());
        assertEquals(3, o.mayBeLiving(), "Mara, Simon, Ana may be living");
        // born-in ×5, migrated ×1, died ×2, occupation ×1, married ×2 (a FAM with HUSB and WIFE is a marriage, dated or not), parent-of ×4, sex ×5 = 20
        assertEquals(20, o.findings(), "one draft finding per edge");
        for (Finding f : store.scanFindings().findings()) {
            assertEquals(Finding.State.draft, f.state(), "nothing is accepted by import: " + f.id());
            assertEquals("gedcom-import", f.writer());
            assertTrue(f.sources().get(0).locator().startsWith("file://"), "the file is the source");
            assertNotNull(f.triple());
        }
        Graph g = Graph.build(store);
        assertEquals("person", g.node("arthur ellis").kind());
        assertEquals("place", g.node("lyttelton, new zealand").kind());
        assertFalse(g.node("arthur ellis").mayBeLiving(), "died 2011");
        assertTrue(g.node("mara ellis").mayBeLiving());
        Graph.Neighbourhood nb = g.around("Arthur Ellis", 1, 50);
        assertTrue(nb.nodes().stream().anyMatch(n -> n.id().equals("rose morgan")));
        assertTrue(nb.nodes().stream().anyMatch(n -> n.id().equals("simon ellis")), "the living son is on the map like anybody else");
        // a second import adds nothing
        assertEquals(0, Gedcom.importFile(store, ged).findings());
        // export the subgraph: everyone in the family, the living too
        String out = Gedcom.export(store, "Arthur Ellis");
        assertTrue(out.contains("1 NAME Arthur /Ellis/") && out.contains("1 NAME Rose /Morgan/"), out);
        assertTrue(out.contains("1 IMMI\n2 DATE 1952\n2 PLAC Lyttelton, New Zealand"), out);
        assertTrue(out.contains("1 OCCU Fitter and turner"), out);
        assertTrue(out.contains("Simon /Ellis/") && out.contains("Mara /Ellis/"), out);
        assertTrue(out.contains("0 @F1@ FAM") && out.contains("1 CHIL"), out);
        // the discovery: a patent on a computer-science shelf meets the family at the person node
        Finding patent = new Finding(store.nextFindingId("patent"), "Arthur Ellis patent", List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.high, "test", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://patents.google.com/patent/NZ000001", "n/a", "cited")), List.of(), null, "Arthur Ellis holds NZ patent 000001 on a route-mapping method.\n",
                new Finding.Triple("Arthur Ellis", "patented", "route-mapping method"), List.of());
        store.write(patent);
        Graph.Neighbourhood two = Graph.build(store).around("route-mapping method", 2, 50);
        assertTrue(two.nodes().stream().anyMatch(n -> n.id().equals("lyttelton, new zealand")), "from the algorithm to the migration in two hops");
    }
}
