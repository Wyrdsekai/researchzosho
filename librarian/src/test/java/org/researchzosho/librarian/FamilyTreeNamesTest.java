package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tree and the sentences about a person name them by their latest name with the birth name beside it; every link and command keeps
 * the label the library files them under. Invented names only.
 */
class FamilyTreeNamesTest {

    private static FamilyTree.Person person(FamilyTree.Tree t, String label) { return t.people().stream().filter(p -> p.label().equals(label)).findFirst().orElseThrow(); }

    @Test
    void aBoxShowsTheLatestNameWithTheBirthNameBesideItAndLinksByTheLabel(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNamesFixture.family(tmp);
        FamilyTree.Tree t = FamilyTree.around(store, "森田健二", 5, 4);
        assertEquals("森田健二", t.focus().shown());
        assertEquals("遠藤", t.focus().bornAs());
        assertEquals("森田健二 (born 遠藤)", t.focus().heading());
        FamilyTree.Person shoji = person(t, "森田正二");
        assertEquals("髙橋正二", shoji.shown(), "his latest name, while the library files him under 森田正二");
        assertEquals("森田", shoji.bornAs());
        String svg = FamilyTree.svg(t, n -> "/tree?focus=" + n, f -> "/entry/" + f);
        assertTrue(svg.contains(">森田健二</text>") && svg.contains(">1905 – ? · born 遠藤</text>"), "line 1 the latest name, line 2 the years and the birth name: " + svg);
        assertTrue(svg.contains("<a href=\"/tree?focus=森田正二\"><g><title>髙橋正二 (born 森田)</title>") && svg.contains(">髙橋正二</text>") && svg.contains(">1912 – ? · born 森田</text>"),
                "the link keeps the label, the box shows the name: " + svg);
        assertTrue(svg.contains("aria-label=\"Family tree of 森田健二 (born 遠藤)\""), svg);
        assertTrue(svg.contains(">遠藤正一</text>") && svg.contains(">1875 – 1930</text>"), "a person with one name is drawn as before: " + svg);
        FamilyTree.Person old = new FamilyTree.Person("x", "Tom Hale", 0, "1850", "", "miner", false);
        assertEquals("Tom Hale", old.shown());
        assertEquals("Tom Hale", old.heading(), "a box made the old way shows its label");
    }

    @Test
    void theTreePageNamesEachPersonByTheirHeadingAndLeadsToTheirNamesAndFamilies(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNamesFixture.family(tmp);
        String people = FamilyPages.treeBody(store, "");
        assertTrue(people.contains("<a href=\"/tree?focus=" + FamilyNamePages.enc("森田正二") + "\">髙橋正二 (born 森田)</a>"), people);
        assertTrue(people.contains("<a href=\"/tree?focus=" + FamilyNamePages.enc("森田健二") + "\">森田健二 (born 遠藤)</a>"), people);
        String tree = FamilyPages.treeBody(store, "森田正二");
        assertTrue(tree.contains("<a href=\"/person?name=" + FamilyNamePages.enc("森田正二") + "\">Names and families of 髙橋正二 (born 森田)</a>"), tree);
        assertTrue(tree.contains("<a href=\"/map?focus=" + FamilyNamePages.enc("森田正二") + "\">"), tree);
    }

    @Test
    void theTreeCommandTheRelationAndThePlanNameThePersonByTheHeading(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNamesFixture.family(tmp);
        String drawn = FamilyNamesFilingTest.run(store, "tree", "森田健二", "--out", tmp.resolve("t.svg").toString());
        assertTrue(drawn.contains(" around 森田健二 (born 遠藤), whose box has a red border."), drawn);
        Graph g = FamilyPeople.view(store);
        String said = FamilyKin.said(g, g.nodeIdOf("遠藤正一"), g.nodeIdOf("森田健二"));
        assertTrue(said.startsWith("遠藤正一 is 森田健二 (born 遠藤)'s father."), said);
        // nobody is looked up on the web: the plan is only shown, and every person was looked up before
        for (String who : List.of("森田健二", "森田勇", "森田ハル", "森田正二", "遠藤正一", "遠藤勇")) FamilyIdentity.find(store, Graph.build(store), who, q -> List.of(), null);
        String plan = FamilyNamesFilingTest.run(store, "research");
        assertTrue(Pattern.compile("(?m)^  \\d+\\. 髙橋正二 \\(born 森田\\)\\.").matcher(plan).find(), plan);
        assertTrue(plan.contains("researchzosho genealogy research \"") && !plan.contains("research \"髙橋正二 (born 森田)\""), "the commands keep the label: " + plan);
    }
}
