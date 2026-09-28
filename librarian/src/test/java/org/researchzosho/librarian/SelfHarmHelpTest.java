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
import org.researchzosho.drive.Judge;
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
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A question that reads as a person asking about harming themselves: where to find help is shown first, in plain words, and then, where
 * a person can answer, whether to research it (y/N); no, Enter or no answer starts nothing. Where nobody can answer, the help and the way
 * to say yes come back and nothing starts. An unsure judge shows the help anyway, and the run goes on. The questions here are placeholders
 * with a marker word the stub judge reads.
 */
class SelfHarmHelpTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String SURE = "A placeholder question from a person, marked MARKER-HARM, about their own plans?";
    static final String UNSURE = "A placeholder question from a person, marked MARKER-UNSURE, about their own plans?";
    static final String HELP_START = "If you are thinking about harming yourself, you can talk to someone now, in confidence.";
    /** A question shorter than the twelve characters a research question needs, as a short question in Japanese often is. */
    static final String SHORT = "MK-HARM?";

    @TempDir Path tmp;

    @BeforeEach void judge() {
        // the typed judge: a sure yes for the marked question, an even answer for the unsure one, a sure no for everything else
        ContentJudge.use(new ContentJudge(new Judge("m", body -> {
            String c = body.path("messages").path(0).path("content").asText();
            boolean harmQuestion = c.contains(ContentOffer.HARM);
            return DeclinesTest.judged(harmQuestion && (c.contains("MARKER-HARM") || c.contains(SHORT)) ? 0.97 : harmQuestion && c.contains("MARKER-UNSURE") ? 0.5 : 0.02);
        }), null));
    }

    @AfterEach void back() { ContentJudge.use(null); Interaction.OVERRIDE = null; Interaction.INPUT = null; }

    LibraryStore library(String name) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve(name)); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    static ObjectNode ask(String question) {
        ObjectNode a = M.createObjectNode(); a.put("question", question);
        a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        return a;
    }

    @Test
    void theHelpIsPlainTheOwnCountryFirstThenTheDirectory() {
        String us = CrisisHelp.text(Locale.US);
        assertTrue(us.startsWith(HELP_START + "\n- In the United States and Canada: call or text 988, free, at any hour.\n- Anywhere in the world: findahelpline.com"), us);
        String jp = CrisisHelp.text(Locale.JAPAN);
        assertTrue(jp.startsWith(HELP_START + "\n- In Japan: よりそいホットライン 0120-279-338, free, at any hour; or いのちの電話 0120-783-556, free, every day from 16:00 to 21:00.\n- Anywhere in the world"), jp);
        String unknown = CrisisHelp.text(Locale.FRANCE);
        assertTrue(unknown.startsWith(HELP_START + "\n- Anywhere in the world: findahelpline.com lists free helplines in more than 175 countries."), "no line for the country: the directory first: " + unknown);
        for (String line : List.of("116 123", "13 11 14", "0800 111 0 111", "0800 111 0 222", "988")) assertTrue(unknown.contains(line), line);
        assertFalse(unknown.toLowerCase(Locale.ROOT).contains("email") || unknown.contains("@"), "the Samaritans' email is not shown");
        assertTrue(unknown.endsWith("If you are in danger right now, call your local emergency number."));
    }

    @Test
    void whereNobodyCanAnswerTheHelpComesBackAndNothingStarts() throws Exception {
        LibraryStore store = library("lib");
        LibraryProtocol p = new LibraryProtocol(store);
        // the confirm error: a client that prints errors shows the person where to find help, and the one sentence that says to ask them
        ProtocolError e = assertThrows(ProtocolError.class, () -> p.research(ask(SURE)));
        assertEquals("confirm", e.code);
        assertEquals(-32007, e.rpc);
        assertTrue(e.getMessage().startsWith(HELP_START), e.getMessage());
        assertTrue(e.getMessage().endsWith("\n\nShow this to the person and ask them whether the library should research the question; "
                + "send the question again with allow [\"self-harm\"] only if the person says yes."), e.getMessage());
        assertEquals(422, LibrarianDaemon.status("confirm"), "over HTTP: not an outage a client retries");
        assertTrue(new Jobs(store, j -> "").active().isEmpty(), "nothing was filed");
        // the way to say yes: the explicit argument
        ObjectNode yes = ask(SURE); yes.putArray("allow").add(ContentPolicy.SELF_HARM);
        ObjectNode filed = p.research(yes);
        assertEquals("queued", filed.path("state").asText());
        assertFalse(filed.has("help"));
        assertEquals("[\"self-harm\"]", GenealogyOfferTest.filed(store).path("args").path("allow").toString());
        // unsure: the help comes back anyway, and the run starts
        ObjectNode unsure = p.research(ask(UNSURE));
        assertEquals("queued", unsure.path("state").asText());
        assertTrue(unsure.path("help").path("text").asText().startsWith(HELP_START), unsure.toString());
        // a batch run the same way: the help on the batch's result and on that question, nothing started for it, and no empty id anywhere
        ObjectNode questions = M.createObjectNode(); questions.put("text", SURE + "\n" + "A placeholder question about how the gears were cut?"); questions.put("as", "runs");
        questions.set("patron", ask("x").get("patron"));
        ObjectNode batch = p.questions(questions);
        assertTrue(batch.path("help").path("text").asText().startsWith(HELP_START), batch.toString());
        assertEquals(SURE, batch.path("help").path("question").asText());
        assertTrue(batch.path("help").path("how").asText().startsWith("Show this to the person and ask them") && batch.path("help").path("how").asText().endsWith("only if the person says yes."), batch.toString());
        JsonNode first = batch.path("questions").get(0);
        assertEquals("not_started", first.path("state").asText(), batch.toString());
        assertFalse(first.has("job_id"), batch.toString());
        assertTrue(first.path("help").asText().startsWith(HELP_START) && first.path("help").asText().endsWith("send it to library_research with allow [\"self-harm\"] only if the person says yes."), first.toString());
        assertEquals(1, batch.path("jobs").size(), batch.toString());
        assertTrue(batch.path("jobs").get(0).asText().startsWith("J-"), batch.toString());
        assertTrue(batch.path("questions").get(1).path("job_id").asText().startsWith("J-"), batch.toString());
    }

    /**
     * The batch tools that file a run each: a question that reads as a person asking about harming themselves is not filed, is reported
     * with where to find help, is not marked explored, records no kept bridge, and leaves no empty job id anywhere.
     */
    @Test
    void batchToolsFileNothingForSuchAQuestionAndLeaveNoEmptyId() throws Exception {
        var drives = Explain.DRIVES; Explain.DRIVES = () -> null;
        try {
            LibraryStore store = library("lib");
            LibraryProtocol p = new LibraryProtocol(store);
            // a survey of a document: its directions carry the marker through the document's name, so each run's question does
            Path paper = tmp.resolve("MARKER-HARM-notes.md");
            Files.writeString(paper, "# Placeholder notes\n\nA placeholder paragraph that says what these notes are about, long enough to be the first real paragraph of them.\n\n## Future work\n\nA placeholder direction.\n");
            ObjectNode survey = ask("x"); survey.remove("question"); survey.put("path", paper.toString());
            String name = p.survey(survey).path("name").asText();
            int open = Surveys.options(store, name).size();
            assertTrue(open > 0);
            ObjectNode pick = ask("x"); pick.remove("question"); pick.put("op", "pick").put("name", name).put("picks", "1");
            ObjectNode picked = p.survey(pick);
            JsonNode run = picked.path("runs").get(0);
            assertEquals("not_started", run.path("state").asText(), picked.toString());
            assertFalse(run.has("job_id"), picked.toString());
            assertTrue(run.path("help").asText().startsWith(HELP_START), picked.toString());
            assertTrue(picked.path("summary").asText().startsWith("0 research run(s) were filed"), picked.path("summary").asText());
            assertEquals(open, Surveys.options(store, name).size(), "the direction is not marked explored");
            ObjectNode doIt = ask(SURE); doIt.put("op", "do").put("name", name);
            ObjectNode done = p.survey(doIt);
            assertEquals("not_started", done.path("runs").get(0).path("state").asText(), done.toString());
            assertFalse(done.path("runs").get(0).has("job_id"), done.toString());
            assertTrue(Frontier.read(store).stream().filter(l -> l.text().contains("MARKER-HARM")).allMatch(Frontier.Line::open), "nothing is marked explored");
            // a bridge proposal: not accepted, no kept bridge written down without a run
            store.frontier("bridge person", SURE);
            ObjectNode accept = ask(SURE); accept.put("op", "accept");
            ObjectNode acc = p.bridges(accept);
            assertEquals("not_started", acc.path("state").asText(), acc.toString());
            assertFalse(acc.has("job_id") || acc.has("accepted"), acc.toString());
            assertTrue(acc.path("help").asText().startsWith(HELP_START), acc.toString());
            assertEquals(1, Bridges.open(store).size(), "the proposal stays open");
            assertEquals(0, Bridges.measure(store).path("kept").asInt(), "no kept bridge");
            // a list of items as runs: no empty id in jobs[]
            ObjectNode items = ask("x"); items.remove("question"); items.put("text", "MARKER-HARM placeholder item\n"); items.put("as", "runs");
            ObjectNode it = p.items(items);
            assertEquals(0, it.path("jobs").size(), it.toString());
            assertTrue(it.path("not_started").get(0).path("help").asText().startsWith(HELP_START), it.toString());
            // a checking run of a draft: no empty verify_job_id
            ObjectNode draft = ask("x"); draft.remove("question"); draft.put("text", "A placeholder draft. The MARKER-HARM placeholder bridge opened in 1901 and carried 4,000 trams a day until 1950.\n"); draft.put("verify", true);
            ObjectNode checked = p.check(draft);
            assertFalse(checked.has("verify_job_id"), checked.toString());
            assertEquals(1, checked.path("not_started").size(), checked.toString());
            assertTrue(new Jobs(store, j -> "").active().isEmpty(), "nothing at all was filed");
        } finally { Explain.DRIVES = drives; }
    }

    private String cli(Path home, int[] rc, String... words) throws Exception {
        String realHome = System.getProperty("user.home");
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setProperty("user.home", home.toString());
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            String[] args = new String[words.length + 1];
            args[0] = "librarian";
            System.arraycopy(words, 0, args, 1, words.length);
            rc[0] = LibrarianCli.run(args, "http://127.0.0.1:1", "m");
        } finally { System.setOut(was); System.setProperty("user.home", realHome); }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theResearchCommandShowsTheHelpFirstAndSendsNothingWithoutAYes() throws Exception {
        Path home = tmp.resolve("home");
        LibraryStore store = new LibraryStore(home.resolve("researchzosho-library")); store.init();
        int[] rc = {0};
        // a script: the help, how to say yes, nothing sent
        Interaction.OVERRIDE = false;
        String said = cli(home, rc, "research", "ask", SURE);
        assertTrue(said.startsWith(HELP_START), said);
        assertTrue(said.contains("Nothing was sent. To research this question, send it again with --allow self-harm: researchzosho research ask '" + SURE + "' --allow self-harm"), said);
        assertEquals(1, rc[0]);
        assertNull(GenealogyOfferTest.filed(store), "nothing was filed");
        // a terminal, Enter: the help first, then the question, and nothing is sent
        Interaction.OVERRIDE = true; Interaction.INPUT = new BufferedReader(new StringReader("\n"));
        said = cli(home, rc, "research", "ask", SURE);
        assertTrue(said.startsWith(HELP_START), said);
        assertTrue(said.indexOf(CrisisHelp.QUESTION) > said.indexOf("findahelpline.com"), "the question comes after the help: " + said);
        assertTrue(said.contains("Nothing was sent. The question is not researched."), said);
        assertNull(GenealogyOfferTest.filed(store), "Enter is no");
        // a terminal, y: sent, as ordinary research
        Interaction.INPUT = new BufferedReader(new StringReader("y\n"));
        said = cli(home, rc, "research", "ask", SURE);
        assertTrue(said.contains("sent as J-"), said);
        assertEquals("[\"self-harm\"]", GenealogyOfferTest.filed(store).path("args").path("allow").toString());
        assertFalse(said.contains("This run lets in"), "nothing is let in: the run is ordinary: " + said);
    }

    @Test
    void aShortQuestionIsAskedToo() throws Exception {
        assertTrue(SHORT.length() < 12);
        // a program: the help, not "too short"
        LibraryStore store = library("lib");
        ProtocolError e = assertThrows(ProtocolError.class, () -> new LibraryProtocol(store).research(ask(SHORT)));
        assertEquals("confirm", e.code, e.getMessage());
        assertTrue(e.getMessage().startsWith(HELP_START), e.getMessage());
        // the terminal: the help first
        Path home = tmp.resolve("home");
        new LibraryStore(home.resolve("researchzosho-library")).init();
        int[] rc = {0};
        Interaction.OVERRIDE = false;
        String said = cli(home, rc, "research", "ask", SHORT);
        assertTrue(said.startsWith(HELP_START), said);
        assertEquals(1, rc[0]);
        // the web page: the help first
        HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/", x -> { byte[] b = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json"); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        model.start();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:" + model.getAddress().getPort(), "local-model", -1);
        try {
            String page = PagesTest.post(HttpClient.newHttpClient(), "http://127.0.0.1:" + d.port() + "/research", "question=" + Pages.enc(SHORT), null).body();
            assertTrue(page.contains("findahelpline.com") && page.contains("Do you want the library to research this question?"), page);
        } finally { d.stop(); model.stop(0); GenealogyOfferTest.quiet(store); }
    }

    @Test
    void theChatShowsTheHelpFirstAndNoStartsNothing() throws Exception {
        LibraryStore store = library("lib");
        Librarian lib = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + SURE + "\"}"), LibrarianChatTest.say("I have filed it.")), new ArrayList<>()),
                Librarian.person(), Librarian.Session.open(store));
        String reply = lib.say("Look into this for me");
        assertTrue(reply.startsWith(HELP_START), "the help first: " + reply);
        assertTrue(reply.endsWith(CrisisHelp.QUESTION), reply);
        ObjectNode job = GenealogyOfferTest.filed(store);
        assertEquals(Jobs.OFFERED, job.path("state").asText());
        assertTrue(job.path("offered_help").asBoolean());
        String no = lib.say("");
        assertTrue(no.startsWith("Nothing is researched for that question."), no);
        ObjectNode after = new Jobs(store, j -> "").get(job.path("job_id").asText());
        assertEquals("stopped", after.path("state").asText(), "no starts nothing");
        assertEquals(Jobs.NOT_RESEARCHED, after.path("result").asText());
        // a yes: researched as ordinary research
        LibraryStore other = library("lib2");
        Librarian yesLib = new Librarian(other, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + SURE + "\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>()),
                Librarian.person(), Librarian.Session.open(other));
        yesLib.say("Look into this for me");
        String yes = yesLib.say("y");
        assertTrue(yes.startsWith("The question is researched. Research run "), yes);
        assertEquals("queued", GenealogyOfferTest.filed(other).path("state").asText());
        assertEquals("[\"self-harm\"]", GenealogyOfferTest.filed(other).path("args").path("allow").toString());
        // the wait ending starts nothing
        LibraryStore third = library("lib3");
        Librarian waits = new Librarian(third, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + SURE + "\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>()),
                Librarian.person(), Librarian.Session.open(third));
        waits.say("Look into this for me");
        ObjectNode w = GenealogyOfferTest.filed(third);
        assertTrue(new Jobs(third, j -> "").expire(w));
        assertEquals("stopped", new Jobs(third, j -> "").get(w.path("job_id").asText()).path("state").asText());
    }

    @Test
    void theChatTellsItsModelThatTheRunStartsOnlyOnAYes() throws Exception {
        LibraryStore store = library("lib");
        List<ArrayNode> seen = new ArrayList<>();
        Librarian lib = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + SURE + "\"}"), LibrarianChatTest.say("I have filed it.")), seen),
                Librarian.person(), Librarian.Session.open(store));
        lib.say("Look into this for me");
        String toolResult = "";
        for (ArrayNode turn : seen) for (var m : turn) if ("tool".equals(m.path("role").asText())) toolResult = m.path("content").asText();
        assertTrue(toolResult.contains("the library researches this question only if they say yes to its question"), toolResult);
        assertFalse(toolResult.contains("starts as soon as they answer"), toolResult);
    }

    @Test
    void anUnsureJudgeShowsTheHelpAnywayAndTheRunGoesOn() throws Exception {
        LibraryStore store = library("lib");
        Librarian lib = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + UNSURE + "\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>()),
                Librarian.person(), Librarian.Session.open(store));
        String reply = lib.say("Look into this for me");
        assertTrue(reply.startsWith(HELP_START), reply);
        assertFalse(reply.contains("(y/N)"), "nothing to answer: " + reply);
        assertEquals("queued", GenealogyOfferTest.filed(store).path("state").asText());
    }

    @Test
    void helpIsAskedBeforeWhatTheLibraryLeavesOut() throws Exception {
        ContentJudge.use(new ContentJudge(new Judge("m", body -> {
            String c = body.path("messages").path(0).path("content").asText();
            return DeclinesTest.judged(c.contains(ContentOffer.HARM) || c.contains(ContentOffer.NEEDS_HOWTO) ? 0.97 : 0.02);
        }), null));
        LibraryStore store = library("lib");
        Librarian lib = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + SURE + "\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>()),
                Librarian.person(), Librarian.Session.open(store));
        assertTrue(lib.say("Look into this for me").endsWith(CrisisHelp.QUESTION));
        String next = lib.say("y");
        assertTrue(next.startsWith("The question is researched.") && next.endsWith("Let it in for this question? (y/N)"), next);
        assertEquals(Jobs.OFFERED, GenealogyOfferTest.filed(store).path("state").asText());
        lib.say("n");
        ObjectNode job = GenealogyOfferTest.filed(store);
        assertEquals("queued", job.path("state").asText());
        assertEquals("[\"self-harm\"]", job.path("args").path("allow").toString(), "the second no let nothing in");
    }

    @Test
    void theNightNeverResearchesSuchAQuestion() throws Exception {
        LibraryStore store = library("lib");
        ContentOffer.NotStarted n = assertThrows(ContentOffer.NotStarted.class,
                () -> Researcher.forExplorer(store, "http://127.0.0.1:1", "m", SURE, List.of(), 10, 10, "", "crew:explorer", line -> { }));
        assertEquals(ContentOffer.NOT_STARTED, n.getMessage());
        // the explorer marks it, and does not take it again
        store.frontier("person", SURE);
        String out = Crews.explore(store, (question, writer) -> { throw new ContentOffer.NotStarted(ContentOffer.NOT_STARTED); }, 1);
        assertTrue(out.contains("it reads as a person asking about harming themselves"), out);
        assertTrue(Frontier.read(store).stream().noneMatch(Frontier.Line::researchable), "marked, not taken again");
    }

    @Test
    void theResearchPageShowsTheHelpFirstAndFilesNothingWithoutAYes() throws Exception {
        LibraryStore store = library("lib");
        HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/", x -> { byte[] b = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json"); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        model.start();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:" + model.getAddress().getPort(), "local-model", -1);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + d.port();
            String first = PagesTest.post(c, base + "/research", "question=" + Pages.enc(SURE) + "&mode=depth", null).body();
            assertTrue(first.contains("If you are thinking about harming yourself") && first.contains("findahelpline.com"), first);
            assertTrue(first.indexOf("findahelpline.com") < first.indexOf("Do you want the library to research this question?"), "the help first");
            assertTrue(first.contains("name=\"harm_ok\" value=\"1\"") && first.contains("No, do not research it"), first);
            assertTrue(new Jobs(store, j -> "").active().isEmpty() && new Jobs(store, j -> "").recent(5, null).isEmpty(), "nothing is filed without a yes");
            String yes = PagesTest.post(c, base + "/research", "question=" + Pages.enc(SURE) + "&mode=depth&harm_ok=1", null).body();
            ObjectNode j = GenealogyOfferTest.job(store, yes);
            assertEquals("[\"self-harm\"]", j.path("args").path("allow").toString());
        } finally { d.stop(); model.stop(0); GenealogyOfferTest.quiet(store); }
    }

    @Test
    void theResearchPageShowsTheHelpOnceWhenTheQuestionAlsoNeedsAnOffer() throws Exception {
        ContentJudge.use(new ContentJudge(new Judge("m", body -> {
            String c = body.path("messages").path(0).path("content").asText();
            return DeclinesTest.judged(c.contains(ContentOffer.HARM) || c.contains(ContentOffer.NEEDS_HOWTO) ? 0.97 : 0.02);
        }), null));
        LibraryStore store = library("lib");
        HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/", x -> { byte[] b = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json"); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        model.start();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:" + model.getAddress().getPort(), "local-model", -1);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + d.port();
            assertTrue(PagesTest.post(c, base + "/research", "question=" + Pages.enc(SURE), null).body().contains("findahelpline.com"), "the help first");
            // yes to researching it: the page says what the question may need, and the form carries the yes
            String offer = PagesTest.post(c, base + "/research", "question=" + Pages.enc(SURE) + "&harm_ok=1", null).body();
            assertTrue(offer.contains("This question may need material the library leaves out"), offer);
            assertTrue(offer.contains("name=\"harm_ok\" value=\"1\""), "the yes rides in the form: " + offer);
            assertFalse(offer.contains("findahelpline.com"), "the help is not shown a second time");
            // sent as it is: filed, with the yes, and the help is not asked again
            String sent = PagesTest.post(c, base + "/research", "question=" + Pages.enc(SURE) + "&harm_ok=1&content_told=1", null).body();
            assertFalse(sent.contains("Do you want the library to research this question?"), sent);
            assertEquals("[\"self-harm\"]", GenealogyOfferTest.job(store, sent).path("args").path("allow").toString());
        } finally { d.stop(); model.stop(0); GenealogyOfferTest.quiet(store); }
    }
}
