package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Librarian you can talk to. One conversation engine behind two fronts (the terminal verb {@code chat} and the
 * {@code /chat} page): the model, the library's own tools and nothing else, the person in the loop.
 *
 * <p>The model for it is the Librarian in <i>Snow Crash</i>, held as seven rules: (1) what the shelves hold is said as
 * theirs, with sources; an inference is marked as one; a question the library cannot answer gets "I don't know" and
 * the offer to go and look. (2) Every fact carries where it came from. (3) A follow-up is read against the thread.
 * (4) After an answer, one adjacent thing is offered, not a list. (5) "Find out" files a run and the Librarian comes
 * back with the answer and the checks. (6) Short exact sentences, no filler. (7) Nothing is said that no tool result
 * holds: the harness reads a reply's numbers and names against the tool results of the conversation before it is
 * shown, and an unbacked one is admitted as a guess in a line under the reply.
 *
 * <p>Design: {@code docs/DESIGN_LIBRARIAN_CHAT.md}. Sessions live in {@code catalog/chat/<id>.jsonl}; every turn also
 * writes a line to {@code catalog/chat-turns.jsonl} (tools called, unbacked numbers, wall time) so the one number that
 * matters is on the ledger from the first day.
 */
public class Librarian {
    static final ObjectMapper M = new ObjectMapper();
    /** The tools the Librarian may use in a conversation: the library's, and nothing that touches files or the shell. */
    static final List<String> TOOLS = List.of("library_ask", "library_search", "library_get", "library_research", "library_job",
            "library_inbox", "library_frontier", "library_map", "library_changes", "library_submit", "library_sharpen", "library_status");
    /** Tool rounds one turn may take before the Librarian has to speak. */
    static final int MAX_TOOL_ROUNDS = org.researchzosho.Config.getInt("RESEARCHZOSHO_CHAT_TOOL_ROUNDS", 5);
    /** Look-ups one turn may make in all; past it the Librarian answers from what it has (17 opens on one question, 2026-09-13). */
    static final int MAX_CALLS = org.researchzosho.Config.getInt("RESEARCHZOSHO_CHAT_MAX_CALLS", 8);
    /** A tool result longer than this is cut, head and tail, before the model sees it; the person can open the entry. */
    static final int RESULT_CAP = org.researchzosho.Config.getInt("RESEARCHZOSHO_CHAT_RESULT_CHARS", 7000);
    /** Turns of history kept in the model's context; older turns are dropped, the session file keeps them all. */
    static final int HISTORY_TURNS = org.researchzosho.Config.getInt("RESEARCHZOSHO_CHAT_HISTORY_TURNS", 16);
    static final int REPLY_TOKENS = org.researchzosho.Config.getInt("RESEARCHZOSHO_CHAT_REPLY_TOKENS", 1500);
    /** Items of a list a tool result keeps for the model; the rest is counted and the filters named. */
    static final int LIST_CAP = org.researchzosho.Config.getInt("RESEARCHZOSHO_CHAT_LIST_ITEMS", 25);

    private final LibraryStore store;
    private final Researcher.Drive drive;
    private final Patrons.Patron patron;
    private final Session session;
    private final LibraryProtocol protocol;
    /** Every tool result of this session, folded, for the reply check; the session file carries them too. */
    private final StringBuilder evidence = new StringBuilder();

    public Librarian(LibraryStore store, Researcher.Drive drive, Patrons.Patron patron, Session session) {
        this.store = store; this.drive = drive; this.patron = patron; this.session = session;
        this.protocol = new LibraryProtocol(store);
        for (JsonNode m : session.messages) if ("tool".equals(m.path("role").asText())) evidence.append('\n').append(m.path("content").asText(""));
    }

    public Session session() { return session; }

