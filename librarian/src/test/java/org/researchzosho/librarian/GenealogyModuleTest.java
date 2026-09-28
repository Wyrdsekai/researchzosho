package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Genealogy as a module: on by default, which means available, and acting only on genealogy work somebody asked for. Which work is
 * genealogy's is told by where it came from (its own intake, a run asked for in genealogy mode, the claims and questions those filed),
 * never by the words of a question; a question whose words look like family history is told that genealogy mode is there.
 */
class GenealogyModuleTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String FAMILY = "Who were my great-grandfather's parents, and where did they farm?";

    static ObjectNode ask(String question) {
        ObjectNode a = M.createObjectNode(); a.put("question", question); a.putObject("patron").put("did", "person"); return a;
    }

    static Finding claim(LibraryStore store, String s, String p, String o, String writer, String why) throws Exception {
        Finding f = new Finding(store.nextFindingId(s + " " + p + " " + o), s + " " + p + " " + o + ".", List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.medium, writer, Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/" + s.replace(' ', '-'), "n/a", why)), List.of(), null, s + " " + p + " " + o + ".\n", new Finding.Triple(s, p, o), List.of());
        store.write(f);
        return f;
    }

    /** A folder and everything in it, gone. */
    static void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) { for (Path x : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(x); }
    }

    @Test
    void genealogyIsOnByDefaultAndJoinsARunOnlyWhenAskedFor(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        assertTrue(Profiles.isEnabled(store, "genealogy"), "on by default: available");
        assertTrue(Profiles.named("genealogy").joinsOnlyWhenAsked() && !Profiles.named("science").joinsOnlyWhenAsked() && !Profiles.named("software").joinsOnlyWhenAsked());
        Researcher.Ask plain = new Researcher.Ask(FAMILY, "depth", 0, List.of());
        assertTrue(Fields.forRun(store, plain).isEmpty(), "the words of a family question do not switch genealogy on");
        assertEquals(List.of("genealogy"), Fields.forRun(store, plain.withFields(List.of("genealogy"))).stream().map(Profile::name).toList(), "asking for it does");
        assertEquals(List.of("software"), Fields.forRun(store, new Researcher.Ask("Which open source projects read GEDCOM 7 files?", "broad", 0, List.of())).stream().map(Profile::name).toList(),
                "a field that recognises its own questions still does");
        Profiles.disable(store, "genealogy");
        assertTrue(Fields.forRun(store, plain.withFields(List.of("genealogy"))).isEmpty(), "a field switched off joins nothing");
        assertNull(Fields.suggest(store, FAMILY), "and is not suggested");
    }

    @Test
    void theProtocolTakesAFieldAndTellsAFamilyQuestionOnceThatGenealogyModeIsThere(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibraryProtocol p = new LibraryProtocol(store);
        Jobs jobs = new Jobs(store, j -> "");
        // no field: filed as ordinary research, and the result says what genealogy mode is and how to ask for it
        ObjectNode r = p.research(ask(FAMILY));
        JsonNode args = jobs.get(r.path("job_id").asText()).path("args");
        assertFalse(args.has("field"), args.toString());
        assertEquals("genealogy", args.path("suggested").path("field").asText(), args.toString());
        assertEquals("genealogy", r.path("suggestion").path("field").asText(), r.toString());
        assertTrue(r.path("suggestion").path("why").asText().startsWith("This looks like family history."), r.toString());
        assertEquals("send the same question again with field: \"genealogy\"", r.path("suggestion").path("how").asText());
        // once per question
        ObjectNode again = p.research(ask(FAMILY));
        assertFalse(again.has("suggestion"), "the same question is not told twice: " + again);
        // asked for: a run of genealogy, told nothing
        ObjectNode asked = ask(FAMILY); asked.put("field", "genealogy");
        ObjectNode g = p.research(asked);
        assertEquals("genealogy", g.path("field").asText());
        assertEquals("genealogy", jobs.get(g.path("job_id").asText()).path("args").path("field").asText());
        assertEquals("mcp-field", jobs.get(g.path("job_id").asText()).path("args").path("field_how").asText());
        assertFalse(g.has("suggestion"));
        // an ordinary question is told nothing
        assertFalse(p.research(ask("How were the Antikythera gears cut, and with what tools?")).has("suggestion"));
        // a field the library does not have, or has switched off, is refused with a sentence that says which there are
        ObjectNode unknown = ask(FAMILY); unknown.put("field", "astrology");
        ProtocolError e = assertThrows(ProtocolError.class, () -> p.research(unknown));
        assertTrue(e.getMessage().contains("has no field called \"astrology\"") && e.getMessage().contains("genealogy"), e.getMessage());
        Profiles.disable(store, "genealogy");
        ProtocolError off = assertThrows(ProtocolError.class, () -> p.research(asked));
        assertTrue(off.getMessage().contains("turned off") && off.getMessage().contains("researchzosho profile enable genealogy"), off.getMessage());
    }

    @Test
    void aRunOfAFieldIsWrittenInTheLedgerBeforeItsReportAndItsClaimsAreTheFieldsWork(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive();
        drive.criticWantsMore = false;
        List<String> seenWhenAdmitted = new CopyOnWriteArrayList<>();
        Researcher.Filed filed = Researcher.file(store, new Researcher(drive, drive, new ResearcherTest.FakeTools(), null, 2, store),
                new Researcher.Ask(FAMILY, "depth", 60, List.of()).withFields(List.of("genealogy")), "patron:person", "J-0007", "cli-flag");
        assertTrue(filed.admitted(), filed.reason());
        List<Fields.Row> rows = Fields.rows(store);
        assertEquals(1, rows.size());
        assertEquals(filed.investigationId(), rows.get(0).investigation());
        assertEquals("J-0007", rows.get(0).job());
        assertEquals("genealogy", rows.get(0).field());
        assertEquals("cli-flag", rows.get(0).how());
        assertEquals(Set.of("genealogy"), Fields.ofRun(store, filed.investigationId()));
        // the claims its review files are genealogy's work; a claim of an ordinary run is not
        Finding owned = claim(store, "Ann Ellis", "is the daughter of", "Tom Hale", "reviewer", "cited by " + filed.investigationId());
        Finding plain = claim(store, "Alphabet", "is the parent of", "Google", "reviewer", "cited by I-0999-ordinary");
        assertEquals(Set.of("genealogy"), Fields.ofClaim(store, owned));
        assertEquals(Set.of(), Fields.ofClaim(store, plain));
        Finding intake = claim(store, "Tom Hale", "born-in", "Leeds", "family-account", "the family's own account");
        assertEquals(Set.of("genealogy"), Fields.ofClaim(store, intake), "the family's own intake");
        assertTrue(Fields.holdsWork(store, Profiles.named("genealogy")), "a run of it is genealogy work in this library");
        // an ordinary run writes nothing into the ledger
        ResearcherTest.ScriptedDrive d2 = new ResearcherTest.ScriptedDrive(); d2.criticWantsMore = false;
        Researcher.file(store, new Researcher(d2, d2, new ResearcherTest.FakeTools(), null, 2, store), new Researcher.Ask(FAMILY, "depth", 60, List.of()), "patron:person", "J-0008", "asked");
        assertEquals(1, Fields.rows(store).size());
        // taken back, the run is ordinary again
        Fields.forget(store, filed.investigationId(), "genealogy");
        assertEquals(Set.of(), Fields.ofClaim(store, owned));
    }

    @Test
    void theCoreMapReadsKinshipWordsOnlyInGenealogysOwnClaimsAndGenealogysViewReadsThemEverywhere(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Hale", "died-on", "1901", "", "q")), List.of()), "file:///family/notes.txt", "an aunt");
        claim(store, "Alphabet", "is the parent of", "Google", "reviewer", "cited by I-0001-ordinary");
        claim(store, "Dropbox", "was founded", "2007", "reviewer", "cited by I-0001-ordinary");
        Fields.record(store, "I-0002-family", "J-0002", "genealogy", "genealogy-command");
        claim(store, "Ann Ellis", "is the daughter of", "Tom Hale", "reviewer", "cited by I-0002-family");
        claim(store, "Ann Ellis", "lives in", "Leeds", "reviewer", "cited by I-0002-family");
        Graph core = Graph.build(store);
        assertEquals("is the parent of", edge(core, "alphabet"), "an ordinary claim keeps its words");
        assertEquals("was founded", edge(core, "dropbox"));
        assertNotEquals("person", core.node("alphabet").kind());
        assertEquals("child-of", edge(core, "ann ellis", "tom hale"), "a family-history run's claim is read with genealogy's relations");
        assertEquals("lived-in", edge(core, "ann ellis", "leeds"));
        assertEquals("person", core.node("ann ellis").kind(), "and makes the person it names a person");
        Graph view = FamilyPeople.view(store);
        assertEquals("parent-of", edge(view, "alphabet"), "genealogy's own view reads every claim with its relations, as before");
        assertEquals("life-event", edge(view, "dropbox"));
        assertTrue(Fields.holdsModuleWork(store) && Files.isDirectory(store.root().resolve("family")), "the family read made the family folder");
    }

    @Test
    void theCoreListingOfWhatRestsOnASourceTakesTwoWaysRoundAsOneFactOnlyInGenealogysOwnWork(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        // ordinary claims that happen to use a relation's name the family also uses: each is the one way it was written
        Finding one = claim(store, "Pierre Curie", "married-to", "Marie Curie", "reviewer", "cited by I-0001-ordinary");
        claim(store, "Marie Curie", "married-to", "Pierre Curie", "reviewer", "cited by I-0001-ordinary");
        Evidence.Resting plain = Evidence.restingOn(store, "https://example.org/Pierre-Curie", "");
        assertEquals(List.of(one.id()), plain.alone().stream().map(Finding::id).toList(), "genealogy does not fold an ordinary claim: " + plain);
        // the family's own claims: the two ways round are one fact, which the other source gives too
        Finding his = claim(store, "Tom Hale", "married-to", "Ann Hale", "family-account", "the family's own account");
        claim(store, "Ann Hale", "married-to", "Tom Hale", "family-account", "the family's own account");
        Evidence.Resting family = Evidence.restingOn(store, "https://example.org/Tom-Hale", "");
        assertEquals(List.of(his.id()), family.backed().stream().map(Finding::id).toList(), family.toString());
    }

    private static String edge(Graph g, String from) { return edge(g, from, null); }
    private static String edge(Graph g, String from, String to) {
        for (Graph.Edge e : g.edges()) if (e.from().equals(from) && (to == null || e.to().equals(to)) && !e.predicate().equals("is filed under") && !e.predicate().equals("mentions")) return e.predicate();
        return null;
    }

    @Test
    void anOpenQuestionGenealogyFiledIsResearchedAsGenealogysAndAnOrdinaryOneIsNot(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.frontier("person me " + FamilyReset.FROM_TREE + Fields.mark("genealogy"), "Tom Hale (died 1901): who were the parents?");
        store.frontier("person me " + FamilyReset.FROM_TREE, "Ann Ellis (born 1880): who were the parents?");   // as older versions filed it
        store.frontier("person me", "Who were my great-grandfather's parents, and where did they farm?");
        store.frontier("person me", "How were the Antikythera gears cut?");
        var runs = Fields.runs(store);
        List<Frontier.Line> open = Frontier.read(store);
        assertEquals(List.of("genealogy", "genealogy", "", ""), open.stream().map(l -> Fields.ofLine(l, runs)).toList());
        assertEquals("person me " + FamilyReset.FROM_TREE, Fields.unmarked(open.get(0).kind()), "the mark is not part of what a person reads");
        assertEquals("person", open.get(0).type());
        // the explorer: a genealogy question runs as genealogy's, bundled only with genealogy's; the ordinary family-looking one runs as ordinary
        List<String[]> ran = new ArrayList<>();
        Crews.Researcher r = new Crews.Researcher() {
            @Override public String research(String question, String writer) { ran.add(new String[]{question, ""}); return "I-0100-x"; }
            @Override public String research(String question, List<String> subs, String writer, String field) { ran.add(new String[]{question, field}); return "I-0101-x"; }
        };
        Crews.explore(store, r, 4);
        assertEquals(4, ran.size(), ran.toString());
        assertEquals("genealogy", ran.get(0)[1]);
        assertEquals("genealogy", ran.get(1)[1], "an older line from the family tree is genealogy's too");
        assertEquals("", ran.get(2)[1], "a family-looking question nobody filed as genealogy is ordinary");
        String log = Files.readString(store.root().resolve("catalog").resolve("crews.log"));
        assertTrue(log.contains("suggestion: genealogy — This looks like family history."), log);
        assertEquals(1, log.split("suggestion: genealogy", -1).length - 1, "once");
    }

    @Test
    void whatTheNightlyResearchWritesInItsLogDoesNotCountAsTellingThePerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.frontier("person me", FAMILY);
        Crews.explore(store, new Crews.Researcher() {
            @Override public String research(String question, String writer) { return "I-0100-x"; }
            @Override public String research(String question, List<String> subs, String writer, String field) { return "I-0101-x"; }
        }, 4);
        assertTrue(Files.readString(store.root().resolve("catalog").resolve("crews.log")).contains("suggestion: genealogy"));
        assertFalse(Fields.seen(store, FAMILY, "genealogy"), "nobody reads the log");
        ObjectNode r = new LibraryProtocol(store).research(ask(FAMILY));
        assertEquals("genealogy", r.path("suggestion").path("field").asText(), "a program that sends the question is still told: " + r);
        assertNull(Fields.logged(store, FAMILY, "explorer"), "and the log says it once");
    }

    @Test
    void anOrdinaryAskIsNotFoldedIntoAQuestionGenealogyFiled(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.frontier("person me " + FamilyReset.FROM_TREE + Fields.mark("genealogy"), "Tom Hale (died 1901): who were the parents of Tom Hale of Leeds?");
        assertTrue(Frontier.demand(store, "Who were the parents of Tom Hale of Leeds?", "patron:x"), "filed as its own ordinary question");
        assertFalse(Frontier.demand(store, "Who were the parents of Tom Hale of Leeds?", "patron:x"), "and the same ordinary ask again is folded into that one");
    }

    /** How the older `genealogy research` wrote a person's question, the last of its three templates. */
    static final String OLD_COMMAND_QUESTION = "Tom Hale (died 1901): answer each of these questions on its own, and say for each whether it is answered, not found, or in conflict between records. 1. Who were the parents of Tom Hale?";

    /** A research job an older version finished, written where the job ledger keeps it. */
    static void finishedJob(LibraryStore store, String id, String question, String mode, String report) throws Exception {
        ObjectNode j = M.createObjectNode();
        j.put("job_id", id); j.put("kind", "research"); j.put("state", "done"); j.put("patron", "person");
        j.put("queued_at", "2026-09-20T01:00:00Z"); j.put("ended_at", "2026-09-20T01:30:00Z");
        j.putObject("args").put("question", question).put("mode", mode);
        j.put("result", "investigation " + report + " (3 claims)"); j.put("is_error", false);
        Path month = new Jobs(store, x -> "").doneDir().resolve("2026-09");
        Files.createDirectories(month);
        Files.writeString(month.resolve(id + ".json"), j.toString());
    }

    @Test
    void aLibraryFromBeforeRunsWereRecordedKeepsTheRunsGenealogyMadeAndNotTheOnesItsWordsLookLike(@TempDir Path tmp) throws Exception {
        for (boolean on : new boolean[]{true, false}) {
            LibraryStore store = new LibraryStore(tmp.resolve(on ? "on" : "off")); store.init();
            if (!on) Profiles.disable(store, "genealogy");
            FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Hale", "died-on", "1901", "", "q")), List.of()), "file:///family/notes.txt", "an aunt");
            deleteTree(store.root().resolve("family"));   // as an older version left it: no family folder
            for (String[] inv : new String[][]{{"I-0001-tom-hale", "Tom Hale (died 1901): answer each of these questions on its own"}, {"I-0002-hale-family", "What is the family history of the Hales of Leeds?"},
                    {"I-0003-gears", "How were the Antikythera gears cut?"}, {"I-0004-tom-hale-again", "Tom Hale (died 1901): who were the parents?"}, {"I-0005-register", "Where is the register of the chapel in Leeds?"}})
                store.write(new Investigation(inv[0], inv[1], Finding.State.accepted, "model:research", Instant.now().toString(), List.of(), List.of(), "Notes.\n"));
            // genealogy research filed I-0001; a person asked the questions of I-0002 and I-0004 in their own words
            finishedJob(store, "J-0001", OLD_COMMAND_QUESTION, "depth", "I-0001-tom-hale");
            finishedJob(store, "J-0002", "What is the family history of the Hales of Leeds?", "depth", "I-0002-hale-family");
            finishedJob(store, "J-0004", "Tom Hale (died 1901): who were the parents?", "depth", "I-0004-tom-hale-again");
            // the research log of an older version: the runner said genealogy's rules joined the run, and the job filed I-0005
            Files.writeString(store.root().resolve("catalog").resolve("crews.log"), "2026-09-20T01:00:00Z\tresearch\t0ms\tfield: genealogy — its rules join this run\n"
                    + "2026-09-20T01:00:01Z\tresearch\t0ms\tplan: 3 sub-question(s)\n2026-09-20T01:30:00Z\tresearch J-0005\t0ms\tdone → I-0005-register\n"
                    + "2026-09-20T02:00:00Z\tresearch J-0006\t0ms\tdone → I-0003-gears\n");
            // predicates.md as `profile enable genealogy` wrote it, and one relation the owner wrote
            for (Vocabulary.Term t : new GenealogyProfile().predicates()) Graph.predicate(store, t.slug(), t.description(), t.also());
            Graph.predicate(store, "funded-by", "is funded by", List.of("sponsored by"));
            Graph.setKind(store, "Marie Curie", "person");
            Fields.Migrated m = Fields.migrate(store);
            assertNotNull(m);
            Vocabulary left = Vocabulary.read(Graph.predicatesFile(store));
            assertNull(left.get("parent-of"), "the relations turning genealogy on wrote go back out, genealogy on or off");
            assertNotNull(left.get("funded-by"), "the owner's own stays");
            assertNull(Fields.migrate(store), "once");
            if (!on) {
                assertTrue(Fields.runs(store).isEmpty(), "nothing is recorded where genealogy is off: " + Fields.runs(store));
                assertFalse(Files.exists(store.root().resolve("family")), "and genealogy makes nothing");
                continue;
            }
            assertEquals(Set.of("I-0001-tom-hale", "I-0005-register"), Fields.runs(store).keySet(), "the run genealogy research filed and the logged one; the ones only their words look like stay ordinary");
            assertEquals(1, m.fromLog());
            assertEquals(1, m.byCommand());
            assertTrue(Fields.rows(store).stream().anyMatch(r -> r.investigation().equals("I-0001-tom-hale") && r.how().equals("backfill-command") && r.job().equals("J-0001")), Fields.rows(store).toString());
            assertTrue(Files.isDirectory(store.root().resolve("family")));
            assertTrue(String.join(" ", m.notes()).contains("Marie Curie"), "a person nothing of the family names is listed for the owner: " + m.notes());
            assertEquals("person", Graph.kindOf(Vocabulary.read(Graph.nodesFile(store)).get("marie curie").description()), "and nothing is changed");
        }
    }

    @Test
    void theNightlyRunsOfTheQuestionsGenealogyResearchLeftWaitingAndTheRunsTheQuestionsPageSentAreGenealogysAfterTheUpgrade(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Hale", "died-on", "1901", "", "q")), List.of()), "file:///family/notes.txt", "an aunt");
        deleteTree(store.root().resolve("family"));   // as an older version left it
        for (String[] inv : new String[][]{{"I-0010-tom-hale", "Tom Hale (died 1901): answer each of these questions on its own"}, {"I-0011-gears", "How were the Antikythera gears cut?"},
                {"I-0012-ann-hart", "Ann Hart (died 1920): answer each of these questions on its own"}})
            store.write(new Investigation(inv[0], inv[1], Finding.State.accepted, "model:research", Instant.now().toString(), List.of(), List.of(), "Notes.\n"));
        Finding mother = claim(store, "Ann Hart", "is the mother of", "Tom Hale", "model:research", "cited by I-0010-tom-hale");
        // the waiting list an older `genealogy research` wrote, with no field mark: the nightly research took Tom Hale's line and marked the report
        // it filed; an ordinary question it took the same night; a waiting line a person dropped
        String waiting = "Tom Hale (died 1901): answer each of these questions on its own, and say for each whether it is answered, not found, or in conflict between records. 1. Who were the parents of Tom Hale?";
        store.frontier("person me " + FamilyReset.FROM_TREE, waiting);
        store.frontier("asked ×2 patron:x", "How were the Antikythera gears cut?");
        store.frontier("person me " + FamilyReset.FROM_TREE, "Ann Ellis (died 1930): what records exist for this person, and what did they do in life?");
        Frontier.markExplored(store, waiting, "I-0010-tom-hale");
        Frontier.markExplored(store, "How were the Antikythera gears cut?", "I-0011-gears");
        Frontier.markExplored(store, "Ann Ellis (died 1930): what records exist for this person, and what did they do in life?", "(dropped by patron:x)");
        // the older Questions page sent a waiting line as a broad run: one finished, one still waits
        finishedJob(store, "J-0012", OLD_COMMAND_QUESTION.replace("Tom Hale", "Ann Hart"), "broad", "I-0012-ann-hart");
        Jobs jobs = new Jobs(store, j -> "");
        String queued = jobs.submit("research", "person", ask(OLD_COMMAND_QUESTION.replace("Tom Hale", "Tom Ellis")).put("mode", "broad"));
        Fields.Migrated m = Fields.migrate(store);
        assertEquals(Set.of("I-0010-tom-hale", "I-0012-ann-hart"), Fields.runs(store).keySet(), "the runs of genealogy's own questions, and nothing the same night researched besides");
        assertEquals(1, m.fromQuestions());
        assertEquals(1, m.byCommand());
        assertEquals("genealogy", jobs.get(queued).path("args").path("field").asText(), "a waiting run the Questions page sent runs in genealogy mode");
        assertEquals(Set.of("genealogy"), Fields.ofClaim(store, mother), "the claims that run filed are genealogy's");
        assertFalse(Fields.shownTo(store, List.of()).test("I-0010-tom-hale"), "and an ordinary run is not shown its searches");
        assertTrue(Fields.shownTo(store, List.of()).test("I-0011-gears"));
    }

    @Test
    void runsAnEarlierBuildRecordedByTheWordsOfTheirTitlesAreOrdinaryAgain(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Fields.record(store, "I-0002-hale-family", "", "genealogy", "backfill-rule");
        Fields.record(store, "I-0005-register", "", "genealogy", "backfill-log");
        Fields.record(store, "I-0007-ellis", "J-0007", "genealogy", "backfill-rule");   // a waiting run that then ran in genealogy mode
        Jobs jobs = new Jobs(store, j -> "");
        ObjectNode words = ask(FAMILY); words.put("field", "genealogy"); words.put("field_how", "backfill-rule");
        String waiting = jobs.submit("research", "person", words);
        ObjectNode asked = ask(FAMILY); asked.put("field", "genealogy"); asked.put("field_how", "cli-flag");
        String flagged = jobs.submit("research", "person", asked);
        Files.createDirectories(Fields.marker(store).getParent());
        Files.writeString(Fields.marker(store), "2026-09-21\tan earlier build upgraded this library\n");
        Fields.Migrated m = Fields.migrate(store);
        assertNotNull(m, "the earlier build's word rule is taken back although the upgrade ran before");
        assertEquals(Set.of("I-0005-register", "I-0007-ellis"), Fields.runs(store).keySet(), "a run by title words goes; a logged run and a run that ran in genealogy mode stay");
        assertFalse(jobs.get(waiting).path("args").has("field"), "waiting research the words put into genealogy mode runs as ordinary research");
        assertEquals("genealogy", jobs.get(flagged).path("args").path("field").asText(), "a run somebody asked for keeps its field");
        assertTrue(String.join(" ", m.notes()).contains("taken back"), m.notes().toString());
        assertNull(Fields.migrate(store), "and it is done once");
    }

    @Test
    void aFamilyHistoryRunThatWasStoppedOrEndedWithoutAReportGivesItsFieldToNoOtherRunAtTheUpgrade(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        for (String[] inv : new String[][]{{"I-0008-hobbit", "When was The Hobbit published?"}, {"I-0010-gears", "How were the Antikythera gears cut?"},
                {"I-0011-register", "Where is the register of the chapel in Leeds?"}})
            store.write(new Investigation(inv[0], inv[1], Finding.State.accepted, "model:research", Instant.now().toString(), List.of(), List.of(), "Notes.\n"));
        claim(store, "The Hobbit", "was published", "1937", "reviewer", "cited by I-0008-hobbit");
        // the research log of an older version, one run at a time: a family-history run a person stopped, then an ordinary run; a
        // family-history run that ended without a report after its plan, then an ordinary run; then a family-history run that filed its report
        String log = String.join("\n",
                "2026-09-20T01:00:00Z\tresearch\t0ms\tfield: genealogy — its rules join this run",
                "2026-09-20T01:00:05Z\tresearch\t0ms\tplan: 4 perspective(s): a · b · c · d",
                "2026-09-20T01:00:06Z\tresearch\t0ms\tplan: 4 sub-question(s), 1 worker(s), no limits",
                "2026-09-20T01:05:00Z\tresearch\t0ms\tstopped: a person stopped this run; ending at this turn",
                "2026-09-20T01:06:00Z\tresearch\t0ms\tplan: 3 perspective(s): a · b · c",
                "2026-09-20T01:06:01Z\tresearch\t0ms\tplan: 3 sub-question(s), 1 worker(s), no limits",
                "2026-09-20T01:30:00Z\tresearch J-0008\t0ms\tdone → I-0008-hobbit",
                "2026-09-20T02:00:00Z\tresearch\t0ms\tfield: genealogy — its rules join this run",
                "2026-09-20T02:00:01Z\tresearch\t0ms\tplan: 3 sub-question(s), 1 worker(s), no limits",
                "2026-09-20T02:10:00Z\tresearch\t0ms\tformat asked for: a table",
                "2026-09-20T02:10:01Z\tresearch\t0ms\tplan: 2 sub-question(s), 1 worker(s), no limits",
                "2026-09-20T02:30:00Z\tresearch J-0010\t0ms\tdone → I-0010-gears",
                "2026-09-20T03:00:00Z\tresearch\t0ms\tfield: genealogy — its rules join this run",
                "2026-09-20T03:00:01Z\tresearch\t0ms\tplan: 3 sub-question(s), 1 worker(s), no limits",
                "2026-09-20T03:30:00Z\tresearch J-0011\t0ms\tdone → I-0011-register") + "\n";
        Files.writeString(store.root().resolve("catalog").resolve("crews.log"), log);
        Fields.Migrated m = Fields.migrate(store);
        assertEquals(Set.of("I-0011-register"), Fields.runs(store).keySet(), "only the run the field's line belongs to");
        assertEquals(1, m.fromLog());
        assertEquals("was published", edge(Graph.build(store), "the hobbit"), "the ordinary run's claim stays ordinary");
    }

    /** A research job an older version ran from {@code started} to {@code ended}, written where the job ledger keeps it. */
    static void ranJob(LibraryStore store, String id, String question, String report, String started, String ended) throws Exception {
        finishedJob(store, id, question, "broad", report);
        Path file = new Jobs(store, x -> "").doneDir().resolve("2026-09").resolve(id + ".json");
        ObjectNode j = (ObjectNode) M.readTree(Files.readString(file));
        j.put("queued_at", started); j.put("started_at", started); j.put("ended_at", ended);
        Files.writeString(file, j.toString());
    }

    @Test
    void aFieldLineWrittenWhileTwoRunsRanAtOnceGivesItsFieldToNeitherAtTheUpgrade(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        for (String[] inv : new String[][]{{"I-0005-tom-hale", "Tom Hale (died 1901): who were the parents?"}, {"I-0006-gears", "How were the Antikythera gears cut?"},
                {"I-0007-register", "Where is the register of the chapel in Leeds?"}})
            store.write(new Investigation(inv[0], inv[1], Finding.State.accepted, "model:research", Instant.now().toString(), List.of(), List.of(), "Notes.\n"));
        // two job workers: the ordinary run J-0006 and the family-history run J-0005 ran at the same time, and the ordinary one ended first;
        // then J-0007 ran alone
        ranJob(store, "J-0006", "How were the Antikythera gears cut?", "I-0006-gears", "2026-09-20T01:00:00Z", "2026-09-20T01:30:01Z");
        ranJob(store, "J-0005", "Tom Hale (died 1901): who were the parents?", "I-0005-tom-hale", "2026-09-20T01:00:02Z", "2026-09-20T02:00:01Z");
        ranJob(store, "J-0007", "Where is the register of the chapel in Leeds?", "I-0007-register", "2026-09-20T03:00:00Z", "2026-09-20T03:30:01Z");
        String log = String.join("\n",
                "2026-09-20T01:00:01Z\tresearch\t0ms\tplan: 3 sub-question(s), 1 worker(s), no limits",
                "2026-09-20T01:00:03Z\tresearch\t0ms\tfield: genealogy — its rules join this run",
                "2026-09-20T01:00:04Z\tresearch\t0ms\tplan: 4 sub-question(s), 1 worker(s), no limits",
                "2026-09-20T01:30:00Z\tresearch J-0006\t0ms\tdone → I-0006-gears",
                "2026-09-20T02:00:00Z\tresearch J-0005\t0ms\tdone → I-0005-tom-hale",
                "2026-09-20T03:00:01Z\tresearch\t0ms\tfield: genealogy — its rules join this run",
                "2026-09-20T03:00:02Z\tresearch\t0ms\tplan: 3 sub-question(s), 1 worker(s), no limits",
                "2026-09-20T03:30:00Z\tresearch J-0007\t0ms\tdone → I-0007-register") + "\n";
        Files.writeString(store.root().resolve("catalog").resolve("crews.log"), log);
        Fields.Migrated m = Fields.migrate(store);
        assertEquals(Set.of("I-0007-register"), Fields.runs(store).keySet(), "a field line is a run's only when no other run ran beside it: " + Fields.rows(store));
        assertEquals(1, m.fromLog());
    }

    @Test
    void researchGenealogyResearchFiledInAnOlderVersionRunsInGenealogyModeAndNothingElseDoes(@TempDir Path tmp) throws Exception {
        for (boolean on : new boolean[]{true, false}) {
            LibraryStore store = new LibraryStore(tmp.resolve(on ? "on" : "off")); store.init();
            if (!on) Profiles.disable(store, "genealogy");
            FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Ellis", "died-on", "1950", "", "q"),
                    new FamilyAccount.Fact("Ann Hart", "parent-of", "Tom Ellis", "", "q")), List.of()), "file:///family/notes.txt", "an aunt");
            deleteTree(store.root().resolve("family"));   // as an older version left it
            Jobs jobs = new Jobs(store, j -> "");
            // as the older `genealogy research` filed them, with no mark: one was running when the service stopped for the update
            String byCommand = jobs.submit("research", "person", ask(OLD_COMMAND_QUESTION.replace("Tom Hale", "Tom Ellis")).put("mode", "depth"));
            String early = jobs.submit("research", "person", ask("Tom Ellis (died 1950): what records exist for this person, and what did they do in life? Who were their parents?").put("mode", "depth"));
            // a person's own questions: family words, and the name of somebody the family's own texts are about
            String byWords = jobs.submit("research", "person", ask(FAMILY));
            String byName = jobs.submit("research", "person", ask("Tom Ellis (died 1950): who were the parents of Tom Ellis?").put("mode", "depth"));
            String ordinary = jobs.submit("research", "person", ask("How were the Antikythera gears cut?"));
            ObjectNode running = jobs.get(byCommand); running.put("state", "running");
            Files.writeString(jobs.activeDir().resolve(byCommand + ".json"), running.toString());
            Fields.Migrated m = Fields.migrate(store);
            for (String words : List.of(byWords, byName, ordinary)) assertFalse(jobs.get(words).path("args").has("field"), "words never put a run into genealogy mode: " + jobs.get(words));
            if (!on) {
                assertFalse(jobs.get(byCommand).path("args").has("field"), "nothing is changed where genealogy is off");
                assertTrue(m.notes().stream().noneMatch(n -> n.contains("research run")), m.notes().toString());
                continue;
            }
            assertEquals("genealogy", jobs.get(byCommand).path("args").path("field").asText(), "a running run genealogy research filed restarts in genealogy mode");
            assertEquals("backfill-command", jobs.get(byCommand).path("args").path("field_how").asText());
            assertEquals("genealogy", jobs.get(early).path("args").path("field").asText(), "and so does one an earlier template wrote");
            assertTrue(String.join(" ", m.notes()).contains("2 research runs that a field's own command filed"), m.notes().toString());
        }
    }

    /** predicates.md as `researchzosho profile enable genealogy` wrote it in 0.4.6, the release whose guide told people to run it. */
    static final String PREDICATES_046 = "# Graph predicates — the relations the findings assert\n\n"
            + "One per line: `- <slug> — <description> | also: other wordings`. A finding's triple predicate is\n"
            + "resolved against these before it becomes an edge; unknown predicates are kept as written.\n\n"
            + "- parent-of — is a parent of | also: father of, mother of, parent\n"
            + "- child-of — is a child of | also: son of, daughter of, child\n"
            + "- married-to — was married to | also: spouse of, husband of, wife of, married, spouse\n"
            + "- born-in — was born in | also: birthplace, born at, born\n"
            + "- died-in — died in | also: place of death, died at, died\n"
            + "- lived-in — resided in | also: resided in, residence, lived at\n"
            + "- migrated-to — emigrated or immigrated to | also: emigrated to, immigrated to, moved to, arrived in\n"
            + "- buried-in — was buried in | also: burial, interred in\n"
            + "- occupation — worked as | also: worked as, profession, was a\n";

    @Test
    void aLibraryUpgradedFrom046LosesTheRelationsTurningGenealogyOnWroteWhetherOrNotItHoldsFamilyWork(@TempDir Path tmp) throws Exception {
        for (boolean family : new boolean[]{false, true}) {
            LibraryStore store = new LibraryStore(tmp.resolve(family ? "family" : "plain")); store.init();
            Files.createDirectories(Graph.dir(store));
            Files.writeString(Graph.predicatesFile(store), PREDICATES_046 + "- funded-by — is funded by | also: sponsored by\n", StandardCharsets.UTF_8);
            claim(store, "Alphabet", "is the parent of", "Google", "model:research", "cited by I-0001-ordinary");
            claim(store, "Grace Hopper", "was a", "rear admiral", "model:research", "cited by I-0001-ordinary");
            claim(store, "Many engineers", "moved to", "Silicon Valley", "model:research", "cited by I-0001-ordinary");
            if (family) claim(store, "Tom Hale", "died-on", "1901", "family-account", "the family's own account");
            Fields.Migrated m = Fields.migrate(store);
            assertNotNull(m);
            Vocabulary left = Vocabulary.read(Graph.predicatesFile(store));
            assertNull(left.get("parent-of"), (family ? "with" : "without") + " family work, the relations 0.4.6 wrote go back out: " + Files.readString(Graph.predicatesFile(store)));
            assertNull(left.get("occupation"));
            assertNotNull(left.get("funded-by"), "the owner's own relation stays");
            assertTrue(String.join(" ", m.notes()).contains("were taken out"), m.notes().toString());
            Graph core = Graph.build(store);
            assertEquals("is the parent of", edge(core, "alphabet"), "an ordinary claim keeps its words after the upgrade");
            assertEquals("was a", edge(core, "grace hopper"));
            assertEquals("moved to", edge(core, "many engineers"));
        }
    }

    @Test
    void aRelationTheOwnerChangedIsNotTakenForTheOnesGenealogyWrote(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Files.createDirectories(Graph.dir(store));
        // three of 0.4.6's nine, and one of them with a word the owner added: not a block turning genealogy on wrote
        Files.writeString(Graph.predicatesFile(store), "# My relations\n\n- parent-of — is a parent of | also: father of, mother of, parent\n- born-in — was born in | also: birthplace, born at, born\n"
                + "- occupation — worked as | also: worked as, profession, was a, job\n", StandardCharsets.UTF_8);
        assertNotNull(Fields.migrate(store));
        Vocabulary left = Vocabulary.read(Graph.predicatesFile(store));
        assertNotNull(left.get("parent-of"));
        assertNotNull(left.get("occupation"));
    }

    @Test
    void theGenealogyCommandsFileTheirRunsAndQuestionsAsGenealogys(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Ellis", "died-on", "1950", "", "q"),
                new FamilyAccount.Fact("Ann Hart", "parent-of", "Tom Ellis", "", "q"), new FamilyAccount.Fact("Ann Hart", "died-on", "1920", "", "q")), List.of()), "file:///family/notes.txt", "an aunt");
        for (String who : List.of("Tom Ellis", "Ann Hart")) FamilyIdentity.find(store, FamilyPeople.view(store), who, q -> List.of(), null);   // looked up before: nothing goes out
        PrintStream was = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try { new GenealogyProfile().cli(store, new String[]{"researchzosho", "genealogy", "research", "--now", "1", "--queue"}); } finally { System.setOut(was); }
        List<ObjectNode> active = new Jobs(store, j -> "").active();
        assertEquals(1, active.size(), active.toString());
        assertEquals("genealogy", active.get(0).path("args").path("field").asText(), "the run is genealogy's by the command, not by its words");
        assertEquals("genealogy-command", active.get(0).path("args").path("field_how").asText());
        assertFalse(active.get(0).path("args").has("suggested"), "an explicit genealogy command is never told genealogy mode is there");
        var runs = Fields.runs(store);
        List<Frontier.Line> waiting = Frontier.read(store).stream().filter(l -> l.open() && l.kind().contains(FamilyReset.FROM_TREE)).toList();
        assertFalse(waiting.isEmpty());
        for (Frontier.Line l : waiting) assertEquals("genealogy", Fields.ofLine(l, runs), l.toString());
        assertTrue(waiting.stream().allMatch(l -> l.kind().contains(Fields.mark("genealogy").strip())), waiting.toString());
    }
}
