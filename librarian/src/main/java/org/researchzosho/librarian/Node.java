package org.researchzosho.librarian;

import org.researchzosho.tools.Outbound;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.time.ZonedDateTime;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The node part of the account service (0.8.0, placement): it runs the libraries of one machine. It takes a library's lease in the
 * registry when the library is asked for and starts it as that start (the row's epoch); it holds the lease on every few seconds,
 * tells each library "still yours" and hears what the library is doing; it stops a library that has nothing to do and gives the
 * lease back, so the next request, or the scheduler, starts it again; and it stops the library at once when the lease turns out to
 * be somebody else's. On one machine the account service runs this inside itself.
 *
 * <p><b>Idle</b> is all of: no request open (a stream, an upload and a long call are open requests), no job running or waiting to
 * run (the night is a job, and so is a run waiting for its model), no chat turn in progress, nothing asked for the time the setting
 * says (15 minutes), and nothing due within ten. A job held back by the person's pause or hours does not keep a library awake: the
 * hour goes into the registry, and the scheduler starts the library then.
 *
 * <p><b>An update.</b> A library an older version of the program started is stopped at its next quiet moment (no request open, no
 * job, no chat turn) and started again by this one, where its repairs run. A person with a page open sees one request wait while
 * the library starts.
 */
public final class Node implements LibraryRunner, Scheduler.Libraries {
    private static final Logger LOG = LoggerFactory.getLogger(Node.class);
    /** How long a lease stands, and how often it is held on; a test shortens both. */
    static volatile long LEASE_MS = Long.getLong("researchzosho.accounts.lease_ms", 30_000), RENEW_MS = Long.getLong("researchzosho.accounts.renew_ms", 10_000);
    /** Whether {@code serve} runs a node inside itself: {@code off} when its libraries run on nodes of their own (researchzosho node). Unset: on. */
    public static final String OWN = "RESEARCHZOSHO_ACCOUNTS_NODE";
    static boolean ownOn() { String v = Config.get(OWN); return v == null || v.isBlank() || !v.strip().equalsIgnoreCase("off"); }
    /** The port a node answers the service's calls on. Unset: any free one, which it writes into the registry. */
    public static final String PORT = "RESEARCHZOSHO_ACCOUNTS_NODE_PORT";
    static int port() { return Math.max(0, Config.getInt(PORT, 0)); }

    /** After how many minutes with nothing to do a library is stopped; {@code off}: never. Unset: 15. */
    public static final String SLEEP_AFTER = "RESEARCHZOSHO_ACCOUNTS_SLEEP_AFTER";
    /** A test's own time, in milliseconds; null: the setting. */
    static volatile Long SLEEP_AFTER_MS = Long.getLong("researchzosho.accounts.sleep_after_ms");
    /** Work due sooner than this keeps a library awake. */
    static volatile long DUE_SOON_MS = 10 * 60_000L;

    /** After how long with nothing to do a library is put to sleep, in milliseconds; 0: never. */
    static long sleepAfterMs() {
        Long t = SLEEP_AFTER_MS;
        if (t != null) return t;
        String v = Config.get(SLEEP_AFTER);
        if (v == null || v.isBlank()) return 15 * 60_000L;
        v = v.strip().toLowerCase();
        if (v.equals("off") || v.equals("never") || v.equals("0")) return 0;
        try { return Math.max(1, Long.parseLong(v)) * 60_000L; } catch (NumberFormatException e) { return 15 * 60_000L; }
    }

    /** What a library said it is doing, the last time it was told "still yours". */
    public record State(String version, int open, long idleMs, int jobsRunning, int jobsWaiting, int jobsHeld, boolean night, boolean chatLive, long dueAt, long jobsEnded) {
        /** The same, with no count of the jobs it has ended. */
        public State(String version, int open, long idleMs, int jobsRunning, int jobsWaiting, int jobsHeld, boolean night, boolean chatLive, long dueAt) { this(version, open, idleMs, jobsRunning, jobsWaiting, jobsHeld, night, chatLive, dueAt, 0); }
        /** Nothing in progress: no request, no job running or waiting to run, no chat turn. */
        boolean quiet() { return open == 0 && jobsRunning == 0 && jobsWaiting == 0 && !chatLive; }
    }

    /** One library this node holds: which start it is, where it answers (null while it starts), and the requests sent to it that have not ended. */
    private static final class Awake {
        final long epoch;
        volatile String address;
        volatile long renewed = System.nanoTime();
        final AtomicInteger inFlight = new AtomicInteger();
        volatile boolean closing;
        Awake(long epoch) { this.epoch = epoch; }
    }

    private final AccountStore store;
    private final String service, id, root;
    private final LibraryRunner.Local local;   // a process or a container for each library (accounts_runner)
    private final Map<String, Awake> awake = new ConcurrentHashMap<>();
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>(), copyLocks = new ConcurrentHashMap<>();
    private final LibraryPlaces places;
    /** When what this node set aside was last swept: once an hour (ROB 4). */
    private volatile long swept;
    /** How the lease is held on: the store's renewal; a test stands in for a store that does not answer. */
    interface Renewer { boolean renew(String libraryId, String node, long epoch, long ms) throws IOException; }
    volatile Renewer renewer;
    /** Libraries whose lease this node holds on while they do not run: their copy is brought up to date as they go to sleep. */
    private final Map<String, Long> holding = new ConcurrentHashMap<>();
    /** Per library: how many jobs it said it had ended, the last time it was heard; and when its copy was last brought up to date. */
    private final Map<String, Long> jobsEnded = new ConcurrentHashMap<>(), copiedAt = new ConcurrentHashMap<>();
    /** What takes time (a library stopped, its copy, its start after an update) is done here, off the round that holds every lease on. */
    private final ExecutorService work = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "accounts-node-work"); t.setDaemon(true); return t; });
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    /** The libraries being packed to take home now (stage 4): their copy is not brought up to date meanwhile, and without copies they are held asleep. */
    private final Set<String> packing = ConcurrentHashMap.newKeySet();
    private volatile ToLongFunction<String> nextNight;
    private volatile Thread ticker;
    private volatile boolean stopping;
    private static final HttpClient HTTP = Outbound.client(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build());

    /** {@code service}: the account service's address, in whose name statements are made; {@code id}: this node's own name in the registry ({@link #idAt}). */
    public Node(AccountStore store, String service, LibraryRunner.Local local, String id, Path root) {
        this.store = store; this.service = service; this.local = local; this.id = id; this.root = root == null ? "" : root.toString();
        this.places = new LibraryPlaces(store, id);
        this.renewer = store::renew;
        this.nextNight = libraryId -> Scheduler.nextNight(store, libraryId, ZonedDateTime.now());
    }

    /** Where the service reaches this node's own calls ({@link NodeServer}); "" until it listens. */
    private volatile String address = "";
    void address(String url) { address = url == null ? "" : url; }
    public String address() { return address; }

    /** The folder this node's libraries are made in, and brought to from a copy. */
    public Path root() { return Path.of(root); }

    /** This node cannot reach the library's folder (it is on a disk of another machine, or the disk is gone). */
    public static final class NotHere extends IOException { NotHere(String why) { super(why); } }

    /** A library held asleep while it is packed to take home: asked for meanwhile, it says so (stage 4). */
    public static final class Packing extends IOException { Packing(String why) { super(why); } }

    /** What a request to a library being packed is told. */
    static String packingSaid() { return Pages.t("This library is being packed for download; it opens again in a minute."); }

    /** Whether the library's keeper is suspended or being deleted (stage 4): it opens for nobody, and sleeps as soon as it is quiet. */
    private boolean frozen(String libraryId) {
        try { AccountStore.Library l = store.library(libraryId); return l != null && !AccountActs.keeperOpen(store, l); } catch (IOException e) { return false; }
    }

    /** How often the node measures each library's folder (0.8.0, stage 3): once a day; a test shortens it. */
    static volatile long STORAGE_MS = Long.getLong("researchzosho.accounts.storage_ms", 24 * 3_600_000L);
    /** When each library was last measured here, so the store is asked only once a library. */
    private final Map<String, Long> measured = new ConcurrentHashMap<>();

    /**
     * Each library whose folder is on this machine, measured once a day and charged to its keeper: a storage row of its bytes, which
     * makes megabyte-days in the keeper's month; the last measure is what the storage cap is checked against. Asleep or awake.
     */
    void measureWhenDue() {
        List<AccountStore.Library> all;
        Map<String, String> nodeOf = new HashMap<>();
        try { all = store.libraries(); for (AccountStore.Placement p : store.placements()) nodeOf.put(p.id(), p.node()); } catch (IOException e) { return; }
        long now = System.currentTimeMillis();
        for (AccountStore.Library lib : all) {
            String at = nodeOf.getOrDefault(lib.id(), "");
            if (!lib.active() && asideHere(lib).isEmpty()) continue;   // a library let go is counted while its folder waits here to go (round F, money 10)
            if (lib.active() && !at.isEmpty() && !at.equals(id)) continue;   // another machine's; one never started is where it was made
            Long last = measured.get(lib.id());
            if (last == null) {
                try { long[] s = store.lastStorage(lib.id()); last = s == null ? 0L : s[0]; } catch (IOException e) { continue; }
                measured.put(lib.id(), last);
            }
            if (now - last < STORAGE_MS) continue;
            submit("storage", lib.id(), () -> measure(lib.id()));
        }
    }

    private void measure(String libraryId) {
        try {
            AccountStore.Library lib = store.library(libraryId);
            if (lib == null) return;
            long bytes = 0;
            if (lib.active()) {
                Path folder = LibraryPlaces.folder(lib);
                if (!Files.isDirectory(folder)) { measured.put(libraryId, System.currentTimeMillis()); return; }   // not on this machine: looked for again tomorrow
                bytes = folderBytes(folder);
            }
            for (Path aside : asideHere(lib)) bytes += folderBytes(aside);   // what was set aside for it here, until it goes
            if (bytes == 0 && !lib.active()) { measured.put(libraryId, System.currentTimeMillis()); return; }
            store.addUsage(List.of(new AccountStore.Usage(System.currentTimeMillis(), lib.owner(), libraryId, "", "storage", "operator", "", 0, 0, 0, (bytes + 999_999) / 1_000_000, bytes, 0, false)));
            measured.put(libraryId, System.currentTimeMillis());
        } catch (IOException e) { LOG.warn("The folder of the library {} could not be measured: {}", libraryId, e.getMessage()); }
    }

    /** What was set aside for a library on this machine's disk and is still there: counted on its keeper's storage until it goes. */
    List<Path> asideHere(AccountStore.Library lib) {
        List<Path> out = new ArrayList<>();
        try { for (AccountStore.Aside a : store.asides(lib.id())) if (store.asideNode(a.path()).equals(id) && Files.isDirectory(Path.of(a.path()))) out.add(Path.of(a.path())); }
        catch (IOException | RuntimeException unread) { /* counted at the next round */ }
        return out;
    }

    /** The bytes of every file under a folder; one that cannot be read counts nothing. */
    static long folderBytes(Path folder) throws IOException {
        long[] n = {0};
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) { if (a.isRegularFile()) n[0] += a.size(); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
        });
        return n[0];
    }

    /** One piece of work of a kind for a library, on the node's workers; nothing when the same is under way. */
    private boolean submit(String kind, String libraryId, Runnable r) {
        String key = kind + ":" + libraryId;
        if (stopping || !busy.add(key)) return false;
        try { work.execute(() -> { try { r.run(); } catch (RuntimeException e) { LOG.warn("The node's work on {} failed: {}", libraryId, e.toString()); } finally { busy.remove(key); } }); return true; }
        catch (RuntimeException e) { busy.remove(key); return false; }
    }

    /**
     * The library's copy brought up to date, when a folder for copies is set: {@code stopped}, the library does not run and its index
     * comes too. One pass at a time for a library.
     */
    void copy(String libraryId, long epoch, boolean stopped) {
        if (LibraryPlaces.copies() == null || packing.contains(libraryId)) return;   // an archive is read from the copy now: it is brought up to date after
        synchronized (copyLocks.computeIfAbsent(libraryId, k -> new Object())) {
            try {
                AccountStore.Library lib = store.library(libraryId);
                if (lib == null) return;
                LibraryPlaces.Copy c = places.bringUp(lib, epoch, stopped);
                if (c == null) return;
                copiedAt.put(libraryId, c.ended());
                LOG.info("The copy of the library {} is up to date{}: {} files written, {} removed, {} files in all, in {} ms.", libraryId, stopped ? " with its index" : "", c.changed(), c.removed(), c.files(), c.ended() - c.began());
            } catch (IOException | RuntimeException e) { LOG.warn("The copy of the library {} was not brought up to date: {}", libraryId, e.toString()); }
        }
    }

    /** This machine's name as a node: written once into {@code node.id} under the folder its libraries are kept in, so a service started again is the same node. */
    public static String idAt(Path root) throws IOException {
        Path f = root.resolve("node.id");
        if (Files.isRegularFile(f)) { String v = Files.readString(f, StandardCharsets.UTF_8).strip(); if (v.matches("[A-Za-z0-9_-]{4,64}")) return v; }
        Files.createDirectories(root);
        String made = AccountStore.id("node_");
        Files.writeString(f, made + "\n", StandardCharsets.UTF_8);
        return made;
    }

    public String id() { return id; }

    /**
     * Whether this node may serve the library from the folder its row names (ROB 3): the library is written down for this node, or for
     * none (a row from before), or for the node whose disk this node reaches there (a disk both machines see: the root above the folder
     * holds that node's own {@code node.id}). A folder at the same path on another disk (two machines whose roots are the same path)
     * holds this machine's own name there, or none, and is not the library's.
     */
    private boolean homeHere(AccountStore.Library lib) throws IOException {
        String h = store.libraryHome(lib.id());
        if (h.isEmpty() || h.equals(id)) return true;
        Path mark = LibraryPlaces.rootOf(lib).resolve("node.id");
        try { return Files.isRegularFile(mark) && Files.readString(mark, StandardCharsets.UTF_8).strip().equals(h); } catch (IOException e) { return false; }
    }

    /** The node whose root is {@code root} on this machine, when it has written itself into the store; "" otherwise. Nothing is made. */
    static String at(AccountStore store, Path root) {
        try {
            Path f = root.resolve("node.id");
            if (!Files.isRegularFile(f)) return "";
            String v = Files.readString(f, StandardCharsets.UTF_8).strip();
            for (AccountStore.NodeRow n : store.nodes()) if (n.id().equals(v)) return v;
        } catch (IOException | RuntimeException e) { /* none known */ }
        return "";
    }

    /** The scheduler says when a library's night is next due, so a library is not put to sleep minutes before it. */
    @Override public void nextNight(ToLongFunction<String> at) { nextNight = at; }

    @Override public void wake(AccountStore.Library lib) throws IOException { ensure(lib); done(lib.id()); }

    /** The round ended and nothing stopped: what a service that was killed leaves behind, its libraries still running. A test's. */
    void abandon() { stopping = true; Thread t = ticker; if (t != null) t.interrupt(); awake.clear(); states.clear(); }

    /** Takes back the libraries this node left running when it last ended, and begins to hold the leases on. */
    public void start() {
        try { store.nodeSeen(id, address, root, Version.string()); } catch (IOException e) { LOG.warn("The node could not write itself into the account store: {}", e.getMessage()); }
        try {
            for (AccountStore.Placement p : store.placements()) {
                if (!p.node().equals(id) || p.address().isEmpty()) continue;
                AccountStore.Library lib = store.library(p.id());
                if (lib != null) synchronized (lock(p.id())) { if (!awake.containsKey(p.id())) takeBack(lib, p); }   // unless a request that came first has taken it back already
            }
        } catch (IOException e) { LOG.warn("The libraries left running could not be looked for: {}", e.getMessage()); }
        Thread t = new Thread(() -> {
            while (!stopping) {
                try { Thread.sleep(RENEW_MS); } catch (InterruptedException e) { return; }
                try { tick(); } catch (RuntimeException e) { LOG.warn("The node's round failed: {}", e.toString()); }
            }
        }, "accounts-node");
        t.setDaemon(true);
        ticker = t;
        t.start();
    }

    private Object lock(String libraryId) { return locks.computeIfAbsent(libraryId, k -> new Object()); }

    // ---- the runner's three calls ----

    @Override public String ensure(AccountStore.Library lib) throws IOException {
        if (packing.contains(lib.id()) && LibraryPlaces.copies() == null) throw new Packing(packingSaid());   // held asleep while it is packed
        Awake a = awake.get(lib.id());
        if (a != null && a.address != null) {   // the usual road: it is up; the request is counted before the look, so a sleep that began meanwhile is seen
            a.inFlight.incrementAndGet();
            if (!a.closing && awake.get(lib.id()) == a && local.alive(lib.id(), a.epoch)) return a.address;
            a.inFlight.decrementAndGet();
        }
        synchronized (lock(lib.id())) {
            a = awake.get(lib.id());
            if (a != null && a.address != null && local.alive(lib.id(), a.epoch)) { a.inFlight.incrementAndGet(); return a.address; }
            if (!homeHere(lib) || !Files.isDirectory(LibraryPlaces.library(lib))) throw new NotHere("The node " + id + " cannot reach the folder of the library " + lib.title() + ".");
            if (a != null) forget(lib.id(), a, true);   // it ended by itself
            AccountStore.Placement p = store.placement(lib.id());
            a = p == null ? null : takeBack(lib, p);
            if (a == null) a = begin(lib);
            a.inFlight.incrementAndGet();
            return a.address;
        }
    }

    @Override public String ifAwake(AccountStore.Library lib) {
        Awake a = awake.get(lib.id());
        if (a == null || a.address == null) return null;
        a.inFlight.incrementAndGet();
        if (!a.closing && awake.get(lib.id()) == a && local.alive(lib.id(), a.epoch)) return a.address;
        a.inFlight.decrementAndGet();
        return null;
    }

    @Override public void done(String libraryId) { Awake a = awake.get(libraryId); if (a != null && a.inFlight.get() > 0) a.inFlight.decrementAndGet(); }

    /** How long a request the service routed here counts as going when the service never said it ended (its end lost on the way). */
    static volatile long ROUTED_MS = 60 * 60_000L;
    /** The requests the service routed here and has not said ended, by library: when each began (ROB 14). */
    private final Map<String, Deque<Long>> routed = new ConcurrentHashMap<>();

    /** A request of the service's, routed here after {@link #ensure}: it counts as going on until the service says it ended. */
    void routed(String libraryId) { routed.computeIfAbsent(libraryId, k -> new ConcurrentLinkedDeque<>()).add(System.currentTimeMillis()); }

    /** The service says a request it routed here ended. */
    void routedEnded(String libraryId) { Deque<Long> d = routed.get(libraryId); if (d != null && d.poll() != null) done(libraryId); }

    /** The routed requests whose end never came, let go after {@link #ROUTED_MS}. */
    private void routedLost() {
        long before = System.currentTimeMillis() - ROUTED_MS;
        for (Map.Entry<String, Deque<Long>> e : routed.entrySet()) {
            Long first;
            while ((first = e.getValue().peek()) != null && first < before) { if (e.getValue().remove(first)) { done(e.getKey()); LOG.info("A request the service routed to the library {} more than {} minutes ago never said it ended; it is let go.", e.getKey(), ROUTED_MS / 60_000); } }
        }
    }

    @Override public void lost(String libraryId) {
        Awake a = awake.get(libraryId);
        if (a == null || a.address == null || local.alive(libraryId, a.epoch)) return;
        synchronized (lock(libraryId)) { if (awake.get(libraryId) == a) forget(libraryId, a, true); }
    }

    @Override public long epoch(String libraryId) { Awake a = awake.get(libraryId); return a == null ? 0 : a.epoch; }

    @Override public boolean awake(String libraryId) { Awake a = awake.get(libraryId); return a != null && a.address != null; }

    /**
     * The library brought from its copy into this node's own root (its disk elsewhere is lost), the registry pointed at the new folder.
     * It must be out of everyone's hands: its lease ran out. The sentence that says which copy.
     */
    public String bring(AccountStore.Library lib) throws IOException {
        synchronized (lock(lib.id())) {
            Path to = LibraryPlaces.newFolder(root(), lib.name());
            if (Files.exists(to)) to = LibraryPlaces.newFolder(root(), lib.name() + "-" + id);
            String said = places.bringTo(lib, to);
            store.libraryHome(lib.id(), id);   // its folder is this node's from now on (ROB 3): the node it was on, back, serves it no more
            AccountActs.audit(store, AccountActs.By.service(), "library_restored", lib.owner(), lib.id(), AccountActs.detail().put("node", id));   // a machine's silence: brought over from its copy
            return said;
        }
    }

    /** A library this node made on its disk: its id and its folder. */
    record Made(String id, String folder) { }

    /**
     * The library's copy, brought up to date with its index and written to {@code out} as a zip of the copy's folder, for a node of
     * another machine that brings the library onto its own disk (a move between machines). The library must be out of service.
     */
    void copyOut(AccountStore.Library lib, OutputStream out) throws IOException {
        if (!homeHere(lib) || !Files.isDirectory(LibraryPlaces.library(lib))) throw new NotHere("The node " + id + " cannot reach the folder of the library " + lib.title() + ".");
        if (LibraryPlaces.copies() == null) throw new IOException("The machine " + id + " keeps no copies (accounts_copies is not set there).");
        synchronized (lock(lib.id())) {
            AccountStore.Library now = store.library(lib.id());
            if (now == null || now.active()) throw new IOException("The library " + lib.title() + " is in service; it is taken out of service first.");
            Awake a = awake.get(lib.id());
            if (a != null) { local.stop(lib.id()); forget(lib.id(), a, false); }   // out of service, not yet stopped by this node's round
            places.bringUp(lib, epoch(lib.id()), true);   // with its index: nothing runs it
        }
        Path copy = LibraryPlaces.copy(lib.id());
        try (Closeable held = LibraryPlaces.holdCopy(lib.id()); ZipOutputStream z = new ZipOutputStream(out); Stream<Path> all = Files.walk(copy)) {
            for (Path f : all.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String rel = LibraryPlaces.rel(copy, f);
                if (rel.endsWith(LibraryPlaces.HALF)) continue;
                ZipEntry e = new ZipEntry(rel);
                e.setLastModifiedTime(Files.getLastModifiedTime(f));
                z.putNextEntry(e);
                Files.copy(f, z);
                z.closeEntry();
            }
        }
    }

    /**
     * The service's word on a library's folder on this node's disk (ROB 4): moved, removed, released, brought back from its copy (here,
     * or under a root of this machine's), or, brought onto another machine, its folder here set aside. What was done, in the words the
     * command says it in.
     */
    String places(AccountStore.Library lib, String op, Path to, boolean now) throws IOException {
        if (op.equals("set-aside")) {   // the folder it had here, named (the service has its new folder written down by now): under this node's root, holding this library
            Path left = to == null ? LibraryPlaces.folder(lib) : to.toAbsolutePath().normalize();
            if (!Files.isDirectory(left, LinkOption.NOFOLLOW_LINKS) || to != null && !left.startsWith(root().toAbsolutePath().normalize())) return "";
            if (!Files.isRegularFile(LibraryPlaces.library(left).resolve("catalog").resolve("library.md")) || !lib.id().equals(new LibraryStore(LibraryPlaces.library(left)).identity().id())) return "";
            synchronized (lock(lib.id())) { Awake a = awake.get(lib.id()); if (a != null) { local.stop(lib.id()); forget(lib.id(), a, false); } }
            String said = Pages.t("Its folder on {0} is set aside at {1}.", id, places.aside(lib, left, "moved-to-another-machine"));
            places.copyAside(lib, "moved-to-another-machine");   // its copy here goes with it, at the end of the same grace (round F: it stayed for ever)
            return said;
        }
        if (op.equals("take")) {   // taken back from where it was set aside on this disk, within its grace
            LibraryPlaces.Taken t = places.take(to, null, root());
            store.libraryHome(t.library().id(), id);
            return Pages.t("The library \"{0}\" is taken back, kept by its keeper as before, at {1} on {2}.", t.library().title(), LibraryPlaces.folder(t.library()), id);
        }
        if (!List.of("move", "delete", "release", "restore").contains(op)) throw new IOException("A node moves, removes, releases or brings back a library; " + op + " is none of these.");
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        AccountsCli.placesHere(store, lib, op, to, now, new PrintStream(said, true, StandardCharsets.UTF_8), id);
        return said.toString(StandardCharsets.UTF_8);
    }

    /**
     * A library's archive sent by the service ({@code accounts take} on a service that runs no library itself), kept under this node's
     * root while it is taken in there, for the account {@code keeper} (its id; empty: the account that kept it before). What it says.
     */
    String take(InputStream in, String keeper) throws IOException {
        Path taking = root().resolve("taking");
        Files.createDirectories(taking);
        Path archive = taking.resolve(AccountStore.id("t_") + LibraryArchive.SUFFIX);
        try {
            Files.copy(in, archive);
            AccountStore.Account a = keeper == null || keeper.isBlank() ? null : store.account(keeper.strip());
            AccountsCli.TakenIn t = AccountsCli.takeIn(store, archive, a, AccountActs.By.service(), root());
            store.libraryHome(t.taken().library().id(), id);   // its folder is this node's
            ByteArrayOutputStream said = new ByteArrayOutputStream();
            AccountsCli.saidTaken(store, t, new PrintStream(said, true, StandardCharsets.UTF_8));
            return said.toString(StandardCharsets.UTF_8) + Pages.t("It is kept on {0}.", id);
        } finally { Files.deleteIfExists(archive); }
    }

    /**
     * A new library made under this node's root (stage 4, step 5), as the service makes one under its own: its folder, the library in
     * it with its name. The service writes it down; it runs here, the node whose root holds it. A name already there is refused.
     */
    Made make(String name, String title) throws IOException {
        if (!name.matches("[a-z0-9][a-z0-9-]{0,63}")) throw new IOException("A library's name is letters, digits and hyphens.");
        Path folder = LibraryPlaces.newFolder(root(), name);
        if (Files.exists(folder)) throw new IOException("There is a folder at " + folder + " already.");
        LibraryStore ls = new LibraryStore(LibraryPlaces.library(folder));
        ls.init();
        ls.setName(title == null || title.isBlank() ? name : title.strip());
        return new Made(ls.identity().id(), folder.toString());
    }

    /** How long packing waits for the requests a library is answering to end before it is put to sleep; a test shortens it. */
    static volatile long PACK_WAIT_MS = 60_000;

    /**
     * The library's archive to take home (stage 4), written to {@code out} as it is made. With copies kept: from its copy brought up to
     * date now, while the library runs on (its search index as its last sleep left it). Without: the library put to sleep and held
     * asleep while it is packed, a request meanwhile told so; it starts again at the next request after. One archive of a library at a
     * time.
     */
    @Override public void archive(AccountStore.Library lib, OutputStream out, String from) throws IOException {
        if (!homeHere(lib) || !Files.isDirectory(LibraryPlaces.library(lib))) throw new NotHere("The node " + id + " cannot reach the folder of the library " + lib.title() + ".");
        if (!packing.add(lib.id())) throw new Packing(packingSaid());
        try {
            if (LibraryPlaces.copies() != null) {
                // the copy as the library is now; no other pass until it is read. Asleep, its index comes too, so nothing may start it
                // meanwhile (ROB 9): under the library's own lock, as when it goes to sleep, a request that wakes it waits for the copy
                boolean asleep;
                synchronized (lock(lib.id())) {
                    asleep = !awake(lib.id());
                    if (asleep) { synchronized (copyLocks.computeIfAbsent(lib.id(), k -> new Object())) { places.bringUp(lib, epoch(lib.id()), true); } }
                }
                if (!asleep) { synchronized (copyLocks.computeIfAbsent(lib.id(), k -> new Object())) { places.bringUp(lib, epoch(lib.id()), false); } }   // awake: its index as its last sleep left it
                LibraryArchive.write(out, LibraryPlaces.copy(lib.id()).resolve("files"), lib, from);
                return;
            }
            long until = System.currentTimeMillis() + PACK_WAIT_MS;
            while (awake(lib.id()) && !sleep(lib.id(), "packed to take home")) {   // a request it is answering ends first; no new one is let in
                if (System.currentTimeMillis() > until) throw new Packing(Pages.t("The library {0} is busy answering; ask for its archive again in a minute.", lib.title()));
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
            }
            synchronized (lock(lib.id())) { LibraryArchive.write(out, LibraryPlaces.folder(lib), lib, from); }
        } finally { packing.remove(lib.id()); }
    }

    /** What this node holds now, a line a library: its id, start, address, process (when known), and what it last said it is doing. */
    public List<Map<String, Object>> holding() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Awake> e : awake.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("library", e.getKey()); m.put("epoch", e.getValue().epoch); m.put("address", e.getValue().address == null ? "" : e.getValue().address);
            m.put("pid", local.pid(e.getKey()));
            State s = states.get(e.getKey());
            if (s != null) { m.put("night", s.night()); m.put("jobs_running", s.jobsRunning()); m.put("quiet", s.quiet()); }
            out.add(m);
        }
        return out;
    }

    /** What the library last said it is doing; null for one that is asleep or has not been asked yet. */
    public State state(String libraryId) { return states.get(libraryId); }

    /** The libraries this node holds now. */
    public List<String> held() { return new ArrayList<>(awake.keySet()); }

    @Override public void stopAll() {
        stopping = true;
        Thread t = ticker;
        if (t != null) t.interrupt();
        work.shutdown();
        local.stopAll();   // each is asked to stop, so each lets its folder's lease go
        for (Map.Entry<String, Awake> e : awake.entrySet()) { try { store.release(e.getKey(), id, e.getValue().epoch); } catch (IOException gone) { /* the lease runs out by itself */ } }
        awake.clear(); states.clear();
    }

    // ---- taking, starting, giving back ----

    /** One this node left running when it ended: taken back when the row still names this node and the process answers as that start. Called under the library's lock. */
    private Awake takeBack(AccountStore.Library lib, AccountStore.Placement p) throws IOException {
        if (!p.node().equals(id) || p.address().isEmpty() || !local.adopt(lib, p.address(), p.epoch())) return null;
        if (!store.renew(lib.id(), id, p.epoch(), LEASE_MS)) return null;
        Awake a = new Awake(p.epoch());
        a.address = p.address();
        awake.put(lib.id(), a);
        return a;
    }

    /** The lease taken and the library started as the row's next start. Called under the library's lock. */
    private Awake begin(AccountStore.Library lib) throws IOException {
        long epoch = store.take(lib.id(), id, LEASE_MS);
        if (epoch < 0) throw new IOException("The library " + lib.title() + " is held by another machine of this service now.");
        Awake a = new Awake(epoch);
        awake.put(lib.id(), a);   // known to the round from now, which holds the lease on while the library starts
        try {
            a.address = local.start(lib, epoch);
            return a;
        } catch (IOException | RuntimeException e) {
            forget(lib.id(), a, true);
            throw e;
        }
    }

    private void forget(String libraryId, Awake a, boolean release) {
        awake.remove(libraryId, a); states.remove(libraryId); jobsEnded.remove(libraryId);
        if (release) { try { store.release(libraryId, id, a.epoch); } catch (IOException gone) { /* the lease runs out by itself */ } }
    }

    /** A library stopped as a service stop stops it, and its lease given back; false when a request is on its way to it. */
    boolean sleep(String libraryId, String why) {
        synchronized (lock(libraryId)) {
            Awake a = awake.get(libraryId);
            if (a == null || a.address == null) return false;
            a.closing = true;
            if (a.inFlight.get() > 0) { a.closing = false; return false; }
            LOG.info("The library {} is stopped: {}.", libraryId, why);
            awake.remove(libraryId, a); states.remove(libraryId);   // asleep from here for whoever asks; a request that comes now waits on the lock and starts it again
            holding.put(libraryId, a.epoch);   // its lease held on while its copy is brought up to date, with the index, the library stopped
            try {
                local.stop(libraryId);
                copy(libraryId, a.epoch, true);
            } finally { holding.remove(libraryId); forget(libraryId, a, true); }
            return true;
        }
    }

    // ---- the round ----

    /** One round: every lease held on, every library told "still yours" and heard, the idle ones put to sleep, the ones of an older version started again. */
    void tick() {
        try { store.nodeSeen(id, address, root, Version.string()); } catch (IOException e) { /* said by the leases below */ }
        measureWhenDue();
        routedLost();
        if (System.currentTimeMillis() - swept > 3_600_000L) {   // what this node set aside on its disk, whose grace is over
            swept = System.currentTimeMillis();
            submit("sweep", "", () -> { try { int gone = places.sweep(); if (gone > 0) LOG.info("{} folder(s) set aside on this machine for 30 days are removed.", gone); } catch (IOException e) { LOG.warn("What was set aside could not all be removed: {}", e.getMessage()); } });
        }
        long sleepAfter = sleepAfterMs();
        for (Map.Entry<String, Long> h : holding.entrySet()) { try { store.renew(h.getKey(), id, h.getValue(), LEASE_MS); } catch (IOException e) { /* the next round */ } }
        for (String libraryId : new ArrayList<>(awake.keySet())) {
            Awake a = awake.get(libraryId);
            if (a == null) continue;
            boolean mine, renewed = false;
            try { mine = renewer.renew(libraryId, id, a.epoch, LEASE_MS); if (mine) { a.renewed = System.nanoTime(); renewed = true; } }
            catch (IOException e) { mine = (System.nanoTime() - a.renewed) / 1_000_000 < LEASE_MS * 2 / 3; }   // the store does not answer: the library goes on until two thirds of its lease are gone
            if (!mine) {   // somebody else's now, taken out of service, or the store has been silent too long: stopped before anyone else may take it
                if (submit("life", libraryId, () -> { synchronized (lock(libraryId)) { if (awake.get(libraryId) == a) { local.stop(libraryId); forget(libraryId, a, true); } } }))   // the lease given back only if it is still this start's
                    LOG.warn("The library {} is no longer this node's to serve (start {}); it is stopped.", libraryId, a.epoch);
                continue;
            }
            if (a.address == null) continue;   // it is starting
            if (!local.alive(libraryId, a.epoch)) { synchronized (lock(libraryId)) { if (awake.get(libraryId) == a && !local.alive(libraryId, a.epoch)) forget(libraryId, a, true); } continue; }
            if (!renewed) continue;   // "still yours" only after the lease was renewed this round (ROB 8): the library's own fence ends it in time
            State s = stillYours(libraryId, a);
            if (s == null) continue;
            states.put(libraryId, s);
            try { store.due(libraryId, s.dueAt()); } catch (IOException e) { /* written at the next round */ }
            copyWhenDue(libraryId, a, s);
            if (!s.quiet()) continue;
            if (!s.version().equals(Version.string())) { submit("life", libraryId, () -> restart(libraryId, a, s.version())); continue; }
            if (frozen(libraryId)) { submit("life", libraryId, () -> sleep(libraryId, "its keeper's account is suspended or being deleted")); continue; }   // closed to everyone: asleep once quiet
            if (sleepAfter <= 0 || s.idleMs() < sleepAfter) continue;
            long now = System.currentTimeMillis(), night = nextNight.applyAsLong(libraryId);
            if ((s.dueAt() > 0 && s.dueAt() - now < DUE_SOON_MS) || (night > 0 && night - now < DUE_SOON_MS)) continue;
            submit("life", libraryId, () -> sleep(libraryId, "nothing asked of it and nothing to do for " + s.idleMs() / 1000 + " s"));
        }
    }

    /**
     * A running library's copy is brought up to date after each job it ends (its night is a job), and at a quiet moment (no request
     * open, no job, no chat turn) when it was asked something since the last copy and that copy is older than the setting says.
     * These copies leave its index as the last copy made while it was stopped has it.
     */
    private void copyWhenDue(String libraryId, Awake a, State s) {
        if (LibraryPlaces.copies() == null) return;
        long before = jobsEnded.getOrDefault(libraryId, 0L);
        jobsEnded.put(libraryId, s.jobsEnded());
        long now = System.currentTimeMillis();
        long copied = copiedAt.computeIfAbsent(libraryId, k -> { try { AccountStore.Placement p = store.placement(k); return p == null ? 0L : p.copiedAt(); } catch (IOException e) { return 0L; } });
        boolean afterJob = s.jobsEnded() > before;
        boolean quietChange = s.quiet() && now - s.idleMs() > copied && now - copied >= LibraryPlaces.copyAfterMs();
        // a job that runs for long is copied as it goes: its kept work (the checkpoints) is what a takeover from the copy resumes from
        boolean duringJob = (s.jobsRunning() > 0 || s.night()) && now - copied >= LibraryPlaces.copyAfterMs();
        if (afterJob || quietChange || duringJob) submit("copy", libraryId, () -> copy(libraryId, a.epoch, false));
    }

    /** A library an older version started, stopped at a quiet moment and started by this one. */
    private void restart(String libraryId, Awake a, String was) {
        synchronized (lock(libraryId)) {
            if (awake.get(libraryId) != a) return;
            a.closing = true;
            if (a.inFlight.get() > 0) { a.closing = false; return; }
            LOG.info("The library {} was started by {}; it is stopped and started by {}.", libraryId, was, Version.string());
            local.stop(libraryId);
            forget(libraryId, a, false);
            try {
                AccountStore.Library lib = store.library(libraryId);
                if (lib != null && lib.active()) begin(lib);
            } catch (IOException e) { LOG.warn("The library {} did not start again after the update: {}; the next request starts it.", libraryId, e.getMessage()); }
        }
    }

    /** "Still yours", said to the library under the service's own statement; what it answers is what it is doing. Null when it did not answer. */
    private State stillYours(String libraryId, Awake a) {
        try {
            JsonNode j = call(libraryId, a, Held.STILL_YOURS);
            if (j == null) return null;
            return new State(j.path("version").asText(""), j.path("open").asInt(0), j.path("idle_ms").asLong(0), j.path("jobs_running").asInt(0), j.path("jobs_waiting").asInt(0),
                    j.path("jobs_held").asInt(0), j.path("night").asBoolean(false), j.path("chat_live").asBoolean(false), j.path("due_at").asLong(0), j.path("jobs_ended").asLong(0));
        } catch (IOException e) { return null; }
    }

    /** The library's night started: its nightly tasks, as one job. The library is started first when it is asleep. */
    @Override public void night(AccountStore.Library lib) throws IOException {
        ensure(lib);
        try {
            Awake a = awake.get(lib.id());
            if (a == null || call(lib.id(), a, Held.NIGHT) == null) throw new IOException("The library " + lib.title() + " did not take its night.");
        } finally { done(lib.id()); }
    }

    /**
     * The people of a library as the store has them now, each account by its did and level: its keeper and its members whose accounts are
     * active. Told to the library with every "still yours", so that someone taken out, suspended or deleted stops getting its changes
     * (their webhook) within the minute, though they made no request (round F, the second review, S15).
     */
    static ObjectNode people(AccountStore store, String libraryId) throws IOException {
        ObjectNode o = Statement.M.createObjectNode(), m = o.putObject("members");
        AccountStore.Library lib = store.library(libraryId);
        if (lib == null) return o;
        AccountStore.Account keeper = store.account(lib.owner());
        if (keeper != null && keeper.active()) m.put(Hosted.DID + keeper.id(), Statement.KEEPER);
        for (AccountStore.Member mem : store.members(libraryId)) {
            AccountStore.Account a = store.account(mem.account());
            if (a != null && a.active() && !m.has(Hosted.DID + a.id())) m.put(Hosted.DID + a.id(), mem.level());
        }
        return o;
    }

    private JsonNode call(String libraryId, Awake a, String path) throws IOException {
        AccountStore.Signing k = store.signing();
        String statement = Statement.sign(k.key(), k.kid(), Statement.Claims.now(service, libraryId, "service", "the account service", Statement.READ, Statement.SERVICE).epoch(a.epoch));
        String body = path.equals(Held.STILL_YOURS) ? people(store, libraryId).toString() : "";
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(a.address + path)).timeout(Duration.ofSeconds(5)).header(Statement.HEADER, statement).POST(body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? Statement.M.readTree(r.body()) : null;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
    }
}
