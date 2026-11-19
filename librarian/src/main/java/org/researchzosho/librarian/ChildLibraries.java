package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import org.researchzosho.Main;
import org.researchzosho.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Each library of the account service as a process of its own on the service's machine (0.8.0, stage 1). A library is started the
 * first time it is asked for: this same program, {@code serve --vouched-by <the service>}, on a free port of the loopback, with its
 * own library folder, settings folder, temporary folder and home, and with the settings of whoever runs the service handed down in its
 * environment, where a library cannot change them. It is up when its {@code /healthz} answers with its own id. Libraries stop with
 * the service, asked to and never killed, so each lets its lease go; a library left running by a service that died is found again by
 * its address and its id.
 *
 * <p>The libraries of different accounts run here as one user of the operating system: what keeps one account out of another's
 * library is the program's own checks ({@link Hosted}), not the operating system. A container for each library is the wall
 * ({@link ContainerLibraries}, accounts_runner container).
 */
public final class ChildLibraries implements LibraryRunner.Local {
    /** The first port tried for a library unless the setting names another, and how long a library is given to start. */
    public static final int FIRST_PORT = 4700;
    static volatile long START_MS = 60_000;
    /** The settings a library does not get from the service's own: the service's, the ones each library sets for itself, and the ones pinned below. */
    private static final Set<String> NOT_HANDED_DOWN = Set.of(Proxy.SETTING, WebAccess.SIGNIN, WebAccess.LOCAL, "RESEARCHZOSHO_SHARE", "RESEARCHZOSHO_SHARE_ADDRESS", "RESEARCHZOSHO_SHARE_MAIL", "RESEARCHZOSHO_DOOR",
            "RESEARCHZOSHO_UPDATE", Logs.SETTING, Accounts.SESSION_DAYS, Settings.CALIBRE, "RESEARCHZOSHO_DNA_SHARE", "RESEARCHZOSHO_DNA_CONTRIBUTOR", "RESEARCHZOSHO_FETCH_PRIVATE", Helpers.LOCAL);

    /** A library this runner knows to be up: its process (null when it could not be told which process holds it), its address, and when it was last seen to answer. */
    /** What a library's process takes from the service's environment as it is: how its Java runs and how much it reads at once; and every {@code <KEY>_DEFAULT}, an offer. */
    private static final Set<String> PASSED_AS_IT_IS = Set.of("RESEARCHZOSHO_XMX", "RESEARCHZOSHO_JAVA", "RESEARCHZOSHO_JAVA_OPTS", "RESEARCHZOSHO_CTX", "RESEARCHZOSHO_RERANK", "RESEARCHZOSHO_ENRICH");

    /** The settings that hold a model host's key ({@code models key <host>}). */
    private static final String HOST_KEYS = "RESEARCHZOSHO_KEY_";

    private record Running(ProcessHandle process, String address, long checked) { boolean alive() { return process != null && process.isAlive(); } }

    private final AccountStore store;
    private final String service;
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    /** Each library's web gate, a port of this machine's loopback its process reaches the web through (0.8.0, stage 3). */
    private final Map<String, Relays> webGates = new ConcurrentHashMap<>();
    private static final Logger LOG = LoggerFactory.getLogger(ChildLibraries.class);
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();

    /** {@code service}: the address libraries reach the account service at, which is also the name its statements are made in. */
    public ChildLibraries(AccountStore store, String service) { this.store = store; this.service = service; }

    @Override public String ensure(AccountStore.Library lib) throws IOException {
        Running r = running.get(lib.id());
        if (r != null && r.alive()) return r.address();
        if (r != null && r.process() == null && System.currentTimeMillis() - r.checked() < 5_000) return r.address();
        synchronized (locks.computeIfAbsent(lib.id(), k -> new Object())) {
            r = running.get(lib.id());
            if (r != null && r.alive()) return r.address();
            // one a service that ended without stopping it left running: taken back when it answers at its address with its own id
            AccountStore.Library now = store.library(lib.id());
            String was = now == null ? "" : now.address();
            if (!was.isEmpty() && answers(was, lib.id())) { ProcessHandle h = provenHolder(lib); webGateAgain(lib, h); running.put(lib.id(), new Running(h, was, System.currentTimeMillis())); return was; }
            return start(lib);
        }
    }

