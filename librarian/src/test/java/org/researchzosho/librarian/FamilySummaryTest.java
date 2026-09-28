package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The family summary ({@link FamilySummary}), with an invented family: close family by how they are related to the owner, nearest first,
 * each line with where it comes from; then the people easy to mix up, where the owner's notes and a source disagree, and what is not
 * settled.
 */
class FamilySummaryTest {

    static final String OWNER = FamilyClose.OWNER;
    static final String MEMOIR = "file:///family/hale-memoir_a-life-in-leeds.epub";

    static void file(LibraryStore store, String locator, String teller, List<FamilyAccount.Fact> facts, List<FamilyAccount.NameRead> names) throws Exception {
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), facts, List.of(), List.of(), names, List.of()), teller, f -> List.of(locator), f -> List.of());
    }

    static FamilyAccount.Fact fact(String s, String r, String o, String date, String quote) { return new FamilyAccount.Fact(s, r, o, date, quote); }

    static FamilyAccount.NameRead name(String person, String name, String family, String given, String kind, String date, String quote, String... forms) {
        return new FamilyAccount.NameRead(person, name, family, given, List.of(forms), kind, "", date, quote);
    }

    /**
     * The owner's notes beside links: Ned Hale is the owner's father, Tom Hale the father's father, Kit Hale a brother, 森田健二 the father's
     * friend, and Lily Wood the daughter of the father's father's younger brother. A memoir: Tom Hale's son Ned, born 1940, and his brother
     * Walter; Tom Hale born 1910, son of Tom Hale (born 1880); Ned married Ann Ellis in 1965, who became Ann Hale; Walter's son Jim; a grocer
     * the memoir mentions once. Geni gives Tom Hale's birth as 3 May 1911. An obituary names Lily's parents Sam and Nell Wood. Letters name
     * 森田健吾, born the same year as 森田健二, of the same father.
     */
    static LibraryStore hales(Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        file(store, "told://link-note/https://example.org/ned-hale", OWNER, List.of(fact("Ned Hale", "relative-of", OWNER, "", "About Ned Hale: my father")), List.of());
        file(store, "told://link-note/https://example.org/tom-hale", OWNER, List.of(fact("Tom Hale", "relative-of", OWNER, "", "About Tom Hale: my father's father")), List.of());
        file(store, "told://link-note/https://example.org/kit-hale", OWNER, List.of(fact("Kit Hale", "relative-of", OWNER, "", "About Kit Hale: my brother")), List.of());
        file(store, "told://link-note/https://example.org/morita", OWNER, List.of(fact("森田健二", "relative-of", OWNER, "", "About 森田健二: my father's friend from school")), List.of());
        file(store, "told://link-note/https://example.org/lily-wood", OWNER, List.of(fact("Lily Wood", "relative-of", OWNER + "'s father's father's younger brother", "",
                "lily wood - my father's father's younger brother's daughter")), List.of());
        String married = "Ned Hale married Ann Ellis in 1965, and she became Ann Hale.";
        file(store, MEMOIR, "an aunt", List.of(
                fact("Tom Hale", "parent-of", "Ned Hale", "", "Tom Hale's son Ned Hale was born in 1940."),
                fact("Ned Hale", "born-on", "1940", "", "Tom Hale's son Ned Hale was born in 1940."),
                fact("Ned Hale", "sex", "male", "", "Tom Hale's son Ned Hale was born in 1940."),
                fact("Ned Hale", "sibling-of", "Walter Hale", "", "Ned Hale and his brother Walter Hale ran the mill."),
                fact("Walter Hale", "parent-of", "Jim Hale", "", "Walter Hale's son Jim Hale went to sea."),
                fact("Jim Hale", "born-on", "1970", "", "Jim Hale was born in 1970."),
                fact("Tom Hale", "born-on", "1910", "", "Tom Hale was born in 1910."),
                fact("Tom Hale", "sex", "male", "", "Tom Hale was born in 1910."),
                fact("Tom Hale (born 1880)", "parent-of", "Tom Hale", "", "Tom Hale (born 1880) was the father of Tom Hale."),
                fact("Tom Hale (born 1880)", "born-on", "1880", "", "Tom Hale (born 1880) was the father of Tom Hale."),
                fact("Ned Hale", "married-to", "Ann Ellis", "1965", married),
                fact("Ann Ellis", "sex", "female", "", married),
                fact("森田健二", "born-on", "1905", "", "森田健二は1905年に生まれた。"),
                fact("森田健二", "child-of", "森田一郎", "", "森田健二は森田一郎の子である。"),
                fact("Kit Hale", "born-on", "1972", "", "Kit Hale was born in 1972."),
                fact("Kit", "child-of", "Ann Ellis", "", "Ann Ellis's youngest, Kit, was a sailor."),
                fact("Arthur Ellis", "lived-in", "Leeds", "", "Arthur Ellis, a grocer, lived in Leeds.")),
                List.of(name("Ann Ellis", "Ann Ellis", "Ellis", "Ann", "birth", "", married),
                        name("Ann Ellis", "Ann Hale", "Hale", "Ann", "marriage", "1965", married, "Anne Hale"),
                        name("Ned Hale", "Peter", "", "Peter", "religious", "", "Ned Hale was baptised Peter.")));
        file(store, "https://www.geni.com/people/Tom-Hale/6000000001", "a family tree site", List.of(fact("Tom Hale", "born-on", "3 May 1911", "", "Tom Hale, born 3 May 1911")), List.of());
        String born = "Lily was born to Sam and Nell Wood of Leeds.";
        file(store, "file:///family/obituary.txt", "an aunt", List.of(fact("Sam Wood", "parent-of", "Lily Wood", "", born), fact("Nell Wood", "parent-of", "Lily Wood", "", born),
                fact("Sam Wood", "sex", "male", "", "her father Sam Wood, a joiner")), List.of());
        file(store, "file:///family/letters.txt", "an uncle", List.of(fact("森田健吾", "born-on", "1905", "", "森田健吾は1905年に生まれた。"),
                fact("森田健吾", "child-of", "森田一郎", "", "森田健吾は森田一郎の子である。")), List.of());
        return store;
    }

    private static FamilySummary.Group group(FamilySummary.Summary s, String heading) {
        return s.groups().stream().filter(g -> g.heading().equals(heading)).findFirst().orElse(null);
    }

    private static FamilySummary.Person person(FamilySummary.Summary s, String heading) {
        return s.groups().stream().flatMap(g -> g.people().stream()).filter(p -> p.heading().equals(heading)).findFirst().orElse(null);
    }

    private static List<String> lines(FamilySummary.Summary s, String heading) { return person(s, heading).lines().stream().map(FamilySummary.Line::plain).toList(); }

    private static List<String> plain(List<FamilySummary.Line> ls) { return ls.stream().map(FamilySummary.Line::plain).toList(); }

    private static final String BOOK = "hale memoir a life in leeds";

    @Test
    void theGroupsComeNearestFirstAndEachPersonIsInOneOnly(@TempDir Path tmp) throws Exception {
        FamilySummary.Summary s = FamilySummary.of(hales(tmp), false);
        assertTrue(s.ownerKnown());
        List<String> h = FamilySummary.HEADINGS;
        assertEquals(List.of(h.get(0), h.get(1), h.get(2), h.get(3), h.get(5), h.get(8)), s.groups().stream().map(FamilySummary.Group::heading).toList(), "nobody further back, and no grandparent's brother or sister with a block");
        assertEquals(List.of("You", "Kit Hale"), group(s, h.get(0)).people().stream().map(FamilySummary.Person::heading).toList());
        assertEquals(List.of("Ned Hale", "Ann Hale (born Ellis)"), group(s, h.get(1)).people().stream().map(FamilySummary.Person::heading).toList(), "the father, then his wife");
        assertEquals(List.of("your father", "your father's wife"), group(s, h.get(1)).people().stream().map(FamilySummary.Person::relation).toList());
        assertEquals(List.of("Tom Hale"), group(s, h.get(3)).people().stream().map(FamilySummary.Person::heading).toList());
        assertEquals(List.of("Tom Hale (born 1880)"), group(s, h.get(5)).people().stream().map(FamilySummary.Person::heading).toList());
        assertNull(group(s, h.get(4)), "your father's father's brother, known only by your words and one relation, has no block of his own");
        assertTrue(lines(s, "Lily Wood").contains("Parents: Sam Wood (obituary.txt), Nell Wood (obituary.txt) and your father's father's brother (your link notes)."), "his daughter's list names him by your words: " + lines(s, "Lily Wood"));
        assertEquals(Set.of("森田健二", "Lily Wood"), group(s, h.get(8)).people().stream().map(FamilySummary.Person::heading).collect(Collectors.toSet()), "the people your notes name, who are in no group above");
        assertEquals("", person(s, "森田健二").relation(), "\"my father's friend\" makes him no relative");
        List<String> ids = s.groups().stream().flatMap(g -> g.people().stream()).map(FamilySummary.Person::id).toList();
        assertEquals(ids.size(), Set.copyOf(ids).size(), "a person is in one group only: " + ids);
        String text = FamilySummary.text(s);
        assertTrue(text.startsWith("# Your family, as the sources say it\n"), text);
        assertTrue(text.contains("\n## Your parents\n\n**Ned Hale** — your father\n- Ned Hale: the name your library files this person under. (your link notes; " + BOOK + ")\n"), text);
        for (String word : List.of("entry", "node", "claim", "F-0", "proved", "possible")) assertFalse(text.contains(word), "no word a layman must decode: " + word + "\n" + text);
    }

    @Test
    void namesOverALifeHaveTheirKindTheirYearsTheirOtherFormsAndTheirSource(@TempDir Path tmp) throws Exception {
        FamilySummary.Summary s = FamilySummary.of(hales(tmp), false);
        assertEquals(List.of("Ann Ellis: the name at birth, until 1965. (" + BOOK + ")", "Ann Hale (Anne Hale): the name taken at marriage, in 1965. (" + BOOK + ")",
                "Married to Ned Hale in 1965. (" + BOOK + ")", "Children: Kit Hale. (" + BOOK + "; the library joined \"Kit\" and Kit Hale as one person: probable, the same source names this person in full)"),
                lines(s, "Ann Hale (born Ellis)"));
        List<String> ned = lines(s, "Ned Hale");
        assertTrue(ned.indexOf("Peter: a religious or posthumous name, used beside the others. (" + BOOK + ")") == 1, "a name carried beside the others, on its own line after the names of the life: " + ned);
    }

    @Test
    void twoBirthDatesAreBothShownEachWithItsSourceAndAreNotSettled(@TempDir Path tmp) throws Exception {
        FamilySummary.Summary s = FamilySummary.of(hales(tmp), false);
        assertEquals("Born 1910 (" + BOOK + ") or 3 May 1911 (Geni).", lines(s, "Tom Hale").get(0));
        assertTrue(plain(s.notSettled()).contains("Tom Hale's birth: the sources give 1910 (" + BOOK + ") and 3 May 1911 (Geni)."), plain(s.notSettled()).toString());
        assertEquals("Born 1940. (" + BOOK + ")", lines(s, "Ned Hale").get(2), "one date, its source at the end");
        // dates that may be one date: a year and a day in it, a date written two ways, an about date around a year
        assertTrue(FamilySummary.agree(FamilyDate.parse("1911"), FamilySummary.monthDay("1911"), FamilyDate.parse("3 May 1911"), FamilySummary.monthDay("3 May 1911")));
        assertTrue(FamilySummary.agree(FamilyDate.parse("1911-05-03"), FamilySummary.monthDay("1911-05-03"), FamilyDate.parse("3 May 1911"), FamilySummary.monthDay("3 May 1911")));
        assertTrue(FamilySummary.agree(FamilyDate.parse("about 1910"), FamilySummary.monthDay("about 1910"), FamilyDate.parse("1911"), FamilySummary.monthDay("1911")));
        assertFalse(FamilySummary.agree(FamilyDate.parse("4 May 1911"), FamilySummary.monthDay("4 May 1911"), FamilyDate.parse("1911年5月3日"), FamilySummary.monthDay("1911年5月3日")));
        assertFalse(FamilySummary.agree(FamilyDate.parse("1910"), FamilySummary.monthDay("1910"), FamilyDate.parse("1911"), FamilySummary.monthDay("1911")));
    }

    @Test
    void aRelationTheLibraryWorkedOutSaysSoAndAJoinItRestsOnIsShown(@TempDir Path tmp) throws Exception {
        FamilySummary.Summary s = FamilySummary.of(hales(tmp), false);
        assertEquals("Parents: Tom Hale. (worked out: brother or sister of Ned Hale)", lines(s, "Walter Hale").get(0));
        assertTrue(lines(s, "Tom Hale").contains("Children: Ned Hale (" + BOOK + ") and Walter Hale (worked out: brother or sister of Ned Hale)."), lines(s, "Tom Hale").toString());
        assertTrue(lines(s, "Kit Hale").contains("Parents: Ann Hale (born Ellis). (" + BOOK + "; the library joined \"Kit\" and Kit Hale as one person: probable, the same source names this person in full)"),
                "the source writes Kit alone there, and only the join makes Ann Kit Hale's mother: " + lines(s, "Kit Hale"));
        assertEquals(List.of("Parents: Ned Hale. (your link notes)", "Brothers and sisters: Kit Hale. (your link notes)"), lines(s, "You"), "your own word for a relative one step away is a relation");
    }

    @Test
    void theMixUpsNameANamesakeFatherAndSonAndAPairThatDiffersByOneCharacter(@TempDir Path tmp) throws Exception {
        FamilySummary.Summary s = FamilySummary.of(hales(tmp), false);
        List<String> mix = plain(s.mixUps());
        assertTrue(mix.contains("Two people are called Tom Hale: Tom Hale (born 1910; your father's father) and Tom Hale (born 1880) (your father's father's father). Tom Hale (born 1880) is Tom Hale's father."), mix.toString());
        assertTrue(mix.contains("森田健二 (born 1905) and 森田健吾 (born 1905) may be one person: the names differ by one character, and the library keeps them as two people until your family says otherwise."), mix.toString());
        String page = FamilySummary.html(s);
        assertTrue(page.contains("<a href=\"/person?name=" + FamilyNamePages.enc("森田健吾") + "\">森田健吾</a>"), "each of the two is a link on the page: " + page);
    }

    private static List<FamilyNameQuestions.Question> notes(LibraryStore store) throws Exception {
        return FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals("notes-source")).toList();
    }

    @Test
    void whereYourNotesAndASourceDisagreeBothAreShownAnsweredOrNot(@TempDir Path tmp) throws Exception {
        LibraryStore store = hales(tmp);
        List<String> before = plain(FamilySummary.of(store, false).disagree());
        assertEquals(1, before.size(), before.toString());
        String d = before.get(0);
        assertTrue(d.startsWith("Your notes and a source disagree about the parents of Lily Wood. Your note beside the link to example.org says: “lily wood - my father's father's younger brother's daughter”"), d);
        assertTrue(d.contains("The source, obituary.txt, says: “Lily was born to Sam and Nell Wood of Leeds.”") && d.endsWith("You have not answered this yet: researchzosho genealogy who asks you which is right."), d);
        assertFalse(d.contains("Which is right?") || d.contains("or is your note or the source wrong?"), "information, not a question: " + d);
        FamilyNameQuestions.Question q = notes(store).get(0);
        FamilyNameQuestions.answer(store, q.code(), "notes", "", "Ann");
        assertEquals(List.of(), notes(store), "the note is disputed: nothing is asked any more");
        List<String> after = plain(FamilySummary.of(store, false).disagree());
        assertEquals(1, after.size(), "the summary still says where they disagreed: " + after);
        assertTrue(after.get(0).contains("“lily wood - my father's father's younger brother's daughter”") && after.get(0).endsWith("Your answer: my note is wrong."), after.get(0));
        FamilyNameQuestions.reopen(store, q.code());
        FamilyNameQuestions.answer(store, q.code(), "later", "", "Ann");
        assertTrue(plain(FamilySummary.of(store, false).disagree()).get(0).endsWith("You put this off. researchzosho genealogy who --reopen " + q.code() + " asks it again."));
    }

    @Test
    void closeFamilyByDefaultAndWithAllEveryoneTheLibraryPlacesInTheFamily(@TempDir Path tmp) throws Exception {
        LibraryStore store = hales(tmp);
        FamilySummary.Summary close = FamilySummary.of(store, false), all = FamilySummary.of(store, true);
        assertNull(person(close, "Jim Hale"), "a father's brother's son is no close family");
        assertNull(person(close, "Arthur Ellis"));
        assertEquals("your father's brother's son", person(all, "Jim Hale").relation());
        assertTrue(group(all, FamilySummary.HEADINGS.get(2)).people().stream().anyMatch(p -> p.heading().equals("Jim Hale")), "under his parents' brothers and sisters, with their children");
        assertTrue(group(all, FamilySummary.HEADINGS.get(4)).people().stream().anyMatch(p -> p.heading().equals("Lily Wood")), "placed by how she is related to you, not as somebody your notes name");
        assertNull(person(all, "Arthur Ellis"), "a grocer a book mentions once is outside the family");
        assertTrue(FamilySummary.intro(all).startsWith("What the sources in your library say about everyone the library places in your family"));
    }

    @Test
    void whatIsNotSettledSaysWhomNoSourceNames(@TempDir Path tmp) throws Exception {
        List<String> open = plain(FamilySummary.of(hales(tmp), false).notSettled());
        for (String x : List.of("No source names your mother.", "No source names your father's mother.", "No source names your father's father's mother.",
                "Lily Wood's parents: the sources name Sam Wood, Nell Wood and your father's father's brother.", "No source gives a birth date for Ann Hale (born Ellis) and Walter Hale."))
            assertTrue(open.contains(x), x + " in " + open);
        assertFalse(open.contains("No source names your father's parents."), "the memoir names Tom Hale");
    }

    @Test
    void thePageShowsTheSameWithEachNameALinkToThePersonsPage(@TempDir Path tmp) throws Exception {
        LibraryStore store = hales(tmp);
        String page = FamilyPages.page(store, Patrons.Patron.PERSON, "/summary", "GET", Map.of(), Map.of()).body();
        assertTrue(page.contains("<h2>Your parents</h2>") && page.contains("<h3><a href=\"/person?name=Ned+Hale\">Ned Hale</a> <span class=\"k\">— your father</span></h3>"), page);
        assertTrue(page.contains("<li>Born 1910 (" + BOOK + ") or 3 May 1911 (Geni).</li>"), page);
        assertTrue(page.contains("Children: <a href=\"/person?name=Ned+Hale\">Ned Hale</a> (" + BOOK + ")"), page);
        String jim = "<h3><a href=\"/person?name=Jim+Hale\">Jim Hale</a>";
        assertFalse(page.contains(jim), "your father's brother's son is named as his father's child, with no place of his own: " + page);
        assertTrue(page.contains("<a href=\"/summary?all=1\">"), "the page offers everyone in the family");
        assertTrue(FamilyPages.page(store, Patrons.Patron.PERSON, "/summary", "GET", Map.of("all", "1"), Map.of()).body().contains(jim));
        assertTrue(FamilyPages.menu().stream().anyMatch(l -> l.href().equals("/summary") && !l.writersOnly()), "in the menu, for everybody who may read the library");
        assertTrue(FamilyPages.treeBody(store, "").contains("<a href=\"/summary\">") && FamilyNamePages.personPage(store, Patrons.Patron.PERSON, "Ned Hale").contains("<a href=\"/summary\">"),
                "the family pages link to it");
    }

    private static String[] run(LibraryStore store, String... rest) throws Exception {
        String[] args = new String[rest.length + 2];
        args[0] = "researchzosho"; args[1] = "genealogy";
        System.arraycopy(rest, 0, args, 2, rest.length);
        PrintStream was = System.out, wasErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        Integer rc;
        try { rc = new GenealogyProfile().cli(store, args); } finally { System.setOut(was); System.setErr(wasErr); }
        return new String[]{String.valueOf(rc), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8)};
    }

    @Test
    void theCommandPrintsTheSummaryOrWritesItToAFile(@TempDir Path tmp) throws Exception {
        LibraryStore store = hales(tmp);
        String[] printed = run(store, "summary");
        assertEquals("0", printed[0], printed[2]);
        assertTrue(printed[1].startsWith("# Your family, as the sources say it") && printed[1].contains("**Tom Hale** — your father's father"), printed[1]);
        Path md = tmp.resolve("out").resolve("family.md");
        String[] saved = run(store, "summary", "--all", "--out", md.toString());
        assertEquals("0", saved[0], saved[2]);
        assertTrue(saved[1].startsWith("The summary of your family is saved in " + md.toAbsolutePath().normalize() + "."), saved[1]);
        assertTrue(Files.readString(md, StandardCharsets.UTF_8).contains("**Jim Hale** — your father's brother's son"));
        String[] wrong = run(store, "summary", "--everyone");
        assertEquals("2", wrong[0]);
        assertTrue(wrong[2].startsWith("usage: researchzosho genealogy summary [--all] [--out <file.md>]"), wrong[2]);
    }

    @Test
    void whileTheLibraryDoesNotKnowWhoYouAreItListsThePeopleYourFamilysFilesName(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        file(store, MEMOIR, "an aunt", List.of(fact("Tom Hale", "parent-of", "Ned Hale", "", "Tom Hale's son Ned Hale was born in 1940."),
                fact("Ned Hale", "born-on", "1940", "", "Tom Hale's son Ned Hale was born in 1940.")), List.of());
        FamilySummary.Summary s = FamilySummary.of(store, false);
        assertFalse(s.ownerKnown());
        assertEquals(List.of(FamilySummary.NAMED_BY_FILES), s.groups().stream().map(FamilySummary.Group::heading).toList());
        assertEquals(Set.of("Ned Hale"), s.groups().get(0).people().stream().map(FamilySummary.Person::heading).collect(Collectors.toSet()), "Tom Hale, with one relation and nothing else, is in Ned's list only");
        assertTrue(lines(s, "Ned Hale").contains("Parents: Tom Hale. (" + BOOK + ")"), lines(s, "Ned Hale").toString());
        assertTrue(FamilySummary.intro(s).startsWith("The library does not know yet which person in it is you"), FamilySummary.intro(s));
        assertEquals(List.of(), s.disagree());
    }

    @Test
    void aSourceIsAShortLabelAPersonRecognises() {
        assertEquals("The Morita Family in Kure A History", FamilySummary.title("The Morita Family in Kure_ A History (Ann Hale).epub"));
        assertEquals("hale memoir a life in leeds", FamilySummary.title("hale-memoir_a-life-in-leeds.txt"));
        assertEquals("Pre-War Leeds", FamilySummary.title("Pre-War Leeds.pdf"), "a hyphen inside a title written with spaces stays");
        assertTrue(FamilySummary.title("A Very Long Title About The Morita Family Of Kure And Their Mill.pdf").endsWith("…"));
        assertEquals("ja.wikipedia: 森田健二", FamilySummary.site("https://ja.wikipedia.org/wiki/%E6%A3%AE%E7%94%B0%E5%81%A5%E4%BA%8C"));
        assertEquals("en.wikipedia: Morita Kenji", FamilySummary.site("https://en.wikipedia.org/wiki/Morita_Kenji"));
        assertEquals("Geni", FamilySummary.site("https://www.geni.com/people/Tom-Hale/6000000001"));
        assertEquals("example.org", FamilySummary.site("https://www.example.org/hales"));
    }

    @Test
    void wordsThatGoOnPastTheRelativesNameNoRelative() {
        assertNull(FamilyClose.chain("About 森田健二: my father's friend from school"), "a father's friend is no relative");
        assertNull(FamilyClose.chain("私の父の友人です"));
        assertEquals("your father's father", FamilyClose.chain("About Tom Hale: my father's father").said());
        assertEquals("your father's father's father", FamilyClose.chain("About Tom Hale: my great grandfather on my father's side (my father's father's father)").said(), "\"my father's side\" says only the side");
        assertEquals("your father's mother", FamilyClose.chain("私の父方の祖母").said());
    }

    /** The owner's note makes Ned Hale the father; the rest comes from the memoir, and what is added to it. */
    private static LibraryStore ned(Path tmp, List<FamilyAccount.Fact> more) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        file(store, "told://link-note/https://example.org/ned-hale", OWNER, List.of(fact("Ned Hale", "relative-of", OWNER, "", "About Ned Hale: my father")), List.of());
        file(store, MEMOIR, "an aunt", more, List.of());
        return store;
    }

    @Test
    void aGivenNameTheLibraryFilesUnderOnePersonIsShownBesideAnotherOfTheFamilyWhoCarriesIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = ned(tmp, List.of(fact("Ned Hale", "sibling-of", "Bess Hale", "", "Ned Hale and his sister Bess Hale kept the shop."),
                fact("Ned Hale", "married-to", "Bess Ellis", "1965", "Ned Hale married Bess Ellis in 1965."), fact("Bess", "lived-in", "York", "", "Bess lived in York.")));
        Graph.merge(store, "Bess", "Bess Hale", "person", "the family said so", new GenealogyProfile());
        List<String> mix = plain(FamilySummary.of(store, false).mixUps());
        assertTrue(mix.contains("\"Bess\", a name the library files under Bess Hale (your father's sister), is the given name of Bess Ellis (your father's wife) too."), mix.toString());
    }

    @Test
    void aRelativeReachedByBloodAndByMarriageInAsManyStepsIsNamedByBlood(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        file(store, "told://link-note/https://example.org/tom-hale", OWNER, List.of(fact("Tom Hale", "relative-of", OWNER, "", "About Tom Hale: my father's father")), List.of());
        file(store, "told://link-note/https://example.org/sam-hale", OWNER, List.of(fact("Sam Hale", "relative-of", OWNER, "", "About Sam Hale: my father's father's father")), List.of());
        file(store, MEMOIR, "an aunt", List.of(fact("Meg Hale", "parent-of", "Tom Hale", "", "Meg Hale was Tom Hale's mother."), fact("Meg Hale", "sex", "female", "", "Meg Hale was Tom Hale's mother."),
                fact("Sam Hale", "married-to", "Meg Hale", "1905", "Sam Hale married Meg Hale in 1905.")), List.of());
        Graph g = FamilyPeople.view(store);
        assertEquals("your father's father's mother", FamilyClose.of(g).said(g.nodeIdOf("Meg Hale")), "his mother, not his father's wife");
    }

    @Test
    void aHusbandWrittenAsHisWifesBrotherTooIsShownBothWaysAndIsNotSettled(@TempDir Path tmp) throws Exception {
        // a son-in-law adopted as heir is his wife's parents' child in law, and a source may write him so; or one of the claims is wrong
        LibraryStore store = ned(tmp, List.of(fact("Sam Ellis", "parent-of", "Ned Hale", "", "Sam Ellis took Ned Hale as his son and heir."),
                fact("Sam Ellis", "parent-of", "Ann Ellis", "", "Sam Ellis's daughter Ann Ellis."), fact("Ned Hale", "married-to", "Ann Ellis", "1965", "Ned Hale married Ann Ellis in 1965.")));
        FamilySummary.Summary s = FamilySummary.of(store, false);
        List<String> ned = lines(s, "Ned Hale");
        assertTrue(ned.contains("Married to Ann Ellis in 1965. (" + BOOK + ")") && ned.contains("Brothers and sisters: Ann Ellis. (" + BOOK + ")"), "nothing a source says is hidden: " + ned);
        assertTrue(plain(s.notSettled()).contains("Ned Hale and Ann Ellis are written as husband and wife and as brother and sister. A son-in-law adopted as heir is both in law; otherwise one of the two is wrong."),
                plain(s.notSettled()).toString());
    }

    @Test
    void aSourceThatGivesLessOfADateIsCreditedWithWhatItGives(@TempDir Path tmp) throws Exception {
        LibraryStore store = ned(tmp, List.of(fact("Ned Hale", "born-on", "1940", "", "Ned Hale was born in 1940.")));
        // a tree site files the birthplace with the date of the birth
        file(store, "https://www.geni.com/people/Ned-Hale/6000000002", "a family tree site", List.of(fact("Ned Hale", "born-in", "Leeds", "12 June 1940", "Ned Hale, born 12 June 1940 in Leeds")), List.of());
        List<String> ned = lines(FamilySummary.of(store, false), "Ned Hale");
        assertTrue(ned.contains("Born 12 June 1940 (Geni; " + BOOK + " gives 1940), in Leeds (Geni)."), "the day is Geni's alone: " + ned);
    }

    @Test
    void aPersonDescribedByABooksWriterIsHeadedByTheBooksTitle(@TempDir Path tmp) throws Exception {
        LibraryStore store = ned(tmp, List.of(fact("Ned Hale", "married-to", "the writer of hale-memoir_a-life-in-leeds.epub's mother", "1965", "My mother married Ned Hale in 1965.")));
        FamilySummary.Summary s = FamilySummary.of(store, false);
        assertNull(person(s, "The writer of hale memoir a life in leeds's mother"), "a description of somebody has no block of its own");
        assertTrue(lines(s, "Ned Hale").contains("Married to the writer of hale memoir a life in leeds's mother in 1965. (" + BOOK + ")"), lines(s, "Ned Hale").toString());
    }

    @Test
    void aFamilyNameAloneTheSourcesWriteForSomebodyOfTheFamilyIsShownWithWhomItMayBe(@TempDir Path tmp) throws Exception {
        LibraryStore store = ned(tmp, List.of(fact("Tom Hale", "parent-of", "Ned Hale", "", "Tom Hale's son Ned Hale was born in 1940."),
                fact("Ned Hale", "sibling-of", "Walter Hale", "", "Ned Hale and his brother Walter Hale ran the mill."),
                fact("Mr. Hale", "occupation", "miller", "", "Mr. Hale's mill stood by the river.")));
        List<String> mix = plain(FamilySummary.of(store, false).mixUps());
        assertTrue(mix.contains("\"Mr. Hale\" in " + BOOK + " is somebody of the Hale family whom nothing identifies yet (1 fact). Of your family, the same sources name Ned Hale (your father), "
                + "Tom Hale (your father's parent) and Walter Hale (your father's brother)."), "nothing gives Tom Hale's sex here: " + mix);
    }

    @Test
    void everyAncestorOnYourLineIsListedWithHusbandsAndWivesAndTheirBrothersAndSistersOnlyWithAll(@TempDir Path tmp) throws Exception {
        String line = "The Hales of Leeds, from father to son.";
        LibraryStore store = ned(tmp, List.of(fact("Tom Hale", "parent-of", "Ned Hale", "", line), fact("Sam Hale", "parent-of", "Tom Hale", "", line),
                fact("Will Hale", "parent-of", "Sam Hale", "", line), fact("Abe Hale", "parent-of", "Will Hale", "", line), fact("Abe Hale", "married-to", "Ruth Hale", "1820", line),
                fact("Will Hale", "sibling-of", "Jack Hale", "", line), fact("Ruth Hale", "sex", "female", "", line),
                fact("Ruth Hale", "born-on", "1800", "", line), fact("Jack Hale", "born-on", "1822", "", line),
                fact("Tom Hale", "sex", "male", "", line), fact("Sam Hale", "sex", "male", "", line), fact("Will Hale", "sex", "male", "", line), fact("Abe Hale", "sex", "male", "", line)));
        FamilySummary.Summary close = FamilySummary.of(store, false);
        FamilySummary.Group back = group(close, FamilySummary.HEADINGS.get(FamilySummary.FURTHER));
        assertEquals(List.of("your father's father's father's father", "your father's father's father's father's father", "your father's father's father's father's father's wife"),
                back.people().stream().map(FamilySummary.Person::relation).toList(), "a great-great-grandfather, the generation above him, and his wife");
        assertNull(person(close, "Jack Hale"), "a great-great-grandfather's brother comes only with --all");
        assertEquals("your father's father's father's father's brother or sister", person(FamilySummary.of(store, true), "Jack Hale").relation());
        assertFalse(FamilyClose.of(FamilyPeople.view(store)).close(FamilyPeople.view(store).nodeIdOf("Will Hale")), "the questions keep close family as it is");
    }

    @Test
    void aNotesFileOfYourFolderIsYourNotesUnlessTheFoldersNotesNameItsWriter(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameKindFromWordsTest.store(tmp);
        Path folder = Files.createDirectories(tmp.resolve("family-papers"));
        Path mine = Files.writeString(folder.resolve("notes.txt"), "Ned Hale is my father.\nKimie Hale wrote aunt-notes.txt.\n", StandardCharsets.UTF_8);
        Path aunt = Files.writeString(folder.resolve("aunt-notes.txt"), "Ned Hale was born in 1940.\n", StandardCharsets.UTF_8);
        Path alone = Files.writeString(tmp.resolve("loose-notes.txt"), "Ned Hale married Ann Ellis.\n", StandardCharsets.UTF_8);
        Path rose = Files.writeString(tmp.resolve("rose-notes.txt"), "Ned Hale died in 1990.\n", StandardCharsets.UTF_8);
        file(store, "told://link-note/https://example.org/ned-hale", OWNER, List.of(fact("Ned Hale", "relative-of", OWNER, "", "About Ned Hale: my father")), List.of());
        file(store, "file://" + mine, "the writer of notes.txt", List.of(fact("Ned Hale", "sibling-of", "Walter Hale", "", "Ned Hale and his brother Walter Hale ran the mill.")), List.of());
        file(store, "file://" + aunt, "Kimie Hale", List.of(fact("Ned Hale", "born-on", "1940", "", "Ned Hale was born in 1940.")), List.of());
        file(store, "file://" + alone, "the writer of loose-notes.txt", List.of(fact("Ned Hale", "married-to", "Ann Ellis", "1965", "Ned Hale married Ann Ellis in 1965.")), List.of());
        file(store, "file://" + rose, "Rose Hart", List.of(fact("Ned Hale", "died-on", "1990", "", "Ned Hale died in 1990.")), List.of());
        FamilyFolder.markRead(store, mine, FamilyReads.FILE, folder.toString());
        FamilyFolder.markRead(store, aunt, FamilyReads.FILE, folder.toString());
        FamilyReads.readFile(store, alone, FamilyReads.FILE, "", "", "");
        FamilyReads.readFile(store, rose, FamilyReads.FILE, "", "Rose Hart", "");
        List<String> ned = lines(FamilySummary.of(store, false), "Ned Hale");
        assertTrue(ned.contains("Born 1940. (Kimie Hale's notes)"), "the folder's notes say who wrote it: " + ned);
        assertTrue(ned.contains("Married to Ann Ellis in 1965. (your notes)"), "notes read on their own, with no other writer named, are yours too (FamilyFolder.ownerNotes): " + ned);
        assertTrue(ned.stream().anyMatch(l -> l.startsWith("Died 1990") && l.endsWith("(Rose Hart's notes)")), "notes read as somebody else's account (--by) are theirs: " + ned);
        assertTrue(ned.contains("Brothers and sisters: Walter Hale. (your notes)"), "the folder's own notes are yours, with no \"I am\" in them: " + ned);
    }

    @Test
    void namesThatDifferByOneCharacterAreEasyToMixUpBetweenRelativesAndNotAcrossTheSexes(@TempDir Path tmp) throws Exception {
        LibraryStore father = ned(tmp.resolve("father"), List.of(fact("Ned Hale", "parent-of", "森田健二", "", "Ned Hale's son 森田健二."),
                fact("森田健二", "parent-of", "森田健吾", "", "森田健二の子、森田健吾。"), fact("森田健二", "sex", "male", "", "森田健二の子、森田健吾。"), fact("森田健吾", "sex", "male", "", "森田健二の子、森田健吾。"),
                fact("森田健二", "born-on", "1965", "", "森田健二は1965年に生まれた。"), fact("森田健吾", "born-on", "1990", "", "森田健吾は1990年に生まれた。")));
        List<String> mix = plain(FamilySummary.of(father, true).mixUps());
        assertTrue(mix.contains("森田健二 (born 1965; your brother) and 森田健吾 (born 1990; your brother's son) are two people whose names differ by one character: 森田健吾 is 森田健二's son."), mix.toString());
        LibraryStore sexes = ned(tmp.resolve("sexes"), List.of(fact("Ned Hale", "parent-of", "森田健二", "", "Ned Hale's son 森田健二."), fact("森田健二", "sex", "male", "", "Ned Hale's son 森田健二."),
                fact("森田健吾", "sex", "female", "", "森田健吾は女性である。"), fact("森田健吾", "born-on", "1965", "", "森田健吾は1965年に生まれた。")));
        assertTrue(plain(FamilySummary.of(sexes, true).mixUps()).stream().noneMatch(l -> l.contains("森田健吾")), "a man and a woman are not mixed up");
        LibraryStore years = ned(tmp.resolve("years"), List.of(fact("Ned Hale", "parent-of", "森田健二", "", "Ned Hale's son 森田健二."), fact("森田健二", "born-on", "1965", "", "森田健二は1965年に生まれた。"),
                fact("森田健吾", "born-on", "1870", "", "森田健吾は1870年に生まれた。")));
        assertTrue(plain(FamilySummary.of(years, true).mixUps()).stream().noneMatch(l -> l.contains("森田健吾")), "nothing relates them, and they were born a lifetime apart");
    }

    @Test
    void theBrothersAndSistersOfEachGenerationHaveAGroupOfTheirOwnAndFurtherBackKeepsTheAncestors(@TempDir Path tmp) throws Exception {
        String line = "The Hales of Leeds.";
        LibraryStore store = ned(tmp, List.of(fact("Tom Hale", "parent-of", "Ned Hale", "", line), fact("Sam Hale", "parent-of", "Tom Hale", "", line), fact("Will Hale", "parent-of", "Sam Hale", "", line),
                fact("Sam Hale", "sibling-of", "Bess Hale", "", line), fact("Bess Hale", "married-to", "Joe Wood", "1905", line), fact("Bess Hale", "parent-of", "Ann Wood", "", line),
                fact("Will Hale", "sibling-of", "Jack Hale", "", line), fact("Tom Hale", "sibling-of", "Kit Hale", "", line), fact("Ned Hale", "sibling-of", "Walter Hale", "", line),
                fact("Bess Hale", "born-on", "1882", "", line), fact("Jack Hale", "born-on", "1850", "", line), fact("Kit Hale", "born-on", "1912", "", line), fact("Walter Hale", "born-on", "1942", "", line),
                fact("Joe Wood", "born-on", "1880", "", line), fact("Ann Wood", "born-on", "1908", "", line), fact("Sam Hale", "born-on", "1880", "", line), fact("Will Hale", "born-on", "1850", "", line),
                fact("Bess Hale", "sex", "female", "", line), fact("Sam Hale", "sex", "male", "", line), fact("Tom Hale", "sex", "male", "", line), fact("Will Hale", "sex", "male", "", line)));
        FamilySummary.Summary close = FamilySummary.of(store, false), all = FamilySummary.of(store, true);
        List<String> h = FamilySummary.HEADINGS;
        assertEquals(List.of("Bess Hale"), group(close, h.get(6)).people().stream().map(FamilySummary.Person::heading).toList(), "a great-grandfather's sister has a group of her own");
        assertEquals("your father's father's father's sister", person(close, "Bess Hale").relation());
        assertEquals(List.of("Will Hale"), group(close, h.get(FamilySummary.FURTHER)).people().stream().map(FamilySummary.Person::heading).toList(), "further back keeps the ancestors beyond the great-grandparents");
        assertNull(person(close, "Joe Wood"), "her husband and her daughter come with --all");
        assertEquals(List.of("Bess Hale", "Joe Wood", "Ann Wood"), group(all, h.get(6)).people().stream().map(FamilySummary.Person::heading).toList(), "with her, in her group");
        assertEquals(List.of("Will Hale", "Jack Hale"), group(all, h.get(FamilySummary.FURTHER)).people().stream().map(FamilySummary.Person::heading).toList(), "a great-great-grandfather's brother stays further back");
        assertEquals(List.of("Kit Hale"), group(close, h.get(4)).people().stream().map(FamilySummary.Person::heading).toList(), "a grandfather's brother or sister");
        assertEquals(List.of("Walter Hale"), group(close, h.get(2)).people().stream().map(FamilySummary.Person::heading).toList(), "a father's brother or sister");
        assertEquals(List.of(h.get(1), h.get(2), h.get(3), h.get(4), h.get(5), h.get(6), h.get(FamilySummary.FURTHER)), close.groups().stream().map(FamilySummary.Group::heading).toList());
    }

    @Test
    void aSecondWifeTheEvidenceHoldsAsPossiblyTheFirstSaysSo(@TempDir Path tmp) throws Exception {
        LibraryStore store = ned(tmp, List.of(fact("Ned Hale", "married-to", "森田健二", "1965", "Ned Hale married 森田健二 in 1965."), fact("森田健二", "sex", "female", "", "Ned Hale married 森田健二 in 1965."),
                fact("森田健二", "born-on", "1940", "", "森田健二は1940年に生まれた。")));
        file(store, "file:///family/letters.txt", "an uncle", List.of(fact("Ned Hale", "married-to", "森田健吾", "", "Ned Hale's wife 森田健吾."), fact("森田健吾", "sex", "female", "", "Ned Hale's wife 森田健吾.")), List.of());
        List<String> ned = lines(FamilySummary.of(store, false), "Ned Hale");
        assertTrue(ned.contains("Married to 森田健二 in 1965. (" + BOOK + ")") && ned.contains("Married to 森田健吾 (possibly the same person as 森田健二). (letters.txt)"), ned.toString());
    }

    @Test
    void thePeopleATreeSiteLeftUnnamedHaveNoBlockAndAreCountedOnceInALists(@TempDir Path tmp) throws Exception {
        LibraryStore store = ned(tmp, List.of(fact("Tom Hale", "parent-of", "Ned Hale", "", "Tom Hale's son Ned Hale was born in 1940."), fact("Tom Hale", "born-on", "1910", "", "Tom Hale was born in 1910.")));
        String geni = "https://www.geni.com/people/Tom-Hale/6000000001";
        file(store, geni, "a family tree site", List.of(fact("Tom Hale", "parent-of", "? Hale", "", "Tom Hale's child ? Hale"), fact("Tom Hale", "parent-of", "? Hale (Geni 4786)", "", "Tom Hale's child ? Hale"),
                fact("Tom Hale", "married-to", "? Hale (Geni 4849)", "", "Tom Hale's wife ? Hale"), fact("Tom Hale", "parent-of", "Ann Hale", "", "Tom Hale's daughter Ann Hale")), List.of());
        FamilySummary.Summary s = FamilySummary.of(store, true);
        assertTrue(s.groups().stream().flatMap(g -> g.people().stream()).noneMatch(p -> p.heading().contains("?")), "an unnamed person is never a block: " + s.groups());
        List<String> tom = lines(s, "Tom Hale");
        assertTrue(tom.contains("Children: Ned Hale (" + BOOK + "), Ann Hale (Geni) and two unnamed children (Geni).") && tom.contains("Married to an unnamed husband or wife. (Geni)"), tom.toString());
        assertTrue(lines(s, "Ned Hale").contains("Brothers and sisters: Ann Hale and two unnamed brothers or sisters. (Geni)"), "one source for all, said once: " + lines(s, "Ned Hale"));
        assertTrue(lines(s, "Ann Hale").contains("Brothers and sisters: Ned Hale (" + BOOK + ") and two unnamed brothers or sisters (Geni)."), "two sources, each said: " + lines(s, "Ann Hale"));
    }

    @Test
    void oneDisagreementBetweenOneNoteAndOneSourceIsSaidOnce(@TempDir Path tmp) throws Exception {
        LibraryStore store = hales(tmp);
        // the same note beside the same link, read as a parent claim too: two claims, one disagreement
        file(store, "told://link-note/https://example.org/lily-wood", OWNER, List.of(fact(OWNER + "'s father's father's younger brother", "parent-of", "Lily Wood", "",
                "lily wood - my father's father's younger brother's daughter")), List.of());
        assertEquals(2, notes(store).size(), "two questions wait");
        List<String> said = plain(FamilySummary.of(store, false).disagree());
        assertEquals(1, said.size(), said.toString());
    }

    @Test
    void aDescriptionInJapaneseAndAPersonWithOneRelationOnlyHaveNoBlock(@TempDir Path tmp) throws Exception {
        LibraryStore store = ned(tmp, List.of(fact("森田健二の父", "parent-of", "森田健二", "", "森田健二の父は漁師であった。"), fact("森田健二", "born-on", "1965", "", "森田健二は1965年に生まれた。"),
                fact("Ned Hale", "parent-of", "森田健二", "", "Ned Hale's son 森田健二."), fact("Ned Hale", "sibling-of", "Walter Hale", "", "Ned Hale and his brother Walter Hale ran the mill.")));
        file(store, "told://link-note/https://example.org/morita", OWNER, List.of(fact("森田健二の父", "relative-of", OWNER, "", "About 森田健二の父: a page about him")), List.of());
        FamilySummary.Summary s = FamilySummary.of(store, true);
        assertNull(person(s, "森田健二の父"), "Xの父 is a description, never a block: " + s.groups());
        assertTrue(lines(s, "森田健二").contains("Parents: Ned Hale. (" + BOOK + ")"), "森田健二の父 is Ned Hale, the one father the same book records, so his son's list names him once: " + lines(s, "森田健二"));
        assertNull(person(s, "Walter Hale"), "one relation and nothing else: no block");
        assertTrue(lines(s, "Ned Hale").contains("Brothers and sisters: Walter Hale. (" + BOOK + ")"), lines(s, "Ned Hale").toString());
        assertTrue(FamilySummary.description("森田健二の父の父") && FamilySummary.description("森田健二の長男") && FamilySummary.description("Kano's mother") && !FamilySummary.description("森田健二"));
    }
}
