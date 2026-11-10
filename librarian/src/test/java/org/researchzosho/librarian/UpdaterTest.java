package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The self-update: a release is fetched, checked against SHA256SUMS and installed into versions/ beside the one that runs; the pointer
 * moves (a link on Linux and macOS, current.txt on Windows); the version before stays as the way back and two are kept; an install of the
 * first layout takes the new one at its first update without taking the running program's files from under it; a bad checksum changes
 * nothing. The releases are tarballs built here and served on the loopback.
 */
class UpdaterTest {

    @AfterEach void marks() { Updater.unhold(); try { Files.deleteIfExists(Updater.restartMark()); } catch (IOException ignored) { } }

    /** Serves {@code dir} as GitHub does: the release URL answers 302 to the store that holds the bytes. */
    static String serve(Path dir, HttpServer s) {
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

    /** An install of the first layout: bin/ and lib/ at the root, one jar and one launcher that says its version. */
    static void fakeRoot(Path root, String version) throws Exception {
        Files.createDirectories(root.resolve("lib")); Files.createDirectories(root.resolve("bin"));
        Files.writeString(root.resolve("lib").resolve("librarian-" + version + ".jar"), "jar " + version);
        Files.writeString(root.resolve("bin").resolve("researchzosho"), "#!/bin/sh\necho " + version + " \"$@\"\n");
        Files.writeString(root.resolve("bin").resolve("zosho"), "#!/bin/sh\necho zosho " + version + " \"$@\"\n");
        Files.writeString(root.resolve("README.md"), "readme " + version);
        try { for (String n : List.of("researchzosho", "zosho")) Files.setPosixFilePermissions(root.resolve("bin").resolve(n), PosixFilePermissions.fromString("rwxr-xr-x")); } catch (UnsupportedOperationException ignored) { }
    }

    /** The release of {@code version} in {@code release/}: its tarball (with its own runtime when {@code runtime}) added to SHA256SUMS. */
    static void release(Path tmp, String version, boolean runtime) throws Exception {
        Path release = tmp.resolve("release"); Files.createDirectories(release);
        Path stage = tmp.resolve("stage-" + version + (runtime ? "-rt" : "")); fakeRoot(stage.resolve("researchzosho"), version);
        if (runtime) Files.createDirectories(stage.resolve("researchzosho").resolve("jre").resolve("bin"));
        String asset = "researchzosho-" + version + (runtime ? "-" + Updater.platformTag() : "") + ".tar.gz";
        assertEquals(0, new ProcessBuilder("tar", "czf", release.resolve(asset).toString(), "-C", stage.toString(), "researchzosho").inheritIO().start().waitFor());
        Files.writeString(release.resolve("SHA256SUMS"), Updater.sha256(release.resolve(asset)) + "  " + asset + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static Path release(Path tmp) throws Exception { release(tmp, "0.1.2", false); return tmp.resolve("release"); }

    /** This machine's own pid record, task script and log are never looked at: each test has its own. */
    static Updater.Places places(Path tmp) { return new Updater.Places(tmp.resolve("state").resolve("researchzosho.pid"), tmp.resolve("state").resolve("ResearchZosho.ps1"), tmp.resolve("state").resolve("logs").resolve("update.log")); }

    static PrintStream quiet() { return new PrintStream(new ByteArrayOutputStream()); }

    static Updater.Outcome update(Service.Os os, Path root, String have, String to, String base, Path tmp, Path running) throws Exception {
        return Updater.install(os, root, have, to, base, false, places(tmp), quiet(), cmd -> { }, running);
    }

    static String run(Path launcher, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("sh", launcher.toString())); cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String o = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, p.waitFor(), o);
        return o;
    }

    @Test @DisabledOnOs(OS.WINDOWS)
    void aFirstLayoutInstallMovesBesideTheNewVersionWithoutTakingTheRunningProgramsFiles(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("share").resolve("researchzosho"); fakeRoot(root, "0.1.1");
        Path release = release(tmp);
        Updater.Layout l = new Updater.Layout(root, Service.Os.linux);
        assertFalse(l.versioned());
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            String base = serve(release, s);
            var out = new ByteArrayOutputStream();
            // the program that updates runs from the first layout's lib/: its files stay where it reads them
            Updater.Outcome o = Updater.install(Service.Os.linux, root, "0.1.1", "0.1.2", base, false, places(tmp), new PrintStream(out, true), cmd -> { }, root.resolve("lib"));
            assertTrue(o.updated(), o.toString());
            assertTrue(out.toString().contains("checksum verified"), out.toString());
            assertTrue(l.versioned(), "the install keeps its versions side by side now");
            assertEquals("0.1.2", l.current());
            assertEquals(Path.of("versions", "0.1.2"), Files.readSymbolicLink(root.resolve("current")), "a relative link: the install can be moved whole");
            assertEquals(List.of("0.1.1", "0.1.2"), l.installed());
            assertTrue(Files.isSameFile(root.resolve("lib").resolve("librarian-0.1.1.jar"), root.resolve("versions").resolve("0.1.1").resolve("lib").resolve("librarian-0.1.1.jar")),
                    "the old files are in versions/0.1.1 as hard links of the running program's own, which stay");
            assertTrue(Files.readString(root.resolve("bin").resolve("researchzosho")).contains(Updater.LAUNCHER_MARK), "bin/ holds the launchers that read the pointer");
            assertEquals("#!/bin/sh\necho 0.1.1 \"$@\"\n", Files.readString(root.resolve("versions").resolve("0.1.1").resolve("bin").resolve("researchzosho")), "the old launcher is kept with its version");
            assertEquals("0.1.2 a b", run(root.resolve("bin").resolve("researchzosho"), "a", "b"), "the launcher runs the new version");
            assertEquals("zosho 0.1.2", run(root.resolve("bin").resolve("zosho")));
            assertEquals("0.1.2", Updater.installedVersion(l));
            assertTrue(o.note().startsWith("updated to 0.1.2; 0.1.1 stays beside it as the way back (researchzosho update back); restart the service to run it"), o.note());
            try (var x = Files.list(tmp.resolve("share"))) { assertEquals(1, x.count(), "no update folder is left beside the install"); }
            // the next update, from the new layout: the first layout's names go, and two versions are kept
            release(tmp, "0.1.3", false);
            update(Service.Os.linux, root, "0.1.2", "0.1.3", base, tmp, root.resolve("versions").resolve("0.1.2").resolve("lib"));
            assertEquals(List.of("0.1.2", "0.1.3"), l.installed(), "two versions: the one that runs and the way back");
            assertFalse(Files.exists(root.resolve("lib")) || Files.exists(root.resolve("README.md")), "nothing runs from the first layout's names any more: they are gone");
            assertEquals("0.1.3 x", run(root.resolve("bin").resolve("researchzosho"), "x"));
        } finally { s.stop(0); }
    }