    @Override public void lost(String libraryId) { Running r = running.get(libraryId); if (r != null && !r.alive()) running.remove(libraryId); }

    // ---- for the node, which keeps the lease and says which start of the library this is ----

    /**
     * The address of this machine the libraries of a node listen on, which the account service reaches them at: on one machine the
     * loopback; with nodes on other machines their address on the private network between them and the service. Unset: 127.0.0.1.
     */
    public static final String HOST = "RESEARCHZOSHO_ACCOUNTS_NODE_HOST";
    static String host() { String v = Config.get(HOST); return v == null || v.isBlank() ? "127.0.0.1" : v.strip(); }

    /** What a hosted library's Java heap is limited to: the setting ({@code 512m}, {@code 2g}), else 1 GB. */
    public static final String HEAP = "RESEARCHZOSHO_ACCOUNTS_LIBRARY_HEAP";
    static String heap() { String v = Config.get(HEAP); return v != null && v.strip().matches("\\d+[kKmMgG]") ? v.strip() : "1g"; }

    /** The class path a library's process is started with: this process's own. A test names another build's. */
    volatile String classpath = System.getProperty("java.class.path");

    /** The number of the library's process this runner started or proved, for whoever runs the service; 0 when it has none. */
    @Override public long pid(String libraryId) { Running r = running.get(libraryId); return r == null || r.process() == null ? 0 : r.process().pid(); }

    /** Whether the library this runner started or took back is still there: its process, or, where the process could not be told, its answer. */
    @Override public boolean alive(String libraryId, long epoch) {
        Running r = running.get(libraryId);
        return r != null && (r.alive() || (r.process() == null && answers(r.address(), libraryId, epoch)));
    }

    /** One library asked to stop as a service stop asks it, so it lets its folder's lease go and leaves its jobs for its next start; by force after 30 s. */
    @Override public void stop(String libraryId) {
        Running r = running.remove(libraryId);
        closeWebGate(libraryId);
        if (r == null || !r.alive()) return;
        r.process().destroy();
        try { r.process().onExit().get(30, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); r.process().destroyForcibly(); }
        catch (ExecutionException | TimeoutException e) { r.process().destroyForcibly(); }
    }

