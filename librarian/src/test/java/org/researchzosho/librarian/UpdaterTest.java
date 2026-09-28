package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
/** The self-update: the release's tarball is fetched, checked against SHA256SUMS, and swapped in with two renames; a bad checksum leaves the install as it was. */
class UpdaterTest {

    /** A fake install root with one jar and one launcher, and a fake release: a tarball of a newer root plus SHA256SUMS, served locally. */
    static String serve(Path dir, HttpServer s) {
        // like GitHub: the release URL answers 302 to the store that holds the bytes; an updater that does not follow it fails
        s.createContext("/", x -> {
            String path = x.getRequestURI().getPath();
            if (!path.startsWith("/store/")) { x.getResponseHeaders().set("Location", "/store" + path); x.sendResponseHeaders(302, -1); return; }
            Path f = dir.resolve(path.substring("/store/".length()));
            if (!Files.exists(f)) { x.sendResponseHeaders(404, -1); return; }
            byte[] b = Files.readAllBytes(f);
            x.sendResponseHeaders(200, b.length);
            try (var o = x.getResponseBody()) { o.write(b); }
        });
        s.start();
        return "http://127.0.0.1:" + s.getAddress().getPort();
    }

    static void fakeRoot(Path root, String version) throws Exception {
        Files.createDirectories(root.resolve("lib")); Files.createDirectories(root.resolve("bin"));
        Files.writeString(root.resolve("lib").resolve("librarian-" + version + ".jar"), "jar " + version);
        Files.writeString(root.resolve("bin").resolve("researchzosho"), "#!/bin/sh\necho " + version + "\n");
    }

