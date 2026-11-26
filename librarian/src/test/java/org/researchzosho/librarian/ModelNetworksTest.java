package org.researchzosho.librarian;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model server's network with no way out asks nothing of the library. A model's container on such a network still reaches this machine
 * at the network's own address, and a library that listens beyond the loopback and is left open answered it at every door (checked on
 * 2026-11-25 from a container on an internal network: pages, the JSON API, a setting changed, MCP's tools). Now every request from a
 * network a model plan of this machine records is refused at every door, whatever the reader list says; a request from anywhere else is
 * answered as before. The doors are the real ones over the loopback: the plan of the test's home records a range that holds the loopback,
 * so the test's own requests stand where the model's container stands.
 */
class ModelNetworksTest {
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    String realHome;
    @TempDir Path home;

    @BeforeEach void ownHome() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Files.createDirectories(home.resolve(".researchzosho"));   // this program's own home, so CodeZaiku's folder below is another one
        Config.invalidate();
        ModelNetworks.forget();
    }

    @AfterEach void back() {
        Proxy.OVERRIDE = null;
        System.setProperty("user.home", realHome);
        Config.invalidate();
        ModelNetworks.forget();
    }

    Path ours() { return home.resolve(".researchzosho").resolve("model"); }
    Path codeZaikus() { return home.resolve(".codezaiku").resolve("model"); }

    static void write(Path folder, String name, String text) throws Exception { Files.createDirectories(folder); Files.writeString(folder.resolve(name), text); }

    static boolean inside(String address) throws Exception { return ModelNetworks.inside(InetAddress.getByName(address), ModelNetworks.ranges()); }

    @Test
    void theNetworksAreTheOnesTheModelPlansOfThisMachineRecord() throws Exception {
        assertTrue(ModelNetworks.ranges().isEmpty(), "no model folder: nothing recorded");
        assertFalse(inside("172.16.211.2"));
        // this program's plan, with no way out
        write(ours(), "plan.properties", "name=qwen3.8-27b\nnetwork=shut\nsubnet=172.16.211.0/28\nnetwork_tried=2026-11-25\n");
        ModelNetworks.forget();
        assertTrue(inside("172.16.211.2") && inside("172.16.211.3") && inside("::ffff:172.16.211.2"), "the model, the embeddings server beside it, and the same address in IPv6 form");
        assertFalse(inside("172.16.211.16") || inside("10.1.2.3") || inside("192.168.1.20") || inside("127.0.0.1") || inside("::1"), "an ordinary private address, the loopback: not the model's network");
        // CodeZaiku's folder: its start script makes its network with --internal
        write(codeZaikus(), "run.sh", "#!/bin/sh\ndocker network inspect codezaiku-model-shut >/dev/null 2>&1 || docker network create --internal --subnet 172.16.221.0/28 codezaiku-model-shut >/dev/null 2>&1\nexec docker run --rm --name codezaiku-model --network codezaiku-model-shut --ip 172.16.221.2 --cap-drop NET_RAW image\n");
        ModelNetworks.forget();
        assertTrue(inside("172.16.221.2") && inside("172.16.211.2"), "both programs' networks");
        // a plan that says the server can reach the network records none, and a range of a whole private block is not a network of a model plan
        write(ours(), "plan.properties", "name=qwen3.8-27b\nnetwork=open\nsubnet=172.16.211.0/28\nnetwork_why=asked\n");
        write(codeZaikus(), "run.sh", "#!/bin/sh\ndocker network create --internal --subnet 10.0.0.0/8 codezaiku-model-shut\nexec docker run --rm --name codezaiku-model image\n");
        ModelNetworks.forget();
        assertTrue(ModelNetworks.ranges().isEmpty(), "nothing recorded: " + ModelNetworks.ranges().size());
        // this program's embeddings server on a network of its own beside another program's model (a shared home): its script records it
        write(ours(), "run-embed.sh", "#!/bin/sh\ndocker network inspect researchzosho-model-shut >/dev/null 2>&1 || docker network create --internal --subnet 172.16.211.0/28 researchzosho-model-shut >/dev/null 2>&1\nexec docker run --rm --name researchzosho-model-embed --network researchzosho-model-shut --ip 172.16.211.3 --cap-drop NET_RAW image\n");
        ModelNetworks.forget();
        assertTrue(inside("172.16.211.3"));
    }

    record Answer(int status, String body) { }

    Answer ask(String base, String method, String path, String body, String forwardedFor) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        if (forwardedFor != null) b.header("X-Forwarded-For", forwardedFor);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = c.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Answer(r.statusCode(), r.body());
    }

    static final List<String[]> DOORS = List.of(
            new String[]{"GET", "/healthz", null}, new String[]{"GET", "/", null}, new String[]{"GET", "/search?q=test", null}, new String[]{"GET", "/v1/status", null},
            new String[]{"POST", "/v1/search", "{\"query\":\"test\"}"}, new String[]{"POST", "/v1/questions", "{\"text\":\"Is this filed?\"}"},
            new String[]{"POST", "/rpc", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"});

    @Test
    void aRequestFromARecordedNetworkIsRefusedAtEveryDoorWhateverTheReaderListSaysAndWrittenDownOnce(@TempDir Path tmp) throws Exception {
        write(ours(), "plan.properties", "name=qwen3.8-27b\nnetwork=shut\nsubnet=127.0.0.0/28\nnetwork_tried=2026-11-25\n");   // the loopback stands where the model's container stands
        Logger logger = (Logger) LoggerFactory.getLogger(ModelNetworks.class);
        ListAppender<ILoggingEvent> heard = new ListAppender<>();
        heard.start();
        logger.addAppender(heard);
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        assertEquals(Patrons.Level.write, Patrons.load(store).dflt(), "the library is open as shipped: anyone may write");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        HelperServer front = HelperServer.serve("127.0.0.1", 0, null, Map.of("stand-in", "http://127.0.0.1:1"), new ArrayList<>());
        AccountStore accounts = null; AccountService service = null;
        try {
            String base = "http://127.0.0.1:" + d.port();
            for (String[] door : DOORS) {
                Answer a = ask(base, door[0], door[1], door[2], null);
                assertEquals(403, a.status(), door[0] + " " + door[1] + ": " + a.body());
                assertTrue(a.body().contains(ModelNetworks.REFUSED), a.body());
            }
            Answer f = ask("http://127.0.0.1:" + front.port(), "GET", "/health", null, null);
            assertTrue(f.status() == 403 && f.body().contains(ModelNetworks.REFUSED), "the helpers' front, which beyond the loopback asks a secret: " + f);
            Config.set(AccountService.ROOT, tmp.resolve("accounts").toString());
            accounts = AccountStore.open(AccountService.root().resolve("accounts.db").toString());
            service = AccountService.start(accounts, "127.0.0.1", 0, self -> new AccountServiceTest.Here(self));
            for (String path : List.of("/healthz", "/login", "/v1/status")) {
                Answer a = ask("http://127.0.0.1:" + service.port(), "GET", path, null, null);
                assertTrue(a.status() == 403 && a.body().contains(ModelNetworks.REFUSED), "the account service, " + path + ": " + a);
            }
            long lines = heard.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
            assertEquals(1, lines, "written down once, however many times it asks: " + heard.list);
            // a plan whose network is another range: the loopback is answered as before, at each door
            write(ours(), "plan.properties", "name=qwen3.8-27b\nnetwork=shut\nsubnet=172.16.211.0/28\nnetwork_tried=2026-11-25\n");
            ModelNetworks.forget();
            assertEquals(200, ask(base, "GET", "/healthz", null, null).status());
            assertEquals(200, ask(base, "GET", "/v1/status", null, null).status());
            assertEquals(200, ask(base, "POST", "/v1/search", "{\"query\":\"test\"}", null).status());
            assertEquals(200, ask("http://127.0.0.1:" + front.port(), "GET", "/health", null, null).status());
            assertEquals(200, ask("http://127.0.0.1:" + service.port(), "GET", "/healthz", null, null).status());
            // and with no plan at all, as before too
            Files.delete(ours().resolve("plan.properties"));
            ModelNetworks.forget();
            assertEquals(200, ask(base, "GET", "/v1/status", null, null).status());
        } finally {
            logger.detachAppender(heard);
            if (service != null) service.stop();
            if (accounts != null) accounts.close();
            Config.set(AccountService.ROOT, "");
            front.close();
            d.stop();
        }
    }

    @Test
    void aForwardedAddressCountsOnlyWhenATrustedProxySaysIt(@TempDir Path tmp) throws Exception {
        write(ours(), "plan.properties", "name=qwen3.8-27b\nnetwork=shut\nsubnet=172.16.211.0/28\nnetwork_tried=2026-11-25\n");
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        String base = "http://127.0.0.1:" + d.port();
        try {
            Proxy.OVERRIDE = "";
            assertEquals(200, ask(base, "GET", "/v1/status", null, "172.16.211.2").status(), "a header from no trusted proxy changes nothing");
            assertEquals(200, ask(base, "GET", "/v1/status", null, "10.0.0.5").status());
            // the loopback is a proxy the setting names: what it says it forwards for is tested too
            Proxy.OVERRIDE = "127.0.0.1/32";
            Answer a = ask(base, "GET", "/v1/status", null, "172.16.211.2");
            assertTrue(a.status() == 403 && a.body().contains(ModelNetworks.REFUSED), "a trusted proxy that forwards for the model's network: " + a);
            assertEquals(200, ask(base, "GET", "/v1/status", null, "10.0.0.5").status(), "one that forwards for an ordinary address: as before");
            // the connection's own address inside the range is refused whatever a header says, trusted or not
            write(ours(), "plan.properties", "name=qwen3.8-27b\nnetwork=shut\nsubnet=127.0.0.0/28\nnetwork_tried=2026-11-25\n");
            ModelNetworks.forget();
            assertEquals(403, ask(base, "GET", "/v1/status", null, "10.0.0.5").status());
            Proxy.OVERRIDE = "";
            assertEquals(403, ask(base, "GET", "/v1/status", null, "10.0.0.5").status());
        } finally { Proxy.OVERRIDE = null; d.stop(); }
    }
}
