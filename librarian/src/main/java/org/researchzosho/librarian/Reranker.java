package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.researchzosho.Config;

/**
 * The cross-encoder reranker — the single largest precision lever the organization survey
 * found (I-0005: Recall@5 0.695 → 0.816 on top of hybrid RRF). Speaks llama.cpp's
 * {@code /v1/rerank} (bge-reranker-v2-m3, ~1GB, multilingual incl. CJK — household-sized).
 * {@code RESEARCHZOSHO_RERANK} names the endpoint; unset means {@link #none()} and the fused
 * ranking stands. A reranker that is down returns null and the fused order stands — never a
 * failed search.
 */
public final class Reranker {

    public interface Scorer {
        /** One relevance score per document, same order, or null when unavailable. */
        List<Double> score(String query, List<String> documents);
        String modelId();
    }

    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private Reranker() { }

    public static Scorer configured() {
        String e = Config.get("RESEARCHZOSHO_RERANK");
        if (e == null || e.isBlank() || e.equalsIgnoreCase("off")) return none();
        return http(e.replaceAll("/+$", ""), Config.get("RESEARCHZOSHO_RERANK_MODEL", "rerank"));
    }

    public static Scorer none() {
        return new Scorer() {
            @Override public List<Double> score(String q, List<String> d) { return null; }
            @Override public String modelId() { return "none"; }
        };
    }

    /** Documents are capped: the reranker has a window too, and the head decides relevance. */
    static final int MAX_DOC_CHARS = 2500;

    public static Scorer http(String endpoint, String model) {
        return new Scorer() {
            @Override public List<Double> score(String query, List<String> documents) {
                if (documents == null || documents.isEmpty()) return List.of();
                try {
                    var body = M.createObjectNode();
                    body.put("model", model);
                    body.put("query", query);
                    body.put("top_n", documents.size());
                    var arr = body.putArray("documents");
                    for (String d : documents) arr.add(d.length() > MAX_DOC_CHARS ? d.substring(0, MAX_DOC_CHARS) : d);
                    HttpResponse<String> resp = HTTP.send(HttpRequest.newBuilder(URI.create(endpoint + "/v1/rerank"))
                            .timeout(Duration.ofSeconds(120))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body), StandardCharsets.UTF_8))
                            .build(), HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() != 200) return null;
                    JsonNode results = M.readTree(resp.body()).path("results");
                    if (!results.isArray()) return null;
                    Double[] out = new Double[documents.size()];
                    for (JsonNode r : results) {
                        int i = r.path("index").asInt(-1);
                        if (i >= 0 && i < out.length) out[i] = r.path("relevance_score").asDouble(Double.NEGATIVE_INFINITY);
                    }
                    for (int i = 0; i < out.length; i++) if (out[i] == null) out[i] = Double.NEGATIVE_INFINITY;
                    return List.of(out);
                } catch (Exception e) {
                    return null;
                }
            }
            @Override public String modelId() { return model + "@" + endpoint; }
        };
    }
}
