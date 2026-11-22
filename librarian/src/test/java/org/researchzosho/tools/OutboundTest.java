package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.librarian.GivenAddresses;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The check at the request ({@link Outbound}, 0.8.1) and what keeps it the one place: the sources are read to see that every client the
 * program builds is made through it, that the hosts it takes as the program's own are the hosts that stand in the sources, and that the
 * other ways out of the process (a program started, a connection opened by hand) are the ones listed here, each with what it does. A
 * tool added later that builds a client of its own, starts a program or opens a socket fails one of these until it is brought in.
 */
class OutboundTest {
    static final ObjectMapper M = new ObjectMapper();

    static Path src() { return Files.exists(Path.of("src")) ? Path.of("src") : Path.of("librarian/src"); }

    static List<Path> javaSources() throws IOException {
        try (Stream<Path> s = Files.walk(src().resolve("main").resolve("java"))) { return s.filter(p -> p.toString().endsWith(".java")).sorted().toList(); }
    }

    static String name(Path p) { return src().resolve("main").resolve("java").relativize(p).toString().replace('\\', '/').replaceFirst("^org/researchzosho/", ""); }

    @Test
    void everyClientTheProgramBuildsIsMadeThroughTheCheck() throws IOException {
        List<String> bare = new ArrayList<>();
        int made = 0;
        for (Path p : javaSources()) {
            if (name(p).equals("tools/Outbound.java")) continue;
            String s = Files.readString(p);
            Matcher m = Pattern.compile("HttpClient\\s*\\.\\s*(?:newHttpClient|newBuilder)\\s*\\(").matcher(s);
            while (m.find()) { made++; if (!s.substring(Math.max(0, m.start() - 16), m.start()).endsWith("Outbound.client(")) bare.add(name(p) + ": " + s.substring(m.start(), Math.min(s.length(), m.start() + 60)).replace('\n', ' ')); }
        }
        assertTrue(made > 50, "the sources were read: " + made + " clients found");
        assertEquals(List.of(), bare, "a client is built without the check; write Outbound.client(HttpClient…) there");
    }

    /** The files that start a program, each with what it starts and what of a model's call can reach it. */
    static final Set<String> STARTS_PROGRAMS = Set.of(
            "drive/aws/AwsCredentials.java",          // the aws command, for the owner's own credentials
            "librarian/ChildLibraries.java",          // this program again, for an account's library
            "librarian/ComicHelper.java",             // the comic helper's Python, on files of the library
            "librarian/ContainerLibraries.java",      // docker, for an account's library
            "librarian/Deployment.java",              // docker compose, by the owner's command
            "librarian/HelperCards.java",             // the probes of this machine's own cards and processes
            "librarian/HelperServer.java",            // the helpers' own programs
            "librarian/HelpersRemote.java",           // ssh to the helpers machine the owner set
            "librarian/LikenessHelper.java",          // the likeness helper's Python, on kept pictures
            "librarian/ModelServer.java",             // docker, curl and the model server, by the owner's command
            "librarian/PdfReading.java",              // pdftoppm, on a file of the library's
            "librarian/ProcessProof.java",            // a look at this machine's own processes
            "librarian/profiles/GenealogyProfile.java", // the person's own editor, on a transcript, from the command line
            "librarian/Repos.java",                   // git clone, for a survey: its address is checked like a request (Outbound.check) and stands after --
            "librarian/Searx.java",                   // docker, for the owner's SearXNG
            "librarian/Service.java",                 // systemctl and launchctl, by the owner's command
            "librarian/Setup.java",                   // docker and the installers, by the owner's command
            "librarian/Share.java",                   // tailscale and cloudflared: held for the person's yes in the chat
            "librarian/TranslationScores.java",       // the scoring helper's Python, on a translation's text
            "librarian/Updater.java",                 // the updater, by the owner's command
            "librarian/Video.java",                   // yt-dlp: a search's words after ytsearchN:, a video's eleven-letter id, a channel's own address; never a model's address
            "librarian/VideoRecall.java",             // ffmpeg and ffprobe, on files named by a video's id
            "mcp/McpClient.java");                    // the keeper's own MCP server, started by the command the keeper wrote

