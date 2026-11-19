package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Setting up a public service and moving it to another version (0.8.0, stage 4, step 5), without Docker: what the setup writes (the
 * compose files, the password made for the database, Caddy's name, the proxy to Docker that alone holds Docker's socket, the service's
 * settings, every one a setting the program has), what it refuses, the second machine's file; the update, with a stand-in for the
 * docker command; and a node making a new library on its own disk when the service's machine runs none. The real run of these files
 * on Docker is the stage's own, recorded in its notes.
 */
class DeploymentTest {
    final List<String> set = new ArrayList<>();
    Deployment.Shell shell = Deployment.SHELL;

    @AfterEach void back() throws IOException { Deployment.SHELL = shell; for (String k : set) Config.set(k, ""); }

    record Ran(int code, String out, String err) { }

    static Ran run(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        String[] all = new String[args.length + 2]; all[0] = "researchzosho"; all[1] = "accounts"; System.arraycopy(args, 0, all, 2, args.length);
        int code = AccountsCli.run(all, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Ran(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    static Map<String, String> env(Path f) throws IOException {
        Map<String, String> m = new HashMap<>();
        for (String l : Files.readAllLines(f.resolve(".env"))) if (!l.startsWith("#") && l.contains("=")) m.put(l.substring(0, l.indexOf('=')), l.substring(l.indexOf('=') + 1));
        return m;
    }

    /** Each line of a settings file the setup wrote names a setting the program has. */
    static void everyLineASetting(Path config) throws IOException {
        for (String l : Files.readAllLines(config)) {
            if (l.isBlank() || l.startsWith("#")) continue;
            String key = l.substring(0, l.indexOf('=')).strip();
            assertNotNull(Settings.named(key), key + " in " + config.getFileName() + " is a setting the program has");
        }
    }

    /** The lines of a service's block in a compose file. */
    static String block(String compose, String service) {
        int a = compose.indexOf("\n  " + service + ":\n");
        int z = compose.indexOf("\n  ", a + 3 + service.length());
        while (z > 0 && compose.charAt(z + 3) == ' ') z = compose.indexOf("\n  ", z + 1);
        return compose.substring(a, z < 0 ? compose.length() : z);
    }

    @Test
    void theSetupWritesAFolderComposeRunsWithThePublicSettingsAndAPasswordOfItsOwn(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("rz-public");
        Ran r = run("setup", "public", "--domain", "research.example.org", "--folder", f.toString(), "--signup", "domain");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("cd " + f + " && docker compose up -d") && r.out().contains("docker compose exec service researchzosho accounts add \"<your name>\" --admin")
                && r.out().contains("on a socket only your user opens; the proxy has no network") && r.out().contains("Making a container is as strong as root on this machine"), r.out());
        Map<String, String> env = env(f);
        assertTrue(env.get("POSTGRES_PASSWORD").matches("[A-Za-z0-9_-]{32}"), "a password of its own");
        assertEquals("research.example.org", env.get("RZ_DOMAIN"));
        assertEquals(Deployment.publishedImage(), env.get("RZ_IMAGE"));
        assertEquals(f.resolve("data").toString(), env.get("RZ_DATA"));
        assertEquals(String.valueOf(Files.getAttribute(f, "unix:uid")), env.get("RZ_UID"));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.resolve(".env"))), "the password's file is its owner's alone");
        String compose = Files.readString(f.resolve("compose.yml"));
        assertFalse(compose.contains(env.get("POSTGRES_PASSWORD")), "the password stays in .env");
        assertEquals(1, compose.split("/var/run/docker.sock:/var/run/docker.sock:ro", -1).length - 1, "Docker's socket, mounted once");
        assertEquals(2, compose.split("/var/run/docker.sock", -1).length - 1, "and nowhere else");
        String proxy = block(compose, "docker"), service = block(compose, "service"), db = block(compose, "db"), caddy = block(compose, "caddy");
        assertTrue(proxy.contains("/var/run/docker.sock:/var/run/docker.sock:ro") && proxy.contains("CONTAINERS: 1") && proxy.contains("IMAGES: 1") && proxy.contains("POST: 1") && !proxy.contains("ports:"), proxy);
        assertTrue(service.contains("DOCKER_HOST: unix://" + Deployment.DOCKER_AT + "/docker.sock") && service.contains("user: \"${RZ_UID}:${RZ_GID}\"") && service.contains("${RZ_DATA}:${RZ_DATA}")
                && service.contains("${RZ_DATA}/sockets:" + Deployment.SOCKETS_AT) && service.contains("postgresql://zosho:${POSTGRES_PASSWORD}@db:5432/zosho") && !service.contains("ports:") && !service.contains("/var/run/docker.sock"), service);
        assertTrue(db.contains(Deployment.POSTGRES) && db.contains("pg_isready") && !db.contains("ports:"), "the database has no port on the machine: " + db);
        assertTrue(caddy.contains("\"${RZ_HTTP_PORT}:80\"") && caddy.contains("\"${RZ_HTTPS_PORT}:443\""), caddy);
        assertEquals("80", env.get("RZ_HTTP_PORT")); assertEquals("443", env.get("RZ_HTTPS_PORT"));
        assertTrue(Files.readString(f.resolve("Caddyfile")).contains("{$RZ_DOMAIN} {\n\treverse_proxy service:4649\n}"));
        Path config = f.resolve("data").resolve("config");
        String settings = Files.readString(config);
        for (String line : List.of("accounts = on", "accounts.address = https://research.example.org", "accounts.service = http://service:4649", "accounts.runner = container",
                "accounts.signup = domain", "accounts.sockets = " + Deployment.SOCKETS_AT, "accounts.sockets.host = " + f.resolve("data").resolve("sockets"),
                "accounts.root = " + f.resolve("data").resolve("accounts"), "accounts.keys.file = " + f.resolve("data").resolve("keys").resolve("own-keys.key"), "trusted.proxy = "))
            assertTrue(settings.contains(line), line + " in " + settings);
        assertFalse(settings.contains("accounts.node") || settings.contains("accounts.image"), "one machine runs the libraries itself, from the published image");
        everyLineASetting(config);
        for (String sub : List.of("accounts", "home", "tmp", "keys", "sockets")) assertTrue(Files.isDirectory(f.resolve("data").resolve(sub)), sub);
        assertFalse(Files.exists(f.resolve("compose.node.yml")));

