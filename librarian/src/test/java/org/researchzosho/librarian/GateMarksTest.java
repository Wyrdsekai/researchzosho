package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.researchzosho.Config;
import org.researchzosho.drive.Gated;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a library's requests carry for its node's gates (0.8.0, stage 3), and the settings an operator sets for the gates: a page of the
 * web carries the use its work is part of in the proxy's header, on the thread that does the work and on one doing part of it, and
 * nothing for this machine's own addresses; an entry's calls at once and kept slots, and a search place's price, are settings by name.
 */
class GateMarksTest {
    @AfterEach void reset() { Gated.WEB = () -> null; Gated.CAPTURE = () -> null; }

    static String mark(String url) { return Gated.webMark(url, HttpRequest.newBuilder(URI.create(url))).build().headers().firstValue(Gated.WEB_USE).orElse(null); }

    @Test
    void aPageOfTheWebCarriesTheUseOfTheWorkItIsPartOf() throws Exception {
        assertNull(mark("https://example.org/page"), "outside an account service, nothing");
        Gated.WEB = () -> "use_run";
        assertEquals("use_run", mark("https://example.org/page"));
        assertNull(mark("http://127.0.0.1:8080/v1/chat/completions"), "this machine's own: nothing");
        assertNull(mark("http://localhost:4649/"));
        assertEquals("use_run", Gated.request("https://api.crossref.org/works").build().headers().firstValue(Gated.WEB_USE).orElse(null), "a data source's call too");
        // a thread doing part of the work carries it; a page added by its address carries its own use
        Callable<String> reader = Gated.carried(() -> mark("https://example.org/other"));
        Gated.WEB = () -> null;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try { assertEquals("use_run", pool.submit(reader).get()); } finally { pool.shutdown(); }
        assertEquals("use_add", Gated.under("use_add", Gated.WAITING, "en", () -> mark("https://example.org/added")));
        assertNull(mark("https://example.org/after"), "and only while it runs");
    }

    @Test
    void theGatesSettingsAreSetByName() throws Exception {
        Settings.Setting calls = Settings.named("entry.default.calls"), kept = Settings.named("entry.local-27b.kept"), price = Settings.named("search_price.brave");
        assertEquals("RESEARCHZOSHO_ENTRY_DEFAULT_CALLS", calls.key());
        assertEquals(ModelEntries.key("local-27b", "KEPT"), kept.key(), "the entry's own key, as the gate reads it");
        assertTrue(!Settings.refuse(calls, "many").isEmpty() && Settings.refuse(calls, "8").isEmpty());
        assertEquals("0.005", price.example(), "Brave's published price is what it is unless set");
        assertTrue(Settings.refuse(price, "0.004").isEmpty() && !Settings.refuse(price, "four").isEmpty());
        assertNull(Settings.named("search_price.nowhere"), "a place the gate does not know");
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        try {
            assertEquals(0, AccountsCli.settings(new String[]{"researchzosho", "settings", "set", "entry.default.kept", "0"}, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8)), err.toString());
            assertEquals("0", Config.get(ModelEntries.key(ModelEntries.DEFAULT, "KEPT")));
            assertEquals(0, AccountsCli.settings(new String[]{"researchzosho", "settings", "set", "search_price.serpapi", "0.015"}, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8)), err.toString());
            assertEquals(15_000, NodeGates.priceMicros("serpapi"));
        } finally {
            Config.set(ModelEntries.key(ModelEntries.DEFAULT, "KEPT"), "");
            Config.set(Settings.SEARCH_PRICE + "SERPAPI", "");
        }
        assertEquals(25_000, NodeGates.priceMicros("serpapi"), "unset: the price read from SerpApi's page");
    }
}
