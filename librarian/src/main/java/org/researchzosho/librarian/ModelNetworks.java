package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The networks with no way out that a model server of this machine is on, and the refusal of every request that comes from one. A model's
 * container on such a network has no route to anything but this machine, and it still reaches this machine at the network's own address:
 * a library listening beyond the loopback would answer it, and a library left open answers whoever reaches it, so a model could ask it to
 * research, fetch a page or change a setting, and its words would leave the machine that way. A model server never needs to ask the
 * library anything (the install's proof and llama-swap ask the model, never the library), so whatever comes from such a network is refused
 * at every door: the pages, the JSON API, MCP over HTTP, the account service and the helpers' front, {@code /healthz} too, whatever the
 * reader list and the settings say.
 *
 * <p>The networks are the ones a model plan of this machine records: this program's model folder and CodeZaiku's
 * ({@code ~/.codezaiku/model}; the same folder where the two share a home), each read for its plan's range when it says {@code network=shut},
 * and for the range its start scripts make their network on with {@code --internal} (the model's, the embeddings server's). They are read
 * again at most every ten seconds. The address tested is the connection's own; behind a proxy the trusted-proxy setting names, the address
 * that proxy says it forwards for is tested as well. A header from anyone else changes nothing.
 */
final class ModelNetworks {
    private ModelNetworks() { }

    private static final Logger LOG = LoggerFactory.getLogger(ModelNetworks.class);

    /** What a refused request is told. */
    static final String REFUSED = "Nothing here answers a request from the network of a model server that has no way out to the network.";

    /** The ranges as read, and when: read again when older than {@link #FRESH_MS}, or when the folders read are other ones (a test's home). */
    private record Read(List<String> folders, List<long[]> ranges, long at) { }
    private static volatile Read read;
    static final long FRESH_MS = 10_000;
    private static final AtomicBoolean SAID = new AtomicBoolean();

    /** The model folders of this machine: this program's, and CodeZaiku's when it is another. */
    static List<Path> folders() {
        Set<Path> out = new LinkedHashSet<>();
        out.add(ModelServer.dir().toAbsolutePath().normalize());
        out.add(Path.of(System.getProperty("user.home")).resolve(".codezaiku").resolve("model").toAbsolutePath().normalize());
        return new ArrayList<>(out);
    }

    private static final Pattern MADE = Pattern.compile("docker network create --internal --subnet (\\S+) ");

    /** The no-way-out ranges the folders record, as {@link ModelServer#range} gives them. */
    static List<long[]> recorded(List<Path> folders) {
        List<long[]> out = new ArrayList<>();
        for (Path d : folders) {
            try {
                Path plan = d.resolve("plan.properties");
                if (Files.isRegularFile(plan)) {
                    Map<String, String> m = ModelServer.readPlan(d);
                    if ("shut".equals(m.get("network"))) add(out, m.get("subnet"));
                }
                for (String s : List.of("run.sh", "run-embed.sh")) {
                    Path f = d.resolve(s);
                    if (!Files.isRegularFile(f)) continue;
                    Matcher m = MADE.matcher(Files.readString(f, StandardCharsets.UTF_8));
                    while (m.find()) add(out, m.group(1));
                }
            } catch (IOException | RuntimeException e) { LOG.debug("The model folder {} could not be read for its networks: {}", d, e.getMessage()); }
        }
        return out;
    }

    private static void add(List<long[]> out, String cidr) {
        long[] r = ModelServer.range(cidr);
        if (r != null && r[1] - r[0] < 65536) out.add(r);   // a network's range, never a whole private block written by hand
    }

    static List<long[]> ranges() {
        List<String> names = new ArrayList<>();
        List<Path> folders = folders();
        for (Path p : folders) names.add(p.toString());
        Read r = read;
        long now = System.currentTimeMillis();
        if (r == null || now - r.at() > FRESH_MS || !r.folders().equals(names)) {
            List<long[]> got = new ArrayList<>(recorded(folders));
            String embed = Embed.recordedSubnet();   // the embeddings server `embed start` put on this program's network with no way out
            if (embed != null) add(got, embed);
            read = r = new Read(names, got, now);
        }
        return r.ranges();
    }

    /** Forgets what was read and whether a refusal was written down: a test's home is read afresh. */
    static void forget() { read = null; SAID.set(false); }

    /** Whether an address lies inside a recorded network (an IPv4 address; an IPv4 address in IPv6 form is read as the IPv4 one). */
    static boolean inside(InetAddress a, List<long[]> ranges) {
        if (a == null || ranges.isEmpty()) return false;
        byte[] b = a.getAddress();
        if (b.length == 16) {   // ::ffff:a.b.c.d
            for (int i = 0; i < 10; i++) if (b[i] != 0) return false;
            if (b[10] != (byte) 0xff || b[11] != (byte) 0xff) return false;
            try { a = InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}); } catch (IOException e) { return false; }
        }
        if (!(a instanceof Inet4Address)) return false;
        long v = 0;
        for (byte x : a.getAddress()) v = v << 8 | (x & 0xff);
        for (long[] r : ranges) if (v >= r[0] && v <= r[1]) return true;
        return false;
    }

    /** Whether this request comes from a recorded network: by the connection's address, or behind a trusted proxy by the one it forwards for. */
    static boolean fromModelNetwork(HttpExchange x) {
        List<long[]> ranges = ranges();
        if (ranges.isEmpty()) return false;
        InetSocketAddress from = x.getRemoteAddress();
        if (from != null && inside(from.getAddress(), ranges)) return true;
        return Proxy.behindTrusted(x) && inside(forwardedFor(x), ranges);
    }

    /** The address a trusted proxy says it forwards for: the last X-Forwarded-For entry, which that proxy appended. */
    private static InetAddress forwardedFor(HttpExchange x) {
        String f = x.getRequestHeaders().getFirst("X-Forwarded-For");
        if (f == null || f.isBlank()) return null;
        String[] parts = f.split(",");
        String last = parts[parts.length - 1].strip();
        if (!last.matches("[0-9a-fA-F:.]+")) return null;
        try { return InetAddress.getByName(last); } catch (IOException | IllegalArgumentException e) { return null; }
    }

    /**
     * Refuses this request when it comes from a recorded network: a 403 with {@link #REFUSED}, written down once for the life of the
     * process (a model server that asks in a loop does not fill the log). True when it was refused.
     */
    static boolean refused(HttpExchange x) throws IOException {
        if (!fromModelNetwork(x)) return false;
        if (SAID.compareAndSet(false, true)) {
            InetSocketAddress from = x.getRemoteAddress();
            LOG.warn("A request from {} came from the network of a model server with no way out, and was refused; every such request is refused, and this is written down once until the next start.", from == null ? "an unknown address" : from.getAddress().getHostAddress());
        }
        byte[] b = ("{\"error\":{\"code\":\"forbidden\",\"message\":\"" + REFUSED + "\"}}").getBytes(StandardCharsets.UTF_8);
        x.getRequestBody().close();
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.sendResponseHeaders(403, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
        return true;
    }
}
