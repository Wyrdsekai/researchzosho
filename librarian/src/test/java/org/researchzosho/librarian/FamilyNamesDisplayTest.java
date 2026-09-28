package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the names and family pages, the related sentence and the research questions print about names: a suggested command gives back the
 * very label when the shell reads it, the words a source wrote for a family open its page only when no other family has that name, prose
 * names a person by the heading while checks keep the entry's name with its bracket, a two-character name with both its parts is searched
 * for its period, and a source's words for how a name came are shown only when they say how. Invented names only.
 */
class FamilyNamesDisplayTest {

    /** The words a POSIX shell makes of a command line, as the program that runs gets them. */
    static List<String> shellWords(String line) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", "eval \"set -- $1\"; for a in \"$@\"; do printf '%s\\n' \"$a\"; done", "sh", line).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "the shell could not read the command line " + line + ": " + out);
        return new ArrayList<>(out.lines().toList());
    }

    /** The command a line of output suggests, from "researchzosho genealogy" up to the words that say what it does. */
    static String command(String output, String start, String does) {
        int at = output.indexOf(start);
        assertTrue(at >= 0, "no command starting " + start + " in: " + output);
        int end = output.indexOf(does, at);
        assertTrue(end > at, "the command is not followed by " + does + ": " + output);
        return output.substring(at, end).strip();
    }

    /** Runs a suggested command as the shell would give it to the program. */
    static String runSuggested(LibraryStore store, String commandLine) throws Exception {
        List<String> words = shellWords(commandLine);
        assertEquals(List.of("researchzosho", "genealogy"), words.subList(0, 2), commandLine);
        return FamilyNamesFilingTest.run(store, words.subList(2, words.size()).toArray(String[]::new));
    }

    // ── display-1 ──

    @Test
    void aCommandThePagesSuggestForALabelWithADoubleQuoteGivesBackThatLabelWhenTheShellReadsIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        Path ged = tmp.resolve("tree.ged");
        Files.writeString(ged, "0 HEAD\n1 CHAR UTF-8\n0 @I1@ INDI\n1 NAME Mary \"Ruth\" /Ellis/\n1 NAME Mary \"Ruth\" /Hale/\n2 TYPE birth\n1 SEX F\n1 BIRT\n2 DATE 1850\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.importFile(store, ged);
        String label = "Mary \"Ruth\" Ellis";
        assertNotNull(FamilyPeople.view(store).node(FamilyPeople.view(store).nodeIdOf(label)), "the tree file's label keeps the quotes");
        String page = FamilyNamesFilingTest.run(store, "names", label);
        for (String[] c : new String[][]{{"researchzosho genealogy life ", " shows everything"}, {"researchzosho genealogy tree ", " draws the family"}}) {
            String line = command(page, c[0], c[1]);
            assertEquals(label, shellWords(line).get(3), "the shell gives back the label from " + line);
        }
        String life = runSuggested(store, command(page, "researchzosho genealogy life ", " shows everything"));
        assertTrue(life.contains("1850") && life.contains("Ruth"), "the suggested life command opens her life: " + life);

        // a family whose seat has double quotes in it, and a label with the other characters a shell reads inside double quotes
        String q = "The Hale family of York \"Old\" Town kept the farm.";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Hale", "member-of", "the Hale family", "", q)), List.of(), List.of(), List.of(),
                List.of(new FamilyAccount.FamilyRead("Hale", "the Hale family", "York \"Old\" Town", q))), "an aunt", f -> List.of("file:///family/farm.txt"), f -> List.of());
        String families = FamilyNamesFilingTest.run(store, "family");
        String open = runSuggested(store, command(families, "researchzosho genealogy family ", " shows the heads and members"));
        assertTrue(open.startsWith("Hale family (York \"Old\" Town)\n"), "the suggested command opens that family's page, not the list of Hale families: " + open);
        String member = runSuggested(store, command(open, "researchzosho genealogy names ", " shows this person's names"));
        assertTrue(member.startsWith("Tom Hale\n"), member);
        for (String odd : List.of("Ann $HOME Hart", "Ann `Hart`", "Ann \\Hart", "Ann Hart!", "Ann 'Ruth' Hart", "Ann \"Ruth\" Lee's"))
            assertEquals(odd, shellWords("researchzosho genealogy names " + FamilyNamePages.shellQuoted(odd)).get(3), "the shell gives back " + odd);
        assertEquals("\"森田健二\"", FamilyNamePages.shellQuoted("森田健二"), "a label with nothing a shell reads in it keeps its double quotes");
    }

    // ── display-2 ──

    @Test
    void theWordsASourceWroteForAFamilyOpenItsPageOnlyWhenNoOtherFamilyHasThatName(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNamesFixture.family(tmp);
        String endo = FamilyNamesFilingTest.run(store, "family", "遠藤家");
        assertTrue(endo.startsWith("The 2 families called 遠藤."), "遠藤家 is written for both 遠藤 families, so both are listed: " + endo);
        assertFalse(endo.contains("Heads, in order:"), "and neither page is opened as if it were the only one: " + endo);
        assertTrue(FamilyNamesFilingTest.run(store, "family", "遠藤 family (山口県大島郡)").startsWith("遠藤 family (山口県大島郡)\n"), "a family's own label opens its page");
        assertTrue(FamilyNamesFilingTest.run(store, "family", "森田家").startsWith("森田 family\n"), "a family word of the one family of its name opens its page");
        String web = FamilyPages.page(store, Patrons.Patron.PERSON, "/family", "GET", Map.of("name", "遠藤家"), Map.of()).body();
        assertTrue(web.contains("The 2 families called 遠藤") && !web.contains("<h2>遠藤 family (広島県安芸郡)</h2>"), "the web page lists both as well: " + web);
        assertFalse(web.contains("holds no family called"), web);
    }

    /**
     * A branch bears its family's name but is a family of its own, which its words tell apart: 森田家 opens the page of the 森田 family
     * beside its branch 森田分家, and 森田分家 opens the branch's.
     */
    @Test
    void theWordsForAFamilyOpenItsPageBesideABranchOfItsName(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q = "森田分家は森田本家の分家である。森田正一は森田分家の当主であった。";
        FamilyHousesTest.file(store, "file:///family/book.txt", List.of(new FamilyAccount.Fact("森田分家", "branch-of", "森田本家", "", q),
                FamilyHousesTest.member("森田正一", "森田分家", "", q, Map.of("role", "head"))), List.of());
        FamilyHousesTest.file(store, "file:///family/letter.txt", List.of(FamilyHousesTest.member("森田勇", "森田家", "", "森田勇は森田家の人である。", Map.of())), List.of());
        String main = FamilyHousesTest.page(store, "森田家");
        assertTrue(main.startsWith("森田 family\n"), "森田家 is the family, not its branch: " + main);
        String branch = FamilyHousesTest.page(store, "森田分家");
        assertTrue(branch.startsWith("森田 branch family\n"), branch);
        assertTrue(FamilyHousesTest.page(store, "森田").contains("森田 branch family"), "the family name alone lists both");
    }

    /**
     * A name worked out from entering a family says so, not that it was worked out from a marriage, and a name whose kind a source gives
     * and whose year is worked out does not say that no source gives it in words.
     */
    @Test
    void aNameWorkedOutFromTheEntryIntoAFamilySaysWhatItWasWorkedOutFrom(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String q = "In 1932 he married Haru (森田ハル), the only daughter of Morita Isamu (森田勇), the head of the Morita family, and entered the family as mukoyōshi (婿養子) of Isamu.";
        FamilyNameHistoryTest.fileWith(store, "file:///family/shop.txt", List.of(
                        new FamilyAccount.Fact("森田健二", "adopted-by", "森田勇", "1932", q, Map.of("kind", "mukoyoshi")),
                        new FamilyAccount.Fact("森田健二", "married-to", "森田ハル", "1932", q),
                        new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", q, Map.of("how", "mukoyoshi")),
                        new FamilyAccount.Fact("森田勇", "member-of", "森田家", "", q, Map.of("role", "head"))),
                List.of(FamilyNameHistoryTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "Kenji was born in 1905 as Endō Kenji (遠藤健二).")),
                List.of(new FamilyAccount.FamilyRead("森田", "the Morita family", "", q)));
        String page = FamilyNamesFilingTest.run(store, "names", "森田健二");
        assertTrue(page.contains("(The library worked this out from the entry into the family."), page);
        assertFalse(page.contains("worked this out from the marriage"), page);
        Graph g = FamilyPeople.view(store);
        String facts = String.join("\n", FamilyQuestions.nameFacts(FamilyQuestions.periods(g, g.nodeIdOf("森田健二"))));
        assertTrue(facts.contains("worked out from entering the family") && !facts.contains("from the marriage"), facts);
    }

    /** The search for the record of a change uses the family's own words for itself, "the Hales" too, where the family is the Hale family. */
    @Test
    void theSearchForAChangeUsesTheWordsTheSourcesWroteForTheFamily(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q = "Tom Ellis, born in 1850, was adopted by the Hales in 1860 and was Tom Hale from then on.";
        FamilyNameHistoryTest.fileWith(store, "file:///family/letter.txt", List.of(new FamilyAccount.Fact("Tom Hale", "member-of", "the Hales", "1860", q, Map.of("how", "adoption")),
                        new FamilyAccount.Fact("Tom Hale", "born-on", "1850", "", q)),
                List.of(FamilyNameHistoryTest.name("Tom Hale", "Tom Ellis", "Ellis", "Tom", "birth", "1850", q), FamilyNameHistoryTest.name("Tom Hale", "Tom Hale", "Hale", "Tom", "adoptive", "1860", q)),
                List.of(new FamilyAccount.FamilyRead("Hale", "the Hales", "", q)));
        Graph g = FamilyPeople.view(store);
        String tom = g.nodeIdOf("Tom Hale");
        String family = FamilyHouses.families(g, tom).get(0).family();
        assertTrue(FamilyHouses.aliasesOf(g, family).contains("the Hales"), FamilyHouses.aliasesOf(g, family).toString());
        List<FamilyQuestions.Period> periods = FamilyQuestions.periods(g, tom);
        String together = FamilyQuestions.togetherText(g, tom, periods.get(0), periods.get(1));
        assertTrue(together.contains("the Hales"), together);
    }

    /** A tree file written from the library types a name by the source's words only when they say how it came; else by its kind. */
    @Test
    void theTreeFileTypesANameByTheSourcesWordsOnlyWhenTheySayHowItCame(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q = "Isamu's son Shōji (森田正二) was adopted into the Takahashi family in 1940 and was 髙橋正二 from then on. Kenji entered the Morita family as 婿養子 in 1932.";
        FamilyNameHistoryTest.file(store, "file:///family/shop.txt", List.of(new FamilyAccount.Fact("髙橋正二", "born-on", "1910", "", q), new FamilyAccount.Fact("森田健二", "born-on", "1905", "", q)),
                List.of(new FamilyAccount.NameRead("髙橋正二", "髙橋正二", "髙橋", "正二", List.of(), "adoptive", "Isamu's son", "1940", q),
                        new FamilyAccount.NameRead("森田健二", "森田健二", "森田", "健二", List.of(), "mukoyoshi", "婿養子", "1932", q)));
        String shoji = Gedcom.export(store, "髙橋正二");
        assertTrue(shoji.contains("1 NAME 正二 /髙橋/\n2 TYPE adoptive\n"), shoji);
        assertFalse(shoji.contains("Isamu's son"), shoji);
        assertTrue(Gedcom.export(store, "森田健二").contains("2 TYPE 婿養子\n"), "words that say how it came are the type");
    }

    // ── display-4 ──

    /**
     * The person page sends a reader to no page they cannot open: a reader is told who answers the questions, and a person who may write
     * the library is linked to the first of that person's questions on Who is who.
     */
    @Test
    void aReaderIsNotSentToThePageThatTakesTheFamilysAnswers(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyNameHistoryTest.file(store, "file:///family/notes.txt", List.of(new FamilyAccount.Fact("Tom Hart", "sex", "male", "", "Tom Hart was born Tom Ellis in 1850.")),
                List.of(FamilyNameHistoryTest.name("Tom Hart", "Tom Ellis", "Ellis", "Tom", "birth", "1850", "Tom Hart was born Tom Ellis in 1850.")));
        List<FamilyNameQuestions.Question> qs = FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals("name-change-how")).toList();
        assertFalse(qs.isEmpty(), "the fixture has a question about the name: " + FamilyNameQuestions.open(store));
        Patrons.setDefault(store, Patrons.Level.read);
        Patrons.set(store, "did:key:cousin", "a cousin", Patrons.Level.read);
        Patrons.Patron cousin = new Patrons.Patron("did:key:cousin", "a cousin", "web");
        String read = FamilyNamePages.personPage(store, cousin, "Tom Hart");
        assertTrue(read.contains("Questions for your family"), read);
        assertFalse(read.contains("href=\"/who"), "no link to a page the reader cannot open: " + read);
        assertTrue(read.contains("answer these on the page Who is who"), read);
        String owner = FamilyNamePages.personPage(store, Patrons.Patron.PERSON, "Tom Hart");
        assertTrue(owner.contains("href=\"/who?show=names-" + qs.get(0).code() + "\""), "the link opens the person's first question about names: " + owner);
    }

    // ── display-6 ──

    @Test
    void theBearerListNamesThePersonInItsProseByTheHeadingAndItsCommandByTheLabel(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNamesFixture.family(tmp);
        String takahashi = FamilyNamesFilingTest.run(store, "family", "髙橋");
        assertTrue(takahashi.contains("The command researchzosho genealogy names \"森田正二\" shows all the names and families of 髙橋正二 (born 森田);"), takahashi);
    }

    // ── boundary-9 ──

    @Test
    void theCheckKeepsTheEntrysNameWithItsBracketInsideTheQuoteAndProseKeepsTheBracketBesideTheHeading(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String q = "Ann Hale, born 1941, married Tom Hart in 1960. Her daughter Ann Hale kept her name.";
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Ann Hale (born 1941)", "", List.of("Ann Hart"), "", "")), List.of(
                        new FamilyAccount.Fact("Ann Hale (born 1941)", "born-on", "1941", "", q),
                        new FamilyAccount.Fact("Ann Hale (born 1941)", "sex", "female", "", q),
                        new FamilyAccount.Fact("Ann Hale (born 1941)", "married-to", "Tom Hart", "1960", q),
                        new FamilyAccount.Fact("Tom Hart", "sex", "male", "", q),
                        new FamilyAccount.Fact("Ann Hale", "child-of", "Ann Hale (born 1941)", "", q)), List.of()),
                "file:///family/old.txt", "an aunt");
        Graph g = FamilyPeople.view(store);
        String mother = g.nodeIdOf("Ann Hale (born 1941)");
        assertEquals("Ann Hart", FamilyNameHistory.of(g).heading(mother), "the heading is her married name, worked out from the marriage");
        String checks = String.join("\n", FamilyChecks.check(store).stream().map(FamilyChecks.Problem::text).toList());
        assertTrue(checks.contains("share a name and are two people: \"") && checks.contains("Ann Hale (born 1941)\""), "the quoted claim names the entry as the library files it: " + checks);
        assertFalse(checks.contains("Ann Hart\""), "never a name no claim is written under, inside quotation marks: " + checks);
        String related = FamilyKin.said(g, mother, g.nodeIdOf("Tom Hart"));
        assertTrue(related.startsWith("Ann Hart (born 1941) was married to Tom Hart (F-"), "prose names her by the heading, with the bracket that tells her from her daughter: " + related);
    }

    // ── display-3 ──

    @Test
    void aTwoCharacterNameWithBothItsPartsIsSearchedForItsPeriod(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String born = "勝は1905年に勇家に生まれた。", adopted = "勝は1930年に健家の養子となり、健勝となった。";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                        new FamilyAccount.Fact("健勝", "born-on", "1905", "", born),
                        new FamilyAccount.Fact("健勝", "died-on", "1970", "", "健勝は1970年に亡くなった。")),
                List.of(), List.of(),
                List.of(FamilyNameHistoryTest.name("健勝", "勇勝", "勇", "勝", "birth", "1905", born),
                        FamilyNameHistoryTest.name("健勝", "健勝", "健", "勝", "adoptive", "1930", adopted)),
                List.of()), "an aunt", f -> List.of("file:///family/book.txt"), f -> List.of());
        Graph g = FamilyPeople.view(store);
        List<FamilyQuestions.Period> periods = FamilyQuestions.periods(g, g.nodeIdOf("健勝"));
        assertEquals(List.of("勇勝", "健勝"), periods.stream().map(FamilyQuestions.Period::shown).toList(), "both names, each for its period");
        FamilyQuestions.Ask a = FamilyQuestionsPeriodsTest.ask(store, "健勝");
        assertTrue(a.question().contains("named 勇勝 at birth, until 1930"), a.question());
        assertTrue(a.questions().stream().anyMatch(x -> x.startsWith("Search the records of 健勝 under the name carried at the time of each: before 1930 under 勇勝")), a.questions().toString());
        assertTrue(FamilyQuestions.searchForms(FamilyNameHistory.of(g).names(g.nodeIdOf("健勝")).get(0), "han").contains("勇勝"));
        // a name whose parts are not known is still no search of its own when it is one or two characters: it may be a family name alone
        FamilyNameHistory.Name loose = new FamilyNameHistory.Name("勇勝", "", "", List.of(), "unknown", "", null, false, null, "", List.of(), Evidence.clue, false, true, false);
        assertTrue(FamilyQuestions.searchForms(loose, "han").isEmpty());
    }

    // ── R10 ──

    @Test
    void theSourcesWordsForHowANameCameAreShownOnlyWhenTheySayHow(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        String muko = "In 1932 he entered the Morita family as mukoyōshi (婿養子) of Isamu, and was Morita Kenji from then on.";
        String son = "Isamu's son Shōji (森田正二) was born in 1912.";
        String became = "In 1990 Masaru became Takahashi Masaru (髙橋勝).";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(), List.of(), List.of(), List.of(
                        new FamilyAccount.NameRead("森田健二", "森田健二", "森田", "健二", List.of("Morita Kenji"), "mukoyoshi", "mukoyōshi (婿養子)", "1932", muko),
                        new FamilyAccount.NameRead("森田正二", "森田正二", "森田", "正二", List.of(), "birth", "Isamu's son", "1912", son),
                        new FamilyAccount.NameRead("森田勝", "髙橋勝", "髙橋", "勝", List.of(), "unknown", "became", "1990", became)),
                List.of()), "an aunt", f -> List.of("file:///family/book.txt"), f -> List.of());
        String kenji = FamilyNamesFilingTest.run(store, "names", "森田健二");
        assertTrue(kenji.contains("The source says: \"mukoyōshi (婿養子)\"."), "words that say how the name came are shown: " + kenji);
        String shoji = FamilyNamesFilingTest.run(store, "names", "森田正二");
        assertFalse(shoji.contains("The source says"), "\"Isamu's son\" does not say how a name came: " + shoji);
        String masaru = FamilyNamesFilingTest.run(store, "names", "森田勝");
        assertTrue(masaru.contains("髙橋勝: a name; the library does not know yet how it came"), masaru);
        assertFalse(masaru.contains("The source says"), "\"became\" does not say how either: " + masaru);
    }
}
