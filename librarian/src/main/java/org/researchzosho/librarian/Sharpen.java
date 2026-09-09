package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * "Sharpen this" — a rough question in, a better one out, before anything runs. The library looks at what it already
 * holds on it, asks who studies the question and what each would insist on asking (the perspectives move), and then
 * one judge call rewrites the question and fills a brief: in scope, out of scope, sub-questions, sources wanted, what
 * the answer must deliver — and says what it ASSUMED, because a model sharpens a question by narrowing it, and a
 * narrowing the person did not notice is worse than a vague question they did. It proposes; the person edits and
 * sends, or sends it as it is. The original question rides with the sharpened one.
 */
public final class Sharpen {

    private static final ObjectMapper M = new ObjectMapper();

    private Sharpen() { }

    public record Held(String id, String title, String state) { }

    public record Sharpened(String original, String question, List<String> assumptions, ResearchBrief brief, List<Held> held,
                            List<String> questionsForYou, String mode, String size, List<Lanes.Lane> lanes) {
        /** The research prompt to send: the brief rendered as the runner reads it. */
        public String researchQuestion() { return brief.toQuestion(); }

        public ObjectNode json() {
            ObjectNode o = M.createObjectNode();
            o.put("original", original); o.put("question", question);
            ArrayNode a = o.putArray("assumptions"); for (String s : assumptions) a.add(s);
            o.put("brief", brief.render());
            ObjectNode b = o.putObject("brief_fields");
            b.put("mode", mode);
            ArrayNode subs = b.putArray("sub_questions"); for (String s : brief.subQuestions()) subs.add(s);
            ArrayNode h = o.putArray("held"); for (Held x : held) h.addObject().put("id", x.id()).put("title", x.title()).put("state", x.state());
            ArrayNode q = o.putArray("questions_for_you"); for (String s : questionsForYou) q.add(s);
            ArrayNode l = o.putArray("languages"); for (Lanes.Lane x : lanes) l.add(x.name());
            o.put("mode", mode); o.put("size", size);
            o.put("research_question", researchQuestion());
            return o;
        }
    }

