package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Names over a life, worked out from the name claims: the heading, the name in a year, the periods, what outranks what, and a library from
 * before names were claims. Invented names only.
 */
class FamilyNameHistoryTest {

    static LibraryStore store(Path tmp) throws Exception {
        LibraryStore s = new LibraryStore(tmp.resolve("lib")); s.init();
        return s;
    }

    /** A read filed as it stands, from one source. */
    static FamilyAccount.Outcome file(LibraryStore store, String locator, List<FamilyAccount.Fact> facts, List<FamilyAccount.NameRead> names) throws Exception {
        return FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), facts, List.of(), List.of(), names, List.of()), "an aunt", f -> List.of(locator), f -> List.of());
    }

    static FamilyAccount.NameRead name(String person, String name, String family, String given, String kind, String date, String quote, String... forms) {
        return new FamilyAccount.NameRead(person, name, family, given, List.of(forms), kind, "", date, quote);
    }

    static FamilyNameHistory.Index index(LibraryStore store) throws Exception { return FamilyNameHistory.of(FamilyPeople.view(store)); }

    static String id(LibraryStore store, String name) throws Exception { return FamilyPeople.view(store).nodeIdOf(name); }

    @Test
    void aWomanWhoTookHerHusbandsNameIsHeadedWithHerBirthNameAndCarriedItBeforeTheMarriage(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/notes.txt", List.of(
                        new FamilyAccount.Fact("Mary Ellis", "married-to", "John Ellis", "1875", "Mary Hale married John Ellis in 1875."),
                        new FamilyAccount.Fact("Mary Ellis", "sex", "female", "", "Mary Hale married John Ellis in 1875."),
                        new FamilyAccount.Fact("John Ellis", "sex", "male", "", "Mary Hale married John Ellis in 1875.")),
                List.of(name("Mary Ellis", "Mary Hale", "Hale", "Mary", "birth", "1850", "Mary Hale was born in 1850.")));
        FamilyNameHistory.Index idx = index(store);
        String mary = id(store, "Mary Ellis");
        assertEquals("Mary Ellis (born Hale)", idx.heading(mary));
        assertEquals("Mary Hale", idx.at(mary, 1870).written());
        FamilyNameHistory.Name married = idx.latest(mary);
        assertEquals("Mary Ellis", married.written());
        assertEquals("marriage", married.kind(), "worked out from the marriage to a man of that family name");
        assertTrue(married.workedOut() && married.claims().isEmpty(), "and shown as worked out, never filed");
        assertEquals(1875, married.from().year());
        assertEquals(1875, idx.birth(mary).to().year(), "the birth name ends where the married name begins");
        assertEquals("Mary Ellis", idx.nameAt(mary, 1880, null));
        assertEquals("Mary Hale", idx.nameAt(mary, 1860, "Mary Ellis"), "a dated line reads the name she carried then");
    }

    @Test
    void aManWhoEnteredHisWifesFamilyAs婿養子IsHeadedWithHisBirthFamily(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        String q = "In 1932 he entered the Morita family as mukoyōshi (婿養子) of Isamu.";
        file(store, "file:///family/book.txt", List.of(new FamilyAccount.Fact("森田健二", "adopted-by", "森田勇", "1932", q)),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。", "えんどう けんじ"),
                        name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "1932", q, "もりた けんじ", "Morita Kenji")));
        FamilyNameHistory.Index idx = index(store);
        String kenji = id(store, "森田健二");
        assertEquals(kenji, id(store, "遠藤健二"), "the birth name is a name of the same person, from one read");
        assertEquals(kenji, id(store, "Morita Kenji"));
        assertEquals("森田健二 (born 遠藤)", idx.heading(kenji));
        assertEquals("遠藤健二", idx.at(kenji, 1920).written(), "a 1920 line reads 遠藤健二");
        assertEquals("森田健二", idx.at(kenji, 1940).written());
        List<FamilyNameHistory.Name> names = idx.names(kenji);
        assertEquals(2, names.size(), names.toString());
        assertEquals("birth", names.get(0).kind());
        assertEquals(1905, names.get(0).from().year());
        assertEquals(1932, names.get(0).to().year());
        FamilyNameHistory.Name later = names.get(1);
        assertEquals("mukoyoshi", later.kind());
        Finding adoption = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("adopted-by")).findFirst().orElseThrow();
        assertEquals(adoption.id(), later.event(), "the name's event is the adoption claim");
        assertTrue(later.isForm("Kenji Morita") && later.isForm("モリタ ケンジ"), "its forms: " + later.forms());
        assertFalse(later.isForm("遠藤健二"));
        assertArrayEquals(new String[]{"森田", "健二"}, idx.parts(kenji, "森田健二"));
    }

    @Test
    void anHeirshipAndTheNameTakenUnderAWillKeepTheirTwoDates(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/hart.txt", List.of(new FamilyAccount.Fact("John Hart", "heir-of", "Tom Hart", "1783", "In 1783 John Hale became the heir of his cousin Tom Hart.")),
                List.of(name("John Hart", "John Hale", "Hale", "John", "birth", "1767", "John Hale was born in 1767."),
                        new FamilyAccount.NameRead("John Hart", "John Hart", "Hart", "John", List.of(), "legal", "the name clause of Tom Hart's will", "1812", "In 1812, under the name clause of Tom Hart's will, he took the name Hart.")));
        FamilyNameHistory.Index idx = index(store);
        String john = id(store, "John Hart");
        FamilyNameHistory.Name taken = idx.latest(john);
        assertEquals("legal", taken.kind());
        assertEquals(1812, taken.from().year(), "the name from the will's clause, not from the heirship");
        assertEquals("the name clause of Tom Hart's will", taken.said());
        Finding heir = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("heir-of")).findFirst().orElseThrow();
        assertEquals(1783, FamilyChecks.claimDate(heir).year(), "the heirship keeps its own date");
        assertEquals("John Hale", idx.at(john, 1790).written(), "an heir from 1783 was still John Hale in 1790");
        assertEquals("John Hart (born Hale)", idx.heading(john));
    }

    @Test
    void aNameTakenBackAfterADivorceIsAPeriodOfItsOwnAndANameAlongsideEndsNone(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/ann.txt", List.of(),
                List.of(name("Ann Hale", "Ann Hale", "Hale", "Ann", "birth", "1850", "Ann Hale was born in 1850."),
                        name("Ann Hale", "Ann Ellis", "Ellis", "Ann", "marriage", "1872", "She married Tom Ellis in 1872 and was Ann Ellis."),
                        name("Ann Hale", "Ann Hale", "Hale", "Ann", "taken-back", "1890", "After the divorce in 1890 she took back the name Hale."),
                        name("Ann Hale", "Ann Hart", "Hart", "Ann", "aka", "1880", "From 1880 she sang on the stage as Ann Hart.")));
        FamilyNameHistory.Index idx = index(store);
        String ann = id(store, "Ann Hale");
        assertEquals(4, idx.names(ann).size(), idx.names(ann).toString());
        assertEquals("birth", idx.at(ann, 1860).kind());
        assertEquals("Ann Ellis", idx.at(ann, 1885).written(), "the stage name from 1880 ended nothing");
        assertEquals("taken-back", idx.at(ann, 1895).kind());
        assertEquals("taken-back", idx.latest(ann).kind());
        FamilyNameHistory.Name stage = idx.names(ann).stream().filter(n -> n.kind().equals("aka")).findFirst().orElseThrow();
        assertNull(stage.to(), "a name alongside the others has no end of its own");
        assertEquals(1890, idx.names(ann).stream().filter(n -> n.kind().equals("marriage")).findFirst().orElseThrow().to().year());
    }

    @Test
    void whereNothingIsDatedTheYearSaysNothingAndALineKeepsItsOwnWords(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/tom.txt", List.of(new FamilyAccount.Fact("Tom Hale", "lived-in", "York", "", "Tom Hale lived in York.")),
                List.of(name("Tom Hale", "Tom Hart", "Hart", "Tom", "birth", "", "Tom Hale was born Tom Hart."),
                        name("Tom Hale", "Tom Hale", "Hale", "Tom", "", "", "He was later known as Tom Hale.")));
        FamilyNameHistory.Index idx = index(store);
        String tom = id(store, "Tom Hale");
        assertNull(idx.at(tom, 1900), "no name holds a year the evidence does not give");
        assertEquals("Tom Hart", idx.nameAt(tom, 1900, "Tom Hart"), "a line written under one of his names keeps it");
        assertEquals("Tom Hale", idx.nameAt(tom, 1900, "somebody else"), "else the latest name");
        assertEquals("unknown", idx.latest(tom).kind(), "a name whose kind the source does not give is of a kind not known yet");
    }

    @Test
    void aDisputedNameCountsForNothingAndTakesBackTheOtherNamesItGave(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        file(store, "file:///family/kenji.txt", List.of(new FamilyAccount.Fact("森田健二", "lived-in", "広島", "", "森田健二は広島に住んだ。")),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。", "えんどう けんじ")));
        String kenji = id(store, "森田健二");
        assertEquals(kenji, id(store, "えんどう けんじ"));
        Finding claim = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("has-name")).findFirst().orElseThrow();
        new Council(store).dispute(claim.id(), "he was born 森田; the book confused him with his cousin");
        FamilyNameHistory.Index idx = index(store);
        assertTrue(idx.names(kenji).stream().noneMatch(n -> n.written().equals("遠藤健二") && !n.implicit()), "the disputed name claim is no name of his: " + idx.names(kenji));
        assertNull(idx.birth(kenji));
        String nodes = Files.readString(Graph.nodesFile(store));
        assertFalse(nodes.contains("えんどう けんじ"), "the forms it gave him are taken back: " + nodes);
        assertNotEquals(kenji, id(store, "遠藤健二"));
    }

    @Test
    void theFamilysAcceptedAnswerOutranksABook(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        file(store, "file:///family/book.txt", List.of(), List.of(name("森田健二", "森田健二", "森田", "健二", "marriage", "1930", "森田健二は1930年に結婚して森田姓となった。")));
        FamilyAccount.Outcome told = file(store, "told://family-answer/a1b2c3", List.of(), List.of(name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "1932", "婿養子 in 1932, the family says.")));
        assertEquals(1, told.names());
        Finding answer = store.scanFindings().findings().stream().filter(f -> f.sources().stream().anyMatch(s -> s.locator().startsWith("told://family-answer/"))).findFirst().orElseThrow();
        new Council(store).accept(answer.id());
        FamilyNameHistory.Name n = index(store).latest(id(store, "森田健二"));
        assertEquals("mukoyoshi", n.kind(), "the family's word: " + n);
        assertEquals(1932, n.from().year());
        assertTrue(n.accepted());
        assertEquals(2, n.claims().size(), "both claims give the one name");
        assertEquals(answer.id(), n.claims().get(0), "the family's answer first");
    }

    @Test
    void onlyARecordDatesAName(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/book.txt", List.of(new FamilyAccount.Fact("森田健二", "life-event", "went to the village school", "1920", "Morita Kenji went to the village school in 1920.")),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "", "健二は遠藤家に生まれた。"),
                        name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "", "健二は森田家に婿養子として入った。")));
        file(store, "cite:広島県 除籍簿 entry 3", List.of(new FamilyAccount.Fact("遠藤健二", "born-in", "広島県安芸郡", "1905", "遠藤健二 明治三十八年 広島県安芸郡に生る")), List.of());
        file(store, "cite:森田商店 directory 1935", List.of(new FamilyAccount.Fact("森田健二", "occupation", "shopkeeper", "1935", "森田健二 商店主")), List.of());
        FamilyNameHistory.Index idx = index(store);
        String kenji = id(store, "森田健二");
        FamilyNameHistory.Name born = idx.birth(kenji), later = idx.latest(kenji);
        assertArrayEquals(new int[]{1905, 1905}, idx.useWindow(kenji, born), "the register writes him 遠藤健二 in 1905");
        assertArrayEquals(new int[]{1935, 1935}, idx.useWindow(kenji, later), "the directory writes him 森田健二 in 1935, and the book's 1920 is no record");
        assertEquals("遠藤健二", idx.at(kenji, 1905).written());
        assertNull(idx.at(kenji, 1920), "the book writes him Morita in 1920, which says nothing about when the name changed");
    }

    @Test
    void anOlderLibrarysOtherNameUnderAnotherFamilyIsAnImplicitNameOfAKindNotKnownYet(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田健二", "もりた けんじ", List.of("遠藤健二"))),
                List.of(new FamilyAccount.Fact("森田健二", "born-in", "広島県安芸郡", "1905", "q")), List.of()), "file:///family/old.txt", "an aunt");
        FamilyNameHistory.Index idx = index(store);
        String kenji = id(store, "森田健二");
        List<FamilyNameHistory.Name> names = idx.names(kenji);
        assertEquals(2, names.size(), names.toString());
        assertTrue(names.stream().allMatch(FamilyNameHistory.Name::implicit), "nothing was filed: " + names);
        FamilyNameHistory.Name label = names.stream().filter(n -> n.written().equals("森田健二")).findFirst().orElseThrow();
        assertTrue(label.isForm("もりた けんじ"), "the reading is a form of the label's name");
        FamilyNameHistory.Name other = names.stream().filter(n -> n.written().equals("遠藤健二")).findFirst().orElseThrow();
        assertEquals("unknown", other.kind());
        assertEquals("森田健二", idx.heading(kenji), "no birth name, so nothing beside the heading");
        assertEquals(1, store.scanFindings().findings().size(), "and nothing was rewritten");
    }

    @Test
    void theKindOfAResearchRunsNameIsReadFromItsWords() {
        assertEquals("birth", FamilyNameHistory.kindFromWords("maiden name"));
        assertEquals("marriage", FamilyNameHistory.kindFromWords("married name"));
        assertEquals("art", FamilyNameHistory.kindFromWords("pen name"));
        assertEquals("unknown", FamilyNameHistory.kindFromWords("took the name"));
        assertTrue(FamilyNameHistory.saysKind("清は大正三年に山本家の婿養子となり", "mukoyoshi"));
        assertFalse(FamilyNameHistory.saysKind("He entered the Morita family in 1932.", "mukoyoshi"), "the words must say it");
        assertTrue(FamilyNameHistory.replaces("mukoyoshi") && !FamilyNameHistory.replaces("aka"));
        assertEquals("森田健二 was named 遠藤健二 at birth (1905).", FamilyNameHistory.claimSentence("森田健二", "遠藤健二", "birth", "1905"));
    }

    // ── the defects found in review and in a real model's read: one test each, invented names ──

    static String nodesLine(LibraryStore store, String label) throws Exception {
        return Files.readString(Graph.nodesFile(store)).lines().filter(l -> l.contains("— person: " + label)).findFirst().orElse("");
    }

    static String aliasRows(LibraryStore store) throws Exception {
        return Files.exists(Graph.aliasSourcesFile(store)) ? Files.readString(Graph.aliasSourcesFile(store)) : "";
    }

    /** names-1: a dispute of one name claim takes back only what that claim alone gave; an older source's other name stays with its row. */
    @Test
    void disputingANameClaimKeepsTheSameOtherNameAnOlderSourceGave(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田健二", "", List.of("遠藤健二"))),
                List.of(new FamilyAccount.Fact("森田健二", "born-in", "広島県安芸郡", "1905", "森田健二（旧姓 遠藤）は広島県安芸郡に生まれた。")), List.of()), "file:///family/old.txt", "an aunt");
        file(store, "file:///family/book.txt", List.of(), List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1906", "健二は1906年に遠藤家に生まれた。")));
        Finding claim = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("has-name")).findFirst().orElseThrow();
        new Council(store).dispute(claim.id(), "the book has the year wrong: he was born in 1905");
        assertTrue(nodesLine(store, "森田健二").contains("遠藤健二"), "old.txt gave the other name before the claim, so it stays: " + nodesLine(store, "森田健二"));
        assertTrue(aliasRows(store).contains("森田健二\t遠藤健二\tfile:///family/old.txt"), "with old.txt's row: " + aliasRows(store));
        assertEquals(id(store, "森田健二"), id(store, "遠藤健二"), "and the name still finds him");
    }

    /** boundary-2: a spelling a tree site gave and one the owner typed stay when a later tree file's name claim that also lists them is disputed. */
    @Test
    void disputingANameClaimKeepsASpellingAnotherSourceGaveAndOneTypedByHand(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Files.createDirectories(store.root().resolve("family"));
        accepted(store, "Morita Kenji", "child-of", "Morita Isamu", "file:///family/book.txt");
        Graph.setKind(store, "Morita Kenji", "person");
        Graph.alias(store, "Morita Kenji", List.of("Kenji Morita"), "https://www.geni.com/people/Kenji-Morita/6000000000000000002");
        Graph.alias(store, "Morita Kenji", List.of("K. Morita"));
        Finding nc = new Finding(store.nextFindingId("Morita Kenji has name Kenji Morita"), "Morita Kenji was also known as Kenji Morita.", List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.low, "family-account", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source("file:///family/other-tree.ged", "a tree file", "")), List.of(), null, "Morita Kenji was also known as Kenji Morita.\n",
                new Finding.Triple("Morita Kenji", "has-name", "name: Kenji Morita"), List.of(FamilyDetail.note(Map.of("kind", "aka", "forms", "Kenji Morita@ja-Latn,K. Morita@en"), "family-account")));
        store.write(nc);
        new Council(store).dispute(nc.id(), "that tree file is somebody else's");
        String line = nodesLine(store, "Morita Kenji");
        assertTrue(line.contains("Kenji Morita") && line.contains("K. Morita"), "both spellings stay: " + line);
        assertTrue(aliasRows(store).contains("geni.com"), "and the tree site's row: " + aliasRows(store));
    }

    /** boundary-1: an ordinary claim worded like a name ("also known as", "pen name") is not genealogy's; its dispute leaves the owner's other names alone. */
    @Test
    void disputingAnOrdinaryClaimWordedLikeANameLeavesTheOwnersOtherNamesAlone(@TempDir Path tmp) throws Exception {
        for (boolean off : new boolean[]{false, true}) {
            LibraryStore store = store(tmp.resolve(off ? "off" : "on"));
            new LibrarianIndex(store, Embeddings.none()).rebuild();
            Files.createDirectories(store.root().resolve("family"));
            store.write(new Investigation("I-0100-notes", "Notes on string quartets", Finding.State.accepted, "model:research", Instant.now().toString(), List.of(), List.of(), "Notes.\n"));
            String[][] claims = {{"The Ellis Quartet", "also known as", "the Ellises"}, {"Tom Hale", "pen name", "T. H. Hart"}};
            List<String> ids = new ArrayList<>();
            for (String[] c : claims) {
                String line = c[0] + " " + c[1] + " " + c[2] + ".";
                Finding f = new Finding(store.nextFindingId(line), line, List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.medium, "reviewer",
                        "2026-09-01T00:00:00Z", "2026-09-01", Finding.Volatility.stable, "", List.of(new Finding.Source("https://example.org/q", "n/a", "cited by I-0100-notes")), List.of(), null,
                        line + "\n", new Finding.Triple(c[0], c[1], c[2]), List.of());
                store.write(f);
                ids.add(f.id());
            }
            Graph.alias(store, "The Ellis Quartet", List.of("the Ellises"));
            Graph.alias(store, "Tom Hale", List.of("T. H. Hart"));
            if (off) Profiles.disable(store, "genealogy");
            new Council(store).dispute(ids.get(0), "the page was about another quartet");
            new Council(store).retire(ids.get(1));
            String nodes = Files.readString(Graph.nodesFile(store));
            assertTrue(nodes.contains("also: the Ellises") && nodes.contains("also: T. H. Hart"), "genealogy " + (off ? "off" : "on") + ", the owner's other names stay: " + nodes);
            Graph core = Graph.build(store);
            assertEquals(core.nodeIdOf("The Ellis Quartet"), core.nodeIdOf("the Ellises"));
        }
    }

    /** names-2: a dated later name holds after its year though the entry's own name has no claim, and its bearers are found then. */
    @Test
    void aDatedLaterNameHoldsAfterItsYearBesideTheEntrysUnclaimedName(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/book.txt", List.of(new FamilyAccount.Fact("森田正二", "lived-in", "広島", "", "正二は広島に住んだ。")),
                List.of(name("森田正二", "髙橋正二", "髙橋", "正二", "adoptive", "1940", "正二は1940年に髙橋家の養子となり髙橋正二となった。")));
        FamilyNameHistory.Index idx = index(store);
        String shoji = id(store, "森田正二");
        assertNotNull(idx.at(shoji, 1945), "the name in 1945: " + idx.names(shoji));
        assertEquals("髙橋正二", idx.at(shoji, 1945).written());
        assertTrue(idx.bearers("髙橋", 1945).contains(shoji), "he bore 髙橋 in 1945: " + idx.bearers("髙橋", 1945));
        // a name given only its end is ordered by it, before the name that began then
        LibraryStore s2 = store(tmp.resolve("b"));
        FamilyNameHistory.file(s2, name("Mary Ellis", "Mary Hale", "Hale", "Mary", "", "", "She was Mary Hale until 1875."), List.of(new Finding.Source("file:///family/a.txt", "a", "")), "family-account", List.of(), Map.of("to", "1875"));
        file(s2, "file:///family/a.txt", List.of(), List.of(name("Mary Ellis", "Mary Ellis", "Ellis", "Mary", "marriage", "1875", "She took the name Mary Ellis on her marriage in 1875.")));
        FamilyNameHistory.Index i2 = index(s2);
        String mary = id(s2, "Mary Ellis");
        assertEquals(List.of("Mary Hale", "Mary Ellis"), i2.names(mary).stream().map(FamilyNameHistory.Name::written).toList());
        assertNotNull(i2.at(mary, 1880), "the name in 1880: " + i2.names(mary));
        assertEquals("Mary Ellis", i2.at(mary, 1880).written());
    }

    static String gedcom(LibraryStore store, Path tmp, String text) throws Exception {
        Path ged = tmp.resolve("tree-" + Math.abs(text.hashCode()) + ".ged");
        Files.writeString(ged, text);
        Gedcom.importFile(store, ged);
        return ged.toString();
    }

    /** names-3: two married names a tree types as married but does not date are dated by the two marriages, and the heading is the later one. */
    @Test
    void marriedNamesATreeTypesButDoesNotDateAreDatedByTheirMarriages(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        gedcom(store, tmp, "0 HEAD\n1 GEDC\n2 VERS 5.5.1\n1 CHAR UTF-8\n0 @I1@ INDI\n1 NAME Mary /Hale/\n2 TYPE birth\n1 NAME Mary /Ellis/\n2 TYPE married\n1 NAME Mary /Hart/\n2 TYPE married\n"
                + "1 SEX F\n1 BIRT\n2 DATE 1850\n1 RESI\n2 DATE 1880\n2 PLAC Leeds\n1 FAMS @F1@\n1 FAMS @F2@\n0 @I2@ INDI\n1 NAME Tom /Ellis/\n1 SEX M\n1 BIRT\n2 DATE 1845\n1 FAMS @F1@\n"
                + "0 @I3@ INDI\n1 NAME John /Hart/\n1 SEX M\n1 BIRT\n2 DATE 1848\n1 FAMS @F2@\n0 @F1@ FAM\n1 HUSB @I2@\n1 WIFE @I1@\n1 MARR\n2 DATE 1875\n0 @F2@ FAM\n1 HUSB @I3@\n1 WIFE @I1@\n1 MARR\n2 DATE 1890\n0 TRLR\n");
        FamilyNameHistory.Index idx = index(store);
        String mary = id(store, "Mary Hale");
        assertEquals("Mary Hart (born Hale)", idx.heading(mary), idx.names(mary).toString());
        assertEquals("Mary Hale", idx.at(mary, 1860).written());
        assertEquals("Mary Ellis", idx.at(mary, 1880).written());
        assertEquals("Mary Hart", idx.at(mary, 1895).written());
        FamilyNameHistory.Name ellis = idx.names(mary).stream().filter(n -> n.written().equals("Mary Ellis")).findFirst().orElseThrow();
        assertTrue(ellis.workedOut() && ellis.dated() && ellis.explained(), "its year is worked out from the marriage and shown as such: " + ellis);
    }

    /** gedcom-8: a married name typed as married, whose marriage the file dates and links, gets that year and no question asks for it. */
    @Test
    void aTypedMarriedNameGetsTheYearOfItsMarriageAndNoWhenQuestion(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        gedcom(store, tmp, "0 HEAD\n1 GEDC\n2 VERS 5.5.1\n1 CHAR UTF-8\n0 @I1@ INDI\n1 NAME Mary /Hale/\n2 TYPE birth\n1 NAME Mary /Ellis/\n2 TYPE married\n1 SEX F\n1 BIRT\n2 DATE 1860\n1 FAMS @F1@\n"
                + "0 @I2@ INDI\n1 NAME Tom /Ellis/\n1 SEX M\n1 FAMS @F1@\n0 @F1@ FAM\n1 HUSB @I2@\n1 WIFE @I1@\n1 MARR\n2 DATE 1884\n0 TRLR\n");
        FamilyNameHistory.Index idx = index(store);
        String mary = id(store, "Mary Hale");
        FamilyNameHistory.Name ellis = idx.latest(mary);
        assertEquals("Mary Ellis", ellis.written());
        assertNotNull(ellis.from(), "dated by the marriage: " + ellis);
        assertEquals(1884, ellis.from().year());
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(q -> q.kind().equals("name-change-when")), "and nobody is asked the year: " + FamilyNameQuestions.open(store));
    }

    /** names-4: a second source that gives the kind of a name the first left open is a claim of its own, and its kind counts. */
    @Test
    void aSecondSourceThatGivesTheKindOfANameIsAClaimOfItsOwn(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/book.txt", List.of(), List.of(name("森田健二", "森田健二", "森田", "健二", "", "1932", "In 1932 he entered the Morita family and was 森田健二 from then on.")));
        file(store, "cite:森田家 除籍簿", List.of(), List.of(name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "1932", "昭和七年 森田勇の婿養子となる 森田健二")));
        List<Finding> claims = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("has-name")).toList();
        assertEquals(2, claims.size(), "the register's reading is kept: " + claims.stream().map(FamilyDetail::of).toList());
        assertEquals("mukoyoshi", index(store).latest(id(store, "森田健二")).kind());
        // and a deed poll that gives another kind is never the record behind a book's marriage
        LibraryStore s2 = store(tmp.resolve("b"));
        file(s2, "file:///family/book.txt", List.of(), List.of(name("Mary Ellis", "Mary Ellis", "Ellis", "Mary", "marriage", "1875", "She took the name Mary Ellis on her marriage in 1875.")));
        file(s2, "cite:deed poll 1880", List.of(), List.of(name("Mary Ellis", "Mary Ellis", "Ellis", "Mary", "legal", "", "By deed poll she changed her name to Mary Ellis.")));
        Finding marriage = s2.scanFindings().findings().stream().filter(f -> f.triple() != null && FamilyDetail.get(f, "kind").equals("marriage")).findFirst().orElseThrow();
        assertEquals(List.of("file:///family/book.txt"), marriage.sources().stream().map(Finding.Source::locator).toList());
    }

    /** names-5: names in scripts other than characters, kana and Latin letters group, are worked out and bear their family names. */
    @Test
    void namesInOtherScriptsGroupAndAreWorkedOut(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        String q = "Мэри Хейл вышла замуж за Тома Эллиса в 1875.";
        file(store, "file:///family/notes.txt", List.of(new FamilyAccount.Fact("Мэри Эллис", "married-to", "Том Эллис", "1875", q),
                        new FamilyAccount.Fact("Мэри Эллис", "sex", "female", "", q), new FamilyAccount.Fact("Том Эллис", "sex", "male", "", q)),
                List.of(name("Мэри Эллис", "Мэри Хейл", "Хейл", "Мэри", "birth", "1850", "Мэри Хейл родилась в 1850.")));
        file(store, "cite:register 12", List.of(), List.of(name("Мэри Эллис", "Мэри Хейл", "Хейл", "Мэри", "birth", "1851", "Мэри Хейл 1851")));
        FamilyNameHistory.Index idx = index(store);
        String m = id(store, "Мэри Эллис");
        assertEquals("Мэри Эллис (born Хейл)", idx.heading(m), idx.names(m).toString());
        assertEquals(2, idx.names(m).size(), "one birth name from two sources, and the married name: " + idx.names(m));
        assertEquals("Мэри Хейл", idx.at(m, 1860).written());
        assertEquals("Мэри Эллис", idx.at(m, 1880).written());
        assertTrue(idx.bearers("Хейл", 1860).contains(m));
    }

    /** names-6: a Western name is compared without the romaji long-vowel rule: Moore and Mohre are two family names. */
    @Test
    void aWesternMarriedNameIsNotFoldedIntoTheBirthNameByTheRomajiRule(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/notes.txt", List.of(),
                List.of(name("Mary Mohre", "Mary Moore", "Moore", "Mary", "birth", "1850", "Mary Moore was born in 1850."),
                        name("Mary Mohre", "Mary Mohre", "Mohre", "Mary", "marriage", "1875", "On her marriage in 1875 she took the name Mary Mohre.")));
        FamilyNameHistory.Index idx = index(store);
        String mary = id(store, "Mary Mohre");
        assertEquals(2, idx.names(mary).size(), idx.names(mary).toString());
        assertEquals("Mary Mohre (born Moore)", idx.heading(mary));
        assertEquals("Mary Mohre", idx.at(mary, 1880).written());
        assertFalse(idx.bearers("Mohre", 1860).contains(mary), "a Moore in 1860 is no Mohre");
    }

    /** gedcom-13: an English name's forms are not tagged as romaji; a Japanese name's romaji form is. */
    @Test
    void onlyANameWithAJapaneseFormTagsItsLatinFormAsRomaji(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/notes.txt", List.of(),
                List.of(name("Mary Ellis", "Mary Hale", "Hale", "Mary", "birth", "1850", "Mary Hale was born in 1850."),
                        name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "遠藤健二 (Endō Kenji) was born in 1905.", "Endō Kenji")));
        Finding hale = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().object().equals("name: Mary Hale")).findFirst().orElseThrow();
        assertFalse(FamilyDetail.of(hale).toString().contains("ja-Latn"), "an English name is no romaji: " + FamilyDetail.of(hale));
        Finding endo = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().object().equals("name: 遠藤健二")).findFirst().orElseThrow();
        assertTrue(FamilyDetail.get(endo, "forms").contains("Endō Kenji@ja-Latn"), FamilyDetail.of(endo).toString());
    }

    /** names-7: a name taken in the year of birth ends the birth name that year. */
    @Test
    void aNameTakenInTheYearOfBirthEndsTheBirthNameThatYear(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/a.txt", List.of(),
                List.of(name("森田勝", "遠藤勝", "遠藤", "勝", "birth", "1905", "勝は1905年に遠藤家に生まれた。"),
                        name("森田勝", "森田勝", "森田", "勝", "adoptive", "1905", "同じ1905年に森田家の養子となった。")));
        FamilyNameHistory.Index idx = index(store);
        String masaru = id(store, "森田勝");
        assertEquals("森田勝", idx.at(masaru, 1910).written(), idx.names(masaru).toString());
        assertEquals("森田勝", idx.at(masaru, 1950).written());
        assertNotNull(idx.birth(masaru).to(), "the birth name ends: " + idx.birth(masaru));
        assertEquals(1905, idx.birth(masaru).to().year());
        assertFalse(idx.bearers("遠藤", 1950).contains(masaru));
    }

    /** names-8: a person whose given name is a family name does not bear that family name. */
    @Test
    void aGivenNameThatIsAFamilyNameIsNotBorneAsAFamilyName(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/a.txt", List.of(new FamilyAccount.Fact("Ellis Hale", "born-on", "1850", "", "Ellis Hale was born in 1850.")),
                List.of(name("Ellis Hale", "Ellis Hale", "Hale", "Ellis", "birth", "1850", "Ellis Hale was born in 1850.")));
        FamilyNameHistory.Index idx = index(store);
        String e = id(store, "Ellis Hale");
        assertFalse(idx.names(e).get(0).hasFamily("Ellis"));
        assertEquals(List.of(), idx.bearers("Ellis", 1880));
        assertEquals(List.of(e), idx.bearers("Hale", 1880));
    }

    /** names-9: a wife's name is worked out against her husband's name AT the marriage: one he took later dates nothing of hers. */
    @Test
    void aWifesNameIsWorkedOutAgainstHerHusbandsNameAtTheMarriage(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        String q = "In 1800 John Hale married Ann Ellis.";
        file(store, "file:///family/hart.txt", List.of(new FamilyAccount.Fact("John Hart", "married-to", "Ann Hart", "1800", q),
                        new FamilyAccount.Fact("Ann Hart", "sex", "female", "", q), new FamilyAccount.Fact("John Hart", "sex", "male", "", q)),
                List.of(name("John Hart", "John Hale", "Hale", "John", "birth", "1767", "John Hale was born in 1767."),
                        new FamilyAccount.NameRead("John Hart", "John Hart", "Hart", "John", List.of(), "legal", "the name clause of Tom Hart's will", "1812", "In 1812, under the name clause of Tom Hart's will, he took the name Hart."),
                        name("Ann Hart", "Ann Ellis", "Ellis", "Ann", "birth", "1775", "Ann Ellis was born in 1775.")));
        FamilyNameHistory.Index idx = index(store);
        String ann = id(store, "Ann Hart");
        FamilyNameHistory.Name hart = idx.names(ann).stream().filter(n -> n.written().equals("Ann Hart")).findFirst().orElseThrow();
        assertFalse(hart.workedOut(), "her husband was John Hale when they married: " + hart);
        assertEquals("unknown", hart.kind());
        FamilyNameHistory.Name then = idx.at(ann, 1805);
        assertTrue(then == null || !then.written().equals("Ann Hart"), "in 1805 she was not yet known to be Ann Hart: " + then);
    }

    @Test
    void aNameOfTheFamilyOfOneParentBesideABirthParentOfAnotherCameWithThatParentNotWithTheMarriage(@TempDir Path tmp) throws Exception {
        // a tree gives Heisuke his father by birth, Taro Aoki, and his adoptive father, Goro Ueno, both as fathers; he married Kiku Ueno. His
        // name Ueno came with Goro Ueno (養子), and the marriage does not explain it
        String q = "Heisuke, born to Taro Aoki, was the son of Goro Ueno and married Kiku Ueno.";
        List<FamilyAccount.NameRead> names = List.of(name("Heisuke Ueno", "Heisuke Aoki", "Aoki", "Heisuke", "birth", "1850", "Heisuke Aoki was born in 1850."),
                name("Taro Aoki", "Taro Aoki", "Aoki", "Taro", "birth", "1820", "Taro Aoki was born in 1820."),
                name("Goro Ueno", "Goro Ueno", "Ueno", "Goro", "birth", "1815", "Goro Ueno was born in 1815."),
                name("Kiku Ueno", "Kiku Ueno", "Ueno", "Kiku", "birth", "1852", "Kiku Ueno was born in 1852."));
        LibraryStore store = store(tmp);
        file(store, "file:///family/tree.txt", List.of(new FamilyAccount.Fact("Heisuke Ueno", "child-of", "Taro Aoki", "", q), new FamilyAccount.Fact("Heisuke Ueno", "child-of", "Goro Ueno", "", q),
                new FamilyAccount.Fact("Heisuke Ueno", "married-to", "Kiku Ueno", "1870", q), new FamilyAccount.Fact("Heisuke Ueno", "sex", "male", "", q)), names);
        String heisuke = id(store, "Heisuke Ueno");
        FamilyNameHistory.Name ueno = index(store).names(heisuke).stream().filter(n -> n.written().equals("Heisuke Ueno")).findFirst().orElseThrow();
        assertNotEquals("marriage", ueno.kind(), "a father of the Ueno family beside one of the Aoki: " + ueno);

        // the same without the father of that family: the marriage explains the name
        LibraryStore wed = store(tmp.resolve("wed"));
        file(wed, "file:///family/tree.txt", List.of(new FamilyAccount.Fact("Heisuke Ueno", "child-of", "Taro Aoki", "", q),
                new FamilyAccount.Fact("Heisuke Ueno", "married-to", "Kiku Ueno", "1870", q), new FamilyAccount.Fact("Heisuke Ueno", "sex", "male", "", q)), names);
        FamilyNameHistory.Name married = index(wed).names(id(wed, "Heisuke Ueno")).stream().filter(n -> n.written().equals("Heisuke Ueno")).findFirst().orElseThrow();
        assertEquals("marriage", married.kind(), "the control: " + married);
    }

    @Test
    void aFamilyPartInCharactersIsReadAsTheLatinWordTwoPeopleOfItAgreeOn(@TempDir Path tmp) throws Exception {
        // 遠藤健二 is also written Kenji Endoh and 遠藤ハル Haru Endoh: two people of 遠藤 agree it is Endoh (Endō, Endo by the Japanese rules).
        // Rosa Endoh, married to 遠藤健二, carries his family name: her married name
        String q = "Rosa Morita married Kenji Endoh in 1940.";
        FamilyAccount.Person kenji = new FamilyAccount.Person("遠藤健二", "", List.of("Kenji Endoh"), "遠藤", "健二");
        FamilyAccount.Person haru = new FamilyAccount.Person("遠藤ハル", "", List.of("Haru Endoh"), "遠藤", "ハル");
        List<FamilyAccount.Fact> facts = List.of(new FamilyAccount.Fact("Rosa Endoh", "married-to", "遠藤健二", "1940", q), new FamilyAccount.Fact("Rosa Endoh", "sex", "female", "", q),
                new FamilyAccount.Fact("遠藤ハル", "born-on", "1912", "", "遠藤ハル、1912年生。"));
        List<FamilyAccount.NameRead> names = List.of(name("Rosa Endoh", "Rosa Morita", "Morita", "Rosa", "birth", "1915", "Rosa Morita was born in 1915."),
                name("遠藤健二", "遠藤健二", "遠藤", "健二", "birth", "1910", "遠藤健二、1910年生。"));
        LibraryStore store = store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(kenji, haru), facts, List.of(), List.of(), names, List.of()), "an aunt", f -> List.of("file:///family/letters.txt"), f -> List.of());
        FamilyNameHistory.Name endoh = index(store).names(id(store, "Rosa Endoh")).stream().filter(n -> n.written().equals("Rosa Endoh")).findFirst().orElseThrow();
        assertEquals("marriage", endoh.kind(), "her husband's family, read as the two people of it write it: " + endoh);

        // one person written both ways is not yet a reading: the other word may be the given name
        LibraryStore one = store(tmp.resolve("one"));
        FamilyAccount.fileAsRead(one, new FamilyAccount.Read(List.of(kenji), facts, List.of(), List.of(), names, List.of()), "an aunt", f -> List.of("file:///family/letters.txt"), f -> List.of());
        FamilyNameHistory.Name alone = index(one).names(id(one, "Rosa Endoh")).stream().filter(n -> n.written().equals("Rosa Endoh")).findFirst().orElseThrow();
        assertNotEquals("marriage", alone.kind(), "one person's two scripts read nothing: " + alone);
    }

    /** names-10: in an older library, romaji other names of two family names are not forms of one name, and none makes him a bearer of Endo. */
    @Test
    void romajiOtherNamesOfTwoFamilyNamesAreNotFormsOfOneName(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田健二", "", List.of("遠藤健二", "Kenji Endo", "Kenji Morita"))),
                List.of(new FamilyAccount.Fact("森田健二", "born-in", "広島県安芸郡", "1905", "q")), List.of()), "file:///family/old.txt", "an aunt");
        FamilyNameHistory.Index idx = index(store);
        String k = id(store, "森田健二");
        for (FamilyNameHistory.Name n : idx.names(k)) assertFalse(n.isForm("Kenji Endo") && n.isForm("Kenji Morita"), "two family names in one name: " + idx.names(k));
        FamilyNameHistory.Name label = idx.names(k).stream().filter(n -> n.written().equals("森田健二")).findFirst().orElseThrow();
        assertFalse(label.isForm("Kenji Endo"), "Kenji Endo is no way of writing 森田健二: " + label);
        assertFalse(idx.bearers("Endo", 1950).contains(k));
        LibraryStore s2 = store(tmp.resolve("b"));
        FamilyAccount.fileAsRead(s2, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田健二", "", List.of("Kenji Endo"))),
                List.of(new FamilyAccount.Fact("森田健二", "born-in", "広島県安芸郡", "1905", "q")), List.of()), "file:///family/old.txt", "an aunt");
        FamilyNameHistory.Index i2 = index(s2);
        String k2 = id(s2, "森田健二");
        assertFalse(i2.bearers("Endo", 1906).contains(k2), "a romaji other name alone is a clue, not the family name 遠藤 or 森田 bore");
        assertFalse(i2.bearers("Endo", 1950).contains(k2));
    }

    /** names-11: a label in romaji is headed with the birth FAMILY name, split against the later name's romaji. */
    @Test
    void aRomajiLabelIsHeadedWithTheBirthFamilyName(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/book.txt", List.of(new FamilyAccount.Fact("Morita Kenji", "life-event", "went to school", "1920", "Morita Kenji went to the village school in 1920.")), List.of());
        file(store, "file:///family/tree.txt", List.of(),
                List.of(name("Morita Kenji", "遠藤健二", "遠藤", "健二", "birth", "1905", "遠藤健二 1905", "Endo Kenji"),
                        name("Morita Kenji", "森田健二", "森田", "健二", "mukoyoshi", "1932", "婿養子 1932 森田健二", "Morita Kenji")));
        FamilyNameHistory.Index idx = index(store);
        assertEquals("Morita Kenji (born Endo)", idx.heading(id(store, "Morita Kenji")));
    }

    /** boundary-8: an older Geni label written given name first ("勇 森田") bears 森田, the family part the library knows, never 勇. */
    @Test
    void aLabelWrittenGivenNameFirstBearsTheFamilyPartTheLibraryKnows(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/a.txt", List.of(new FamilyAccount.Fact("勇 森田", "born-on", "1880", "", "Geni: 勇 森田, 1880")),
                List.of(name("森田ハル", "森田ハル", "森田", "ハル", "birth", "1910", "ハルは1910年に森田家に生まれた。")));
        FamilyNameHistory.Index idx = index(store);
        String isamu = id(store, "勇 森田");
        assertArrayEquals(new String[]{"森田", "勇"}, idx.parts(isamu, "勇 森田"));
        assertFalse(idx.bearers("勇", 1920).contains(isamu), "勇 is his given name");
        assertTrue(idx.bearers("森田", 1920).contains(isamu), "and 森田 his family name");
    }

    static FamilyAccount.Outcome fileWith(LibraryStore store, String locator, List<FamilyAccount.Fact> facts, List<FamilyAccount.NameRead> names, List<FamilyAccount.FamilyRead> families) throws Exception {
        return FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), facts, List.of(), List.of(), names, families), "an aunt", f -> List.of(locator), f -> List.of());
    }

    /** R3: the name of the family a man entered as 婿養子 is worked out as taken then, and nobody is asked how. */
    @Test
    void aNameFromTheFamilyAManEnteredIsWorkedOutFromTheEntry(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String q = "In 1932 he married Haru (森田ハル), the only daughter of Morita Isamu (森田勇), the head of the Morita family, and entered the family as mukoyōshi (婿養子) of Isamu.";
        fileWith(store, "file:///family/shop.txt", List.of(
                        new FamilyAccount.Fact("森田健二", "adopted-by", "森田勇", "1932", q, Map.of("kind", "mukoyoshi")),
                        new FamilyAccount.Fact("森田健二", "married-to", "森田ハル", "1932", q),
                        new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", q, Map.of("how", "mukoyoshi")),
                        new FamilyAccount.Fact("森田勇", "member-of", "森田家", "", q, Map.of("role", "head")),
                        new FamilyAccount.Fact("森田健二", "life-event", "went to the village school", "1912", "He went to the village school in 1912.")),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "Kenji was born in 1905 as Endō Kenji (遠藤健二).")),
                List.of(new FamilyAccount.FamilyRead("森田", "the Morita family", "", q)));
        FamilyNameHistory.Index idx = index(store);
        String kenji = id(store, "森田健二");
        FamilyNameHistory.Name later = idx.latest(kenji);
        assertEquals("森田健二", later.written());
        assertEquals("mukoyoshi", later.kind(), "worked out from entering the family: " + idx.names(kenji));
        assertEquals(1932, later.from().year());
        assertTrue(later.workedOut() && later.explained() && later.dated(), later.toString());
        assertEquals("遠藤健二", idx.at(kenji, 1912).written(), "the 1912 line reads the birth name");
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(qq -> qq.kind().equals("name-change-how") && qq.people().contains(kenji)), "nobody is asked how: " + FamilyNameQuestions.open(store).stream().map(FamilyNameQuestions.Question::text).toList());
    }

    /** R5: the first name, under a birth parent's family name, is worked out as the name at birth and is not asked about. */
    @Test
    void theFirstNameUnderABirthParentsFamilyNameIsTheBirthName(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String q = "Isamu's son Shōji (森田正二) left the Morita family in 1940, when he was adopted as heir into the Takahashi family (髙橋家), and was known afterwards as Takahashi Shōji (髙橋正二).";
        file(store, "file:///family/shop.txt", List.of(new FamilyAccount.Fact("髙橋正二", "child-of", "森田勇", "", q),
                        new FamilyAccount.Fact("森田勇", "born-on", "1880", "", "Morita Isamu (森田勇) was born in 1880.")),
                List.of(name("髙橋正二", "森田正二", "森田", "正二", "", "", q),
                        name("髙橋正二", "髙橋正二", "髙橋", "正二", "adoptive", "1940", q),
                        name("森田ハル", "森田ハル", "森田", "ハル", "birth", "1910", "ハルは1910年に森田家に生まれた。")));
        FamilyNameHistory.Index idx = index(store);
        String shoji = id(store, "髙橋正二");
        FamilyNameHistory.Name first = idx.names(shoji).get(0);
        assertEquals("森田正二", first.written());
        assertEquals("birth", first.kind(), "worked out from his father's family name: " + idx.names(shoji));
        assertTrue(first.workedOut() && first.explained(), first.toString());
        assertEquals("髙橋正二 (born 森田)", idx.heading(shoji));
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(qq -> qq.kind().equals("name-change-how") && qq.people().contains(shoji)), "nobody is asked how he came to carry his birth name: "
                + FamilyNameQuestions.open(store).stream().map(FamilyNameQuestions.Question::text).toList());
    }

    /** R5, conflicting evidence: two birth fathers of two family names settle nothing, and the name stays a question. */
    @Test
    void twoBirthParentsOfTwoFamilyNamesSettleNoBirthName(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/a.txt", List.of(new FamilyAccount.Fact("髙橋正二", "child-of", "森田勇", "", "Shōji was Isamu's son."),
                        new FamilyAccount.Fact("髙橋正二", "child-of", "遠藤正一", "", "Shōji was Shōichi's son.")),
                List.of(name("髙橋正二", "森田正二", "森田", "正二", "", "", "Shōji (森田正二)"),
                        name("髙橋正二", "遠藤正二", "遠藤", "正二", "", "", "Shōji (遠藤正二)"),
                        name("髙橋正二", "髙橋正二", "髙橋", "正二", "adoptive", "1940", "adopted in 1940 as 髙橋正二"),
                        name("森田ハル", "森田ハル", "森田", "ハル", "birth", "1910", "ハル"), name("遠藤勇", "遠藤勇", "遠藤", "勇", "birth", "1880", "勇")));
        FamilyNameHistory.Index idx = index(store);
        assertNull(idx.birth(id(store, "髙橋正二")), "two fathers, two family names: which was his at birth is asked: " + idx.names(id(store, "髙橋正二")));
    }

    /** R7: a romaji other name spelling another family part is a form of the name with that family part, not of the label's. */
    @Test
    void aRomajiOtherNameIsAFormOfTheNameWhoseFamilyPartItSpells(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        String q = "Kenji was born in 1905 as Endō Kenji (遠藤健二). From then on he was known as Morita Kenji (森田健二, もりた けんじ). The Endō family (遠藤家) and the Morita family.";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田健二", "もりた けんじ", List.of("Morita Kenji", "Endō Kenji"))),
                List.of(new FamilyAccount.Fact("森田健二", "born-on", "1905", "", q)), List.of(), List.of(),
                List.of(new FamilyAccount.NameRead("森田健二", "遠藤健二", "遠藤", "健二", List.of(), "birth", "", "1905", q)),
                List.of(new FamilyAccount.FamilyRead("遠藤", "The Endō family", "", q), new FamilyAccount.FamilyRead("森田", "the Morita family", "", q))), "an aunt", f -> List.of("file:///family/shop.txt"), f -> List.of());
        FamilyNameHistory.Index idx = index(store);
        String kenji = id(store, "森田健二");
        FamilyNameHistory.Name born = idx.birth(kenji);
        assertTrue(born.isForm("Endō Kenji"), "Endō spells 遠藤: " + idx.names(kenji));
        FamilyNameHistory.Name label = idx.names(kenji).stream().filter(n -> n.written().equals("森田健二")).findFirst().orElseThrow();
        assertFalse(label.isForm("Endō Kenji"), "and is no way of writing 森田健二: " + label);
        assertTrue(label.isForm("Morita Kenji"));
    }

    /** R8: the label a married woman is filed under is the same name as the claimed one it spells with an initial (M. Ellis). */
    @Test
    void theLabelIsTheSameNameAsAClaimedNameItSpellsWithAnInitial(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        String q = "Mary Ellis was born Mary Hale in York in 1850. She married Tom Ellis in 1875, and signed her letters \"M. Ellis\" from then on. In 1890 she married John Hart.";
        file(store, "file:///family/letter.txt", List.of(new FamilyAccount.Fact("Mary Ellis", "married-to", "Tom Ellis", "1875", q), new FamilyAccount.Fact("Mary Ellis", "married-to", "John Hart", "1890", q),
                        new FamilyAccount.Fact("Mary Ellis", "sex", "female", "", q)),
                List.of(name("Mary Ellis", "Mary Hale", "Hale", "Mary", "birth", "1850", q), name("Mary Ellis", "M. Ellis", "Ellis", "M.", "marriage", "1875", q),
                        name("Mary Ellis", "Mary Hart", "Hart", "Mary", "marriage", "1890", q)));
        FamilyNameHistory.Index idx = index(store);
        String mary = id(store, "Mary Ellis");
        assertEquals(List.of("Mary Hale", "M. Ellis", "Mary Hart"), idx.names(mary).stream().map(FamilyNameHistory.Name::written).toList(), "no fourth, undated name: " + idx.names(mary));
        assertTrue(idx.names(mary).get(1).isForm("Mary Ellis"));
    }

    /**
     * Two readings of one name that differ only in the given part (正二, しょうじ or まさじ) are both forms of that name, which the family is
     * asked about; neither goes to a later name of another family part. A reading whose given part is read otherwise is another name's only
     * when another name of the person has the same family part (森田勇 and 森田勝).
     */
    @Test
    void twoReadingsOfOneNameThatDifferInTheGivenPartStayWithThatName(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田正二", "もりた しょうじ", List.of())),
                List.of(new FamilyAccount.Fact("森田正二", "lived-in", "広島", "1935", "森田正二（もりた しょうじ）は広島に住んだ。")), List.of()), "file:///family/a.txt", "an aunt");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田正二", "もりた まさじ", List.of())),
                List.of(new FamilyAccount.Fact("森田正二", "occupation", "shopkeeper", "", "森田正二（もりた まさじ）は店を営んだ。")), List.of()), "file:///family/b.txt", "an uncle");
        file(store, "file:///family/c.txt", List.of(), List.of(name("森田正二", "髙橋正二", "髙橋", "正二", "adoptive", "1940", "1940年に髙橋家の養子となり、髙橋正二となった。")));
        FamilyNameHistory.Index idx = index(store);
        String shoji = id(store, "森田正二");
        FamilyNameHistory.Name morita = idx.names(shoji).stream().filter(n -> n.written().equals("森田正二")).findFirst().orElseThrow();
        FamilyNameHistory.Name takahashi = idx.names(shoji).stream().filter(n -> n.written().equals("髙橋正二")).findFirst().orElseThrow();
        assertTrue(morita.texts().containsAll(List.of("もりた しょうじ", "もりた まさじ")), "both read 森田正二: " + idx.names(shoji));
        assertFalse(takahashi.texts().contains("もりた まさじ"), "もりた is no reading of 髙橋: " + takahashi);

        // two names of one family part: the given part tells them apart
        file(store, "file:///family/d.txt", List.of(new FamilyAccount.Fact("森田勝", "born-on", "1900", "", "森田勝は1900年に生まれた。")),
                List.of(name("森田勝", "森田勇", "森田", "勇", "birth", "1900", "森田勝は森田勇（もりた いさむ）として生まれた。", "もりた いさむ")));
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田勝", "もりた まさる", List.of())),
                List.of(new FamilyAccount.Fact("森田勝", "occupation", "shopkeeper", "", "森田勝（もりた まさる）は店を営んだ。")), List.of()), "file:///family/e.txt", "an uncle");
        idx = index(store);
        String masaru = id(store, "森田勝");
        FamilyNameHistory.Name isamu = idx.names(masaru).stream().filter(n -> n.written().equals("森田勇")).findFirst().orElseThrow();
        assertFalse(isamu.texts().contains("もりた まさる"), "まさる is no reading of 勇: " + idx.names(masaru));
        assertTrue(idx.names(masaru).stream().anyMatch(n -> n.written().equals("森田勝") && n.texts().contains("もりた まさる")), idx.names(masaru).toString());
    }

    /**
     * The bracket that tells two entries of one name apart is no part of the name: Ann Hart, another name of "Ann Hale (born 1941)", differs
     * from Ann Hale in a whole word, so it is a name of its own, which her marriage to Tom Hart explains, and not a way of writing Ann Hale.
     */
    @Test
    void anOtherNameIsComparedWithTheLabelWithoutTheBracketThatTellsNamesakesApart(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        String q = "Ann Hale, born 1941, married Tom Hart in 1960. Her daughter Ann Hale kept her name.";
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Ann Hale (born 1941)", "", List.of("Ann Hart"), "", "")), List.of(
                        new FamilyAccount.Fact("Ann Hale (born 1941)", "born-on", "1941", "", q),
                        new FamilyAccount.Fact("Ann Hale (born 1941)", "sex", "female", "", q),
                        new FamilyAccount.Fact("Ann Hale (born 1941)", "married-to", "Tom Hart", "1960", q),
                        new FamilyAccount.Fact("Tom Hart", "sex", "male", "", q)), List.of()),
                "file:///family/old.txt", "an aunt");
        FamilyNameHistory.Index idx = index(store);
        String ann = id(store, "Ann Hale (born 1941)");
        FamilyNameHistory.Name hart = idx.names(ann).stream().filter(n -> n.written().equals("Ann Hart")).findFirst().orElse(null);
        assertNotNull(hart, "Ann Hart is a name of its own: " + idx.names(ann));
        assertTrue(hart.workedOut() && hart.from() != null && hart.from().year() == 1960, hart.toString());
        assertEquals("Ann Hart", idx.latest(ann).written());
    }

    // ── names-fix2 G1 ──

    static List<Finding> nameClaims(LibraryStore store) throws Exception {
        return store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("has-name")).toList();
    }

    static List<FamilyNameQuestions.Question> questionsAbout(LibraryStore store, String id) throws Exception {
        return FamilyNameQuestions.open(store).stream().filter(q -> q.people().contains(id)).toList();
    }

    /**
     * d-names-1: a register that gives a name alone, beside a book that gives its kind and year, is a claim of its own. It never backs the
     * book's kind and year, and when the book's claim is disputed, the register's other name stays.
     */
    @Test
    void aSourceThatGivesLessOfANameIsAClaimOfItsOwnAndKeepsTheNameWhenTheOtherIsDisputed(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        file(store, "file:///family/book.txt", List.of(), List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1906", "健二は1906年に遠藤家に生まれた。"),
                name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "1932", "1932年に森田家の婿養子となった。")));
        file(store, "cite:遠藤家 除籍簿", List.of(), List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "", "", "除籍簿: 遠藤健二"), name("森田健二", "森田健二", "森田", "健二", "", "", "除籍簿: 森田健二")));
        Finding bookBirth = nameClaims(store).stream().filter(f -> f.triple().object().equals("name: 遠藤健二") && FamilyDetail.get(f, "kind").equals("birth")).findFirst().orElseThrow();
        assertEquals(List.of("file:///family/book.txt"), bookBirth.sources().stream().map(Finding.Source::locator).toList(), "the register is not made to back the book's kind and year: "
                + nameClaims(store).stream().map(f -> f.triple().object() + " " + f.sources().stream().map(Finding.Source::locator).toList()).toList());
        assertEquals(4, nameClaims(store).size(), "the register's two readings are claims of their own");
        new Council(store).dispute(bookBirth.id(), "the book has the year wrong: he was born in 1905");
        assertEquals(id(store, "森田健二"), id(store, "遠藤健二"), "the register's other name stays with him");
        assertTrue(Graph.aliasSources(store, "森田健二").getOrDefault("遠藤健二", List.of()).contains("cite:遠藤家 除籍簿"), Graph.aliasSources(store, "森田健二").toString());
    }

    /**
     * d-names-3: a man who entered his wife's family as 婿養子 and later adopts his nephew brings the nephew into the family he belonged to
     * at the adoption, not the one he was born into: the nephew's own birth family name is never worked out as a name taken on adoption.
     */
    @Test
    void anAdoptionBringsTheChildIntoTheFamilyTheAdopterBelongedToThen(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String q1 = "Kenji was born into the Endō family (遠藤家) in 1905 as 遠藤健二, and in 1932 entered the Morita family (森田家) as mukoyōshi (婿養子), 森田健二 from then on.";
        fileWith(store, "file:///family/book.txt", List.of(
                        new FamilyAccount.Fact("森田健二", "member-of", "遠藤家", "1905", q1, Map.of("how", "birth", "left", "adoption-out", "to", "1932")),
                        new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", q1, Map.of("how", "mukoyoshi")),
                        new FamilyAccount.Fact("森田健二", "sex", "male", "", q1)),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", q1), name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "1932", q1)),
                List.of(new FamilyAccount.FamilyRead("遠藤", "the Endō family", "", q1), new FamilyAccount.FamilyRead("森田", "the Morita family", "", q1)));
        String q2 = "In 1950 Kenji adopted his nephew Shōichi (遠藤正一), who was 森田正一 from then on.";
        file(store, "file:///family/letter.txt", List.of(new FamilyAccount.Fact("森田正一", "adopted-by", "森田健二", "1950", q2, Map.of("kind", "ordinary"))),
                List.of(name("森田正一", "遠藤正一", "遠藤", "正一", "", "", q2)));
        FamilyNameHistory.Index idx = index(store);
        String shoichi = id(store, "森田正一");
        FamilyNameHistory.Name endo = idx.names(shoichi).stream().filter(n -> n.written().equals("遠藤正一")).findFirst().orElseThrow();
        assertFalse(endo.workedOut(), "his birth family's name was not taken on the adoption: " + idx.names(shoichi));
        assertEquals("unknown", endo.kind());
        assertEquals("森田正一", idx.heading(shoichi));
        FamilyNameHistory.Name morita = idx.latest(shoichi);
        assertEquals("森田正一", morita.written());
        assertEquals("adoptive", morita.kind(), idx.names(shoichi).toString());
    }

    /**
     * d-names-3: one entry into a family that fits two of a person's names settles neither: which of them was taken on it is a question.
     */
    @Test
    void anEntryThatFitsTwoNamesSettlesNeither(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String q = "In 1932 Kenji entered the Morita family (森田家) as mukoyōshi (婿養子).";
        fileWith(store, "file:///family/book.txt", List.of(new FamilyAccount.Fact("遠藤健二", "member-of", "森田家", "1932", q, Map.of("how", "mukoyoshi"))),
                List.of(name("遠藤健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。"),
                        name("遠藤健二", "森田健二", "森田", "健二", "", "", "森田健二"), name("遠藤健二", "森田正一", "森田", "正一", "", "", "森田正一")),
                List.of(new FamilyAccount.FamilyRead("森田", "the Morita family", "", q)));
        FamilyNameHistory.Index idx = index(store);
        String kenji = id(store, "遠藤健二");
        assertTrue(idx.names(kenji).stream().filter(n -> n.family().equals("森田")).noneMatch(FamilyNameHistory.Name::workedOut), "which of the two was taken is asked: " + idx.names(kenji));
    }

    /**
     * d-names-4: a man who married into his wife's family, as the words say without saying whether he was adopted, is asked how his name
     * came (婿養子, 入夫, an adoption, only the name at the marriage); the library does not work it out as taken at marriage. Since the
     * owner's rule of 2026-09-25 (sex is not the switch) the same holds for a woman who married into her husband's family, and the year of
     * the entry is shown beside the question.
     */
    @Test
    void aManWhoMarriedIntoHisWifesFamilyIsAskedHowHisNameCame(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String q = "In 1932 Kenji (遠藤健二) married Haru (森田ハル) and married into the Morita family (森田家).";
        fileWith(store, "file:///family/book.txt", List.of(
                        new FamilyAccount.Fact("森田健二", "married-to", "森田ハル", "1932", q),
                        new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", q, Map.of("how", "marriage")),
                        new FamilyAccount.Fact("森田健二", "sex", "male", "", q), new FamilyAccount.Fact("森田ハル", "sex", "female", "", q)),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "Kenji was born in 1905 as Endō Kenji (遠藤健二)."),
                        name("森田ハル", "森田ハル", "森田", "ハル", "birth", "1910", "ハルは1910年に森田家に生まれた。")),
                List.of(new FamilyAccount.FamilyRead("森田", "the Morita family", "", q)));
        FamilyNameHistory.Index idx = index(store);
        String kenji = id(store, "森田健二");
        FamilyNameHistory.Name later = idx.names(kenji).stream().filter(n -> n.written().equals("森田健二")).findFirst().orElseThrow();
        assertEquals("unknown", later.kind(), "not worked out as taken at marriage: " + later);
        assertEquals(1932, later.from().year(), "only the year is worked out, from the marriage: " + later);
        assertTrue(questionsAbout(store, kenji).stream().anyMatch(qq -> qq.kind().equals("name-change-how") && qq.text().contains("had the name 森田健二 from 1932")), "how he came to carry it is asked, the year shown: "
                + questionsAbout(store, kenji).stream().map(FamilyNameQuestions.Question::text).toList());
        // a person whose sex is not filed is asked the same way
        LibraryStore s2 = store(tmp.resolve("b"));
        new LibrarianIndex(s2, Embeddings.none()).rebuild();
        fileWith(s2, "file:///family/book.txt", List.of(new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", q, Map.of("how", "marriage"))),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "Kenji was born in 1905 as Endō Kenji (遠藤健二).")),
                List.of(new FamilyAccount.FamilyRead("森田", "the Morita family", "", q)));
        FamilyNameHistory.Index i2 = index(s2);
        FamilyNameHistory.Name entered = i2.names(id(s2, "森田健二")).stream().filter(n -> n.written().equals("森田健二")).findFirst().orElseThrow();
        assertEquals("unknown", entered.kind(), "how is not worked out: " + entered);
        assertEquals(1932, entered.from().year(), "the year of the entry is shown: " + entered);
        // a woman who married into her husband's family is asked the same way (the owner, 2026-09-25: those cases can happen to a woman too)
        LibraryStore s3 = store(tmp.resolve("c"));
        new LibrarianIndex(s3, Embeddings.none()).rebuild();
        String qh = "In 1875 Mary Hale married into the Ellis family.";
        fileWith(s3, "file:///family/letter.txt", List.of(new FamilyAccount.Fact("Mary Ellis", "member-of", "the Ellis family", "1875", qh, Map.of("how", "marriage")),
                        new FamilyAccount.Fact("Mary Ellis", "sex", "female", "", qh)),
                List.of(name("Mary Ellis", "Mary Hale", "Hale", "Mary", "birth", "1850", "Mary Hale was born in 1850.")),
                List.of(new FamilyAccount.FamilyRead("Ellis", "the Ellis family", "", qh)));
        FamilyNameHistory.Index i3 = index(s3);
        assertEquals("unknown", i3.latest(id(s3, "Mary Ellis")).kind(), i3.names(id(s3, "Mary Ellis")).toString());
        assertEquals(1875, i3.latest(id(s3, "Mary Ellis")).from().year(), "the year of the entry is shown: " + i3.names(id(s3, "Mary Ellis")));
        assertTrue(questionsAbout(s3, id(s3, "Mary Ellis")).stream().anyMatch(qq -> qq.kind().equals("name-change-how")), questionsAbout(s3, id(s3, "Mary Ellis")).toString());
    }

    /**
     * d-names-6: a given name alone, joined in by the family's answer, and another name with a middle name more, are ways of writing the
     * label's name: no name of their own, no question how they came, and the married years still read the married name.
     */
    @Test
    void aGivenNameAloneOrAMiddleNameMoreIsAWayOfWritingTheLabelsName(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String q = "Mary Hale, born in 1850, married Tom Ellis in 1875.";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Mary Ellis", "", List.of("Mary Ann Ellis"))),
                List.of(new FamilyAccount.Fact("Mary Ellis", "married-to", "Tom Ellis", "1875", q), new FamilyAccount.Fact("Mary Ellis", "sex", "female", "", q),
                        new FamilyAccount.Fact("Tom Ellis", "sex", "male", "", q), new FamilyAccount.Fact("Mary Ellis", "lived-in", "York", "1880", q)), List.of(), List.of(),
                List.of(name("Mary Ellis", "Mary Hale", "Hale", "Mary", "birth", "1850", q)), List.of()), "an aunt", f -> List.of("file:///family/letter.txt"), f -> List.of());
        file(store, "file:///family/note.txt", List.of(new FamilyAccount.Fact("Mary", "lived-in", "York", "1890", "Mary kept the shop in York in 1890.")), List.of());
        Graph.merge(store, "Mary", "Mary Ellis", "person", "the family said so");
        FamilyNameHistory.Index idx = index(store);
        String mary = id(store, "Mary Ellis");
        List<FamilyNameHistory.Name> names = idx.names(mary);
        assertEquals(List.of("Mary Hale", "Mary Ellis"), names.stream().map(FamilyNameHistory.Name::written).toList(), "no third or fourth name: " + names);
        assertTrue(names.get(1).texts().containsAll(List.of("Mary", "Mary Ann Ellis")), names.toString());
        assertEquals("Mary Ellis", idx.at(mary, 1880).written());
        assertEquals("Mary Ellis", idx.at(mary, 1890).written());
        assertTrue(questionsAbout(store, mary).stream().noneMatch(qq -> qq.kind().equals("name-change-how")), questionsAbout(store, mary).stream().map(FamilyNameQuestions.Question::text).toList().toString());
        // another name with another family's word in it is still a name of its own, and no way of writing her birth name
        file(store, "file:///family/card.txt", List.of(), List.of(name("John Hart", "John Hart", "Hart", "John", "birth", "1840", "John Hart was born in 1840.")));
        Graph.alias(store, "Mary Ellis", List.of("Mary Ann Hart"), "file:///family/card.txt");
        idx = index(store);
        assertTrue(idx.names(mary).stream().anyMatch(n -> n.written().equals("Mary Ann Hart")), idx.names(mary).toString());
    }

    /**
     * d-owner-4: a given name alone in characters that the family's answer joins in (健二 into 森田健二) is a way of writing his name, never
     * a name of its own: nothing asks how he came to carry 健二, and the heading stays.
     */
    @Test
    void aGivenNameAloneInCharactersIsAWayOfWritingTheName(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String q = "In 1932 he entered the Morita family as mukoyōshi (婿養子) of Isamu.";
        file(store, "file:///family/book.txt", List.of(new FamilyAccount.Fact("森田健二", "adopted-by", "森田勇", "1932", q)),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。", "えんどう けんじ"),
                        name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "1932", q, "もりた けんじ", "Morita Kenji")));
        file(store, "file:///family/note.txt", List.of(new FamilyAccount.Fact("健二", "lived-in", "広島", "1950", "健二は広島に住んだ。"), new FamilyAccount.Fact("Kenji", "occupation", "shopkeeper", "", "Kenji kept the shop.")), List.of());
        Graph.merge(store, "健二", "森田健二", "person", "the family said so");
        Graph.merge(store, "Kenji", "森田健二", "person", "the family said so");
        FamilyNameHistory.Index idx = index(store);
        String kenji = id(store, "森田健二");
        assertEquals(List.of("遠藤健二", "森田健二"), idx.names(kenji).stream().map(FamilyNameHistory.Name::written).toList(), "健二 and Kenji are no names of their own: " + idx.names(kenji));
        assertTrue(idx.names(kenji).get(1).texts().containsAll(List.of("健二", "Kenji")), idx.names(kenji).toString());
        assertEquals("森田健二 (born 遠藤)", idx.heading(kenji));
        assertTrue(questionsAbout(store, kenji).stream().noneMatch(qq -> qq.text().contains("get the name 健二")), questionsAbout(store, kenji).stream().map(FamilyNameQuestions.Question::text).toList().toString());
    }

    /**
     * d-names-7: a child-of link beside two names that nothing orders does not make one of them the name at birth: "son" does not say born
     * or adopted, and which name came first is asked.
     */
    @Test
    void twoNamesThatNothingOrdersSettleNoBirthName(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        file(store, "file:///family/a.txt", List.of(new FamilyAccount.Fact("髙橋正二", "child-of", "髙橋勇", "", "Shōji (髙橋正二) was the son of Takahashi Isamu (髙橋勇).")),
                List.of(name("髙橋正二", "髙橋正二", "髙橋", "正二", "", "", "Shōji (髙橋正二) was the son of Takahashi Isamu."),
                        name("髙橋正二", "森田正二", "森田", "正二", "", "", "Shōji was once called 森田正二.")));
        FamilyNameHistory.Index idx = index(store);
        String shoji = id(store, "髙橋正二");
        assertNull(idx.birth(shoji), "neither name is worked out as the name at birth: " + idx.names(shoji));
        assertEquals("髙橋正二", idx.heading(shoji));
    }

    /**
     * d-names-8: a record written under the married name before the marriage leaves the year worked out from the marriage as it is, and
     * the family is asked about that record; the same for a record under a name before the entry into a family.
     */
    @Test
    void aRecordUnderANameBeforeTheEventIsAskedAbout(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path ged = tmp.resolve("t.ged");
        Files.writeString(ged, "0 HEAD\n1 GEDC\n2 VERS 5.5.1\n1 CHAR UTF-8\n0 @I1@ INDI\n1 NAME Mary /Hale/\n2 TYPE birth\n1 NAME Mary /Ellis/\n2 TYPE married\n1 SEX F\n1 BIRT\n2 DATE 1860\n1 FAMS @F1@\n"
                + "0 @I2@ INDI\n1 NAME Tom /Ellis/\n1 SEX M\n1 FAMS @F1@\n0 @F1@ FAM\n1 HUSB @I2@\n1 WIFE @I1@\n1 MARR\n2 DATE 1884\n0 TRLR\n");
        Gedcom.importFile(store, ged);
        file(store, "cite:census 1880 Leeds", List.of(new FamilyAccount.Fact("Mary Ellis", "lived-in", "Leeds", "1880", "Mary Ellis, wife, 20, Leeds (1880)")), List.of());
        FamilyNameHistory.Index idx = index(store);
        String mary = id(store, "Mary Hale");
        FamilyNameHistory.Name ellis = idx.names(mary).stream().filter(n -> n.written().equals("Mary Ellis")).findFirst().orElseThrow();
        assertEquals(1884, ellis.from() == null ? 0 : ellis.from().year(), "the year from the marriage stands: " + ellis);
        assertTrue(questionsAbout(store, mary).stream().anyMatch(qq -> qq.kind().equals("name-at-date") && qq.text().contains("“Mary Ellis”")),
                "the census of 1880 writes Mary Ellis before the marriage of 1884, and the family is asked about it: " + questionsAbout(store, mary).stream().map(FamilyNameQuestions.Question::text).toList());
        // the same for a name of the family a man entered as 婿養子
        LibraryStore s2 = store(tmp.resolve("b"));
        new LibrarianIndex(s2, Embeddings.none()).rebuild();
        String q = "In 1932 he entered the Morita family (森田家) as mukoyōshi (婿養子).";
        fileWith(s2, "file:///family/book.txt", List.of(new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", q, Map.of("how", "mukoyoshi"))),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "Kenji was born in 1905 as Endō Kenji (遠藤健二).")),
                List.of(new FamilyAccount.FamilyRead("森田", "the Morita family", "", q)));
        file(s2, "cite:広島県 学籍簿 1925", List.of(new FamilyAccount.Fact("森田健二", "life-event", "left school", "1925", "森田健二 大正十四年 卒業")), List.of());
        FamilyNameHistory.Index i2 = index(s2);
        String kenji = id(s2, "森田健二");
        FamilyNameHistory.Name morita = i2.names(kenji).stream().filter(n -> n.written().equals("森田健二")).findFirst().orElseThrow();
        assertEquals(1932, morita.from() == null ? 0 : morita.from().year(), morita.toString());
        assertTrue(questionsAbout(s2, kenji).stream().anyMatch(qq -> qq.kind().equals("name-at-date") && qq.text().contains("“森田健二”")),
                "a record of 1925 writes 森田健二 before the entry of 1932, and the family is asked about it: " + questionsAbout(s2, kenji).stream().map(FamilyNameQuestions.Question::text).toList());
    }

    /**
     * d-names-9: a name in characters has a romanised form; where a source gives a reading (the name's kana, a family's romanised name),
     * that reading says which word is the family part, and its given name in letters is never its family name. Where no source reads it,
     * either word may be, and the name is a candidate for both that a question offers (e-owner-3).
     */
    @Test
    void aRomanisedGivenNameIsNotTheFamilyPartOfANameInCharacters(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        file(store, "file:///family/a.txt", List.of(new FamilyAccount.Fact("山田太郎", "born-on", "1900", "", "山田太郎 (Yamada Taro) was born in 1900.")),
                List.of(name("山田太郎", "山田太郎", "山田", "太郎", "birth", "1900", "山田太郎 (Yamada Taro) was born in 1900.", "Yamada Taro")));
        FamilyNameHistory.Index idx = index(store);
        String taro = id(store, "山田太郎");
        assertTrue(idx.names(taro).get(0).hasFamily("Yamada"), idx.names(taro).toString());
        assertTrue(idx.bearers("Yamada", 1950).contains(taro), "with no reading, a word of the romanised form may be the family name");
        // with the reading a source gives, the family part is known in letters
        LibraryStore s2 = store(tmp.resolve("b"));
        file(s2, "file:///family/a.txt", List.of(), List.of(name("山田太郎", "山田太郎", "山田", "太郎", "birth", "1900", "山田太郎 (やまだ たろう, Yamada Taro)", "やまだ たろう", "Yamada Taro")));
        FamilyNameHistory.Index i2 = index(s2);
        FamilyNameHistory.Name n2 = i2.names(id(s2, "山田太郎")).get(0);
        assertTrue(n2.hasFamily("Yamada"));
        assertFalse(n2.hasFamily("Taro"));
        // and a family's romanised name is such a reading
        LibraryStore s3 = store(tmp.resolve("c"));
        String q = "Taro (山田太郎) was the head of the Yamada family (山田家).";
        fileWith(s3, "file:///family/a.txt", List.of(new FamilyAccount.Fact("山田太郎", "member-of", "山田家", "", q, Map.of("role", "head"))),
                List.of(name("山田太郎", "山田太郎", "山田", "太郎", "birth", "1900", q, "Yamada Taro")), List.of(new FamilyAccount.FamilyRead("山田", "the Yamada family", "", q)));
        FamilyNameHistory.Index i3 = index(s3);
        assertTrue(i3.names(id(s3, "山田太郎")).get(0).hasFamily("Yamada"));
        assertTrue(FamilyMentions.forms(i3, i3.graph(), "Yamada").contains("山田"), FamilyMentions.forms(i3, i3.graph(), "Yamada").toString());
        assertFalse(i3.names(id(s3, "山田太郎")).get(0).hasFamily("Taro"));
    }

    /**
     * d-owner-5: when the owner joins two entries, the other names of the entry that went into the other come along as forms: the reading
     * a source gave it stays on the joined person's names.
     */
    @Test
    void theOtherNamesOfAnEntryJoinedIntoAnotherComeAlong(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田健二", "もりた けんじ", List.of())),
                List.of(new FamilyAccount.Fact("森田健二", "lived-in", "広島", "1940", "森田健二（もりた けんじ）は広島に住んだ。")), List.of()), "file:///family/register.txt", "an aunt");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Morita Kenji", "", List.of())),
                List.of(new FamilyAccount.Fact("Morita Kenji", "occupation", "shopkeeper", "", "Morita Kenji kept the shop.")), List.of()), "file:///family/book.txt", "an uncle");
        Graph.merge(store, "森田健二", "Morita Kenji", "person", "the family said so");
        FamilyNameHistory.Index idx = index(store);
        String kenji = id(store, "Morita Kenji");
        assertTrue(idx.names(kenji).stream().anyMatch(n -> n.texts().contains("もりた けんじ")), "the reading comes along: " + idx.names(kenji));
        assertTrue(Graph.aliasSources(store, "Morita Kenji").getOrDefault("もりた けんじ", List.of()).contains("file:///family/register.txt"), "with where it came from");
    }

    /**
     * d-owner-6: the family's answer "an ordinary adoption" to the question whether he entered as 婿養子 settles how his name came: the
     * book's word 婿養子 on the older adoption claim counts no more, and the name is not asked about again.
     */
    @Test
    void theFamilysAnswerOnHowHeWasAdoptedOutranksTheBooksWord(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String q = "In 1932 he entered the Morita family as mukoyōshi (婿養子) of Isamu.";
        fileWith(store, "file:///family/book.txt", List.of(new FamilyAccount.Fact("森田健二", "adopted-by", "森田勇", "1932", q),
                        new FamilyAccount.Fact("森田勇", "member-of", "森田家", "", q, Map.of("role", "head"))),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。")),
                List.of(new FamilyAccount.FamilyRead("森田", "the Morita family", "", q)));
        // the answer as the questions file it: the adoption again with the family's kind, and the family he entered, each the family's word
        answer(store, new Finding.Triple("森田健二", "adopted-by", "森田勇"), "森田健二 was adopted by 森田勇 (1932).", Map.of("kind", "ordinary", "from", "1932"));
        answer(store, new Finding.Triple("森田健二", "member-of", "森田 family"), "森田健二 entered the 森田 family on an adoption (1932).", Map.of("how", "adoption", "from", "1932"));
        FamilyNameHistory.Index idx = index(store);
        String kenji = id(store, "森田健二");
        FamilyNameHistory.Name later = idx.latest(kenji);
        assertEquals("森田健二", later.written());
        assertEquals("adoptive", later.kind(), "the family's word settles it: " + idx.names(kenji));
        assertEquals(1932, later.from().year());
        assertTrue(questionsAbout(store, kenji).stream().noneMatch(qq -> qq.kind().equals("name-change-how") && qq.text().contains("get the name 森田健二")),
                questionsAbout(store, kenji).stream().map(FamilyNameQuestions.Question::text).toList().toString());
    }

    /**
     * N2: a name in characters whose family part a source reads as the family part of the birth name written in letters (the Morita
     * family, 森田家) carries the family he was born into: nobody is asked how he came to carry it. Without a reading it is no form of the
     * birth name.
     */
    @Test
    void aNameInCharactersOfTheBirthFamilyByAKnownReadingIsNotAskedHowItCame(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String q = "Isamu's son Shōji (森田正二) was born into the Morita family (森田家). In 1940 he was adopted as heir into the Takahashi family (髙橋家), and was known afterwards as Takahashi Shōji (髙橋正二).";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Takahashi Shōji", "", List.of("髙橋正二", "森田正二"))),
                List.of(new FamilyAccount.Fact("Takahashi Shōji", "sex", "male", "", q)), List.of(), List.of(),
                List.of(new FamilyAccount.NameRead("Takahashi Shōji", "Shōji", "Morita", "Shōji", List.of(), "birth", "born into", "", q),
                        new FamilyAccount.NameRead("Takahashi Shōji", "Takahashi Shōji", "Takahashi", "Shōji", List.of(), "adoptive", "", "1940", q)),
                List.of(new FamilyAccount.FamilyRead("森田", "the Morita family", "", q))), "an aunt", f -> List.of("file:///family/shop.txt"), f -> List.of());
        FamilyNameHistory.Index idx = index(store);
        String shoji = id(store, "Takahashi Shōji");
        assertTrue(questionsAbout(store, shoji).stream().noneMatch(qq -> qq.kind().equals("name-change-how") && qq.text().contains("get the name 森田正二")),
                questionsAbout(store, shoji).stream().map(FamilyNameQuestions.Question::text).toList().toString());
        FamilyNameHistory.Name inCharacters = idx.names(shoji).stream().filter(n -> n.written().equals("森田正二")).findFirst().orElseThrow();
        assertTrue(inCharacters.hasFamily("Morita"), "森田 is read Morita by the family's own entry: " + inCharacters);
        assertFalse(idx.birth(shoji).isForm("森田正二"), "no reading ties its given part, so it is no form of the birth name: " + idx.names(shoji));
    }

    /**
     * e-names-atbirth-reads-names-before-working-out: a married name whose kind and year come from the marriage, or a name whose kind and
     * year come from entering a family as 婿養子, is dated after the birth, so the other name of a birth parent's family is the name at birth.
     */
    @Test
    void theNameAtBirthIsWorkedOutBesideANameTheMarriageOrTheEntryDates(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String q = "Mary Hale, John Hale's daughter, married Tom Ellis in 1875.";
        file(store, "file:///family/letter.txt", List.of(new FamilyAccount.Fact("Mary Ellis", "child-of", "John Hale", "", q),
                        new FamilyAccount.Fact("Mary Ellis", "married-to", "Tom Ellis", "1875", q),
                        new FamilyAccount.Fact("Mary Ellis", "sex", "female", "", q), new FamilyAccount.Fact("Tom Ellis", "sex", "male", "", q)),
                List.of(name("Mary Ellis", "Mary Hale", "Hale", "Mary", "", "", q)));
        FamilyNameHistory.Index idx = index(store);
        String mary = id(store, "Mary Ellis");
        assertEquals("Mary Ellis (born Hale)", idx.heading(mary), idx.names(mary).toString());
        assertEquals("Mary Hale", idx.at(mary, 1860) == null ? null : idx.at(mary, 1860).written(), idx.names(mary).toString());
        assertEquals("Mary Ellis", idx.at(mary, 1880) == null ? null : idx.at(mary, 1880).written());
        // a man who entered his wife's family as 婿養子, the son of a father of another family
        LibraryStore s2 = store(tmp.resolve("b"));
        new LibrarianIndex(s2, Embeddings.none()).rebuild();
        String q2 = "Kenji (遠藤健二), the son of 遠藤正一, entered the Morita family (森田家) as mukoyōshi (婿養子) in 1932.";
        fileWith(s2, "file:///family/book.txt", List.of(new FamilyAccount.Fact("森田健二", "child-of", "遠藤正一", "", q2),
                        new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", q2, Map.of("how", "mukoyoshi")), new FamilyAccount.Fact("森田健二", "sex", "male", "", q2)),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "", "", q2)), List.of(new FamilyAccount.FamilyRead("森田", "the Morita family", "", q2)));
        FamilyNameHistory.Index i2 = index(s2);
        String kenji = id(s2, "森田健二");
        assertEquals("森田健二 (born 遠藤)", i2.heading(kenji), i2.names(kenji).toString());
        assertEquals("遠藤健二", i2.at(kenji, 1920) == null ? null : i2.at(kenji, 1920).written());
        assertEquals("森田健二", i2.at(kenji, 1940) == null ? null : i2.at(kenji, 1940).written());
    }

    /**
     * e-names-older-record-uses-the-reported-date: a family register that lists a 婿養子's birth under his adoptive name, or a death
     * certificate that gives a woman's birth under her married name, reports an earlier fact under a later name. The year worked out from
     * the entry or the marriage stands.
     */
    @Test
    void aLaterRecordOfTheBirthUnderTheLaterNameLeavesTheWorkedOutYear(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String q = "In 1932 Kenji (遠藤健二) entered the Morita family (森田家) as mukoyōshi (婿養子), and was 森田健二 from then on.";
        fileWith(store, "file:///family/book.txt", List.of(new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", q, Map.of("how", "mukoyoshi")),
                        new FamilyAccount.Fact("森田健二", "sex", "male", "", q)),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", q)), List.of(new FamilyAccount.FamilyRead("森田", "the Morita family", "", q)));
        file(store, "cite:森田家 戸籍", List.of(new FamilyAccount.Fact("森田健二", "born-on", "1905", "", "森田健二 明治三十八年生")), List.of());
        FamilyNameHistory.Index idx = index(store);
        String kenji = id(store, "森田健二");
        FamilyNameHistory.Name morita = idx.latest(kenji);
        assertEquals("mukoyoshi", morita.kind(), "the register lists his birth under the name he carried later: " + idx.names(kenji));
        assertEquals(1932, morita.from() == null ? 0 : morita.from().year());
        assertEquals("遠藤健二", idx.at(kenji, 1920) == null ? null : idx.at(kenji, 1920).written());
        // a tree file's married name, dated by its marriage, and a death certificate that gives her birth under it
        LibraryStore s2 = store(tmp.resolve("b"));
        new LibrarianIndex(s2, Embeddings.none()).rebuild();
        Path ged = tmp.resolve("t.ged");
        Files.writeString(ged, "0 HEAD\n1 GEDC\n2 VERS 5.5.1\n1 CHAR UTF-8\n0 @I1@ INDI\n1 NAME Mary /Hale/\n2 TYPE birth\n1 NAME Mary /Ellis/\n2 TYPE married\n1 SEX F\n1 FAMS @F1@\n"
                + "0 @I2@ INDI\n1 NAME Tom /Ellis/\n1 SEX M\n1 FAMS @F1@\n0 @F1@ FAM\n1 HUSB @I2@\n1 WIFE @I1@\n1 MARR\n2 DATE 1884\n0 TRLR\n");
        Gedcom.importFile(s2, ged);
        file(s2, "cite:death certificate 1920 Leeds", List.of(new FamilyAccount.Fact("Mary Ellis", "born-on", "1860", "", "Mary Ellis, widow, born 1860, died at Leeds in 1920.")), List.of());
        FamilyNameHistory.Index i2 = index(s2);
        String mary = id(s2, "Mary Hale");
        FamilyNameHistory.Name ellis = i2.names(mary).stream().filter(n -> n.written().equals("Mary Ellis")).findFirst().orElseThrow();
        assertEquals(1884, ellis.from() == null ? 0 : ellis.from().year(), "the death certificate gives her birth under her married name: " + i2.names(mary));
        assertEquals("Mary Hale", i2.at(mary, 1870) == null ? null : i2.at(mary, 1870).written());
    }

    /**
     * e-names-joinedin-brings-back-disputed-names: a name the owner disputes after joining the entry that carried it into another is gone
     * from the joined person's names, and nobody is asked how he came to carry it.
     */
    @Test
    void aNameDisputedAfterTheJoinIsGoneFromTheJoinedPerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        file(store, "file:///family/register.txt", List.of(new FamilyAccount.Fact("森田健二", "lived-in", "広島", "1940", "森田健二は広島に住んだ。")), List.of());
        String q = "Morita Kenji, born Kenji Endo in 1905, kept the shop.";
        file(store, "file:///family/book.txt", List.of(new FamilyAccount.Fact("Morita Kenji", "occupation", "shopkeeper", "", q)),
                List.of(name("Morita Kenji", "Kenji Endo", "Endo", "Kenji", "birth", "1905", q)));
        Graph.merge(store, "Morita Kenji", "森田健二", "person", "the family said so");
        Finding claim = nameClaims(store).stream().filter(f -> f.triple().object().equals("name: Kenji Endo")).findFirst().orElseThrow();
        new Council(store).dispute(claim.id(), "he was born 森田; the book confused him with his cousin");
        FamilyNameHistory.Index idx = index(store);
        String kenji = id(store, "森田健二");
        assertTrue(idx.names(kenji).stream().noneMatch(n -> n.texts().contains("Kenji Endo")), "the disputed name is no name of his: " + idx.names(kenji));
        assertTrue(questionsAbout(store, kenji).stream().noneMatch(qq -> qq.text().contains("Kenji Endo")), questionsAbout(store, kenji).stream().map(FamilyNameQuestions.Question::text).toList().toString());
        assertTrue(idx.names(kenji).stream().anyMatch(n -> n.texts().contains("Morita Kenji")), "the joined entry's own name stays: " + idx.names(kenji));
    }

    /**
     * e-names-joinedin-quadratic: reading the other names of joined entries is done once for the whole library, so the names of everybody
     * in a large tree with many joins are worked out in about the time they take without the joins.
     */
    @Test
    void theNamesOfALargeTreeWithManyJoinsAreWorkedOutQuickly(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        int people = 800, joins = 2400;
        for (int i = 0; i < people; i++) {
            String s = "Tom Hale " + i;
            store.write(new Finding(store.nextFindingId(s + " lived-in York"), s + " lived in York.", List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                    Finding.Confidence.low, "family-account", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                    List.of(new Finding.Source("file:///family/list.txt", "n/a", "")), List.of(), null, s + " lived in York.\n", new Finding.Triple(s, "lived-in", "York"), List.of()));
        }
        Graph g0 = FamilyPeople.view(store);
        StringBuilder merges = new StringBuilder();
        for (int j = 0; j < joins; j++) merges.append("ann hart ").append(j).append('\t').append(g0.nodeIdOf("Tom Hale " + (j % people))).append("\tperson\t2026-09-24\n");
        Files.createDirectories(Graph.mergesFile(store).getParent());
        Files.writeString(Graph.mergesFile(store), merges.toString());
        Graph g = FamilyPeople.view(store);
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        long start = System.nanoTime();
        for (Graph.Node n : g.nodes()) if ("person".equals(n.kind())) idx.names(n.id());
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 1500, "the names of " + people + " people with " + joins + " joins took " + ms + " ms");
    }

    /**
     * e-names-any-accepted-claim-outranks-mukoyoshi: a letter's plain "adopted into the Morita family", accepted by the owner, does not say
     * whether the adoption was as 婿養子, so the book's 婿養子 still counts: the two disagree and the family is asked how his name came.
     */
    @Test
    void anAcceptedPlainAdoptionDoesNotSettleWhetherItWasAs婿養子(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String q = "In 1932 he entered the Morita family as mukoyōshi (婿養子) of Isamu.";
        fileWith(store, "file:///family/book.txt", List.of(new FamilyAccount.Fact("森田健二", "adopted-by", "森田勇", "1932", q),
                        new FamilyAccount.Fact("森田勇", "member-of", "森田家", "", q, Map.of("role", "head"))),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。")),
                List.of(new FamilyAccount.FamilyRead("森田", "the Morita family", "", q)));
        String q2 = "Kenji was adopted into the Morita family (森田家) in 1932.";
        fileWith(store, "file:///family/letter.txt", List.of(new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", q2, Map.of("how", "adoption"))), List.of(),
                List.of(new FamilyAccount.FamilyRead("森田", "the Morita family", "", q2)));
        Finding letter = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("member-of")
                && f.sources().stream().anyMatch(s -> s.locator().contains("letter.txt"))).findFirst().orElseThrow();
        new Council(store).accept(letter.id());
        FamilyNameHistory.Index idx = index(store);
        String kenji = id(store, "森田健二");
        FamilyNameHistory.Name later = idx.names(kenji).stream().filter(n -> n.written().equals("森田健二")).findFirst().orElseThrow();
        assertEquals("unknown", later.kind(), "a plain adoption does not deny 婿養子: " + idx.names(kenji));
        assertTrue(questionsAbout(store, kenji).stream().anyMatch(qq -> qq.kind().equals("name-change-how") && qq.text().contains("get the name 森田健二")),
                questionsAbout(store, kenji).stream().map(FamilyNameQuestions.Question::text).toList().toString());
    }

    /**
     * e-names-heldat-ignores-birth-membership-end: a man born into the 遠藤 family (a membership with no end, as a read files it) who
     * entered the 森田 family as 婿養子 and later adopted his nephew brings the nephew into the 森田 family, the one his names say he
     * belonged to then.
     */
    @Test
    void anAdoptionBringsTheChildIntoTheFamilyTheAdoptersNamesGiveThen(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String q1 = "Kenji was born into the Endō family (遠藤家) in 1905 as 遠藤健二, and in 1932 entered the Morita family (森田家) as mukoyōshi (婿養子), 森田健二 from then on.";
        fileWith(store, "file:///family/book.txt", List.of(
                        new FamilyAccount.Fact("森田健二", "member-of", "遠藤家", "1905", q1, Map.of("how", "birth")),
                        new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", q1, Map.of("how", "mukoyoshi")),
                        new FamilyAccount.Fact("森田健二", "sex", "male", "", q1)),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", q1), name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "1932", q1)),
                List.of(new FamilyAccount.FamilyRead("遠藤", "the Endō family", "", q1), new FamilyAccount.FamilyRead("森田", "the Morita family", "", q1)));
        String q2 = "In 1950 Kenji adopted his nephew Shōichi (遠藤正一), who was 森田正一 from then on.";
        file(store, "file:///family/letter.txt", List.of(new FamilyAccount.Fact("森田正一", "adopted-by", "森田健二", "1950", q2, Map.of("kind", "ordinary"))),
                List.of(name("森田正一", "遠藤正一", "遠藤", "正一", "", "", q2)));
        FamilyNameHistory.Index idx = index(store);
        String shoichi = id(store, "森田正一");
        FamilyNameHistory.Name endo = idx.names(shoichi).stream().filter(n -> n.written().equals("遠藤正一")).findFirst().orElseThrow();
        assertFalse(endo.workedOut(), "his birth family's name was not taken on the adoption: " + idx.names(shoichi));
        assertEquals("森田正一", idx.heading(shoichi));
        assertEquals("adoptive", idx.latest(shoichi).kind(), idx.names(shoichi).toString());
    }

    /**
     * e-names-heldat-until-death-counts-as-left: a head of the family "until his death" was its member when he adopted, so the man he took
     * as 婿養子 entered that family then.
     */
    @Test
    void anAdoptionByAHeadUntilHisDeathBringsTheChildIntoHisFamily(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String q = "Isamu, head of the Morita family (森田家) until his death, took Kenji (遠藤健二) as mukoyōshi (婿養子) in 1932; Kenji was 森田健二 from then on.";
        fileWith(store, "file:///family/book.txt", List.of(new FamilyAccount.Fact("Isamu", "member-of", "森田家", "", q, Map.of("role", "head", "left", "death")),
                        new FamilyAccount.Fact("森田健二", "adopted-by", "Isamu", "1932", q, Map.of("kind", "mukoyoshi")), new FamilyAccount.Fact("森田健二", "sex", "male", "", q)),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", q)), List.of(new FamilyAccount.FamilyRead("森田", "the Morita family", "", q)));
        FamilyNameHistory.Index idx = index(store);
        String kenji = id(store, "森田健二");
        FamilyNameHistory.Name morita = idx.names(kenji).stream().filter(n -> n.written().equals("森田健二")).findFirst().orElseThrow();
        assertEquals("mukoyoshi", morita.kind(), "taken on entering the family his adopter headed: " + idx.names(kenji));
        assertEquals("遠藤健二", idx.at(kenji, 1920) == null ? null : idx.at(kenji, 1920).written());
        assertEquals("森田健二", idx.at(kenji, 1940) == null ? null : idx.at(kenji, 1940).written());
    }

    /**
     * e-names-partial-form-dates-the-name: a record written under the given name alone (健二), joined in by the family, does not say
     * which family name he carried then, so it dates no name: the name tied to the adoption of 1932 keeps that year. Nor does the
     * name-at-date question ask about it (G3).
     */
    @Test
    void aRecordUnderTheGivenNameAloneDatesNoName(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String q = "In 1932 he entered the Morita family as mukoyōshi (婿養子) of Isamu, and was 森田健二 from then on.";
        file(store, "file:///family/book.txt", List.of(new FamilyAccount.Fact("森田健二", "adopted-by", "森田勇", "1932", q)),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。"), name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "", q)));
        file(store, "cite:広島県 学籍簿", List.of(new FamilyAccount.Fact("健二", "life-event", "left school", "1920", "健二 大正九年 卒業")), List.of());
        Graph.merge(store, "健二", "森田健二", "person", "the family said so");
        FamilyNameHistory.Index idx = index(store);
        String kenji = id(store, "森田健二");
        FamilyNameHistory.Name morita = idx.names(kenji).stream().filter(n -> n.written().equals("森田健二")).findFirst().orElseThrow();
        assertNull(idx.useWindow(kenji, morita), "the register writes 健二 alone: " + idx.names(kenji));
        assertEquals(1932, morita.from() == null ? 0 : morita.from().year(), idx.names(kenji).toString());
        assertEquals("遠藤健二", idx.at(kenji, 1920) == null ? null : idx.at(kenji, 1920).written());
        // and the family is not asked whether the register wrote 森田健二 before 1932: 健二 alone says no family name at all
        assertTrue(questionsAbout(store, kenji).stream().noneMatch(qq -> qq.kind().equals("name-at-date")),
                "a record under the given name alone raises no question about the name carried then: " + questionsAbout(store, kenji).stream().map(FamilyNameQuestions.Question::text).toList());
    }

    /**
     * e-owner-3: a tree file's romanised form (ROMN Kenji /Endo/) is the one source of the name in letters; with no reading of the family
     * part anywhere, a word of it may be the family name: 遠藤健二 bore Endo, the family page lists him, and "Endo" leads to 遠藤, so a
     * question about an Endo offers the tree's 遠藤 people.
     */
    @Test
    void aTreeFilesRomanisedFormFindsItsPeopleByTheFamilyNameInLetters(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path ged = tmp.resolve("tree.ged");
        Files.writeString(ged, "0 HEAD\n1 GEDC\n2 VERS 5.5.1\n1 CHAR UTF-8\n0 @I1@ INDI\n1 NAME 健二 /遠藤/\n2 TYPE birth\n2 ROMN Kenji /Endo/\n3 TYPE romaji\n1 NAME 健二 /森田/\n2 TYPE 婿養子\n"
                + "2 _NAMEKIND mukoyoshi\n2 _NAMEDATE FROM 1932\n1 SEX M\n1 BIRT\n2 DATE 1905\n1 FAMC @F1@\n0 @I5@ INDI\n1 NAME 正一 /遠藤/\n2 ROMN Shoichi /Endo/\n3 TYPE romaji\n1 SEX M\n"
                + "1 BIRT\n2 DATE 1875\n1 FAMS @F1@\n0 @F1@ FAM\n1 HUSB @I5@\n1 CHIL @I1@\n0 TRLR\n");
        Gedcom.importFile(store, ged);
        FamilyNameHistory.Index idx = index(store);
        String kenji = id(store, "遠藤健二"), shoichi = id(store, "遠藤正一");
        assertTrue(idx.bearers("Endo", 1930).contains(kenji), "the tree writes him Kenji Endo: " + idx.bearers("Endo", 1930) + " " + idx.names(kenji));
        assertTrue(FamilyMentions.forms(idx, idx.graph(), "Endo").contains("遠藤"), FamilyMentions.forms(idx, idx.graph(), "Endo").toString());
        assertTrue(idx.bearers("遠藤", 1930).containsAll(List.of(kenji, shoichi)), idx.bearers("遠藤", 1930).toString());
        String page = FamilyNamesFilingTest.run(store, "family", "Endo");
        assertTrue(page.contains("as 遠藤健二, from 1905 to 1932"), page);
    }

    /**
     * e-owner-4: an older library's Geni name in characters written given name first ("健二 遠藤") is 遠藤健二: another name of the person,
     * not a way of writing 森田健二; once the birth name is claimed, it is a way of writing that.
     */
    @Test
    void aNameInCharactersWrittenGivenNameFirstIsReadFamilyNameFirst(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田健二", "もりた けんじ", List.of("健二 遠藤"))),
                List.of(new FamilyAccount.Fact("森田健二", "born-in", "広島県安芸郡", "1905", "q")), List.of()), "file:///family/old.txt", "an aunt");
        FamilyNameHistory.Index idx = index(store);
        String kenji = id(store, "森田健二");
        FamilyNameHistory.Name label = idx.names(kenji).stream().filter(n -> n.written().equals("森田健二")).findFirst().orElseThrow();
        assertFalse(label.isForm("健二 遠藤"), "健二 遠藤 is 遠藤健二, another name: " + idx.names(kenji));
        FamilyNameHistory.Name other = idx.names(kenji).stream().filter(n -> n.texts().contains("健二 遠藤")).findFirst().orElseThrow();
        assertEquals("遠藤", other.family(), other.toString());
        assertEquals("unknown", other.kind());
        file(store, "file:///family/book.txt", List.of(), List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。")));
        idx = index(store);
        assertTrue(idx.birth(kenji) != null && idx.birth(kenji).texts().contains("健二 遠藤"), "a way of writing the birth name: " + idx.names(kenji));
    }

    /**
     * e-owner-2: 森田健二, known only by that name, entered the 森田 family as 婿養子 in 1932 (by a membership, or by an adoption by its head),
     * and his father is of the 遠藤 family. The name comes from the entry (R3): it is worked out as taken then, and before 1932 he bore no
     * 森田. With no birth parent, or a father of the 森田 family, nothing says the name came later, and it stays as it is.
     */
    @Test
    void aSoleNameFromAStatedEntryIsDatedFromTheEntryWhenABirthParentIsOfAnotherFamily(@TempDir Path tmp) throws Exception {
        String q = "Kenji (森田健二), the son of 遠藤正一, was born in 1905 and entered the Morita family (森田家) as mukoyōshi (婿養子) of 森田勇 in 1932.";
        List<FamilyAccount.FamilyRead> morita = List.of(new FamilyAccount.FamilyRead("森田", "the Morita family", "", q));
        for (String by : List.of("member-of", "adopted-by")) {
            LibraryStore store = store(tmp.resolve(by));
            new LibrarianIndex(store, Embeddings.none()).rebuild();
            FamilyAccount.Fact entry = by.equals("member-of") ? new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", q, Map.of("how", "mukoyoshi"))
                    : new FamilyAccount.Fact("森田健二", "adopted-by", "森田勇", "1932", q, Map.of("kind", "mukoyoshi"));
            fileWith(store, "file:///family/book.txt", List.of(new FamilyAccount.Fact("森田健二", "child-of", "遠藤正一", "", q), new FamilyAccount.Fact("森田健二", "born-on", "1905", "", q),
                            new FamilyAccount.Fact("森田健二", "sex", "male", "", q), entry, new FamilyAccount.Fact("森田勇", "member-of", "森田家", "", q, Map.of("role", "head"))),
                    List.of(name("遠藤正一", "遠藤正一", "遠藤", "正一", "birth", "", q), name("森田勇", "森田勇", "森田", "勇", "birth", "", q)), morita);
            FamilyNameHistory.Index idx = index(store);
            String kenji = id(store, "森田健二");
            FamilyNameHistory.Name only = idx.names(kenji).get(0);
            assertEquals(1, idx.names(kenji).size(), idx.names(kenji).toString());
            assertTrue(only.workedOut() && only.kind().equals("mukoyoshi") && only.from() != null && only.from().year() == 1932, by + ": the name came with the entry: " + only);
            assertNull(idx.at(kenji, 1920), "which name he carried in 1920 is not known: " + idx.names(kenji));
            assertFalse(idx.bearers("森田", 1920).contains(kenji), "he bore no 森田 before 1932");
            assertTrue(idx.bearers("森田", 1940).contains(kenji), "he bore 森田 after 1932");
            String page = FamilyNamesFilingTest.run(store, "names", "森田健二");
            assertFalse(page.contains("the name your library files this person under"), page);
        }
        // no birth parent, or a birth parent of the 森田 family: nothing says the name came later, and it stays as it is
        for (String father : List.of("", "森田正一")) {
            LibraryStore store = store(tmp.resolve("father-" + father));
            new LibrarianIndex(store, Embeddings.none()).rebuild();
            List<FamilyAccount.Fact> facts = new ArrayList<>(List.of(new FamilyAccount.Fact("森田健二", "born-on", "1905", "", q), new FamilyAccount.Fact("森田健二", "sex", "male", "", q),
                    new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", q, Map.of("how", "mukoyoshi"))));
            if (!father.isEmpty()) facts.add(new FamilyAccount.Fact("森田健二", "child-of", father, "", q));
            fileWith(store, "file:///family/book.txt", facts, father.isEmpty() ? List.of() : List.of(name(father, father, "森田", "正一", "birth", "", q)), morita);
            FamilyNameHistory.Index idx = index(store);
            String kenji = id(store, "森田健二");
            assertFalse(idx.names(kenji).get(0).workedOut(), (father.isEmpty() ? "no birth parent" : "a father of the 森田 family") + ": " + idx.names(kenji));
        }
    }

    /**
     * f-final-2: 森田健二, born 1905, whose mother came from the 遠藤 family, succeeded as head of the 森田 family in 1932. A succession brings
     * nobody in from outside, so his one name is not dated from 1932, and he bears 森田 before it. A grandson adopted as heir by his
     * grandfather, whose father is written by his given name alone, is not dated from the adoption either: the father may have carried 森田.
     */
    @Test
    void aSoleNameIsNotDatedFromASuccessionOrWhenAParentsFamilyIsNotKnown(@TempDir Path tmp) throws Exception {
        String q = "森田健二は1905年に生まれた。母は遠藤家の出のハル。1932年に森田家の家督を相続し、戸主となった。";
        List<FamilyAccount.FamilyRead> fams = List.of(new FamilyAccount.FamilyRead("森田", "森田家", "", q), new FamilyAccount.FamilyRead("遠藤", "遠藤家", "", q));
        for (String variant : List.of("mother-by-birth-name", "mother-by-membership", "father-by-given-name", "heir-adoption-father-by-given-name")) {
            LibraryStore store = store(tmp.resolve(variant));
            new LibrarianIndex(store, Embeddings.none()).rebuild();
            List<FamilyAccount.Fact> facts = new ArrayList<>(List.of(new FamilyAccount.Fact("森田健二", "born-on", "1905", "", q)));
            if (variant.startsWith("heir")) {
                facts.add(new FamilyAccount.Fact("森田健二", "adopted-by", "森田正一", "1932", q, Map.of("kind", "heir")));
                facts.add(new FamilyAccount.Fact("森田正一", "member-of", "森田家", "", q, Map.of("role", "head")));
            } else facts.add(new FamilyAccount.Fact("森田健二", "member-of", "森田家", "1932", q, Map.of("how", "succession", "role", "head")));
            List<FamilyAccount.NameRead> names = new ArrayList<>();
            if (variant.equals("mother-by-birth-name")) { facts.add(new FamilyAccount.Fact("森田健二", "child-of", "遠藤ハル", "", q)); names.add(name("遠藤ハル", "遠藤ハル", "遠藤", "ハル", "birth", "", q)); }
            else { facts.add(new FamilyAccount.Fact("森田健二", "child-of", "ハル", "", q)); facts.add(new FamilyAccount.Fact("ハル", "member-of", "遠藤家", "", q, Map.of("how", "birth"))); }
            if (variant.contains("father-by-given-name")) facts.add(new FamilyAccount.Fact("森田健二", "child-of", "勇", "", q));
            fileWith(store, "file:///family/koseki.txt", facts, names, fams);
            FamilyNameHistory.Index idx = index(store);
            String kenji = id(store, "森田健二");
            FamilyNameHistory.Name only = idx.names(kenji).get(0);
            assertFalse(only.workedOut() || only.dated(), variant + ": nothing says he was born under another name: " + idx.names(kenji));
            assertTrue(idx.bearers("森田", 1920).contains(kenji), variant + ": he bore 森田 before 1932");
        }
    }

    /**
     * f-owner-placeholder-shown-as-name-form: once the family says who "森田健二's parent (written only as 遠藤)" is, that description is no
     * name of 遠藤正一's: it is not a written form of his name, and his names page does not say he is also written so.
     */
    @Test
    void aDescribedPersonJoinedIntoSomebodyIsNoWrittenFormOfTheirName(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        String q = "遠藤の子、森田健二は1905年に生まれた。";
        FamilyNameQuestionsTest.file(store, "file:///family/book.txt", List.of(
                new FamilyAccount.Fact("森田健二", "child-of", "森田健二's parent (written only as 遠藤)", "", q),
                new FamilyAccount.Fact("森田健二", "born-on", "1905", "", q),
                new FamilyAccount.Fact("遠藤正一", "born-on", "1875", "", "遠藤正一は1875年に生まれた。")),
                List.of(name("遠藤正一", "遠藤正一", "遠藤", "正一", "birth", "", "遠藤正一は1875年に生まれた。", "Endō Shōichi")));
        FamilyNameQuestions.Question who = FamilyNameQuestions.open(store).stream().filter(x -> x.kind().equals("family-name-alone")).findFirst().orElseThrow();
        FamilyNameQuestions.answer(store, who.code(), "c1", "", "Ann");
        String shoichi = id(store, "遠藤正一");
        assertEquals(shoichi, id(store, "森田健二's parent (written only as 遠藤)"), "joined on the family's answer");
        for (FamilyNameHistory.Name n : index(store).names(shoichi))
            assertFalse(n.texts().stream().anyMatch(t -> t.contains("written only as")), "a description is no name of his: " + index(store).names(shoichi));
        String page = FamilyNamesFilingTest.run(store, "names", "遠藤正一");
        assertFalse(page.contains("written only as"), page);
    }

    /**
     * f-married-widow-birthname: Mary Hale, born 1850, married Tom Ellis in 1875 and, as a widow, her cousin John Hale in 1890. Her name
     * Mary Hale, which the account writes her birth under, is not worked out as taken at the second marriage: it stays undated, and the
     * family is asked how it came. Mary Ellis still dates from 1875.
     */
    @Test
    void aWidowsBirthNameIsNotDatedFromASecondMarriageToSomebodyOfThatName(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        file(store, "file:///family/account.txt", List.of(
                        new FamilyAccount.Fact("Mary Hale", "born-in", "York", "1850", "Mary was born in York in 1850."),
                        new FamilyAccount.Fact("Mary Hale", "married-to", "Tom Ellis", "1875", "In 1875 Mary married Tom Ellis."),
                        new FamilyAccount.Fact("Mary Hale", "married-to", "John Hale", "1890", "In 1890 Mary married her cousin John Hale.")),
                List.of(name("Mary Hale", "Mary Ellis", "Ellis", "Mary", "", "", "In 1875 Mary married Tom Ellis.")));
        FamilyNameHistory.Index idx = index(store);
        String mary = id(store, "Mary Hale");
        FamilyNameHistory.Name ellis = idx.names(mary).stream().filter(n -> n.written().equals("Mary Ellis")).findFirst().orElseThrow();
        assertEquals("marriage", ellis.kind(), idx.names(mary).toString());
        assertEquals(1875, ellis.from().year());
        FamilyNameHistory.Name hale = idx.names(mary).stream().filter(n -> n.written().equals("Mary Hale")).findFirst().orElseThrow();
        assertFalse(hale.workedOut(), "the account writes her birth under Mary Hale, before either marriage: " + idx.names(mary));
        assertNull(hale.from(), hale.toString());
        assertTrue(FamilyNameQuestions.open(store).stream().anyMatch(q -> q.kind().equals("name-change-how") && q.people().contains(mary) && q.text().contains("get the name Mary Hale")),
                FamilyNameQuestions.open(store).stream().map(FamilyNameQuestions.Question::text).toList().toString());
    }

    /** A claim the family's answer files, with its reading, accepted as the family's word. */
    static void answer(LibraryStore store, Finding.Triple t, String sentence, Map<String, String> detail) throws Exception {
        Finding f = new Finding(store.nextFindingId(t.subject() + " " + t.predicate() + " " + t.object()), sentence, List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.low, "family-account", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source(FamilyNameQuestions.SOURCE + "a1b2c3", "as told by the family", "")), List.of(), null, sentence + "\n", t,
                List.of(FamilyDetail.note(detail, "family-account")));
        store.write(f);
        new Council(store).accept(f.id());
    }

    static Finding accepted(LibraryStore store, String s, String p, String o, String locator) throws Exception {
        Finding f = new Finding(store.nextFindingId(s + " " + p + " " + o), s + " " + p + " " + o + ".", List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.medium, "family-account", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source(locator, "n/a", "")), List.of(), null, s + " " + p + " " + o + ".\n", new Finding.Triple(s, p, o), List.of());
        store.write(f);
        return f;
    }
}
