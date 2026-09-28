package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A person's names and families and a family's page, at the terminal and on the web: the same sentences, and one can drill in and out,
 * from the tree to the person, to a family, to a member, to that member's other family, and back. Invented names only.
 */
class FamilyNamePagesTest {

    private static Profile.Page page(LibraryStore store, Patrons.Patron who, String path, Map<String, String> q) throws Exception {
        return FamilyPages.page(store, who, path, "GET", q, Map.of());
    }

    private static String link(String path, String key, String value) { return "<a href=\"" + path + "?" + key + "=" + FamilyNamePages.enc(value) + "\">"; }

    @Test
    void thePersonPageAtTheTerminalListsTheNamesInTheOrderOfTheLifeAndEachFamilyWithHowTheyCameAndLeft(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNamesFixture.family(tmp);
        String kenji = FamilyNamesFilingTest.run(store, "names", "森田健二");
        assertTrue(kenji.startsWith("森田健二 (born 遠藤)\n\nNames, in the order of the life:"), kenji);
        assertTrue(kenji.indexOf("  遠藤健二: the name at birth, from 1905 to 1932.") < kenji.indexOf("  森田健二: the name he took when he married into the 森田 family as 婿養子, in 1932."), kenji);
        assertTrue(kenji.contains("    also written Morita Kenji"), kenji);
        assertTrue(kenji.contains("\nFamilies:\n  森田 family: married into it as 婿養子 in 1932, coming from 遠藤 family (広島県安芸郡)"), kenji);
        assertTrue(kenji.contains("  researchzosho genealogy life \"森田健二\" shows everything") && kenji.contains("  researchzosho genealogy tree \"森田健二\" draws"), kenji);

        String shoji = FamilyNamesFilingTest.run(store, "names", "森田正二");
        assertTrue(shoji.startsWith("髙橋正二 (born 森田)\nYour library files this person under 森田正二. Use that name in commands."), shoji);
        assertTrue(shoji.contains("  森田 family: born into it in 1912; left it in 1940 on being adopted into another family, going to 髙橋 family. [F-"), shoji);
        assertTrue(shoji.contains("  髙橋 family: adopted into it as its heir in 1940, coming from 森田 family. [F-"), shoji);
        assertTrue(shoji.contains("    researchzosho genealogy family \"髙橋 family\" shows the family they went to."), shoji);
        assertTrue(shoji.contains("  researchzosho genealogy life \"森田正二\""), "commands take the label: " + shoji);

        String family = FamilyNamesFilingTest.run(store, "names", "森田 family");
        assertTrue(family.contains("森田 family is a family, not a person.") && family.contains("researchzosho genealogy family \"森田 family\""), family);
        String nobody = FamilyNamesFilingTest.run(store, "names", "Tom Hale");
        assertTrue(nobody.contains("Nobody in your library is called \"Tom Hale\"."), nobody);
    }

