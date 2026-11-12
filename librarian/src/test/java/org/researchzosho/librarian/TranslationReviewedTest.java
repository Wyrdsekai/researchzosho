package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.librarian.Translation.Outcome;
import org.researchzosho.librarian.Translation.Seats;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a reviewed run with a stronger reader showed: the reader's choice checked against the shorter names inside it, a part never told
 * to the reader as a name's rendering nor the program's own flags repeated by it, the names step kept out of the record rule, the
 * reader's cost counted from what the tier sends, and the spend record's fields in their places.
 */
class TranslationReviewedTest {
    @BeforeEach void standIns() { TranslationScores.SCORERS = () -> new TranslationScores.Scorers(TranslationTest.labseStandIn(), null); Translation.SETUP_SAID = () -> ""; }
    @AfterEach void back() { TranslationScores.SCORERS = TranslationScores::configured; }

    static DocNames.Compared byReader(String s, List<Integer> holders, String chosen, Map<String, Integer> forms) {
        return new DocNames.Compared(s, holders, chosen, "organisation", forms.getOrDefault(chosen, 0), List.of(), "", 1, "", DocNames.BY_READER, forms, "reader", chosen);
    }

    /** A cabinet chosen with a given name the document's other form leaves out, and a full name that writes the family name otherwise than the family name alone: both left to a person. */
    @Test void theReadersChoiceMustAgreeWithTheShorterNamesDecidedInsideIt() {
        Map<Integer, String> sources = new LinkedHashMap<>(), translations = new LinkedHashMap<>();
        String[][] units = {
                {"山田は首相であった。", "Yamada was prime minister."},
                {"山田は議会で答えた。", "Yamada answered in the Diet."},
                {"山田は辞めなかった。", "Yamada did not resign."},
                {"山田内閣は倒れた。", "The Yamada Kenta cabinet fell."},
                {"山田内閣の閣僚は残った。", "The ministers of the Yamada Cabinet stayed."},
                {"山田内閣は翌年に終わった。", "The Yamada Cabinet ended the next year."},
                {"古田は記者であった。", "Furuta was a reporter."},
                {"古田一郎は新聞に書いた。", "Futa Ichiro wrote in the paper."},
                {"古田一郎は議会を見た。", "Futa Ichiro watched the Diet."},
                {"古田一郎は筆を置いた。", "Futa Ichiro put down his pen."}};
        for (int i = 0; i < units.length; i++) { sources.put(i, units[i][0]); translations.put(i, units[i][1]); }
        List<DocNames.Compared> compared = new ArrayList<>(List.of(
                byReader("山田", List.of(0, 1, 2), "Yamada", Map.of("Yamada", 3)),
                byReader("山田内閣", List.of(3, 4, 5), "Yamada Kenta cabinet", Map.of("Yamada Cabinet", 2, "Yamada Kenta cabinet", 1)),
                byReader("古田", List.of(6), "Furuta", Map.of("Furuta", 1)),
                byReader("古田一郎", List.of(7, 8, 9), "Futa Ichiro", Map.of("Futa Ichiro", 3))));
        List<DocNames.Compared> out = DocNames.nested(compared, sources, translations);
        assertEquals("Yamada", out.get(0).rendering(), "nothing inside it: kept");
        assertTrue(out.get(1).doubted() && out.get(1).said().equals("Yamada Kenta cabinet (it adds \"Kenta\", which \"Yamada Cabinet\" does without)"), out.get(1).toString());
        assertTrue(out.get(3).doubted() && out.get(3).said().equals("Futa Ichiro (it does not write \"Furuta\", the form decided for 古田)"), out.get(3).toString());
        // a choice that holds the shorter one's form and adds only what the other forms write too is kept
        List<DocNames.Compared> good = DocNames.nested(List.of(byReader("山田", List.of(0, 1, 2), "Yamada", Map.of("Yamada", 3)), byReader("山田内閣", List.of(3, 4, 5), "Yamada Cabinet", Map.of("Yamada Cabinet", 2, "Yamada Kenta cabinet", 1))), sources, translations);
        assertEquals("Yamada Cabinet", good.get(1).rendering());
        // the prompt says a shorter sure form beats a longer guess
        assertTrue(DocNames.reading("English").contains("A shorter form you are sure of is better than a longer one that adds a reading you cannot check against the original, and none is the right answer when every form misreads the name."));
    }

