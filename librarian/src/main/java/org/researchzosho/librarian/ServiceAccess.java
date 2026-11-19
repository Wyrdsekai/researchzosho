package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Who may read and write a hosted library, and the keys of its people, as the library's own call answers them (0.8.0): the call
 * {@code access} is passed to the account service, which keeps the members and the keys, with the statement of the request that
 * asked, so the service acts for that account and for nobody else. The library itself holds no list.
 */
final class ServiceAccess {
    private ServiceAccess() { }

    /** The service a hosted library in this process answers to; set when its daemon starts. */
    static volatile String service;
    /** Where the service takes the call. */
    static final String PATH = "/internal/access";
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();

    static ObjectNode access(LibraryStore store, Patrons.Patron patron, JsonNode args, ObjectNode r) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        String statement = Vouching.CURRENT.get(), at = service;
        if (statement == null || at == null) throw ProtocolError.unavailable("The people of this library and the keys for programs are on your account page, at the account service this library runs under.");
        ObjectNode body = args.deepCopy();
        body.remove("patron");
        try {
            HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(at + PATH)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json").header(Statement.HEADER, statement)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode j = Statement.M.readTree(resp.body());
            if (resp.statusCode() >= 400 || j.has("error")) {
                String code = j.path("error").path("code").asText("unavailable"), said = j.path("error").path("message").asText("The account service did not answer (" + resp.statusCode() + ").");
                throw code.equals("forbidden") ? ProtocolError.forbidden(said) : code.equals("invalid_args") ? ProtocolError.invalidArgs(said) : code.equals("not_found") ? ProtocolError.notFound(said)
                        : code.equals(ProtocolError.RATE_LIMITED) ? ProtocolError.rateLimited(said, j.path("error").path("retry_after").asLong(60)) : ProtocolError.unavailable(said);
            }
            if (j.isObject()) r.setAll((ObjectNode) j);
            return r;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw ProtocolError.unavailable("interrupted"); }
    }
}
