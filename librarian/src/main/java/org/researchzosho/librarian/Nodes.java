package org.researchzosho.librarian;

import org.researchzosho.tools.Outbound;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToLongFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The account service's side of its nodes (0.8.0, nodes and takeover): which node runs a library, asked over the node's own port
 * ({@link NodeServer}), or in this process for a node the service runs inside itself. A library runs where it holds its lease; asleep,
 * on the node whose root its folder is under; when that node is silent, on any live node that reaches its folder (a disk both see);
 * and when none does, brought from its copy to the live node with the fewest libraries awake, once the node it was on has been
 * silent for the setting's minutes. A node is silent when it has not written itself into the store for the length of a lease.
 */
public final class Nodes implements LibraryRunner, Scheduler.Libraries {
    private static final Logger LOG = LoggerFactory.getLogger(Nodes.class);
    /** After how many minutes of a node's silence its libraries are brought from their copies to another node; {@code off}: only by the operator's word. Unset: 15. */
    public static final String TAKEOVER_AFTER = "RESEARCHZOSHO_ACCOUNTS_TAKEOVER_AFTER";
    /** A test's own time, in milliseconds; null: the setting. */
    static volatile Long TAKEOVER_AFTER_MS = Long.getLong("researchzosho.accounts.takeover_after_ms");

    /** After how long a silent node's libraries are brought from their copies; -1: never by itself. */
    static long takeoverAfterMs() {
        Long t = TAKEOVER_AFTER_MS;
        if (t != null) return t;
        String v = Config.get(TAKEOVER_AFTER);
        if (v == null || v.isBlank()) return 15 * 60_000L;
        v = v.strip().toLowerCase();
        if (v.equals("off") || v.equals("never")) return -1;
        try { return Math.max(0, Long.parseLong(v)) * 60_000L; } catch (NumberFormatException e) { return 15 * 60_000L; }
    }

    private final AccountStore store;
    private final String issuer;
    /** The node this process runs itself; null when the service runs none. */
    private final Node local;
    private final Map<String, Long> epochs = new ConcurrentHashMap<>();
    private final Map<String, Node.State> remoteStates = new ConcurrentHashMap<>();
    private volatile long statesRead;
    private static final HttpClient HTTP = Outbound.client(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build());

    public Nodes(AccountStore store, String issuer, Node local) { this.store = store; this.issuer = issuer; this.local = local; }

    /** The nodes that have written themselves into the store within a lease, by the store's clock. */
    List<AccountStore.NodeRow> live() throws IOException { return liveIn(store); }

    static List<AccountStore.NodeRow> liveIn(AccountStore store) throws IOException {
        long now = store.clock();
        List<AccountStore.NodeRow> out = new ArrayList<>();
        for (AccountStore.NodeRow n : store.nodes()) if (now - n.seenAt() <= Node.LEASE_MS && !n.address().isEmpty()) out.add(n);
        return out;
    }

    /** A library a node made on its own disk: its id, its folder there, and the node. */
    record Made(String id, String folder, String node) { }

