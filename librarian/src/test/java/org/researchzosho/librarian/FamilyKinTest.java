package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** How people are related, what sex a record gives them, and how a child belongs to a family, each used by the checks. */
class FamilyKinTest {

    private static FamilyAccount.Fact fact(String s, String r, String o) { return new FamilyAccount.Fact(s, r, o, "", "q"); }
    private static FamilyAccount.Fact said(String s, String r, String o, String quote) { return new FamilyAccount.Fact(s, r, o, "", quote); }

    private static LibraryStore store(Path tmp, FamilyAccount.Fact... facts) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(facts), List.of()), "file:///notes.txt", "an aunt");
        return store;
    }

    private static String out(Runnable r) {
        PrintStream real = System.out;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        System.setOut(new PrintStream(b, true, StandardCharsets.UTF_8));
        try { r.run(); } finally { System.setOut(real); }
        return b.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theRelationIsCountedInGenerationsAndNamed(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, fact("髙橋源三郎", "parent-of", "髙橋正一"), fact("髙橋源三郎", "parent-of", "髙橋勇"), fact("髙橋源三郎", "sex", "male"),
                fact("髙橋正一", "parent-of", "髙橋まり"), fact("髙橋勇", "parent-of", "髙橋ハル"), fact("Ann Hart", "married-to", "髙橋勇"), fact("Ann Hart", "sex", "female"),
                fact("Tom Ellis", "born-in", "Dunedin"));
        Graph g = Graph.build(store);
        assertTrue(FamilyKin.said(g, g.nodeIdOf("髙橋まり"), g.nodeIdOf("髙橋ハル")).startsWith("髙橋まり is 髙橋ハル's first cousin: their nearest shared ancestor is 髙橋源三郎. The claims on the way: F-"));
        assertTrue(FamilyKin.said(g, g.nodeIdOf("髙橋源三郎"), g.nodeIdOf("髙橋まり")).startsWith("髙橋源三郎 is 髙橋まり's grandfather."), FamilyKin.said(g, g.nodeIdOf("髙橋源三郎"), g.nodeIdOf("髙橋まり")));
        assertTrue(FamilyKin.said(g, g.nodeIdOf("髙橋勇"), g.nodeIdOf("髙橋まり")).startsWith("髙橋勇 is 髙橋まり's uncle or aunt"));
        assertTrue(FamilyKin.said(g, g.nodeIdOf("Ann Hart"), g.nodeIdOf("髙橋まり")).startsWith("Ann Hart was married to 髙橋勇 (F-"), FamilyKin.said(g, g.nodeIdOf("Ann Hart"), g.nodeIdOf("髙橋まり")));
        assertTrue(FamilyKin.said(g, g.nodeIdOf("Ann Hart"), g.nodeIdOf("髙橋まり")).contains("who is 髙橋まり's uncle or aunt"));
        assertNull(FamilyKin.said(g, g.nodeIdOf("Tom Ellis"), g.nodeIdOf("髙橋まり")), "no line in the claims");
        assertEquals("second cousin once removed", FamilyKin.words(3, 4, false, ""));
        assertEquals("great-grandmother", FamilyKin.words(0, 3, false, "female"));
        assertEquals("great-nephew", FamilyKin.words(3, 1, false, "male"));
        String cli = out(() -> LibrarianCli.familyCommand(store, "related \"髙橋まり\" \"髙橋ハル\""));
        assertTrue(cli.contains("髙橋まり is 髙橋ハル's first cousin"), cli);
    }

    @Test
    void aRelationWrittenInWordsIsHeldAgainstTheTree(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, fact("髙橋源三郎", "parent-of", "髙橋正一"), fact("髙橋源三郎", "parent-of", "髙橋勇"),
                fact("髙橋正一", "parent-of", "髙橋まり"), fact("髙橋勇", "parent-of", "髙橋ハル"),
                said("髙橋まり", "relative-of", "髙橋源三郎", "源三郎の曾孫まり"),
                said("髙橋ハル", "relative-of", "髙橋源三郎", "孫のハル"),
                said("Tom Ellis", "relative-of", "髙橋源三郎", "his grandson Tom"),
                fact("髙橋まり", "sibling-of", "髙橋ハル"));
        String all = FamilyChecks.render(FamilyChecks.check(store));
        assertTrue(all.contains("[unlikely] \"髙橋まり is a relative of 髙橋源三郎\", in the words \"源三郎の曾孫まり\", does not fit the parent claims, which make 髙橋まり 髙橋源三郎's grandchild."), all);
        assertTrue(all.contains("[unlikely] \"髙橋まり is a brother or sister of 髙橋ハル\" does not fit the parent claims, which make 髙橋まり 髙橋ハル's first cousin."), all);
        assertFalse(all.contains("髙橋ハル is a relative of"), "孫 fits a grandchild: " + all);
        assertFalse(all.contains("Tom Ellis"), "no line between them in the tree says nothing: " + all);
        assertNull(FamilyKin.spanOf("曾祖父と祖父"), "two relations in the words say neither");
        assertArrayEquals(new int[]{0, 3}, FamilyKin.spanOf("his great-grandson"));
        assertNull(FamilyKin.spanOf("her son-in-law's cousin"));
    }

    @Test
    void theSexARecordGivesSharpensTheParentChecks(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp,
                said("Tom Hale", "parent-of", "Kimie Hale", "Kimie Hale, whose father was Tom Hale"),
                said("森田まり", "child-of", "森田正一", "父：森田正一"),
                said("Ann Hart", "parent-of", "Kimie Hale", "Kimie's mother and father came from Dunedin"),
                said("森田勇", "child-of", "Isamu Ellis", "his grandfather Isamu Ellis"),
                fact("Ann Hart", "born-on", "1850"), fact("Ann Hart", "sex", "female"), fact("Kimie Hale", "born-on", "1906"), fact("Ann Hart", "died-on", "1905"),
                fact("Tom Hale", "born-on", "1840"), fact("Tom Hale", "died-on", "1905"),
                fact("森田勇", "child-of", "森田正一"), fact("Isamu Ellis", "sex", "male"),
                fact("Haru Endo", "sex", "female"), fact("Haru Endo", "sex", "male"));
        String prompt = FamilyAccount.prompt("text", "an aunt", Profiles.named("genealogy").predicates());
        assertTrue(prompt.contains("- step-parent-of:") && prompt.contains("- foster-child-of:") && !prompt.contains("- sex:"), "sex is no relation of the list: the model gives it with the person, and the reader keeps it only where the words say it");
        Graph g = Graph.build(store);
        var sexes = FamilyKin.sexes(g);
        assertEquals("male", FamilyKin.sexOf(sexes, g.nodeIdOf("Tom Hale")), "her father");
        assertEquals("male", FamilyKin.sexOf(sexes, g.nodeIdOf("森田正一")), "父：");
        assertEquals(1, sexes.get(g.nodeIdOf("Ann Hart")).get("female").size(), "\"mother and father\" says neither, so Ann's is the one claim given");
        assertEquals("male", FamilyKin.sexOf(sexes, g.nodeIdOf("Isamu Ellis")), "a grandfather in the words is not a father");
        Graph.Neighbourhood nb = g.around("Haru Endo", 3, 100);
        assertTrue(nb.nodes().stream().anyMatch(n -> n.label().equals("male")) && nb.nodes().stream().noneMatch(n -> n.label().equals("Tom Hale")), "one sex does not make two people neighbours: " + nb.nodes());
        String all = FamilyChecks.render(FamilyChecks.check(store));
        assertTrue(all.contains("[unlikely] Ann Hart (born 1850) was 56 when Kimie Hale was born in 1906. A mother of that age is rare"), all);
        assertTrue(all.contains("[impossible] Ann Hart died in 1905, before Kimie Hale was born in 1906."), "a mother is alive at the birth: " + all);
        assertFalse(all.contains("Tom Hale died"), "a father may die up to a year before: " + all);
        // "his grandfather Isamu Ellis" is no father: the family's view reads that claim as a relative, so 森田勇 has one birth father and the check is quiet
        assertFalse(all.contains("two birth fathers"), all);
        Graph view = FamilyPeople.view(store);
        assertTrue(FamilyDoubts.readAsAbout(store, view, view.nodeIdOf("Isamu Ellis")).get(0).contains("read as a relative: the words say a grandparent"), FamilyDoubts.readAsAbout(store, view, view.nodeIdOf("Isamu Ellis")).toString());
        assertEquals(1, FamilyKin.parents(view).get(view.nodeIdOf("森田勇")).size());
        assertTrue(all.contains("[unlikely] Haru Endo is recorded as a man in F-") && all.contains("and as a woman in F-"), all);
    }

    @Test
    void aTreeFileSaysHowEachChildBelongsToTheFamily(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path ged = tmp.resolve("tree.ged");
        Files.writeString(ged, String.join("\n",
                "0 HEAD", "0 @I1@ INDI", "1 NAME Tom /Hale/", "1 SEX M", "1 BIRT", "2 DATE 1850", "1 DEAT", "2 DATE 1920", "1 FAMS @F1@", "1 FAMS @F2@",
                "0 @I2@ INDI", "1 NAME Ann /Hart/", "1 SEX F", "1 BIRT", "2 DATE 1852", "1 DEAT", "2 DATE 1880", "1 FAMS @F1@",
                "0 @I3@ INDI", "1 NAME Kimie /Hale/", "1 SEX F", "1 BIRT", "2 DATE 1878", "1 FAMC @F1@", "1 FAMC @F2@", "2 PEDI step",
                "0 @I4@ INDI", "1 NAME Mari /Ellis/", "1 SEX F", "1 BIRT", "2 DATE 1860", "1 DEAT", "2 DATE 1930", "1 FAMS @F2@",
                "0 @I5@ INDI", "1 NAME Isamu /Hale/", "1 BIRT", "2 DATE 1885", "1 FAMC @F1@", "2 PEDI foster",
                "0 @I6@ INDI", "1 NAME Haru /Hale/", "1 BIRT", "2 DATE 1879", "1 FAMC @F1@", "2 PEDI sealing",
                "0 @F1@ FAM", "1 HUSB @I1@", "1 WIFE @I2@", "1 CHIL @I3@", "1 CHIL @I5@", "1 CHIL @I6@",
                "0 @F2@ FAM", "1 HUSB @I1@", "1 WIFE @I4@", "1 CHIL @I3@", "2 _FREL Natural", "2 _MREL Step", "0 TRLR", ""), StandardCharsets.UTF_8);
        Gedcom.importFile(store, ged);
        List<Finding> all = store.scanFindings().findings();
        assertTrue(all.stream().anyMatch(f -> f.triple().subject().equals("Mari Ellis") && f.triple().predicate().equals("step-parent-of") && f.triple().object().equals("Kimie Hale")), "the wife is Kimie's step-mother");
        assertTrue(all.stream().anyMatch(f -> f.triple().subject().equals("Isamu Hale") && f.triple().predicate().equals("foster-child-of") && f.triple().object().equals("Ann Hart")));
        Finding sealed = all.stream().filter(f -> f.triple().object().equals("Haru Hale") && f.triple().predicate().equals("parent-of")).findFirst().orElseThrow();
        assertEquals(Finding.Confidence.low, sealed.confidence(), "a kind the library does not know is kept at low confidence");
        assertTrue(sealed.sources().get(0).edition().contains("written as sealing"), sealed.sources().get(0).toString());
        assertTrue(all.stream().anyMatch(f -> f.triple().subject().equals("Tom Hale") && f.triple().predicate().equals("sex") && f.triple().object().equals("male")));
        String checks = FamilyChecks.render(FamilyChecks.check(store));
        assertFalse(checks.contains("birth parents"), "a step-mother is not a third birth parent: " + checks);
        assertFalse(checks.contains("Isamu Hale"), "a foster child born after the mother's death is not hers by birth: " + checks);
        assertEquals("value", Graph.build(store).node("male").kind());
        FamilyTree.Tree t = FamilyTree.around(store, "Kimie Hale", 3, 3);
        assertTrue(t.links().stream().anyMatch(l -> l.kind().equals("adopted")), "a step-parent is drawn dashed");
    }

    @Test
    void tidyShowsWhatAgreesHoldsBackWhatDoesNotFitAndLeavesApartWhatYouSay(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, fact("Tom Hale", "born-in", "Dunedin"), fact("Hale, Tom", "born-in", "Dunedin, Otago"),
                fact("Ann Hart", "born-in", "Dunedin"), fact("Hart, Ann", "born-in", "Glasgow"),
                fact("Kimie Hale", "occupation", "teacher"), fact("Hale, Kimie", "married-to", "Isamu Ellis"));
        var genealogy = Profiles.named("genealogy");
        String first = out(() -> { try { genealogy.cli(store, new String[]{"librarian", "genealogy", "tidy"}); } catch (Exception e) { throw new RuntimeException(e); } });
        assertTrue(first.contains("Tom Hale   ←   Hale, Tom\n       What else agrees: both were born in Dunedin."), first);
        assertTrue(first.contains("Kimie Hale   ←   Hale, Kimie\n       Nothing else agrees"), first);
        assertTrue(first.contains("1 pair has a name written the same way, but something in the facts does not fit one person.") && first.contains("Ann Hart was born in Dunedin and Hart, Ann in Glasgow")
                && first.contains("researchzosho genealogy different \"Ann Hart\" \"Hart, Ann\""), first);
        assertTrue(first.contains("Nothing was changed."), first);
        Matcher m = Pattern.compile("(\\d+)\\. Kimie Hale").matcher(first);
        assertTrue(m.find());
        String done = out(() -> { try { genealogy.cli(store, new String[]{"librarian", "genealogy", "tidy", "--yes", "--apart", m.group(1)}); } catch (Exception e) { throw new RuntimeException(e); } });
        assertTrue(done.contains("Done. 1 person was joined, 1 pair was written down as two people"), done);
        Graph g = Graph.build(store);
        assertEquals(g.nodeIdOf("Tom Hale"), g.nodeIdOf("Hale, Tom"));
        assertNotEquals(g.nodeIdOf("Ann Hart"), g.nodeIdOf("Hart, Ann"), "held back");
        assertTrue(Graph.differentPairs(store).contains(Graph.pair(g.nodeIdOf("Kimie Hale"), g.nodeIdOf("Hale, Kimie"))));
        assertTrue(Files.readString(Graph.mergesFile(store), StandardCharsets.UTF_8).contains("genealogy tidy\t" + LocalDate.now() + "\tthe same words of the name, written another way; both were born in Dunedin"));
        String again = out(() -> { try { genealogy.cli(store, new String[]{"librarian", "genealogy", "tidy"}); } catch (Exception e) { throw new RuntimeException(e); } });
        assertFalse(again.contains("Kimie Hale"), "a pair left apart is not shown again: " + again);
    }
}
