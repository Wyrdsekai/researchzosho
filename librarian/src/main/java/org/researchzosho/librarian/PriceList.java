package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.tools.Fetch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A host's public price list, read when an entry on that host is added or asked to read it again: OpenRouter's, at
 * https://openrouter.ai/api/v1/models, read with no key and the User-Agent the program sends to public sites. Its {@code pricing.prompt},
 * {@code pricing.completion} and {@code pricing.input_cache_read} are dollars per token; the entry keeps them per million, with the date
 * they were read. No other host the program knows publishes such a list in so simple a form.
 */
final class PriceList {

    private PriceList() { }

    private static final ObjectMapper M = new ObjectMapper();

    /** The host whose list is read, and where. */
    static final String HOST = "openrouter.ai", URL = "https://openrouter.ai/api/v1/models";

    /** Where the list is read; a test points it at a stand-in on the loopback, so no test reaches the network. */
    static volatile String LIST_URL = URL;

    /**
     * The list's body, or null when it cannot be read; a test may stand it in. The test task turns it off for the whole suite (the property
     * researchzosho.pricelist=off), so a test that adds an OpenRouter entry never reaches the network; a test of the reading stands it in.
     */
    static volatile Function<String, String> FETCH = "off".equals(System.getProperty("researchzosho.pricelist")) ? url -> null : PriceList::fetch;

    /** The day the list is read; a test stands it in. */
    static volatile Supplier<LocalDate> TODAY = LocalDate::now;

    /** What a reading came to: the prices per million tokens {sent, written, cached?} and the day, or why there are none. */
    record Read(double[] prices, String date, String why) { boolean found() { return prices != null; } }

    /** Whether an entry's host has a list the program reads. */
    static boolean hasList(ModelEntries.Entry e) { return e != null && HOST.equals(e.host()); }

    /** The prices the list gives for {@code model}, per million tokens; none, with the reason in plain words, when it cannot be read or does not hold the model. */
    static Read read(String model) {
        String day = TODAY.get().toString();
        String body;
        try { body = FETCH.apply(LIST_URL); } catch (RuntimeException e) { body = null; }
        if (body == null) return new Read(null, day, HOST + "'s price list could not be read now");
        JsonNode list;
        try { list = M.readTree(body); } catch (Exception e) { return new Read(null, day, HOST + "'s price list could not be read now"); }
        for (JsonNode m : list.path("data")) {
            if (!m.path("id").asText("").equals(model)) continue;
            JsonNode p = m.path("pricing");
            double in = perMillion(p.path("prompt")), out = perMillion(p.path("completion")), cached = perMillion(p.path("input_cache_read"));
            if (in < 0 || out < 0) return new Read(null, day, HOST + "'s price list gives no fixed price for " + model);
            return new Read(cached >= 0 ? new double[]{in, out, cached} : new double[]{in, out}, day, "");
        }
        return new Read(null, day, HOST + "'s price list does not hold " + model);
    }

    /** Dollars per token as the list writes them ("0.000000192"), per million tokens; -1 when absent or not a price. */
    static double perMillion(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return -1;
        try { double d = Double.parseDouble(n.asText().strip()); return d < 0 ? -1 : Math.round(d * 1e6 * 1e6) / 1e6; } catch (NumberFormatException e) { return -1; }
    }

    /** The list, fetched: its body when the host answers with it, else null. */
    static String fetch(String url) {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", Fetch.USER_AGENT).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? r.body() : null;
        } catch (Exception e) { return null; }
    }
}
