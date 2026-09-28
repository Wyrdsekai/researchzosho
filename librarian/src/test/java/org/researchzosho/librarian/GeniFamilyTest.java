package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;
import org.researchzosho.tools.Fetch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
/** A Geni profile's immediate family, as its API gives it, becomes people and relations by rule. */
class GeniFamilyTest {

    private static final String ANSWER = """
            {"focus": {"id": "profile-1", "name": "Isamu Morita", "is_alive": false, "occupation": "engineer",
               "birth": {"date": {"year": 1903, "formatted_date": "March 4, 1903"}, "location": {"city": "Tokyo", "country": "Japan"}},
               "death": {"date": {"year": 1990, "circa": true, "formatted_date": "circa 1990"}}},
             "nodes": {
               "profile-1": {"id": "profile-1", "first_name": "Isamu", "last_name": "Morita", "edges": {"union-9": {"rel": "partner"}, "union-8": {"rel": "child"}}},
               "profile-2": {"id": "profile-2", "name": "Fusa Morita", "is_alive": false},
               "profile-3": {"id": "profile-3", "first_name": "Ken", "last_name": "Morita", "is_alive": true},
               "profile-4": {"id": "profile-4", "name": "Gen Morita", "is_alive": false},
               "profile-5": {"id": "profile-5", "is_alive": true},
               "union-9": {"status": "spouse", "edges": {"profile-1": {"rel": "partner"}, "profile-2": {"rel": "partner"}, "profile-3": {"rel": "child"}, "profile-5": {"rel": "child"}}},
               "union-8": {"edges": {"profile-4": {"rel": "partner"}, "profile-1": {"rel": "child"}}}}}""";

    @Test
    void unionsBecomeMarriagesAndChildrenAndTheFocusKeepsItsDates() throws Exception {
        var answer = new ObjectMapper().readTree(ANSWER);
        FamilyAccount.Read read = GeniFamily.read(answer);
        String facts = read.facts().toString();
        assertTrue(facts.contains("subject=Isamu Morita, relation=born-in, object=Tokyo, Japan, date=March 4, 1903"), facts);
        assertTrue(facts.contains("subject=Isamu Morita, relation=died-on, object=about 1990"), facts);
        assertTrue(facts.contains("subject=Isamu Morita, relation=married-to, object=Fusa Morita"), facts);
        assertTrue(facts.contains("subject=Ken Morita, relation=child-of, object=Isamu Morita") && facts.contains("subject=Ken Morita, relation=child-of, object=Fusa Morita"), facts);
        assertTrue(facts.contains("subject=Isamu Morita, relation=child-of, object=Gen Morita"), facts);
        assertTrue(facts.contains("relation=occupation, object=engineer"), facts);
        assertEquals(4, read.people().size(), "a profile Geni gives no name for is not a person here: " + read.people());
        assertEquals(1, read.dropped().size());
        assertTrue(facts.contains("subject=Gen Morita, relation=life-event, object=died"), "Geni says he is not living: a death without a date: " + facts);
        assertFalse(facts.contains("subject=Ken Morita, relation=life-event"), "and says nothing of the living: " + facts);
        assertEquals(List.of("profile-2", "profile-3", "profile-4", "profile-5"), GeniFamily.relatives(answer));
    }

    @Test
    void aMaidenNameIsKeptAsAnotherName() throws Exception {
        var answer = new ObjectMapper().readTree("""
                {"focus": {"id": "profile-1", "first_name": "Ann", "last_name": "Ellis", "maiden_name": "Hart", "is_alive": false},
                 "nodes": {"profile-1": {"id": "profile-1"}, "profile-2": {"id": "profile-2", "first_name": "Tom", "last_name": "Ellis", "maiden_name": "Ellis", "is_alive": false}}}""");
        FamilyAccount.Read read = GeniFamily.read(answer);
        assertEquals(List.of("Ann Hart"), read.people().stream().filter(p -> p.name().equals("Ann Ellis")).findFirst().orElseThrow().also());
        assertEquals(List.of(), read.people().stream().filter(p -> p.name().equals("Tom Ellis")).findFirst().orElseThrow().also());
    }

    @Test
    void aProfileAddressGivesItsId() {
        assertEquals("6000000012345678901", GeniFamily.guidOf("https://www.geni.com/people/Isamu-Morita/6000000012345678901?through=6000000098765#/tab/source"));
        assertNull(GeniFamily.guidOf("https://www.geni.com/family-tree/index/6000000012345678901"));
        assertNull(GeniFamily.guidOf("https://example.org/people/x/6000000012345678901"));
    }

