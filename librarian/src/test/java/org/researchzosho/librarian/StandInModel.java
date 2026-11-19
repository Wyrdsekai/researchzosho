package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A stand-in for a model server, so no test calls a paid model (0.8.0, stage 3): every completion is the same canned text, in the
 * OpenAI shape ({@code /v1/chat/completions}) and the Anthropic shape ({@code /v1/messages}), streamed when asked, with the usage in
 * it or without, as a test chooses; and slow when a test wants a long run, with at most so many calls at once.
 */
final class StandInModel implements AutoCloseable {
    static final String CANNED = "The harbour was built in stages. The first quay dates from the spring of the founding year, and the lighthouse was lit that autumn.";

    final HttpServer server;
    volatile boolean usage = true;
    volatile long delayMs = 0;
    final AtomicLong calls = new AtomicLong();
    final AtomicInteger now = new AtomicInteger(), most = new AtomicInteger();
    /** The credentials the last call came with, as the server saw them. */
    volatile String lastAuthorization, lastApiKey;
    /** A call whose words hold this is noted on arrival: what the server was doing then. */
    static final String MARKED = "(timed turn ";
    final List<String> atArrival = new CopyOnWriteArrayList<>();

    /** How many calls it answers at once, as llama.cpp says on its props page; the others wait their turn, as there. 0: no limit. */
    volatile int slots = 0;
    private final Semaphore turns = new Semaphore(1_000, true);

    StandInModel() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::answer);
        server.start();
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    /** How many of the calls were for embeddings. */
    final AtomicLong embeddings = new AtomicLong();

    /** An embeddings server's answer, OpenAI's shape: a vector of eight numbers for each text, made from its words, and the usage. */
    private void embed(HttpExchange x, String body) throws IOException {
        calls.incrementAndGet(); embeddings.incrementAndGet();
        lastAuthorization = x.getRequestHeaders().getFirst("Authorization");
        JsonNode in = new ObjectMapper().readTree(body).path("input");
        List<String> texts = new ArrayList<>();
        if (in.isArray()) for (JsonNode t : in) texts.add(t.asText()); else texts.add(in.asText());
        StringBuilder data = new StringBuilder();
        long tokens = 0;
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i).toLowerCase(Locale.ROOT);
            double[] v = new double[8];
            for (String w : t.split("\\W+")) if (!w.isEmpty()) v[Math.floorMod(w.hashCode(), 8)] += 1;
            double n = 0; for (double d : v) n += d * d; n = Math.sqrt(n) == 0 ? 1 : Math.sqrt(n);
            StringBuilder vec = new StringBuilder();
            for (int k = 0; k < 8; k++) vec.append(k == 0 ? "" : ",").append(v[k] / n == 0 ? 0.001 : v[k] / n);
            data.append(i == 0 ? "" : ",").append("{\"object\":\"embedding\",\"index\":").append(i).append(",\"embedding\":[").append(vec).append("]}");
            tokens += Math.max(1, t.length() / 4);
        }
        send(x, 200, "application/json", "{\"object\":\"list\",\"model\":\"stand-in-embed\",\"data\":[" + data + "]" + (usage ? ",\"usage\":{\"prompt_tokens\":" + tokens + ",\"total_tokens\":" + tokens + "}" : "") + "}");
    }

    static String json(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""; }

    private void answer(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        byte[] in = x.getRequestBody().readAllBytes();
        String body = new String(in, StandardCharsets.UTF_8);
        if (path.endsWith("/models")) { send(x, 200, "application/json", "{\"object\":\"list\",\"data\":[{\"id\":\"stand-in\",\"object\":\"model\"}]}"); return; }
        if (path.endsWith("/props") && slots > 0) { send(x, 200, "application/json", "{\"total_slots\":" + slots + "}"); return; }
        if (path.endsWith("/embeddings")) { embed(x, body); return; }
        if (!path.endsWith("/chat/completions") && !path.endsWith("/messages")) { send(x, 200, "application/json", "{\"status\":\"ok\"}"); return; }
        calls.incrementAndGet();
        lastAuthorization = x.getRequestHeaders().getFirst("Authorization"); lastApiKey = x.getRequestHeaders().getFirst("x-api-key");
        if (body.contains(MARKED)) atArrival.add(now.get() + " answering, " + turns.getQueueLength() + " waiting");   // what a marked call found when it came
        boolean limited = slots > 0;
        if (limited) { try { turns.acquire(1_000 / slots); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; } }
        int going = now.incrementAndGet();
        most.accumulateAndGet(going, Math::max);
        try {
            if (delayMs > 0) Thread.sleep(delayMs);
            boolean anthropic = path.endsWith("/messages"), stream = body.contains("\"stream\":true") || body.contains("\"stream\" : true");
            long prompt = Math.max(1, in.length / 4), completion = 30;
            if (anthropic) {
                if (!stream) {
                    send(x, 200, "application/json", "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"stand-in\",\"content\":[{\"type\":\"text\",\"text\":" + json(CANNED) + "}],\"stop_reason\":\"end_turn\""
                            + (usage ? ",\"usage\":{\"input_tokens\":" + prompt + ",\"cache_read_input_tokens\":0,\"output_tokens\":" + completion + "}" : "") + "}");
                } else {
                    sse(x, "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"role\":\"assistant\"" + (usage ? ",\"usage\":{\"input_tokens\":" + prompt + ",\"output_tokens\":1}" : "") + "}}\n\n"
                            + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":" + json(CANNED) + "}}\n\n"
                            + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}" + (usage ? ",\"usage\":{\"output_tokens\":" + completion + "}" : "") + "}\n\n"
                            + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n");
                }
                return;
            }
            String usageJson = ",\"usage\":{\"prompt_tokens\":" + prompt + ",\"completion_tokens\":" + completion + ",\"total_tokens\":" + (prompt + completion) + "}";
            if (!stream) {
                send(x, 200, "application/json", "{\"id\":\"stand-in\",\"object\":\"chat.completion\",\"model\":\"stand-in\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":" + json(CANNED)
                        + "},\"finish_reason\":\"stop\"}]" + (usage ? usageJson : "") + "}");
            } else {
                sse(x, "data: {\"id\":\"s\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":" + json(CANNED) + "},\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"s\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + (usage ? "data: {\"id\":\"s\",\"object\":\"chat.completion.chunk\",\"choices\":[]" + usageJson + "}\n\n" : "")
                        + "data: [DONE]\n\n");
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { now.decrementAndGet(); if (limited) turns.release(1_000 / slots); }
    }

    private static void send(HttpExchange x, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", type);
        x.sendResponseHeaders(code, b.length);
        try (OutputStream o = x.getResponseBody()) { o.write(b); }
    }

    private static void sse(HttpExchange x, String events) throws IOException {
        x.getResponseHeaders().set("Content-Type", "text/event-stream");
        x.sendResponseHeaders(200, 0);
        try (OutputStream o = x.getResponseBody()) { o.write(events.getBytes(StandardCharsets.UTF_8)); }
    }

    @Override public void close() { server.stop(0); }
}
