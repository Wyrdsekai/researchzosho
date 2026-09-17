package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** A code repository as a starting point: read, one draft claim, directions offered; pick and do file the runs. */
class ReposTest {

    static final ObjectMapper M = new ObjectMapper();
    static Supplier<Researcher.Drive> drives;
    @BeforeAll static void noModel() { drives = Explain.DRIVES; Explain.DRIVES = () -> null; }
    @AfterAll static void restore() { Explain.DRIVES = drives; }

    /** A small repository: a README, a manifest, two source folders, a build folder that must be skipped. */
    static Path repo(Path home) throws Exception {
        Path r = home.resolve("tidebook");
        Files.createDirectories(r.resolve("src/rank")); Files.createDirectories(r.resolve("src/store")); Files.createDirectories(r.resolve("docs")); Files.createDirectories(r.resolve("node_modules/junk"));
        Files.writeString(r.resolve("README.md"), "# tidebook\n\n[![build](x)](y)\n\nTidebook ranks the harbours of a coast by how well their tide tables agree with observed water levels, using a Kalman smoother over the published constituents.\n\n## Install\n\nnpm install\n");
        Files.writeString(r.resolve("package.json"), "{\n  \"name\": \"tidebook\",\n  \"version\": \"0.3.1\",\n  \"dependencies\": {\n    \"kalman-filter\": \"^1.9.0\",\n    \"d3-array\": \"^3.2.4\"\n  },\n  \"devDependencies\": {\n    \"vitest\": \"^1.0.0\"\n  }\n}\n");
        Files.writeString(r.resolve("src/rank/rank.js"), "// Rank harbours by residual variance of the smoothed tide against observations.\nimport { KalmanFilter } from 'kalman-filter';\nexport function rank(harbours) { return harbours.sort((a, b) => a.residual - b.residual); }\n" + "x".repeat(400));
        Files.writeString(r.resolve("src/store/store.js"), "// A JSON store of observed water levels by harbour and hour.\nexport function load(path) { return JSON.parse(require('fs').readFileSync(path)); }\n");
        Files.writeString(r.resolve("docs/method.md"), "# Method\n\nThe constituents come from the IHO list.\n");
        Files.writeString(r.resolve("node_modules/junk/index.js"), "module.exports = 1;\n" + "y".repeat(5000));
        return r;
    }

    @Test
    void aFolderIsReadAndSurveyedWithoutTheJunk(@TempDir Path home) throws Exception {
        Path r = repo(home);
        Repos.Repo repo = Repos.obtain(new LibraryStore(home.resolve("lib")), r.toString());
        assertEquals("tidebook", repo.name()); assertFalse(repo.cloned());
        Repos.Survey s = Repos.survey(repo);
        assertEquals(5, s.files(), "node_modules is skipped: " + s.samples().keySet());
        assertTrue(s.readme().startsWith("# tidebook"));
        assertEquals(List.of("package.json"), List.copyOf(s.manifests().keySet()));
        assertEquals(List.of("docs/method.md"), s.docs());
        assertEquals(2, s.byLanguage().get("js"));
        assertEquals(List.of("docs", "src"), s.topDirs());
        assertTrue(s.samples().containsKey("src/rank/rank.js") && s.samples().containsKey("src/store/store.js"), "one sample per folder: " + s.samples().keySet());
        String text = Repos.render(s);
        assertTrue(text.contains("## README") && text.contains("## package.json") && text.contains("## src/rank/rank.js (top)"), text);
        // without a model the description is mechanical: the README's first real paragraph, the dependencies, a question each
        Surveys.Read read = Surveys.read(new LibraryStore(home.resolve("lib")), Surveys.Kind.repo, r.toString());
        assertEquals("5", read.facts().get("files"));
        Surveys.Description d = Surveys.describe(read, null);
        assertFalse(d.byModel());
        assertTrue(d.whatItIs().startsWith("Tidebook ranks the harbours"), "the badge line is skipped: " + d.whatItIs());
        assertTrue(d.restsOn().contains("kalman-filter") && d.restsOn().contains("d3-array"), d.restsOn().toString());
        assertTrue(d.options().get(0).startsWith("What problem does tidebook solve"), d.options().toString());
        assertTrue(d.options().stream().anyMatch(q -> q.contains("kalman-filter")), d.options().toString());
        assertTrue(d.options().size() <= Surveys.MAX_OPTIONS);
    }

    @Test
    void theModelsReadingIsParsedBySectionAndTheDirectionsAreQuestions() {
        Surveys.Description d = Surveys.parse("""
                WHAT IT IS
                A command-line tool for coastal surveyors.

                WHAT IT DOES
                It reads tide tables, smooths them, and ranks harbours.

                RESTS ON
                - Kalman smoothing
                - harmonic tidal constituents (IHO)

                DIRECTIONS
                1. What is the published basis for Kalman smoothing of tidal constituents
                2. What other tools rank tide-table agreement, and how do they differ?
                """);
        assertNotNull(d); assertTrue(d.byModel());
        assertEquals("A command-line tool for coastal surveyors.", d.whatItIs());
        assertEquals(List.of("Kalman smoothing", "harmonic tidal constituents (IHO)"), d.restsOn());
        assertEquals(2, d.options().size());
        assertTrue(d.options().get(0).endsWith("?"), "a direction ends as a question: " + d.options().get(0));
        assertNull(Surveys.parse("I cannot read this."), "no sections, no description: the mechanical one is used");
        assertEquals("tidebook", Repos.nameOf("https://github.com/someone/tidebook.git"));
        assertEquals("tidebook", Repos.nameOf("git@github.com:someone/tidebook.git"));
        assertEquals("tidebook", Repos.nameOf("/home/x/src/tidebook/"));
    }