    public static Sharpened run(LibraryStore store, Researcher.Drive judge, Researcher.Tools tools, String question) throws IOException {
        String original = question.strip();
        if (original.length() < 8) throw ProtocolError.invalidArgs("The question is too short to sharpen.");
        if (judge == null) throw ProtocolError.unavailable("No model drive answers; sharpening a question needs one.");
        // 1. what the shelves hold on it, so the new question starts past that
        List<Held> held = new ArrayList<>();
        if (store != null) {
            ObjectNode a = M.createObjectNode(); a.put("question", original); a.put("k", 5); a.put("peers", "none");
            try {
                for (JsonNode e : new LibraryProtocol(store).ask(a).path("entries")) {
                    if (held.size() >= 5) break;
                    held.add(new Held(e.path("id").asText(), Pages.unentity(e.path("title").asText()), e.path("state").asText()));
                }
            } catch (Exception ignored) { }
        }
        // 2. who studies this, and what would each ask
        List<Perspectives.Perspective> ps = tools == null ? List.of() : Perspectives.discover(original, judge, tools, 5);
        List<String> seeded = Perspectives.questions(ps, 8);
        // 3. one judge call: the question rewritten, its assumptions owned up to, the brief, the questions back
        StringBuilder p = new StringBuilder();
        p.append("A person typed a research question. Make it a better one to research, without changing what they want to know.\n\n");
        p.append("THEIR QUESTION:\n").append(original).append("\n\n");
        if (!ps.isEmpty()) { p.append("WHO STUDIES THIS (and what each would ask):\n"); for (var x : ps) { p.append("- ").append(x.name()).append(": ").append(String.join(" / ", x.questions())).append('\n'); } p.append('\n'); }
        if (!held.isEmpty()) { p.append("WHAT THE LIBRARY ALREADY HOLDS ON IT (build on it, do not re-research):\n"); for (Held h : held) p.append("- ").append(h.id()).append(" ").append(h.title()).append(" (").append(h.state()).append(")\n"); p.append('\n'); }
        p.append("Answer with JSON only:\n{\"question\": \"the question rewritten in one or two plain sentences, specific about who, what, where and when\",\n"
                + " \"assumptions\": [\"each thing you pinned down or left out that the person did not say, e.g. 'recently = the last three years'\"],\n"
                + " \"scope_in\": [\"...\"], \"scope_out\": [\"...\"],\n"
                + " \"sub_questions\": [\"3 to 8 self-contained questions that together answer it\"],\n"
                + " \"sources\": [\"the kinds of sources that would settle it: the archive, the paper, the interview, the register…\"],\n"
                + " \"asks\": [\"what a good answer must contain, as deliverables\"],\n"
                + " \"questions_for_you\": [\"at most 3 questions back to the person, only where the answer changes the plan; [] when none\"],\n"
                + " \"depth\": \"broad or depth\", \"size\": \"quick or full — quick when a few pages settle it, full when it takes real reading\"}\n"
                + "Keep the person's words where they are precise. Use plain language. NAME people, places, works, companies or dates ONLY when the "
                + "person's question or the library's holdings above name them; otherwise describe them (\"the lead actors\", \"the showrunner\", "
                + "\"the network\") — a name from your own memory can be wrong, and the research will find the real ones.");
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content", p.toString());
        String raw = judge.classify(msgs, 1_400);
        ResearchBrief brief = new ResearchBrief(original);
        List<String> assumptions = new ArrayList<>(), back = new ArrayList<>();
        String mode = "broad", size = "full";
        try {
            int x = raw.indexOf('{'), y = raw.lastIndexOf('}');
            JsonNode j = M.readTree(raw.substring(x, y + 1));
            brief.apply(j);
            for (JsonNode s : j.path("assumptions")) if (s.isTextual() && !s.asText().isBlank()) assumptions.add(s.asText().strip());
            for (JsonNode s : j.path("questions_for_you")) if (s.isTextual() && !s.asText().isBlank() && back.size() < 3) back.add(s.asText().strip());
            mode = "depth".equals(j.path("depth").asText("")) ? "depth" : "broad";
            size = "quick".equals(j.path("size").asText("")) ? "quick" : "full";
        } catch (Exception e) {
            throw ProtocolError.unavailable("The model's answer could not be read; try again.");
        }
        if (brief.subQuestions().isEmpty()) { ObjectNode seed = M.createObjectNode(); ArrayNode sq = seed.putArray("sub_questions"); for (String s : seeded) sq.add(s); brief.apply(seed); }
        if (!held.isEmpty()) { ObjectNode hp = M.createObjectNode(); ArrayNode ha = hp.putArray("held"); for (Held h : held) ha.add(h.id() + " " + h.title()); brief.apply(hp); }
        ObjectNode dp = M.createObjectNode(); dp.put("depth", mode); brief.apply(dp);
        List<Lanes.Lane> lanes = Lanes.detect(brief.question(), null);
        return new Sharpened(original, brief.question(), assumptions, brief, held, back, mode, size, lanes);
    }

    /** What a person reads on the command line. */
    public static String text(Sharpened s) {
        StringBuilder sb = new StringBuilder();
        sb.append("you asked:   ").append(s.original()).append('\n');
        sb.append("sharpened:   ").append(s.question()).append('\n');
        if (!s.assumptions().isEmpty()) { sb.append("assumed:\n"); for (String a : s.assumptions()) sb.append("  - ").append(a).append('\n'); }
        sb.append('\n').append(s.brief().render());
        if (!s.lanes().isEmpty()) { sb.append("  languages: "); for (Lanes.Lane l : s.lanes()) sb.append(l.name()).append(' '); sb.append("(searched on their own lanes)\n"); }
        sb.append("  size:      ").append(s.size().equals("quick") ? "quick — a few pages should settle it" : "full — it takes real reading").append('\n');
        if (!s.questionsForYou().isEmpty()) { sb.append("\nit would help to know:\n"); for (String q : s.questionsForYou()) sb.append("  ? ").append(q).append('\n'); }
        return sb.toString();
    }
}