    /** One a node that ended without stopping it left running, taken back when it answers at its address as this library and this start. */
    @Override public boolean adopt(AccountStore.Library lib, String address, long epoch) {
        if (address == null || address.isEmpty()) return false;
        long until = System.currentTimeMillis() + (heldNow(lib) != null ? 10_000 : 0);   // a process holds the folder: it may only be slow to answer
        while (true) {
            if (answers(address, lib.id(), epoch)) { ProcessHandle h = provenHolder(lib); webGateAgain(lib, h); running.put(lib.id(), new Running(h, address, System.currentTimeMillis())); return true; }
            if (System.currentTimeMillis() >= until || !listening(address)) return false;   // slow is waited for; nothing at the address is not
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
    }

    /**
     * A process of this machine that serves the library's folder and that this runner did not start or could not take back, stopped
     * before a new start: it would hold the folder's own lease, and the new start could not take it. Whatever start of the library it
     * was, the registry holds no lease for it now.
     */
    private void stopStale(AccountStore.Library lib, long epoch) throws IOException {
        Lease.Holder held = heldNow(lib);
        if (held == null) return;   // nothing holds the folder now, or its hold has run out: the new start takes it
        ProcessHandle h = provenHolder(lib);
        if (h == null && held.replacedBy(epoch, service)) {
            // an earlier start of this library by this service, which cannot be proven from here (no /proc, or not this machine's): it is
            // not stopped; the new start takes the folder's lease at once, and the earlier one gives up when it next looks
            LOG.info("An earlier start ({}) of the library {} holds its folder (pid {}); the new start {} takes it over.", held.epoch(), lib.id(), held.pid(), epoch);
            return;
        }
        if (h == null) {   // something holds the folder, and it is not proven to be a start of this library by this service: nothing is stopped
            LOG.warn("The library {} ({}) is not started: process {} on this machine holds its folder until {}, and it is not proven to be a start of this library by this service, so it is not stopped. Whoever runs the service has to look into it; the hold ends by itself at {}.",
                    lib.title(), lib.id(), held.pid(), held.expires(), held.expires());
            throw new IOException(Pages.t("The library {0} cannot be started now: another process holds its folder. Whoever runs the service has to look into it.", lib.title()));
        }
        LOG.warn("A start of the library {} that this service did not take back serves its folder (pid {}); it is stopped before the library is started.", lib.id(), h.pid());
        h.destroy();
        try { h.onExit().get(30, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); h.destroyForcibly(); }
        catch (ExecutionException | TimeoutException e) { h.destroyForcibly(); }
    }

    /** The folder's own lease when a process of this machine holds it now: current by its own expiry, and its process alive. Null otherwise. */
    private static Lease.Holder heldNow(AccountStore.Library lib) {
        try {
            Lease.Holder h = Lease.holder(new LibraryStore(LibraryPlaces.library(lib)));
            return h != null && h.host().equals(Lease.hostName()) && h.live() ? h : null;
        } catch (IOException | RuntimeException e) { return null; }
    }

    /**
     * The process that holds the library's folder, when it is proven to be a start of this library by this service, and so may be
     * stopped by it; null otherwise. A lease file outlives a crash and a process number is used again by the system, so the number in
     * the file is not proof: the process must have been started as this program's library daemon under this service, for this
     * library ({@link #startedAs}), before the lease file was last written, and the lease must still be current by its own expiry.
     */
    private ProcessHandle provenHolder(AccountStore.Library lib) {
        try {
            LibraryStore ls = new LibraryStore(LibraryPlaces.library(lib));
            Lease.Holder h = Lease.holder(ls);
            if (h == null || !h.host().equals(Lease.hostName())) return null;
            ProcessHandle p = ProcessHandle.of(h.pid()).orElse(null);
            if (p == null || !p.isAlive() || p.pid() == ProcessHandle.current().pid()) return null;
            ProcessHandle.Info info = p.info();
            List<String> args = argumentsOf(p);
            Instant written = Files.getLastModifiedTime(Lease.file(ls)).toInstant();
            return proven(args, info.startInstant().orElse(null), written, h.expires(), Instant.now(), service, lib.id()) ? p : null;
        } catch (IOException | RuntimeException e) { return null; }
    }

    /**
     * Whether what is known of a process proves it is a start of {@code libraryId} by the service at {@code service}: its arguments are
     * this program's {@code serve} with {@code --vouched-by} naming that service and {@code --library-id} naming that library; it
     * started no later than the lease file was last written (a process started after it cannot be the one that wrote it; within
     * {@link ProcessProof#ALLOWANCE}); and the
     * lease is current by its own expiry. Anything not known proves nothing.
     */
    static boolean proven(List<String> args, Instant started, Instant leaseWritten, Instant leaseExpires, Instant now, String service, String libraryId) {
        if (args == null || started == null || leaseWritten == null || leaseExpires == null) return false;
        if (!leaseExpires.isAfter(now) || !ProcessProof.startedBy(started, leaseWritten)) return false;
        int main = args.indexOf(Main.class.getName());
        if (main < 0 || main + 1 >= args.size() || !args.get(main + 1).equals("serve")) return false;
        List<String> rest = args.subList(main + 2, args.size());
        return service.equals(valueOf(rest, "--vouched-by")) && libraryId.equals(valueOf(rest, "--library-id"));
    }

    /**
     * A process's whole command line, word by word, as {@link ProcessProof#words} reads it on each system. Empty when it cannot be read:
     * nothing is proven by it.
     */
    static List<String> argumentsOf(ProcessHandle p) {
        if (!READ_ARGUMENTS) return List.of();
        return ProcessProof.words(p.pid());
    }

    /** False: a process's command line is read as on a machine that gives none (Windows): a test's, to see what is done when nothing is proven. */
    static volatile boolean READ_ARGUMENTS = true;

    private static String valueOf(List<String> args, String flag) { int i = args.indexOf(flag); return i < 0 || i + 1 >= args.size() ? null : args.get(i + 1); }

    /**
     * The library's process started on a free port. A port is free when nothing listens on it at the moment it is tried; two account
     * services on one machine (two people, two tests) can try the same one in the same instant, and then one library finds it taken
     * and ends at once: that library is started again on the next port, up to five times.
     */
    private String start(AccountStore.Library lib) throws IOException { return start(lib, 0L); }

    /** The same as one start of the library by the registry's count ({@code epoch}; 0: none kept). */
    @Override public String start(AccountStore.Library lib, long epoch) throws IOException {
        if (epoch > 0) stopStale(lib, epoch);
        Set<Integer> tried = new HashSet<>();
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            int port = freePort(tried);
            tried.add(port);
            try { return start(lib, port, epoch); }
            catch (EndedAtOnce e) { last = e; }
        }
        throw last;
    }

