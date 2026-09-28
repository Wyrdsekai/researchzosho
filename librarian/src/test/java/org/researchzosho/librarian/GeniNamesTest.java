package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;
import org.researchzosho.tools.Fetch;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A Geni profile's birth surname and its last name are two names of one person, for a man as for a woman, and Geni's names in other languages are their forms. */
class GeniNamesTest {

    /** Kenji, born 遠藤, on Geni under 森田, with his names in Japanese and in English; his wife Haru. */
    static final String KENJI = """
            {"focus": {"id": "profile-1", "name": "森田健二", "first_name": "健二", "last_name": "森田", "maiden_name": "遠藤", "gender": "male", "is_alive": false,
                       "names": {"ja": {"first_name": "健二", "last_name": "森田", "maiden_name": "遠藤"}, "en-US": {"first_name": "Kenji", "last_name": "Morita", "maiden_name": "Endo"}},
                       "birth": {"date": {"year": 1905, "formatted_date": "1905"}}},
             "nodes": {
               "profile-1": {"id": "profile-1"},
               "profile-2": {"id": "profile-2", "first_name": "ハル", "last_name": "森田", "is_alive": false},
               "union-1": {"status": "spouse", "edges": {"profile-1": {"rel": "partner"}, "profile-2": {"rel": "partner"}}}}}""";

    @Test
    void aBirthSurnameAndALastNameAreTwoNamesOfOneManWithTheirForms() throws Exception {
        FamilyAccount.Read read = GeniFamily.read(new ObjectMapper().readTree(KENJI));
        assertEquals(2, read.names().size(), read.names().toString());
        FamilyAccount.NameRead born = read.names().get(0), now = read.names().get(1);
        assertEquals("森田健二", born.person());
        assertEquals("遠藤健二", born.name());
        assertEquals("birth", born.kind());
        assertEquals("遠藤", born.family());
        assertEquals("健二", born.given());
        assertEquals(List.of("Kenji Endo"), born.forms(), "the English name with the birth surname is a form of the birth name");
        assertEquals("森田健二", now.name());
        assertEquals("unknown", now.kind(), "Geni does not say why his name is 森田 now");
        assertEquals(List.of("Kenji Morita"), now.forms());
        assertTrue(born.quote().startsWith("Geni: 森田健二, born 遠藤健二 (birth surname 遠藤)"), born.quote());
        FamilyAccount.Person haru = read.people().stream().filter(p -> p.name().equals("森田ハル")).findFirst().orElseThrow();
        assertEquals("森田", haru.family(), "a name in characters is written family name first: " + read.people());
        assertEquals("ハル", haru.given());
    }

