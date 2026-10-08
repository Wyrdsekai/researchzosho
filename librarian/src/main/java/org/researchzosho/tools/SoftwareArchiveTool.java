package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.Fence;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code software_archive}: the archives of source code and packages — Software Heritage (every public repository it has visited, by
 * origin address, with its snapshots) and Debian's snapshot archive (every version of every Debian package). For a project whose
 * repository is gone, moved or rewritten, and for what a package shipped at a date.
 */
public final class SoftwareArchiveTool implements Tool {

    static final int MOST = 20, MOST_CALLS = 12;
    static volatile String SWH = "https://archive.softwareheritage.org", DEBIAN = "https://snapshot.debian.org";
    private static final ObjectMapper M = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();

    @Override public String name() { return "software_archive"; }

    @Override public String description() {
        return "The archives of source code: Software Heritage (kind=origins: repositories by name or address, with when they were last archived and "
                + "the archived copy to browse, also for repositories that no longer exist) and Debian's snapshot archive (kind=debian: every version "
                + "a package ever had, query = the package name). web_fetch an archived copy's address to read its files.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode().put("type", "object");
        ObjectNode props = p.putObject("properties");
        ObjectNode kind = props.putObject("kind").put("type", "string").put("description", "origins (default) | debian");
        kind.withArray("enum").add("origins").add("debian");
        props.putObject("query").put("type", "string").put("description", "words of a repository's address or name, or a Debian package name");
        props.putObject("limit").put("type", "integer").put("description", "how many rows, up to " + MOST + " (default 10)");
        p.putArray("required").add("query");
        return p;
    }

    @Override public String execute(JsonNode args) {
        String query = args.path("query").asText("").strip(), kind = args.path("kind").asText("origins").strip().toLowerCase();
        if (query.isEmpty()) return "ERROR: query is empty";
        if (calls.incrementAndGet() > MOST_CALLS) return "ERROR: this run has made its " + MOST_CALLS + " software-archive lookups; write with what was found.";
        int limit = Math.max(1, Math.min(MOST, args.path("limit").asInt(10)));
        StringBuilder sb = new StringBuilder();
        if (kind.equals("debian")) {
            String pkg = query.toLowerCase().replaceAll("[^a-z0-9.+-]", "");
            String body = Archives.text(DEBIAN + "/mr/package/" + Archives.enc(pkg) + "/", Duration.ofSeconds(30), "Debian's snapshot archive");
            if (body == null) return "no Debian package named " + pkg + " in the snapshot archive (or it did not answer).";
            sb.append("versions of the Debian package ").append(pkg).append(", newest first (").append(DEBIAN).append("/package/").append(pkg).append("/):\n");
            int n = 0;
            try { for (JsonNode v : M.readTree(body).path("result")) { if (n++ >= limit) break; sb.append("  ").append(v.path("version").asText("")).append('\n'); } }
            catch (Exception e) { return "ERROR: the answer could not be read (" + e.getMessage() + ")"; }
            if (n > limit) sb.append("  … and more\n");
        } else {
            String body = Archives.text(SWH + "/api/1/origin/search/" + Archives.enc(query) + "/?limit=" + limit, Duration.ofSeconds(30), "Software Heritage");
            if (body == null) return "ERROR: Software Heritage did not answer. This is not a search that found nothing: try once more.";
            sb.append("repositories in Software Heritage matching \"").append(query).append("\":\n");
            int n = 0;
            try {
                for (JsonNode o : M.readTree(body)) {
                    String url = o.path("url").asText("");
                    sb.append(++n).append(". ").append(url).append("  [").append(String.join(",", toList(o.path("visit_types")))).append("]")
                      .append(o.path("has_visits").asBoolean(false) ? "  last archived " + o.path("last_visit_date").asText("").replaceAll("T.*", "") : "  not yet archived").append('\n')
                      .append("   archived copy: ").append(SWH).append("/browse/origin/directory/?origin_url=").append(Archives.enc(url)).append('\n');
                }
            } catch (Exception e) { return "ERROR: the answer could not be read (" + e.getMessage() + ")"; }
            if (n == 0) sb.append("nothing found.\n");
        }
        return Fence.wrap("SOFTWARE ARCHIVE", sb.toString().strip()) + "\n" + Fence.rule("SOFTWARE ARCHIVE");
    }

    private static List<String> toList(JsonNode n) { List<String> out = new ArrayList<>(); for (JsonNode x : n) out.add(x.asText("")); return out; }
}
