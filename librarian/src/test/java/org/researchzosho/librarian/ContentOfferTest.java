package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.mcp.McpServer;
import org.researchzosho.tools.ContentPolicy;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The offer to let in, for one question's run, what the library leaves out by default. A stub judge reads a placeholder question as
 * needing it when the question carries a marker word; no real material is anywhere. A clear yes lets it into that run alone; Enter, no and
 * no answer leave it out; a yes is never remembered; the model can never turn it on; a field's question and this one are asked one after
 * the other; a program names it with allow, is told how when it did not, and cannot name what the library does not know.
 */
class ContentOfferTest {

    static final ObjectMapper M = new ObjectMapper();
    /** A placeholder question the stub reads as needing what the library leaves out by default. */
    static final String NEEDS = "What does the placeholder archive hold about the subject marked MARKER-NEEDS?";
    static final String OFFER_LINE = "This question may need material the library leaves out of research by default: pornography, and gore: pictures or detailed descriptions of mutilated or dead bodies. "
            + "A yes lets it into this question's research run only; every other run leaves it out as before. Let it in for this question? (y/N)";

    @TempDir Path tmp;
    final List<String> asked = new ArrayList<>();

    @BeforeEach void judge() {
        ContentJudge.use(new ContentJudge(null, messages -> {
            String c = messages.get(0).path("content").asText();
            asked.add(c);
            return c.contains("MARKER-NEEDS") && c.contains(ContentOffer.NEEDS_EXPLICIT) ? "yes" : "no";
        }));
    }

    @AfterEach void back() { ContentJudge.use(null); Interaction.OVERRIDE = null; Interaction.INPUT = null; }

