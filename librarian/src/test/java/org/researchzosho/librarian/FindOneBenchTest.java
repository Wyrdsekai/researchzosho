package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The find-one bench without the network or the box: the cases file holds together and the sealed file matches its holdout, the split is
 * the same every time, the runner keeps the artefacts and never the holdout's answer, the scorer reads a saved sample of artefacts into
 * the right counts, and the holdout's page never lists a case. The second set, the cases titled by their thing, holds together the
 * same way, carries a band for every case, and its pages say its name and its counts by band. And the guard against the bench's own
 * leak: a case's own thread, its mirrors and the pages that quote its post are left out of the results and of the pages read, counted
 * and kept with the run, and an answer that reached the pool through a guarded address alone is no find.
 */
class FindOneBenchTest {

    static final ObjectMapper M = new ObjectMapper();

    static Path casesDir() {
        for (String c : new String[]{"../bench/findone", "bench/findone"}) if (Files.isRegularFile(Path.of(c, FindOneBench.CASES_FILE))) return Path.of(c);
        assumeTrue(false, "bench/findone/" + FindOneBench.CASES_FILE + " is not in this tree: the benches do not ship (oss/exclude.txt), so the exported tree skips this test");
        return null;
    }

    static Path titledDir() {
        for (String c : new String[]{"../bench/findone-titled", "bench/findone-titled"}) if (Files.isRegularFile(Path.of(c, FindOneBench.CASES_FILE))) return Path.of(c);
        assumeTrue(false, "bench/findone-titled/" + FindOneBench.CASES_FILE + " is not in this tree: the benches do not ship (oss/exclude.txt), so the exported tree skips this test");
        return null;
    }

    @Test void theTitledSetHoldsTogetherWithABandForEveryCase() throws Exception {
        Path dir = titledDir();
        List<FindOneBench.Case> all = FindOneBench.load(dir.resolve(FindOneBench.CASES_FILE));
        Map<String, String> sealed = FindOneBench.sealed(dir.resolve(FindOneBench.SEALED_FILE));
        assertEquals(24, all.size(), "two dozen cases");
        assertEquals(List.of(), FindOneBench.check(all, sealed), "every case has what it needs and the sealed file names exactly the holdout");
        assertEquals(12, all.stream().filter(FindOneBench.Case::dev).count(), "twelve and twelve");
        Map<String, String> split = FindOneBench.split(all, FindOneBench.SEED);
        for (FindOneBench.Case c : all) assertEquals(c.set(), split.get(c.id()), c.id() + " is in the set the same seeded split puts it in");
        // six subject classes or more, and no half holds more than three cases of one
        Map<String, int[]> bySubject = new LinkedHashMap<>();
        for (FindOneBench.Case c : all) bySubject.computeIfAbsent(c.subject(), k -> new int[2])[c.dev() ? 0 : 1]++;
        assertTrue(bySubject.size() >= 6, "cases across six classes or more: " + bySubject.keySet());
        bySubject.forEach((s, n) -> assertTrue(n[0] <= 3 && n[1] <= 3, s + ": " + n[0] + " dev, " + n[1] + " holdout"));
        // the bands: every case says one for the views and one for the subscribers, the holdout's too, and nothing but a band
        Set<String> views = Set.of("under-50k", "50k-1m", "over-1m"), subscribers = Set.of("under-10k", "10k-1m", "over-1m", "not-shown");
        for (FindOneBench.Case c : all) {
            assertTrue(views.contains(c.views()), c.id() + " says the band of its answer's views: " + c.views());
            assertTrue(subscribers.contains(c.subscribers()), c.id() + " says the band of its channel's subscribers: " + c.subscribers());
            assertTrue(FindOneBench.BANDS.contains(c.views()) && FindOneBench.BANDS.contains(c.subscribers()), c.id() + ": the page knows the band's words");
        }
        // what stays out: the question the pipeline was built on, and every post of the first set
        Set<String> first = new TreeSet<>(), own = new TreeSet<>();
        for (FindOneBench.Case c : FindOneBench.load(casesDir().resolve(FindOneBench.CASES_FILE))) first.add(thread(c.source()));
        for (FindOneBench.Case c : all) {
            assertFalse(c.description().toLowerCase(Locale.ROOT).contains("tote"), c.id() + ": the question the pipeline was built on is not a case");
            assertTrue(c.source().startsWith("https://www.reddit.com/r/") && !thread(c.source()).isEmpty(), c.id() + " says where it came from");
            assertFalse(first.contains(thread(c.source())), c.id() + " is not a post of the first set");
            assertTrue(own.add(thread(c.source())), c.id() + " is a thread of its own");
        }
    }

    /** The id of a Reddit thread in its address, with or without the words of the post's title after it; "" when the address has none. */
    static String thread(String source) {
        int at = source.indexOf("/comments/");
        if (at < 0) return "";
        String rest = source.substring(at + "/comments/".length());
        return rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
    }

