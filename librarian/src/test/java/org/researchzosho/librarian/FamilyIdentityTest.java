package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.researchzosho.mcp.McpServer;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.LinkedHashMap;
import java.util.Map;
import org.researchzosho.drive.Declined;

import static org.junit.jupiter.api.Assertions.*;

class FamilyIdentityTest {

    private static FamilyAccount.Fact fact(String s, String r, String o) { return new FamilyAccount.Fact(s, r, o, "", "q"); }

    private static LibraryStore family(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田まり", "", List.of("Mari Morita"))), List.of(
                fact("森田まり", "child-of", "森田正一"), fact("森田まり", "born-on", "1975"), fact("森田まり", "born-in", "広島"),
                fact("森田正一", "born-on", "明治41年"), fact("森田正一", "died-on", "1990")), List.of()), "file:///notes.txt", "an aunt");
        return store;
    }

    private static final List<FamilyIdentity.Page> WEB = List.of(
            new FamilyIdentity.Page("https://books.example/author/mari-morita", "Mari Morita", "Mari Morita is the co-author of a book on bridges. Born in Hiroshima in 1975."),
            new FamilyIdentity.Page("https://films.example/name/1", "Mari Morita | Actress", "Mari Morita is an actress, known for two films."),
            new FamilyIdentity.Page("https://work.example/in/mari-morita", "Mari Morita - Engineer", "Bridge engineer. Author."),
            new FamilyIdentity.Page("https://names.example/morita", "People named Morita", "A list of 400 people named Morita."));

    private static final FamilyIdentity.Search SEARCH = q -> q.contains("Mari Morita") ? WEB : List.of();
    private static final Function<String, String> MODEL = prompt -> "rows 1,3: a bridge engineer and author, born in Hiroshima in 1975\nrows 2: an actress\nrows 9: somebody the search never returned";

    @Test
    void oneDeclinedPersonDoesNotStopTheLookUpOfTheOthers(@TempDir Path tmp) throws Exception {
        // M3: the model declines to sort one person's pages; that person is left, recorded and said, and the next person is looked up
        LibraryStore store = family(tmp);
        Function<String, String> model = prompt -> {
            if (prompt.contains("for the name 森田まり")) throw new Declined("placeholder-model", "I am not able to help with that request.", Declined.How.WORDS);
            return MODEL.apply(prompt);
        };
        int n = assertDoesNotThrow(() -> FamilyWho.findAll(store, List.of("森田まり", "森田正一"), q -> WEB, model, null));
        assertEquals(1, n, "the other person was looked up");
        assertNotNull(FamilyIdentity.forPerson(store, Graph.build(store), "森田正一"));
        assertNull(FamilyIdentity.forPerson(store, Graph.build(store), "森田まり"), "nothing was sorted for her without the model");
        Map<String, Declined> declined = new LinkedHashMap<>();
        FamilyWho.findAll(store, List.of("森田まり", "森田正一"), q -> WEB, model, null, declined);
        assertEquals(List.of("森田まり"), List.copyOf(declined.keySet()));
        assertTrue(declined.get("森田まり").statement().startsWith("The model this library uses (placeholder-model) declined to sort the web's pages about 森田まり"), declined.toString());
        assertTrue(Files.readString(store.circulationFile(), StandardCharsets.UTF_8).contains("who is who: 森田まり"));
    }

    @Test
    void thePeopleTheWebShowsAreLaidOutAndTheOneWhosePagesAgreeWithTheFamilyComesFirst(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        List<String> asked = new ArrayList<>();
        FamilyIdentity.Question q = FamilyIdentity.find(store, Graph.build(store), "森田まり", query -> { asked.add(query); return SEARCH.of(query); }, MODEL);
        assertEquals(List.of("\"森田まり\"", "\"Mari Morita\""), asked, "each written form, as a plain name");
        assertEquals("open", q.state());
        assertEquals(2, q.candidates().size(), "the list of names is nobody, and a row the model made up is dropped: " + q.candidates());
        FamilyIdentity.Candidate first = q.candidates().get(0);
        assertEquals("1", first.id());
        assertTrue(first.what().contains("bridge engineer"));
        assertEquals(2, first.pages().size());
        assertTrue(first.matches().contains("1975"), "the year the family gave is on the page: " + first.matches());
        assertTrue(q.candidates().get(1).matches().isEmpty());
        assertEquals(1, FamilyIdentity.open(store).size());
    }

    @Test
    void theFamilysWordGoesWithANameJoinedIntoAnotherAndComesBackWhenTheJoinIsTakenBack(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        FamilyIdentity.find(store, Graph.build(store), "森田まり", SEARCH, MODEL);
        FamilyIdentity.answer(store, "森田まり", List.of("1"), "confirmed");
        // the tree file gave her a name that tells her from a namesake, and the owner joins the notes' name into it
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("森田まり (born 1975)", "born-on", "1975")), List.of()), "file:///home/me/tree.ged", "a tree file");
        Graph.merge(store, "森田まり", "森田まり (born 1975)", "person");
        Graph g = Graph.build(store);
        assertEquals(0, FamilyWho.findAll(store, List.of("森田まり (born 1975)"), query -> { throw new AssertionError("searched again: " + query); }, MODEL, null), "she is not looked up and asked about again");
        // a new search under the joined name keeps the family's word
        FamilyIdentity.Question again = FamilyIdentity.find(store, g, "森田まり (born 1975)", query -> WEB, MODEL);
        assertEquals("confirmed", again.state(), again.toString());
        Files.delete(FamilyIdentity.file(store, "森田まり (born 1975)"));
        FamilyIdentity.Question q = FamilyIdentity.forPerson(store, g, "森田まり (born 1975)");
        assertNotNull(q);
        assertEquals("confirmed", q.state());
        assertTrue(FamilyIdentity.forQuestion(q).contains("https://books.example/author/mari-morita"), "her research starts from the page the family confirmed");
        // the join taken back: the answer is the notes' name's own again, and the tree file's name has its own
        Graph.unmerge(store, "森田まり", null, "person", "");
        assertEquals("confirmed", FamilyIdentity.forPerson(store, Graph.build(store), "森田まり").state());
    }

    @Test
    void theFamilysWordIsKeptGoesIntoTheQuestionAndOutlivesANewSearch(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        FamilyIdentity.find(store, Graph.build(store), "森田まり", SEARCH, MODEL);
        assertThrows(ProtocolError.class, () -> FamilyIdentity.answer(store, "森田まり", List.of("7"), "confirmed"));
        FamilyIdentity.Question said = FamilyIdentity.answer(store, "森田まり", List.of("1"), "confirmed");
        assertEquals("confirmed", said.state());
        assertTrue(FamilyIdentity.open(store).isEmpty());
        String text = FamilyIdentity.forQuestion(said);
        assertTrue(text.contains("It is known who this person is on the web") && text.contains("https://books.example/author/mari-morita") && text.contains("Start from these pages"), text);
        assertTrue(text.contains("other people of the same name") && text.contains("an actress (films.example)"), text);

        // searched again later, with a new page: the word given for a page stays with that page
        List<FamilyIdentity.Page> more = new ArrayList<>(WEB); more.add(new FamilyIdentity.Page("https://talks.example/mari", "Mari Morita speaks", "A talk on bridges."));
        FamilyIdentity.Question again = FamilyIdentity.find(store, Graph.build(store), "森田まり", q -> q.contains("Mari Morita") ? more : List.of(),
                p -> "rows 1,3,5: a bridge engineer and author\nrows 2: an actress");
        assertEquals("confirmed", again.state());
        assertEquals("yes", again.candidates().stream().filter(c -> c.what().contains("bridge")).findFirst().orElseThrow().said());
        assertEquals("no", again.candidates().stream().filter(c -> c.what().contains("actress")).findFirst().orElseThrow().said());
    }

    @Test
    void nobodyShownIsThemOrNobodyIsShownAtAll(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        FamilyIdentity.find(store, Graph.build(store), "森田まり", SEARCH, null);
        assertEquals(4, FamilyIdentity.read(store, "森田まり").candidates().size(), "with no model every page stands for itself");
        FamilyIdentity.Question rejected = FamilyIdentity.answer(store, "森田まり", List.of(), "none");
        String none = FamilyIdentity.forQuestion(rejected);
        assertTrue(none.contains("none of them is this person") && none.contains("nothing from these pages belongs in the answer"), none);
        for (FamilyIdentity.Candidate c : rejected.candidates()) assertTrue(none.contains(c.pages().get(0).url()), "each page the family turned down is named by its address: " + none);
        String aboutThem = none.substring(0, none.indexOf("On any other page"));
        assertFalse(aboutThem.contains("may be the same person"), "the pages turned down are not offered as maybe the same person: " + aboutThem);

        FamilyIdentity.Question father = FamilyIdentity.find(store, Graph.build(store), "森田正一", q -> List.of(), MODEL);
        assertEquals("nobody", father.state());
        assertEquals("", FamilyIdentity.forQuestion(father));
        assertTrue(FamilyIdentity.open(store).isEmpty());
    }

    @Test
    void aNameOfOneWordAndADescribedPersonAreNotSearchedFor() {
        Graph.Node n = new Graph.Node("p1", "person", "Thomas Ellis (the elder)", List.of("Tom", "the writer's father", "Thomas Ellis", "T. W. Ellis"), "", 0);
        assertEquals(List.of("Thomas Ellis", "T. W. Ellis"), FamilyIdentity.forms(n));
    }

    @Test
    void theSittingTakesANumberNoneLaterATellingAndCanBeLeft(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        Graph g = Graph.build(store);
        FamilyIdentity.find(store, g, "森田まり", SEARCH, MODEL);
        FamilyIdentity.find(store, g, "森田正一", q -> WEB, null);
        List<String> told = new ArrayList<>();
        ByteArrayOutputStream shown = new ByteArrayOutputStream();
        // the first person: something odd, a number that is not there, a telling, then the number; the second: stop
        BufferedReader typed = new BufferedReader(new StringReader("maybe\n9\ntell\nShe builds bridges in Osaka.\n1\nstop\n"));
        int answered = FamilyWho.sitting(store, FamilyIdentity.open(store), typed, new PrintStream(shown, true, StandardCharsets.UTF_8), p -> "born 1975", (person, text) -> told.add(person + ": " + text));
        String out = shown.toString(StandardCharsets.UTF_8);
        assertEquals(1, answered);
        assertEquals(List.of("森田まり: She builds bridges in Osaka."), told);
        assertTrue(out.contains("Person 1 of 2") && out.contains("Their pages match these facts from your library: 1975") && out.contains("There is no number 9") && out.contains("\"maybe\" is not an answer"), out);
        assertEquals("confirmed", FamilyIdentity.read(store, "森田まり").state());
        assertEquals("open", FamilyIdentity.read(store, "森田正一").state(), "left by stop: it waits");
    }

    @Test
    void theSearchToolsTextIsReadBackAsPages() {
        List<FamilyIdentity.Page> pages = FamilyWho.pages("1. Mari Morita - Engineer\n   https://work.example/in/mari  [web, trusted by the person]\n   Bridge engineer. Author.\n2. No snippet here\n   https://plain.example/x  [web]\n3. Last\n   https://last.example/  [web]\n   The end.\nNOTE: a note from the tool\n");
        assertEquals(3, pages.size());
        assertEquals(new FamilyIdentity.Page("https://work.example/in/mari", "Mari Morita - Engineer", "Bridge engineer. Author."), pages.get(0));
        assertEquals(new FamilyIdentity.Page("https://plain.example/x", "No snippet here", ""), pages.get(1));
        assertEquals("The end.", pages.get(2).snippet());
    }

    @Test
    void theChatAndOtherProgramsAskThroughOneToolAndTheAnswerIsThePersonsOwn(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        LibraryProtocol p = new LibraryProtocol(store);
        p.whoSearch = () -> SEARCH; p.whoModel = () -> MODEL;
        ObjectMapper m = new ObjectMapper();
        Function<String, ObjectNode> call = json -> { try { ObjectNode a = (ObjectNode) m.readTree(json); a.putObject("patron").put("did", "person").put("name", "me").put("runtime", "cli"); return p.who(a); } catch (Exception e) { throw new IllegalStateException(e.getMessage(), e); } };

        assertTrue(call.apply("{\"op\":\"list\"}").path("summary").asText().contains("has not looked anybody"));
        ObjectNode found = call.apply("{\"op\":\"find\",\"person\":\"Mari Morita\"}");   // typed in Latin letters: found as the library writes the name
        assertEquals("森田まり", found.path("question").path("person").asText());
        assertEquals(2, found.path("question").path("entries").size());
        assertEquals("1975", found.path("question").path("entries").get(0).path("also_says_as_the_family_does").get(0).asText());
        assertTrue(found.path("question").path("entries").get(0).path("pages").get(0).asText().startsWith("https://"));
        assertEquals(1, call.apply("{\"op\":\"list\"}").path("waiting").asInt());
        assertEquals("森田まり", call.apply("{\"op\":\"show\"}").path("question").path("person").asText(), "with nobody named: the first who waits");

        call.apply("{\"op\":\"tell\",\"person\":\"森田まり\",\"text\":\"She builds bridges in Osaka.\"}");
        ObjectNode said = call.apply("{\"op\":\"answer\",\"person\":\"森田まり\",\"is\":\"1\"}");
        assertEquals("confirmed", said.path("question").path("state").asText());
        assertTrue(said.path("summary").asText().contains("bridge engineer"));
        assertEquals(0, said.path("waiting").asInt());
        assertTrue(FamilyIdentity.forQuestion(FamilyIdentity.read(store, "森田まり")).contains("She builds bridges in Osaka."));
        assertThrows(IllegalStateException.class, () -> call.apply("{\"op\":\"answer\",\"person\":\"nobody of that name\",\"none\":true}"));

        // the who questions need write access: someone who may only read is refused, even the list
        ObjectNode reader = m.createObjectNode().put("op", "list"); reader.putObject("patron").put("did", "did:key:stranger").put("name", "s").put("runtime", "mcp");
        Patrons.set(store, "did:key:stranger", "S", Patrons.Level.read);
        assertThrows(ProtocolError.class, () -> p.who(reader));

        // the chat of a library that holds a family and the MCP server both offer it
        assertTrue(Librarian.toolNames(store).contains("library_who"));
        boolean served = false; for (var tool : McpServer.allTools()) if (tool.path("name").asText().equals("library_who")) served = true;
        assertTrue(served);
        assertTrue(Librarian.tools(store).toString().contains("library_who"));
    }

    @Test
    void aPageTheLibraryReadThePersonsFactsFromIsThePersonWithoutAsking(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田まり", "died-in", "大阪")), List.of()), "https://www.trees.example/people/Mari-Morita/123?utm_source=share", "a family site");
        List<FamilyIdentity.Page> web = List.of(new FamilyIdentity.Page("https://trees.example/people/Mari-Morita/123", "Mari Morita", "family tree"), WEB.get(1));
        FamilyIdentity.Question q = FamilyIdentity.find(store, Graph.build(store), "森田まり", x -> web, p -> "rows 1: a person on a family tree site\nrows 2: an actress");
        assertEquals("confirmed", q.state(), "the library read her facts from that page, so it knows: " + q);
        assertEquals("source", q.candidates().get(0).said());
        assertTrue(FamilyIdentity.open(store).isEmpty());
        assertTrue(FamilyIdentity.forQuestion(q).contains("trees.example/people/Mari-Morita/123"));
    }

    @Test
    void aRelativeNamedOnThePageCountsOnceAndComesBeforeYearsAndPlaces(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田まり", "", List.of("Mari Morita")),
                        new FamilyAccount.Person("森田正一", "", List.of("Shoichi Morita"))), List.of(
                fact("森田まり", "child-of", "森田正一"), fact("森田まり", "married-to", "Hale"), fact("森田まり", "born-on", "1935"), fact("森田まり", "born-in", "Hiroshima"),
                fact("森田正一", "born-on", "1905")), List.of()), "file:///notes.txt", "an aunt");
        Graph g = Graph.build(store);
        List<FamilyIdentity.Known> known = FamilyIdentity.facts(g, g.nodeIdOf("森田まり"));
        assertEquals(1, known.stream().filter(k -> k.kind().equals("relative")).count(), "a family name alone is no relative to look for: " + known);
        List<FamilyIdentity.Page> pages = List.of(
                new FamilyIdentity.Page("https://a.example/1", "Mari Morita (1935-2001)", "Born in Hiroshima in 1935. Married a Mr Hale."),
                new FamilyIdentity.Page("https://b.example/2", "Mari Morita", "Daughter of Shoichi Morita (森田正一), the potter."));
        assertEquals(List.of("森田正一"), FamilyIdentity.matching(known, List.of(pages.get(1))), "one relative, counted once for two of his names");
        assertEquals(List.of("1935", "Hiroshima"), FamilyIdentity.matching(known, List.of(pages.get(0))), "the one-word name Hale is not matched");
        FamilyIdentity.Question q = FamilyIdentity.find(store, g, "森田まり", query -> pages, null);
        assertEquals("https://b.example/2", q.candidates().get(0).pages().get(0).url(), "a page naming a parent comes before one with two other facts: " + q.candidates());
        assertEquals(List.of("森田正一"), q.candidates().get(0).relatives());
        assertEquals(List.of("森田正一"), FamilyIdentity.read(store, "森田まり").candidates().get(0).relatives(), "kept with the answer");
        String shown = FamilyWho.shown(q, "");
        assertTrue(shown.contains("Their pages name a relative your library has for this person: 森田正一") && shown.contains("Their pages match these facts from your library: 1935, Hiroshima"), shown);
    }

    @Test
    void aRecordsNumberInTheAddressKeepsTwoRecordsApart() {
        assertEquals(FamilyIdentity.samePage("https://www.records.example/record/?id=12&utm_source=x&page=2#top"), FamilyIdentity.samePage("http://records.example/record?page=2&id=12"));
        assertNotEquals(FamilyIdentity.samePage("https://records.example/record?id=12"), FamilyIdentity.samePage("https://records.example/record?id=13"));
        assertEquals("records.example/person/7", FamilyIdentity.samePage("https://records.example/person/7/?fbclid=abc"));
    }
}
