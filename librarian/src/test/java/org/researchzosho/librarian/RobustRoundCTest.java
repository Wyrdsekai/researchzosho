package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single-person program's robustness (0.8.0 review, round C, items 15): `service stop` keeps a server's record when it cannot tell
 * (ROB 6), an earlier install's llama-swap on Windows is found by this program's config (ROB 13), and the findings cache counts only what
 * it keeps (ROB 11).
 */
class RobustRoundCTest {
    final ModelServer.Runner realRunner = ModelServer.runner;
    final Predicate<String> realHealth = ModelServer.health;

    @AfterEach void back() { ProcessProof.READ = null; ModelServer.runner = realRunner; ModelServer.health = realHealth; ModelServer.SWAPS = ModelServer.SWAPS_RUNNING; }

    @Test
    void serviceStopKeepsTheRecordWhenItCannotTellAndStopsAServerWhoseStartReadsLater(@TempDir Path tmp) throws Exception {
        String home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        Process server = null;
        try {
            server = StandInServer.start(tmp);
            Files.createDirectories(Service.pidFile().getParent());
            Files.writeString(Service.pidFile(), Long.toString(server.pid()), StandardCharsets.UTF_8);
            Service.Plan plan = Service.plan(Service.Os.linux, "researchzosho", 4649, 3, Path.of(System.getProperty("user.home")));
            // the command line cannot be read (PowerShell's CIM over five seconds, say): nothing is known, so nothing is forgotten
            ProcessProof.READ = pid -> List.of();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            Service.run("stop", plan, new PrintStream(o, true, StandardCharsets.UTF_8));
            assertTrue(server.isAlive());
            assertTrue(Files.exists(Service.pidFile()), "the record is kept: " + o.toString(StandardCharsets.UTF_8));
            assertTrue(o.toString(StandardCharsets.UTF_8).contains("could not be read"), o.toString(StandardCharsets.UTF_8));
            // read, and the server's own, but its start reads later than its record (a clock stepped forward): it is the server, and stopped
            ProcessProof.READ = null;
            Files.setLastModifiedTime(Service.pidFile(), FileTime.fromMillis(server.info().startInstant().orElseThrow().toEpochMilli() - Duration.ofSeconds(30).toMillis()));
            o.reset();
            Service.run("stop", plan, new PrintStream(o, true, StandardCharsets.UTF_8));
            server.waitFor(10, TimeUnit.SECONDS);
            assertFalse(server.isAlive(), "the server is stopped: " + o.toString(StandardCharsets.UTF_8));
        } finally {
            if (server != null) { server.destroyForcibly(); server.waitFor(); }
            System.setProperty("user.home", home); Config.invalidate();
        }
    }

    @Test
    void anEarlierInstallsModelProxyIsFoundByThisProgramsConfig(@TempDir Path tmp) throws Exception {
        String config = tmp.resolve("config.yaml").toString();
        ModelServer.SWAPS = () -> List.of(4242L, 4343L);
        ProcessProof.READ = pid -> pid == 4242L ? List.of("C:\\rz\\llama-swap.exe", "--config", config, "--listen", "127.0.0.1:8211") : List.of("C:\\theirs\\llama-swap.exe", "--config", "D:\\theirs\\config.yaml");
        List<List<String>> ran = new CopyOnWriteArrayList<>();
        ModelServer.runner = cmd -> { ran.add(cmd); return new ModelServer.Result(0, ""); };
        ModelServer.health = url -> false;
        ModelServer.stopOurProxy(tmp);   // no llama-swap.pid: an install from a release before it was kept
        assertTrue(ran.contains(List.of("taskkill", "/pid", "4242", "/t", "/f")), "the one with this program's config: " + ran);
        assertFalse(ran.stream().anyMatch(c -> c.contains("4343")), "a llama-swap the person runs is left alone");
    }

    @Test
    void theFindingsCacheCountsWhatItKeeps(@TempDir Path tmp) throws Exception {
        FindingsCache.clear();
        Path dir = Files.createDirectories(tmp.resolve("findings"));
        long real = 0;
        for (int i = 1; i <= 40; i++) {
            String id = String.format("F-%04d-n%d", i, i), text = FindingsCacheTest.text(id, "title " + i, "body " + i);
            Files.writeString(dir.resolve(id + ".md"), text, StandardCharsets.UTF_8);
            real += text.getBytes(StandardCharsets.UTF_8).length;
        }
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        long until = System.currentTimeMillis() + 1_500;   // within the settling time every ask reads every file again
        for (int t = 0; t < 8; t++) ts.add(Thread.ofPlatform().start(() -> { try { go.await(); while (System.currentTimeMillis() < until) FindingsCache.scan(dir); } catch (Exception ignored) { } }));
        go.countDown();
        for (Thread th : ts) th.join();
        Field kept = FindingsCache.class.getDeclaredField("SHELVES_KEPT"); kept.setAccessible(true);
        Object shelf = ((Map<?, ?>) kept.get(null)).values().iterator().next();
        Field bytes = shelf.getClass().getDeclaredField("bytes"); bytes.setAccessible(true);
        long counted = ((AtomicLong) bytes.get(shelf)).get();
        assertTrue(counted <= real, "the shelf counts " + counted + " bytes for files of " + real);
    }
}