    @Test void aNamedSetSaysItsNameAndItsCountsByBand(@TempDir Path tmp) throws Exception {
        // sealing keeps each case's bands, in both halves
        StringBuilder picked = new StringBuilder();
        for (int i = 1; i <= 4; i++) picked.append(((ObjectNode) M.readTree(caseJson("P" + i, "cooking", "aaaaaaaaa" + String.format("%02d", i)))).put("views", i % 2 == 0 ? "under-50k" : "over-1m").put("subscribers", "not-shown")).append('\n');
        Path in = tmp.resolve("picked.jsonl"); Files.writeString(in, picked.toString(), StandardCharsets.UTF_8);
        FindOneBench.seal(in, tmp.resolve("findone-titled"));
        List<FindOneBench.Case> sealedCases = FindOneBench.load(tmp.resolve("findone-titled").resolve(FindOneBench.CASES_FILE));
        assertEquals(2, sealedCases.stream().filter(c -> !c.dev()).count());
        for (FindOneBench.Case c : sealedCases) { assertEquals(Integer.parseInt(c.id().substring(1)) % 2 == 0 ? "under-50k" : "over-1m", c.views(), c.id()); assertEquals("not-shown", c.subscribers(), c.id()); }
        assertEquals("titled", FindOneBench.setName(tmp.resolve("findone-titled")), "the set's name is its folder's");
        assertEquals("", FindOneBench.setName(Path.of("bench", "findone")), "the first set has no name");
        // B1: seen by few, in the pool at rank 2; B2: seen by few, absent; B3: seen by many, at rank 1; H1 (holdout): in between, at rank 1
        Map<String, Integer> rank = Map.of("B1", 2, "B2", 0, "B3", 1, "H1", 1);
        Map<String, String> answer = Map.of("B1", "answer00001", "B2", "answer00002", "B3", "answer00003", "H1", "answer00004");
        String words = "a video where someone makes a thing out of paper, words shown on the screen, a yellow table";
        List<FindOneBench.Case> cases = List.of(
                new FindOneBench.Case("B1", FindOneBench.DEV, "cooking", "en", "2025-01-02", "https://www.reddit.com/r/x/comments/B1/", words, answer.get("B1"), "under-50k", "under-10k"),
                new FindOneBench.Case("B2", FindOneBench.DEV, "cooking", "en", "2025-01-02", "https://www.reddit.com/r/x/comments/B2/", words, answer.get("B2"), "under-50k", "10k-1m"),
                new FindOneBench.Case("B3", FindOneBench.DEV, "crafts", "ja", "2025-01-02", "https://www.reddit.com/r/x/comments/B3/", words, answer.get("B3"), "over-1m", "over-1m"),
                new FindOneBench.Case("H1", FindOneBench.HOLDOUT, "crafts", "en", "2025-01-02", "https://www.reddit.com/r/x/comments/H1/", words, "", "50k-1m", "not-shown"));
        Path out = tmp.resolve("out");
        FindOneBench.run(cases, new FindOneBench.Settings("http://127.0.0.1:1", "stand-in", out, FindOneBench.CANDIDATES, 1, 1, 5), standIn(rank, answer, Set.of(), Set.of()), s -> { });
        assertTrue(Files.readString(FindOneBench.caseDir(out, FindOneBench.HOLDOUT, "H1").resolve("case.json")).contains("\"views\":\"50k-1m\""), "the case file beside the artefacts carries the band");
        String devPage = FindOneBench.writeResults("titled", out, FindOneBench.DEV, answer, "stand-in", FindOneBench.CANDIDATES);
        assertTrue(devPage.startsWith("# The find-one bench, the titled cases, the dev set, "), devPage);
        assertTrue(devPage.contains("the dev set's 3 cases of the titled cases, through the candidate step alone"), devPage);
        assertTrue(devPage.contains("## By the answer's views when the case was picked\n\n- under 50,000: in the pool 1 of 2.\n- over 1,000,000: in the pool 1 of 1.\n"), devPage);
        assertTrue(devPage.contains("## By its channel's subscribers when the case was picked\n\n- under 10,000: in the pool 1 of 1.\n- 10,000 to 1,000,000: in the pool 0 of 1.\n- over 1,000,000: in the pool 1 of 1.\n"), devPage);
        assertTrue(devPage.contains("| case | subject | things named | pool | the answer's rank | found by | views | subscribers | minutes |"), devPage);
        assertTrue(devPage.contains("| B1 | cooking | paper thing, 紙の物 | 5 | 2 | search + channel | under 50,000 | under 10,000 |"), devPage);
        assertTrue(devPage.contains("| B2 | cooking | paper thing, 紙の物 | 5 | absent | — | under 50,000 | 10,000 to 1,000,000 |"), devPage);
        // the holdout's page has the counts by band and still no case
        String holdPage = FindOneBench.writeResults("titled", out, FindOneBench.HOLDOUT, answer, "stand-in", FindOneBench.CANDIDATES);
        assertTrue(holdPage.startsWith("# The find-one bench, the titled cases, the holdout set, "), holdPage);
        assertTrue(holdPage.contains("- 50,000 to 1,000,000: in the pool 1 of 1.") && holdPage.contains("- not shown: in the pool 1 of 1."), holdPage);
        assertFalse(holdPage.contains("| H1") || holdPage.contains("answer00004"), "the holdout page lists no case and names no answer");
        JsonNode counts = M.readTree(Files.readString(out.resolve("counts-holdout.json")));
        assertEquals("titled", counts.path("case_set").asText());
        assertEquals(1, counts.path("by_views").path("50k-1m").path("in_pool").asInt()); assertEquals(1, counts.path("by_subscribers").path("not-shown").path("n").asInt());
        assertFalse(counts.has("cases"), "the holdout's counts file has no case row");
        assertEquals("under-50k", M.readTree(Files.readString(out.resolve("counts-dev.json"))).path("cases").get(0).path("views").asText(), "the dev rows say their band");
        // a set whose cases carry no band has no such section, and a set with no name says none
        List<FindOneBench.Scored> plain = new ArrayList<>();
        for (FindOneBench.Scored sc : FindOneBench.score(out.resolve(FindOneBench.DEV), answer)) plain.add(new FindOneBench.Scored(sc.id(), sc.subject(), sc.error(), sc.poolSize(), sc.bySource(), sc.cut(), sc.rank(), sc.foundBy(), sc.things(),
                sc.reached(), sc.thumbVerdict(), sc.sheetVerdict(), sc.survivors(), sc.survivorRank(), sc.looked(), sc.named(), sc.ms(), sc.stage(), "", ""));
        String plainPage = FindOneBench.results(FindOneBench.DEV, plain, "2025-01-02", "stand-in", FindOneBench.CANDIDATES, false);
        assertTrue(plainPage.startsWith("# The find-one bench, the dev set, 2025-01-02"), plainPage);
        assertFalse(plainPage.contains("when the case was picked") || plainPage.contains("| views |"), plainPage);
        assertTrue(plainPage.contains("| case | subject | things named | pool | the answer's rank | found by | minutes |"), plainPage);
    }

