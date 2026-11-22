package org.researchzosho.librarian;

import org.researchzosho.tools.Outbound;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A library's side of the account service (0.8.0): the service it answers to, the service's public keys, and the reading of the
 * statement each request carries. A library started with {@code serve --vouched-by <address>} has one of these, and then a request is
 * who its statement says and nobody else: no cookie, no key of the library's own, no token, no header a proxy added, and no browser on
 * this computer is believed.
 */
public final class Vouching {
    /** The service's address, which is also the name its statements are made in. */
    final String service;
    /** Where this library reaches the service: its address, or for a library in a container the way to it that the node opened. */
    final String at;
    private final Statement.Keys keys;
    /** The statement of the request this thread is serving, for the calls the library makes back to the service in the caller's name. */
    static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** A library under the service at this address; its keys are read from the service's own key file. */
    public Vouching(String service) { this(service, service); }

    /** The same, the service reached at {@code at} (0.8.0: a library in a container reaches it through the node), its statements still made in its own name; null: at its own address. */
    public Vouching(String service, String at) { this(service, at == null || at.isBlank() ? service : at, new Published(at == null || at.isBlank() ? service : at)); }

    /** The same with the keys given (a test's). */
    public Vouching(String service, Statement.Keys keys) { this(service, service, keys); }

    /** The service reached at {@code at}, with the keys given (a test's). */
    Vouching(String service, String at, Statement.Keys keys) {
        this.service = service.strip().replaceAll("/+$", ""); this.at = (at == null || at.isBlank() ? service : at).strip().replaceAll("/+$", ""); this.keys = keys;
    }

    /**
     * Who a request is, from its statement alone; an exception says in a sentence why the request is refused. What the statement said
     * of the account is noted for the library ({@link Hosted#note}).
     */
    Patrons.Patron patron(HttpExchange x, LibraryStore store) throws IOException { return patron(store, claims(x, store)); }

    /** What the request's statement says, checked: signed by the service, made for this library, in its minute. */
    Statement.Claims claims(HttpExchange x, LibraryStore store) throws IOException {
        return Statement.verify(x.getRequestHeaders().getFirst(Statement.HEADER), keys, service, store.identity().id());
    }

    /** The person a checked statement vouches for. The service's own statement vouches for no person. */
    Patrons.Patron patron(LibraryStore store, Statement.Claims c) throws IOException {
        if (c.via().equals(Statement.SERVICE)) throw new IOException("The account service's own statement opens only its own calls.");
        String did = Hosted.DID + c.sub();
        boolean keeper = c.lvl().equals(Statement.KEEPER);
        Patrons.Level level = c.lvl().equals(Statement.READ) ? Patrons.Level.read : Patrons.Level.write;
        Hosted.note(store, did, new Hosted.Vouch(level, keeper, c.sub(), c.name()));
        String runtime = c.via().equals(Statement.KEY_READ) ? Patrons.KEY_READ : c.via().equals(Statement.KEY) ? Patrons.KEY : "proxy";
        return new Patrons.Patron(did, c.name(), runtime);
    }

    /** The service's published keys, read once and again when a statement names a key not yet seen (at most every five seconds). */
    static final class Published implements Statement.Keys {
        private final String service;
        private final Map<String, PublicKey> known = new ConcurrentHashMap<>();
        private volatile long read;
        private static final HttpClient HTTP = Outbound.client(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build());

        Published(String service) { this.service = service.strip().replaceAll("/+$", ""); }

        @Override public PublicKey key(String kid) throws IOException {
            PublicKey k = known.get(kid);
            if (k != null) return k;
            synchronized (this) {
                k = known.get(kid);
                if (k != null || System.currentTimeMillis() - read < 5_000) return k;
                read = System.currentTimeMillis();
                try {
                    HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(service + AccountService.KEYS_PATH)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() != 200) throw new IOException("The account service at " + service + " did not give its keys (" + r.statusCode() + ").");
                    for (JsonNode j : Statement.M.readTree(r.body()).path("keys")) known.put(j.path("kid").asText(""), Statement.fromJwk(j));
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
                return known.get(kid);
            }
        }
    }
}