    @Test
    void aVerifiedTarballIsSwappedInAndTheOldInstallIsGone(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("share").resolve("researchzosho"); fakeRoot(root, "0.1.1");
        Path release = tmp.resolve("release"); Files.createDirectories(release);
        Path stage = tmp.resolve("stage"); fakeRoot(stage.resolve("researchzosho"), "0.1.2");
        assertEquals(0, new ProcessBuilder("tar", "czf", release.resolve("researchzosho-0.1.2.tar.gz").toString(), "-C", stage.toString(), "researchzosho").inheritIO().start().waitFor());
        Files.writeString(release.resolve("SHA256SUMS"), Updater.sha256(release.resolve("researchzosho-0.1.2.tar.gz")) + "  researchzosho-0.1.2.tar.gz\n");
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            String base = serve(release, s);
            var out = new ByteArrayOutputStream();
            Updater.swapIn(root, "0.1.2", base, new PrintStream(out, true));
            assertTrue(Files.exists(root.resolve("lib").resolve("librarian-0.1.2.jar")), "the new jar is in place");
            assertFalse(Files.exists(root.resolve("lib").resolve("librarian-0.1.1.jar")), "the old jar is gone");
            assertFalse(Files.exists(root.resolveSibling("researchzosho.old")), "the old root was removed after the swap");
            assertTrue(out.toString().contains("checksum verified") && out.toString().contains("0.1.2 is in place"), out.toString());
            try (var l = Files.list(tmp.resolve("share"))) { assertEquals(1, l.count(), "no update work directory is left behind"); }
        } finally { s.stop(0); }
    }

    @Test
    void aBadChecksumIsRefusedAndNothingChanges(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("share").resolve("researchzosho"); fakeRoot(root, "0.1.1");
        Path release = tmp.resolve("release"); Files.createDirectories(release);
        Path stage = tmp.resolve("stage"); fakeRoot(stage.resolve("researchzosho"), "0.1.2");
        assertEquals(0, new ProcessBuilder("tar", "czf", release.resolve("researchzosho-0.1.2.tar.gz").toString(), "-C", stage.toString(), "researchzosho").inheritIO().start().waitFor());
        Files.writeString(release.resolve("SHA256SUMS"), "0000000000000000000000000000000000000000000000000000000000000000  researchzosho-0.1.2.tar.gz\n");
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            String base = serve(release, s);
            var e = assertThrows(Exception.class, () -> Updater.swapIn(root, "0.1.2", base, new PrintStream(new ByteArrayOutputStream())));
            assertTrue(e.getMessage().contains("checksum mismatch"), e.getMessage());
            assertTrue(Files.exists(root.resolve("lib").resolve("librarian-0.1.1.jar")), "the install is as it was");
        } finally { s.stop(0); }
    }

    @Test
    void theModeIsCheckUnlessSetAndTheStatusSaysSo() {
        assertEquals("check", Updater.mode());
        assertTrue(Updater.status().contains("installed:") && Updater.status().contains("mode:      check"), Updater.status());
    }

    @Test
    void anInstallWithItsOwnRuntimeTakesTheNextVersionsPlatformBuild(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("researchzosho"); fakeRoot(root, "0.1.1");
        Files.createDirectories(root.resolve("jre").resolve("bin"));   // the mark of a build that carries its own Java
        Path release = tmp.resolve("release"); Files.createDirectories(release);
        Path stage = tmp.resolve("stage"); fakeRoot(stage.resolve("researchzosho"), "0.1.2");
        Files.createDirectories(stage.resolve("researchzosho").resolve("jre").resolve("bin"));
        String asset = "researchzosho-0.1.2-" + Updater.platformTag() + ".tar.gz";
        assertEquals(0, new ProcessBuilder("tar", "czf", release.resolve(asset).toString(), "-C", stage.toString(), "researchzosho").inheritIO().start().waitFor());
        // the plain tarball is there too, and must NOT be the one taken
        Path plainStage = tmp.resolve("plain"); fakeRoot(plainStage.resolve("researchzosho"), "0.1.2");
        assertEquals(0, new ProcessBuilder("tar", "czf", release.resolve("researchzosho-0.1.2.tar.gz").toString(), "-C", plainStage.toString(), "researchzosho").inheritIO().start().waitFor());
        Files.writeString(release.resolve("SHA256SUMS"), Updater.sha256(release.resolve(asset)) + "  " + asset + "\n" + Updater.sha256(release.resolve("researchzosho-0.1.2.tar.gz")) + "  researchzosho-0.1.2.tar.gz\n");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> asked = new ArrayList<>();
        server.createContext("/", ex -> {
            asked.add(ex.getRequestURI().getPath());
            Path f = release.resolve(ex.getRequestURI().getPath().substring(1));
            if (!Files.exists(f)) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            byte[] b = Files.readAllBytes(f); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        server.start();
        try {
            Updater.swapIn(root, "0.1.2", "http://127.0.0.1:" + server.getAddress().getPort(), new PrintStream(new ByteArrayOutputStream()));
            assertTrue(asked.contains("/" + asset), asked.toString());
            assertFalse(asked.contains("/researchzosho-0.1.2.tar.gz"), "the plain tarball was not taken: " + asked);
            assertTrue(Files.isDirectory(root.resolve("jre")), "still carries its runtime");
        } finally { server.stop(0); }
        assertTrue(Updater.platformTag().matches("(linux|macos|windows)-(x64|arm64)"), Updater.platformTag());
    }

    /** A release of 0.1.2 (the plain tarball and SHA256SUMS) in {@code tmp/release}. */
    static Path release(Path tmp) throws Exception {
        Path release = tmp.resolve("release"); Files.createDirectories(release);
        Path stage = tmp.resolve("stage"); fakeRoot(stage.resolve("researchzosho"), "0.1.2");
        assertEquals(0, new ProcessBuilder("tar", "czf", release.resolve("researchzosho-0.1.2.tar.gz").toString(), "-C", stage.toString(), "researchzosho").inheritIO().start().waitFor());
        Files.writeString(release.resolve("SHA256SUMS"), Updater.sha256(release.resolve("researchzosho-0.1.2.tar.gz")) + "  researchzosho-0.1.2.tar.gz\n");
        return release;
    }

    /** This machine's own pid record, task script and log are never looked at: each test has its own. */
    static Updater.Places places(Path tmp) { return new Updater.Places(tmp.resolve("state").resolve("researchzosho.pid"), tmp.resolve("state").resolve("ResearchZosho.ps1"), tmp.resolve("state").resolve("logs").resolve("update.log")); }

    static Process sleeperJvm() throws Exception {
        String jvm = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(jvm, "-cp", System.getProperty("java.class.path"), ServiceTest.Sleeper.class.getName()).redirectErrorStream(true).start();
        Thread.sleep(300);
        assertTrue(p.isAlive());
        return p;
    }

    static Path updateFolder(Path parent) throws Exception {
        try (var l = Files.list(parent)) { return l.filter(p -> p.getFileName().toString().startsWith(".researchzosho-update-")).findFirst().orElse(null); }
    }

    @Test
    void aProgramAsksForTheUpdateBeforeAnyLibraryExists() {
        // `researchzosho update --json` answers with its JSON on an install with no library yet, as a program that keeps it up to date expects
        assertFalse(LibrarianCli.needsLibrary(new String[]{"librarian", "update", "--json"}));
        assertFalse(LibrarianCli.needsLibrary(new String[]{"librarian", "update", "now", "--json"}));
        assertTrue(LibrarianCli.needsLibrary(new String[]{"librarian", "status"}), "the library's own commands still need one");
    }

    @Test
    void oneUpdateAtATimeAndTheOtherIsBusyAtOnce(@TempDir Path tmp) throws Exception {
        Path lock = tmp.resolve("state").resolve("update.lock"), pending = tmp.resolve("state").resolve("update.pending");
        List<Updater.Outcome> inner = new ArrayList<>();
        Updater.Outcome outer = Updater.guarded(lock, pending, () -> {
            assertTrue(Updater.updating(lock, pending), "the update going on is seen");
            inner.add(Updater.guarded(lock, pending, () -> { throw new AssertionError("a second update ran beside the first"); }));
            return new Updater.Outcome(true, "0.1.1", "0.1.2", "updated", false, Updater.Result.UPDATED);
        });
        assertEquals(Updater.Result.UPDATED, outer.result());
        assertEquals(Updater.Result.BUSY, inner.get(0).result(), inner.get(0).toString());
        assertEquals(75, inner.get(0).result().code, "the usual code for try again later");
        assertFalse(Updater.updating(lock, pending), "the lock is free again");
        assertEquals(Updater.Result.CURRENT, Updater.guarded(lock, pending, () -> new Updater.Outcome(false, "0.1.2", "0.1.2", "already 0.1.2", false, Updater.Result.CURRENT)).result());
    }

    @Test
    void aWindowsHelperStillSwappingKeepsAnotherUpdateAwayUntilItsFolderIsGone(@TempDir Path tmp) throws Exception {
        Path lock = tmp.resolve("state").resolve("update.lock"), pending = tmp.resolve("state").resolve("update.pending");
        Path work = Files.createDirectories(tmp.resolve(".researchzosho-update-1"));
        Files.createDirectories(pending.getParent());
        Files.writeString(pending, work + "\n");
        assertEquals(Updater.Result.BUSY, Updater.guarded(lock, pending, () -> { throw new AssertionError("ran while the helper swaps"); }).result());
        Updater.deleteTree(work);
        assertEquals(Updater.Result.UPDATED, Updater.guarded(lock, pending, () -> new Updater.Outcome(true, "0.1.1", "0.1.2", "updated", false, Updater.Result.UPDATED)).result());
        assertFalse(Files.exists(pending), "a mark whose helper is done is removed");
    }

    @Test
    void theVersionIsCheckedAgainstTheFilesInstalledNotTheProgramRunning(@TempDir Path tmp) throws Exception {
        // another program updated the files to 0.1.2 while this one, started earlier, runs on
        Path root = tmp.resolve("researchzosho"); fakeRoot(root, "0.1.2");
        assertEquals("0.1.2", Updater.installedVersion(root));
        Updater.Outcome o = Updater.nowLocked(root, "0.1.2", false, new PrintStream(new ByteArrayOutputStream()));
        assertEquals(Updater.Result.CURRENT, o.result(), o.toString());
        assertEquals(0, o.result().code, "already current is no failure for the program that asked");
        String json = Updater.outcomeJson(o);
        assertTrue(json.contains("\"result\":\"current\"") && json.contains("\"code\":0") && json.contains("\"from\":\"0.1.2\""), json);
        assertTrue(Updater.outcomeJson(new Updater.Outcome(false, "0.1.1", "0.1.1", "busy", false, Updater.Result.BUSY)).contains("\"result\":\"busy\""));
    }

    @Test
    void onWindowsTheCheckedDownloadIsHandedToAHelperThatSwapsAfterTheProgramEnds(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("Programs").resolve("researchzosho"); fakeRoot(root, "0.1.1");
        Path release = release(tmp);
        List<List<String>> started = new ArrayList<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            var out = new ByteArrayOutputStream();
            Updater.Outcome o = Updater.install(Service.Os.windows, root, "0.1.1", "0.1.2", serve(release, s), true, places(tmp), new PrintStream(out, true), started::add);
            assertTrue(o.updated() && o.finishesAfterExit(), o.toString());
            // nothing is renamed while this program runs from the install
            assertTrue(Files.exists(root.resolve("lib").resolve("librarian-0.1.1.jar")), "the install is untouched until the program has ended");
            Path work = updateFolder(root.getParent());
            assertNotNull(work, "the checked download waits beside the install");
            Path fresh = work.resolve("x").resolve("researchzosho");
            assertTrue(Files.exists(fresh.resolve("lib").resolve("librarian-0.1.2.jar")), "unpacked beside the install, on the same drive");
            assertTrue(out.toString().contains("checksum verified"), out.toString());
            // what the helper is given
            Path script = work.resolve("finish-update.ps1");
            String text = Files.readString(script, StandardCharsets.UTF_8);
            assertTrue(text.startsWith("\uFEFF"), "a byte order mark, so Windows PowerShell reads a user folder with any letters");
            long self = ProcessHandle.current().pid();
            assertTrue(text.contains("$root = '" + root.toAbsolutePath() + "'"), text);
            assertTrue(text.contains("$fresh = '" + fresh.toAbsolutePath() + "'"), text);
            assertTrue(text.contains("$old = '" + root.toAbsolutePath() + ".old'"), text);
            assertTrue(text.contains("$waitFor = @(" + self + ")"), "it waits for this program: " + text);
            assertTrue(text.contains("$server = 0") && text.contains("$service = ''"), "no server ran, so none is started again: " + text);
            assertTrue(text.contains("$log = '" + places(tmp).log().toAbsolutePath() + "'"), text);
            assertTrue(Files.isDirectory(places(tmp).log().getParent()), "the log's folder is there for the helper");
            // what it does: move the install aside, move the new one in; when that fails, put the old one back
            int aside = text.indexOf("Move-Folder $root $old"), in = text.indexOf("Move-Folder $fresh $root"), back = text.indexOf("Move-Folder $old $root");
            assertTrue(aside > 0 && in > aside && back > in, "aside, in, and back only after a failed move in: " + text);
            assertTrue(text.indexOf("Remove-Item -LiteralPath $work") > back, "and it removes its own folder last");
            // one update at a time: the mark says the update goes on after this program ends, and the helper holds the lock while it swaps
            assertEquals(work.toAbsolutePath().toString(), Files.readString(places(tmp).pending(), StandardCharsets.UTF_8).strip(), "the mark names the helper's folder");
            assertTrue(Updater.updating(places(tmp).lock(), places(tmp).pending()), "another update finds this one going on");
            int lock = text.indexOf("[System.IO.File]::Open($lockPath"), unmark = text.indexOf("Remove-Item -LiteralPath $pending");
            assertTrue(lock > text.indexOf("Test-Gone $id") && lock < aside && unmark > back && unmark < text.indexOf("Remove-Item -LiteralPath $work"),
                    "the helper takes the lock once the program has ended, before the swap, and lets go of the mark before its folder: " + text);
            // how it is started: detached, hidden, from outside the install, with no double quote for the JVM to mangle
            assertEquals(1, started.size());
            List<String> cmd = started.get(0);
            assertEquals(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command"), cmd.subList(0, 4));
            assertTrue(cmd.get(4).startsWith("Start-Process -FilePath powershell") && cmd.get(4).contains("-WindowStyle Hidden"), cmd.get(4));
            assertTrue(cmd.get(4).contains("'" + script.toAbsolutePath() + "'") && cmd.get(4).contains("-WorkingDirectory '" + root.toAbsolutePath().getParent() + "'"), cmd.get(4));
            assertFalse(cmd.get(4).contains("\""), cmd.get(4));
            assertTrue(o.note().contains("The update finishes when this command ends. Open a new terminal and run researchzosho --version to see 0.1.2."), o.note());
            assertTrue(o.note().contains(places(tmp).log().toAbsolutePath().toString()), "the note says where the helper writes: " + o.note());
        } finally { s.stop(0); }
    }

    @Test
    void onWindowsTheRunningServerIsStoppedAfterTheHelperStartsAndTheServiceComesBack(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("Programs").resolve("researchzosho"); fakeRoot(root, "0.1.1");
        Path release = release(tmp);
        var places = places(tmp);
        Files.createDirectories(places.pidFile().getParent());
        Files.writeString(places.taskScript(), "# the service's task script\n");
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Process server = sleeperJvm(), second = null;
        try {
            String base = serve(release, s);
            Files.writeString(places.pidFile(), Long.toString(server.pid()));
            var out = new ByteArrayOutputStream();
            Updater.Outcome o = Updater.install(Service.Os.windows, root, "0.1.1", "0.1.2", base, true, places, new PrintStream(out, true), cmd -> assertTrue(server.isAlive(), "the server still runs when the helper starts"));
            assertFalse(server.isAlive(), "the server held the install's files; it is stopped");
            assertTrue(out.toString().contains("stopped the running server (pid " + server.pid() + ")"), out.toString());
            String text = Files.readString(updateFolder(root.getParent()).resolve("finish-update.ps1"), StandardCharsets.UTF_8);
            assertTrue(text.contains("$waitFor = @(" + ProcessHandle.current().pid() + ", " + server.pid() + ")"), text);
            assertTrue(text.contains("$server = " + server.pid()) && text.contains("$service = '" + places.taskScript() + "'"), text);
            assertTrue(o.note().contains("The server was stopped for the update, and the service starts again on 0.1.2 once the files are in place."), o.note());
            Updater.deleteTree(updateFolder(root.getParent()));

            // --no-restart: the server still has to stop, and it is not started again
            second = sleeperJvm();
            Files.writeString(places.pidFile(), Long.toString(second.pid()));
            Updater.Outcome n = Updater.install(Service.Os.windows, root, "0.1.1", "0.1.2", base, false, places, new PrintStream(new ByteArrayOutputStream()), cmd -> { });
            assertFalse(second.isAlive());
            String text2 = Files.readString(updateFolder(root.getParent()).resolve("finish-update.ps1"), StandardCharsets.UTF_8);
            assertTrue(text2.contains("$service = ''"), text2);
            assertTrue(n.note().contains("The server was stopped for the update. Start it again when the update has finished"), n.note());
        } finally {
            s.stop(0); server.destroyForcibly(); if (second != null) second.destroyForcibly();
        }
    }

    @Test
    void onWindowsAHelperThatCannotStartChangesNothing(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("Programs").resolve("researchzosho"); fakeRoot(root, "0.1.1");
        Path release = release(tmp);
        var places = places(tmp);
        Files.createDirectories(places.pidFile().getParent());
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Process server = sleeperJvm();
        try {
            String base = serve(release, s);
            Files.writeString(places.pidFile(), Long.toString(server.pid()));
            var e = assertThrows(Exception.class, () -> Updater.install(Service.Os.windows, root, "0.1.1", "0.1.2", base, true, places, new PrintStream(new ByteArrayOutputStream()),
                    cmd -> { throw new IOException("the helper that finishes the update did not start (exit 1)"); }));
            assertTrue(e.getMessage().contains("did not start"), e.getMessage());
            assertTrue(server.isAlive(), "the server keeps running when there is no helper to finish the update");
            assertTrue(Files.exists(root.resolve("lib").resolve("librarian-0.1.1.jar")), "the install is as it was");
            assertNull(updateFolder(root.getParent()), "the download is removed");
        } finally { s.stop(0); server.destroyForcibly(); }
    }

    @Test
    void linuxAndMacosStillRenameInPlaceThroughTheSameSeam(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("share").resolve("researchzosho"); fakeRoot(root, "0.1.1");
        Path release = release(tmp);
        List<List<String>> started = new ArrayList<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            Updater.Outcome o = Updater.install(Service.Os.macos, root, "0.1.1", "0.1.2", serve(release, s), false, places(tmp), new PrintStream(new ByteArrayOutputStream()), started::add);
            assertTrue(o.updated() && !o.finishesAfterExit(), o.toString());
            assertTrue(Files.exists(root.resolve("lib").resolve("librarian-0.1.2.jar")), "swapped in now");
            assertTrue(started.isEmpty(), "no helper");
            assertEquals("updated to 0.1.2; restart the service to run it", o.note());
        } finally { s.stop(0); }
    }
}
