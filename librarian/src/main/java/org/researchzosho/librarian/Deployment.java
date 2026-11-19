package org.researchzosho.librarian;

import org.researchzosho.Version;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * A service for people you do not know, set up and updated with one command each (0.8.0, stage 4, step 5).
 *
 * <p>{@code researchzosho accounts setup public --domain research.example.org} writes a folder that Docker Compose runs: Caddy in
 * front with its certificate got by itself, PostgreSQL with a password made for it, a proxy to Docker that lets the service manage
 * containers and images and nothing else, and the service, whose libraries each run in a container of their own. The service's
 * settings are a file in the folder, written with what a public service should have. With {@code --nodes 2} a second file runs a
 * node on a second machine, which makes and runs the libraries, and the first machine runs the service alone.
 *
 * <p>{@code researchzosho accounts update <version>} moves the service to another version: the image named in the folder's .env, the
 * service's container made again from it (the store's steps are only ever added, so what runs elsewhere keeps working meanwhile),
 * its health asked for; the libraries follow at their next quiet moment; a second machine's line is said, not run from here.
 */
final class Deployment {
    private Deployment() { }

    /** The images the folder names besides the service's own: the router, the database and the proxy to Docker. */
    static final String CADDY = "caddy:2", POSTGRES = "postgres:16-alpine", PROXY = "tecnativa/docker-socket-proxy:v0.5.0";
    /** Where the node in a container makes its libraries' sockets: a short path, the folder of the data's "sockets" mounted there. */
    static final String SOCKETS_AT = "/run/rz-sockets";
    /**
     * Where the proxy to Docker listens, in the service's (or the node's) container and in the proxy's: a Unix socket in the data's
     * "docker" folder, readable by the setup's user alone. The proxy has no network at all; nothing on a network reaches it.
     */
    static final String DOCKER_AT = "/run/rz-docker";
    /**
     * The network Caddy and the service alone are on, Caddy's fixed address there (the one address the service believes forwarded headers
     * from), and the addresses Docker gives the rest (the service), which never include Caddy's.
     */
    static final String FRONT_NET = "10.249.73.0/29", CADDY_AT = "10.249.73.2", FRONT_RANGE = "10.249.73.4/30";
    /** The service's image when none is named: the published one at this program's version. */
    static String publishedImage() { String v = Version.number(); return "ghcr.io/wyrdsekai/researchzosho:" + (v == null ? "latest" : v); }

    /** A command run in a folder to its end: its exit and what it printed. A test stands in for it. */
    interface Shell { Result run(Path dir, List<String> cmd, long ms) throws IOException; }
    record Result(int code, String out) { }

