package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.researchzosho.tools.Tool;

import java.util.ArrayList;
import java.util.List;

/**
 * Perspective discovery, STORM's pre-writing move: before decomposing a question, look at what is
 * already written about it and ask WHO cares about it and WHY — the historian of technology, the
 * conservator, the engineer replicating it, the sceptic — then let each perspective ask its own
 * questions. A decomposition made this way covers the disagreements a single viewpoint never sees.
 * One or two searches, one judge call; the runner uses it when a brief brings no sub-questions, and
 * the refine phase can call it through {@code library_perspectives}.
 */
public final class Perspectives {

    private static final ObjectMapper M = new ObjectMapper();

    public record Perspective(String name, String why, List<String> questions) { }

    private Perspectives() { }

    /** Up to {@code max} perspectives with their questions; empty when the judge could not answer. */
    public static List<Perspective> discover(String question, Researcher.Drive judge, Researcher.Tools tools, int max) {
        StringBuilder seen = new StringBuilder();
        try {
            Tool search = null;
            for (Tool t : tools.web(question)) if ("web_search".equals(t.name())) search = t;
            if (search != null) {
                for (String q : new String[]{question, question + " overview debate"}) {
                    String r = search.execute(M.createObjectNode().put("query", q));
                    if (r != null && !r.startsWith("ERROR")) seen.append(Acquisitions.compress(r, 2500)).append("\n");
                }
            }
        } catch (Exception ignored) {
            // no search: the judge works from the question alone
        }
        List<Perspective> out = new ArrayList<>();
        try {
            ArrayNode msgs = M.createArrayNode();
            msgs.addObject().put("role", "user").put("content",
                    "A research question is about to be investigated. Name the PERSPECTIVES from which it is studied — the "
                    + "kinds of people who care about it and why (a field, a profession, a school of thought, a sceptic, a "
                    + "practitioner). For each, write one or two self-contained sub-questions that perspective would insist "
                    + "on. Perspectives must differ in what they would look for, not in wording.\n\nQUESTION:\n" + question
                    + (seen.isEmpty() ? "" : "\n\nWHAT A FIRST SEARCH SHOWS:\n" + Fence.wrap("SEARCH RESULTS", seen.toString()) + "\n" + Fence.rule("SEARCH RESULTS"))
                    + "\n\nAnswer with JSON only: [{\"perspective\": \"…\", \"why\": \"…\", \"questions\": [\"…\"]}] — at most " + max + ".");
            String raw = judge.classify(msgs, 1500);
            int a = raw.indexOf('['), b = raw.lastIndexOf(']');
            if (a < 0 || b <= a) return out;
            for (JsonNode p : M.readTree(raw.substring(a, b + 1))) {
                List<String> qs = new ArrayList<>();
                for (JsonNode q : p.path("questions")) if (q.isTextual() && !q.asText().isBlank()) qs.add(q.asText().strip());
                String name = p.path("perspective").asText("").strip();
                if (!name.isEmpty() && !qs.isEmpty() && out.size() < max) out.add(new Perspective(name, p.path("why").asText("").strip(), qs));
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    /** The sub-questions the perspectives ask, tagged with their perspective, at most {@code max}. */
    public static List<String> questions(List<Perspective> ps, int max) {
        List<String> out = new ArrayList<>();
        // round-robin so every perspective gets its first question before any gets a second
        for (int i = 0; out.size() < max; i++) {
            boolean any = false;
            for (Perspective p : ps) {
                if (i < p.questions().size() && out.size() < max) { out.add(p.questions().get(i) + " [" + p.name() + "]"); any = true; }
            }
            if (!any) break;
        }
        return out;
    }
}
