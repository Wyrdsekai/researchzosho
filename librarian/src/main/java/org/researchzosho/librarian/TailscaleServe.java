package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * What this machine's Tailscale serves, read from {@code tailscale serve status --json} (the ServeConfig: TCP ports, web handlers by
 * host:port and mount, the ports Funnel is on, services, foreground sessions), so that {@code share} and {@code phone} change only the
 * one handler each makes: the mount {@code /} that proxies to its door, on its own port of the machine's address ({@code share} on 443,
 * {@code phone} on 8443, two of the three ports Funnel allows), so the two run side by side and neither moves the other's address. Read in Tailscale's own source, v1.104.7
 * (cmd/tailscale/cli/serve_v2.go): {@code tailscale serve --https=443 off} with no {@code --set-path} removes every handler on the port,
 * another program's with it, and {@code funnel ... off} goes the same way; {@code --set-path=/ ... off} removes that mount alone, and
 * Funnel for the port only when no mount is left (ipn/serve.go, RemoveWebHandler).
 */
final class TailscaleServe {
    private TailscaleServe() { }

    private static final ObjectMapper M = new ObjectMapper();

    /** The serve config as Tailscale reports it; null when it cannot be read (an old Tailscale, or none answering). An empty config is an empty object. */
    static JsonNode read(String ts) {
        try {
            Video.Result r = Share.runner.run(List.of(ts, "serve", "status", "--json"), Duration.ofSeconds(30));
            if (r.code() != 0) return null;
            String out = r.out() == null ? "" : r.out().strip();
            int at = out.indexOf('{');
            if (out.equals("null") || out.isEmpty()) return M.createObjectNode();
            if (at < 0) return null;
            JsonNode j = M.readTree(out.substring(at));
            return j != null && j.isObject() ? j : null;
        } catch (Exception e) { return null; }
    }

    /** Whether a handler's proxy is this door: the forms {@code tailscale serve <port>} writes and a person might. */
    static boolean toDoor(String proxy, int door) {
        String p = proxy == null ? "" : proxy.strip().replaceAll("/+$", "");
        for (String f : List.of("http://127.0.0.1:%d", "http://localhost:%d", "127.0.0.1:%d", "localhost:%d", "%d")) if (p.equals(String.format(f, door))) return true;
        return false;
    }

    /** The host:port, on {@code port}, whose mount {@code /} proxies to the door, or null when none does. */
    static String ours(JsonNode sc, int door, int port) {
        if (sc == null) return null;
        for (Iterator<Map.Entry<String, JsonNode>> it = sc.path("Web").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().endsWith(":" + port) && toDoor(e.getValue().path("Handlers").path("/").path("Proxy").asText(""), door)) return e.getKey();
        }
        return null;
    }

    /**
     * Everything the config serves on {@code port} that is not the door's own handler, one line each, in Tailscale's own terms: another
     * mount or another target there, a TCP forward on it, Funnel for another host on it. What is served on other ports is not this door's
     * business: each door keeps to its own port.
     */
    static List<String> others(JsonNode sc, int door, int port) {
        List<String> out = new ArrayList<>();
        if (sc == null) return out;
        String own = ours(sc, door, port);
        for (Iterator<Map.Entry<String, JsonNode>> it = sc.path("Web").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getKey().endsWith(":" + port)) continue;
            for (Iterator<Map.Entry<String, JsonNode>> h = e.getValue().path("Handlers").fields(); h.hasNext(); ) {
                Map.Entry<String, JsonNode> m = h.next();
                if (e.getKey().equals(own) && m.getKey().equals("/")) continue;
                JsonNode v = m.getValue();
                String to = v.hasNonNull("Proxy") ? v.path("Proxy").asText() : v.hasNonNull("Path") ? v.path("Path").asText() : v.hasNonNull("Text") ? "text" : "?";
                out.add("https://" + e.getKey().replaceAll(":443$", "") + m.getKey() + " → " + to);
            }
        }
        JsonNode tcp = sc.path("TCP").path(String.valueOf(port));
        if (!tcp.path("TCPForward").asText("").isEmpty()) out.add("port " + port + " → " + tcp.path("TCPForward").asText());
        for (Iterator<Map.Entry<String, JsonNode>> it = sc.path("AllowFunnel").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getKey().endsWith(":" + port) || e.getKey().equals(own) || !e.getValue().asBoolean(false)) continue;
            if (!hasWeb(sc, e.getKey())) out.add("Funnel on " + e.getKey());
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = sc.path("Foreground").fields(); it.hasNext(); ) {
            JsonNode fg = it.next().getValue();
            if (fg.path("TCP").has(String.valueOf(port))) out.add("a foreground tailscale serve or funnel session on port " + port);
        }
        return out;
    }

    private static boolean hasWeb(JsonNode sc, String hostPort) { return sc.path("Web").has(hostPort); }

    /** Whether Funnel is on for the door's own host:port. */
    static boolean funnel(JsonNode sc, int door, int port) { String own = ours(sc, door, port); return own != null && sc.path("AllowFunnel").path(own).asBoolean(false); }

    /** The command that takes the door's own handler off and nothing else: its mount on its port (and Funnel for the port when no other mount is left there). */
    static List<String> offCommand(String ts, int port) { return List.of(ts, "serve", "--https=" + port, "--set-path=/", "off"); }

    /** The command that puts the door behind the relay on its port: on the tailnet, or public through Funnel. */
    static List<String> onCommand(String ts, int port, int door, boolean funnel) { return List.of(ts, funnel ? "funnel" : "serve", "--bg", "--https=" + port, String.valueOf(door)); }
}
