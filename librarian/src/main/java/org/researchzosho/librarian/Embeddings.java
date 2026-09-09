package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.researchzosho.Config;

/**
 * The dense half of retrieval. An {@link Embedder} turns text into a vector for Lucene's HNSW
 * index; the live one speaks the OpenAI-compatible {@code /v1/embeddings} that llama.cpp's
 * {@code --embedding} server exposes (the household rule: a 0.6B multilingual embedder runs
 * on any card, beside the drive). {@code RESEARCHZOSHO_EMBED} names the endpoint; unset means
 * {@link #none()} and the index is BM25-only — the library never depends on a service to be
 * readable.
 *
 * <p>the operator, 2026-09-02: the library is consumed by The Librarian and other agents, not
 * browsed by people — so it is organized for MACHINE retrieval, and dense retrieval is part of
 * that from the start (Lucene already carries the HNSW machinery; the ocean index used it).
 */
public final class Embeddings {

    /** Text → unit vector, or null when unavailable. Same text must give the same vector. */
    public interface Embedder {
        float[] embed(String text);
        /** Batched: one call for many texts (llama.cpp accepts an array input). Default loops. */
        default java.util.List<float[]> embedAll(java.util.List<String> texts) {
            java.util.List<float[]> out = new java.util.ArrayList<>();
            for (String t : texts) out.add(embed(t));
            return out;
        }
        /** A short id that changes when the model changes — vectors from different models must
         *  never share an index; the store records it to know when a rebuild is due. */
        String modelId();
    }

    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private Embeddings() { }

    /** The configured embedder: RESEARCHZOSHO_EMBED (e.g. http://localhost:8213), else none. */
    public static Embedder configured() {
        String e = Config.get("RESEARCHZOSHO_EMBED");
        // "off" is an explicit switch (an EMPTY env var falls through to the config file — a
        // BM25-only A/B arm run that way silently had the embedder on, 2026-09-02).
        if (e == null || e.isBlank() || e.equalsIgnoreCase("off") || e.equalsIgnoreCase("none")) return none();
        return http(e.replaceAll("/+$", ""), Config.get("RESEARCHZOSHO_EMBED_MODEL", "embed"));
    }

    public static Embedder none() {
        return new Embedder() {
            @Override public float[] embed(String text) { return null; }
            @Override public String modelId() { return "none"; }
        };
    }

    /** Text is capped before embedding: an embedder has a window too, and the head of an entry
     *  (title + opening) is what carries its identity. */
    static final int MAX_CHARS = 6000;
    static final int BATCH = 32;

    public static Embedder http(String endpoint, String model) {
        return new Embedder() {
            @Override public java.util.List<float[]> embedAll(java.util.List<String> texts) {
                // Batched, BATCH at a time: a chunked rebuild embeds thousands of chunks — one
                // request each ran past ten minutes (2026-09-03); batched it is a minute.
                java.util.List<float[]> out = new java.util.ArrayList<>();
                for (int from = 0; from < texts.size(); from += BATCH) {
                    var slice = texts.subList(from, Math.min(texts.size(), from + BATCH));
                    java.util.List<float[]> got = null;
                    try {
                        var body = M.createObjectNode();
                        body.put("model", model);
                        var arr = body.putArray("input");
                        for (String t : slice) arr.add(t == null ? "" : (t.length() > MAX_CHARS ? t.substring(0, MAX_CHARS) : t));
                        HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(endpoint + "/v1/embeddings"))
                                .timeout(Duration.ofSeconds(300))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body), StandardCharsets.UTF_8))
                                .build(), HttpResponse.BodyHandlers.ofString());
                        if (resp.statusCode() == 200) {
                            JsonNode data = M.readTree(resp.body()).path("data");
                            float[][] vs = new float[slice.size()][];
                            for (JsonNode d : data) {
                                int i = d.path("index").asInt(-1);
                                if (i < 0 || i >= vs.length) continue;
                                JsonNode e = d.path("embedding");
                                float[] v = new float[e.size()];
                                double norm = 0;
                                for (int j = 0; j < v.length; j++) { v[j] = (float) e.get(j).asDouble(); norm += v[j] * v[j]; }
                                norm = Math.sqrt(norm);
                                if (norm > 0) for (int j = 0; j < v.length; j++) v[j] /= (float) norm;
                                vs[i] = v;
                            }
                            got = java.util.Arrays.asList(vs);
                        }
                    } catch (Exception e) {
                        got = null;
                    }
                    if (got == null) { for (int i = 0; i < slice.size(); i++) out.add(null); }
                    else out.addAll(got);
                }
                return out;
            }

            @Override public float[] embed(String text) {
                if (text == null || text.isBlank()) return null;
                String t = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
                try {
                    var body = M.createObjectNode();
                    body.put("model", model);
                    body.put("input", t);
                    HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(endpoint + "/v1/embeddings"))
                            .timeout(Duration.ofSeconds(60))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body), StandardCharsets.UTF_8))
                            .build(), HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() != 200) return null;
                    JsonNode arr = M.readTree(resp.body()).path("data").path(0).path("embedding");
                    if (!arr.isArray() || arr.isEmpty()) return null;
                    float[] v = new float[arr.size()];
                    double norm = 0;
                    for (int i = 0; i < v.length; i++) { v[i] = (float) arr.get(i).asDouble(); norm += v[i] * v[i]; }
                    norm = Math.sqrt(norm);
                    if (norm > 0) for (int i = 0; i < v.length; i++) v[i] /= (float) norm;
                    return v;
                } catch (Exception e) {
                    return null;   // an embedder that is down degrades to BM25, never to a crash
                }
            }
            @Override public String modelId() { return model + "@" + endpoint; }
        };
    }
}