    @Test
    void aWomansMarriedNameIsShownAsWorkedOutAndAnOldOtherNameAsANameWhoseReasonIsNotKnown(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyNameHistoryTest.file(store, "file:///family/notes.txt", List.of(
                        new FamilyAccount.Fact("Mary Ellis", "married-to", "John Ellis", "1875", "Mary Hale married John Ellis in 1875."),
                        new FamilyAccount.Fact("Mary Ellis", "sex", "female", "", "Mary Hale married John Ellis in 1875."),
                        new FamilyAccount.Fact("John Ellis", "sex", "male", "", "Mary Hale married John Ellis in 1875.")),
                List.of(FamilyNameHistoryTest.name("Mary Ellis", "Mary Hale", "Hale", "Mary", "birth", "1850", "Mary Hale was born in 1850.")));
        String mary = FamilyNamesFilingTest.run(store, "names", "Mary Ellis");
        assertTrue(mary.startsWith("Mary Ellis (born Hale)\n"), mary);
        assertTrue(mary.contains("  Mary Hale: the name at birth, from 1850 to 1875."), mary);
        assertTrue(mary.contains("  Mary Ellis: the name taken at marriage, in 1875. (The library worked this out from the marriage. No source says it in words.) [F-"), mary);
        // a library from before: an other name under another family name, which no claim explains
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Ruth Hart", "", List.of("Ruth Hale"))),
                List.of(new FamilyAccount.Fact("Ruth Hart", "born-on", "1880", "", "Ruth Hart was born in 1880.")), List.of()), "file:///family/old.txt", "an aunt");
        String ruth = FamilyNamesFilingTest.run(store, "names", "Ruth Hart");
        assertTrue(ruth.contains("  Ruth Hart: the name your library files this person under.") && ruth.contains("  Ruth Hale: another name your library has for this person; the library does not know yet how it came."), ruth);
    }

    @Test
    void aFamilysPageAtTheTerminalHasItsHeadsInOrderAndItsMembersWithWhereEachCameFromAndWentTo(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNamesFixture.family(tmp);
        String morita = FamilyNamesFilingTest.run(store, "family", "森田 family");
        assertTrue(morita.startsWith("森田 family\nAlso written: 森田家.\n"), morita);
        assertTrue(morita.contains("\nHeads, in order:\n  1. 森田勇, head from 1907. [F-"), morita);
        int isamu = morita.indexOf("  森田勇: came into it by succession as its head in 1907"), shoji = morita.indexOf("  髙橋正二 (born 森田): born into it in 1912; left it in 1940 on being adopted into another family, going to 髙橋 family"),
                kenji = morita.indexOf("  森田健二 (born 遠藤): married into it as 婿養子");
        assertTrue(isamu > 0 && isamu < shoji && shoji < kenji, "each member once, in the order they came in: " + morita);
        assertTrue(morita.contains("    researchzosho genealogy names \"森田正二\" shows this person's names"), "the command takes the label: " + morita);
        assertTrue(morita.contains("    researchzosho genealogy family \"髙橋 family\" shows that family."), "and leads to the family he went to: " + morita);
        assertEquals(morita, FamilyNamesFilingTest.run(store, "family", "森田家"), "the words the source wrote open the same page");

        String all = FamilyNamesFilingTest.run(store, "family");
        assertTrue(all.contains("  森田 family, with 3 members") && all.contains("  遠藤 family (広島県安芸郡), with 1 member") && all.contains("  遠藤 family (山口県大島郡), with 1 member"), all);
        assertTrue(all.contains("researchzosho genealogy family \"森田 family\" shows the heads and members of 森田 family."), all);
        String takahashi = FamilyNamesFilingTest.run(store, "family", "髙橋 family");
        assertTrue(takahashi.contains("No head of this family is written down.") && takahashi.contains("  髙橋正二 (born 森田): adopted into it as its heir in 1940, coming from 森田 family"), takahashi);
    }

    @Test
    void aFamilyNameAloneListsEveryFamilyOfThatNameAndWhoBoreItInWhichYears(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNamesFixture.family(tmp);
        String endo = FamilyNamesFilingTest.run(store, "family", "遠藤");
        assertTrue(endo.startsWith("The 2 families called 遠藤. A family name is not a family"), endo);
        assertTrue(endo.contains("  遠藤 family (広島県安芸郡), with its seat in 広島県安芸郡") && endo.contains("  遠藤 family (山口県大島郡), with its seat in 山口県大島郡"), endo);
        int shoichi = endo.indexOf("  遠藤正一: as 遠藤正一, from their birth in 1875 to their death in 1930."), isamu = endo.indexOf("  遠藤勇: as 遠藤勇, from their birth in 1880."),
                kenji = endo.indexOf("  森田健二 (born 遠藤): as 遠藤健二, from 1905 to 1932.");
        assertTrue(shoichi > 0 && shoichi < isamu && isamu < kenji, "who bore the name, and when, by year: " + endo);

        String morita = FamilyNamesFilingTest.run(store, "family", "森田");
        assertTrue(morita.contains("  森田健二 (born 遠藤): as 森田健二, from 1932."), "he bore 森田 only from 1932: " + morita);
        assertTrue(morita.contains("  髙橋正二 (born 森田): as 森田正二, from 1912 to 1940."), morita);
        assertTrue(morita.indexOf("as 森田正二") < morita.indexOf("as 森田健二, from 1932"), morita);
        assertFalse(FamilyNamesFilingTest.run(store, "family", "髙橋").contains("as 髙橋正二, from 1912"), "a name taken in 1940 is borne from 1940");

        String hale = FamilyNamesFilingTest.run(store, "family", "Hale");
        assertTrue(hale.contains("Your library holds no family called \"Hale\", and nobody in it bore that family name."), hale);
        String person = FamilyNamesFilingTest.run(store, "family", "森田健二");
        assertTrue(person.contains("森田健二 is a person: the command researchzosho genealogy names \"森田健二\" shows the families they belonged to."), person);
    }

    // e-owner-2: a man with one name who entered the 森田 family as 婿養子 in 1932 was listed as bearing 森田 from his birth in 1905
    @Test
    void aPersonWithOneNameWhoEnteredTheFamilyLaterBoreItFromTheEntry(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q = "森田健二は1905年に生まれ、1932年に森田家の婿養子となった。";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                        FamilyNameQuestionsTest.fact("森田健二", "born-on", "1905", "", q),
                        new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", q, Map.of("how", "mukoyoshi", "from", "1932"))),
                List.of(), List.of(), List.of(), List.of(new FamilyAccount.FamilyRead("森田", "森田家", "", q))), "an aunt", f -> List.of("file:///family/book.txt"), f -> List.of());
        String morita = FamilyNamesFilingTest.run(store, "family", "森田");
        assertFalse(morita.contains("from their birth"), "he came into the family in 1932: " + morita);
        assertTrue(morita.contains("  森田健二: as 森田健二, from 1932, when they entered 森田 family."), morita);
    }

    @Test
    void theWebPagesLinkInAndOutFromTheTreeToAPersonToAFamilyToAMemberToTheirOtherFamilyAndBack(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNamesFixture.family(tmp);
        // the tree leads to the person
        assertTrue(FamilyPages.treeBody(store, "森田健二").contains(link("/person", "name", "森田健二")));
        Profile.Page kenji = page(store, Patrons.Patron.PERSON, "/person", Map.of("name", "森田健二"));
        assertEquals("Names and families", kenji.title());
        String body = kenji.body();
        assertTrue(body.contains("<h2>森田健二 (born 遠藤)</h2>"), body);
        // the person leads to each family, the family they came from, each claim, and back to the tree
        assertTrue(body.contains(link("/family", "name", "森田 family") + "森田 family</a>: married into it as 婿養子"), body);
        assertTrue(body.contains(link("/family", "name", "遠藤 family (広島県安芸郡)") + "遠藤 family (広島県安芸郡)</a> (the library worked this out"), body);
        assertTrue(body.contains("<a href=\"/entry/F-") && body.contains(link("/tree", "focus", "森田健二") + "The family tree around 森田健二 (born 遠藤)</a>"), body);
        assertFalse(body.contains("researchzosho genealogy"), "the web page has links, not commands: " + body);
        // the family leads to its members, by their label
        String morita = page(store, Patrons.Patron.PERSON, "/family", Map.of("name", "森田 family")).body();
        assertTrue(morita.contains("<h2>森田 family</h2>") && morita.contains(link("/person", "name", "森田正二") + "髙橋正二 (born 森田)</a>: born into it in 1912"), morita);
        assertTrue(morita.contains(link("/person", "name", "森田勇") + "森田勇</a>, head from 1907."), morita);
        // the member leads to their other family, and it back to the first
        assertTrue(morita.contains(link("/family", "name", "髙橋 family") + "髙橋 family</a>"), morita);
        String takahashi = page(store, Patrons.Patron.PERSON, "/family", Map.of("name", "髙橋 family")).body();
        assertTrue(takahashi.contains(link("/person", "name", "森田正二")) && takahashi.contains("coming from " + link("/family", "name", "森田 family")), takahashi);
        // a family name alone, and every family
        String endo = page(store, Patrons.Patron.PERSON, "/family", Map.of("surname", "遠藤")).body();
        assertTrue(endo.contains("The 2 families called 遠藤") && endo.contains(link("/person", "name", "森田健二") + "森田健二 (born 遠藤)</a>: as 遠藤健二, from 1905 to 1932."), endo);
        String families = page(store, Patrons.Patron.PERSON, "/family", Map.of()).body();
        assertTrue(families.contains(link("/family", "name", "遠藤 family (山口県大島郡)")), families);
        String nobody = page(store, Patrons.Patron.PERSON, "/person", Map.of("name", "Tom Hale")).body();
        assertTrue(nobody.contains("Nobody of that name is in your library.") && nobody.contains("<a href=\"/tree\">"), nobody);
    }

    /**
     * f-owner-membership-contradiction-after-answer: a source says 髙橋勝 was a member of the 髙橋 family from 1990 and not how; the family
     * answers that he was adopted into it as its heir. Both pages then say it once: adopted into it as its heir in 1990, with both claims,
     * and no longer that how he came in is not written down.
     */
    @Test
    void aMembershipWithoutHowIsShownAsTheOneTheFamilysAnswerSaysHowOf(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String q = "In 1990 Masaru became Takahashi Masaru (髙橋勝) and carried on the Takahashi line.";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("髙橋勝", FamilyHouses.MEMBER, "髙橋家", "1990", q)), List.of(), List.of(),
                        List.of(FamilyNameQuestionsTest.name("髙橋勝", "森田勝", "森田", "勝", "birth", "1962", "Masaru (森田勝) was born in 1962."), FamilyNameQuestionsTest.name("髙橋勝", "髙橋勝", "髙橋", "勝", "", "1990", q)),
                        List.of(new FamilyAccount.FamilyRead("髙橋", "髙橋家", "Nagano", q))),
                "an aunt", f -> List.of("file:///family/book.txt"), f -> List.of());
        String masaru = FamilyPeople.view(store).nodeIdOf("髙橋勝");
        FamilyNameQuestions.Question how = FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("name-change-how") && x.people().contains(masaru)).findFirst().orElseThrow();
        FamilyNameQuestions.answer(store, how.code(), "heir", "", "Ann");
        String names = FamilyNamesFilingTest.run(store, "names", "髙橋勝");
        assertFalse(names.contains("how they came in is not written down"), names);
        assertEquals(1, names.split("髙橋 family \\(Nagano\\): ", -1).length - 1, "the family once: " + names);
        assertTrue(names.contains("髙橋 family (Nagano): adopted into it as its heir in 1990."), names);
        String family = FamilyNamesFilingTest.run(store, "family", "髙橋 family (Nagano)");
        assertFalse(family.contains("how they came in is not written down"), family);
        String line = family.lines().filter(l -> l.contains("髙橋勝") && l.contains("adopted into it")).findFirst().orElseThrow(() -> new AssertionError(family));
        assertEquals(2, line.split("F-", -1).length - 1, "both claims stay on the line: " + line);
    }

    @Test
    void aReaderMayOpenThePagesAndNobodyMayPostToThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNamesFixture.family(tmp);
        Patrons.set(store, "did:key:reader", "a cousin", Patrons.Level.read);
        Patrons.set(store, "did:key:gone", "a former partner", Patrons.Level.deny);
        Patrons.Patron cousin = new Patrons.Patron("did:key:reader", "a cousin", "web"), gone = new Patrons.Patron("did:key:gone", "a former partner", "web");
        assertTrue(page(store, cousin, "/person", Map.of("name", "森田健二")).body().contains("森田健二 (born 遠藤)"));
        assertTrue(page(store, cousin, "/family", Map.of("name", "森田 family")).body().contains("森田勇"));
        for (String path : new String[]{"/person", "/family"}) {
            ProtocolError refused = assertThrows(ProtocolError.class, () -> page(store, gone, path, Map.of("name", "森田健二")));
            assertTrue(refused.getMessage().contains("may not read this library"), refused.getMessage());
            ProtocolError posted = assertThrows(ProtocolError.class, () -> FamilyPages.page(store, Patrons.Patron.PERSON, path, "POST", Map.of(), Map.of("name", "森田健二")));
            assertTrue(posted.getMessage().contains("It has nothing to send."), posted.getMessage());
        }
        assertTrue(FamilyPages.menu().stream().anyMatch(l -> l.href().equals("/family") && !l.writersOnly()), "Families is in the menu, for readers too");
    }
}
