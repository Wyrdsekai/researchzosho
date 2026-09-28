package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the reader takes from a text about names and families, and what it keeps of what the model says: a name only when its words and
 * the name itself are in the text, a kind only when the words say it, a date only when the words date the name's coming, a family only
 * when the words speak of it as a family, and 婿養子 as its three facts.
 */
class FamilyReaderNamesTest {

    static final String SCHOOL = "Endo's son, Morita Kenji, went to the village school in 1920.";
    static final String ENTERED = "In 1932 he entered the Morita family (森田家), whose head was Morita Isamu, as mukoyōshi (婿養子) of Isamu, and married Isamu's daughter Haru.";
    static final String SHOP = "森田健二（もりた けんじ）ran the family shop for thirty years.";
    static final String HEIR = "Isamu's son Morita Shōji (森田正二) left the Morita family in 1940, when he was adopted as heir into the Takahashi family (髙橋家), and became Takahashi Shōji (髙橋正二).";
    static final String BOOK = SCHOOL + " " + ENTERED + " " + SHOP + " " + HEIR + "\n";

    static FamilyAccount.Read read(String text, String json) { return read(text, json, null); }

    static FamilyAccount.Read read(String text, String json, FamilyAccount.YesNo judge) {
        return FamilyAccount.read(text, "an aunt", new GenealogyProfile().predicates(), prompt -> json, List.of(), null, judge);
    }

    private static boolean has(FamilyAccount.Read r, String s, String rel, String o) {
        return r.facts().stream().anyMatch(f -> f.subject().equals(s) && f.relation().equals(rel) && f.object().equals(o));
    }

    private static FamilyAccount.Fact fact(FamilyAccount.Read r, String s, String rel, String o) {
        return r.facts().stream().filter(f -> f.subject().equals(s) && f.relation().equals(rel) && f.object().equals(o)).findFirst().orElseThrow(() -> new AssertionError(s + " " + rel + " " + o + " in " + r.facts()));
    }

    static final String WHOLE = """
            {"people": [{"name": "Morita Kenji", "family": "Morita", "given": "Kenji", "also": ["森田健二", "もりた けんじ"]}, {"name": "Endo", "family": "Endo"},
                        {"name": "Morita Isamu", "family": "Morita", "given": "Isamu"}, {"name": "Morita Haru", "family": "Morita", "given": "Haru"},
                        {"name": "Morita Shōji", "family": "Morita", "given": "Shōji", "also": ["森田正二"]}],
             "facts": [{"subject": "Morita Kenji", "relation": "child-of", "object": "Endo", "date": "", "quote": "%s", "only_family_name": true},
                       {"subject": "Morita Kenji", "relation": "adopted-by", "object": "Morita Isamu", "date": "1932", "kind": "mukoyoshi", "quote": "%s"},
                       {"subject": "Morita Haru", "relation": "child-of", "object": "Morita Isamu", "date": "", "quote": "%s"},
                       {"subject": "Morita Isamu", "relation": "member-of", "object": "森田家", "role": "head", "date": "", "quote": "%s"},
                       {"subject": "Morita Shōji", "relation": "child-of", "object": "Morita Isamu", "date": "", "quote": "%s"},
                       {"subject": "Morita Shōji", "relation": "member-of", "object": "髙橋家", "how": "adoption", "role": "heir", "date": "1940", "quote": "%s"}],
             "names": [{"person": "Morita Shōji", "name": "髙橋正二", "family": "髙橋", "given": "正二", "also": ["Takahashi Shōji"], "kind": "adoptive", "said": "adopted as heir", "date": "1940", "quote": "%s"}],
             "families": [{"name": "森田", "written": "森田家", "seat": "", "quote": "%s"}, {"name": "髙橋", "written": "髙橋家", "seat": "", "quote": "%s"}]}
            """.formatted(SCHOOL, ENTERED, ENTERED, ENTERED, HEIR, HEIR, HEIR, ENTERED, HEIR);

