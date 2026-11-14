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
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The model calls outside a research run, a translation's seats and the pictures' eyes are on the spending ledger too, each line saying
 * what its calls were for: a chat turn on a model with prices leaves a line for the chat. No call is on it twice: a research run's own
 * calls are its run's, a step that meters its calls is the only one to count them, and the rest go to the purse of the work they belong
 * to. The lines written before keep their meaning, read by their kind. A stand-in model on the loopback, with prices set for it.
 */
class SpendByUseTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String MODEL = "stand-in";

    @TempDir Path tmp;
    String home;
    HttpServer model;
    String base;
    final AtomicInteger asked = new AtomicInteger();

    @BeforeEach void standIn() throws Exception {
        home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/", x -> {
            String asks = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out; String type = "application/json";
            if (x.getRequestURI().getPath().endsWith("/models")) out = ("{\"data\":[{\"id\":\"" + MODEL + "\"}]}").getBytes(StandardCharsets.UTF_8);
            else if (!x.getRequestURI().getPath().endsWith("/chat/completions")) out = "{}".getBytes(StandardCharsets.UTF_8);   // a probe of the server (its props), not a model call
            else if (asks.contains("\"stream\":true")) {   // the answer in pieces, what it cost in the last one
                asked.incrementAndGet(); type = "text/event-stream";
                out = ("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"lo.\"},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":300,\"total_tokens\":1500}}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            } else {
                asked.incrementAndGet();
                out = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello.\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":300,\"total_tokens\":1500}}".getBytes(StandardCharsets.UTF_8);
            }
            x.getResponseHeaders().set("Content-Type", type);
            x.sendResponseHeaders(200, out.length);
            try (OutputStream os = x.getResponseBody()) { os.write(out); }
        });
        model.start();
        base = "http://127.0.0.1:" + model.getAddress().getPort();
        // the library's model, named reader-b with its prices: $1 a million tokens in, $2 out
        Config.set("RESEARCHZOSHO_DRIVE", base); Config.set("RESEARCHZOSHO_MODEL", MODEL);
        Config.set(ModelEntries.key("reader-b", "DRIVE"), base); Config.set(ModelEntries.key("reader-b", "MODEL"), MODEL); Config.set(ModelEntries.key("reader-b", "PRICES"), "1/2");
    }

    @AfterEach void restore() {
        model.stop(0);
        DriveClient.streamHere(null);
        System.setProperty("user.home", home); Config.invalidate();
    }

    static List<JsonNode> linesFor(LibraryStore store, String purpose) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode l : Spend.lines(store)) if (purpose.equals(l.path("for").asText(""))) out.add(l);
        return out;
    }

    static ArrayNode ask(String words) { ArrayNode m = M.createArrayNode(); m.addObject().put("role", "user").put("content", words); return m; }

    @Test void aChatTurnOnAModelWithPricesIsOnTheLedgerForTheChat() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Librarian lib = new Librarian(store, Researcher.watchedChat(base, MODEL), Librarian.person(), Librarian.Session.open(store));
        lib.say("Hello there.");
        assertTrue(asked.get() > 0, "the turn asked the model");
        List<JsonNode> chat = linesFor(store, "chat");
        assertEquals(1, chat.size(), "one line for the turn, its calls folded: " + Spend.lines(store));
        JsonNode l = chat.get(0);
        assertEquals("reader-b", l.path("entry").asText(), "the named model with prices for that server and model");
        assertEquals(asked.get(), l.path("calls").asInt(), "every call of the turn");
        assertEquals(1200L * asked.get(), l.path("prompt").asLong());
        assertEquals(300L * asked.get(), l.path("completion").asLong());
        assertEquals(Math.round((1200.0 * 1 + 300.0 * 2) * asked.get() / 1e6 * 10000) / 10000.0, l.path("cost").asDouble(), 1e-9);
        assertEquals("calls", l.path("kind").asText(), "not a run: the runs of the entry are counted as before");
        // what the turn cost, by use, in the summary and in words
        ObjectNode sum = Spend.summary(store);
        JsonNode use = sum.path("uses").get(0);
        assertEquals("chat", use.path("for").asText());
        assertEquals(l.path("cost").asDouble(), use.path("month").asDouble(), 0.006);
        JsonNode entry = null; for (JsonNode e : sum.path("entries")) if (e.path("name").asText().equals("reader-b")) entry = e;
        assertNotNull(entry);
        assertEquals(0, entry.path("runs_month").asInt(), "a chat turn is not a run");
        assertTrue(Spend.rowSaid(entry, Chrome.ENGLISH).contains("of which outside a run: "), Spend.rowSaid(entry, Chrome.ENGLISH));
        assertTrue(Spend.useRowSaid(use, Chrome.ENGLISH).startsWith("the chat: $"), Spend.useRowSaid(use, Chrome.ENGLISH));
        // a row's size, for a busy day's ledger
        long bytes = Files.size(Spend.file(store));
        assertTrue(bytes < 400, "one line, folded: " + bytes + " bytes");
    }

    @Test void aModelWithNoPricesOnTheLibrarysOwnMachineLeavesNoLine() throws Exception {
        Config.set(ModelEntries.key("reader-b", "PRICES"), "");
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new Librarian(store, Researcher.watchedChat(base, MODEL), Librarian.person(), Librarian.Session.open(store)).say("Hello there.");
        assertTrue(asked.get() > 0);
        assertEquals(List.of(), Spend.lines(store), "it costs nothing: nothing to write");
    }

    @Test void noCallIsCountedTwice() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        DriveClient c = new DriveClient(base, MODEL);
        long[] run = new long[3], seat = new long[3];
        try (SpendPurse job = SpendPurse.open(store, SpendPurse.RESEARCH, "J-0001")) {
            // a research run's own calls: its count, as before, and not the purse
            DriveClient.countInto(run);
            c.chat(ask("one"), null, 50);
            c.classify(ask("one, a yes or a no"), 5);   // a short decision too: its tokens never reached the run's count before
            // a hosted seeing entry's call inside the run: the pictures' tally alone, not the run's count too
            DriveClient.meteredByCaller(true);
            c.chat(ask("two"), null, 50);
            DriveClient.meteredByCaller(false);
            DriveClient.stopCounting();
            // a translation's hosted seat: its tally alone
            Translation.metered(Researcher.drive(c), seat, true).prose(ask("three"), 50);
            // the step after the run: the job's purse
            c.chat(ask("four"), null, 50);
        }
        assertArrayEquals(new long[]{2400, 0, 600}, run, "the run's count holds its own calls, the decision's among them, and not the picture's");
        assertArrayEquals(new long[]{1200, 300, 0}, seat, "the seat counts its call (prompt, completion, cached)");
        List<JsonNode> research = linesFor(store, "research");
        assertEquals(1, research.size(), Spend.lines(store).toString());
        assertEquals(1, research.get(0).path("calls").asInt(), "only the step after the run");
        assertEquals("J-0001", research.get(0).path("job").asText());
    }

    @Test void aStreamedCallCountsAsAPlainOneDoes() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        StringBuilder shown = new StringBuilder();
        DriveClient.streamHere(shown::append);
        try (SpendPurse turn = SpendPurse.open(store, SpendPurse.CHAT, "")) {
            new DriveClient(base, MODEL).chat(ask("hello"), null, 50);
        } finally { DriveClient.streamHere(null); }
        assertEquals("Hello.", shown.toString(), "the answer streamed");
        List<JsonNode> chat = linesFor(store, "chat");
        assertEquals(1, chat.size(), Spend.lines(store).toString());
        assertEquals(1200, chat.get(0).path("prompt").asLong());
        assertEquals(300, chat.get(0).path("completion").asLong());
    }

    @Test void theLinesWrittenBeforeKeepTheirMeaning() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Files.createDirectories(Spend.file(store).getParent());
        String month = YearMonth.now().toString();
        Files.writeString(Spend.file(store), String.join("\n",
                "{\"secs\":600,\"status\":\"filed\",\"kind\":\"research\",\"t\":\"" + month + "-02T10:00:00Z\",\"job\":\"J-0001\",\"report\":\"I-0001\",\"entry\":\"reader-b\",\"host\":\"127.0.0.1\",\"model\":\"stand-in\",\"prompt\":100000,\"cached\":0,\"completion\":10000,\"cost\":0.12,\"priced\":true}",
                "{\"secs\":60,\"status\":\"filed\",\"kind\":\"translate\",\"t\":\"" + month + "-02T11:00:00Z\",\"job\":\"J-0002\",\"report\":\"\",\"entry\":\"reader-b\",\"host\":\"127.0.0.1\",\"model\":\"stand-in\",\"prompt\":10000,\"cached\":0,\"completion\":1000,\"cost\":0.012,\"priced\":true}",
                "{\"secs\":60,\"status\":\"filed\",\"kind\":\"pictures\",\"t\":\"" + month + "-02T11:00:00Z\",\"job\":\"J-0002\",\"report\":\"\",\"entry\":\"reader-b\",\"host\":\"127.0.0.1\",\"model\":\"stand-in\",\"prompt\":10000,\"cached\":0,\"completion\":1000,\"cost\":0.012,\"priced\":true}") + "\n");
        ObjectNode sum = Spend.summary(store);
        List<String> uses = new ArrayList<>(); for (JsonNode u : sum.path("uses")) uses.add(u.path("for").asText());
        assertEquals(List.of("research", "translation", "picture"), uses, "read by their kind");
        JsonNode entry = sum.path("entries").get(0);
        assertEquals(3, entry.path("runs_month").asInt(), "three runs, as before");
        assertFalse(entry.has("calls_all"), "no calls outside a run");
        assertEquals("reader-b: $0.14 this month (3 runs), $0.14 in all (3)", Spend.rowSaid(entry, Chrome.ENGLISH), "the line reads as it did");
    }
}