        // a second setup: another password; and what is refused, writing nothing
        Path g = tmp.resolve("rz-other");
        assertEquals(0, run("setup", "public", "--domain", "research.example.org", "--folder", g.toString()).code());
        assertNotEquals(env.get("POSTGRES_PASSWORD"), env(g).get("POSTGRES_PASSWORD"));
        Ran there = run("setup", "public", "--domain", "research.example.org", "--folder", f.toString());
        assertEquals(1, there.code()); assertTrue(there.err().contains("There is something at " + f + " already; nothing was written."));
        assertTrue(run("setup", "public", "--domain", "not a name", "--folder", tmp.resolve("x").toString()).err().contains("is not a name a service is reached at"));
        assertTrue(run("setup", "public", "--folder", tmp.resolve("x").toString()).err().contains("Say the name people reach the service at"));
        assertTrue(run("setup", "public", "--domain", "research.example.org", "--nodes", "2", "--folder", tmp.resolve("x").toString()).err().contains("name both machines' addresses"));
        assertEquals(2, run("setup", "private", "--domain", "research.example.org").code());
        assertFalse(Files.exists(tmp.resolve("x")), "nothing written by a refusal");
    }

    @Test
    void withTwoMachinesTheSecondRunsANodeThatReachesTheStoreAndTheServiceOnThePrivateNetwork(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("rz-two");
        Ran r = run("setup", "public", "--domain", "research.example.org", "--folder", f.toString(), "--nodes", "2", "--private", "10.0.0.2", "--node-address", "10.0.0.3", "--image", "registry.example.org/rz:0.8.0");
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("docker compose -f compose.node.yml up -d") && r.out().contains("never on a public one"), r.out());
        String one = Files.readString(f.resolve("compose.yml")), two = Files.readString(f.resolve("compose.node.yml"));
        assertTrue(block(one, "db").contains("\"${RZ_PRIVATE}:${RZ_DB_PORT}:5432\"") && block(one, "service").contains("\"${RZ_PRIVATE}:${RZ_SERVICE_PORT}:4649\""), "the store and the service on the private address");
        String node = block(two, "node"), proxy = block(two, "docker");
        assertTrue(node.contains("network_mode: host") && node.contains("command: [\"node\"]") && node.contains("postgresql://zosho:${POSTGRES_PASSWORD}@${RZ_PRIVATE}:${RZ_DB_PORT}/zosho")
                && node.contains("DOCKER_HOST: unix://" + Deployment.DOCKER_AT + "/docker.sock") && !node.contains("/var/run/docker.sock"), node);
        assertTrue(proxy.contains("network_mode: none") && !proxy.contains("ports:"), "the second machine's proxy on a socket of this user's alone, with no network");
        Map<String, String> env = env(f);
        assertEquals("10.0.0.2", env.get("RZ_PRIVATE")); assertEquals(f.resolve("node-data").toString(), env.get("RZ_NODE_DATA")); assertEquals("registry.example.org/rz:0.8.0", env.get("RZ_IMAGE"));
        String service = Files.readString(f.resolve("data").resolve("config")), nodeSettings = Files.readString(f.resolve("node-data").resolve("config"));
        assertTrue(service.contains("accounts.node = off") && service.contains("accounts.service = http://10.0.0.2:4649") && service.contains("accounts.image = registry.example.org/rz:0.8.0"), service);
        assertTrue(nodeSettings.contains("accounts.service = http://10.0.0.2:4649") && nodeSettings.contains("accounts.node.host = 10.0.0.3") && nodeSettings.contains("accounts.node.port = 4690")
                && nodeSettings.contains("accounts.root = " + f.resolve("node-data").resolve("accounts")) && !nodeSettings.contains("accounts.image"), nodeSettings);
        assertTrue(node.contains("RESEARCHZOSHO_ACCOUNTS_IMAGE_DEFAULT: ${RZ_IMAGE}"), "the node's libraries run the node's own image: " + node);
        everyLineASetting(f.resolve("data").resolve("config"));
        everyLineASetting(f.resolve("node-data").resolve("config"));
    }

    @Test
    void theProxyToDockerIsOnASocketOfThisUsersAloneAndCaddyAloneIsBelieved(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("rz-public");
        assertEquals(0, run("setup", "public", "--domain", "research.example.org", "--folder", f.toString()).code());
        String compose = Files.readString(f.resolve("compose.yml"));
        String proxy = block(compose, "docker"), service = block(compose, "service"), db = block(compose, "db"), caddy = block(compose, "caddy");
        // the proxy: no network at all, a Unix socket in the data's docker folder, mode 600, this user's
        assertTrue(proxy.contains("network_mode: none") && !proxy.contains("ports:") && !proxy.contains("networks:")
                && proxy.contains("BIND_CONFIG: \"" + Deployment.DOCKER_AT + "/docker.sock mode 600 uid ${RZ_UID} gid ${RZ_GID}\"") && proxy.contains("\"${RZ_DATA}/docker:" + Deployment.DOCKER_AT + "\""), proxy);
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.resolve("data").resolve("docker"))));
        assertTrue(service.contains("DOCKER_HOST: unix://" + Deployment.DOCKER_AT + "/docker.sock") && service.contains("\"${RZ_DATA}/docker:" + Deployment.DOCKER_AT + "\""), service);
        assertFalse(caddy.contains("docker") || db.contains("${RZ_DATA}/docker"), "Caddy and the database never reach the proxy");
        // Caddy and the service on a network of their own, Caddy at a fixed address; the database and the service on another
        assertTrue(caddy.contains("front:\n        ipv4_address: ${RZ_CADDY_AT}") && !caddy.contains("store"), caddy);
        assertTrue(db.contains("networks:\n      - store") && !db.contains("front"), db);
        assertTrue(service.contains("networks:\n      - front\n      - store"), service);
        assertTrue(compose.contains("  front:") && compose.contains("subnet: ${RZ_FRONT_NET}\n          ip_range: ${RZ_FRONT_RANGE}"), compose);
        Map<String, String> env = env(f);
        assertEquals(Deployment.CADDY_AT, env.get("RZ_CADDY_AT")); assertEquals(Deployment.FRONT_NET, env.get("RZ_FRONT_NET"));
        assertTrue(Proxy.matches(InetAddress.getByName(env.get("RZ_CADDY_AT")), env.get("RZ_FRONT_NET")));
        assertTrue(!Proxy.matches(InetAddress.getByName(env.get("RZ_CADDY_AT")), env.get("RZ_FRONT_RANGE")) && Proxy.matches(InetAddress.getByName(env.get("RZ_FRONT_RANGE").replaceAll("/.*", "")), env.get("RZ_FRONT_NET")),
                "the addresses Docker gives others are on the network and never Caddy's");
        // the service believes forwarded headers from Caddy alone
        String settings = Files.readString(f.resolve("data").resolve("config"));
        assertTrue(settings.contains("trusted.proxy = " + Deployment.CADDY_AT + "\n") && !settings.contains("10.0.0.0/8") && !settings.contains("172.16.0.0/12"), settings);

        // the second machine: its proxy has no port on the machine either
        Path g = tmp.resolve("rz-two");
        assertEquals(0, run("setup", "public", "--domain", "research.example.org", "--folder", g.toString(), "--nodes", "2", "--private", "10.0.0.2", "--node-address", "10.0.0.3").code());
        String two = Files.readString(g.resolve("compose.node.yml"));
        String nodeProxy = block(two, "docker"), node = block(two, "node");
        assertTrue(nodeProxy.contains("network_mode: none") && !nodeProxy.contains("ports:") && nodeProxy.contains("\"${RZ_NODE_DATA}/docker:" + Deployment.DOCKER_AT + "\""), nodeProxy);
        assertTrue(node.contains("DOCKER_HOST: unix://" + Deployment.DOCKER_AT + "/docker.sock") && !two.contains("2375") && !env(g).containsKey("RZ_PROXY_PORT"), node);
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(g.resolve("node-data").resolve("docker"))));
    }

    /** A port named for the setup inside the machine's range for outgoing connections is said (round C, round B's runs). */
    @Test
    void aPortInTheRangeForOutgoingConnectionsIsSaid(@TempDir Path tmp) throws Exception {
        Supplier<int[]> was = Deployment.EPHEMERAL;
        Deployment.EPHEMERAL = () -> new int[]{32768, 60999};
        try {
            Ran inside = run("setup", "public", "--domain", "research.example.org", "--folder", tmp.resolve("one").toString(), "--https-port", "47344");
            assertTrue(inside.out().contains("--https-port 47344 is in this machine's range of ports for outgoing connections (32768-60999)"), inside.out());
            Ran outside = run("setup", "public", "--domain", "research.example.org", "--folder", tmp.resolve("two").toString());
            assertFalse(outside.out().contains("range of ports"), "443 and 80 are not in it");
        } finally { Deployment.EPHEMERAL = was; }
    }

    /**
     * The second machine updated as the update says (RZ_IMAGE set in its copy of .env, the node made again): its libraries start from
     * the new image too. Its copy of node-data/config is the one the setup wrote, never changed by the update on the first machine.
     */
    @Test
    void theSecondMachinesLibrariesStartFromTheImageItsEnvNamesAfterAnUpdate(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("rz-two"), second = tmp.resolve("second");
        assertEquals(0, run("setup", "public", "--domain", "research.example.org", "--folder", f.toString(), "--nodes", "2", "--private", "10.0.0.2", "--node-address", "10.0.0.3", "--image", "registry.example.org/rz:0.8.0").code());
        Files.createDirectories(second.resolve("node-data"));   // what is copied to the second machine at the setup
        for (String c : List.of(".env", "compose.node.yml", "node-data/config")) Files.copy(f.resolve(c), second.resolve(c));
        Deployment.SHELL = (dir, cmd, ms) -> new Deployment.Result(0, cmd.contains("exec") ? "{\"ok\":true,\"version\":\"0.8.1\",\"service\":\"accounts\"}" : "");
        Ran r = run("update", "0.8.1", "--folder", f.toString());
        assertEquals(0, r.code(), r.err());
        assertTrue(r.out().contains("set RZ_IMAGE=registry.example.org/rz:0.8.1 in .env"), r.out());
        // on the second machine, the line the update said: RZ_IMAGE in its .env
        Path env = second.resolve(".env");
        Files.writeString(env, Files.readString(env).replaceAll("(?m)^RZ_IMAGE=.*$", "RZ_IMAGE=registry.example.org/rz:0.8.1"));
        assertEquals("registry.example.org/rz:0.8.1", librariesImage(second), "the node's libraries start from the new image");
        assertEquals("registry.example.org/rz:0.8.1", librariesImage(f), "and those of the first machine's copy");
    }

    /** The image a node started from {@code folder}'s compose.node.yml gives its libraries: its settings file's, else what compose offers it. */
    private static String librariesImage(Path folder) throws IOException {
        for (String line : Files.readAllLines(folder.resolve("node-data").resolve("config"))) if (line.startsWith("accounts.image = ")) return line.substring("accounts.image = ".length()).strip();
        Matcher m = Pattern.compile("(?m)^\\s*RESEARCHZOSHO_ACCOUNTS_IMAGE(?:_DEFAULT)?: \\$\\{(\\w+)}\\s*$").matcher(Files.readString(folder.resolve("compose.node.yml")));
        return m.find() ? env(folder).get(m.group(1)) : "the program's own published image";
    }

    @Test
    void anUpdateMovesTheServiceToTheNewImageAndSaysTheSecondMachinesLine(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("rz-two");
        assertEquals(0, run("setup", "public", "--domain", "research.example.org", "--folder", f.toString(), "--nodes", "2", "--private", "10.0.0.2", "--node-address", "10.0.0.3", "--image", "registry.example.org/rz:0.8.0").code());
        List<String> asked = new ArrayList<>();
        Deployment.SHELL = (dir, cmd, ms) -> {
            asked.add(String.join(" ", cmd));
            assertEquals(f, dir);
            if (cmd.get(1).equals("compose") && cmd.contains("exec")) return new Deployment.Result(0, "{\"ok\":true,\"version\":\"0.8.1\",\"service\":\"accounts\"}");
            return new Deployment.Result(0, "");
        };
        Ran r = run("update", "0.8.1", "--folder", f.toString());
        assertEquals(0, r.code(), r.err());
        assertEquals(List.of("docker pull registry.example.org/rz:0.8.1", "docker compose up -d --no-deps service", "docker compose exec -T service curl -fsS http://127.0.0.1:4649/healthz"), asked, "the image, the service made again, its health");
        assertEquals("registry.example.org/rz:0.8.1", env(f).get("RZ_IMAGE"));
        assertTrue(Files.readString(f.resolve("data").resolve("config")).contains("accounts.image = registry.example.org/rz:0.8.1"));
        assertTrue(r.out().contains("The service runs registry.example.org/rz:0.8.1 now, and says it is 0.8.1.") && r.out().contains("docker compose -f compose.node.yml up -d node"), r.out());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.resolve(".env"))));

        // an image that is neither published nor here: nothing is changed
        asked.clear();
        Deployment.SHELL = (dir, cmd, ms) -> { asked.add(String.join(" ", cmd)); return new Deployment.Result(1, "Error response from daemon: manifest unknown"); };
        Ran no = run("update", "0.9.9", "--folder", f.toString());
        assertEquals(1, no.code());
        assertTrue(no.err().contains("could not be fetched, and this machine does not have it") && no.err().contains("Nothing was changed."), no.err());
        assertEquals("registry.example.org/rz:0.8.1", env(f).get("RZ_IMAGE"));
        assertEquals(2, asked.size(), "asked for the image and looked for it here; nothing else");
        // the service could not be made again: .env names the image it ran
        Deployment.SHELL = (dir, cmd, ms) -> new Deployment.Result(cmd.contains("up") ? 1 : 0, cmd.contains("up") ? "no such image" : "");
        Ran failed = run("update", "0.8.2", "--folder", f.toString());
        assertEquals(1, failed.code());
        assertEquals("registry.example.org/rz:0.8.1", env(f).get("RZ_IMAGE"), "put back");
        assertTrue(run("update", "0.8.2", "--folder", tmp.resolve("nowhere").toString()).err().contains("There is no service set up in"));
    }

    /** An update that does not come up puts everything back as it was (0.8.0 review, round B, ROB 12): .env, both settings files, and the old image running. */
    @Test
    void anUpdateThatDoesNotComeUpPutsBackWhatItChangedAndStartsTheOldImage(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("rz-two");
        assertEquals(0, run("setup", "public", "--domain", "research.example.org", "--folder", f.toString(), "--nodes", "2", "--private", "10.0.0.2", "--node-address", "10.0.0.3", "--image", "registry.example.org/rz:0.8.0").code());
        String env = Files.readString(f.resolve(".env")), service = Files.readString(f.resolve("data").resolve("config")), node = Files.readString(f.resolve("node-data").resolve("config"));
        Deployment.HEALTH_MS = 300;
        try {
            // the new image is made, but the service does not answer its health within the time
            List<String> asked = new ArrayList<>();
            List<String> imageAtUp = new ArrayList<>();
            Deployment.SHELL = (dir, cmd, ms) -> {
                asked.add(String.join(" ", cmd));
                if (cmd.contains("up")) imageAtUp.add(env(f).get("RZ_IMAGE"));
                if (cmd.contains("exec")) return new Deployment.Result(7, "curl: (7) Failed to connect");
                return new Deployment.Result(0, "");
            };
            Ran r = run("update", "0.8.1", "--folder", f.toString());
            assertEquals(1, r.code());
            assertEquals(env, Files.readString(f.resolve(".env")), ".env as it was");
            assertEquals(service, Files.readString(f.resolve("data").resolve("config")), "the service's settings as they were");
            assertEquals(node, Files.readString(f.resolve("node-data").resolve("config")), "the second machine's settings as they were");
            assertEquals(List.of("registry.example.org/rz:0.8.1", "registry.example.org/rz:0.8.0"), imageAtUp, "made again from the new image, then from the old one");
            assertTrue(r.err().contains("registry.example.org/rz:0.8.0"), r.err());
            // the service could not even be made again: the settings files are put back too
            Deployment.SHELL = (dir, cmd, ms) -> new Deployment.Result(cmd.contains("up") && env(f).get("RZ_IMAGE").endsWith("0.8.2") ? 1 : 0, "");
            assertEquals(1, run("update", "0.8.2", "--folder", f.toString()).code());
            assertEquals(env, Files.readString(f.resolve(".env")));
            assertEquals(service, Files.readString(f.resolve("data").resolve("config")));
            assertEquals(node, Files.readString(f.resolve("node-data").resolve("config")));
        } finally { Deployment.HEALTH_MS = 180_000; }
    }

    @Test
    void aServiceWhoseMachineRunsNoLibrariesHasANodeMakeEachOnItsOwnDisk(@TempDir Path tmp) throws Exception {
        try (AccountStore store = AccountStore.open(tmp.resolve("accounts.db").toString())) {
            String issuer = "http://10.0.0.2:4649";
            Config.set(Node.OWN, "off"); set.add(Node.OWN);
            Config.set(AccountService.SERVICE, issuer); set.add(AccountService.SERVICE);
            long lease = Node.LEASE_MS, renew = Node.RENEW_MS;
            Node.LEASE_MS = 1_000; Node.RENEW_MS = 100;
            try {
                AccountService.Made cy = AccountService.newAccount(store, tmp.resolve("service-root"), "Cy Example", "", false, "the test");
                assertTrue(cy.library().folder().startsWith(tmp.resolve("service-root").toString()), "a service that never had a node makes it on its own disk, as before");
                store.nodeSeen("node_gone", "http://127.0.0.1:1", tmp.resolve("gone").toString(), "0.8.0");   // a node the service had, silent now
                Thread.sleep(1_500);
                assertTrue(assertThrowsIO(() -> AccountService.newAccount(store, tmp.resolve("service-root"), "Ann Example", "", false, "the test")).contains("No machine of this service answers now"));
                assertEquals(1, store.accounts().size(), "no account without a library");
                made(store, issuer, tmp);
            } finally { Node.LEASE_MS = lease; Node.RENEW_MS = renew; }
        }
    }

    void made(AccountStore store, String issuer, Path tmp) throws Exception {
        {
            Path second = tmp.resolve("second-machine");
            Node n = new Node(store, issuer, new ChildLibraries(store, issuer), "node_second", second);
            NodeServer ns = NodeServer.start(n, store, issuer, "127.0.0.1", 0);
            n.start();
            try {
                AccountService.Made ann = AccountService.newAccount(store, tmp.resolve("service-root"), "Ann Example", "", false, "the test");
                assertTrue(ann.library().folder().startsWith(second.toString()), "made on the node's disk: " + ann.library().folder());
                assertTrue(Files.isRegularFile(LibraryPlaces.library(ann.library()).resolve("catalog").resolve("library.md")));
                assertEquals(ann.library().id(), new LibraryStore(LibraryPlaces.library(ann.library())).identity().id());
                assertFalse(Files.exists(tmp.resolve("service-root").resolve("libraries").resolve("ann-example")), "nothing of it on the service's machine");
                assertEquals("node_second", Nodes.home(store.nodes(), ann.library()).id(), "it runs there");
            } finally { n.stopAll(); ns.stop(); }
        }
    }

    interface Io { void run() throws IOException; }
    static String assertThrowsIO(Io r) { try { r.run(); } catch (IOException e) { return e.getMessage(); } throw new AssertionError("no refusal"); }
}
