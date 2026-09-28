package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;
import org.researchzosho.tools.RecordSearchTool;
import org.researchzosho.tools.Tool;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The owner's rule for the genealogy module, read from what an ordinary library shows: with genealogy on (the default) an ordinary
 * library, ordinary questions and ordinary claims look exactly as they do with genealogy switched off. Library A is
 * {@code profiles: science, software}; library B is the default. Both hold the same ordinary material, written the same way; every
 * surface is taken from both and compared text for text, with the dates and the one-time form keys masked.
 *
 * <p>Every suggestion goes through {@link Fields#suggest}. With suggestions off, B equals A byte for byte; with them on, B differs from
 * B-with-them-off only in lines that carry the suggestion's own sentence, and only for the question that looks like family history; a
 * surface that is JSON differs only in the suggestion's own keys, and the family question is never filed in genealogy mode. The
 * genealogy box on the research page is genealogy's way in: it is the one thing B shows that A does not, and it is checked on its own.
 *
 * <p>Before this rule was built (9405bf9), the comparison failed on the graph's edges and kinds, library_map, the vault, the family
 * question's prompts and log, the menu of every page, and the absolute checks on the chat, the extraction prompt and the search log.
 */
class GenealogyIsolationDifferentialTest {

    static final ObjectMapper M = new ObjectMapper();

    /** Ordinary claims whose relations genealogy's vocabulary would read as family relations, as a research run's review files them. */
    static final String[][] CLAIMS = {
            {"Alphabet", "is the parent of", "Google"},
            {"Dropbox", "was founded", "2007"},
            {"The Hobbit", "was published", "1937"},
            {"Marie Curie", "was awarded", "the Nobel Prize in Physics"},
            {"Marie Curie", "born", "Warsaw"},
            {"Ada Lovelace", "died", "London"},
            {"Linus Torvalds", "lives in", "Portland"},
            {"Grace Hopper", "works as", "a computer scientist"},
            {"Grace Hopper", "was a", "rear admiral"},
            {"The universe", "age", "13.8 billion years"},
            {"Survey respondents", "gender", "self-reported"},
            {"Mozilla", "sponsor of", "the Rust project"},
            {"The notary", "witness to", "the treaty"},
            {"Many engineers", "moved to", "Silicon Valley"},
            {"Emigrants", "arrived in", "New York"},
            {"A daughter cell", "child", "the mother cell"},
            {"Pierre Curie", "was married to", "Marie Curie"},
            {"Kernel maintainers", "has child", "the patch series"},
            {"Albert Einstein", "was appointed", "professor at Prague"},
            {"Wikipedia", "is known for", "its volunteer editors"},
            {"CERN", "is maintained by", "its member states"},
            // the wordings genealogy reads as names and families (0.5.0): an ordinary claim keeps its own words
            {"Tom Hale", "pen name", "T. H. Hart"},
            {"Istanbul", "formerly", "Constantinople"},
            {"Norway", "member of", "NATO"},
            {"The Ellis Quartet", "also known as", "the Ellises"},
            // a person's other name of one word, and one with a comma (nodes.md below): an ordinary claim reads them as it always did
            {"Lovelace", "wrote", "the first published algorithm"},
            {"Lovelace, Ada", "corresponded with", "Charles Babbage"},
    };

    /** a–f: ordinary questions; g: a question that looks like family history. */
    static final List<String> QUESTIONS = List.of(
            "How were the Antikythera gears cut?",
            "Who founded Dropbox, and in which year?",
            "Marie Curie: what did she discover, and what was she awarded for it?",
            "How does Alphabet own and run Google?",
            "Where does Linus Torvalds live and work today?",
            "How does the Rust project get its money?",
            "Who were my great-grandfather's parents, and where did they farm?");

    static LibraryStore fixture(Path root, boolean genealogyOn) throws Exception {
        LibraryStore store = new LibraryStore(root); store.init();
        if (!genealogyOn) Profiles.disable(store, "genealogy");
        Files.createDirectories(Graph.dir(store));
        // the owner's own vocabulary and notes, as written by hand
        Files.writeString(Graph.predicatesFile(store), "# My relations\n\n- occupation — worked as | also: profession\n- lived-in — resided in\n", StandardCharsets.UTF_8);
        Files.writeString(Graph.nodesFile(store), "# My nodes\n\nThese are my own notes about the graph: CERN is a lab.\n\n- cern — organisation, lab: CERN\n"
                + "- ada lovelace — person: Ada Lovelace | also: Lovelace, \"Lovelace, Ada\"\n", StandardCharsets.UTF_8);
        store.write(new Investigation("I-0001-notes", "Notes on the history of computing and science", Finding.State.accepted, "model:research", Instant.now().toString(), List.of(), List.of(), "Notes.\n"));
        int n = 0;
        for (String[] c : CLAIMS) {
            String line = c[0] + " " + c[1] + " " + c[2] + ".";
            store.write(new Finding(String.format("F-%04d-claim", ++n), line, List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                    Finding.Confidence.medium, "reviewer", "2026-09-01T00:00:00Z", "2026-09-01", Finding.Volatility.stable, "",
                    List.of(new Finding.Source("https://example.org/page-" + n, "n/a", "cited by I-0001-notes")), List.of(), null, line + "\n",
                    new Finding.Triple(c[0], c[1], c[2]), List.of()));
        }
        Graph.setKind(store, "Marie Curie", "person");   // the owner marked one public figure a person by hand
        store.frontier("gap person", "Who measured the tooth profiles of the Antikythera gears first?");
        SearchLog.add(store, List.of(new SearchLog.Entry("2026-09-01", QUESTIONS.get(2), "web", "Marie Curie discovery", 0, 0, 3, "I-0001-notes", SearchLog.OK)));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    /** The dates, times, one-time keys and temporary paths that differ between two libraries made a moment apart. */
    static String mask(String s, Path root) {
        return s.replace(root.toString(), "<root>")
                .replaceAll("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z?", "<time>")
                .replaceAll("\\d{4}-\\d{2}-\\d{2}", "<date>")
                .replaceAll("name=\"once\" value=\"[0-9a-f]+\"", "name=\"once\" value=\"<once>\"")
                .replaceAll("\\b\\d+ (second|minute|hour|day)s? ago\\b", "<ago>")
                .replaceAll("lib_[0-9a-f]{6,}", "<lib>");
    }

    /** A drive that records every prompt and every tool list it is shown, and answers as the scripted one does. */
    static final class Recording extends ResearcherTest.ScriptedDrive {
        final List<String> seen = new CopyOnWriteArrayList<>();
        @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
            ObjectNode r = super.chat(history, tools, maxTokens, toolChoice);
            StringBuilder b = new StringBuilder("CHAT tools=" + names(tools) + "\n");
            for (JsonNode m : history) if (!"assistant".equals(m.path("role").asText()) && !"tool".equals(m.path("role").asText())) b.append(m.path("role").asText()).append(": ").append(m.path("content").asText()).append('\n');
            seen.add(b.toString());
            return r;
        }
        @Override public String classify(ArrayNode messages, int maxTokens) {
            seen.add("CLASSIFY " + messages.get(messages.size() - 1).path("content").asText());
            return super.classify(messages, maxTokens);
        }
    }

    /** Every surface of one library, by name. */
    static Map<String, String> surfaces(LibraryStore store, Path tmp) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        Path root = store.root();
        // the graph: every edge with its relation, every node with its kind
        Graph g = Graph.build(store);
        List<String> edges = new ArrayList<>(), nodes = new ArrayList<>();
        for (Graph.Edge e : g.edges()) edges.add(e.from() + " -" + e.predicate() + "-> " + e.to());
        for (Graph.Node x : g.nodes()) nodes.add(x.id() + " [" + x.kind() + "] " + x.label());
        edges.sort(null); nodes.sort(null);
        out.put("graph edges", String.join("\n", edges));
        out.put("graph nodes", String.join("\n", nodes));
        // library_map around a few names
        LibraryProtocol p = new LibraryProtocol(store);
        for (String focus : List.of("Alphabet", "Marie Curie", "Grace Hopper", "Pierre Curie")) {
            ObjectNode a = M.createObjectNode(); a.put("focus", focus); a.put("depth", 2); a.putObject("patron").put("did", "person");
            out.put("library_map " + focus, mask(p.map(a).toString(), root));
        }
        // the vault's notes
        Path vault = tmp.resolve(root.getFileName() + "-vault");
        Vault.generate(store, vault);
        StringBuilder v = new StringBuilder();
        try (Stream<Path> s = Files.walk(vault)) {
            for (Path f : s.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".md")).sorted().toList())
                v.append("== ").append(vault.relativize(f)).append('\n').append(Files.readString(f, StandardCharsets.UTF_8)).append('\n');
        }
        out.put("vault", mask(v.toString(), root));
        // what each question's run is shown: the library's block, the planner, every worker, the critic, the writer, and the tools
        for (int i = 0; i < QUESTIONS.size(); i++) {
            String q = QUESTIONS.get(i);
            String label = String.valueOf((char) ('a' + i));
            out.put("known " + label, mask(Researcher.known(store, q), root));
            Recording drive = new Recording();
            List<String> log = new CopyOnWriteArrayList<>();
            new Researcher(drive, drive, new ResearcherTest.FakeTools(), log::add, 2, store).run(new Researcher.Ask(q, "depth", 60, List.of()), Researcher.known(store, q));
            List<String> seen = new ArrayList<>(drive.seen); seen.sort(null);
            out.put("run " + label + " prompts", mask(String.join("\n----\n", seen), root));
            // the times, and the pace of the drive (a turn slower than the run's own, and back), differ between two runs made a moment apart
            List<String> lines = new ArrayList<>(log.stream().filter(l -> !l.contains(" ms") && !l.matches(".*\\d+(\\.\\d+)?s\\b.*") && !l.startsWith("drive slowed") && !l.startsWith("drive recovered")).toList());
            lines.sort(null);   // the workers finish in any order
            out.put("run " + label + " log", mask(String.join("\n", lines), root));
        }
        // the chat: what the Librarian is told and which tools it is given
        Librarian lib = new Librarian(store, new ResearcherTest.ScriptedDrive(), Librarian.person(), Librarian.Session.open(store));
        out.put("chat system prompt", mask(lib.systemPrompt(), root));
        out.put("chat tools", Librarian.tools(store).toString());
        // the extraction prompts: the review of the ordinary run, and the triple of an ordinary claim
        out.put("review extraction prompt", LibrarianReview.extractPrompt("A report about the history of computing.\n", LibrarianReview.extractionRule(store, Fields.ofRun(store, "I-0001-notes"))));
        Finding first = store.finding("F-0001-claim");
        out.put("triple prompt", Triples.prompt(first.body(), LibrarianReview.extractionRule(store, Fields.ofClaim(store, first))));
        // the pages
        Explain.DRIVES = () -> null;
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + d.port();
            for (String path : List.of("/", "/research", "/research?q=" + URLEncoder.encode(QUESTIONS.get(6), StandardCharsets.UTF_8), "/questions", "/inbox", "/subjects"))
                out.put("page " + path, mask(PagesTest.get(c, base + path, null).body(), root).replaceAll("Last change [^.]*\\.", "Last change <when>.")
                        .replaceAll("<label class=\"choice\">.*?</label>", ""));   // genealogy's own way in, checked on its own
        } finally { d.stop(); Explain.DRIVES = Explain::configuredDrive; }
        // what a filed run carries, for every question, on every way in that nobody answers: the protocol (MCP and HTTP), the research
        // command in a script, a list of questions sent as runs, the nightly research of the open questions, and the chat when the person
        // leaves without answering
        Jobs jobs = new Jobs(store, j -> "");
        for (int i = 0; i < QUESTIONS.size(); i++) {
            char label = (char) ('a' + i);
            ObjectNode a = M.createObjectNode(); a.put("question", QUESTIONS.get(i)); a.putObject("patron").put("did", "person");
            ObjectNode r = p.research(a);
            out.put("protocol result " + label, mask(r.toString(), root));
            out.put("filed args " + label, mask(jobs.get(r.path("job_id").asText()).path("args").toString(), root));
        }
        ObjectNode qs = M.createObjectNode(); qs.put("text", String.join("\n", QUESTIONS)); qs.put("as", "runs"); qs.put("title", "my list"); qs.putObject("patron").put("did", "person");
        ObjectNode batch = p.questions(qs);
        out.put("questions as runs", mask(batch.toString().replaceAll("J-\\d+", "J-n"), root));
        List<String> batchArgs = new ArrayList<>();
        for (JsonNode id : batch.path("jobs")) batchArgs.add(mask(jobs.get(id.asText()).path("args").toString(), root));
        out.put("questions as runs, filed args", String.join("\n", batchArgs));
        List<String> nightly = new ArrayList<>();
        Crews.explore(store, new Crews.Researcher() {
            @Override public String research(String question, String writer) { nightly.add(question + " | ordinary"); return null; }
            @Override public String research(String question, List<String> subs, String writer, String field) { nightly.add(question + " | " + (field.isEmpty() ? "ordinary" : field)); return null; }
        }, 20);
        nightly.sort(null);
        out.put("nightly research", String.join("\n", nightly));
        List<ObjectNode> steps = new ArrayList<>();
        for (int i : new int[]{6, 0, 1, 2}) steps.add(LibrarianChatTest.tool("library_research", M.createObjectNode().put("question", QUESTIONS.get(i)).toString()));   // a turn takes a few tools
        steps.add(LibrarianChatTest.say("Filed."));
        Librarian talk = new Librarian(store, LibrarianChatTest.scripted(steps, new ArrayList<>()), Librarian.person(), Librarian.Session.open(store));
        String said = talk.say("Find out about all of these");
        talk.leaveUnanswered();
        out.put("chat reply", said);
        List<String> chatArgs = new ArrayList<>();
        for (ObjectNode j : jobs.active()) if (j.path("args").path("question").asText().length() > 0) chatArgs.add(mask(j.path("job_id").asText() + " " + j.path("state").asText() + " " + j.path("args").toString(), root));
        out.put("every filed run, as it stands", String.join("\n", chatArgs));
        return out;
    }

    /** Where two texts part, for a failure a person can read: the first lines that differ, each around the first character that does. */
    static String firstDifference(String a, String b) {
        String[] x = a.split("\n", -1), y = b.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < Math.max(x.length, y.length) && shown < 6; i++) {
            String l = i < x.length ? x[i] : "(nothing)", r = i < y.length ? y[i] : "(nothing)";
            if (l.equals(r)) continue;
            int at = 0;
            while (at < l.length() && at < r.length() && l.charAt(at) == r.charAt(at)) at++;
            int from = Math.max(0, at - 120);
            out.append("\n  line ").append(i + 1).append(", at character ").append(at)
               .append("\n    without genealogy: …").append(l.substring(Math.min(from, l.length()), Math.min(l.length(), at + 200)))
               .append("\n    with genealogy:    …").append(r.substring(Math.min(from, r.length()), Math.min(r.length(), at + 200)));
            shown++;
        }
        return out.toString();
    }

    /** A surface that is JSON, a document a line: compared as documents, without the suggestion's own keys. */
    static boolean structural(String surface) {
        return surface.startsWith("protocol result") || surface.startsWith("filed args") || surface.startsWith("questions as runs") || surface.startsWith("every filed run");
    }

    /** The JSON in a line (after any words before its first brace), or null. */
    static JsonNode json(String line) {
        int at = line.indexOf('{');
        if (at < 0) return null;
        try { return M.readTree(line.substring(at)); } catch (Exception e) { return null; }
    }

    /** Every line with the suggestion's own keys ({@code suggested}, {@code suggestion}) taken out of its JSON, wherever they are in it. */
    static String withoutSuggestion(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            JsonNode j = json(line);
            if (j == null) { out.add(line); continue; }
            drop(j);
            out.add(line.substring(0, line.indexOf('{')) + j);
        }
        return String.join("\n", out);
    }

    private static void drop(JsonNode n) {
        if (n.isObject()) { ((ObjectNode) n).remove(List.of("suggested", "suggestion")); n.forEach(GenealogyIsolationDifferentialTest::drop); }
        else if (n.isArray()) n.forEach(GenealogyIsolationDifferentialTest::drop);
    }

    /** Every object in a document that is a run of {@code question}. */
    static void asked(JsonNode n, String question, List<JsonNode> out) {
        if (n.isObject() && question.equals(n.path("question").asText())) out.add(n);
        if (n.isContainerNode()) n.forEach(c -> asked(c, question, out));
    }

    @Test
    void anOrdinaryLibraryLooksTheSameWithGenealogyOnAndOff(@TempDir Path tmp) throws Exception {
        LibraryStore a = fixture(tmp.resolve("a").resolve("lib"), false);
        LibraryStore b = fixture(tmp.resolve("b").resolve("lib"), true);
        LibraryStore bOn = fixture(tmp.resolve("c").resolve("lib"), true);
        Map<String, String> sa = surfaces(a, tmp.resolve("a")), sb, sOn;
        Fields.suggesting(false);
        try { sb = surfaces(b, tmp.resolve("b")); } finally { Fields.suggesting(true); }
        sOn = surfaces(bOn, tmp.resolve("c"));
        assertEquals(sa.keySet(), sb.keySet());
        List<Executable> checks = new ArrayList<>();
        // with suggestions off, genealogy on is genealogy off, byte for byte
        for (String k : sa.keySet()) {
            String x = sa.get(k), y = sb.get(k);
            checks.add(() -> assertTrue(x.equals(y), k + " differs:" + firstDifference(x, y)));
        }
        // with them on, what differs is the suggestion and nothing else, and only for the family question
        String payload = "This looks like family history.";
        List<String> where = new ArrayList<>();
        for (String k : sb.keySet()) {
            String[] off = sb.get(k).split("\n", -1), on = sOn.get(k).split("\n", -1);
            List<String> added = new ArrayList<>(List.of(on)); for (String l : off) added.remove(l);
            List<String> gone = new ArrayList<>(List.of(off)); for (String l : on) gone.remove(l);
            if (added.isEmpty() && gone.isEmpty()) continue;
            where.add(k);
            checks.add(() -> assertTrue(added.stream().allMatch(l -> l.isBlank() || l.contains(payload)), k + " changed in a line that is not the suggestion: " + added));
            checks.add(() -> assertTrue(gone.stream().allMatch(l -> List.of(on).stream().anyMatch(x -> x.contains(payload))), k + " lost a line: " + gone));
            checks.add(() -> assertTrue(added.stream().noneMatch(l -> QUESTIONS.subList(0, 6).stream().anyMatch(l::contains)) || k.startsWith("questions as runs") || k.startsWith("every filed run"),
                    k + ": an ordinary question was told: " + added));
        }
        // a surface that is JSON differs in the suggestion's own keys and nowhere else: a key added beside them is not hidden in their line
        for (String k : sb.keySet()) {
            if (!structural(k)) continue;
            String off = withoutSuggestion(sb.get(k)), on = withoutSuggestion(sOn.get(k));
            checks.add(() -> assertTrue(off.equals(on), k + " differs beside the suggestion:" + firstDifference(off, on)));
        }
        // and the family question is never filed in genealogy mode where nobody said yes
        for (String k : sOn.keySet()) for (String line : sOn.get(k).split("\n")) {
            JsonNode j = json(line);
            if (j == null) continue;
            List<JsonNode> runs = new ArrayList<>();
            asked(j, QUESTIONS.get(6), runs);
            for (JsonNode run : runs) checks.add(() -> assertFalse(run.has("field"), k + ": the family question was filed in genealogy mode: " + run));
        }
        checks.add(() -> assertTrue(where.containsAll(List.of("protocol result g", "filed args g", "page /research?q=" + URLEncoder.encode(QUESTIONS.get(6), StandardCharsets.UTF_8), "chat reply")), "the family question is told on every way in: " + where));
        checks.add(() -> assertFalse(where.stream().anyMatch(k -> k.matches("(protocol result|filed args|known|run) [a-f].*")), "and no ordinary one is: " + where));
        System.out.println("surfaces where the suggestion shows: " + where);
        assertAll(checks);
    }

    @Test
    void theUpgradeOfAnOrdinaryLibraryRecordsNothingAndChangesNoWaitingRun(@TempDir Path tmp) throws Exception {
        Map<Boolean, String> seen = new LinkedHashMap<>();
        for (boolean on : new boolean[]{false, true}) {
            LibraryStore s = fixture(tmp.resolve(on ? "b" : "a").resolve("lib"), on);
            // what an older version left: a report and waiting research for every question, the family question among them
            s.write(new Investigation("I-0002-family", QUESTIONS.get(6), Finding.State.accepted, "model:research", "2026-09-01T00:00:00Z", List.of(), List.of(), "Notes.\n"));
            Jobs jobs = new Jobs(s, j -> "");
            for (String q : QUESTIONS) jobs.submit("research", "person", M.createObjectNode().put("question", q).put("mode", "depth"));
            Fields.migrate(s);
            StringBuilder b = new StringBuilder("runs " + Fields.rows(s) + "\n");
            for (ObjectNode j : jobs.active()) b.append(j.path("job_id").asText()).append(' ').append(j.path("args")).append('\n');
            b.append("family folder ").append(Files.exists(s.root().resolve("family")));
            seen.put(on, mask(b.toString(), s.root()));
        }
        assertEquals(seen.get(false), seen.get(true), "the upgrade with genealogy on is the upgrade with it off");
        for (String text : seen.values()) assertTrue(text.startsWith("runs []") && !text.contains("\"field\"") && text.endsWith("family folder false"), "the upgrade gave ordinary work a field:\n" + text);
    }

    @Test
    void anOrdinaryLibraryNeverAsksGenealogyToReadOrDecideAnything(@TempDir Path tmp) throws Exception {
        LibraryStore b = fixture(tmp.resolve("b").resolve("lib"), true);
        String focus = "Marie Curie (born 1867; died 1934): what did she discover?";
        Set<String> asked = ConcurrentHashMap.newKeySet();
        GenealogyProfile.ASKED = asked;
        try {
            Graph.build(b);
            // a worker's tools, as the service makes them, and a run whose workers are offered record_search
            for (Tool t : Researcher.webTools().web(focus)) assertFalse(t.name().isEmpty());
            Researcher.Tools records = new Researcher.Tools() {
                @Override public List<Tool> web(String sub) { return List.of(new RecordSearchTool(List.of("en"), sub)); }
                @Override public BooleanSupplier exhausted() { return () -> false; }
            };
            new Researcher(new ResearcherTest.ScriptedDrive(), new ResearcherTest.ScriptedDrive(), records, line -> { }, 1, b).run(new Researcher.Ask(focus, "depth", 60, List.of()), Researcher.known(b, focus));
            // a dispute and a retirement, and what else rests on the disputed claim's source
            new Council(b).dispute("F-0005-claim", "she was born in Warsaw, but the claim was about something else");
            new Council(b).retire("F-0006-claim");
            Evidence.restingOn(b, "https://example.org/page-5", "");
        } finally { GenealogyProfile.ASKED = null; }
        assertFalse(asked.contains(b.root().toString()), "genealogy was asked to read or decide something in a library that holds none of its work: " + asked);
        assertTrue(asked.stream().noneMatch(q -> q.contains("Marie Curie")), "genealogy read the dates of an ordinary run's question: " + asked);
    }

    @Test
    void theResearchPageOffersGenealogyWhereItIsOnAndNotWhereItIsOff(@TempDir Path tmp) throws Exception {
        LibraryStore a = fixture(tmp.resolve("a").resolve("lib"), false);
        LibraryStore b = fixture(tmp.resolve("b").resolve("lib"), true);
        assertEquals("", Pages.fieldChoices(a, QUESTIONS.get(6), ""));
        String box = Pages.fieldChoices(b, QUESTIONS.get(0), "");
        assertTrue(box.contains("<input type=\"checkbox\" name=\"field\" value=\"genealogy\"> Family history") && !box.contains("checked") && !box.contains("This looks like family history"), box);
    }

    @Test
    void anOrdinaryLibraryIsToldNothingOfFamilyHistory(@TempDir Path tmp) throws Exception {
        LibraryStore b = fixture(tmp.resolve("b").resolve("lib"), true);
        List<Executable> checks = new ArrayList<>();
        Librarian lib = new Librarian(b, new ResearcherTest.ScriptedDrive(), Librarian.person(), Librarian.Session.open(b));
        checks.add(() -> assertFalse(lib.systemPrompt().contains("library_who") || lib.systemPrompt().contains("Family history"), "the chat's instructions talk about family history"));
        checks.add(() -> assertFalse(Librarian.tools(b).toString().contains("library_who"), "the chat is given the family tool"));
        checks.add(() -> assertFalse(LibrarianReview.extractPrompt("A report.\n", LibrarianReview.extractionRule(b, Fields.ofRun(b, "I-0001-notes"))).contains("parent-of"), "the review is told to write family relations"));
        checks.add(() -> assertFalse(Pages.hasFamily(b), "the family pages are in the menu"));
        String known = Researcher.known(b, QUESTIONS.get(2));
        checks.add(() -> assertFalse(known.contains("FOR THIS PERSON") || known.contains("record collections"), "a re-asked ordinary question is told about a person's record collections:\n" + known));
        assertAll(checks);
    }
}