    /** The reader's reading is told a settled part as a part, and that the program flags a unit that writes a settled name otherwise; a revision is never told a part as the name's rendering. */
    @Test void theReaderIsToldAPartAsAPartAndLeavesTheProgramsFlagsToIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = Files.createDirectories(tmp.resolve("docs")).resolve("記録.md"); Files.writeString(in, DocNamesTest.FAMILY, StandardCharsets.UTF_8);
        TranslationTest.Script sc = DocNamesTest.familyScript();
        Outcome o = Translation.run(store, in, "en", "", "reviewed", tmp.resolve("r.en.md"), null, TranslationTest.seats(sc), null, () -> false);
        assertEquals(DocNames.BY_PART, o.decided().of("山田太郎").by());
        List<String> checks = sc.systems.stream().filter(x -> x.startsWith("You check a translation")).toList();
        assertFalse(checks.isEmpty());
        assertTrue(checks.stream().anyMatch(x -> x.contains(" These names and terms are settled for the document and right as the translation writes them, each where it stands on its own; the program itself flags a unit that writes one otherwise, so your lines are for other errors: 山田太郎 is written with Yamada")), checks.toString());
        assertTrue(sc.systems.stream().noneMatch(x -> x.contains("山田太郎 → Yamada")), "a part is never said as the name's rendering, to the reader or to a revision: " + sc.systems.stream().filter(x -> x.contains("山田太郎 → Yamada")).map(x -> x.substring(0, Math.min(80, x.length())) + " … " + x.substring(Math.max(0, x.indexOf("山田太郎 → Yamada") - 120), Math.min(x.length(), x.indexOf("山田太郎 → Yamada") + 40))).toList());
    }

    static final String MINISTRY = "# 厚生労働省の一年\n\n"
            + "厚生労働省は春に新しい制度の案を発表し、全国の病院や診療所に広く意見を求めることにした。\n\n"
            + "厚生労働省は夏に病院の代表と会い、人手が足りないという声について長い時間をかけて話し合った。\n\n"
            + "厚生労働省は秋に制度の詳しい案を作り、議会に出すための準備を担当の部署で急いで進めた。\n\n"
            + "厚生労働省は冬に最後の案を公表し、翌年の春から全国で順に実施すると記者たちに説明した。\n";

    static String ministry(String t) {
        String m = t.contains(DocNamesTest.M) || t.contains("春に") ? DocNamesTest.M : "the Health Ministry";
        if (t.contains("一年")) return "A Year of " + m;
        if (t.contains("春に")) return "In spring the " + m + " announced a plan for a new system and decided to ask hospitals and clinics across the country for their views.";
        if (t.contains("夏に")) return "In summer " + m + " met the hospitals' representatives and spent a long time discussing the complaint that there were not enough hands.";
        if (t.contains("秋に")) return "In autumn " + m + " drew up a detailed plan of the system and hurried its preparations in the section in charge to put it to the Diet.";
        if (t.contains("冬に")) return "In winter " + m + " published its final plan and told the reporters it would be put into effect across the country from the next spring.";
        return "訳 " + t;
    }

    /** The reader chose the form one paragraph of four writes: the three others are its strays, and still the job's paragraphs go on the record. */
    @Test void theNamesStepsFlagsDoNotKeepAJobOffTheRecord(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = Files.createDirectories(tmp.resolve("docs")).resolve("一年.md"); Files.writeString(in, MINISTRY, StandardCharsets.UTF_8);
        TranslationTest.Script sc = new TranslationTest.Script();
        sc.decide = t -> t.equals("厚生労働省") ? "organisation | " + DocNamesTest.M : null;
        sc.translate = TranslationReviewedTest::ministry; sc.revise = TranslationReviewedTest::ministry;
        sc.reader = DocNamesTest.reading(DocNamesTest.M);
        Outcome o = Translation.run(store, in, "en", "", "reviewed", tmp.resolve("y.en.md"), null, TranslationTest.seats(sc), null, () -> false, Checkpoint.NONE, "", "J-0061");
        DocNames.Compared m = o.decided().of("厚生労働省");
        assertEquals(DocNames.BY_READER, m.by()); assertEquals(4, m.strays().size(), "the title and three of the four paragraphs");
        assertEquals("", o.withheld(), "three of four paragraphs flagged by the names step, none by what breaks a measurement");
        assertEquals(Map.of("ja→en", 4), TranslationScores.onRecord(store, "J-0061"));
        Translation.Checked c = new Translation.Checked(); c.paragraphs = 4; c.paragraphsFlagged = 3;
        assertEquals("the program's own checks of what breaks a measurement (a missing translation, numbers, addresses, citations, the glossary, the length), before any revision, flagged more than half of its paragraphs major or critical (3 of 4)", Translation.withheld(null, c, Chrome.ENGLISH));
    }

    /** What a reader is sent and writes is counted from what the tier sends, and the plan says both. */
    @Test void theReadersCostCountsWhatTheTierSends(@TempDir Path tmp) throws Exception {
        StringBuilder doc = new StringBuilder("# 町の記録\n\n");
        for (int p = 0; p < 40; p++) doc.append("町の人々は川のそばに集まり、季節ごとの祭りの準備について長い時間をかけて話し合った。その").append(p % 7).append("回目の会合でも同じであった。\n\n");
        Path f = Files.createDirectories(tmp.resolve("docs")).resolve("記録.md"); Files.writeString(f, doc.toString(), StandardCharsets.UTF_8);
        DocUnits.Doc d = DocUnits.cut(Files.readAllBytes(f), "記録.md", PdfReading.Options.preflightOnly());
        assertArrayEquals(new long[]{0, 0}, Translation.readerTokens(d.translatable(), "en", Translation.CHECKED, List.of()));
        long[] t = Translation.readerTokens(d.translatable(), "en", Translation.REVIEWED, List.of(5, 5, 5, 5, 5));
        int units = d.translatable().size();
        assertTrue(t[0] > units * (Translation.JUDGE_INSTRUCTIONS / 2), "every unit read is read again after its revision half the time, each with the instructions: " + t[0]);
        assertEquals(units * Translation.WRITTEN_PER_UNIT + (5 * 2 + 2) * 10, t[1], "written: each unit read, and the names step's lines for two of the five strings, five passages each");
        Translation.Plan plan = new Translation.Plan("記録.md", "md", "ja", "en", "md", Translation.REVIEWED, units, 2000, 10, 20, 60, 90, false, "kimi", true, 0.4066, 201722, "", false, 0, 0, "", null, null, 0, 0, null, 22993);
        assertTrue(plan.costSaid().equals("the reader kimi is a hosted model: about $0.41 at its list prices (tokens sent: about 201,722; written: about 22,993)"), plan.costSaid());
    }

    /** A hosted host's reply, its usage counted by the reader's seat and filed: prompt, completion and cached each in its own field, the money as the prices make it. */
    @Test void theSpendRecordFilesTheReplysTokensInTheirFields(@TempDir Path tmp) throws Exception {
        HttpServer h = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        h.createContext("/", x -> {
            byte[] out = ("{\"id\":\"gen-1\",\"provider\":\"Moonshot AI\",\"model\":\"moonshotai/kimi-k3\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"1. OK\"},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":84379,\"completion_tokens\":12750,\"total_tokens\":97129,\"prompt_tokens_details\":{\"cached_tokens\":0},\"completion_tokens_details\":{\"reasoning_tokens\":0},\"cost\":0.2202}}").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, out.length);
            try (OutputStream os = x.getResponseBody()) { os.write(out); }
        });
        h.start();
        try {
            long[] used = new long[3];
            Researcher.Drive reader = Translation.metered(Researcher.drive(new DriveClient("http://127.0.0.1:" + h.getAddress().getPort(), "moonshotai/kimi-k3")), used);
            ObjectMapper om = new ObjectMapper();
            for (int k = 0; k < 2; k++) { ArrayNode messages = om.createArrayNode(); messages.addObject().put("role", "user").put("content", "1. SOURCE: 一 TRANSLATION: one"); reader.prose(messages, 50); }
            assertArrayEquals(new long[]{168758, 25500, 0}, used, "the seat counts prompt, completion, cached");
            LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
            ModelEntries.Entry kimi = new ModelEntries.Entry("kimi", "https://openrouter.ai/api/v1", "moonshotai/kimi-k3", new double[]{0.192, 16}, "", 0, "", "");
            Researcher.Drive local = TranslationTest.standIn(new TranslationTest.Script(), "local");
            Seats seats = new Seats(local, "local", "local", null, reader, "moonshotai/kimi-k3", "kimi", kimi, false, false, "", new long[3], used);
            Translation.tally(store, "J-0062", seats, 60);
            var line = Spend.lines(store).get(0);
            assertEquals(168758, line.path("prompt").asLong()); assertEquals(25500, line.path("completion").asLong()); assertEquals(0, line.path("cached").asLong());
            assertEquals(0.4404, line.path("cost").asDouble(), 1e-9);
        } finally { h.stop(0); }
    }

    // ---- the reader's lines that are no error ----

    @Test void aLineWhoseCorrectionIsItsOwnWordsOrThatSaysTheTranslationIsRightIsNoFlag() {
        // its correction is the words it flags: in the line's own field, or, in the earlier form, in its sentence; case, accents and quotes folded
        assertEquals(Translation.ASIDE_SAME, Translation.asideReason("Kobayashi Club", "Kobayashi Club", "the source says 小林会 (Kobayashi Club), not Kobayashi Club", "小林会が集まった。", null, 0));
        assertEquals(Translation.ASIDE_SAME, Translation.asideReason("Kōbayashi Shobō", null, "the source says 小林書房 (Kobayashi Shobo), not \"Kōbayashi Shobō\"", "小林書房", null, 0));
        assertEquals(Translation.ASIDE_SAME, Translation.asideReason("Ueda Kenji", null, "the source says 上田健二 (Ueda Kenji), but the reading is questionable; it could be Ueda Takeji", "上田健二", null, 0));
        assertEquals(-1, Translation.asideReason("February", null, "the source ends with 二月 (February), but the translation adds \"February\" without completing the sentence", "二月", null, 0), "the sentence says what is wrong in its own words");
        assertEquals(-1, Translation.asideReason("8-1", null, "The source gives the issues \"8-1, 2\" (8-1), but the translation only includes \"8-1\".", "八－一・二", null, 0));
        assertEquals(-1, Translation.asideReason("Kozuka Wataru", null, "the source name is 小塚亘 (Kotsuka Wataru), not Kozuka Wataru", "小塚亘", null, 0), "a differing reading is a flag");
        // it says the translation is right, and asks for no change
        assertEquals(Translation.ASIDE_RIGHT, Translation.asideReason("absence", "", "欠席 is rendered as \"absence\", which is acceptable, though \"non-attendance\" is fine.", "欠席", null, 0));
        assertEquals(Translation.ASIDE_RIGHT, Translation.asideReason("rejected", null, "却下する means rejected or dismissed; fine. No error.", "却下する", null, 0));
        assertEquals(Translation.ASIDE_RIGHT, Translation.asideReason("Village Law", null, "村法 is glossed as Village Law, and the translation uses this correctly.", "村法", null, 0));
        assertEquals(-1, Translation.asideReason("unruly men", null, "\"unruly men\" is acceptable but \"rampant activity\" adds a word not in the source", "不逞の徒", null, 0));
        assertEquals(-1, Translation.asideReason("misfortune", "scandal", "不祥事 is rendered as \"misfortune\" but should be \"scandal\"; the translation is acceptable but not precise.", "不祥事", null, 0));
        assertEquals(-1, Translation.asideReason("Masaaki Ueno", null, "上野正明 is the well-known Ueno Masaaki; \"Masaaki\" order aside, the given name is a misreading", "上野正明", null, 0));
        // an earlier reply, with no correction field, still parses into a flag
        TranslationTest.Script sc = new TranslationTest.Script();
        sc.verdict = x -> x.contains("one") ? "accuracy/omission critical | 一 | the second half is missing" : x.contains("two") ? "accuracy/mistranslation major | 二 | 二 | the source says two (二), not 二" : null;
        List<DocUnits.Unit> units = List.of(DocUnits.Unit.of("para", "one"), DocUnits.Unit.of("para", "two"));
        int[] aside = new int[3];
        List<Translation.Flag> flags = Translation.judge(TranslationTest.twoSeats(sc), units, Map.of(0, "一", 1, "二"), List.of(0, 1), "Japanese", Map.of(), Set.of(), null, false, aside);
        assertEquals(List.of(new Translation.Flag(0, "accuracy", "omission", "critical", "一", "the second half is missing", "checker", false, false)), flags);
        assertArrayEquals(new int[]{1, 0, 0}, aside);
    }

    /**
     * A reviewed run whose reader writes a line for each paragraph: one asks for the settled form in a unit the program flags for it, one
     * gives its words as the correction, one says the translation is acceptable, and one is an error. The first three are no flag; the note
     * says so in one sentence.
     */
    @Test void theReadersLinesThatAreNoErrorAreSetAsideAndCounted(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = Files.createDirectories(tmp.resolve("docs")).resolve("一年.md"); Files.writeString(in, MINISTRY, StandardCharsets.UTF_8);
        TranslationTest.Script sc = new TranslationTest.Script();
        sc.decide = t -> t.equals("厚生労働省") ? "organisation | " + DocNamesTest.M : null;
        sc.translate = TranslationReviewedTest::ministry; sc.revise = TranslationReviewedTest::ministry;
        sc.reader = DocNamesTest.reading(DocNamesTest.M);
        sc.verdict = x -> x.contains("夏に") ? "accuracy/mistranslation major | the Health Ministry | " + DocNamesTest.M + " | the source says 厚生労働省 (" + DocNamesTest.M + "), not the Health Ministry"
                : x.contains("秋に") ? "accuracy/mistranslation major | hurried | Hurried | the source says 急いで (hurried), not hurried"
                : x.contains("冬に") ? "accuracy/mistranslation major | published | | 公表 is rendered as \"published\", which is acceptable"
                : x.contains("春に") ? "accuracy/omission major | hospitals and clinics | hospitals and clinics in every region | the source says 全国の病院や診療所, and the translation drops every region"
                : null;
        Outcome o = Translation.run(store, in, "en", "", "reviewed", tmp.resolve("r.en.md"), null, TranslationTest.seats(sc), null, () -> false);
        List<Translation.Flag> fromReader = o.flags().stream().filter(f -> f.by().equals("stand-in")).toList();
        assertTrue(fromReader.stream().anyMatch(f -> f.kind().equals("omission") && f.words().equals("hospitals and clinics")), o.flags().toString());
        assertTrue(fromReader.stream().noneMatch(f -> f.words().equals("hurried") || f.words().equals("published") || f.words().equals("the Health Ministry")), fromReader.toString());
        assertTrue(o.flags().stream().anyMatch(f -> f.kind().equals("name") && f.by().equals("program")), "the program's own flag for the stray stands: " + o.flags());
        assertArrayEquals(new int[]{1, 1, 1}, o.readerAside());
        assertTrue(Files.readString(o.note()).contains(" Lines of the reader set aside as no error: 3 (giving as the correction the very words they flag: 1; saying the translation is right, with nothing to change: 1; asking for a name or term the program settled and flags itself: 1)."), Files.readString(o.note()));
        // the reader is asked for the correction in a field of its own
        assertTrue(sc.systems.stream().anyMatch(x -> x.startsWith("You check a translation") && x.contains("a vertical bar, the words concerned as they stand in the translation, a vertical bar, the same words as they should read, a vertical bar, and one sentence saying what is wrong. Write a line only where the words as they should read differ from the words as they stand.")));
    }
}
