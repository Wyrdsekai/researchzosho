package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A directory is a library whose findings are listings: "library X at Y holds these subjects and takes
 * access requests". Nothing new on the wire. Listing yourself is a submit to the directory (so you need a
 * writer's token there, which you ask for the same way as anywhere: an access request); finding a
 * library is an ask against the directory; the way into a library you found is its access request.
 * Anyone can run a directory for their circle by running a library with its default set to read.
 *
 * <p>A listing says the library's name, id, address and up to twelve subjects from its vocabulary. It
 * never says what is on the shelves, and it is made only when the owner runs {@code directory publish}.
 */
public final class Directory {

    private static final ObjectMapper M = new ObjectMapper();
    static final int SUBJECTS = 12;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private Directory() { }

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("directory.md"); }

    /** The listing this library would publish: a submit body for the directory. */
    public static ObjectNode listing(LibraryStore store, String myUrl) throws IOException {
        LibraryStore.Identity id = store.identity();
        String url = myUrl.replaceAll("/+$", "");
        List<String> subjects = new ArrayList<>();
        Map<String, String> vocab = Cataloger.vocabulary(store);
        for (Map.Entry<String, String> e : vocab.entrySet()) {
            if (subjects.size() >= SUBJECTS) break;
            String desc = e.getValue() == null ? "" : e.getValue().replaceAll("\\s*\\|.*$", "").strip();   // the description, without any "also:" tail
            subjects.add(desc.isEmpty() ? e.getKey() : e.getKey() + " (" + desc + ")");   // slug AND words: a search for "records of Osaka families" must find it
        }
        StringBuilder claim = new StringBuilder();
        claim.append(id.name()).append(" (").append(id.id()).append(") is a ResearchZosho library at ").append(url).append(". ");
        claim.append(subjects.isEmpty() ? "Its subjects are not catalogued yet. " : "It holds material on: " + String.join(", ", subjects) + ". ");
        claim.append("It takes access requests at ").append(url).append("/v1/request_access.");
        ObjectNode body = M.createObjectNode();
        body.put("title", "Library: " + id.name());
        body.put("claim", claim.toString());
        body.put("claim_type", "extraction");
        body.put("confidence", "high");
        body.putArray("sources").addObject().put("locator", url + "/v1/status").put("edition", LocalDate.now().toString()).put("why", "the library's own status");
        ObjectNode t = body.putObject("triple"); t.put("subject", id.id()); t.put("predicate", "is listed at"); t.put("object", url);
        return body;
    }

    /** Publish this library's listing to a directory; returns the finding id the directory gave it, and remembers it. */
    public static String publish(LibraryStore store, String directoryUrl, String token, String myUrl) throws IOException {
        String dir = directoryUrl.replaceAll("/+$", "");
        String why = Webhooks.refusal(dir);
        if (why != null) throw new IOException("cannot publish to " + dir + ": " + why);
        ObjectNode body = listing(store, myUrl);
        JsonNode j = post(dir + "/v1/submit", token, body);
        String id = j.path("id").asText("");
        if (id.isEmpty()) throw new IOException("the directory did not return a listing id: " + j);
        Files.createDirectories(file(store).getParent());
        Files.writeString(file(store), "- " + dir + " — " + id + " — " + LocalDate.now() + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        store.circulate("directory-publish", dir + " → " + id);
        return id;
    }

    /** Where this library is listed. */
    public static List<String> published(LibraryStore store) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.exists(file(store))) return out;
        for (String line : Files.readAllLines(file(store), StandardCharsets.UTF_8)) if (line.startsWith("- ")) out.add(line.substring(2));
        return out;
    }

    /**
     * Search a directory for libraries. Plain search, not the desk: the desk's strict floor is right when
     * "holds nothing" must be honest, but someone looking for a library types "who has records of Osaka
     * families" and wants candidates, not a verdict.
     */
    public static String find(String directoryUrl, String token, String query) throws IOException {
        String dir = directoryUrl.replaceAll("/+$", "");
        ObjectNode body = M.createObjectNode();
        body.put("query", query); body.put("k", 10);
        JsonNode j = post(dir + "/v1/search", token, body);
        StringBuilder sb = new StringBuilder();
        for (JsonNode h : j.path("hits")) {
            if (!"finding".equals(h.path("kind").asText("finding"))) continue;
            sb.append("- ").append(h.path("title").asText("")).append("  [").append(h.path("state").asText("")).append("]\n");
            String snip = h.path("snippet").asText("");
            if (!snip.isBlank()) sb.append("    ").append(Acquisitions.compress(snip, 300)).append('\n');
        }
        return sb.length() == 0 ? "the directory at " + dir + " lists nothing on: " + query : sb.toString().stripTrailing();
    }

    static JsonNode post(String url, String token, ObjectNode body) throws IOException {
        try {
            var b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            if (token != null && !token.isBlank()) b.header("Authorization", "Bearer " + token);
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode j = M.readTree(r.body());
            if (r.statusCode() / 100 != 2) throw new IOException(j.path("error").path("code").asText("status " + r.statusCode()) + ": " + j.path("error").path("message").asText(""));
            return j;
        } catch (java.net.ConnectException e) { throw new IOException("nothing is answering at " + url);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
    }
}
