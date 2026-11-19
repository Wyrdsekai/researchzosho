package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.drive.Gated;
import org.researchzosho.tools.Addresses;
import org.researchzosho.tools.Fetch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The web of a library in a container goes through its node's gate (0.8.0): an HTTP proxy that resolves a name itself, refuses this
 * machine, private networks, link-local addresses (the cloud's metadata), multicast and the like by the one list the fetcher has,
 * and connects to the address it checked. CONNECT for https, a plain request for http, bytes both ways; a redirect is a new request,
 * checked again. The library's own clients reach it by the Java settings and the environment its node gives it, without asking DNS.
 */
class WebGateTest {
    final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach void close() { for (AutoCloseable c : open) { try { c.close(); } catch (Exception ignored) { } } }

    static InetAddress at(String literal) { try { return InetAddress.getByName(literal); } catch (IOException e) { throw new IllegalStateException(e); } }

    /** A gate that looks names up in {@code names} (a literal is itself), by the list given. */
    static WebGate gate(Map<String, String> names, Set<String> named, Function<InetAddress, Addresses.Kind> rule) {
        return new WebGate("lib_test", named, h -> names.containsKey(h) ? new InetAddress[]{at(names.get(h))} : Addresses.literal(h) ? new InetAddress[]{at(h)} : new InetAddress[0], rule);
    }

    @Test
    void everyKindOfAddressTheListNamesIsRefused() {
        WebGate g = gate(Map.of("intranet.test", "10.1.2.3", "metadata.test", "169.254.169.254"), Set.of(), Addresses::kind);
        for (String refused : List.of("127.0.0.1", "127.8.9.10", "::1", "10.1.2.3", "172.16.5.4", "172.31.255.1", "192.168.1.20", "100.64.0.1", "fc00::1", "fd00:ec2::254",
                "169.254.169.254", "fe80::1", "224.0.0.1", "ff02::1", "0.0.0.0", "0.1.2.3", "255.255.255.255", "240.0.0.1", "::",
                "::ffff:10.0.0.1", "::ffff:169.254.169.254", "64:ff9b::a9fe:a9fe", "2002:a9fe:a9fe::1", "::169.254.169.254", "intranet.test", "metadata.test")) {
            WebGate.Decision d = g.check(refused, 443);
            assertNull(d.to(), refused + " is refused");
            assertTrue(d.why().contains("which a library of this service does not reach"), d.why());
        }
        for (String open : List.of("93.184.216.34", "1.1.1.1", "2606:4700:4700::1111", "172.32.0.1", "100.128.0.1")) assertNotNull(g.check(open, 443).to(), open + " is on the open internet");
        assertEquals(Addresses.Kind.LINK_LOCAL, Addresses.kind(at("169.254.169.254")));
        assertEquals(Addresses.Kind.PRIVATE, Addresses.kind(at("fd00:ec2::254")), "the IPv6 metadata address is a private one");
        assertEquals(Addresses.Kind.LINK_LOCAL, Addresses.kind(at("64:ff9b::a9fe:a9fe")), "NAT64's form of 169.254.169.254");
        assertTrue(g.check("no-such-name.test", 443).why().contains("was not found"));
    }

    @Test
    void aNameIsRefusedWhenAnyOfItsAddressesIs() {
        WebGate g = new WebGate("lib_test", Set.of(), h -> new InetAddress[]{at("93.184.216.34"), at("10.0.0.7")}, Addresses::kind);
        assertNull(g.check("both.test", 443).to(), "one public and one private address: refused");
    }

    /** The gate on a TCP port of the test's own, as the node runs it on a library's socket. */
    InetSocketAddress serve(WebGate g) throws IOException {
        ServerSocketChannel s = ServerSocketChannel.open();
        s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        open.add(s);
        Thread.ofVirtual().start(() -> { while (s.isOpen()) { try { SocketChannel c = s.accept(); Thread.ofVirtual().start(() -> { try { g.handle(c); } catch (IOException ignored) { } }); } catch (IOException e) { return; } } });
        return (InetSocketAddress) s.getLocalAddress();
    }

    /** A web server on the loopback: a page, a page that echoes what was posted, and a redirect to an address on a private network. */
    int web() throws IOException {
        HttpServer h = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        h.createContext("/hello", x -> { byte[] b = ("hello from " + x.getRequestHeaders().getFirst("Host") + " at " + x.getRequestURI()).getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        h.createContext("/echo", x -> { byte[] in = x.getRequestBody().readAllBytes(); byte[] b = ("got " + in.length + " bytes").getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        h.createContext("/away", x -> { x.getResponseHeaders().set("Location", "http://intranet.test/inside"); x.sendResponseHeaders(302, -1); x.close(); });
        h.start();
        open.add(() -> h.stop(0));
        return h.getAddress().getPort();
    }

    @Test
    void plainHttpConnectAndARedirectThroughTheGate() throws Exception {
        int port = web();
        // the public site of this test is on the loopback: this gate lets the loopback through, and nothing else the list names
        WebGate g = gate(Map.of("public.test", "127.0.0.1", "intranet.test", "10.1.2.3"), Set.of(), a -> a.isLoopbackAddress() ? null : Addresses.kind(a));
        InetSocketAddress gate = serve(g);
        HttpClient c = HttpClient.newBuilder().proxy(ProxySelector.of(gate)).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<String> hello = c.send(HttpRequest.newBuilder(URI.create("http://public.test:" + port + "/hello?x=1")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, hello.statusCode());
        assertEquals("hello from public.test:" + port + " at /hello?x=1", hello.body(), "the site was asked as itself, the path and query as they were");
        byte[] big = new byte[300_000];
        HttpResponse<String> posted = c.send(HttpRequest.newBuilder(URI.create("http://public.test:" + port + "/echo")).POST(HttpRequest.BodyPublishers.ofByteArray(big)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("got 300000 bytes", posted.body(), "bytes the other way");
        HttpResponse<String> away = c.send(HttpRequest.newBuilder(URI.create("http://public.test:" + port + "/away")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, away.statusCode(), "the redirect is a request of its own, to a private address: refused");
        assertTrue(away.body().contains("intranet.test (10.1.2.3) is an address on a private network"), away.body());

        // CONNECT, as https goes: the tunnel carries bytes both ways
        try (ServerSocket echo = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> { try (Socket s = echo.accept()) { InputStream i = s.getInputStream(); OutputStream o = s.getOutputStream(); byte[] b = i.readNBytes(4); o.write(("pong:" + new String(b, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)); o.flush(); } catch (IOException ignored) { } });
            try (Socket s = new Socket(gate.getAddress(), gate.getPort())) {
                s.getOutputStream().write(("CONNECT public.test:" + echo.getLocalPort() + " HTTP/1.1\r\nHost: public.test\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                String status = readLine(s.getInputStream());
                assertEquals("HTTP/1.1 200 Connection established", status);
                assertEquals("", readLine(s.getInputStream()));
                s.getOutputStream().write("ping".getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
                assertEquals("pong:ping", new String(s.getInputStream().readNBytes(9), StandardCharsets.UTF_8));
            }
        }
        // CONNECT to the cloud's metadata, and to this machine's ssh: refused, and nothing is connected
        for (String to : List.of("169.254.169.254:80", "10.1.2.3:22", "[fd00:ec2::254]:80", "127.0.0.1:22")) {
            WebGate strict = gate(Map.of(), Set.of(), Addresses::kind);
            InetSocketAddress sg = serve(strict);
            try (Socket s = new Socket(sg.getAddress(), sg.getPort())) {
                s.getOutputStream().write(("CONNECT " + to + " HTTP/1.1\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                assertTrue(readLine(s.getInputStream()).startsWith("HTTP/1.1 403"), to);
            }
        }
    }

    /**
     * Under an account service (stage 3) the gate counts each request to the web against the use it carries, in a header named for the
     * proxy that Java's client sends on a CONNECT as well, with the bytes both ways, and never passes that header to the site; it
     * refuses a request whose account may fetch no more; a server the settings name is the service's own and is not counted.
     */
    @Test
    void eachRequestIsCountedAgainstItsUseAndRefusedWhenItsAccountMayFetchNoMore() throws Exception {
        int port = web();
        List<String> sawHeader = new CopyOnWriteArrayList<>();
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        site.createContext("/", x -> { sawHeader.add(String.valueOf(x.getRequestHeaders().getFirst(Gated.WEB_USE))); byte[] b = "a page".getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        site.start();
        open.add(() -> site.stop(0));
        List<String> counted = new CopyOnWriteArrayList<>();
        WebGate.Counting counting = new WebGate.Counting() {
            @Override public String refused(String use) { return "use_full".equals(use) ? "This account has fetched as many pages this month as it may (1)." : null; }
            @Override public void counted(String use, long bytes) { counted.add(use + ":" + bytes); }
        };
        WebGate g = new WebGate("lib_test", WebGate.named(Map.of("RESEARCHZOSHO_SEARXNG", "http://search.lan:" + port)),
                h -> Map.of("public.test", "127.0.0.1", "search.lan", "127.0.0.1").containsKey(h) ? new InetAddress[]{at("127.0.0.1")} : new InetAddress[0], a -> null, counting);
        InetSocketAddress gate = serve(g);
        HttpClient c = HttpClient.newBuilder().proxy(ProxySelector.of(gate)).build();
        HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create("http://public.test:" + site.getAddress().getPort() + "/page")).header(Gated.WEB_USE, "use_1").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("a page", r.body());
        assertEquals(List.of("null"), sawHeader, "the site never sees the use");
        until(() -> counted.size() == 1);
        assertTrue(counted.get(0).startsWith("use_1:") && Long.parseLong(counted.get(0).substring(6)) > 50, counted.toString());
        // https: Java's own client sends the use on the CONNECT (this pins it); the tunnel is counted when it closes
        try { c.send(HttpRequest.newBuilder(URI.create("https://public.test:" + port + "/hello")).header(Gated.WEB_USE, "use_2").timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString()); }
        catch (IOException notTls) { /* the test's server speaks no TLS: the handshake fails after the tunnel was made */ }
        until(() -> counted.stream().anyMatch(x -> x.startsWith("use_2:")));
        // a request with none: counted as the library's own
        c.send(HttpRequest.newBuilder(URI.create("http://public.test:" + site.getAddress().getPort() + "/page")).build(), HttpResponse.BodyHandlers.ofString());
        until(() -> counted.stream().anyMatch(x -> x.startsWith("null:")));
        // the account may fetch no more: refused, and nothing reaches the site
        int before = sawHeader.size();
        HttpResponse<String> no = c.send(HttpRequest.newBuilder(URI.create("http://public.test:" + site.getAddress().getPort() + "/page")).header(Gated.WEB_USE, "use_full").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(402, no.statusCode());
        assertTrue(no.body().contains("as many pages this month"), no.body());
        assertEquals(before, sawHeader.size());
        // a server the settings name: let through, not counted
        int n = counted.size();
        assertEquals(200, c.send(HttpRequest.newBuilder(URI.create("http://search.lan:" + port + "/hello")).header(Gated.WEB_USE, "use_1").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        Thread.sleep(300);
        assertEquals(n, counted.size(), "the service's own server is not a page of the web");
    }

    static void until(BooleanSupplier is) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (!is.getAsBoolean()) { assertTrue(System.currentTimeMillis() < end, "within 10 s"); Thread.sleep(20); }
    }

    /** A server the service's settings name on the private network is let through by its name and port, and nothing else at that address. */
    @Test
    void anAddressTheSettingsNameIsLetThroughByName() throws Exception {
        int port = web();
        WebGate g = gate(Map.of("models.lan", "127.0.0.1"), WebGate.named(Map.of("RESEARCHZOSHO_DRIVE", "http://models.lan:" + port + "/v1", "RESEARCHZOSHO_EMBED", "http://127.0.0.1:9")), Addresses::kind);
        InetSocketAddress gate = serve(g);
        HttpClient c = HttpClient.newBuilder().proxy(ProxySelector.of(gate)).build();
        assertEquals(200, c.send(HttpRequest.newBuilder(URI.create("http://models.lan:" + port + "/hello")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, c.send(HttpRequest.newBuilder(URI.create("http://models.lan:" + (port == 65535 ? 1 : port + 1) + "/hello")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), "another port of it: refused");
        assertTrue(WebGate.named(Map.of("A", "http://127.0.0.1:9", "B", "http://localhost:8")).isEmpty(), "this machine's own are relays, not names");
    }

    /**
     * The library's own clients, in a process of their own as a container's library is: given the gate by the Java settings and the
     * environment its node puts there, the web client, a URL connection and the fetcher all go through it, and none asks DNS for the
     * name (public.test has no address anywhere but in the gate).
     */
    @Test
    void theLibrarysClientsGoThroughTheGate(@TempDir Path tmp) throws Exception {
        int port = web();
        WebGate g = gate(Map.of("public.test", "127.0.0.1"), Set.of(), a -> a.isLoopbackAddress() ? null : Addresses.kind(a));
        InetSocketAddress gate = serve(g);
        String url = "http://public.test:" + port + "/hello";
        List<String> cmd = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java"), "-Duser.home=" + tmp,
                "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=" + gate.getPort(), "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=" + gate.getPort(),
                "-cp", System.getProperty("java.class.path"), ClientsInAContainer.class.getName(), url));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put(Addresses.THROUGH_GATE, "on");
        Process p = pb.start();
        String said = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertTrue(said.contains("web client: 200 hello from public.test:" + port), said);
        assertTrue(said.contains("url connection: hello from public.test:" + port), said);
        assertTrue(said.contains("fetcher: 200 hello from public.test:" + port), said);
        assertTrue(said.contains("dns: none"), "the name has no address outside the gate: " + said);
    }

    /** What a library's process runs to reach the web, run in a process of its own. */
    public static final class ClientsInAContainer {
        public static void main(String[] a) throws Exception {
            String url = a[0];
            try { InetAddress.getByName(URI.create(url).getHost()); System.out.println("dns: found"); } catch (IOException e) { System.out.println("dns: none"); }
            HttpResponse<String> r = HttpClient.newBuilder().build().send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
            System.out.println("web client: " + r.statusCode() + " " + r.body());
            try (InputStream in = URI.create(url).toURL().openStream()) { System.out.println("url connection: " + new String(in.readAllBytes(), StandardCharsets.UTF_8)); }
            Fetch.Result f = Fetch.get(url, Duration.ofSeconds(20));
            System.out.println("fetcher: " + f.status() + " " + new String(f.body(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void relaysPassBytesBetweenAPortAndASocketBothWays(@TempDir Path tmp) throws Exception {
        try (ServerSocket echo = new ServerSocket(0, 5, InetAddress.getLoopbackAddress()); Relays far = new Relays(); Relays near = new Relays()) {
            Thread.ofVirtual().start(() -> { try (Socket s = echo.accept()) { byte[] all = s.getInputStream().readAllBytes(); s.getOutputStream().write(("echo " + all.length).getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) { } });
            Path sock = tmp.resolve("to-echo.sock");
            far.unixToTcp(sock, "127.0.0.1", echo.getLocalPort());   // the node's side: the socket, on to the one address
            int port = near.tcpToUnix(InetAddress.getLoopbackAddress(), 0, sock);   // the library's side: a port of its loopback, on to the socket
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
                s.getOutputStream().write(new byte[123_456]);
                s.shutdownOutput();   // the end of what was sent reaches the far side
                assertEquals("echo 123456", new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int ch; (ch = in.read()) >= 0; ) { if (ch == '\n') break; if (ch != '\r') b.append((char) ch); }
        return b.toString();
    }
}