    static volatile Shell SHELL = (dir, cmd, ms) -> {
        Path log = Files.createTempFile("researchzosho-compose", ".log");
        try {
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null"))).start();
            if (!p.waitFor(ms, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return new Result(-1, "did not end within " + ms / 1000 + " s"); }
            return new Result(p.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        finally { Files.deleteIfExists(log); }
    };

    static final String USAGE = """
            usage: researchzosho accounts setup public --domain <name> [--folder <dir>] [--signup invited|domain|anyone] [--image <image>]
                                 [--http-port <n>] [--https-port <n>]
                                 [--nodes 2 --private <this machine's private address> --node-address <the second machine's private address>
                                  [--service-port <n>] [--db-port <n>] [--node-port <n>] [--first-port <n>]]
                   researchzosho accounts update <version> [--folder <dir>]
            """;

    // ---- setup ----

    /** What the setup was asked for. */
    record Plan(String domain, Path folder, String signup, String image, boolean customImage, int httpPort, int httpsPort, boolean two, String privateAddress, String nodeAddress,
                int servicePort, int dbPort, int nodePort, int firstPort) { }

    static Plan plan(List<String> a) throws IOException {
        Map<String, String> o = new LinkedHashMap<>();
        for (int i = 0; i < a.size(); i++) {
            String w = a.get(i);
            if (!w.startsWith("--")) throw new IOException("What is " + w + "? " + USAGE);
            if (i + 1 >= a.size()) throw new IOException(w + " needs a value.");
            o.put(w.substring(2), a.get(++i));
        }
        String domain = o.getOrDefault("domain", "").strip().toLowerCase();
        if (domain.isEmpty()) throw new IOException("Say the name people reach the service at: --domain research.example.org");
        if (!domain.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*") || !(domain.contains(".") || domain.equals("localhost"))) throw new IOException(domain + " is not a name a service is reached at, such as research.example.org.");
        String signup = o.getOrDefault("signup", "invited");
        if (!List.of("invited", "domain", "anyone").contains(signup)) throw new IOException("--signup is invited (you make each account), domain (people of your mail domains sign up) or anyone.");
        int nodes = number(o, "nodes", 1);
        if (nodes != 1 && nodes != 2) throw new IOException("--nodes is 1 (one machine) or 2 (the service on this one, the libraries on a second).");
        boolean two = nodes == 2;
        String priv = o.getOrDefault("private", ""), nodeAt = o.getOrDefault("node-address", "");
        if (two && (!priv.matches("[0-9a-fA-F:.]+") || !nodeAt.matches("[0-9a-fA-F:.]+")))
            throw new IOException("With --nodes 2, name both machines' addresses on the private network between them: --private <this machine's> --node-address <the second's>.");
        Path folder = Path.of(o.getOrDefault("folder", "researchzosho-" + domain)).toAbsolutePath().normalize();
        String image = o.getOrDefault("image", publishedImage());
        return new Plan(domain, folder, signup, image, o.containsKey("image"), number(o, "http-port", 80), number(o, "https-port", 443), two, priv, nodeAt,
                number(o, "service-port", 4649), number(o, "db-port", 5432), number(o, "node-port", 4690), number(o, "first-port", 4700));
    }

    private static int number(Map<String, String> o, String k, int dflt) throws IOException {
        String v = o.get(k);
        if (v == null) return dflt;
        try { int n = Integer.parseInt(v.strip()); if (n < 1 || n > 65535) throw new NumberFormatException(); return n; } catch (NumberFormatException e) { throw new IOException("--" + k + " is a number from 1 to 65535."); }
    }

    /** A password for the database: 32 letters, digits, - and _, from the system's strong random source. */
    static String password() { byte[] b = new byte[24]; new SecureRandom().nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    static int setup(String[] args, PrintStream out, PrintStream err) {
        List<String> words = new ArrayList<>(List.of(args).subList(Math.min(args.length, 3), args.length));
        if (words.isEmpty() || !words.get(0).equals("public")) { err.print(USAGE); return 2; }
        try {
            Plan p = plan(words.subList(1, words.size()));
            Written w = write(p, password());
            out.println("A public service for " + p.domain() + " is set up in " + p.folder() + ":");
            for (String f : w.files()) out.println("    " + f);
            out.println("Start it there:");
            out.println("    cd " + p.folder() + " && docker compose up -d");
            if (p.two()) {   // the libraries are made on the second machine: its node answers before the first account is made
                out.println("Then, on the second machine: copy compose.node.yml, .env and node-data to a folder of the same path, and start the node there:");
                out.println("    docker compose -f compose.node.yml up -d");
                out.println("It makes and runs the libraries; this machine runs the service alone. Keep the ports " + p.dbPort() + " (the database), " + p.servicePort() + " (the service) and "
                        + p.nodePort() + " (the node) on the private network between the two, never on a public one.");
            }
            out.println("Then make the first account, which runs the service, and open the link it prints:");
            out.println("    docker compose exec service researchzosho accounts add \"<your name>\" --admin");
            out.println("and name the model the libraries use (an API on the internet, or a server of your own on the private network):");
            out.println("    docker compose exec service researchzosho models add <name> <host> <model id>");
            out.println("Point " + p.domain() + " at this machine first: Caddy gets its certificate the first time the name is asked for.");
            out.println("The service reaches Docker through a proxy that lets it make, start and stop containers and fetch images, on a socket only your user opens; the proxy has no network.");
            out.println("Making a container is as strong as root on this machine: whoever can make the service do what it should not can do that too. Keep the machine for the service alone.");
            out.println("Update it later with: researchzosho accounts update <version>   (in this folder)");
            for (String said : ephemeral(p)) out.println(said);
            return 0;
        } catch (IOException e) { err.println(e.getMessage()); return 1; }
    }

    /** This machine's range of ports for outgoing connections (Linux: ip_local_port_range); null where it cannot be read. A test gives its own. */
    static volatile Supplier<int[]> EPHEMERAL = () -> {
        try {
            String[] r = Files.readString(Path.of("/proc/sys/net/ipv4/ip_local_port_range"), StandardCharsets.UTF_8).strip().split("\\s+");
            return new int[]{Integer.parseInt(r[0]), Integer.parseInt(r[1])};
        } catch (IOException | RuntimeException e) { return null; }
    };

    /**
     * A sentence for each port named for the setup inside this machine's range for outgoing connections: another program's connection can
     * hold it for a moment, and then the container that publishes it does not start (round B's runs met it twice).
     */
    static List<String> ephemeral(Plan p) {
        int[] r = EPHEMERAL.get();
        List<String> said = new ArrayList<>();
        if (r == null) return said;
        Map<String, Integer> ports = new LinkedHashMap<>();
        ports.put("http", p.httpPort()); ports.put("https", p.httpsPort());
        if (p.two()) { ports.put("service", p.servicePort()); ports.put("db", p.dbPort()); ports.put("node", p.nodePort()); ports.put("first", p.firstPort()); }
        for (Map.Entry<String, Integer> e : ports.entrySet())
            if (e.getValue() >= r[0] && e.getValue() <= r[1])
                said.add("Note: --" + e.getKey() + "-port " + e.getValue() + " is in this machine's range of ports for outgoing connections (" + r[0] + "-" + r[1] + "): another program can hold it for a moment, and then what publishes it does not start. A port below " + r[0] + " never meets that.");
        return said;
    }

    /** What the setup wrote: the folder's files, by their names in it. */
    record Written(List<String> files) { }

    static Written write(Plan p, String password) throws IOException {
        Path f = p.folder();
        if (Files.exists(f) && !(Files.isDirectory(f) && empty(f))) throw new IOException("There is something at " + f + " already; nothing was written. Name another folder with --folder.");
        Path data = f.resolve("data");
        for (String sub : List.of("accounts", "home", "tmp", "keys", "sockets", "docker")) Files.createDirectories(data.resolve(sub));
        Files.setPosixFilePermissions(data.resolve("docker"), PosixFilePermissions.fromString("rwx------"));   // the proxy's socket: this user's alone
        Object uid = Files.getAttribute(f, "unix:uid", LinkOption.NOFOLLOW_LINKS), gid = Files.getAttribute(f, "unix:gid", LinkOption.NOFOLLOW_LINKS);
        List<String> files = new ArrayList<>();
        StringBuilder env = new StringBuilder("# What compose.yml" + (p.two() ? " and compose.node.yml take" : " takes") + ": the name, the image, the folder of the data, the user, the ports, and the database's password.\n"
                + "# It holds a password: keep it to yourself (this file is readable by its owner alone).\n");
        Map<String, String> e = new LinkedHashMap<>();
        e.put("RZ_DOMAIN", p.domain()); e.put("RZ_IMAGE", p.image()); e.put("RZ_DATA", data.toString()); e.put("RZ_UID", String.valueOf(uid)); e.put("RZ_GID", String.valueOf(gid));
        e.put("RZ_HTTP_PORT", String.valueOf(p.httpPort())); e.put("RZ_HTTPS_PORT", String.valueOf(p.httpsPort())); e.put("POSTGRES_PASSWORD", password);
        e.put("RZ_FRONT_NET", FRONT_NET); e.put("RZ_FRONT_RANGE", FRONT_RANGE); e.put("RZ_CADDY_AT", CADDY_AT);
        if (p.two()) {
            e.put("RZ_PRIVATE", p.privateAddress()); e.put("RZ_SERVICE_PORT", String.valueOf(p.servicePort())); e.put("RZ_DB_PORT", String.valueOf(p.dbPort()));
            e.put("RZ_NODE_DATA", f.resolve("node-data").toString());
        }
        e.forEach((k, v) -> env.append(k).append('=').append(v).append('\n'));
        Path envFile = f.resolve(".env");
        // made readable by its owner alone before the password goes into it, never after (round F): an empty file first, then its words
        Files.deleteIfExists(envFile);
        try { Files.createFile(envFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); }
        catch (UnsupportedOperationException noPosix) { Files.createFile(envFile); }
        Files.setPosixFilePermissions(envFile, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(envFile, env.toString(), StandardCharsets.UTF_8);
        files.add(".env  the database's password and the folder's values (readable by its owner alone)");
        Files.writeString(f.resolve("compose.yml"), compose(p), StandardCharsets.UTF_8);
        files.add("compose.yml  Caddy, PostgreSQL, the proxy to Docker, and the service");
        Files.writeString(f.resolve("Caddyfile"), "# Caddy in front of the service: the certificate for the name, got by itself, and every request passed on.\n{$RZ_DOMAIN} {\n\treverse_proxy service:4649\n}\n", StandardCharsets.UTF_8);
        files.add("Caddyfile  the name, and the way to the service");
        String service = p.two() ? "http://" + bracket(p.privateAddress()) + ":" + p.servicePort() : "http://service:4649";
        Files.writeString(data.resolve("config"), serviceSettings(p, data, service), StandardCharsets.UTF_8);
        files.add("data/config  the service's settings (change one with: docker compose exec service researchzosho settings set <name> <value>)");
        if (p.two()) {
            Path node = f.resolve("node-data");
            for (String sub : List.of("accounts", "home", "tmp", "sockets", "docker")) Files.createDirectories(node.resolve(sub));
            Files.setPosixFilePermissions(node.resolve("docker"), PosixFilePermissions.fromString("rwx------"));
            Files.writeString(f.resolve("compose.node.yml"), composeNode(p), StandardCharsets.UTF_8);
            Files.writeString(node.resolve("config"), nodeSettings(p, node, service), StandardCharsets.UTF_8);
            files.add("compose.node.yml and node-data/config  the second machine's node, and its settings");
        }
        return new Written(files);
    }

    private static String bracket(String address) { return address.contains(":") ? "[" + address + "]" : address; }

    private static boolean empty(Path dir) throws IOException { try (Stream<Path> s = Files.list(dir)) { return s.findAny().isEmpty(); } }

    /** The settings a public service should have; what is not written here holds at its default, which is the public one (the caps, the rate limits). */
    static String serviceSettings(Plan p, Path data, String service) {
        StringBuilder b = new StringBuilder("# The service's settings, written by researchzosho accounts setup public. What is not here holds at its default:\n"
                + "# the caps and the rate limits are on, at their defaults for a service open to people you do not know.\n");
        b.append("accounts = on\n");
        b.append("accounts.address = https://").append(p.domain()).append(p.httpsPort() == 443 ? "" : ":" + p.httpsPort()).append('\n');
        b.append("accounts.service = ").append(service).append('\n');
        b.append("accounts.root = ").append(data.resolve("accounts")).append('\n');
        b.append("accounts.keys.file = ").append(data.resolve("keys").resolve("own-keys.key")).append('\n');
        b.append("accounts.runner = container\n");
        b.append("accounts.sockets = ").append(SOCKETS_AT).append('\n');   // short, inside the service's container; the same folder as the machine sees it next
        b.append("accounts.sockets.host = ").append(data.resolve("sockets")).append('\n');
        if (p.customImage()) b.append("accounts.image = ").append(p.image()).append('\n');
        b.append("accounts.signup = ").append(p.signup()).append('\n');
        if (p.two()) b.append("accounts.node = off\n");
        b.append("trusted.proxy = ").append(CADDY_AT).append('\n');   // Caddy alone, at its fixed address on the network only it and the service are on (RZ_CADDY_AT in .env)
        b.append("log = stdout\nupdate = off\n");
        return b.toString();
    }

    /** The second machine's node: where the store and the service are, its own folder, the address and the port the service reaches it at. */
    static String nodeSettings(Plan p, Path node, String service) {
        StringBuilder b = new StringBuilder("# The node's settings on the second machine, written by researchzosho accounts setup public --nodes 2.\n");
        b.append("accounts.service = ").append(service).append('\n');
        b.append("accounts.root = ").append(node.resolve("accounts")).append('\n');
        b.append("accounts.node.host = ").append(p.nodeAddress()).append('\n');
        b.append("accounts.node.port = ").append(p.nodePort()).append('\n');
        b.append("accounts.first.port = ").append(p.firstPort()).append('\n');
        b.append("accounts.runner = container\n");
        b.append("accounts.sockets = ").append(SOCKETS_AT).append('\n');
        b.append("accounts.sockets.host = ").append(node.resolve("sockets")).append('\n');
        // no accounts.image: the node's libraries run the image .env names for the node (RZ_IMAGE, offered in compose.node.yml), so an
        // update that sets RZ_IMAGE there moves both; a line here would hold them on the image of the setup
        b.append("log = stdout\nupdate = off\n");
        return b.toString();
    }

    static String compose(Plan p) {
        String store = "postgresql://zosho:${POSTGRES_PASSWORD}@db:5432/zosho";
        return "# ResearchZosho serving accounts" + (p.two() ? ", with its libraries on a second machine (compose.node.yml)" : " on one machine") + ", written by researchzosho accounts setup public.\n"
                + "# Start: docker compose up -d. Update: researchzosho accounts update <version>. The values come from .env.\n"
                + "services:\n"
                + "  caddy:\n"
                + "    image: " + CADDY + "\n"
                + "    ports:\n      - \"${RZ_HTTP_PORT}:80\"\n      - \"${RZ_HTTPS_PORT}:443\"\n"
                + "    networks:\n      front:\n        ipv4_address: ${RZ_CADDY_AT}   # the one address the service believes forwarded headers from (trusted.proxy)\n"
                + "    environment:\n      RZ_DOMAIN: ${RZ_DOMAIN}\n"
                + "    volumes:\n      - ./Caddyfile:/etc/caddy/Caddyfile:ro\n      - caddy-data:/data\n      - caddy-config:/config\n"
                + "    depends_on:\n      - service\n"
                + "    restart: unless-stopped\n"
                + "  db:\n"
                + "    image: " + POSTGRES + "\n"
                + "    environment:\n      POSTGRES_USER: zosho\n      POSTGRES_DB: zosho\n      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}\n"
                + "    volumes:\n      - db-data:/var/lib/postgresql/data\n"
                + "    networks:\n      - store\n"
                + (p.two() ? "    ports:\n      - \"${RZ_PRIVATE}:${RZ_DB_PORT}:5432\"   # for the second machine's node, on the private network\n" : "")
                + "    healthcheck:\n      test: [\"CMD-SHELL\", \"pg_isready -U zosho -d zosho\"]\n      interval: 5s\n      timeout: 5s\n      retries: 30\n"
                + "    restart: unless-stopped\n"
                + proxy("data", "the service")
                + "  service:\n"
                + "    image: ${RZ_IMAGE}\n"
                + "    user: \"${RZ_UID}:${RZ_GID}\"\n"
                + "    entrypoint: [\"researchzosho\"]\n"
                + "    command: [\"serve\", \"--host\", \"0.0.0.0\", \"--port\", \"4649\"]\n"
                + "    tmpfs:   # the image's own library and settings, which a service does not use: nothing is kept for them\n      - /library\n      - /config\n"
                + "    environment:\n"
                + "      RESEARCHZOSHO_CONFIG: ${RZ_DATA}/config\n"
                + "      RESEARCHZOSHO_HOME: ${RZ_DATA}/home\n"
                + "      RESEARCHZOSHO_ACCOUNTS_STORE: " + store + "\n"
                + "      DOCKER_HOST: unix://" + DOCKER_AT + "/docker.sock\n"
                + "      JAVA_OPTS: -Djava.io.tmpdir=${RZ_DATA}/tmp\n"
                + "    # the data at the same path inside as on the machine: Docker is handed these paths for each library's container\n"
                + "    volumes:\n      - \"${RZ_DATA}:${RZ_DATA}\"\n      - \"${RZ_DATA}/sockets:" + SOCKETS_AT + "\"   # the libraries' sockets, at a short path (a socket's path is short)\n"
                + "      - \"${RZ_DATA}/docker:" + DOCKER_AT + "\"   # the proxy's socket\n"
                + "    networks:\n      - front\n      - store\n"
                + (p.two() ? "    ports:\n      - \"${RZ_PRIVATE}:${RZ_SERVICE_PORT}:4649\"   # for the second machine's node and libraries, on the private network\n" : "")
                + "    depends_on:\n      db:\n        condition: service_healthy\n      docker:\n        condition: service_started\n"
                + "    restart: unless-stopped\n"
                + "networks:\n"
                + "  front:   # Caddy and the service alone; Caddy's address is never given to another\n    ipam:\n      config:\n        - subnet: ${RZ_FRONT_NET}\n          ip_range: ${RZ_FRONT_RANGE}\n"
                + "  store:   # the service and its database alone\n"
                + "volumes:\n  caddy-data:\n  caddy-config:\n  db-data:\n";
    }

    /**
     * The proxy to Docker: it lets through what managing containers and images needs (and POST), refuses the rest (volumes, networks,
     * the swarm, the system), and listens only on a Unix socket in {@code <folder>/docker} that the setup's user alone opens. It has no
     * network: nothing on a network, and no other user of the machine, reaches it.
     */
    private static String proxy(String folder, String whose) {
        String data = folder.equals("data") ? "${RZ_DATA}" : "${RZ_NODE_DATA}";
        return "  # Docker for " + whose + ", through a proxy that lets it manage containers and images and nothing else. Docker's own socket is root\n"
                + "  # on this machine, and only this container holds it; it listens on a socket in " + folder + "/docker that this user alone opens, and has no network.\n"
                + "  docker:\n"
                + "    image: " + PROXY + "\n"
                + "    network_mode: none\n"
                + "    environment:\n      CONTAINERS: 1\n      IMAGES: 1\n      POST: 1\n"
                + "      BIND_CONFIG: \"" + DOCKER_AT + "/docker.sock mode 600 uid ${RZ_UID} gid ${RZ_GID}\"\n"
                + "    volumes:\n      - /var/run/docker.sock:/var/run/docker.sock:ro\n      - \"" + data + "/docker:" + DOCKER_AT + "\"\n"
                + "    restart: unless-stopped\n";
    }

    static String composeNode(Plan p) {
        return "# The second machine of a ResearchZosho service: a node that makes and runs the libraries, each in a container of its own, written by\n"
                + "# researchzosho accounts setup public --nodes 2. Start: docker compose -f compose.node.yml up -d. The values come from .env.\n"
                + "services:\n"
                + proxy("node-data", "the node")
                + "  node:\n"
                + "    image: ${RZ_IMAGE}\n"
                + "    user: \"${RZ_UID}:${RZ_GID}\"\n"
                + "    # on the machine's own network: the service reaches the node and its libraries at this machine's private address\n"
                + "    network_mode: host\n"
                + "    entrypoint: [\"researchzosho\"]\n"
                + "    command: [\"node\"]\n"
                + "    tmpfs:\n      - /library\n      - /config\n"
                + "    environment:\n"
                + "      RESEARCHZOSHO_CONFIG: ${RZ_NODE_DATA}/config\n"
                + "      RESEARCHZOSHO_HOME: ${RZ_NODE_DATA}/home\n"
                + "      RESEARCHZOSHO_ACCOUNTS_STORE: postgresql://zosho:${POSTGRES_PASSWORD}@${RZ_PRIVATE}:${RZ_DB_PORT}/zosho\n"
                + "      # its libraries run the node's own image, unless accounts.image in node-data/config names another\n"
                + "      RESEARCHZOSHO_ACCOUNTS_IMAGE_DEFAULT: ${RZ_IMAGE}\n"
                + "      DOCKER_HOST: unix://" + DOCKER_AT + "/docker.sock\n"
                + "      JAVA_OPTS: -Djava.io.tmpdir=${RZ_NODE_DATA}/tmp\n"
                + "    volumes:\n      - \"${RZ_NODE_DATA}:${RZ_NODE_DATA}\"\n      - \"${RZ_NODE_DATA}/sockets:" + SOCKETS_AT + "\"\n      - \"${RZ_NODE_DATA}/docker:" + DOCKER_AT + "\"\n"
                + "    depends_on:\n      - docker\n"
                + "    restart: unless-stopped\n";
    }

    // ---- update ----

    static int update(String[] args, PrintStream out, PrintStream err) {
        List<String> words = new ArrayList<>(List.of(args).subList(Math.min(args.length, 3), args.length));
        Path folder = Path.of("").toAbsolutePath();
        String version = null;
        for (int i = 0; i < words.size(); i++) {
            if (words.get(i).equals("--folder") && i + 1 < words.size()) folder = Path.of(words.get(++i)).toAbsolutePath().normalize();
            else if (version == null && !words.get(i).startsWith("--")) version = words.get(i);
            else { err.print(USAGE); return 2; }
        }
        if (version == null || !version.matches("[A-Za-z0-9._:/@-]+")) { err.print(USAGE); return 2; }
        try { return update(folder, version, out); } catch (IOException e) { err.println(e.getMessage()); return 1; }
    }

    /** How long the service is given to answer after its container is made again. */
    static volatile long HEALTH_MS = 180_000;

    static int update(Path folder, String version, PrintStream out) throws IOException {
        Path envFile = folder.resolve(".env"), compose = folder.resolve("compose.yml");
        if (!Files.isRegularFile(envFile) || !Files.isRegularFile(compose)) throw new IOException("There is no service set up in " + folder + ": it has no compose.yml and .env. Run this in the folder researchzosho accounts setup public wrote, or name it with --folder.");
        List<String> env = Files.readAllLines(envFile, StandardCharsets.UTF_8);
        String was = env.stream().filter(l -> l.startsWith("RZ_IMAGE=")).map(l -> l.substring("RZ_IMAGE=".length()).strip()).findFirst().orElseThrow(() -> new IOException(".env names no image (RZ_IMAGE)."));
        int colon = was.lastIndexOf(':'), slash = was.lastIndexOf('/');
        String repo = colon > slash ? was.substring(0, colon) : was;
        String image = version.contains(":") || version.contains("/") ? version : repo + ":" + version;
        if (image.equals(was)) { out.println("The service runs " + image + " already."); return 0; }
        // the image first: fetched where it is published, else the one this machine has; nothing is changed until it is here
        Result pulled = SHELL.run(folder, List.of("docker", "pull", image), 15 * 60_000L);
        if (pulled.code() != 0) {
            Result here = SHELL.run(folder, List.of("docker", "image", "inspect", "--format", "{{.Id}}", image), 60_000L);
            if (here.code() != 0) throw new IOException("The image " + image + " could not be fetched, and this machine does not have it: " + last(pulled.out()) + " Nothing was changed.");
            out.println("The image " + image + " is not published; the one this machine has is used.");
        } else out.println("The image " + image + " is here.");
        // what is changed, kept as it was first: an update that does not come up puts all of it back (ROB 12)
        Map<Path, String> before = new LinkedHashMap<>();
        before.put(envFile, Files.readString(envFile, StandardCharsets.UTF_8));
        List<String> now = new ArrayList<>();
        for (String l : env) now.add(l.startsWith("RZ_IMAGE=") ? "RZ_IMAGE=" + image : l);
        Files.writeString(envFile, String.join("\n", now) + "\n", StandardCharsets.UTF_8);
        for (Path config : List.of(folder.resolve("data").resolve("config"), folder.resolve("node-data").resolve("config"))) {   // a library's image named in the settings follows the service's
            if (!Files.isRegularFile(config)) continue;
            String text = Files.readString(config, StandardCharsets.UTF_8);
            String put = text.replaceAll("(?m)^accounts\\.image = .*$", "accounts.image = " + image.replace("$", "\\$"));
            if (!put.equals(text)) { before.put(config, text); Files.writeString(config, put, StandardCharsets.UTF_8); }
        }
        Result up = SHELL.run(folder, List.of("docker", "compose", "up", "-d", "--no-deps", "service"), 10 * 60_000L);
        if (up.code() != 0) {
            putBack(before);
            throw new IOException("The service's container could not be made again from " + image + ": " + last(up.out()) + " .env and the settings name " + was + " again; start it with docker compose up -d.");
        }
        long until = System.currentTimeMillis() + HEALTH_MS;
        String said = "";
        while (System.currentTimeMillis() < until) {
            Result h = SHELL.run(folder, List.of("docker", "compose", "exec", "-T", "service", "curl", "-fsS", "http://127.0.0.1:4649/healthz"), 30_000L);
            if (h.code() == 0 && h.out().contains("\"ok\":true")) { said = h.out().strip(); break; }
            try { Thread.sleep(Math.min(2_000, Math.max(50, HEALTH_MS / 10))); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        }
        if (said.isEmpty()) {   // the new image does not come up: everything put back, and the service made again from the image it ran
            putBack(before);
            Result back = SHELL.run(folder, List.of("docker", "compose", "up", "-d", "--no-deps", "service"), 10 * 60_000L);
            throw new IOException("The service was made again from " + image + " but did not answer within " + HEALTH_MS / 1000 + " s, so .env and the settings name " + was + " again and "
                    + (back.code() == 0 ? "the service runs " + was + " again." : "making it again from " + was + " failed too: " + last(back.out()) + " Start it with docker compose up -d.") + " What the new one said: docker compose logs service");
        }
        int v = said.indexOf("\"version\":\"");
        out.println("The service runs " + image + " now" + (v < 0 ? "." : ", and says it is " + said.substring(v + 11, said.indexOf('"', v + 11)) + "."));
        if (Files.isRegularFile(folder.resolve("compose.node.yml"))) {   // the libraries are the second machine's node's: it is updated there, after the service
            out.println("The libraries run on the second machine. Update its node there, in its copy of this folder: set RZ_IMAGE=" + image + " in .env (or copy .env and node-data/config again), then:");
            out.println("    docker compose -f compose.node.yml up -d node");
            out.println("Its libraries stop with the old node and start again from the new image at their next request; a running job goes on from the work it kept.");
        } else out.println("Its libraries stopped with the old service and start again from the new image at their next request; a running job goes on from the work it kept.");
        return 0;
    }

    /** The files an update changed, written back as they were. */
    private static void putBack(Map<Path, String> before) throws IOException {
        for (Map.Entry<Path, String> e : before.entrySet()) Files.writeString(e.getKey(), e.getValue(), StandardCharsets.UTF_8);
    }

    private static String last(String out) { String s = out.strip(); int nl = s.lastIndexOf('\n'); return (nl < 0 ? s : s.substring(nl + 1)).strip(); }
}