    LibraryStore library(String name) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve(name)); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    Librarian chat(LibraryStore store, List<ObjectNode> steps, List<ArrayNode> seen) throws Exception {
        return new Librarian(store, LibrarianChatTest.scripted(steps, seen), Librarian.person(), Librarian.Session.open(store));
    }

    static ObjectNode filed(LibraryStore store) throws Exception { return GenealogyOfferTest.filed(store); }

    // ---- the chat ----

    @Test
    void theChatAsksAndEnterIsNo() throws Exception {
        LibraryStore store = library("lib");
        List<ArrayNode> seen = new ArrayList<>();
        Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + NEEDS + "\"}"), LibrarianChatTest.say("I have filed it.")), seen);
        String reply = lib.say("Find out what the placeholder archive holds");
        assertTrue(reply.endsWith(OFFER_LINE), reply);
        ObjectNode job = filed(store);
        assertEquals(Jobs.OFFERED, job.path("state").asText());
        assertEquals("[\"explicit\"]", job.path("offered_content").toString());
        assertFalse(job.path("args").has("allow"));
        assertFalse(job.has("offered"), "not a field's question");
        assertTrue(Jobs.view(job).path("waiting").asText().contains("whether to let in pornography, and gore"), Jobs.view(job).toString());
        int modelCalls = seen.size();
        String no = lib.say("");   // Enter
        assertEquals(modelCalls, seen.size(), "the answer is read before the model sees anything");
        assertTrue(no.startsWith("The question is researched with that material left out. Research run " + job.path("job_id").asText() + " has started"), no);
        ObjectNode after = new Jobs(store, j -> "").get(job.path("job_id").asText());
        assertEquals("queued", after.path("state").asText());
        assertFalse(after.path("args").has("allow"), "Enter is no");
        assertFalse(after.path("args").has("field"), "and never a field");
        for (String other : List.of("n", "no", "sure", "maybe later")) {
            LibraryStore s = library("lib-" + other.replace(' ', '-'));
            Librarian l = chat(s, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + NEEDS + "\"}"), LibrarianChatTest.say("Filed."), LibrarianChatTest.say("Noted.")), new ArrayList<>());
            l.say("Find out what the placeholder archive holds");
            l.say(other);
            assertFalse(filed(s).path("args").has("allow"), other + " is no");
        }
    }

    @Test
    void aClearYesLetsItIntoThatRunAloneAndIsNeverRemembered() throws Exception {
        LibraryStore store = library("lib");
        Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + NEEDS + "\"}"), LibrarianChatTest.say("Filed."),
                LibrarianChatTest.tool("library_research", "{\"question\": \"" + NEEDS + "\"}"), LibrarianChatTest.say("Filed again.")), new ArrayList<>());
        lib.say("Find out what the placeholder archive holds");
        String first = filed(store).path("job_id").asText();
        String yes = lib.say("y");
        assertTrue(yes.startsWith("That material is let into this question's research run only. Research run " + first + " has started"), yes);
        ObjectNode job = new Jobs(store, j -> "").get(first);
        assertEquals("[\"explicit\"]", job.path("args").path("allow").toString());
        assertEquals("chat-yes", job.path("args").path("allow_how").asText());
        assertEquals("[\"explicit\"]", Jobs.view(job).path("allow").toString(), "the job view says what the run lets in");
        // the same question again, in the same conversation: asked again, never taken from the earlier yes
        String again = lib.say("Look it up once more");
        assertTrue(again.endsWith(OFFER_LINE), again);
        ObjectNode second = filed(store);
        assertNotEquals(first, second.path("job_id").asText());
        assertEquals(Jobs.OFFERED, second.path("state").asText());
        assertFalse(second.path("args").has("allow"), "a remembered yes is never applied");
    }

    @Test
    void theModelCanNeverTurnItOn() throws Exception {
        LibraryStore store = library("lib");
        ContentJudge.use(new ContentJudge(null, m -> "no"));   // nothing about the question calls for an offer
        Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"How were the placeholder gears cut?\", \"allow\": [\"explicit\", \"howto\"]}"),
                LibrarianChatTest.say("Filed.")), new ArrayList<>());
        lib.say("Find out how the placeholder gears were cut");
        ObjectNode job = filed(store);
        assertEquals("queued", job.path("state").asText());
        assertFalse(job.path("args").has("allow"), "the model's allow is taken out of its call: " + job);
        for (JsonNode t : Librarian.tools(store)) assertFalse(t.path("function").path("parameters").path("properties").has("allow"), "the chat's tools do not offer it: " + t);
        // nor through a batch tool: its runs are filed the chat's way, with what the model passed taken out
        Librarian items = chat(store, List.of(LibrarianChatTest.tool("library_items", "{\"text\": \"placeholder item one\\nplaceholder item two\", \"as\": \"runs\", \"allow\": [\"explicit\"]}"),
                LibrarianChatTest.say("Filed.")), new ArrayList<>());
        items.say("Research each of these");
        for (ObjectNode j : new Jobs(store, x -> "").active()) assertFalse(j.path("args").has("allow"), j.toString());
    }

    @Test
    void aFieldsQuestionAndThisOneAreAskedOneAfterTheOther() throws Exception {
        ContentJudge.use(new ContentJudge(null, m -> m.get(0).path("content").asText().contains(ContentOffer.NEEDS_EXPLICIT) ? "yes" : "no"));
        LibraryStore store = library("lib");
        Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + GenealogyOfferTest.FAMILY + "\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>());
        String reply = lib.say("Find out who my great-grandfather's parents were");
        assertTrue(reply.endsWith(GenealogyOfferTest.ASK_LINE), "the field's question first: " + reply);
        assertFalse(reply.contains("Let it in"), "one question at a time: " + reply);
        String id = filed(store).path("job_id").asText();
        String afterField = lib.say("y");
        assertTrue(afterField.startsWith("The question is researched in genealogy mode."), afterField);
        assertFalse(afterField.contains("has started"), "the run waits for its second answer: " + afterField);
        assertTrue(afterField.endsWith("Let it in for this question? (y/N)"), "then the other question: " + afterField);
        ObjectNode waiting = new Jobs(store, j -> "").get(id);
        assertEquals(Jobs.OFFERED, waiting.path("state").asText());
        assertEquals("genealogy", waiting.path("args").path("field").asText());
        String afterContent = lib.say("");
        assertTrue(afterContent.startsWith("The question is researched with that material left out. Research run " + id + " has started"), afterContent);
        ObjectNode done = new Jobs(store, j -> "").get(id);
        assertEquals("queued", done.path("state").asText());
        assertEquals("genealogy", done.path("args").path("field").asText(), "the field's yes stands");
        assertFalse(done.path("args").has("allow"), "the second answer was Enter");
        assertFalse(lib.waitingForAnswer());
    }

    @Test
    void theEndOfTheWaitAndLeavingAreNo() throws Exception {
        LibraryStore store = library("lib");
        Librarian lib = chat(store, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + NEEDS + "\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>());
        lib.say("Find out what the placeholder archive holds");
        Jobs jobs = new Jobs(store, j -> "");
        ObjectNode job = filed(store);
        assertTrue(jobs.expire(job));
        ObjectNode after = jobs.get(job.path("job_id").asText());
        assertEquals("queued", after.path("state").asText());
        assertEquals("none", after.path("content_answered").asText());
        assertFalse(after.path("args").has("allow"), "the wait ending is no");
        LibraryStore other = library("lib2");
        Librarian left = chat(other, List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + NEEDS + "\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>());
        left.say("Find out what the placeholder archive holds");
        left.leaveUnanswered();
        assertEquals("queued", filed(other).path("state").asText());
        assertFalse(filed(other).path("args").has("allow"));
    }

    // ---- the command line ----

    private String cli(Path home, String... words) throws Exception {
        String realHome = System.getProperty("user.home");
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setProperty("user.home", home.toString());
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            String[] args = new String[words.length + 1];
            args[0] = "librarian";
            System.arraycopy(words, 0, args, 1, words.length);
            LibrarianCli.run(args, "http://127.0.0.1:1", "m");
        } finally { System.setOut(was); System.setProperty("user.home", realHome); }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theResearchCommandAsksAtATerminalAndTellsAScript() throws Exception {
        Path home = tmp.resolve("home");
        LibraryStore store = new LibraryStore(home.resolve("researchzosho-library")); store.init();
        // at a terminal, Enter: no
        Interaction.OVERRIDE = true; Interaction.INPUT = new BufferedReader(new StringReader("\n"));
        String said = cli(home, "research", "ask", NEEDS);
        assertTrue(said.contains(OFFER_LINE), said);
        assertFalse(filed(store).path("args").has("allow"), "Enter is no");
        // at a terminal, y: this run only
        Interaction.INPUT = new BufferedReader(new StringReader("y\n"));
        said = cli(home, "research", "ask", NEEDS);
        assertEquals("[\"explicit\"]", filed(store).path("args").path("allow").toString());
        assertEquals("cli-yes", filed(store).path("args").path("allow_how").asText());
        assertTrue(said.contains("This run lets in pornography, and gore: pictures or detailed descriptions of mutilated or dead bodies, for this question only, because you asked for it."), said);
        // the same question again: asked again, the earlier yes is not applied
        Interaction.INPUT = new BufferedReader(new StringReader("\n"));
        said = cli(home, "research", "ask", NEEDS);
        assertTrue(said.contains(OFFER_LINE), said);
        assertFalse(filed(store).path("args").has("allow"));
        // in a script: nothing is read, the run goes with the material left out, and the flag that lets it in is printed ready to paste
        Interaction.OVERRIDE = false; Interaction.INPUT = new BufferedReader(new StringReader("y\n"));
        said = cli(home, "research", "ask", NEEDS);
        assertFalse(said.contains("(y/N)"), said);
        assertFalse(filed(store).path("args").has("allow"));
        assertTrue(said.contains("It was sent with that material left out. To let it in for this question only, send it again with --allow explicit: researchzosho research ask '" + NEEDS + "' --allow explicit"), said);
        assertEquals("y", Interaction.INPUT.readLine(), "a script's input is never read");
        // asked for on the command line
        cli(home, "research", "ask", "How were the placeholder gears cut?", "--allow", "howto");
        assertEquals("[\"howto\"]", filed(store).path("args").path("allow").toString());
        assertEquals("cli-flag", filed(store).path("args").path("allow_how").asText());
    }

    // ---- a program, over MCP or HTTP ----

    static ObjectNode ask(String question) {
        ObjectNode a = M.createObjectNode(); a.put("question", question);
        a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        return a;
    }

    @Test
    void aProgramNamesItWithAllowAndIsToldHowWhenItDidNot() throws Exception {
        LibraryStore store = library("lib");
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode told = p.research(ask(NEEDS));
        assertEquals("queued", told.path("state").asText());
        assertFalse(filed(store).path("args").has("allow"), "nobody can answer: the run goes with the material left out");
        JsonNode s = told.path("content_suggestion");
        assertEquals("[\"explicit\"]", s.path("allow").toString());
        assertTrue(s.path("why").asText().startsWith("This question may need material the library leaves out of research by default"), s.toString());
        assertEquals("Show this to the person and ask them whether to let it in for this question; send the same question again with allow: [\"explicit\"] only if the person says yes.", s.path("how").asText(),
                "the person's to answer: a host's model is told to ask them, never to send it for them");

        ObjectNode withAllow = ask(NEEDS); withAllow.putArray("allow").add("explicit");
        ObjectNode r = p.research(withAllow);
        assertFalse(r.has("content_suggestion"), "it named what it needs");
        assertEquals("[\"explicit\"]", r.path("allow").toString());
        assertEquals("[\"explicit\"]", filed(store).path("args").path("allow").toString());
        assertEquals("mcp-allow", filed(store).path("args").path("allow_how").asText());

        for (String bad : List.of("[\"everything\"]", "{\"explicit\": true}", "[\"explicit\", \"howto\", \"self-harm\", \"explicit\"]")) {
            ObjectNode b = ask("How were the placeholder gears cut?"); b.set("allow", M.readTree(bad));
            ProtocolError e = assertThrows(ProtocolError.class, () -> p.research(b), bad);
            assertEquals("invalid_args", e.code, bad);
            assertTrue(e.getMessage().contains("\"explicit\"") && e.getMessage().contains("\"howto\""), "it says what allow takes: " + e.getMessage());
        }
        // a batch copies allow onto every run it files, as it copies field
        ObjectNode items = M.createObjectNode(); items.put("text", "placeholder item one\nplaceholder item two"); items.put("as", "runs"); items.putArray("allow").add("howto");
        items.set("patron", ask("x").get("patron"));
        p.items(items);
        int withHowto = 0;
        for (ObjectNode j : new Jobs(store, x -> "").active()) if (j.path("args").path("question").asText().contains("placeholder item")) { assertEquals("[\"howto\"]", j.path("args").path("allow").toString()); withHowto++; }
        assertTrue(withHowto > 0);
    }

    @Test
    void aBatchCarriesTheContentSuggestionOnceWithItsQuestion() throws Exception {
        LibraryStore store = library("lib");
        ObjectNode questions = M.createObjectNode(); questions.put("text", NEEDS + "\n" + "A placeholder question about how the gears were cut?"); questions.put("as", "runs");
        questions.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        ObjectNode batch = new LibraryProtocol(store).questions(questions);
        JsonNode s = batch.path("content_suggestion");
        assertEquals("[\"explicit\"]", s.path("allow").toString(), batch.toString());
        assertEquals(NEEDS, s.path("question").asText());
        assertTrue(s.path("how").asText().endsWith("only if the person says yes."), s.toString());
    }

    @Test
    void everyToolThatFilesARunListsAllowInItsSchema() throws Exception {
        JsonNode tools = McpServer.handle("tools/list", M.createObjectNode()).get("tools");
        for (String name : List.of("library_research", "library_items", "library_questions", "library_absorb", "library_check", "library_meeting")) {
            JsonNode tool = null;
            for (JsonNode t : tools) if (t.path("name").asText().equals(name)) tool = t;
            assertNotNull(tool, name);
            JsonNode allow = tool.path("inputSchema").path("properties").path("allow");
            assertEquals("array", allow.path("type").asText(), name);
            assertTrue(allow.path("description").asText().contains("\"explicit\"") && allow.path("description").asText().contains("\"howto\""), name);
        }
        // and the protocol's table says so, where a program reads what it may send
        Path doc = null;
        for (String c : new String[]{"../docs/public/LIBRARY_PROTOCOL.md", "docs/public/LIBRARY_PROTOCOL.md", "../docs/LIBRARY_PROTOCOL.md", "docs/LIBRARY_PROTOCOL.md"}) if (Files.exists(Path.of(c))) { doc = Path.of(c); break; }
        assertNotNull(doc, "LIBRARY_PROTOCOL.md not found from " + Path.of("").toAbsolutePath());
        String text = Files.readString(doc);
        for (String name : List.of("library_research", "library_items", "library_questions", "library_absorb", "library_check", "library_meeting")) {
            String row = text.lines().filter(l -> l.startsWith("| `" + name + "` |")).findFirst().orElse("");
            assertTrue(row.contains("`allow"), name + ": the protocol's table lists allow: " + row);
        }
        String research = text.lines().filter(l -> l.startsWith("| `library_research` |")).findFirst().orElse("");
        assertTrue(research.contains("content_suggestion") && research.contains("`confirm`") && research.contains("help"), research);
        String errors = text.substring(text.indexOf("## 5. Errors"), text.indexOf("## 6. Transports"));
        assertTrue(errors.contains("| `confirm` | -32007 |"), "the confirm error is in the table of errors: " + errors);
        assertTrue(text.contains("`confirm` 422"), "and its HTTP status");
        String job = text.lines().filter(l -> l.startsWith("| `library_job` |")).findFirst().orElse("");
        assertTrue(job.contains("`allow`"), "the job view's allow: " + job);
    }

    // ---- the web page ----

    @Test
    void theResearchPageHasItsOwnUntickedBoxAndSaysWhatAQuestionMayNeed() throws Exception {
        LibraryStore store = library("lib");
        HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/", x -> { byte[] b = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json"); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        model.start();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:" + model.getAddress().getPort(), "local-model", -1);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + d.port();
            String form = PagesTest.get(c, base + "/research", null).body();
            assertTrue(form.contains("<input type=\"checkbox\" name=\"allow\" value=\"1\"> Let in what the library leaves out by default, for this question only"), "its own box, unticked, not a field: " + form);
            // sent without the box: the page says what the question may need and files nothing
            String first = PagesTest.post(c, base + "/research", "question=" + Pages.enc(NEEDS) + "&mode=depth", null).body();
            assertTrue(first.contains("This question may need material the library leaves out of research by default") && first.contains("name=\"content_told\" value=\"1\""), first);
            assertTrue(new Jobs(store, j -> "").active().isEmpty() && new Jobs(store, j -> "").recent(5, null).isEmpty(), "nothing is filed until the person sends it again");
            // sent again as it is: the material left out
            String sent = PagesTest.post(c, base + "/research", "question=" + Pages.enc(NEEDS) + "&mode=broad&content_told=1", null).body();
            assertFalse(GenealogyOfferTest.job(store, sent).path("args").has("allow"));
            // the box ticked: let in, for this run
            String ticked = PagesTest.post(c, base + "/research", "question=" + Pages.enc(NEEDS) + "&mode=broad&allow=1", null).body();
            ObjectNode j = GenealogyOfferTest.job(store, ticked);
            assertEquals("[\"explicit\",\"howto\"]", j.path("args").path("allow").toString());
            assertEquals("web-box", j.path("args").path("allow_how").asText());
            assertFalse(j.path("args").has("field"), "the box is not a field");
        } finally { d.stop(); model.stop(0); GenealogyOfferTest.quiet(store); }
    }

    // ---- the record ----

    @Test
    void aRunThatLetSomethingInSaysSoUnderItsQuestionInTheLedgerAndInTheRunLedger() throws Exception {
        LibraryStore store = library("lib");
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive();
        drive.criticWantsMore = false;
        Researcher.Ask ask = new Researcher.Ask("How were the placeholder gears cut?", "depth", 60, List.of("how were the gears cut?")).withAllow(List.of(ContentPolicy.EXPLICIT));
        Researcher.Filed filed = Researcher.file(store, new Researcher(drive, drive, new ResearcherTest.FakeTools(), null, 1, store), ask, "patron:person", "J-0042", "asked", "chat-yes");
        assertTrue(filed.admitted(), filed.reason());
        String body = Files.readString(store.root().resolve("investigations").resolve(filed.investigationId() + ".md"));
        assertTrue(body.contains("## Question\n\nHow were the placeholder gears cut?\n\nThis run let in pornography, and gore: pictures or detailed descriptions of mutilated or dead bodies because you asked for it.\n\n"), body);
        List<String[]> rows = ContentOffer.rows(store);
        assertEquals(1, rows.size());
        assertEquals(List.of(filed.investigationId(), "J-0042", "explicit", "chat-yes"), List.of(rows.get(0)).subList(0, 4));
        assertEquals("explicit", RunLedger.row("J-0042", ask, filed.result(), "filed", 1, "d", "m", null).path("allow").asText());
        // an ordinary run: no sentence, no ledger line
        Researcher.Filed plain = Researcher.file(store, new Researcher(drive, drive, new ResearcherTest.FakeTools(), null, 1, store), new Researcher.Ask("How were the placeholder dials read?", "depth", 60, List.of("how were the dials read?")), "patron:person", "J-0043", "asked", "asked");
        assertFalse(Files.readString(store.root().resolve("investigations").resolve(plain.investigationId() + ".md")).contains("This run let in"));
        assertEquals(1, ContentOffer.rows(store).size());
    }
}
