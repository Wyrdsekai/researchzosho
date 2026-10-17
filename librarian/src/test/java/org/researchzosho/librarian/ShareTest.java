package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * `share` (AFTER_0_6 §5): the relay chosen by what the machine has, the settings written, sign-in turned on, each known refusal answered
 * with its next step; the service's watcher opening the door and running the tunnel while the setting says so and nothing otherwise;
 * the door's own rule, that nobody unlisted gets in through it. The programs are stand-ins with canned answers, as the plan asked.
 */
class ShareTest {

    String realHome; Share.Runner realRunner; Function<String, String> realWhich; BooleanSupplier realService; Function<Integer, List<String>> realTunnel; long realPoll; Boolean realSignIn;
    Share.Interactive realInteractive; Video.Downloader realDownloader; Supplier<String> realOs, realArch; Boolean realAtKeyboard; BufferedReader realInput;
    final List<String> ran = new ArrayList<>(), typed = new ArrayList<>();
    String machine = "linux";
    Map<String, String> programs = Map.of();
    Map<String, Video.Result> answers = Map.of();
    boolean serviceUp = true;

    @BeforeEach
    void scratch(@TempDir Path tmp) throws Exception {
        realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        realRunner = Share.runner; realWhich = Share.which; realService = Share.serviceRunning; realTunnel = Share.tunnelCommand; realPoll = Share.pollMs; realSignIn = WebAccess.OVERRIDE;
        WebAccess.OVERRIDE = null;
        Share.which = p -> programs.get(p);
        Share.serviceRunning = () -> serviceUp;
        Share.runner = (cmd, t) -> { String line = String.join(" ", cmd); ran.add(line); for (var e : answers.entrySet()) if (line.contains(e.getKey())) return e.getValue(); return new Video.Result(1, "unexpected: " + line); };
        realInteractive = Share.interactive; realDownloader = Share.downloader; realOs = Share.os; realArch = Share.arch; realAtKeyboard = Interaction.OVERRIDE; realInput = Interaction.INPUT;
        Share.interactive = (cmd, limit) -> { typed.add(String.join(" ", cmd)); return 0; };
        Share.downloader = (url, to) -> Files.writeString(to, "binary from " + url);
        Share.addressWaitMs = 0;   // no service here to report a tunnel's address
        Share.os = () -> machine; Share.arch = () -> "arm64";
        Interaction.OVERRIDE = false;   // nobody at the keyboard unless a test says so
        Share.askOwnerAccount = false;   // the owner's account step has its own test
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", realHome); Config.invalidate();
        Share.runner = realRunner; Share.which = realWhich; Share.serviceRunning = realService; Share.tunnelCommand = realTunnel; Share.pollMs = realPoll; WebAccess.OVERRIDE = realSignIn;
        Share.askOwnerAccount = true; Share.addressWaitMs = 45_000; Share.interactive = realInteractive; Share.downloader = realDownloader; Share.os = realOs; Share.arch = realArch; Interaction.OVERRIDE = realAtKeyboard; Interaction.INPUT = realInput;
    }

    /** A person at the keyboard who answers the questions in order (a blank line is Enter, which is yes). */
    void person(String... answers) { Interaction.OVERRIDE = true; Interaction.INPUT = new BufferedReader(new StringReader(String.join("\n", answers) + "\n")); }

    /** A Tailscale that answers "logged in" only once `tailscale up` has run on the person's screen. */
    void loggedInAfterUp() {
        Share.runner = (cmd, t) -> { String line = String.join(" ", cmd); ran.add(line);
            if (line.contains("status --json")) return new Video.Result(0, typed.stream().anyMatch(x -> x.endsWith(" up")) ? RUNNING : "{\"BackendState\":\"NeedsLogin\"}");
            if (line.contains("serve --bg") || line.contains("funnel --bg")) return new Video.Result(0, "ok");
            return new Video.Result(1, "unexpected: " + line); };
    }

