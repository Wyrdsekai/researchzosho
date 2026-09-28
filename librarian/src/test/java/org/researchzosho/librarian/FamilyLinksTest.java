package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Who is who from the evidence ({@link FamilyLinks}), with invented families. Each rule links where the evidence says so, and each has a
 * case where it must not: a wrong join hides a person and moves their facts onto somebody else, so it is worse than a split.
 */
class FamilyLinksTest {

    private static final String BOOK = "file:///family/morita-book.txt";
    private static final String LETTERS = "file:///family/letters.txt";
    private static final String TREE = "file:///family/tree-notes.txt";
    private static final String PAGE = "https://ja.wikipedia.org/wiki/morita";

    private static LibraryStore family(Path tmp) throws Exception {
        LibraryStore s = new LibraryStore(tmp.resolve("lib"));
        s.init();
        FamilyPeople.holdsAFamily(s);
        return s;
    }

    /** A claim of the family's own reading, as a read files it: a draft from one source. */
    private static Finding claim(LibraryStore store, String s, String p, String o, String source) throws Exception {
        String sentence = s + " " + p.replace('-', ' ') + " " + o + ".";
        Finding f = new Finding(store.nextFindingId(sentence), sentence, List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.low, "family-account", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source(source, "as told by an aunt", "")), List.of(), null, sentence + "\n\nThe account says: \"" + sentence + "\"\n",
                new Finding.Triple(s, p, o), List.of());
        store.write(f);
        return f;
    }

    private static String entry(LibraryStore store, String name) throws IOException { return FamilyPeople.view(store).nodeIdOf(name); }

    private static String side(LibraryStore store, Finding f, boolean subject) throws IOException { return FamilyPeople.view(store).nodeOf(f, subject); }

    private static List<FamilyLinks.Link> links(LibraryStore store) throws IOException { return FamilyLinks.current(store).links(); }

    private static boolean joined(LibraryStore store, String a, String b) throws IOException { return entry(store, a).equals(entry(store, b)); }

    // ── L1: one claim gives both forms ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void aNameAPersonCarriedIsTheEntryOfThatNameWhenOneTextGivesBothOrAFactAgrees(@TempDir Path tmp) throws Exception {
        LibraryStore book = family(tmp.resolve("book"));
        claim(book, "Morita Kenji", "born-on", "1905", BOOK);
        claim(book, "Morita Kenji", "has-name", "name: Endo Kenji", BOOK);
        claim(book, "Endo Kenji", "born-in", "Kure", BOOK);
        assertTrue(joined(book, "Endo Kenji", "Morita Kenji"), "the book says Morita Kenji carried the name Endo Kenji, and writes Endo Kenji: one man of that name in one text");

        LibraryStore name = family(tmp.resolve("name"));
        claim(name, "Morita Kenji", "born-on", "1905", BOOK);
        claim(name, "Morita Kenji", "has-name", "name: Endo Kenji", BOOK);
        claim(name, "Endo Kenji", "born-in", "Kure", LETTERS);
        assertFalse(joined(name, "Endo Kenji", "Morita Kenji"), "the letters' Endo Kenji shares only the name: possible, and joined to nothing");
        claim(name, "Endo Kenji", "born-on", "1905", LETTERS);
        assertTrue(joined(name, "Endo Kenji", "Morita Kenji"), "born the same year: a second fact agrees");
        assertTrue(links(name).stream().anyMatch(l -> l.rule().equals("L1") && l.grade() == FamilyLinks.Grade.probable && l.joins()));

        LibraryStore apart = family(tmp.resolve("apart"));
        claim(apart, "Morita Kenji", "born-on", "1905", BOOK);
        claim(apart, "Morita Kenji", "has-name", "name: Endo Kenji", BOOK);
        claim(apart, "Endo Kenji", "born-on", "1905", BOOK);
        claim(apart, "Endo Kenji", "parent-of", "Morita Kenji", LETTERS);
        assertFalse(joined(apart, "Endo Kenji", "Morita Kenji"), "a claim that one is the other's parent says they are two, whatever else agrees");
    }

    @Test
    void theSameNameWrittenAnotherWayJoinsButJuniorAndSeniorAndClashingYearsStayTwo(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "Kenji Morita", "born-in", "Kure", BOOK);
        claim(store, "Morita, Kenji", "occupation", "shopkeeper", LETTERS);
        assertFalse(joined(store, "Kenji Morita", "Morita, Kenji"), "two sources and the name alone agree: possible only");
        claim(store, "Kenji Morita", "born-on", "1905", BOOK);
        claim(store, "Morita, Kenji", "born-on", "1905", LETTERS);
        claim(store, "Tom Hale, Jr.", "born-in", "York", BOOK);
        claim(store, "Tom Hale, Sr.", "born-in", "Leeds", BOOK);
        claim(store, "Ichiro Morita", "born-on", "1905", BOOK);
        claim(store, "Morita, Ichiro", "born-on", "1931", BOOK);
        assertTrue(joined(store, "Kenji Morita", "Morita, Kenji"), "an index writes the family name first, with a comma, and the birth year agrees");
        assertFalse(joined(store, "Tom Hale, Jr.", "Tom Hale, Sr."), "Jr. and Sr. tell a son and a father apart");
        assertFalse(joined(store, "Ichiro Morita", "Morita, Ichiro"), "birth years 26 apart are two people");
    }

    // ── L6: a reading across scripts ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void aReadingIsPossibleAloneProbableWithOneFactAndProvedWithTwo(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "森田健二", "born-on", "1905", BOOK);
        claim(store, "Kenji Morita", "occupation", "shopkeeper", LETTERS);
        Graph.alias(store, "森田健二", List.of("もりた けんじ"));
        assertFalse(joined(store, "森田健二", "Kenji Morita"), "a reading that matches, and nothing else: possible, joined to nothing");
        assertTrue(links(store).stream().anyMatch(l -> l.rule().equals("L6") && l.grade() == FamilyLinks.Grade.possible));
        Graph g = FamilyPeople.view(store);
        assertTrue(FamilyLinks.about(store, g, g.nodeIdOf("森田健二")).stream().anyMatch(s -> s.startsWith("May be the same person as")), "shown as may be the same person");

        claim(store, "Kenji Morita", "born-on", "1905", TREE);
        assertTrue(joined(store, "森田健二", "Kenji Morita"), "the same birth year agrees: probable, and joined");
        assertTrue(links(store).stream().anyMatch(l -> l.rule().equals("L6") && l.grade() == FamilyLinks.Grade.probable && l.joins()));
        assertEquals("森田健二", FamilyPeople.view(store).node(entry(store, "Kenji Morita")).label(), "the entry is shown under the full name in characters");

        claim(store, "森田健二", "child-of", "森田一郎", BOOK);
        claim(store, "Kenji Morita", "child-of", "森田一郎", TREE);
        assertFalse(links(store).stream().anyMatch(l -> l.rule().equals("L6") && l.grade() == FamilyLinks.Grade.proved), "a father known by his name alone is no second fact");
        claim(store, "森田一郎", "born-on", "1870", BOOK);
        claim(store, "森田一郎", "born-on", "1870", TREE);
        assertTrue(links(store).stream().anyMatch(l -> l.rule().equals("L6") && l.grade() == FamilyLinks.Grade.proved), "a birth year, and a father two sources give the same year: proved");
    }

    @Test
    void aReadingOfTheGivenNameAloneOrClashingYearsJoinNothing(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "森田健二", "born-on", "1905", BOOK);
        claim(store, "Kenji Morita", "born-on", "1905", LETTERS);
        Graph.alias(store, "森田健二", List.of("けんじ"));
        assertFalse(joined(store, "森田健二", "Kenji Morita"), "けんじ reads the given name only, not the whole name");

        LibraryStore years = family(tmp.resolve("years"));
        claim(years, "森田健二", "born-on", "1905", BOOK);
        claim(years, "Kenji Morita", "born-on", "1950", LETTERS);
        Graph.alias(years, "森田健二", List.of("もりた けんじ"));
        assertFalse(joined(years, "森田健二", "Kenji Morita"), "the reading matches, and the birth years are 45 apart");
        assertTrue(links(years).stream().noneMatch(l -> l.rule().equals("L6")), "a block holds for a possible link too");
    }

    // ── L4 and L2: a page's subject, and the owner's own note ──────────────────────────────────────────────────────────

    @Test
    void aLinkedPageIsThePersonItsTitleNamesAndTheOwnersNoteDescribesThemToo(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        String reader = "森田健二 - Wikipedia (ja.wikipedia.org) — the person who keeps this library notes: \"my father's father\"";
        claim(store, reader, "married-to", "森田ハル", PAGE);
        claim(store, "森田健二", "born-on", "1905", BOOK);
        claim(store, "the owner of this library's father's father", "occupation", "shopkeeper", "told://notes");
        assertTrue(joined(store, reader, "森田健二"), "the page is about the person its title names");
        assertTrue(joined(store, "the owner of this library's father's father", "森田健二"), "the note beside the page and the owner's own words describe one relative");
        assertEquals(entry(store, "森田健二"), side(store, store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().subject().equals(reader)).findFirst().orElseThrow(), true));

        LibraryStore nobody = family(tmp.resolve("nobody"));
        String other = "遠藤一郎 - Wikipedia (ja.wikipedia.org)";
        claim(nobody, other, "married-to", "遠藤ハル", PAGE);
        claim(nobody, "森田健二", "born-on", "1905", BOOK);
        assertNotEquals(entry(nobody, other), entry(nobody, "森田健二"), "a page whose title names nobody in the library is linked to nobody");
    }

    // ── L3: a description with one answer ──────────────────────────────────────────────────────────────────────────────

    @Test
    void aDescriptionWalksTheRelationsTheEvidenceEstablishes(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "Morita Kenji", "born-on", "1905", BOOK);
        claim(store, "Morita Kenji", "child-of", "Morita Isamu", BOOK);
        claim(store, "Morita Kenji", "child-of", "Morita Isamu", LETTERS);
        claim(store, "Morita Isamu", "sex", "male", BOOK);
        claim(store, "Morita Kenji", "parent-of", "Morita Aya", BOOK);
        claim(store, "Morita Kenji", "parent-of", "Morita Aya", LETTERS);
        claim(store, "Morita Aya", "sex", "female", BOOK);
        claim(store, "Morita Kenji's father", "occupation", "carpenter", TREE);
        claim(store, "Morita Kenji's daughter", "occupation", "teacher", TREE);
        assertTrue(joined(store, "Morita Kenji's father", "Morita Isamu"), "two sources give his father, and a man has one");
        assertFalse(joined(store, "Morita Kenji's daughter", "Morita Aya"), "one daughter known is no sign he had one: the walk stops at a daughter");

        LibraryStore once = family(tmp.resolve("once"));
        claim(once, "Morita Kenji", "child-of", "Morita Isamu", BOOK);
        claim(once, "Morita Isamu", "sex", "male", BOOK);
        claim(once, "Morita Kenji's father", "occupation", "carpenter", TREE);
        assertFalse(joined(once, "Morita Kenji's father", "Morita Isamu"), "one text's word for the father is not enough to carry a description to him");
    }

    // ── L5: a short form ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aGivenNameAloneIsTheOnePersonTheSameSourceNamesInFullAndNobodyWhenTwoOrNone(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "Morita Kenji", "married-to", "Morita Haru", BOOK);
        Finding shop = claim(store, "Kenji", "occupation", "shopkeeper", BOOK);
        claim(store, "Morita Kenji", "born-on", "1905", LETTERS);
        claim(store, "Endo Kenji", "born-on", "1911", LETTERS);
        Finding two = claim(store, "Kenji", "occupation", "carpenter", LETTERS);
        Finding none = claim(store, "Kenji", "occupation", "farmer", TREE);
        assertEquals(entry(store, "Morita Kenji"), side(store, shop, true), "the book names one Kenji in full");
        assertEquals(Vocabulary.norm("Kenji"), side(store, two, true), "the letters name two Kenjis: nobody, until the family or the model says");
        assertEquals(Vocabulary.norm("Kenji"), side(store, none, true), "the notes name no Kenji in full: it stays as written");
        assertTrue(FamilyLinks.current(store).open().stream().anyMatch(o -> o.claim().equals(two.id()) && o.candidates().size() == 2), "the two it could be are kept for later");
    }

    @Test
    void aNameAloneRelatedTheSameWayIsThePersonRelatedSoAndNobodyWhenTwoAre(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "森田健二", "born-on", "1905", BOOK);
        Finding wife = claim(store, "ハル", "married-to", "森田健二", PAGE);
        claim(store, "森田ハル", "married-to", "森田健二", LETTERS);
        assertEquals(entry(store, "森田ハル"), side(store, wife, true), "his wife ハル is 森田ハル, whom another source gives as his wife");

        LibraryStore two = family(tmp.resolve("two"));
        claim(two, "森田健二", "born-on", "1905", BOOK);
        Finding w = claim(two, "ハル", "married-to", "森田健二", PAGE);
        claim(two, "森田ハル", "married-to", "森田健二", LETTERS);
        claim(two, "遠藤ハル", "married-to", "森田健二", TREE);
        assertEquals(Vocabulary.norm("ハル"), side(two, w, true), "two wives of his are written with ハル: nobody");
    }

    @Test
    void aFamilyNameAloneNeverJoinsTwoPeopleNorHoldsTheirFullNames(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "Morita Kenji", "born-on", "1905", BOOK);
        claim(store, "Morita Ichiro", "born-on", "1931", BOOK);
        claim(store, "Morita", "occupation", "shopkeeper", BOOK);
        claim(store, "Morita", "sex", "male", BOOK);
        // a read once gave the family name alone both men's names as its own other names
        Graph.alias(store, "Morita", List.of("Morita Kenji", "Morita Ichiro"), BOOK);
        claim(store, "Morita Kenji", "lived-in", "Kure", LETTERS);
        claim(store, "Morita Ichiro", "lived-in", "Hiroshima", LETTERS);
        Graph g = FamilyPeople.view(store);
        assertNotEquals(g.nodeIdOf("Morita Kenji"), g.nodeIdOf("Morita Ichiro"), "two men, not one entry called Morita");
        Graph.Node alone = g.node(Vocabulary.norm("Morita"));
        assertNotNull(alone, "what the book writes with the family name alone stays as written");
        assertFalse(alone.aliases().contains("Morita Kenji") || alone.aliases().contains("Morita Ichiro"), "and holds neither man's name");
        assertTrue(g.edges().stream().anyMatch(e -> e.from().equals(g.nodeIdOf("Morita Kenji")) && e.predicate().equals("lived-in")), "Kure is Kenji's");

        // the person's own list of names is theirs: an entry they filed under one word keeps its whole name, and the name leads to it
        LibraryStore own = family(tmp.resolve("own"));
        claim(own, "勇", "born-on", "1880", BOOK);
        Graph.alias(own, "勇", List.of("森田勇"));
        claim(own, "森田勇", "died-on", "1940", LETTERS);
        assertEquals(entry(own, "勇"), entry(own, "森田勇"));
    }

    // ── L8 and L9 ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void namesThatDifferByOneCharacterAreNeverJoined(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "森田健二", "born-on", "1905", BOOK);
        claim(store, "森田健吾", "born-on", "1905", LETTERS);
        claim(store, "森田健二", "child-of", "森田一郎", BOOK);
        claim(store, "森田健吾", "child-of", "森田一郎", LETTERS);
        assertFalse(joined(store, "森田健二", "森田健吾"), "one character differs: two people until the family says otherwise");
        assertTrue(links(store).stream().anyMatch(l -> l.rule().equals("L8") && !l.joins()), "shown as may be the same person");
    }

    @Test
    void aTitleComesOffAndMrsBeforeAMansNameIsHisWife(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "Viscount Morita Kenji", "occupation", "banker", BOOK);
        claim(store, "Morita Kenji", "born-on", "1905", BOOK);
        claim(store, "Morita Haru", "married-to", "Morita Kenji", LETTERS);
        claim(store, "Haru", "sex", "female", BOOK);
        Finding haru = claim(store, "Haru", "has-name", "name: Mrs. Kenji Morita", BOOK);
        assertTrue(joined(store, "Viscount Morita Kenji", "Morita Kenji"), "a rank is no part of the name");
        assertEquals(entry(store, "Morita Haru"), side(store, haru, true), "Mrs. Kenji Morita is his wife, and he has one");

        LibraryStore two = family(tmp.resolve("two"));
        claim(two, "Morita Kenji", "born-on", "1905", LETTERS);
        claim(two, "Morita Haru", "married-to", "Morita Kenji", LETTERS);
        claim(two, "Endo Aya", "married-to", "Morita Kenji", TREE);
        claim(two, "Haru", "sex", "female", BOOK);
        Finding h = claim(two, "Haru", "has-name", "name: Mrs. Kenji Morita", BOOK);
        assertEquals(Vocabulary.norm("Haru"), side(two, h, true), "two wives: the form of address says which neither is");
    }

    // ── the blocks, and the person's word ──────────────────────────────────────────────────────────────────────────────

    @Test
    void differentSexesADeathBeforeTheBirthAndTheFamilysTwoPeopleKeepTheSameNameApart(@TempDir Path tmp) throws Exception {
        LibraryStore sexes = family(tmp.resolve("sexes"));
        claim(sexes, "Kenji Morita", "sex", "male", BOOK);
        claim(sexes, "Morita, Kenji", "sex", "female", BOOK);
        assertFalse(joined(sexes, "Kenji Morita", "Morita, Kenji"), "a man and a woman");

        LibraryStore died = family(tmp.resolve("died"));
        claim(died, "Kenji Morita", "died-on", "1890", BOOK);
        claim(died, "Morita, Kenji", "born-on", "1905", BOOK);
        assertFalse(joined(died, "Kenji Morita", "Morita, Kenji"), "dead before the other was born");

        LibraryStore told = family(tmp.resolve("told"));
        claim(told, "Kenji Morita", "born-on", "1905", BOOK);
        claim(told, "Morita, Kenji", "born-on", "1905", LETTERS);
        assertTrue(joined(told, "Kenji Morita", "Morita, Kenji"));
        Graph.different(told, "Kenji Morita", "Morita, Kenji", "person", "two cousins of one name");
        assertFalse(joined(told, "Kenji Morita", "Morita, Kenji"), "the family said two people, and that takes the link back");
        claim(told, "Kenji Morita", "died-on", "1970", TREE);
        assertFalse(joined(told, "Kenji Morita", "Morita, Kenji"), "and it stays taken back when the links are worked out again");
    }

    @Test
    void aSplitMovesALinkedMentionAndItStaysApart(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "Morita Kenji", "married-to", "Morita Haru", BOOK);
        Finding shop = claim(store, "Kenji", "occupation", "shopkeeper", BOOK);
        assertEquals(entry(store, "Morita Kenji"), side(store, shop, true));
        FamilySplit.Outcome o = FamilySplit.split(store, "Morita Kenji", "Kenji (the shopkeeper)", List.of(shop.id()));
        assertEquals(1, o.moved(), "the claim is found through its link");
        Finding moved = store.finding(shop.id());
        assertNotEquals(entry(store, "Morita Kenji"), side(store, moved, true), "and the split holds when the links are worked out again");
    }

    // ── where the links live ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void theCoreGraphIsTheSameWithAndWithoutTheLinks(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "Morita Kenji", "married-to", "Morita Haru", BOOK);
        claim(store, "Kenji", "occupation", "shopkeeper", BOOK);
        claim(store, "Kenji Morita", "born-in", "Kure", LETTERS);
        String before = core(store);
        FamilyLinks.update(store);
        assertTrue(Files.exists(FamilyLinks.file(store)));
        assertEquals(before, core(store), "the core graph never reads genealogy's links");
        assertNotEquals(core(store), view(store), "genealogy's own view does");
        Files.delete(FamilyLinks.file(store));
        assertEquals(before, core(store));
    }

    private static String core(LibraryStore store) throws IOException { return render(Graph.build(store)); }

    private static String view(LibraryStore store) throws IOException { return render(FamilyPeople.view(store)); }

    private static String render(Graph g) {
        List<String> out = new ArrayList<>();
        for (Graph.Node n : g.nodes()) out.add(n.id() + "|" + n.kind() + "|" + n.label() + "|" + n.aliases());
        for (Graph.Edge e : g.edges()) out.add(e.from() + " " + e.predicate() + " " + e.to() + " " + e.findingId());
        out.sort(null);
        return String.join("\n", out);
    }

    @Test
    void theLinksAreWorkedOutAgainWhenTheClaimsChangeAndNeverInALibraryWithoutFamilyWork(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "Morita Kenji", "married-to", "Morita Haru", BOOK);
        FamilyLinks.update(store);
        String was = Files.readString(FamilyLinks.file(store), StandardCharsets.UTF_8);
        Finding shop = claim(store, "Kenji", "occupation", "shopkeeper", BOOK);
        assertEquals(entry(store, "Morita Kenji"), side(store, shop, true), "the new claim is linked: a stale file is never read");
        String now = Files.readString(FamilyLinks.file(store), StandardCharsets.UTF_8);
        assertNotEquals(was, now);
        assertTrue(now.contains(shop.id()));

        LibraryStore plain = new LibraryStore(tmp.resolve("plain"));
        plain.init();
        claim(plain, "Morita Kenji", "born-on", "1905", BOOK);
        claim(plain, "Kenji", "occupation", "shopkeeper", BOOK);
        assertNull(FamilyPeople.view(plain).links(), "a library that holds no family work has no links");
        assertFalse(Files.exists(plain.root().resolve("family")), "and no family folder is made for them");
    }
}
