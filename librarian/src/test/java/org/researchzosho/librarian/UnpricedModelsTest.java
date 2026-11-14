package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.librarian.Translation.Seats;
import org.researchzosho.librarian.TranslationTest.Script;
import org.researchzosho.tools.Fetch;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A hosted model whose entry has no prices: its calls are paid at prices the entry does not state, so no surface says they cost $0.00 (the
 * end line, the job, the result and spend name the tokens the server counted), spend keeps them apart, and a cap on such an entry is said
 * to count nothing (models add, models list, spend, a translation's plan) without refusing the run. An OpenRouter model added with no
 * prices reads them from openrouter.ai's public list, with the date; a list that cannot be read leaves it without prices and says so.
 */
class UnpricedModelsTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String LIST = "{\"data\":[{\"id\":\"moonshotai/kimi-k3\",\"pricing\":{\"prompt\":\"0.000000192\",\"completion\":\"0.000016\",\"input_cache_read\":\"0.000000182\"}},"
            + "{\"id\":\"some/other-model\",\"pricing\":{\"prompt\":\"0.0000005\",\"completion\":\"0.000002\"}}]}";

    String home;
    HttpServer list;
    Function<String, String> suiteFetch;
    final List<String[]> asked = new ArrayList<>();   // each request to the stand-in list: its path, its User-Agent, its Authorization

    @BeforeEach void standIn(@TempDir Path tmp) throws IOException {
        home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        TranslationScores.SCORERS = () -> new TranslationScores.Scorers(TranslationTest.labseStandIn(), null);
        Translation.SETUP_SAID = () -> "";
        Translation.ANY_MODEL = () -> true;
        Translation.DRIVE_ANSWERS = url -> true;
        Config.set("RESEARCHZOSHO_DRIVE", "http://homebox:8212"); Config.set("RESEARCHZOSHO_MODEL", "qwen3.8-27b");
        Config.set(DriveClient.hostKeySetting("openrouter.ai"), "sk-or-test");
        list = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        list.createContext("/api/v1/models", x -> {
            synchronized (asked) { asked.add(new String[]{x.getRequestURI().getPath(), x.getRequestHeaders().getFirst("User-Agent"), String.valueOf(x.getRequestHeaders().getFirst("Authorization"))}); }
            byte[] b = LIST.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length);
            try (OutputStream o = x.getResponseBody()) { o.write(b); }
        });
        list.start();
        PriceList.LIST_URL = "http://127.0.0.1:" + list.getAddress().getPort() + "/api/v1/models";
        suiteFetch = PriceList.FETCH; PriceList.FETCH = PriceList::fetch;   // the real reading, of the stand-in list on the loopback
        PriceList.TODAY = () -> LocalDate.of(2026, 11, 13);
    }

    @AfterEach void restore() {
        list.stop(0);
        PriceList.LIST_URL = PriceList.URL; PriceList.TODAY = LocalDate::now; PriceList.FETCH = suiteFetch;
        TranslationScores.SCORERS = TranslationScores::configured;
        Translation.SETUP_SAID = () -> { String a = TranslationScores.labseSetupSaid(), b = TranslationScores.metricxSetupSaid(); return a.isEmpty() ? b : b.isEmpty() ? a : a + "; " + b; };
        Translation.ANY_MODEL = Translation::anyModelNow;
        Translation.DRIVE_ANSWERS = Crews::driveAnswers;
        Translation.SEATS = Translation::seatsFor;
        System.setProperty("user.home", home); Config.invalidate();
    }

    static String cli(LibraryStore store, String... args) throws Exception {
        String[] full = new String[args.length + 1]; full[0] = "researchzosho"; System.arraycopy(args, 0, full, 1, args.length);
        return DocNamesTest.cli(() -> full[1].equals("models") ? LibrarianCli.modelsCommand(store, full) : LibrarianCli.spendCommand(store));
    }

    /** A translation job filed through the API, run with the reader stood in, its record as the job keeps it at the end. */
    static ObjectNode ranJob(LibraryStore store, Path f, String reader, long sent, long written) throws Exception {
        ObjectNode ask = M.createObjectNode(); ask.put("path", f.toString()); ask.put("to", "ja"); ask.put("quality", "reviewed"); ask.put("judge", reader);
        ask.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        String id = new LibraryProtocol(store).translate(ask).path("job_id").asText();
        Script sc = new Script();
        Translation.SEATS = (drive, model, judge, back) -> new Seats(TranslationTest.standIn(sc, "qwen3.8-27b"), "qwen3.8-27b", "qwen3.8-27b", null,
                TranslationTest.standIn(sc, "moonshotai/kimi-k3"), "moonshotai/kimi-k3", reader, ModelEntries.get(reader), false, false, "", new long[3], new long[]{sent, written, 0});
        ObjectNode job = new Jobs(store, j -> "").get(id);
        String result = Translation.runJob(store, job, "http://homebox:8212", "qwen3.8-27b", p -> { }, () -> false, Checkpoint.NONE, job::setAll);
        job.put("state", "done"); job.put("result", result);
        return job;
    }

    @Test void anUnpricedReadersCallsAreSaidInTokensNeverInDollars(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path f = TranslationTest.fixture(Files.createDirectories(tmp.resolve("docs")));
        ModelEntries.put("kimi", "openrouter", "moonshotai/kimi-k3", null, null, "0.05", null);   // no prices, a cap: as an entry added before prices were read
        ObjectNode job = ranJob(store, f, "kimi", 695, 21);
        JsonNode facts = Jobs.view(job).path("translation");
        String said = facts.path("said").asText();
        assertTrue(said.contains("read by kimi (moonshotai/kimi-k3, hosted at openrouter.ai), a paid call at prices the entry does not state: 695 tokens sent and 21 written."), said);
        assertFalse(said.contains("$"), "no dollar figure for a call at prices nobody stated: " + said);
        assertFalse(facts.path("reader").has("cost_dollars"), facts.toString());
        assertEquals(695, facts.path("reader").path("sent_tokens").asLong()); assertFalse(facts.path("reader").path("priced").asBoolean(true));
        assertTrue(job.path("result").asText().contains("a paid call at prices the entry does not state: 695 tokens sent and 21 written") && !job.path("result").asText().contains("it cost"), job.path("result").asText());
        // spend keeps it apart, in tokens, with the cap said to count nothing; the month's dollars hold none of it
        String spend = cli(store, "spend");
        assertTrue(spend.contains("    kimi: runs at prices the entry does not state: 1 this month (695 tokens sent, 21 written), 1 in all, cap $0.05 a month, which counts nothing until the entry has prices"), spend);
        assertFalse(spend.contains("kimi: $0.00"), spend);
        JsonNode api = new LibraryProtocol(store).spend(M.createObjectNode().set("patron", M.createObjectNode().put("did", "person").put("name", "keeper").put("runtime", "cli")));
        assertTrue(api.path("summary").asText().endsWith(" 1 run was at prices the entry does not state, so not in that figure."), api.path("summary").asText());
        // a record of an earlier release, with the dollars written as nothing and the reader filed as unpriced: no "$0.00" either
        ObjectNode older = job.deepCopy(); ((ObjectNode) older.path("spent")).removeAll(); ((ObjectNode) older.path("spent")).put("reader_dollars", 0.0);
        String olderSaid = Jobs.view(older).path("translation").path("said").asText();
        assertTrue(olderSaid.contains("a paid call at prices the entry does not state") && !olderSaid.contains("$0.00"), olderSaid);
    }

    @Test void aPricedReadersLinesAreAsBefore(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path f = TranslationTest.fixture(Files.createDirectories(tmp.resolve("docs")));
        ModelEntries.put("reader-b", "openrouter", "moonshotai/kimi-k3", "0.192/16/0.182", null, "1", null);
        ObjectNode job = ranJob(store, f, "reader-b", 20_000, 3_000);
        String said = Jobs.view(job).path("translation").path("said").asText();
        assertTrue(said.matches("(?s).*read by reader-b \\(moonshotai/kimi-k3, hosted at openrouter.ai\\), a paid call: about \\$\\d+\\.\\d\\d at its list prices; it cost \\$0\\.05\\..*"), said);
        assertTrue(cli(store, "spend").contains("    reader-b: $0.05 this month (1 run), $0.05 in all (1), cap $1.00 a month\n"), cli(store, "spend"));
    }

    @Test void anOpenRouterModelAddedWithNoPricesReadsThemFromTheListWithTheDate(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String added = cli(store, "models", "add", "kimi", "openrouter", "moonshotai/kimi-k3");
        assertTrue(added.contains("Prices as read from openrouter.ai's list on 2026-11-13: $0.192 per million tokens sent, $16 written, $0.182 cached; researchzosho models set kimi --prices in/out/cache sets others, and --prices list reads the list again."), added);
        assertEquals(1, asked.size(), "one reading of the list");
        assertEquals(Fetch.USER_AGENT, asked.get(0)[1], "the program's User-Agent for public sites"); assertEquals("null", asked.get(0)[2], "no key is sent");
        assertArrayEquals(new double[]{0.192, 16, 0.182}, ModelEntries.get("kimi").prices(), 1e-9);
        assertEquals("openrouter.ai 2026-11-13", ModelEntries.pricedFrom("kimi"));
        assertTrue(cli(store, "models", "list").contains("kimi: moonshotai/kimi-k3 via openrouter.ai — $0.192/16/0.182 per M, tier ") && cli(store, "models", "list").contains("; prices as read from openrouter.ai's list on 2026-11-13;"), cli(store, "models", "list"));
        // another change leaves the prices as they are and reads nothing
        cli(store, "models", "set", "kimi", "--tier", "A");
        assertEquals(1, asked.size(), "a change of tier reads no list");
        // prices set by hand are never overwritten without being asked; --prices list is the asking
        cli(store, "models", "set", "kimi", "--prices", "1/2");
        assertEquals("", ModelEntries.pricedFrom("kimi"));
        String again = cli(store, "models", "add", "kimi", "openrouter", "moonshotai/kimi-k3");
        assertTrue(again.contains("kimi keeps the prices set by hand; researchzosho models set kimi --prices list takes openrouter.ai's instead."), again);
        assertArrayEquals(new double[]{1, 2}, ModelEntries.get("kimi").prices(), 1e-9); assertEquals(1, asked.size());
        String reread = cli(store, "models", "set", "kimi", "--prices", "list");
        assertTrue(reread.contains("Prices as read from openrouter.ai's list on 2026-11-13: $0.192 per million tokens sent"), reread);
        assertEquals(2, asked.size()); assertArrayEquals(new double[]{0.192, 16, 0.182}, ModelEntries.get("kimi").prices(), 1e-9);
    }

    @Test void aListThatCannotBeReadLeavesTheEntryWithoutPricesAndACapIsSaidToCountNothing(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        String missing = cli(store, "models", "add", "other", "openrouter", "not/listed-model");
        assertTrue(missing.contains("openrouter.ai's price list does not hold not/listed-model, so other has no prices: what its calls cost will not be known, and a cap cannot count them."), missing);
        PriceList.LIST_URL = "http://127.0.0.1:1/api/v1/models";   // nothing answers there
        String added = cli(store, "models", "add", "kimi", "openrouter", "moonshotai/kimi-k3", "--cap", "0.05");
        assertTrue(added.contains("openrouter.ai's price list could not be read now, so kimi has no prices: what its calls cost will not be known, and a cap cannot count them. researchzosho models set kimi --prices list reads the list again; --prices in/out/cache sets them by hand."), added);
        assertTrue(added.endsWith("Its cap of $0.05 a month counts nothing until kimi has prices: the cap adds up dollars, and calls with no prices cost none it can count.\n"), "the way to prices said once: " + added);
        int[] fetches = {0};
        PriceList.FETCH = url -> { fetches[0]++; return PriceList.fetch(url); };
        String capOnly = cli(store, "models", "add", "plain", "anthropic", "claude-some-model", "--cap", "2");   // a host with no list the program reads
        assertTrue(capOnly.endsWith("Its cap of $2.00 a month counts nothing until plain has prices: the cap adds up dollars, and calls with no prices cost none it can count. researchzosho models set plain --prices in/out/cache gives it prices.\n"), capOnly);
        assertEquals(0, fetches[0], "no list is read for a host that publishes none");
        assertNull(ModelEntries.get("kimi").prices());
        assertTrue(cli(store, "models", "list").contains("cap $0.05 a month, tier ") && cli(store, "models", "list").contains("; the cap counts nothing until kimi has prices (researchzosho models set kimi --prices list)"), cli(store, "models", "list"));
        // the plan of a translation it reads: its cost is not known and the cap does not apply; the run is not refused
        Path f = TranslationTest.fixture(Files.createDirectories(tmp.resolve("docs")));
        Translation.Plan plan = Translation.plan(store, f, "ja", "", "reviewed", "qwen3.8-27b", "", "kimi");
        assertTrue(plan.said().contains("the reader kimi is a hosted model: a paid call, at prices the entry does not state (tokens sent: about ")
                && plan.said().contains("; its cost is not known, and its cap of $0.05 a month does not apply to it: the entry has no prices to count with"), plan.said());
        assertTrue(plan.askedSaid(Chrome.ENGLISH).contains("its cap of $0.05 a month does not apply to it"), "the question before the job says it too");
        ObjectNode job = ranJob(store, f, "kimi", 100, 10);
        assertTrue(job.path("result").asText().startsWith("Translated \"The Saros dial\""), "the person named the model: the run goes ahead: " + job.path("result").asText());
        assertTrue(cli(store, "spend").contains("cap $0.05 a month, which counts nothing until the entry has prices"), cli(store, "spend"));
    }
}
