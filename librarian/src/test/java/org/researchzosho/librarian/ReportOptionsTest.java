package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.mcp.McpServer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The options a report is written with (0.7.0): its length, its citations and its shape. Read from what a person or a program says and
 * checked; put to the writer as rules on top of the report's basis; left out when the question asks for a format of their kind itself;
 * recorded on the report; reached from the chat's words, the command line, the protocol, MCP, the research page and the library's
 * settings. Every run is on a stand-in drive whose replies are scripted: nothing reaches a model or the network.
 */
class ReportOptionsTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String Q = "How were the Antikythera gears cut, and by whom?";

    @Test
    void theValuesAreReadAsPeopleWriteThemAndAnythingElseIsRefusedWithWhatItTakes() {
        assertEquals("short", ReportOptions.length("Short")); assertEquals("800", ReportOptions.length("800 words")); assertEquals("1500", ReportOptions.length("about 1,500"));
        assertNull(ReportOptions.length("50")); assertNull(ReportOptions.length("huge")); assertEquals("", ReportOptions.length(" "));
        assertEquals("apa", ReportOptions.citations("APA 7th")); assertEquals("chicago", ReportOptions.citations("Chicago style")); assertEquals("numbered", ReportOptions.citations("numbers"));
        assertNull(ReportOptions.citations("Harvard"));
        assertEquals("reading-list", ReportOptions.shape("a reading list")); assertEquals("reading-list", ReportOptions.shape("annotated bibliography")); assertEquals("outline", ReportOptions.shape("Outline"));
        assertNull(ReportOptions.shape("essay"));
        ReportOptions o = ReportOptions.of(M.createObjectNode().put("length", 900).put("citations", "MLA").put("shape", "reading list"));
        assertEquals(new ReportOptions("900", "mla", "reading-list"), o);
        assertEquals("about 900 words, with MLA citations, as a reading list", o.described());
        for (String[] bad : new String[][]{{"length", "huge", "length is short (about 400 words), medium"}, {"citations", "harvard", "citations is numbered (the default), apa, mla, chicago or ieee."}, {"shape", "essay", "shape is report (the default), reading-list"}}) {
            ProtocolError e = assertThrows(ProtocolError.class, () -> ReportOptions.of(M.createObjectNode().put(bad[0], bad[1])));
            assertEquals("invalid_args", e.code); assertTrue(e.getMessage().startsWith(bad[2]), e.getMessage());
        }
        assertEquals(ReportOptions.NONE, ReportOptions.of(M.createObjectNode()));
    }

    @Test
    void thePersonsOwnWordsAskForAReportsFormAndOnlyThoseWordsDo() {
        assertEquals(new ReportOptions("short", "apa", ""), ReportOptions.fromWords("Give me a short report with APA citations on how the gears were cut."));
        assertEquals("reading-list", ReportOptions.fromWords("give me a reading list on the Antikythera mechanism").shape());
        assertEquals("outline", ReportOptions.fromWords("Just an outline, please.").shape());
        assertEquals("apa", ReportOptions.fromWords("use APA citations").citations());
        assertEquals("500", ReportOptions.fromWords("Find out what the trials say, in under 500 words").length());
        assertEquals("long", ReportOptions.fromWords("I want a detailed report on the gears").length());
        assertEquals("ieee", ReportOptions.fromWords("cite the sources in IEEE style").citations());
        for (String nothing : new String[]{"Find homes for sale in Chicago near the lake", "Outline the history of the gears for me", "Find IEEE papers on antennas",
                "What does the APA say about screen time?", "In short, who cut the gears?", "Make a list of the museums that hold one"})
            assertEquals(ReportOptions.NONE, ReportOptions.fromWords(nothing), "asks for no form: " + nothing);
    }

    @Test
    void aQuestionThatAsksForAFormatOfTheSameKindWins() {
        ReportOptions o = new ReportOptions("long", "apa", "outline");
        assertEquals(new ReportOptions("", "apa", ""), o.under("a table, under 300 words"), "the table and the 300 words are the question's");
        assertEquals(new ReportOptions("long", "", "outline"), o.under("MLA citations"));
        assertEquals(o, o.under(""), "a question that asks nothing leaves every option");
        assertEquals(new ReportOptions("short", "", "reading-list"), new ReportOptions("short", "", "reading-list").under("a short reading list"), "the same thing asked twice stands");
    }

    @Test
    void theLibrarysDefaultsComeFromItsSettingsAndARunsOwnWin(@TempDir Path tmp) throws Exception {
        String realHome = System.getProperty("user.home");
        try {
            Files.createDirectories(tmp.resolve("home"));
            System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
            assertEquals(ReportOptions.NONE, ReportOptions.defaults());
            Settings.Setting length = Settings.named("report_length"), style = Settings.named("citation_style");
            assertNotNull(length); assertNotNull(style);
            assertEquals("report_length is short, medium, long or a number of words, for example 800.", Settings.refuse(length, "huge"));
            assertEquals("", Settings.refuse(length, "1200")); assertEquals("", Settings.refuse(style, "APA"));
            assertTrue(Settings.refuse(style, "harvard").startsWith("citation_style is one of numbered, apa, mla, chicago, ieee"), Settings.refuse(style, "harvard"));
            Settings.set(length, "Short"); Settings.set(style, "MLA");
            assertEquals(new ReportOptions("short", "mla", ""), ReportOptions.defaults());
            assertEquals(new ReportOptions("long", "mla", "outline"), ReportOptions.forRun(M.createObjectNode().put("length", "long").put("shape", "outline")));
            assertEquals(new ReportOptions("short", "mla", ""), ReportOptions.forRun(M.createObjectNode().put("length", "a mile")), "a job's bad value falls back on the defaults");
        } finally { System.setProperty("user.home", realHome); Config.invalidate(); }
    }

    @Test
    void theRecordKeepsTheOptionsInItsFrontMatterAndEveryRewriteCarriesThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        ReportOptions o = new ReportOptions("short", "apa", "reading-list");
        String id = store.nextInvestigationId(Q);
        store.write(new Investigation(id, Q, Finding.State.draft, "patron:test", Instant.now().toString(), List.of(), List.of(), "## Question\n\n" + Q + "\n\n## Answer\n\nBy hand.\n", "", o));
        String file = Files.readString(store.investigationsDir().resolve(id + ".md"));
        assertTrue(file.contains("\nlength: short\ncitations: apa\nshape: reading-list\n"), file);
        Investigation back = store.investigation(id);
        assertEquals(o, back.options());
        assertEquals(o, back.withFindings(List.of("F-0001-x")).options()); assertEquals(o, back.withOpen(List.of("who?")).options());
        assertEquals(o, back.with(Finding.State.accepted, List.of(), List.of()).options());
        String old = store.nextInvestigationId("An older report");
        store.write(new Investigation(old, "An older report", Finding.State.draft, "patron:test", Instant.now().toString(), List.of(), List.of(), "## Answer\n\nx\n"));
        assertEquals(ReportOptions.NONE, store.investigation(old).options(), "a report filed before the options has none");
        assertFalse(Files.readString(store.investigationsDir().resolve(old + ".md")).contains("length:"));
        JsonNode entry = new LibraryProtocol(store).get(M.createObjectNode().put("id", id)).path("entry");
        assertEquals("short", entry.path("options").path("length").asText()); assertEquals("reading-list", entry.path("options").path("shape").asText());
        assertFalse(new LibraryProtocol(store).get(M.createObjectNode().put("id", old)).path("entry").has("options"), "nothing recorded, nothing said");
    }

    /** A stand-in for the run's two seats: the workers' scripted turns, a writer that follows a reading list's shape, a judge that reads the question's format. */
    static final class OptionDrive extends ResearcherTest.ScriptedDrive {
        final String format;
        OptionDrive(String format) { this.format = format; criticWantsMore = false; }

        @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) {
            List<String> n = names(tools);
            if (n.contains("write_section") && history.get(0).path("content").asText().contains("THE SHAPE THE PERSON ASKED FOR: a reading list")) {
                histories.add(history.deepCopy());
                return switch (assistantTurns(history) + 1) {
                    case 1 -> call("write_section", M.createObjectNode().put("heading", "Answer").put("text", "One source covers the cutting; start with the museum's page [1]."));
                    case 2 -> call("write_section", M.createObjectNode().put("heading", "Reading list").put("text",
                            "- [How the gears were cut](https://example.org/gears) — a museum's page on the workshop; it shows how the teeth were made; strong: it describes the object itself [1]\n"
                            + "- The same page, read again for the tools — the files and the dividing plate it names; strong [1]"));
                    case 3 -> call("write_section", M.createObjectNode().put("heading", "Sources").put("text", "[1] Example Museum. (2025, March 2). How the gears were cut. Example Museum. https://example.org/gears"));
                    default -> call("done", M.createObjectNode().put("summary", "a reading list of one source"));
                };
            }
            return super.chat(history, tools, maxTokens, toolChoice);
        }

        @Override public String classify(ArrayNode messages, int maxTokens) {
            String prompt = messages.get(messages.size() - 1).path("content").asText();
            if (prompt.startsWith("Split this research request") && format != null) return "{\"task\": \"how the gears were cut\", \"format\": \"" + format + "\"}";
            return super.classify(messages, maxTokens);
        }

        /** The writer's system words: the first message of a turn that offered write_section. */
        String writer() {
            for (ArrayNode h : histories) if (h.get(0).path("content").asText().contains("write_section")) return h.get(0).path("content").asText();
            return "";
        }
    }

    static Researcher.Filed run(LibraryStore store, OptionDrive drive, String question, ReportOptions o) throws Exception {
        Researcher r = new Researcher(drive, drive, new ResearcherTest.FakeTools(), s -> { }, 2, store);
        if (o != null) r.options(o);
        return Researcher.file(store, r, new Researcher.Ask(question, "broad", 60, List.of("how were the gears cut?")), "patron:test");
    }

    @Test
    void eachOptionIsTheWritersRuleOnTopOfTheBasisAndTheReportRecordsIt(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Object[][] cases = {
            {new ReportOptions("short", "", ""), "THE LENGTH THE PERSON ASKED FOR: a short report, about 400 words", "length: short"},
            {new ReportOptions("medium", "", ""), "a report of medium length, about 1,200 words", "length: medium"},
            {new ReportOptions("long", "", ""), "a long report, about 3,000 words", "length: long"},
            {new ReportOptions("2500", "", ""), "a report of about 2,500 words", "length: 2500"},
            {new ReportOptions("", "numbered", ""), "THE CITATIONS THE PERSON ASKED FOR: numbered.", "citations: numbered"},
            {new ReportOptions("", "apa", ""), "APA style (7th edition)", "citations: apa"},
            {new ReportOptions("", "mla", ""), "MLA style (9th edition)", "citations: mla"},
            {new ReportOptions("", "chicago", ""), "Chicago style (notes and bibliography)", "citations: chicago"},
            {new ReportOptions("", "ieee", ""), "IEEE style", "citations: ieee"},
            {new ReportOptions("", "", "outline"), "THE SHAPE THE PERSON ASKED FOR: an outline", "shape: outline"},
            {new ReportOptions("", "", "report"), "THE REPORT'S BASIS", "shape: report"},
        };
        for (Object[] c : cases) {
            ReportOptions o = (ReportOptions) c[0];
            OptionDrive drive = new OptionDrive(null);
            Researcher.Filed f = run(store, drive, Q, o);
            assertTrue(f.admitted(), f.reason());
            String writer = drive.writer();
            assertTrue(writer.contains((String) c[1]), o + ": the writer is told " + c[1] + "\n" + writer);
            assertTrue(writer.indexOf("THE REPORT'S BASIS") < writer.indexOf((String) c[1]) || c[1].equals("THE REPORT'S BASIS"), "the option's rule comes on top of the basis: " + o);
            assertTrue(writer.contains("[name](url)") && writer.contains("How each point was checked"), "the basis is there whatever the option");
            String file = Files.readString(store.investigationsDir().resolve(f.investigationId() + ".md"));
            assertTrue(file.contains("\n" + c[2] + "\n"), o + ": recorded in the front matter\n" + file.substring(0, Math.min(400, file.length())));
            assertEquals(o, store.investigation(f.investigationId()).options());
        }
        // the rules are positive directives, every one
        String all = new ReportOptions("short", "ieee", "reading-list").rules() + new ReportOptions("long", "apa", "outline").rules();
        for (String no : new String[]{"Do not", "do not", "Don't", "don't", "never", "Never", "avoid"}) assertFalse(all.contains(no), "a rule says what to do, not what not to do: " + no);
        // the writer's sections are as long as the length asks: shorter for a short report, longer for a long one
        assertEquals(900, new ReportOptions("short", "", "").sectionChars()); assertEquals(1500, ReportOptions.NONE.sectionChars()); assertEquals(2500, new ReportOptions("long", "", "").sectionChars());
        // no option: no rule, and nothing in the front matter
        OptionDrive plain = new OptionDrive(null);
        Researcher.Filed f = run(store, plain, Q, null);
        assertFalse(plain.writer().contains("THE PERSON ASKED FOR:"), plain.writer());
        assertFalse(Files.readString(store.investigationsDir().resolve(f.investigationId() + ".md")).matches("(?s).*\\n(length|citations|shape): .*"));
    }

    @Test
    void aReadingListsItemsCarryTheirLinksAndItsChecksAndSourcesStay(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        OptionDrive drive = new OptionDrive(null);
        Researcher.Filed f = run(store, drive, Q, new ReportOptions("", "apa", "reading-list"));
        assertTrue(f.admitted(), f.reason());
        String writer = drive.writer();
        assertTrue(writer.contains("a section 'Reading list': one list line per source") && writer.contains("the source's title as its link") && writer.contains("APA style"), writer);
        Investigation inv = store.investigation(f.investigationId());
        String body = inv.body();
        assertTrue(body.contains("## Reading list"), body);
        for (String line : body.substring(body.indexOf("## Reading list")).split("\n## ")[0].split("\n"))
            if (line.startsWith("- ")) assertTrue(line.contains("](https://example.org/gears)"), "every item of the list carries its link on its line: " + line);
        assertTrue(body.contains("[1] Example Museum. (2025, March 2). How the gears were cut."), "the writer's sources in APA style");
        assertTrue(body.contains("## Checks") && body.contains("How each point was checked"), "a reading list keeps its checks: the library says the checked-by section is missing");
        assertEquals(new ReportOptions("", "apa", "reading-list"), inv.options());
    }

    @Test
    void theQuestionsOwnFormatWinsInTheRun(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        OptionDrive drive = new OptionDrive("a table, under 300 words");
        Researcher.Filed f = run(store, drive, "How were the Antikythera gears cut? Answer in a table, under 300 words.", new ReportOptions("long", "apa", "outline"));
        String writer = drive.writer();
        assertTrue(writer.contains("THE FORMAT THE PERSON ASKED FOR — the answer takes this shape: a table, under 300 words"), writer);
        assertFalse(writer.contains("THE LENGTH THE PERSON ASKED FOR") || writer.contains("THE SHAPE THE PERSON ASKED FOR"), "the question's own length and shape win: " + writer);
        assertTrue(writer.contains("APA style"), "an option of a kind the question does not name stands");
        assertEquals(new ReportOptions("", "apa", ""), store.investigation(f.investigationId()).options(), "the report records the options it was written with");
    }

    @Test
    void theProtocolTheCommandLineAndMcpFileTheOptions(@TempDir Path tmp) throws Exception {
        String realHome = System.getProperty("user.home");
        PrintStream realOut = System.out;
        try {
            System.setProperty("user.home", tmp.toString()); Config.invalidate();
            LibraryStore store = new LibraryStore(tmp.resolve("researchzosho-library")); store.init();
            LibraryProtocol p = new LibraryProtocol(store);
            ObjectNode ask = M.createObjectNode().put("question", "How were the gears of the Antikythera mechanism cut, and with what tools?").put("length", "Short").put("citations", "APA").put("shape", "reading list");
            ask.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "test");
            ObjectNode filed = p.research(ask, new LibraryProtocol.Way("asked", "cli", false));
            assertEquals("short", filed.path("options").path("length").asText(), "the result says the options it filed: " + filed);
            JsonNode args = new Jobs(store, j -> "").get(filed.path("job_id").asText()).path("args");
            assertEquals("short", args.path("length").asText()); assertEquals("apa", args.path("citations").asText()); assertEquals("reading-list", args.path("shape").asText());
            ObjectNode bad = ask.deepCopy(); bad.put("question", "How were the gears of the Antikythera mechanism cut, by hand or by a machine?"); bad.put("citations", "harvard");
            ProtocolError e = assertThrows(ProtocolError.class, () -> p.research(bad, new LibraryProtocol.Way("asked", "cli", false)));
            assertEquals("invalid_args", e.code); assertEquals(ReportOptions.CITATIONS_TAKES, e.getMessage());
            // MCP: the three, optional, on library_research
            JsonNode props = null;
            for (JsonNode t : McpServer.allTools()) if (t.path("name").asText().equals("library_research")) props = t.path("inputSchema").path("properties");
            assertNotNull(props);
            assertTrue(props.has("length") && props.path("citations").has("enum") && props.path("shape").has("enum"), props.toString());
            assertTrue(props.path("length").path("description").asText().contains("Since 0.7.0"));
            // the command line
            ByteArrayOutputStream said = new ByteArrayOutputStream();
            System.setOut(new PrintStream(said, true, StandardCharsets.UTF_8));
            assertEquals(0, LibrarianCli.run(new String[]{"librarian", "research", "ask", "How were the Antikythera gears cut and who made them?", "--length", "800", "--citations", "ieee", "--shape", "outline"}, "http://none", "m"));
            List<ObjectNode> active = new Jobs(store, j -> "").active();
            JsonNode cli = active.get(active.size() - 1).path("args");
            assertEquals("800", cli.path("length").asText()); assertEquals("ieee", cli.path("citations").asText()); assertEquals("outline", cli.path("shape").asText());
            assertTrue(said.toString(StandardCharsets.UTF_8).contains("The report is to be about 800 words, with IEEE citations, as an outline."), said.toString(StandardCharsets.UTF_8));
            assertEquals(2, LibrarianCli.run(new String[]{"librarian", "research", "ask", "How were the Antikythera gears cut and who made them?", "--shape", "essay"}, "http://none", "m"));
            assertTrue(LibrarianCli.USAGE.contains("[--length short|medium|long|<words>] [--citations numbered|apa|mla|chicago|ieee] [--shape report|reading-list|outline]"), "the help says so");
        } finally { System.setOut(realOut); System.setProperty("user.home", realHome); Config.invalidate(); }
    }

    @Test
    void theChatsWordsSetTheOptionsOfTheRunItFiles(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Librarian lib = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"How were the gears of the Antikythera mechanism cut?\"}"),
                LibrarianChatTest.say("Filed: a short report with APA citations.")), new ArrayList<>()), Librarian.person(), Librarian.Session.open(store));
        lib.say("Find out how the Antikythera gears were cut. A short report, and use APA citations please.");
        JsonNode args = new Jobs(store, j -> "").active().get(0).path("args");
        assertEquals("short", args.path("length").asText(), args.toString()); assertEquals("apa", args.path("citations").asText()); assertFalse(args.has("shape"));
        // the person's own words win over what the model passed; what the model passed fills what they left unsaid
        Librarian two = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"What survives of the workshop that made the Antikythera mechanism?\", \"shape\": \"outline\", \"length\": \"long\"}"),
                LibrarianChatTest.say("Filed.")), new ArrayList<>()), Librarian.person(), Librarian.Session.open(store));
        two.say("Give me a reading list on what survives of the workshop.");
        List<ObjectNode> active = new Jobs(store, j -> "").active();
        JsonNode second = active.get(active.size() - 1).path("args");
        assertEquals("reading-list", second.path("shape").asText(), "the person said a reading list: " + second); assertEquals("long", second.path("length").asText(), "the model's length stands where the person said none");
    }

    @Test
    void theResearchPageOffersTheOptionsAndTheServiceWritesTheReportWithThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Patrons.setDefault(store, Patrons.Level.write);
        HttpServer probe = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);   // a drive that answers the liveness probe; the run itself is scripted
        probe.createContext("/", ex -> {
            byte[] b = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json"); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        probe.start();
        LibrarianDaemon daemon = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:" + probe.getAddress().getPort(), "scripted", -1);
        List<OptionDrive> drives = new CopyOnWriteArrayList<>();   // the service's worker thread adds, this thread reads
        daemon.researcherFactory = d -> { OptionDrive o = new OptionDrive(null); drives.add(o); return new Researcher(o, new ResearcherTest.FakeTools(), null, 2); };
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            String base = "http://127.0.0.1:" + daemon.port();
            String form = c.send(HttpRequest.newBuilder(URI.create(base + "/research")).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(form.contains("<select name=\"length\">") && form.contains("<option value=\"short\">") && form.contains("<select name=\"citations\">") && form.contains("<option value=\"apa\">APA</option>")
                    && form.contains("<select name=\"shape\">") && form.contains("<option value=\"reading-list\">") && form.contains("name=\"words\""), form);
            String body = "question=" + URLEncoder.encode("How were the gears of the Antikythera mechanism cut, and by whom?", StandardCharsets.UTF_8) + "&mode=broad&sources=both&length=long&citations=chicago&shape=outline&words=";
            HttpResponse<String> sent = c.send(HttpRequest.newBuilder(URI.create(base + "/research")).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, sent.statusCode(), sent.body());
            String id = sent.body().replaceFirst("(?s).*Sent as <a href=\"/jobs/(J-\\d+)[^\"]*\">.*", "$1");
            assertTrue(id.matches("J-\\d+"), sent.body());
            JsonNode job = null;
            for (long until = System.currentTimeMillis() + 60_000; System.currentTimeMillis() < until; Thread.sleep(100)) { job = new Jobs(store, j -> "").get(id); if (job != null && List.of("done", "failed").contains(job.path("state").asText())) break; }
            assertNotNull(job);
            assertEquals("long", job.path("args").path("length").asText(), job.toString()); assertEquals("chicago", job.path("args").path("citations").asText()); assertEquals("outline", job.path("args").path("shape").asText());
            assertEquals("done", job.path("state").asText(), job.toString());
            JsonNode view = new LibraryProtocol(store).job(M.createObjectNode().put("job_id", id)).path("job");   // the job as a program reads it: the report's id
            Investigation inv = store.investigation(view.path("investigation").asText());
            assertNotNull(inv, "the run filed its report: " + view);
            assertEquals(new ReportOptions("long", "chicago", "outline"), inv.options(), "the service's run is written with the page's options and records them");
            assertTrue(drives.get(0).writer().contains("THE SHAPE THE PERSON ASKED FOR: an outline") && drives.get(0).writer().contains("Chicago style"), drives.get(0).writer());
            // the words box: a number of words wins over the length chosen beside it
            String words = "question=" + URLEncoder.encode("What tools survive from the workshop that made the Antikythera mechanism?", StandardCharsets.UTF_8) + "&mode=broad&sources=both&length=short&words=750&citations=&shape=report";
            String sent2 = c.send(HttpRequest.newBuilder(URI.create(base + "/research")).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(words)).build(), HttpResponse.BodyHandlers.ofString()).body();
            String id2 = sent2.replaceFirst("(?s).*Sent as <a href=\"/jobs/(J-\\d+)[^\"]*\">.*", "$1");
            assertEquals("750", new Jobs(store, j -> "").get(id2).path("args").path("length").asText(), sent2);
        } finally { daemon.stop(); probe.stop(0); }
    }
}