    @Test void theCasesFileHoldsTogetherAndTheSealedFileMatchesItsHoldout() throws Exception {
        Path dir = casesDir();
        List<FindOneBench.Case> all = FindOneBench.load(dir.resolve(FindOneBench.CASES_FILE));
        Map<String, String> sealed = FindOneBench.sealed(dir.resolve(FindOneBench.SEALED_FILE));
        assertTrue(all.size() >= 20, "about two dozen cases: " + all.size());
        assertEquals(List.of(), FindOneBench.check(all, sealed), "every case has what it needs and the sealed file names exactly the holdout");
        long dev = all.stream().filter(FindOneBench.Case::dev).count();
        assertTrue(Math.abs(dev - (all.size() - dev)) <= all.size() / 4, "two halves: " + dev + " dev of " + all.size());
        Set<String> subjects = new TreeSet<>(), languages = new TreeSet<>();
        for (FindOneBench.Case c : all) { subjects.add(c.subject()); languages.add(c.language().toLowerCase(Locale.ROOT)); }
        assertTrue(subjects.size() >= 5, "cases across subjects: " + subjects);
        assertTrue(languages.size() >= 2, "answers in more than one language: " + languages);
        for (FindOneBench.Case c : all) {
            String d = c.description().toLowerCase(Locale.ROOT);
            assertFalse(d.contains("tote bag") || d.contains("sewing") || d.contains("sew "), c.id() + ": the question the pipeline was built on is not a case");
            assertTrue(c.source().startsWith("https://www.reddit.com/r/"), c.id() + " says where it came from");
        }
        for (String id : sealed.keySet()) assertTrue(all.stream().anyMatch(c -> c.id().equals(id) && !c.dev()), id + " is a holdout case");
    }

    @Test void theSplitIsTheSameEveryTimeAndIsTheOneRecorded() throws Exception {
        Path dir = casesDir();
        List<FindOneBench.Case> all = FindOneBench.load(dir.resolve(FindOneBench.CASES_FILE));
        Map<String, String> once = FindOneBench.split(all, FindOneBench.SEED), twice = FindOneBench.split(all, FindOneBench.SEED);
        assertEquals(once, twice, "the same cases and seed give the same split");
        for (FindOneBench.Case c : all) assertEquals(c.set(), once.get(c.id()), c.id() + " is in the set the split puts it in");
        // by subject: the two halves of a subject differ by one at most, so no subject sits in one half alone
        Map<String, int[]> bySubject = new LinkedHashMap<>();
        for (FindOneBench.Case c : all) bySubject.computeIfAbsent(c.subject(), k -> new int[2])[c.dev() ? 0 : 1]++;
        bySubject.forEach((s, n) -> assertTrue(Math.abs(n[0] - n[1]) <= 1, s + ": " + n[0] + " dev, " + n[1] + " holdout"));
        List<FindOneBench.Case> reversed = new ArrayList<>(all); Collections.reverse(reversed);
        assertEquals(once, FindOneBench.split(reversed, FindOneBench.SEED), "the order of the file does not change the split");
        assertFalse(once.equals(FindOneBench.split(all, FindOneBench.SEED + 1)), "another seed draws another split");
    }

    @Test void sealingAPickedFileLeavesDevAnswersOpenAndMovesHoldoutAnswersToTheSealedFile(@TempDir Path tmp) throws Exception {
        StringBuilder picked = new StringBuilder();
        for (int i = 1; i <= 6; i++) picked.append(caseJson("P" + i, i % 2 == 0 ? "cooking" : "animation", "aaaaaaaaa" + String.format("%02d", i))).append('\n');
        Path in = tmp.resolve("picked.jsonl"); Files.writeString(in, picked.toString(), StandardCharsets.UTF_8);
        Path dir = tmp.resolve("findone");
        FindOneBench.seal(in, dir);
        List<FindOneBench.Case> all = FindOneBench.load(dir.resolve(FindOneBench.CASES_FILE));
        Map<String, String> sealed = FindOneBench.sealed(dir.resolve(FindOneBench.SEALED_FILE));
        assertEquals(6, all.size());
        assertEquals(List.of(), FindOneBench.check(all, sealed));
        assertEquals(3, all.stream().filter(FindOneBench.Case::dev).count());
        for (FindOneBench.Case c : all) assertEquals(c.dev(), !c.answer().isEmpty(), c.id());
        String sealedText = Files.readString(dir.resolve(FindOneBench.SEALED_FILE), StandardCharsets.UTF_8);
        for (String v : sealed.values()) assertFalse(sealedText.contains(v), "a glance at the sealed file shows no video id");
        assertTrue(sealedText.contains("nobody reads it by hand"), sealedText);
        Map<String, String> answers = FindOneBench.answers(all, sealed);
        for (int i = 1; i <= 6; i++) assertEquals("aaaaaaaaa" + String.format("%02d", i), answers.get("P" + i), "every answer is known to the scorer, from the open or the sealed file");
    }

    static String caseJson(String id, String subject, String answer) {
        return M.createObjectNode().put("id", id).put("set", "").put("subject", subject).put("language", "en").put("posted", "2025-01-02").put("source", "https://www.reddit.com/r/example/comments/" + id + "/")
                .put("description", "I remember a video where someone makes a thing out of paper, with words shown on the screen and a yellow table, no talking at all.").put("answer", answer).toString();
    }

