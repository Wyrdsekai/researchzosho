package org.researchzosho.librarian;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Genealogy is a module: the questions about names and families are about the people its own work names (its intake's claims, the family's
 * answers, the runs asked for in genealogy mode), never about a person the owner marked by hand in an ordinary library; a library with
 * genealogy switched off has none and files none; and the note that says how many wait comes only in a library that holds family work,
 * never on a run that changes nothing.
 */
class FamilyNameQuestionsOwnWorkTest {

    static final ObjectMapper M = new ObjectMapper();

    /** An ordinary research run's claims about two people the owner marked persons by hand, with other names in their wording. */
    static LibraryStore ordinary(Path root) throws Exception {
        LibraryStore store = new LibraryStore(root); store.init();
        Files.createDirectories(Graph.dir(store));
        store.write(new Investigation("I-0001-notes", "Notes on writers of York", Finding.State.accepted, "model:research", "2026-09-01T00:00:00Z", List.of(), List.of(), "Notes.\n"));
        String[][] claims = {{"Ruth Hale", "changed name to", "Ruth Hart"}, {"Ruth Hale", "née", "Ruth Moore"}, {"Tom Hart", "former name", "Tom Ellis"}, {"Ruth Hale", "born", "York"}};
        int n = 0;
        for (String[] c : claims) {
            String line = c[0] + " " + c[1] + " " + c[2] + ".";
            store.write(new Finding(String.format("F-%04d-claim", ++n), line, List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.medium, "reviewer",
                    "2026-09-01T00:00:00Z", "2026-09-01", Finding.Volatility.stable, "", List.of(new Finding.Source("https://example.org/page-" + n, "n/a", "cited by I-0001-notes")),
                    List.of(), null, line + "\n", new Finding.Triple(c[0], c[1], c[2]), List.of()));
        }
        Graph.setKind(store, "Ruth Hale", "person");   // the owner marked two writers persons by hand
        Graph.setKind(store, "Tom Hart", "person");
        Graph.alias(store, "Ruth Hale", List.of("Ruth Ellis"));   // and gave one of them another name
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    static ObjectNode who(LibraryStore store, String json) throws Exception {
        ObjectNode a = (ObjectNode) M.readTree(json); a.putObject("patron").put("did", "person");
        return new LibraryProtocol(store).who(a);
    }

    @Test
    void anOrdinaryLibraryHasNoQuestionAboutNamesAndABesideFamilyWorkTheOwnersPeopleAreNotAskedAbout(@TempDir Path tmp) throws Exception {
        LibraryStore store = ordinary(tmp.resolve("ordinary"));
        assertEquals(List.of(), FamilyNameQuestions.open(store), "an ordinary library, genealogy on");
        assertEquals(0, who(store, "{\"op\":\"list\"}").path("names_waiting").asInt(), "library_who counts none");
        assertFalse(Files.exists(FamilyNameQuestions.askedFile(store)));

        // the same writers beside a tree file about two other people: still nobody asks about them
        LibraryStore mixed = ordinary(tmp.resolve("mixed"));
        FamilyAccount.fileAsRead(mixed, new FamilyAccount.Read(List.of(), List.of(
                FamilyNameQuestionsTest.fact("Ann Hart", "child-of", "Mary Moore", "", "Ann Hart was Mary Moore's daughter."),
                FamilyNameQuestionsTest.fact("Ann Hart", "born-on", "1901", "", "Ann Hart was born in 1901.")), List.of()), "file:///family/notes.txt", "an aunt");
        Graph g = FamilyPeople.view(mixed);
        List<FamilyNameQuestions.Question> qs = FamilyNameQuestions.open(mixed);
        assertTrue(qs.stream().noneMatch(q -> q.people().contains(g.nodeIdOf("Ruth Hale")) || q.people().contains(g.nodeIdOf("Tom Hart"))),
                "the owner's two writers are in no family claim: " + qs.stream().map(FamilyNameQuestions.Question::text).toList());

        // a person the family's own work names is asked about, whatever claim gives the other name
        FamilyAccount.fileAsRead(mixed, new FamilyAccount.Read(List.of(), List.of(
                FamilyNameQuestionsTest.fact("Ruth Hale", "lived-in", "York", "1920", "Aunt Ruth Hale lived in York in 1920.")), List.of()), "file:///family/letter.txt", "an aunt");
        Graph now = FamilyPeople.view(mixed);
        assertTrue(FamilyNameQuestions.open(mixed).stream().anyMatch(q -> q.people().contains(now.nodeIdOf("Ruth Hale"))), "Ruth Hale is the family's now");
    }

    // e-boundary-1: two families the owner marked by hand in an ordinary library were asked about as "which family"
    @Test
    void familiesTheOwnerMarkedByHandAreNotAskedAbout(@TempDir Path tmp) throws Exception {
        LibraryStore store = ordinary(tmp.resolve("ordinary"));
        String[][] claims = {{"The House of Hale", "seat", "Micklegate"}, {"The Hale family", "owned", "the Hale Mill"}};
        int n = 10;
        for (String[] c : claims) {
            String line = c[0] + " " + c[1] + " " + c[2] + ".";
            store.write(new Finding(String.format("F-%04d-claim", ++n), line, List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.medium, "reviewer",
                    "2026-09-01T00:00:00Z", "2026-09-01", Finding.Volatility.stable, "", List.of(new Finding.Source("https://example.org/page-" + n, "n/a", "cited by I-0001-notes")),
                    List.of(), null, line + "\n", new Finding.Triple(c[0], c[1], c[2]), List.of()));
        }
        Graph.setKind(store, "The House of Hale", "family");
        Graph.setKind(store, "The Hale family", "family");
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        assertEquals(List.of(), FamilyNameQuestions.open(store).stream().map(FamilyNameQuestions.Question::text).toList(), "no family work names either family");
        assertEquals(0, who(store, "{\"op\":\"list\"}").path("names_waiting").asInt());
    }

    // e-boundary-2: an ordinary report's "heir to the Hart estate" beside family work made the estate a person in genealogy's view
    @Test
    void anEstateAnOrdinaryReportNamesAnHeirToIsNoPerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = ordinary(tmp.resolve("mixed"));
        String[][] claims = {{"Tom Hart", "heir to", "the Hart estate"}, {"Ruth Hale", "heir to", "the Hale estate"}};
        int n = 20;
        for (String[] c : claims) {
            String line = c[0] + " " + c[1] + " " + c[2] + ".";
            store.write(new Finding(String.format("F-%04d-claim", ++n), line, List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.medium, "reviewer",
                    "2026-09-01T00:00:00Z", "2026-09-01", Finding.Volatility.stable, "", List.of(new Finding.Source("https://example.org/page-" + n, "n/a", "cited by I-0001-notes")),
                    List.of(), null, line + "\n", new Finding.Triple(c[0], c[1], c[2]), List.of()));
        }
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                FamilyNameQuestionsTest.fact("Ann Hart", "child-of", "Mary Moore", "", "Ann Hart was Mary Moore's daughter."),
                FamilyNameQuestionsTest.fact("Ann Hart", "born-on", "1901", "", "Ann Hart was born in 1901.")), List.of()), "file:///family/notes.txt", "an aunt");
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Graph g = FamilyPeople.view(store);
        for (String estate : List.of("the Hart estate", "the Hale estate"))
            assertNotEquals("person", g.node(g.nodeIdOf(estate)).kind(), estate + " is no person in genealogy's view");
        List<String> checks = FamilyChecks.check(store).stream().map(FamilyChecks.Problem::text).filter(t -> t.contains("estate")).toList();
        assertEquals(List.of(), checks, "the checks never ask whether two estates are one person");
    }

    @Test
    void withGenealogySwitchedOffNothingIsAskedAndNoAnswerIsFiled(@TempDir Path tmp) throws Exception {
        LibraryStore store = FamilyNameQuestionsTest.store(tmp);
        FamilyNameQuestionsTest.file(store, "file:///family/book.txt", List.of(FamilyNameQuestionsTest.fact("森田健二", "born-on", "1905", "", "健二 1905年生")),
                List.of(FamilyNameQuestionsTest.name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", "健二は1905年に遠藤家に生まれた。")));
        FamilyNameQuestions.Question q = FamilyNameQuestions.open(store).get(0);
        Profiles.disable(store, "genealogy");
        assertEquals(List.of(), FamilyNameQuestions.open(store), "the field is off");
        assertEquals(0, who(store, "{\"op\":\"list\"}").path("names_waiting").asInt());
        long before = store.scanFindings().findings().size();
        IllegalArgumentException off = assertThrows(IllegalArgumentException.class, () -> FamilyNameQuestions.answer(store, q.code(), "1", "", "Ann"));
        assertTrue(off.getMessage().contains("turned off") && off.getMessage().contains("researchzosho profile enable genealogy"), off.getMessage());
        assertEquals(before, store.scanFindings().findings().size(), "nothing filed");
        assertFalse(Files.exists(FamilyNameQuestions.askedFile(store)));
    }

    @Test
    void theNoteOnTheQuestionsThatWaitComesOnlyInALibraryThatHoldsFamilyWorkAndNeverOnADryRun(@TempDir Path tmp) throws Exception {
        LibraryStore store = ordinary(tmp.resolve("lib"));
        Path marker = store.root().resolve("catalog").resolve("migrations").resolve("run-fields.txt");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "2026-09-20\tupgraded\n");   // a library an earlier 0.5.0 build upgraded
        Path ged = tmp.resolve("tree.ged");
        Files.writeString(ged, "0 HEAD\n1 CHAR UTF-8\n0 @I1@ INDI\n1 NAME Mary /Moore/\n1 BIRT\n2 DATE 1870\n0 TRLR\n", StandardCharsets.UTF_8);
        String err = cli(store, "researchzosho", "genealogy", "import", ged.toString(), "--dry");
        assertFalse(err.contains("questions about names"), err);
        assertFalse(Files.exists(told(store)), "an ordinary library, and a run that changes nothing: nothing is written");

        // a library that holds family work, on a dry run: still nothing
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(), List.of(), List.of(),
                List.of(FamilyNameQuestionsTest.name("Ann Hart", "Ann Lee", "Lee", "Ann", "birth", "1870", "Ann Hart was born Ann Lee in 1870."),
                        FamilyNameQuestionsTest.name("Ann Hart", "Ann Hart", "Hart", "Ann", "unknown", "", "Ann Hart was born Ann Lee in 1870.")), List.of()),
                "an aunt", f -> List.of("file:///family/notes.txt"), f -> List.of());
        cli(store, "researchzosho", "genealogy", "import", ged.toString(), "--dry");
        assertFalse(Files.exists(told(store)), "a run that changes nothing writes nothing");
    }

    static Path told(LibraryStore store) { return store.root().resolve("catalog").resolve("migrations").resolve("names-questions.txt"); }

    static String cli(LibraryStore store, String... args) throws Exception {
        PrintStream was = System.err, wasOut = System.out;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try { new GenealogyProfile().cli(store, args); } finally { System.setErr(was); System.setOut(wasOut); }
        return err.toString(StandardCharsets.UTF_8);
    }
}
