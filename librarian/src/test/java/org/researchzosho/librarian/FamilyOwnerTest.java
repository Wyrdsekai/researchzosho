package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The owner is a person ({@link FamilyLinks}), with an invented family. The owner's own notes, read with the family's folder, say who
 * the owner is ("i am kenji morita - 森田健二"), and name the owner's father, mother, brother and grandfather; the list of links the owner
 * keeps files what it says as "the owner of this library's father" and so on. "You", "your father", "your mother", "your father's father"
 * and the brother are then one entry each with the person named, and close family starts from the owner's own entry.
 */
class FamilyOwnerTest {

    private static final String OWNER = "as told by the owner of this library";

    private static final String NOTES = """
            i am kenji morita - 森田健二  born 1973
            my father is 森田勇 (Isamu Morita)
            my mother is haru morita (formerly haru endo) 森田ハル formerly 遠藤ハル
            my brother is osamu morita 森田修 - born 1976
            森田正一 is my dad's father (so my grandfather)
            Hanae Endo is my father's cousin
            森田健吾 is 森田正一's younger brother
            遠藤源三郎 - my mother's grandfather
            森田源太 is my great grandfather (my father's father's father)
            森田良子 is my father's sister
            """;

    private static void claim(LibraryStore store, String s, String p, String o, String locator, String edition, String quote) throws Exception { claimOf(store, s, p, o, locator, edition, quote); }

