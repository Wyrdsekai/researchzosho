package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Two names that may be one person: what else agrees is computed claim by claim, and the family's answer, either way, is kept and can be taken back. */
class FamilySameTest {

    private static FamilyAccount.Fact fact(String s, String r, String o) { return new FamilyAccount.Fact(s, r, o, "", "q"); }

    private static LibraryStore store(Path tmp, FamilyAccount.Fact... facts) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(facts), List.of()), "file:///notes.txt", "an aunt");
        return store;
    }

    private static String id(LibraryStore store, String subject, String predicate) throws Exception {
        return store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().subject().equals(subject) && f.triple().predicate().equals(predicate)).findFirst().orElseThrow().id();
    }

    @Test
    void whatElseAgreesIsComputedRelationByRelationAndTheBestSupportedPairComesFirst(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp,
                // a wife on one side and a mother on the other are not a sign: relations are compared like for like
                fact("Shoichi Morita", "married-to", "Kimie Hale"), fact("Shoichi Morrita", "child-of", "Kimie Hale"),
                fact("Genzaburo Takahashi", "child-of", "Isamu Takahashi"), fact("Genzaburo Takahashi", "born-on", "1872"), fact("Genzaburo Takahashi", "born-in", "廣島"),
                fact("Genzaburo Takahasi", "child-of", "Isamu Takahashi"), fact("Genzaburo Takahasi", "born-on", "1872"), fact("Genzaburo Takahasi", "born-in", "広島県広島"),
                // a claim that one is the other's parent says they are two
                fact("Tom Ellis", "parent-of", "Tom Elis"));
        List<FamilyChecks.Problem> all = FamilyChecks.check(store);
        List<FamilyChecks.Problem> pairs = all.stream().filter(p -> p.kind().equals("same-person?")).toList();
        assertEquals(2, pairs.size(), FamilyChecks.render(all));
        FamilyChecks.Problem first = pairs.get(0);
        assertTrue(first.text().startsWith("Genzaburo Takahashi and Genzaburo Takahasi differ by one character") && first.text().contains("What else agrees: both are children of Isamu Takahashi; both were born in 廣島; both born in 1872."), first.text());
        assertTrue(first.findings().contains(id(store, "Genzaburo Takahasi", "child-of")) && first.findings().contains(id(store, "Genzaburo Takahashi", "born-on")), "each thing that agrees names its claims: " + first.findings());
        assertTrue(first.text().contains("graph merge \"Genzaburo Takahasi\" \"Genzaburo Takahashi\" --because \"both are children of Isamu Takahashi; both were born in 廣島; both born in 1872\""), first.text());
        FamilyChecks.Problem second = pairs.get(1);
        assertTrue(second.text().contains("Nothing else agrees") && !second.text().contains("married to Kimie Hale"), second.text());
        assertTrue(second.text().contains("genealogy different \"Shoichi Morrita\" \"Shoichi Morita\""), second.text());
        String render = FamilyChecks.render(all);
        assertTrue(render.contains("[same-name] Tom Ellis and Tom Elis share a name and are two people: \"Tom Ellis is a parent of Tom Elis\""), render);
    }

    @Test
    void twoPeopleWrittenDownStayApartEverywhereUntilTakenBack(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, fact("Genzaburo Takahashi", "born-on", "1872"), fact("Genzaburo Takahasi", "born-on", "1872"),
                fact("Tom Hale", "born-in", "Dunedin"));
        // the index form from another text: across two texts the name alone does not settle it (FamilyLinks), so it is put forward
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Hale, Tom", "born-in", "Dunedin")), List.of()), "file:///register.txt", "an aunt");
        assertEquals(2, FamilyChecks.check(store).stream().filter(p -> p.kind().equals("same-person?")).count(), "one letter apart, and one name in another order");
        Graph.different(store, "Genzaburo Takahasi", "Genzaburo Takahashi", "person", "different fathers in the register");
        Graph.different(store, "Tom Hale", "Hale, Tom", "person", "the register has two");
        assertTrue(FamilyChecks.check(store).stream().noneMatch(p -> p.kind().equals("same-person?")), FamilyChecks.render(FamilyChecks.check(store)));
        assertTrue(FamilyChecks.render(FamilyChecks.check(store)).contains("[same-name] Genzaburo Takahashi and Genzaburo Takahasi share a name and were written down as two people."), "and a record naming either needs a second identifier");
        assertTrue(FamilyNames.sameByName(Graph.build(store), Graph.differentPairs(store)).isEmpty(), "tidy does not put them forward either");
        Graph.propose(store);
        assertFalse(Files.readString(Graph.dir(store).resolve("proposals.md"), StandardCharsets.UTF_8).contains("tom hale"));
        assertThrows(IllegalArgumentException.class, () -> Graph.different(store, "Tom Hale", "Nobody Here", "person", "x"));
        assertTrue(Graph.notDifferent(store, "Hale, Tom", "Tom Hale", "person"), "taken back whichever way round the two are named");
        assertEquals(1, FamilyNames.sameByName(Graph.build(store), Graph.differentPairs(store)).size());
        assertTrue(Files.readString(Graph.differentFile(store), StandardCharsets.UTF_8).contains("different fathers in the register"), "the reason is kept");
    }

    @Test
    void aMergeKeepsItsReasonAndIsTakenBackOnItsOwn(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, fact("Tom Hale", "born-in", "Dunedin"), fact("Hale, Tom", "married-to", "Ann Hart"), fact("Hale, Tom", "occupation", "miner"),
                fact("Kimie Hale", "born-in", "Dunedin"), fact("Hale, Kimie", "child-of", "Ann Hart"));
        Graph.Merged m = Graph.merge(store, "Hale, Tom", "Tom Hale", "person", "both married to Ann Hart in the parish book");
        assertEquals(2, m.claims().size(), "the claims about the folded name, which are now about the other: " + m.claims());
        Graph.merge(store, "Hale, Kimie", "Kimie Hale", "genealogy tidy", "the same words of the name");
        Graph g = Graph.build(store);
        assertEquals(g.nodeIdOf("Tom Hale"), g.nodeIdOf("Hale, Tom"));
        assertTrue(Files.readString(Graph.mergesFile(store), StandardCharsets.UTF_8).contains("\tboth married to Ann Hart in the parish book\n"));

        Graph.Merged back = Graph.unmerge(store, "Hale, Tom", "Tom Hale", "person", "two men of one name");
        assertEquals(2, back.claims().size(), back.claims().toString());
        g = Graph.build(store);
        assertNotEquals(g.nodeIdOf("Tom Hale"), g.nodeIdOf("Hale, Tom"), "the one merge is taken back");
        assertEquals(g.nodeIdOf("Kimie Hale"), g.nodeIdOf("Hale, Kimie"), "the other stays");
        assertFalse(g.node(g.nodeIdOf("Tom Hale")).aliases().contains("Hale, Tom"), "the other name the merge gave goes with it");
        assertThrows(IllegalArgumentException.class, () -> Graph.unmerge(store, "Hale, Tom", "", "person", ""));
        assertThrows(IllegalArgumentException.class, () -> Graph.unmerge(store, "Hale, Kimie", "Tom Hale", "person", ""), "joined to somebody else");
        List<String> lines = Files.readAllLines(Graph.mergesFile(store), StandardCharsets.UTF_8);
        assertEquals(3, lines.size(), "the merge stays in the file, taken back by a line of its own: " + lines);
        assertTrue(lines.get(2).startsWith("-\t"), lines.toString());

        // a merge again after it was taken back is in force again
        Graph.merge(store, "Hale, Tom", "Tom Hale", "person", "");
        assertEquals(Graph.build(store).nodeIdOf("Tom Hale"), Graph.build(store).nodeIdOf("Hale, Tom"));

        // a fresh start keeps the person's merges and the ones the person said yes to in genealogy tidy
        FamilyReset.apply(store, FamilyReset.plan(store, List.of("family-account")));
        String after = Files.readString(Graph.mergesFile(store), StandardCharsets.UTF_8);
        assertFalse(after.contains("\tgenealogy reset"), after);
        assertTrue(after.startsWith("hale, tom\ttom hale\tperson"), "nothing is rewritten: " + after);
        assertTrue(Graph.merges(store).containsKey("hale, tom") && Graph.merges(store).containsKey("hale, kimie"), Graph.merges(store).toString());
    }

    @Test
    void aThingLookedAtStaysApartUntilItsClaimsChange(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, fact("Tom Hale", "born-on", "1850"), fact("Kimie Hale", "child-of", "Tom Hale"), fact("Kimie Hale", "born-on", "1932"));
        List<FamilyChecks.Problem> p = FamilyChecks.check(store);
        FamilyChecks.Problem old = p.stream().filter(x -> x.text().contains("was 82 when Kimie Hale was born")).findFirst().orElseThrow();
        assertEquals(old.id(), FamilyChecks.check(store).stream().filter(x -> x.text().contains("was 82")).findFirst().orElseThrow().id(), "the same code on every run");
        assertTrue(FamilyChecks.forPerson(store, p).contains("[code " + old.id() + "]"));
        assertThrows(IllegalArgumentException.class, () -> FamilyChecks.accept(store, "000000", "why", "me"));
        FamilyChecks.accept(store, "[code " + old.id() + "]", "the register gives both births in his hand", "Mari");
        String said = FamilyChecks.forPerson(store, FamilyChecks.check(store));
        assertTrue(said.contains("It found nothing new to look at.") && said.contains("ALREADY LOOKED AT (1)") && said.contains("Looked at on ") && said.contains("\"the register gives both births in his hand\""), said);
        assertTrue(FamilyChecks.open(store, FamilyChecks.check(store)).isEmpty());
        assertTrue(FamilyChecks.reopen(store, old.id()));
        assertEquals(1, FamilyChecks.open(store, FamilyChecks.check(store)).size());
        FamilyChecks.accept(store, old.id(), "looked at", "Mari");
        // another birth year for the child: the problem is a new one, and it is on the list again
        LibraryStore same = store;
        String born = id(same, "Kimie Hale", "born-on");
        new Council(same).retire(born);
        FamilyAccount.fileAsRead(same, new FamilyAccount.Read(List.of(), List.of(fact("Kimie Hale", "born-on", "1935")), List.of()), "file:///notes2.txt", "an aunt");
        List<FamilyChecks.Problem> now = FamilyChecks.open(same, FamilyChecks.check(same));
        assertTrue(now.stream().anyMatch(x -> x.text().contains("was 85 when Kimie Hale was born")), FamilyChecks.render(now));
    }

    @Test
    void aDescribedParentOrAGivenNameAloneIsNotAThirdParent(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp,
                fact("Kimie Hale", "child-of", "Tom Hale"), fact("Kimie Hale", "child-of", "Ann Hart"), fact("Kimie Hale", "child-of", "Kimie Hale's father"),
                fact("Tom Hale", "sex", "male"), fact("Ann Hart", "sex", "female"),
                fact("森田勇", "child-of", "森田正一"), fact("森田勇", "child-of", "森田まり"), fact("森田勇", "child-of", "まり"));
        String all = FamilyChecks.render(FamilyChecks.check(store));
        assertFalse(all.contains("birth parents:"), all);
        // the account's own "my father" is the father the same account records, Tom Hale ({@link FamilyLinks}, L3): one father, not a question
        assertFalse(all.contains("Kimie Hale's father is a person an account describes"), all);
        assertEquals(FamilyPeople.view(store).nodeIdOf("Tom Hale"), FamilyPeople.view(store).nodeIdOf("Kimie Hale's father"), "the described father is the recorded one");
        // まり in the text that names 森田まり in full is 森田まり ({@link FamilyLinks}): one mother, not a question
        assertFalse(all.contains("まり and 森田まり"), all);
        Graph g = FamilyPeople.view(store);
        Finding mari = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().object().equals("まり")).findFirst().orElseThrow();
        assertEquals(g.nodeIdOf("森田まり"), g.nodeOf(mari, false));
        assertTrue(FamilyChecks.partOfName("Kimie", "Kimie Hale") && FamilyChecks.partOfName("Hale", "Hale, Kimie") && !FamilyChecks.partOfName("Kimie Hale", "Kimie Hart") && !FamilyChecks.partOfName("正一", "正二"));
        // a fourth named parent is still one too many
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("森田勇", "child-of", "Isamu Ellis"), fact("森田勇", "child-of", "Haru Endo")), List.of()), "file:///n2.txt", "an aunt");
        assertTrue(FamilyChecks.render(FamilyChecks.check(store)).contains("森田勇 has 4 birth parents: 森田正一, 森田まり, Isamu Ellis, Haru Endo."), FamilyChecks.render(FamilyChecks.check(store)));
    }

    @Test
    void namesThatSoundAlikeInLatinLettersAreASecondWayIn(@TempDir Path tmp) throws Exception {
        assertEquals(FamilyNames.codes("Hale"), FamilyNames.codes("Hail"));
        assertEquals(FamilyNames.codes("Ellis"), FamilyNames.codes("Elys"));
        assertTrue(FamilyNames.codes("髙橋").isEmpty(), "kanji has its own forms, not sound codes");
        assertNull(FamilyNames.sound("Hale"), "one word is no name to compare");
        assertEquals(FamilyNames.sound("Tom Hale").family(), FamilyNames.sound("Hail, Tom").family(), "the family name is found either way round");
        LibraryStore store = store(tmp, fact("Isamu Hale", "born-in", "Dunedin"), fact("Isamu Hail", "born-in", "Dunedin"),
                fact("Kimie Ellis", "born-on", "1850"), fact("Kimie Elys", "born-on", "1890"),
                fact("Tom Hart", "born-in", "Glasgow"), fact("Ann Hart", "born-in", "Glasgow"));
        String all = FamilyChecks.render(FamilyChecks.check(store));
        assertTrue(all.contains("[same-person?] Isamu Hale and Isamu Hail sound alike and no date sets them apart. What else agrees: both were born in Dunedin."), all);
        assertFalse(all.contains("Elys"), "the dates set them apart, and two people who only sound alike need no warning: " + all);
        assertFalse(all.contains("Tom Hart and Ann Hart"), "the given names neither sound alike nor begin alike: " + all);
        assertTrue(FamilyNames.sameByName(Graph.build(store)).isEmpty(), "a sound is never a key tidy joins on");
    }

    @Test
    void twoClaimsThatCiteOnePageOfOneSourceAreSaidToAndTheirDisagreementIsAMisreading(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path a = tmp.resolve("aunt.ged"), b = tmp.resolve("cousin.ged");
        Files.writeString(a, "0 HEAD\n0 @S1@ SOUR\n1 TITL Dunedin Parish Register\n0 @I1@ INDI\n1 NAME Tom /Hale/\n1 BIRT\n2 DATE 1850\n2 SOUR @S1@\n3 PAGE p. 12\n1 DEAT\n2 DATE 1901\n0 TRLR\n", StandardCharsets.UTF_8);
        Files.writeString(b, "0 HEAD\n0 @S9@ SOUR\n1 TITL Dunedin parish register\n0 @I4@ INDI\n1 NAME Tom /Hale/\n1 BIRT\n2 DATE 1851\n2 SOUR @S9@\n3 PAGE page 12\n1 DEAT\n2 DATE 1901\n"
                + "0 @I5@ INDI\n1 NAME Tom /Hail/\n1 RESI\n2 PLAC Leeds\n2 SOUR @S9@\n3 PAGE page 12\n1 DEAT\n2 DATE 1902\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.importFile(store, a);
        Gedcom.importFile(store, b);
        Finding born = store.scanFindings().findings().stream().filter(f -> f.triple().predicate().equals("born-on")).findFirst().orElseThrow();
        assertArrayEquals(new String[]{"Dunedin Parish Register", "p. 12"}, Gedcom.unitOf(born.sources().get(0)), born.sources().get(0).edition());
        assertTrue(born.sources().get(0).edition().contains("cited there: Dunedin Parish Register, p. 12"), "the citation as written stays: " + born.sources().get(0).edition());
        String all = FamilyChecks.render(FamilyChecks.check(store));
        assertTrue(all.contains("[unlikely] 2 claims about Tom Hale differ and cite one page of one source (Dunedin Parish Register, p. 12): \"Tom Hale was born in 1850\" and \"Tom Hale was born in 1851\". The page says one thing, so one of them was read or copied wrongly."), all);
        assertTrue(all.contains("Tom Hale and Tom Hail sound alike") && all.contains("both are named on one page of one source (Dunedin Parish Register, p. 12)"), "\"p. 12\" and \"page 12\" of one register are one page: " + all);
    }
}
