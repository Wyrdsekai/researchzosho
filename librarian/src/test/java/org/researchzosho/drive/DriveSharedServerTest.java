package org.researchzosho.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.researchzosho.HangingServer;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A server shared with other programs: the library sends it no more requests at once than it has slots, and leaves the rest of the
 * request as it was — the adapters a server loads (Wyrdsekai's companions') are its host's to set.
 */
class DriveSharedServerTest {
    static final ObjectMapper M = new ObjectMapper();

    /** A llama.cpp server with {@code slots} slots and, when {@code adapter}, one adapter loaded at full strength; each answer takes 200 ms. */
    record Fake(HttpServer server, List<JsonNode> bodies, AtomicInteger most) {
        String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    }

    static Fake fake(Integer slots, boolean adapter) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.setExecutor(Executors.newFixedThreadPool(8));
        List<JsonNode> bodies = new CopyOnWriteArrayList<>();
        AtomicInteger now = new AtomicInteger(), most = new AtomicInteger();
        s.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] out; int code = 200;
            if (path.equals("/props") && slots != null) out = ("{\"default_generation_settings\":{\"n_ctx\":32768},\"total_slots\":" + slots + "}").getBytes(StandardCharsets.UTF_8);
            else if (path.equals("/lora-adapters") && slots != null) out = (adapter ? "[{\"id\":0,\"path\":\"/adapters/floor.gguf\",\"scale\":1.0}]" : "[]").getBytes(StandardCharsets.UTF_8);
            else if (path.equals("/v1/chat/completions")) {
                bodies.add(M.readTree(ex.getRequestBody().readAllBytes()));
                most.accumulateAndGet(now.incrementAndGet(), Math::max);
                try { Thread.sleep(200); } catch (InterruptedException ignored) { }
                now.decrementAndGet();
                out = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ready\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            } else { code = 404; out = "{}".getBytes(StandardCharsets.UTF_8); }
            ex.sendResponseHeaders(code, out.length); ex.getResponseBody().write(out); ex.close();
        });
        s.start();
        return new Fake(s, bodies, most);
    }

    static void askThreeAtOnce(String base, String model) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < 3; i++) done.add(pool.submit(() -> {
                ArrayNode msgs = M.createArrayNode();
                msgs.addObject().put("role", "user").put("content", "hello");
                return new DriveClient(base, model).chat(msgs, null, 8, "auto");
            }));
            for (Future<?> f : done) f.get();
        } finally { pool.shutdownNow(); }
    }

    @Test
    void aOneSlotServerWithAnAdapterGetsOneRequestAtATimeWithItsAdaptersLeftAsSet() throws Exception {
        Fake f = fake(1, true);
        try {
            askThreeAtOnce(f.base(), "one-slot");
            assertEquals(3, f.bodies().size());
            assertEquals(1, f.most().get(), "never more requests at once than the server has slots");
            for (JsonNode b : f.bodies()) assertFalse(b.has("lora"), "the adapters stay as the server's host set them: " + b);
        } finally { f.server().stop(0); }
    }

    @Test
    void aServerWithoutAdaptersGetsNoAdapterFieldAndAsManyAtOnceAsItsSlots() throws Exception {
        Fake f = fake(4, false);
        try {
            askThreeAtOnce(f.base(), "four-slots");
            assertTrue(f.most().get() > 1, "three at once on four slots: " + f.most().get());
            for (JsonNode b : f.bodies()) assertFalse(b.has("lora"), b.toString());
        } finally { f.server().stop(0); }
    }

    @Test
    void aServerThatDoesNotAnswerTheLookInTimeIsAnsweredQuietly() throws Exception {
        // a proxy still starting its model: the look at /props hangs, the chat is answered. The person's console hears nothing of the look.
        Logger logger = (Logger) LoggerFactory.getLogger(DriveClient.class);
        ListAppender<ILoggingEvent> heard = new ListAppender<>();
        heard.start();
        logger.addAppender(heard);
        try (HangingServer s = new HangingServer(HangingServer.Mode.SILENT, r -> r.line().startsWith("POST"),
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ready\"}}]}")) {
            ArrayNode msgs = M.createArrayNode();
            msgs.addObject().put("role", "user").put("content", "hello");
            assertEquals("ready", new DriveClient(s.url(), "quiet-look").chat(msgs, null, 8, "auto").path("content").asText());
            for (ILoggingEvent e : heard.list)
                assertFalse(e.getLevel().isGreaterOrEqual(Level.INFO) && e.getFormattedMessage().contains("gave no answer"), "said on the console: " + e.getFormattedMessage());
        } finally {
            logger.detachAppender(heard);
        }
    }

    @Test
    void aServerThatSaysNothingOfItselfIsSentAsBefore() throws Exception {
        Fake f = fake(null, false);   // Ollama, a hosted API: no /props, no /lora-adapters
        try {
            askThreeAtOnce(f.base(), "silent");
            assertTrue(f.most().get() > 1, "no limit the server did not ask for: " + f.most().get());
            for (JsonNode b : f.bodies()) assertFalse(b.has("lora"), b.toString());
        } finally { f.server().stop(0); }
    }
}