    /** A claim with a date, as a read files a dated marriage. */
    private static void claim(LibraryStore store, String s, String p, String o, String date, String locator, String edition, String quote) throws Exception {
        String sentence = s + " " + p.replace('-', ' ') + " " + o + " (" + date + ").";
        store.write(new Finding(store.nextFindingId(sentence), sentence, List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.low, "family-account", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source(locator, edition, "")), List.of(), null, sentence + "\n\nThe account says: \"" + quote + "\"\nDate: " + date + "\n",
                new Finding.Triple(s, p, o), List.of()));
    }

    private static Finding claimOf(LibraryStore store, String s, String p, String o, String locator, String edition, String quote) throws Exception {
        String sentence = s + " " + p.replace('-', ' ') + " " + o + ".";
        Finding f = new Finding(store.nextFindingId(sentence), sentence, List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.low, "family-account", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source(locator, edition, "")), List.of(), null, sentence + "\n\nThe account says: \"" + quote + "\"\n",
                new Finding.Triple(s, p, o), List.of());
        store.write(f);
        return f;
    }

    /**
     * The library as a read of the family's folder leaves it: the notes captured and marked as read with the folder, the facts read from
     * them told by their writer, and the accounts of the pages the owner keeps a list of, told by the owner.
     */
    /**
     * {@code how}: "folder", the notes read with the family's folder; "file", read on their own; "by", read on their own with another writer
     * named (--by); "credited", read with the folder, whose other notes name a writer for them.
     */
    private static LibraryStore family(Path tmp, String how) throws Exception { return family(tmp, how, NOTES); }

    private static LibraryStore family(Path tmp, String how, String notesText) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib"));
        store.init();
        FamilyPeople.holdsAFamily(store);
        Path folder = Files.createDirectories(tmp.resolve("family"));
        Path notes = folder.resolve("notes.txt");
        Files.writeString(notes, notesText, StandardCharsets.UTF_8);
        String loc = "file://" + notes.toAbsolutePath().normalize();
        RawCapture.capture(store, loc, notesText, "notes", "corpus:family", "family");
        switch (how) {
            case "folder" -> FamilyFolder.markRead(store, notes, FamilyReads.FILE, folder.toString());
            case "file" -> FamilyReads.readFile(store, notes, FamilyReads.FILE, "", "", "");
            case "by" -> FamilyReads.readFile(store, notes, FamilyReads.FILE, "", "Emiko Takahashi", "");
            case "credited" -> {
                Path credits = folder.resolve("credits.txt");
                Files.writeString(credits, "notes.txt was written by Emiko Takahashi\n", StandardCharsets.UTF_8);
                FamilyFolder.markRead(store, notes, FamilyReads.FILE, folder.toString());
                FamilyFolder.markRead(store, credits, FamilyReads.FILE, folder.toString());
            }
            default -> throw new IllegalArgumentException(how);
        }
        String by = how.equals("by") ? "as told by Emiko Takahashi" : "as told by the writer of notes.txt";
        claim(store, "森田健二", "born-on", "1973", loc, by, "i am kenji morita - 森田健二  born 1973");
        claim(store, "森田勇", "parent-of", "森田健二", loc, by, "my father is 森田勇 (Isamu Morita)");
        claim(store, "森田勇", "sex", "male", loc, by, "my father is 森田勇 (Isamu Morita)");
        claim(store, "森田ハル", "parent-of", "森田健二", loc, by, "my mother is haru morita (formerly haru endo) 森田ハル formerly 遠藤ハル");
        claim(store, "森田ハル", "sex", "female", loc, by, "my mother is haru morita (formerly haru endo) 森田ハル formerly 遠藤ハル");
        claim(store, "森田修", "sibling-of", "森田健二", loc, by, "my brother is osamu morita 森田修 - born 1976");
        claim(store, "森田修", "sex", "male", loc, by, "my brother is osamu morita 森田修 - born 1976");
        claim(store, "森田正一", "parent-of", "森田勇", loc, by, "森田正一 is my dad's father (so my grandfather)");
        claim(store, "森田正一", "sex", "male", loc, by, "森田正一 is my dad's father (so my grandfather)");
        claim(store, "Hanae Endo", "relative-of", "森田健二", loc, by, "Hanae Endo is my father's cousin");
        claim(store, "森田健吾", "sex", "male", loc, by, "森田健吾 is 森田正一's younger brother");
        // the reader files the mother's grandfather as a relative of the mother, and the great grandfather said two ways as a relative of the owner
        claim(store, "遠藤源三郎", "relative-of", "森田ハル", loc, by, "遠藤源三郎 - my mother's grandfather");
        claim(store, "森田源太", "relative-of", "森田健二", loc, by, "森田源太 is my great grandfather (my father's father's father)");
        claim(store, "森田良子", "sibling-of", "森田勇", loc, by, "森田良子 is my father's sister");
        // the aunt's own records: born a Morita, daughter of 森田正一, married into the Endō family in 1966; the register writes her married name too
        String register = "https://example.org/register/morita";
        claim(store, "森田良子", "born-on", "1941", register, "the register", "森田良子、昭和十六年生。");
        claim(store, "森田良子", "sex", "female", register, "the register", "森田良子、女。");
        claim(store, "森田良子", "child-of", "森田正一", register, "the register", "森田正一の長女 森田良子。");
        claim(store, "森田良子", "married-to", "遠藤健吾", "1966", register, "the register", "昭和四十一年 遠藤健吾と婚姻。");
        claim(store, "遠藤健吾", "sex", "male", register, "the register", "遠藤健吾、男。");
        Graph.alias(store, "森田良子", List.of("遠藤良子"), register);
        // a family tree site writes the owner in Latin letters
        claim(store, "Kenji Morita", "born-on", "1973", "https://www.geni.com/people/Kenji-Morita/1", "the family tree site", "Kenji Morita, born 1973");
        // what the pages the owner keeps a list of say, as the owner's notes beside them tell whose page it is
        String page = "https://example.org/morita-shop";
        claim(store, "the owner of this library", "lived-in", "Leeds", page, OWNER, "the owner of this library lived in Leeds");
        claim(store, "the owner of this library's father", "occupation", "shopkeeper", page, OWNER, "About the Morita shop: my father ran it");
        claim(store, "the owner of this library's mother", "occupation", "teacher", page, OWNER, "About the Morita shop: my mother taught");
        claim(store, "the owner of this library's father's father", "occupation", "founder of the shop", page, OWNER, "About the Morita shop: my paternal grandfather founded it");
        claim(store, "the owner of this library's brother", "lived-in", "Kobe", page, OWNER, "About the Morita shop: my brother lives in Kobe");
        // a claim the reader filed the wrong way round, whose words give a woman's sex to "the owner of this library's father": the
        // description's own word says he is a man, and the words of a stray claim do not make him a woman
        claim(store, "Yoko Morita", "child-of", "the owner of this library's father", page, OWNER, "About the Morita shop: Yoko Morita's mother kept the books");
        claim(store, "the owner of this library's mother's grandfather", "occupation", "miller", page, OWNER, "About the Morita shop: my mother's grandfather milled the flour");
        claim(store, "the owner of this library's father's father's father", "occupation", "farmer", page, OWNER, "About the Morita shop: my father's father's father farmed");
        // what the owner wrote beside three pages' addresses, filed as "About <the page's subject>: <the words>": each description is the page's subject
        claim(store, "Ichiro Hale", "relative-of", "the owner of this library's mother's uncle", "told://link-note/https://example.org/hale-ichiro", OWNER, "About Ichiro Hale - The Hale Family Site: my mother's uncle");
        claim(store, "the owner of this library's mother's uncle's father", "relative-of", "Tom Hale", "told://link-note/https://example.org/hale-tom", OWNER, "I think this is my mother's uncle's father");
        claim(store, "Yoko Morita", "relative-of", "the owner of this library's father's father's younger brother", "told://link-note/https://example.org/morita-yoko", OWNER, "About Yoko Morita: my father's father's younger brother's daughter");
        claim(store, "the owner of this library's father's sister", "lived-in", "Kobe", "told://link-note/https://example.org/morita-yoshiko", OWNER, "About 森田良子 - Wikipedia: my father's sister, who lived in Kobe");
        claim(store, "森田良子", "sibling-of", "the owner of this library's father", "told://link-note/https://example.org/morita-yoshiko", OWNER, "About 森田良子 - Wikipedia: my father's sister, who lived in Kobe");
        return store;
    }

    @Test
    void aNoteThatAPageIsTheFathersCousinFiledAsHisRelativeLeavesTheFatherAlone(@TempDir Path tmp) throws Exception {
        // beside a page about somebody else: "i think this is also my father's cousin", which the reader filed as a relative of the father
        LibraryStore store = family(tmp, "folder");
        claim(store, "the owner of this library's father's cousin", "relative-of", "the owner of this library's father", "told://link-note/https://example.org/cousin-page", OWNER, "i think this is also my father's cousin");
        Graph g = FamilyPeople.view(store);
        assertNotEquals(entry(g, "the owner of this library's father"), entry(g, "the owner of this library's father's cousin"),
                "his cousin is not he: " + FamilyLinks.current(store).links().stream().map(FamilyLinks::sentence).toList());
    }

    @Test
    void anAuntTheOwnersNotesDescribeKeepsHerNamesOverALifeWhenTheDescriptionJoinsHer(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp, "folder");
        Graph g = FamilyPeople.view(store);
        String aunt = entry(g, "森田良子");
        assertEquals(aunt, entry(g, "the owner of this library's father's sister"), "your notes call her your father's sister: the description is she: " + FamilyLinks.current(store).links().stream().map(FamilyLinks::sentence).toList());
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        List<FamilyNameHistory.Name> ns = idx.names(aunt);
        FamilyNameHistory.Name birth = idx.birth(aunt);
        assertNotNull(birth, "her name at birth, from her father's family name: " + ns);
        assertEquals("森田良子", birth.written());
        assertEquals(1941, birth.from().year(), ns.toString());
        assertEquals(1966, birth.to().year(), ns.toString());
        FamilyNameHistory.Name married = idx.latest(aunt);
        assertEquals("遠藤良子", married.written(), ns.toString());
        assertEquals("marriage", married.kind(), "taken at her marriage into the Endō family: " + ns);
        assertEquals(1966, married.from().year(), ns.toString());
    }

    private static String entry(Graph g, String name) { return g.nodeIdOf(name); }

    @Test
    void theOwnersNotesSayingANameIsAlsoWrittenAnotherWayJoinTwoNamesOneCharacterApart(@TempDir Path tmp) throws Exception {
        // a family tree page writes the mother's grandfather with one character different, and gives his parents and birth year
        String tree = "https://example.org/endo-family";
        String treeSays = "遠藤源四郎、明治三年生。父 遠藤玄斎。";
        // without the owner's word the two stay two people: one character apart, kept apart until the family says otherwise
        LibraryStore apart = family(tmp.resolve("apart"), "folder");
        claim(apart, "遠藤源四郎", "born-on", "1870", tree, "a family tree page", treeSays);
        claim(apart, "遠藤玄斎", "parent-of", "遠藤源四郎", tree, "a family tree page", treeSays);
        // a book that writes the same words is nobody's word for the family
        claim(apart, "遠藤源三郎", "lived-in", "Kumamoto", "file:///family/history.txt", "as told by the writer of history.txt", "遠藤源三郎, also written 遠藤源四郎, lived in Kumamoto");
        Graph g = FamilyPeople.view(apart);
        assertNotEquals(entry(g, "遠藤源三郎"), entry(g, "遠藤源四郎"));
        // the owner's notes say it: one man, proved, and his parent and birth year are the mother's grandfather's
        LibraryStore one = family(tmp.resolve("one"), "folder", NOTES.replace("遠藤源三郎 - my mother's grandfather", "遠藤源三郎 - my mother's grandfather. also written 遠藤源四郎"));
        claim(one, "遠藤源四郎", "born-on", "1870", tree, "a family tree page", treeSays);
        claim(one, "遠藤玄斎", "parent-of", "遠藤源四郎", tree, "a family tree page", treeSays);
        Graph h = FamilyPeople.view(one);
        assertEquals(entry(h, "遠藤源三郎"), entry(h, "遠藤源四郎"), "your notes say the two names are one man's");
        assertEquals(entry(h, "遠藤源三郎"), entry(h, "the owner of this library's mother's grandfather"));
        assertEquals("遠藤源三郎", h.node(entry(h, "遠藤源四郎")).label(), "the joined entry is headed with the owner's own spelling");
        FamilyClose.Close close = FamilyClose.of(h);
        assertEquals("your mother's parent's father's father", close.said(entry(h, "遠藤玄斎")), "his father is the mother's great-grandfather now");
    }

    @Test
    void theOwnersOwnNotesSayWhoTheOwnerIsAndEachRelativeTheyDescribeIsTheNamedPerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp, "folder");
        Graph g = FamilyPeople.view(store);
        String you = entry(g, "森田健二");
        assertEquals(you, entry(g, "the owner of this library"), "you are 森田健二: " + FamilyLinks.current(store).links().stream().map(FamilyLinks::sentence).toList());
        assertEquals(you, entry(g, "Kenji Morita"), "and Kenji Morita, as the same line writes you");
        assertEquals(entry(g, "森田勇"), entry(g, "the owner of this library's father"), "your father");
        assertNotEquals(entry(g, "Hanae Endo"), entry(g, "森田勇"), "your father's cousin is not your father");
        assertEquals(entry(g, "森田ハル"), entry(g, "the owner of this library's mother"), "your mother");
        assertEquals(entry(g, "森田正一"), entry(g, "the owner of this library's father's father"), "your father's father");
        assertEquals(entry(g, "森田修"), entry(g, "the owner of this library's brother"), "your brother, the one brother your notes name");
        assertEquals(entry(g, "Ichiro Hale"), entry(g, "the owner of this library's mother's uncle"), "the note beside his page calls him your mother's uncle, and was filed as about him");
        assertEquals(entry(g, "Tom Hale"), entry(g, "the owner of this library's mother's uncle's father"), "the note beside his page, hedged");
        FamilyLinks.Link tom = FamilyLinks.current(store).links().stream().filter(x -> x.rule().equals("L4") && x.why().contains("I think this is my mother's uncle's father")).findFirst().orElseThrow();
        assertEquals(FamilyLinks.Grade.probable, tom.grade(), "\"I think\": probable, not proved");
        assertEquals(entry(g, "森田健吾"), entry(g, "the owner of this library's father's father's younger brother"),
                "your notes say 森田健吾 is 森田正一's younger brother, and 森田正一 your father's father: the walk takes the step your notes state");
        assertNotEquals(entry(g, "Yoko Morita"), entry(g, "the owner of this library's father's father's younger brother"), "the note beside her page calls her his daughter, not him");
        assertEquals(entry(g, "遠藤源三郎"), entry(g, "the owner of this library's mother's grandfather"), "your notes call him your mother's grandfather, filed as a relative of your mother: the steps go on from her");
        assertEquals(entry(g, "森田源太"), entry(g, "the owner of this library's father's father's father"), "said two ways in one line, which agree");
        FamilyLinks.Link self = FamilyLinks.current(store).links().stream().filter(l -> l.why().startsWith("Your own notes say")).findFirst().orElseThrow();
        assertEquals("L2", self.rule());
        assertEquals(FamilyLinks.Grade.proved, self.grade());

        FamilyClose.Close close = FamilyClose.of(g);
        assertEquals("you", close.said(you));
        assertEquals("your father", close.said(entry(g, "森田勇")));
        assertEquals("your mother", close.said(entry(g, "森田ハル")));
        assertEquals("your brother", close.said(entry(g, "森田修")));
        assertEquals("your father's father", close.said(entry(g, "森田正一")));
        long yous = g.nodes().stream().filter(n -> "person".equals(n.kind()) && "you".equals(close.said(n.id()))).count();
        assertEquals(1, yous, "the owner once, not also as a brother or sister of their own");
        assertTrue(g.nodes().stream().noneMatch(n -> "person".equals(n.kind()) && n.label().toLowerCase().startsWith("the owner of this library")),
                "no relative is left under a description with no name: " + g.nodes().stream().filter(n -> "person".equals(n.kind())).map(Graph.Node::label).toList());
    }

    @Test
    void theNotesReadOnTheirOwnAreTheOwnersTooAndTheSummaryOpensWithTheOwnerOnce(@TempDir Path tmp) throws Exception {
        // taking the notes back and reading them again on their own is the usual way to refresh them: how they were read makes no difference
        LibraryStore alone = family(tmp.resolve("alone"), "file");
        Graph g = FamilyPeople.view(alone);
        assertEquals(entry(g, "森田健二"), entry(g, "the owner of this library"), "read on their own, the notes are still the owner's: " + FamilyLinks.current(alone).links().stream().map(FamilyLinks::sentence).toList());
        assertEquals(entry(g, "森田勇"), entry(g, "the owner of this library's father"));
        assertEquals(entry(g, "森田ハル"), entry(g, "the owner of this library's mother"));
        FamilySummary.Summary s = FamilySummary.of(alone, false);
        assertTrue(s.ownerKnown());
        List<FamilySummary.Person> people = s.groups().stream().flatMap(x -> x.people().stream()).toList();
        assertEquals(1, people.stream().filter(x -> x.relation().equals("you")).count(), "the owner once: " + people.stream().map(x -> x.heading() + " — " + x.relation()).toList());
        assertEquals(1, people.stream().filter(x -> x.relation().equals("your father")).count(), people.stream().map(x -> x.heading() + " — " + x.relation()).toList().toString());
        assertTrue(people.stream().anyMatch(x -> x.relation().equals("your father") && x.heading().contains("森田勇")), "a named father");
        assertTrue(people.stream().anyMatch(x -> x.relation().equals("your mother") && x.heading().contains("森田ハル")), "a named mother");
        assertTrue(people.stream().noneMatch(x -> x.heading().toLowerCase().startsWith("the owner of this library")), "nobody left as a description");
        String text = FamilySummary.text(s);
        assertTrue(text.contains("(your notes"), "the notes read on their own are labelled as yours: " + text.lines().filter(l -> l.contains("notes")).findFirst().orElse(""));
        assertFalse(text.contains("the writer of notes.txt's notes"), text);
    }

    @Test
    void notesAnotherWriterIsNamedForOrALineThatNamesTwoPeopleSayNothingOfTheOwner(@TempDir Path tmp) throws Exception {
        LibraryStore by = family(tmp.resolve("by"), "by");
        Graph gb = FamilyPeople.view(by);
        assertNotEquals(entry(gb, "森田健二"), entry(gb, "the owner of this library"), "read as Emiko Takahashi's account, the \"i am\" is hers");
        String byLoc = "file://" + tmp.resolve("by").resolve("family").resolve("notes.txt").toAbsolutePath().normalize();
        assertFalse(FamilyFolder.ownerNotes(by).is(byLoc), "the one rule the summary labels by says the same");
        assertTrue(FamilyFolder.ownerNotes(family(tmp.resolve("file"), "file")).is("file://" + tmp.resolve("file").resolve("family").resolve("notes.txt").toAbsolutePath().normalize()));

        LibraryStore credited = family(tmp.resolve("credited"), "credited");
        Graph gc = FamilyPeople.view(credited);
        assertNotEquals(entry(gc, "森田健二"), entry(gc, "the owner of this library"), "the folder's other notes name a writer for the file");

        LibraryStore two = family(tmp.resolve("two"), "folder");
        Path more = Files.createDirectories(tmp.resolve("two").resolve("family")).resolve("more-notes.txt");
        String text = "I'm Emiko Takahashi, and these are my notes too.\n";
        Files.writeString(more, text, StandardCharsets.UTF_8);
        String loc = "file://" + more.toAbsolutePath().normalize();
        RawCapture.capture(two, loc, text, "more notes", "corpus:family", "family");
        FamilyFolder.markRead(two, more, FamilyReads.FILE, more.getParent().toString());
        claim(two, "Emiko Takahashi", "born-on", "1950", loc, "as told by the writer of more-notes.txt", "I'm Emiko Takahashi, and these are my notes too.");
        Graph t = FamilyPeople.view(two);
        assertNotEquals(entry(t, "森田健二"), entry(t, "the owner of this library"), "two notes that each say \"I am\" of another person say nothing");
        assertNotEquals(entry(t, "Emiko Takahashi"), entry(t, "the owner of this library"));
    }

    @Test
    void theSameWordsBesideTwoPagesGoToEachPagesSubjectAndJoinNeither(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp, "folder");
        Finding a = claimOf(store, "the owner of this library's father's cousin", "relative-of", "Hanae Endo", "told://link-note/https://example.org/endo-hanae", OWNER, "About Hanae Endo: my father's cousin");
        Finding b = claimOf(store, "the owner of this library's father's cousin", "relative-of", "Kenzo Endo", "told://link-note/https://example.org/endo-kenzo", OWNER, "About Kenzo Endo: my father's cousin too");
        Graph g = FamilyPeople.view(store);
        assertEquals(entry(g, "Hanae Endo"), g.nodeOf(store.finding(a.id()), true), "beside her page, the words are she");
        assertEquals(entry(g, "Kenzo Endo"), g.nodeOf(store.finding(b.id()), true), "beside his page, the words are he");
        assertNotEquals(entry(g, "Hanae Endo"), entry(g, "Kenzo Endo"), "two cousins, not one person");
        String d = entry(g, "the owner of this library's father's cousin");
        assertNotEquals(entry(g, "Hanae Endo"), d);
        assertNotEquals(entry(g, "Kenzo Endo"), d);
    }

    @Test
    void aNoteBesideAPageAboutSomebodyTheOwnersWordsPlaceElsewhereLinksNothing(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp, "folder");
        // a page about a namesake of the owner's father, with the owner's doubt beside it: the father is not his own cousin, and the page is left as it is
        claim(store, "the owner of this library's father's uncle", "relative-of", "森田勇", "told://link-note/https://example.org/morita-isamu", OWNER, "I think this is my father's uncle");
        Graph g = FamilyPeople.view(store);
        assertNotEquals(entry(g, "森田勇"), entry(g, "the owner of this library's father's uncle"), "your own notes call 森田勇 your father");
        assertEquals(entry(g, "森田勇"), entry(g, "the owner of this library's father"));
    }

    @Test
    void theWaysAPersonSaysWhoTheyAreGiveTheNamesOfTheLine() {
        assertEquals(List.of("kenji morita", "森田健二"), FamilyLinks.selfNames("i am kenji morita - 森田健二  born 1973"));
        assertEquals(List.of("Kenji Morita"), FamilyLinks.selfNames("I'm Kenji Morita, the elder son."));
        assertEquals(List.of("Kenji Morita"), FamilyLinks.selfNames("My name is Kenji Morita."));
        assertEquals(List.of("Kenji Morita"), FamilyLinks.selfNames("- Kenji Morita (me) b. 1973"));
        assertEquals(List.of("森田健二"), FamilyLinks.selfNames("私は森田健二です。"));
        assertEquals(List.of("森田健二"), FamilyLinks.selfNames("私の名前は森田健二。"));
        assertEquals(List.of(), FamilyLinks.selfNames("my father is 森田勇"));
        assertEquals(List.of("森田健吾", "森田正一", "younger brother"), List.of(FamilyLinks.statedLine("森田健吾 is 森田正一's younger brother")));
        assertEquals(List.of("森田健吾", "森田正一", "younger brother"), List.of(FamilyLinks.statedLine("森田健吾は森田正一の弟です。")));
        assertNull(FamilyLinks.statedLine("森田正一 is my dad's father (so my grandfather)"), "from the owner, not from a named person");
        assertEquals(List.of("mother", "uncle"), FamilyLinks.noteSteps("About Ichiro Hale: my mother's uncle"));
        assertEquals(List.of("father", "father", "father"), FamilyLinks.oneChain("森田源太 is my great grandfather (my father's father's father) on my father's side"));
        assertNull(FamilyLinks.oneChain("my father is 森田勇 and my mother is 森田ハル"), "two relations");
        assertEquals(List.of("father", "father", "younger brother", "daughter"), FamilyLinks.noteSteps("my father's father's younger brother's daughter"));
        assertEquals(List.of("father", "father"), FamilyLinks.atoms(List.of("paternal grandfather")));
        assertEquals(List.of("father", "father"), FamilyLinks.atoms(List.of("dad", "father")));
        assertEquals(List.of(FamilyLinks.GRAND_PARENT, FamilyLinks.GRAND_PARENT, "mother"), FamilyLinks.atoms(List.of("great grandmother")));
    }
}
