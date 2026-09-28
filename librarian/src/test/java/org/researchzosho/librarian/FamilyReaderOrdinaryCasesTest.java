package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ordinary sentences of a family book, read with a scripted model: every fact the words state is kept, a person the words write two
 * ways within one read is one entry, and a party the words write only by a family name is somebody the family is asked about, never the
 * model's pick. Invented names only.
 */
class FamilyReaderOrdinaryCasesTest {

    static final String BOOK = "file:///family/book.txt";

    static FamilyAccount.Read read(String text, String json) { return read(text, json, q -> 0.0); }

    static FamilyAccount.Read read(String text, String json, FamilyAccount.YesNo judge) {
        return FamilyAccount.read(text, "an aunt", new GenealogyProfile().predicates(), prompt -> json, List.of(), null, judge);
    }

    static LibraryStore filed(Path dir, String text, String json) throws Exception { return filed(dir, text, json, q -> 0.0); }

    static LibraryStore filed(Path dir, String text, String json, FamilyAccount.YesNo judge) throws Exception {
        LibraryStore store = FamilyNameHistoryTest.store(dir);
        FamilyAccount.file(store, read(text, json, judge), BOOK, "an aunt");
        return store;
    }

    static List<Finding> claims(LibraryStore store, String predicate) {
        return store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals(predicate)).toList();
    }

    static List<String> said(LibraryStore store) {
        List<String> out = new ArrayList<>();
        for (Finding f : store.scanFindings().findings()) if (f.triple() != null) out.add(f.triple().subject() + " | " + f.triple().predicate() + " | " + f.triple().object() + " " + FamilyDetail.of(f));
        return out;
    }

    // ── e-reader-role-split-lost: two readings of one membership from one sentence are both kept ─────────────────────────────────

    @Test
    void anHeirsAdoptionAndABirthBesideAHeadshipAreEachKept(@TempDir Path tmp) throws Exception {
        String heir = "Isamu's son Shōji (森田正二) left the Morita family in 1940, when he was adopted as heir into the Takahashi family (髙橋家) of Nagano.";
        LibraryStore a = filed(tmp.resolve("heir"), heir + "\n", """
                {"people": [{"name": "森田正二", "family": "森田", "given": "正二", "also": ["Shōji"]}],
                 "facts": [{"subject": "森田正二", "relation": "member-of", "object": "the Takahashi family (髙橋家)", "how": "adoption", "date": "1940", "quote": "%s"},
                           {"subject": "森田正二", "relation": "heir-of", "object": "the Takahashi family", "date": "1940", "quote": "%s"}],
                 "families": [{"name": "髙橋", "written": "the Takahashi family", "seat": "Nagano", "quote": "%s"}]}
                """.formatted(heir, heir, heir));
        assertTrue(claims(a, "member-of").stream().anyMatch(f -> FamilyDetail.get(f, "role").equals("heir") && FamilyDetail.get(f, "how").equals("adoption")),
                "adopted as heir into the family is kept as its heir by adoption: " + said(a));
        // the birth and the headship, in either order, dated or not: two things, and both are kept
        String dated = "Morita Isamu, born into the Morita family (森田家), became its head in 1920.";
        String undated = "Morita Isamu (森田勇), born into the Morita family (森田家), was its head.";
        for (String[] c : new String[][]{
                {"headFirst", dated, """
                        {"people": [{"name": "Morita Isamu", "family": "Morita", "given": "Isamu"}],
                         "facts": [{"subject": "Morita Isamu", "relation": "head-of-household", "object": "森田家", "date": "1920", "quote": "%1$s"},
                                   {"subject": "Morita Isamu", "relation": "member-of", "object": "森田家", "how": "birth", "date": "", "quote": "%1$s"}],
                         "families": [{"name": "森田", "written": "森田家", "seat": "", "quote": "%1$s"}]}
                        """.formatted(dated)},
                {"undated", undated, """
                        {"people": [{"name": "森田勇", "family": "森田", "given": "勇", "also": ["Morita Isamu"]}],
                         "facts": [{"subject": "森田勇", "relation": "member-of", "object": "森田家", "how": "birth", "date": "", "quote": "%1$s"},
                                   {"subject": "森田勇", "relation": "head-of-household", "object": "森田家", "date": "", "quote": "%1$s"}],
                         "families": [{"name": "森田", "written": "森田家", "seat": "", "quote": "%1$s"}]}
                        """.formatted(undated)}}) {
            LibraryStore s = filed(tmp.resolve(c[0]), c[1] + "\n", c[2]);
            List<Finding> m = claims(s, "member-of");
            assertTrue(m.stream().anyMatch(f -> FamilyDetail.get(f, "role").equals("head")), c[0] + ": the headship is kept: " + said(s));
            assertTrue(m.stream().anyMatch(f -> FamilyDetail.get(f, "how").equals("birth")), c[0] + ": the birth into the family is kept: " + said(s));
        }
    }

    // ── e-reader-life-mention-across-read: a family name alone pages apart is not the same described person ──────────────────────

    @Test
    void aDeathWrittenByTheFamilyNameAloneInAnotherPassageIsNotEndosSonsFather(@TempDir Path tmp) throws Exception {
        String s1 = "Endo's son, Morita Kenji, went to the village school in 1912.";
        String s2 = "Endo, a cousin of Haru on her mother's side, died in Kōfu in 1950.";
        LibraryStore store = filed(tmp, s1 + "\n\nChapter 5.\n\n" + s2 + "\n", """
                {"people": [{"name": "Morita Kenji", "family": "Morita", "given": "Kenji"}, {"name": "Endo", "family": "Endo"}],
                 "facts": [{"subject": "Morita Kenji", "relation": "child-of", "object": "Endo", "date": "", "quote": "%s", "only_family_name": true},
                           {"subject": "Endo", "relation": "died-in", "object": "Kōfu", "date": "1950", "quote": "%s", "only_family_name": true}]}
                """.formatted(s1, s2));
        Finding death = claims(store, "died-in").stream().findFirst().orElseThrow(() -> new AssertionError("the death is kept: " + said(store)));
        assertNotEquals("Morita Kenji's parent (written only as Endo)", death.triple().subject(), "the cousin's death is not the father's: " + said(store));
        assertTrue(FamilyMentions.isMention(death.triple().subject()), "the one who died is somebody written only as Endo, whom the family is asked about: " + death.triple().subject());
    }

    // ── e-reader-related-chain-blocks-paired and e-reader-related-quadratic: what the read pairs is one person ────────────────────

    @Test
    void aNameTheTextPairsIsOnePersonAlsoWhenHisWifeAndSonAreInTheRead(@TempDir Path tmp) throws Exception {
        String s1 = "In 1932 Morita Kenji (森田健二, もりた けんじ) married Haru (森田ハル), the only daughter of Morita Isamu.";
        String s2 = "Kenji and Haru had one son, Morita Masaru (森田勝), born in 1934.";
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        FamilyAccount.Outcome o = FamilyAccount.file(store, read(s1 + " " + s2 + "\n", """
                {"people": [{"name": "Morita Kenji", "family": "Morita", "given": "Kenji"},
                            {"name": "森田健二", "reading": "もりた けんじ", "also": ["Morita Kenji"], "family": "森田", "given": "健二"},
                            {"name": "森田ハル", "family": "森田", "given": "ハル", "also": ["Haru"]},
                            {"name": "森田勝", "family": "森田", "given": "勝", "also": ["Morita Masaru"]}],
                 "facts": [{"subject": "Morita Kenji", "relation": "married-to", "object": "森田ハル", "date": "1932", "quote": "%s"},
                           {"subject": "森田勝", "relation": "child-of", "object": "森田健二", "date": "", "quote": "%s"},
                           {"subject": "森田勝", "relation": "child-of", "object": "森田ハル", "date": "", "quote": "%s"},
                           {"subject": "森田勝", "relation": "born-on", "object": "1934", "date": "", "quote": "%s"}]}
                """.formatted(s1, s2, s2, s2)), BOOK, "an aunt");
        Graph g = FamilyPeople.view(store);
        assertEquals(g.nodeIdOf("森田健二"), g.nodeIdOf("Morita Kenji"), "the man the text writes Morita Kenji (森田健二) is one entry: " + said(store));
        assertTrue(o.dropped().stream().noneMatch(d -> d.contains("is another person in your library")), o.dropped().toString());
        // the given name alone the model gives among his other spellings, beside his wife and son: one entry too
        String t1 = "Morita Kenji married Morita Haru in 1932.", t2 = "Kenji and Haru had one son, Morita Masaru, born in 1934.", t3 = "Kenji ran the Morita shop in Kofu until 1965.";
        LibraryStore two = FamilyNameHistoryTest.store(tmp.resolve("given"));
        FamilyAccount.file(two, read(t1 + " " + t2 + " " + t3 + "\n", """
                {"people": [{"name": "Morita Kenji", "family": "Morita", "given": "Kenji", "also": ["Kenji"]},
                            {"name": "Morita Haru", "family": "Morita", "given": "Haru"},
                            {"name": "Morita Masaru", "family": "Morita", "given": "Masaru"},
                            {"name": "Kenji", "given": "Kenji"}],
                 "facts": [{"subject": "Morita Kenji", "relation": "married-to", "object": "Morita Haru", "date": "1932", "quote": "%s"},
                           {"subject": "Morita Masaru", "relation": "child-of", "object": "Kenji", "date": "", "quote": "%s"},
                           {"subject": "Morita Masaru", "relation": "child-of", "object": "Morita Haru", "date": "", "quote": "%s"},
                           {"subject": "Kenji", "relation": "occupation", "object": "ran the Morita shop in Kofu", "date": "", "quote": "%s"}]}
                """.formatted(t1, t2, t2, t3)), BOOK, "an aunt");
        Graph tg = FamilyPeople.view(two);
        assertTrue(tg.node(tg.nodeIdOf("Kenji")) == null || tg.nodeIdOf("Kenji").equals(tg.nodeIdOf("Morita Kenji")), "no entry of its own for Kenji: " + said(two));
        assertTrue(claims(two, "occupation").stream().allMatch(f -> f.triple().subject().equals("Morita Kenji")) && claims(two, "child-of").stream().anyMatch(f -> f.triple().object().equals("Morita Kenji")),
                "Kenji, whom the model gives as Morita Kenji's, is he: his son and his shop are his: " + said(two));
    }

    @Test
    void whoARelatesToGrowsWithTheFactsNotWithTheSquareOfThePeople() {
        int n = 600;
        List<FamilyAccount.Person> people = new ArrayList<>();
        List<FamilyAccount.Fact> facts = new ArrayList<>();
        for (int i = 0; i < n; i++) people.add(new FamilyAccount.Person("Hale " + i, "", List.of()));
        for (int i = 1; i < n; i++) facts.add(new FamilyAccount.Fact("Hale " + i, "child-of", "Hale " + (i - 1), "", "Hale " + i + " was the child of Hale " + (i - 1) + "."));
        int pairs = FamilyAccount.related(new FamilyAccount.Read(people, facts, List.of())).size();
        assertTrue(pairs <= 2 * facts.size(), "a line of " + n + " people gives " + pairs + " related pairs, as many as its facts give, not one for every two people");
    }

    // ── e-reader-heir-clause: the heir in the next clause ───────────────────────────────────────────────────────────────────────

    @Test
    void anHeirInTheNextClauseIsAskedOfTheJudgeAndKeptWhenItSaysSo(@TempDir Path tmp) throws Exception {
        FamilyAccount.YesNo heirYes = q -> q.contains("as heir") ? 1.0 : 0.0;
        String en = "In 1940 Shōji (森田正二) was adopted into the Takahashi family (髙橋家), and became its heir.";
        String ja = "森田正二は昭和十五年に髙橋家の養子となり、家督を相続した。";
        for (String[] c : new String[][]{{"en", en, "1940"}, {"ja", ja, "昭和十五年"}}) {
            LibraryStore s = filed(tmp.resolve(c[0]), c[1] + "\n", """
                    {"people": [{"name": "森田正二", "family": "森田", "given": "正二"}],
                     "facts": [{"subject": "森田正二", "relation": "adopted-by", "object": "髙橋家", "date": "%s", "quote": "%s"}],
                     "families": [{"name": "髙橋", "written": "髙橋家", "seat": "", "quote": "%s"}]}
                    """.formatted(c[2], c[1], c[1]), heirYes);
            assertTrue(claims(s, "member-of").stream().anyMatch(f -> FamilyDetail.get(f, "role").equals("heir")), c[0] + ": adopted into the family and its heir: " + said(s));
        }
        // a 婿養子 who also became the heir: the 婿養子 words decide his adoption's kind, and the judge is not asked about an heir
        String muko = "健二は昭和七年に森田家の婿養子となり、家督を相続した。";
        LibraryStore m = filed(tmp.resolve("muko"), muko + "\n", """
                {"people": [{"name": "森田健二", "family": "森田", "given": "健二"}, {"name": "森田勇", "family": "森田", "given": "勇"}],
                 "facts": [{"subject": "森田健二", "relation": "adopted-by", "object": "森田勇", "date": "昭和七年", "quote": "%1$s"}],
                 "families": [{"name": "森田", "written": "森田家", "seat": "", "quote": "%1$s"}]}
                """.formatted(muko), heirYes);
        assertTrue(claims(m, "adopted-by").stream().anyMatch(f -> FamilyDetail.get(f, "kind").equals("mukoyoshi")), "his adoption is his 婿養子: " + said(m));
        // the heir word is about another family, and the judge says so: no heir
        String other = "In 1940 Shōji was adopted into the Takahashi family (髙橋家); the heir of the Morita family was his brother Masaru.";
        LibraryStore s = filed(tmp.resolve("other"), other + "\n", """
                {"people": [{"name": "森田正二", "family": "森田", "given": "正二", "also": ["Shōji"]}],
                 "facts": [{"subject": "森田正二", "relation": "adopted-by", "object": "髙橋家", "date": "1940", "quote": "%s"}],
                 "families": [{"name": "髙橋", "written": "髙橋家", "seat": "", "quote": "%s"}]}
                """.formatted(other, other), q -> 0.0);
        assertTrue(claims(s, "member-of").stream().noneMatch(f -> FamilyDetail.get(f, "role").equals("heir")), "the heir of another family makes no heir here: " + said(s));
    }

    // ── e-reader-mukoyoshi-entered-first: the wife given the entry as 婿養子 does not take the husband's 婿養子 ─────────────────

    @Test
    void theWifeGivenTheEntryAs婿養子LeavesTheHusbandsAdoptionAsHis(@TempDir Path tmp) throws Exception {
        String q = "In 1932 Kenji married Haru (森田ハル), the only daughter of Morita Isamu (森田勇), and entered the Morita family as 婿養子 of Isamu.";
        LibraryStore store = filed(tmp, "Morita Kenji was born in 1905. " + q + "\n", """
                {"people": [{"name": "Morita Kenji", "family": "Morita", "given": "Kenji", "also": ["Kenji"]}, {"name": "森田ハル", "family": "森田", "given": "ハル", "also": ["Haru"]},
                            {"name": "森田勇", "family": "森田", "given": "勇", "also": ["Morita Isamu"]}],
                 "facts": [{"subject": "Morita Kenji", "relation": "adopted-by", "object": "森田勇", "date": "1932", "quote": "%1$s"},
                           {"subject": "Morita Kenji", "relation": "married-to", "object": "森田ハル", "date": "1932", "quote": "%1$s"},
                           {"subject": "森田ハル", "relation": "child-of", "object": "森田勇", "date": "", "quote": "%1$s"},
                           {"subject": "森田ハル", "relation": "member-of", "object": "the Morita family", "how": "mukoyoshi", "date": "1932", "quote": "%1$s"}],
                 "families": [{"name": "Morita", "written": "the Morita family", "seat": "", "quote": "%1$s"}]}
                """.formatted(q));
        assertTrue(claims(store, "adopted-by").stream().anyMatch(f -> f.triple().subject().equals("Morita Kenji") && FamilyDetail.get(f, "kind").equals("mukoyoshi")), "his adoption is his 婿養子: " + said(store));
        assertTrue(claims(store, "member-of").stream().anyMatch(f -> f.triple().subject().equals("Morita Kenji") && FamilyDetail.get(f, "how").equals("mukoyoshi")), "and he entered the family: " + said(store));
        assertTrue(claims(store, "member-of").stream().noneMatch(f -> f.triple().subject().equals("森田ハル") && FamilyDetail.get(f, "how").equals("mukoyoshi")), "the daughter did not enter her own family as 婿養子: " + said(store));
    }

    // ── e-reader-life-fact-model-pick: a life fact the words give "his father" is the described father's ────────────────────────

    @Test
    void theFathersWorkWrittenBesideEndosSonIsTheDescribedFathersNotTheModelsPick(@TempDir Path tmp) throws Exception {
        String silk = "Endo's son, Morita Kenji, told us in 1998 that his father kept silkworms and sold the cocoons in Kōfu every autumn.";
        String text = "The Endō family (遠藤家) had farmed near Kōfu for four generations. Endō Shōichi (遠藤正一) was born in 1875 and married Endō Haru in 1899. " + silk + "\n";
        LibraryStore store = filed(tmp, text, """
                {"people": [{"name": "遠藤正一", "family": "遠藤", "given": "正一", "also": ["Endō Shōichi"]}, {"name": "Morita Kenji", "family": "Morita", "given": "Kenji"}],
                 "facts": [{"subject": "遠藤正一", "relation": "born-on", "object": "1875", "date": "", "quote": "Endō Shōichi (遠藤正一) was born in 1875 and married Endō Haru in 1899."},
                           {"subject": "遠藤正一", "relation": "parent-of", "object": "Morita Kenji", "date": "", "quote": "%1$s"},
                           {"subject": "遠藤正一", "relation": "occupation", "object": "kept silkworms and sold the cocoons in Kōfu every autumn", "date": "", "quote": "%1$s"}],
                 "families": [{"name": "遠藤", "written": "The Endō family", "seat": "near Kōfu", "quote": "The Endō family (遠藤家) had farmed near Kōfu for four generations."}]}
                """.formatted(silk));
        Finding work = claims(store, "occupation").stream().findFirst().orElseThrow(() -> new AssertionError("the work is kept: " + said(store)));
        assertEquals("Morita Kenji's parent (written only as Endo)", work.triple().subject(), "the work is the father's the words describe, whom the family is asked about: " + said(store));
        assertEquals("遠藤正一", FamilyDetail.get(work, FamilyMentions.PICKED), "the model's reading is kept as a candidate");
    }

    // ── e-owner-1: reading the book again never links the described father to an entry that is a family name alone ─────────────

    @Test
    void aReadAgainDoesNotLinkEndosSonsFatherToAnOldEntryNamedEndo(@TempDir Path tmp) throws Exception {
        String school = "Endo's son, Morita Kenji, went to the village school in 1920.";
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        // what an older build filed from the same sentence: a person named Endo
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Morita Kenji", "", List.of(), "Morita", "Kenji"), new FamilyAccount.Person("Endo", "", List.of())),
                List.of(new FamilyAccount.Fact("Morita Kenji", "child-of", "Endo", "", school), new FamilyAccount.Fact("Morita Kenji", "born-on", "1905", "", "Morita Kenji was born in 1905.")), List.of()), BOOK, "an aunt");
        FamilyAccount.Outcome o = FamilyAccount.file(store, read(school + " Morita Kenji was born in 1905.\n", """
                {"people": [{"name": "Morita Kenji", "family": "Morita", "given": "Kenji"}, {"name": "Endo", "family": "Endo"}],
                 "facts": [{"subject": "Morita Kenji", "relation": "child-of", "object": "Endo", "date": "", "quote": "%s", "only_family_name": true}]}
                """.formatted(school)), BOOK, "an aunt");
        Graph g = FamilyPeople.view(store);
        String parent = "Morita Kenji's parent (written only as Endo)";
        assertNotEquals(g.nodeIdOf("Endo"), g.nodeIdOf(parent), "the described father is not joined into the old entry Endo, which is a family name alone: " + o.linked());
        Path merges = Graph.mergesFile(store);
        assertTrue(!Files.exists(merges) || Files.readString(merges, StandardCharsets.UTF_8).lines().noneMatch(l -> l.contains(FamilyMentions.BY)), "the library made no link by its own rule");
        // the old entry is a family name alone, as the read of the same words says: the family is asked who it is (spec, existing libraries)
        String endo = g.nodeIdOf("Endo");
        List<FamilyNameQuestions.Question> asked = FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals("family-name-alone") && q.people().contains(endo)).toList();
        assertEquals(1, asked.size(), "the old entry Endo is asked about: " + FamilyNameQuestions.open(store).stream().map(FamilyNameQuestions.Question::text).toList());
    }

    @Test
    void theSameSentenceReadTwiceIsNoSecondFact(@TempDir Path tmp) throws Exception {
        String school = "Endo's son, Morita Kenji, went to the village school in 1920.";
        LibraryStore store = FamilyNameHistoryTest.store(tmp);
        // an older build filed the model's pick from the sentence as it stood, beside the father's own birth and his name in characters
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("Morita Kenji", "", List.of(), "Morita", "Kenji"), new FamilyAccount.Person("遠藤正一", "", List.of("Endō Shōichi"), "遠藤", "正一")),
                List.of(new FamilyAccount.Fact("Morita Kenji", "child-of", "遠藤正一", "", school), new FamilyAccount.Fact("Morita Kenji", "born-on", "1905", "", "Morita Kenji was born in 1905."),
                        new FamilyAccount.Fact("遠藤正一", "born-on", "1875", "", "Endō Shōichi (遠藤正一) was born in 1875.")), List.of(),
                List.of(), List.of(new FamilyAccount.NameRead("遠藤正一", "遠藤正一", "遠藤", "正一", List.of("Endō Shōichi"), "birth", "", "1875", "Endō Shōichi (遠藤正一) was born in 1875.")), List.of()), BOOK, "an aunt");
        String farm = "The Endō family (遠藤家) had farmed near Kōfu for four generations.";
        FamilyAccount.Outcome o = FamilyAccount.file(store, read(farm + " " + school + " Morita Kenji was born in 1905. Endō Shōichi (遠藤正一) was born in 1875.\n", """
                {"people": [{"name": "Morita Kenji", "family": "Morita", "given": "Kenji"}, {"name": "遠藤正一", "family": "遠藤", "given": "正一", "also": ["Endō Shōichi"]}],
                 "facts": [{"subject": "遠藤正一", "relation": "parent-of", "object": "Morita Kenji", "date": "", "quote": "%s"}],
                 "families": [{"name": "遠藤", "written": "The Endō family", "seat": "near Kōfu", "quote": "%s"}]}
                """.formatted(school, farm)), BOOK, "an aunt");
        Graph g = FamilyPeople.view(store);
        assertNotEquals(g.nodeIdOf("遠藤正一"), g.nodeIdOf("Morita Kenji's parent (written only as Endo)"),
                "the older reading of the same words is no second fact, so the family is asked who Endo is: " + o.linked());
    }

    // ── e-reader-kind-person-drops-membership and e-reader-not-by-birth-whole-quote: the birth into the family is kept ───────────

    @Test
    void endosSecondSonBornIntoTheEndoFamilyKeepsBothTheFatherAndTheBirth(@TempDir Path tmp) throws Exception {
        String q = "Kenji, Endo's second son, was born into the Endo family (遠藤家) of Kofu in 1905.";
        LibraryStore store = filed(tmp, q + "\n", """
                {"people": [{"name": "Endo Kenji", "family": "Endo", "given": "Kenji", "also": ["Kenji"]}],
                 "facts": [{"subject": "Endo Kenji", "relation": "child-of", "object": "Endo", "date": "", "quote": "%s", "only_family_name": true}],
                 "families": [{"name": "Endo", "written": "the Endo family", "seat": "Kofu", "quote": "%s"}]}
                """.formatted(q, q));
        assertTrue(claims(store, "child-of").stream().anyMatch(f -> f.triple().object().equals("Endo Kenji's parent (written only as Endo)")), "his father is somebody written only as Endo: " + said(store));
        assertTrue(claims(store, "member-of").stream().anyMatch(f -> f.triple().subject().equals("Endo Kenji") && FamilyDetail.get(f, "how").equals("birth")), "and he was born into the Endo family: " + said(store));
    }

    @Test
    void theSecondSonOfTheEndoHouseWhoBecame婿養子ElsewhereWasBornIntoTheEndoHouse(@TempDir Path tmp) throws Exception {
        String ja = "遠藤家の次男健二は、昭和七年に森田家の婿養子となった。";
        LibraryStore j = filed(tmp.resolve("ja"), ja + "\n", """
                {"people": [{"name": "遠藤健二", "family": "遠藤", "given": "健二", "also": ["健二"]}, {"name": "遠藤", "family": "遠藤"}],
                 "facts": [{"subject": "遠藤健二", "relation": "child-of", "object": "遠藤", "date": "", "quote": "%1$s", "only_family_name": true}],
                 "families": [{"name": "遠藤", "written": "遠藤家", "seat": "", "quote": "%1$s"}, {"name": "森田", "written": "森田家", "seat": "", "quote": "%1$s"}]}
                """.formatted(ja));
        assertTrue(claims(j, "member-of").stream().anyMatch(f -> f.triple().subject().equals("遠藤健二") && f.triple().object().startsWith("遠藤") && FamilyDetail.get(f, "how").equals("birth")),
                "the 婿養子 into the 森田 house says nothing of how he came into the 遠藤 house: " + said(j));
        String en = "Kenji, the second son of the Endo family, was adopted into the Morita family in 1932.";
        LibraryStore e = filed(tmp.resolve("en"), en + "\n", """
                {"people": [{"name": "Endo Kenji", "family": "Endo", "given": "Kenji", "also": ["Kenji"]}],
                 "facts": [{"subject": "Endo Kenji", "relation": "child-of", "object": "Endo", "date": "", "quote": "%1$s", "only_family_name": true}],
                 "families": [{"name": "Endo", "written": "the Endo family", "seat": "", "quote": "%1$s"}, {"name": "Morita", "written": "the Morita family", "seat": "", "quote": "%1$s"}]}
                """.formatted(en));
        assertTrue(claims(e, "member-of").stream().anyMatch(f -> f.triple().subject().equals("Endo Kenji") && f.triple().object().startsWith("Endo") && FamilyDetail.get(f, "how").equals("birth")), said(e).toString());
        // the kin words themselves say adopted: no birth
        String adopted = "遠藤家の養子健二は昭和七年に家を出た。";
        LibraryStore a = filed(tmp.resolve("adopted"), adopted + "\n", """
                {"people": [{"name": "遠藤健二", "family": "遠藤", "given": "健二"}, {"name": "遠藤", "family": "遠藤"}],
                 "facts": [{"subject": "遠藤健二", "relation": "child-of", "object": "遠藤", "date": "", "quote": "%1$s", "only_family_name": true}],
                 "families": [{"name": "遠藤", "written": "遠藤家", "seat": "", "quote": "%1$s"}]}
                """.formatted(adopted));
        assertTrue(claims(a, "member-of").stream().noneMatch(f -> FamilyDetail.get(f, "how").equals("birth")), "遠藤家の養子 was not born into it: " + said(a));
    }

    // ── e-reader-family-phrase-model-pick: "born into the Endo family" names no father ─────────────────────────────────────────

    @Test
    void bornIntoTheEndoFamilyNamesNoFatherSoTheModelsPickIsOnlyACandidate(@TempDir Path tmp) throws Exception {
        String q = "Kenji was born into the Endo family (遠藤家) of Kōfu in 1905.";
        LibraryStore store = filed(tmp, "Endō Shōichi (遠藤正一) was born in 1875. " + q + "\n", """
                {"people": [{"name": "遠藤正一", "family": "遠藤", "given": "正一", "also": ["Endō Shōichi"]}, {"name": "Endō Kenji", "family": "Endō", "given": "Kenji", "also": ["Kenji"]}],
                 "facts": [{"subject": "遠藤正一", "relation": "born-on", "object": "1875", "date": "", "quote": "Endō Shōichi (遠藤正一) was born in 1875."},
                           {"subject": "Endō Kenji", "relation": "child-of", "object": "遠藤正一", "date": "", "quote": "%1$s"},
                           {"subject": "Endō Kenji", "relation": "born-on", "object": "1905", "date": "", "quote": "%1$s"}],
                 "families": [{"name": "遠藤", "written": "the Endo family", "seat": "Kōfu", "quote": "%1$s"}]}
                """.formatted(q));
        List<Finding> childOf = claims(store, "child-of");
        assertTrue(childOf.stream().noneMatch(f -> f.triple().object().equals("遠藤正一")), "the words name no father: the model's pick is no link by itself: " + said(store));
        Finding parent = childOf.stream().filter(f -> FamilyMentions.isMention(f.triple().object())).findFirst().orElseThrow(() -> new AssertionError("his parent is somebody of the Endo family: " + said(store)));
        assertEquals("遠藤正一", FamilyDetail.get(parent, FamilyMentions.PICKED), "the model's reading is a candidate");
        assertTrue(claims(store, "member-of").stream().anyMatch(f -> f.triple().subject().equals("Endō Kenji") && FamilyDetail.get(f, "how").equals("birth")), "and he was born into the family: " + said(store));
    }

    // ── e-reader-capitalised-word-blocks-bracket: "Then Shōji (森田正二)" ───────────────────────────────────────────────────────

    @Test
    void aSentenceThatBeginsWithThenStillWritesTheCharactersBesideTheName() {
        String q = "Then Shōji (森田正二) left for Nagano.";
        FamilyAccount.Read r = read("Isamu had a son. " + q + "\n", """
                {"people": [{"name": "Shōji", "given": "Shōji", "also": ["森田正二"]}],
                 "facts": [{"subject": "Shōji", "relation": "lived-in", "object": "Nagano", "date": "", "quote": "%s"}]}
                """.formatted(q));
        assertEquals(List.of("森田正二"), r.people().stream().map(FamilyAccount.Person::name).toList(), "the text writes 森田正二 right beside Shōji");
        // a capitalised word before the name inside the sentence, or one of the read's name words at its start, is still a word of that name
        assertEquals(List.of(), FamilyAccount.bracketed("In 1932 Morita Kenji (森田健二) married.", "Kenji", "森田健二", Set.of()));
        assertEquals(List.of(), FamilyAccount.bracketed("Morita Kenji (森田健二) married.", "Kenji", "森田健二", Set.of("morita")));
    }

    // ── e-reader-prompt-dropped-latin-stays ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void thePromptKeepsANameWrittenOnlyInLatinLettersInLatinLetters() {
        String p = FamilyAccount.prompt("text", "an aunt", new GenealogyProfile().predicates());
        assertTrue(p.contains("When the account writes a name both in characters and in Latin letters, give the characters as the name and the Latin letters in `also`."), p);
        assertTrue(p.contains("A name the account writes only in Latin letters stays in Latin letters."), "the model keeps a name the text gives only in Latin letters as it is written");
    }
}
