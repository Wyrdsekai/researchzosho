package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.researchzosho.Config;
import org.researchzosho.Main;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Takeover (0.8.0): two nodes of their own (`researchzosho node`, each a process with its root, its ports and a machine name of its
 * own) on one store, the service in this process. A library runs on node A and translates a document of 141 units against a stand-in
 * model that answers as a model would and counts what it is asked; each node's libraries ask a stand-in of their own, so what each
 * asked is known. In the middle of the translation node A and its library are killed, as a machine that goes off: node B takes the
 * library over, from A's folder when B reaches that disk, and from its copy when the disk is lost, and the job goes on from what it
 * kept: no batch it had kept is asked again, and the translation is the same as one nobody stopped. Frozen instead (SIGSTOP), node A
 * and its library are let go after B took over: the library asks its model nothing more and exits, and node A starts nothing again.
 * Every process stopped or frozen here is one this test started, or a child of one, by its number; nothing is looked for by name.
 */
@EnabledOnOs(value = OS.LINUX, disabledReason = "the machines of the service (accounts node) run on Linux only in 0.8.0 and refuse to start elsewhere; one test reads /proc/<pid>/environ")
class TakeoverTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String BATCH = "You translate the numbered lines";
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    final List<Process> started = Collections.synchronizedList(new ArrayList<>());
    final List<Model> models = new ArrayList<>();
    long lease = Node.LEASE_MS, round = Scheduler.ROUND_MS;
    Long takeover = Nodes.TAKEOVER_AFTER_MS;
    AccountStore store; AccountService svc; Scheduler scheduler; Path tmp;

    /** A stand-in model: the translation tests' scripted stand-in, over HTTP. It counts every call, keeps every batch it was asked, and can hold one batch unanswered. */
    static final class Model implements AutoCloseable {
        final HttpServer s;
        final Researcher.Drive drive = TranslationTest.standIn(CheckpointTest.script(), "stand-in");
        final AtomicInteger calls = new AtomicInteger(), batchCalls = new AtomicInteger();
        final List<String> batches = Collections.synchronizedList(new ArrayList<>());
        /** The calls the scripted stand-in did not expect, with why: a probe, or a prompt of another shape. */
        final List<String> unexpected = Collections.synchronizedList(new ArrayList<>());
        volatile int holdAt = -1;
        volatile long heldSince;
        final CountDownLatch release = new CountDownLatch(1);

        Model() throws IOException {
            s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.setExecutor(Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "stand-in-model"); t.setDaemon(true); return t; }));
            s.createContext("/", this::handle);
            s.start();
        }
        String url() { return "http://127.0.0.1:" + s.getAddress().getPort(); }

        void handle(HttpExchange x) throws IOException {
            try (x) {
                byte[] in = x.getRequestBody().readAllBytes();
                String path = x.getRequestURI().getPath(), answer;
                if (path.endsWith("/models")) { send(x, "{\"object\":\"list\",\"data\":[{\"id\":\"stand-in\",\"object\":\"model\"}]}"); return; }
                calls.incrementAndGet();
                JsonNode body = M.readTree(in);
                ArrayNode messages = (ArrayNode) body.path("messages");
                String system = messages.isEmpty() ? "" : messages.get(0).path("content").asText("");
                if (system.startsWith(BATCH)) {
                    int n = batchCalls.incrementAndGet();
                    batches.add(messages.get(messages.size() - 1).path("content").asText(""));
                    if (n == holdAt) { heldSince = System.currentTimeMillis(); try { release.await(10, TimeUnit.MINUTES); } catch (InterruptedException e) { return; } }
                }
                if (system.startsWith(BATCH)) answer = lines(system, messages.get(messages.size() - 1).path("content").asText(""));
                else {
                    try { answer = drive.classify(messages, body.path("max_tokens").asInt(512)); } catch (AssertionError | RuntimeException other) { answer = "OK"; unexpected.add(system.substring(0, Math.min(120, system.length())) + " :: " + other.getMessage()); }
                }
                ObjectNode r = M.createObjectNode(); r.put("id", "stand-in"); r.put("object", "chat.completion"); r.put("model", "stand-in");
                ObjectNode ch = r.putArray("choices").addObject(); ch.put("index", 0); ch.putObject("message").put("role", "assistant").put("content", answer == null ? "" : answer); ch.put("finish_reason", "stop");
                r.putObject("usage").put("prompt_tokens", in.length / 4).put("completion_tokens", 50).put("total_tokens", in.length / 4 + 50);
                send(x, M.writeValueAsString(r));
            } catch (IOException gone) { /* the caller went away */ }
        }
        /**
         * A batch translated as the translation tests' script translates it ("訳 " before each line, the 7 lost), the glossary's terms
         * rendered as the prompt says. The script's own check of the fence is left out here: a fence's mark is drawn by each process,
         * and this stand-in runs in another process than the library that asks it.
         */
        static String lines(String system, String user) {
            Matcher terms = Pattern.compile("Render these terms as given, the same every time: (.*?)\\.$", Pattern.MULTILINE).matcher(system);
            List<String[]> render = new ArrayList<>();
            if (terms.find()) for (String pair : terms.group(1).split("; ")) { String[] q = pair.split(" → ", 2); if (q.length == 2) render.add(q); }
            StringBuilder o = new StringBuilder();
            for (String line : TranslationTest.inside(user).split("\n")) {
                Matcher mm = Pattern.compile("^(\\d+)\\. (.*)$").matcher(line);
                if (!mm.matches()) continue;
                String text = mm.group(2);
                for (String[] q : render) text = text.replace(q[0], q[1]);
                o.append(mm.group(1)).append(". ").append("訳 ").append(text.replace(" 7 ", " ")).append('\n');
            }
            return o.toString();
        }

        static void send(HttpExchange x, String json) throws IOException {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            x.sendResponseHeaders(200, b.length);
            try (OutputStream o = x.getResponseBody()) { o.write(b); }
        }
        @Override public void close() { release.countDown(); s.stop(0); }
    }

    /** A node of its own: this program's `node`, in a process this test starts, with its root, its ports, its machine's name and its model. */
    record NodeProc(Process p, Path root, String machine, Model model) {
        /** The node and its libraries, by the numbers of the process this test started and of its children. */
        List<ProcessHandle> tree() { List<ProcessHandle> t = new ArrayList<>(p.toHandle().descendants().toList()); t.add(p.toHandle()); return t; }
    }

    NodeProc node(String machine, int firstPort, Model model, Path copies, Map<String, String> more) throws IOException {
        Path root = Files.createDirectories(tmp.resolve(machine)), home = Files.createDirectories(tmp.resolve(machine + "-home"));
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElse("java"));
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) if (!a.startsWith("-Xmx") && !a.startsWith("-Duser.home=") && !a.startsWith("-javaagent") && !a.startsWith("-agentlib")) cmd.add(a);
        cmd.addAll(List.of("-Xmx512m", "-Duser.home=" + home, "-Dresearchzosho.accounts.machine=" + machine, "-Dresearchzosho.accounts.lease_ms=4000", "-Dresearchzosho.accounts.renew_ms=250",
                "-Dresearchzosho.accounts.sleep_after_ms=600000", "-Dresearchzosho.accounts.copy_after_ms=500"));
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), Main.class.getName(), "node"));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("RESEARCHZOSHO_") && !k.equals("RESEARCHZOSHO_CTX"));
        env.put("RESEARCHZOSHO_HOME", home.toString());
        env.put(AccountService.STORE, tmp.resolve("accounts.db").toString());
        env.put(AccountService.SERVICE, svc.url());
        env.put(AccountService.ROOT, root.toString());
        env.put(AccountService.FIRST_PORT, String.valueOf(firstPort));
        if (copies != null) env.put(LibraryPlaces.COPIES, copies.toString());
        env.put(Scheduler.NIGHTS, "off");
        env.put("RESEARCHZOSHO_DRIVE", model.url()); env.put("RESEARCHZOSHO_MODEL", "stand-in");
        env.put("RESEARCHZOSHO_LOCAL_HELPERS", "off"); env.put("RESEARCHZOSHO_EMBED", "off"); env.put("RESEARCHZOSHO_UPDATE", "off"); env.put("RESEARCHZOSHO_FALLBACK_SEARCH", "off"); env.put("RESEARCHZOSHO_SEARXNG", "http://127.0.0.1:2");
        env.putAll(more);
        pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(tmp.resolve(machine + ".log").toFile())).redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process p = pb.start();
        started.add(p);
        until(machine + " has written itself into the store", 60_000, () -> { try { return store.nodes().stream().anyMatch(n -> n.root().equals(root.toString()) && !n.address().isEmpty()); } catch (IOException e) { return false; } });
        return new NodeProc(p, root, machine, model);
    }

    void start(Path dir) throws Exception {
        tmp = dir;
        Node.LEASE_MS = 4_000; Scheduler.ROUND_MS = 500; Nodes.TAKEOVER_AFTER_MS = 0L;
        Config.set(Scheduler.NIGHTS, "off");
        store = AccountStore.open(tmp.resolve("accounts.db").toString());
        store.setCap("*", Caps.JOBS_AT_ONCE, -1);   // what is taken over here is the jobs, not the caps (stage 3)
        Nodes[] nodes = new Nodes[1];
        svc = AccountService.start(store, "127.0.0.1", 0, issuer -> nodes[0] = new Nodes(store, issuer, null));   // the service runs no node: the libraries run on the two of their own
        scheduler = new Scheduler(store, nodes[0], "the test's service", ZonedDateTime::now);
        scheduler.start();
    }

    @AfterEach void stop() throws Exception {
        try {
            for (Process p : started) { for (ProcessHandle h : p.toHandle().descendants().toList()) { h.destroyForcibly(); } p.destroyForcibly(); p.waitFor(30, TimeUnit.SECONDS); }   // only what this test started, and its children
            for (Model m : models) m.close();
            if (scheduler != null) scheduler.stop();
            if (svc != null) svc.stop();
            if (store != null) store.close();
        } finally { Node.LEASE_MS = lease; Scheduler.ROUND_MS = round; Nodes.TAKEOVER_AFTER_MS = takeover; Config.set(Scheduler.NIGHTS, ""); Config.set(LibraryPlaces.COPIES, ""); }
    }

    static void until(String what, long ms, BooleanSupplier is) {
        long end = System.currentTimeMillis() + ms;
        while (!is.getAsBoolean()) { if (System.currentTimeMillis() > end) throw new AssertionError("not within " + ms + " ms: " + what); try { Thread.sleep(100); } catch (InterruptedException e) { throw new AssertionError(e); } }
    }

    Model model() throws IOException { Model m = new Model(); models.add(m); return m; }

    HttpResponse<String> get(String key, String path) throws Exception { return c.send(HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString()); }
    HttpResponse<String> post(String key, String path, String json) throws Exception { return c.send(HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Authorization", "Bearer " + key).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString()); }

    /** A library under a root, kept by a new account, with a key that writes, and the long document on its shelves (added through the service, as a person adds a file). */
    record Lib(AccountStore.Library lib, String key, String raw) { }

    Lib library(String name, Path root) throws Exception {
        AccountService.Made m = AccountService.newAccount(store, root, name, "", false, "the test");
        String key = store.mintKey(m.account().id(), m.library().id(), "write", "the test").secret();
        Path doc = CheckpointTest.longDoc(Files.createDirectories(tmp.resolve("docs-" + m.library().name())));
        String b = "----rz" + System.nanoTime();
        byte[] body = ("--" + b + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"gears.md\"\r\nContent-Type: text/markdown\r\n\r\n" + Files.readString(doc) + "\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> added = c.send(HttpRequest.newBuilder(URI.create(svc.url() + "/add")).header("Authorization", "Bearer " + key).header("Content-Type", "multipart/form-data; boundary=" + b).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, added.statusCode(), added.body());
        AccountStore.Library now = store.library(m.library().id());
        String raw;
        try (Stream<Path> s = Files.list(LibraryPlaces.library(now).resolve("raw"))) { raw = s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".md")).findFirst().orElseThrow(); }
        return new Lib(now, key, raw);
    }

    String translate(Lib l) throws Exception {
        HttpResponse<String> r = post(l.key(), "/v1/translate", "{\"raw\":\"" + l.raw() + "\",\"to\":\"ja\",\"from\":\"en\",\"quality\":\"quick\"}");
        assertEquals(200, r.statusCode(), r.body());
        return M.readTree(r.body()).path("job_id").asText();
    }

    JsonNode job(Lib l, String id) { try { HttpResponse<String> r = get(l.key(), "/v1/jobs/" + id); JsonNode j = M.readTree(r.body()); return j.has("job") ? j.get("job") : j; } catch (Exception e) { return M.createObjectNode(); } }

    JsonNode done(Lib l, String id, long ms) {
        JsonNode[] last = {null};
        until("the translation " + id + " ends", ms, () -> { last[0] = job(l, id); String st = last[0].path("state").asText(); return st.equals("done") || st.equals("failed") || st.equals("stopped"); });
        assertEquals("done", last[0].path("state").asText(), last[0].toString());
        return last[0];
    }

    /** The translated document the library wrote: the file of its folder that holds the stand-in's translation of the heading. */
    static String translated(AccountStore.Library lib) throws IOException {
        try (Stream<Path> s = Files.walk(LibraryPlaces.library(lib))) {
            for (Path p : s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".md") && !p.toString().contains("/raw/")).toList()) {
                String t = Files.readString(p);
                if (t.contains("訳 Paragraph 140")) return t;
            }
        }
        return null;
    }

    /** The reference translation and the batches it asked: the same in every test (one document, one stand-in), so made once for the class. */
    static String referenceDoc;
    static int referenceBatches;

    /**
     * The reference: the same document translated on node B by a library nobody stops, made by the first test that needs it and kept
     * for the next; {@link #referenceBatches} is how many batches it asked. Node B's model is left as it was before the reference: none
     * of its batches counted, none kept.
     */
    String reference(NodeProc b) throws Exception {
        if (referenceDoc == null) {
            Lib ref = library("Reference Example", b.root());
            String id = translate(ref);
            done(ref, id, 120_000);
            String doc = translated(store.library(ref.lib().id()));
            assertNotNull(doc, "the reference's translation is written");
            referenceBatches = b.model().batchCalls.get();
            referenceDoc = doc;
        }
        b.model().batchCalls.set(0);
        b.model().batches.clear();
        return referenceDoc;
    }

    /** The library on node A, its translation held at its fourth batch, and what A had asked by then. */
    record Interrupted(Lib lib, String job, List<String> keptOnA) { }

    Interrupted interrupt(NodeProc a) throws Exception {
        a.model().holdAt = 4;
        Lib ann = library("Ann Example", a.root());
        String id = translate(ann);
        until("the fourth batch is asked on node A, and held", 120_000, () -> a.model().heldSince > 0);
        assertEquals("node_" , store.placement(ann.lib().id()).node().substring(0, 5));
        List<String> kept = new ArrayList<>(a.model().batches.subList(0, 3));   // answered before the hold: kept by the job's checkpoint
        return new Interrupted(ann, id, kept);
    }

    void killed(NodeProc a) throws Exception {
        List<ProcessHandle> tree = a.tree();   // the node this test started, and its children (its library), by their numbers
        assertTrue(tree.size() >= 2, "the node and its library: " + tree);
        for (ProcessHandle h : tree) h.destroyForcibly();
        for (ProcessHandle h : tree) h.onExit().get(30, TimeUnit.SECONDS);
    }

    void resumedOnB(Interrupted i, NodeProc b, String reference, int whole) throws Exception {
        JsonNode j = done(i.lib(), i.job(), 180_000);
        assertTrue(j.path("resumed").asText().startsWith("Resumed after "), "the job says it went on from what it kept: " + j);
        AccountStore.Library now = store.library(i.lib().lib().id());
        assertEquals(reference, translated(now), "the same translation as the one nobody stopped");
        for (String k : i.keptOnA()) assertFalse(b.model().batches.contains(k), "a batch kept before the machine went off is not asked again");
        assertEquals(whole - 3, b.model().batchCalls.get(), "node B asked the batches from the one in flight on, and no other");
    }

    @Test
    void aMachineThatGoesOffInTheMiddleOfATranslationIsTakenOverFromItsFolder(@TempDir Path dir) throws Exception {
        start(dir);
        NodeProc a = node("machine-a", 47410, model(), null, Map.of()), b = node("machine-b", 47430, model(), null, Map.of());
        String reference = reference(b);
        int whole = referenceBatches;
        assertTrue(whole >= 5, "the document is translated in batches: " + whole);
        Interrupted i = interrupt(a);
        String nodeA = store.placement(i.lib().lib().id()).node();
        killed(a);
        until("taken over by node B", 60_000, () -> { try { AccountStore.Placement p = store.placement(i.lib().lib().id()); return !p.node().equals(nodeA) && p.leaseUntil() > store.clock(); } catch (IOException e) { return false; } });
        assertEquals(LibraryPlaces.folder(i.lib().lib()).toString(), store.library(i.lib().lib().id()).folder(), "from A's folder, which B reaches: the same folder");
        resumedOnB(i, b, reference, whole);
    }

    @Test
    void aMachineWhoseDiskIsLostIsTakenOverFromTheCopy(@TempDir Path dir) throws Exception {
        start(dir);
        Path copies = Files.createDirectories(dir.resolve("copies"));
        Config.set(LibraryPlaces.COPIES, copies.toString());   // the service reads the copies too: it decides a takeover from one
        NodeProc a = node("machine-a", 47450, model(), copies, Map.of()), b = node("machine-b", 47470, model(), copies, Map.of());
        String reference = reference(b);
        int whole = referenceBatches;
        Interrupted i = interrupt(a);
        String id = i.lib().lib().id();
        long held = a.model().heldSince;
        until("a copy made while the fourth batch is held, so it holds the three kept", 30_000, () -> { try { LibraryPlaces.Copy cp = LibraryPlaces.lastCopy(id); return cp != null && cp.began() > held + 100 && cp.ended() > 0; } catch (IOException e) { return false; } });
        killed(a);
        Path lost = a.root().resolveSibling("machine-a-lost");
        Files.move(a.root(), lost);   // its disk goes with it
        until("taken over by node B from the copy", 60_000, () -> { try { AccountStore.Library l = store.library(id); return l.folder().startsWith(b.root().toString()) && store.placement(id).leaseUntil() > store.clock(); } catch (IOException e) { return false; } });
        resumedOnB(i, b, reference, whole);
        assertFalse(Files.exists(a.root()), "nothing was written where the lost disk was: the job's paths name its new folder");
        assertNotNull(LibraryPlaces.broughtBack(new LibraryStore(LibraryPlaces.library(store.library(id)))), "and the library says it was brought back from its copy");
        // asked until the library answers with it: straight after the takeover the service may still be starting the library on node B,
        // and one look then got an answer with no note in it (a four-core runner, once)
        until("the library's status says it was brought back from its copy", 30_000, () -> {
            try { return M.readTree(get(i.lib().key(), "/v1/status").body()).path("brought_back").path("note").asText().startsWith("This library was brought back from its copy of "); }
            catch (Exception notYet) { return false; }
        });
    }

    @Test
    void aFrozenMachineLetGoAfterTheTakeoverAsksNothingMoreAndItsLibraryEnds(@TempDir Path dir) throws Exception {
        start(dir);
        NodeProc a = node("machine-a", 47490, model(), null, Map.of()), b = node("machine-b", 47510, model(), null, Map.of());
        Interrupted i = interrupt(a);
        String id = i.lib().lib().id(), nodeA = store.placement(id).node();
        List<ProcessHandle> tree = a.tree();   // node A and its library, by the numbers this test started
        ProcessHandle library = tree.stream().filter(h -> h.pid() != a.p().pid()).findFirst().orElseThrow();
        signal("-STOP", tree);
        until("taken over by node B", 60_000, () -> { try { AccountStore.Placement p = store.placement(id); return !p.node().equals(nodeA) && p.leaseUntil() > store.clock(); } catch (IOException e) { return false; } });
        until("node B's library is translating", 60_000, () -> b.model().batchCalls.get() > 0);
        int askedOnA = a.model().calls.get();
        signal("-CONT", tree);   // let go
        a.model().release.countDown();   // and the batch it was waiting for is answered: it may go on from there, and must not
        until("the library of node A ends", 60_000, () -> !library.isAlive());
        Thread.sleep(2_000);
        assertEquals(askedOnA, a.model().calls.get(), "let go, it asked its model nothing more");
        assertTrue(a.p().isAlive(), "node A goes on");
        assertTrue(b.model().batchCalls.get() > 0);
        done(i.lib(), i.job(), 180_000);
        assertNotEquals(nodeA, store.placement(id).node(), "node A started nothing of it again");
        assertTrue(a.p().toHandle().descendants().noneMatch(ProcessHandle::isAlive), "node A runs no library");
    }

    /**
     * The store's address and its password never reach a library's process: not in its environment, not on its command line. A node
     * holds them; what it hands its libraries is built by name (0.8.0). Read from the library's own process, as the system shows it.
     */
    @Test
    void theStoresAddressAndItsPasswordNeverReachALibrary(@TempDir Path dir) throws Exception {
        start(dir);
        String storeWord = "a-made-up-store-word";
        NodeProc a = node("machine-a", 47530, model(), null, Map.of("PGPASSWORD", storeWord, "PGHOST", "db.internal"));
        Lib ann = library("Ann Example", a.root());
        assertEquals(200, get(ann.key(), "/v1/status").statusCode());
        List<ProcessHandle> libraries = a.p().toHandle().children().toList();   // the node's children: its library
        assertEquals(1, libraries.size());
        long pid = libraries.get(0).pid();
        String environment = new String(Files.readAllBytes(Path.of("/proc", String.valueOf(pid), "environ")), StandardCharsets.UTF_8).replace('\0', '\n');
        String commandLine = new String(Files.readAllBytes(Path.of("/proc", String.valueOf(pid), "cmdline")), StandardCharsets.UTF_8).replace('\0', ' ');
        String nodeEnvironment = new String(Files.readAllBytes(Path.of("/proc", String.valueOf(a.p().pid()), "environ")), StandardCharsets.UTF_8);
        String storeAt = tmp.resolve("accounts.db").toString();
        assertTrue(nodeEnvironment.contains(storeAt) && nodeEnvironment.contains(storeWord), "the instrument: the node's own environment holds both");
        for (String what : new String[]{storeAt, storeWord, "db.internal", AccountService.STORE})
            assertFalse(environment.contains(what) || commandLine.contains(what), what + " reached the library's process");
        assertTrue(commandLine.contains("--vouched-by " + svc.url()), "what it is given is there: " + commandLine);
        // and an address with its password in it, as a PostgreSQL store is named, is not handed down either
        Config.set(AccountService.STORE, "postgresql://rz:" + storeWord + "@db.internal:5432/rz");
        try {
            Map<String, String> env = new HashMap<>(System.getenv());
            new ChildLibraries(store, svc.url()).environment(env, tmp.resolve("lib"), tmp.resolve("home"));
            for (Map.Entry<String, String> e : env.entrySet()) assertFalse(e.getValue().contains(storeWord) || e.getValue().contains("db.internal"), e.getKey());
        } finally { Config.set(AccountService.STORE, ""); }
    }

    /** A signal to processes this test started, by their numbers. */
    static void signal(String which, List<ProcessHandle> to) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("kill", which));
        for (ProcessHandle h : to) cmd.add(String.valueOf(h.pid()));
        assertEquals(0, new ProcessBuilder(cmd).start().waitFor());
    }
}
