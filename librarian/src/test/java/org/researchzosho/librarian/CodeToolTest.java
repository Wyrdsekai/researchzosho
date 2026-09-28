package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A run about a repository reads its code, inside the repository's own folder and nowhere else. */
class CodeToolTest {

    private static final ObjectMapper J = new ObjectMapper();

    private static LibraryStore repo(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path r = store.rawDir().resolve("repos").resolve("hale-tree");
        Files.createDirectories(r.resolve("src"));
        Files.createDirectories(r.resolve("docs"));
        Files.createDirectories(r.resolve(".git"));
        Files.writeString(r.resolve("src").resolve("Reader.java"), "package hale;\n\n// reads a GEDCOM file line by line\nclass Reader {\n    void read() { }\n}\n");
        Files.writeString(r.resolve("docs").resolve("DESIGN.md"), "# Plans\n\nA GEDCOM writer is planned for a later version.\n");
        Files.writeString(r.resolve(".git").resolve("config"), "gedcom in the history\n");
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "GEDCOM secret outside the repository\n");
        Files.createSymbolicLink(r.resolve("escape.txt"), outside.resolve("secret.txt"));
        Files.createSymbolicLink(r.resolve("elsewhere"), outside);
        return store;
    }

    private static ObjectNode op(String op) { return J.createObjectNode().put("op", op).put("repo", "hale-tree"); }

    @Test
    void theRepositoryAQuestionIsAboutIsFoundByItsFolderName(@TempDir Path tmp) throws Exception {
        LibraryStore store = repo(tmp);
        assertEquals(List.of("hale-tree"), CodeTool.reposIn(store, "About the repository hale-tree (https://example.org/hale-tree), whose text and description are on the shelves: does it write GEDCOM?"));
        assertEquals(List.of("hale-tree"), CodeTool.reposIn(store, "What file formats does hale-tree read?"));
        assertEquals(List.of(), CodeTool.reposIn(store, "Who were the parents of Tom Hale?"), "a name inside a word is not the repository");
    }

    @Test
    void listGrepAndReadStayInsideTheRepository(@TempDir Path tmp) throws Exception {
        LibraryStore store = repo(tmp);
        CodeTool t = new CodeTool(store, List.of("hale-tree"));
        String list = t.execute(op("list"));
        assertTrue(list.contains("src/Reader.java") && list.contains("docs/DESIGN.md"), list);
        assertFalse(list.contains(".git") || list.contains("escape.txt") || list.contains("secret"), "no history, no links: " + list);
        String grep = t.execute(op("grep").put("pattern", "gedcom"));
        assertTrue(grep.contains("src/Reader.java:3: // reads a GEDCOM file line by line") && grep.contains("docs/DESIGN.md:3:"), grep);
        assertFalse(grep.contains("secret") || grep.contains("history"), "a linked file or folder is not searched: " + grep);
        String read = t.execute(op("read").put("path", "src/Reader.java").put("from_line", 3));
        assertTrue(read.startsWith("raw/repos/hale-tree/src/Reader.java lines 3-7 of 7:") && read.contains("    3  // reads a GEDCOM file"), read);
        for (String out : List.of("../../outside/secret.txt", "escape.txt", "elsewhere/secret.txt", tmp.resolve("outside").resolve("secret.txt").toString(), "src/../../../outside/secret.txt")) {
            String r = t.execute(op("read").put("path", out));
            assertTrue(r.startsWith("ERROR:") && r.contains("outside the repository"), out + " → " + r);
            assertFalse(r.contains("secret outside"), r);
        }
        assertTrue(t.execute(op("grep").put("path", "elsewhere").put("pattern", "secret")).startsWith("ERROR:"));
        assertTrue(t.execute(op("list").put("path", "elsewhere")).startsWith("ERROR:"));
        assertTrue(t.execute(J.createObjectNode().put("op", "read").put("repo", "another").put("path", "x")).startsWith("ERROR: give repo"), "only the repositories the run was given");
    }

    @Test
    void aCitationOfTheCodeIsASourceTheReviewAndTheCiteCheckCanRead(@TempDir Path tmp) throws Exception {
        LibraryStore store = repo(tmp);
        assertTrue(Acquisitions.urls("It reads GEDCOM (raw/repos/hale-tree/src/Reader.java:3-5).").contains("raw/repos/hale-tree/src/Reader.java:3-5"));
        String text = CodeTool.textOf(store, "raw/repos/hale-tree/src/Reader.java:3");
        assertTrue(text != null && text.contains("reads a GEDCOM file line by line"), text);
        assertNull(CodeTool.textOf(store, "raw/repos/hale-tree/escape.txt"), "a link out of the repository is not read");
        assertNull(CodeTool.textOf(store, "raw/repos/hale-tree/../../outside/secret.txt"));
        assertNull(CodeTool.textOf(store, "https://example.org/hale-tree"));
    }

    @Test
    void aRunAboutTheRepositoryIsOfferedTheCodeAndToldToCiteIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = repo(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive();
        drive.criticWantsMore = false;
        new Researcher(drive, drive, new ResearcherTest.FakeTools(), null, 1, store).run(new Researcher.Ask("About the repository hale-tree (https://example.org/hale-tree): what file formats does it read?", "depth", 40, List.of()), "");
        assertTrue(drive.offered.stream().anyMatch(o -> o.contains("read_code")), drive.offered.toString());
        assertTrue(drive.histories.stream().anyMatch(h -> h.toString().contains("THE CODE: read_code reads the files of hale-tree")), "the survey's own run is told to settle it from the code");
        ResearcherTest.ScriptedDrive software = new ResearcherTest.ScriptedDrive();
        software.criticWantsMore = false;
        new Researcher(software, software, new ResearcherTest.FakeTools(), null, 1, store).run(new Researcher.Ask("What file formats does the open source project hale-tree read?", "depth", 40, List.of()), "");
        assertTrue(software.histories.stream().anyMatch(h -> h.toString().contains("THE CODE: read_code reads the files of hale-tree")), "and so is a question about software that names it");
        // a question that only uses the repository's name as a word is not about the repository: no tool, no rule
        ResearcherTest.ScriptedDrive named = new ResearcherTest.ScriptedDrive();
        named.criticWantsMore = false;
        new Researcher(named, named, new ResearcherTest.FakeTools(), null, 1, store).run(new Researcher.Ask("How tall does a hale-tree grow in a garden?", "depth", 40, List.of()), "");
        assertTrue(named.offered.stream().noneMatch(o -> o.contains("read_code")), named.offered.toString());
        assertTrue(named.histories.stream().noneMatch(h -> h.toString().contains("THE CODE") || h.toString().contains("repository's own files can be read")), "no rule to settle it from the code");
        ResearcherTest.ScriptedDrive other = new ResearcherTest.ScriptedDrive();
        other.criticWantsMore = false;
        new Researcher(other, other, new ResearcherTest.FakeTools(), null, 1, store).run(new Researcher.Ask("How were the Antikythera gears cut?", "depth", 40, List.of()), "");
        assertTrue(other.offered.stream().noneMatch(o -> o.contains("read_code")), "a question about no repository is not offered it");
    }

    @Test
    void aDirectionOfTheSurveyTheNightlyResearchTakesReadsTheRepositorysCode(@TempDir Path tmp) throws Exception {
        LibraryStore store = repo(tmp);
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Surveys.Read read = new Surveys.Read(Surveys.Kind.repo, "hale-tree", "https://example.org/hale-tree", "hale-tree",
                "# hale-tree\n\nhale-tree reads family history files written in the GEDCOM format and draws the tree for a person to print.\n", Map.of());
        Surveys.file(store, read, Surveys.mechanical(read), "patron:person");
        // the nightly research takes the survey's first direction, as the service's explorer runs it
        List<ResearcherTest.ScriptedDrive> drives = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        Crews.Researcher explorer = new Crews.Researcher() {
            @Override public String research(String question, String writer) throws Exception { return research(question, List.of(), writer, ""); }
            @Override public String research(String question, List<String> subQuestions, String writer) throws Exception { return research(question, subQuestions, writer, ""); }
            @Override public String research(String question, List<String> subQuestions, String writer, String field) throws Exception {
                ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive();
                drive.criticWantsMore = false;
                drives.add(drive); asked.add(question);
                Researcher runner = new Researcher(drive, drive, new ResearcherTest.FakeTools(), null, 1, store);
                Researcher.Ask ask = new Researcher.Ask(question, "broad", 40, subQuestions, "both", List.of(), 0, field.isEmpty() ? List.of() : List.of(field));
                return Researcher.file(store, runner, ask, writer, "", "explorer-line").investigationId();
            }
        };
        Crews.explore(store, explorer, 1);
        assertEquals(1, drives.size(), asked.toString());
        assertTrue(asked.get(0).startsWith("About the repository hale-tree (https://example.org/hale-tree), whose text and description are on the shelves: "), "asked as the survey's own run of it: " + asked);
        assertTrue(drives.get(0).offered.stream().anyMatch(o -> o.contains("read_code")), drives.get(0).offered.toString());
        assertTrue(drives.get(0).histories.stream().anyMatch(h -> h.toString().contains("THE CODE: read_code reads the files of hale-tree")), "and told to settle it from the code");
    }

    @Test
    void aSearchStopsAtItsLimitsAndSaysSo(@TempDir Path tmp) throws Exception {
        LibraryStore store = repo(tmp);
        Path src = store.rawDir().resolve("repos").resolve("hale-tree").resolve("src");
        // more matching lines than one search counts
        Files.writeString(src.resolve("Many.java"), "// gedcom\n".repeat(CodeTool.GREP_HITS + 5));
        String many = new CodeTool(store, List.of("hale-tree")).execute(op("grep").put("pattern", "gedcom"));
        assertTrue(many.contains("The search stopped before it had searched every file, because it had found " + CodeTool.GREP_HITS + " matching lines"), many);
        assertTrue(many.startsWith(CodeTool.GREP_HITS + " line(s)"), many);
        // more files than one search reads
        for (int i = 0; i < 6; i++) Files.writeString(src.resolve("F" + i + ".java"), "class F" + i + " { }\n");
        Path real = store.rawDir().resolve("repos").resolve("hale-tree").toRealPath();
        String files = CodeTool.grep(real, real, "raw/repos/hale-tree/", "no such words", 3, 100, 10_000);
        assertTrue(files.startsWith("no line under") && files.contains("(3 files searched)") && files.contains("it had searched 3 files, and there are more"), files);
        // a pattern that backtracks without end on one line
        Files.writeString(src.resolve("Long.java"), "a".repeat(40) + "!\n");
        long t0 = System.nanoTime();
        String slow = CodeTool.grep(real, real.resolve("src").resolve("Long.java"), "raw/repos/hale-tree/", "((a+)+)+b", 10, 100, 300);
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 5_000, "the time limit holds");
        assertTrue(slow.contains("because it had run for 300 milliseconds"), slow);
        // within the limits nothing is said
        String plain = new CodeTool(store, List.of("hale-tree")).execute(op("grep").put("path", "docs").put("pattern", "planned"));
        assertFalse(plain.contains("stopped"), plain);
    }
}