    /** The library's process ended before it answered: most often its port was taken in the instant between the look and the start. */
    private static final class EndedAtOnce extends IOException { EndedAtOnce(String said) { super(said); } }

    private String start(AccountStore.Library lib, int port, long epoch) throws IOException {
        Path folder = LibraryPlaces.folder(lib), library = LibraryPlaces.library(folder), home = LibraryPlaces.home(folder), tmp = LibraryPlaces.tmp(folder), user = LibraryPlaces.user(folder);
        for (Path p : new Path[]{library, home, tmp, user}) Files.createDirectories(p);
        String host = host(), address = "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        // what this process's own Java was started with (the opens the launcher gives, a test's stand-ins), less what each library gets of its own
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) if (!arg.startsWith("-Xmx") && !arg.startsWith("-Duser.home=") && !arg.startsWith("-Djava.io.tmpdir=") && !arg.startsWith("-javaagent") && !arg.startsWith("-agentlib") && !arg.startsWith("-Dhttp.proxy") && !arg.startsWith("-Dhttps.proxy")) cmd.add(arg);
        cmd.add("-Xmx" + heap());
        cmd.add("-Djava.io.tmpdir=" + tmp);   // its uploads and its scratch are its own: nothing of another library's is beside them
        cmd.add("-Duser.home=" + user);       // and nothing of the home of whoever runs the service is read or written by it
        cmd.add("-cp"); cmd.add(classpath);
        cmd.add(Main.class.getName());
        // the service and the library on the command line: the proof that a process holding the folder later is this start, and may be stopped
        cmd.addAll(List.of("serve", "--host", host, "--port", String.valueOf(port), "--vouched-by", service, "--library-id", lib.id()));
        // a start the registry counts is the node's to keep: it is told which start it is, and its night is the service's scheduler's
        if (epoch > 0) cmd.addAll(List.of("--epoch", String.valueOf(epoch), "--no-crews"));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        environment(pb.environment(), library, home, lib.id());
        // its web through a gate of the node's on this machine's loopback, as a container's is (0.8.0, stage 3): each request checked and counted
        int web = openWebGate(lib.id(), 0, WebGate.named(pb.environment()));
        webGatePorts.put(lib.id(), web);
        Files.writeString(folder.resolve(WEB_GATE_PORT), web + "\n");   // where a service that takes it back after a crash opens the gate again
        pb.command().addAll(pb.command().indexOf("-cp"), proxied(web));
        for (String k : List.of("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy")) pb.environment().put(k, "http://127.0.0.1:" + web);   // git, and any other program it runs
        for (String k : List.of("NO_PROXY", "no_proxy")) pb.environment().put(k, "localhost,127.0.0.1");
        try {
            File log = LibraryPlaces.log(folder).toFile();
            try { Service.rotate(log.toPath(), 20L * 1024 * 1024); } catch (RuntimeException kept) { /* the log goes on as it is */ }
            pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).redirectInput(ProcessBuilder.Redirect.from(new File(File.separatorChar == '\\' ? "NUL" : "/dev/null")));
            Process p = pb.start();
            long until = System.currentTimeMillis() + START_MS;
            while (System.currentTimeMillis() < until) {
                if (!p.isAlive()) { LOG.warn("The library {} ({}) ended as it started, on port {}; its log is {}", lib.title(), lib.id(), port, log); throw new EndedAtOnce("The library " + lib.title() + " did not start. Whoever runs this service finds why in the service's log."); }
                if (answers(address, lib.id(), epoch)) {
                    store.libraryAddress(lib.id(), address);
                    running.put(lib.id(), new Running(p.toHandle(), address, System.currentTimeMillis()));
                    return address;
                }
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); p.destroy(); throw new IOException("interrupted while the library started"); }
            }
            p.destroy();
            LOG.warn("The library {} ({}) did not answer within {} s of being started; its log is {}", lib.title(), lib.id(), START_MS / 1000, log);
            throw new IOException("The library " + lib.title() + " did not answer within " + START_MS / 1000 + " seconds of being started. Whoever runs this service finds why in the service's log.");
        } catch (IOException | RuntimeException e) { closeWebGate(lib.id()); throw e; }   // a start that failed leaves no gate open
    }

    /**
     * What a library's process is given: its own folders; the service's model, search and helper settings, which outrank anything in
     * the library's own settings file; and what a library behind a router is given since 0.7.0 (the router trusted for the forwarded
     * address, no fetch into the private network), with the sign-in on, no update, no sharing and no helper installed by itself.
     */
    void environment(Map<String, String> env, Path library, Path home) throws IOException { environment(env, library, home, null); }

    /** The same for the library {@code libraryId}, whose heavy helpers it reaches at the node's gate under its own id. */
    void environment(Map<String, String> env, Path library, Path home, String libraryId) throws IOException {
        handedDown(env, library.toString(), home.toString(), trusted(), NodeGates.of(store), libraryId);
    }

    /**
     * The same for any form of the library: {@code library} and {@code home} as the library's process sees them (a container sees its
     * own paths), and {@code trusted} the addresses the service's requests reach it from.
     */
    static void handedDown(Map<String, String> env, String library, String home, String trusted, NodeGates gates) { handedDown(env, library, home, trusted, gates, null); }

    /** The same for the library {@code libraryId} (null: none named), whose heavy helpers are put through the node's gate under its id. */
    static void handedDown(Map<String, String> env, String library, String home, String trusted, NodeGates gates, String libraryId) {
        // nothing of this program's settings comes down by being in the service's own environment: a folder of whoever runs the service
        // (a vault, a shelf of books, where backups go) is not a library's to read. What a library gets is put in below, by name.
        env.keySet().removeIf(k -> (k.startsWith(Config.PREFIX) || k.startsWith("CODEZAIKU_")) && !PASSED_AS_IT_IS.contains(k)
                && !(k.endsWith(Config.DEFAULT_SUFFIX) && !NOT_HANDED_DOWN.contains(k.substring(0, k.length() - Config.DEFAULT_SUFFIX.length()))));
        // no key reaches a library (stage 3): the node's gates hold the model's and the paid places' keys, and the other keys stay here
        // and nothing of the service's own environment that holds a credential (a process inherits the rest of it): AWS's, any *_KEY, *_TOKEN
        env.keySet().removeIf(k -> k.startsWith("AWS_") || k.toUpperCase(Locale.ROOT).matches(".*(KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL).*"));
        Set<String> secret = new HashSet<>();
        for (Settings.Setting s : Settings.known()) if (s.kind() == Settings.Kind.secret) secret.add(s.key());
        env.keySet().removeIf(k -> k.startsWith(HOST_KEYS) || secret.contains(k) || (k.endsWith(Config.DEFAULT_SUFFIX) && secret.contains(k.substring(0, k.length() - Config.DEFAULT_SUFFIX.length()))));
        // nor what reaches the service's store by another road (PostgreSQL's own variables: PGPASSWORD, PGHOST, PGPASSFILE…): a library never opens it
        env.keySet().removeIf(k -> k.startsWith("PG"));
        for (Settings.Setting s : Settings.known()) {
            if (NOT_HANDED_DOWN.contains(s.key()) || s.key().startsWith("RESEARCHZOSHO_ACCOUNTS") || s.key().startsWith("RESEARCHZOSHO_OIDC_") || s.kind() == Settings.Kind.secret) continue;
            String v = Config.get(s.key());
            if (v == null || v.isBlank()) continue;
            if (Hosted.ownSetting(s.key())) env.putIfAbsent(s.key() + Config.DEFAULT_SUFFIX, v);   // the library's own to change: offered, not fixed
            else env.put(s.key(), v);
        }
        env.put("RESEARCHZOSHO_LIBRARY", library);
        env.put(Config.HOME_NAME, home);
        env.put(Proxy.SETTING, trusted);
        env.put("RESEARCHZOSHO_FETCH_PRIVATE", "deny");
        env.put(WebAccess.SIGNIN, "on");
        env.put(WebAccess.LOCAL, "signin");
        env.put("RESEARCHZOSHO_UPDATE", "off");
        env.put("RESEARCHZOSHO_SHARE", "off");
        env.put(Helpers.LOCAL, "off");
        env.put(Logs.SETTING, "stdout");
        if (gates != null) gates.rewrite(env, libraryId);   // the model servers, the paid places and the helpers as the node's gates' addresses, their keys as a word
    }

    /** Whether anything listens at an address's port: a library that is only slow does; one that is gone does not. */
    static boolean listening(String address) {
        try (Socket s = new Socket()) {
            URI u = URI.create(address);
            s.connect(new InetSocketAddress(u.getHost(), u.getPort()), 500);
            return true;
        } catch (IOException | RuntimeException e) { return false; }
    }

    /** Who a library trusts for the forwarded address of the person: the loopback, and the service's own host when it is on another machine. */
    private String trusted() {
        try { String h = URI.create(service).getHost(); return h == null || h.equals("127.0.0.1") || h.equals("localhost") ? "127.0.0.1" : "127.0.0.1," + h; }
        catch (RuntimeException e) { return "127.0.0.1"; }
    }

    /** Whether a library answers at an address: its health check, with its own id. */
    static boolean answers(String address, String libraryId) { return answers(address, libraryId, 0); }

    /** The same, as one start of it: the health check names the epoch the library was started with ({@code epoch} 0: any). */
    static boolean answers(String address, String libraryId, long epoch) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(address + "/healthz")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return false;
            JsonNode j = Statement.M.readTree(r.body());
            return j.path("ok").asBoolean(false) && j.path("library_id").asText("").equals(libraryId) && (epoch == 0 || j.path("epoch").asLong(0) == epoch);
        } catch (IOException | RuntimeException e) { return false; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    /** A port of the loopback nothing listens on, from {@link #FIRST_PORT} up, that no running library of this runner was given. */
    private int freePort(Set<Integer> tried) throws IOException {
        Set<String> taken = new HashSet<>();
        for (Running r : running.values()) taken.add(r.address());
        return freePort(tried, taken);
    }

    /** The same for any form of the library: a port nothing listens on, not one of {@code tried}, and not one of the addresses {@code taken}. */
    static int freePort(Set<Integer> tried, Set<String> taken) throws IOException {
        int first = Math.max(1024, Math.min(63000, Config.getInt(AccountService.FIRST_PORT, FIRST_PORT)));
        for (int port = first; port < first + 2000; port++) {
            String h = host();
            if (tried.contains(port) || taken.contains("http://" + (h.contains(":") ? "[" + h + "]" : h) + ":" + port)) continue;
            try (ServerSocket s = new ServerSocket(port, 1, InetAddress.getByName(h))) { return s.getLocalPort(); } catch (IOException inUse) { /* the next one */ }
        }
        throw new IOException("No port is free between " + first + " and " + (first + 2000) + " for another library.");
    }

    @Override public void stopAll() {
        List<ProcessHandle> all = new ArrayList<>();
        for (Running r : running.values()) if (r.alive()) { r.process().destroy(); all.add(r.process()); }   // asked to stop, so each lets its lease go; the ones taken back after a crash too
        long until = System.currentTimeMillis() + 30_000;
        for (ProcessHandle p : all) {
            try { p.onExit().get(Math.max(1, until - System.currentTimeMillis()), TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); p.destroyForcibly(); }
            catch (ExecutionException | TimeoutException e) { p.destroyForcibly(); }
        }
        running.clear();
        for (String id : List.copyOf(webGates.keySet())) closeWebGate(id);
    }

    // ---- the web gate of the process form ----

    /** The Java options that send a library's own web clients to its gate at {@code port}; the loopback (the service, the node's gates) goes direct. */
    static List<String> proxied(int port) {
        return List.of("-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=" + port, "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=" + port);
    }

    /**
     * The library's web gate (0.8.0, stage 3): a port of this machine's loopback ({@code port}, or a free one when 0) where the node's
     * gate checks each request by the same rule as a container's and counts it against its use; the fetcher's own rule holds as well.
     */
    int openWebGate(String libraryId, int port, Set<String> named) throws IOException {
        closeWebGate(libraryId);
        Relays r = new Relays();
        try {
            int p = r.tcp(InetAddress.getLoopbackAddress(), port, new WebGate(libraryId, named, NodeGates.of(store).fetches(libraryId)));
            webGates.put(libraryId, r);
            return p;
        } catch (IOException e) { r.close(); throw e; }
    }

    void closeWebGate(String libraryId) { Relays r = webGates.remove(libraryId); webGatePorts.remove(libraryId); if (r != null) r.close(); }

    /** The port of a library's web gate, while it is open; 0 when not (a test's). */
    int webGatePort(String libraryId) { return webGates.containsKey(libraryId) ? webGatePorts.getOrDefault(libraryId, 0) : 0; }
    private final Map<String, Integer> webGatePorts = new ConcurrentHashMap<>();

    /** The file in a library's folder that says the port of its web gate, which its process was started with. */
    static final String WEB_GATE_PORT = "web-gate.port";

    /**
     * A library taken back (a service that ended left it running) has its web gate again, at the port its process was started with,
     * which its folder keeps. Where it cannot be read, its web is closed until its next start, and the log says so.
     */
    private void webGateAgain(AccountStore.Library lib, ProcessHandle h) {
        if (webGates.containsKey(lib.id())) return;
        int port = 0;
        try { port = Integer.parseInt(Files.readString(LibraryPlaces.folder(lib).resolve(WEB_GATE_PORT)).strip()); } catch (IOException | NumberFormatException e) { /* said below */ }
        if (port <= 0) { LOG.warn("The library {} ({}) was taken back, but the port of its web gate could not be read from its folder; its web is closed until it is started again.", lib.title(), lib.id()); return; }
        try {
            Map<String, String> env = new HashMap<>(System.getenv());
            environment(env, LibraryPlaces.library(LibraryPlaces.folder(lib)), LibraryPlaces.home(LibraryPlaces.folder(lib)), lib.id());
            webGatePorts.put(lib.id(), openWebGate(lib.id(), port, WebGate.named(env)));
        } catch (IOException e) { LOG.warn("The web gate of the library {} ({}) could not be opened again at port {}: {}", lib.title(), lib.id(), port, e.getMessage()); }
    }
}
