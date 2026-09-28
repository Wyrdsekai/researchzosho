package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A parent claim whose own words say adoptive is adoptive in the family's view: a claim an older reader filed as parent-of or child-of from
 * words that say 婿養子, 養父, "adoptive", a step-parent, a parent-in-law, a godparent or a foster parent, with no plain parent word, is read
 * as that relation in genealogy's view only, no question is raised for it, and the person's page says so. A plain "father" stays a birth
 * parent, and a claim the family accepted is read as it stands.
 */
class FamilyReadAsTheWordsSayTest {

    private static FamilyAccount.Fact said(String s, String r, String o, String quote) { return new FamilyAccount.Fact(s, r, o, "", quote); }

    private static LibraryStore store(Path tmp, FamilyAccount.Fact... facts) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(facts), List.of()), "https://ja.example.org/wiki/x", "a page");
        return store;
    }

    private static List<String> edges(Graph g, String... predicates) {
        Set<String> want = Set.of(predicates);
        return g.edges().stream().filter(e -> want.contains(e.predicate()) && !e.disputed()).map(e -> g.node(e.from()).label() + " " + e.predicate() + " " + g.node(e.to()).label()).sorted().toList();
    }

    @Test
    void wordsThatSayAdoptiveReadAsAdoptiveAndRaiseNoBirthFatherQuestion(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp,
                said("森田健二", "child-of", "森田勇", "養父：森田勇（婿養子として森田家に入る）"),
                said("森田健二", "child-of", "遠藤正一", "父：遠藤正一"),
                said("森田健二", "born-on", "1905", "1905年に生まれた"));
        Graph g = FamilyPeople.view(store);
        assertEquals(List.of("森田健二 adopted-by 森田勇", "森田健二 child-of 遠藤正一"), edges(g, "child-of", "parent-of", "adopted-by"), "the adoptive father is read as adoptive, the birth father stays");
        assertEquals(1, FamilyKin.parents(g).get(g.nodeIdOf("森田健二")).size(), "one birth parent");
        String checks = FamilyChecks.render(FamilyChecks.check(store));
        assertFalse(checks.contains("two birth fathers"), checks);
        assertTrue(FamilyNameQuestions.about(store, g, Set.of(g.nodeIdOf("森田健二"), g.nodeIdOf("森田勇"), g.nodeIdOf("遠藤正一"))).stream().noneMatch(q -> q.text().contains("birth")), "no birth-father question");
        List<String> about = FamilyDoubts.readAsAbout(store, g, g.nodeIdOf("森田勇"));
        assertEquals(1, about.size(), about.toString());
        assertTrue(about.get(0).contains("森田健二 is a child of 森田勇") && about.get(0).contains("is read as adoptive: the words say 養父"), about.get(0));
        assertTrue(FamilyDoubts.readAsAbout(store, g, g.nodeIdOf("遠藤正一")).isEmpty(), "a plain father is read as it stands");
        String page = FamilyWhoATextIsByTest.run(store, "names", "森田勇");
        assertTrue(page.contains(FamilyDoubts.READ_AS_HEADING + ":") && page.contains("read as adoptive"), page);
        // the claim itself and the core graph are unchanged
        Graph core = Graph.build(store);
        assertEquals(List.of("森田健二 child-of 森田勇", "森田健二 child-of 遠藤正一"), edges(core, "child-of", "adopted-by"));
        assertTrue(store.scanFindings().findings().stream().anyMatch(f -> f.triple() != null && f.triple().predicate().equals("child-of") && f.triple().object().equals("森田勇")));
    }

    @Test
    void aStepParentAParentInLawAGodparentAndAFosterParentReadAsTheirOwnRelations(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp,
                said("Tom Hale", "parent-of", "Ann Hale", "Her stepfather Tom Hale kept a shop."),
                said("Ann Hale", "child-of", "Ken Ellis", "her father-in-law Ken Ellis was a printer"),
                said("Ned Hart", "parent-of", "Ann Hale", "her godfather Ned Hart was a rector"),
                said("Ruth Hale", "child-of", "Mary Ellis", "her foster mother Mary Ellis kept the books"),
                said("Isamu Morita", "parent-of", "Ruth Hale", "Ruth Hale's adoptive father Isamu Morita farmed at Sendai"),
                said("Ken Hale", "parent-of", "Ann Hale", "my mother's grandfather Ken Hale"));
        Graph g = FamilyPeople.view(store);
        assertEquals(List.of("Ken Ellis parent-in-law-of Ann Hale", "Ken Hale relative-of Ann Hale", "Ned Hart godparent-of Ann Hale", "Ruth Hale adopted-by Isamu Morita", "Ruth Hale foster-child-of Mary Ellis", "Tom Hale step-parent-of Ann Hale"),
                edges(g, "step-parent-of", "parent-in-law-of", "godparent-of", "foster-child-of", "adopted-by", "relative-of"));
        assertTrue(edges(g, "parent-of", "child-of").isEmpty(), "no birth parent among them: " + edges(g, "parent-of", "child-of"));
        assertTrue(FamilyDoubts.readAsAbout(store, g, g.nodeIdOf("Tom Hale")).get(0).contains("read as a step-parent"));
        assertTrue(FamilyDoubts.readAsAbout(store, g, g.nodeIdOf("Ken Hale")).get(0).contains("read as a relative: the words say a grandparent"));
    }

    @Test
    void anAcceptedClaimAndATreeFilesClaimAreReadAsTheyStand(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, said("森田健二", "child-of", "森田勇", "養父：森田勇"));
        Finding claim = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("child-of")).findFirst().orElseThrow();
        new Council(store).accept(claim.id());
        Graph g = FamilyPeople.view(store);
        assertEquals(List.of("森田健二 child-of 森田勇"), edges(g, "child-of", "adopted-by"), "the family's word stands");
        assertTrue(FamilyDoubts.readAsAbout(store, g, g.nodeIdOf("森田勇")).isEmpty());
    }
}