    @Test
    void oneProfileIsOnePersonWithBothNames(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        GenealogyProfile.geniForTests = url -> new Fetch.Result(url, 200, KENJI.getBytes(StandardCharsets.UTF_8), "application/json");
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            new GenealogyProfile().cli(store, new String[]{"researchzosho", "genealogy", "read", "https://www.geni.com/people/Kenji-Morita/6000000000001"});
        } finally { System.setOut(was); GenealogyProfile.geniForTests = null; }
        Graph g = FamilyPeople.view(store);
        String kenji = g.nodeIdOf("森田健二");
        for (String other : List.of("遠藤健二", "Kenji Endo", "Kenji Morita")) assertEquals(kenji, g.nodeIdOf(other), other + " leads to him: " + out.toString(StandardCharsets.UTF_8));
        List<Finding> names = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals("has-name")).toList();
        assertEquals(2, names.size(), names.toString());
        assertTrue(names.stream().allMatch(f -> f.triple().subject().equals("森田健二")), "both names are his, one profile being one record");
        assertEquals("birth", FamilyDetail.get(names.stream().filter(f -> f.triple().object().equals("name: 遠藤健二")).findFirst().orElseThrow(), "kind"));
        assertEquals("森田健二 (born 遠藤)", FamilyNameHistory.of(g).heading(kenji));
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(q -> q.kind().equals("one-person")), "no question whether the two names are one person");
    }

    @Test
    void oneNameInTwoLanguagesIsOneNameWrittenTwoWays() throws Exception {
        FamilyAccount.Read read = GeniFamily.read(new ObjectMapper().readTree("""
                {"focus": {"id": "profile-1", "first_name": "勇", "last_name": "森田", "is_alive": false,
                           "names": {"ja": {"first_name": "勇", "last_name": "森田"}, "en": {"first_name": "Isamu", "last_name": "Morita"}}},
                 "nodes": {"profile-1": {"id": "profile-1"}}}"""));
        assertEquals(List.of(), read.names(), "one name is no name claim");
        FamilyAccount.Person isamu = read.people().get(0);
        assertEquals("森田勇", isamu.name());
        assertEquals(List.of("Isamu Morita"), isamu.also(), "the English name is another name he is found by");
    }

    /** Reads a Geni answer through the genealogy command, as a person does, and says what the command printed. */
    static String readGeni(LibraryStore store, String json) throws Exception {
        GenealogyProfile.geniForTests = url -> new Fetch.Result(url, 200, json.getBytes(StandardCharsets.UTF_8), "application/json");
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            new GenealogyProfile().cli(store, new String[]{"researchzosho", "genealogy", "read", "https://www.geni.com/people/Someone/6000000000001"});
        } finally { System.setOut(was); GenealogyProfile.geniForTests = null; }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static List<Finding> claims(LibraryStore store, String subject, String predicate) {
        return store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().subject().equals(subject) && f.triple().predicate().equals(predicate)).toList();
    }

    @Test
    void geniSaysWhetherAPersonIsAManOrAWomanSoAMarriedWomansNameIsWorkedOut(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String said = readGeni(store, """
                {"focus": {"id": "profile-1", "name": "Ruth Ellis", "first_name": "Ruth", "last_name": "Ellis", "maiden_name": "Hale", "gender": "female", "is_alive": false},
                 "nodes": {
                   "profile-1": {"id": "profile-1"},
                   "profile-2": {"id": "profile-2", "first_name": "Hart", "last_name": "Ellis", "gender": "male", "is_alive": false},
                   "union-1": {"status": "spouse", "edges": {"profile-1": {"rel": "partner"}, "profile-2": {"rel": "partner"}}}}}""");
        assertEquals(List.of("female"), claims(store, "Ruth Ellis", "sex").stream().map(f -> f.triple().object()).toList(), said);
        assertEquals(List.of("male"), claims(store, "Hart Ellis", "sex").stream().map(f -> f.triple().object()).toList(), "a relative's too");
        Graph g = FamilyPeople.view(store);
        String ruth = g.nodeIdOf("Ruth Ellis");
        FamilyNameHistory.Name now = FamilyNameHistory.of(g).names(ruth).stream().filter(n -> n.written().equals("Ruth Ellis")).findFirst().orElseThrow();
        assertEquals("marriage", now.kind(), "her married name is worked out from her marriage: " + FamilyNameHistory.of(g).names(ruth));
        assertTrue(now.workedOut());
        assertTrue(FamilyNameQuestions.open(store).stream().noneMatch(q -> q.kind().equals("name-change-how") && q.people().contains(ruth)), FamilyNameQuestions.open(store).toString());
    }

    @Test
    void aMiddleNameIsPartOfBothNamesAndTheLabelIsTheNameGeniFilesThePersonUnderNow(@TempDir Path tmp) throws Exception {
        FamilyAccount.Read read = GeniFamily.read(new ObjectMapper().readTree("""
                {"focus": {"id": "profile-1", "name": "John Hart Ellis", "first_name": "John", "middle_name": "Hart", "last_name": "Ellis", "maiden_name": "Hale", "gender": "male", "is_alive": false},
                 "nodes": {"profile-1": {"id": "profile-1"}}}"""));
        assertEquals(List.of("John Hart Hale", "John Hart Ellis"), read.names().stream().map(FamilyAccount.NameRead::name).toList());
        assertEquals("John Hart", read.names().get(0).given());
        assertEquals(List.of("John Hart Hale"), read.people().get(0).also(), "the birth name he is found by is the birth name");

        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        readGeni(store, """
                {"focus": {"id": "profile-1", "name": "John Hart Ellis", "first_name": "John", "middle_name": "Hart", "last_name": "Ellis", "maiden_name": "Hale", "gender": "male", "is_alive": false},
                 "nodes": {"profile-1": {"id": "profile-1"}}}""");
        Graph g = FamilyPeople.view(store);
        String john = g.nodeIdOf("John Hart Ellis");
        List<FamilyNameHistory.Name> names = FamilyNameHistory.of(g).names(john);
        assertEquals(List.of("John Hart Hale", "John Hart Ellis"), names.stream().map(FamilyNameHistory.Name::written).toList(), names.toString());
        assertTrue(names.stream().noneMatch(FamilyNameHistory.Name::implicit), "the name he is filed under is the name Geni gives now, not a third name: " + names);
        List<FamilyNameQuestions.Question> how = FamilyNameQuestions.open(store).stream().filter(q -> q.kind().equals("name-change-how")).toList();
        assertEquals(1, how.size(), "one question, how he came by the name Ellis: " + how);
        assertTrue(how.get(0).text().contains("get the name John Hart Ellis?"), how.get(0).text());
    }

    @Test
    void aProfileAnOlderVersionFiledInAnotherOrderKeepsItsEntry(@TempDir Path tmp) throws Exception {
        String json = """
                {"focus": {"id": "profile-1", "first_name": "ハル", "last_name": "森田", "gender": "female", "is_alive": false, "birth": {"date": {"year": 1910, "formatted_date": "1910"}}},
                 "nodes": {"profile-1": {"id": "profile-1"},
                   "profile-2": {"id": "profile-2", "first_name": "Kenji", "last_name": "Morita", "gender": "male", "is_alive": false},
                   "union-1": {"status": "spouse", "edges": {"profile-1": {"rel": "partner"}, "profile-2": {"rel": "partner"}}}}}""";
        Map<String, String> labels = new LinkedHashMap<>(Map.of("profile-1", "ハル 森田"));
        List<String[]> renamed = new ArrayList<>();
        FamilyAccount.Read read = GeniFamily.read(new ObjectMapper().readTree(json), labels, renamed);
        assertEquals(List.of(), renamed.stream().map(r -> String.join(" -> ", r)).toList(), "the same name in another order is no new name: nothing is joined");
        assertEquals("ハル 森田", read.people().get(0).name(), "the profile keeps the entry it has");
        assertTrue(read.people().get(0).also().contains("森田ハル"), "and the name in its order now is another name of hers: " + read.people());

        // through the command: a book's 森田ハル, born 1850, is somebody else, and nothing joins the two
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyNameHistoryTest.file(store, "file:///family/book.txt", List.of(new FamilyAccount.Fact("森田ハル", "born-on", "1850", "", "森田ハル 1850年生")), List.of());
        FamilyNameHistoryTest.file(store, "https://www.geni.com/people/Haru/6000000000001", List.of(new FamilyAccount.Fact("ハル 森田", "born-on", "1910", "", "Geni: ハル 森田 born 1910")), List.of());
        Path f = store.root().resolve("family").resolve("geni-people.tsv");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "profile-1\tハル 森田\n", StandardCharsets.UTF_8);
        String said = readGeni(store, json);
        Graph g = FamilyPeople.view(store);
        assertNotEquals(g.nodeIdOf("森田ハル"), g.nodeIdOf("ハル 森田"), "the book's 森田ハル and Geni's ハル 森田 stay two entries: " + said);
        assertTrue(!Files.exists(Graph.mergesFile(store)) || Files.readAllLines(Graph.mergesFile(store), StandardCharsets.UTF_8).stream().noneMatch(l -> l.contains("geni")),
                "no merge was made by the read");
    }
    /** A read that takes a profile's earlier, shorter name and its full name as one person says so, with the command that takes it back. */
    @Test
    void aReadThatJoinsAProfilesShortNameToItsFullNameSaysSoWithTheCommandThatTakesItBack(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyNameHistoryTest.file(store, "https://www.geni.com/people/Tom/6000000000001", List.of(new FamilyAccount.Fact("Tom Hale", "born-on", "1880", "", "Geni: Tom Hale born 1880")), List.of());
        Path f = store.root().resolve("family").resolve("geni-people.tsv");
        Files.createDirectories(f.getParent());
        Files.writeString(f, "profile-1\tTom Hale\n", StandardCharsets.UTF_8);
        String said = readGeni(store, """
                {"focus": {"id": "profile-1", "name": "Tom John Hale", "first_name": "Tom", "middle_name": "John", "last_name": "Hale", "gender": "male", "is_alive": false, "birth": {"date": {"year": 1880, "formatted_date": "1880"}}},
                 "nodes": {"profile-1": {"id": "profile-1"}}}""");
        Graph g = FamilyPeople.view(store);
        assertEquals(g.nodeIdOf("Tom Hale"), g.nodeIdOf("Tom John Hale"), said);
        assertTrue(said.contains("researchzosho graph unmerge \"Tom Hale\""), "the read says what it joined and how to take it back: " + said);
    }
}