    /** A stand-in pipeline: the brain names the thing, the pool holds the answer at a known rank (or not), the sift keeps it (or not), the look finds it (or not). */
    static FindOneBench.Pipeline standIn(Map<String, Integer> rankOf, Map<String, String> answerOf, Set<String> survives, Set<String> foundByLook) {
        return new FindOneBench.Pipeline() {
            @Override public ObjectNode name(String description) {
                ObjectNode o = M.createObjectNode().put("said", "").put("called", "candidates");
                ObjectNode args = o.putObject("args"); args.putArray("things").add("paper thing").add("紙の物"); args.put("before", "2025-01");
                return o;
            }
            @Override public Candidates.Result candidates(LibraryStore store, Candidates.Ask ask, Consumer<String> progress, long untilMs) {
                String id = store.root().getParent().getFileName().toString();
                List<Candidates.Hit> pool = new ArrayList<>();
                int rank = rankOf.getOrDefault(id, 0);
                for (int i = 1; i <= 5; i++) {
                    Candidates.Hit h = new Candidates.Hit(i == rank ? answerOf.get(id) : "other" + String.format("%06d", i)); h.title = "video " + i; h.sources.add(i == rank ? Candidates.SEARCH : Candidates.CHANNEL);
                    if (i == rank) h.sources.add(Candidates.CHANNEL);
                    pool.add(h);
                }
                progress.accept("search: 5 results");
                return new Candidates.Result(pool, Map.of(Candidates.SEARCH, 1, Candidates.CHANNEL, 5), List.of("Searched as paper thing."), 0, 0, 0, 0, 1, "2025-01-02-0123456789abcdef", false, "the pool's text");
            }
            @Override public Sift.Result sift(LibraryStore store, Sift.Ask ask, Consumer<String> progress, long untilMs) {
                String id = store.root().getParent().getFileName().toString();
                String answer = answerOf.get(id);
                Map<String, Sift.Judged> ledger = new LinkedHashMap<>();
                List<Sift.Sifted> survivors = new ArrayList<>();
                Candidates.Hit other = new Candidates.Hit("other000001"); other.title = "video 1";
                Sift.Sifted s1 = new Sift.Sifted(other); s1.thumbVerdict = "maybe"; s1.sheetVerdict = "yes"; survivors.add(s1);
                Sift.record(store, ledger, new Sift.Judged("other000001", Sift.THUMB, "h", "maybe", "a thing")); Sift.record(store, ledger, new Sift.Judged("other000001", Sift.SHEET, "h", "yes", "words"));
                if (rankOf.getOrDefault(id, 0) > 0) {
                    boolean keep = survives.contains(id);
                    Sift.record(store, ledger, new Sift.Judged(answer, Sift.THUMB, "h", "yes", "the thing"));
                    Sift.record(store, ledger, new Sift.Judged(answer, Sift.SHEET, "h", keep ? "yes" : "no", keep ? "the words shown" : "a different thing"));
                    if (keep) { Candidates.Hit a = new Candidates.Hit(answer); Sift.Sifted s2 = new Sift.Sifted(a); s2.thumbVerdict = "yes"; s2.sheetVerdict = "yes"; survivors.add(s2); }
                }
                return new Sift.Result(survivors, 5, 5, 5, 5, 2, 2, 2, survivors.size(), 0, 0, 0, 1.0, 5.0, "grid", "k", "2025-01-02-0123456789abcdef", List.of(), "the sift's text");
            }
            @Override public VideoRecall.Look look(LibraryStore store, String videoId, String what) {
                String id = store.root().getParent().getFileName().toString();
                boolean hit = videoId.equals(answerOf.get(id)) && foundByLook.contains(id);
                return new VideoRecall.Look(videoId, "video " + videoId, List.of(new VideoRecall.Answer("words shown on the screen", hit ? "found" : "no", hit ? 12 : 0, "why"), new VideoRecall.Answer("a yellow table", hit ? "found" : "maybe", 0, "why")), "read");
            }
        };
    }

    @Test void theRunnerKeepsTheArtefactsAndTheScorerReadsThemIntoTheCounts(@TempDir Path tmp) throws Exception {
        // C1: in the pool at rank 3, survives, found by the look; C2: absent; C3: in the pool at rank 2, dropped by the sift; H1 (holdout): rank 1, survives, named
        Map<String, Integer> rank = Map.of("C1", 3, "C2", 0, "C3", 2, "H1", 1);
        Map<String, String> answer = Map.of("C1", "answer00001", "C2", "answer00002", "C3", "answer00003", "H1", "answer00004");
        List<FindOneBench.Case> cases = new ArrayList<>();
        for (String id : List.of("C1", "C2", "C3")) cases.add(new FindOneBench.Case(id, FindOneBench.DEV, id.equals("C2") ? "cooking" : "animation", "en", "2025-01-02", "https://www.reddit.com/r/x/comments/" + id + "/", "a video where someone makes a thing out of paper, words shown on the screen, a yellow table", answer.get(id)));
        cases.add(new FindOneBench.Case("H1", FindOneBench.HOLDOUT, "cooking", "ja", "2025-01-02", "https://www.reddit.com/r/x/comments/H1/", "a video of a cake with no talking and a yellow table", ""));
        Path out = tmp.resolve("out");
        FindOneBench.Settings s = new FindOneBench.Settings("http://127.0.0.1:1", "stand-in", out, FindOneBench.FULL, 1, 1, 5);
        List<String> said = new ArrayList<>();
        FindOneBench.run(cases, s, standIn(rank, answer, Set.of("C1", "H1"), Set.of("C1", "H1")), said::add);
        // the artefacts of a case, and never the holdout's answer
        Path h1 = FindOneBench.caseDir(out, FindOneBench.HOLDOUT, "H1");
        for (String f : List.of("case.json", "named.json", "pool.json", "candidates.txt", "sift.json", "judged.jsonl", "looks.json", "run.json", "trace.txt")) assertTrue(Files.isRegularFile(h1.resolve(f)), f);
        assertFalse(Files.readString(h1.resolve("case.json")).contains("answer00004"), "the case file written beside the artefacts carries no answer");
        assertFalse(Files.readString(h1.resolve("run.json")).contains("answer00004"), "the run's record carries no answer");
        assertTrue(Files.readString(h1.resolve("named.json")).contains("紙の物"), "what the brain named is kept");
        // the scorer, offline
        Map<String, String> answers = new LinkedHashMap<>(answer);
        List<FindOneBench.Scored> dev = FindOneBench.score(out.resolve(FindOneBench.DEV), answers);
        assertEquals(3, dev.size());
        FindOneBench.Scored c1 = dev.stream().filter(x -> x.id().equals("C1")).findFirst().orElseThrow();
        assertEquals(3, c1.rank()); assertEquals("search+channel", c1.foundBy()); assertEquals("yes", c1.thumbVerdict()); assertEquals("yes", c1.sheetVerdict()); assertEquals(2, c1.survivorRank()); assertTrue(c1.looked()); assertTrue(c1.named());
        FindOneBench.Scored c2 = dev.stream().filter(x -> x.id().equals("C2")).findFirst().orElseThrow();
        assertFalse(c2.inPool()); assertFalse(c2.sifted()); assertFalse(c2.survived()); assertFalse(c2.looked()); assertFalse(c2.named());
        FindOneBench.Scored c3 = dev.stream().filter(x -> x.id().equals("C3")).findFirst().orElseThrow();
        assertEquals(2, c3.rank()); assertTrue(c3.sifted()); assertEquals("no", c3.sheetVerdict()); assertFalse(c3.survived()); assertFalse(c3.looked());
        FindOneBench.Counts k = FindOneBench.Counts.of(dev);
        assertEquals(3, k.n()); assertEquals(0, k.crashed()); assertEquals(2, k.inPool()); assertEquals(2, k.sifted()); assertEquals(1, k.survived()); assertEquals(1, k.looked()); assertEquals(1, k.named());
        assertEquals(2.5, k.medianRank(), 0.001); assertEquals(5, k.medianPool(), 0.001);
        assertEquals(Map.of("search", 2, "channel", 2), k.foundBy());
        // the pages: the dev page lists every case; the holdout page lists none and names no answer
        String devPage = FindOneBench.writeResults(out, FindOneBench.DEV, answers, "stand-in", FindOneBench.FULL);
        assertTrue(devPage.contains("| C1 | animation | paper thing, 紙の物 | 5 | 3 | search + channel | yes | yes | 2 of 2 | yes | yes |"), devPage);
        assertTrue(devPage.contains("| C2 | cooking | paper thing, 紙の物 | 5 | absent | — | — | — | — | no | no |"), devPage);
        assertTrue(devPage.contains("In the pool: 2 of 3."), devPage);
        assertTrue(devPage.contains("keyless"), "the page says the candidate step ran keyless");
        assertTrue(devPage.contains("one run on one day"), "the page says what the noise means");
        String holdPage = FindOneBench.writeResults(out, FindOneBench.HOLDOUT, answers, "stand-in", FindOneBench.FULL);
        assertTrue(holdPage.contains("In the pool: 1 of 1."), holdPage);
        assertTrue(holdPage.contains("Named: 1 of 1."), holdPage);
        assertFalse(holdPage.contains("| H1"), "the holdout page lists no case");
        assertFalse(holdPage.contains("answer00004"), "the holdout page names no answer");
        assertTrue(holdPage.contains("not listed one by one"), holdPage);
        assertTrue(Files.isRegularFile(out.resolve("RESULTS-holdout.md")) && Files.isRegularFile(out.resolve("counts-holdout.json")));
        String counts = Files.readString(out.resolve("counts-holdout.json"));
        assertFalse(counts.contains("H1") || counts.contains("answer00004"), "the holdout's counts file has no case row either");
        assertTrue(Files.readString(out.resolve("counts-dev.json")).contains("\"id\" : \"C1\""), "the dev counts file has the rows");
        // the same cases again with the pools taken as they are: the candidate counts stand, the sift runs from the kept pool, nothing is enumerated
        FindOneBench.Pipeline noEnumeration = new FindOneBench.Pipeline() {
            FindOneBench.Pipeline inner = standIn(rank, answer, Set.of("C1", "H1"), Set.of("C1", "H1"));
            @Override public ObjectNode name(String description) { throw new IllegalStateException("the thing was named already"); }
            @Override public Candidates.Result candidates(LibraryStore store, Candidates.Ask ask, Consumer<String> progress, long untilMs) { throw new IllegalStateException("the pool was made already"); }
            @Override public Sift.Result sift(LibraryStore store, Sift.Ask ask, Consumer<String> progress, long untilMs) {
                assertTrue(Candidates.poolFileByKey(store, ask.from()) != null, "the kept pool is under the fresh library by its key");
                return inner.sift(store, ask, progress, untilMs);
            }
            @Override public VideoRecall.Look look(LibraryStore store, String videoId, String what) throws IOException { return inner.look(store, videoId, what); }
        };
        FindOneBench.run(cases, new FindOneBench.Settings("http://127.0.0.1:1", "stand-in", out, FindOneBench.SIFT, 1, 1, 5, true), noEnumeration, said::add);
        JsonNode again = M.readTree(Files.readString(FindOneBench.caseDir(out, FindOneBench.DEV, "C1").resolve("run.json")));
        assertEquals("sift", again.path("stage").asText()); assertEquals(5, again.path("candidates").path("pool").asInt()); assertFalse(again.path("candidates").path("reused_from").asText("").isEmpty(), "the record says the pool was reused");
        assertTrue(again.has("sift") && !again.has("error"), again.toString());
        List<FindOneBench.Scored> devAgain = FindOneBench.score(out.resolve(FindOneBench.DEV), answers);
        assertEquals(3, devAgain.stream().filter(x -> x.id().equals("C1")).findFirst().orElseThrow().rank(), "the rank is the kept pool's");
        assertEquals(2, devAgain.stream().filter(x -> x.id().equals("C1")).findFirst().orElseThrow().survivorRank());
    }

