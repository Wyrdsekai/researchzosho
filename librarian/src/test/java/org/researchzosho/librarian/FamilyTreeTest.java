package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
/** The tree is drawn from the claims: a row per generation, a couple side by side, an adoption dashed, a living person drawn like anybody else. */
class FamilyTreeTest {

    private static FamilyAccount.Fact fact(String s, String r, String o, String date) { return new FamilyAccount.Fact(s, r, o, date, "q"); }

    private static LibraryStore family(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.Read read = new FamilyAccount.Read(List.of(new FamilyAccount.Person("Mara", "", List.of())), List.of(
                fact("渡邊喜平", "parent-of", "渡邊ツネ", ""), fact("髙橋源三郎", "married-to", "渡邊ツネ", "about 1906"),
                fact("髙橋源三郎", "born-in", "広島県佐伯郡", "明治5年"), fact("髙橋源三郎", "died-in", "広島", "昭和20年"), fact("髙橋源三郎", "occupation", "farmer", ""),
                fact("髙橋正一", "child-of", "髙橋源三郎", ""), fact("髙橋正一", "child-of", "渡邊ツネ", ""), fact("髙橋正一", "born-on", "明治41年3月", ""),
                fact("髙橋勇", "child-of", "髙橋源三郎", ""), fact("髙橋勇", "adopted-by", "渡邊喜平", "大正10年"),
                fact("髙橋正一", "parent-of", "Mara", "")), List.of());
        FamilyAccount.file(store, read, "file:///family/notes.txt", "an aunt");
        return store;
    }

    @Test
    void generationsCouplesAndTheFactsInEachBox(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        FamilyTree.Tree t = FamilyTree.around(store, "高橋正一", 5, 4);   // asked for in the modern form of the name
        assertEquals("髙橋正一", t.focus().label());
        Map<String, Integer> gen = new HashMap<>();
        for (FamilyTree.Person p : t.people()) gen.put(p.label(), p.generation());
        assertEquals(Map.of("髙橋正一", 0, "髙橋源三郎", -1, "渡邊ツネ", -1, "渡邊喜平", -2, "髙橋勇", 0, "Mara", 1), gen);
        FamilyTree.Person genzaburo = t.people().stream().filter(p -> p.label().equals("髙橋源三郎")).findFirst().orElseThrow();
        assertEquals("1872 1945 farmer", genzaburo.born() + " " + genzaburo.died() + " " + genzaburo.work());
        assertEquals("1908", t.focus().born(), "a birth with a date and no place");
        assertTrue(t.links().stream().anyMatch(l -> l.kind().equals("adopted") && l.state().equals("draft")));
        Map<String, int[]> at = FamilyTree.layout(t);
        assertEquals(at.get(id(t, "髙橋源三郎"))[1], at.get(id(t, "渡邊ツネ"))[1], "a couple stands in one row");
        assertEquals(FamilyTree.BOX_W + FamilyTree.GAP_X, Math.abs(at.get(id(t, "髙橋源三郎"))[0] - at.get(id(t, "渡邊ツネ"))[0]), "side by side");
        assertTrue(at.get(id(t, "渡邊喜平"))[1] < at.get(id(t, "渡邊ツネ"))[1] && at.get(id(t, "渡邊ツネ"))[1] < at.get(id(t, "髙橋正一"))[1] && at.get(id(t, "髙橋正一"))[1] < at.get(id(t, "Mara"))[1]);

        String svg = FamilyTree.svg(t, n -> "/tree?focus=" + n, f -> "/entry/" + f);
        assertTrue(svg.startsWith("<svg") && svg.contains(">髙橋源三郎</text>") && svg.contains("1872 – 1945 · farmer") && svg.contains("stroke-dasharray=\"6 4\""), svg);
        assertTrue(svg.contains("<a href=\"/entry/F-") && svg.contains("<a href=\"/tree?focus=Mara\">") && !svg.contains(">private</text>"), "no box is marked private: " + svg);
        assertFalse(svg.contains("<style") || svg.contains("class="), "inline attributes only: a viewer without CSS paints a styled SVG black");
        assertFalse(FamilyTree.svg(t, n -> "", f -> "").contains("<a "), "a file has no links");
    }

    @Test
    void aLivingPersonIsDrawnLikeAnybodyElse(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        FamilyTree.Tree t = FamilyTree.around(store, "髙橋正一", 5, 4);
        assertTrue(t.people().stream().anyMatch(p -> p.label().equals("Mara")) && t.links().stream().anyMatch(l -> l.to().equals("mara")), t.toString());
        assertEquals("Mara", FamilyTree.around(store, "Mara", 5, 4).focus().label());
        assertNull(FamilyTree.around(store, "nobody at all", 5, 4).focus());
        // held in today's forms (a model reading a picture wrote 高 for 髙), asked for as the register writes it
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("高橋一", "child-of", "高橋二", "")), List.of()), "file:///x.txt", "x");
        assertEquals("高橋一", FamilyTree.around(store, "髙橋一", 5, 4).focus().label());
        assertEquals(List.of("genealogy"), Fields.recognised(store, "髙橋二 の職業は"));
        assertTrue(FamilyPages.treeBody(store, "").contains("/tree?focus="), "with no name the page offers the people who have family claims");
    }

    private static String id(FamilyTree.Tree t, String label) { return t.people().stream().filter(p -> p.label().equals(label)).findFirst().orElseThrow().id(); }
}
