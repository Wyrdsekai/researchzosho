package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.Translation.Outcome;
import org.researchzosho.librarian.Translation.Seats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Names and recurring terms decided once for a document: the program finds the candidates, the model renders each once with its kind
 * and may turn one down, every unit is translated with the entries it holds, the check flags the unit that strays and the revision puts
 * it right, the original stands once in parentheses where a name first appears, the note lists the decided strings and says what was
 * done, a resumed job does not decide again and a second translation reuses the list, the person's glossary wins. Then the record the
 * checks are measured from: each unit under its job, a job taken off it, a job kept off it. The documents are made up here; the model is
 * a stand-in that, untold, renders one name two ways.
 */
class DocNamesTest {
    @BeforeEach void scorers() { TranslationScores.SCORERS = () -> new TranslationScores.Scorers(TranslationTest.labseStandIn(), null); Translation.SETUP_SAID = () -> ""; }
    @AfterEach void back() { TranslationScores.SCORERS = TranslationScores::configured; Chrome.clear(); }

    // ---- the finder ----

    @Test void inJapaneseTheRepeatedRunsOfHanAndKatakanaCutAtKanaAreFoundNotDatesNorPieces() {
        List<String> units = List.of("山田太郎と委員会", "佐藤花子",
                "昭和三十年五月、山田太郎は厚生労働省の委員会で説明した。",
                "昭和三十年五月の会議で、山田太郎は厚生労働省に報告した。",
                "議会ノ決定ヲ尊重シ、山田太郎氏はアフリカ開発会議に出た。",
                "議会ノ決定ヲ尊重シ、アフリカ開発会議は厚生労働省と話した。",
                "帰りにコーヒーを飲んだ。", "翌日もコーヒーを飲んだ。",
                "ジョン・スミスは名古屋・福岡を回った。", "ジョン・スミスは名古屋・福岡で話した。");
        List<String> found = new ArrayList<>(); for (DocNames.Candidate c : DocNames.candidates(units, "山田太郎と委員会")) found.add(c.text());
        assertTrue(found.containsAll(List.of("山田太郎", "厚生労働省", "アフリカ開発会議", "コーヒー")), "the name, the ministry, the conference, a word with the long sound mark inside: " + found);
        assertTrue(found.contains("委員会") && !found.contains("佐藤花子"), "a string that stands on its own in fewer than two units is no candidate, the author's line among them: " + found);
        assertFalse(found.stream().anyMatch(s -> s.contains("昭和三十年") || s.equals("二月")), "a date in Han is no name: " + found);
        assertFalse(found.stream().anyMatch(s -> s.contains("ノ") || s.contains("ヲ")), "the katakana particles of the older writing cut the runs: " + found);
        assertTrue(found.contains("ジョン・スミス") && !found.contains("名古屋・福岡"), "a middle dot joins a foreign name in katakana and lists two things in Han: " + found);
        assertFalse(found.contains("田太郎") || found.contains("山田太"), "a piece of a longer string that stands where it stands is not one: " + found);
        assertTrue(DocNames.dateOrNumber("昭和三〇年五月") && DocNames.dateOrNumber("一八八") && !DocNames.dateOrNumber("田中健一") && !DocNames.dateOrNumber("鈴木三郎"));
    }

    @Test void inEnglishTheCapitalisedNamesThatRecurNotASentencesFirstWord() {
        List<String> units = List.of("A Scandal at Homebox", "Ada Lindqvist",
                "The letter came on a Tuesday. Holmes read it twice and passed it to Watson, who gave it back to Holmes.",
                "Holmes said nothing about Briony Lodge. The house stood empty.",
                "Watson walked to Briony Lodge. Holmes followed him.",
                "The evening was cold.");
        List<String> found = new ArrayList<>(); for (DocNames.Candidate c : DocNames.candidates(units, "A Scandal at Homebox")) found.add(c.text());
        assertTrue(found.containsAll(List.of("Holmes", "Watson", "Briony Lodge")) && !found.contains("Ada Lindqvist"), found.toString());
        assertFalse(found.contains("The") || found.contains("Tuesday.") || found.contains("Briony Lodge."), "a sentence's first word, a full stop: " + found);
    }