    /** The files that open a connection by hand, outside any client. */
    static final Set<String> OPENS_CONNECTIONS = Set.of(
            "librarian/ChildLibraries.java",          // a socket to this machine's own port, to see whether a library answers
            "librarian/Relays.java",                  // sockets on this machine, between a relay and the library's door
            "librarian/DbDrivers.java");              // a database driver's file, from the one address written in that class

    @Test
    void theOtherWaysOutOfTheProcessAreTheOnesListed() throws IOException {
        Set<String> starts = new TreeSet<>(), opens = new TreeSet<>();
        for (Path p : javaSources()) {
            String s = Files.readString(p);
            if (Pattern.compile("new\\s+ProcessBuilder\\b|Runtime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*exec").matcher(s).find()) starts.add(name(p));
            if (Pattern.compile("\\.openConnection\\s*\\(|\\.openStream\\s*\\(|new\\s+Socket\\s*\\(|SocketChannel\\s*\\.\\s*open|new\\s+DatagramSocket|new\\s+URL\\s*\\(").matcher(s).find()) opens.add(name(p));
        }
        assertEquals(new TreeSet<>(STARTS_PROGRAMS), starts, "a file starts a program and is not on the list (or no longer does): say what it starts and what of a model's call can reach it");
        assertEquals(new TreeSet<>(OPENS_CONNECTIONS), opens, "a file opens a connection outside the checked clients");
    }

    private static final Pattern WRITTEN = Pattern.compile("(?i)https?://((?:[a-z0-9-]+|%s)(?:\\.[a-z0-9-]+)*\\.[a-z]{2,24})(?![a-z0-9.%-])");

    /** The hosts that stand in the program's own sources and the files it ships: names of the public web, each once; {@code %s.} before a name is one label. */
    static Set<String> hostsInSources() throws IOException {
        Set<String> out = new TreeSet<>();
        List<Path> files = new ArrayList<>(javaSources());
        try (Stream<Path> s = Files.walk(src().resolve("main").resolve("resources"))) { files.addAll(s.filter(p -> p.toString().matches(".*\\.(json|jsonl|txt|tsv|md|py|js|xml)$") && !p.getFileName().toString().equals("fixed-hosts.txt")
                    && !p.getFileName().toString().equals("openrouter-controllers.json")).sorted().toList()); }   // not the owners' table: it cites its sources, addresses a person reads and this program never asks
        for (Path p : files) {
            Matcher m = WRITTEN.matcher(Files.readString(p, StandardCharsets.UTF_8));
            while (m.find()) {
                String h = m.group(1).toLowerCase();
                if (h.startsWith("%s.")) h = "*" + h.substring(2);
                String bare = h.startsWith("*.") ? h.substring(2) : h;
                if (bare.contains("%") || Outbound.local(bare) || bare.matches("(?:.*\\.)?example(?:\\.(?:org|com|net))?") || bare.endsWith(".test") || bare.endsWith(".invalid")) continue;   // a host of an example, of this machine or of a home network is nobody's fixed host
                out.add(h);
            }
        }
        return out;
    }

    @Test
    void theHostsTakenAsTheProgramsOwnAreTheHostsThatStandInItsSources() throws IOException {
        Set<String> inSources = hostsInSources();
        Set<String> listed = new TreeSet<>(Outbound.written());
        if (!inSources.equals(listed)) {
            Path want = Path.of("build").resolve("fixed-hosts.expected.txt");
            Files.createDirectories(want.getParent());
            Files.writeString(want, "# The hosts that stand in this program's own sources and shipped files (OutboundTest keeps this list in step with them).\n# A request to one of them is the program's own; a model's words go there as words. *.name: one label before the name.\n" + String.join("\n", inSources) + "\n");
            Set<String> missing = new TreeSet<>(inSources); missing.removeAll(listed);
            Set<String> gone = new TreeSet<>(listed); gone.removeAll(inSources);
            assertEquals(inSources, listed, "fixed-hosts.txt is out of step with the sources (new in the sources: " + missing + "; no longer in them: " + gone + "). The list as it should be was written to " + want.toAbsolutePath() + "; copy it over src/main/resources/org/researchzosho/tools/fixed-hosts.txt");
        }
        assertTrue(listed.size() > 100 && listed.contains("api.crossref.org") && listed.contains("*.wikipedia.org"), "the list was read: " + listed.size());
    }