    /** A stand-in for the programs that appear once installed: the install command, when it runs, puts the program on the machine. */
    void installsPut(String marker, String program, String path) {
        Share.interactive = (cmd, limit) -> { String line = String.join(" ", cmd); typed.add(line); if (line.contains(marker)) { Map<String, String> m = new HashMap<>(programs); m.put(program, path); programs = m; } return 0; };
    }

    static LibraryStore lib(Path tmp) throws Exception { LibraryStore s = new LibraryStore(tmp.resolve("lib")); s.init(); return s; }
    static final String RUNNING = "{\"BackendState\":\"Running\",\"Self\":{\"DNSName\":\"mylibrary.tail1234.ts.net.\"}}";

    @Test
    void withoutEitherProgramAndNobodyAtTheKeyboardShareSaysWhereToGetThemAndChangesNothing(@TempDir Path tmp) throws Exception {
        Share.Outcome o = Share.share(lib(tmp), List.of("--tailnet"), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(1, o.code());
        assertTrue(o.text().contains("tailscale.com/download") && o.text().contains("researchzosho share --cloudflare") && o.text().contains("Nothing is installed without your yes"), o.text());
        assertEquals("off", Share.kind()); assertFalse(WebAccess.signInRequired(), "nothing was turned on"); assertTrue(ran.isEmpty() && typed.isEmpty(), "nothing was run");
    }

    @Test
    void onLinuxShareInstallsTailscaleItsOwnWayLogsInAndServes(@TempDir Path tmp) throws Exception {
        installsPut("tailscale.com/install.sh | sh", "tailscale", "/usr/bin/tailscale");
        loggedInAfterUp();
        person("", "", "");   // Enter three times: share with Tailscale, install it, log in
        ByteArrayOutputStream steps = new ByteArrayOutputStream();
        Share.Outcome o = Share.share(lib(tmp), List.of("--tailnet"), new PrintStream(steps, true, StandardCharsets.UTF_8));
        assertEquals(0, o.code(), steps + o.text());
        String s = steps.toString(StandardCharsets.UTF_8);
        assertTrue(s.contains("Install Tailscale, the better long-term setup?") && s.contains("Install Tailscale with its own install script?") && s.contains("Log in to Tailscale now?"), s);
        assertEquals(List.of("sh -c curl -fsSL https://tailscale.com/install.sh | sh", "sudo /usr/bin/tailscale up", "sudo /usr/bin/tailscale set --operator=" + System.getProperty("user.name")), typed, "the vendor's script, the login, the operator permission: each on the person's own screen");
        assertTrue(o.text().contains("Shared on your tailnet at https://mylibrary.tail1234.ts.net"), o.text());
        assertEquals("tailscale", Share.kind());
    }

    @Test
    void aScriptsYesGivesTheLoginTenMinutesThenLeavesTheLinkOpen(@TempDir Path tmp) throws Exception {
        programs = Map.of("tailscale", "/usr/bin/tailscale");
        answers = Map.of("status --json", new Video.Result(0, "{\"BackendState\":\"NeedsLogin\"}"));   // nobody logs in
        List<Duration> limits = new ArrayList<>();
        Share.interactive = (cmd, limit) -> { typed.add(String.join(" ", cmd)); limits.add(limit); return String.join(" ", cmd).endsWith(" up") ? Share.TIMED_OUT : 0; };
        ByteArrayOutputStream steps = new ByteArrayOutputStream();
        Share.Outcome o = Share.share(lib(tmp), List.of("--yes", "--tailscale"), new PrintStream(steps, true, StandardCharsets.UTF_8));
        String s = steps.toString(StandardCharsets.UTF_8);
        assertEquals(1, o.code(), s + o.text());
        assertEquals(List.of("sudo /usr/bin/tailscale up"), typed, "the login is the one command typed; the operator permission waits for a login");
        assertEquals(List.of(Duration.ofMinutes(10)), limits, "a script's yes has nobody to end the wait: the login gets ten minutes");
        assertTrue(s.contains("Nobody logged in within 10 minutes. The link above still works: open it, log in, then researchzosho share again."), s);
        assertTrue(o.text().contains("not logged in"), o.text());
        assertEquals("off", Share.kind());
    }

    @Test
    void aPersonAtTheKeyboardWaitsForTheLoginAsLongAsTheyLike(@TempDir Path tmp) throws Exception {
        programs = Map.of("tailscale", "/usr/bin/tailscale");
        loggedInAfterUp();
        List<Duration> limits = new ArrayList<>();
        Share.interactive = (cmd, limit) -> { typed.add(String.join(" ", cmd)); limits.add(limit); return 0; };
        person("", "");   // Enter: share with Tailscale, log in
        Share.Outcome o = Share.share(lib(tmp), List.of("--tailnet"), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(0, o.code(), o.text());
        assertNull(limits.get(0), "tailscale up on a person's screen is waited for, as it waits itself; Ctrl-C is theirs");
    }

    @Test
    void onAMacWithoutHomebrewShareInstallsHomebrewFirstThenTailscale(@TempDir Path tmp) throws Exception {
        machine = "darwin";
        installsPut("Homebrew/install/HEAD/install.sh", "brew", "/opt/homebrew/bin/brew");
        Share.Interactive brewInstaller = Share.interactive;
        Share.interactive = (cmd, limit) -> { int rc = brewInstaller.run(cmd, limit); String line = String.join(" ", cmd); if (line.equals("/opt/homebrew/bin/brew install tailscale")) { Map<String, String> m = new HashMap<>(programs); m.put("tailscale", "/opt/homebrew/bin/tailscale"); programs = m; } return rc; };
        loggedInAfterUp();
        Share.Outcome o = Share.share(lib(tmp), List.of("--yes", "--tailnet"), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(0, o.code(), o.text());
        assertEquals(List.of("/bin/bash -c /bin/bash -c \"$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\"", "/opt/homebrew/bin/brew install tailscale", "sudo /opt/homebrew/bin/brew services start tailscale",
                "sudo /opt/homebrew/bin/tailscale up", "sudo /opt/homebrew/bin/tailscale set --operator=" + System.getProperty("user.name")), typed, "Homebrew first, then Tailscale through it, then the login");
        assertEquals("tailscale", Share.kind());
    }

    @Test
    void aNoToTailscaleFetchesCloudflaredIntoTheLibrarysOwnFolder(@TempDir Path tmp) throws Exception {
        machine = "darwin";
        person("n", "");   // no to Tailscale, Enter for the tunnel
        answers = Map.of("tar -xzf", new Video.Result(0, ""));
        Share.downloader = (url, to) -> { assertTrue(url.endsWith("/releases/latest/download/cloudflared-darwin-arm64.tgz"), url); Files.writeString(to, "tgz"); Files.writeString(to.getParent().resolve("cloudflared"), "binary"); };
        ByteArrayOutputStream steps = new ByteArrayOutputStream();
        Share.Outcome o = Share.share(lib(tmp), List.of("--tailnet"), new PrintStream(steps, true, StandardCharsets.UTF_8));
        assertEquals(0, o.code(), steps + o.text());
        assertTrue(steps.toString(StandardCharsets.UTF_8).contains("Use a Cloudflare quick tunnel instead?") && steps.toString(StandardCharsets.UTF_8).contains("fetching cloudflared (cloudflared-darwin-arm64.tgz)"), steps.toString());
        assertTrue(Files.isRegularFile(Share.binDir().resolve("cloudflared")), "fetched into the library's own folder");
        assertTrue(typed.isEmpty(), "no install script, no password: nothing on the person's screen");
        assertEquals("cloudflare", Share.kind());
        assertTrue(o.text().contains("quick tunnel"), o.text());
        // on Linux the binary comes as it is
        machine = "linux"; Share.downloader = (url, to) -> { assertTrue(url.endsWith("/cloudflared-linux-arm64"), url); Files.writeString(to, "binary"); };
        assertEquals("cloudflared", Path.of(Share.fetchCloudflared(new PrintStream(new ByteArrayOutputStream()))).getFileName().toString());
    }

    @Test
    void theLibrariansToolTurnsTheRelayOnWhenTheProgramIsThereAndNamesTheStepWhenItIsNot(@TempDir Path tmp) throws Exception {
        LibraryStore store = lib(tmp);
        Patrons.set(store, "did:key:owner", "Owner", Patrons.Level.write);
        LibraryProtocol p = new LibraryProtocol(store);
        var who = Share.M.createObjectNode(); who.putObject("patron").put("did", "did:key:owner").put("runtime", "chat");
        var status = p.share(who.deepCopy().put("op", "status"));
        assertTrue(status.path("ok").asBoolean() && status.path("text").asText().contains("shared: no"), status.toString());
        var missing = p.share(who.deepCopy().put("op", "tailscale"));
        assertFalse(missing.path("ok").asBoolean()); assertTrue(missing.path("text").asText().contains("the person says /share in the chat"), missing.toString());
        assertTrue(typed.isEmpty(), "a tool installs nothing: it has no keyboard to ask with");
        programs = Map.of("tailscale", "/usr/bin/tailscale");
        answers = Map.of("status --json", new Video.Result(0, RUNNING), "funnel --bg 4651", new Video.Result(0, "ok"), "serve --bg 4651", new Video.Result(0, "ok"));
        var on = p.share(who.deepCopy().put("op", "tailscale"));
        assertTrue(on.path("ok").asBoolean() && on.path("address").asText().equals("https://mylibrary.tail1234.ts.net"), on.toString());
        assertEquals("funnel", Share.kind(), "op=tailscale is the lasting public address");
        var tailnet = p.share(who.deepCopy().put("op", "tailnet"));
        assertTrue(tailnet.path("ok").asBoolean(), tailnet.toString()); assertEquals("tailscale", Share.kind(), "op=tailnet keeps it to the tailnet");
        Patrons.setDefault(store, Patrons.Level.read);
        var reader = Share.M.createObjectNode(); reader.putObject("patron").put("did", "did:key:nobody");
        assertThrows(ProtocolError.class, () -> p.share(reader.put("op", "off")), "write access");
    }

    @Test
    void withTailscaleLoggedInShareServesTheDoorAndWritesTheSettings(@TempDir Path tmp) throws Exception {
        programs = Map.of("tailscale", "/usr/bin/tailscale", "cloudflared", "/usr/bin/cloudflared");
        answers = Map.of("status --json", new Video.Result(0, RUNNING), "serve --bg 4651", new Video.Result(0, "Available within your tailnet:\nhttps://mylibrary.tail1234.ts.net/\n|-- proxy http://127.0.0.1:4651"));
        Share.Outcome o = Share.share(lib(tmp), List.of("--tailnet"));
        assertEquals(0, o.code(), o.text());
        assertTrue(o.text().startsWith("Sign-in is on now:"), "the first open says what it turned on: " + o.text());
        assertTrue(o.text().contains("Shared on your tailnet at https://mylibrary.tail1234.ts.net") && o.text().contains("researchzosho share\n") && o.text().contains("reader invite"), o.text());
        assertEquals("tailscale", Share.kind()); assertEquals("https://mylibrary.tail1234.ts.net", Share.address()); assertTrue(WebAccess.signInRequired());
        assertEquals(List.of("/usr/bin/tailscale status --json", "/usr/bin/tailscale serve --bg 4651"), ran);
        assertEquals("https://mylibrary.tail1234.ts.net/join?code=", Invites.address() + "/join?code=", "join links are written with the shared address");
        // serve not yet approved for the node: tailscale prints the link and polls, so the runner's limit ends it (124) and the refusal says the step
        answers = Map.of("status --json", new Video.Result(0, RUNNING), "serve --bg 4651", new Video.Result(124, "\nServe is not enabled on your tailnet.\nTo enable, visit:\n\n         https://login.tailscale.com/f/serve?node=abc\n\n(timed out after 60 s)"));
        Share.Outcome notYet = Share.share(lib(tmp), List.of("--tailnet"));
        assertEquals(1, notYet.code(), notYet.text());
        assertTrue(notYet.text().contains("Serve is not approved for this machine yet") && notYet.text().contains("https://login.tailscale.com/f/serve?node=abc"), notYet.text());
        // public: funnel, at the same address
        answers = Map.of("status --json", new Video.Result(0, RUNNING), "funnel --bg 4651", new Video.Result(0, "Available on the internet:\nhttps://mylibrary.tail1234.ts.net/"));
        o = Share.share(lib(tmp), List.of());   // public is the default
        assertEquals(0, o.code(), o.text());
        assertTrue(o.text().startsWith("Public at https://mylibrary.tail1234.ts.net (Tailscale Funnel)") && o.text().contains("share --tailnet"), "sign-in was on already, so nothing to say about it: " + o.text());
        assertEquals("funnel", Share.kind(), "public is the default: share with no option is Funnel");
        assertTrue(Share.share(lib(tmp), List.of("--public")).text().startsWith("Public at"), "--public is accepted and means the default");
        assertEquals("funnel", Share.kind());
        // status and off
        answers = Map.of("serve status", new Video.Result(0, "https://mylibrary.tail1234.ts.net (Funnel on)\n|-- / proxy http://127.0.0.1:4651"), "funnel --https=443 off", new Video.Result(0, ""), "serve --https=443 off", new Video.Result(0, ""));
        String status = Share.share(lib(tmp), List.of("status")).text();
        assertTrue(status.contains("shared: public, Tailscale Funnel at https://mylibrary.tail1234.ts.net") && status.contains("door: 127.0.0.1:4651 (the service is running)") && status.contains("Funnel on"), status);
        o = Share.share(lib(tmp), List.of("off"));
        assertTrue(o.text().startsWith("Not shared any more") && o.text().contains("readers stay"), o.text());
        assertEquals("off", Share.kind()); assertEquals("", Share.address()); assertTrue(WebAccess.signInRequired(), "sign-in stays on");
        assertTrue(ran.get(ran.size() - 2).endsWith("funnel --https=443 off") && ran.get(ran.size() - 1).endsWith("serve --https=443 off"), ran.toString());
        assertEquals("shared: no (researchzosho share puts it behind a relay: reachable now; --tailscale for an address that lasts)", Share.statusLine());
    }

    @Test
    void eachKnownRefusalGetsItsNextStep(@TempDir Path tmp) throws Exception {
        programs = Map.of("tailscale", "/usr/bin/tailscale");
        LibraryStore store = lib(tmp);
        answers = Map.of("status --json", new Video.Result(0, "{\"BackendState\":\"NeedsLogin\"}"));
        Share.Outcome o = Share.share(store, List.of("--tailnet"));
        assertEquals(1, o.code()); assertTrue(o.text().contains("Tailscale is not logged in. Run `tailscale up`"), o.text()); assertEquals("off", Share.kind());
        answers = Map.of("status --json", new Video.Result(0, RUNNING), "serve --bg", new Video.Result(1, "Access denied: tailscale serve requires the operator permission. Run: sudo tailscale set --operator=$USER"));
        o = Share.share(store, List.of("--tailnet"));
        assertEquals(1, o.code()); assertTrue(o.text().contains("sudo tailscale set --operator=" + System.getProperty("user.name")), o.text()); assertEquals("off", Share.kind(), "nothing recorded on a refusal");
        answers = Map.of("status --json", new Video.Result(0, RUNNING), "serve --bg", new Video.Result(1, "error: HTTPS is not enabled for this tailnet. Enable HTTPS certificates in the admin console."));
        assertTrue(Share.share(store, List.of("--tailnet")).text().contains("https://login.tailscale.com/admin/dns"));
        answers = Map.of("status --json", new Video.Result(0, RUNNING), "funnel --bg", new Video.Result(1, "Funnel not enabled on this node. To enable it, visit: https://login.tailscale.com/f/funnel?node=abc"));
        o = Share.share(store, List.of());
        assertTrue(o.text().contains("Funnel is not approved for this machine yet") && o.text().contains("\"attr\": [\"funnel\"]") && o.text().contains("https://login.tailscale.com/f/funnel?node=abc"), o.text());
        programs = Map.of("cloudflared", "/opt/homebrew/bin/cloudflared");
        serviceUp = true;
        o = Share.share(store, List.of("--cloudflare", "--mail", "ann@example.com,*@example.org"));
        assertEquals(0, o.code(), o.text());
        assertTrue(o.text().contains("quick tunnel, for ann@example.com, *@example.org") && o.text().contains("within half a minute"), o.text());
        assertEquals("cloudflare", Share.kind()); assertEquals("ann@example.com,*@example.org", Config.get(Share.MAIL));
        assertTrue(Share.tunnelCommandNow(4651).containsAll(List.of("/opt/homebrew/bin/cloudflared", "tunnel", "--no-autoupdate", "--url", "http://127.0.0.1:4651", "--allowed-mail=ann@example.com", "--allowed-mail=*@example.org")), Share.tunnelCommandNow(4651).toString());
        assertTrue(Share.statusLine().contains("no address yet"));
        assertEquals(2, Share.share(store, List.of("--sideways")).code());
    }

    static HttpResponse<String> get(String url, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET();
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static boolean stateSays(String text) { try { return Files.readString(Share.stateFile()).contains(text); } catch (java.io.IOException e) { return false; } }

    static void until(BooleanSupplier ok, String what) throws InterruptedException {
        for (int i = 0; i < 100 && !ok.getAsBoolean(); i++) Thread.sleep(50);
        assertTrue(ok.getAsBoolean(), what);
    }

    @Test
    void theServicesWatcherOpensTheDoorRunsTheTunnelAndStopsBothWhenShareIsOff(@TempDir Path tmp) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"), "the stand-in tunnel is a shell command");
        LibraryStore store = lib(tmp);
        Patrons.set(store, "did:key:owner", "Owner", Patrons.Level.write);
        Share.pollMs = 50;
        Share.tunnelCommand = door -> List.of("sh", "-c", "echo 'INF | https://abc-def-ghi.trycloudflare.com |'; sleep 60");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        Thread watcher = Share.supervise(store, d);
        watcher.start();
        try {
            Thread.sleep(200);
            assertEquals(-1, d.doorPort(), "share off: no door");
            Config.set(Share.DOOR, "0");   // any free port, for the test
            Config.set(Share.SHARE, "tailscale");
            until(() -> d.doorPort() >= 0, "the door opens when share is on");
            String door = "http://127.0.0.1:" + d.doorPort();
            var visitor = get(door + "/");
            assertEquals(403, visitor.statusCode());
            assertTrue(visitor.body().contains("Ask to be let in") && visitor.body().contains("shared with named readers"), "a visitor through the door is shown the way in, whatever the library's default (write here): " + visitor.body().substring(0, Math.min(2000, visitor.body().length())));
            assertEquals(200, get("http://127.0.0.1:" + d.port() + "/").statusCode(), "the main port is as it was: open");
            var call = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(door + "/v1/ask")).POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"anything at all\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, call.statusCode(), "and a program with no token: " + call.body());
            assertTrue(call.body().contains("only a listed reader gets in"), call.body());
            // the quick tunnel: started by the watcher, its address read into the setting; ended when share goes off
            Config.set(Share.SHARE, "cloudflare");
            until(() -> Share.address().equals("https://abc-def-ghi.trycloudflare.com"), "the tunnel's address is read from its output: " + Share.address());
            until(() -> { try { return Files.readString(Share.stateFile()).contains("\"state\":\"up\""); } catch (Exception e) { return false; } }, "the state file says up (the address is set a moment before it is written; read as false once under a load of 18 on 2026-10-15)");
            assertTrue(Share.statusLine().contains("quick tunnel at https://abc-def-ghi.trycloudflare.com"));
            Config.set(Share.SHARE, "off");
            until(() -> d.doorPort() < 0, "the door closes when share is off");
            until(() -> Share.address().isEmpty() && stateSays("\"state\":\"stopped\""), "the tunnel is ended and its address forgotten");
            assertEquals(200, get("http://127.0.0.1:" + d.port() + "/").statusCode());
        } finally { watcher.interrupt(); d.stop(); }
    }

    /** The plain share, 2026-10-13 ("that is the user friendly mechanism"): reachable now, no account and nothing asked, through the quick
     *  tunnel when Tailscale is not ready, saying that the address changes and that --tailscale is the better long-term setup. */
    @Test
    void thePlainShareWithoutTailscaleOpensTheQuickTunnelNowAndNamesTheUpgrade(@TempDir Path tmp) throws Exception {
        machine = "linux";
        ByteArrayOutputStream steps = new ByteArrayOutputStream();
        Share.Outcome o = Share.share(lib(tmp), List.of(), new PrintStream(steps, true, StandardCharsets.UTF_8));
        assertEquals(0, o.code(), steps + o.text());
        assertEquals("cloudflare", Share.kind());
        assertTrue(steps.toString(StandardCharsets.UTF_8).contains("fetching cloudflared"), steps.toString());
        assertTrue(typed.isEmpty() && ran.isEmpty(), "nothing on the person's screen, no tailscale run: " + typed + ran);
        assertTrue(o.text().contains("This address changes when the service restarts") && o.text().contains("researchzosho share --tailscale") && o.text().contains("better long-term setup"), o.text());
        assertTrue(Share.status().contains("for one that lasts: researchzosho share --tailscale"), Share.status());
    }

    @Test
    void thePlainShareWithTailscaleLoggedOutAlsoTakesTheQuickTunnel(@TempDir Path tmp) throws Exception {
        programs = Map.of("tailscale", "/usr/bin/tailscale", "cloudflared", "/usr/bin/cloudflared");
        answers = Map.of("status --json", new Video.Result(0, "{\"BackendState\":\"NeedsLogin\"}"));
        Share.Outcome o = Share.share(lib(tmp), List.of(), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(0, o.code(), o.text()); assertEquals("cloudflare", Share.kind());
        assertEquals(List.of("/usr/bin/tailscale status --json"), ran, "asked whether it is logged in, nothing more");
        assertTrue(typed.isEmpty(), "no login on the person's screen: that is --tailscale");
        assertTrue(o.text().contains("share --tailscale"), o.text());
    }

    @Test
    void shareTailscaleIsTheUpgradePathInstallLoginFunnel(@TempDir Path tmp) throws Exception {
        installsPut("tailscale.com/install.sh | sh", "tailscale", "/usr/bin/tailscale");
        loggedInAfterUp();
        person("", "", "");   // Enter three times: install Tailscale, with its own script, log in
        ByteArrayOutputStream steps = new ByteArrayOutputStream();
        Share.Outcome o = Share.share(lib(tmp), List.of("--tailscale"), new PrintStream(steps, true, StandardCharsets.UTF_8));
        assertEquals(0, o.code(), steps + o.text());
        assertEquals("funnel", Share.kind(), "the lasting, public address");
        assertEquals(List.of("sh -c curl -fsSL https://tailscale.com/install.sh | sh", "sudo /usr/bin/tailscale up", "sudo /usr/bin/tailscale set --operator=" + System.getProperty("user.name")), typed);
        assertTrue(o.text().contains("Public at https://mylibrary.tail1234.ts.net (Tailscale Funnel)"), o.text());
    }
}
