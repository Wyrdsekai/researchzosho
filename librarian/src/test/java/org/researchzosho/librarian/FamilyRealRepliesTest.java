package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.Judge;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real model's path, locked. Two invented family texts (resources/names/real) were read by the 27B drive (qwen3.8-27b) on
 * 2026-09-24; its replies to the reader's prompt, and the probability of yes it gave each check it was asked, are in
 * resources/names/real/replies-27b.json. Here the reader reads the same two texts with a scripted model that gives those replies back,
 * chosen by which text the prompt carries; a yes-or-no check the recording holds gets the recorded answer (yes at the judge's sure
 * margin), and any other check gets no, as the other reader tests' scripted judge answers. The assertions are what a person checked by
 * hand in that real run: what the library then holds for Mary Ellis, for 森田健二 and his family, and which questions wait.
 * A second real read of the same two texts, on 2026-09-25 with the prompt that asks for each person's sex and writes an unnamed relative
 * with the account's own words, is in replies-27b-2026-09-25.json, and the tests named "second" hold what was checked by hand in it.
 */
class FamilyRealRepliesTest {

    static final String HALE = "hale-letter.txt", MORITA = "morita-shop.txt";

    static String resource(String name) {
        try (InputStream in = FamilyRealRepliesTest.class.getResourceAsStream("/names/real/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private static LibraryStore store;
    private static Graph g;
    private static FamilyNameHistory.Index idx;
    private static String who;
    private static final List<String> unrecorded = new ArrayList<>();

    /** What one recording's read left: the library, genealogy's view of it, the names, what `genealogy who --list` printed, and the checks the recording lacks. */
    record Replay(LibraryStore store, Graph g, FamilyNameHistory.Index idx, String who, List<String> unrecorded) { }

    private static Replay second;

    /** The two texts read once for each recording, as `researchzosho genealogy read <folder>` reads them at a terminal nobody answers. */
    @BeforeAll
    static void read(@TempDir Path tmp) throws Exception {
        Replay first = replay(tmp.resolve("first"), "replies-27b.json");
        store = first.store(); g = first.g(); idx = first.idx(); who = first.who(); unrecorded.addAll(first.unrecorded());
        second = replay(tmp.resolve("second"), "replies-27b-2026-09-25.json");
    }

    static Replay replay(Path tmp, String recording) throws Exception {
        List<String> unrecorded = new ArrayList<>();
        JsonNode real = new ObjectMapper().readTree(resource(recording));
        String hale = resource(HALE), morita = resource(MORITA);
        String haleReply = real.path("replies").path("hale").asText(), moritaReply = real.path("replies").path("morita").asText();
        JsonNode judge = real.path("judge");
        String yesNo = "\nAnswer with one word: yes or no.";
        GenealogyProfile.useReader(prompt -> {
            if (prompt.endsWith(yesNo)) {
                // the judge was asked this as "QUESTION: <question> Answer yes or no."
                JsonNode p = judge.get("QUESTION: " + prompt.substring(0, prompt.length() - yesNo.length()) + " Answer yes or no.");
                if (p == null) { unrecorded.add(prompt); return "no"; }
                return p.asDouble() >= 0.5 + Judge.SURE / 2 ? "yes" : "no";
            }
            if (prompt.contains(hale.lines().findFirst().orElseThrow())) return haleReply;
            if (prompt.contains(morita.lines().findFirst().orElseThrow())) return moritaReply;
            throw new AssertionError("a prompt for neither text: " + prompt.substring(0, Math.min(300, prompt.length())));
        });
        String home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString());
        Interaction.OVERRIDE = false;
        try {
            LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
            new LibrarianIndex(store, Embeddings.none()).rebuild();
            Path folder = tmp.resolve("family");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(HALE), hale, StandardCharsets.UTF_8);
            Files.writeString(folder.resolve(MORITA), morita, StandardCharsets.UTF_8);
            FamilyNamesFilingTest.run(store, "read", folder.toString());
            Graph g = FamilyPeople.view(store);
            return new Replay(store, g, FamilyNameHistory.of(g), FamilyNamesFilingTest.run(store, "who", "--list"), unrecorded);
        } finally {
            GenealogyProfile.useReader(null);
            Interaction.OVERRIDE = null;
            System.setProperty("user.home", home);
            Config.invalidate();
        }
    }

    @AfterEach void restore() { Interaction.OVERRIDE = null; }

    static String id(String name) { return g.nodeIdOf(name); }

    static FamilyNameHistory.Name name(String person, String written) {
        return idx.names(id(person)).stream().filter(n -> n.written().equals(written)).findFirst()
                .orElseThrow(() -> new AssertionError(person + " has no name " + written + ": " + idx.names(id(person))));
    }

    static int year(FamilyNameHistory.Name n) { return n.from() == null ? 0 : n.from().year(); }

    static List<Finding> claims(String subject, String predicate) {
        List<Finding> out = new ArrayList<>();
        for (Finding f : store.scanFindings().findings())
            if (f.triple() != null && f.triple().predicate().equals(predicate) && id(f.triple().subject()).equals(id(subject))) out.add(f);
        return out;
    }

    @Test
    void maryEllisCarriedThreeNamesAndIsHeadedByTheLastWithHerBirthName() {
        FamilyNameHistory.Name hale = name("Mary Ellis", "Mary Hale"), ellis = name("Mary Ellis", "M. Ellis"), hart = name("Mary Ellis", "Mary Hart");
        assertEquals("birth", hale.kind(), hale.toString());
        assertEquals("marriage", ellis.kind(), ellis.toString());
        assertEquals(1875, year(ellis), ellis.toString());
        assertTrue(ellis.isForm("Mary Ellis"), "M. Ellis is also written Mary Ellis: " + ellis);
        assertEquals("marriage", hart.kind(), hart.toString());
        assertEquals(1890, year(hart), hart.toString());
        assertEquals(3, idx.names(id("Mary Ellis")).size(), idx.names(id("Mary Ellis")).toString());
        assertEquals("Mary Hart (born Hale)", idx.heading(id("Mary Ellis")));
    }

    @Test
    void kenjiIsOneEntryWithEveryWayTheTextWritesHim() {
        String kenji = id("森田健二");
        assertEquals("person", g.node(kenji).kind());
        for (String form : List.of("もりた けんじ", "Morita Kenji", "Endō Kenji", "遠藤健二"))
            assertEquals(kenji, id(form), form + " is 森田健二's entry: " + g.node(kenji).aliases());
        assertEquals("森田健二 (born 遠藤)", idx.heading(kenji));
    }

    @Test
    void kenjiEnteredTheMoritaFamilyAs婿養子OfIsamuIn1932() {
        List<Finding> adopted = claims("森田健二", "adopted-by");
        assertEquals(1, adopted.size(), adopted.toString());
        assertEquals(id("森田勇"), id(adopted.get(0).triple().object()));
        assertEquals("mukoyoshi", FamilyDetail.get(adopted.get(0), "kind"));
        List<Finding> member = claims("森田健二", FamilyHouses.MEMBER);
        assertEquals(1, member.size(), member.toString());
        assertEquals("森田 family", member.get(0).triple().object());
        assertEquals("mukoyoshi", FamilyDetail.get(member.get(0), "how"));
        assertEquals(1932, FamilyChecks.claimDate(member.get(0)).year());
    }

    @Test
    void isamuIsTheHeadOfTheMoritaFamily() {
        List<Finding> member = claims("森田勇", FamilyHouses.MEMBER);
        assertTrue(member.stream().anyMatch(f -> f.triple().object().equals("森田 family") && FamilyDetail.get(f, "role").equals("head")), member.toString());
    }

    @Test
    void aFamilyOrAGivenNameAloneIsNoPerson() {
        for (String n : List.of("森田", "森田家", "髙橋家", "Kenji", "Isamu")) {
            Graph.Node node = g.node(id(n));
            assertTrue(node == null || !"person".equals(node.kind()) || node.id().equals(id("森田健二")) || node.id().equals(id("森田勇")),
                    n + " is no person of its own: " + node);
        }
        for (Graph.Node node : g.nodes())
            if ("person".equals(node.kind())) assertFalse(List.of("森田", "森田家", "髙橋家", "Kenji", "Isamu", "Endo").contains(node.label()), node.label());
    }

    @Test
    void endosSonsFatherIsAskedAboutWith遠藤正一Offered() {
        String parent = id("森田健二's parent (written only as Endo)");
        assertEquals("person", g.node(parent).kind());
        List<FamilyNameQuestions.Question> asked = open().stream().filter(q -> q.kind().equals("family-name-alone") && q.people().contains(parent)).toList();
        assertEquals(1, asked.size(), open().toString());
        assertTrue(asked.get(0).options().stream().anyMatch(o -> o.says().startsWith("遠藤正一")), asked.get(0).options().toString());
        assertTrue(claims("森田健二", "child-of").stream().noneMatch(f -> id(f.triple().object()).equals(id("遠藤正一"))), "the model's pick is no link");
        // "his father kept silkworms" is the work of the father the same words write only as Endo; the model's pick stays a candidate
        List<Finding> work = claims("森田健二's parent (written only as Endo)", "occupation");
        assertEquals(1, work.size(), work.toString());
        assertEquals("遠藤正一", FamilyDetail.get(work.get(0), FamilyMentions.PICKED));
        assertEquals(List.of(), claims("遠藤正一", "occupation"));
    }

    @Test
    void shojiTookTheTakahashiNameOnAdoptionIn1940AndIsTheFamilysHeir() {
        FamilyNameHistory.Name takahashi = name("髙橋正二", "髙橋正二");
        assertEquals("adoptive", takahashi.kind(), takahashi.toString());
        assertEquals(1940, year(takahashi), takahashi.toString());
        List<Finding> member = claims("髙橋正二", FamilyHouses.MEMBER).stream().filter(f -> f.triple().object().startsWith("髙橋 family")).toList();
        assertEquals(1, member.size(), member.toString());
        assertEquals("heir", FamilyDetail.get(member.get(0), "role"));
    }

    @Test
    void masaruCarriesTheTakahashiNameFrom1990ForAReasonTheFamilyIsAsked() {
        FamilyNameHistory.Name takahashi = name("髙橋勝", "髙橋勝");
        assertEquals("unknown", takahashi.kind(), takahashi.toString());
        assertEquals(1990, year(takahashi), takahashi.toString());
        String masaru = id("髙橋勝");
        List<FamilyNameQuestions.Question> how = open().stream().filter(q -> q.kind().equals("name-change-how") && q.people().contains(masaru)).toList();
        assertEquals(1, how.size(), how.toString());
    }

    @Test
    void exactlyTwoQuestionsWait() {
        assertEquals(2, open().size(), open().stream().map(FamilyNameQuestions.Question::text).toList().toString());
        assertTrue(who.contains("Questions about names and families that only your family can answer (2):"), who);
    }

    @Test
    void everyCheckTheReaderAskedIsOneTheRealModelAnswered() {
        // a check the recording lacks was answered no here, which the real model may not have said: then this is no longer the real
        // model's path, and the two texts need a new real read to record its answer
        assertEquals(List.of(), unrecorded, "checks the real model was never asked");
    }

    static List<FamilyNameQuestions.Question> open() {
        try { return FamilyNameQuestions.open(store); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @Test
    void theRelativeThe27BCopiedFromThePromptsExampleIsLeftOut() {
        // the first recording's reply files Mary Ellis as the child of "the writer of hale-letter.txt's mother's father", from "Notes on my
        // great-grandmother": the quote carries neither a mother and a father nor a grandfather
        assertTrue(g.nodes().stream().noneMatch(n -> n.label().startsWith("the writer of")), g.nodes().stream().map(Graph.Node::label).toList().toString());
        assertEquals(List.of(), claims("Mary Ellis", "child-of"));
    }

    // ── the second real read, 2026-09-25 ──────────────────────────────────────────────────────────────────────────────

    static String id(Replay r, String name) { return r.g().nodeIdOf(name); }

    static FamilyNameHistory.Name name(Replay r, String person, String written) {
        return r.idx().names(id(r, person)).stream().filter(n -> n.written().equals(written)).findFirst()
                .orElseThrow(() -> new AssertionError(person + " has no name " + written + ": " + r.idx().names(id(r, person))));
    }

    static List<Finding> claims(Replay r, String subject, String predicate) {
        List<Finding> out = new ArrayList<>();
        for (Finding f : r.store().scanFindings().findings())
            if (f.triple() != null && f.triple().predicate().equals(predicate) && id(r, f.triple().subject()).equals(id(r, subject))) out.add(f);
        return out;
    }

    static List<FamilyNameQuestions.Question> open(Replay r) {
        try { return FamilyNameQuestions.open(r.store()); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    @Test
    void secondMaryCarriedHaleThenEllisFromHerFirstMarriageThenHartAndIsNotAsked() {
        FamilyNameHistory.Name hale = name(second, "Mary Ellis", "Mary Hale"), ellis = name(second, "Mary Ellis", "M. Ellis"), hart = name(second, "Mary Ellis", "Mary Hart");
        assertEquals("birth", hale.kind(), hale.toString());
        assertEquals(1850, year(hale), hale.toString());
        assertEquals("marriage", ellis.kind(), "the reply gave no kind the words bear out; the marriage to Tom Ellis in 1875 settles it: " + ellis);
        assertEquals(1875, year(ellis), ellis.toString());
        assertTrue(ellis.cameWithTheMarriage(), "shown as worked out: " + ellis);
        assertTrue(ellis.isForm("Mary Ellis"), ellis.toString());
        assertEquals("marriage", hart.kind(), hart.toString());
        assertEquals(1890, year(hart), hart.toString());
        String mary = id(second, "Mary Ellis");
        assertEquals("Mary Hale", second.idx().at(mary, 1860).written());
        assertEquals("M. Ellis", second.idx().at(mary, 1880).written());
        assertEquals("Mary Hart", second.idx().at(mary, 1895).written());
        assertEquals("Mary Hart (born Hale)", second.idx().heading(mary));
        assertTrue(open(second).stream().noneMatch(q -> q.people().contains(mary)), open(second).toString());
    }

    @Test
    void secondSexIsFiledFromEachPersonsOwnWordsAndNeverFromAName() {
        var sexes = FamilyKin.sexes(second.g());
        assertEquals("female", FamilyKin.sexOf(sexes, id(second, "Mary Ellis")), "she, in her own sentence");
        assertEquals("female", FamilyKin.sexOf(sexes, id(second, "Ruth")), "Hale's daughter, Mary's younger sister");
        assertEquals("male", FamilyKin.sexOf(sexes, id(second, "森田健二")), "the second son");
        assertEquals("female", FamilyKin.sexOf(sexes, id(second, "森田ハル")), "the only daughter");
        assertEquals("", FamilyKin.sexOf(sexes, id(second, "Tom Ellis")), "the model said male; his own sentence says she, which is Mary");
        assertEquals("", FamilyKin.sexOf(sexes, id(second, "John Hart")), "the model said male; no sentence of his own");
        Finding mary = claims(second, "Mary Ellis", "sex").get(0);
        assertEquals("family-account", mary.writer());
        assertTrue(mary.body().contains("She married Tom Ellis, a printer, in York in 1875"), mary.body());
        String texts = resource(HALE) + "\n" + resource(MORITA);
        for (Finding f : second.store().scanFindings().findings())
            if (f.triple() != null && f.triple().predicate().equals("sex")) assertTrue(texts.contains(FamilyChecks.quoteOf(f)), "each sex quotes its sentence as the text writes it: " + f.body());
    }

    @Test
    void secondNobodyIsWrittenAsTheWritersRelative() {
        assertTrue(second.g().nodes().stream().noneMatch(n -> n.label().startsWith("the writer of")), second.g().nodes().stream().map(Graph.Node::label).toList().toString());
    }

    @Test
    void secondTheMoritaSideIsAsBefore() {
        FamilyNameHistory.Name kenji = name(second, "森田健二", "森田健二");
        assertEquals("mukoyoshi", kenji.kind(), kenji.toString());
        assertEquals(1932, year(kenji), kenji.toString());
        assertEquals("森田健二 (born 遠藤)", second.idx().heading(id(second, "森田健二")));
        List<Finding> adopted = claims(second, "森田健二", "adopted-by");
        assertEquals(1, adopted.size(), adopted.toString());
        assertEquals("mukoyoshi", FamilyDetail.get(adopted.get(0), "kind"));
        List<Finding> member = claims(second, "森田健二", FamilyHouses.MEMBER);
        assertEquals(1, member.size(), member.toString());
        assertEquals("mukoyoshi", FamilyDetail.get(member.get(0), "how"));
        assertEquals(1932, FamilyChecks.claimDate(member.get(0)).year());
        assertTrue(claims(second, "森田勇", FamilyHouses.MEMBER).stream().anyMatch(f -> f.triple().object().equals("森田 family") && FamilyDetail.get(f, "role").equals("head")));
        FamilyNameHistory.Name shoji = name(second, "髙橋正二", "髙橋正二");
        assertEquals("adoptive", shoji.kind(), shoji.toString());
        assertEquals(1940, year(shoji), shoji.toString());
        assertTrue(claims(second, "髙橋正二", FamilyHouses.MEMBER).stream().anyMatch(f -> f.triple().object().startsWith("髙橋 family") && FamilyDetail.get(f, "role").equals("heir")));
        FamilyNameHistory.Name masaru = name(second, "髙橋勝", "髙橋勝");
        assertEquals("unknown", masaru.kind(), masaru.toString());
        assertEquals(1990, year(masaru), masaru.toString());
    }

    @Test
    void secondThreeQuestionsWait() {
        List<FamilyNameQuestions.Question> qs = open(second);
        assertEquals(3, qs.size(), qs.stream().map(FamilyNameQuestions.Question::text).toList().toString());
        assertTrue(second.who().contains("Questions about names and families that only your family can answer (3):"), second.who());
        String endo = id(second, "森田健二's parent (written only as Endo)");
        FamilyNameQuestions.Question e = qs.stream().filter(q -> q.kind().equals("family-name-alone") && q.people().contains(endo)).findFirst().orElseThrow(() -> new AssertionError(qs.toString()));
        assertTrue(e.options().stream().anyMatch(o -> o.says().startsWith("遠藤正一")), e.options().toString());
        // "Hale's daughter Ruth": the reply writes her parent by the family name alone this time
        String hale = id(second, "Ruth's parent (written only as Hale)");
        assertTrue(qs.stream().anyMatch(q -> q.kind().equals("family-name-alone") && q.people().contains(hale)), qs.toString());
        assertTrue(qs.stream().anyMatch(q -> q.kind().equals("name-change-how") && q.people().contains(id(second, "髙橋勝"))), qs.toString());
    }

    @Test
    void secondEveryCheckTheReaderAskedIsOneTheRealModelAnswered() {
        assertEquals(List.of(), second.unrecorded(), "checks the real model was never asked");
    }
}
