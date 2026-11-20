package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A library to take home (0.8.0, stage 4, step 3). Its archive holds the library and its own settings, the manifest first, and nothing
 * of other people's, nothing a running job keeps to resume, no log and no lock; an archive that is not whole, of a newer format, or
 * with an entry outside its folder is refused and leaves nothing behind. Through the service and a library in a process of its own: its
 * keeper takes it home, nobody else does; without copies the library is held asleep while it is packed and says so to a request, with
 * copies it answers meanwhile. The archive opens as a person's own library (researchzosho take), and a service takes it in for an
 * account (accounts take).
 */
class LibraryArchiveTest {
    static final ObjectMapper M = new ObjectMapper();
    final HttpClient c = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    long lease = Node.LEASE_MS, renew = Node.RENEW_MS, soon = Node.DUE_SOON_MS;
    StandInModel model; AccountStore store; AccountService svc; volatile Node node; Path root;
    final List<String> set = new ArrayList<>();

    void setting(String k, String v) throws IOException { Config.set(k, v); set.add(k); }

    void start(Path tmp) throws Exception {
        Node.LEASE_MS = 4_000; Node.RENEW_MS = 250; Node.SLEEP_AFTER_MS = 0L; Node.DUE_SOON_MS = 0;
        model = new StandInModel();
        setting("RESEARCHZOSHO_DRIVE", model.url()); setting("RESEARCHZOSHO_MODEL", "stand-in"); setting(Scheduler.NIGHTS, "off");
        root = tmp.resolve("accounts");
        setting(AccountService.ROOT, root.toString());
        store = AccountStore.open(root.resolve("accounts.db").toString());
        svc = AccountService.start(store, "127.0.0.1", 0, self -> new LibraryRunner() {
            @Override public String ensure(AccountStore.Library lib) throws IOException { return node.ensure(lib); }
            @Override public void lost(String id) { node.lost(id); }
            @Override public void stopAll() { node.stopAll(); }
            @Override public long epoch(String id) { return node.epoch(id); }
            @Override public boolean awake(String id) { return node.awake(id); }
            @Override public String ifAwake(AccountStore.Library lib) throws IOException { return node.ifAwake(lib); }
            @Override public void done(String id) { node.done(id); }
            @Override public void archive(AccountStore.Library lib, OutputStream out, String from) throws IOException { node.archive(lib, out, from); }
        });
        node = new Node(store, svc.url(), new ChildLibraries(store, svc.url()), "node_test", root);
        node.start();
    }

    @AfterEach void stop() throws Exception {
        try { if (svc != null) svc.stop(); if (store != null) store.close(); if (model != null) model.close(); }
        finally {
            Node.LEASE_MS = lease; Node.RENEW_MS = renew; Node.SLEEP_AFTER_MS = null; Node.DUE_SOON_MS = soon;
            for (String k : set) Config.set(k, "");
        }
    }

