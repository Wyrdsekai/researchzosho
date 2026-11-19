package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.Version;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The container form of the libraries (0.8.0, accounts_runner container), with a stand-in for the docker command: each library in a
 * container of its own that is given its own four folders and no other, its limits, no capabilities, a read-only root and the service's
 * network; started, stopped and removed by the name and labels the service gave it, and a container of that name that the service did
 * not make left alone. The node drives it as it drives the process form: asleep with nothing to do, started again by the next request.
 */
class ContainerLibrariesTest {
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    long lease = Node.LEASE_MS, renew = Node.RENEW_MS, soon = Node.DUE_SOON_MS;
    final ContainerLibraries.Docker docker = ContainerLibraries.DOCKER;
    final StandInDocker standIn = new StandInDocker();
    AccountStore store; AccountService svc; Path root; Node node; ContainerLibraries local;

    void start(Path tmp) throws Exception {
        ContainerLibraries.DOCKER = standIn;
        long ours = ((Number) Files.getAttribute(tmp, "unix:gid")).longValue();
        ContainerLibraries.GROUP = id -> ours;   // the stand-in runs the library as this process's own user and group, where Docker would give it the library's own group
        // asleep after 5 s with nothing to do: long enough that a loaded machine does not put a library to sleep between the
        // test's request and its look at the library's sockets (1.5 s did, once, with the machine at a load of 8)
        Node.LEASE_MS = 4_000; Node.RENEW_MS = 250; Node.SLEEP_AFTER_MS = 5_000L; Node.DUE_SOON_MS = 0;
        root = tmp.resolve("accounts");
        store = AccountStore.open(root.resolve("accounts.db").toString());
        Node[] n = new Node[1];
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new LibraryRunner() {
            @Override public String ensure(AccountStore.Library lib) throws IOException { return n[0].ensure(lib); }
            @Override public void lost(String id) { n[0].lost(id); }
            @Override public void stopAll() { n[0].stopAll(); }
            @Override public long epoch(String id) { return n[0].epoch(id); }
            @Override public boolean awake(String id) { return n[0].awake(id); }
            @Override public String ifAwake(AccountStore.Library lib) throws IOException { return n[0].ifAwake(lib); }
            @Override public void done(String id) { n[0].done(id); }
        });
        node = n[0] = new Node(store, svc.url(), local = new ContainerLibraries(store, svc.url()), "node_test", root);
        node.start();
    }

    @AfterEach void stop() {
        try { if (svc != null) svc.stop(); if (store != null) store.close(); }
        finally {
            standIn.endAll();
            ContainerLibraries.DOCKER = docker;
            ContainerLibraries.GROUP = ContainerLibraries::libraryGroup;
            Node.LEASE_MS = lease; Node.RENEW_MS = renew; Node.SLEEP_AFTER_MS = null; Node.DUE_SOON_MS = soon;
        }
    }

    HttpResponse<String> get(String path, String key) throws Exception { return c.send(HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString()); }

    static void until(String what, long ms, BooleanSupplier is) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!is.getAsBoolean()) { assertTrue(System.currentTimeMillis() < end, "within " + ms + " ms: " + what); Thread.sleep(50); }
    }

    /** The value that follows a flag in a command line, each time it is given. */
    static List<String> values(List<String> cmd, String flag) {
        List<String> v = new ArrayList<>();
        for (int i = 0; i + 1 < cmd.size(); i++) if (cmd.get(i).equals(flag)) v.add(cmd.get(i + 1));
        return v;
    }

    @Test
    void eachLibraryRunsInAContainerOfItsOwnThatSeesOnlyItsOwnFoldersAndSleepStopsAndRemovesIt(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        AccountService.Made bo = AccountService.newAccount(store, root, "Bo Example", "", false, "the test");
        String annKey = store.mintKey(ann.account().id(), ann.library().id(), "write", "the test").secret();
        String boKey = store.mintKey(bo.account().id(), bo.library().id(), "write", "the test").secret();
        HttpResponse<String> a = get("/v1/status", annKey), b = get("/v1/status", boKey);
        assertEquals(200, a.statusCode(), a.body()); assertEquals(200, b.statusCode(), b.body());
        assertTrue(a.body().contains(ann.library().id()) && b.body().contains(bo.library().id()), "each answered by its own library");

        assertTrue(standIn.ran.stream().noneMatch(c -> c.get(0).equals("network")), "no network is made: the containers have none " + standIn.ran);

        List<List<String>> runs = standIn.ran("run");
        assertEquals(2, runs.size(), runs.toString());
        for (AccountService.Made m : List.of(ann, bo)) {
            AccountStore.Library lib = m.library(), other = (m == ann ? bo : ann).library();
            List<String> run = runs.stream().filter(r -> values(r, "--name").equals(List.of("researchzosho-" + lib.id()))).findFirst().orElseThrow();
            Path folder = Path.of(lib.folder());
            // its own four folders and its sockets' folder, at the paths it sees, and nothing of the other library's or of the machine's
            Path sockets = ContainerLibraries.sockets(lib);
            assertEquals(List.of(folder.resolve("library") + ":/library", folder.resolve("home") + ":/config", folder.resolve("tmp") + ":/tmp", folder.resolve("user") + ":/home/library", sockets + ":/run/rz"), values(run, "--volume"));
            assertTrue(sockets.endsWith(lib.id()) && Files.exists(sockets.resolve("library.sock")) && Files.exists(sockets.resolve("web.sock")), "the library's socket and the node's gate");
            assertTrue(run.stream().noneMatch(w -> w.contains(other.id()) || w.contains(other.folder())), "nothing of the other library: " + run);
            // its labels, which are the proof it is this service's container of this library
            assertEquals(List.of(ContainerLibraries.SERVICE_LABEL + "=" + svc.url(), ContainerLibraries.LIBRARY_LABEL + "=" + lib.id(), ContainerLibraries.EPOCH_LABEL + "=1"), values(run, "--label"));
            // its walls and its limits
            assertTrue(run.containsAll(List.of("--rm", "--init", "--read-only")), run.toString());
            assertEquals(List.of("ALL"), values(run, "--cap-drop"));
            assertEquals(List.of("no-new-privileges"), values(run, "--security-opt"));
            assertEquals(List.of("1792m"), values(run, "--memory"), "the heap (1g) and room besides");
            assertEquals(List.of("1792m"), values(run, "--memory-swap"), "and no swap");
            assertEquals(List.of("2"), values(run, "--cpus"));
            assertEquals(List.of("1024"), values(run, "--pids-limit"));
            assertEquals(List.of(Files.getAttribute(folder, "unix:uid") + ":" + Files.getAttribute(folder, "unix:gid")), values(run, "--user"), "as the user who owns its folder");
            assertEquals(List.of("none"), values(run, "--network"), "no network at all");
            assertEquals(List.of(), values(run, "--publish"), "nothing published: the node's port passes to the library's socket");
            String address = store.library(lib.id()).address();
            int port = URI.create(address).getPort();
            assertTrue(port >= 4700, "a port of the node's table, on the loopback: " + address);
            // the image of the service's own version, and the library started as now, its service reached through the node
            int image = run.indexOf("ghcr.io/wyrdsekai/researchzosho:" + (Version.number() == null ? "latest" : Version.number()));
            assertTrue(image > 0, run.toString());
            List<String> program = run.subList(image + 1, run.size());
            assertEquals(List.of("serve", "--host", "127.0.0.1"), program.subList(0, 3), "its own loopback, which only its relays reach");
            assertEquals(List.of("/run/rz"), values(program, "--relays"));
            assertEquals(List.of(svc.url()), values(program, "--vouched-by"));
            assertEquals(List.of(lib.id()), values(program, "--library-id"));
            assertEquals(List.of("1"), values(program, "--epoch"));
            String at = values(program, "--service-at").get(0);
            assertTrue(at.matches("http://127\\.0\\.0\\.1:\\d+") && !at.equals(svc.url()), "the service through a relay of its own: " + at);
            // what it was given: its own paths, nothing of the store, and each loopback address put as the way to it
            StandInDocker.Box box = standIn.boxes.get("researchzosho-" + lib.id());
            Map<String, String> env = box.env;
            assertEquals("/library", env.get("RESEARCHZOSHO_LIBRARY")); assertEquals("/config", env.get(Config.HOME_NAME)); assertEquals("/config/config", env.get("RESEARCHZOSHO_CONFIG"));
            assertEquals("/home/library", env.get("HOME"));
            assertTrue(env.get("RESEARCHZOSHO_JAVA_OPTS").startsWith("-Xmx1g -Djava.io.tmpdir=/tmp -Duser.home=/home/library"), env.get("RESEARCHZOSHO_JAVA_OPTS"));
            // the web through the node's gate, for its Java and for every program it runs
            int gate = -1;
            for (String pair : env.get("RESEARCHZOSHO_RELAYS").split(",")) if (pair.endsWith("=web")) gate = Integer.parseInt(pair.substring(0, pair.indexOf('=')));
            assertTrue(gate > 0, env.get("RESEARCHZOSHO_RELAYS"));
            assertTrue(env.get("RESEARCHZOSHO_JAVA_OPTS").contains("-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=" + gate + " -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=" + gate), env.get("RESEARCHZOSHO_JAVA_OPTS"));
            assertEquals("http://127.0.0.1:" + gate, env.get("HTTPS_PROXY")); assertEquals("http://127.0.0.1:" + gate, env.get("http_proxy"));
            assertEquals("on", env.get("RESEARCHZOSHO_WEB_GATE"));
            assertTrue(env.keySet().stream().allMatch(k -> k.startsWith("RESEARCHZOSHO_") || k.startsWith("CODEZAIKU_") || k.equals("HOME") || k.equals("TZ") || k.toUpperCase().endsWith("_PROXY")), "nothing else of the service's environment: " + env.keySet());
            assertTrue(env.keySet().stream().noneMatch(k -> k.startsWith("PG") || k.startsWith("RESEARCHZOSHO_ACCOUNTS")), env.keySet().toString());
            assertTrue(env.values().stream().noneMatch(v -> v.contains(root.toString())), "nothing names the accounts' folder or the store: " + env);
            String offered = env.get("RESEARCHZOSHO_DRIVE_DEFAULT");   // the suite offers http://localhost:2 to every test
            assertTrue(offered != null && offered.matches("http://127\\.0\\.0\\.1:\\d+/lib/" + lib.id() + "/gate/http/localhost:2"), "the model through the node's gate, the gate through a relay of its own: " + offered);
            assertFalse(Files.exists(standIn.envFiles.get("researchzosho-" + lib.id())), "the settings file docker read is gone once the container has them");
        }

        // nothing to do: each is asleep, its container stopped and removed by the id its labels proved
        AccountStore.Library lib = ann.library();
        String id = standIn.boxes.get("researchzosho-" + lib.id()).id;
        until("asleep", 15_000, () -> !node.awake(lib.id()));
        until("its container gone", 10_000, () -> !standIn.boxes.containsKey("researchzosho-" + lib.id()));
        assertTrue(standIn.ran("stop").stream().anyMatch(c -> c.equals(List.of("stop", "--time", "30", id))), standIn.ran.toString());
        assertTrue(standIn.ran("rm").stream().anyMatch(c -> c.equals(List.of("rm", "--force", id))), standIn.ran.toString());
        assertEquals(0, store.placement(lib.id()).leaseUntil(), "its lease given back");
        Path socketsLeft = Path.of(System.getProperty("java.io.tmpdir"), "researchzosho-" + System.getProperty("user.name", "rz").replaceAll("[^A-Za-z0-9_.-]", "_"), lib.id());
        assertFalse(Files.exists(socketsLeft), "its sockets and their folder go with it");
        // the next request starts it again, the registry's second start
        HttpResponse<String> again = get("/v1/status", annKey);
        assertEquals(200, again.statusCode(), again.body());
        assertEquals(3, standIn.ran("run").size());
        List<String> second = standIn.ran("run").get(2);
        assertEquals(List.of("researchzosho-" + lib.id()), values(second, "--name"));
        assertEquals(List.of("2"), values(second, "--epoch"));

    }

    @Test
    void aContainerWithALibrarysNameThatThisServiceDidNotMakeIsLeftAlone(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        String key = store.mintKey(ann.account().id(), ann.library().id(), "write", "the test").secret();
        String name = "researchzosho-" + ann.library().id();
        for (Map<String, String> labels : List.of(Map.<String, String>of(), Map.of(ContainerLibraries.SERVICE_LABEL, "http://127.0.0.1:1", ContainerLibraries.LIBRARY_LABEL, ann.library().id()),
                Map.of(ContainerLibraries.SERVICE_LABEL, svc.url(), ContainerLibraries.LIBRARY_LABEL, "lib_another"))) {
            StandInDocker.Box theirs = standIn.put(name, labels, null);
            HttpResponse<String> r = get("/v1/status", key);
            assertEquals(503, r.statusCode(), r.body());
            assertTrue(r.body().contains("cannot be started now: a container that this service did not make has its name. Whoever runs the service has to look into it."), r.body());
            assertTrue(standIn.ran.stream().noneMatch(c -> c.contains(theirs.id) && (c.get(0).equals("stop") || c.get(0).equals("rm"))), "not stopped, not removed: " + standIn.ran);
            assertTrue(standIn.ran("run").isEmpty(), "and nothing started under its name");
            assertTrue(standIn.boxes.get(name) == theirs && theirs.up(), "it is there as it was");
            standIn.boxes.remove(name);
            node.lost(ann.library().id());
        }
    }

    @Test
    void anEarlierContainerOfTheLibraryByThisServiceIsStoppedAndRemovedBeforeItStarts(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        String key = store.mintKey(ann.account().id(), ann.library().id(), "write", "the test").secret();
        Process left = new ProcessBuilder("sleep", "600").start();   // what a node that ended left running, standing for the container's library
        StandInDocker.Box earlier = standIn.put("researchzosho-" + ann.library().id(), Map.of(ContainerLibraries.SERVICE_LABEL, svc.url(), ContainerLibraries.LIBRARY_LABEL, ann.library().id(), ContainerLibraries.EPOCH_LABEL, "1"), left);
        HttpResponse<String> r = get("/v1/status", key);
        assertEquals(200, r.statusCode(), r.body());
        int stop = standIn.ran.indexOf(List.of("stop", "--time", "30", earlier.id)), rm = standIn.ran.indexOf(List.of("rm", "--force", earlier.id)), run = standIn.ran.indexOf(standIn.ran("run").get(0));
        assertTrue(stop >= 0 && rm > stop && run > rm, "stopped, removed, then the new start: " + standIn.ran);
        assertFalse(left.isAlive());
    }

    @Test
    void anImageNotOnTheMachineIsFetchedFirst(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test");
        standIn.image = false;
        Config.set(ContainerLibraries.IMAGE, "registry.example.org/rz:test");
        try {
            local.start(ann.library(), 1);
            assertTrue(standIn.ran.contains(List.of("pull", "--quiet", "registry.example.org/rz:test")), standIn.ran.toString());
            assertTrue(standIn.ran("run").get(0).contains("registry.example.org/rz:test"));
        } finally { Config.set(ContainerLibraries.IMAGE, ""); }
    }

    @Test
    void theLimitsFollowTheSettings() throws Exception {
        assertEquals("1792m", ContainerLibraries.memory("1g"));
        assertEquals("1280m", ContainerLibraries.memory("512m"));
        assertEquals("2816m", ContainerLibraries.memory("2g"));
        assertEquals("2", ContainerLibraries.cpus());
        assertTrue(ContainerLibraries.image().startsWith("ghcr.io/wyrdsekai/researchzosho:"), ContainerLibraries.image());
        assertInstanceOf(ChildLibraries.class, LibraryRunner.Local.of(null, "http://127.0.0.1:1"), "the process form unless the setting says container");
        Config.set(LibraryRunner.FORM, "container"); Config.set(ContainerLibraries.CPUS, "1.5");
        try {
            assertInstanceOf(ContainerLibraries.class, LibraryRunner.Local.of(null, "http://127.0.0.1:1"));
            assertEquals("1.5", ContainerLibraries.cpus());
        } finally { Config.set(LibraryRunner.FORM, ""); Config.set(ContainerLibraries.CPUS, ""); }
    }

}