    @Test
    void namesFamiliesAndTheMentionAreRead(@TempDir Path tmp) throws Exception {
        FamilyAccount.Read r = read(BOOK, WHOLE);
        assertEquals(1, r.names().size(), r.names() + " " + r.dropped());
        FamilyAccount.NameRead n = r.names().get(0);
        assertEquals("髙橋正二", n.name());
        assertEquals("adoptive", n.kind(), "the quote says adopted");
        assertEquals("1940", n.date(), "the quote dates the name's coming: became Takahashi Shōji in 1940");
        assertEquals(List.of("森田", "髙橋"), r.families().stream().map(FamilyAccount.FamilyRead::name).toList());
        // the text writes 森田健二 with the reading that spells Morita Kenji, and Morita Shōji with 森田正二 in brackets: both are filed in characters
        assertEquals("true", fact(r, "森田健二", "child-of", "Endo").detail().get("only-family-name"), "the model's mark rides with the fact to the check");
        assertEquals("head", fact(r, "Morita Isamu", "member-of", "森田家").detail().get("role"), "the quote says whose head he was");
        assertEquals("adoption", fact(r, "森田正二", "member-of", "髙橋家").detail().get("how"));
        assertEquals("heir", fact(r, "森田正二", "member-of", "髙橋家").detail().get("role"));
        assertEquals("Morita", r.people().stream().filter(p -> p.name().equals("森田健二")).findFirst().orElseThrow().family(), "a person's parts are read");
        assertTrue(r.people().stream().filter(p -> p.name().equals("森田健二")).findFirst().orElseThrow().also().contains("Morita Kenji"), "and the Latin letters are another spelling");
        // filed: the party written only by a family name is a described person, never a person named Endo
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.Outcome o = FamilyAccount.file(store, r, "file:///family/book.txt", "an aunt");
        assertEquals(1, o.mentions());
        assertEquals(1, o.names());
        assertEquals(2, o.families());
        Graph g = FamilyPeople.view(store);
        assertNull(g.node(g.nodeIdOf("Endo")), "no person named Endo");
        String parent = "森田健二's parent (written only as Endo)";
        assertEquals("person", g.node(g.nodeIdOf(parent)).kind());
        assertEquals(List.of("Endo family"), FamilyHouses.families(g, g.nodeIdOf(parent)).stream().map(m -> FamilyHouses.labelOf(g, m.family())).toList(), "linked to the family of the name");
        assertEquals("child-of", store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().object().equals(parent)).findFirst().orElseThrow().triple().predicate(),
                "\"son\" stays a child, neither birth nor adoption");
    }

    @Test
    void aNameWhoseWordsAreNotInTheTextIsLeftOutAndSaid() {
        String json = """
                {"people": [], "facts": [],
                 "names": [{"person": "Morita Kenji", "name": "Endo Kenji", "family": "Endo", "given": "Kenji", "kind": "birth", "date": "", "quote": "Kenji was born Endo Kenji in a village near Hiroshima."},
                           {"person": "Morita Shōji", "name": "髙橋健二", "family": "髙橋", "given": "健二", "kind": "adoptive", "date": "1940", "quote": "%s"}]}
                """.formatted(HEIR);
        FamilyAccount.Read r = read(BOOK, json);
        assertTrue(r.names().isEmpty(), r.names().toString());
        assertTrue(r.dropped().contains("The name Endo Kenji of Morita Kenji was left out, because the library could not find the words in the text that say it. The words it was given were: \"Kenji was born Endo Kenji in a village near Hiroshima.\""), r.dropped().toString());
        assertTrue(r.dropped().stream().anyMatch(d -> d.startsWith("The name 髙橋健二 of Morita Shōji was left out, because the words given for it (") && d.endsWith(") do not have that name in them.")), r.dropped().toString());
    }

    @Test
    void mukoyoshiIsKeptOnlyWhenTheWordsSayItAndIsOtherwiseAQuestionWithTheModelsWord() {
        String plain = "In 1932 Kenji entered the Morita family and became Morita Kenji (森田健二).";
        String text = plain + "\n\n" + ENTERED + " Morita Kenji (森田健二) ran the shop.\n";
        String json = """
                {"people": [], "facts": [],
                 "names": [{"person": "Endo Kenji", "name": "森田健二", "family": "森田", "given": "健二", "kind": "mukoyoshi", "said": "", "date": "1932", "quote": "%s"}]}
                """;
        List<String> judged = new ArrayList<>();
        FamilyAccount.Read unsure = read(text, json.formatted(plain), q -> { judged.add(q); return 0.3; });
        FamilyAccount.NameRead n = unsure.names().get(0);
        assertEquals("unknown", n.kind(), "the words do not say 婿養子, and the judge is not sure");
        assertEquals("mukoyoshi", n.said(), "the model's word is kept for the question");
        assertEquals("1932", n.date(), "became Morita Kenji (森田健二) in 1932 dates the name");
        assertEquals(1, judged.size());
        assertTrue(judged.get(0).contains("Endo Kenji was named 森田健二 on entering the family as 婿養子 (adopted and married)"), judged.get(0));
        assertEquals("mukoyoshi", read(text, json.formatted(plain), q -> 0.97).names().get(0).kind(), "a sure judge keeps it");
        judged.clear();
        String said = ENTERED + " Morita Kenji (森田健二) ran the shop.";
        FamilyAccount.Read kept = read(text, json.formatted(said), q -> { judged.add(q); return 0.0; });
        assertEquals("mukoyoshi", kept.names().get(0).kind(), "the quote says 婿養子 in words");
        assertTrue(judged.isEmpty(), "the words say it, so nobody is asked: " + judged);
    }

    @Test
    void aNarrativesUseOfANameGivesItNoDate() {
        String json = """
                {"people": [], "facts": [],
                 "names": [{"person": "遠藤健二", "name": "Morita Kenji", "family": "Morita", "given": "Kenji", "kind": "", "date": "1920", "quote": "%s"}]}
                """.formatted(SCHOOL);
        FamilyAccount.Read r = read(BOOK, json);
        assertEquals(1, r.names().size(), r.dropped().toString());
        assertEquals("", r.names().get(0).date(), "going to school in 1920 as Morita Kenji says nothing of when he came to be named so");
        assertEquals("unknown", r.names().get(0).kind());
        assertTrue(r.dropped().stream().anyMatch(d -> d.startsWith("The date 1920 was not kept as the date 遠藤健二 came to be named Morita Kenji")), r.dropped().toString());
        // the person's own name, with nothing said of how it came, is not a name of its own
        String own = """
                {"people": [], "facts": [], "names": [{"person": "Morita Kenji", "name": "Morita Kenji", "kind": "", "date": "", "quote": "%s"}]}
                """.formatted(SCHOOL);
        assertTrue(read(BOOK, own).names().isEmpty());
    }

    @Test
    void theMukoyoshiQuoteGivesTheAdoptionTheMarriageAndTheEntryEvenWhenTheModelGaveOne() {
        String json = """
                {"people": [],
                 "facts": [{"subject": "Morita Kenji", "relation": "adopted-by", "object": "Morita Isamu", "date": "1932", "quote": "%s"},
                           {"subject": "Morita Haru", "relation": "child-of", "object": "Morita Isamu", "date": "", "quote": "%s"}],
                 "families": [{"name": "森田", "written": "森田家", "seat": "", "quote": "%s"}]}
                """.formatted(ENTERED, ENTERED, ENTERED);
        FamilyAccount.Read r = read(BOOK, json);
        assertEquals("mukoyoshi", fact(r, "Morita Kenji", "adopted-by", "Morita Isamu").detail().get("kind"), "the words say 婿養子");
        assertTrue(has(r, "Morita Kenji", "married-to", "Morita Haru"), "the marriage half, to the daughter the quote names: " + r.facts());
        FamilyAccount.Fact entered = fact(r, "Morita Kenji", "member-of", "森田家");
        assertEquals("mukoyoshi", entered.detail().get("how"));
        assertEquals("1932", entered.date());
        assertEquals(ENTERED, entered.quote(), "every half from the same words");
        // a quote that names no wife: the adoption and the entry, and the marriage is the family's to answer
        String alone = "In 1932 Kenji entered the Morita family (森田家) as 婿養子 of Morita Isamu.";
        FamilyAccount.Read half = read(alone + "\n", """
                {"people": [], "facts": [{"subject": "Morita Kenji", "relation": "member-of", "object": "森田家", "how": "mukoyoshi", "date": "1932", "quote": "%s"}]}
                """.formatted(alone));
        assertEquals(1, half.facts().size(), "nobody to adopt him or to marry is named in the words: " + half.facts());
    }

    @Test
    void aFamilyIsKeptOnlyWhenTheWordsSpeakOfItAsAFamily() {
        String text = "Morita Isamu ran a shop in Kure. He was a friend of Hart.\n";
        String json = """
                {"people": [], "facts": [{"subject": "Morita Isamu", "relation": "member-of", "object": "Hart", "date": "", "quote": "He was a friend of Hart."}],
                 "families": [{"name": "Morita", "written": "Morita", "seat": "Kure", "quote": "Morita Isamu ran a shop in Kure."}]}
                """;
        FamilyAccount.Read r = read(text, json);
        assertTrue(r.families().isEmpty() && r.facts().isEmpty(), r.families() + " " + r.facts());
        assertTrue(r.dropped().contains("The Morita family was left out, because the words given for it (\"Morita Isamu ran a shop in Kure.\") do not speak of it as a family."), r.dropped().toString());
        assertTrue(r.dropped().stream().anyMatch(d -> d.contains("do not speak of Hart as a family")), r.dropped().toString());
        // with the words, and a seat only where the words name it
        String house = "The Hale family of York kept the inn.";
        FamilyAccount.Read ok = read(house + "\n", """
                {"people": [], "facts": [], "families": [{"name": "Hale", "written": "The Hale family", "seat": "Leeds", "quote": "%s"}]}
                """.formatted(house));
        assertEquals(1, ok.families().size());
        assertEquals("", ok.families().get(0).seat(), "Leeds is not in the words");
    }

    @Test
    void thePromptAsksForNamesAndFamiliesInPositiveWords() {
        String p = FamilyAccount.prompt("text", "an aunt", new GenealogyProfile().predicates());
        assertTrue(p.contains("Give each other name in `names`") && p.contains("goes in `families`") && p.contains("\"only_family_name\": true"), p);
        assertTrue(p.contains("\"names\": [{\"person\": \"山田太郎\", \"name\": \"遠藤太郎\""), "the example answer has a name and a family");
        for (String no : List.of("Do not", "do not", "Never", "never", "Don't")) assertFalse(p.substring(p.indexOf("People may carry"), p.indexOf("\"only_family_name\": true")).contains(no), no);
    }

    // ── what the real-model read showed, and what the review found ─────────────────────────────────────────────────────

    static final String HEAD = "In 1932 he married Haru (森田ハル), the only daughter of Morita Isamu (森田勇), the head of the Morita family, and entered the family as mukoyōshi (婿養子) of Isamu.";
    static final String TAKAHASHI = "Isamu's son Shōji (森田正二) left the Morita family in 1940, when he was adopted as heir into the Takahashi family (髙橋家) of Nagano, and was known afterwards as Takahashi Shōji (髙橋正二).";

    private static List<String> personLabels(Graph g) { return g.nodes().stream().filter(n -> "person".equals(n.kind())).map(Graph.Node::label).toList(); }

    private static List<Finding> claims(LibraryStore store, String predicate) {
        return store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals(predicate)).toList();
    }

    @Test
    void aFamilyWrittenAsTheOtherPartyOfARelationIsAMembershipAndNeverAPerson(@TempDir Path tmp) throws Exception {
        String json = """
                {"people": [{"name": "森田勇", "family": "森田", "given": "勇", "also": ["Morita Isamu"]}, {"name": "森田ハル", "family": "森田", "given": "ハル"},
                            {"name": "森田正二", "family": "森田", "given": "正二", "also": ["Shōji"]}],
                 "facts": [{"subject": "森田勇", "relation": "head-of-household", "object": "森田家", "date": "", "quote": "%s"},
                           {"subject": "森田ハル", "relation": "child-of", "object": "森田勇", "date": "", "quote": "%s"},
                           {"subject": "森田正二", "relation": "adopted-by", "object": "髙橋家", "date": "1940", "kind": "heir", "quote": "%s"},
                           {"subject": "森田正二", "relation": "heir-of", "object": "the Takahashi family", "date": "1940", "quote": "%s"}],
                 "families": [{"name": "森田", "written": "the Morita family", "seat": "", "quote": "%s"}, {"name": "髙橋", "written": "the Takahashi family", "seat": "Nagano", "quote": "%s"}]}
                """.formatted(HEAD, HEAD, TAKAHASHI, TAKAHASHI, HEAD, TAKAHASHI);
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.file(store, read(HEAD + " " + TAKAHASHI + "\n", json, q -> 0.0), "file:///family/shop.txt", "an aunt");
        Graph g = FamilyPeople.view(store);
        List<String> people = personLabels(g);
        for (String family : List.of("森田家", "髙橋家", "the Takahashi family", "森田 family", "髙橋 family (Nagano)")) assertFalse(people.contains(family), family + " is a family, never a person: " + people);
        List<FamilyHouses.Membership> isamu = FamilyHouses.families(g, g.nodeIdOf("森田勇"));
        assertEquals(1, isamu.size(), isamu.toString());
        assertEquals("森田 family", FamilyHouses.labelOf(g, isamu.get(0).family()));
        assertEquals("head", isamu.get(0).role(), "the head of the family is its head, a membership");
        assertEquals(List.of(g.nodeIdOf("森田勇")), FamilyHouses.heads(g, g.nodeIdOf("森田 family")).stream().map(FamilyHouses.Membership::person).toList());
        List<FamilyHouses.Membership> shoji = FamilyHouses.families(g, g.nodeIdOf("森田正二"));
        assertEquals(1, shoji.size(), "the adoption into the family and the heirship of it are one membership: " + shoji);
        assertEquals("髙橋 family (Nagano)", FamilyHouses.labelOf(g, shoji.get(0).family()));
        assertEquals("adoption", shoji.get(0).how());
        assertEquals("heir", shoji.get(0).role(), "the words say heir");
        for (String p : List.of("adopted-by", "heir-of", "head-of-household")) assertTrue(claims(store, p).isEmpty(), "no " + p + " to a family: " + claims(store, p).stream().map(Finding::triple).toList());
        // a later text that says only where the family lived: a fact of the family, and the family stays a family with its head
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("森田家", "lived-in", "広島", "", "森田家は広島に住んでいた。")), List.of()), "file:///family/letter.txt", "an aunt");
        Graph after = FamilyPeople.view(store);
        assertTrue(FamilyHouses.isFamily(after, after.nodeIdOf("森田 family")), "the family is still a family");
        assertFalse(personLabels(after).contains("森田家"), personLabels(after).toString());
        assertEquals("head", FamilyHouses.families(after, after.nodeIdOf("森田勇")).get(0).role(), "and its head is still its head");
        assertEquals("森田 family", claims(store, "lived-in").get(0).triple().subject(), "the family's own fact is about the family");
    }

    static final String SILK_TEXT = "The Endō family (遠藤家) had farmed near Kōfu for four generations. Endō Shōichi (遠藤正一) was born in 1875 and married Endō Haru in 1899. "
            + "Endo's son, Morita Kenji, told us in 1998 that his father kept silkworms and sold the cocoons in Kōfu every autumn. Shōichi's son Kenji went to the village school in 1912.\n";
    static final String SILK = "Endo's son, Morita Kenji, told us in 1998 that his father kept silkworms and sold the cocoons in Kōfu every autumn.";

    @Test
    void aPartyTheWordsWriteOnlyByItsFamilyNameIsAMentionWithTheModelsPickOfferedFirst(@TempDir Path tmp) throws Exception {
        String json = """
                {"people": [{"name": "遠藤正一", "family": "遠藤", "given": "正一", "also": ["Endō Shōichi"]}, {"name": "森田健二", "family": "森田", "given": "健二", "also": ["Morita Kenji"]}],
                 "facts": [{"subject": "遠藤正一", "relation": "born-on", "object": "1875", "date": "", "quote": "Endō Shōichi (遠藤正一) was born in 1875 and married Endō Haru in 1899."},
                           {"subject": "遠藤正一", "relation": "parent-of", "object": "森田健二", "date": "", "quote": "%s"},
                           {"subject": "森田健二", "relation": "child-of", "object": "遠藤正一", "date": "", "quote": "%s"}],
                 "families": [{"name": "遠藤", "written": "The Endō family", "seat": "near Kōfu", "quote": "The Endō family (遠藤家) had farmed near Kōfu for four generations."}]}
                """;
        LibraryStore store = FamilyNameHistoryTest.store(tmp.resolve("silk"));
        FamilyAccount.Outcome o = FamilyAccount.file(store, read(SILK_TEXT, json.formatted(SILK, SILK)), "file:///family/shop.txt", "an aunt");
        // the text writes him only as Morita Kenji, so the library does too
        String parent = "Morita Kenji's parent (written only as Endo)";
        List<Finding> silk = store.scanFindings().findings().stream().filter(f -> f.triple() != null && (f.triple().predicate().equals("child-of") || f.triple().predicate().equals("parent-of"))).toList();
        assertEquals(2, silk.size(), silk.toString());
        for (Finding f : silk) {
            assertFalse(f.triple().subject().equals("遠藤正一") || f.triple().object().equals("遠藤正一"), "the words write the father only as Endo: the model's pick is no link by itself: " + f.triple());
            assertTrue(f.triple().subject().equals(parent) || f.triple().object().equals(parent), f.triple().toString());
        }
        assertTrue(o.linked().isEmpty(), "no second fact agrees, so nothing is linked: " + o.linked());
        Graph g = FamilyPeople.view(store);
        List<String> cands = FamilyMentions.candidates(FamilyNameHistory.of(g), g, parent);
        assertFalse(cands.isEmpty(), "the model's pick is a candidate");
        assertEquals(g.nodeIdOf("遠藤正一"), cands.get(0), "offered first");
        // where the words carry the party's given name, the model's reading stands
        String school = "Shōichi's son Kenji went to the village school in 1912.";
        LibraryStore named = FamilyNameHistoryTest.store(tmp.resolve("named"));
        FamilyAccount.file(named, read(SILK_TEXT, json.formatted(school, school)), "file:///family/shop.txt", "an aunt");
        assertTrue(named.scanFindings().findings().stream().anyMatch(f -> f.triple() != null && f.triple().subject().equals("Morita Kenji") && f.triple().predicate().equals("child-of") && f.triple().object().equals("遠藤正一")),
                "Shōichi's son names the father by his given name");
        // "Endo's daughter" writes the wife by her father: the family name is his, and she is no person written only as Endo
        String wed = "Endo's daughter married Morita Kenji in 1932.";
        LibraryStore daughter = FamilyNameHistoryTest.store(tmp.resolve("daughter"));
        FamilyAccount.file(daughter, read(SILK_TEXT + wed + "\n", """
                {"people": [{"name": "Endō Haru", "family": "Endō", "given": "Haru"}, {"name": "Morita Kenji", "family": "Morita", "given": "Kenji"}],
                 "facts": [{"subject": "Morita Kenji", "relation": "married-to", "object": "Endō Haru", "date": "1932", "quote": "%s"}]}
                """.formatted(wed)), "file:///family/shop.txt", "an aunt");
        assertTrue(daughter.scanFindings().findings().stream().anyMatch(f -> f.triple() != null && f.triple().predicate().equals("married-to") && f.triple().object().equals("Endō Haru")),
                daughter.scanFindings().findings().stream().map(Finding::triple).toList().toString());
    }

    @Test
    void aLabelIsAFormTheTextWrites(@TempDir Path tmp) throws Exception {
        String json = """
                {"people": [{"name": "遠藤正一", "family": "遠藤", "given": "正一", "also": ["Endō Shōichi"]}, {"name": "遠藤ハル", "family": "遠藤", "given": "ハル", "also": ["Endō Haru"]}],
                 "facts": [{"subject": "遠藤正一", "relation": "married-to", "object": "遠藤ハル", "date": "1899", "quote": "Endō Shōichi (遠藤正一) was born in 1875 and married Endō Haru in 1899."}]}
                """;
        FamilyAccount.Read r = read(SILK_TEXT, json);
        assertEquals(List.of("遠藤正一", "Endō Haru"), r.people().stream().map(FamilyAccount.Person::name).toList(), "the text writes her Endō Haru; 遠藤ハル is written nowhere in it");
        assertTrue(has(r, "遠藤正一", "married-to", "Endō Haru"), r.facts().toString());
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.file(store, r, "file:///family/shop.txt", "an aunt");
        Graph g = FamilyPeople.view(store);
        assertTrue(personLabels(g).contains("Endō Haru"), personLabels(g).toString());
        assertFalse(personLabels(g).contains("遠藤ハル"));
        assertFalse(g.node(g.nodeIdOf("Endō Haru")).aliases().contains("遠藤ハル"), "a form the text never writes is kept nowhere");
        // a name a register builds from the household's family name and the given name the text writes is the text's own
        String reg = "戸主 髙橋勇\\n長男 正一 明治三十年生";
        FamilyAccount.Read built = read("戸主 髙橋勇\n長男 正一 明治三十年生\n", """
                {"people": [{"name": "髙橋正一", "family": "髙橋", "given": "正一"}], "facts": [{"subject": "髙橋正一", "relation": "child-of", "object": "髙橋勇", "date": "", "quote": "%s"}]}
                """.formatted(reg));
        assertEquals(List.of("髙橋正一"), built.people().stream().map(FamilyAccount.Person::name).toList());
    }

    @Test
    void aFamilyWordBesideNoFamilyNameIsNoFamilyWithoutASecondLook() {
        String text = "Tom Hart was a member of the House of Commons from 1880 to 1895. His wife Ruth Hart was a member of the Quakers. In 1932 Morita Kenji entered the family as 婿養子 of Isamu.\n";
        String json = """
                {"people": [{"name": "Tom Hart", "family": "Hart", "given": "Tom"}, {"name": "Ruth Hart", "family": "Hart", "given": "Ruth"}],
                 "facts": [{"subject": "Tom Hart", "relation": "member-of", "object": "the House of Commons", "date": "1880", "quote": "Tom Hart was a member of the House of Commons from 1880 to 1895."},
                           {"subject": "Ruth Hart", "relation": "member-of", "object": "the Quakers", "date": "", "quote": "His wife Ruth Hart was a member of the Quakers."},
                           {"subject": "Morita Kenji", "relation": "member-of", "object": "the family", "date": "1932", "how": "mukoyoshi", "quote": "In 1932 Morita Kenji entered the family as 婿養子 of Isamu."}]}
                """;
        List<String> judged = new ArrayList<>();
        FamilyAccount.Read r = read(text, json, q -> { judged.add(q); return 0.0; });
        assertTrue(r.facts().stream().noneMatch(f -> f.relation().equals("member-of")), "a house of parliament, a religious society and a family nobody names are no family: " + r.facts());
        assertEquals(2, judged.size(), "the Commons and the Quakers are asked about once each; a family the words do not name is not: " + judged);
        assertTrue(r.dropped().stream().anyMatch(d -> d.contains("the family") && d.contains("do not name")), r.dropped().toString());
        // the words a family is named with, beside a family name a person of the read carries: kept without a second look
        String hales = "Mary Hale was the eldest of the Hales of York. The Ellis family kept the inn.";
        judged.clear();
        FamilyAccount.Read ok = read(hales + "\n", """
                {"people": [{"name": "Mary Hale", "family": "Hale", "given": "Mary"}, {"name": "Tom Ellis", "family": "Ellis", "given": "Tom"}],
                 "facts": [{"subject": "Mary Hale", "relation": "member-of", "object": "the Hales", "date": "", "quote": "Mary Hale was the eldest of the Hales of York."},
                           {"subject": "Tom Ellis", "relation": "member-of", "object": "the Ellis family", "date": "", "quote": "The Ellis family kept the inn."}]}
                """, q -> { judged.add(q); return 0.0; });
        assertEquals(2, ok.facts().stream().filter(f -> f.relation().equals("member-of")).count(), ok.dropped().toString());
        assertTrue(judged.isEmpty(), judged.toString());
    }

    @Test
    void theMukoyoshiQuoteMarriesNobodyTheWordsDoNotCallAWifeOrADaughter() {
        String q = "In 1932 Kenji became the mukoyōshi (婿養子) of Morita Isamu, whose son Shōji later left for the Takahashi family.";
        FamilyAccount.Read r = read(q + "\n", """
                {"people": [{"name": "Morita Shōji", "family": "Morita", "given": "Shōji"}],
                 "facts": [{"subject": "Morita Kenji", "relation": "adopted-by", "object": "Morita Isamu", "date": "1932", "kind": "mukoyoshi", "quote": "%s"},
                           {"subject": "Morita Shōji", "relation": "child-of", "object": "Morita Isamu", "date": "", "quote": "%s"}]}
                """.formatted(q, q));
        assertTrue(r.facts().stream().noneMatch(f -> f.relation().equals("married-to")), "a son is no wife: " + r.facts());
        String ja = "1932年、健二は森田勇の婿養子となった。勇の長男正二は後に髙橋家に入った。";
        FamilyAccount.Read j = read(ja + "\n", """
                {"people": [{"name": "森田正二", "family": "森田", "given": "正二"}],
                 "facts": [{"subject": "森田健二", "relation": "adopted-by", "object": "森田勇", "date": "1932", "kind": "mukoyoshi", "quote": "%s"},
                           {"subject": "森田正二", "relation": "child-of", "object": "森田勇", "date": "", "quote": "%s"}]}
                """.formatted(ja, ja));
        assertTrue(j.facts().stream().noneMatch(f -> f.relation().equals("married-to")), j.facts().toString());
    }

    @Test
    void theMukoyoshiWordsAreAboutThePersonTheyAreSaidOf() {
        String json = """
                {"people": [],
                 "facts": [{"subject": "Morita Kenji", "relation": "adopted-by", "object": "Morita Isamu", "date": "1932", "kind": "mukoyoshi", "quote": "%s"},
                           {"subject": "Morita Haru", "relation": "child-of", "object": "Morita Isamu", "date": "", "quote": "%s"},
                           {"subject": "Morita Haru", "relation": "member-of", "object": "森田家", "how": "%s", "date": "", "quote": "%s"}],
                 "families": [{"name": "森田", "written": "森田家", "seat": "", "quote": "%s"}]}
                """;
        for (String how : List.of("birth", "mukoyoshi")) {
            FamilyAccount.Read r = read(BOOK, json.formatted(ENTERED, ENTERED, how, ENTERED, ENTERED));
            assertNotEquals("mukoyoshi", fact(r, "Morita Haru", "member-of", "森田家").detail().get("how"), "the daughter did not enter her own family as 婿養子 (the model said " + how + ")");
            assertEquals("mukoyoshi", fact(r, "Morita Kenji", "member-of", "森田家").detail().get("how"), "her husband did");
        }
        String two = "In 1932 Kenji became the 婿養子 of Morita Isamu, and his brother Endo Masaru was adopted by Takahashi Isamu.";
        FamilyAccount.Read b = read(two + "\n", """
                {"people": [],
                 "facts": [{"subject": "Morita Kenji", "relation": "adopted-by", "object": "Morita Isamu", "date": "1932", "kind": "mukoyoshi", "quote": "%s"},
                           {"subject": "Endo Masaru", "relation": "adopted-by", "object": "Takahashi Isamu", "date": "", "kind": "ordinary", "quote": "%s"}]}
                """.formatted(two, two));
        assertEquals("ordinary", fact(b, "Endo Masaru", "adopted-by", "Takahashi Isamu").detail().get("kind"), "his brother's adoption stays as the words and the model give it");
        assertTrue(b.facts().stream().noneMatch(f -> f.subject().equals("Endo Masaru") && (f.relation().equals("married-to") || f.relation().equals("member-of"))), b.facts().toString());
    }

    @Test
    void aNameWhosePartsStandOnlyInsideOtherPeoplesNamesIsLeftOut() {
        String q = "In 1932 Kenji, the son of Endo Shoichi, married Morita Haru.";
        String json = """
                {"people": [{"name": "Endo Kenji", "family": "Endo", "given": "Kenji"}, {"name": "Endo Shoichi", "family": "Endo", "given": "Shoichi"}, {"name": "Morita Haru", "family": "Morita", "given": "Haru"}],
                 "facts": [{"subject": "Endo Kenji", "relation": "married-to", "object": "Morita Haru", "date": "1932", "quote": "%s"}],
                 "names": [{"person": "Endo Kenji", "name": "Morita Kenji", "family": "Morita", "given": "Kenji", "kind": "marriage", "date": "1932", "quote": "%s"}]}
                """.formatted(q, q);
        FamilyAccount.Read r = read(q + "\n", json);
        assertTrue(r.names().isEmpty(), "Morita stands only in his wife's name: " + r.names());
        assertTrue(r.dropped().stream().anyMatch(d -> d.startsWith("The name Morita Kenji of Endo Kenji was left out")), r.dropped().toString());
        String ja = "1932年、遠藤正一の子健二は森田ハルと結婚した。";
        FamilyAccount.Read j = read(ja + "\n", """
                {"people": [{"name": "遠藤健二", "family": "遠藤", "given": "健二"}, {"name": "遠藤正一"}, {"name": "森田ハル"}],
                 "facts": [{"subject": "遠藤健二", "relation": "married-to", "object": "森田ハル", "date": "1932", "quote": "%s"}],
                 "names": [{"person": "遠藤健二", "name": "森田健二", "family": "森田", "given": "健二", "kind": "marriage", "date": "1932", "quote": "%s"}]}
                """.formatted(ja, ja));
        assertTrue(j.names().isEmpty(), j.names().toString());
        // the family name the person took, written on its own: the name stands in the words
        String took = "In 1932 Kenji took his wife's family name, Morita.";
        FamilyAccount.Read t = read(took + "\n", """
                {"people": [], "facts": [], "names": [{"person": "Endo Kenji", "name": "Morita Kenji", "family": "Morita", "given": "Kenji", "kind": "marriage", "date": "1932", "quote": "%s"}]}
                """.formatted(took));
        assertEquals(1, t.names().size(), t.dropped().toString());
    }

    @Test
    void aBirthYearBeforeTheNameDoesNotDateItsComing() {
        String q = "Born in 1905, Morita Kenji went to the village school in 1920.";
        FamilyAccount.Read r = read(q + "\n", """
                {"people": [], "facts": [], "names": [{"person": "Morita Kenji", "name": "Morita Kenji", "family": "Morita", "given": "Kenji", "kind": "", "date": "1920", "quote": "%s"}]}
                """.formatted(q));
        assertTrue(r.names().isEmpty() || r.names().get(0).date().isEmpty(), "going to school in 1920 as Morita Kenji does not date the name: " + r.names());
        String as = "Born in 1905 as Endō Kenji, he went to the village school in 1920.";
        FamilyAccount.Read a = read(as + "\n", """
                {"people": [], "facts": [], "names": [{"person": "Morita Kenji", "name": "Endō Kenji", "family": "Endō", "given": "Kenji", "kind": "", "date": "1920", "quote": "%s"}]}
                """.formatted(as));
        assertEquals("", a.names().get(0).date(), "the year written with the words that say the name came is 1905, not 1920");
        FamilyAccount.Read right = read(as + "\n", """
                {"people": [], "facts": [], "names": [{"person": "Morita Kenji", "name": "Endō Kenji", "family": "Endō", "given": "Kenji", "kind": "", "date": "1905", "quote": "%s"}]}
                """.formatted(as));
        assertEquals("1905", right.names().get(0).date(), "the year written with them dates it");
        FamilyAccount.Read heir = read(BOOK, """
                {"people": [], "facts": [], "names": [{"person": "Morita Shōji", "name": "髙橋正二", "family": "髙橋", "given": "正二", "kind": "adoptive", "date": "1940", "quote": "%s"}]}
                """.formatted(HEIR));
        assertEquals("1940", heir.names().get(0).date(), "the one year of the sentence that says the name came");
    }

    @Test
    void aFamilyNameAloneAsTheSubjectOfALifeFactIsNeverAPerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Endo", "", List.of(), "Endo", "")),
                List.of(new FamilyAccount.Fact("Endo", "died-on", "1921", "", "Endo died in 1921.", Map.of("only-family-name", "true"))), List.of()), "file:///family/letter1.txt", "an aunt");
        Graph g = FamilyPeople.view(store);
        assertFalse(personLabels(g).contains("Endo"), "no person named Endo: " + personLabels(g));
        Finding died = claims(store, "died-on").get(0);
        assertTrue(FamilyMentions.isMention(died.triple().subject()), "somebody of the Endo family, written only as Endo: " + died.triple());
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Endo", "", List.of(), "Endo", ""), new FamilyAccount.Person("Morita Haru", "", List.of(), "Morita", "Haru")),
                List.of(new FamilyAccount.Fact("Morita Haru", "child-of", "Endo", "", "Endo's daughter Morita Haru married in 1925.", Map.of("only-family-name", "true"))), List.of()), "file:///family/letter2.txt", "an aunt");
        Finding child = claims(store, "child-of").get(0);
        assertEquals("Morita Haru's parent (written only as Endo)", child.triple().object(), "never a child of a person named Endo");
        // in one read, the life fact goes to the person the same words describe; another sentence's Endo may be somebody else, and is asked about
        LibraryStore one = FamilyNameHistoryTest.store(tmp.resolve("one"));
        String shop = "Endo's son, Morita Kenji, ran the shop after Endo died in 1921.";
        FamilyAccount.file(one, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Endo", "", List.of(), "Endo", ""), new FamilyAccount.Person("Morita Kenji", "", List.of(), "Morita", "Kenji")),
                List.of(new FamilyAccount.Fact("Morita Kenji", "child-of", "Endo", "", shop, Map.of("only-family-name", "true")),
                        new FamilyAccount.Fact("Endo", "died-on", "1921", "", shop, Map.of("only-family-name", "true")),
                        new FamilyAccount.Fact("Endo", "lived-in", "Kōfu", "", "Endo lived in Kōfu.", Map.of("only-family-name", "true"))), List.of()), "file:///family/book.txt", "an aunt");
        assertEquals("Morita Kenji's parent (written only as Endo)", claims(one, "died-on").get(0).triple().subject());
        String home = claims(one, "lived-in").get(0).triple().subject();
        assertTrue(FamilyMentions.isMention(home) && !home.equals("Morita Kenji's parent (written only as Endo)"), "somebody of the Endo family, not taken for the father: " + home);
    }

    // ── the second review: an automatic decision that went wrong is a question now ────────────────────────────────────────

    @Test
    void aPersonsDeathBesideTheWordsForTheFamilyIsNeverTheFamilys(@TempDir Path tmp) throws Exception {
        for (String[] c : List.of(new String[]{"Endo", "Endo, the last head of the Endo family, died in 1921."}, new String[]{"遠藤", "遠藤家の当主であった遠藤は大正十年に亡くなった。"})) {
            LibraryStore store = FamilyNameHistoryTest.store(tmp.resolve(c[0]));
            FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person(c[0], "", List.of(), c[0], "")),
                    List.of(new FamilyAccount.Fact(c[0], "died-on", "1921", "", c[1], Map.of("only-family-name", "true"))), List.of()), "file:///family/letter.txt", "an aunt");
            Finding died = claims(store, "died-on").get(0);
            assertTrue(FamilyMentions.isMention(died.triple().subject()), "somebody of the family died, never the family: " + died.triple());
            Graph g = FamilyPeople.view(store);
            assertFalse(FamilyHouses.isFamily(g, g.nodeIdOf(died.triple().subject())), died.triple().toString());
        }
        // the words that write the family and nothing else keep the fact on the family
        LibraryStore family = FamilyNameHistoryTest.store(tmp.resolve("family"));
        FamilyAccount.file(family, new FamilyAccount.Read(List.of(new FamilyAccount.Person("遠藤", "", List.of(), "遠藤", "")),
                List.of(new FamilyAccount.Fact("遠藤", "lived-in", "甲府", "", "遠藤家は甲府に住んでいた。", Map.of("only-family-name", "true"))), List.of()), "file:///family/letter.txt", "an aunt");
        Graph fg = FamilyPeople.view(family);
        assertTrue(FamilyHouses.isFamily(fg, fg.nodeIdOf(claims(family, "lived-in").get(0).triple().subject())), claims(family, "lived-in").get(0).triple().toString());
    }

    @Test
    void aBrothersAdoptionInTheMukoyoshiSentenceNeverMakesHimThe婿養子() {
        String q = "In 1932 Kenji entered the Morita family (森田家) as 婿養子 of Morita Isamu, and that year his brother Masaru was adopted by Takahashi Shōichi.";
        for (String kind : List.of("", "heir")) {
            String json = """
                    {"people": [{"name": "Morita Kenji", "family": "Morita", "given": "Kenji"}, {"name": "Masaru", "given": "Masaru"}],
                     "facts": [{"subject": "Morita Kenji", "relation": "member-of", "object": "森田家", "how": "mukoyoshi", "date": "1932", "quote": "%s"},
                               {"subject": "Masaru", "relation": "adopted-by", "object": "Takahashi Shōichi", "date": "1932", "kind": "%s", "quote": "%s"}],
                     "families": [{"name": "森田", "written": "森田家", "seat": "", "quote": "%s"}]}
                    """.formatted(q, kind, q, q);
            FamilyAccount.Read r = read(q + "\n", json, x -> 0.0);
            assertEquals("mukoyoshi", fact(r, "Morita Kenji", "member-of", "森田家").detail().get("how"), "Kenji entered as 婿養子 (the brother's kind: '" + kind + "')");
            assertNotEquals("mukoyoshi", fact(r, "Masaru", "adopted-by", "Takahashi Shōichi").detail().get("kind"), "his brother's adoption is no 婿養子 (kind '" + kind + "')");
            assertTrue(r.facts().stream().noneMatch(f -> f.subject().equals("Masaru") && f.relation().equals("member-of")), "his brother enters no family from these words: " + r.facts());
        }
        // an adoption without its kind in words that say 婿養子 of somebody else: nobody is made the 婿養子
        String other = "In 1932 Kenji entered the Morita family (森田家) as 婿養子, and that year his brother Masaru was adopted by Takahashi Shōichi.";
        FamilyAccount.Read r = read(other + "\n", """
                {"people": [], "facts": [{"subject": "Masaru", "relation": "adopted-by", "object": "Takahashi Shōichi", "date": "1932", "quote": "%s"}],
                 "families": [{"name": "森田", "written": "森田家", "seat": "", "quote": "%s"}]}
                """.formatted(other, other), x -> 0.0);
        assertTrue(r.facts().stream().noneMatch(f -> f.subject().equals("Masaru") && f.relation().equals("member-of")), r.facts().toString());
        assertNotEquals("mukoyoshi", fact(r, "Masaru", "adopted-by", "Takahashi Shōichi").detail().get("kind"));
    }

    @Test
    void aBrothersAdoptionFiledFromTheMukoyoshiSentenceIsNoMukoyoshiDownstreamEither(@TempDir Path tmp) throws Exception {
        String other = "In 1932 Kenji entered the Morita family (森田家) as 婿養子, and that year his brother Masaru was adopted by Takahashi Shōichi.";
        FamilyAccount.Read r = read(other + "\n", """
                {"people": [], "facts": [{"subject": "Masaru", "relation": "adopted-by", "object": "Takahashi Shōichi", "date": "1932", "quote": "%s"}],
                 "families": [{"name": "森田", "written": "森田家", "seat": "", "quote": "%s"}]}
                """.formatted(other, other), x -> 0.0);
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyAccount.fileAsRead(store, r, "an aunt", f -> List.of("file:///family/book.txt"), f -> List.of());
        Finding adoption = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().subject().equals("Masaru") && f.triple().predicate().equals("adopted-by"))
                .findFirst().orElseThrow();
        assertNotEquals("mukoyoshi", FamilyNameHistory.adoptionKind(adoption), "the 婿養子 word in the sentence is about Kenji: " + adoption.body());
        List<FamilyNameQuestions.Question> qs = FamilyNameQuestions.open(store);
        assertTrue(qs.stream().noneMatch(q -> q.text().contains("Masaru was adopted by") && q.text().contains("婿養子")), "nobody asks how the brother came in as 婿養子: " + qs.stream().map(FamilyNameQuestions.Question::text).toList());
    }

    @Test
    void aFamilyWhoseNameBeginsWithASmallWordIsAFamily() {
        String a = "Tom Hart married into the van Hale family in 1850.", b = "The de Moore family kept the inn at York for a hundred years.", c = "Ann ten Hart was born into the ten Hart family of York.";
        String json = """
                {"people": [{"name": "Tom Hart", "family": "Hart", "given": "Tom"}, {"name": "Ann ten Hart", "family": "ten Hart", "given": "Ann"}],
                 "facts": [{"subject": "Tom Hart", "relation": "member-of", "object": "the van Hale family", "how": "marriage", "date": "1850", "quote": "%s"},
                           {"subject": "Ann ten Hart", "relation": "member-of", "object": "the ten Hart family", "how": "birth", "date": "", "quote": "%s"}],
                 "families": [{"name": "van Hale", "written": "the van Hale family", "seat": "", "quote": "%s"},
                              {"name": "de Moore", "written": "the de Moore family", "seat": "York", "quote": "%s"},
                              {"name": "ten Hart", "written": "the ten Hart family", "seat": "York", "quote": "%s"}]}
                """.formatted(a, c, a, b, c);
        FamilyAccount.Read r = read(a + " " + b + " " + c + "\n", json, x -> 0.0);
        assertEquals(List.of("van Hale", "de Moore", "ten Hart"), r.families().stream().map(FamilyAccount.FamilyRead::name).toList(), r.dropped().toString());
        assertEquals(2, r.facts().stream().filter(f -> f.relation().equals("member-of")).count(), r.dropped().toString());
        assertTrue(r.dropped().stream().noneMatch(d -> d.contains("do not name the family")), r.dropped().toString());
    }

    @Test
    void bornIntoTheFamilyAndItsHeadFromOneSentenceAreTwoClaims(@TempDir Path tmp) throws Exception {
        String q = "Morita Isamu, born into the Morita family (森田家), became its head in 1920.";
        String json = """
                {"people": [{"name": "Morita Isamu", "family": "Morita", "given": "Isamu"}],
                 "facts": [{"subject": "Morita Isamu", "relation": "member-of", "object": "森田家", "how": "birth", "date": "", "quote": "%s"},
                           {"subject": "Morita Isamu", "relation": "head-of-household", "object": "森田家", "date": "1920", "quote": "%s"}],
                 "families": [{"name": "森田", "written": "森田家", "seat": "", "quote": "%s"}]}
                """.formatted(q, q, q);
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.file(store, read(q + "\n", json, x -> 0.0), "file:///family/book.txt", "an aunt");
        List<Finding> members = claims(store, "member-of");
        assertTrue(members.stream().noneMatch(f -> "birth".equals(FamilyDetail.of(f).get("how")) && "head".equals(FamilyDetail.of(f).get("role"))),
                "he was not born into the family as its head in 1920: " + members.stream().map(f -> f.title() + " " + FamilyDetail.of(f)).toList());
        assertTrue(members.stream().anyMatch(f -> "head".equals(FamilyDetail.of(f).get("role"))), "he is its head");
        assertTrue(members.stream().anyMatch(f -> "birth".equals(FamilyDetail.of(f).get("how"))), "and was born into it");
    }

    @Test
    void aFamilyNameAmongTheFathersOtherSpellingsDoesNotLetTheModelNameHim(@TempDir Path tmp) throws Exception {
        String json = """
                {"people": [{"name": "遠藤正一", "family": "遠藤", "given": "正一", "also": ["Endō Shōichi", "Endo"]}, {"name": "森田健二", "family": "森田", "given": "健二", "also": ["Morita Kenji"]}],
                 "facts": [{"subject": "遠藤正一", "relation": "born-on", "object": "1875", "date": "", "quote": "Endō Shōichi (遠藤正一) was born in 1875 and married Endō Haru in 1899."},
                           {"subject": "遠藤正一", "relation": "parent-of", "object": "森田健二", "date": "", "quote": "%s"}],
                 "families": [{"name": "遠藤", "written": "The Endō family", "seat": "near Kōfu", "quote": "The Endō family (遠藤家) had farmed near Kōfu for four generations."}]}
                """.formatted(SILK);
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.Outcome o = FamilyAccount.file(store, read(SILK_TEXT, json), "file:///family/shop.txt", "an aunt");
        assertEquals(1, o.mentions(), "the words write the father only as Endo: " + claims(store, "parent-of").stream().map(Finding::triple).toList());
        assertTrue(claims(store, "parent-of").stream().noneMatch(f -> f.triple().subject().equals("遠藤正一")), claims(store, "parent-of").stream().map(Finding::triple).toList().toString());
        String ja = "遠藤の息子、森田健二は店を継いだ。";
        LibraryStore j = FamilyNameHistoryTest.store(tmp.resolve("ja"));
        FamilyAccount.Outcome jo = FamilyAccount.file(j, read("遠藤正一は明治八年に生まれた。" + ja + "\n", """
                {"people": [{"name": "遠藤正一", "family": "遠藤", "given": "正一", "also": ["遠藤"]}, {"name": "森田健二", "family": "森田", "given": "健二"}],
                 "facts": [{"subject": "遠藤正一", "relation": "parent-of", "object": "森田健二", "date": "", "quote": "%s"}]}
                """.formatted(ja)), "file:///family/shop.txt", "an aunt");
        assertEquals(1, jo.mentions());
        assertTrue(claims(j, "parent-of").stream().noneMatch(f -> f.triple().subject().equals("遠藤正一")), claims(j, "parent-of").stream().map(Finding::triple).toList().toString());
    }

    @Test
    void anHeirWordAboutSomebodyElseMakesNoHeir(@TempDir Path tmp) throws Exception {
        String q = "In 1940 Shōji was adopted into the Takahashi family (髙橋家); the heir of the Morita family was his brother Masaru.";
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Morita Shōji", "", List.of(), "Morita", "Shōji")),
                List.of(new FamilyAccount.Fact("Morita Shōji", "adopted-by", "髙橋家", "1940", q, Map.of("kind", "ordinary"))), List.of(), List.of(), List.of(),
                List.of(new FamilyAccount.FamilyRead("髙橋", "髙橋家", "", q))), "file:///family/book.txt", "an aunt");
        Finding entered = claims(store, "member-of").get(0);
        assertEquals("adoption", FamilyDetail.of(entered).get("how"));
        assertNotEquals("heir", FamilyDetail.of(entered).get("role"), "the heir the words name is his brother, of another family: " + entered.title());
        // the heir word beside the family he entered: its heir
        LibraryStore heir = FamilyNameHistoryTest.store(tmp.resolve("heir"));
        FamilyAccount.file(heir, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Morita Shōji", "", List.of(), "Morita", "Shōji")),
                List.of(new FamilyAccount.Fact("Morita Shōji", "adopted-by", "髙橋家", "1940", TAKAHASHI, Map.of("kind", "ordinary"))), List.of(), List.of(), List.of(),
                List.of(new FamilyAccount.FamilyRead("髙橋", "髙橋家", "", TAKAHASHI))), "file:///family/book.txt", "an aunt");
        assertEquals("heir", FamilyDetail.of(claims(heir, "member-of").get(0)).get("role"), "adopted as heir into the Takahashi family (髙橋家)");
    }

    @Test
    void somebodyOfAFamilyWrittenOnlyByItsNameIsAMemberOfThatFamily(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Endo", "", List.of(), "Endo", "")),
                List.of(new FamilyAccount.Fact("Endo", "died-on", "1921", "", "Endo died in 1921.", Map.of("only-family-name", "true"))), List.of()), "file:///family/letter1.txt", "an aunt");
        Graph g = FamilyPeople.view(store);
        String who = claims(store, "died-on").get(0).triple().subject();
        assertEquals(List.of("Endo family"), FamilyHouses.families(g, g.nodeIdOf(who)).stream().map(m -> FamilyHouses.labelOf(g, m.family())).toList(),
                who + " is somebody of the Endo family, and a member of it: " + FamilyHouses.all(g));
    }

    @Test
    void theWholeNameTheTextWritesIsTheLabelNeverTheGivenNameAlone() {
        String text = "Morita Isamu was head of the shop. Isamu died in 1950.\n";
        FamilyAccount.Read r = read(text, """
                {"people": [{"name": "森田勇", "family": "森田", "given": "勇", "also": ["Isamu", "Morita Isamu"]}],
                 "facts": [{"subject": "森田勇", "relation": "died-on", "object": "1950", "date": "", "quote": "Isamu died in 1950."}]}
                """);
        assertEquals(List.of("Morita Isamu"), r.people().stream().map(FamilyAccount.Person::name).toList(), r.dropped().toString());
        assertTrue(has(r, "Morita Isamu", "died-on", "1950"), r.facts().toString());
    }

    static final String CHARACTERS = "The Endō family (遠藤家) had farmed near Kōfu. Endō Shōichi (遠藤正一) was born in 1875 and married Endō Toku in 1899. "
            + "In 1932 Kenji married Haru (森田ハル), the only daughter of Morita Isamu (森田勇), and was known from then on as Morita Kenji (森田健二, もりた けんじ). "
            + "Isamu's son Shōji (森田正二) was adopted as heir into the Takahashi family (髙橋家) of Nagano, and was known afterwards as Takahashi Shōji (髙橋正二).\n";

    @Test
    void aNameTheTextWritesInCharactersIsFiledInCharacters(@TempDir Path tmp) throws Exception {
        String endo = "The Endō family (遠藤家) had farmed near Kōfu.", shoichi = "Endō Shōichi (遠藤正一) was born in 1875 and married Endō Toku in 1899.";
        String wed = "In 1932 Kenji married Haru (森田ハル), the only daughter of Morita Isamu (森田勇), and was known from then on as Morita Kenji (森田健二, もりた けんじ).";
        String shoji = "Isamu's son Shōji (森田正二) was adopted as heir into the Takahashi family (髙橋家) of Nagano, and was known afterwards as Takahashi Shōji (髙橋正二).";
        String json = """
                {"people": [{"name": "Endō Shōichi", "family": "Endō", "given": "Shōichi", "also": ["遠藤正一"]}, {"name": "Endō Toku", "family": "Endō", "given": "Toku"},
                            {"name": "Morita Kenji", "family": "Morita", "given": "Kenji", "also": ["もりた けんじ", "森田健二"]}, {"name": "Haru", "given": "Haru", "also": ["森田ハル"]},
                            {"name": "Morita Isamu", "family": "Morita", "given": "Isamu", "also": ["森田勇"]}, {"name": "Takahashi Shōji", "family": "Takahashi", "given": "Shōji", "also": ["髙橋正二", "森田正二"]}],
                 "facts": [{"subject": "Endō Shōichi", "relation": "married-to", "object": "Endō Toku", "date": "1899", "quote": "%s"},
                           {"subject": "Morita Kenji", "relation": "married-to", "object": "Haru", "date": "1932", "quote": "%s"},
                           {"subject": "Haru", "relation": "child-of", "object": "Morita Isamu", "date": "", "quote": "%s"},
                           {"subject": "Takahashi Shōji", "relation": "child-of", "object": "Morita Isamu", "date": "", "quote": "%s"}],
                 "families": [{"name": "Endō", "written": "The Endō family", "seat": "near Kōfu", "quote": "%s"},
                              {"name": "Takahashi", "written": "the Takahashi family", "seat": "Nagano", "quote": "%s"}]}
                """.formatted(shoichi, wed, wed, shoji, endo, shoji);
        FamilyAccount.Read r = read(CHARACTERS, json);
        assertEquals(List.of("遠藤正一", "Endō Toku", "森田健二", "森田ハル", "森田勇", "髙橋正二"), r.people().stream().map(FamilyAccount.Person::name).toList(),
                "the characters the text writes beside a name are its label; Endō Toku, whom the text writes only in Latin letters, stays so");
        assertTrue(r.people().stream().filter(p -> p.name().equals("森田健二")).findFirst().orElseThrow().also().containsAll(List.of("Morita Kenji", "もりた けんじ")), "the Latin letters and the kana are his other spellings");
        assertTrue(has(r, "森田健二", "married-to", "森田ハル") && has(r, "髙橋正二", "child-of", "森田勇"), r.facts().toString());
        assertEquals(List.of("遠藤", "髙橋"), r.families().stream().map(FamilyAccount.FamilyRead::name).toList(), "a family the text writes in characters is named in characters");
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.file(store, r, "file:///family/shop.txt", "an aunt");
        Graph g = FamilyPeople.view(store);
        assertEquals(List.of("遠藤 family (near Kōfu)", "髙橋 family (Nagano)"), FamilyHouses.all(g).stream().map(id -> FamilyHouses.labelOf(g, id)).sorted().toList());
        assertEquals(g.nodeIdOf("遠藤 family (near Kōfu)"), g.nodeIdOf("The Endō family"), "the Latin words lead to the same family");
        assertTrue(personLabels(g).containsAll(List.of("森田健二", "森田勇", "遠藤正一", "髙橋正二", "森田ハル")), personLabels(g).toString());
        assertEquals(g.nodeIdOf("森田健二"), g.nodeIdOf("Morita Kenji"));
    }

    @Test
    void thePromptAsksForTheCharactersAsTheName() {
        String p = FamilyAccount.prompt("text", "an aunt", new GenealogyProfile().predicates());
        assertTrue(p.contains("When the account writes a name both in characters and in Latin letters, give the characters as the name and the Latin letters in `also`."), p);
        assertFalse(p.contains("in its own script: a name the account gives only in Latin letters"), "the sentence that made the model write every name in Latin letters is gone");
    }

    // ── the third real-model read: the 27B's own reply for the Morita shop, word for word where it matters ───────────────────────

    static final String BORN = "Kenji was born in 1905 as Endō Kenji (遠藤健二), the second son.";
    static final String KNOWN_AS = "From then on he was known as Morita Kenji (森田健二, もりた けんじ).";
    static final String SHOP_TEXT = BORN + " " + HEAD + " " + KNOWN_AS + "\n";

    /** The people, facts, names and family the model gave for the sentences above, as it gave them: no kind for the adoption, no way in for the entry, the family by its name alone. */
    static String shopReply(String head) {
        return """
                {"people": [{"name": "森田健二", "reading": "もりた けんじ", "also": ["Morita Kenji", "Endō Kenji", "遠藤健二"], "family": "森田", "given": "健二"},
                            {"name": "森田ハル", "reading": "", "also": ["Haru"], "family": "森田", "given": "ハル"},
                            {"name": "森田勇", "reading": "", "also": ["Morita Isamu"], "family": "森田", "given": "勇"}],
                 "facts": [{"subject": "森田健二", "relation": "born-on", "object": "1905", "date": "1905", "quote": "%1$s"},
                           {"subject": "森田健二", "relation": "married-to", "object": "森田ハル", "date": "1932", "quote": "%2$s"},
                           {"subject": "森田健二", "relation": "adopted-by", "object": "森田勇", "date": "1932", "quote": "%2$s"},
                           {"subject": "森田健二", "relation": "member-of", "object": "森田", "date": "1932", "quote": "%2$s"},
                           {"subject": "森田ハル", "relation": "child-of", "object": "森田勇", "date": "", "quote": "%2$s"},
                           {"subject": "森田ハル", "relation": "member-of", "object": "森田", "date": "", "quote": "%2$s"},
                           {"subject": "森田勇", "relation": "head-of-household", "object": "森田", "date": "", "quote": "%2$s"},
                           {"subject": "森田勇", "relation": "parent-of", "object": "森田ハル", "date": "", "quote": "%2$s"}],
                 "names": [{"person": "森田健二", "name": "遠藤健二", "family": "遠藤", "given": "健二", "kind": "birth", "said": "born in 1905 as", "date": "1905", "quote": "%1$s"},
                           {"person": "森田健二", "name": "森田健二", "family": "森田", "given": "健二", "kind": "mukoyoshi", "said": "From then on he was known as", "date": "1932", "quote": "%3$s"}],
                 "families": [{"name": "森田", "written": "the Morita family", "seat": "", "quote": "%2$s"}]}
                """.formatted(BORN, head, KNOWN_AS);
    }

    @Test
    void theMukoyoshiWordInASentenceThatWritesHimAsHeIsHisOwnAdoptions() {
        FamilyAccount.Read r = read(SHOP_TEXT, shopReply(HEAD), x -> 0.0);
        assertEquals("mukoyoshi", fact(r, "森田健二", "adopted-by", "森田勇").detail().get("kind"), "the sentence is about him, written as he, and 婿養子 of Isamu is his: " + r.facts());
        // without "of Isamu" the words tie the word to nobody by name; the sentence is still about the man it writes only as he
        String bare = HEAD.replace(" of Isamu.", ".");
        FamilyAccount.Read b = read(BORN + " " + bare + " " + KNOWN_AS + "\n", shopReply(bare), x -> 0.0);
        assertEquals("mukoyoshi", fact(b, "森田健二", "adopted-by", "森田勇").detail().get("kind"), b.facts().toString());
        // a brother the sentence names is not the one it writes as he, and a brother whose own adoption the sentence tells apart is not either
        for (String q : List.of("In 1932 he entered the Morita family (森田家) as 婿養子, and that year his brother Masaru was adopted by Takahashi Isamu.",
                "In 1932 he entered the Morita family (森田家) as 婿養子, and that year his brother was adopted by Takahashi Isamu.")) {
            FamilyAccount.Read o = read(q + "\n", """
                    {"people": [{"name": "森田勝", "family": "森田", "given": "勝", "also": ["Masaru"]}],
                     "facts": [{"subject": "森田勝", "relation": "adopted-by", "object": "Takahashi Isamu", "date": "1932", "quote": "%s"}],
                     "families": [{"name": "森田", "written": "森田家", "seat": "", "quote": "%s"}]}
                    """.formatted(q, q), x -> 0.0);
            FamilyAccount.Fact brother = o.facts().stream().filter(f -> f.relation().equals("adopted-by") && f.object().equals("Takahashi Isamu")).findFirst().orElseThrow();
            assertNotEquals("mukoyoshi", brother.detail().get("kind"), q);
            assertTrue(o.facts().stream().noneMatch(f -> f.subject().equals(brother.subject()) && f.relation().equals("member-of")), "the brother enters no family from these words: " + o.facts());
        }
    }

    @Test
    void aFamilyTheTextWritesInLatinLettersIsTheFamilyItsReadingTiesToItsCharacters(@TempDir Path tmp) throws Exception {
        FamilyAccount.Read r = read(SHOP_TEXT, shopReply(HEAD), x -> 0.0);
        assertTrue(r.dropped().stream().noneMatch(d -> d.contains("do not speak of 森田 as a family")), "the Morita family is 森田, as Morita Isamu (森田勇) and the family the read gives say: " + r.dropped());
        assertTrue(has(r, "森田健二", "member-of", "森田"), r.facts().toString());
        LibraryStore store = FamilyNameHistoryTest.store(tmp.resolve("shop"));
        FamilyAccount.file(store, r, "file:///family/shop.txt", "an aunt");
        Graph g = FamilyPeople.view(store);
        assertFalse(personLabels(g).contains("森田"), "the household 森田 is the family, never a person: " + personLabels(g));
        assertTrue(claims(store, "head-of-household").isEmpty(), claims(store, "head-of-household").stream().map(Finding::triple).toList().toString());
        List<FamilyHouses.Membership> isamu = FamilyHouses.families(g, g.nodeIdOf("森田勇"));
        assertEquals(List.of("森田 family"), isamu.stream().map(m -> FamilyHouses.labelOf(g, m.family())).toList());
        assertEquals("head", isamu.get(0).role(), "the head of the Morita family is its head");
    }

    @Test
    void anAdoptionAsHeirIntoAFamilyGivenByItsNameAloneIsOneMembershipAsItsHeir(@TempDir Path tmp) throws Exception {
        // the model's own facts for the sentence: the adoption by the family's name alone, and the membership again, both from 1940
        String json = """
                {"people": [{"name": "髙橋正二", "reading": "", "also": ["Takahashi Shōji", "森田正二", "Shōji"], "family": "髙橋", "given": "正二"}],
                 "facts": [{"subject": "髙橋正二", "relation": "adopted-by", "object": "髙橋", "date": "1940", "quote": "%1$s"},
                           {"subject": "髙橋正二", "relation": "member-of", "object": "髙橋", "date": "1940", "quote": "%1$s"}],
                 "families": [{"name": "髙橋", "written": "the Takahashi family", "seat": "Nagano", "quote": "%1$s"}]}
                """.formatted(TAKAHASHI);
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.Outcome o = FamilyAccount.file(store, read(TAKAHASHI + "\n", json, x -> 0.0), "file:///family/shop.txt", "an aunt");
        assertEquals(0, o.alreadyHeld(), "nothing was in the library before this read");
        List<Finding> members = claims(store, "member-of");
        assertEquals(1, members.size(), members.stream().map(f -> f.title() + " " + FamilyDetail.of(f)).toList().toString());
        assertEquals("heir", FamilyDetail.of(members.get(0)).get("role"), "the words say he was adopted as heir");
        assertEquals("adoption", FamilyDetail.of(members.get(0)).get("how"));
        assertTrue(claims(store, "adopted-by").isEmpty(), "no adoption by a person named 髙橋");
        Graph g = FamilyPeople.view(store);
        assertFalse(personLabels(g).contains("髙橋"), personLabels(g).toString());
    }

    @Test
    void aLaterTextThatWritesOnlyTheMoritaFamilyFindsTheFamilyTheLibraryHoldsUnderBothNames(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.file(store, read(SHOP_TEXT, shopReply(HEAD), x -> 0.0), "file:///family/shop.txt", "an aunt");
        Graph g = FamilyPeople.view(store);
        // a later text that writes only "the Morita family": the family the library holds ties the two
        String left = "Isamu's son Shōji (森田正二) left the Morita family in 1940.";
        String json = """
                {"people": [{"name": "森田正二", "family": "森田", "given": "正二"}],
                 "facts": [{"subject": "森田正二", "relation": "member-of", "object": "森田", "date": "", "quote": "%s"}]}
                """.formatted(left);
        FamilyAccount.Read alone = read(left + "\n", json, x -> 0.0);
        assertFalse(has(alone, "森田正二", "member-of", "森田"), "nothing in this text alone ties Morita to 森田: " + alone.facts());
        FamilyAccount.Read held = FamilyAccount.read(left + "\n", "an aunt", new GenealogyProfile().predicates(), prompt -> json, List.of(), null, x -> 0.0, null, FamilyAccount.familyForms(g));
        assertTrue(has(held, "森田正二", "member-of", "森田"), "the library's 森田 family is also written the Morita family: " + held.dropped());
        // words that name another family in Latin letters do not name this one
        String other = "Isamu's son Shōji (森田正二) left the Takahashi family in 1940.";
        FamilyAccount.Read not = FamilyAccount.read(other + "\n", "an aunt", new GenealogyProfile().predicates(), prompt -> json.replace(left, other), List.of(), null, x -> 0.0, null, FamilyAccount.familyForms(g));
        assertFalse(has(not, "森田正二", "member-of", "森田"), not.facts().toString());
    }

    @Test
    void theMoritaShopAsTheModelReadItFilesHisEntryAsMukoyoshiAndAsksNothingTheWordsSettle(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyAccount.file(store, read(SHOP_TEXT, shopReply(HEAD), x -> 0.0), "file:///family/shop.txt", "an aunt");
        Finding adoption = claims(store, "adopted-by").stream().filter(f -> f.triple().subject().equals("森田健二")).findFirst().orElseThrow();
        assertEquals("mukoyoshi", FamilyNameHistory.adoptionKind(adoption), adoption.body());
        Graph g = FamilyPeople.view(store);
        List<FamilyHouses.Membership> kenji = FamilyHouses.families(g, g.nodeIdOf("森田健二"));
        FamilyHouses.Membership entered = kenji.stream().filter(m -> FamilyHouses.labelOf(g, m.family()).equals("森田 family")).findFirst().orElseThrow(() -> new AssertionError("no 森田 family membership: " + kenji));
        assertEquals("mukoyoshi", entered.how());
        assertEquals(1932, entered.from().year());
        List<String> asked = FamilyNameQuestions.open(store).stream().map(FamilyNameQuestions.Question::text).toList();
        assertTrue(asked.stream().noneMatch(q -> q.contains("get the name 森田健二")), "the text says how: he entered the family as 婿養子 in 1932: " + asked);
    }
}
