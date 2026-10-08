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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.text.Normalizer;
import org.researchzosho.Config;
import org.researchzosho.mcp.McpServer;
import org.researchzosho.drive.Declined;
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
            "library_inbox", "library_frontier", "library_map", "library_changes", "library_submit", "library_sharpen", "library_status", "library_add", "library_absorb", "library_survey", "library_items",
            "library_check", "library_reading", "library_questions", "library_bookmarks", "library_meeting", "library_bridges", "library_holdings", "library_db", "library_settings");

    /** How long a run the chat asked about waits for the person's answer before it starts as ordinary research. */
    static final int OFFER_MINUTES = Config.getInt("RESEARCHZOSHO_OFFER_MINUTES", 10);

    /**
     * The fields whose chat tools and notes this library's chat is given: those that recognise their own questions, and a field that
     * joins only when asked where the library holds its work. An ordinary library's chat hears nothing of a module.
     */
    static List<Profile> chatFields(LibraryStore store) {
        List<Profile> out = new ArrayList<>();
        for (Profile p : Fields.enabled(store)) if (!p.joinsOnlyWhenAsked() || Fields.holdsWork(store, p)) out.add(p);
        return out;
    }

    /** The names of the tools this library's chat is given: the core's, and those of its {@link #chatFields}. */
    static List<String> toolNames(LibraryStore store) {
        List<String> out = new ArrayList<>(TOOLS);
        for (Profile p : chatFields(store)) for (String t : p.chatTools()) if (!out.contains(t)) out.add(t);
        return out;
    }

    /** Every tool a chat can be given, whatever the library holds. */
    static List<String> allToolNames() {
        List<String> out = new ArrayList<>(TOOLS);
        for (Profile p : Profiles.known()) for (String t : p.chatTools()) if (!out.contains(t)) out.add(t);
        return out;
    }
    /** The tools that can start a research run, and the shape of a run's id in their result. */
    static final Set<String> STARTS_RUNS = Set.of("library_research", "library_absorb", "library_items", "library_check", "library_questions", "library_meeting", "library_bridges", "library_survey");
    static final Pattern JOB_ID = Pattern.compile("\"(J-\\d{3,})\"");

    /** Tool rounds one turn may take before the Librarian has to speak. */
    static final int MAX_TOOL_ROUNDS = Config.getInt("RESEARCHZOSHO_CHAT_TOOL_ROUNDS", 5);
    /** Look-ups one turn may make in all; past it the Librarian answers from what it has (17 opens on one question, 2026-09-13). */
    static final int MAX_CALLS = Config.getInt("RESEARCHZOSHO_CHAT_MAX_CALLS", 8);
    /** A tool result longer than this is cut, head and tail, before the model sees it; the person can open the entry. */
    static final int RESULT_CAP = Config.getInt("RESEARCHZOSHO_CHAT_RESULT_CHARS", 7000);
    /** Turns of history kept in the model's context; older turns are dropped, the session file keeps them all. */
    static final int HISTORY_TURNS = Config.getInt("RESEARCHZOSHO_CHAT_HISTORY_TURNS", 16);
    static final int REPLY_TOKENS = Config.getInt("RESEARCHZOSHO_CHAT_REPLY_TOKENS", 1500);
    /** Items of a list a tool result keeps for the model; the rest is counted and the filters named. */
    static final int LIST_CAP = Config.getInt("RESEARCHZOSHO_CHAT_LIST_ITEMS", 25);

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
        this.protocol.asker = this::research;   // the runs a batch tool starts are asked about the chat's way too
        for (JsonNode m : session.messages) if ("tool".equals(m.path("role").asText())) evidence.append('\n').append(m.path("content").asText(""));
    }

    public Session session() { return session; }

    /** Whether a run waits for the person's answer to the library's question (y/N): the terminal counts an empty line as no then. */
    public boolean waitingForAnswer() { return !stillWaiting(store, session).isEmpty(); }

    /** The runs of a conversation that wait for the person's answer now: offered, and the wait not over. */
    static List<String[]> stillWaiting(LibraryStore store, Session s) {
        List<String[]> out = new ArrayList<>();
        Jobs jobs = new Jobs(store, j -> "");
        long now = System.currentTimeMillis();
        for (String[] o : s.pending()) {
            try { ObjectNode j = jobs.get(o[0]); if (j != null && Jobs.OFFERED.equals(j.path("state").asText()) && !Jobs.offerEnded(j, now)) out.add(o); }
            catch (IOException ignored) { }
        }
        return out;
    }

    /**
     * The questions whose wait ended before the person answered: each run goes as the end of the wait sends it (as it was filed, or not at
     * all for a second run), the conversation writes the wait down as over, and the person is told in a sentence. What the person says
     * next is a message like any other. "" when no wait ended.
     */
    private String endedWaits() throws IOException {
        Jobs jobs = new Jobs(store, j -> "");
        long now = System.currentTimeMillis();
        Set<String> started = new LinkedHashSet<>(), notStarted = new LinkedHashSet<>(), notResearched = new LinkedHashSet<>();
        for (String[] o : session.pending()) {
            ObjectNode j = jobs.get(o[0]);
            if (j != null && Jobs.OFFERED.equals(j.path("state").asText()) && !Jobs.offerEnded(j, now)) continue;   // still waiting
            if (j != null && Jobs.OFFERED.equals(j.path("state").asText())) { jobs.expire(j); j = jobs.get(o[0]); }   // the service has not got to it yet
            session.answer(o[0], o[1], "none", o[2]);
            if (!isContent(o) && !isHelp(o)) Fields.note(store, o[2], o[1], "asked", "none", "chat");
            if (j == null) continue;
            if (isHelp(o)) { if ("stopped".equals(j.path("state").asText()) && "none".equals(j.path("help_answered").asText())) notResearched.add(o[0]); }
            else if (again(o)) notStarted.add(o[0]);
            else if (!"stopped".equals(j.path("state").asText())) started.add(o[0]);
        }
        started.removeAll(notStarted); started.removeAll(notResearched);
        StringBuilder b = new StringBuilder();
        if (!started.isEmpty()) b.append(started.size() == 1 ? "Research run " + started.iterator().next() + " started" : "Research runs " + String.join(" and ", started) + " started")
                .append(" as ordinary research, because no answer came within ").append(OFFER_MINUTES).append(" minutes.");
        if (!notStarted.isEmpty()) b.append(b.isEmpty() ? "" : " ").append("No second run was started, because no answer came within ").append(OFFER_MINUTES).append(" minutes.");
        if (!notResearched.isEmpty()) b.append(b.isEmpty() ? "" : " ").append(notResearched.size() == 1 ? "Research run " + notResearched.iterator().next() + " was not started" : "Research runs " + String.join(" and ", notResearched) + " were not started")
                .append(", because no answer came within ").append(OFFER_MINUTES).append(" minutes to the question whether to research it.");
        return b.toString();
    }

    /** Whether a waiting run is a second run of a question that already runs, which starts only on a yes. */
    private static boolean again(String[] offer) { return offer.length > 3 && "again".equals(offer[3]); }

    /**
     * The kind the conversation's offers file writes, in its field column, for the question whether to let in what the library leaves
     * out by default ({@link ContentOffer}). A field's name never starts with "!".
     */
    static final String CONTENT = "!content";

    /** Whether a waiting offer is the question about what the library leaves out, rather than a field's. */
    static boolean isContent(String[] offer) { return offer.length > 1 && CONTENT.equals(offer[1]); }

    /** The kind the offers file writes for the question whether to research at all a question that reads as a person asking about harming themselves. */
    static final String HELP = "!help";

    static boolean isHelp(String[] offer) { return offer.length > 1 && HELP.equals(offer[1]); }

    /** The order the questions are asked in: whether to research it at all first, then a field's mode, then what the library leaves out. */
    static int rank(String[] offer) { return isHelp(offer) ? 0 : isContent(offer) ? 2 : 1; }

    /**
     * The person left without answering (quit, a new conversation): the waiting runs start as ordinary research, and a second run does not
     * start. What happened, in a sentence for the person; "" when nothing waited.
     */
    public String leaveUnanswered() throws IOException {
        boolean started = false, notStarted = false, notResearched = false;
        Set<String> stopped = new HashSet<>();
        List<String[]> pending = new ArrayList<>(session.pending());
        pending.sort((a, b) -> Integer.compare(rank(a), rank(b)));   // whether to research it at all is answered first: a no there starts nothing
        for (String[] o : pending) {
            if (stopped.contains(o[0])) { session.answer(o[0], o[1], "none", o[2]); continue; }
            release(o, false);   // no answer is no: nothing is let in, and a question about harming oneself is not researched
            if (isHelp(o)) { notResearched = true; stopped.add(o[0]); }
            else if (again(o)) notStarted = true; else started = true;
        }
        List<String> said = new ArrayList<>();
        if (notResearched) said.add("The question the library asked whether to research is not researched.");
        if (started) said.add("The research the library asked you about starts as ordinary research.");
        if (notStarted) said.add("No second run was started. The run that already started goes on as ordinary research.");
        return String.join(" ", said);
    }

    /**
     * One answer to the library's question, for one waiting run: released with the field on a yes, as ordinary research on anything
     * else. False when the run had stopped waiting already (no answer came in time, so it started as ordinary research).
     */
    private boolean release(String[] offer, boolean yes) throws IOException {
        if (isHelp(offer)) {
            // whether to research at all a question that reads as the person asking about harming themselves: no starts nothing
            boolean now = new Jobs(store, j -> "").releaseHelp(offer[0], yes);
            session.answer(offer[0], offer[1], now ? (yes ? "yes" : "no") : "late", offer[2]);
            return now;
        }
        if (isContent(offer)) {
            // the question about what the library leaves out: a yes goes into the run's allow, for this run alone, and is never remembered
            boolean now = new Jobs(store, j -> "").releaseContent(offer[0], yes);
            session.answer(offer[0], offer[1], now ? (yes ? "yes" : "no") : "late", offer[2]);
            return now;
        }
        boolean now = new Jobs(store, j -> "").release(offer[0], yes ? offer[1] : null, "chat-yes");
        session.answer(offer[0], offer[1], now ? (yes ? "yes" : "no") : "late", offer[2]);
        Fields.note(store, offer[2], offer[1], "asked", now ? (yes ? "yes" : "no") : "none", "chat");
        return now;
    }

    /** The answer words: yes in the languages the chat is spoken in, no, and an empty line (no). Width and case folded. */
    static final List<String> YES = List.of("yes", "y", "はい", "ja", "oui", "sí", "si", "sim", "да", "是", "네", "예");
    static final List<String> NO = List.of("no", "n", "nein", "non", "いいえ", "нет", "不", "아니요", "아니오");

    /** The yes-word the words begin with, or null: "yes", "Yes, and also …", "ｙ", "はい、お願いします". */
    static String yesWord(String words) {
        String w = Normalizer.normalize(words == null ? "" : words, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
        for (String y : YES) if (w.equals(y) || (w.startsWith(y) && !Character.isLetterOrDigit(w.codePointAt(y.length())))) return y;
        return null;
    }

    static boolean isNo(String words) {
        String w = Normalizer.normalize(words == null ? "" : words, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT).replaceAll("[.!。！]+$", "");
        return w.isEmpty() || NO.contains(w);
    }

    /**
     * The line under a reply that asks the person, once, about every run of this turn that waits for their answer: a field's question
     * first, and the question about what the library leaves out after it is answered. {@code offers}: {job, field or {@link #CONTENT}, the
     * field's offer or the categories the content question offers, joined by commas}.
     */
    static String offerLine(List<String[]> offers) {
        List<String[]> help = offers.stream().filter(Librarian::isHelp).toList();
        if (!help.isEmpty()) return help.size() == 1 ? CrisisHelp.QUESTION : "Do you want the library to research these " + help.size() + " questions? (y/N)";
        offers = offers.stream().filter(o -> !isHelp(o)).toList();
        List<String[]> fields = offers.stream().filter(o -> !isContent(o)).toList();
        if (fields.isEmpty()) {
            Set<String> needs = new LinkedHashSet<>();
            for (String[] o : offers) for (String n : o[2].split(",")) if (!n.isBlank()) needs.add(n.strip());
            return ContentOffer.question(needs, offers.size());
        }
        offers = fields;
        String[] first = offers.get(0);
        String offer = first[2];
        if (offers.size() == 1) return offer + " Use " + first[1] + " mode for this question? (y/N)";
        return offer + " Use " + first[1] + " mode for these " + offers.size() + " questions? (y/N)";
    }

    /**
     * The person's answer to the library's own question, read before the model sees anything. {@code reply}: what the library says to
     * it ("" for nothing); {@code rest}: the words that go on to the model as a turn like any other, or null when the words were only
     * the answer.
     */
    record Answered(String reply, String rest, String next) {
        Answered(String reply, String rest) { this(reply, rest, ""); }
    }

    /** The next question the library asks, after one was answered: the one still waiting, "" when none waits. */
    private String nextQuestion() throws IOException {
        List<String[]> next = stillWaiting(store, session);
        if (next.isEmpty()) return "";
        Jobs jobs = new Jobs(store, j -> "");
        List<String[]> offers = new ArrayList<>();
        for (String[] o : next) {
            if (isHelp(o)) offers.add(new String[]{o[0], HELP, ""});
            else if (isContent(o)) {
                ObjectNode j = jobs.get(o[0]);
                List<String> needs = new ArrayList<>();
                if (j != null) for (JsonNode c : j.path("offered_content")) needs.add(c.asText());
                offers.add(new String[]{o[0], CONTENT, String.join(",", needs)});
            } else {
                Profile p = Profiles.named(o[1]);
                offers.add(new String[]{o[0], o[1], p == null ? "" : p.offer()});
            }
        }
        return offerLine(offers);
    }

    /**
     * A yes (or words that begin with one) releases the waiting runs in the field's mode; anything else releases them as ordinary
     * research. A bare yes, a no or an empty line gets a fixed reply; words after the yes, or other words, are then a turn like any other.
     */
    Answered answer(List<String[]> waiting, String words) throws IOException {
        String said = Normalizer.normalize(words == null ? "" : words, Normalizer.Form.NFKC).strip();
        String yes = yesWord(said);
        boolean no = yes == null && isNo(said);
        // one question at a time: whether to research it at all, then a field's, then what the library leaves out; the answer is to the question asked
        int first = waiting.stream().mapToInt(Librarian::rank).min().orElse(1);
        List<String[]> asked = waiting.stream().filter(o -> rank(o) == first).toList();
        if (first == 0) return answerHelp(asked, said, yes, no, words);
        if (first == 2) return answerContent(asked, said, yes, no, words);
        waiting = asked;
        List<String> started = new ArrayList<>(), late = new ArrayList<>(), notStarted = new ArrayList<>(), answered = new ArrayList<>();
        Jobs jobs = new Jobs(store, j -> "");
        for (String[] o : waiting) {
            boolean moved = release(o, yes != null);
            // a second run of a question that already runs starts only on a yes
            if (again(o)) { if (moved && yes != null) { started.add(o[0]); answered.add(o[0]); session.watch(o[0]); } else notStarted.add(o[0]); }
            else if (moved) {
                answered.add(o[0]);
                ObjectNode j = jobs.get(o[0]);
                if (j == null || !Jobs.OFFERED.equals(j.path("state").asText())) started.add(o[0]);   // still offered: its next question waits first
            }
            else late.add(o[0]);
        }
        String field = waiting.get(0)[1];
        StringBuilder reply = new StringBuilder();
        if (!answered.isEmpty())
            reply.append(yes != null ? (answered.size() == 1 ? "The question is researched in " + field + " mode." : "The questions are researched in " + field + " mode.") : "It is researched as ordinary research.");
        if (!started.isEmpty()) {
            String runs = started.size() == 1 ? "Research run " + started.get(0) + " has" : "Research runs " + String.join(" and ", started) + " have";
            reply.append(' ').append(runs).append(" started; a run usually takes twenty to forty minutes, and this chat says when it is done.");
        }
        if (!notStarted.isEmpty())
            reply.append(reply.isEmpty() ? "" : " ").append("No second run was started. The run that already started goes on as ordinary research.");
        if (!late.isEmpty() && yes != null) {
            // the wait had ended and the run started as ordinary research: a second run of the same question in the field's mode is offered
            for (String id : late) {
                ObjectNode j = new Jobs(store, x -> "").get(id);
                if (j == null || !j.path("args").isObject()) continue;
                ObjectNode args = ((ObjectNode) j.path("args")).deepCopy();
                args.remove("suggested"); args.set("patron", patronNode());
                Fields.Suggestion s = new Fields.Suggestion(field, "");
                String again = protocol.research(args, LibraryProtocol.Way.QUIET, s, System.currentTimeMillis() + OFFER_MINUTES * 60_000L, true).path("job_id").asText();
                session.offer(again, field, args.path("question").asText(""), true);
                reply.append(reply.isEmpty() ? "" : " ").append("Research run ").append(id).append(" had already started as ordinary research, because no answer came within ")
                     .append(OFFER_MINUTES).append(" minutes. Start a second run of the same question in ").append(field).append(" mode? (y/N)");
            }
        }
        if (yes != null) {
            String rest = said.substring(yes.length()).replaceFirst("^[\\s,.;:!、。，！]+", "");
            return new Answered(reply.toString(), rest.isBlank() ? null : rest, nextQuestion());   // "yes, and also …": the rest is a turn like any other
        }
        return new Answered(reply.toString(), no ? null : words, nextQuestion());   // other words: released as ordinary research, and the words are a turn
    }

    /**
     * The answer to the question whether to research at all a question that reads as the person asking about harming themselves, asked
     * after the library showed where to find help. A clear yes starts it as ordinary research; anything else, Enter among it, starts nothing.
     */
    private Answered answerHelp(List<String[]> waiting, String said, String yes, boolean no, String words) throws IOException {
        List<String> started = new ArrayList<>(), late = new ArrayList<>(), answered = new ArrayList<>();
        Jobs jobs = new Jobs(store, j -> "");
        for (String[] o : waiting) {
            boolean moved = release(o, yes != null);
            if (!moved) { late.add(o[0]); continue; }
            answered.add(o[0]);
            ObjectNode j = jobs.get(o[0]);
            if (j != null && ("queued".equals(j.path("state").asText()) || "running".equals(j.path("state").asText()))) started.add(o[0]);
        }
        StringBuilder reply = new StringBuilder();
        if (!answered.isEmpty()) reply.append(yes != null ? (answered.size() == 1 ? "The question is researched." : "The questions are researched.") : (answered.size() == 1 ? "Nothing is researched for that question." : "Nothing is researched for those questions."));
        if (!started.isEmpty()) {
            String runs = started.size() == 1 ? "Research run " + started.get(0) + " has" : "Research runs " + String.join(" and ", started) + " have";
            reply.append(' ').append(runs).append(" started; a run usually takes twenty to forty minutes, and this chat says when it is done.");
        }
        if (!late.isEmpty()) reply.append(reply.isEmpty() ? "" : " ").append(late.size() == 1 ? "Research run " + late.get(0) + " was" : "Research runs " + String.join(" and ", late) + " were")
                .append(" not started, because no answer came within ").append(OFFER_MINUTES).append(" minutes. Ask again to research it.");
        if (yes != null) {
            String rest = said.substring(yes.length()).replaceFirst("^[\\s,.;:!、。，！]+", "");
            return new Answered(reply.toString(), rest.isBlank() ? null : rest, nextQuestion());
        }
        return new Answered(reply.toString(), no ? null : words, nextQuestion());
    }

    /**
     * The answer to the question whether to let in what the library leaves out by default. A clear yes lets it into those runs only;
     * anything else, Enter among it, leaves it out. It is never remembered for another question, or another run of this one.
     */
    private Answered answerContent(List<String[]> waiting, String said, String yes, boolean no, String words) throws IOException {
        List<String> started = new ArrayList<>(), late = new ArrayList<>(), answered = new ArrayList<>();
        Jobs jobs = new Jobs(store, j -> "");
        for (String[] o : waiting) {
            boolean moved = release(o, yes != null);
            if (!moved) { late.add(o[0]); continue; }
            answered.add(o[0]);
            ObjectNode j = jobs.get(o[0]);
            if (j == null || !Jobs.OFFERED.equals(j.path("state").asText())) started.add(o[0]);
        }
        StringBuilder reply = new StringBuilder();
        if (!answered.isEmpty()) reply.append(yes != null ? (answered.size() == 1 ? "That material is let into this question's research run only." : "That material is let into these questions' research runs only.")
                : (answered.size() == 1 ? "The question is researched with that material left out." : "The questions are researched with that material left out."));
        if (!started.isEmpty()) {
            String runs = started.size() == 1 ? "Research run " + started.get(0) + " has" : "Research runs " + String.join(" and ", started) + " have";
            reply.append(' ').append(runs).append(" started; a run usually takes twenty to forty minutes, and this chat says when it is done.");
        }
        if (!late.isEmpty()) reply.append(reply.isEmpty() ? "" : " ").append(late.size() == 1 ? "Research run " + late.get(0) + " had" : "Research runs " + String.join(" and ", late) + " had")
                .append(" already started with that material left out, because no answer came within ").append(OFFER_MINUTES).append(" minutes.");
        if (yes != null) {
            String rest = said.substring(yes.length()).replaceFirst("^[\\s,.;:!、。，！]+", "");
            return new Answered(reply.toString(), rest.isBlank() ? null : rest, nextQuestion());
        }
        return new Answered(reply.toString(), no ? null : words, nextQuestion());
    }

    /**
     * One turn: the person's words in, the Librarian's reply out. Tool rounds happen inside; the session is written as it goes. While the
     * library waits for the person's answer to its question, the words are read first as that answer; a wait that has ended is over, and
     * the words are a message like any other.
     */
    public String say(String words) throws IOException {
        String ended = endedWaits();
        List<String[]> waiting = session.pending();
        if (waiting.isEmpty()) { String reply = turn(words); return ended.isEmpty() ? reply : ended + "\n\n" + reply; }
        Answered a = answer(waiting, words);
        String said = ended.isEmpty() ? a.reply() : a.reply().isEmpty() ? ended : ended + " " + a.reply();
        if (a.rest() == null) {
            if (!a.next().isEmpty()) said = said.isEmpty() ? a.next() : said + "\n\n" + a.next();   // the next question, one after the other
            ObjectNode user = M.createObjectNode(); user.put("role", "user"); user.put("content", words == null ? "" : words);
            session.append(user);
            ObjectNode reply = M.createObjectNode(); reply.put("role", "assistant"); reply.put("content", said);
            session.append(reply);
            record(words == null ? "" : words, List.of(), 0, 0);
            return said;
        }
        String reply = turn(a.rest());
        String out = said.isEmpty() ? reply : said + "\n\n" + reply;
        return a.next().isEmpty() || reply.endsWith("(y/N)") ? out : out + "\n\n" + a.next();
    }

    /** One turn, with what the decline judge was unsure of in it written on the turn's line of the chat's turn log. */
    private String turn(String words) throws IOException {
        unsureThisTurn.clear();
        try (var noting = Declines.noting(unsureThisTurn::add)) { return turnNoted(words); }
        finally { unsureThisTurn.clear(); }   // written on this turn's line; never on another's
    }

    private String turnNoted(String words) throws IOException {
        long t0 = System.currentTimeMillis();
        offeredThisTurn.clear();
        helpThisTurn = false;
        partlyDeclined.clear();
        personWords = words == null ? "" : words;
        ObjectNode user = M.createObjectNode(); user.put("role", "user"); user.put("content", words);
        session.append(user);
        ArrayNode tools = tools(store);
        List<String> toolsCalled = new ArrayList<>();
        StringBuilder turnEvidence = new StringBuilder();
        String reply = null;
        boolean nudged = false;
        declinedThisTurn = null;
        for (int round = 0; round <= MAX_TOOL_ROUNDS + 1; round++) {
            ArrayNode messages = fitted(context(), 0.55);
            boolean last = round >= MAX_TOOL_ROUNDS || toolsCalled.size() >= MAX_CALLS;
            ObjectNode assistant;
            try (var step = Declines.step("answer a person's message in a conversation about their research library, with the library's tools: " + Acquisitions.compress(personWords, 300))) {
                try {
                    assistant = drive.chat(messages, last ? M.createArrayNode() : tools, REPLY_TOKENS, last ? "none" : "auto");
                } catch (Declined d) {
                    throw d;
                } catch (RuntimeException e) {
                    // the server's own count outranks the estimate: on an overflow, fit harder once and try again (deepagents' bound on compaction recovery)
                    if (e.getMessage() == null || !e.getMessage().contains("context size")) throw e;
                    assistant = drive.chat(fitted(context(), 0.3), last ? M.createArrayNode() : tools, REPLY_TOKENS, last ? "none" : "auto");
                }
            } catch (Declined d) {
                // the model declined: the library's statement is the reply; no nudge, no second try
                declinedThisTurn = d;
                reply = d.statement("to answer this");
                break;
            }
            JsonNode calls = assistant.path("tool_calls");
            ObjectNode keep = M.createObjectNode(); keep.put("role", "assistant");
            String content = assistant.path("content").asText("");
            if (calls.isArray() && calls.size() > 0 && !last && toolsCalled.size() < MAX_CALLS) {
                keep.put("content", content); keep.set("tool_calls", calls);
                session.append(keep);
                List<String> notRun = new ArrayList<>();
                for (JsonNode c : calls) {
                    String name = c.path("function").path("name").asText("");
                    ObjectNode tm = M.createObjectNode(); tm.put("role", "tool"); tm.put("tool_call_id", c.path("id").asText("")); tm.put("name", name);
                    if (declinedThisTurn != null) {
                        // a tool before it in the same batch had its step declined: the turn stops there, and this one is not run
                        notRun.add(name);
                        tm.put("content", "NOT RUN: the model declined an earlier step of this turn, so the library did not run this tool.");
                        session.append(tm);
                        continue;
                    }
                    JsonNode args = Researcher.parseArgs(c.path("function").path("arguments"));
                    String result = call(name, args);
                    toolsCalled.add(name);
                    if (STARTS_RUNS.contains(name)) { Matcher jm = JOB_ID.matcher(result); while (jm.find()) session.watch(jm.group(1)); }   // the chat follows the runs it starts
                    tm.put("content", result);
                    session.append(tm);
                    evidence.append('\n').append(result); turnEvidence.append('\n').append(result);
                }
                // a tool's model declined its step: the turn ends with the library's statement, and the chat does not go on around it
                if (declinedThisTurn != null) {
                    reply = declinedThisTurn.statement() + (notRun.isEmpty() ? "" : " The library did not run the " + (notRun.size() == 1 ? "other tool" : notRun.size() + " other tools")
                            + " the model asked for in the same turn: " + String.join(", ", notRun) + ".");
                    break;
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
        // (a declined turn's reply is the library's own statement, quoting the model: nothing in it is the model's claim)
        boolean said = declinedThisTurn != null;
        List<String> unbacked = said ? new ArrayList<>() : WriteupChecks.numbersUnbacked(reply, evidence.toString());
        if (!said) unbacked.addAll(WriteupChecks.namesUnbacked(reply, evidence.toString()));
        // rule 2, mechanically: an entry id cited in brackets that no tool result returned is a made-up reference (the 4B did this, 2026-09-14)
        List<String> madeUp = said ? List.of() : idsUnbacked(reply, evidence.toString());
        if (!unbacked.isEmpty() || !madeUp.isEmpty()) {
            StringBuilder adm = new StringBuilder("\n\n(");
            List<String> items = new ArrayList<>();
            for (String u : unbacked) items.add(u.substring(0, u.indexOf(" — ")).strip());
            if (!items.isEmpty()) adm.append(items.size() == 1 ? "The figure " + items.get(0) + " is not in anything I looked up; take it as my guess." : "The figures " + String.join(", ", items) + " are not in anything I looked up; take them as my guesses.");
            if (!madeUp.isEmpty()) adm.append(items.isEmpty() ? "" : " ").append(madeUp.size() == 1 ? "The reference " + madeUp.get(0) + " is not an entry I looked up; I should not have cited it." : "The references " + String.join(", ", madeUp) + " are not entries I looked up; I should not have cited them.");
            adm.append(')');
            reply = reply + adm;
            unbacked.addAll(madeUp);
        }
        // the library's own question, once, under the reply: every run of this turn that waits for the person's answer
        if (!offeredThisTurn.isEmpty()) reply = reply + "\n\n" + offerLine(offeredThisTurn);
        // where to find help comes first, before the model's words and the question whether to research it
        if (helpThisTurn) reply = CrisisHelp.text() + "\n\n" + reply;
        // a tool that did its own part and whose model declined the rest: the library's statement under the reply, in its own words
        if (declinedThisTurn == null && !partlyDeclined.isEmpty()) reply = reply + "\n\n" + String.join("\n\n", partlyDeclined);
        ObjectNode a = M.createObjectNode(); a.put("role", "assistant"); a.put("content", reply);
        session.append(a);
        record(words, toolsCalled, unbacked.size(), System.currentTimeMillis() - t0, declinedThisTurn != null || !partlyDeclined.isEmpty());
        return reply;
    }

    /** What the chat's instructions say of the fields whose work this library holds. */
    String fieldNotes() {
        StringBuilder b = new StringBuilder();
        for (Profile p : chatFields(store)) if (!p.chatNote().isBlank()) b.append(p.chatNote().strip()).append('\n');
        return b.toString();
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
                + LocalDate.now() + ". You answer from what the library holds, through the tools, and you say so.\n"
                + "Rules.\n"
                + "1. What the shelves hold, you state as theirs, with its sources. What you infer, you mark as inference (\"the shelves do not say; I would guess…\"). "
                + "When the library cannot answer, say \"I don't know\", and offer to go and find out when a research run could.\n"
                + "2. Every fact carries where it came from: the entry id or the source, so the person can open it.\n"
                + "3. Read a follow-up against the conversation. \"Why\" after an answer means why that answer; \"the other one\" resolves to what was shown. A change of subject is a new thread; say so.\n"
                + "4. After an answer, offer one adjacent thing: a related finding, an open question, a claim that disagrees, a serial that watches this. One, not a list.\n"
                + "5. \"Find out\" means file a research run with library_research: say in one sentence that it has started and that it usually takes twenty to forty minutes, then carry on. The chat itself shows the run's stage and tells the person when it is done, so never tell them to ask how it is going. When a run is done and they say \"show it\", or they ask how it went, use library_job and library_get and give the answer section and the checks, not the whole text. A plain yes from the person is enough to file a run or to accept an inbox item.\n"
                + "6. Short exact sentences. No enthusiasm, no apology, no filler. Courteous.\n"
                + "7. Never state a name, a number, a date or a quotation that is not in a tool result. If you must estimate, say it is an estimate.\n"
                + "Look things up before answering, and read only what the answer needs: library_ask for a question (it returns the relevant entries whole), library_search for a term, library_get to open one entry, library_map around a name, library_inbox for what waits, library_frontier for open questions, library_changes for what is new. A few look-ups a turn, then answer; a list that says N of M shown is a list of M. "
                + "To read material in — a file, a folder, a drive, a url the person names — use library_add. For a folder, run mode=survey first and put the numbers to the person: keep (the text is copied onto the shelves) or link (read in place, nothing copied). If they already said which, do that. Then \"research X from those\" is library_research with sources=shelves and the collection. "
                + "A conversation the person had with another assistant — a file, a url, or text they paste — is absorbed with library_absorb: say what was shelved, which of their questions joined the open questions, and list the claims to check; then offer two things, verify=true (one run that checks the claims) and a research run on the thread's main question. "
                + "A code repository, a paper, a website or product page, or an issue tracker — a folder or file on this machine, or a url — goes through library_survey: op=survey first, then tell the person what it is in one or two sentences and list the numbered directions exactly as returned; ask which to run, or take their own words with op=do. Runs cost the model about half an hour each; say so. op=pick with the numbers they chose.\n"
                + "A question about the person's own data (\"how many orders last month\", \"what is in my database\") is library_db: op=list, then op=schema for the database, then op=query with one SELECT that counts or groups. Say what the query was and what it returned, and cite the saved locator. Never guess at a table or column the schema does not show.\n"
                + "\"Do I have X\", \"which of these do I own\", \"recommend something I don't have\" is library_holdings, one call per title: what it returns is what the person's lists hold; nothing else counts as owned. A Calibre library or a big list: library_items with match (a tag, an author, a year) or sample picks which items get questions; the whole list is shelved regardless.\n"
                + "A list of things — books, tools, places, an inventory — goes through library_items: first with as=none to say how many items there are and which the shelves already hold, and ask what they want to know about each (that is the lens) unless they said; then as=frontier files a question per item for the housekeeping, or as=runs sends them out now as research runs. "
                + "The other starting points: their own draft or notes to check → library_check (claims to check, citations fetched; offer verify=true); a reading list, BibTeX or a file of DOIs → library_reading (fetched onto the shelves as a collection); a list of questions → library_questions (onto the open questions in order); a bookmarks export → library_bookmarks (the pages onto the shelves; watch=true re-reads them); a meeting transcript → library_meeting (decisions kept, questions raised filed, claims to check with who said them). After any of them, say what was shelved and filed, and what could not be read. "
                + "\"Go deeper on I-0010\", \"follow up on that report\", \"dig into …\": library_get the report, then library_research with mode=depth and a question that begins \"Follow-up to <id> (<its title>): \" followed by the person's own question, or by the questions the report left open when they gave none; the report is handed to the run that way. Say which question was sent and the run's id.\n"
                + "When a software run has finished and the person wants the projects themselves read (\"clone them\", \"look inside\", \"review the code\"): library_survey with op=survey and the repository's url, one call per project, starting from the first in the report's table; say what each is and its directions. The command `researchzosho survey --from <report id>` does the first five at once.\n"
                + "\"What could connect X to something far from it\", \"any discoveries\", \"look for a bridge\" is library_bridges: op=run with the area and dry=true first, show the pairs with their shared terms and the question for each, and ask which to file; op=accept files the run that tests one. Say plainly that a bridge is a question the shelves have not answered, not a finding. "
                + fieldNotes()
                + "Cite entries by their id in square brackets, like [F-0012-a] or [I-0031-…], right after the sentence they support.";
    }

    /**
     * What each tool is, in the chat's words: a sentence or two. The MCP server's descriptions are written for
     * programs and run to a paragraph each; twenty of those cost a quarter of a 32k window on every turn
     * (measured 2026-09-14: 29,575 characters). The arguments keep their schema and the first sentence of
     * their description; the patron field is left out, the chat fills it in.
     */
    static final Map<String, String> CHAT_DESCRIPTIONS = Map.ofEntries(
            Map.entry("library_ask", "Put a question to the shelves; returns the entries that answer it, whole, and holds_nothing when there are none."),
            Map.entry("library_search", "Search the shelves for a term; returns hits with id, title and snippet. Open one with library_get."),
            Map.entry("library_get", "Open one entry by its full id (F-…, I-…, A-…, a raw file); a section or a slice when it is long."),
            Map.entry("library_research", "File a research run on a question. It takes a while; sources=shelves keeps it to what is held, collections narrows it."),
            Map.entry("library_job", "How a research run is going, by job id; op=stop ends it."),
            Map.entry("library_inbox", "What waits for the person: drafts to accept, dispute or retire; op=list, or a decision with ids."),
            Map.entry("library_frontier", "The open questions: list them, add one, move one next or later, park, drop."),
            Map.entry("library_map", "The graph around a name: what connects to it, with the claims behind each edge."),
            Map.entry("library_changes", "What is new on the shelves since a time."),
            Map.entry("library_submit", "File one claim as a draft with its sources; refused without sources."),
            Map.entry("library_sharpen", "Turn a vague question into precise ones the shelves and the web can answer."),
            Map.entry("library_status", "Counts by kind, what is stale, the version running."),
            Map.entry("library_add", "Read a file, a folder or a url into the library. For a folder, mode=survey counts first; mode=keep copies the text, mode=link reads the files in place."),
            Map.entry("library_absorb", "A conversation the person had with another assistant: shelved, their questions filed, the assistant's claims returned to check; verify=true files the checking run."),
            Map.entry("library_survey", "A code repository, a paper, a website or an issue tracker (a path or a url): read, shelved, one draft claim on what it is, and numbered research directions offered; op=pick runs the ones chosen, op=do runs the person's own."),
            Map.entry("library_items", "A list of things (books, tools, an inventory): shelved, each checked against the shelves, then a question per item through the lens; as=none only looks, as=frontier files, as=runs sends runs."),
            Map.entry("library_db", "The owner's databases, read-only: op=list, op=schema (tables, columns, sample rows), op=query (one SELECT, or a MongoDB filter or pipeline). Read the schema first; count and group in the query; cite the saved locator."),
            Map.entry("library_holdings", "What the person's own lists hold (a Calibre library, an inventory): an exact lookup by title words, author or year; ask it per candidate to tell owned from not owned."),
            Map.entry("library_check", "The person's own draft: its claims returned to check, its citations fetched, its questions filed; verify=true files the checking run."),
            Map.entry("library_reading", "A reading list (BibTeX, RIS, CSV, lines of DOIs and urls): every entry fetched onto the shelves as a collection; watch=true re-reads them nightly."),
            Map.entry("library_questions", "A file of questions onto the open questions in order; as=runs sends the first ten out as research runs."),
            Map.entry("library_bookmarks", "A browser's bookmarks: the pages fetched onto the shelves as a collection; folder=… takes one folder, watch=true re-reads them nightly."),
            Map.entry("library_settings", "The library's settings: op=list shows every setting and its value (a key only as set or not); op=set name=… value=… changes one, after the person said so in this conversation; a blank value unsets it."),
            Map.entry("library_bridges", "Discovery by combination: pairs of areas no source read together, joined by terms both use. op=run from an area (dry=true only shows the pairs), op=list the proposals, op=accept files the run that tests one, op=dismiss drops it, op=measure the tally."),
            Map.entry("library_who", "Family history: which of the people the web shows under a relative's name IS the relative. op=list who waits, op=show one person's numbered entries, op=answer the person's word (is=\"1\" or \"1,3\", none=true, later=true), op=tell what they say of the relative, op=find looks one person up."),
            Map.entry("library_meeting", "A meeting transcript: shelved with its decisions, the questions raised filed, the claims returned to check with who said them; verify=true files the checking run."));

    /** Every tool a chat can be given, the MCP schemas described in the chat's words. */
    static ArrayNode tools() { return tools(allToolNames()); }

    /** The tools this library's chat is given. */
    static ArrayNode tools(LibraryStore store) { return tools(toolNames(store)); }

    /**
     * The MCP schemas of these tools, described in the chat's words. The patron is left out, the chat fills it in; so is the field of a
     * research run: only the person chooses a field, by answering the library's own question.
     */
    static ArrayNode tools(List<String> names) {
        ArrayNode out = M.createArrayNode();
        for (JsonNode t : McpServer.allTools()) {
            String name = t.path("name").asText();
            if (!names.contains(name)) continue;
            ObjectNode entry = out.addObject(); entry.put("type", "function");
            ObjectNode fn = entry.putObject("function");
            fn.put("name", name); fn.put("description", CHAT_DESCRIPTIONS.getOrDefault(name, firstSentence(t.path("description").asText(""))));
            ObjectNode params = t.path("inputSchema").deepCopy();
            JsonNode props = params.path("properties");
            if (props.isObject()) {
                ((ObjectNode) props).remove("patron");
                ((ObjectNode) props).remove("field");
                ((ObjectNode) props).remove("allow");   // the person's to give, never the model's
                var it = props.fields();
                while (it.hasNext()) {
                    var e = it.next();
                    if (e.getValue().isObject() && e.getValue().has("description")) ((ObjectNode) e.getValue()).put("description", firstSentence(e.getValue().path("description").asText()));
                }
            }
            fn.set("parameters", params);
        }
        return out;
    }

    /** The first sentence of a description: up to the first period followed by a space, or the whole when there is none. */
    static String firstSentence(String d) {
        String s = d.strip();
        Matcher m = Pattern.compile("\\.(?=\\s+[A-Z(`\"])").matcher(s);
        while (m.find()) {
            String head = s.substring(0, m.end());
            if (head.matches("(?s).*\\b(e\\.g|i\\.e|vs|etc|cf)\\.$")) continue;   // an abbreviation is not the end of the sentence
            return head;
        }
        return s;
    }

    /** The runs this turn filed waiting for the person's answer, and the field each was offered for. */
    private final List<String[]> offeredThisTurn = new ArrayList<>();

    /** Whether this turn filed a question that reads as a person asking about harming themselves: where to find help comes first in the reply. */
    private boolean helpThisTurn = false;

    /** What the person said this turn: their own request for a field's mode ("in genealogy mode") is read there, not in the model's words. */
    private String personWords = "";

    ObjectNode research(ObjectNode args) throws IOException { return research(args, ""); }

    /**
     * A research run from the chat: library_research, and every run a batch tool starts (library_questions, library_items, a check).
     * The model never chooses a field. A run is filed waiting for the person's answer to one question, asked under the reply with no as
     * the default, when it looks like the work of a field that joins only when asked: by the question, by a sentence of the person's own
     * words this turn that is about this run (asking for the mode by name, or a family word), or because the model passed that field
     * ({@code asked}) for the person. A question the person already answered in this conversation is filed as they said. Anything else is
     * filed as ordinary research, and a field the model passed that is not used is said in the result.
     */
    ObjectNode research(ObjectNode args, String asked) throws IOException {
        String question = args.path("question").asText("").strip();
        Fields.Suggestion s = Fields.suggest(store, question);
        if (s == null) s = fromPersonWords(question);
        if (s == null && !asked.isBlank()) { Profile p = Fields.enabledNamed(store, asked); if (p != null && p.joinsOnlyWhenAsked()) s = new Fields.Suggestion(p.name(), p.offer()); }
        // what answering it may need that the library leaves out by default: asked about every time, a yes never remembered
        ContentOffer.Detected detected = ContentOffer.detect(question, null);
        List<String> needs = new ArrayList<>(detected.needs());
        // a person asking about harming themselves: where to find help, first in the reply; asked whether to research it at all when the judge is sure
        if (detected.showHelp()) helpThisTurn = true;
        boolean askHelp = detected.harm() == ContentOffer.Harm.SURE;
        String said = s == null ? null : session.answered(question, s.field());
        if (s != null && "yes".equals(said)) args.put("field", s.field());
        boolean askField = s != null && said == null;
        LibraryProtocol.Way way = s != null && said != null ? new LibraryProtocol.Way("chat-yes", "", false) : LibraryProtocol.Way.QUIET;
        if (!askField && needs.isEmpty() && !askHelp) {
            ObjectNode r = protocol.research(args, way);
            if (s == null && !asked.isBlank()) r.put("note", "This run is ordinary research: the field you passed is not used. When you say how it runs, say ordinary research.");
            return r;
        }
        ObjectNode r = protocol.research(args, way, new Jobs.Offer(askField ? s.field() : null, false, needs, askHelp), System.currentTimeMillis() + OFFER_MINUTES * 60_000L);
        String job = r.path("job_id").asText();
        List<String> what = new ArrayList<>();
        if (askHelp) { session.offer(job, HELP, question); offeredThisTurn.add(new String[]{job, HELP, ""}); what.add("whether to research it at all"); }
        if (askField) { session.offer(job, s.field(), question); offeredThisTurn.add(new String[]{job, s.field(), s.offer()}); what.add("whether to use " + s.field() + " mode"); }
        if (!needs.isEmpty()) { session.offer(job, CONTENT, question); offeredThisTurn.add(new String[]{job, CONTENT, String.join(",", needs)}); what.add("what this run may read"); }
        // the question whether to research it at all starts nothing on a no: the model is told so, and says the run starts only on a yes
        r.put("note", "This run waits for the person's answer to the library's question " + String.join(", then ", what) + ", which the library adds under your reply. "
                + (askHelp ? "Say in one sentence that the library researches this question only if they say yes to its question, and ask nothing about it yourself."
                           : "Say in one sentence that the run starts as soon as they answer, and ask nothing about it yourself."));
        if (s == null && !asked.isBlank()) r.put("note", r.path("note").asText() + " The field you passed is not used.");
        return r;
    }

    /**
     * What the person's own words this turn suggest for a run. A request is a sentence, or the part of one up to where the next request
     * starts ({@link #NEXT_REQUEST}). A request that asks for a field's mode by name ("in genealogy mode") is the person's own choice: it
     * holds every run of the turn when it is a sentence of its own ("Please use genealogy mode."), and the run it names when the sentence
     * holds other requests too. A request that only looks like a field's work (a family word) holds only the run it is about, the two
     * sharing a word that names something, never every run of the turn. Null when there is none.
     */
    private Fields.Suggestion fromPersonWords(String question) {
        for (String sentence : personWords.split("(?<=[.!?。！？])\\s+|[\\r\\n;；]+")) {
            List<String> requests = new ArrayList<>();
            for (String request : NEXT_REQUEST.split(sentence)) if (!request.isBlank()) requests.add(request);
            for (String request : requests) {
                Fields.Suggestion asked = Fields.modeAskedFor(store, request);
                if (asked != null && (requests.size() == 1 || sharesAName(request, question))) return asked;
                Fields.Suggestion s = Fields.suggest(store, request);
                if (s != null && sharesAName(request, question)) return s;
            }
        }
        return null;
    }

    /**
     * Where the next request starts inside a sentence: "and then" or "and also"; a comma before "then", "also" or "separately"; "and" or a
     * comma before a word a request starts with (look, find, research, …); "and" before a question word (how, what, who, …); 、 before
     * それから, そして, また or あと. The words are the defaults of one rule: a request ends where the person starts asking for something else.
     */
    static final Pattern NEXT_REQUEST = Pattern.compile("(?iu),?\\s+and\\s+(?:then|also)\\s+|,\\s*(?:then|also|separately)\\s+"
            + "|(?:,\\s*and|\\s+and|,)\\s+(?=(?:look|find|research|search|check|tell|see|get|dig|read|learn|figure|trace|compare|list|explain|show|give)\\b)"
            + "|(?:,\\s*and|\\s+and)\\s+(?=(?:how|what|who|whom|whose|where|when|why|which|whether)\\b)"
            + "|[、，]\\s*(?=それから|そして|また|あと|次に|ついでに|それと)");

    /** Whether two texts share a word that names something: four letters or more and not a common word, or two characters of a name in kanji or katakana. */
    static boolean sharesAName(String a, String b) {
        Set<String> x = names(a);
        x.retainAll(names(b));
        return !x.isEmpty();
    }

    private static Set<String> names(String s) {
        Set<String> out = new HashSet<>(Bridges.words(s));
        Matcher m = Pattern.compile("[\\p{IsHan}\\p{IsKatakana}]{2,}").matcher(s);
        while (m.find()) { String run = m.group(); for (int i = 0; i + 2 <= run.length(); i++) out.add(run.substring(i, i + 2)); }
        return out;
    }

    /** One tool call, as the person: the patron rides in, the result comes back as text the model reads (cut when long). */
    String call(String name, JsonNode argsIn) {
        ObjectNode args = argsIn != null && argsIn.isObject() ? ((ObjectNode) argsIn).deepCopy() : M.createObjectNode();
        args.set("patron", patronNode());
        String asked = args.path("field").asText("").strip();
        args.remove("field");   // a field is the person's choice, never the model's: a field the model passes is put to the person as a question
        args.remove("allow");   // what the library leaves out by default is let in only by the person's own yes, never by the model
        try {
            JsonNode r = switch (name) {
                case "library_ask" -> protocol.ask(args);
                case "library_search" -> protocol.search(args);
                case "library_get" -> protocol.get(args);
                case "library_research" -> research(args, asked);
                case "library_job" -> protocol.job(args);
                case "library_inbox" -> protocol.inbox(args);
                case "library_frontier" -> protocol.frontier(args);
                case "library_map" -> protocol.map(args);
                case "library_changes" -> protocol.changes(args);
                case "library_submit" -> protocol.submit(args);
                case "library_add" -> protocol.add(args);
                case "library_absorb" -> protocol.absorb(args);
                case "library_survey" -> protocol.survey(args);
                case "library_items" -> protocol.items(args);
                case "library_holdings" -> protocol.holdings(args);
                case "library_db" -> protocol.db(args);
                case "library_check" -> protocol.check(args);
                case "library_reading" -> protocol.reading(args);
                case "library_questions" -> protocol.questions(args);
                case "library_bookmarks" -> protocol.bookmarks(args);
                case "library_settings" -> protocol.settings(args);
                case "library_meeting" -> protocol.meeting(args);
                case "library_who" -> protocol.who(args);
                case "library_bridges" -> protocol.bridges(args);
                case "library_sharpen" -> protocol.sharpen(args);
                case "library_status" -> protocol.status(args);
                default -> null;
            };
            if (r == null) return "ERROR: no tool named " + name + ". The tools are: " + String.join(", ", toolNames(store));
            if (r.path("declined").isTextual() && !r.path("declined").asText().isBlank()) partlyDeclined.add(r.path("declined").asText());   // said under the reply
            String text = trimResult(r).toString();
            return text.length() <= RESULT_CAP + 400 ? text : "{\"note\":\"this result was " + text.length() + " characters; only the start is shown — ask for one entry, or one section of it\"} " + text.substring(0, RESULT_CAP);
        } catch (ProtocolError e) {
            return "ERROR: " + e.getMessage();
        } catch (Declined d) {
            declinedThisTurn = d;   // the turn ends with the statement; the chat's model is not asked to carry on around it
            return "DECLINED: " + d.statement();
        } catch (Exception e) {
            return "ERROR: " + name + " failed: " + e.getMessage();
        }
    }

    /** The decline of this turn, the chat's own or a tool's model's; null when nothing was declined. */
    private Declined declinedThisTurn = null;
    /** What the decline judge was not sure of during this turn: written on the turn's line of {@code catalog/chat-turns.jsonl}. */
    private final List<ObjectNode> unsureThisTurn = new ArrayList<>();
    /** The statements of this turn's tools that did their own part while the model declined the rest (the claims of an absorbed text). */
    private final Set<String> partlyDeclined = new LinkedHashSet<>();

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
    /** Entry ids the reply cites in brackets — F-…, I-…, A-…, or a raw file name — that appear in no tool result. */
    static final Pattern CITED_ID = Pattern.compile("\\[((?:[FIA]-\\d{3,5}-[A-Za-z0-9_.…-]+)|(?:\\d{4}-\\d{2}-\\d{2}-[0-9a-f]{6,12}\\.md))\\]");
    static List<String> idsUnbacked(String reply, String evidence) {
        List<String> out = new ArrayList<>();
        Matcher m = CITED_ID.matcher(reply == null ? "" : reply);
        while (m.find()) {
            String id = m.group(1);
            String stem = id.endsWith("…") ? id.substring(0, id.length() - 1) : id;   // a cut-short id the tool result printed with an ellipsis
            if (!evidence.contains(stem) && !out.contains(id)) out.add(id);
        }
        return out;
    }

    void record(String words, List<String> toolsCalled, int unbacked, long ms) { record(words, toolsCalled, unbacked, ms, false); }

    /** {@code declined}: the model declined the turn, or a step a tool asked of it. */
    void record(String words, List<String> toolsCalled, int unbacked, long ms, boolean declined) {
        try {
            Path f = store.root().resolve("catalog").resolve("chat-turns.jsonl");
            Files.createDirectories(f.getParent());
            ObjectNode o = M.createObjectNode();
            o.put("at", Instant.now().toString()); o.put("session", session.id); o.put("patron", patron.did());
            o.put("words", Acquisitions.compress(words, 160)); ArrayNode t = o.putArray("tools"); for (String n : toolsCalled) t.add(n);
            o.put("unbacked", unbacked); o.put("ms", ms);
            if (declined) o.put("declined", true);
            if (!unsureThisTurn.isEmpty()) { ArrayNode u = o.putArray("decline_unsure"); unsureThisTurn.forEach(u::add); }   // replies the judge was not sure were declines
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

        /**
         * Where this caller's conversations are kept. A conversation belongs to whoever had it. The owner's, and those of anyone the
         * owner lets write, are the chat folder itself, as they always were. Someone who may only read has a folder of their own, so
         * they never open the owner's conversations and the owner's list never fills with theirs. A reader nobody can tell apart (a
         * browser that has not signed in, a caller with no name) has none: null.
         */
        static Path dir(LibraryStore store, Patrons.Patron patron) {
            if (Patrons.mayWrite(store, patron)) return dir(store);
            if (patron == null || patron.anonymous() || patron.web()) return null;
            return dir(store).resolve("readers").resolve(Patrons.sha256(patron.did()).substring(0, 16));
        }

        /** A new session, named by the moment it began. */
        public static Session open(LibraryStore store) throws IOException { return open(dir(store)); }

        /** A new session in this caller's own folder, or null for a reader who has none. */
        public static Session open(LibraryStore store, Patrons.Patron patron) throws IOException {
            Path d = dir(store, patron);
            return d == null ? null : open(d);
        }

        private static Session open(Path dir) throws IOException {
            Files.createDirectories(dir);
            String id = "C-" + LocalDateTime.now().withNano(0).toString().replace(':', '-').replace('T', '-');
            Path f = dir.resolve(id + ".jsonl");
            int n = 0; while (Files.exists(f)) f = dir.resolve(id + "-" + (++n) + ".jsonl");
            return new Session(f.getFileName().toString().replace(".jsonl", ""), f);
        }

        /** An earlier session by id (a prefix will do when it is unique), or null. */
        public static Session resume(LibraryStore store, String id) throws IOException { return resume(dir(store), id); }

        /** One of this caller's own earlier sessions, or null: a reader never resumes a conversation that is not theirs. */
        public static Session resume(LibraryStore store, String id, Patrons.Patron patron) throws IOException {
            Path d = dir(store, patron);
            return d == null ? null : resume(d, id);
        }

        private static Session resume(Path dir, String id) throws IOException {
            if (id == null || !Files.isDirectory(dir)) return null;
            Path hit = null; int hits = 0;
            try (var l = Files.list(dir)) {
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
        public static Session latest(LibraryStore store) throws IOException { return latest(dir(store)); }

        /** This caller's own latest session, or a new one in their folder; null for a reader who has no folder. */
        public static Session latest(LibraryStore store, Patrons.Patron patron) throws IOException {
            Path d = dir(store, patron);
            return d == null ? null : latest(d);
        }

        private static Session latest(Path dir) throws IOException {
            List<String> ids = list(dir);
            return ids.isEmpty() ? open(dir) : resume(dir, ids.get(0));
        }

        /** Session ids, newest first. */
        public static List<String> list(LibraryStore store) throws IOException { return list(dir(store)); }

        /** This caller's own session ids, newest first. */
        public static List<String> list(LibraryStore store, Patrons.Patron patron) throws IOException {
            Path d = dir(store, patron);
            return d == null ? List.of() : list(d);
        }

        private static List<String> list(Path dir) throws IOException {
            List<String> out = new ArrayList<>();
            if (!Files.isDirectory(dir)) return out;
            try (var l = Files.list(dir)) { l.filter(p -> p.getFileName().toString().endsWith(".jsonl")).map(p -> p.getFileName().toString().replace(".jsonl", "")).sorted(Comparator.reverseOrder()).forEach(out::add); }
            return out;
        }

        // ---- the research runs this conversation started: watched until each is done and the person has been told ----

        Path runsFile() { return file.resolveSibling(id + ".runs"); }

        /** Every run this conversation started, oldest first; "!" before an id means the person has been told it is done. */
        List<String> runLines() { try { return Files.exists(runsFile()) ? Files.readAllLines(runsFile(), StandardCharsets.UTF_8) : List.of(); } catch (IOException e) { return List.of(); } }

        /** Start watching a run. */
        public void watch(String jobId) throws IOException {
            for (String l : runLines()) if (l.replace("!", "").strip().equals(jobId)) return;
            if (!Files.exists(file)) { Files.createDirectories(file.getParent()); Files.writeString(file, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE); }   // a conversation that follows a run can be resumed, even before anything is said in it
            Files.writeString(runsFile(), jobId + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        /** The runs the person has not yet been told are done. */
        public List<String> watched() { List<String> out = new ArrayList<>(); for (String l : runLines()) if (!l.isBlank() && !l.startsWith("!")) out.add(l.strip()); return out; }

        /** A run finished: say so once, as the Librarian, so "show it" has something to point at; stop watching it. */
        public String told(String jobId, RunProgress.View v) throws IOException {
            String notice = v.state().equals("done") && v.report().isEmpty() && !v.declined().isEmpty()
                    ? "Research run " + jobId + " ended after " + RunProgress.elapsed(v.elapsedSeconds()) + ". " + v.declined()   // the model declined it: nothing to show
                    : v.state().equals("done")
                    ? "Research run " + jobId + " is done after " + RunProgress.elapsed(v.elapsedSeconds()) + (v.report().isEmpty() ? "." : ". Its report is [" + v.report() + "].") + " Say \"show it\" to read the answer."
                      + (v.declined().isEmpty() ? "" : " " + v.declined())
                    : "Research run " + jobId + " ended as " + v.state() + " after " + RunProgress.elapsed(v.elapsedSeconds()) + ". Say \"what happened\" and I will look.";
            ObjectNode a = M.createObjectNode(); a.put("role", "assistant"); a.put("content", notice);
            append(a);
            List<String> lines = new ArrayList<>();
            for (String l : runLines()) lines.add(l.strip().equals(jobId) ? "!" + jobId : l);
            Files.writeString(runsFile(), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            return notice;
        }

        // ---- the library's own question about a field: asked once per question, the answer kept with the conversation ----

        Path offersFile() { return file.resolveSibling(id + ".offers"); }

        private List<String[]> offerLines() {
            List<String[]> out = new ArrayList<>();
            try { if (Files.exists(offersFile())) for (String l : Files.readAllLines(offersFile(), StandardCharsets.UTF_8)) { String[] p = l.split("\t", -1); if (p.length >= 5) out.add(p); } }
            catch (IOException ignored) { }
            return out;
        }

        /** A run filed waiting for the person's answer. */
        void offer(String job, String field, String question) throws IOException { offer(job, field, question, false); }

        /** The same; {@code again}: a second run of a question that already runs, which starts only on a yes. */
        void offer(String job, String field, String question, boolean again) throws IOException {
            if (!Files.exists(file)) { Files.createDirectories(file.getParent()); Files.writeString(file, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE); }
            Files.writeString(offersFile(), "asked\t" + job + "\t" + field + "\t" + (again ? "again" : "") + "\t" + question.replaceAll("[\t\r\n]+", " ") + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        /** The person's answer for a waiting run: yes, no, or late (the wait had ended). */
        void answer(String job, String field, String said, String question) throws IOException {
            Files.writeString(offersFile(), "answered\t" + job + "\t" + field + "\t" + said + "\t" + question.replaceAll("[\t\r\n]+", " ") + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        /** The runs that wait for the person's answer: {job, field, question, "again" for a second run or ""}. */
        List<String[]> pending() {
            List<String[]> asked = new ArrayList<>();
            for (String[] p : offerLines()) {
                if (p[0].equals("asked")) asked.add(new String[]{p[1], p[2], p[4], p[3]});
                else asked.removeIf(a -> a[0].equals(p[1]) && a[1].equals(p[2]));   // one run may wait on two questions: each is answered on its own
            }
            return asked;
        }

        /**
         * What the person said about a field for this question, or a question much like it, in this conversation: "yes", "no", or null
         * when they were not asked. A late answer counts as what it said.
         */
        String answered(String question, String field) {
            Set<String> q = Frontier.terms(question);
            String said = null;
            for (String[] p : offerLines()) {
                if (!p[0].equals("answered") || !p[2].equals(field)) continue;
                if (p[4].equals(question) || Frontier.jaccard(q, Frontier.terms(p[4])) >= 0.6) said = p[3].equals("yes") ? "yes" : "no";
            }
            return said;
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
