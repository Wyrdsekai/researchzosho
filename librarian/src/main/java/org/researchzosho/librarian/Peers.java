package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Other libraries this one may ask. A peer is a name, an address and a reader token that library
 * gave you, kept in {@code catalog/peers.md}, and it may belong to groups ("family", "the lab"). An
 * ask that names a group, or an ask this library cannot answer when a default group is set, is put to
 * each peer in the group, and every answer comes back labelled with the library it came from. Nothing
 * is copied and nothing is merged: "yours holds nothing; Alice's holds two findings" is the whole of it.
 *
 * <p>One hop only. A peer is asked with {@code peers: "none"}, so it answers from its own shelves and
 * never asks its own peers. Otherwise a question loops round a circle and its provenance turns to mud.
 * A peer sees only what its reader level allows, which is the existing reader list doing its job.
 */
public final class Peers {

    private static final ObjectMapper M = new ObjectMapper();
    static final int TIMEOUT_S = org.researchzosho.Config.getInt("RESEARCHZOSHO_PEER_TIMEOUT", 12);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "peers"); t.setDaemon(true); return t; });

    public record Peer(String name, String url, String token, List<String> groups) {
        boolean in(String group) { return group != null && (group.equals(name) || groups.contains(group) || group.equals("all")); }
    }

    private Peers() { }

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("peers.md"); }

    public static synchronized List<Peer> list(LibraryStore store) throws IOException {
        List<Peer> out = new ArrayList<>();
        if (!Files.exists(file(store))) return out;
        for (String line : Files.readAllLines(file(store), StandardCharsets.UTF_8)) {
            if (!line.startsWith("- ")) continue;
            String[] p = line.substring(2).split(" — ", 4);
            if (p.length < 3) continue;
            List<String> groups = p.length == 4 && !p[3].isBlank() ? List.of(p[3].strip().split("\\s*,\\s*")) : List.of();
            out.add(new Peer(p[0].strip(), p[1].strip().replaceAll("/+$", ""), p[2].strip(), groups));
        }
        return out;
    }

    public static synchronized void add(LibraryStore store, String name, String url, String token, List<String> groups) throws IOException {
        if (name.isBlank() || name.contains(" — ") || url.contains(" — ") || token.contains(" — ")) throw new IOException("a peer's name, address and token may not be blank or contain ' — '");
        String why = Webhooks.refusal(url);
        if (why != null) throw new IOException("cannot ask " + url + ": " + why);
        List<Peer> all = new ArrayList<>();
        for (Peer p : list(store)) if (!p.name().equals(name)) all.add(p);
        all.add(new Peer(name, url, token, groups == null ? List.of() : groups));
        write(store, all);
    }

    public static synchronized boolean remove(LibraryStore store, String name) throws IOException {
        List<Peer> kept = new ArrayList<>(); boolean found = false;
        for (Peer p : list(store)) { if (p.name().equals(name)) found = true; else kept.add(p); }
        if (found) write(store, kept);
        return found;
    }

    private static void write(LibraryStore store, List<Peer> peers) throws IOException {
        Files.createDirectories(file(store).getParent());
        StringBuilder sb = new StringBuilder("# Peers — other libraries this one may ask, with the reader token each gave us (the owner's file)\n\nOne per line: `- <name> — <url> — <token> — <groups>`.\n\n");
        for (Peer p : peers) sb.append("- ").append(p.name()).append(" — ").append(p.url()).append(" — ").append(p.token()).append(" — ").append(String.join(",", p.groups())).append('\n');
        Files.writeString(file(store), sb.toString(), StandardCharsets.UTF_8);
    }

    /** The peers an ask names: a peer's name, a group, or "all". */
    public static List<Peer> select(LibraryStore store, String which) throws IOException {
        List<Peer> out = new ArrayList<>();
        if (which == null || which.isBlank() || which.equals("none")) return out;
        for (Peer p : list(store)) if (p.in(which)) out.add(p);
        return out;
    }

    /** The group asked by default when this library holds nothing, or "" for none. */
    public static String defaultGroup() {
        String g = org.researchzosho.Config.get("RESEARCHZOSHO_PEERS_DEFAULT", "");
        return g == null ? "" : g.strip();
    }

    /**
     * Put {@code question} to each peer at once and collect what each library says, labelled. A peer that
     * does not answer in time, or refuses, is reported as such rather than dropped, so an empty answer is
     * never mistaken for "asked nobody".
     */
    public static ArrayNode ask(List<Peer> peers, String question, int k) {
        ArrayNode out = M.createArrayNode();
        if (peers.isEmpty()) return out;
        List<Future<ObjectNode>> futures = new ArrayList<>();
        for (Peer p : peers) futures.add(POOL.submit(() -> askOne(p, question, k)));
        for (int i = 0; i < peers.size(); i++) {
            Peer p = peers.get(i);
            ObjectNode one;
            try { one = futures.get(i).get(TIMEOUT_S + 2, TimeUnit.SECONDS); }
            catch (Exception e) { one = M.createObjectNode(); one.put("peer", p.name()); one.put("error", "did not answer in time"); }
            out.add(one);
        }
        return out;
    }

    static ObjectNode askOne(Peer p, String question, int k) {
        ObjectNode one = M.createObjectNode();
        one.put("peer", p.name()); one.put("url", p.url());
        try {
            ObjectNode body = M.createObjectNode();
            body.put("question", question); body.put("k", k); body.put("peers", "none");   // one hop
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(p.url() + "/v1/ask")).timeout(Duration.ofSeconds(TIMEOUT_S))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer " + p.token())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode j = M.readTree(r.body());
            if (r.statusCode() / 100 != 2) {
                one.put("error", j.path("error").path("code").asText("status " + r.statusCode()) + ": " + j.path("error").path("message").asText(""));
                return one;
            }
            one.put("library_id", j.path("library_id").asText("")); one.put("library_name", j.path("library_name").asText(""));
            one.put("holds_nothing", j.path("holds_nothing").asBoolean(true));
            one.set("entries", j.path("entries").isArray() ? j.path("entries") : M.createArrayNode());
            one.put("rendered", j.path("rendered").asText(""));
        } catch (Exception e) {
            one.put("error", e instanceof java.net.ConnectException ? "nothing is answering at " + p.url() : e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        return one;
    }

    /** The peers' answers as text, one block per library, for the command line and the chat. */
    public static String render(ArrayNode answers) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode a : answers) {
            sb.append("\n== ").append(a.path("peer").asText()).append(a.hasNonNull("library_name") ? " (" + a.path("library_name").asText() + ")" : "").append(" ==\n");
            if (a.hasNonNull("error")) { sb.append("  could not ask: ").append(a.path("error").asText()).append('\n'); continue; }
            if (a.path("holds_nothing").asBoolean()) { sb.append("  holds nothing on this.\n"); continue; }
            for (JsonNode e : a.path("entries")) {
                sb.append("  ").append(e.path("id").asText()).append(" [").append(e.path("state").asText("")).append("] ")
                  .append(Acquisitions.compress(e.path("claim").asText(e.path("title").asText("")), 140)).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }
}