    @Test void aCrashIsCountedApartNotAsAMiss(@TempDir Path tmp) throws Exception {
        List<FindOneBench.Case> cases = List.of(new FindOneBench.Case("X1", FindOneBench.DEV, "games", "en", "2025-01-02", "https://www.reddit.com/r/x/comments/X1/", "a video of a game where the player builds a tower of boxes and the screen says go", "answer00009"));
        FindOneBench.Pipeline failing = new FindOneBench.Pipeline() {
            @Override public ObjectNode name(String description) { throw new IllegalStateException("no model answered"); }
            @Override public Candidates.Result candidates(LibraryStore store, Candidates.Ask ask, Consumer<String> progress, long untilMs) { throw new IllegalStateException("never reached"); }
            @Override public Sift.Result sift(LibraryStore store, Sift.Ask ask, Consumer<String> progress, long untilMs) { throw new IllegalStateException("never reached"); }
            @Override public VideoRecall.Look look(LibraryStore store, String videoId, String what) { throw new IllegalStateException("never reached"); }
        };
        Path out = tmp.resolve("out");
        FindOneBench.run(cases, new FindOneBench.Settings("http://127.0.0.1:1", "stand-in", out, FindOneBench.CANDIDATES, 1, 1, 5), failing, s -> { });
        List<FindOneBench.Scored> scored = FindOneBench.score(out.resolve(FindOneBench.DEV), Map.of("X1", "answer00009"));
        assertEquals(1, scored.size()); assertTrue(scored.get(0).crashed()); assertTrue(scored.get(0).error().contains("no model answered"), scored.get(0).error());
        FindOneBench.Counts k = FindOneBench.Counts.of(scored);
        assertEquals(1, k.crashed()); assertEquals(0, k.inPool());
        String page = FindOneBench.results(FindOneBench.DEV, scored, "2025-01-02", "stand-in", FindOneBench.CANDIDATES, false);
        assertTrue(page.contains("1 case crashed"), page);
        assertTrue(page.contains("In the pool: 0 of 0."), "the misses count only the cases that ran: " + page);
        assertTrue(page.contains("| X1 | games |  | crashed: the run failed: IllegalStateException: no model answered |"), page);
    }

