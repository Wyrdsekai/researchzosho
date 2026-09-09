package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The graph: findings with triples are edges; identity is the person's act; the map walks it; private stays private. */
class GraphTest {

    private static final ObjectMapper M = new ObjectMapper();

    static Finding f(LibraryStore store, String s, String p, String o, Finding.State st) throws Exception {
        Finding x = new Finding(store.nextFindingId(s + " " + p), s + " " + p + " " + o, List.of(), st, Finding.ClaimType.extraction,
                Finding.Confidence.high, "test", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://arxiv.org/abs/1", "n/a", "cited")), List.of(), null, s + " " + p + " " + o + ".\n",
                new Finding.Triple(s, p, o), List.of());
        store.write(x);
        return x;
    }

    @Test
    void theDaemonCarriesItsOwnFaviconAndTheMapPageLinksIt() {
        byte[] icon = LibrarianDaemon.favicon();
        assertTrue(icon.length > 100 && icon[1] == 'P' && icon[2] == 'N' && icon[3] == 'G', "the bundled kura is a PNG");
        assertTrue(MapPage.body("").contains("id=\"c\"></canvas>"), "the map body carries its canvas; the frame carries the icon");
    }

    @Test
    void findingsWithTriplesAreEdgesAndTheMapWalksThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        f(store, "Arthur Ellis", "migrated to", "Lyttelton", Finding.State.accepted);
        f(store, "Arthur Ellis", "patented", "Journey route mapping", Finding.State.draft);
        f(store, "Journey route mapping", "is a kind of", "shortest-path algorithm", Finding.State.accepted);
        f(store, "Rose Morgan", "married to", "Arthur Ellis", Finding.State.accepted);
        Finding retired = f(store, "Arthur Ellis", "born in", "Mars", Finding.State.retired);
        Graph g = Graph.build(store);
        assertEquals(4, g.edges().size(), "a retired finding is no edge");
        assertNotNull(g.node("arthur ellis"));
        Graph.Neighbourhood one = g.around("arthur ellis", 1, 25, true);
        assertEquals("arthur ellis", one.focus().id());
        assertEquals(4, one.nodes().size(), "arthur + lyttelton + the patent + rose");
        assertEquals(3, one.edges().size());
        Graph.Neighbourhood two = g.around("Arthur  ELLIS.", 2, 25, true);
        assertTrue(two.nodes().stream().anyMatch(n -> n.id().equals("shortest-path algorithm")), "two hops: the family shelf meets the algorithm shelf");
        assertTrue(Graph.render(two).contains("—patented→"));
        // k caps the neighbourhood, nearest first
        assertEquals(2, g.around("arthur ellis", 2, 2, true).nodes().size());
        // an entry id focuses on its triple's subject
        assertEquals("arthur ellis", g.around(retired.id().replace(retired.id(), one.edges().get(0).findingId()), 1, 5, true).focus().id());
        assertNull(g.around("nobody here", 1, 5, true).focus());
    }

    @Test
    void identityIsThePersonsActAndPredicatesResolveThroughTheVocabulary(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        f(store, "A. Ellis", "spouse of", "Rose Morgan", Finding.State.accepted);
        f(store, "Arthur Ellis", "married to", "Rose Morgan", Finding.State.accepted);
        Graph.predicate(store, "married-to", "was married to", List.of("spouse of", "married to"));
        Graph before = Graph.build(store);
        assertEquals(3, before.nodes().size(), "two spellings are two nodes until the person says otherwise");
        assertTrue(before.edges().stream().allMatch(e -> e.predicate().equals("married-to")), "both wordings resolve to one predicate");
        String proposals = Graph.propose(store);
        assertTrue(Files.readString(Graph.dir(store).resolve("proposals.md")).contains("(none)") || proposals.startsWith("0"), "different names are not proposed as one");
        Graph.merge(store, "A. Ellis", "Arthur Ellis", "person");
        Graph after = Graph.build(store);
        assertEquals(2, after.nodes().size());
        assertEquals("arthur ellis", after.nodeIdOf("a. ellis"));
        assertEquals(2, after.node("arthur ellis").degree());
        assertTrue(after.node("arthur ellis").aliases().contains("A. Ellis"));
        assertTrue(Changes.since(store, 0, 10).stream().anyMatch(c -> c.event().equals("merged")));
        Graph.alias(store, "Arthur Ellis", List.of("アーサー・エリス"));
        assertEquals("arthur ellis", Graph.build(store).nodeIdOf("アーサー・エリス"));
        Graph.link(store, "Arthur Ellis", "Q99999");
        assertEquals("Q99999", Graph.build(store).node("arthur ellis").wikidata());
    }

    @Test
    void proposalsNameNearIdenticalNodesButApplyNothing(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        f(store, "Éstevan Szigety", "authored", "Paper A", Finding.State.accepted);
        f(store, "Estevan Szigety", "authored", "Paper B", Finding.State.accepted);
        f(store, "Szigety, Estevan", "authored", "Paper C", Finding.State.accepted);
        String out = Graph.propose(store);
        String text = Files.readString(Graph.dir(store).resolve("proposals.md"), StandardCharsets.UTF_8);
        assertTrue(text.contains("≈"), text);
        assertTrue(out.startsWith("3 proposal(s)"), out);
        assertEquals(6, Graph.build(store).nodes().size(), "nothing merged by the crew");
    }

    @Test
    void aSubmissionWithATripleIsAnEdgeAndTheCrewFillsTheOthers(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Patrons.setDefault(store, Patrons.Level.write);
        LibraryProtocol p = new LibraryProtocol(store);
        var r = p.submit((com.fasterxml.jackson.databind.node.ObjectNode) M.readTree("{\"claim\":\"Arthur Ellis holds NZ patent 000001 on a route-mapping method.\",\"sources\":[\"https://patents.example/NZ000001\"],\"triple\":{\"subject\":\"Arthur Ellis\",\"predicate\":\"patented\",\"object\":\"route-mapping method\"}}"));
        assertEquals("route-mapping method", store.finding(r.get("id").asText()).triple().object());
        assertEquals(1, Graph.build(store).edges().size());
        ProtocolError bad = assertThrows(ProtocolError.class, () -> p.submit((com.fasterxml.jackson.databind.node.ObjectNode) M.readTree("{\"claim\":\"A claim long enough to be a claim about something.\",\"sources\":[\"https://x.example/1\"],\"triple\":{\"subject\":\"A\"}}")));
        assertEquals("invalid_args", bad.code);
        var plain = p.submit((com.fasterxml.jackson.databind.node.ObjectNode) M.readTree("{\"claim\":\"Rose Morgan taught at Otago Girls High School from 1960.\",\"sources\":[\"https://x.example/2\"]}"));
        assertNull(store.finding(plain.get("id").asText()).triple());
        Triples.Outcome o = Triples.fill(store, claim -> claim.contains("Rose") ? "{\"subject\":\"Rose Morgan\",\"predicate\":\"taught at\",\"object\":\"Otago Girls High School\"}" : "{\"triple\": null}", 40);
        assertEquals(1, o.asked()); assertEquals(1, o.filled());
        assertEquals("Otago Girls High School", store.finding(plain.get("id").asText()).triple().object());
        assertEquals(0, Triples.fill(store, claim -> { throw new AssertionError("asked twice"); }, 40).asked(), "a finding is asked once");
        assertEquals(2, Graph.build(store).edges().size());
    }

    @Test
    void enablingAProfileSurvivesTheIdBeingMinted(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Profiles.enable(store, "genealogy");   // before any identity() call
        assertTrue(store.identity().id().startsWith("lib_"));
        assertTrue(Profiles.isEnabled(store, "genealogy"), "minting the id must keep the profiles line");
        String text = Files.readString(store.root().resolve("catalog").resolve("library.md"));
        assertTrue(text.contains("id: lib_") && text.contains("profiles: science, genealogy"), text);
    }

    @Test
    void privateNodesAreShownOnlyToThePerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        f(store, "Mara Ellis", "child of", "Simon Ellis", Finding.State.accepted);
        Graph.setKind(store, "Mara Ellis", "person", true);
        Graph.setKind(store, "Simon Ellis", "person", false);
        LibraryProtocol p = new LibraryProtocol(store);
        var empty = p.map((com.fasterxml.jackson.databind.node.ObjectNode) M.readTree("{\"focus\":\"\"}"));
        assertTrue(empty.path("suggestions").size() > 0, "an empty focus lists the names the map knows best: " + empty);
        assertTrue(empty.path("node").isNull());
        var partial = p.map((com.fasterxml.jackson.databind.node.ObjectNode) M.readTree("{\"focus\":\"" + empty.path("suggestions").get(0).path("label").asText().split(" ")[0].toLowerCase() + "\"}"));
        assertFalse(partial.path("holds_nothing").asBoolean(), "a word of a name finds the nearest name: " + partial);
        assertTrue(partial.hasNonNull("resolved_from") || partial.path("node").path("label").asText().equalsIgnoreCase(empty.path("suggestions").get(0).path("label").asText()), partial.toString());
        var none = p.map((com.fasterxml.jackson.databind.node.ObjectNode) M.readTree("{\"focus\":\"nobody-by-that-name\"}"));
        assertTrue(none.path("holds_nothing").asBoolean() && none.path("suggestions").size() > 0, "and so does a name that finds nothing");
        var person = p.map((com.fasterxml.jackson.databind.node.ObjectNode) M.readTree("{\"focus\":\"Simon Ellis\",\"patron\":{\"did\":\"person\"}}"));
        assertEquals(2, person.get("nodes").size(), "the person sees the living child");
        var patron = p.map((com.fasterxml.jackson.databind.node.ObjectNode) M.readTree("{\"focus\":\"Simon Ellis\"}"));
        assertEquals(1, patron.get("nodes").size(), "another patron sees no private node");
        var hidden = p.map((com.fasterxml.jackson.databind.node.ObjectNode) M.readTree("{\"focus\":\"Mara Ellis\"}"));
        assertTrue(hidden.get("holds_nothing").asBoolean(), "a private focus holds nothing for a patron");
        assertEquals("person", person.get("node").get("kind").asText());
        assertEquals(LibraryProtocol.CONTRACT, person.get("contract").asText());
    }

    @Test
    void subjectsAreHubsOnTheMap(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Files.writeString(store.subjectsFile(), "# Subjects\n\n- film--tokyo — filming in Tokyo\n", StandardCharsets.UTF_8);
        Finding x = new Finding(store.nextFindingId("akasaka"), "Tokyo Vice filmed in Akasaka", List.of("film--tokyo"), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.high, "test", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://a.example/1", "n/a", "cited")), List.of(), null, "Tokyo Vice filmed in Akasaka.\n",
                new Finding.Triple("Tokyo Vice", "filmed in", "Akasaka"), List.of());
        store.write(x);
        Graph g = Graph.build(store);
        Graph.Node subject = g.node("subject:film--tokyo");
        assertNotNull(subject); assertEquals("subject", subject.kind()); assertEquals("filming in Tokyo", subject.label()); assertEquals(2, subject.degree());
        assertTrue(g.edges().stream().anyMatch(e -> e.predicate().equals("is filed under") && e.to().equals("subject:film--tokyo") && e.findingId().equals(x.id())));
        // asked by its label or its slug, the subject is the focus and its names are around it
        for (String ask : new String[]{"filming in Tokyo", "film--tokyo"}) {
            Graph.Neighbourhood nb = g.around(ask, 1, 25, true);
            assertNotNull(nb.focus(), ask); assertEquals("subject:film--tokyo", nb.focus().id());
            assertEquals(3, nb.nodes().size(), "the subject and its two names: " + nb.nodes());
        }
        // and a name still leads to its subject
        Graph.Neighbourhood nb = g.around("Akasaka", 1, 25, true);
        assertTrue(nb.nodes().stream().anyMatch(n -> n.kind().equals("subject")));
    }
}
