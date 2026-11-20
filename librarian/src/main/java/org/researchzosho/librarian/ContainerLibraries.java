package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import org.researchzosho.Config;
import org.researchzosho.Version;
import org.researchzosho.tools.Addresses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Each library of the account service in a container of its own (0.8.0, {@code accounts_runner container}). The container runs this
 * program's own image at the service's version, sees the library's folder at {@code /library}, its settings at {@code /config}, its
 * temporary folder at {@code /tmp} and its home at {@code /home/library}, and no other folder of the machine: the kernel keeps one
 * account's library out of another's, where the process form has only the program's own checks. It runs as the user who owns the
 * library's folder, under a group of the library's own ({@link #libraryGroup}), with no capabilities and no way to gain any, a root file system it cannot write, and limits on its memory (the
 * heap and room besides), its processors and its processes. It answers on a loopback port from the node's table, as the process form
 * does, and is started with the same {@code --vouched-by} and {@code --library-id}.
 *
 * <p>It has no network at all ({@code --network none}): no route to the internet, to this machine, to the private network around it
 * or to another container. What it reaches, it reaches through its node, by Unix sockets in a folder of its own that it sees at
 * {@code /run/rz} ({@link Relays}): the service's requests come in through one; the service, and each server its settings name on
 * this machine's loopback (the model, the search), each through one of their own; and the web through the node's gate
 * ({@link WebGate}), which refuses this machine, private networks, link-local addresses where the cloud's metadata answers, and the
 * like, by the one list the fetcher has ({@code Addresses}).
 *
 * <p>A container is started, stopped and removed only by the name this service gave it and the labels it put on it: the name a
 * library's container has is {@code researchzosho-<library id>}, and a container of that name is this service's only when its labels
 * name this service and this library. One that is not is left alone, and the library is not started while it holds the name.
 */
public final class ContainerLibraries implements LibraryRunner.Local {
    /** The image each library's container runs; unset: the published image at this program's own version. */
    public static final String IMAGE = "RESEARCHZOSHO_ACCOUNTS_IMAGE";
    /** How many processors each library's container may use; unset: 2. */
    public static final String CPUS = "RESEARCHZOSHO_ACCOUNTS_LIBRARY_CPUS";
    /** The labels this service puts on what it makes, which prove a container is its own. */
    static final String SERVICE_LABEL = "org.researchzosho.service", LIBRARY_LABEL = "org.researchzosho.library", EPOCH_LABEL = "org.researchzosho.epoch";
    /** The beginning of every name this service gives a container. */
    static final String PREFIX = "researchzosho-";
    /** Where the container sees its folders. */
    static final String LIBRARY_AT = "/library", CONFIG_AT = "/config", TMP_AT = "/tmp", HOME_AT = "/home/library", RELAYS_AT = "/run/rz";
    /** Room for what a Java process holds besides its heap (its classes, threads and buffers) and for the PDF reader it runs: added to the heap for the container's memory. */
    static final long ROOM_MB = 768;
    /** The most processes and threads a library's container may have: a library runs a few dozen threads, a busy one some hundreds. */
    static final int PIDS = 1024;
    /** How long a library is given to answer once its container is started, and an image to be fetched. */
    static volatile long START_MS = 60_000, PULL_MS = 15 * 60_000;

    /** The docker command: a short one run to its end, and {@code docker run} left attached for as long as its container runs. A test stands in for it. */
    interface Docker {
        Result run(List<String> args, long ms) throws IOException;
        Process start(List<String> args, File log) throws IOException;
    }

    /** What a short docker command ended with, and what it printed. */
    record Result(int code, String out) { }

    static volatile Docker DOCKER = new SystemDocker();

    /** A library this runner knows to be up: the attached {@code docker run} (null for one taken back), its container's id, its address, the node's relays for it. */
    private record Running(Process client, String container, String address, Relays relays) { }

    private final AccountStore store;
    private final String service, reach;
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Set<String> endedSaid = ConcurrentHashMap.newKeySet();
    private volatile String imageReady;
    private static final Logger LOG = LoggerFactory.getLogger(ContainerLibraries.class);

    /** {@code service}: the address libraries reach the account service at, which is also the name its statements are made in. */
    public ContainerLibraries(AccountStore store, String service) { this(store, service, service); }

    /**
     * The same, where the node reaches the service at {@code reach}: its own loopback when the node is part of the service's process, else
     * the service's address. Each library reaches the service through a socket of the node's ({@code service.sock}) that goes there, and
     * not through the web's gate, which lets no library reach the service or anything on the service's networks.
     */
    public ContainerLibraries(AccountStore store, String service, String reach) { this.store = store; this.service = service; this.reach = reach == null || reach.isBlank() ? service : reach; }

    // ---- what is set ----

    /** The image the setting names, else the published image at this program's own version. */
    static String image() {
        String v = Config.get(IMAGE);
        return v != null && !v.isBlank() ? v.strip() : "ghcr.io/wyrdsekai/researchzosho:" + (Version.number() == null ? "latest" : Version.number());
    }

    /** The processors the setting names (1, 1.5, 2), else 2. */
    static String cpus() {
        String v = Config.get(CPUS);
        return v != null && v.strip().matches("\\d+(\\.\\d+)?") && Double.parseDouble(v.strip()) > 0 ? v.strip() : "2";
    }

    /** The container's memory for a heap as Java writes it (512m, 1g): the heap and {@link #ROOM_MB} more, in MB. */
    static String memory(String heap) {
        String h = heap.strip().toLowerCase(Locale.ROOT);
        long n = Long.parseLong(h.substring(0, h.length() - 1));
        long mb = switch (h.charAt(h.length() - 1)) { case 'k' -> Math.max(1, n / 1024); case 'g' -> n * 1024; default -> n; };
        return (mb + ROOM_MB) + "m";
    }


    static String name(String libraryId) { return PREFIX + libraryId; }

    /**
     * The group a library's container runs under (0.8.0 review): the user who owns its folder, so its files are that user's as before, but
     * a group of the library's own, which no program of the machine runs under, so the node can prove a socket in its folder is the
     * library's own by who listens on it ({@link Relays#proven}). Numbered from {@link #GROUP_BASE}, by the library's id: the same at
     * every start, on every node.
     */
    static final long GROUP_BASE = 2_000_000_000L, GROUPS = 100_000_000L;
    static long libraryGroup(String libraryId) {
        // from a hash no name can be chosen for: two ids that String.hashCode sends to one number ("lib_Aa", "lib_BB") are two groups (round F)
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(libraryId.getBytes(StandardCharsets.UTF_8));
            long v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | (d[i] & 0xff);
            return GROUP_BASE + Math.floorMod(v, GROUPS);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /**
     * A library whose group is another library's is not started: the group is how the node knows a socket is the library's own, and an id
     * can be chosen (an archive's) until it lands on another's group (round F).
     */
    void groupIsItsOwn(AccountStore.Library lib) throws IOException {
        if (GROUP != BY_ID) return;   // a test's stand-in runs every library under its own one group
        long mine = GROUP.applyAsLong(lib.id());
        for (AccountStore.Library other : store.libraries())
            if (!other.id().equals(lib.id()) && GROUP.applyAsLong(other.id()) == mine)
                throw new IOException("The library " + lib.id() + " has the group of the library " + other.id() + ", so it is not started; take it in again under another id.");
    }
    /** The group a library's container is run under; a test's stand-in, which runs the library as its own process, says its own. */
    static final ToLongFunction<String> BY_ID = ContainerLibraries::libraryGroup;
    static volatile ToLongFunction<String> GROUP = BY_ID;

    /** Who must listen on the library's own socket: the owner of its folder, under the group its container runs with. */
    static Relays.Peer peer(Path folder, long group) throws IOException {
        return new Relays.Peer(Files.getOwner(folder), FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByGroupName(Long.toString(group)));
    }

    // ---- the runner ----

    @Override public String ensure(AccountStore.Library lib) throws IOException {
        Running r = running.get(lib.id());
        if (r != null && alive(lib.id(), 0)) return r.address();
        synchronized (locks.computeIfAbsent(lib.id(), k -> new Object())) {
            r = running.get(lib.id());
            if (r != null && alive(lib.id(), 0)) return r.address();
            AccountStore.Library now = store.library(lib.id());
            String was = now == null ? "" : now.address();
            if (adopt(lib, was, 0)) return was;
            return start(lib, 0);
        }
    }

    @Override public void lost(String libraryId) { Running r = running.get(libraryId); if (r != null && !alive(libraryId, 0)) running.remove(libraryId); }

    @Override public boolean alive(String libraryId, long epoch) {
        Running r = running.get(libraryId);
        if (r == null) return false;
        if (r.client() == null) return ChildLibraries.answers(r.address(), libraryId, epoch);
        if (r.client().isAlive()) return true;
        if (endedSaid.add(r.container())) LOG.warn("The container of the library {} ended by itself (exit {}{}); its log is in its folder.", libraryId, r.client().exitValue(), r.client().exitValue() == 137 ? ": killed, most often for going over its memory" : "");
        return false;
    }

    @Override public void stop(String libraryId) {
        Running r = running.remove(libraryId);
        if (r == null) return;
        remove(r.container(), r.client(), libraryId);
        if (r.relays() != null) r.relays().close();
        forgetSockets(libraryId);
    }

    /** What is left in the library's sockets' folder once its container is gone (its own socket among them), and the folder: removed. */
    private static void forgetSockets(String libraryId) {
        if (!LibraryPlaces.idOk(libraryId)) return;
        Path dir = LibraryPlaces.under(socketsBase(), libraryId);
        try (var files = Files.list(dir)) { for (Path f : files.filter(f -> f.getFileName().toString().endsWith(".sock")).toList()) Files.deleteIfExists(f); }
        catch (IOException ignored) { /* none there */ }
        try { Files.deleteIfExists(dir); } catch (IOException ignored) { /* something else is in it: left */ }
    }

    @Override public boolean adopt(AccountStore.Library lib, String address, long epoch) {
        if (address == null || address.isEmpty()) return false;
        JsonNode c;
        try { c = container(name(lib.id())); } catch (IOException e) { return false; }
        if (c == null || !ours(c, lib.id()) || !c.path("State").path("Running").asBoolean(false)) return false;
        // its node's side again, where a node that ended left it: the same sockets by the same names, and the way in at its address
        Relays relays = new Relays();
        try {
            environment(lib, relays, new StringBuilder(), new HashSet<>());
            String user = c.path("Config").path("User").asText("");   // the group it was started under, as it says
            long group = user.matches("\\d+:\\d+") ? Long.parseLong(user.substring(user.indexOf(':') + 1)) : GROUP.applyAsLong(lib.id());
            relays.tcpToUnix(InetAddress.getByName(URI.create(address).getHost()), URI.create(address).getPort(), sockets(lib).resolve(IN), peer(LibraryPlaces.folder(lib), group));
        } catch (IOException | RuntimeException e) { relays.close(); return false; }
        long until = System.currentTimeMillis() + 10_000;   // its container runs: it may only be slow to answer
        while (true) {
            if (ChildLibraries.answers(address, lib.id(), epoch)) { running.put(lib.id(), new Running(null, c.path("Id").asText(""), address, relays)); return true; }
            if (System.currentTimeMillis() >= until) { relays.close(); return false; }
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); relays.close(); return false; }
        }
    }

    @Override public String start(AccountStore.Library lib, long epoch) throws IOException {
        String image = image();
        ready(image);
        JsonNode was = container(name(lib.id()));
        if (was != null) {
            if (!ours(was, lib.id())) {
                LOG.warn("The library {} ({}) is not started: a container named {} is on this machine, and its labels do not name this service and this library, so it is not touched. Whoever runs the service has to look into it.", lib.title(), lib.id(), name(lib.id()));
                throw new IOException(Pages.t("The library {0} cannot be started now: a container that this service did not make has its name. Whoever runs the service has to look into it.", lib.title()));
            }
            // an earlier container of this library by this service (a node that ended without stopping it): stopped and removed first, so the new start has the folder
            LOG.info("An earlier container of the library {} ({}) is stopped and removed before the library is started.", lib.id(), was.path("Id").asText("").substring(0, Math.min(12, was.path("Id").asText("").length())));
            remove(was.path("Id").asText(""), null, lib.id());
        }
        Set<Integer> tried = new HashSet<>();
        Set<String> taken = new HashSet<>();
        for (Running r : running.values()) taken.add(r.address());
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            int port = ChildLibraries.freePort(tried, taken);
            tried.add(port);
            try { return start(lib, port, epoch, image); }
            catch (EndedAtOnce e) { last = e; }
        }
        throw last;
    }

    /** The container ended before its library answered, or the port was taken in the instant between the look and the start. */
    private static final class EndedAtOnce extends IOException { EndedAtOnce(String said) { super(said); } }

    private String start(AccountStore.Library lib, int port, long epoch, String image) throws IOException {
        groupIsItsOwn(lib);
        Path folder = LibraryPlaces.folder(lib);
        for (Path p : new Path[]{LibraryPlaces.library(folder), LibraryPlaces.home(folder), LibraryPlaces.tmp(folder), LibraryPlaces.user(folder)}) Files.createDirectories(p);
        String host = ChildLibraries.host(), at = host.contains(":") ? "[" + host + "]" : host, address = "http://" + at + ":" + port;
        Relays relays = new Relays();
        // the settings handed down, in a file only this user reads, gone once the container has them: no key is among them, the gates add those
        Path env = Files.createTempFile("researchzosho-library-", ".env", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        boolean up = false;
        try {
            StringBuilder serviceAt = new StringBuilder();
            StringBuilder lines = new StringBuilder();
            Set<Integer> reserved = new HashSet<>(List.of(port));   // the ports of the container's loopback, each once: they are this machine's free ones, so a stand-in on this machine has them too
            environment(lib, relays, serviceAt, reserved).forEach((k, v) -> lines.append(k).append('=').append(v).append('\n'));
            Files.writeString(env, lines, StandardCharsets.UTF_8);
            // the way in: this machine's port from the node's table, to the library's own socket
            try { relays.tcpToUnix(InetAddress.getByName(host), port, sockets(lib).resolve(IN), peer(folder, GROUP.applyAsLong(lib.id()))); }   // only to the library's own socket
            catch (IOException taken) { throw new EndedAtOnce("The port " + port + " was taken."); }
            int inside = ChildLibraries.freePort(reserved, Set.of());
            List<String> args = runArguments(lib, folder, inside, epoch, env, serviceAt.toString(), image);
            File log = LibraryPlaces.log(folder).toFile();
            try { Service.rotate(log.toPath(), 20L * 1024 * 1024); } catch (RuntimeException kept) { /* the log goes on as it is */ }
            Process client = DOCKER.start(args, log);
            long until = System.currentTimeMillis() + START_MS;
            while (System.currentTimeMillis() < until) {
                if (!client.isAlive()) {
                    LOG.warn("The container of the library {} ({}) ended as it started (exit {}); its log is {}{}", lib.title(), lib.id(), client.exitValue(), log, ChildLibraries.lastLines(log.toPath()));
                    removeIfOurs(lib.id());
                    throw new EndedAtOnce("The library " + lib.title() + " did not start. Whoever runs this service finds why in the service's log.");
                }
                if (ChildLibraries.answers(address, lib.id(), epoch)) {
                    JsonNode c = container(name(lib.id()));
                    String id = c != null && ours(c, lib.id()) ? c.path("Id").asText("") : "";
                    store.libraryAddress(lib.id(), address);
                    running.put(lib.id(), new Running(client, id, address, relays));
                    up = true;
                    return address;
                }
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); removeIfOurs(lib.id()); throw new IOException("interrupted while the library started"); }
            }
            removeIfOurs(lib.id());
            client.destroy();
            LOG.warn("The library {} ({}) did not answer within {} s of its container being started; its log is {}", lib.title(), lib.id(), START_MS / 1000, log);
            throw new IOException("The library " + lib.title() + " did not answer within " + START_MS / 1000 + " seconds of being started. Whoever runs this service finds why in the service's log.");
        } finally {
            Files.deleteIfExists(env);
            if (!up) { relays.close(); forgetSockets(lib.id()); }
        }
    }

    /** {@code docker run} for one start of the library: its name and labels, its user, its walls and limits, its five folders, no network. */
    List<String> runArguments(AccountStore.Library lib, Path folder, int inside, long epoch, Path env, String serviceAt, String image) throws IOException {
        String memory = memory(ChildLibraries.heap());
        List<String> a = new ArrayList<>(List.of("run", "--rm", "--init", "--name", name(lib.id()),
                "--label", SERVICE_LABEL + "=" + service, "--label", LIBRARY_LABEL + "=" + lib.id(), "--label", EPOCH_LABEL + "=" + epoch,
                "--user", Files.getAttribute(folder, "unix:uid") + ":" + GROUP.applyAsLong(lib.id()),   // the folder's owner, under the library's own group
                "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--memory", memory, "--memory-swap", memory, "--cpus", cpus(), "--pids-limit", String.valueOf(PIDS),
                "--network", "none",
                "--volume", LibraryPlaces.library(folder) + ":" + LIBRARY_AT, "--volume", LibraryPlaces.home(folder) + ":" + CONFIG_AT,
                "--volume", LibraryPlaces.tmp(folder) + ":" + TMP_AT, "--volume", LibraryPlaces.user(folder) + ":" + HOME_AT,
                "--volume", socketsAsDockerSees(lib) + ":" + RELAYS_AT,
                "--env-file", env.toString(), image,
                "serve", "--host", "127.0.0.1", "--port", String.valueOf(inside), "--relays", RELAYS_AT,
                "--vouched-by", service, "--service-at", serviceAt, "--library-id", lib.id()));
        // a start the registry counts is the node's to keep: it is told which start it is, and its night is the service's scheduler's
        if (epoch > 0) a.addAll(List.of("--epoch", String.valueOf(epoch), "--no-crews"));
        return a;
    }

    /** The socket the node passes the service's requests to; the library makes it. */
    static final String IN = Relays.IN;
    /** The node's socket for the web's gate; besides it, one for each address of this machine's loopback (the service's among them), by its address. */
    static final String WEB_SOCKET = "web";

    /**
     * The folder the node makes each library's sockets in (stage 4, step 5): a short path, since a socket's path holds at most 107 bytes.
     * Unset: a folder of this user's in the temporary folder. A node that runs in a container itself names the same folder as Docker's
     * machine sees it in {@link #SOCKETS_HOST}, which a library's container is given.
     */
    public static final String SOCKETS = "RESEARCHZOSHO_ACCOUNTS_SOCKETS", SOCKETS_HOST = "RESEARCHZOSHO_ACCOUNTS_SOCKETS_HOST";

    private static Path socketsBase() {
        String set = Config.get(SOCKETS);
        if (set != null && !set.isBlank()) return Path.of(set.strip());
        return Path.of(System.getProperty("java.io.tmpdir")).resolve("researchzosho-" + System.getProperty("user.name", "rz").replaceAll("[^A-Za-z0-9_.-]", "_"));
    }

    /** A library's sockets folder as Docker's machine sees it: the same one, unless the node runs in a container whose folder is mounted elsewhere. */
    static Path socketsAsDockerSees(AccountStore.Library lib) throws IOException {
        String host = Config.get(SOCKETS_HOST);
        Path here = sockets(lib);
        return host == null || host.isBlank() ? here : LibraryPlaces.under(Path.of(host.strip()), lib.id());
    }

    /** The folder of the library's sockets, short enough for a socket's path, the node's user's alone. */
    static Path sockets(AccountStore.Library lib) throws IOException {
        if (!LibraryPlaces.idOk(lib.id())) throw new IOException(LibraryPlaces.notAnId(lib.id()));
        Path base = socketsBase();
        Path dir = LibraryPlaces.under(base, lib.id());
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(base, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        return dir;
    }

    /**
     * What the library's container is given, and the node's side of its ways out made in {@code relays}: what a library's process is
     * given ({@link ChildLibraries#handedDown}), with the paths it sees; each address of this machine's loopback in it put as a port of
     * the container's own loopback that the library passes to a socket of the node's for that address; the service the same way (its
     * address in {@code serviceAt}); and the web through the node's gate, for the library's Java and every program it runs. Nothing else
     * of the service's environment.
     */
    /** What the programs a library runs (git and the like) send past the gate: the service's own address only; localhost goes through it, which refuses it (round F). */
    static final String NO_PROXY = "127.0.0.1";

    Map<String, String> environment(AccountStore.Library lib, Relays relays, StringBuilder serviceAt, Set<Integer> reserved) throws IOException {
        Path dir = sockets(lib);
        Map<String, String> env = new HashMap<>(System.getenv());
        ChildLibraries.handedDown(env, LIBRARY_AT, CONFIG_AT, "127.0.0.1", NodeGates.of(store), lib.id());
        env.keySet().removeIf(k -> !(k.startsWith(Config.PREFIX) || k.startsWith("CODEZAIKU_") || k.equals("TZ")));
        env.remove("RESEARCHZOSHO_JAVA"); env.remove("RESEARCHZOSHO_XMX"); env.remove("CODEZAIKU_JAVA_OPTS");   // the image's own Java, and the heap below
        Set<String> named = WebGate.named(env);   // before the loopback ones are put as relays: the private servers the settings name, let through by name
        Map<String, Integer> ports = new TreeMap<>();   // a socket's name -> the port of the container's loopback the library listens on for it
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : env.entrySet()) {
            String v = e.getValue();
            if (v == null || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) { LOG.warn("The setting {} holds more than one line, which a container's settings cannot carry; the library's container goes without it.", e.getKey()); continue; }
            out.put(e.getKey(), loopbackRelayed(v, dir, relays, ports, reserved));
        }
        serviceAt.append(serviceRelayed(dir, relays, ports, reserved));   // the service through a socket of its own, never through the web's gate
        relays.unix(dir.resolve(WEB_SOCKET + ".sock"), new WebGate(lib.id(), named, NodeGates.of(store).fetches(lib.id())));   // each request counted (stage 3)
        int web = ChildLibraries.freePort(reserved, Set.of());
        reserved.add(web);
        ports.put(WEB_SOCKET, web);
        StringBuilder spec = new StringBuilder();
        ports.forEach((n, p) -> spec.append(spec.isEmpty() ? "" : ",").append(p).append('=').append(n));
        out.put(Relays.SPEC, spec.toString());
        out.put(Addresses.THROUGH_GATE, "on");
        String proxy = "http://127.0.0.1:" + web;
        for (String k : List.of("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy")) out.put(k, proxy);   // git, and any other program the library runs
        for (String k : List.of("NO_PROXY", "no_proxy")) out.put(k, NO_PROXY);
        out.put("RESEARCHZOSHO_CONFIG", CONFIG_AT + "/config");   // the settings file where the process form has it: the folder's home/config
        out.put("HOME", HOME_AT);
        StringBuilder java = new StringBuilder("-Xmx" + ChildLibraries.heap() + " -Djava.io.tmpdir=" + TMP_AT + " -Duser.home=" + HOME_AT
                + " -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=" + web + " -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=" + web);   // the library's own web clients (Java sends a loopback name direct whatever its list says: the fetcher refuses localhost by name, Fetch.refusal)
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) if (arg.startsWith("-Dresearchzosho.") && !arg.contains(" ")) java.append(' ').append(loopbackRelayed(arg, dir, relays, ports, reserved));   // a test's timings, as the process form passes them
        out.put("RESEARCHZOSHO_JAVA_OPTS", java.toString());
        return out;
    }

    /** The node's socket for the service: what the library sends there goes to the service where the node reaches it. */
    static final String SERVICE_SOCKET = "service";

    /** The service's address as the library in its container is given it: a port of its own loopback, relayed by the node to {@link #reach}. */
    private String serviceRelayed(Path dir, Relays relays, Map<String, Integer> ports, Set<Integer> reserved) throws IOException {
        URI at = URI.create(reach);
        int port = at.getPort() >= 0 ? at.getPort() : "https".equalsIgnoreCase(at.getScheme()) ? 443 : 80;
        relays.unixToTcp(dir.resolve(SERVICE_SOCKET + ".sock"), at.getHost().replace("[", "").replace("]", ""), port);
        int local = ChildLibraries.freePort(reserved, Set.of());
        reserved.add(local);
        ports.put(SERVICE_SOCKET, local);
        return "http://127.0.0.1:" + local;
    }

    /** {@code value} with each address of this machine's loopback in it put as a port of the container's loopback, relayed to it by the node. */
    private static String loopbackRelayed(String value, Path dir, Relays relays, Map<String, Integer> ports, Set<Integer> reserved) throws IOException {
        Matcher m = LOOPBACK.matcher(value);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String scheme = m.group(1), host = m.group(2).equalsIgnoreCase("localhost") ? "127.0.0.1" : m.group(2).replace("[", "").replace("]", "");
            int port = m.group(3) != null ? Integer.parseInt(m.group(3)) : scheme.toLowerCase(Locale.ROOT).startsWith("https") ? 443 : 80;
            String name = "to-" + host.replace(':', '_') + "-" + port;
            Integer local = ports.get(name);
            if (local == null) {
                relays.unixToTcp(dir.resolve(name + ".sock"), host, port);
                local = ChildLibraries.freePort(reserved, Set.of());
                reserved.add(local);
                ports.put(name, local);
            }
            m.appendReplacement(b, Matcher.quoteReplacement(scheme + "127.0.0.1:" + local));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** An address of this machine's loopback in a setting's value: the scheme, the host, the port when it is written. */
    static final Pattern LOOPBACK = Pattern.compile("(https?://)(127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|localhost|\\[::1\\])(?::(\\d{1,5}))?", Pattern.CASE_INSENSITIVE);

    // ---- what is this service's ----

    /** Whether a container is this service's container of this library: by its labels, which this service alone puts on what it makes. */
    boolean ours(JsonNode container, String libraryId) {
        JsonNode labels = container.path("Config").path("Labels");
        return service.equals(labels.path(SERVICE_LABEL).asText(null)) && libraryId.equals(labels.path(LIBRARY_LABEL).asText(null));
    }

    /** The container of that name, or null when there is none. */
    static JsonNode container(String name) throws IOException {
        Result r = DOCKER.run(List.of("container", "inspect", name), 30_000);
        if (r.code() != 0) {
            if (r.out().contains("No such") || r.out().contains("not found")) return null;
            throw new IOException("docker container inspect " + name + ": " + r.out().strip());
        }
        JsonNode a = Statement.M.readTree(r.out());
        return a.isArray() && !a.isEmpty() ? a.get(0) : null;
    }

    /** The library's container when it is this service's: stopped and removed. One that is not is not touched. */
    private void removeIfOurs(String libraryId) {
        try { JsonNode c = container(name(libraryId)); if (c != null && ours(c, libraryId)) remove(c.path("Id").asText(""), null, libraryId); }
        catch (IOException e) { LOG.warn("The container of the library {} could not be looked at: {}", libraryId, e.getMessage()); }
    }

    /**
     * A container of this service, by the id its proof gave: asked to stop as a service stop asks a library (so it lets its folder's lease
     * go), by force after 30 s, and removed.
     */
    private void remove(String id, Process client, String libraryId) {
        if (id == null || id.isEmpty()) {   // never learned: looked up by its name and proven again
            try { JsonNode c = container(name(libraryId)); if (c == null || !ours(c, libraryId)) { if (client != null) client.destroy(); return; } id = c.path("Id").asText(""); }
            catch (IOException e) { LOG.warn("The container of the library {} could not be looked at: {}", libraryId, e.getMessage()); return; }
        }
        try { DOCKER.run(List.of("stop", "--time", "30", id), 45_000); } catch (IOException e) { LOG.warn("docker stop {}: {}", id, e.getMessage()); }
        if (client != null) {
            try { if (!client.waitFor(10, TimeUnit.SECONDS)) client.destroy(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); client.destroy(); }
        }
        try { DOCKER.run(List.of("rm", "--force", id), 30_000); } catch (IOException e) { /* gone already: it was started to be removed when it stopped */ }
    }

    /** The image on this machine, fetched the first time when it is not. */
    private void ready(String image) throws IOException {
        if (image.equals(imageReady)) return;
        if (DOCKER.run(List.of("image", "inspect", "--format", "{{.Id}}", image), 30_000).code() != 0) {
            LOG.info("The image {} is not on this machine; it is fetched now (docker pull).", image);
            Result r = DOCKER.run(List.of("pull", "--quiet", image), PULL_MS);
            if (r.code() != 0) {
                LOG.warn("docker pull {}: {}", image, r.out().strip());
                throw new IOException("The libraries' containers cannot be started: their image is not on this machine and could not be fetched. Whoever runs this service finds why in the service's log.");
            }
        }
        imageReady = image;
    }

    @Override public void stopAll() {
        List<Thread> all = new ArrayList<>();
        for (Map.Entry<String, Running> e : running.entrySet()) all.add(Thread.ofVirtual().start(() -> remove(e.getValue().container(), e.getValue().client(), e.getKey())));   // asked to stop, so each lets its lease go
        for (Thread t : all) { try { t.join(60_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        for (Map.Entry<String, Running> e : running.entrySet()) { if (e.getValue().relays() != null) e.getValue().relays().close(); forgetSockets(e.getKey()); }
        running.clear();
    }

    /** The machine's own docker command. */
    private static final class SystemDocker implements Docker {
        private static final File NOTHING = new File(File.separatorChar == '\\' ? "NUL" : "/dev/null");

        @Override public Result run(List<String> args, long ms) throws IOException {
            List<String> cmd = new ArrayList<>();
            cmd.add("docker"); cmd.addAll(args);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.from(NOTHING)).start();
            byte[][] out = new byte[1][];
            Thread reader = Thread.ofVirtual().start(() -> { try { out[0] = p.getInputStream().readAllBytes(); } catch (IOException e) { out[0] = new byte[0]; } });
            try {
                if (!p.waitFor(ms, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); throw new IOException("docker " + String.join(" ", args.subList(0, Math.min(2, args.size()))) + " did not end within " + ms / 1000 + " s"); }
                reader.join(5_000);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); p.destroyForcibly(); throw new IOException("interrupted"); }
            return new Result(p.exitValue(), out[0] == null ? "" : new String(out[0], StandardCharsets.UTF_8));
        }

        @Override public Process start(List<String> args, File log) throws IOException {
            List<String> cmd = new ArrayList<>();
            cmd.add("docker"); cmd.addAll(args);
            return new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).redirectInput(ProcessBuilder.Redirect.from(NOTHING)).start();
        }
    }
}