    @Test void aNamingCallCutAtItsLimitSaysSoAndIsNotAMiss(@TempDir Path tmp) throws Exception {
        List<FindOneBench.Case> cases = List.of(
                new FindOneBench.Case("X1", FindOneBench.DEV, "games", "en", "2025-01-02", "https://www.reddit.com/r/x/comments/X1/", "a video of a game where the player builds a tower of boxes and the screen says go", "answer00009"),
                new FindOneBench.Case("X2", FindOneBench.DEV, "games", "en", "2025-01-02", "https://www.reddit.com/r/x/comments/X2/", "a video of a game where a paper boat crosses a flooded street", "answer00010"));
        FindOneBench.Pipeline cut = new FindOneBench.Pipeline() {
            @Override public ObjectNode name(String description) {
                ObjectNode named = new ObjectMapper().createObjectNode().put("said", "").put("called", "candidates").put("limit", 768);
                named.put("out_tokens", description.contains("tower") ? 768 : 90);   // the first spent its whole limit thinking; the second answered in a few words and named nothing
                named.putObject("args");
                return named;
            }
            @Override public Candidates.Result candidates(LibraryStore store, Candidates.Ask ask, Consumer<String> progress, long untilMs) { throw new IllegalStateException("never reached"); }
            @Override public Sift.Result sift(LibraryStore store, Sift.Ask ask, Consumer<String> progress, long untilMs) { throw new IllegalStateException("never reached"); }
            @Override public VideoRecall.Look look(LibraryStore store, String videoId, String what) { throw new IllegalStateException("never reached"); }
        };
        Path out = tmp.resolve("out");
        FindOneBench.run(cases, new FindOneBench.Settings("http://127.0.0.1:1", "stand-in", out, FindOneBench.CANDIDATES, 1, 1, 5), cut, s -> { });
        List<FindOneBench.Scored> scored = FindOneBench.score(out.resolve(FindOneBench.DEV), Map.of("X1", "answer00009", "X2", "answer00010"));
        assertEquals(2, scored.size());
        FindOneBench.Scored x1 = scored.stream().filter(s -> s.id().equals("X1")).findFirst().orElseThrow(), x2 = scored.stream().filter(s -> s.id().equals("X2")).findFirst().orElseThrow();
        assertTrue(x1.crashed() && x1.error().contains("was cut at its limit of 768 tokens"), x1.error());
        assertTrue(x2.crashed() && x2.error().equals("the brain's candidates call named no thing"), x2.error());
        assertEquals(0, FindOneBench.Counts.of(scored).inPool());
        assertEquals(2, FindOneBench.Counts.of(scored).crashed());
    }

    static final FindOneBench.Case GUARDED = new FindOneBench.Case("G1", FindOneBench.DEV, "crafts", "en", "2025-01-02", "https://www.reddit.com/r/tipofmytongue/comments/1abcd2e/tomt_a_paper_boat_video/",
            "[TOMT][YouTube] A video where a paper boat crosses a flooded street while a small dog runs beside it and a brass band plays, filmed from above.", "answer00001");

    @Test void theGuardLeavesOutTheCasesOwnThreadItsMirrorsAndWhateverQuotesThePost() {
        String src = GUARDED.source();
        for (String u : List.of(src, "https://old.reddit.com/r/tipofmytongue/comments/zzzzzz/another_post/", "https://np.reddit.com/r/TipOfMyTongue/comments/zzzzzz/x/", "https://redd.it/1abcd2e", "https://www.reddit.com/comments/1abcd2e",
                "https://mirror.example/r/tipofmytongue/comments/zzzzzz/x", "https://archive.example/thread?url=https%3A%2F%2Fwww.reddit.com%2Fr%2Fx%2Fcomments%2F1abcd2e%2F", "https://viewer.example/post/1abcd2e/a-paper-boat"))
            assertNotNull(FindOneBench.Guard.byAddress(u, src), "guarded by its address: " + u);
        for (String u : List.of("https://www.reddit.com/r/videos/comments/9zzzz9/a_paper_boat/", "https://blog.example/paper-boats", "https://www.youtube.com/watch?v=answer00001", "https://blog.example/1abcd2ex"))
            assertNull(FindOneBench.Guard.byAddress(u, src), "an address like any other: " + u);
        FindOneBench.Guard g = new FindOneBench.Guard(GUARDED);
        assertEquals("the words of a tipofmytongue thread", g.byText("Solved! [TOMT] something else entirely"));
        assertEquals("the words of a tipofmytongue thread", g.byText("posted in r/tipofmytongue by someone"));
        assertEquals("it quotes the case's post", g.byText("He wrote: a paper boat crosses a flooded street while a small dog runs — and that was all"));
        assertNull(g.byText("A paper boat crosses a street. A small dog runs beside a brass band."), "the same things in other words are another person's page");
        assertNull(g.byText("paper boat crosses a flooded street while"), "seven words in a row are not a quote");
        assertNull(g.byText("")); assertNull(g.byText(null));
        // the results of a search: the thread and the copy go, the honest page and the video's own page stay, written as the search tools write them
        String text = "results for \"x\":\n1. [TOMT] A paper boat video : tipofmytongue\n   https://www.reddit.com/r/tipofmytongue/comments/1abcd2e/tomt_a_paper_boat_video/  [other]\n   A video where a paper boat crosses a street.\n"
                + "2. A copy\n   https://copy.example/threads/77  [other]\n   a paper boat crosses a flooded street while a small dog runs beside it\n"
                + "3. Paper boats in the rain\n   https://blog.example/paper-boats  [other]\n   A boat of paper sails down a street in the rain.\n"
                + "4. The video itself - YouTube\n   https://www.youtube.com/watch?v=honestvid01\n   A paper boat.\n";
        List<Described.Row> kept = Described.rows(g.results(text));
        assertEquals(List.of("https://blog.example/paper-boats", "https://www.youtube.com/watch?v=honestvid01"), kept.stream().map(Described.Row::url).toList());
        assertEquals("A boat of paper sails down a street in the rain.", kept.get(0).snippet());
        assertEquals("no results for: x", g.results("no results for: x"), "a text with no results comes back as it is"); assertNull(g.results(null));
        // the pages: an honest page comes back whole; a copy of the post and a mirror of the thread come back as nothing, with the videos they held kept
        String honest = "<html><title>Paper boats</title><body><p>A boat of paper sails down a street.</p><iframe src=\"https://www.youtube.com/embed/honestvid02\"></iframe></body></html>";
        assertEquals(honest, g.page("https://blog.example/paper-boats", honest));
        assertNull(g.page("https://copy.example/threads/77", "<html><title>Answers</title><body><p>Someone asked: A video where a paper boat crosses a flooded street while a small dog runs beside it. The answer:</p><a href=\"https://youtu.be/answer00001\">this</a></body></html>"));
        assertNull(g.page("https://mirror.example/t/5", "<html><title>[TOMT] a thread</title><body><a href=\"https://youtu.be/answer00001\">x</a></body></html>"));
        assertNull(g.page("https://gone.example/page", null), "a page that was not read is not the guard's");
        JsonNode j = g.json();
        assertEquals(2, j.path("results_left_out").asInt()); assertEquals(2, j.path("pages_left_out").asInt()); assertEquals(4, j.path("left_out").size());
        assertEquals("a tipofmytongue thread", j.path("left_out").get(0).path("why").asText()); assertEquals("it quotes the case's post", j.path("left_out").get(1).path("why").asText());
        assertEquals("page", j.path("left_out").get(2).path("where").asText()); assertEquals("answer00001", j.path("left_out").get(2).path("videos").get(0).asText(), "the videos a guarded page held are kept, so a reader sees what the guard kept out");
        assertEquals("the words of a tipofmytongue thread", j.path("left_out").get(3).path("why").asText());
    }

