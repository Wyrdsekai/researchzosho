package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A conversation the person had with another assistant, absorbed as a starting point for research. The
 * transcript goes on the shelves as it is; the person's questions join the open questions; what the
 * assistant asserted comes back as a list of claims to check — never as findings, because an assistant's
 * say-so is not a source. Verifying them is one research run, filed only when asked.
 *
 * <p>Reads the exports people actually have: ChatGPT's {@code conversations.json} (a tree of nodes under
 * {@code mapping}), Claude's export ({@code chat_messages} with {@code sender}), any {@code [{role, content}]}
 * list, and a text or markdown transcript with role markers ("User:", "**Assistant**", "## Claude"). Text
 * without markers is one voice: its questions are the person's, its statements are the author's claims.
 */
public final class Conversations {

    private static final ObjectMapper M = new ObjectMapper();

    /** Questions filed per thread, claims listed per thread, threads taken per export unless the call says more. */
    static final int MAX_QUESTIONS = org.researchzosho.Config.getInt("RESEARCHZOSHO_ABSORB_QUESTIONS", 20);
    static final int MAX_CLAIMS = org.researchzosho.Config.getInt("RESEARCHZOSHO_ABSORB_CLAIMS", 30);
    static final int CLAIMS_PER_TURN = 8;
    public static final int DEFAULT_THREADS = 25;

    public record Turn(String role, String text) { }
    public record Thread(String title, List<Turn> turns) {
        public int personTurns() { return (int) turns.stream().filter(t -> t.role().equals("person")).count(); }
    }
    public record Outcome(String title, String raw, int turns, List<String> questionsFiled, List<String> questionsHeld, List<String> claims) { }

    private Conversations() { }

    // ---- parsing ----