    @Test
    void oneProfileKeepsOneNameFromOneReadToTheNext() throws Exception {
        var labels = new LinkedHashMap<String, String>();
        var renamed = new ArrayList<String[]>();
        // first read: the son is in focus; his father arrives as a relative, with a first and a last name only, and so does a grandfather of the same name
        GeniFamily.read(new ObjectMapper().readTree("""
            {"focus": {"id": "profile-3", "name": "Ken Morita"}, "nodes": {
               "profile-3": {"id": "profile-3"}, "profile-1": {"id": "profile-1", "first_name": "Isamu", "last_name": "Morita", "is_alive": false},
               "profile-7": {"id": "profile-7", "first_name": "Isamu", "last_name": "Morita", "is_alive": false},
               "union-9": {"edges": {"profile-1": {"rel": "partner"}, "profile-3": {"rel": "child"}}}, "union-8": {"edges": {"profile-7": {"rel": "partner"}, "profile-1": {"rel": "child"}}}}}"""), labels, renamed);
        assertEquals("Isamu Morita", labels.get("profile-1"));
        assertTrue(labels.get("profile-7").startsWith("Isamu Morita (Geni "), "two profiles of one first and last name stay two people: " + labels);
        assertTrue(renamed.isEmpty());
        // second read: the father is in focus and has his full name
        FamilyAccount.Read second = GeniFamily.read(new ObjectMapper().readTree("""
            {"focus": {"id": "profile-1", "name": "Isamu Kenji Morita, Jr.", "is_alive": false}, "nodes": {
               "profile-1": {"id": "profile-1"}, "profile-3": {"id": "profile-3", "first_name": "Kenneth", "last_name": "Morita"},
               "union-9": {"edges": {"profile-1": {"rel": "partner"}, "profile-3": {"rel": "child"}}}}}"""), labels, renamed);
        assertEquals(1, renamed.size());
        assertArrayEquals(new String[]{"Isamu Morita", "Isamu Kenji Morita, Jr."}, renamed.get(0));
        assertTrue(second.facts().toString().contains("subject=Ken Morita, relation=child-of, object=Isamu Kenji Morita, Jr."), "the son keeps the name he was first filed under: " + second.facts());
        // third read: the grandfather's short name was given up by the father. A NEW profile of that first and last name must not take it over,
        // or everything said of the new man would land on the father
        GeniFamily.read(new ObjectMapper().readTree("""
            {"focus": {"id": "profile-3", "name": "Ken Morita"}, "nodes": {"profile-3": {"id": "profile-3"},
               "profile-99": {"id": "profile-99", "first_name": "Isamu", "last_name": "Morita"}, "union-5": {"edges": {"profile-99": {"rel": "partner"}, "profile-3": {"rel": "child"}}}}}"""), labels, renamed);
        assertTrue(labels.get("profile-99").startsWith("Isamu Morita (Geni "), "a name that was folded into somebody is never reused: " + labels.get("profile-99"));
    }

    @Test
    void aGeniReadSaysWhenAMaidenNameIsAnotherPersonsName(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Mary Hart", "born-in", "Leeds", "1850", "Mary Hart was born in Leeds in 1850")), List.of()), "file:///family/notes.txt", "an aunt");
        String json = "{\"focus\":{\"id\":\"profile-1\",\"first_name\":\"Mary\",\"last_name\":\"Ellis\",\"maiden_name\":\"Hart\",\"is_alive\":false,"
                + "\"birth\":{\"date\":{\"formatted_date\":\"1875\"},\"location\":{\"city\":\"York\"}}},\"nodes\":{\"profile-1\":{\"id\":\"profile-1\"}}}";
        GenealogyProfile.geniForTests = url -> new Fetch.Result(url, 200, json.getBytes(StandardCharsets.UTF_8), "application/json");
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            new GenealogyProfile().cli(store, new String[]{"researchzosho", "genealogy", "read", "https://www.geni.com/people/Mary-Ellis/6000000000001"});
        } finally { System.setOut(was); GenealogyProfile.geniForTests = null; }
        String said = out.toString(StandardCharsets.UTF_8);
        assertTrue(said.contains("\"Mary Hart\" was not kept as another name of Mary Ellis") && said.contains("researchzosho graph merge \"Mary Hart\" \"Mary Ellis\""), said);
        assertTrue(said.contains("because each such name is another person in your library"), "under a heading that says why: " + said);
        assertFalse(said.contains("It keeps a fact only when it can point to the exact words"), said);
    }
}