    /** An archive taken in on a person's own computer brings none of what an export leaves out (0.8.0 review, round C, SEC 9). */
    @Test
    void anArchiveTakenOnOnesOwnComputerBringsNoneOfWhatAnExportLeavesOut(@TempDir Path tmp) throws Exception {
        LibraryStore own = new LibraryStore(tmp.resolve("theirs"));
        own.init(); own.setName("Harbour notes");
        Files.createDirectories(own.root().resolve("raw"));
        Files.writeString(own.root().resolve("raw").resolve("a.md"), "a page\n");
        LibraryStore.Identity id = own.identity();
        // an archive packed as the program packs one, and the same with what an export never carries put in by hand
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        JsonNode m = LibraryArchive.write(packed, own.root(), null, id.id(), id.name(), "Harbour notes", System.currentTimeMillis(), "");
        List<String[]> planted = List.of(new String[]{"library/catalog/accounts.json", "{\"accounts\":[{\"name\":\"Mallory\"}]}"}, new String[]{"library/catalog/keys.json", "{\"keys\":[]}"},
                new String[]{"library/catalog/patrons.md", "mallory: write\n"}, new String[]{"library/catalog/jobs/work/j1/state.json", "{}"}, new String[]{"library/catalog/serve.lock", "1\n"});
        long extra = 0; for (String[] p : planted) extra += p[1].getBytes(StandardCharsets.UTF_8).length;
        Path crafted = tmp.resolve("crafted.rzlib.zip");
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(packed.toByteArray())); ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(crafted))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                byte[] data = in.readAllBytes();
                if (e.getName().equals(LibraryArchive.MANIFEST)) data = M.writeValueAsBytes(((ObjectNode) m.deepCopy()).put("files", m.path("files").asLong() + planted.size()).put("bytes", m.path("bytes").asLong() + extra));
                out.putNextEntry(new ZipEntry(e.getName())); out.write(data); out.closeEntry();
            }
            for (String[] p : planted) { out.putNextEntry(new ZipEntry(p[0])); out.write(p[1].getBytes(StandardCharsets.UTF_8)); out.closeEntry(); }
        }
        Path home = tmp.resolve("mine");
        set.add("RESEARCHZOSHO_LIBRARY");
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        assertEquals(0, LibraryArchive.take(new String[]{"researchzosho", "take", crafted.toString(), home.toString()}, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8)), e.toString(StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(home.resolve("raw").resolve("a.md")), "the library's own files come");
        for (String[] p : planted) assertFalse(Files.exists(home.resolve(p[0].substring("library/".length()))), "not taken in: " + p[0]);
    }

    HttpResponse<String> api(String key, String path) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(svc.url() + path)).header("Authorization", "Bearer " + key).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The names in an archive, in order, and its manifest. */
    record Read(List<String> names, JsonNode manifest, String settings) { }

    static Read read(InputStream in) throws IOException {
        List<String> names = new ArrayList<>(); JsonNode m = null; String settings = "";
        try (ZipInputStream z = new ZipInputStream(in)) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                names.add(e.getName());
                byte[] b = z.readAllBytes();
                if (e.getName().equals(LibraryArchive.MANIFEST)) m = M.readTree(b);
                if (e.getName().equals(LibraryArchive.SETTINGS)) settings = new String(b, StandardCharsets.UTF_8);
            }
        }
        return new Read(names, m, settings);
    }

    static void write(Path f, String text) throws IOException { Files.createDirectories(f.getParent()); Files.writeString(f, text, StandardCharsets.UTF_8); }

    /** A library's folder as a service keeps it: the library, with what other people and running work leave in it, and its own settings beside it. */
    static AccountStore.Library folder(Path base) throws IOException {
        LibraryStore ls = new LibraryStore(base.resolve("library"));
        ls.init(); ls.setName("Harbour notes");
        Path lib = base.resolve("library");
        write(lib.resolve("findings/the-keeper.md"), "The first keeper lit the lamp in 1792.\n");
        write(lib.resolve("catalog/jobs/active/J-0002.json"), "{\"job_id\":\"J-0002\"}\n");
        write(lib.resolve(".index/segments_1"), "an index\n");
        for (String other : List.of("catalog/accounts.json", "catalog/keys.json", "catalog/patrons.md", "catalog/access-requests.md", "catalog/serve.lock", "catalog/serve.lock.guard",
                "catalog/crews.log", "catalog/serials.lock", "catalog/.lock-migrate-fields", "catalog/jobs/work/J-0002/checkpoint.json", "catalog/jobs/work/J-0002/artifacts.jsonl", "tmp/half.txt", "catalog/researchzosho.pid"))
            write(lib.resolve(other), "not to take home\n");
        write(base.resolve("home/config"), "language = Japanese\nreport.length = medium\nRESEARCHZOSHO_DRIVE = http://the-service:8080\nbrave.key = a-service-key\nresearch.pause = on\n");
        return new AccountStore.Library(ls.identity().id(), "harbour", "Harbour notes", "acct_1", base.toString(), "active", "", 1_700_000_000_000L);
    }

    @Test
    void anArchiveHoldsTheLibraryAndItsOwnSettingsAndNothingOfOtherPeoplesAndIsTakenWholeOrNotAtAll(@TempDir Path tmp) throws Exception {
        AccountStore.Library lib = folder(tmp.resolve("base"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        JsonNode m = LibraryArchive.write(bytes, tmp.resolve("base"), lib, "https://research.example.org");
        Read r = read(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(LibraryArchive.MANIFEST, r.names().get(0), "the manifest first");
        assertEquals(1, r.manifest().path("format").asInt()); assertEquals(lib.id(), r.manifest().path("library_id").asText()); assertEquals("Harbour notes", r.manifest().path("title").asText());
        assertEquals("https://research.example.org", r.manifest().path("from").asText());
        assertTrue(r.names().containsAll(List.of("library/findings/the-keeper.md", "library/catalog/jobs/active/J-0002.json", "library/.index/segments_1", "library/catalog/library.md", "home/config")), r.names().toString());
        for (String n : r.names()) assertFalse(n.contains("accounts.json") || n.contains("keys.json") || n.contains("patrons.md") || n.contains("access-requests") || n.contains(".lock") || n.endsWith(".log")
                || n.contains("jobs/work") || n.contains("tmp/") || n.endsWith(".pid"), "not taken home: " + n);
        assertEquals(r.names().stream().filter(n -> n.startsWith("library/")).count(), m.path("files").asLong(), "the manifest counts what follows");
        assertTrue(r.settings().contains("RESEARCHZOSHO_LANGUAGE = Japanese") && r.settings().contains("RESEARCHZOSHO_REPORT_LENGTH = medium"), r.settings());
        assertFalse(r.settings().contains("DRIVE") || r.settings().contains("BRAVE") || r.settings().contains("PAUSE"), "the service's settings and a state of the moment stay: " + r.settings());
        assertEquals("Japanese", m.path("settings").path("language").asText());

        Path archive = tmp.resolve("harbour.rzlib.zip");
        Files.write(archive, bytes.toByteArray());
        LibraryArchive.Unpacked u = LibraryArchive.unpack(archive, tmp.resolve("taken"));
        assertEquals(lib.id(), new LibraryStore(u.folder()).identity().id());
        assertEquals("The first keeper lit the lamp in 1792.\n", Files.readString(u.folder().resolve("findings/the-keeper.md")));
        assertFalse(Files.exists(u.folder().resolve("catalog/accounts.json")));
        assertEquals("Japanese", u.settings().get("RESEARCHZOSHO_LANGUAGE"));
        // never over what is there
        IOException there = assertThrows(IOException.class, () -> LibraryArchive.unpack(archive, tmp.resolve("taken")));
        assertTrue(there.getMessage().contains("already"), there.getMessage());

        // an entry outside its folder, an archive that holds more than it says, a newer format, a file that is not an archive: refused, nothing left
        String manifest = M.writerWithDefaultPrettyPrinter().writeValueAsString(m);
        for (Object[] bad : new Object[][]{
                {List.of("library/catalog/library.md", "library/../outside.txt"), manifest, "outside its folder"},
                {List.of("library/catalog/library.md", "library/more.md"), manifest.replace("\"files\" : " + m.path("files").asLong(), "\"files\" : 1").replace("\"bytes\" : " + m.path("bytes").asLong(), "\"bytes\" : 3"), "more than its manifest says"},
                {List.of("library/catalog/library.md"), manifest.replace("\"format\" : 1", "\"format\" : 2"), "newer ResearchZosho"}}) {
            Path f = tmp.resolve("bad.zip");
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(f))) {
                z.putNextEntry(new ZipEntry(LibraryArchive.MANIFEST)); z.write(((String) bad[1]).getBytes(StandardCharsets.UTF_8)); z.closeEntry();
                for (Object n : (List<?>) bad[0]) { z.putNextEntry(new ZipEntry((String) n)); z.write("name: Harbour notes\nsome text\n".getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }
            }
            Path into = tmp.resolve("bad-into");
            IOException e = assertThrows(IOException.class, () -> LibraryArchive.unpack(f, into));
            assertTrue(e.getMessage().contains((String) bad[2]), e.getMessage());
            assertFalse(Files.exists(into), "nothing left behind: " + bad[2]);
            assertFalse(Files.exists(tmp.resolve("outside.txt")), "nothing written outside");   // where the entry points, inside this test's own folder
        }
        Path cut = tmp.resolve("cut.zip");
        Files.write(cut, Arrays.copyOf(bytes.toByteArray(), bytes.size() / 2));
        IOException e = assertThrows(IOException.class, () -> LibraryArchive.unpack(cut, tmp.resolve("cut-into")));
        assertTrue(e.getMessage().contains("not a whole zip") || e.getMessage().contains("not whole"), e.getMessage());
        assertFalse(Files.exists(tmp.resolve("cut-into")));
    }

    @Test
    void itsKeeperTakesALibraryHomeThroughTheServiceHeldAsleepWhileItIsPackedAndNobodyElseTakesIt(@TempDir Path tmp) throws Exception {
        start(tmp);
        AccountService.Made ann = AccountService.newAccount(store, root, "Ann Example", "", false, "the test"), bo = AccountService.newAccount(store, root, "Bo Example", "", false, "the test");
        store.setMember(ann.library().id(), bo.account().id(), "write", "the test");
        String annKey = store.mintKey(ann.account().id(), ann.library().id(), "write", "t").secret(), boKey = store.mintKey(bo.account().id(), ann.library().id(), "write", "t").secret();
        // something on its shelves that does not squeeze: the archive takes a while to send, and a reader who does not read holds it there
        byte[] noise = new byte[32 * 1024 * 1024]; new Random(7).nextBytes(noise);
        Files.write(LibraryPlaces.library(ann.library()).resolve("raw").resolve("big.bin"), noise);
        assertEquals(200, api(annKey, "/v1/status").statusCode());
        assertTrue(node.awake(ann.library().id()), "awake before");

        // someone let in does not take it home
        HttpResponse<String> bos = api(boKey, AccountService.ARCHIVE);
        assertEquals(403, bos.statusCode()); assertTrue(bos.body().contains("Only the person who keeps a library takes it home."), bos.body());

        // its keeper does: the answer begins, and while it is read slowly the library sleeps and says so
        HttpResponse<InputStream> taking = c.send(HttpRequest.newBuilder(URI.create(svc.url() + AccountService.ARCHIVE)).header("Authorization", "Bearer " + annKey).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, taking.statusCode());
        assertEquals("application/zip", taking.headers().firstValue("Content-Type").orElse(""));
        assertTrue(taking.headers().firstValue("Content-Disposition").orElse("").contains("ann-example.rzlib.zip"));
        assertFalse(node.awake(ann.library().id()), "asleep while it is packed");
        HttpResponse<String> meanwhile = api(annKey, "/v1/status");
        assertEquals(503, meanwhile.statusCode());
        assertEquals("This library is being packed for download; it opens again in a minute.", M.readTree(meanwhile.body()).path("error").path("message").asText());
        assertEquals("60", meanwhile.headers().firstValue("Retry-After").orElse(""));
        Path got = tmp.resolve("ann.rzlib.zip");
        try (InputStream in = taking.body()) { Files.copy(in, got); }
        Read r;
        try (InputStream in = Files.newInputStream(got)) { r = read(in); }
        assertEquals(ann.library().id(), r.manifest().path("library_id").asText());
        assertEquals(svc.url(), r.manifest().path("from").asText());
        assertTrue(r.names().contains("library/raw/big.bin"));
        HttpResponse<String> after = api(annKey, "/v1/status");
        assertEquals(200, after.statusCode(), "it opens again after: " + after.body());
        assertTrue(store.audits(ann.account().id(), 0, 50).stream().anyMatch(a -> a.act().equals("library_archived") && a.library().equals(ann.library().id())));

        // the account page offers it beside the libraries one keeps, and only there
        String annPage = c.send(HttpRequest.newBuilder(URI.create(svc.url() + "/account")).header("Cookie", AccountService.COOKIE + "=" + store.newSession(ann.account().id(), "", "a browser", 1)).header("Accept", "text/html").GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        String boPage = c.send(HttpRequest.newBuilder(URI.create(svc.url() + "/account")).header("Cookie", AccountService.COOKIE + "=" + store.newSession(bo.account().id(), "", "a browser", 1)).header("Accept", "text/html").GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(annPage.contains(AccountService.ARCHIVE + "?library=" + ann.library().id()));
        assertFalse(boPage.contains(AccountService.ARCHIVE + "?library=" + ann.library().id()), "Bo is let in; it is not his to take");
        assertTrue(boPage.contains(AccountService.ARCHIVE + "?library=" + bo.library().id()), "his own is");

        // with copies kept: packed from the copy brought up to date now, while the library answers
        setting(LibraryPlaces.COPIES, tmp.resolve("copies").toString());
        Files.writeString(LibraryPlaces.library(ann.library()).resolve("findings").resolve("late.md"), "written just before\n");
        assertEquals(200, api(annKey, "/v1/status").statusCode());
        HttpResponse<InputStream> fromCopy = c.send(HttpRequest.newBuilder(URI.create(svc.url() + AccountService.ARCHIVE)).header("Authorization", "Bearer " + annKey).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, fromCopy.statusCode());
        assertEquals(200, api(annKey, "/v1/status").statusCode(), "it answers meanwhile");
        try (InputStream in = fromCopy.body()) { r = read(in); }
        assertTrue(r.names().contains("library/findings/late.md"), "the copy as the library is now");

        // a person's own computer opens it as a library of their own; a service takes it in for an account
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        Path home = tmp.resolve("ann-home");
        set.add("RESEARCHZOSHO_LIBRARY");   // take makes it this test home's library when it has none: put back after
        int code = LibraryArchive.take(new String[]{"researchzosho", "take", got.toString(), home.toString()}, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
        assertEquals(0, code, e.toString(StandardCharsets.UTF_8));
        assertTrue(o.toString(StandardCharsets.UTF_8).startsWith("The library \"Ann Example\" is at " + home), o.toString(StandardCharsets.UTF_8));
        LibrarianDaemon mine = LibrarianDaemon.start(new LibraryStore(home), "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            JsonNode status = M.readTree(c.send(HttpRequest.newBuilder(URI.create(mine.url() + "/v1/status")).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
            assertEquals(ann.library().id(), status.path("library_id").asText(), "the same library, served as one's own");
        } finally { mine.stop(); }
        o.reset(); e.reset();
        assertEquals(1, LibraryArchive.take(new String[]{"researchzosho", "take", got.toString(), home.toString()}, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8)), "never over a library that is there");
        assertTrue(e.toString(StandardCharsets.UTF_8).contains("already"));
        svc.stop(); svc = null; node.stopAll(); store.close(); store = null;
        Path second = tmp.resolve("second");
        setting(AccountService.ROOT, second.toString());
        assertEquals(0, AccountsCliTest.run("add", "Cy Example").code());
        AccountsCliTest.Ran in = AccountsCliTest.run("take", got.toString(), "--for", "Cy Example");
        assertEquals(0, in.code(), in.err());
        assertTrue(in.out().contains("The library \"Ann Example\" is taken in") && in.out().contains("From its archive of " + r.manifest().path("from").asText()), in.out());
        try (AccountStore other = AccountStore.open(second.resolve("accounts.db").toString())) {
            AccountStore.Library taken = other.library(ann.library().id());
            assertEquals("Ann Example", taken.title()); assertEquals(other.accountByLogin("Cy Example").id(), taken.owner());
            assertTrue(Files.isRegularFile(LibraryPlaces.library(taken).resolve("raw").resolve("big.bin")));
            assertFalse(Files.exists(second.resolve("taking")), "nothing left where it was unpacked");
        }
    }
}
