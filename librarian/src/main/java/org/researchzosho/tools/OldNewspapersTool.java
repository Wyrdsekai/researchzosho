package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.Fence;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** {@code old_newspapers}: the digitised newspaper archives, for the local history, the obituaries and the notices a research run is after. */
public final class OldNewspapersTool implements Tool {

    static final int MOST = 20, MOST_CALLS = 12;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile Fetch.Policy policy = Fetch.Policy.DEFAULT;

    public OldNewspapersTool policy(Fetch.Policy p) { this.policy = p == null ? Fetch.Policy.DEFAULT : p; return this; }

    @Override public String name() { return "old_newspapers"; }

    @Override public String description() {
        return "Search digitised historical newspapers by their text: the Library of Congress (United States, 1770–1963), Gallica (France), Delpher "
                + "(the Netherlands), Papers Past (New Zealand) and Trove (Australia, with a key). Use it for a person, a place or an event before "
                + "the web: an obituary, a notice, a local story. where = us | fr | nl | nz | au | all; from and to narrow the years. Each row says "
                + "the paper, the date and where to read the page; web_fetch the address for the text.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode().put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("query").put("type", "string").put("description", "the words, in the language of the newspapers (a name, a place, a phrase)");
        ObjectNode where = props.putObject("where").put("type", "string").put("description", "us | fr | nl | nz | au | all (default all)");
        for (String w : List.of("all", "us", "fr", "nl", "nz", "au")) where.withArray("enum").add(w);
        props.putObject("from").put("type", "string").put("description", "the first year, 1904; optional");
        props.putObject("to").put("type", "string").put("description", "the last year; optional");
        props.putObject("limit").put("type", "integer").put("description", "rows per archive, up to " + MOST + " (default 8)");
        p.putArray("required").add("query");
        return p;
    }

    @Override public String execute(JsonNode args) {
        String query = args.path("query").asText("").strip();
        if (query.isEmpty()) return "ERROR: query is empty";
        if (calls.incrementAndGet() > MOST_CALLS) return "ERROR: this run has made its " + MOST_CALLS + " newspaper searches; write with what was found.";
        String where = args.path("where").asText("all").strip().toLowerCase();
        int limit = Math.max(1, Math.min(MOST, args.path("limit").asInt(8)));
        OldNewspapers.Answer a = OldNewspapers.search(query, where, args.path("from").asText("").strip(), args.path("to").asText("").strip(), limit);
        if (a.answered().isEmpty()) return "ERROR: no newspaper archive answered (" + String.join("; ", a.silent()) + "). This is not a search that found nothing: try once more later."
                + (a.noKey().isEmpty() ? "" : " Not searched, no key: " + String.join("; ", a.noKey()) + ".");
        StringBuilder sb = new StringBuilder("historical newspapers for \"" + query + "\":\n");
        int n = 0, refused = 0, listed = 0;
        for (OldNewspapers.Row r : a.rows()) {
            if (policy.refused(r.url())) { refused++; continue; }
            if (policy.onSiteList(r.url())) { listed++; continue; }
            sb.append(++n).append(". [").append(r.where()).append("] ").append(r.newspaper().isEmpty() ? "" : r.newspaper() + ", ").append(r.date()).append(r.title().isEmpty() || r.title().equals(r.newspaper()) ? "" : " — " + ArchiveSearchTool.cut(r.title(), 120)).append('\n')
              .append("   ").append(r.url()).append('\n');
            if (!r.snippet().isEmpty()) sb.append("   ").append(ArchiveSearchTool.cut(r.snippet(), 220)).append('\n');
        }
        if (n == 0) sb.append("nothing found in: ").append(String.join(", ", a.answered())).append('\n');
        sb.append(WebSearchTool.leftOutLines(refused, listed));
        if (!a.silent().isEmpty()) sb.append("did not answer: ").append(String.join(", ", a.silent())).append('\n');
        if (!a.noKey().isEmpty()) sb.append("not searched, no key: ").append(String.join("; ", a.noKey())).append('\n');
        return Fence.wrap("NEWSPAPER RESULTS", sb.toString().strip()) + "\n" + Fence.rule("NEWSPAPER RESULTS");
    }
}
