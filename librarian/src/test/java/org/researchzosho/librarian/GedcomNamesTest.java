package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A tree file's names and families: every NAME is a name with its kind and its forms, a married name and an alias are names of their own,
 * an adoption says which parent adopted and when, 婿養子 enters the family, the library's own _HOUSE and _MEMBER records are families and
 * memberships, and an export read back gives the same names and memberships.
 */
class GedcomNamesTest {

    private static LibraryStore store(Path tmp, String name) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve(name)); store.init();
        return store;
    }

    private static Path ged(Path tmp, String name, String body) throws Exception {
        Path f = tmp.resolve(name);
        Files.writeString(f, "0 HEAD\n1 CHAR UTF-8\n" + body + "0 TRLR\n", StandardCharsets.UTF_8);
        return f;
    }

    private static List<Finding> claims(LibraryStore store, String subject, String predicate) {
        return store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().subject().equals(subject) && f.triple().predicate().equals(predicate)
                && f.state() != Finding.State.superseded).toList();
    }

    private static Finding name(LibraryStore store, String person, String name) {
        return claims(store, person, "has-name").stream().filter(f -> f.triple().object().equals("name: " + name)).findFirst().orElseThrow(() -> new AssertionError("no name " + name + " of " + person));
    }

    static final String NAMES = "0 @I1@ INDI\n1 NAME 健二 /遠藤/\n2 TYPE birth\n2 ROMN Kenji /Endo/\n3 TYPE romaji\n2 FONE けんじ /えんどう/\n3 TYPE kana\n"
            + "1 NAME 健二 /森田/\n2 TYPE 婿養子\n2 _NAMEKIND mukoyoshi\n2 _NAMEDATE FROM 1932\n2 TRAN Kenji /Morita/\n3 LANG ja-Latn\n"
            + "1 NAME Kenji /Hart/\n2 TYPE aka\n1 SEX M\n1 BIRT\n2 DATE 1905\n"
            + "0 @I2@ INDI\n1 NAME Mary /Hale/\n2 TYPE maiden\n2 _MARNM Ellis\n1 SEX F\n1 BIRT\n2 DATE 1850\n"
            + "0 @I3@ INDI\n1 NAME Tom /Hart/\n1 NAME John /Hart/\n1 BIRT\n2 DATE 1860\n"
            + "0 @I4@ INDI\n1 NAME Ruth /Ellis/\n2 TYPE OTHER\n3 PHRASE her pen name\n1 BIRT\n2 DATE 1870\n"
            + "0 @I5@ INDI\n1 NAME John /Hale/\n2 TYPE birth\n1 BIRT\n2 DATE 1880\n";

    @Test
    void everyNameIsANameWithItsKindAndItsFormsStayWithTheirName(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, "lib");
        Path file = ged(tmp, "names.ged", NAMES);
        Gedcom.Outcome o = Gedcom.importFile(store, file);
        assertEquals(7, o.names(), "three names of Kenji, two of Mary, Tom's second name and Ruth's pen name");

        Finding birth = name(store, "遠藤健二", "遠藤健二");
        Map<String, String> b = FamilyDetail.of(birth);
        assertEquals("birth", b.get("kind"));
        assertEquals("遠藤", b.get("family"));
        assertEquals("健二", b.get("given"));
        assertEquals("遠藤健二@ja-Hani,Kenji Endo@ja-Latn,えんどう けんじ@ja-Hira", b.get("forms"), "ROMN and FONE are forms of the NAME they stand under");
        assertEquals("遠藤健二 was named 遠藤健二 at birth.", birth.title());
        assertEquals("gedcom-import", birth.writer());

        Finding later = name(store, "遠藤健二", "森田健二");
        Map<String, String> l = FamilyDetail.of(later);
        assertEquals("mukoyoshi", l.get("kind"), "the library's own _NAMEKIND");
        assertEquals("1932", l.get("from"));
        assertEquals("婿養子", l.get("said"), "the file's own word for how");
        assertEquals("森田健二@ja-Hani,Kenji Morita@ja-Latn", l.get("forms"), "a TRAN with its LANG is a form of its NAME");
        assertTrue(later.body().startsWith("遠藤健二 was named 森田健二 on entering the family as 婿養子 (adopted and married) (1932)."), later.body());
        assertTrue(later.sources().get(0).edition().startsWith("GEDCOM @I1@ NAME 健二 /森田/"), later.sources().get(0).edition());
        assertEquals("aka", FamilyDetail.get(name(store, "遠藤健二", "Kenji Hart"), "kind"));

        assertEquals("marriage", FamilyDetail.get(name(store, "Mary Hale", "Mary Ellis"), "kind"), "a married name is a name of its own");
        assertEquals("birth", FamilyDetail.get(name(store, "Mary Hale", "Mary Hale"), "kind"), "maiden is the name at birth");
        assertEquals("art", FamilyDetail.get(name(store, "Ruth Ellis", "Ruth Ellis"), "kind"), "OTHER with its PHRASE");
        assertEquals("her pen name", FamilyDetail.get(name(store, "Ruth Ellis", "Ruth Ellis"), "said"));
        assertTrue(claims(store, "John Hale", "has-name").isEmpty(), "a person's only name, which they were born with, is the name they are filed under and no claim");
        List<Finding> toms = claims(store, "Tom Hart", "has-name");
        assertEquals(1, toms.size(), "the name Tom is filed under says nothing more than the name, and is no claim: " + toms);
        assertEquals("unknown", FamilyDetail.get(toms.get(0), "kind"), "a second NAME with no TYPE is a name whose kind is not known");

        Graph g = FamilyPeople.view(store);
        String kenji = g.nodeIdOf("遠藤健二");
        for (String other : List.of("森田健二", "Kenji Endo", "えんどう けんじ", "Kenji Morita", "Kenji Hart")) assertEquals(kenji, g.nodeIdOf(other), other + " leads to him");
        assertEquals("value", g.node(g.nodeIdOf("name: 森田健二")).kind(), "a name is a value beside the person, never a person");
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        assertEquals("森田健二 (born 遠藤)", idx.heading(kenji));
        assertEquals("遠藤健二", idx.at(kenji, 1920).written());
        assertEquals("森田健二", idx.at(kenji, 1940).written());
        assertEquals("Mary Ellis (born Hale)", idx.heading(g.nodeIdOf("Mary Hale")));

        // the same file again: nothing new, nothing replaced
        Gedcom.Outcome again = Gedcom.importFile(store, file);
        assertEquals(0, again.findings(), again.problems().toString());
        assertEquals(0, again.superseded());
        assertTrue(again.problems().stream().noneMatch(p -> p.contains("replaced")), again.problems().toString());
    }

    @Test
    void anAdoptionTypesOnlyTheParentWhoAdoptedWithItsDate(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, "lib");
        Gedcom.Outcome o = Gedcom.importFile(store, ged(tmp, "hale.ged",
                // John came into the Hale family by adoption, and the file says Tom adopted him: how he is related to Ann it does not say
                "0 @I1@ INDI\n1 NAME John /Ellis/\n1 SEX M\n1 FAMC @F1@\n2 PEDI adopted\n1 ADOP\n2 DATE 1860\n2 FAMC @F1@\n3 ADOP HUSB\n"
                        + "0 @I2@ INDI\n1 NAME Tom /Hale/\n1 SEX M\n1 FAMS @F1@\n0 @I3@ INDI\n1 NAME Ann /Hale/\n1 SEX F\n1 FAMS @F1@\n"
                        // Mary was adopted by her mother's husband: her mother stays her mother by birth
                        + "0 @I4@ INDI\n1 NAME Mary /Hart/\n1 SEX F\n1 FAMC @F2@\n1 ADOP\n2 DATE 1870\n2 FAMC @F2@\n3 ADOP HUSB\n"
                        + "0 @I5@ INDI\n1 NAME Tom /Hart/\n1 SEX M\n1 FAMS @F2@\n0 @I6@ INDI\n1 NAME Ruth /Hart/\n1 SEX F\n1 FAMS @F2@\n"
                        + "0 @F1@ FAM\n1 HUSB @I2@\n1 WIFE @I3@\n1 CHIL @I1@\n0 @F2@ FAM\n1 HUSB @I5@\n1 WIFE @I6@\n1 CHIL @I4@\n"));
        List<Finding> john = claims(store, "John Ellis", "adopted-by");
        assertEquals(1, john.size(), john.toString());
        assertEquals("Tom Hale", john.get(0).triple().object());
        assertEquals("John Ellis was adopted by Tom Hale (1860).", john.get(0).title());
        assertEquals("1860", FamilyDetail.get(john.get(0), "from"));
        assertTrue(store.scanFindings().findings().stream().noneMatch(f -> f.triple() != null && f.triple().toString().contains("Ann Hale") && f.triple().toString().contains("John Ellis")),
                "no link to the parent the file does not say anything of");
        assertTrue(o.problems().stream().anyMatch(p -> p.contains("John Ellis was adopted by Tom Hale alone") && p.contains("no link between John Ellis and Ann Hale")), o.problems().toString());
        assertEquals("Tom Hart", claims(store, "Mary Hart", "adopted-by").get(0).triple().object());
        assertEquals(1, claims(store, "Ruth Hart", "parent-of").size(), "the mother who did not adopt is her mother by birth");
        assertTrue(claims(store, "Tom Hart", "parent-of").isEmpty(), "and the man who adopted her is not her father by birth");
    }

    /** Kenji born 遠藤, entering the 森田 family as 婿養子 in 1932; the 遠藤 family's head 正一 in a branch line; the library's own records. */
    static final String MUKOYOSHI = "0 @H1@ _HOUSE\n1 NAME 森田\n1 NAME 森田家\n1 _SEAT 広島県安芸郡\n0 @H2@ _HOUSE\n1 NAME 遠藤\n0 @H3@ _HOUSE\n1 NAME 遠藤\n1 _SEAT 山口県\n1 _BRANCH @H2@\n"
            + "0 @I1@ INDI\n1 NAME 健二 /遠藤/\n2 TYPE birth\n2 ROMN Endō Kenji\n2 FONE けんじ /えんどう/\n1 NAME 健二 /森田/\n2 _NAMEKIND mukoyoshi\n2 _NAMEEVENT ADOP\n2 ROMN Kenji /Morita/\n"
            + "1 SEX M\n1 BIRT\n2 DATE 1905\n1 FAMC @F1@\n1 FAMC @F2@\n2 PEDI adopted\n1 ADOP\n2 DATE 1932\n2 TYPE 婿養子\n2 FAMC @F2@\n3 ADOP HUSB\n1 FAMS @F3@\n"
            + "1 _MEMBER @H2@\n2 DATE TO 1932\n2 _HOW birth\n2 _LEFT adoption-out\n"
            + "0 @I2@ INDI\n1 NAME 勇 /森田/\n1 SEX M\n1 BIRT\n2 DATE 1875\n1 FAMS @F2@\n1 _MEMBER @H1@\n2 _ROLE head\n"
            + "0 @I3@ INDI\n1 NAME ハル /森田/\n1 SEX F\n1 BIRT\n2 DATE 1910\n1 FAMC @F2@\n1 FAMS @F3@\n1 _MEMBER @H1@\n2 _HOW birth\n"
            + "0 @I4@ INDI\n1 NAME 正一 /遠藤/\n1 SEX M\n1 BIRT\n2 DATE 1870\n1 FAMS @F1@\n1 _MEMBER @H3@\n2 _ROLE head\n"
            + "0 @F1@ FAM\n1 HUSB @I4@\n1 CHIL @I1@\n0 @F2@ FAM\n1 HUSB @I2@\n1 CHIL @I1@\n1 CHIL @I3@\n0 @F3@ FAM\n1 HUSB @I1@\n1 WIFE @I3@\n1 MARR\n2 DATE 1932\n";

    @Test
    void mukoyoshiIsAnAdoptionAMarriageAndAnEntryIntoTheFamily(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, "lib");
        Gedcom.Outcome o = Gedcom.importFile(store, ged(tmp, "tree.ged", MUKOYOSHI));
        assertEquals(3, o.houses());
        Finding adoption = claims(store, "遠藤健二", "adopted-by").get(0);
        assertEquals("森田勇", adoption.triple().object());
        assertEquals("遠藤健二 was adopted by 森田勇 as 婿養子 (adopted and married) (1932).", adoption.title());
        assertEquals(Map.of("kind", "mukoyoshi", "from", "1932", "said", "婿養子"), FamilyDetail.of(adoption));
        assertEquals("遠藤正一", claims(store, "遠藤正一", "parent-of").get(0).triple().subject(), "the birth father stays");
        assertEquals("森田ハル", claims(store, "遠藤健二", "married-to").get(0).triple().object());

        Finding named = name(store, "遠藤健二", "森田健二");
        assertEquals("event", FamilyDetail.get(named, "from"), "the name dates from the adoption the file names");
        assertEquals(adoption.id(), FamilyDetail.get(named, "event"));
        assertEquals(1932, FamilyChecks.claimDate(named).year());

        Graph g = FamilyPeople.view(store);
        String kenji = g.nodeIdOf("遠藤健二"), morita = g.nodeIdOf("森田 family (広島県安芸郡)"), endo = g.nodeIdOf("遠藤 family"), branch = g.nodeIdOf("遠藤 family (山口県)");
        assertEquals("family", g.node(morita).kind(), FamilyHouses.all(g).toString());
        assertEquals(morita, g.nodeIdOf("森田家"), "the name the file writes with a family word is the family's other name");
        assertEquals("広島県安芸郡", FamilyHouses.seat(g, morita));
        assertEquals(endo, FamilyHouses.branchOf(g, branch), "two families of one name stay two, and one is a branch of the other");
        List<FamilyHouses.Membership> his = FamilyHouses.families(g, kenji);
        assertEquals(List.of(endo, morita), his.stream().map(FamilyHouses.Membership::family).toList());
        FamilyHouses.Membership entered = his.get(1);
        assertEquals("mukoyoshi", entered.how(), "the 婿養子 adoption enters him into the family of the man who adopted him");
        assertEquals(1932, entered.from().year());
        assertEquals(endo, entered.cameFrom());
        assertEquals("adoption-out", his.get(0).left());
        Finding member = store.finding(entered.claims().get(0));
        assertEquals(adoption.id(), FamilyDetail.get(member, "event"));
        assertTrue(member.sources().get(0).edition().contains("entered by the 婿養子 adoption the file records"), member.sources().get(0).edition());
        assertEquals(List.of(g.nodeIdOf("森田勇")), FamilyHouses.heads(g, morita).stream().map(FamilyHouses.Membership::person).toList());
        assertEquals("森田健二 (born 遠藤)", FamilyNameHistory.of(g).heading(kenji));
    }

    /** What a library says of a person's names: kind, the years, and the written forms, for two libraries to be compared. */
    private static List<String> names(Graph g, String person) {
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        List<String> out = new ArrayList<>();
        for (FamilyNameHistory.Name n : idx.names(g.nodeIdOf(person))) {
            TreeSet<String> forms = new TreeSet<>();
            for (String t : n.texts()) forms.add(FamilyForms.script(t).equals("latin") ? FamilyForms.latinKey(t) : t.replaceAll("\\s+", ""));
            out.add(n.kind() + " " + (n.from() == null ? "-" : n.from().year()) + "–" + (n.to() == null ? "-" : n.to().year()) + " " + forms + (n.implicit() ? " implicit" : ""));
        }
        out.sort(null);
        return out;
    }

    /** What a library says of a person's memberships: the family's name and seat, how, left, role and the years. */
    private static List<String> memberships(Graph g, String person) {
        List<String> out = new ArrayList<>();
        for (FamilyHouses.Membership m : FamilyHouses.families(g, g.nodeIdOf(person))) {
            String of = FamilyHouses.branchOf(g, m.family());
            out.add(FamilyHouses.nameOf(FamilyHouses.labelOf(g, m.family())) + " seat=" + FamilyHouses.seat(g, m.family()) + " branch-of=" + (of == null ? "" : FamilyHouses.seat(g, of) + FamilyHouses.nameOf(FamilyHouses.labelOf(g, of)))
                    + " how=" + m.how() + " left=" + m.left() + " role=" + m.role() + " " + (m.from() == null ? "-" : m.from().year()) + "–" + (m.to() == null ? "-" : m.to().year()));
        }
        out.sort(null);
        return out;
    }

    @Test
    void anExportReadBackGivesTheSameNamesAndMemberships(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, "lib");
        Gedcom.importFile(store, ged(tmp, "tree.ged", MUKOYOSHI));
        Gedcom.importFile(store, ged(tmp, "hart.ged", "0 @I1@ INDI\n1 NAME Tom /Hart/\n1 NAME John /Hart/\n1 BIRT\n2 DATE 1860\n"));
        String out = Gedcom.export(store, "遠藤健二");
        assertTrue(out.startsWith("0 HEAD\n1 SOUR ResearchZosho\n1 GEDC\n2 VERS 5.5.1\n1 CHAR UTF-8\n1 NOTE This file was written by ResearchZosho."), "the header explains the library's own tags: " + out);
        String kenji = out.substring(out.indexOf("1 NAME 健二 /森田/"), out.indexOf("\n0 ", out.indexOf("1 NAME 健二 /森田/")));
        assertTrue(kenji.startsWith("1 NAME 健二 /森田/\n2 TYPE mukoyoshi\n2 _NAMEKIND mukoyoshi\n2 _NAMEEVENT ADOP\n2 ROMN Kenji Morita\n3 TYPE romaji\n"),
                "the latest name first, with its kind (the library's own short word, where GEDCOM has none and the source gave no words), the event it dates from and its forms: " + kenji);
        assertTrue(kenji.contains("1 NAME 健二 /遠藤/\n2 TYPE birth\n2 _NAMEKIND birth\n2 ROMN Endō Kenji\n3 TYPE romaji\n2 FONE けんじ /えんどう/\n3 TYPE kana\n"), kenji);
        assertTrue(kenji.contains("1 ADOP\n2 DATE 1932\n2 TYPE 婿養子\n2 FAMC @F"), kenji);
        assertTrue(kenji.contains("\n3 ADOP HUSB\n"), kenji);
        assertTrue(kenji.contains("1 _MEMBER @H") && kenji.contains("2 _HOW mukoyoshi") && kenji.contains("2 DATE FROM 1932"), kenji);
        assertTrue(out.contains(" _HOUSE\n1 NAME 森田\n1 NAME 森田家\n1 _SEAT 広島県安芸郡\n"), out);
        assertTrue(out.contains(" _HOUSE\n1 NAME 遠藤\n1 _SEAT 山口県\n1 _BRANCH @H"), out);
        assertTrue(Gedcom.export(store, "Tom Hart").contains("1 NAME Tom /Hart/\n1 NAME John /Hart/\n"), "a name whose kind is not known is written without one");

        LibraryStore back = store(tmp, "back");
        Path file = tmp.resolve("back.ged");
        Files.writeString(file, out, StandardCharsets.UTF_8);
        Gedcom.importFile(back, file);
        Graph was = FamilyPeople.view(store), now = FamilyPeople.view(back);
        String there = now.node(now.nodeIdOf("遠藤健二")).label();
        for (String[] person : new String[][]{{"遠藤健二", there}, {"森田勇", "森田勇"}, {"森田ハル", "森田ハル"}, {"遠藤正一", "遠藤正一"}}) {
            assertEquals(names(was, person[0]), names(now, person[1]), person[0] + "'s names after the round trip, from\n" + out);
            assertEquals(memberships(was, person[0]), memberships(now, person[1]), person[0] + "'s families after the round trip, from\n" + out);
        }
        assertEquals(FamilyDetail.of(claims(store, "遠藤健二", "adopted-by").get(0)).get("kind"), FamilyDetail.of(claims(back, there, "adopted-by").get(0)).get("kind"));
        assertEquals("森田勇", claims(back, there, "adopted-by").get(0).triple().object());
    }

    @Test
    void aNewerCopyThatSaysANameOtherwiseReplacesItsDraft(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, "lib");
        Path file = ged(tmp, "tree.ged", "0 @I1@ INDI\n1 NAME Mary /Hale/\n1 NAME Mary /Ellis/\n2 TYPE aka\n1 BIRT\n2 DATE 1850\n");
        Gedcom.importFile(store, file);
        Finding first = name(store, "Mary Hale", "Mary Ellis");
        // the family corrects the tree: the second name was taken at her marriage in 1875
        Files.writeString(file, "0 HEAD\n1 CHAR UTF-8\n0 @I1@ INDI\n1 NAME Mary /Hale/\n1 NAME Mary /Ellis/\n2 TYPE married\n2 _NAMEDATE FROM 1875\n1 BIRT\n2 DATE 1850\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.Outcome o = Gedcom.importFile(store, file);
        assertEquals(Finding.State.superseded, store.finding(first.id()).state());
        Finding now = name(store, "Mary Hale", "Mary Ellis");
        assertEquals("marriage", FamilyDetail.get(now, "kind"));
        assertEquals(List.of(first.id()), now.supersedes());
        assertEquals(0, o.superseded(), "no event was replaced");
        assertTrue(o.problems().contains("1 name, membership or family's seat from an earlier copy of this file was replaced, because this copy of the file says something else about it. The earlier one is kept, marked as replaced."), o.problems().toString());
    }

    @Test
    void aNameTheBookGaveIsBackedByTheFileAndAKindItDidNotGiveIsAClaimOfItsOwn(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp, "lib");
        String q = "健二は1905年に遠藤家に生まれた。";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(), List.of(), List.of(),
                List.of(new FamilyAccount.NameRead("遠藤健二", "遠藤健二", "遠藤", "健二", List.of(), "birth", "", "", q), new FamilyAccount.NameRead("遠藤健二", "森田健二", "森田", "健二", List.of(), "unknown", "", "", q)), List.of()),
                "file:///family/book.txt", "an aunt");
        Gedcom.importFile(store, ged(tmp, "tree.ged", "0 @I1@ INDI\n1 NAME 健二 /遠藤/\n2 TYPE birth\n1 NAME 健二 /森田/\n2 _NAMEKIND mukoyoshi\n1 BIRT\n2 DATE 1905\n"));
        List<Finding> births = claims(store, "遠藤健二", "has-name").stream().filter(f -> f.triple().object().equals("name: 遠藤健二")).toList();
        assertEquals(1, births.size(), "the same name of the same kind: the book's claim, with the file as a further source");
        assertEquals(2, births.get(0).sources().size());
        List<Finding> later = claims(store, "遠藤健二", "has-name").stream().filter(f -> f.triple().object().equals("name: 森田健二")).toList();
        assertEquals(2, later.size(), "the book did not say how he came by the name and the file does: that is a reading of its own");
        Graph g = FamilyPeople.view(store);
        FamilyNameHistory.Name morita = FamilyNameHistory.of(g).names(g.nodeIdOf("遠藤健二")).stream().filter(n -> n.written().equals("森田健二")).findFirst().orElseThrow();
        assertEquals("mukoyoshi", morita.kind(), "one name, with the kind the file gives");
        assertEquals(2, morita.claims().size());
    }
}
