package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Families as things: a family name is not a family, a family is never a person, and its members, heads and branches are worked out from the claims. */
class FamilyHousesTest {

    static FamilyAccount.Fact member(String person, String family, String date, String quote, Map<String, String> detail) {
        return new FamilyAccount.Fact(person, "member-of", family, date, quote, detail);
    }

    static FamilyAccount.Outcome file(LibraryStore store, List<FamilyAccount.Fact> facts, List<FamilyAccount.FamilyRead> families) throws Exception {
        return FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), facts, List.of(), List.of(), List.of(), families), "an aunt", f -> List.of("file:///family/book.txt"), f -> List.of());
    }

    @Test
    void twoFamiliesOfOneNameWithTwoSeatsStayTwoAndAFamilyNameAlonePicksNeither(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.Outcome o = file(store, List.of(member("遠藤正一", "遠藤家", "", "遠藤正一は広島の遠藤家の人である。", Map.of())),
                List.of(new FamilyAccount.FamilyRead("遠藤", "遠藤家", "広島県安芸郡", "遠藤正一は広島の遠藤家の人である。")));
        assertEquals(1, o.families());
        file(store, List.of(member("遠藤勇", "遠藤家", "", "遠藤勇は山口の遠藤家の人である。", Map.of())),
                List.of(new FamilyAccount.FamilyRead("遠藤", "遠藤家", "山口県大島郡", "遠藤勇は山口の遠藤家の人である。")));
        Graph g = FamilyPeople.view(store);
        List<String> endo = FamilyHouses.named(g, "遠藤");
        assertEquals(2, endo.size(), "two 遠藤 families: " + FamilyHouses.all(g));
        assertEquals(List.of("遠藤 family (広島県安芸郡)", "遠藤 family (山口県大島郡)"), endo.stream().map(id -> FamilyHouses.labelOf(g, id)).toList());
        assertEquals(g.nodeIdOf("遠藤 family (広島県安芸郡)"), FamilyHouses.find(g, "遠藤", "広島県安芸郡"));
        assertNull(FamilyHouses.find(g, "遠藤", ""), "with no seat to choose by, a family name alone picks neither");
        assertNull(FamilyHouses.find(g, "Endo", ""), "and a romaji form is no family name without a form the family has");
        assertEquals("広島県安芸郡", FamilyHouses.seat(g, g.nodeIdOf("遠藤 family (広島県安芸郡)")));
        List<FamilyHouses.Membership> shoichi = FamilyHouses.families(g, g.nodeIdOf("遠藤正一"));
        assertEquals(1, shoichi.size());
        assertEquals("遠藤 family (広島県安芸郡)", FamilyHouses.labelOf(g, shoichi.get(0).family()), "each member in the family the words named");
        assertEquals("遠藤 family (山口県大島郡)", FamilyHouses.labelOf(g, FamilyHouses.families(g, g.nodeIdOf("遠藤勇")).get(0).family()));
    }

    @Test
    void aFamilyIsNeverTakenForAPerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        file(store, List.of(member("森田勇", "森田家", "", "森田勇は森田家の当主であった。", Map.of("role", "head")),
                        new FamilyAccount.Fact("森田家", "founded-by", "森田正一", "", "森田家は森田正一が興した。")),
                List.of(new FamilyAccount.FamilyRead("森田", "森田家", "広島県安芸郡", "森田家の本籍は広島県安芸郡にあった。")));
        Graph g = FamilyPeople.view(store);
        String morita = g.nodeIdOf("森田 family (広島県安芸郡)");
        assertEquals("family", g.node(morita).kind(), "the family is a family: " + g.nodes());
        assertEquals(morita, g.nodeIdOf("森田家"), "the words the source wrote lead to it");
        assertEquals("person", g.node(g.nodeIdOf("森田勇")).kind());
        assertEquals("person", g.node(g.nodeIdOf("森田正一")).kind(), "a founder is a person");
        assertEquals(g.nodeIdOf("森田正一"), FamilyHouses.founder(g, morita));
        assertEquals("広島県安芸郡", FamilyHouses.seat(g, morita));
        assertEquals("place", g.node(g.nodeIdOf("広島県安芸郡")).kind());
        assertTrue(FamilyNames.known(g).values().stream().noneMatch(n -> n.contains("family")), "and never a person to look up");
        Graph core = Graph.build(store);
        assertEquals("family", core.node(morita).kind(), "the library's own map shows it as a family too");
    }

    @Test
    void theHeadsInOrderBranchesBothWaysAndWhereEachMemberCameFromAndWentTo(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q1 = "森田勇は明治四十年に森田家の家督を相続した。", q2 = "健二は1932年に婿養子として森田家に入り、1950年に家督を継いだ。";
        String q3 = "正二は1940年に髙橋家の養子となり、跡を継いだ。", q4 = "遠藤正一は遠藤家の人である。健二は正一の子である。";
        file(store, List.of(
                        member("森田勇", "森田家", "1907", q1, Map.of("role", "head", "how", "succession", "from", "1907", "to", "1950")),
                        member("森田健二", "森田家", "1932", q2, Map.of("how", "mukoyoshi", "from", "1932")),
                        member("森田健二", "森田家", "1950", q2, Map.of("role", "head", "how", "succession", "from", "1950")),
                        member("森田正二", "森田家", "1910", q3, Map.of("how", "birth", "from", "1910", "to", "1940", "left", "adoption-out")),
                        member("森田正二", "髙橋家", "1940", q3, Map.of("how", "adoption", "role", "heir", "from", "1940")),
                        member("遠藤正一", "遠藤家", "", q4, Map.of()),
                        new FamilyAccount.Fact("森田健二", "child-of", "遠藤正一", "", q4),
                        new FamilyAccount.Fact("森田健二", "born-in", "広島", "1905", "健二は1905年に広島で生まれた。"),
                        new FamilyAccount.Fact("森田分家", "branch-of", "森田家", "", "森田分家は森田家の分家である。")),
                List.of());
        Graph g = FamilyPeople.view(store);
        String morita = g.nodeIdOf("森田 family"), takahashi = g.nodeIdOf("髙橋 family"), endo = g.nodeIdOf("遠藤 family");
        assertTrue(FamilyHouses.isFamily(g, morita) && FamilyHouses.isFamily(g, takahashi) && FamilyHouses.isFamily(g, endo), FamilyHouses.all(g).toString());
        List<FamilyHouses.Membership> heads = FamilyHouses.heads(g, morita);
        assertEquals(List.of(g.nodeIdOf("森田勇"), g.nodeIdOf("森田健二")), heads.stream().map(FamilyHouses.Membership::person).toList(), "heads in order of the year each became head");
        assertEquals(List.of(1907, 1950), heads.stream().map(m -> m.from().year()).toList(), "each with the year they became head, not the year they came in");
        List<FamilyHouses.Membership> shoji = FamilyHouses.families(g, g.nodeIdOf("森田正二"));
        assertEquals(List.of(morita, takahashi), shoji.stream().map(FamilyHouses.Membership::family).toList());
        assertEquals(takahashi, shoji.get(0).wentTo(), "he left the 森田 family for the 髙橋 family");
        assertEquals("adoption-out", shoji.get(0).left());
        assertEquals(morita, shoji.get(1).cameFrom(), "and came to the 髙橋 family from the 森田 family");
        assertEquals("heir", shoji.get(1).role());
        List<FamilyHouses.Membership> his = FamilyHouses.families(g, g.nodeIdOf("森田健二"));
        assertEquals(2, his.size(), "he came in 1932 and became head in 1950: " + his);
        assertEquals("", his.get(1).cameFrom(), "becoming head is no move from another family");
        FamilyHouses.Membership kenji = his.get(0);
        assertEquals("mukoyoshi", kenji.how());
        assertEquals(endo, kenji.cameFrom(), "nobody said where 健二 came from: his birth father's family, worked out");
        assertTrue(kenji.workedOut());
        String branch = g.nodeIdOf("森田分家");
        assertTrue(FamilyHouses.isFamily(g, branch), "a branch is a family of its own");
        assertEquals(morita, FamilyHouses.branchOf(g, branch));
        assertEquals(List.of(branch), FamilyHouses.branches(g, morita), "and the family it branched from knows it");
        Finding entered = store.scanFindings().findings().stream().filter(f -> f.body().startsWith("森田健二 entered 森田 family as 婿養子")).findFirst().orElseThrow();
        assertEquals("mukoyoshi", FamilyDetail.get(entered, "how"), "the reading is kept with the claim");
    }

    @Test
    void anOrdinaryMembershipIsNobodysFamily(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        GenealogyModuleTest.claim(store, "Norway", "member of", "NATO", "model:research", "cited by I-0001-ordinary");
        Graph g = FamilyPeople.view(store);
        assertEquals("member-of", g.predicateOf("member of"), "genealogy's own view reads the words");
        assertTrue(FamilyHouses.all(g).isEmpty() && FamilyHouses.families(g, g.nodeIdOf("Norway")).isEmpty(), "and still makes nobody a member of a family");
        assertNotEquals("person", g.node(g.nodeIdOf("Norway")).kind());
    }

    static FamilyAccount.Outcome file(LibraryStore store, String locator, List<FamilyAccount.Fact> facts, List<FamilyAccount.FamilyRead> families) throws Exception {
        return FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), facts, List.of(), List.of(), List.of(), families), "an aunt", f -> List.of(locator), f -> List.of());
    }

    static FamilyAccount.Outcome named(LibraryStore store, String locator, List<FamilyAccount.Fact> facts, List<FamilyAccount.NameRead> names) throws Exception {
        return FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), facts, List.of(), List.of(), names, List.of()), "an aunt", f -> List.of(locator), f -> List.of());
    }

    static String page(LibraryStore store, String family) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        FamilyNamePages.cliFamily(store, family, new PrintStream(b, true, StandardCharsets.UTF_8));
        return b.toString(StandardCharsets.UTF_8);
    }

    static String names(LibraryStore store, String person) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        FamilyNamePages.cliNames(store, person, new PrintStream(b, true, StandardCharsets.UTF_8));
        return b.toString(StandardCharsets.UTF_8);
    }

    // families-2: a family with no seat took in the next family of its name that had one, and nothing asked
    @Test
    void aFamilyWithASeatIsNotJoinedIntoOneWithoutAndTheFamilyIsAsked(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q1 = "遠藤勇は遠藤家の当主であった。", q2 = "遠藤正一は広島県安芸郡の遠藤家の人である。";
        file(store, "file:///family/yamaguchi-book.txt", List.of(member("遠藤勇", "遠藤家", "", q1, Map.of("role", "head"))), List.of(new FamilyAccount.FamilyRead("遠藤", "遠藤家", "", q1)));
        file(store, "file:///family/hiroshima-letter.txt", List.of(member("遠藤正一", "遠藤家", "", q2, Map.of())), List.of(new FamilyAccount.FamilyRead("遠藤", "遠藤家", "広島県安芸郡", q2)));
        Graph g = FamilyPeople.view(store);
        String isamu = FamilyHouses.families(g, g.nodeIdOf("遠藤勇")).get(0).family(), shoichi = FamilyHouses.families(g, g.nodeIdOf("遠藤正一")).get(0).family();
        assertNotEquals(isamu, shoichi, "two sources, two 遠藤 families until somebody says they are one: " + FamilyHouses.all(g).stream().map(id -> FamilyHouses.labelOf(g, id)).toList());
        assertEquals("", FamilyHouses.seat(g, isamu), "the letter's seat is not written onto the family the book made");
        assertEquals("広島県安芸郡", FamilyHouses.seat(g, shoichi));
        List<FamilyNameQuestions.Question> asked = FamilyNameQuestions.open(store);
        assertTrue(asked.stream().anyMatch(q -> q.kind().equals("which-family")), "the family is asked whether the two are one: " + asked.stream().map(FamilyNameQuestions.Question::kind).toList());
        assertNull(FamilyHouses.find(g, "遠藤", "山口県大島郡"), "and a third seat finds neither");
    }

    // families-5: "the Hales" was read as a family named Hal
    @Test
    void thePluralOfAFamilyNameKeepsTheNameWhole(@TempDir Path tmp) throws Exception {
        assertEquals("Hale", FamilyHouses.familyName("the Hales"));
        assertEquals("Lee", FamilyHouses.familyName("the Lees"));
        assertEquals("Moore", FamilyHouses.familyName("the Moores"));
        assertEquals("Endo", FamilyHouses.familyName("the Endos"));
        assertEquals("Hart", FamilyHouses.familyName("the Harts"));
        assertEquals("Ellis", FamilyHouses.familyName("the Ellises"), "a name that ends in s takes -es");
        assertEquals("Hale", FamilyHouses.familyName("the Hales of Micklegate"), "with the place they lived");
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q1 = "Tom Hale was the head of the Hale family of York.", q2 = "In 1875 Mary Ellis married into the Hales.";
        file(store, "file:///family/book.txt", List.of(member("Tom Hale", "the Hale family", "", q1, Map.of("role", "head"))), List.of(new FamilyAccount.FamilyRead("Hale", "the Hale family", "York", q1)));
        file(store, "file:///family/letter.txt", List.of(member("Mary Ellis", "the Hales", "1875", q2, Map.of("how", "marriage"))), List.of());
        Graph g = FamilyPeople.view(store);
        assertEquals(List.of("Hale family (York)"), FamilyHouses.all(g).stream().map(id -> FamilyHouses.labelOf(g, id)).toList(), "one Hale family, no Hal family");
        assertEquals(FamilyHouses.families(g, g.nodeIdOf("Tom Hale")).get(0).family(), FamilyHouses.families(g, g.nodeIdOf("Mary Ellis")).get(0).family(), "she married into his family");
    }

    // families-7: 森田本家 was a family named 森田本, and 森田分家 one named 森田分
    @Test
    void aMainHouseAndItsBranchKeepTheFamilyName(@TempDir Path tmp) throws Exception {
        assertEquals("森田", FamilyHouses.familyName("森田本家"), "the main house is the family itself");
        assertEquals("遠藤", FamilyHouses.familyName("遠藤宗家"));
        assertEquals("森田", FamilyHouses.familyName("森田一族"));
        assertEquals("森田", FamilyHouses.nameOf("森田分家"), "a branch bears the family name");
        assertNotEquals(FamilyHouses.familyName("森田家"), FamilyHouses.familyName("森田分家"), "and is a family of its own");
        assertEquals("branch", FamilyHouses.branchKind("森田分家"));
        assertEquals("main", FamilyHouses.branchKind("森田本家"));
        assertEquals("main", FamilyHouses.branchKind("遠藤宗家"));
        assertEquals("", FamilyHouses.branchKind("森田家"));
        assertEquals("山田", FamilyHouses.familyName("山田本家"), "the longest word for a family that leaves a whole name");
        assertEquals("main", FamilyHouses.branchKind("山田本家"));
        assertEquals("髙橋", FamilyHouses.nameOf("髙橋分家"));
        assertEquals("田本", FamilyHouses.familyName("田本家"), "a two-character name that ends in 本 is that name and 家, not a main house of 田");
        assertEquals("", FamilyHouses.branchKind("田本家"));
        assertNotEquals("分", FamilyHouses.familyName("森田家の分家"), "words around a family in characters are no name");
        assertEquals("", FamilyHouses.familyName("分家"), "and the word alone names nobody's family");
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q = "森田分家は森田本家の分家である。森田正一は森田分家の当主であった。";
        file(store, "file:///family/book.txt", List.of(new FamilyAccount.Fact("森田分家", "branch-of", "森田本家", "", q), member("森田正一", "森田分家", "", q, Map.of("role", "head"))), List.of());
        Graph g = FamilyPeople.view(store);
        String main = g.nodeIdOf("森田本家"), branch = g.nodeIdOf("森田分家");
        assertTrue(FamilyHouses.isFamily(g, main) && FamilyHouses.isFamily(g, branch), FamilyHouses.all(g).stream().map(id -> FamilyHouses.labelOf(g, id)).toList().toString());
        assertNotEquals(main, branch, "the branch is a family of its own");
        assertEquals("森田", FamilyHouses.nameOf(FamilyHouses.labelOf(g, main)), "the main house is a 森田 family");
        assertEquals("森田", FamilyHouses.nameOf(FamilyHouses.labelOf(g, branch)), "and so is its branch");
        assertEquals(main, FamilyHouses.branchOf(g, branch));
        assertEquals(g.nodeIdOf("森田正一"), FamilyHouses.heads(g, branch).get(0).person(), "the head of the branch");
        assertTrue(FamilyHouses.heads(g, main).isEmpty(), "is not the head of the main house");
        assertFalse(FamilyNameHistory.of(g).familyParts().contains("森田分") || FamilyNameHistory.of(g).familyParts().contains("森田本"), FamilyNameHistory.of(g).familyParts().toString());
        String listed = page(store, "森田");
        assertFalse(listed.contains("holds no 森田 family"), listed);
        assertTrue(listed.contains(FamilyHouses.labelOf(g, main)) && listed.contains(FamilyHouses.labelOf(g, branch)), listed);
        // a later text that says 森田家 means the main house, not a third family
        String q2 = "森田勇は森田家の人である。";
        file(store, "file:///family/letter.txt", List.of(member("森田勇", "森田家", "", q2, Map.of())), List.of());
        Graph g2 = FamilyPeople.view(store);
        assertEquals(2, FamilyHouses.all(g2).size(), FamilyHouses.all(g2).stream().map(id -> FamilyHouses.labelOf(g2, id)).toList().toString());
        assertEquals(main, FamilyHouses.families(g2, g2.nodeIdOf("森田勇")).get(0).family());
    }

    // families-6: a head claim without "how" was joined into the person's birth membership and took its year
    @Test
    void aHeadIsAHeadFromItsOwnYearNotFromTheBirth(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q1 = "森田勇は明治四十年に森田家の家督を相続した。", q2 = "健一は1900年に森田家に生まれた。", q3 = "健一は1950年に森田家の当主となった。";
        file(store, "file:///family/book.txt", List.of(
                member("森田勇", "森田家", "1907", q1, Map.of("role", "head", "how", "succession")),
                member("森田健一", "森田家", "1900", q2, Map.of("how", "birth"))), List.of());
        file(store, "file:///family/letter.txt", List.of(member("森田健一", "森田家", "1950", q3, Map.of("role", "head"))), List.of());
        Graph g = FamilyPeople.view(store);
        String morita = g.nodeIdOf("森田 family");
        List<FamilyHouses.Membership> heads = FamilyHouses.heads(g, morita);
        assertEquals(List.of(g.nodeIdOf("森田勇") + "@1907", g.nodeIdOf("森田健一") + "@1950"), heads.stream().map(m -> m.person() + "@" + (m.from() == null ? "" : m.from().year())).toList(), "the heads in order, each from the year they became head");
        List<FamilyHouses.Membership> his = FamilyHouses.families(g, g.nodeIdOf("森田健一"));
        assertEquals(2, his.size(), "born into it, head of it later: " + his);
        assertEquals("birth", his.get(0).how());
        assertEquals("", his.get(0).role());
        assertTrue(heads.stream().noneMatch(m -> m.person().equals(g.nodeIdOf("森田健一")) && m.holds(1905)), "in 1905 the head was 森田勇");
        assertFalse(page(store, "森田 family").contains("born into it as its head"), page(store, "森田 family"));
    }

    // families-8: a claim of how somebody came in and how they left, dated by the leaving, showed the leaving as the coming in
    @Test
    void aLeavingYearIsTheYearTheyLeft(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q = "森田正二は1910年に森田家に生まれ、1940年に髙橋家の養子となって森田家を出た。";
        file(store, "file:///family/book.txt", List.of(
                member("森田正二", "森田家", "1940", q, Map.of("how", "birth", "left", "adoption-out")),
                new FamilyAccount.Fact("森田正二", "born-on", "1910", "", q)), List.of());
        Graph g = FamilyPeople.view(store);
        FamilyHouses.Membership m = FamilyHouses.families(g, g.nodeIdOf("森田正二")).get(0);
        assertNull(m.from(), "the words do not date the coming in apart from the birth");
        assertEquals(1940, m.to().year(), "1940 is the year he left");
        String shown = names(store, "森田正二");
        assertFalse(shown.contains("born into it in 1940"), shown);
        assertTrue(shown.contains("left it in 1940"), shown);
        // the same words, with the fact dated by the birth: the year is the coming in
        LibraryStore other = FamilyNameHistoryTest.store(tmp.resolve("other"));
        file(other, "file:///family/book.txt", List.of(
                member("森田正二", "森田家", "1910", q, Map.of("how", "birth", "left", "adoption-out")),
                new FamilyAccount.Fact("森田正二", "born-on", "1910", "", q)), List.of());
        Graph g2 = FamilyPeople.view(other);
        FamilyHouses.Membership b = FamilyHouses.families(g2, g2.nodeIdOf("森田正二")).get(0);
        assertEquals(1910, b.from() == null ? 0 : b.from().year(), "born into it in 1910");
        assertNull(b.to(), "and the year he left is not given there");
    }

    // R4: undated memberships came out in the wrong order, so came-from and went-to were reversed
    @Test
    void undatedMembershipsFollowTheNames(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q1 = "Tadashi's son Masaru (森田勝) was born in 1962.", q2 = "In 1990 Masaru became Takahashi Masaru (髙橋勝) and carried on the Takahashi line.";
        String q3 = "Kenji and Haru had one son, Morita Tadashi (森田正), born in 1934.";
        String q4 = "Isamu's son Shōji (森田正二) left the Morita family in 1940, when he was adopted as heir into the Takahashi family (髙橋家) of Nagano, and was known afterwards as Takahashi Shōji (髙橋正二).";
        String q5 = "In 1932 he married Haru (森田ハル) and entered the Morita family (森田家) as mukoyōshi (婿養子) of Isamu.";
        named(store, "file:///family/book.txt", List.of(
                        member("森田健二", "森田家", "1932", q5, Map.of("how", "mukoyoshi")),
                        new FamilyAccount.Fact("森田正", "child-of", "森田健二", "", q3),
                        member("髙橋勝", "森田家", "", q1, Map.of()),
                        member("髙橋勝", "髙橋家", "1990", q2, Map.of()),
                        new FamilyAccount.Fact("髙橋勝", "born-on", "1962", "", q1),
                        new FamilyAccount.Fact("髙橋勝", "child-of", "森田正", "", q1),
                        member("森田正", "森田家", "", q3, Map.of()),
                        new FamilyAccount.Fact("森田正", "born-on", "1934", "", q3),
                        member("髙橋正二", "森田家", "", q4, Map.of()),
                        member("髙橋正二", "髙橋家", "1940", q4, Map.of())),
                List.of(new FamilyAccount.NameRead("髙橋勝", "森田勝", "森田", "勝", List.of(), "birth", "", "1962", q1),
                        new FamilyAccount.NameRead("髙橋勝", "髙橋勝", "髙橋", "勝", List.of(), "unknown", "became", "1990", q2),
                        new FamilyAccount.NameRead("髙橋正二", "森田正二", "森田", "正二", List.of(), "unknown", "", "", q4),
                        new FamilyAccount.NameRead("髙橋正二", "髙橋正二", "髙橋", "正二", List.of(), "adoptive", "was known afterwards as", "1940", q4)));
        Graph g = FamilyPeople.view(store);
        String morita = g.nodeIdOf("森田 family"), takahashi = g.nodeIdOf("髙橋 family");
        for (String who : List.of("髙橋勝", "髙橋正二")) {
            List<FamilyHouses.Membership> ms = FamilyHouses.families(g, g.nodeIdOf(who));
            assertEquals(List.of(morita, takahashi), ms.stream().map(FamilyHouses.Membership::family).toList(), who + ": the family of the first name first");
            assertEquals("", ms.get(0).cameFrom(), who + " was born into the 森田 family");
            assertEquals(takahashi, ms.get(0).wentTo(), who + " went from the 森田 family to the 髙橋 family");
            assertEquals(morita, ms.get(1).cameFrom(), who + " came to the 髙橋 family from the 森田 family");
            assertEquals("", ms.get(1).wentTo());
        }
        List<String> members = FamilyHouses.members(g, morita).stream().map(FamilyHouses.Membership::person).distinct().toList();
        assertEquals(List.of(g.nodeIdOf("森田健二"), g.nodeIdOf("森田正"), g.nodeIdOf("髙橋勝"), g.nodeIdOf("髙橋正二")), members,
                "the 森田 members in the order they came in: 健二 in 1932, his son 正 born 1934, 勝 born 1962, and 正二, whose birth year is not written, last");
        assertEquals("", FamilyHouses.families(g, g.nodeIdOf("森田正")).get(0).cameFrom(), "正 was born into the family of his father");
        String shown = page(store, "森田 family");
        assertFalse(shown.contains("coming from 髙橋 family"), shown);
    }

    // N3: the membership of the family he was born into was dated by the year he left it, so the page said he came in that year
    @Test
    void theMembershipOfTheBirthFamilyBeginsAtTheBirthAndEndsWhereTheNextFamilyBegins(@TempDir Path tmp) throws Exception {
        String q1 = "Isamu (森田勇) was the head of the Morita family (森田家).";
        String q4 = "Isamu's son Shōji (森田正二) left the Morita family in 1940, when he was adopted as heir into the Takahashi family (髙橋家) of Nagano, and was known afterwards as Takahashi Shōji (髙橋正二).";
        List<FamilyAccount.Fact> facts = List.of(
                member("森田勇", "森田家", "", q1, Map.of("role", "head", "how", "unstated")),
                new FamilyAccount.Fact("髙橋正二", "child-of", "森田勇", "", q4),
                member("髙橋正二", "森田家", "1940", q4, Map.of()),
                member("髙橋正二", "髙橋家", "1940", q4, Map.of("how", "adoption", "role", "heir")));
        // by his first name, and again with no names at all: then by the family his father belonged to when he was born
        for (boolean withNames : List.of(true, false)) {
            LibraryStore store = FamilyNameHistoryTest.store(tmp.resolve(withNames ? "names" : "parent"));
            named(store, "file:///family/book.txt", facts, !withNames ? List.of() : List.of(
                    new FamilyAccount.NameRead("髙橋正二", "森田正二", "森田", "正二", List.of(), "unknown", "", "", q4),
                    new FamilyAccount.NameRead("髙橋正二", "髙橋正二", "髙橋", "正二", List.of(), "adoptive", "was known afterwards as", "1940", q4)));
            Graph g = FamilyPeople.view(store);
            String morita = g.nodeIdOf("森田 family"), takahashi = g.nodeIdOf("髙橋 family");
            assertTrue(FamilyHouses.isFamily(g, morita) && FamilyHouses.isFamily(g, takahashi), FamilyHouses.all(g).toString());
            String how = withNames ? "by his first name" : "by his father's family";
            List<FamilyHouses.Membership> ms = FamilyHouses.families(g, g.nodeIdOf("髙橋正二"));
            assertEquals(List.of(morita, takahashi), ms.stream().map(FamilyHouses.Membership::family).toList(), how + ": " + ms);
            FamilyHouses.Membership born = ms.get(0);
            assertNull(born.from(), how + ": he was born into the 森田 family, and 1940 is not the year he came in: " + born);
            assertEquals(1940, born.to() == null ? 0 : born.to().year(), how + ": he left it when he came into the 髙橋 family, in 1940: " + born);
            assertEquals(takahashi, born.wentTo(), how);
            assertEquals(1940, ms.get(1).from() == null ? 0 : ms.get(1).from().year(), how + ": the family he came into keeps the year its claim gives");
            assertEquals(morita, ms.get(1).cameFrom(), how);
            String shown = page(store, "森田 family");
            assertFalse(shown.contains("a member from 1940"), how + ": " + shown);
            assertTrue(shown.contains("left it in 1940"), how + ": " + shown);
            assertNull(FamilyHouses.heads(g, morita).get(0).to(), how + ": the head's own membership is not ended by anything of his son's");
        }
    }

    // e-asking-birth-membership-by-name: two 家 of one name in two places. A woman born into one of them, and listed in the other in 1932 with
    // nothing said of how, was shown as born into the second and lost its year, because the family's name was all that was compared
    @Test
    void aMembershipOfAnotherFamilyOfTheSameNameKeepsItsYear(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q1 = "森田ハルは1905年に広島の森田家に生まれた。", q2 = "1932年、森田ハルは長野の森田家の人となる。";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                        new FamilyAccount.Fact("森田ハル", "born-on", "1905", "", q1),
                        member("森田ハル", "森田家 (広島)", "1905", q1, Map.of("how", "birth"))), List.of(), List.of(),
                        List.of(new FamilyAccount.NameRead("森田ハル", "森田ハル", "森田", "ハル", List.of(), "birth", "生まれた", "1905", q1)),
                        List.of(new FamilyAccount.FamilyRead("森田", "森田家 (広島)", "広島", q1))),
                "an aunt", f -> List.of("file:///family/a.txt"), f -> List.of());
        file(store, "file:///family/b.txt", List.of(member("森田ハル", "森田家 (長野)", "1932", q2, Map.of())), List.of(new FamilyAccount.FamilyRead("森田", "森田家 (長野)", "長野", q2)));
        Graph g = FamilyPeople.view(store);
        List<String> ms = FamilyHouses.families(g, g.nodeIdOf("森田ハル")).stream().map(m -> FamilyHouses.seat(g, m.family()) + " " + m.how() + " " + (m.from() == null ? "" : m.from().year())).toList();
        assertEquals(List.of("広島 birth 1905", "長野  1932"), ms, "born into the 森田 family of 広島; in the 森田 family of 長野 from 1932, as the claim says");
    }

    // N3, the end it works out: only an adoption into another family takes a person out of the family they were born into. A woman who married
    // into another family may stay in the one she was born into (a Korean clan, a family in England), and an end worked out is said to be so
    @Test
    void onlyAnAdoptionEndsTheBirthFamilysMembershipAndTheEndItWorksOutIsShownAndExportedAsWorkedOut(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp.resolve("marriage"));
        String q1 = "Ann Lee was born into the Lee family of York.", q2 = "In 1950 Ann Lee married Tom Hart and joined the Hart family.";
        file(store, "file:///family/letter.txt", List.of(member("Ann Lee", "the Lee family", "", q1, Map.of("how", "birth")), member("Ann Lee", "the Hart family", "1950", q2, Map.of("how", "marriage"))),
                List.of(new FamilyAccount.FamilyRead("Lee", "the Lee family", "", q1), new FamilyAccount.FamilyRead("Hart", "the Hart family", "", q2)));
        Graph g = FamilyPeople.view(store);
        List<FamilyHouses.Membership> ms = FamilyHouses.families(g, g.nodeIdOf("Ann Lee"));
        FamilyHouses.Membership lee = ms.stream().filter(m -> FamilyHouses.labelOf(g, m.family()).startsWith("Lee")).findFirst().orElseThrow(() -> new AssertionError(ms.toString()));
        assertNull(lee.to(), "a marriage into the Hart family does not end her membership of the Lee family: " + ms);

        LibraryStore adopted = FamilyNameHistoryTest.store(tmp.resolve("adoption"));
        String q3 = "Isamu (森田勇) was the head of the Morita family (森田家).";
        String q4 = "Isamu's son Shōji (森田正二) left the Morita family in 1940, when he was adopted as heir into the Takahashi family (髙橋家) of Nagano, and was known afterwards as Takahashi Shōji (髙橋正二).";
        named(adopted, "file:///family/book.txt", List.of(
                member("森田勇", "森田家", "", q3, Map.of("role", "head", "how", "unstated")),
                new FamilyAccount.Fact("髙橋正二", "child-of", "森田勇", "", q4),
                member("髙橋正二", "森田家", "1940", q4, Map.of()),
                member("髙橋正二", "髙橋家", "1940", q4, Map.of("how", "adoption", "role", "heir"))), List.of());
        String shown = page(adopted, "森田 family");
        assertTrue(shown.contains("left it in 1940"), shown);
        assertTrue(shown.contains("left it in 1940 (the library worked out the year from when they came into their next family"), "the year he left is worked out, and the page says so: " + shown);
        String file = Gedcom.export(adopted, "髙橋正二");
        assertFalse(file.contains("TO 1940"), "a year worked out is no year a source gave, and a tree file does not write it as one: " + file);
    }

    // d-names-5: another romaji spelling of a family known in characters found no family, and a read made a second one
    @Test
    void aFamilyKnownInCharactersIsFoundByEveryRomajiSpellingOfItsName(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp.resolve("endo"));
        String q = "Shōichi (遠藤正一) was the head of the Endo family (遠藤家).";
        file(store, "file:///family/book.txt", List.of(member("遠藤正一", "遠藤家", "", q, Map.of("role", "head"))), List.of(new FamilyAccount.FamilyRead("遠藤", "the Endo family", "", q)));
        Graph g = FamilyPeople.view(store);
        String endo = g.nodeIdOf("遠藤 family");
        for (String w : List.of("Endo", "Endō", "Endou", "Endoh", "Endoo", "the Endoh family"))
            assertEquals(List.of(endo), FamilyHouses.named(g, w), w + " is a romaji spelling of the name of the 遠藤 family: "
                    + FamilyHouses.all(g).stream().map(id -> FamilyHouses.labelOf(g, id) + " also " + FamilyHouses.aliasesOf(g, id)).toList());
        assertEquals(endo, FamilyHouses.find(g, "Endoh", ""));
        String q2 = "In 1925 the Endoh family sold the shop.";
        file(store, "file:///family/letter.txt", List.of(member("遠藤正一", "the Endoh family", "1925", q2, Map.of("role", "head"))), List.of(new FamilyAccount.FamilyRead("Endoh", "the Endoh family", "", q2)));
        Graph g2 = FamilyPeople.view(store);
        assertEquals(List.of("遠藤 family"), FamilyHouses.all(g2).stream().map(id -> FamilyHouses.labelOf(g2, id)).toList(), "the letter's spelling is the same family, not a second one");
        assertEquals(List.of(g2.nodeIdOf("遠藤 family")), FamilyHouses.families(g2, g2.nodeIdOf("遠藤正一")).stream().map(FamilyHouses.Membership::family).distinct().toList());
        // a family known only in Latin letters is no Japanese name: Endoh is another name than Endo there, as Gould is not Gold
        LibraryStore latin = FamilyNameHistoryTest.store(tmp.resolve("latin"));
        String q3 = "Tom Endo was the head of the Endo family.";
        file(latin, "file:///family/book.txt", List.of(member("Tom Endo", "the Endo family", "", q3, Map.of("role", "head"))), List.of(new FamilyAccount.FamilyRead("Endo", "the Endo family", "", q3)));
        Graph g3 = FamilyPeople.view(latin);
        assertEquals(1, FamilyHouses.named(g3, "Endo").size());
        assertTrue(FamilyHouses.named(g3, "Endoh").isEmpty(), "a family with no form in characters or kana is not read as romaji");
    }

    // families-4 (the words): an institution, a religious society, a common noun and a family with no name were families by their words alone
    @Test
    void wordsThatAlsoNameOtherThingsAreAFamilyOnlyBesideAKnownFamilyName() {
        for (String w : List.of("the House of Commons", "the Quakers", "the books", "the house burned", "a railway line", "the family", "In 1932 he entered the family as 婿養子",
                "分家", "本家に戻った", "作家の森田健二", "国家", "政治家になった"))
            assertFalse(FamilyHouses.familyWord(w), w + " names no family by its words alone");
        for (String w : List.of("the Morita family", "森田家", "森田一族", "森田分家", "森田一家", "森田家の当主", "the Hale clan", "Endo's son entered the Morita family", "the van Hart family", "森田 family", "森田 branch family (広島)"))
            assertTrue(FamilyHouses.familyWord(w), w);
        assertEquals("森田", FamilyHouses.familyName("森田一家"));
        assertNotEquals("作", FamilyHouses.familyName("作家"), "a writer is no family named 作");
        assertTrue(FamilyHouses.familyWord("the Hales", List.of("Hale")), "the Hales, where Hale is a family name the library knows");
        assertTrue(FamilyHouses.familyWord("the house of Hale", List.of("Hale")));
        assertTrue(FamilyHouses.familyWord("the Endō line", List.of("Endo")), "in any of its spellings");
        assertFalse(FamilyHouses.familyWord("the House of Commons", List.of("Hale")));
        assertFalse(FamilyHouses.familyWord("the Quakers", List.of("Hale", "Ellis")));
        assertEquals("", FamilyHouses.familyName("the family"), "no name, so no family name");
        assertEquals("", FamilyHouses.familyName("a railway line"));
        assertEquals("", FamilyHouses.familyName("the books"));
        assertEquals("van Hart", FamilyHouses.familyName("the van Hart family"));
    }

    @Test
    void theWordsForAFamily() {
        assertEquals("森田", FamilyHouses.familyName("森田家"));
        assertEquals("Morita", FamilyHouses.familyName("the Morita family"));
        assertEquals("Hale", FamilyHouses.familyName("the house of Hale"));
        assertEquals("Endo", FamilyHouses.familyName("the Endos"));
        assertEquals("遠藤", FamilyHouses.familyName("遠藤 family (広島県安芸郡)"));
        assertEquals("遠藤 family (of 遠藤正一)", FamilyHouses.label("遠藤", "", "遠藤正一"));
        assertTrue(FamilyHouses.familyWord("Endo's son entered the Morita family") && FamilyHouses.familyWord("森田家の婿養子"));
        assertFalse(FamilyHouses.familyWord("Endo's son, Morita Kenji"));
    }
}