    @Test void inGermanANounAfterItsArticleIsNoName() {
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < 12; i++) t.append("Gregor sah die Schwester an, und die Schwester brachte dem Vater die Zeitung aus dem Zimmer. ");
        List<String> units = List.of("Die Nachricht", t.toString(), "Am Abend kam Grete mit der Schwester, und Gregor hörte zu.", "Grete und Gregor sprachen lange über die Reise, und dann ging Grete schlafen.");
        List<String> found = new ArrayList<>(); for (DocNames.Candidate c : DocNames.candidates(units, "Die Nachricht")) found.add(c.text());
        assertTrue(DocNames.capitalisesNouns(String.join(" ", units)));
        assertTrue(found.contains("Gregor") && found.contains("Grete"), found.toString());
        assertFalse(found.contains("Schwester") || found.contains("Vater") || found.contains("Zeitung"), "nouns with their article: " + found);
    }

    @Test void aPieceThatStandsInsideALongerStringIsNoCandidate() {
        List<String> units = List.of("山田太郎は町長であった。", "山田太郎の意見を聞いた。", "太郎の書簡が残る。", "青葉市立図書館と青葉市立病院が論じられた。", "青葉市立図書館は延期された。", "青葉市立病院は変わらなかった。");
        List<String> found = new ArrayList<>(); for (DocNames.Candidate c : DocNames.candidates(units, "")) found.add(c.text());
        assertTrue(found.contains("山田太郎") && found.contains("青葉市立図書館") && found.contains("青葉市立病院"), found.toString());
        assertFalse(found.contains("太郎"), "a given name that stands on its own once is a piece of the full name: " + found);
        assertFalse(found.contains("青葉市立"), "a fragment that never stands on its own: " + found);
        Map<Integer, String> byUnit = new LinkedHashMap<>(); for (int i = 0; i < units.size(); i++) byUnit.put(i, units.get(i));
        assertEquals(List.of(2), DocNames.holders("太郎", List.of("山田太郎", "太郎"), byUnit), "the one unit where it stands on its own");
    }

    // ---- a document compared across its first pass ----

    static final String DOC = "# 山田太郎と委員会\n\n佐藤花子\n\n"
            + "山田太郎は厚生労働省の委員会で、新しい制度について三時間にわたり説明した。委員の多くは賛成した。\n\n"
            + "委員会の後、山田太郎は記者に対して、制度の目的は地域の病院を支えることだと述べ、質問に答えた。\n\n"
            + "厚生労働省は翌月、山田太郎の説明をもとに、制度の詳しい案を作り、それを広く公表した。\n\n"
            + "地方の病院からは、山田太郎の案に対して、人手が足りないという声が多く寄せられたと伝えられた。\n\n"
            + "山田太郎は最後に、厚生労働省と病院が話し合う場を毎月設けると約束し、会は終わった。\n";

    static Path doc(Path dir) throws IOException { Files.createDirectories(dir); Path f = dir.resolve("会議録.md"); Files.writeString(f, DOC, StandardCharsets.UTF_8); return f; }

    static final String Y = "Taro Yamada", M = "Ministry of Health, Labour and Welfare", S = "Hanako Sato";

    /**
     * The stand-in: the English of each unit by a word of it. Its first pass writes the person as "Taro Yamada" in five units and, in the
     * unit about the hospitals' voices, as an invented "Kentaro Yamagata" (the stray); a revision follows the line it is given. Its answers
     * after the first pass are {@code answers}' (null: turned down).
     */
    static TranslationTest.Script script(Map<String, String> answers) {
        TranslationTest.Script sc = new TranslationTest.Script();
        sc.decide = answers::get;
        sc.translate = t -> english(t, true);
        sc.revise = t -> english(t, false);
        return sc;
    }

    static final Map<String, String> RIGHT = Map.of("山田太郎", "person | " + Y, "厚生労働省", "organisation | " + M, "委員会", "term | committee");

    static TranslationTest.Script script() { return script(RIGHT); }

    static String english(String t, boolean strays) {
        String y = t.contains("Yamada Tarō") ? "Yamada Tarō" : Y;   // the person's glossary, when it renders the name
        if (t.contains("三時間")) return y + " explained the new system for three hours at the committee of " + M + ". Most of the members agreed.";
        if (t.contains("記者")) return "After the committee, " + y + " told reporters that the aim of the system was to support the hospitals of the regions, and answered questions.";
        if (t.contains("翌月")) return "The next month " + M + " made a detailed plan of the system based on the explanation of " + y + " and published it widely.";
        if (t.contains("人手")) return "From the hospitals of the regions came many voices against the plan of " + (strays ? "Kentaro Yamagata" : y) + ", saying that hands were short.";
        if (t.contains("毎月")) return "At the end " + y + " promised that " + M + " and the hospitals would meet every month, and the meeting ended.";
        if (t.contains("佐藤花子") || t.contains(S)) return S;
        if (t.contains("委員会") || t.contains("committee")) return y + " and the committee";
        return "訳 " + t;   // a back-translation's text
    }

    static Seats seats(TranslationTest.Script sc) { return TranslationTest.seats(sc); }

    @Test void afterTheFirstPassTheWayMostUnitsWriteANameIsDecidedAndTheStrayIsRevisedTowardIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = doc(tmp.resolve("docs"));
        TranslationTest.Script sc = script();
        Outcome o = Translation.run(store, in, "en", "", "checked", tmp.resolve("decided.en.md"), null, seats(sc), null, () -> false, Checkpoint.NONE, "", "J-0042");
        String out = Files.readString(o.output());
        // nothing decided before the first pass: the units are translated first, the names compared after
        assertTrue(sc.steps.indexOf("names") > sc.steps.indexOf("lines"), "the first pass, then the comparison: " + sc.steps);
        assertEquals(1, sc.steps.stream().filter(x -> x.equals("names")).count(), "in one call");
        DocNames.Compared y = o.decided().of("山田太郎");
        assertEquals(Y, y.rendering()); assertEquals(5, y.writes()); assertEquals(6, y.held()); assertEquals(List.of(5), y.strays(), "the unit about the hospitals' voices, the sixth, is the stray");
        assertEquals(Set.of("委員会", "厚生労働省", "山田太郎"), Set.copyOf(o.decided().entries().stream().map(DocNames.Entry::source).toList()), "the ordinary words turned down are not decided: " + o.decided());
        // the stray is flagged, told what the rest of the document writes, and revised toward it
        assertTrue(o.flags().stream().anyMatch(f -> f.unit() == 5 && f.kind().equals("name") && f.severity().equals(Translation.MAJOR) && f.why().equals("the rest of this document writes 山田太郎 as \"Taro Yamada\" (5 of 6 units)") && f.revised() && !f.stands()), o.flags().toString());
        assertEquals(1, o.flags().stream().filter(f -> f.kind().equals("name")).count(), "no other unit is flagged for a name: " + o.flags());
        assertFalse(out.contains("Kentaro Yamagata"), out);
        // the original once, where each name first stands, set by the program; the title passed over; a term never
        assertTrue(out.contains(Y + " (山田太郎) explained") && out.contains(M + " (厚生労働省). Most"), out);
        assertEquals(1, out.split("\\(山田太郎\\)", -1).length - 1, "once"); assertEquals(1, out.split("\\(厚生労働省\\)", -1).length - 1, "once");
        assertTrue(out.startsWith("# " + Y + " and the committee\n"), "the title holds no original: " + out);
        assertFalse(out.contains("(委員会)") || out.contains("(佐藤花子)"), "a term has no original beside it, nor a name only one unit holds");
        // the note says what happened, with counts
        String note = Files.readString(o.note());
        assertTrue(note.contains("- Names decided after the first pass: 2; of them, set with their original in parentheses by the program where each first appears: 2. Any other name stands as the model wrote it, with no original beside it. Letters in the script of the original that stand in the translation: 9."), note);
        assertTrue(note.contains("## Names and terms decided for this document\n\nCandidates the program found in the document: ") && note.contains("Decided: 3; not decided, the words named standing in too few of the units or being another string's: 0; not decided, the words named standing in none of them: 0; turned down: ")
                && note.contains("| 山田太郎 | Taro Yamada | person | 5 of 6 | 6 |") && note.contains("| 厚生労働省 | Ministry of Health, Labour and Welfare | organisation | 3 of 3 |  |")
                && note.contains("A unit that writes a decided name or term otherwise is flagged, a name as major and a term as minor, and revised toward the decided form. Units that still differ after the revisions: 0."), note);
        assertTrue(note.contains("researchzosho glossary add \"<source>\" en \"<rendering>\", or \"render <source> as <rendering> in English\" in the chat"), note);
        Map<String, String> read = ChatWords.ask("glossary.add", "render 山田太郎 as Taro Yamada in English", TranslateTurn.places(), false, x -> !ChatWords.language(x.getOrDefault("lang", "")).isEmpty() && x.get("term") != null && x.get("rendering") != null);
        assertTrue(read != null && read.get("term").equals("山田太郎") && read.get("rendering").equals("Taro Yamada"), "the note's chat phrase is one the chat reads: " + read);
        assertTrue(o.summary().contains("Compared across the document after the first pass, names and terms written one way in most of their units: 3; units revised toward that way: 1. 2 names carry their original in parentheses where each first appears."), o.summary());
        // the plan says it before the run, with the count the run compares
        Translation.Plan plan = Translation.plan(store, in, "en", "", "checked", "stand-in", "");
        assertEquals(o.decided().compared().size(), plan.names());
        assertTrue(plan.said().contains("after the first pass, the names and terms the document repeats are compared across its units (candidates: " + plan.names() + "), and a unit that writes one otherwise than most is revised"), plan.said());
        // a second translation of the same document reuses the answers and checks them again against its own first pass
        TranslationTest.Script again = script();
        Outcome second = Translation.run(store, in, "en", "", "quick", tmp.resolve("again.en.md"), null, seats(again), null, () -> false);
        assertFalse(again.steps.contains("names"), again.steps.toString());
        assertTrue(second.decided().reused() && Files.readString(second.note()).contains("The model's answers were kept from an earlier translation of the same document into English; the program checked them again against this translation."), Files.readString(second.note()));
        assertEquals(o.decided().entries(), second.decided().entries());
        assertTrue(Files.readString(second.output()).contains("Kentaro Yamagata") && Files.readString(second.note()).contains("The quick tier revises nothing: units that write a decided name or term otherwise than the rest: 1."), "the quick tier revises nothing and says so");
    }

    /** The verification holds whatever the model says: an answer the translations do not write, another person of the same sentences, a longer name's words. */
    @Test void aWrongAnswerIsNeverDecided(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = doc(tmp.resolve("docs"));
        Map<String, String> wrong = Map.of("山田太郎", "person | Yamagata Kentaro", "厚生労働省", "person | " + Y, "委員会", "organisation | " + M);
        TranslationTest.Script ws = script(wrong);
        Outcome o = Translation.run(store, in, "en", "", "checked", tmp.resolve("wrong.en.md"), null, seats(ws), null, () -> false);
        assertEquals(List.of("names", "names again"), ws.steps.stream().filter(x -> x.startsWith("names")).toList(), "asked once more for the answer that stands in no unit, and only once: " + ws.steps);
        assertTrue(o.decided().entries().isEmpty(), "none of the three is decided: " + o.decided());
        assertEquals("Yamagata Kentaro", o.decided().of("山田太郎").leading()); assertEquals(0, o.decided().of("山田太郎").leadingWrites(), "not written in the translations at all, nor either of its parts in more than one unit");
        assertEquals(3, o.decided().of("厚生労働省").leadingWrites(), "the person named in every passage about the ministry is written in all three, and goes with the person, not the ministry");
        assertTrue(o.flags().stream().noneMatch(f -> f.kind().equals("name")), "no unit is pushed toward a wrong answer: " + o.flags());
        String note = Files.readString(o.note());
        // the note says what the program knows: words in too few units, or another string's, apart from words in none of them
        assertTrue(note.contains("Decided: 0; not decided, the words named standing in too few of the units or being another string's: 2; not decided, the words named standing in none of them: 1; turned down: 2."), note);
        assertTrue(note.contains("Not decided: the words the model named stand at the string's place in too few of the units that hold it, or they are the words of another string those units hold")
                && note.contains("| 厚生労働省 | Taro Yamada | 3 of 3 | 山田太郎 |") && note.contains("| 委員会 | " + M + " | 1 of 3 |  |"), note);
        assertTrue(note.contains("Not decided: the words the model named stand in none of the units that hold the string, even when it was asked once more.") && note.contains("| 山田太郎 | Yamagata Kentaro |\n"), note);
        assertFalse(note.contains("Written more than one way"), note);
        // with the right answer for the person beside the wrong one for the ministry, the person keeps the words
        Map<String, String> mixed = Map.of("山田太郎", "person | " + Y, "厚生労働省", "person | " + Y);
        LibraryStore fresh = new LibraryStore(tmp.resolve("fresh")); fresh.init();   // a library that kept no answers for this document
        Outcome m = Translation.run(fresh, in, "en", "", "quick", tmp.resolve("mixed.en.md"), null, seats(script(mixed)), null, () -> false);
        assertEquals(List.of("山田太郎"), m.decided().entries().stream().map(DocNames.Entry::source).toList(), m.decided().toString());
    }

    @Test void thePersonsGlossaryWinsAndItsStringIsNotCompared(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = doc(tmp.resolve("docs"));
        Glossary.add(store, "山田太郎", "English", "Yamada Tarō");
        TranslationTest.Script sc = script();
        Outcome o = Translation.run(store, in, "en", "", "checked", null, null, seats(sc), null, () -> false);
        String out = Files.readString(o.output());
        assertFalse(out.contains(Y) || out.contains("Kentaro Yamagata"), "the person's rendering everywhere, the stray unit revised to it: " + out);
        assertNull(o.decided().of("山田太郎"), "a string the person's glossary renders is not compared");
        assertFalse(out.contains("(山田太郎)"), "the person's own term carries no original");
        assertTrue(o.flags().stream().anyMatch(f -> f.kind().equals("glossary") && f.severity().equals(Translation.MAJOR) && !f.stands()), o.flags().toString());
    }

    /** A list kept by the build that decided before the first pass has no version: it is never used, and the model is asked again. */
    @Test void aListKeptBeforeTheRedesignIsNeverUsed(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = doc(tmp.resolve("docs"));
        Path kept = DocNames.keptFile(store, Checkpoint.sha(new String(Files.readAllBytes(in), StandardCharsets.ISO_8859_1)), "en");
        Files.createDirectories(kept.getParent());
        Files.writeString(kept, "{\"entries\": [{\"source\": \"山田太郎\", \"rendering\": \"Kentaro Yamagata\", \"kind\": \"person\"}], \"found\": 9, \"turned_down\": 0, \"reused\": false}\n");
        TranslationTest.Script sc = script();
        Outcome o = Translation.run(store, in, "en", "", "quick", null, null, seats(sc), null, () -> false);
        assertTrue(sc.steps.contains("names"), "asked again: " + sc.steps);
        assertFalse(o.decided().reused()); assertEquals(Y, o.decided().of("山田太郎").rendering());
        assertTrue(Files.readString(kept).contains("\"version\" : 2"), Files.readString(kept));
    }

    /** A job ended partway after the comparison's call: the next process gives the answers back from the job's kept work and asks nothing again. */
    @Test void aResumedJobDoesNotAskAgain(@TempDir Path tmp) throws Exception {
        Path in = doc(tmp.resolve("docs"));
        LibraryStore whole = new LibraryStore(tmp.resolve("whole")); whole.init();
        Outcome w = Translation.run(whole, in, "en", "", "checked", tmp.resolve("whole.en.md"), null, seats(script()), null, () -> false);
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Checkpoint first = Checkpoint.open(store, "J-0007", Translation.JOB_KIND, "b");
        TranslationTest.Script one = script();
        CheckpointTest.Counting d = new CheckpointTest.Counting(TranslationTest.standIn(one, "stand-in"), 3);   // the first pass, the comparison's call, then the process ends
        assertThrows(IllegalStateException.class, () -> Translation.run(store, in, "en", "", "checked", tmp.resolve("job.en.md"), null, Translation.kept(new Seats(d, "stand-in", d, "stand-in"), first), null, () -> false, first));
        first.close();
        assertTrue(one.steps.contains("names"), one.steps.toString());
        Checkpoint second = Checkpoint.open(store, "J-0007", Translation.JOB_KIND, "b");
        TranslationTest.Script sc = script();
        Outcome r = Translation.run(store, in, "en", "", "checked", tmp.resolve("job.en.md"), null, Translation.kept(seats(sc), second), null, () -> false, second);
        second.close();
        assertFalse(sc.steps.contains("names"), "the answers come back from the job's kept work: " + sc.steps);
        assertEquals(w.decided().entries(), r.decided().entries());
        assertEquals(Files.readString(w.output()), Files.readString(r.output()));
    }

    /**
     * A small model echoes the source for its answer: the words stand in none of the units, so the program asks once more, for those
     * strings only, showing the earlier words; the second answer is verified like the first, and both are kept with the job.
     */
    @Test void anAnswerThatEchoesTheSourceIsAskedOnceMoreAndKeptWithTheJob(@TempDir Path tmp) throws Exception {
        Path in = doc(tmp.resolve("docs"));
        Map<String, String> echo = Map.of("山田太郎", "person | 山田太郎", "厚生労働省", "organisation | " + M, "委員会", "term | 委員会");
        Map<String, String> copied = Map.of("山田太郎", "person | " + Y, "委員会", "term | committee");
        LibraryStore whole = new LibraryStore(tmp.resolve("whole")); whole.init();
        TranslationTest.Script w = script(echo); w.again = copied::get;
        Outcome all = Translation.run(whole, in, "en", "", "checked", tmp.resolve("whole.en.md"), null, seats(w), null, () -> false);
        assertEquals(List.of("names", "names again"), w.steps.stream().filter(x -> x.startsWith("names")).toList(), w.steps.toString());
        assertEquals(Set.of("委員会", "厚生労働省", "山田太郎"), Set.copyOf(all.decided().entries().stream().map(DocNames.Entry::source).toList()), all.decided().toString());
        assertEquals(Y, all.decided().of("山田太郎").rendering()); assertEquals(5, all.decided().of("山田太郎").writes());
        // a job ended after the second call: the next process asks neither again
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Checkpoint first = Checkpoint.open(store, "J-0008", Translation.JOB_KIND, "b");
        TranslationTest.Script one = script(echo); one.again = copied::get;
        CheckpointTest.Counting d = new CheckpointTest.Counting(TranslationTest.standIn(one, "stand-in"), 4);   // the first pass, the two calls, then the process ends
        assertThrows(IllegalStateException.class, () -> Translation.run(store, in, "en", "", "checked", tmp.resolve("job.en.md"), null, Translation.kept(new Seats(d, "stand-in", d, "stand-in"), first), null, () -> false, first));
        first.close();
        assertTrue(one.steps.contains("names again"), one.steps.toString());
        Checkpoint second = Checkpoint.open(store, "J-0008", Translation.JOB_KIND, "b");
        TranslationTest.Script sc = script(echo); sc.again = copied::get;
        Outcome r = Translation.run(store, in, "en", "", "checked", tmp.resolve("job.en.md"), null, Translation.kept(seats(sc), second), null, () -> false, second);
        second.close();
        assertTrue(sc.steps.stream().noneMatch(x -> x.startsWith("names")), "both answers come back from the job's kept work: " + sc.steps);
        assertEquals(all.decided().entries(), r.decided().entries());
        assertEquals(Files.readString(all.output()), Files.readString(tmp.resolve("job.en.md")));
    }

    /** The string written beside the words, in another script, is taken off; what is left is what the model named, and is checked like any answer. */
    @Test void theStringWrittenBesideItsWordsIsTakenOff() {
        assertEquals("Hanako Sato", DocNames.withoutEcho("佐藤花子 Hanako Sato", "佐藤花子"));
        assertEquals("Hanako Sato", DocNames.withoutEcho("Hanako Sato (佐藤花子)", "佐藤花子"));
        assertEquals("Kitagawa Town", DocNames.withoutEcho("Kitagawa Town 北川町", "北川町"));
        assertEquals("佐藤花子", DocNames.withoutEcho("佐藤花子", "佐藤花子"), "an answer that is only the string stays, and fails the check");
        assertEquals("Library 図書館 Library", DocNames.withoutEcho("Library 図書館 Library", "図書館"), "only at the start or the end");
        assertEquals("Holmes Sherlock", DocNames.withoutEcho("Holmes Sherlock", "Holmes"), "the same script: nothing to tell apart");
    }

    /** Echoed twice: nothing is decided, and the note says the answers stand in no unit, never that the translation writes a name more than one way. */
    @Test void anAnswerThatStandsInNoUnitSaysNothingOfTheTranslation(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = doc(tmp.resolve("docs"));
        TranslationTest.Script sc = script(Map.of("山田太郎", "person | 山田太郎", "厚生労働省", "organisation | 厚生労働省", "委員会", "term | 委員会"));
        Outcome o = Translation.run(store, in, "en", "", "checked", tmp.resolve("echo.en.md"), null, seats(sc), null, () -> false);
        assertTrue(o.decided().entries().isEmpty() && o.decided().failed().size() == 3 && o.decided().tooFew().isEmpty(), o.decided().toString());
        String note = Files.readString(o.note());
        assertTrue(note.contains("Decided: 0; not decided, the words named standing in too few of the units or being another string's: 0; not decided, the words named standing in none of them: 3; turned down: 2."), note);
        assertTrue(note.contains("| source | words named |\n| --- | --- |\n") && note.contains("| 山田太郎 | 山田太郎 |\n") && note.contains("| 委員会 | 委員会 |\n"), note);
        assertFalse(note.contains("more than one way"), "nothing is said of how the translation writes them: " + note);
        assertTrue(o.flags().stream().noneMatch(f -> f.kind().equals("name")), o.flags().toString());
    }

    /**
     * A name's original goes where the source first holds the string on its own, and never within a longer decided name's rendering:
     * the town's original is not set inside the library's name, in a unit whose source has only the library's.
     */
    @Test void theOriginalOfAShorterNameIsNeverSetInsideALongerOne() {
        Map<Integer, String> sources = Map.of(0, "北川町立図書館は大正十二年に生まれた。", 1, "北川町立図書館は北川町の中心にある。", 2, "昭和九年、北川町は水害に見舞われた。");
        Map<Integer, String> words = new LinkedHashMap<>();
        words.put(0, "The Kitagawa Town Public Library was established in Taisho 12.");
        words.put(1, "The Kitagawa Town Public Library stands in the centre of Kitagawa Town.");
        words.put(2, "In Showa 9, Kitagawa Town was hit by a flood.");
        List<String> strings = List.of("北川町立図書館", "北川町");
        List<Integer> library = DocNames.holders("北川町立図書館", strings, sources), town = DocNames.holders("北川町", strings, sources);
        assertEquals(List.of(1, 2), town.stream().sorted().toList(), "the town stands on its own in the second and third units only");
        // the town listed first, so marked first: the place rule holds in either order
        DocNames.Decided d = new DocNames.Decided(List.of(
                new DocNames.Compared("北川町", town.stream().sorted().toList(), "Kitagawa Town", "place", 2, List.of(), "Kitagawa Town", 2),
                new DocNames.Compared("北川町立図書館", library.stream().sorted().toList(), "Kitagawa Town Public Library", "organisation", 2, List.of(), "Kitagawa Town Public Library", 2)), 2, false);
        DocNames.markOriginals(List.of(0, 1, 2), words, Set.of(), d, "en");
        assertEquals("The Kitagawa Town Public Library (北川町立図書館) was established in Taisho 12.", words.get(0));
        assertEquals("The Kitagawa Town Public Library stands in the centre of Kitagawa Town (北川町).", words.get(1), "the town's own place in the first unit that holds it on its own");
        assertEquals("In Showa 9, Kitagawa Town was hit by a flood.", words.get(2), "once");
    }

    /** Parentheses are set once for a form, and never where parentheses already follow it. */
    @Test void theOriginalIsSetOnceAndNeverTwiceInARow() {
        Map<Integer, String> words = new LinkedHashMap<>(Map.of(0, "The Yamada Cabinet (山田内閣) began.", 1, "The Yamada Cabinet ruled.", 2, "Later the Yamada Cabinet fell."));
        List<Integer> h = List.of(0, 1, 2);
        DocNames.Decided d = new DocNames.Decided(List.of(new DocNames.Compared("山田太郎内閣", h, "Yamada Cabinet", "organisation", 3, List.of(), "Yamada Cabinet", 3), new DocNames.Compared("山田内閣", h, "Yamada Cabinet", "organisation", 3, List.of(), "Yamada Cabinet", 3)), 2, false);
        List<DocNames.Entry> marked = DocNames.markOriginals(List.of(0, 1, 2), words, Set.of(), d, "en");
        assertEquals(1, marked.size(), "one form, one original: " + marked);
        assertEquals("The Yamada Cabinet (山田内閣) began.", words.get(0)); assertEquals("The Yamada Cabinet (山田太郎内閣) ruled.", words.get(1), "the first place without parentheses after it");
        assertEquals("Later the Yamada Cabinet fell.", words.get(2));
    }

    // ---- A: the part of a person's name most units share ----

    /** A person named in two word orders, alone by the family name where only it stands, and misread once. */
    static final String FAMILY = "# 山田太郎の記録\n\n"
            + "山田太郎は町の医者であった。夜でも呼ばれれば出かけた。\n\n"
            + "山田太郎は川の近くに住み、庭で野菜を育てていた。\n\n"
            + "山田太郎は毎朝早く起きて、町の患者を一人ずつ診た。\n\n"
            + "山田太郎の妻は学校の先生で、子どもたちに字を教えた。\n\n"
            + "その年、山田は町を出て都に移り、新しい病院を開いた。\n\n"
            + "都でも山田は医者として働き、町の人々を忘れなかった。\n\n"
            + "川は冬になると凍り、子どもたちはその上で遊んだ。\n";

    static String family(String t, boolean strays) {
        if (t.contains("山田太郎の記録")) return "The Record of Taro Yamada";
        if (t.contains("夜でも")) return "Yamada Taro was the town's doctor. He went out whenever he was called, even at night.";
        if (t.contains("野菜")) return "Taro Yamada lived near the river and grew vegetables in his garden.";
        if (t.contains("毎朝")) return (strays ? "Yamagata Taro" : "Taro Yamada") + " got up early every morning and saw the town's patients one by one.";
        if (t.contains("妻")) return "The wife of Taro Yamada was a teacher at the school and taught the children to write.";
        if (t.contains("その年")) return "That year Yamada left the town, moved to the capital and opened a new hospital.";
        if (t.contains("都でも")) return "In the capital too Yamada worked as a doctor and did not forget the people of the town.";
        if (t.contains("凍り")) return "The river froze in winter, and the children played on it.";
        return "訳 " + t;
    }

    static TranslationTest.Script familyScript() {
        TranslationTest.Script sc = new TranslationTest.Script();
        sc.decide = t -> t.equals("山田太郎") ? "person | Taro Yamada" : null;
        sc.translate = t -> family(t, true);
        sc.revise = t -> family(t, false);
        return sc;
    }

    /**
     * The model named the person "Taro Yamada", which three of the five units holding the name write; the family name stands in four, and
     * where the family name stands alone too: the program settles the name by it, and the unit that misread it is flagged and revised.
     */
    @Test void aPersonNamedInTwoWordOrdersIsSettledByTheFamilyNameMostUnitsShare(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = Files.createDirectories(tmp.resolve("docs")).resolve("記録.md"); Files.writeString(in, FAMILY, StandardCharsets.UTF_8);
        TranslationTest.Script sc = familyScript();
        Outcome o = Translation.run(store, in, "en", "", "checked", tmp.resolve("family.en.md"), null, seats(sc), null, () -> false);
        DocNames.Compared y = o.decided().of("山田太郎");
        assertEquals(DocNames.BY_PART, y.by()); assertEquals("Yamada", y.rendering(), "the family name, not the given name every unit also writes: " + y);
        assertEquals(4, y.writes()); assertEquals(5, y.held()); assertEquals(List.of(3), y.strays()); assertEquals(3, y.leadingWrites(), "the whole name named stands in three");
        assertTrue(o.flags().stream().anyMatch(f -> f.unit() == 3 && f.kind().equals("name") && f.severity().equals(Translation.MAJOR) && f.why().equals("the rest of this document writes 山田太郎 with \"Yamada\" (4 of 5 units)") && f.revised() && !f.stands()), o.flags().toString());
        String out = Files.readString(o.output());
        assertFalse(out.contains("Yamagata"), out);
        assertTrue(out.contains("Yamada Taro (山田太郎) was the town's doctor"), "the original after the whole name the part stands in: " + out);
        String note = Files.readString(o.note());
        assertTrue(note.contains("Settled by the part of the name most of their units share, the whole words named standing in too few of them: 1.\n\n| source | part | words named | kind | units writing the part | units writing it otherwise |\n| --- | --- | --- | --- | --- | --- |\n| 山田太郎 | Yamada | Taro Yamada | person | 4 of 5 | 4 |"), note);
        assertTrue(note.contains("Decided: 0; ") && note.contains("A unit that writes a decided name or term otherwise is flagged, a name as major and a term as minor, and revised toward the decided form. Units that still differ after the revisions: 0."), note);
        assertTrue(note.contains("- Names decided after the first pass: 1; of them, set with their original in parentheses by the program where each first appears: 1."), note);
        assertTrue(o.summary().contains("Settled by the part most of their units share: 1; decided by the reader: 0; left by the reader to a person: 0."), o.summary());
        // the quick tier settles it the same way and revises nothing
        Outcome quick = Translation.run(new LibraryStore(tmp.resolve("q")), in, "en", "", "quick", tmp.resolve("quick.en.md"), null, seats(familyScript()), null, () -> false);
        assertEquals("Yamada", quick.decided().of("山田太郎").rendering());
        assertTrue(Files.readString(quick.output()).contains("Yamagata Taro") && Files.readString(quick.note()).contains("The quick tier revises nothing: units that write a decided name or term otherwise than the rest: 1."));
    }

    /** A word that stands in many units unrelated to the string, a title, is never decided as a person's name, however many of its own units write it. */
    @Test void aGenericWordIsNeverDecidedAsAPersonsName() {
        Map<Integer, String> sources = new LinkedHashMap<>(), translations = new LinkedHashMap<>();
        String[][] units = {
                {"森田大臣は会議で説明した。", "Minister Morita explained at the meeting."},
                {"森田大臣は記者に答えた。", "Minister Nakamoto answered the reporters."},
                {"森田大臣は辞任しなかった。", "Minister Nakayama did not resign."},
                {"翌日、森田大臣は国会に出た。", "The next day Minister Morita went to the Diet."},
                {"財務大臣は予算を示した。", "The Finance Minister presented the budget."},
                {"文部大臣は学校を回った。", "The Education Minister toured the schools."},
                {"外務大臣は海外に出た。", "The Foreign Minister went abroad."},
                {"農林大臣は村を訪ねた。", "The Agriculture Minister visited the village."}};
        for (int i = 0; i < units.length; i++) { sources.put(i, units[i][0]); translations.put(i, units[i][1]); }
        List<String> candidates = List.of("森田大臣");
        Map<String, List<Integer>> holders = Map.of("森田大臣", DocNames.holders("森田大臣", candidates, sources));
        List<DocNames.Compared> c = DocNames.verify(List.of("森田大臣"), Map.of("森田大臣", new DocNames.Answer("person", "Minister Morita")), holders, sources, translations);
        assertFalse(c.get(0).decided());
        DocNames.Part minister = DocNames.parts("森田大臣", "Minister Morita", holders.get("森田大臣"), candidates, sources, translations).stream().filter(p -> p.words().equals("Minister")).findFirst().orElseThrow();
        assertEquals(4, minister.writes(), "every unit of the person writes it at its place");
        assertEquals(4, minister.outside(), "and so do the four units of other ministers");
        List<DocNames.Compared> settled = DocNames.settleByParts(c, candidates, holders, sources, translations);
        assertFalse(settled.get(0).decided(), "neither the title (not specific) nor the name (2 of 4) is decided: " + settled.get(0));
        // an organisation's part is left to the reader whatever it shares
        List<DocNames.Compared> org = DocNames.verify(List.of("森田大臣"), Map.of("森田大臣", new DocNames.Answer("organisation", "Ministry of Morita")), holders, sources, translations);
        assertFalse(DocNames.settleByParts(org, candidates, holders, sources, translations).get(0).decided());
    }

    // ---- B: the reader's final call ----

    /** The ministry written in full in one unit and as "the Health Ministry" in two, until a revision is told otherwise. */
    static TranslationTest.Script ministryScript(BiFunction<String, List<String>, List<String>> reader) {
        TranslationTest.Script sc = script();
        UnaryOperator<String> twoWays = t -> { String e = english(t, false); return !t.contains(M) && (t.contains("翌月") || t.contains("毎月")) ? e.replace(M, "the Health Ministry") : e; };
        sc.translate = twoWays; sc.revise = twoWays;
        sc.reader = reader;
        return sc;
    }

    /** The reader's words for each passage as the translation writes them, and its choice. */
    static BiFunction<String, List<String>, List<String>> reading(String use) {
        return (s, trs) -> {
            if (!s.equals("厚生労働省")) return null;
            List<String> out = new ArrayList<>();
            for (String tr : trs) out.add(tr.contains(M) ? M : tr.contains("Health Ministry") ? "Health Ministry" : "none");
            out.add(use);
            return out;
        };
    }

    @Test void theReaderChoosesTheMinoritysFormAndTheOtherUnitsAreRevisedTowardIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = doc(tmp.resolve("docs"));
        TranslationTest.Script sc = ministryScript(reading(M));
        Outcome o = Translation.run(store, in, "en", "", "reviewed", tmp.resolve("reviewed.en.md"), null, seats(sc), null, () -> false);
        assertEquals(1, sc.steps.stream().filter(x -> x.equals("reader")).count(), sc.steps.toString());
        DocNames.Compared m = o.decided().of("厚生労働省");
        assertEquals(DocNames.BY_READER, m.by()); assertEquals(M, m.rendering()); assertEquals(1, m.writes(), "the minority's form"); assertEquals(List.of(4, 6), m.strays());
        assertEquals(Map.of(M, 1, "Health Ministry", 2), m.forms());
        assertTrue(o.flags().stream().filter(f -> f.kind().equals("name") && f.why().equals("the reader chose \"" + M + "\" for 厚生労働省; 1 of 3 units write it") && f.revised()).count() == 2, o.flags().toString());
        String out = Files.readString(o.output());
        assertFalse(out.contains("Health Ministry made") || out.contains("that the Health Ministry"), out);
        String note = Files.readString(o.note());
        assertTrue(note.contains("Decided by the reader, stand-in, choosing among the forms the document writes; the chosen form is applied even where fewer units write it: 1.\n\n| source | chosen | kind | forms written, with the units writing each | units writing it otherwise |\n| --- | --- | --- | --- | --- |\n| 厚生労働省 | " + M + " | organisation | " + M + " (1); Health Ministry (2) | 5, 7 |"), note);
        assertTrue(o.summary().contains("Settled by the part most of their units share: 0; decided by the reader: 1; left by the reader to a person: 0."), o.summary());
        assertTrue(Translation.plan(store, in, "en", "", "reviewed", "stand-in", "").said().contains("a unit that writes one otherwise than most is revised, and the reader settles those the units do not agree on"));
        // a second reviewed translation keeps the reader's call, checks it again, and asks nothing
        TranslationTest.Script again = ministryScript(reading("none"));
        Outcome second = Translation.run(store, in, "en", "", "reviewed", tmp.resolve("again.en.md"), null, seats(again), null, () -> false);
        assertFalse(again.steps.contains("reader") || again.steps.contains("names"), again.steps.toString());
        assertEquals(M, second.decided().of("厚生労働省").rendering());
        assertTrue(Files.readString(DocNames.keptFile(store, Checkpoint.sha(new String(Files.readAllBytes(in), StandardCharsets.ISO_8859_1)), "en")).contains("\"by\" : \"stand-in\""), "who decided is kept");
    }

    @Test void aReaderThatNamesAFormNoPassageWritesIsRefused(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Outcome o = Translation.run(store, doc(tmp.resolve("docs")), "en", "", "reviewed", tmp.resolve("r.en.md"), null, seats(ministryScript(reading("Ministry of Welfare"))), null, () -> false);
        DocNames.Compared m = o.decided().of("厚生労働省");
        assertFalse(m.decided()); assertTrue(m.doubted()); assertEquals("Ministry of Welfare", m.said());
        assertTrue(o.flags().stream().noneMatch(f -> f.kind().equals("name") && f.words().startsWith("厚生労働省")), "nothing is applied: " + o.flags());
        assertTrue(Files.readString(o.output()).contains("the Health Ministry"));
        assertTrue(Files.readString(o.note()).contains("Left by the reader to a person, since it found none of the forms the document writes right, chose words no passage writes, or chose a form that disagrees with a shorter name decided inside it: 1.\n\n| source | forms written, with the units writing each | the reader's answer |\n| --- | --- | --- |\n| 厚生労働省 | " + M + " (1); Health Ministry (2) | Ministry of Welfare |"), Files.readString(o.note()));
    }

    @Test void aReaderThatFindsNoFormRightLeavesTheDoubtInTheNote(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Outcome o = Translation.run(store, doc(tmp.resolve("docs")), "en", "", "thorough", tmp.resolve("t.en.md"), null, seats(ministryScript(reading("none"))), null, () -> false);
        DocNames.Compared m = o.decided().of("厚生労働省");
        assertTrue(m.doubted()); assertEquals("none", m.said()); assertEquals(Map.of(M, 1, "Health Ministry", 2), m.forms());
        assertTrue(Files.readString(o.output()).contains("the Health Ministry"));
        assertTrue(Files.readString(o.note()).contains("| 厚生労働省 | " + M + " (1); Health Ministry (2) | none |"), Files.readString(o.note()));
        assertTrue(o.summary().contains("decided by the reader: 0; left by the reader to a person: 1."), o.summary());
    }

    /** The checked tier has no reader: the units and the parts settle what they can, and the rest stands as it is. */
    @Test void theCheckedTierAsksNoReader(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        TranslationTest.Script sc = ministryScript(reading(M));
        Outcome o = Translation.run(store, doc(tmp.resolve("docs")), "en", "", "checked", tmp.resolve("c.en.md"), null, seats(sc), null, () -> false);
        assertFalse(sc.steps.contains("reader"), sc.steps.toString());
        assertTrue(o.decided().tooFew().stream().anyMatch(x -> x.source().equals("厚生労働省")), o.decided().toString());
        assertTrue(Translation.plan(store, doc(tmp.resolve("docs2")), "en", "", "checked", "stand-in", "").said().contains("(candidates: 5), and a unit that writes one otherwise than most is revised"));
    }

    /** A reviewed job ended after the reader's call: the next process takes the call back from the job's kept work and asks nothing again. */
    @Test void aResumedJobAsksTheReaderNothingTwice(@TempDir Path tmp) throws Exception {
        Path in = doc(tmp.resolve("docs"));
        LibraryStore whole = new LibraryStore(tmp.resolve("whole")); whole.init();
        Outcome w = Translation.run(whole, in, "en", "", "reviewed", tmp.resolve("whole.en.md"), null, seats(ministryScript(reading(M))), null, () -> false);
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Checkpoint first = Checkpoint.open(store, "J-0009", Translation.JOB_KIND, "b");
        TranslationTest.Script one = ministryScript(reading(M));
        CheckpointTest.Counting d = new CheckpointTest.Counting(TranslationTest.standIn(one, "stand-in"), 4);   // the first pass, the names, the reader, then the process ends
        assertThrows(IllegalStateException.class, () -> Translation.run(store, in, "en", "", "reviewed", tmp.resolve("job.en.md"), null, Translation.kept(new Seats(d, "stand-in", d, "stand-in"), first), null, () -> false, first));
        first.close();
        assertTrue(one.steps.contains("reader"), one.steps.toString());
        Checkpoint second = Checkpoint.open(store, "J-0009", Translation.JOB_KIND, "b");
        TranslationTest.Script sc = ministryScript(reading("none"));
        Outcome r = Translation.run(store, in, "en", "", "reviewed", tmp.resolve("job.en.md"), null, Translation.kept(seats(sc), second), null, () -> false, second);
        second.close();
        assertFalse(sc.steps.contains("reader") || sc.steps.contains("names"), "the calls come back from the job's kept work: " + sc.steps);
        assertEquals(w.decided().entries(), r.decided().entries());
        assertEquals(Files.readString(w.output()), Files.readString(tmp.resolve("job.en.md")));
    }

    // ---- the record the checks are measured from ----

    /**
     * Every paragraph of a job goes on the record, flagged or not, with the scores and length of its first pass, as a row measured from;
     * the entries of earlier versions stay in the file, are listed apart and are never measured from; a job's rows are put there once
     * however often it resumed, and a withdrawal takes them off.
     */
    @Test void everyParagraphGoesOnTheRecordAndTheEntriesOfEarlierVersionsAreNotMeasuredFrom(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        // a record of earlier versions: plain lists naming no job, and a row of a job that put on only its paragraphs nothing flagged
        Path f = TranslationScores.file(store); Files.createDirectories(f.getParent());
        StringBuilder plain = new StringBuilder(); for (int i = 0; i < 20; i++) plain.append(i == 0 ? "" : ", ").append(0.07 + i * 0.001);
        Files.writeString(f, "{\"ja→en\": {\"labse\": [" + plain + "], \"units\": [{\"job\": \"J-0010\", \"unit\": 3, \"labse\": 0.9, \"length\": 3.0}]}}\n");
        assertFalse(TranslationScores.thresholds(store, "ja", "en").ready(), "never measured from");
        assertEquals(0, TranslationScores.thresholds(store, "ja", "en").labseAccepted());
        assertEquals(21, TranslationScores.earlierCount(store, "ja", "en"));
        assertEquals(TranslationLengths.Band.PRIOR, TranslationLengths.band(store, "ja", "en", null, null).basis(), "the prior band stands for length");
        Path in = doc(tmp.resolve("docs"));
        Outcome o = Translation.run(store, in, "en", "", "checked", null, null, seats(script()), null, () -> false, Checkpoint.NONE, "", "J-0042");
        assertEquals("", o.withheld());
        assertEquals(5, o.accepted(), "the five paragraphs, the one flagged for a name among them; the title and the author's line are under forty characters");
        assertTrue(o.flags().stream().anyMatch(fl -> fl.unit() == 5 && fl.kind().equals("name")), o.flags().toString());
        assertEquals(Map.of("ja→en", 5), TranslationScores.onRecord(store, "J-0042"));
        assertEquals(5, TranslationScores.lengths(store, "ja", "en").size(), "only this job's rows are measured from");
        // the flagged unit is on the record with its first pass, the stray name in it, not with its revision
        String source = DocUnits.plainText(DOC.split("\n\n")[5]);
        double first = english(source, true).length() / (double) source.length();
        JsonNode row = null; for (JsonNode r : new ObjectMapper().readTree(Files.readString(f)).path("ja→en").path("units")) if (r.path("job").asText().equals("J-0042") && r.path("unit").asInt() == 6) row = r;
        assertTrue(row != null && row.path("whole").asBoolean() && Math.abs(row.path("length").asDouble() - TranslationScores.round4(first)) < 1e-9, "first pass " + first + ": " + row);
        String note = Files.readString(o.note());
        assertTrue(note.contains("- The record: Every paragraph of 40 characters or more went on the record, flagged or not, with the scores and the length of its first pass: 5; left off, with no translation from the first pass to measure: 0. "
                + "The next thresholds and length band are measured from them; researchzosho translate scores withdraw J-0042 takes them off. The record of Japanese into English also holds entries of earlier versions: 21. "
                + "Earlier versions put on the record only the units nothing flagged or a person chose as right, a sample cut off at the threshold it set, which made the threshold tighter at every run; those entries stay in the file and nothing is measured from them.\n"), note);
        // the list: what is measured from, and apart from it the entries of earlier versions and why
        assertEquals("The record the checks' thresholds and the length band are measured from, by language pair:\n  Japanese into English, units on the record: 5 (J-0042: 5)\n"
                + "  Japanese into English, entries of earlier versions, not measured from: 21 (no job named: 20, J-0010: 1)\n"
                + "Earlier versions put on the record only the units nothing flagged or a person chose as right, a sample cut off at the threshold it set, which made the threshold tighter at every run; those entries stay in the file and nothing is measured from them.\n",
                cli(() -> TranslateCli.scores(store, new String[]{"researchzosho", "translate", "scores"})));
        // the same job's rows put there again (a resumed job) replace its own, never doubled
        TranslationScores.accept(store, "ja", "en", List.of(new TranslationScores.Row("J-0042", "", 1, 0.9, null, 3.0), new TranslationScores.Row("J-0042", "", 2, 0.91, null, 3.1), new TranslationScores.Row("J-0042", "", 3, 0.92, null, 3.2)));
        assertEquals(Map.of("ja→en", 3), TranslationScores.onRecord(store, "J-0042"));
        // taken off by the command line: the sentence says how many, and the thresholds now
        String said = cli(() -> TranslateCli.scores(store, new String[]{"researchzosho", "translate", "scores", "withdraw", "J-0042"}));
        assertEquals("3 units of J-0042 left the record of Japanese into English. The record of Japanese into English now: no distribution yet for ja→en: nothing is flagged by score until 20 paragraphs are on the record (0 so far); the scores are shown; the length band 2.1 to 3.8 times, from parallel texts until 20 units are on the record. "
                + "20 units on that record name no job (they were put there by an earlier version) and stay; researchzosho translate scores withdraw --unnamed ja en takes such units off.\n", said);
        assertTrue(TranslationScores.onRecord(store, "J-0042").isEmpty());
        TranslationScores.accept(store, "ja", "en", List.of(new TranslationScores.Row("J-0043", "", 1, 0.9, null, 3.0)));
        assertEquals("J-0043 の 1 単位を、日本語から英語への記録から外しました。日本語から英語への記録の今：ja→en の分布はまだありません。記録に段落が 20 たまるまで、点数では何も指摘しません（今 0）。点数は示します。長さの幅は 2.1 倍から 3.8 倍（記録に 20 単位たまるまでは対訳の文書から）。その記録にはジョブの名のない単位が 20 あり（以前の版が載せたもの）、残しています。researchzosho translate scores withdraw --unnamed ja en でそうした単位を外せます。",
                TranslationScores.withdrawnSaid(store, TranslationScores.withdraw(store, "J-0043"), Chrome.in("ja")));
        // the job's own command, and the entries that name no job
        assertEquals("No unit of J-0042 is on the record the checks' thresholds are measured from.\n", cli(() -> LibrarianCli.jobs(store, new String[]{"researchzosho", "jobs", "J-0042", "--withdraw-scores"})));
        assertEquals("20 units that named no job left the record of Japanese into English. The record of Japanese into English now: no distribution yet for ja→en: nothing is flagged by score until 20 paragraphs are on the record (0 so far); the scores are shown; the length band 2.1 to 3.8 times, from parallel texts until 20 units are on the record.\n",
                cli(() -> TranslateCli.scores(store, new String[]{"researchzosho", "translate", "scores", "withdraw", "--unnamed", "ja", "en"})));
        assertTrue(cli(() -> TranslateCli.scores(store, new String[]{"researchzosho", "translate", "scores"})).contains("  Japanese into English, entries of earlier versions, not measured from: 1 (J-0010: 1)\n"), "the earlier job's row stays until its job is withdrawn");
        TranslationScores.withdraw(store, "J-0010");
        assertEquals("The record the checks' thresholds and the length band are measured from is empty: no translation has put units on it yet.\n", cli(() -> TranslateCli.scores(store, new String[]{"researchzosho", "translate", "scores"})));
        // units are no longer put on by hand
        PrintStream err = System.err; ByteArrayOutputStream e = new ByteArrayOutputStream(); System.setErr(new PrintStream(e, true, StandardCharsets.UTF_8));
        int rc; try { rc = TranslateCli.scores(store, new String[]{"researchzosho", "translate", "scores", "accept", o.note().toString()}); } finally { System.setErr(err); }
        assertEquals(2, rc); assertTrue(e.toString(StandardCharsets.UTF_8).startsWith("Units are no longer put on the record by hand: every paragraph a checked translation scores goes on it, flagged or not"), e.toString(StandardCharsets.UTF_8));
        assertFalse(Files.readString(f).contains("\"note\""), "nothing was put on");
    }

    /** A job whose PDF the reader could not read whole, or whose program's own checks flagged more than half its paragraphs, puts nothing on the record. */
    @Test void aJobNotReadReliablyOrMostlyFlaggedIsKeptOffTheRecord() {
        Translation.Checked c = new Translation.Checked();
        c.paragraphs = 10; c.paragraphsFlagged = 5;
        assertEquals("", Translation.withheld(null, c, Chrome.ENGLISH), "half is not more than half");
        c.paragraphsFlagged = 6;
        assertEquals("the program's own checks of what breaks a measurement (a missing translation, numbers, addresses, citations, the glossary, the length), before any revision, flagged more than half of its paragraphs major or critical (6 of 10)", Translation.withheld(null, c, Chrome.ENGLISH));
        c.paragraphsFlagged = 0;
        PdfReading.Preflight pre = new PdfReading.Preflight(3, List.of(new PdfReading.Check(1, PdfReading.Way.TEXT, "", null, "ttb", 0), new PdfReading.Check(2, PdfReading.Way.EYE, PdfReading.ORDER, null, "ttb", 0), new PdfReading.Check(3, PdfReading.Way.EYE, PdfReading.GARBLED, null, "ltr", 0)), List.of(), "ja");
        PdfReading.Read unread = new PdfReading.Read(pre, List.of(), List.of(), null, Map.of(3, PdfReading.NOT_READ_NO_EYES), 0, 0, "");
        assertEquals("the reader could not read one of the PDF's pages", Translation.withheld(unread, c, Chrome.ENGLISH));
        PdfReading.Read orderOnly = new PdfReading.Read(pre, List.of(), List.of(), null, Map.of(), 0, 0, "");
        assertEquals("the order of the words on one page of the PDF was in doubt, and no model that sees read it", Translation.withheld(orderOnly, c, Chrome.ENGLISH));
        PdfReading.Read byEye = new PdfReading.Read(pre, List.of(), List.of(2, 3), "seer", Map.of(), 0, 0, "");
        assertEquals("", Translation.withheld(byEye, c, Chrome.ENGLISH), "the pages in doubt were read by eye: the reading is the program's best");
    }

    interface Run { int run() throws Exception; }

    /** What a command printed. */
    static String cli(Run r) throws Exception {
        PrintStream was = System.out;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        System.setOut(new PrintStream(b, true, StandardCharsets.UTF_8));
        try { r.run(); } finally { System.setOut(was); }
        return b.toString(StandardCharsets.UTF_8);
    }
}
