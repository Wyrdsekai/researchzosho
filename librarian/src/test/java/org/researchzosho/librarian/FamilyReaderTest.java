package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the family reader takes from a text, and for whom: a kinship label says a relation by itself only when it is the right kind of
 * label next to one of the two names, a single family name is nobody in particular, and "I" in a book is its writer only when the
 * judge is sure the writer is speaking.
 */
class FamilyReaderTest {

    private static FamilyAccount.Fact fact(String s, String r, String o, String quote) { return new FamilyAccount.Fact(s, r, o, "", quote); }

    private static LibraryStore store(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    @Test
    void aKinshipLabelSaysTheRelationOnlyWhenItIsTheRightKindNextToOneOfTheTwoNames() {
        assertTrue(FamilyAccount.labelSays(fact("森田源三郎", "parent-of", "森田勇", "森田源三郎 （父）")));
        assertTrue(FamilyAccount.labelSays(fact("森田岳", "child-of", "森田勇", "森田岳 （二男）")));
        assertTrue(FamilyAccount.labelSays(fact("森田岳", "relative-of", "森田勇", "曾孫・ 森田岳 （衆議院議員）")));
        assertTrue(FamilyAccount.labelSays(fact("髙橋正一", "child-of", "髙橋源三郎", "長男 正一")), "a register writes the given name only");
        assertTrue(FamilyAccount.labelSays(fact("Tom Hale", "married-to", "Ann Hale", "Spouse: Ann Hale")));
        assertFalse(FamilyAccount.labelSays(fact("Ann Hale", "parent-of", "Tom Hale", "Spouse: Ann Hale")), "a spouse label says nothing of a parent");
        assertFalse(FamilyAccount.labelSays(fact("Morita Yuri", "parent-of", "Morita Isamu", "Morita Isamu - my mother (Morita Yuri) and (Endo Shin)'s uncle")), "whose mother? the note speaks as I");
        assertFalse(FamilyAccount.labelSays(fact("Morita Yuri", "parent-of", "Morita Isamu", "Morita Isamu - mother (Morita Yuri) and (Endo Shin)'s uncle")), "a third person is named");
        assertFalse(FamilyAccount.labelSays(fact("Tom Hale", "parent-of", "Kimie Hale", "Father: a soldier in the war. Tom Hale")), "the label is not next to either name");
        assertFalse(FamilyAccount.labelSays(fact("森田正一", "married-to", "森田勇", "次代 森田正一")), "a successor in an office is no relative");
    }

    @Test
    void aQuoteThatSpeaksAsIGoesToTheJudgeInsteadOfPassingOnItsLabel() {
        String text = "Morita Isamu\n\nuncle | \nmy mother | Morita Yuri | \n";
        List<String> judged = new ArrayList<>();
        FamilyAccount.Read read = FamilyAccount.read(text, "the owner of this library", new GenealogyProfile().predicates(), prompt -> """
                {"people": [], "facts": [{"subject": "Morita Yuri", "relation": "parent-of", "object": "Morita Isamu", "date": "", "quote": "my mother | Morita Yuri"}]}""",
                List.of(), null, q -> { judged.add(q); return 0.1; });
        assertEquals(1, judged.size(), "the label is a mother, and the words still go to the judge: " + judged);
        assertTrue(read.facts().isEmpty(), read.facts().toString());
    }

    @Test
    void aSingleFamilyNameIsNobodyInParticularUnlessTheLibraryHasThatPerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.Outcome o = FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("Isamu Morita", "child-of", "Hale", "Interview with Hale's son, Isamu Morita"),
                fact("Isamu Morita", "born-in", "Sendai", "Isamu Morita was born in Sendai"), fact("Kimie Hale", "child-of", "Genzaburo Hale", "Kimie, the daughter of Genzaburo Hale")), List.of()), "file:///family/community.pdf", "Kimie Hale");
        // Hale is the family name two people of the read share, and "Hale's son" names somebody by it alone: a described parent, never a person named Hale
        assertEquals(5, o.claims(), "the birthplace, both parents, and the sexes from the words son and daughter: " + o.dropped());
        assertEquals(1, o.mentions());
        assertTrue(o.dropped().isEmpty(), "a family name alone is never left out as noise: " + o.dropped());
        Graph g = FamilyPeople.view(store);
        assertNull(g.node(g.nodeIdOf("Hale")), "no person named Hale");
        String parent = "Isamu Morita's parent (written only as Hale)";
        assertTrue(store.scanFindings().findings().stream().anyMatch(f -> f.triple() != null && f.triple().predicate().equals("child-of") && f.triple().object().equals(parent)));
        assertEquals("Hale family", FamilyHouses.labelOf(g, FamilyHouses.families(g, g.nodeIdOf(parent)).get(0).family()), "linked to the family of the name, how not known");
        // the teller's own single name, a one-word name nobody else's name carries, and a one-word name the library already holds as a person, are somebody
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("Mara", "child-of", "Tom Hale", "I am Tom Hale's daughter")), List.of()), "file:///family/notes.txt", "Mara");
        assertEquals(1, FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("Haru", "child-of", "Tom Hale", "Tom's girl Haru")), List.of()), "file:///family/notes4.txt", "an aunt").claims());
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("Hart", "born-in", "York", "Hart was born in York")), List.of()), "file:///family/notes2.txt", "an aunt");
        FamilyAccount.Outcome kept = FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("Isamu Morita", "child-of", "Hart", "Hart's son Isamu Morita")), List.of()), "file:///family/notes3.txt", "an aunt");
        assertEquals(1, kept.claims(), "the parent; Isamu's sex from the word son the library has from the first text: " + kept.dropped());
        assertEquals(0, kept.mentions(), "Hart is somebody the library knows by that one name: a person, not a family name alone");
        assertTrue(store.scanFindings().findings().stream().anyMatch(f -> f.triple() != null && f.triple().subject().equals("Isamu Morita") && f.triple().predicate().equals("child-of") && f.triple().object().equals("Hart")));
        assertTrue(store.scanFindings().findings().stream().anyMatch(f -> f.triple() != null && f.triple().subject().equals("Mara") && f.triple().predicate().equals("child-of")));
    }

    static final String BOOK = "Kimie Hale\n\nThe Hale family came to Sendai in 1901.\n\nAn interview with the son of Genzaburo Hale, recorded in 1960.\n\n"
            + "When I went to London I was 12, and my father Genzaburo Hale decided to move the whole family.\n\nKimie Hale is the daughter of Tom Hale.\n";

    private static FamilyAccount.Read readBook(FamilyAccount.YesNo judge, FamilyAccount.Voice voice) {
        return FamilyAccount.read(BOOK, "Kimie Hale", new GenealogyProfile().predicates(), prompt -> """
                {"people": [], "facts": [
                 {"subject": "Genzaburo Hale", "relation": "parent-of", "object": "Kimie Hale", "date": "", "quote": "When I went to London I was 12, and my father Genzaburo Hale decided to move the whole family."},
                 {"subject": "Kimie Hale", "relation": "child-of", "object": "Tom Hale", "date": "", "quote": "Kimie Hale is the daughter of Tom Hale."}]}""", List.of(), null, judge, voice);
    }

    @Test
    void iInABookIsItsWriterOnlyWhenTheJudgeIsSureTheWriterIsSpeaking() {
        FamilyAccount.Voice book = FamilyAccount.Voice.of("Kimie Hale", "file:///family/community.pdf");
        assertTrue(book.book());
        List<String> asked = new ArrayList<>();
        FamilyAccount.Read read = readBook(q -> { asked.add(q); return 0.2; }, book);
        String kept = read.facts().toString();
        assertTrue(kept.contains("subject=Genzaburo Hale, relation=parent-of, object=the speaker in community.pdf, part 1"), kept);
        assertTrue(kept.contains("subject=Kimie Hale, relation=child-of, object=Tom Hale"), "a sentence about the writer that does not speak as I is hers: " + kept);
        assertEquals(1, asked.size(), "only the first-person words go to the judge");
        assertTrue(asked.get(0).contains("a text written by Kimie Hale") && asked.get(0).contains("An interview with the son of Genzaburo Hale"), "the judge reads the passage around the words: " + asked.get(0));
        assertTrue(read.dropped().stream().anyMatch(d -> d.contains("researchzosho graph merge \"the speaker in community.pdf, part 1\" \"Kimie Hale\"")), read.dropped().toString());
        assertTrue(FamilyQuestions.placeholder("the speaker in community.pdf, part 1"), "a quoted speaker is never searched for");

        assertTrue(readBook(q -> 0.9, book).facts().toString().contains("subject=Genzaburo Hale, relation=parent-of, object=Kimie Hale"), "a sure yes keeps the writer");
        assertTrue(readBook(null, book).facts().toString().contains("object=the speaker in community.pdf"), "with no judge, the safe side");
        assertTrue(readBook(null, FamilyAccount.Voice.of("Kimie Hale", "file:///family/notes.txt")).facts().toString().contains("object=Kimie Hale"), "short notes speak for their writer");
        assertNull(FamilyAccount.Voice.of("the writer of community.pdf", "file:///family/community.pdf"), "a stand-in writer has no voice to check");
    }

    static final String REGISTER = "Death register, Sendai, 1950\n\nGenzaburo Hale, died 12 March 1950 at Sendai, aged 80.\nInformant: Isamu Hale, son, of Sendai.\n";

    @Test
    void anInformantIsNamedByTheRecordAndDoesNotInheritItsDeath(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.Read read = FamilyAccount.read(REGISTER, "a register", new GenealogyProfile().predicates(), prompt -> {
            assertTrue(prompt.contains("the person who reported a birth or a death (informant-for)"), prompt);
            return """
                {"people": [], "facts": [
                 {"subject": "Genzaburo Hale", "relation": "died-on", "object": "12 March 1950", "date": "12 March 1950", "quote": "Genzaburo Hale, died 12 March 1950 at Sendai, aged 80."},
                 {"subject": "Isamu Hale", "relation": "informant-for", "object": "Genzaburo Hale", "date": "1950", "quote": "Informant: Isamu Hale, son, of Sendai."},
                 {"subject": "Isamu Hale", "relation": "died-in", "object": "Sendai", "date": "1950", "quote": "Informant: Isamu Hale, son, of Sendai."}]}""";
        });
        assertEquals(2, read.facts().size(), read.facts().toString());
        assertTrue(read.dropped().stream().anyMatch(d -> d.startsWith("\"Isamu Hale died in Sendai\" was left out.") && d.contains("name Isamu Hale as the person who reported the event")), read.dropped().toString());
        FamilyAccount.file(store, read, "file:///family/register.txt", "a register");
        Graph g = Graph.build(store);
        assertTrue(g.node(g.nodeIdOf("Isamu Hale")).mayBeLiving(), "the informant was alive in 1950 and may be living");
        assertEquals("person", g.node(g.nodeIdOf("Isamu Hale")).kind());
        assertFalse(g.node(g.nodeIdOf("Genzaburo Hale")).mayBeLiving(), "the man the record is about has died");
        assertEquals("person", g.node(g.nodeIdOf("Genzaburo Hale")).kind(), "the person an informant reported about is a person, not a place");
        FamilyTree.Tree tree = FamilyTree.around(store, "Genzaburo Hale", 3, 3);
        assertTrue(tree.links().isEmpty(), "an informant is not drawn as family: " + tree.links());

        // a witness who had died before the event is somebody else of the same name; a dead witness is a lead in the research question
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Ellis", "died-on", "1870", "", "Tom Ellis died in 1870."),
                new FamilyAccount.Fact("Tom Ellis", "witness-for", "Genzaburo Hale", "1895", "Witnesses: Tom Ellis")), List.of()), "file:///family/notes.txt", "an aunt");
        String problems = FamilyChecks.render(FamilyChecks.check(store));
        assertTrue(problems.contains("[impossible] Tom Ellis died in 1870, before being a witness at an event of Genzaburo Hale in 1895."), problems);
        String q = FamilyQuestions.around(store, "Genzaburo Hale", 2, 2).stream().filter(a -> a.person().equals("Genzaburo Hale")).findFirst().orElseThrow().question();
        assertTrue(q.contains("People the records name beside this person, as leads: Tom Ellis was a witness at an event of Genzaburo Hale."), q);
        assertFalse(q.contains("Isamu Hale"), "the living informant is not named to a search service: " + q);
        assertTrue(FamilyIdentity.facts(g = Graph.build(store), g.nodeIdOf("Genzaburo Hale")).stream().anyMatch(k -> k.kind().equals("associate") && k.names().contains("Isamu Hale")), "an associate's name is a word a page about him may carry");
    }

    @Test
    void aGodparentOrAWitnessInAFamilyFileIsKeptAsSuch(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path ged = tmp.resolve("hale.ged");
        Files.writeString(ged, "0 HEAD\n1 CHAR UTF-8\n0 @I1@ INDI\n1 NAME Mari /Hale/\n1 BIRT\n2 DATE 1880\n1 DEAT\n2 DATE 1950\n1 ASSO @I2@\n2 RELA Godfather\n1 ASSO @I3@\n2 RELA Friend\n"
                + "0 @I2@ INDI\n1 NAME Tom /Ellis/\n1 BIRT\n2 DATE 1850\n1 DEAT\n2 DATE 1920\n0 @I3@ INDI\n1 NAME Ann /Hart/\n1 BIRT\n2 DATE 1860\n1 DEAT\n2 DATE 1930\n0 TRLR\n", StandardCharsets.UTF_8);
        Gedcom.Outcome o = Gedcom.importFile(store, ged);
        assertTrue(store.scanFindings().findings().stream().anyMatch(f -> f.triple() != null && f.triple().subject().equals("Tom Ellis") && f.triple().predicate().equals("godparent-of") && f.triple().object().equals("Mari Hale")));
        assertTrue(o.problems().stream().anyMatch(p -> p.startsWith("1 entry of the kind the file calls ASSO (associates) was not imported")), "a friend is not a role the library reads: " + o.problems());
    }

    static final String KOSEKI = "戸籍 森田勇\n\n父 不詳\n母 森田ふさ\n\n明治13年生 昭和25年死亡\n\n配偶者 | |\n";

    @Test
    void aRecordThatSaysAParentIsUnknownIsKeptAsEvidenceAndAsksWhereToLookNext(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        FamilyAccount.Read read = FamilyAccount.read(KOSEKI, "a register", new GenealogyProfile().predicates(), prompt -> {
            assertTrue(prompt.contains("give that relation with the object \"(unknown: <the record's words>)\""), prompt);
            return """
                {"people": [], "facts": [
                 {"subject": "森田勇", "relation": "child-of", "object": "(unknown: 父 不詳)", "date": "", "quote": "父 不詳"},
                 {"subject": "森田勇", "relation": "child-of", "object": "森田ふさ", "date": "", "quote": "母 森田ふさ"},
                 {"subject": "森田勇", "relation": "married-to", "object": "(blank)", "date": "", "quote": "配偶者 | |"},
                 {"subject": "森田勇", "relation": "adopted-by", "object": "(unknown: none)", "date": "", "quote": "母 森田ふさ"},
                 {"subject": "森田勇", "relation": "born-on", "object": "明治13年", "date": "明治13年", "quote": "明治13年生"},
                 {"subject": "森田勇", "relation": "died-on", "object": "昭和25年", "date": "昭和25年", "quote": "昭和25年死亡"}]}""";
        });
        String facts = read.facts().toString();
        assertTrue(facts.contains("object=森田勇's father (unknown: 父 不詳)"), facts);
        assertTrue(facts.contains("object=森田勇's husband or wife (unknown: left blank in the record)"), "the empty spouse column: " + facts);
        assertTrue(read.dropped().stream().anyMatch(d -> d.startsWith("That 森田勇's adoptive parent is not known was left out")), "words that do not say unknown prove nothing: " + read.dropped());
        FamilyAccount.file(store, read, "file:///family/koseki.txt", "a register");

        List<FamilyQuestions.Ask> asks = FamilyQuestions.around(store, "森田勇", 2, 2);
        FamilyQuestions.Ask isamu = asks.stream().filter(a -> a.person().equals("森田勇")).findFirst().orElseThrow();
        assertTrue(isamu.questions().contains("A record gives 森田勇's father as unknown (\"父 不詳\"). Who was 森田勇's father? Look for a later record that names him: an acknowledgement or a legitimation of the child, the mother's marriage, or the mother's own family records."), isamu.questions().toString());
        assertTrue(asks.stream().noneMatch(a -> a.person().contains("unknown")), "the unknown father is never searched for as a person");
        FamilyTree.Tree tree = FamilyTree.around(store, "森田勇", 2, 2);
        assertEquals(1, tree.links().size(), "the tree draws the mother; the unknown father is a question, not a box: " + tree.links());

        // when a later record names a father, the unknown is not counted as a third parent
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("森田源三郎", "parent-of", "森田勇", "", "q")), List.of()), "https://example.org/morita", "a page");
        assertFalse(FamilyChecks.render(FamilyChecks.check(store)).contains("birth parents"), FamilyChecks.render(FamilyChecks.check(store)));
        assertTrue(FamilyAccount.blankCell("Father: \nMother: Ann Hart", "Father:", "child-of"));
        assertFalse(FamilyAccount.blankCell("Father: Tom Ellis\nMother: Ann Hart", "Father:", "child-of"), "a filled cell is not blank");
    }

    @Test
    void aNameReadTwoWaysIsKeptBothWaysAndSaidSo(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        String text = "森田勇（もりた いさむ）は明治13年に生まれた。\n\n森田勇（もりた ゆう）は仙台に住んだ。";
        FamilyAccount.Read read = FamilyAccount.read(text, "an aunt", new GenealogyProfile().predicates(), prompt -> """
                {"people": [{"name": "森田勇", "reading": "もりた いさむ", "also": [], "living": false}, {"name": "森田勇", "reading": "モリタ イサム", "also": [], "living": false},
                            {"name": "森田勇", "reading": "もりた ゆう", "also": [], "living": false}, {"name": "森田勇", "reading": "Morita Isamu", "also": [], "living": false}],
                 "facts": [{"subject": "森田勇", "relation": "born-on", "object": "明治13年", "date": "明治13年", "quote": "森田勇（もりた いさむ）は明治13年に生まれた。"}]}""");
        assertEquals(1, read.twoWays().size(), "katakana and romanised forms of the same reading are one reading");
        assertArrayEquals(new String[]{"森田勇", "もりた いさむ", "もりた ゆう"}, read.twoWays().get(0));
        FamilyAccount.Outcome o = FamilyAccount.file(store, read, "file:///family/notes.txt", "an aunt");
        assertEquals(List.of("The text reads 森田勇's name two ways: もりた いさむ and もりた ゆう. Both are kept as other names of 森田勇. The reading decides what a search looks for, so a record that gives the reading, such as a register with the reading beside the name, tells which is right."), o.twoWays());
        Finding born = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("born-on")).findFirst().orElseThrow();
        assertTrue(born.notes().stream().anyMatch(n -> n.kind().equals("read-two-ways") && n.text().equals("read two ways in file:///family/notes.txt: もりた いさむ / もりた ゆう")), born.notes().toString());
        String checked = FamilyChecks.render(FamilyChecks.check(store));
        assertTrue(checked.contains("[read-two-ways] 森田勇 is read 2 ways in your sources: もりた いさむ, もりた ゆう."), checked);
        assertTrue(FamilyChecks.forPerson(store, FamilyChecks.check(store)).contains("NAMES THAT ARE READ TWO WAYS (1)"));
    }
}