    /** One turn: the person's words in, the Librarian's reply out. Tool rounds happen inside; the session is written as it goes. */
    public String say(String words) throws IOException {
        long t0 = System.currentTimeMillis();
        ObjectNode user = M.createObjectNode(); user.put("role", "user"); user.put("content", words);
        session.append(user);
        ArrayNode tools = tools();
        List<String> toolsCalled = new ArrayList<>();
        StringBuilder turnEvidence = new StringBuilder();
        String reply = null;
        boolean nudged = false;
        for (int round = 0; round <= MAX_TOOL_ROUNDS + 1; round++) {
            ArrayNode messages = fitted(context(), 0.55);
            boolean last = round >= MAX_TOOL_ROUNDS || toolsCalled.size() >= MAX_CALLS;
            ObjectNode assistant;
            try {
                assistant = drive.chat(messages, last ? M.createArrayNode() : tools, REPLY_TOKENS, last ? "none" : "auto");
            } catch (RuntimeException e) {
                // the server's own count outranks the estimate: on an overflow, fit harder once and try again (deepagents' bound on compaction recovery)
                if (e.getMessage() == null || !e.getMessage().contains("context size")) throw e;
                assistant = drive.chat(fitted(context(), 0.3), last ? M.createArrayNode() : tools, REPLY_TOKENS, last ? "none" : "auto");
            }
            JsonNode calls = assistant.path("tool_calls");
            ObjectNode keep = M.createObjectNode(); keep.put("role", "assistant");
            String content = assistant.path("content").asText("");
            if (calls.isArray() && calls.size() > 0 && !last && toolsCalled.size() < MAX_CALLS) {
                keep.put("content", content); keep.set("tool_calls", calls);
                session.append(keep);
                for (JsonNode c : calls) {
                    String name = c.path("function").path("name").asText("");
                    JsonNode args = Researcher.parseArgs(c.path("function").path("arguments"));
                    String result = call(name, args);
                    toolsCalled.add(name);
                    ObjectNode tm = M.createObjectNode(); tm.put("role", "tool"); tm.put("tool_call_id", c.path("id").asText("")); tm.put("name", name); tm.put("content", result);
                    session.append(tm);
                    evidence.append('\n').append(result); turnEvidence.append('\n').append(result);
                }
                continue;
            }
            reply = withoutToolMarkup(content);
            if (reply.isEmpty() && !nudged) {
                // the model thought and said nothing: one more turn, words only; its thinking is never the reply
                nudged = true;
                ObjectNode nudge = M.createObjectNode(); nudge.put("role", "user"); nudge.put("content", "Say it in words now, briefly, from what you have looked up.");
                session.append(nudge);
                continue;
            }
            if (reply.isEmpty()) reply = "I have nothing to say to that; ask me again, another way.";
            break;
        }
        // rule 7, mechanically: a number with a unit, a licence or a CVE id the reply states that no tool result holds is admitted
        List<String> unbacked = WriteupChecks.numbersUnbacked(reply, evidence.toString());
        unbacked.addAll(WriteupChecks.namesUnbacked(reply, evidence.toString()));
        if (!unbacked.isEmpty()) {
            StringBuilder adm = new StringBuilder("\n\n(");
            List<String> items = new ArrayList<>();
            for (String u : unbacked) items.add(u.substring(0, u.indexOf(" — ")).strip());
            adm.append(items.size() == 1 ? "The figure " + items.get(0) + " is not in anything I looked up; take it as my guess." : "The figures " + String.join(", ", items) + " are not in anything I looked up; take them as my guesses.").append(')');
            reply = reply + adm;
        }
        ObjectNode a = M.createObjectNode(); a.put("role", "assistant"); a.put("content", reply);
        session.append(a);
        record(words, toolsCalled, unbacked.size(), System.currentTimeMillis() - t0);
        return reply;
    }