    @Test @DisabledOnOs(OS.WINDOWS)
    void aNewVersionInstallsBesideTheOneThatRunsWhichStaysUntouchedAndTwoAreKept(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("share").resolve("researchzosho"); fakeRoot(root, "0.1.1");
        Path release = release(tmp);
        release(tmp, "0.1.3", false); release(tmp, "0.1.4", false); release(tmp, "0.1.5", false);
        Updater.Layout l = new Updater.Layout(root, Service.Os.linux);
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            String base = serve(release, s);
            update(Service.Os.linux, root, "0.1.1", "0.1.2", base, tmp, null);
            Path running = root.resolve("versions").resolve("0.1.2").resolve("lib");
            long before = Files.getLastModifiedTime(running.resolve("librarian-0.1.2.jar")).toMillis();
            Object key = Files.readAttributes(running.resolve("librarian-0.1.2.jar"), BasicFileAttributes.class).fileKey();
            update(Service.Os.linux, root, "0.1.2", "0.1.3", base, tmp, running);
            assertEquals("0.1.3", l.current());
            assertEquals(key, Files.readAttributes(running.resolve("librarian-0.1.2.jar"), BasicFileAttributes.class).fileKey(), "the running program's own jar is the same file");
            assertEquals(before, Files.getLastModifiedTime(running.resolve("librarian-0.1.2.jar")).toMillis());
            // a third: the oldest goes, except the one the program runs from
            update(Service.Os.linux, root, "0.1.3", "0.1.4", base, tmp, running);
            assertEquals(List.of("0.1.2", "0.1.3", "0.1.4"), l.installed(), "0.1.2 runs this program: it stays until the program has restarted");
            update(Service.Os.linux, root, "0.1.4", "0.1.5", base, tmp, root.resolve("versions").resolve("0.1.4").resolve("lib"));   // the next update, the program restarted onto 0.1.4
            assertEquals(List.of("0.1.4", "0.1.5"), l.installed(), "two versions are kept");
        } finally { s.stop(0); }
    }

    @Test @DisabledOnOs(OS.WINDOWS)
    void backRunsTheVersionBeforeAndTheAutoUpdateDoesNotTakeTheOtherAgain(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("share").resolve("researchzosho"); fakeRoot(root, "0.1.1");
        Path release = release(tmp);
        Updater.Layout l = new Updater.Layout(root, Service.Os.linux);
        // a first-layout install has one version: no way back yet
        Updater.Outcome none = Updater.backLocked(Service.Os.linux, root, false, places(tmp), quiet(), cmd -> { });
        assertEquals(Updater.Result.NOT_HERE, none.result(), none.toString());
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            update(Service.Os.linux, root, "0.1.1", "0.1.2", serve(release, s), tmp, null);
        } finally { s.stop(0); }
        Updater.Outcome back = Updater.backLocked(Service.Os.linux, root, false, places(tmp), quiet(), cmd -> { });
        assertTrue(back.updated() && back.from().equals("0.1.2") && back.to().equals("0.1.1"), back.toString());
        assertEquals("0.1.1", l.current());
        assertEquals("0.1.1 now", run(root.resolve("bin").resolve("researchzosho"), "now"));
        assertEquals(List.of("0.1.1", "0.1.2"), l.installed(), "the one gone back from stays beside it");
        assertEquals("0.1.2", Updater.held());
        assertTrue(Updater.whyNot(true, "0.1.2", "0.1.1", Updater.held()).startsWith("you went back from 0.1.2 with researchzosho update back"), "the auto-update leaves it");
        assertEquals("", Updater.whyNot(true, "0.1.3", "0.1.1", Updater.held()), "a newer one it takes");
        // update now goes to it again, from what is kept: nothing is downloaded (no server answers)
        var out = new ByteArrayOutputStream();
        Updater.Outcome again = Updater.install(Service.Os.linux, root, "0.1.1", "0.1.2", "http://127.0.0.1:1", false, places(tmp), new PrintStream(out, true), cmd -> { }, null);
        assertTrue(again.updated(), again.toString());
        assertTrue(out.toString().contains("0.1.2 is kept from before; nothing to download"), out.toString());
        assertEquals("0.1.2", l.current());
    }

    @Test @DisabledOnOs(OS.WINDOWS)
    void aBadChecksumChangesNothingThePointerIncluded(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("share").resolve("researchzosho"); fakeRoot(root, "0.1.1");
        Path release = release(tmp);
        Updater.Layout l = new Updater.Layout(root, Service.Os.linux);
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            String base = serve(release, s);
            update(Service.Os.linux, root, "0.1.1", "0.1.2", base, tmp, null);
            release(tmp, "0.1.3", false);
            Files.writeString(release.resolve("SHA256SUMS"), "0000000000000000000000000000000000000000000000000000000000000000  researchzosho-0.1.3.tar.gz\n");
            var e = assertThrows(Exception.class, () -> update(Service.Os.linux, root, "0.1.2", "0.1.3", base, tmp, null));
            assertTrue(e.getMessage().contains("checksum mismatch"), e.getMessage());
            assertEquals("0.1.2", l.current(), "the pointer is where it was");
            assertEquals(List.of("0.1.1", "0.1.2"), l.installed(), "nothing installed, nothing removed");
            try (var x = Files.list(tmp.resolve("share"))) { assertEquals(1, x.count(), "the download is removed"); }
            // and on the first layout, nothing moves either
            Path flat = tmp.resolve("other").resolve("researchzosho"); fakeRoot(flat, "0.1.1");
            assertThrows(Exception.class, () -> update(Service.Os.linux, flat, "0.1.1", "0.1.3", base, tmp, null));
            assertFalse(Files.exists(flat.resolve("versions").resolve("0.1.1")) || Files.exists(flat.resolve("current")), "the first layout stays as it was");
            assertEquals("#!/bin/sh\necho 0.1.1 \"$@\"\n", Files.readString(flat.resolve("bin").resolve("researchzosho")));
        } finally { s.stop(0); }
    }

    @Test @DisabledOnOs(OS.WINDOWS)
    void theLauncherFollowsALinkToItself(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("share").resolve("researchzosho"); fakeRoot(root, "0.1.1");
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try { update(Service.Os.linux, root, "0.1.1", "0.1.2", serve(release(tmp), s), tmp, null); } finally { s.stop(0); }
        Path elsewhere = Files.createDirectories(tmp.resolve("bin"));
        Files.createSymbolicLink(elsewhere.resolve("researchzosho"), root.resolve("bin").resolve("researchzosho"));
        assertEquals("0.1.2 hi", run(elsewhere.resolve("researchzosho"), "hi"));
    }

    @Test
    void anInstallWithItsOwnRuntimeTakesTheNextVersionsPlatformBuild(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("researchzosho"); fakeRoot(root, "0.1.1");
        Files.createDirectories(root.resolve("jre").resolve("bin"));   // the mark of a build that carries its own Java
        release(tmp, "0.1.2", true);
        release(tmp, "0.1.2", false);   // the plain tarball is there too, and must NOT be the one taken
        Path release = tmp.resolve("release");
        String asset = "researchzosho-0.1.2-" + Updater.platformTag() + ".tar.gz";
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
            Path fresh = Updater.fetchAndUnpack(root, "0.1.2", "http://127.0.0.1:" + server.getAddress().getPort(), Files.createDirectories(tmp.resolve("work")), quiet());
            assertTrue(asked.contains("/" + asset), asked.toString());
            assertFalse(asked.contains("/researchzosho-0.1.2.tar.gz"), "the plain tarball was not taken: " + asked);
            assertTrue(Files.isDirectory(fresh.resolve("jre")), "still carries its runtime");
        } finally { server.stop(0); }
        assertTrue(Updater.platformTag().matches("(linux|macos|windows)-(x64|arm64)"), Updater.platformTag());
    }

    @Test
    void theModeIsCheckUnlessSetAndTheStatusSaysSo() {
        assertEquals("check", Updater.mode());
        assertTrue(Updater.status().contains("installed:") && Updater.status().contains("mode:      check"), Updater.status());
    }

    @Test
    void aProgramAsksForTheUpdateBeforeAnyLibraryExists() {
        // `researchzosho update --json` answers with its JSON on an install with no library yet, as a program that keeps it up to date expects
        assertFalse(LibrarianCli.needsLibrary(new String[]{"librarian", "update", "--json"}));
        assertFalse(LibrarianCli.needsLibrary(new String[]{"librarian", "update", "now", "--json"}));
        assertFalse(LibrarianCli.needsLibrary(new String[]{"librarian", "update", "back"}));
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
    void aWindowsHelperStillAtWorkKeepsAnotherUpdateAwayUntilItsFolderIsGone(@TempDir Path tmp) throws Exception {
        Path lock = tmp.resolve("state").resolve("update.lock"), pending = tmp.resolve("state").resolve("update.pending");
        Path work = Files.createDirectories(tmp.resolve(".researchzosho-update-1"));
        Files.createDirectories(pending.getParent());
        Files.writeString(pending, work + "\n");
        assertEquals(Updater.Result.BUSY, Updater.guarded(lock, pending, () -> { throw new AssertionError("ran while the helper works"); }).result());
        Updater.deleteTree(work);
        assertEquals(Updater.Result.UPDATED, Updater.guarded(lock, pending, () -> new Updater.Outcome(true, "0.1.1", "0.1.2", "updated", false, Updater.Result.UPDATED)).result());
        assertFalse(Files.exists(pending), "a mark whose helper is done is removed");
    }

    @Test
    void theVersionIsCheckedAgainstTheInstallNotTheProgramRunning(@TempDir Path tmp) throws Exception {
        // another program updated the install to 0.1.2 while this one, started earlier, runs on
        Path root = tmp.resolve("researchzosho"); fakeRoot(root, "0.1.2");
        assertEquals("0.1.2", Updater.installedVersion(root));
        Updater.Outcome o = Updater.nowLocked(root, "0.1.2", false, quiet());
        assertEquals(Updater.Result.CURRENT, o.result(), o.toString());
        assertEquals(0, o.result().code, "already current is no failure for the program that asked");
        String json = Updater.outcomeJson(o);
        assertTrue(json.contains("\"result\":\"current\"") && json.contains("\"code\":0") && json.contains("\"from\":\"0.1.2\""), json);
        assertTrue(Updater.outcomeJson(new Updater.Outcome(false, "0.1.1", "0.1.1", "busy", false, Updater.Result.BUSY)).contains("\"result\":\"busy\""));
        // the install root of a program that runs from versions/<v>/lib, and of one that runs from the first layout's lib/
        assertEquals(root, Updater.rootOf(root.resolve("versions").resolve("0.1.2").resolve("lib")));
        assertEquals(root, Updater.rootOf(root.resolve("lib")));
    }

    @Test
    void aNightThatDoesNothingSaysWhy() {
        assertEquals("this is not an installed release, so nothing is installed", Updater.whyNot(false, "0.6.9", "0.6.8-dev", null));
        assertEquals("the latest release could not be read from GitHub; the next try is tomorrow night", Updater.whyNot(true, null, "0.6.8", null));
        assertEquals("up to date (0.6.8)", Updater.whyNot(true, "0.6.8", "0.6.8", null));
        assertEquals("up to date (0.6.8)", Updater.whyNot(true, "0.6.7", "0.6.8", null));
        assertEquals("", Updater.whyNot(true, "0.6.9", "0.6.8", null), "a newer release: install it, whether or not a job runs");
    }

    // ── Windows: the pointer file and the helper, as far as Linux can show them ───────────────────────────────────────

    /** An install of the new layout on Windows: versions\<v>, current.txt, and the launchers that read it. */
    static void windowsVersioned(Path root, String version) throws Exception {
        fakeRoot(root.resolve("versions").resolve(version), version);
        Updater.Layout l = new Updater.Layout(root, Service.Os.windows);
        Updater.point(l, version);
        Updater.launchers(l);
    }

    @Test
    void onWindowsTheNewVersionGoesBesideTheRunningOneAndTheFileNamesIt(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("Programs").resolve("researchzosho"); windowsVersioned(root, "0.1.1");
        Updater.Layout l = new Updater.Layout(root, Service.Os.windows);
        assertTrue(l.versioned());
        String bat = Files.readString(root.resolve("bin").resolve("researchzosho.bat"));
        assertTrue(bat.contains("set /p RZ_VERSION=<\"%RZ_ROOT%\\current.txt\"") && bat.contains("call \"%~dp0..\\versions\\%RZ_VERSION%\\bin\\researchzosho.bat\" %*") && bat.contains("\r\n"), bat);
        assertTrue(bat.chars().allMatch(c -> c < 128), "ASCII only: cmd reads a .bat in the console's code page");
        Path release = release(tmp);
        List<List<String>> started = new ArrayList<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            Updater.Outcome o = Updater.install(Service.Os.windows, root, "0.1.1", "0.1.2", serve(release, s), true, places(tmp), quiet(), started::add, root.resolve("versions").resolve("0.1.1").resolve("lib"));
            assertEquals("0.1.2\r\n", Files.readString(root.resolve("current.txt")), "the file names the new version");
            assertTrue(Files.exists(root.resolve("versions").resolve("0.1.2").resolve("lib").resolve("librarian-0.1.2.jar")));
            assertTrue(Files.exists(root.resolve("versions").resolve("0.1.1").resolve("lib").resolve("librarian-0.1.1.jar")), "the running one is untouched");
            assertTrue(started.isEmpty(), "no server runs: nothing is left for a helper to do");
            assertFalse(o.finishesAfterExit());
            assertTrue(o.note().contains("0.1.2 is in place; the next start of the program runs it."), o.note());
        } finally { s.stop(0); }
    }

    @Test
    void onWindowsAFirstLayoutInstallIsMovedByTheHelperOnceTheProgramHasEnded(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("Programs").resolve("researchzosho"); fakeRoot(root, "0.1.1");
        Path release = release(tmp);
        List<List<String>> started = new ArrayList<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            var out = new ByteArrayOutputStream();
            Updater.Outcome o = Updater.install(Service.Os.windows, root, "0.1.1", "0.1.2", serve(release, s), true, places(tmp), new PrintStream(out, true), started::add, root.resolve("lib"));
            assertTrue(o.updated() && o.finishesAfterExit(), o.toString());
            assertTrue(Files.exists(root.resolve("lib").resolve("librarian-0.1.1.jar")) && Files.readString(root.resolve("bin").resolve("researchzosho")).startsWith("#!/bin/sh\necho 0.1.1"),
                    "nothing of the running install moves while it runs");
            assertTrue(Files.exists(root.resolve("versions").resolve("0.1.2").resolve("lib").resolve("librarian-0.1.2.jar")), "the new version is beside it, in a folder of its own");
            assertEquals("0.1.2\r\n", Files.readString(root.resolve("current.txt")));
            Path work = updateFolder(root.getParent());
            assertNotNull(work, "the helper's folder waits beside the install");
            String text = Files.readString(work.resolve("finish-update.ps1"), StandardCharsets.UTF_8);
            assertTrue(text.startsWith("﻿"), "a byte order mark, so Windows PowerShell reads a user folder with any letters");
            assertTrue(text.contains("$flat = $true") && text.contains("$from = '0.1.1'; $to = '0.1.2'") && text.contains("$root = '" + root.toAbsolutePath() + "'"), text);
            assertTrue(text.contains("$waitFor = @(" + ProcessHandle.current().pid() + ")"), "it waits for this program: " + text);
            assertTrue(text.contains("$server = 0") && text.contains("$service = ''"), "no server ran, so none is started again: " + text);
            int wait = text.indexOf("Test-Gone $id"), lock = text.indexOf("[System.IO.File]::Open($lockPath"), move = text.indexOf("$why = Move-Path $p (Join-Path $dest $n)"), back = text.indexOf("[void](Move-Path (Join-Path $dest $n) (Join-Path $root $n))"), copy = text.indexOf("Copy-Item -Path (Join-Path $launchers '*')");
            assertTrue(wait > 0 && lock > wait && move > lock && back > move && copy > back, "waits, locks, moves each name, puts back what moved when one cannot, then the launchers: " + text);
            assertTrue(text.contains("foreach ($n in @('bin', 'lib', 'jre', 'README.md', 'LICENSE'))"), text);
            assertTrue(text.indexOf("Remove-Item -LiteralPath $pending") > copy && text.indexOf("Remove-Item -LiteralPath $work") > text.indexOf("Remove-Item -LiteralPath $pending"), "the mark, then its own folder, last");
            String launcher = Files.readString(work.resolve("launchers").resolve("researchzosho.bat"));
            assertTrue(launcher.contains(Updater.LAUNCHER_MARK) && launcher.contains("current.txt"), launcher);
            assertEquals(work.toAbsolutePath().toString(), Files.readString(places(tmp).pending(), StandardCharsets.UTF_8).strip(), "the mark names the helper's folder");
            assertTrue(Updater.updating(places(tmp).lock(), places(tmp).pending()), "another update finds this one going on");
            assertEquals(1, started.size());
            List<String> cmd = started.get(0);
            assertEquals(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command"), cmd.subList(0, 4));
            assertTrue(cmd.get(4).startsWith("Start-Process -FilePath powershell") && cmd.get(4).contains("-WindowStyle Hidden") && !cmd.get(4).contains("\""), cmd.get(4));
            assertTrue(o.note().contains("The update finishes when this command ends") && o.note().contains(places(tmp).log().toAbsolutePath().toString()), o.note());
        } finally { s.stop(0); }
    }

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
    void onWindowsTheServiceRestartsOntoTheNewVersionThroughTheHelper(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("Programs").resolve("researchzosho"); windowsVersioned(root, "0.1.1");
        Path release = release(tmp);
        var places = places(tmp);
        Files.createDirectories(places.pidFile().getParent());
        Files.writeString(places.taskScript(), "# the service's task script\n");
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Process server = sleeperJvm();
        try {
            Files.writeString(places.pidFile(), Long.toString(server.pid()));
            Updater.Outcome o = Updater.install(Service.Os.windows, root, "0.1.1", "0.1.2", serve(release, s), true, places, quiet(), cmd -> assertTrue(server.isAlive(), "the server still runs when the helper starts"), null);
            assertFalse(server.isAlive(), "the server is stopped, to start again on the new version");
            String text = Files.readString(updateFolder(root.getParent()).resolve("finish-update.ps1"), StandardCharsets.UTF_8);
            assertTrue(text.contains("$flat = $false") && text.contains("$server = " + server.pid()) && text.contains("$service = '" + places.taskScript() + "'"), text);
            assertTrue(text.contains("$waitFor = @(" + ProcessHandle.current().pid() + ", " + server.pid() + ")"), text);
            assertEquals("0.1.2", Updater.restartingTo(), "the jobs the stop interrupts will say they resume after an update to 0.1.2");
            assertTrue(o.note().contains("The server was stopped, and the service starts again on 0.1.2 once it has ended."), o.note());
        } finally { s.stop(0); server.destroyForcibly(); }
    }

    @Test
    void onWindowsAHelperThatCannotStartLeavesTheProgramRunning(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("Programs").resolve("researchzosho"); fakeRoot(root, "0.1.1");
        Path release = release(tmp);
        var places = places(tmp);
        Files.createDirectories(places.pidFile().getParent());
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Process server = sleeperJvm();
        try {
            Files.writeString(places.pidFile(), Long.toString(server.pid()));
            var e = assertThrows(Exception.class, () -> Updater.install(Service.Os.windows, root, "0.1.1", "0.1.2", serve(release, s), true, places, quiet(),
                    cmd -> { throw new IOException("the helper that finishes the update did not start (exit 1)"); }, null));
            assertTrue(e.getMessage().contains("did not start"), e.getMessage());
            assertTrue(server.isAlive(), "the server keeps running when there is no helper to finish the update");
            assertTrue(Files.exists(root.resolve("lib").resolve("librarian-0.1.1.jar")) && Files.readString(root.resolve("bin").resolve("researchzosho")).startsWith("#!/bin/sh"), "the install runs as it was");
            assertNull(updateFolder(root.getParent()), "the helper's folder is removed");
            assertFalse(Files.exists(places.pending()));
        } finally { s.stop(0); server.destroyForcibly(); }
    }

    @Test
    void onWindowsBackRewritesTheFile(@TempDir Path tmp) throws Exception {
        Path root = tmp.resolve("Programs").resolve("researchzosho"); windowsVersioned(root, "0.1.2");
        fakeRoot(root.resolve("versions").resolve("0.1.1"), "0.1.1");
        Updater.Outcome o = Updater.backLocked(Service.Os.windows, root, true, places(tmp), quiet(), cmd -> fail("no server runs: no helper"));
        assertTrue(o.updated() && o.to().equals("0.1.1"), o.toString());
        assertEquals("0.1.1\r\n", Files.readString(root.resolve("current.txt")));
        assertEquals("0.1.2", Updater.held());
    }
}