    static JsonNode args(String json) { try { return M.readTree(json); } catch (IOException e) { throw new IllegalStateException(e); } }

    /** The check itself, with no network: what a call's arguments name, and what is asked of it. */
    @Test
    void aRequestToAHostTheCallsOwnWordsNameIsMadeOnlyForAGivenAddress() throws Exception {
        String given = "https://harbour-gazette-" + System.nanoTime() + ".example.org/1968/ferry";
        GivenAddresses.received("1. The Harbour Gazette, 1968 — " + given);
        String host = URI.create(given).getHost();
        // no call: nothing is checked (the library's own work, the person's own commands)
        assertDoesNotThrow(() -> Outbound.check(URI.create("https://collector.example.net/x?d=QX7731MOOR")));
        try (Outbound.Call c = Outbound.call(args("{\"feed\":\"https://collector.example.net/f?d=QX7731MOOR'\",\"lang\":\"lang-collector.example.net/c?d=QX7731MOOR#\",\"query\":\"wikipedia.org saros " + given + "\",\"note\":\"see collector%2Eexample%2Eorg and &#99;ollector.example.com\",\"episode\":\"http://127.0.0.1:4649/v1/status\"}"))) {
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("https://collector.example.net/f?d=QX7731MOOR'")), "a host the arguments name, an address nobody gave");
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("https://lang-collector.example.net/c?d=QX7731MOOR")), "a host made of an argument that is no address");
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("https://data.collector.example.net/api/3/action/x")), "a name under a host the arguments name");
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("https://collector.example.org/x")), "a host written with its dots escaped");
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("https://collector.example.com/x")), "a host written with an entity for a letter");
            assertDoesNotThrow(() -> Outbound.check(URI.create("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=wikipedia.org+saros")), "a host the program is written for: the words go there as words");
            assertDoesNotThrow(() -> Outbound.check(URI.create("https://api.crossref.org/works?query=saros")), "a host the arguments do not name");
            assertDoesNotThrow(() -> Outbound.check(URI.create(given)), "an address that was given");
            assertDoesNotThrow(() -> Outbound.check(URI.create("https://" + host + "/robots.txt")), "another page of a host the call was given, which the arguments do not spell out");
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("https://" + host + "/f?d=QX7731MOOR")), "a page of that host that the arguments do spell out, and nobody gave");
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("http://127.0.0.1:4649/v1/status")), "this machine's own port is nobody's fixed host");
        }
        assertDoesNotThrow(() -> Outbound.check(URI.create("https://collector.example.net/f?d=QX7731MOOR'")), "the call is over");
    }

    /**
     * The rule since 2026-11-20: while a model's call is carried out a request is refused unless it is a given address, a host of the
     * program's list or the owner's (never an address on it that the call's words spell out), or where a server sent an allowed request
     * on. A host nobody named and nobody listed is refused too: the call's words need not name it.
     */
    @Test
    void whileACallIsCarriedOutARequestGoesOnlyToAGivenAddressOrAListedHost() throws Exception {
        String given = "https://lighthouse-keepers-" + System.nanoTime() + ".example.org/log/1911";
        GivenAddresses.received("The log of 1911: " + given);
        try (Outbound.Call c = Outbound.call("open", args("{\"address\":\"" + given + "\",\"note\":\"see https://web.archive.org/save/https://collector.example.net/x?d=QX7731MOOR and &#104;ttps://en.wikipedia.org/wiki/Special:Search/QX7731MOOR-door-code\"}"))) {
            assertDoesNotThrow(() -> Outbound.check(URI.create(given)), "(a) a given address");
            assertDoesNotThrow(() -> Outbound.check(URI.create("https://api.crossref.org/works?query=lighthouse")), "(b) a listed host, the words as words");
            Outbound.NotOpened unlisted = assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("https://quiet-host.example.net/anything")), "a host the call never named, that nobody listed and nobody gave");
            assertEquals(Outbound.NOT_LISTED, unlisted.rule());
            Outbound.NotOpened relay = assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("https://web.archive.org/save/https://collector.example.net/x?d=QX7731MOOR")), "a listed host asked for an address the call's words spell out");
            assertEquals(Outbound.SPELLED, relay.rule());
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("https://en.wikipedia.org/wiki/Special:Search/QX7731MOOR-door-code")), "the same, written with an entity for a letter");
            assertDoesNotThrow(() -> Outbound.check(URI.create("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=door+code")), "the listed host's own path: words as words");
            // (c) a hop, by who chose it. Where the server of a given address sends the request on is that server's choice: anywhere
            Outbound.check(URI.create(given));
            assertDoesNotThrow(() -> Outbound.check(c, URI.create("https://cdn.quiet-host.example.net/log/1911.pdf"), true), "a given address, sent on to another host");
            Outbound.check(URI.create(given));
            assertDoesNotThrow(() -> Outbound.check(c, URI.create("https://collector.example.net/x?d=QX7731MOOR"), true), "and to a host the call's words name: the request held nothing of the model's");
            // a request to a listed host may carry the call's words: it is followed to a listed host and within its own site, and nowhere else
            Outbound.check(URI.create("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=door+code"));
            assertDoesNotThrow(() -> Outbound.check(c, URI.create("https://api.crossref.org/works?query=door"), true), "to a listed host");
            Outbound.check(URI.create("https://api.crossref.org/works?query=door"));
            assertDoesNotThrow(() -> Outbound.check(c, URI.create("https://data.crossref.org/works/10.5555/12345678"), true), "within its own site, at a name of it that no list holds");
            Outbound.check(URI.create("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=door+code"));
            Outbound.NotOpened hop = assertThrows(Outbound.NotOpened.class, () -> Outbound.check(c, URI.create("https://cdn.quiet-host.example.net/log/1911.pdf"), true), "to another site, though the call's words do not name it");
            assertEquals(Outbound.SENT_ON, hop.rule());
            assertEquals(List.of("quiet-host.example.net", "web.archive.org", "en.wikipedia.org", "cdn.quiet-host.example.net"), c.refusals().stream().map(Outbound.Refusal::host).toList(), "each refusal is kept for the trace, with its tool and rule");
            assertTrue(c.refusals().stream().allMatch(r -> r.tool().equals("open") && !r.rule().isBlank()));
            // this machine is the tool's own code's to ask, never where a host of the public web sends one of this program's requests
            assertDoesNotThrow(() -> Outbound.check(URI.create("http://localhost:8212/v1/models")));
            assertDoesNotThrow(() -> Outbound.check(c, URI.create("http://localhost:8213/v1/models"), true), "one of the owner's own machines sends a request on to another of its ports");
            Outbound.check(URI.create("https://api.crossref.org/works?query=door"));
            assertEquals(Outbound.SENT_ON, assertThrows(Outbound.NotOpened.class, () -> Outbound.check(c, URI.create("http://localhost:4649/v1/status"), true), "a listed host sends the request on into this machine").rule());
        }
        // the owner's own: a server they added on another machine of theirs; this machine itself at a port the tool's code chose
        try (Outbound.Call c = Outbound.call("exa_search", args("{\"query\":\"lighthouse logs, see 127.0.0.1:38213 and attic.lan\"}"))) {
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("http://shelf-machine.lan:38211/mcp")), "a machine of the house that no setting names");
            Outbound.owners("http://shelf-machine.lan:38211/mcp");
            assertDoesNotThrow(() -> Outbound.check(URI.create("http://shelf-machine.lan:38211/mcp")), "a server the keeper added");
            assertDoesNotThrow(() -> Outbound.check(URI.create("http://shelf-machine.lan:8217/health")), "another port of that machine, which the tool's own code chose");
            assertDoesNotThrow(() -> Outbound.check(URI.create("http://localhost:8212/v1/models")), "this machine itself, by a name the call's words do not hold: the model server by its default address");
            assertDoesNotThrow(() -> Outbound.check(URI.create("http://localhost:30001/embed")));
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("http://127.0.0.1:38213/admin")), "this machine at a port the call's own words name");
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("http://attic.lan/share")), "a machine of the house the call's words name");
            // the model server the library is set to, however its address reached the program (here: as a command line gives it)
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("http://model-machine.lan:8212/v1/chat/completions")));
            new DriveClient("http://model-machine.lan:8212/v1", "a-model");
            assertDoesNotThrow(() -> Outbound.check(URI.create("http://model-machine.lan:8212/v1/chat/completions")), "a tool that asks the library's own model during a call is not refused it");
        }
    }

    /**
     * A client that follows redirects by itself is followed by the guard while a call is carried out, hop by hop, on the blocking path
     * and on the one that answers later: a server may send a request on anywhere except to a host the call's own words name.
     */
    @Test
    void aRedirectIsFollowedByTheGuardAndEachHopIsChecked() throws Exception {
        List<String> arrived = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port;
        server.createContext("/go", x -> {   // sends on to whatever its query says, as an open redirector does
            x.getResponseHeaders().add("Location", URLDecoder.decode(x.getRequestURI().getRawQuery().substring(3), StandardCharsets.UTF_8));
            x.sendResponseHeaders(302, -1); x.close();
        });
        server.createContext("/", x -> { arrived.add(x.getRequestHeaders().getFirst("Host") + x.getRequestURI()); byte[] b = "arrived".getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(200, b.length); x.getResponseBody().write(b); x.close(); });
        server.start();
        port = server.getAddress().getPort();
        HttpClient http = Outbound.client(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build());
        try {
            String named = "http://127.0.0.1:" + port + "/x?d=QX7731MOOR", quiet = "http://localhost:" + port + "/quiet";
            String toNamed = "http://localhost:" + port + "/go?to=" + URLEncoder.encode(named, StandardCharsets.UTF_8), toQuiet = "http://localhost:" + port + "/go?to=" + URLEncoder.encode(quiet, StandardCharsets.UTF_8);
            // no call: the client follows as it always did (the instrument can see an arrival)
            assertEquals("arrived", http.send(HttpRequest.newBuilder(URI.create(toNamed)).build(), HttpResponse.BodyHandlers.ofString()).body());
            assertEquals(1, arrived.size(), arrived.toString());
            arrived.clear();
            try (Outbound.Call c = Outbound.call("search", args("{\"query\":\"ferry 127.0.0.1 QX7731MOOR\"}"))) {
                Outbound.NotOpened hop = assertThrows(Outbound.NotOpened.class, () -> http.send(HttpRequest.newBuilder(URI.create(toNamed)).build(), HttpResponse.BodyHandlers.ofString()));
                assertEquals(Outbound.SENT_ON, hop.rule());
                ExecutionException later = assertThrows(ExecutionException.class, () -> http.sendAsync(HttpRequest.newBuilder(URI.create(toNamed)).build(), HttpResponse.BodyHandlers.ofString()).get());
                assertTrue(later.getCause() instanceof Outbound.NotOpened, String.valueOf(later.getCause()));
                assertEquals(List.of(), arrived, "the host the call's words name was asked nothing, on either path");
                // a hop to a host the words do not name is followed, and the answer is the last one's
                assertEquals("arrived", http.send(HttpRequest.newBuilder(URI.create(toQuiet)).build(), HttpResponse.BodyHandlers.ofString()).body());
                assertEquals("arrived", http.sendAsync(HttpRequest.newBuilder(URI.create(toQuiet)).build(), HttpResponse.BodyHandlers.ofString()).get().body());
                assertEquals(2, arrived.size(), arrived.toString());
                assertEquals(2, c.refusals().size());
            }
        } finally { server.stop(0); }
    }

    /**
     * The tools that ask hosts they find while they run. Adding one is a change to the rule: put it here with the reason, and write its
     * constraints beside its name in {@code Outbound.FINDS}.
     */
    @Test
    void theToolsThatAskHostsTheyFindAreTheOnesDeclared() throws Exception {
        assertEquals(Set.of("records", "board"), Outbound.FINDS.keySet(), "records: no list names every county's data portal; board: an employer's own careers site is found by a search");
        // what holds a declared tool: a host is asked only when one of the tool's own answers named it, and never when the call's words do
        try (Outbound.Call c = Outbound.call("records", args("{\"address\":\"1 City Hall Square, Boston, MA\",\"note\":\"data.collector.example.net\"}"))) {
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("https://gisportal.boston-example.gov/arcgis/rest/services/Parcels/MapServer/0/query?where=1%3D1")), "a host no answer named yet");
            Outbound.found("{\"results\":[{\"url\":\"https://gisportal.boston-example.gov/arcgis/rest/services/Parcels/MapServer/0\"},{\"domain\":\"data.boston-example.gov\"},{\"url\":\"https://data.collector.example.net/api\"}]}");
            assertDoesNotThrow(() -> Outbound.check(URI.create("https://gisportal.boston-example.gov/arcgis/rest/services/Parcels/MapServer/0/query?where=ST_NAME%3D%27CITY+HALL%27")), "a host its own answer named, a path its own code built");
            assertDoesNotThrow(() -> Outbound.check(URI.create("https://data.boston-example.gov/api/3/action/package_search?q=property")), "a host named without a scheme, as a catalogue names a domain");
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("https://data.collector.example.net/api/3/action/package_search?q=property")), "a host the call's own words name, though an answer named it too");
            assertTrue(Outbound.wasFound("data.boston-example.gov") && !Outbound.wasFound("data.collector.example.net") && !Outbound.wasFound("data.elsewhere-example.gov"));
        }
        // any other tool: an answer's hosts give it nothing here
        try (Outbound.Call c = Outbound.call("search", args("{\"query\":\"boston parcels\"}"))) {
            Outbound.found("https://gisportal.boston-example.gov/arcgis/rest/services/Parcels/MapServer/0");
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(URI.create("https://gisportal.boston-example.gov/arcgis/rest/services/Parcels/MapServer/0")));
        }
    }

    /** The site a host belongs to, for where a request with a call's words may be sent on: the registered name, and never a name under which each further name is somebody else's. */
    @Test
    void theSiteOfAHostIsItsRegisteredName() {
        assertEquals("example.org", Outbound.site("www.example.org"));
        assertEquals("example.org", Outbound.site("API.Example.org."));
        assertEquals("example.co.uk", Outbound.site("data.example.co.uk"));
        assertEquals("example.jp", Outbound.site("www.example.jp"));
        assertEquals("archive.org", Outbound.site("ia800100.us.archive.org"));
        assertEquals("harbour.github.io", Outbound.site("harbour.github.io"), "a name under which each further name is somebody else's is a site of its own");
        assertEquals("bucket.s3.amazonaws.com", Outbound.site("bucket.s3.amazonaws.com"));
        assertEquals("127.0.0.1", Outbound.site("127.0.0.1"));
        assertEquals("localhost", Outbound.site("localhost"));
        assertEquals("attic.lan", Outbound.site("attic.lan"));
    }

    /**
     * The resolvers, and what holds one: a request for a name and nothing else is followed wherever the resolver sends it when the
     * name was given or the call's own words do not hold it; a name the call made up, or a request with a query, is followed as any
     * request with a call's words is. Adding a resolver is a change to the rule: put it here with the reason.
     */
    @Test
    void aResolverIsFollowedAnywhereOnlyForANameThatWasGiven() throws Exception {
        assertEquals(Set.of("doi.org"), Outbound.RESOLVERS.keySet(), "doi.org: a work by its DOI is on its publisher's own host, which no list names");
        String n = Long.toHexString(System.nanoTime());
        GivenAddresses.personSaid("Add the papers 10.5555/given." + n + " and 10.5555/also-given." + n + " to my reading list.");
        URI publisher = URI.create("https://publisher-" + n + ".example.net/article/1");
        try (Outbound.Call c = Outbound.call("library_reading", args("{\"text\":\"10.5555/given." + n + "\\n10.5555/made-up." + n + "\"}"))) {
            Outbound.check(URI.create("https://doi.org/10.5555/given." + n));
            assertDoesNotThrow(() -> Outbound.check(c, publisher, true), "a DOI the person gave: where the resolver sends the request is followed");
            Outbound.check(URI.create("https://doi.org/10.5555/made-up." + n));
            assertEquals(Outbound.SENT_ON, assertThrows(Outbound.NotOpened.class, () -> Outbound.check(c, publisher, true), "a DOI only the call's own words hold").rule());
            Outbound.check(URI.create("https://doi.org/10.5555/also-given." + n));
            assertDoesNotThrow(() -> Outbound.check(c, publisher, true), "a DOI the call's words do not hold (the tool read it in the person's file), which was given");
            Outbound.check(URI.create("https://doi.org/10.5555/from-a-file." + n));
            assertDoesNotThrow(() -> Outbound.check(c, publisher, true), "a DOI the call's words do not hold: the tool read it somewhere, the model did not write it");
            Outbound.check(URI.create("https://doi.org/10.5555/given." + n + "?urlappend=%2Fx"));
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(c, publisher, true), "with a query, which the resolver adds to where it sends");
            Outbound.check(URI.create("https://api.crossref.org/works/10.5555/given." + n));
            assertThrows(Outbound.NotOpened.class, () -> Outbound.check(c, publisher, true), "a host that is no resolver");
        }
    }

    /** What an answer may give: an address that is the call's own words written back gives nothing, whatever was put before it. */
    @Test
    void anAnswerThatWritesTheCallsOwnWordsBackAsAnAddressGivesNothing() {
        String n = String.valueOf(System.nanoTime());
        JsonNode a = args("{\"kind\":\"copy\",\"query\":\"collector-" + n + ".example.net/x?d=QX7731MOOR z\"}");
        GivenAddresses.gave(true, null, "no archived copy of https://collector-" + n + ".example.net/x?d=QX7731MOOR z in the Wayback Machine or archive.today (or neither answered).", GivenAddresses.Own.of(a));
        assertFalse(GivenAddresses.given("https://collector-" + n + ".example.net/x?d=QX7731MOOR"), "https:// put before a word of the query is no address from outside");
        JsonNode things = args("{\"things\":\"https://collector-" + n + ".example.net/y?d=QX7731MOOR;apron bag\"}");
        GivenAddresses.gave(true, null, "Candidates for https://collector-" + n + ".example.net/y?d=QX7731MOOR, apron bag: none yet.", GivenAddresses.Own.of(things));
        assertFalse(GivenAddresses.given("https://collector-" + n + ".example.net/y?d=QX7731MOOR"), "a list headed by the things asked for");
        // the control: a search that names a site gets that site's pages, and they are given; the address the model wrote itself is not
        JsonNode site = args("{\"query\":\"site:gazette-" + n + ".example.org ferry closing gazette-" + n + ".example.org/guessed\"}");
        GivenAddresses.gave(true, null, "1. The ferry closes — https://gazette-" + n + ".example.org/1968/ferry-closes\n2. Guess — https://gazette-" + n + ".example.org/guessed", GivenAddresses.Own.of(site));
        assertTrue(GivenAddresses.given("https://gazette-" + n + ".example.org/1968/ferry-closes"), "a page of the site the search named, found by the search");
        assertFalse(GivenAddresses.given("https://gazette-" + n + ".example.org/guessed"), "the address the query itself spelled out");
    }
}