    /**
     * The context kept inside {@code share} of the model's window: older tool results are cleared whole, oldest first, until
     * it fits (a conversation on a 32k window ran to 37k tokens of tool results and the server refused it, 2026-09-13).
     */
    ArrayNode fitted(ArrayNode messages, double share) {
        int ctx = Math.max(8000, drive.contextWindow());
        int limit = (int) (ctx * share);
        int total = 0; for (JsonNode m : messages) total += Researcher.estTokens(m.path("content").asText("")) + Researcher.estTokens(m.path("tool_calls").toString()) + 8;
        for (int i = 1; i < messages.size() && total > limit; i++) {
            ObjectNode m = (ObjectNode) messages.get(i);
            if ("tool".equals(m.path("role").asText()) && m.path("content").asText("").length() > 200) {
                total -= Researcher.estTokens(m.path("content").asText(""));
                m.put("content", "[an earlier look-up, cleared to fit the model's window; look it up again if it matters now]");
                total += 20;
            }
        }
        // still too big: drop whole turns from the front (after the system prompt) until it fits
        while (total > limit && messages.size() > 3) {
            JsonNode gone = messages.remove(1);
            total -= Researcher.estTokens(gone.path("content").asText("")) + 8;
        }
        return messages;
    }

    /** The model's context: the system prompt, then the last {@link #HISTORY_TURNS} turns of the session, tool results cut. */
    ArrayNode context() {
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "system").put("content", systemPrompt());
        int turns = 0; int start = session.messages.size();
        for (int i = session.messages.size() - 1; i >= 0; i--) { if ("user".equals(session.messages.get(i).path("role").asText())) { turns++; start = i; if (turns >= HISTORY_TURNS) break; } }
        for (int i = start; i < session.messages.size(); i++) {
            ObjectNode m = session.messages.get(i).deepCopy();
            if ("tool".equals(m.path("role").asText())) m.put("content", Researcher.cut(m.path("content").asText("")));
            m.remove("name");
            msgs.add(m);
        }
        return msgs;
    }

    String systemPrompt() {
        String name = "";
        try { name = store.identity().name(); } catch (Exception ignored) { }
        return "You are the Librarian of " + (name.isEmpty() ? "this research library" : "the library \"" + name + "\"") + ". Today is "
                + java.time.LocalDate.now() + ". You answer from what the library holds, through the tools, and you say so.\n"
                + "Rules.\n"
                + "1. What the shelves hold, you state as theirs, with its sources. What you infer, you mark as inference (\"the shelves do not say; I would guess…\"). "
                + "When the library cannot answer, say \"I don't know\", and offer to go and find out when a research run could.\n"
                + "2. Every fact carries where it came from: the entry id or the source, so the person can open it.\n"
                + "3. Read a follow-up against the conversation. \"Why\" after an answer means why that answer; \"the other one\" resolves to what was shown. A change of subject is a new thread; say so.\n"
                + "4. After an answer, offer one adjacent thing: a related finding, an open question, a claim that disagrees, a serial that watches this. One, not a list.\n"
                + "5. \"Find out\" means file a research run with library_research: say what it will cost and that it takes a while, then carry on. When asked how it went, use library_job and library_get and give the answer section and the checks, not the whole text. A plain yes from the person is enough to file a run or to accept an inbox item.\n"
                + "6. Short exact sentences. No enthusiasm, no apology, no filler. Courteous.\n"
                + "7. Never state a name, a number, a date or a quotation that is not in a tool result. If you must estimate, say it is an estimate.\n"
                + "Look things up before answering, and read only what the answer needs: library_ask for a question (it returns the relevant entries whole), library_search for a term, library_get to open one entry, library_map around a name, library_inbox for what waits, library_frontier for open questions, library_changes for what is new. A few look-ups a turn, then answer; a list that says N of M shown is a list of M. "
                + "Cite entries by their id in square brackets, like [F-0012-a] or [I-0031-…], right after the sentence they support.";
    }

    /** The library's tools, as the MCP server describes them, restricted to the conversation set. */
    static ArrayNode tools() {
        ArrayNode out = M.createArrayNode();
        for (JsonNode t : org.researchzosho.mcp.McpServer.allTools()) {
            if (!TOOLS.contains(t.path("name").asText())) continue;
            ObjectNode entry = out.addObject(); entry.put("type", "function");
            ObjectNode fn = entry.putObject("function");
            fn.put("name", t.path("name").asText()); fn.put("description", t.path("description").asText(""));
            fn.set("parameters", t.path("inputSchema").deepCopy());
        }
        return out;
    }

    /** One tool call, as the person: the patron rides in, the result comes back as text the model reads (cut when long). */
    String call(String name, JsonNode argsIn) {
        ObjectNode args = argsIn != null && argsIn.isObject() ? ((ObjectNode) argsIn).deepCopy() : M.createObjectNode();
        args.set("patron", patronNode());
        try {
            JsonNode r = switch (name) {
                case "library_ask" -> protocol.ask(args);
                case "library_search" -> protocol.search(args);
                case "library_get" -> protocol.get(args);
                case "library_research" -> protocol.research(args);
                case "library_job" -> protocol.job(args);
                case "library_inbox" -> protocol.inbox(args);
                case "library_frontier" -> protocol.frontier(args);
                case "library_map" -> protocol.map(args);
                case "library_changes" -> protocol.changes(args);
                case "library_submit" -> protocol.submit(args);
                case "library_sharpen" -> protocol.sharpen(args);
                case "library_status" -> protocol.status(args);
                default -> null;
            };
            if (r == null) return "ERROR: no tool named " + name + ". The tools are: " + String.join(", ", TOOLS);
            String text = trimResult(r).toString();
            return text.length() <= RESULT_CAP + 400 ? text : "{\"note\":\"this result was " + text.length() + " characters; only the start is shown — ask for one entry, or one section of it\"} " + text.substring(0, RESULT_CAP);
        } catch (ProtocolError e) {
            return "ERROR: " + e.getMessage();
        } catch (Exception e) {
            return "ERROR: " + name + " failed: " + e.getMessage();
        }
    }

    /**
     * A tool result the model can read whole: every list at the top level keeps its first {@link #LIST_CAP} items and says
     * how many more there are and which filters the tool takes (a head-and-tail cut of the JSON left the model puzzling over a
     * broken list of 116 drafts, 2026-09-13).
     */
    static JsonNode trimResult(JsonNode r) { return trimResult(r, RESULT_CAP); }

    /** As above, sized to {@code cap} characters of compact JSON: the count of every list comes BEFORE the list, so a cut never hides it. */
    static JsonNode trimResult(JsonNode r, int cap) {
        if (r == null || !r.isObject()) return r;
        ObjectNode in = (ObjectNode) r;
        var fields = new ArrayList<String>(); in.fieldNames().forEachRemaining(fields::add);
        ObjectNode o = M.createObjectNode();
        // scalars and the counts first
        for (String f : fields) { JsonNode v = in.get(f); if (!v.isArray()) o.set(f, v); else o.put(f + "_total", v.size()); }
        int budget = Math.max(600, cap - o.toString().length() - 200);
        for (String f : fields) {
            JsonNode v = in.get(f);
            if (!v.isArray()) continue;
            ArrayNode kept = M.createArrayNode();
            int used = 0, n = 0;
            for (JsonNode item : v) {
                int len = item.toString().length() + 2;
                if (n >= LIST_CAP || (n > 0 && used + len > budget / countArrays(in))) break;
                kept.add(item); used += len; n++;
            }
            if (n < v.size()) o.put(f + "_shown", n + " of " + v.size() + " " + f + " shown; narrow with the tool's filters, or ask for a count by group");
            o.set(f, kept);
        }
        return o;
    }

    static int countArrays(ObjectNode in) { int c = 0; var it = in.elements(); while (it.hasNext()) if (it.next().isArray()) c++; return Math.max(1, c); }

    /** Tool-call markup a model writes as prose when its tools are withheld ("<tool_call>…</tool_call>"): never part of a reply. */
    static String withoutToolMarkup(String reply) {
        return reply.replaceAll("(?s)<tool_call>.*?</tool_call>", "").replaceAll("(?s)<function=[^>]*>.*?</function>", "").replaceAll("(?s)<tool_call>.*$", "").strip();
    }

    ObjectNode patronNode() {
        ObjectNode p = M.createObjectNode(); p.put("did", patron.did()); p.put("name", patron.name()); p.put("runtime", patron.runtime()); return p;
    }

    /** One line per turn on the chat ledger: what was asked, which tools ran, how many figures the check could not back, wall time. */
    void record(String words, List<String> toolsCalled, int unbacked, long ms) {
        try {
            Path f = store.root().resolve("catalog").resolve("chat-turns.jsonl");
            Files.createDirectories(f.getParent());
            ObjectNode o = M.createObjectNode();
            o.put("at", Instant.now().toString()); o.put("session", session.id); o.put("patron", patron.did());
            o.put("words", Acquisitions.compress(words, 160)); ArrayNode t = o.putArray("tools"); for (String n : toolsCalled) t.add(n);
            o.put("unbacked", unbacked); o.put("ms", ms);
            Files.writeString(f, o.toString() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    // ---- sessions ----

    /** A conversation: its messages, on disk as one JSON line each under catalog/chat/. */
    public static final class Session {
        public final String id;
        final Path file;
        final List<ObjectNode> messages = new ArrayList<>();
        Session(String id, Path file) { this.id = id; this.file = file; }

        static Path dir(LibraryStore store) { return store.root().resolve("catalog").resolve("chat"); }

        /** A new session, named by the moment it began. */
        public static Session open(LibraryStore store) throws IOException {
            Files.createDirectories(dir(store));
            String id = "C-" + java.time.LocalDateTime.now().withNano(0).toString().replace(':', '-').replace('T', '-');
            Path f = dir(store).resolve(id + ".jsonl");
            int n = 0; while (Files.exists(f)) f = dir(store).resolve(id + "-" + (++n) + ".jsonl");
            return new Session(f.getFileName().toString().replace(".jsonl", ""), f);
        }

        /** An earlier session by id (a prefix will do when it is unique), or null. */
        public static Session resume(LibraryStore store, String id) throws IOException {
            if (!Files.isDirectory(dir(store))) return null;
            Path hit = null; int hits = 0;
            try (var l = Files.list(dir(store))) {
                for (Path p : l.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                    String n = p.getFileName().toString().replace(".jsonl", "");
                    if (n.equals(id)) { hit = p; hits = 1; break; }
                    if (n.startsWith(id)) { hit = p; hits++; }
                }
            }
            if (hit == null || hits != 1) return null;
            Session s = new Session(hit.getFileName().toString().replace(".jsonl", ""), hit);
            for (String line : Files.readAllLines(hit, StandardCharsets.UTF_8)) { if (line.isBlank()) continue; JsonNode j = M.readTree(line); if (j.isObject()) s.messages.add((ObjectNode) j); }
            return s;
        }

        /** The latest session, or a new one. */
        public static Session latest(LibraryStore store) throws IOException {
            List<String> ids = list(store);
            return ids.isEmpty() ? open(store) : resume(store, ids.get(0));
        }

        /** Session ids, newest first. */
        public static List<String> list(LibraryStore store) throws IOException {
            List<String> out = new ArrayList<>();
            if (!Files.isDirectory(dir(store))) return out;
            try (var l = Files.list(dir(store))) { l.filter(p -> p.getFileName().toString().endsWith(".jsonl")).map(p -> p.getFileName().toString().replace(".jsonl", "")).sorted(java.util.Comparator.reverseOrder()).forEach(out::add); }
            return out;
        }

        void append(ObjectNode m) throws IOException {
            messages.add(m);
            Files.writeString(file, m.toString() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        public List<ObjectNode> messages() { return List.copyOf(messages); }

        /** The first thing the person said, for a list. */
        public String title() { for (JsonNode m : messages) if ("user".equals(m.path("role").asText())) return Acquisitions.compress(m.path("content").asText(""), 70); return "(empty)"; }
    }

    /** The person at the keyboard: the same patron the CLI acts as. */
    public static Patrons.Patron person() {
        return new Patrons.Patron("person", System.getProperty("user.name", "person"), "chat");
    }

    static final Set<String> QUIT = Set.of("/quit", "/exit", "/q");
}