    /**
     * A new library made by a node on its own disk (stage 4, step 5), for a service whose own machine runs no libraries: the live node
     * with the fewest libraries on its disk first, the next when one cannot. The library runs there, on the node whose root holds it.
     */
    static Made make(AccountStore store, String issuer, String name, String title) throws IOException {
        if (issuer == null || issuer.isBlank()) throw new IOException("Set accounts_service, the address the service is reached at on its private network: a node is asked to make the library.");
        List<AccountStore.NodeRow> live = liveIn(store);
        Map<String, Integer> kept = new HashMap<>();
        for (AccountStore.Library l : store.libraries()) { if (!l.active()) continue; AccountStore.NodeRow h = homeOf(store, live, l); if (h != null) kept.merge(h.id(), 1, Integer::sum); }
        live.sort(Comparator.comparingInt((AccountStore.NodeRow n) -> kept.getOrDefault(n.id(), 0)).thenComparing(AccountStore.NodeRow::id));
        IOException last = null;
        for (AccountStore.NodeRow n : live) {
            if (n.root().isEmpty()) continue;
            try {
                AccountStore.Signing k = store.signing();
                String statement = Statement.sign(k.key(), k.kid(), Statement.Claims.now(issuer, n.id(), "service", "the account service", Statement.READ, Statement.SERVICE));
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(n.address() + "/node/make")).timeout(Duration.ofSeconds(60)).header(Statement.HEADER, statement).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(Statement.M.createObjectNode().put("name", name).put("title", title).toString())).build(), HttpResponse.BodyHandlers.ofString());
                JsonNode j = Statement.M.readTree(r.body().isEmpty() ? "{}" : r.body());
                if (r.statusCode() == 200 && !j.path("library_id").asText("").isEmpty()) return new Made(j.path("library_id").asText(), j.path("folder").asText(), n.id());
                last = new IOException(j.path("error").path("message").asText("The node " + n.id() + " answered " + r.statusCode() + "."));
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
            catch (IOException e) { last = e; }
        }
        throw last != null ? last : new IOException(Pages.t("No machine of this service answers now. Ask again in a minute."));
    }

    /**
     * The node whose disk holds a library's folder: the one written down for it (ROB 3), when it is among {@code all}; for a row from
     * before (none written down), the one whose root its folder is under. Null when none.
     */
    static AccountStore.NodeRow homeOf(AccountStore store, List<AccountStore.NodeRow> all, AccountStore.Library lib) throws IOException {
        String written = store.libraryHome(lib.id());
        if (!written.isEmpty()) { for (AccountStore.NodeRow n : all) if (n.id().equals(written)) return n; return null; }
        return home(all, lib);
    }

    /** The live node whose root is {@code root}; null when none is. Two with the same root (two machines' /srv/rz) cannot be told apart: refused. */
    static AccountStore.NodeRow rootOf(AccountStore store, Path root) throws IOException {
        List<AccountStore.NodeRow> found = new ArrayList<>();
        for (AccountStore.NodeRow n : liveIn(store)) if (!n.root().isEmpty() && Path.of(n.root()).toAbsolutePath().normalize().equals(root)) found.add(n);
        if (found.size() > 1) throw new IOException(Pages.t("More than one machine of the service has {0} for its root; name the machine by its id instead (researchzosho accounts lists them).", root));
        return found.isEmpty() ? null : found.get(0);
    }

    /** The live node named by its id ({@code node_…}, as {@code accounts} lists it), with a root; null when the word names none. */
    static AccountStore.NodeRow named(AccountStore store, String word) throws IOException {
        for (AccountStore.NodeRow n : liveIn(store)) if (n.id().equals(word) && !n.root().isEmpty()) return n;
        return null;
    }

    /**
     * A library's folder moved, removed, released or brought back from its copy by the node {@code n} that holds it (ROB 4), through the
     * node's own call, from the service's command line or pages, which may be on a machine that cannot see that disk. What the node said.
     */
    static String places(AccountStore store, AccountStore.NodeRow n, AccountStore.Library lib, String op, Path to, boolean now) throws IOException {
        if (n.address().isEmpty() || store.clock() - n.seenAt() > Node.LEASE_MS) throw new IOException(Pages.t("The machine that keeps the library {0} does not answer now, so nothing was changed. Ask again when it is back.", lib.title()));
        ObjectNode body = Statement.M.createObjectNode().put("library", lib.id()).put("op", op).put("now", now);
        if (to != null) body.put("to", to.toAbsolutePath().normalize().toString());
        AccountStore.Signing k = store.signing();
        String statement = Statement.sign(k.key(), k.kid(), Statement.Claims.now(AccountService.issuer(""), n.id(), "service", "the account service", Statement.READ, Statement.SERVICE));
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(n.address() + "/node/places")).timeout(Duration.ofMinutes(15)).header(Statement.HEADER, statement).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode j = Statement.M.readTree(r.body().isEmpty() ? "{}" : r.body());
            if (r.statusCode() != 200) throw new IOException(j.path("error").path("message").asText("The machine that keeps the library " + lib.title() + " answered " + r.statusCode() + "."));
            return j.path("said").asText("").strip();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
    }

    /**
     * A library's copy fetched from the node {@code nodeId} that holds it, into this machine's folder of copies, for a move onto this
     * machine when its own copies do not hold it. Only the copy's own files are written, each under the copy's folder.
     */
    static void fetchCopy(AccountStore store, String nodeId, AccountStore.Library lib) throws IOException {
        AccountStore.NodeRow n = liveIn(store).stream().filter(r -> r.id().equals(nodeId)).findFirst().orElse(null);
        Path into = LibraryPlaces.copy(lib.id());
        if (n == null || into == null) return;
        AccountStore.Signing k = store.signing();
        String statement = Statement.sign(k.key(), k.kid(), Statement.Claims.now(AccountService.issuer(""), n.id(), "service", "the account service", Statement.READ, Statement.SERVICE));
        HttpResponse<InputStream> r;
        try { r = HTTP.send(HttpRequest.newBuilder(URI.create(n.address() + "/node/copy")).timeout(Duration.ofMinutes(60)).header(Statement.HEADER, statement).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Statement.M.createObjectNode().put("library", lib.id()).toString())).build(), HttpResponse.BodyHandlers.ofInputStream()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        if (r.statusCode() != 200) { try (InputStream in = r.body()) { JsonNode j = Statement.M.readTree(in.readAllBytes()); throw new IOException(j.path("error").path("message").asText("The machine " + n.id() + " did not give the copy.")); } }
        Path half = into.resolveSibling(into.getFileName() + ".arriving");
        LibraryPlaces.remove(half);
        try (InputStream in = r.body(); ZipInputStream z = new ZipInputStream(in)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                Path f = half.resolve(e.getName()).normalize();
                if (!f.startsWith(half) || e.isDirectory()) continue;
                Files.createDirectories(f.getParent());
                Files.copy(z, f, StandardCopyOption.REPLACE_EXISTING);
                if (e.getLastModifiedTime() != null) Files.setLastModifiedTime(f, e.getLastModifiedTime());
            }
        } catch (IOException e) { LibraryPlaces.remove(half); throw e; }
        if (!Files.isRegularFile(half.resolve("whole.json"))) { LibraryPlaces.remove(half); throw new IOException("The copy that came from " + n.id() + " is not whole."); }
        LibraryPlaces.remove(into);
        Files.move(half, into);
    }

    /** A library brought from its copy onto another machine: the node it was on, when it answers, sets the folder it has there aside. */
    static String leftBehind(AccountStore store, String nodeId, AccountStore.Library lib) {
        try {
            AccountStore.NodeRow n = liveIn(store).stream().filter(r -> r.id().equals(nodeId)).findFirst().orElse(null);
            if (n != null) return places(store, n, lib, "set-aside", LibraryPlaces.folder(lib), false);   // the folder it had there: the store has its new one by now
            return Pages.t("The machine it was on ({0}) does not answer now, so the folder it has there is not set aside.", nodeId);
        } catch (IOException e) {
            LOG.info("The folder the library {} left on {} was not set aside: {}", lib.id(), nodeId, e.getMessage());
            return Pages.t("The folder it has on {0} was not set aside: {1}", nodeId, e.getMessage());
        }
    }

    /** The node whose root a library's folder is under: the one that has its disk. Null when none of them has. */
    static AccountStore.NodeRow home(List<AccountStore.NodeRow> all, AccountStore.Library lib) {
        Path folder = LibraryPlaces.folder(lib).toAbsolutePath().normalize();
        AccountStore.NodeRow best = null;
        for (AccountStore.NodeRow n : all) {
            if (n.root().isEmpty()) continue;
            Path r = Path.of(n.root()).toAbsolutePath().normalize();
            if (folder.startsWith(r) && (best == null || r.getNameCount() > Path.of(best.root()).getNameCount())) best = n;
        }
        return best;
    }

    // ---- the runner ----

    @Override public String ensure(AccountStore.Library lib) throws IOException {
        AccountStore.Placement p = store.placement(lib.id());
        long now = store.clock();
        List<AccountStore.NodeRow> all = store.nodes(), live = live();
        Map<String, AccountStore.NodeRow> liveById = new HashMap<>();
        for (AccountStore.NodeRow n : live) liveById.put(n.id(), n);
        Set<String> unanswered = new HashSet<>();
        if (p != null && p.leaseUntil() > now && !p.node().isEmpty()) {   // held: where it is
            AccountStore.NodeRow holder = liveById.get(p.node());
            if (holder != null) {
                try { return ask(holder, "/node/ensure", lib); }
                catch (Silent gone) { /* its machine does not answer: below */ }
            }
            // the machine it runs on does not answer (round B's run on a second machine): the library there stops itself before its lease
            // runs out (Held), so the request waits out the lease, a half-minute at most, and goes on as for a library asleep: another
            // machine that reaches its folder serves it, and when none does, the request is told why
            long wait = p.leaseUntil() - store.clock();
            if (wait > Node.LEASE_MS + 5_000) throw new IOException(Pages.t("The machine the library {0} runs on does not answer. Ask again in a minute.", lib.title()));
            try { Thread.sleep(Math.max(0, wait) + 250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
            unanswered.add(p.node());
            p = store.placement(lib.id());
            now = store.clock();
            if (p != null && p.leaseUntil() > now && !p.node().isEmpty()) {   // renewed meanwhile: its machine answers again
                String at = p.node();
                AccountStore.NodeRow back = liveIn(store).stream().filter(n -> n.id().equals(at)).findFirst().orElse(null);
                if (back == null) throw new IOException(Pages.t("The machine the library {0} runs on does not answer. Ask again in a minute.", lib.title()));
                return ask(back, "/node/ensure", lib);
            }
        }
        // asleep, or its node fell silent and its lease ran out: the node it is written down for first (ROB 3), then any node that
        // reaches that node's disk; each node itself refuses a folder at the same path on a disk of its own (Node.homeHere)
        AccountStore.NodeRow home = homeOf(store, all, lib);
        List<AccountStore.NodeRow> order = new ArrayList<>(live);
        Map<String, Integer> awake = awakeByNode(now);
        order.sort(Comparator.comparingInt((AccountStore.NodeRow n) -> home != null && n.id().equals(home.id()) ? 0 : 1).thenComparingInt(n -> awake.getOrDefault(n.id(), 0)).thenComparing(AccountStore.NodeRow::id));
        for (AccountStore.NodeRow n : order) {
            if (unanswered.contains(n.id())) continue;
            try { return ask(n, "/node/ensure", lib); }
            catch (Node.NotHere notHere) { /* its folder is not on this node's disks: the next */ }
            catch (Silent gone) { unanswered.add(n.id()); /* does not answer: the next */ }
        }
        if (order.isEmpty()) throw new IOException(Pages.t("No machine of this service answers now. Ask again in a minute."));
        // no live node reaches its folder: brought from its copy, when the node it was on has been silent long enough
        AccountStore.Placement last = p;
        AccountStore.NodeRow was = home != null ? home : all.stream().filter(n -> last != null && n.id().equals(last.node())).findFirst().orElse(null);
        long silent = was == null ? Long.MAX_VALUE : now - was.seenAt(), after = takeoverAfterMs();
        if (LibraryPlaces.lastCopy(lib.id()) == null) throw new IOException(Pages.t("The machine the library {0} is kept on does not answer, and it has no copy. Whoever runs the service has to look into it.", lib.title()));
        if (after < 0 || silent < after) throw new IOException(after < 0
                ? Pages.t("The machine the library {0} is kept on does not answer. Whoever runs the service brings it back from its copy.", lib.title())
                : Pages.t("The machine the library {0} is kept on does not answer. If it does not come back, the library is brought back from its copy within {1} minutes.", lib.title(), Math.max(1, (after - silent + 59_999) / 60_000)));
        AccountStore.NodeRow to = order.get(0);
        for (AccountStore.NodeRow n : order) if (awake.getOrDefault(n.id(), 0) < awake.getOrDefault(to.id(), 0)) to = n;
        LOG.warn("The node {} of the library {} has been silent for {} s and no live node reaches its folder: it is brought from its copy to {}.", was == null ? "(none)" : was.id(), lib.id(), silent == Long.MAX_VALUE ? -1 : silent / 1000, to.id());
        ask(to, "/node/bring", lib);
        return ask(to, "/node/ensure", store.library(lib.id()));
    }

    private Map<String, Integer> awakeByNode(long now) throws IOException {
        Map<String, Integer> m = new HashMap<>();
        for (AccountStore.Placement q : store.placements()) if (q.leaseUntil() > now && !q.node().isEmpty()) m.merge(q.node(), 1, Integer::sum);
        return m;
    }

    /** One call to a node: in this process for the node it runs, else over the node's own port under a statement made out to it. */
    private String ask(AccountStore.NodeRow n, String call, AccountStore.Library lib) throws IOException {
        if (local != null && n.id().equals(local.id())) {
            switch (call) {
                case "/node/ensure" -> { String a = local.ensure(lib); epochs.put(lib.id(), local.epoch(lib.id())); return a; }
                case "/node/bring" -> { return local.bring(lib); }
                default -> { local.sleep(lib.id(), "the account service asked"); return ""; }
            }
        }
        JsonNode r = post(n, call, "{\"library\":\"" + lib.id() + "\"}");
        if (call.equals("/node/ensure")) {
            epochs.put(lib.id(), r.path("epoch").asLong(0));
            routedTo.put(lib.id(), n);
            routedOut.computeIfAbsent(lib.id(), k -> new AtomicInteger()).incrementAndGet();   // going on there until done() says it ended
            return r.path("address").asText();
        }
        return r.path("said").asText("");
    }

    private JsonNode post(AccountStore.NodeRow n, String call, String body) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(n.address() + call)).timeout(Duration.ofSeconds(90)).header(Statement.HEADER, statementFor(n.id()));
        b = body == null ? b.GET() : b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r;
        try { r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        catch (IOException e) { throw new Silent(Pages.t("No machine of this service answers now. Ask again in a minute.")); }
        JsonNode j = Statement.M.readTree(r.body().isEmpty() ? "{}" : r.body());
        if (r.statusCode() == 409) throw new Node.NotHere(j.path("error").path("message").asText(""));
        if (r.statusCode() == 423) throw new Node.Packing(j.path("error").path("message").asText(Node.packingSaid()));
        if (r.statusCode() != 200) throw new IOException(j.path("error").path("message").asText("The node " + n.id() + " answered " + r.statusCode() + "."));
        return j;
    }

    /**
     * {@code accounts take} on a service that runs no library itself (round B's run on a second machine): the library's archive, or a
     * folder of this machine packed as one (the folder left as it is), sent to the node with the fewest libraries, which takes it in
     * under its own root. What the node said.
     */
    static String takeOnNode(AccountStore store, Path given, AccountStore.Account keeper) throws IOException {
        List<AccountStore.NodeRow> live = liveIn(store).stream().filter(n -> !n.root().isEmpty()).toList();
        if (live.isEmpty()) throw new IOException(Pages.t("No machine of this service answers now. Ask again in a minute."));
        Map<String, Integer> kept = new HashMap<>();
        for (AccountStore.Library l : store.libraries()) if (l.active()) kept.merge(store.libraryHome(l.id()), 1, Integer::sum);
        AccountStore.NodeRow n = live.stream().min(Comparator.comparingInt((AccountStore.NodeRow r) -> kept.getOrDefault(r.id(), 0)).thenComparing(AccountStore.NodeRow::id)).orElseThrow();
        Path archive = given, packed = null;
        if (Files.isDirectory(given)) {   // a library of this machine's own: packed as its archive, the folder left as it was
            Path real = given.toRealPath();
            LibraryStore source = new LibraryStore(real);
            if (!Files.isRegularFile(source.libraryFile())) throw new IOException("There is no library at " + real + ": a library's folder has catalog/library.md.");
            LibraryStore.Identity id = source.identity();
            packed = Files.createTempFile("rz-take-", LibraryArchive.SUFFIX);
            try (OutputStream o = Files.newOutputStream(packed)) { LibraryArchive.write(o, real, null, id.id(), id.name(), id.name(), System.currentTimeMillis(), ""); }
            archive = packed;
        } else if (!Files.isRegularFile(given)) throw new IOException("There is no file or folder at " + given + ".");
        AccountStore.Signing k = store.signing();
        String statement = Statement.sign(k.key(), k.kid(), Statement.Claims.now(AccountService.issuer(""), n.id(), "service", "the account service", Statement.READ, Statement.SERVICE));
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(n.address() + "/node/take")).timeout(Duration.ofMinutes(60)).header(Statement.HEADER, statement)
                    .header("Content-Type", "application/zip").header(FOR, keeper == null ? "" : keeper.id()).POST(HttpRequest.BodyPublishers.ofFile(archive)).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode j = Statement.M.readTree(r.body().isEmpty() ? "{}" : r.body());
            if (r.statusCode() != 200) throw new IOException(j.path("error").path("message").asText("The machine " + n.id() + " answered " + r.statusCode() + "."));
            return j.path("said").asText("").strip();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        finally { if (packed != null) Files.deleteIfExists(packed); }
    }

    /** The header that names the account a library taken in on a node is kept by (its id; empty: the one the library was kept by before). */
    static final String FOR = "X-RZ-For";

    /** A node that could not be reached at all (no connection, no answer): unlike a refusal, it says nothing of the library. */
    static final class Silent extends IOException { Silent(String said) { super(said); } }

    private String statementFor(String nodeId) throws IOException {
        AccountStore.Signing k = store.signing();
        return Statement.sign(k.key(), k.kid(), Statement.Claims.now(issuer, nodeId, "service", "the account service", Statement.READ, Statement.SERVICE));
    }

    /**
     * The library's archive to take home (stage 4), streamed from the node that holds it, or asleep from the node whose disk it is on;
     * in this process for the node the service runs itself.
     */
    @Override public void archive(AccountStore.Library lib, OutputStream out, String from) throws IOException {
        AccountStore.Placement p = store.placement(lib.id());
        long now = store.clock();
        List<AccountStore.NodeRow> live = live();
        AccountStore.NodeRow n = null;
        if (p != null && p.leaseUntil() > now && !p.node().isEmpty()) for (AccountStore.NodeRow r : live) if (r.id().equals(p.node())) n = r;
        if (n == null) n = homeOf(store, live, lib);
        if (n == null) throw new IOException(Pages.t("No machine of this service answers now. Ask again in a minute."));
        if (local != null && n.id().equals(local.id())) { local.archive(lib, out, from); return; }
        HttpRequest q = HttpRequest.newBuilder(URI.create(n.address() + "/node/archive")).header(Statement.HEADER, statementFor(n.id())).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Statement.M.createObjectNode().put("library", lib.id()).put("from", from == null ? "" : from).toString())).build();
        HttpResponse<InputStream> r;
        try { r = HTTP.send(q, HttpResponse.BodyHandlers.ofInputStream()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        catch (IOException e) { throw new Silent(Pages.t("No machine of this service answers now. Ask again in a minute.")); }
        try (InputStream in = r.body()) {
            if (r.statusCode() != 200) {
                JsonNode j = Statement.M.readTree(in.readNBytes(1 << 16));
                String said = j.path("error").path("message").asText("The node " + n.id() + " answered " + r.statusCode() + ".");
                if (r.statusCode() == 423) throw new Node.Packing(said);
                throw new IOException(said);
            }
            in.transferTo(out);
        }
    }

    @Override public void lost(String libraryId) { if (local != null) local.lost(libraryId); }
    @Override public void stopAll() { if (local != null) local.stopAll(); }
    @Override public long epoch(String libraryId) { if (local != null && local.epoch(libraryId) > 0) return local.epoch(libraryId); return epochs.getOrDefault(libraryId, 0L); }
    /** The requests routed to a library on another machine's node, and the node they went to: each is told ended there (ROB 14). */
    private final Map<String, AtomicInteger> routedOut = new ConcurrentHashMap<>();
    private final Map<String, AccountStore.NodeRow> routedTo = new ConcurrentHashMap<>();

    @Override public void done(String libraryId) {
        AtomicInteger out = routedOut.get(libraryId);
        AccountStore.NodeRow n = routedTo.get(libraryId);
        if (out != null && n != null && out.getAndUpdate(v -> Math.max(0, v - 1)) > 0) {   // routed to another machine: its node is told, off this thread
            String body = Statement.M.createObjectNode().put("library", libraryId).toString();
            Thread.ofVirtual().start(() -> { try { post(n, "/node/done", body); } catch (IOException e) { LOG.debug("The node {} was not told a request of {} ended: {}", n.id(), libraryId, e.getMessage()); } });
            return;
        }
        if (local != null) local.done(libraryId);
    }

    @Override public boolean awake(String libraryId) {
        try { AccountStore.Placement p = store.placement(libraryId); return p != null && p.leaseUntil() > store.clock() && !p.address().isEmpty(); }
        catch (IOException e) { return false; }
    }

    @Override public String ifAwake(AccountStore.Library lib) throws IOException {
        if (local != null && local.awake(lib.id())) return local.ifAwake(lib);
        AccountStore.Placement p = store.placement(lib.id());
        if (p == null || p.leaseUntil() <= store.clock() || p.address().isEmpty()) return null;
        epochs.put(lib.id(), p.epoch());
        return p.address();
    }

    // ---- for the scheduler ----

    @Override public void night(AccountStore.Library lib) throws IOException {
        String address = ensure(lib);
        try {
            AccountStore.Signing k = store.signing();
            String statement = Statement.sign(k.key(), k.kid(), Statement.Claims.now(issuer, lib.id(), "service", "the account service", Statement.READ, Statement.SERVICE).epoch(epoch(lib.id())));
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(address + Held.NIGHT)).timeout(Duration.ofSeconds(10)).header(Statement.HEADER, statement).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new IOException("The library " + lib.title() + " did not take its night: " + r.body());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        finally { done(lib.id()); }
    }

    @Override public void wake(AccountStore.Library lib) throws IOException { ensure(lib); done(lib.id()); }

    @Override public Node.State state(String libraryId) {
        if (local != null && local.state(libraryId) != null) return local.state(libraryId);
        readStates();
        return remoteStates.get(libraryId);
    }

    /** What the remote nodes' libraries said they are doing, read at most once a second. */
    private void readStates() {
        long t = System.currentTimeMillis();
        if (t - statesRead < 1_000) return;
        statesRead = t;
        try {
            Map<String, Node.State> now = new HashMap<>();
            for (AccountStore.NodeRow n : live()) {
                if (local != null && n.id().equals(local.id())) continue;
                try {
                    for (JsonNode l : post(n, "/node/held", null).path("libraries"))
                        if (l.has("night")) now.put(l.path("library").asText(), new Node.State("", 0, 0, l.path("jobs_running").asInt(0), 0, 0, l.path("night").asBoolean(false), false, 0));
                } catch (IOException e) { /* a silent node says nothing */ }
            }
            remoteStates.clear(); remoteStates.putAll(now);
        } catch (IOException e) { /* the next look */ }
    }

    @Override public void nextNight(ToLongFunction<String> at) { if (local != null) local.nextNight(at); }

    /**
     * The libraries that were awake on a node that fell silent, started elsewhere as soon as their leases have run out, so their jobs go
     * on from where they were kept without waiting for anyone to ask. What was started, a line each.
     */
    public List<String> takeOver() throws IOException {
        List<String> did = new ArrayList<>();
        long now = store.clock();
        Map<String, AccountStore.NodeRow> liveById = new HashMap<>();
        for (AccountStore.NodeRow n : live()) liveById.put(n.id(), n);
        if (liveById.isEmpty()) return did;
        for (AccountStore.Placement p : store.placements()) {
            if (p.node().isEmpty() || liveById.containsKey(p.node()) || p.leaseUntil() == 0 || p.leaseUntil() > now) continue;   // asleep, held, or on a live node
            AccountStore.Library lib = store.library(p.id());
            if (lib == null || !lib.active()) continue;
            try { ensure(lib); done(lib.id()); did.add(p.id()); LOG.warn("The library {} was awake on the node {}, which is silent: it is started on another.", p.id(), p.node()); }
            catch (IOException e) { LOG.info("The library {} of the silent node {} is not started yet: {}", p.id(), p.node(), e.getMessage()); }
        }
        return did;
    }
}