    static void artefacts(Path dir, String id, String set, String run, String pool) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("case.json"), M.createObjectNode().put("id", id).put("set", set).put("subject", "crafts").put("source", "https://www.reddit.com/r/tipofmytongue/comments/1abcd2e/x/").toString());
        Files.writeString(dir.resolve("run.json"), run);
        Files.writeString(dir.resolve("pool.json"), pool);
    }

    @Test void anAnswerReachedThroughAGuardedAddressAloneIsNoFindAndThePagesSayWhichPageLedToEachFind(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("out");
        // D1: an honest page and the video's own page led to the answer; the guard also left out a copy that held it. D2: only a mirror of the thread led to it. D3: a deep search found it.
        artefacts(out.resolve("dev/D1"), "D1", "dev", "{\"id\":\"D1\",\"set\":\"dev\",\"stage\":\"candidates\",\"day\":\"2025-01-02\",\"web_index\":true,\"candidates\":{\"cut\":false,\"described\":{\"asked\":10,\"answered\":9}},"
                + "\"guard\":{\"results_left_out\":3,\"pages_left_out\":1,\"left_out\":[{\"where\":\"page\",\"url\":\"https://copy.example/t/1\",\"why\":\"it quotes the case's post\",\"videos\":[\"answer00001\"]}]}}",
                "{\"things\":[\"paper boat\"],\"by_source\":{\"described\":2},\"pool\":[{\"id\":\"other000001\",\"sources\":[\"web: blog.example\"],\"pages\":[\"https://blog.example/a\"]},"
                + "{\"id\":\"answer00001\",\"sources\":[\"web: blog.example\",\"web: youtube.com\"],\"pages\":[\"https://blog.example/paper-boats\",\"https://www.youtube.com/watch?v=answer00001\"],\"fit\":2}]}");
        artefacts(out.resolve("dev/D2"), "D2", "dev", "{\"id\":\"D2\",\"set\":\"dev\",\"stage\":\"candidates\",\"day\":\"2025-01-02\",\"candidates\":{\"cut\":false}}",
                "{\"things\":[\"paper boat\"],\"by_source\":{\"described\":1},\"pool\":[{\"id\":\"answer00002\",\"sources\":[\"web: mirror.example\"],\"pages\":[\"https://mirror.example/r/tipofmytongue/comments/2bcde3f/x\"]}]}");
        artefacts(out.resolve("dev/D3"), "D3", "dev", "{\"id\":\"D3\",\"set\":\"dev\",\"stage\":\"candidates\",\"day\":\"2025-01-02\",\"candidates\":{\"cut\":false}}",
                "{\"things\":[\"paper boat\"],\"by_source\":{\"search\":1},\"pool\":[{\"id\":\"answer00003\",\"sources\":[\"search\"]}]}");
        artefacts(out.resolve("holdout/H2"), "H2", "holdout", "{\"id\":\"H2\",\"set\":\"holdout\",\"stage\":\"candidates\",\"day\":\"2025-01-02\",\"web_index\":true,\"candidates\":{\"cut\":false,\"described\":{\"asked\":4,\"answered\":4}},\"guard\":{\"results_left_out\":1,\"pages_left_out\":0,\"left_out\":[]}}",
                "{\"things\":[\"cake\"],\"by_source\":{\"described\":1},\"pool\":[{\"id\":\"answer00004\",\"sources\":[\"web: cakes.example\"],\"pages\":[\"https://cakes.example/quiet-cake\"]}]}");
        Map<String, String> answers = Map.of("D1", "answer00001", "D2", "answer00002", "D3", "answer00003", "H2", "answer00004");
        List<FindOneBench.Scored> dev = FindOneBench.score(out.resolve("dev"), answers);
        FindOneBench.Scored d1 = dev.get(0), d2 = dev.get(1), d3 = dev.get(2);
        assertTrue(d1.inPool() && d1.rank() == 2 && d1.guardHeld() && !d1.guardedOnly(), d1.toString());
        assertEquals("blog.example, youtube.com", d1.ledBy()); assertEquals(4, d1.leftOut()); assertEquals(9, d1.answered()); assertEquals(10, d1.asked());
        assertTrue(!d2.inPool() && d2.guardedOnly() && d2.rank() == 1, "in the pool's file, and no find: " + d2);
        assertTrue(d3.inPool() && !d3.guardedOnly() && d3.ledBy().isEmpty(), d3.toString());
        FindOneBench.Counts k = FindOneBench.Counts.of(dev);
        assertEquals(2, k.inPool()); assertEquals(1, k.guardedOnly()); assertEquals(1, k.guardHeld()); assertEquals(1, k.webAnswered());
        assertEquals(Map.of(Candidates.DESCRIBED, 1, Candidates.SEARCH, 1), k.foundBy(), "a page's site is a case's own detail: the counts say only that a page led to the answer, once a case");
        String page = FindOneBench.writeResults(out, FindOneBench.DEV, answers, "stand-in", FindOneBench.CANDIDATES);
        assertTrue(page.contains("In the pool: 2 of 3."), page);
        assertTrue(page.contains("| D1 | crafts | paper boat | 2 | 2 | web: blog.example + web: youtube.com | 0 | blog.example, youtube.com | 9 of 10 | 4 (one held the answer) |"), page);
        assertTrue(page.contains("| D2 | crafts | paper boat | 1 | only through a guarded page | web: mirror.example | 0 | mirror.example | 0 of 0 | 0 |"), page);
        assertTrue(page.contains("- Which source found it: 1 by a page that describes the video, 1 by a deep search"), page);
        assertTrue(page.contains("- The web: it answered in 1 of 3 cases.") && page.contains("A page it left out held the answer in 1 case, which counts for nothing") && page.contains("through guarded addresses alone in 1 case, which is not counted as in the pool."), page);
        assertTrue(page.contains("the web was asked through the library's own SearXNG and the built-in fallback alone") && page.contains("every result and every page passed the guard"), page);
        // a run whose web search was the library's own says that an allowance was spent, and never that it ran keyless
        String paidPage = FindOneBench.results(FindOneBench.DEV, dev, "2025-01-02", "stand-in", FindOneBench.CANDIDATES, true, true);
        assertTrue(paidPage.contains("this run spent that index's allowance") && !paidPage.contains("ran keyless") && paidPage.contains("every result and every page passed the guard"), paidPage);
        assertFalse(FindOneBench.libraryWeb, "the bench is keyless unless the command says otherwise");
        String hold = FindOneBench.writeResults(out, FindOneBench.HOLDOUT, answers, "stand-in", FindOneBench.CANDIDATES);
        assertTrue(hold.contains("In the pool: 1 of 1.") && hold.contains("1 by a page that describes the video"), hold);
        assertFalse(hold.contains("cakes.example") || hold.contains("| H2") || hold.contains("answer00004"), "the holdout page names no case, no answer and no page that led to one: " + hold);
        assertFalse(Files.readString(out.resolve("counts-holdout.json")).contains("cakes.example"));
    }

    @Test void theRunnerSetsTheGuardForEachCaseAndKeepsWhatItLeftOutAndTheWebStageStopsAtThePool(@TempDir Path tmp) throws Exception {
        List<String> began = new ArrayList<>();
        FindOneBench.Pipeline p = new FindOneBench.Pipeline() {
            @Override public void begin(FindOneBench.Case c) { began.add(c.id()); }
            @Override public ObjectNode guarded() { ObjectNode o = M.createObjectNode().put("results_left_out", 2).put("pages_left_out", 1); o.putArray("left_out").addObject().put("where", "result").put("url", "https://www.reddit.com/r/tipofmytongue/comments/x1/").put("why", "a tipofmytongue thread"); return o; }
            @Override public ObjectNode name(String description) {
                ObjectNode o = M.createObjectNode().put("said", "").put("called", "candidates");
                ObjectNode args = o.putObject("args"); args.putArray("things").add("paper boat"); args.putArray("remembered").add("a small dog runs beside it").add("a brass band plays");
                return o;
            }
            @Override public Candidates.Result candidates(LibraryStore store, Candidates.Ask ask, Consumer<String> progress, long untilMs) {
                assertEquals(List.of("a small dog runs beside it", "a brass band plays"), ask.remembered(), "what the brain said is remembered reaches the pool as data");
                Candidates.Hit h = new Candidates.Hit("answer00009"); h.sources.add("web: blog.example"); h.pages.add("https://blog.example/paper-boats"); h.fit = 2;
                ObjectNode described = M.createObjectNode().put("asked", 6).put("answered", 5).put("pages_named", 30).put("pages_read", 12).put("pages_linking", 3).put("found", 1);
                return new Candidates.Result(List.of(h), Map.of(Candidates.DESCRIBED, 1), List.of("The web was asked 6 questions."), 0, 0, 0, 0, 1, "2025-01-02-0123456789abcdef", false, "the pool's text", described);
            }
            @Override public Sift.Result sift(LibraryStore store, Sift.Ask ask, Consumer<String> progress, long untilMs) { throw new IllegalStateException("the web stage stops at the pool"); }
            @Override public VideoRecall.Look look(LibraryStore store, String videoId, String what) { throw new IllegalStateException("never reached"); }
        };
        List<FindOneBench.Case> cases = List.of(new FindOneBench.Case("X1", FindOneBench.DEV, "crafts", "en", "2025-01-02", "https://www.reddit.com/r/tipofmytongue/comments/x1/", "a video where a paper boat crosses a flooded street while a small dog runs beside it", "answer00009"));
        Path out = tmp.resolve("out");
        FindOneBench.run(cases, new FindOneBench.Settings("http://127.0.0.1:1", "stand-in", out, FindOneBench.WEB, 1, 1, 5), p, s -> { });
        assertEquals(List.of("X1"), began);
        JsonNode run = M.readTree(Files.readString(FindOneBench.caseDir(out, FindOneBench.DEV, "X1").resolve("run.json")));
        assertFalse(run.has("error"), run.toString()); assertFalse(run.has("sift"));
        assertTrue(run.path("web_index").asBoolean(), "the web answered, and the record says so");
        assertEquals(5, run.path("candidates").path("described").path("answered").asInt()); assertEquals(12, run.path("candidates").path("described").path("pages_read").asInt());
        assertEquals(2, run.path("guard").path("results_left_out").asInt()); assertEquals("a tipofmytongue thread", run.path("guard").path("left_out").get(0).path("why").asText());
        JsonNode pool = M.readTree(Files.readString(FindOneBench.caseDir(out, FindOneBench.DEV, "X1").resolve("pool.json")));
        assertEquals("https://blog.example/paper-boats", pool.path("pool").get(0).path("pages").get(0).asText()); assertEquals(6, pool.path("described").path("asked").asInt());
        List<FindOneBench.Scored> scored = FindOneBench.score(out.resolve(FindOneBench.DEV), Map.of("X1", "answer00009"));
        assertTrue(scored.get(0).inPool() && scored.get(0).ledBy().equals("blog.example") && scored.get(0).leftOut() == 3, scored.get(0).toString());
        String page = FindOneBench.writeResults(out, FindOneBench.DEV, Map.of("X1", "answer00009"), "", "");
        assertTrue(page.contains("through the pages that describe a video alone, with YouTube itself not searched") && page.contains("| X1 | crafts | paper boat | 1 | 1 | web: blog.example | 0 | blog.example | 5 of 6 | 3 |"), page);
    }
}
