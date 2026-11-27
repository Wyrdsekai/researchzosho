package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one conversation holds on each card (2026-11-18). The chat's tools and instructions are about 16,000 tokens, so a conversation of
 * 16,384 holds none of it: gpt-oss-20b served with 32k for two conversations refused the first page the chat read. Measured on a 16 GB
 * card, gpt-oss-20b holds 32k for one conversation and not 64k for two; the 9B and Gemma 4 E4B hold 32k for one by their cache sizes;
 * Gemma 4 E2B's card holds the 16k it has. An install still on the slot an earlier release wrote is moved to the new one, unless its
 * person chose their own model or wrote another slot.
 */
class SlotsTest {
    final ModelServer.Os realOs = ModelServer.os;
    final ModelServer.Runner realRunner = ModelServer.runner;
    final Predicate<String> realHealth = ModelServer.health;
    String home;

    @AfterEach void back() {
        ModelServer.os = realOs; ModelServer.runner = realRunner; ModelServer.health = realHealth;
        if (home != null) { System.setProperty("user.home", home); Config.invalidate(); }
    }

    static int perConversation(ModelServer.Row r) { return r.ctx() / r.parallel(); }

    @Test
    void eachCardsModelGivesOneConversationWhatTheCardHolds() {
        assertEquals("gpt-oss-20b", ModelServer.rowFor(16, 16).name());
        assertEquals(32768, ModelServer.rowFor(16, 16).ctx()); assertEquals(1, ModelServer.rowFor(16, 16).parallel(), "64k for two did not load on a 16 GB card");
        assertEquals("qwen3.5-9b", ModelServer.rowFor(8, 16).name());
        assertEquals(32768, perConversation(ModelServer.rowFor(8, 16)));
        assertEquals("gemma-4-e4b", ModelServer.rowFor(4, 16).name());
        assertEquals(32768, perConversation(ModelServer.rowFor(4, 16)));
        assertEquals("gemma-4-e2b", ModelServer.rowFor(2, 16).name());
        assertEquals(16384, perConversation(ModelServer.rowFor(2, 16)), "a 2 GB card holds the 16k it has");
        for (ModelServer.Row r : ModelServer.ROWS) if (!r.name().equals("gemma-4-e2b")) assertTrue(perConversation(r) >= 32768, r.name() + " gives a conversation " + perConversation(r));
    }

    /** An install as an earlier release wrote it: the row with the slot of then, its files, its plan; {@code extra} goes on the plan. */
    static ModelServer.Row earlier(ModelServer.Row now, int ctx, int parallel) {
        return new ModelServer.Row(now.name(), now.minGb(), now.hf(), now.revision(), now.file(), now.sha256(), now.bytes(), ctx, parallel, now.extra(), now.moe());
    }

    static void install(Path dir, ModelServer.Row row, String extra) throws Exception {
        Path model = ModelServer.modelsDir().resolve(row.file());
        ModelServer.Plan p = ModelServer.plan(ModelServer.Os.linux, row, model, dir, ModelServer.unitPath(ModelServer.Os.linux), "all", 10, false, "89-1.8");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.yaml"), p.configYaml());
        Files.writeString(dir.resolve("run.sh"), p.runScript());
        Files.writeString(dir.resolve("plan.properties"), ModelServer.planProperties(row, model, "all", 10, false) + extra);
    }

    @Test
    void anInstallOnTheSlotOfBeforeMovesToTheNewOneUnlessItsPersonChoseTheirOwn(@TempDir Path tmp) throws Exception {
        home = System.getProperty("user.home");
        System.setProperty("user.home", tmp.toString()); Config.invalidate();
        ModelServer.os = ModelServer.Os.linux;
        List<List<String>> ran = new CopyOnWriteArrayList<>();
        ModelServer.runner = cmd -> { ran.add(cmd); return new ModelServer.Result(0, ""); };
        ModelServer.health = base -> true;
        Path dir = ModelServer.dir();
        List<String> restart = List.of("systemctl", "--user", "restart", ModelServer.proxyUnit());
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true);
        for (Object[] c : new Object[][]{{ModelServer.rowFor(16, 16), 32768, 2}, {ModelServer.rowFor(8, 16), 16384, 1}, {ModelServer.rowFor(4, 16), 16384, 1}}) {
            ModelServer.Row now = (ModelServer.Row) c[0], then = earlier(now, (int) c[1], (int) c[2]);
            install(dir, then, "chosen=suggested\n");
            ran.clear();
            assertEquals(now.name(), ModelServer.upgrade(quiet), now.name());
            String run = Files.readString(dir.resolve("run.sh"));
            assertTrue(run.contains(" -c " + now.ctx() + " --parallel " + now.parallel() + " ") && !run.contains(" -c " + then.ctx() + " --parallel " + then.parallel() + " "), now.name() + ": " + run);
            assertTrue(Files.readString(dir.resolve("plan.properties")).contains("ctx=" + now.ctx() + "\nparallel=" + now.parallel() + "\n"), Files.readString(dir.resolve("plan.properties")));
            assertTrue(ran.contains(restart), "the proxy starts the model with it: " + ran);
            ran.clear();
            assertEquals("", ModelServer.upgrade(quiet), "nothing the second time");
            assertTrue(ran.isEmpty());
        }
        // the person's own model, and a slot written by hand: left as they are
        ModelServer.Row gpt = ModelServer.rowFor(16, 16), then = earlier(gpt, 32768, 2);
        install(dir, then, "chosen=yours\n");
        assertEquals("", ModelServer.upgrade(quiet));
        assertTrue(Files.readString(dir.resolve("run.sh")).contains(" -c 32768 --parallel 2 "));
        install(dir, then, "chosen=suggested\n");
        Files.writeString(dir.resolve("run.sh"), Files.readString(dir.resolve("run.sh")).replace(" -c 32768 --parallel 2 ", " -c 49152 --parallel 2 "));
        assertEquals("", ModelServer.upgrade(quiet));
        assertTrue(Files.readString(dir.resolve("run.sh")).contains(" -c 49152 --parallel 2 "), "a slot written by hand stays");
        assertFalse(Files.readString(dir.resolve("run.sh")).contains("--parallel 1 "));
    }
}
