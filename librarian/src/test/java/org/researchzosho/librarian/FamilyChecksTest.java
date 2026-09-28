package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
/** What cannot be true in a family is found by arithmetic, and names are compared only after the dates have had their say. */
class FamilyChecksTest {

    private static FamilyAccount.Fact fact(String s, String r, String o, String date) { return new FamilyAccount.Fact(s, r, o, date, "q"); }

    private static List<FamilyChecks.Problem> check(Path tmp, FamilyAccount.Fact... facts) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(facts), List.of()), "file:///notes.txt", "an aunt");
        return FamilyChecks.check(store);
    }

    @Test
    void aConsistentFamilyHasNothingToLookAt(@TempDir Path tmp) throws Exception {
        List<FamilyChecks.Problem> p = check(tmp, fact("髙橋源三郎", "born-on", "明治5年", ""), fact("髙橋源三郎", "died-in", "広島", "昭和20年"),
                fact("髙橋正一", "child-of", "髙橋源三郎", ""), fact("髙橋正一", "born-on", "明治41年", ""), fact("髙橋正二", "child-of", "髙橋源三郎", ""), fact("髙橋正二", "born-on", "明治43年", ""));
        assertTrue(p.isEmpty(), "正一 and 正二 are brothers, not one man misspelt: " + FamilyChecks.render(p));
        assertEquals("Nothing in the family's claims contradicts itself.", FamilyChecks.render(p));
    }

    @Test
    void datesThatCannotAllBeTrue(@TempDir Path tmp) throws Exception {
        String all = FamilyChecks.render(check(tmp,
                fact("John Ellis", "born-on", "1850", ""), fact("John Ellis", "died-on", "1840", ""),
                fact("Mary Ellis", "born-on", "1855", ""), fact("Mary Ellis", "child-of", "John Ellis", ""),
                fact("Tom Ellis", "born-on", "1845", ""), fact("Tom Ellis", "child-of", "John Ellis", ""),
                fact("Old Ann", "born-on", "1700", ""), fact("Old Ann", "died-on", "1830", ""),
                fact("Mary Ellis", "married-to", "Sam Hart", "1860")));
        assertTrue(all.contains("[impossible] John Ellis died in 1840, before the birth in 1850."), all);
        assertTrue(all.contains("[set aside] \"Mary Ellis is a child of John Ellis\" is set aside in the family's view: the years say this cannot be, as a parent is at least 12 years older than a child (John Ellis was born in 1850, Mary Ellis in 1855)."), all);
        assertTrue(all.contains("[set aside] \"Tom Ellis is a child of John Ellis\" is set aside in the family's view: the years say this is the wrong way round (John Ellis was born in 1850, Tom Ellis in 1845)."), all);
        assertTrue(all.contains("[unlikely] Old Ann would have lived 130 years"), all);
        assertTrue(all.contains("Mary Ellis (born 1855) was 5 at the marriage in 1860."), all);
        assertTrue(all.contains("(F-"), "each line names the claims to look at: " + all);
    }

    @Test
    void aThirdParentAndAnAncestorOfOneself(@TempDir Path tmp) throws Exception {
        String all = FamilyChecks.render(check(tmp, fact("Kiyo", "child-of", "A", ""), fact("Kiyo", "child-of", "B", ""), fact("C", "parent-of", "Kiyo", ""),
                fact("Kiyo", "adopted-by", "D", ""), fact("A", "child-of", "Kiyo", "")));
        assertTrue(all.contains("Kiyo has 3 birth parents: A, B, C."), "an adoptive parent is not counted: " + all);
        assertTrue(all.contains("comes out as their own ancestor"), all);
    }

    @Test
    void twoPeopleOfOneNameAreHeldApartAndTheChecksGoQuiet(@TempDir Path tmp) throws Exception {
        // a father and a son, both John Ellis, arrive as one man with two birth years who married at minus nineteen
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("John Ellis", "born-on", "1810", ""), fact("John Ellis", "married-to", "Ann Hart", "1832"),
                fact("John Ellis", "born-on", "1851", ""), fact("John Ellis", "died-in", "Dunedin", "1921")), List.of()), "file:///notes.txt", "an aunt");
        assertTrue(FamilyChecks.render(FamilyChecks.check(store)).contains("John Ellis has two birth years, 1810 and 1851"), FamilyChecks.render(FamilyChecks.check(store)));
        List<Finding> all = store.scanFindings().findings();
        String born1851 = all.stream().filter(f -> f.triple().object().equals("1851")).findFirst().orElseThrow().id();
        String died = all.stream().filter(f -> f.triple().predicate().equals("died-in")).findFirst().orElseThrow().id();
        FamilySplit.Outcome o = FamilySplit.split(store, "John Ellis", "John Ellis (born 1851)", List.of(born1851, died.substring(0, 6), "F-9999"));
        assertEquals(2, o.moved(), "a claim is found by the front of its id too");
        assertEquals(List.of("F-9999"), o.notFound());
        assertThrows(IllegalArgumentException.class, () -> FamilySplit.split(store, "John Ellis", "john ellis", List.of(died)));
        Finding moved = store.scanFindings().findings().stream().filter(f -> f.id().equals(died)).findFirst().orElseThrow();
        assertEquals("John Ellis (born 1851)", moved.triple().subject());
        assertTrue(moved.body().startsWith("John Ellis (born 1851) died in Dunedin") && moved.notes().get(0).text().contains("two people of one name"), moved.body());
        String after = FamilyChecks.render(FamilyChecks.check(store));
        assertFalse(after.contains("two birth years"), after);
        assertTrue(after.contains("[same-name] John Ellis (born 1810) and John Ellis (born 1851) share a name and are two people"), "and the run is warned that a record naming John Ellis needs a second identifier: " + after);
        assertEquals(List.of("genealogy"), Fields.recognised(store, "What did John Ellis do in Dunedin?"), "asked about without the part in brackets");
    }

    @Test
    void theChatsFamilyCommandRunsTheSameVerbs(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("John Ellis", "born-on", "1850", ""), fact("John Ellis", "died-on", "1840", "")), List.of()), "file:///n.txt", "an aunt");
        PrintStream real = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            LibrarianCli.familyCommand(store, "");
            LibrarianCli.familyCommand(store, "check");
            LibrarianCli.familyCommand(store, "dance");
        } finally { System.setOut(real); }
        String said = out.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("/family tell <what you know") && said.contains("THINGS THAT CANNOT BE TRUE (1)") && said.contains("  1. John Ellis died in 1840, before the birth in 1850."), said);
        assertEquals(2, said.split("/family tell <what you know", -1).length - 1, "an unknown verb shows the help, as no verb does");
    }

    @Test
    void oneNameTwoPeopleAndTwoSpellingsOfOnePerson(@TempDir Path tmp) throws Exception {
        String all = FamilyChecks.render(check(tmp, fact("Genzaburo Takahashi", "born-on", "1872", ""), fact("Genzaburo Takahasi", "born-on", "1872", ""),
                fact("William Morgan", "born-on", "1810", ""), fact("William Morgann", "born-on", "1851", "")));
        assertTrue(all.contains("[same-person?] Genzaburo Takahashi and Genzaburo Takahasi differ by one character and no date sets them apart"), all);
        assertTrue(all.contains("graph merge \"Genzaburo Takahasi\" \"Genzaburo Takahashi\""), all);
        assertTrue(all.contains("[same-name] William Morgan (born 1810) and William Morgann (born 1851) share a name and are two people"), all);
    }

    @Test
    void saidToAPersonWhoHasNeverUsedTheProgram(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        assertTrue(FamilyChecks.forPerson(store, List.of()).contains("It found nothing wrong") && FamilyChecks.forPerson(store, List.of()).contains("It did not change anything."));
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("John Ellis", "born-on", "1850", ""), fact("John Ellis", "died-on", "1840", "")), List.of()), "file:///notes.txt", "an aunt");
        String said = FamilyChecks.forPerson(store, FamilyChecks.check(store));
        assertTrue(said.contains("It found 1 thing for you to look at.") && said.contains("THINGS THAT CANNOT BE TRUE (1)") && said.contains("  1. John Ellis died in 1840, before the birth in 1850."), said);
        assertTrue(said.contains("       F-0001: John Ellis was born in 1850.") && said.contains("(from notes.txt)"), "each fact is written out under the finding, so no second command is needed to read it: " + said);
        assertTrue(said.matches("(?s).*researchzosho dispute F-000[12] .*") && said.contains("researchzosho genealogy split"), "the example disputes a fact from the list: " + said);
        assertTrue(said.contains("researchzosho genealogy source notes.txt") && said.contains("researchzosho genealogy check accept " + FamilyChecks.check(store).get(0).id()), said);
    }

    private static void readAs(LibraryStore store, String file, FamilyAccount.Fact... facts) throws Exception {
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(facts), List.of()), file, "an aunt");
    }

    private static FamilyAccount.Fact said(String s, String r, String o, String date) { return new FamilyAccount.Fact(s, r, o, date, s + " " + r + " " + o); }

    @Test
    void aDisputedFactNoLongerCountsInTheCheckAndTheExampleIsAFactStillWaiting(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        readAs(store, "file:///family/aunt.txt", said("Tom Hale", "born-on", "1850", ""), said("Tom Ellis", "born-on", "1900", ""), said("Tom Ellis", "child-of", "John Ellis", ""), said("Tom Ellis", "child-of", "Mary Ellis", ""));
        readAs(store, "file:///family/tree.txt", said("Tom Hale", "born-on", "1860", ""), said("Genzo Hale", "born-on", "1940", ""), said("Tom Ellis", "child-of", "Genzo Hale", ""));
        FamilyDecisions.Clash clash = FamilyDecisions.clashes(store).stream().filter(c -> c.person().equals("Tom Hale")).findFirst().orElseThrow();
        Finding kept = clash.one().triple().object().equals("1850") ? clash.one() : clash.other();
        FamilyDecisions.keep(store, clash, kept, "person");
        Finding genzo = store.scanFindings().findings().stream().filter(f -> f.triple().object().equals("Genzo Hale")).findFirst().orElseThrow();
        new Council(store).dispute(genzo.id(), "not his father");
        List<FamilyChecks.Problem> problems = FamilyChecks.check(store);
        assertTrue(problems.stream().noneMatch(p -> p.text().contains("two birth years") || p.text().contains("3 birth parents") || p.text().contains("Genzo")), problems.toString());
        // a death before the kept birth: the example is the death, which waits for the family's word, never the birth they kept
        readAs(store, "file:///family/letters.txt", said("Tom Hale", "died-on", "1840", ""));
        String death = store.scanFindings().findings().stream().filter(f -> f.triple().predicate().equals("died-on")).findFirst().orElseThrow().id().replaceFirst("^(F-\\d+).*", "$1");
        String said = FamilyChecks.forPerson(store, FamilyChecks.check(store));
        assertFalse(said.contains("dispute " + kept.id().replaceFirst("^(F-\\d+).*", "$1") + " "), "never the fact the family just kept: " + said);
        assertTrue(said.contains("researchzosho dispute " + death + " "), said);
    }

    @Test
    void theExamplesAreRealCodesAndRealAddresses(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        // a name pair with nothing to compare, and a dozen unrelated facts: no example may name a fact the list does not show
        readAs(store, "file:///family/notes.txt", said("Shoichi Morita", "married-to", "Kimie Hale", ""), said("Shoichi Morrita", "child-of", "Kimie Hale", ""));
        List<String> given = List.of("Tom", "Ann", "Mary", "Ruth", "John", "Emma", "Kimie", "Taro", "Hisa", "Ichiro", "Genzo", "Isamu");
        for (int i = 0; i < 12; i++) readAs(store, "file:///family/list" + i + ".txt", said(given.get(i) + " " + List.of("Ellis", "Hart", "Hale", "Endo").get(i % 4), "born-on", String.valueOf(1800 + 15 * i), ""));
        List<FamilyChecks.Problem> problems = FamilyChecks.check(store);
        assertTrue(problems.stream().allMatch(p -> p.findings().isEmpty()), "only the name pair, with no fact to show: " + problems);
        String said = FamilyChecks.forPerson(store, problems);
        assertFalse(said.contains("dispute F-"), "no code that the list does not show: " + said);
        assertFalse(said.contains("the fact itself is written out underneath"), said);

        // a web page as the source: the example gives its whole address, which genealogy source finds
        LibraryStore web = new LibraryStore(tmp.resolve("web")); web.init();
        readAs(web, "https://www.geni.com/people/John-Ellis/6000000012345", said("John Ellis", "born-on", "1850", ""), said("John Ellis", "died-on", "1840", ""));
        String out = FamilyChecks.forPerson(web, FamilyChecks.check(web));
        assertTrue(out.contains("researchzosho genealogy source https://www.geni.com/people/John-Ellis/6000000012345"), out);
        assertFalse(Evidence.restingOn(web, "https://www.geni.com/people/John-Ellis/6000000012345", "").isEmpty());
    }
}
