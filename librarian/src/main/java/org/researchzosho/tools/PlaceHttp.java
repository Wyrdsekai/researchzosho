package org.researchzosho.tools;

import org.researchzosho.Stopping;
import org.researchzosho.Version;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The HTTP the search places share (PubMed, Semantic Scholar, Exa, Tavily, the keeper's own search service): one User-Agent that names
 * the library and its site and nobody's address, a time limit on every call, the run's stop, and no redirect followed, so a key sent to
 * one address never reaches another. A call that gets no answer comes back as status 0, never as an exception, so a place can say
 * plainly that the service did not answer.
 */
public final class PlaceHttp {
    private PlaceHttp() { }

    /** The library and its site; never an e-mail address. */
    public static final String UA = "ResearchZosho/" + Version.string() + " (a research library; https://researchzosho.org)";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    /** What came back: the status (0 when nothing did), the headers by lower-case name, and the body. */
    public record Res(int status, Map<String, String> headers, String body) {
        public boolean ok() { return status >= 200 && status < 300; }
        public String header(String name) { return headers.getOrDefault(name.toLowerCase(Locale.ROOT), ""); }
    }

    public static Res get(String url, Map<String, String> headers, Duration limit, String what) { return send("GET", url, headers, null, limit, what); }

    public static Res post(String url, Map<String, String> headers, String json, Duration limit, String what) { return send("POST", url, headers, json, limit, what); }

    static Res send(String method, String url, Map<String, String> headers, String json, Duration limit, String what) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(limit).setHeader("User-Agent", UA).setHeader("Accept", "application/json");
            for (Map.Entry<String, String> h : headers.entrySet()) b.setHeader(h.getKey(), h.getValue());
            if (method.equals("POST")) b.setHeader("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json));
            else b.GET();
            HttpResponse<String> r = Stopping.send(HTTP, b.build(), HttpResponse.BodyHandlers.ofString(), limit, what);
            Map<String, String> hs = new HashMap<>();
            r.headers().map().forEach((k, v) -> hs.put(k.toLowerCase(Locale.ROOT), String.join(", ", v)));
            return new Res(r.statusCode(), hs, r.body() == null ? "" : r.body());
        } catch (Stopping.Requested stop) {
            throw stop;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Res(0, Map.of(), "");
        } catch (Exception e) {
            return new Res(0, Map.of(), "");
        }
    }

    /** A pause between two calls, cut short by nothing but an interrupt. */
    static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
