package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.librarian.DocUnits.Doc;
import org.researchzosho.librarian.Translation.Flag;
import org.researchzosho.librarian.Translation.Outcome;
import org.researchzosho.librarian.Translation.Plan;
import org.researchzosho.librarian.Translation.Seats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.util.Set;

/** A document translated unit by unit at either tier, with the glossary, the checks, the note and the shelves; the model is a scripted stand-in. */
class TranslationTest {
    static final ObjectMapper M = new ObjectMapper();

    /** The calls a stand-in saw, by the step the system prompt names. */
    static final class Script {
        final List<String> steps = new ArrayList<>();
        /** The translation of one unit's text; null for no answer. */
        UnaryOperator<String> translate = t -> "訳 " + t;
        /** The checker's line for a source: null for OK, else "category/kind severity | words | why", as the typology asks. */
        UnaryOperator<String> verdict = s -> null;
        /** The revision of a source; null for none. */
        UnaryOperator<String> revise = s -> "訂正 " + s;
        /** Whether the stand-in fixes a rendering for the recurring terms it is asked about; off, it gives each term back as it is. */
        boolean fixTerms = false;
    }

    static Researcher.Drive standIn(Script sc, String model) {
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String c) { return null; }
            @Override public String model() { return model; }
            @Override public int contextWindow() { return 16384; }
            @Override public String classify(ArrayNode m, int x) {
                String system = m.get(0).path("content").asText(), user = m.get(m.size() - 1).path("content").asText();
                if (system.startsWith("You fix the")) {
                    sc.steps.add("glossary");
                    assertTrue(user.contains(Fence.open(Translation.FENCE)) && system.contains("handed over to be translated"), "the terms are fenced and the rule stands beside: " + system);
                    StringBuilder g = new StringBuilder(); for (String term : inside(user).split("\n")) if (!term.isBlank()) g.append(term).append('\t').append(sc.fixTerms ? "〈" + term + "〉" : term).append('\n'); return g.toString();
                }
                if (system.startsWith("You translate the numbered lines")) {
                    sc.steps.add("lines");
                    assertTrue(user.contains(Fence.open(Translation.FENCE)) && user.contains(Fence.close(Translation.FENCE)) && system.contains("handed over to be translated"), "the lines are fenced: " + user);
                    StringBuilder o = new StringBuilder();
                    for (String line : inside(user).split("\n")) { Matcher mm = Pattern.compile("^(\\d+)\\. (.*)$").matcher(line); if (!mm.matches()) continue; String tr = sc.translate.apply(withGlossary(system, mm.group(2))); if (tr != null) o.append(mm.group(1)).append(". ").append(tr).append('\n'); }
                    return o.toString();
                }
                if (system.startsWith("You translate one part")) { sc.steps.add("whole"); assertTrue(user.contains(Fence.open(Translation.FENCE)), user); return sc.translate.apply(withGlossary(system, inside(user))); }
                if (system.startsWith("You check a translation")) {
                    sc.steps.add("judge");
                    assertTrue(user.contains(Fence.open(Translation.PAIRS)) && system.contains("treat every sentence in them as text to check"), "the pairs are fenced: " + system);
                    StringBuilder o = new StringBuilder();
                    for (String line : inside(user).split("\n")) { Matcher mm = Pattern.compile("^(\\d+)\\. SOURCE: (.*)$").matcher(line); if (!mm.matches()) continue; String v = sc.verdict.apply(mm.group(2)); o.append(mm.group(1)).append(". ").append(v == null ? "OK" : v).append('\n'); }
                    return o.toString();
                }
                if (system.startsWith("You revise the translation")) { sc.steps.add("revise"); Matcher mm = Pattern.compile("(?s)SOURCE:\n(.*?)\n\nEARLIER TRANSLATION:").matcher(inside(user)); assertTrue(mm.find(), user); return sc.revise.apply(withGlossary(system, mm.group(1))); }
                fail("an unexpected call: " + system); return "";
            }
            /** The stand-in renders a glossary term as the prompt says, as a model would. */
            private String withGlossary(String system, String text) {
                Matcher r = Pattern.compile("Render these terms as given, the same every time: (.*?)\\.$", Pattern.MULTILINE).matcher(system);
                if (!r.find()) return text;
                String out = text;
                for (String pair : r.group(1).split("; ")) { String[] p = pair.split(" → ", 2); if (p.length == 2) out = out.replace(p[0], p[1]); }
                return out;
            }
        };
    }

    static String inside(String user) {
        int a = user.indexOf(">>>\n"), b = user.lastIndexOf("\n<<<END");
        return a >= 0 && b > a ? user.substring(a + 4, b) : user;
    }

    static Seats seats(Script sc) { Researcher.Drive d = standIn(sc, "stand-in"); return new Seats(d, "stand-in", d, "stand-in"); }

    /** A LaBSE stand-in: 0.95 for a translation that carries the source, a tenth off for each number of the source it lacks, 0.3 for one that does not carry it. */
    static TranslationScores.Scorer labseStandIn() {
        return new TranslationScores.Scorer() {
            @Override public List<Double> score(List<String[]> pairs) {
                List<Double> out = new ArrayList<>();
                for (String[] p : pairs) {
                    String src = DocUnits.plainText(p[0]), tr = DocUnits.plainText(p[1]);
                    String firstWord = src.replaceAll("[^\\p{L} ]", " ").strip().split("\\s+")[0];
                    if (!firstWord.isEmpty() && !tr.contains(firstWord)) { out.add(0.3); continue; }
                    double sc = 0.95; for (String n : Translation.numbers(src)) if (!Translation.numbers(tr).contains(n)) sc -= 0.1;
                    out.add(Math.round(sc * 100) / 100.0);
                }
                return out;
            }
            @Override public String name() { return "LaBSE"; }
            @Override public String model() { return "a stand-in"; }
            @Override public boolean higherIsBetter() { return true; }
        };
    }

    @BeforeEach void standInTheScorers() { TranslationScores.SCORERS = () -> new TranslationScores.Scorers(labseStandIn(), null); Translation.SETUP_SAID = () -> ""; }
    @AfterEach void restoreTheScorers() { TranslationScores.SCORERS = TranslationScores::configured; Translation.SETUP_SAID = () -> { String a = TranslationScores.labseSetupSaid(), b = TranslationScores.metricxSetupSaid(); return a.isEmpty() ? b : b.isEmpty() ? a : a + "; " + b; }; }

    /** Twenty accepted units on the record for English→Japanese, LaBSE 0.90 to 0.99: the lowest tenth is about 0.909. */
    static void seedRecord(LibraryStore store) {
        List<Double> l = new ArrayList<>(); for (int i = 0; i < 20; i++) l.add(0.90 + i * 0.0047);
        TranslationScores.accept(store, "en", "ja", l, List.of());
    }
    /** Two seats: the translator, and a second model checking. */
    static Seats twoSeats(Script sc) { Researcher.Drive d = standIn(sc, "stand-in"); return new Seats(d, "stand-in", "stand-in", null, standIn(sc, "checker"), "checker-model", "checker", null, false, false, "", new long[3], new long[3]); }

    static Path fixture(Path dir) throws IOException {
        Path f = dir.resolve("saros notes.md");
        Files.writeString(f, "# The Saros dial\n\nThe Saros dial predicts eclipses over 223 lunar months, about 18 years. The drawing is at https://example.org/saros and the count comes from [1].\n\n## What it shows\n\n- the month of the eclipse\n- the hour, as **bold** letters\n\n| Glyph | Meaning |\n| --- | --- |\n| Σ | a lunar eclipse |\n\n```\ncode stays\n```\n\n[1] https://example.org/count\n", StandardCharsets.UTF_8);
        return f;
    }

    /** auto for the checker: the same model on another card or host is the same reader, not a second one; a local entry whose server does not list its model is passed over; local before hosted. */
    @Test void autoPicksAnotherModelThatAnswersNeverTheSameModelOnAnotherCard(@TempDir Path tmp) throws Exception {
        String home = System.getProperty("user.home"); System.setProperty("user.home", tmp.toString()); Config.invalidate();
        var serves = Translation.SERVES;
        try {
            Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b");
            ModelEntries.put("27b-card1", "http://homebox:8213", "qwen3.8-27b", null, null, null, null);
            ModelEntries.put("gemma26", "http://homebox:8213", "gemma-4-26b-a4b", null, null, null, null);
            Translation.SERVES = (drive, model) -> model.equals("gemma-4-26b-a4b");
            Translation.Pick p = Translation.pickJudge("http://homebox:8212", "qwen3.8-27b");
            assertEquals("gemma26", p.entry().name(), "the same 27B on another card is not another model; the local Gemma is: " + p.why());
            Translation.SERVES = (drive, model) -> false;
            p = Translation.pickJudge("http://homebox:8212", "qwen3.8-27b");
            assertNull(p.entry(), "no local entry answers and there is no hosted one: the same model reads in fresh calls");
            assertTrue(p.why().contains("gemma26") && p.why().contains("does not list the model"), "the note says what was passed over and why: " + p.why());
            ModelEntries.put("deepseek", "openrouter", "deepseek/deepseek-v4.1-flash", null, null, null, null);
            p = Translation.pickJudge("http://homebox:8212", "qwen3.8-27b");
            assertNull(p.entry(), "a hosted entry without its host's key cannot be called: " + p.why());
            assertTrue(p.why().contains("deepseek passed over: no key kept for the host"), p.why());
            Config.set(DriveClient.hostKeySetting("openrouter.ai"), "sk-or-test");
            p = Translation.pickJudge("http://homebox:8212", "qwen3.8-27b");
            assertEquals("deepseek", p.entry().name(), "when no local one answers, the hosted entry with a key: " + p.why());
            assertTrue(p.why().contains("gemma26 was passed over"), p.why());
        } finally { Translation.SERVES = serves; System.setProperty("user.home", home); Config.invalidate(); }
    }

    /** The program's glossary check takes an inflected rendering, grades the person's terms major and the model's own minor; a unit with no letters is not translated. */
    @Test void theGlossaryCheckTakesInflectedRenderingsAndGradesThePersonsTermsAbove() {
        assertTrue(Translation.usesRendering("History of Pandesowna, chief of the Gypsies", "Gypsy")); assertTrue(Translation.usesRendering("the Eternal Jew", "Eternal")); assertTrue(Translation.usesRendering("At last he came", "at last"));
        assertFalse(Translation.usesRendering("the chief of the Roma", "Gypsy")); assertFalse(Translation.usesRendering("", "Gypsy"));
        Map<String, String> glossary = new LinkedHashMap<>(Map.of("Cygan", "Gypsy", "Dalszy", "Further"));
        List<Translation.Flag> fs = Translation.mechanical(3, "Dalszy ciąg historii naczelnika Cyganów", "Continuation of the story of the chief of the Roma", glossary, Set.of("Cygan"), "en", "");
        assertEquals(2, fs.size(), fs.toString());
        assertTrue(fs.stream().anyMatch(f -> f.severity().equals(Translation.MAJOR) && f.words().equals("Cygan → Gypsy")), "the person's term rendered otherwise is major: " + fs);
        assertTrue(fs.stream().anyMatch(f -> f.severity().equals(Translation.MINOR) && f.words().equals("Dalszy → Further") && f.why().startsWith("the rendering fixed for this document is not used")), "the model's own is minor: " + fs);
        assertTrue(Translation.mechanical(3, "naczelnik Cyganów", "the chief of the Gypsies", glossary, Set.of("Cygan"), "en", "").isEmpty(), "Gypsies is Gypsy inflected");
        assertFalse(DocUnits.Unit.of(DocUnits.PARA, "---").translated() || DocUnits.Unit.of(DocUnits.PARA, "![Okładka](OPS/images/cover.jpg)").translated(), "no letters outside markup, an image's alt text included: nothing to translate");
        assertTrue(DocUnits.Unit.of(DocUnits.PARA, "Okładka").translated());
    }

    @Test void quickTierTranslatesEveryUnitInOrderWithTheGlossaryAndShelvesBothFiles(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = fixture(Files.createDirectories(tmp.resolve("docs")));
        assertTrue(Glossary.add(store, "Saros", "Japanese", "サロス").contains("renders \"Saros\" as \"サロス\" in Japanese"));
        assertEquals(Map.of("Saros", "サロス"), Glossary.forLanguage(store, "ja"));
        Plan plan = Translation.plan(store, in, "Japanese", "", "", "stand-in", "");
        assertEquals("md", plan.format()); assertEquals("en", plan.from()); assertEquals("ja", plan.to()); assertEquals("md", plan.as()); assertEquals("checked", plan.quality(), "checked is the tier when none is named");
        assertEquals(10, plan.units(), "the two headings, the paragraph, two items, four cells and the source line carry words; the code block does not: " + plan.units());
        assertTrue(plan.chars() > 150 && !plan.measured() && plan.said().contains(" checked, the default; quick ") && plan.said().contains(", reviewed ") && plan.said().contains(", thorough ") && plan.said().contains("first guess"), plan.said());
        assertTrue(plan.checkedSeconds() > plan.quickSeconds() && plan.thoroughSeconds() >= plan.reviewedSeconds() && plan.reviewedSeconds() > plan.checkedSeconds(), plan.toString());
        assertNull(Translation.tier("fast")); assertEquals("checked", Translation.tier("")); assertEquals("thorough", Translation.tier("Thorough"));
        assertEquals("under a minute", Translation.duration(30)); assertEquals("about 2 minutes", Translation.duration(110)); assertEquals("about 1 hour 20 minutes", Translation.duration(4800)); assertEquals("about 2 hours", Translation.duration(7200));
        Script sc = new Script();
        List<ObjectNode> progress = new ArrayList<>();
        Outcome o = Translation.run(store, in, "ja", "", "quick", null, null, seats(sc), progress::add, () -> false);
        assertEquals(in.resolveSibling("saros notes.ja.md"), o.output()); assertEquals(in.resolveSibling("saros notes.ja.translation.md"), o.note());
        assertTrue(Files.exists(o.output()) && Files.exists(o.note()));
        String out = Files.readString(o.output());
        assertTrue(out.startsWith("# 訳 The サロス dial\n"), "the heading translated, the glossary's rendering used: " + out);
        assertTrue(out.contains("訳 The サロス dial predicts eclipses over 223 lunar months, about 18 years. The drawing is at https://example.org/saros and the count comes from [1].") && out.contains("- 訳 the hour, as **bold** letters") && out.contains("| 訳 Glyph | 訳 Meaning |\n| --- | --- |\n| 訳 Σ | 訳 a lunar eclipse |") && out.contains("```\ncode stays\n```"), out);
        assertEquals("訳 The サロス dial", o.translatedTitle());
        assertEquals(List.of("lines"), sc.steps, "the one recurring phrase holds the person's term, so no rendering is asked of the model; one numbered batch for the short units");
        assertEquals(Map.of("Saros", "サロス"), o.glossaryApplied());
        assertTrue(o.keptOriginal() == 0 && o.flags().isEmpty() && o.marked() == 0, o.summary());
        assertTrue(o.summary().startsWith("Translated \"The Saros dial\" into Japanese (quick): 11 units"), o.summary());
        assertTrue(progress.stream().anyMatch(p -> p.path("phase").asText().equals("translating") && p.path("units_total").asInt() == 10) && progress.get(progress.size() - 1).path("phase").asText().equals("writing"), progress.toString());
        String note = Files.readString(o.note());
        assertTrue(note.startsWith("# Translation note: \"The Saros dial\" into Japanese\n") && note.contains("- Tier: quick (one pass)") && note.contains("- Models: stand-in translating\n") && note.contains("From the library's glossary, applied: Saros → サロス") && note.contains("Markdown to Markdown keeps every block") && note.contains("The layout is not"), note);
        // both files on the shelves: the original under no collection, the translation beside it with a link back and its language
        assertNotNull(o.originalShelved()); assertNotNull(o.shelved());
        String shelved = Files.readString(o.shelved());
        assertTrue(shelved.contains("translation_of: " + o.originalShelved().getFileName()) && shelved.contains("language: ja") && shelved.contains("translated_from: en") && shelved.contains("title: 訳 The サロス dial") && shelved.contains("url: file://" + o.output().toAbsolutePath().normalize()), shelved);
        assertEquals("", RawCapture.collectionOf(o.shelved()));
        assertEquals(o.originalShelved(), RawCapture.find(store, "file://" + in.toAbsolutePath().normalize()));
        // the speed is on the ledger: the next plan is measured
        assertTrue(Translation.charsPerSecond(store, "stand-in", "quick") > 0);
        Plan again = Translation.plan(store, in, "ja", "pdf", "checked", "stand-in", "");
        assertTrue(again.measured() && again.as().equals("pdf") && !again.said().contains("first guess"), again.said());
        assertTrue(assertThrows(IOException.class, () -> Translation.plan(store, in, "ja", "", "fast", "x", "")).getMessage().contains("quick, checked, reviewed or thorough"));
        // a second run does not overwrite the first: the name takes a number
        Outcome o2 = Translation.run(store, in, "ja", "", "quick", null, "notes", seats(new Script()), null, () -> false);
        assertEquals(in.resolveSibling("saros notes.ja-2.md"), o2.output());
        assertEquals("notes", RawCapture.collectionOf(o2.shelved()));
        // a document already in the language asked for
        Path ja = tmp.resolve("docs").resolve("日本語.md"); Files.writeString(ja, "# 眼鏡の歴史\n\n眼鏡は十三世紀に生まれた。\n");
        assertTrue(assertThrows(IOException.class, () -> Translation.plan(store, ja, "ja", "", "", "x", "")).getMessage().contains("is written in Japanese already"));
        assertTrue(assertThrows(IOException.class, () -> Translation.plan(store, in, "ja", "", "checked", "x", "nobody")).getMessage().startsWith("No model entry matches \"nobody\"."));
    }

    @Test void checkedTierScoresEveryUnitFlagsByTheMeasuredThresholdAndKeepsARevisionOnlyWhenItsScoreImproves(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = fixture(Files.createDirectories(tmp.resolve("docs")));
        Script sc = new Script();
        sc.translate = t -> t.contains("223") ? "訳 " + t.replace("223 ", "").replace("18 years", "many years") : t.contains("Meaning") ? null : "訳 " + t;   // a number dropped; a unit dropped
        sc.revise = s -> "訂正 " + s;
        // no record yet for the pair: the scores are shown, nothing is flagged by them; the program's checks still catch the number and the unit
        Outcome first = Translation.run(store, in, "ja", "", "", null, null, seats(sc), null, () -> false);
        assertEquals("checked", first.quality(), "the default tier");
        assertFalse(first.thresholds().ready()); assertTrue(first.scored() && first.labse().size() == 10, "every unit with a translation is scored, the revised one too: " + first.labse());
        assertTrue(first.flags().stream().noneMatch(Flag::score), "no flag by score before the record has twenty units: " + first.flags());
        assertTrue(first.flags().stream().anyMatch(f -> f.kind().equals("number") && f.revised() && !f.stands()) && first.flags().stream().anyMatch(f -> f.kind().equals("omission") && !f.stands()), first.flags().toString());
        assertEquals(3, first.backTranslated(), "one model only: a sample of three units is translated back and compared");
        assertTrue(first.accepted() >= 6, "the units nothing flagged go on the record: " + first.accepted());
        assertTrue(first.summary().contains("no distribution yet for this pair") && first.summary().contains("3 units translated back and compared"), first.summary());
        String firstNote = Files.readString(first.note());
        assertTrue(firstNote.contains("- Tier: checked (the default:") && firstNote.contains("- Scorers: LaBSE (a stand-in); MetricX-24 is not installed, so the tier ran on LaBSE alone") && firstNote.contains("- Thresholds: no distribution yet for en→ja: nothing is flagged by score until 20 accepted units are on record") && firstNote.contains("## Scores\n\n| unit | LaBSE | MetricX-24 | flags |") && firstNote.contains("| 1 | 0.95 | — |  |") && firstNote.contains("## What changed") && firstNote.contains("| 2 | 0.75 → 0.95 | — → — | kept | program |"), firstNote);
        assertTrue(firstNote.contains("- Back-translation: 3 of the units translated back into English"), firstNote);
        // with the record seeded the dropped number's unit is flagged by its score too, and the revision is kept because the score rose
        seedRecord(store);
        Outcome o = Translation.run(store, in, "ja", "", "checked", tmp.resolve("second.md"), null, seats(sc), null, () -> false);
        assertTrue(o.thresholds().ready() && o.thresholds().labseLow() > 0.9 && o.thresholds().labseLow() < 0.92, o.thresholds().said());
        assertTrue(o.flags().stream().anyMatch(f -> f.kind().equals("labse") && f.unit() == 1 && f.why().startsWith("the closeness of meaning LaBSE measures is low: 0.75, below ") && f.revised() && !f.stands()), "the score flags the unit and shows the score: " + o.flags());
        assertTrue(o.flags().stream().noneMatch(f -> f.kind().equals("labse") && f.unit() == 10), "a short unit (the source line) is scored but never flagged by its score: " + o.flags());
        assertTrue(Files.readString(o.note()).contains("is scored and shown, never flagged by its score"), Files.readString(o.note()));
        assertTrue(Files.readString(o.output()).contains("訂正 The Saros dial predicts eclipses over 223 lunar months"), "the revision with the number back is kept");
        assertTrue(Files.readString(o.note()).contains("LaBSE below 0.9") && Files.readString(o.note()).contains("flags (the lowest tenth of"), Files.readString(o.note()));
    }

    @Test void reviewedTierReadsWithTheTypologyRevisesMajorFlagsAndMarksWhatStillStands(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = fixture(Files.createDirectories(tmp.resolve("docs")));
        Script sc = new Script();
        sc.translate = t -> t.contains("223") ? "訳 " + t.replace("223 ", "").replace("18 years", "many years") : t.contains("Meaning") ? null : "訳 " + t;   // a number dropped; a unit dropped
        sc.verdict = s -> s.contains("the hour") ? "fluency/wording major | 時間 | 「the hour」 reads oddly in Japanese" : s.contains("the month") ? "style/register minor | 月 | a touch informal for the document" : null;
        sc.revise = s -> "訂正 " + s;   // the number comes back; the hour stays as the reader dislikes it
        Outcome o = Translation.run(store, in, "ja", "", "reviewed", null, null, twoSeats(sc), null, () -> false);
        assertEquals("reviewed", o.quality()); assertTrue(o.twoModels()); assertEquals("checker", o.checkerName());
        assertEquals(9, o.readUnits(), "a short document is read whole"); assertTrue(o.readSaid().startsWith("every unit, since the document is short"), o.readSaid());
        assertEquals(0, o.backTranslated(), "a second model reads: no back-translation unless asked");
        List<String> kinds = o.flags().stream().map(f -> (f.unit() + 1) + ":" + f.category() + "/" + f.kind() + ":" + f.severity() + ":" + f.by() + ":" + (f.stands() ? "stands" : f.revised() ? "cleared" : "left")).toList();
        assertTrue(kinds.contains("2:accuracy/number:major:program:cleared"), "the dropped numbers 223 and 18 are caught by the program and come back on revision: " + kinds);
        assertTrue(kinds.contains("7:accuracy/omission:critical:program:cleared"), "the unit with no translation is caught and translated on revision: " + kinds);
        assertTrue(kinds.contains("5:fluency/wording:major:checker:stands"), "the second reading's major flag on the hour stands after the revision: " + kinds);
        assertTrue(kinds.contains("4:style/register:minor:checker:left"), "a minor flag is listed and the unit left as it is: " + kinds);
        assertEquals(1, o.marked()); assertEquals(1, o.minor().size());
        String out = Files.readString(o.output());
        assertTrue(out.contains("- 訂正 the hour, as **bold** letters ［訳注：要確認 — wording 「時間」: 「the hour」 reads oddly in Japanese］"), "the unit still flagged is marked in Japanese: " + out);
        assertTrue(out.contains("- 訳 the month of the eclipse\n"), "the unit with only a minor flag is left as it was: " + out);
        assertTrue(out.contains("訂正 The Saros dial predicts eclipses over 223 lunar months") && out.contains("| 訳 Glyph | 訂正 Meaning |"), out);
        String note = Files.readString(o.note());
        assertTrue(note.contains("- Tier: reviewed (one pass, the program's checks, the scores, a revision of what they flagged, a second reading with the typology of every unit, since the document is short (under 150 units), a revision of what it flagged major or critical)") && note.contains("- Models: stand-in translating; checker (checker-model) reading, a second model\n"), note);
        assertTrue(note.contains("| 2 | accuracy | number | major | program | 223, 18 | the numbers 223, 18 are not in the translation | cleared |") && note.contains("| 5 | fluency | wording | major | checker | 時間 | 「the hour」 reads oddly in Japanese | stands, marked |") && note.contains("| 7 | accuracy | omission | critical | program |  | no translation came back for this unit | cleared |") && note.contains("| 4 | style | register | minor | checker | 月 | a touch informal for the document | minor, left as it is |"), note);
        assertTrue(note.contains("| category | minor | major | critical | standing after revision |") && note.contains("| accuracy | 0 | 1 | 1 | 0 |") && note.contains("| fluency | 0 | 1 | 0 | 1 |") && note.contains("| style | 1 | 0 | 0 | 0 |"), "the counts by category and severity, before and after: " + note);
        assertTrue(note.contains("- Standing after everything: 1, marked in the output with a bracketed note in Japanese; major or critical in all: 3; minor, listed and left: 1."), note);
        assertTrue(sc.steps.get(0).equals("glossary") && sc.steps.contains("judge") && sc.steps.stream().filter(s -> s.equals("revise")).count() == 3, "the recurring terms fixed once first; three units revised, the minor one not: " + sc.steps);
        assertTrue(o.summary().contains("9 units read by checker") && o.summary().contains("4 flags: 3 major or critical, 1 minor"), o.summary());
        // the same model reading: the note says it is the weaker check, and a sample is translated back
        Outcome same = Translation.run(store, in, "ja", "", "thorough", tmp.resolve("same.md"), null, seats(new Script()), null, () -> false);
        assertFalse(same.twoModels()); assertEquals("every unit", same.readSaid()); assertEquals(3, same.backTranslated());
        assertTrue(Files.readString(same.note()).contains("read by the same model, the only one set, in fresh calls: a weaker check than a second model, since a model tends to approve its own work"));
        assertTrue(Translation.result(store, same, false).contains("The reader was the same model as the translator, in fresh calls: a weaker check than a second model would give."));
        // a long document: reviewed reads the flagged units and a sample of the rest, half the lowest-scored and half spread through
        Translation.Checked c = new Translation.Checked();
        List<Integer> read = new ArrayList<>(); for (int i = 0; i < 200; i++) { read.add(i); c.labse.put(i, i % 10 == 0 ? 0.5 : 0.95); }
        List<Integer> toRead = Translation.toRead("reviewed", read, Set.of(3, 4), c);
        assertTrue(toRead.size() == 42 && toRead.contains(3) && toRead.contains(4) && toRead.contains(0) && toRead.contains(190) && toRead.get(0) % 10 == 0, "the two flagged, twenty of the lowest-scored, twenty spread through, worst first: " + toRead);
        assertEquals(200, Translation.toRead("thorough", read, Set.of(), c).size());
        // the program's checks on their own: an address, a citation, a glossary term, the length band
        assertTrue(Translation.mechanical(0, "See https://example.org/a [2].", "見よ [2]。", Map.of(), "ja", "").stream().anyMatch(f -> f.kind().equals("address") && f.severity().equals("major") && f.category().equals("accuracy")));
        assertTrue(Translation.mechanical(0, "See https://example.org/a [2].", "https://example.org/a を見よ。", Map.of(), "ja", "").stream().anyMatch(f -> f.kind().equals("citation")));
        assertTrue(Translation.mechanical(0, "Acme Corp sells gears.", "会社は歯車を売る。", Map.of("Acme Corp", "アクメ社"), "ja", "").stream().anyMatch(f -> f.kind().equals("glossary") && f.category().equals("terminology")));
        assertTrue(Translation.mechanical(0, "Acme Corp sells gears.", "アクメ社は歯車を売る。", Map.of("Acme Corp", "アクメ社"), "ja", "").isEmpty());
        assertTrue(Translation.mechanical(0, "A paragraph of forty characters or more, about gears and their teeth.", "歯車。", Map.of(), "ja", "").stream().anyMatch(f -> f.kind().equals("length")));
        assertTrue(Translation.mechanical(0, "In 2024-05-01 the count was 1,234.", "２０２４年５月１日の数は1234だった。", Map.of(), "ja", "").isEmpty(), "full-width digits, a date written the Japanese way and a thousands comma are the same numbers");
        assertEquals("[translation note: check this — drift: x]", Translation.noteIn("en", "drift: x")); assertEquals("［訳注：要確認 — x］", Translation.noteIn("ja", "x"));
    }

    @Test void anEpubComesOutAsAnEpubWithItsChaptersNavigationPicturesAndTitle(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = tmp.resolve("clock.epub"); Files.write(in, DocUnitsTest.handMadeEpub());
        Script sc = new Script();
        Outcome o = Translation.run(store, in, "ja", "", "quick", null, null, seats(sc), null, () -> false);
        assertEquals("epub", o.format()); assertEquals(tmp.resolve("clock.ja.epub"), o.output());
        byte[] out = Files.readAllBytes(o.output());
        DocUnitsTest.checkEpubPackage(out);
        Doc back = DocUnits.cut(out, "clock.ja.epub");
        assertEquals(2, back.chapters().size()); assertEquals("訳 An Invented Clock", back.title()); assertEquals("ja", back.lang(), "the package says its language");
        assertEquals("An Invented Hand", back.meta().get("creator"));
        assertTrue(back.images().containsKey("OEBPS/images/dial.png"), back.images().keySet().toString());
        String opf = new String(DocUnits.unzip(out).get("OEBPS/content.opf"), StandardCharsets.UTF_8);
        assertTrue(opf.contains("<meta property=\"dcterms:alternative\">An Invented Clock</meta>") && opf.contains("<dc:source>clock.epub</dc:source>"), opf);
        String nav = new String(DocUnits.unzip(out).get("OEBPS/nav.xhtml"), StandardCharsets.UTF_8);
        assertTrue(nav.contains(">訳 The Saros dial</a>") && nav.contains(">訳 Notes</a>"), nav);
        assertTrue(Files.readString(o.note()).contains("EPUB to EPUB keeps the chapters, the navigation, the pictures"));
        // the same EPUB as a Word file and as a PDF, through the library's own writers
        Outcome docx = Translation.run(store, in, "ja", "docx", "quick", tmp.resolve("clock.ja.docx"), null, seats(new Script()), null, () -> false);
        assertEquals("docx", DocUnits.format(Files.readAllBytes(docx.output()), "x"));
        assertTrue(DocUnits.cut(Files.readAllBytes(docx.output()), "clock.ja.docx").units().stream().anyMatch(u -> u.text().equals("訳 the month")));
    }

    @Test void aJobRunsTheTranslationIntoTheExportsFolderAndItsResultCarriesTheLinks(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = fixture(Files.createDirectories(tmp.resolve("docs")));
        Script sc = new Script();
        Translation.SEATS = (drive, model, judge, back) -> seats(sc);
        Translation.DRIVE_ANSWERS = url -> true;
        try {
            ObjectNode job = M.createObjectNode(); job.put("job_id", "J-0001"); job.put("kind", Translation.JOB_KIND);
            job.set("args", Translation.args(in, "saros notes.md", "ja", "", "quick", null, null, "web"));
            String result = Translation.runJob(store, job, "http://127.0.0.1:1", "stand-in", null, () -> false);
            assertTrue(result.startsWith("Translated \"The Saros dial\" into Japanese (quick)") && result.contains("Download: [saros notes.ja.md](/exports/saros%20notes.ja.md) · [the translation note](/exports/saros%20notes.ja.translation.md)") && result.contains("On the shelves: the translation as 20"), result);
            assertTrue(Files.exists(Export.exportsDir(store).resolve("saros notes.ja.md")) && Files.exists(Export.exportsDir(store).resolve("saros notes.ja.translation.md")));
            // the chat's answer to "quick or checked?" is the tier
            ObjectNode job2 = M.createObjectNode(); job2.put("job_id", "J-0002"); job2.put("kind", Translation.JOB_KIND);
            ObjectNode a = Translation.args(in, "saros notes.md", "ja", "", "quick", tmp.resolve("out.md"), null, "cli"); a.put("choice", "checked"); job2.set("args", a);
            String r2 = Translation.runJob(store, job2, "http://127.0.0.1:1", "stand-in", null, () -> false);
            assertTrue(r2.contains("(checked)") && r2.contains("The file: " + tmp.resolve("out.md")) && r2.contains("Scored by LaBSE"), r2);
            // no model: the job fails in a sentence
            Translation.DRIVE_ANSWERS = url -> false;
            assertTrue(assertThrows(Jobs.Failure.class, () -> Translation.runJob(store, job, "http://127.0.0.1:1", "stand-in", null, () -> false)).getMessage().startsWith("No model is answering at http://127.0.0.1:1."));
        } finally { Translation.SEATS = Translation::seatsFor; Translation.DRIVE_ANSWERS = Crews::driveAnswers; }
    }

    @Test void aGlossaryTermsOriginalIsNotLeftInParenthesesAndACapitalisedTermIsTheTermToo(@TempDir Path tmp) throws Exception {
        // a name's original stands in parentheses once in the document, the first time, and never when the two are the same
        Set<String> shown = new HashSet<>();
        assertEquals("I was beneath the gallows of Los Hermanos. The brothers Zoto lay there.", Translation.unparenthesised("I was beneath the gallows of Los Hermanos (Los Hermanos). The brothers Zoto (Zoto) lay there.", Map.of(), shown));
        assertEquals("Camilla (Kamilla) came from Cordoba (Kordoba).", Translation.unparenthesised("Camilla (Kamilla) came from Cordoba (Kordoba).", Map.of(), shown), "the first time, when the spelling differs");
        assertEquals("Camilla went back to Cordoba.", Translation.unparenthesised("Camilla (Kamilla) went back to Cordoba (Kordoba).", Map.of(), shown), "not again");
        assertEquals("Condillac's views; a Corregidor (Spanish) is a judge", Translation.unparenthesised("Condillac's (Condillac) views; a Corregidor (Spanish) is a judge", Map.of(), new HashSet<>()), "a possessive of the same name is the same; a gloss that is no original stays");
        assertEquals("読書会はこれを描いた", Translation.unparenthesised("読書会（the group）はこれを描いた", Map.of("the group", "読書会")));
        assertEquals("読書会 drew it", Translation.unparenthesised("読書会 (The group) drew it", Map.of("the group", "読書会")));
        assertEquals("文字盤（a dial of brass）", Translation.unparenthesised("文字盤（a dial of brass）", Map.of("dial", "文字盤")), "other words in the parentheses stay");
        assertEquals("The group", Translation.capitalised("the group"));
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path in = tmp.resolve("group.md");
        Files.writeString(in, "# Notes\n\nThe group met on Tuesdays. A member of the group drew the dial (dial) for the group.\n", StandardCharsets.UTF_8);
        Glossary.add(store, "the group", "ja", "読書会"); Glossary.add(store, "dial", "ja", "文字盤");
        Script sc = new Script();
        sc.translate = t -> "訳 " + t.replace("The group", "読書会（The group）").replace("the group", "読書会（the group）").replace("dial", "文字盤（dial）");   // the model's habit
        Outcome o = Translation.run(store, in, "ja", "", "quick", null, null, seats(sc), null, () -> false);
        String out = Files.readString(o.output());
        assertTrue(out.contains("訳 読書会 met on Tuesdays. A member of 読書会 drew the 文字盤 (文字盤) for 読書会."), "the originals in parentheses are gone, the capitalised term rendered too: " + out);
        assertTrue(o.glossaryApplied().containsKey("the group") && o.glossaryApplied().containsKey("dial"), o.glossaryApplied().toString());
    }

    @Test void theJudgesLinesAreReadInTheShapesAModelWritesThem() {
        Script sc = new Script();
        sc.verdict = s -> s.contains("one") ? "accuracy/omission critical | 一 | the second half is missing" : s.contains("two") ? "wording, minor: \"二\" reads stiffly" : s.contains("three") ? "terminology/glossary major | 三 | the glossary says 参" : null;
        List<DocUnits.Unit> units = List.of(DocUnits.Unit.of("para", "one"), DocUnits.Unit.of("para", "two"), DocUnits.Unit.of("para", "three"), DocUnits.Unit.of("para", "four"));
        List<Translation.Flag> flags = Translation.judge(twoSeats(sc), units, Map.of(0, "一", 1, "二", 2, "三", 3, "四"), List.of(0, 1, 2, 3), "Japanese", Map.of("three", "参"), false);
        assertEquals(3, flags.size(), flags.toString());
        assertEquals(new Translation.Flag(0, "accuracy", "omission", "critical", "一", "the second half is missing", "checker", false, false), flags.get(0));
        assertEquals(new Translation.Flag(1, "fluency", "wording", "minor", "二", "\"二\" reads stiffly", "checker", false, false), flags.get(1));
        assertEquals(new Translation.Flag(2, "terminology", "glossary", "major", "三", "the glossary says 参", "checker", false, false), flags.get(2));
        assertEquals("accuracy/omission, critical: 「一」 — the second half is missing", flags.get(0).line());
    }

    @Test void theGlossaryIsKeptAcrossDocumentsAndListedForAPerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        assertTrue(Glossary.describe(store, "").startsWith("The glossary is empty."));
        Glossary.add(store, "Acme Corp", "ja", "アクメ社"); Glossary.add(store, "Acme Corp", "de", "Acme AG"); Glossary.add(store, "Acme Corp", "ja", "アクメ株式会社");
        assertEquals(2, Glossary.list(store).size(), "the same term and language is replaced, not doubled");
        assertEquals("アクメ株式会社", Glossary.forLanguage(store, "Japanese").get("Acme Corp"));
        assertTrue(Glossary.describe(store, "ja").contains("Acme Corp → アクメ株式会社 (Japanese)") && !Glossary.describe(store, "ja").contains("Acme AG"));
        assertTrue(Glossary.remove(store, "Acme Corp", "de")); assertFalse(Glossary.remove(store, "Acme Corp", "de"));
        assertTrue(Files.readString(Glossary.file(store)).contains("\"rendering\" : \"アクメ株式会社\""));
        assertThrows(ProtocolError.class, () -> Glossary.add(store, "", "ja", "x"));
    }
}