    /** Every thread in a file: JSON exports may hold many, a transcript holds one. */
    public static List<Thread> parse(byte[] bytes, String nameHint) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String head = text.stripLeading();
        if (head.startsWith("{") || head.startsWith("[")) {
            try {
                List<Thread> out = fromJson(M.readTree(bytes));
                if (!out.isEmpty()) return out;
            } catch (Exception ignored) { }
        }
        return List.of(fromText(text, titleFrom(nameHint)));
    }

    static List<Thread> fromJson(JsonNode root) {
        List<Thread> out = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode n : root) {
                Thread t = oneJson(n);
                if (t != null) out.add(t);
            }
            if (out.isEmpty() && root.size() > 0 && root.get(0).has("role")) {   // a bare messages list
                Thread t = fromMessages(root, "conversation");
                if (t != null) out.add(t);
            }
            return out;
        }
        if (root.isObject()) {
            if (root.path("conversations").isArray()) return fromJson(root.get("conversations"));
            Thread t = oneJson(root);
            if (t != null) out.add(t);
        }
        return out;
    }

    /** One conversation object, in whichever export shape it has. */
    static Thread oneJson(JsonNode n) {
        if (!n.isObject()) return null;
        String title = n.path("title").asText(n.path("name").asText("")).strip();
        if (n.path("mapping").isObject()) return fromChatGpt(n, title);                       // ChatGPT export
        if (n.path("chat_messages").isArray()) {                                              // Claude export
            List<Turn> turns = new ArrayList<>();
            for (JsonNode m : n.get("chat_messages")) {
                String role = m.path("sender").asText("");
                String text = m.path("text").asText("");
                if (text.isBlank() && m.path("content").isArray()) { StringBuilder sb = new StringBuilder(); for (JsonNode c : m.get("content")) if (c.path("text").isTextual()) sb.append(c.get("text").asText()).append('\n'); text = sb.toString(); }
                if (!text.isBlank()) turns.add(new Turn(role.equals("human") ? "person" : "assistant", text.strip()));
            }
            return turns.isEmpty() ? null : new Thread(title.isEmpty() ? "conversation" : title, turns);
        }
        if (n.path("messages").isArray()) return fromMessages(n.get("messages"), title.isEmpty() ? "conversation" : title);
        return null;
    }

    static Thread fromMessages(JsonNode messages, String title) {
        List<Turn> turns = new ArrayList<>();
        for (JsonNode m : messages) {
            String role = m.path("role").asText(m.path("sender").asText("")).toLowerCase(Locale.ROOT);
            JsonNode c = m.path("content");
            String text = c.isTextual() ? c.asText() : c.isArray() ? partsText(c) : m.path("text").asText("");
            if (role.equals("system") || role.equals("tool") || text.isBlank()) continue;
            turns.add(new Turn(role.equals("user") || role.equals("human") ? "person" : "assistant", text.strip()));
        }
        return turns.isEmpty() ? null : new Thread(title, turns);
    }

    /** ChatGPT keeps a tree; the conversation is the path from the root through each node's first child (branches are regenerations). */
    static Thread fromChatGpt(JsonNode conv, String title) {
        JsonNode mapping = conv.get("mapping");
        String cur = null;
        var it = mapping.fields();
        while (it.hasNext()) { var e = it.next(); if (e.getValue().path("parent").isNull() || !e.getValue().has("parent")) { cur = e.getKey(); break; } }
        if (cur == null && it.hasNext()) cur = mapping.fieldNames().next();
        List<Turn> turns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        while (cur != null && seen.add(cur)) {
            JsonNode node = mapping.path(cur);
            JsonNode msg = node.path("message");
            String role = msg.path("author").path("role").asText("");
            String text = partsText(msg.path("content").path("parts"));
            if (text.isBlank() && msg.path("content").path("text").isTextual()) text = msg.path("content").get("text").asText();
            if ((role.equals("user") || role.equals("assistant")) && !text.isBlank()) turns.add(new Turn(role.equals("user") ? "person" : "assistant", text.strip()));
            JsonNode children = node.path("children");
            cur = children.isArray() && children.size() > 0 ? children.get(children.size() - 1).asText() : null;   // the last child = the branch the person kept
        }
        return turns.isEmpty() ? null : new Thread(title.isEmpty() ? "conversation" : title, turns);
    }

    static String partsText(JsonNode parts) {
        if (!parts.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode p : parts) {
            if (p.isTextual()) sb.append(p.asText()).append('\n');
            else if (p.path("text").isTextual()) sb.append(p.get("text").asText()).append('\n');
        }
        return sb.toString();
    }

    static final Pattern MARKER = Pattern.compile(
            "^\\s*(?:#{1,4}\\s*)?(?:\\*\\*|__)?\\s*(User|You|Human|Me|Person|Q|Question|Prompt|Assistant|ChatGPT|Claude|AI|Bot|A|Answer|GPT|Gemini|Copilot|Model|Response)\\s*(?:\\*\\*|__)?\\s*(?::|：|$)\\s*(?:\\*\\*|__)?\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    static final Set<String> PERSON_MARKERS = Set.of("user", "you", "human", "me", "person", "q", "question", "prompt");

    /** A transcript with role markers at line starts; without them, one voice. */
    static Thread fromText(String text, String title) {
        List<Turn> turns = new ArrayList<>();
        Matcher m = MARKER.matcher(text);
        int last = -1; String role = null;
        while (m.find()) {
            if (role != null) add(turns, role, text.substring(last, m.start()));
            role = PERSON_MARKERS.contains(m.group(1).toLowerCase(Locale.ROOT)) ? "person" : "assistant";
            last = m.end();
        }
        if (role != null) add(turns, role, text.substring(last));
        if (turns.size() < 2) turns = List.of(new Turn("author", text.strip()));
        return new Thread(title, turns);
    }

    private static void add(List<Turn> turns, String role, String text) {
        String t = text.strip();
        if (!t.isEmpty()) turns.add(new Turn(role, t));
    }

    static String titleFrom(String nameHint) {
        if (nameHint == null || nameHint.isBlank()) return "conversation";
        String n = nameHint;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.replaceAll("\\.[A-Za-z0-9]+$", "").replace('_', ' ').replace('-', ' ').strip();
        return n.isEmpty() ? "conversation" : n;
    }

    // ---- what a thread yields ----

    static final Pattern ASKS = Pattern.compile("(?i)^(what|who|when|where|why|how|which|is|are|was|were|do|does|did|can|could|should|would|will|has|have|tell me|explain|describe|compare|find|list|give me|summari[sz]e|is there|are there)\\b");

    /** The person's questions, one per turn at most: the first sentence that asks. */
    public static List<String> questions(Thread t) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Turn turn : t.turns()) {
            if (!turn.role().equals("person") && !turn.role().equals("author")) continue;
            for (String s : CiteCheck.sentences(turn.text())) {
                String q = s.strip().replaceAll("\\s+", " ");
                if (q.length() < 12 || q.length() > 300) continue;
                boolean asks = q.endsWith("?") || (turn.role().equals("person") && ASKS.matcher(q).find());
                if (!asks) continue;
                if (seen.add(q.toLowerCase(Locale.ROOT))) out.add(q);
                break;   // one ask per turn
            }
            if (out.size() >= MAX_QUESTIONS) break;
        }
        return out;
    }

    /** What the other voice asserted: through the model when there is one, mechanically otherwise. */
    public static List<String> claims(Thread t, Function<String, List<String>> extractor) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Turn turn : t.turns()) {
            if (!turn.role().equals("assistant") && !turn.role().equals("author")) continue;
            List<String> found = extractor != null ? extractor.apply(turn.text()) : mechanical(turn.text());
            int n = 0;
            for (String c : found) {
                String s = c.strip().replaceAll("\\s+", " ");
                if (s.length() < 20 || s.length() > 400) continue;
                if (seen.add(s.toLowerCase(Locale.ROOT))) { out.add(s); n++; }
                if (n >= CLAIMS_PER_TURN || out.size() >= MAX_CLAIMS) break;
            }
            if (out.size() >= MAX_CLAIMS) break;
        }
        return out;
    }

    static final Pattern CHECKABLE = Pattern.compile("\\d|\\b[A-Z][a-z]{2,}\\b.*\\b(is|are|was|were|has|have|had|became|founded|born|died|published|introduced|invented|discovered|measured|costs?|weighs?|contains?|holds?|runs?|means)\\b");
    static final Pattern HEDGE = Pattern.compile("(?i)^(i |i'm|i’m|as an ai|let me|here'?s|here is|sure|certainly|of course|you |your |if you|you're|you’re|would you|do you|let'?s|note that|remember|feel free|hope this|great question)");

    /** Sentences that name something and say something definite about it; the assistant's chatter is left out. */
    static List<String> mechanical(String text) {
        List<String> out = new ArrayList<>();
        for (String s : CiteCheck.sentences(text)) {
            String c = s.strip().replaceAll("\\s+", " ").replaceAll("^[-*•\\d.)\\s]+", "");
            if (c.endsWith("?") || c.endsWith(":") || HEDGE.matcher(c).find()) continue;
            if (c.length() < 30 || !CHECKABLE.matcher(c).find()) continue;
            out.add(c);
        }
        return out;
    }

    /** The model's reading of one turn: checkable claims, one per line. */
    public static Function<String, List<String>> modelExtractor(Researcher.Drive drive) {
        if (drive == null) return null;
        return text -> {
            var messages = M.createArrayNode();
            messages.addObject().put("role", "system").put("content", "You list the checkable factual claims in a text. A claim names something and states something definite about it: a number, a date, a name, a cause, a property. Leave out opinions, advice, questions, hedges and anything about the conversation itself. Write each claim as one short self-contained sentence, one per line, no bullets, at most " + CLAIMS_PER_TURN + " lines. Write NONE when there are no such claims.");
            messages.addObject().put("role", "user").put("content", Fence.open("TEXT") + "\n" + Acquisitions.compress(text, 6000) + "\n" + Fence.close("TEXT"));
            String reply = drive.classify(messages, 600);
            List<String> out = new ArrayList<>();
            if (reply == null) return out;
            for (String line : reply.split("\n")) {
                String c = line.strip().replaceAll("^[-*•\\d.)\\s]+", "");
                if (c.isEmpty() || c.equalsIgnoreCase("NONE")) continue;
                out.add(c);
            }
            return out;
        };
    }

    // ---- absorbing ----

    /** The transcript as text for the shelf: the roles as headings, the turns in order. */
    static String render(Thread t) {
        StringBuilder sb = new StringBuilder("# ").append(t.title()).append("\n\n");
        for (Turn turn : t.turns()) sb.append("## ").append(turn.role().equals("person") ? "Person" : turn.role().equals("author") ? "Author" : "Assistant").append("\n\n").append(turn.text()).append("\n\n");
        return sb.toString();
    }

    /**
     * One thread onto the shelves and into the open questions. {@code source} is where it came from (a file
     * path, a url, "pasted"); {@code who} the patron's writer label. Nothing is filed as a finding.
     */
    public static Outcome absorb(LibraryStore store, Thread t, String source, String collection, String who, Function<String, List<String>> extractor) throws IOException {
        String body = render(t);
        String locator = "conversation://" + HexFormat.of().formatHex(sha(body), 0, 8);
        Path raw = RawCapture.capture(store, locator, body, t.title(), "conversation:" + Acquisitions.compress(source, 120), collection);
        List<String> filed = new ArrayList<>(), held = new ArrayList<>();
        List<Frontier.Line> open = Frontier.read(store);
        for (String q : questions(t)) {
            boolean have = false;
            for (Frontier.Line l : open) if (Frontier.sameQuestion(l.text(), q) || Frontier.jaccard(Frontier.terms(q), Frontier.terms(l.text())) >= 0.6) { have = true; break; }
            if (have) { held.add(q); continue; }
            store.frontier("person " + who + " (from a conversation: " + Acquisitions.compress(t.title(), 60) + ")", q);
            filed.add(q);
        }
        List<String> claims = claims(t, extractor);
        store.circulate("absorb", who + " :: " + Acquisitions.compress(t.title(), 80) + " — " + t.turns().size() + " turns, " + filed.size() + " question(s) filed, " + claims.size() + " claim(s) to check");
        return new Outcome(t.title(), raw == null ? "" : raw.getFileName().toString(), t.turns().size(), filed, held, claims);
    }

    /** The question for a run that checks the claims: numbered, with the standing instruction. */
    public static String verifyQuestion(String title, List<String> claims) {
        StringBuilder sb = new StringBuilder("Check each of these claims against sources; they were stated by an assistant in a conversation titled \"")
                .append(Acquisitions.compress(title, 80)).append("\" and none of them is established. For each, say whether it holds, holds with corrections, or does not hold, and cite the source.\n");
        for (int i = 0; i < claims.size(); i++) sb.append(i + 1).append(". ").append(claims.get(i)).append('\n');
        return sb.toString().strip();
    }

    /** The thread's own question for a run: the first thing the person asked, or the title. */
    public static String mainQuestion(Thread t) {
        List<String> qs = questions(t);
        return qs.isEmpty() ? t.title() : qs.get(0);
    }

    /** Eight hex characters of a text's SHA-256, for content locators. */
    public static String hash8(String s) { return HexFormat.of().formatHex(sha(s), 0, 8); }

    private static byte[] sha(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