    @Test
    void surveyFilesTheClaimAndTheDirectionsAndPickAndDoFileTheRuns(@TempDir Path home) throws Exception {
        Path r = repo(home);
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode().put("path", r.toString());
        a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        ObjectNode s = p.repo(a);
        assertEquals("repo", s.path("kind").asText());
        assertEquals("tidebook", s.path("name").asText());
        assertEquals("mechanical", s.path("described_by").asText());
        assertTrue(s.path("options").size() >= 3, s.path("options").toString());
        assertEquals(1, s.path("options").get(0).path("n").asInt());
        // the survey is on the shelves, the claim is a draft with the repository as its source and a note saying how it was read
        assertFalse(s.path("raw").asText().isEmpty());
        Finding claim = store.scanFindings().findings().stream().filter(f -> f.id().equals(s.path("claim_id").asText())).findFirst().orElseThrow();
        assertEquals(Finding.State.draft, claim.state());
        assertTrue(claim.title().startsWith("tidebook: Tidebook ranks"), claim.title());
        assertEquals(r.toString(), claim.sources().get(0).locator());
        assertTrue(claim.notes().stream().anyMatch(n -> n.kind().equals("survey") && n.text().startsWith("kind=repo")), claim.notes().toString());
        assertTrue(claim.body().contains("It rests on:\n- kalman-filter"), claim.body());
        assertEquals(claim.id(), Surveys.claimFor(store, "tidebook").id());
        // the directions are open questions of type person, numbered as offered, and nothing has run
        List<Surveys.Option> options = Surveys.options(store, "tidebook");
        assertEquals(s.path("options").size(), options.size());
        assertEquals("person", options.get(0).line().type());
        assertTrue(options.get(0).line().kind().contains("(from a survey of tidebook, option 1)"), options.get(0).line().kind());
        var jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        assertEquals(0, jobs.active().size());
        assertTrue(s.path("summary").asText().contains("Nothing runs until you pick one."), s.path("summary").asText());
        // pick two: a run each, the questions marked explored, the rest still open
        ObjectNode pick = a.deepCopy().put("op", "pick").put("picks", "1, 3");
        ObjectNode pr = p.repo(pick);
        assertEquals(2, pr.path("runs").size(), pr.toString());
        assertTrue(pr.path("runs").get(0).hasNonNull("job_id") && pr.path("runs").get(1).hasNonNull("job_id"), pr.path("runs").toString());
        assertEquals(options.size() - 2, Surveys.options(store, "tidebook").size(), "picked directions are no longer open");
        var first = jobs.active().stream().filter(j -> j.path("job_id").asText().equals(pr.path("runs").get(0).path("job_id").asText())).findFirst().orElseThrow();
        String q = first.path("args").path("question").asText();
        assertTrue(q.startsWith("About the repository tidebook (" + r + ")") && q.contains(options.get(0).question()), q);
        // a number that is not open says so and files nothing
        ObjectNode again = p.repo(a.deepCopy().put("op", "pick").put("picks", "1"));
        assertTrue(again.path("runs").get(0).path("error").asText().contains("no open direction numbered 1"), again.toString());
        // the person's own direction: filed on the open questions and run at once; by name, no path needed
        ObjectNode own = M.createObjectNode().put("op", "do").put("name", "tidebook").put("question", "Compare its residual-variance ranking with how port authorities grade tide tables");
        own.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        ObjectNode dr = p.repo(own);
        assertEquals(1, dr.path("runs").size());
        assertTrue(Frontier.read(store).stream().anyMatch(l -> l.kind().contains("(from a survey of tidebook, own)") && l.text().startsWith("Compare its residual")), Frontier.read(store).toString());
        assertEquals(3, jobs.active().size(), "three runs in all, none from the survey");
        // a repo nobody surveyed
        ObjectNode none = M.createObjectNode().put("op", "pick").put("name", "nothing").put("picks", "1");
        none.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        assertThrows(ProtocolError.class, () -> p.repo(none));
    }

    @Test
    void aGitUrlIsClonedIntoTheLibraryAndAMissingGitIsSaidPlainly(@TempDir Path home) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Repos.gitInstalled(), "git on this machine");
        Path r = repo(home);
        run(r, "git", "init", "-q"); run(r, "git", "add", "."); run(r, "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "seed");
        Path bare = home.resolve("tidebook.git");
        run(home, "git", "clone", "-q", "--bare", r.toString(), bare.toString());
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        assertTrue(Repos.isUrl(bare.toString()), "a .git locator is cloned, not read in place");
        Repos.Repo repo = Repos.obtain(store, bare.toString());
        assertTrue(repo.cloned());
        assertEquals(store.rawDir().resolve("repos").resolve("tidebook"), repo.dir());
        assertTrue(Files.exists(repo.dir().resolve("README.md")));
        Repos.Repo twice = Repos.obtain(store, bare.toString());
        assertEquals(repo.dir(), twice.dir(), "a second survey pulls into the same clone");
        assertThrows(java.io.IOException.class, () -> Repos.obtain(store, home.resolve("nowhere").toString()), "a path that is not a folder");
    }

    static void run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), String.join(" ", cmd) + ": " + out);
    }
}
