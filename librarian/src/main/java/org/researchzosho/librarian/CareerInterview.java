package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The career interview (docs/DESIGN_CAREER.md, step 2): the reading's questions, put to the person. In a chat one at a time, the count
 * said first; on the Career page all at once, answered in any order. One interview per person, shared by the terminal chat, the browser
 * chat and the page, picked up where it stopped. Each answer is kept as a fact, so the next reading uses it; the library reads each for
 * the topics it settles, in the person's words, and for at most one follow-up question.
 */
public final class CareerInterview {
    private CareerInterview() { }

    private static final ObjectMapper M = new ObjectMapper();

    /** One question: its number ("4", or "4a" for a follow-up to 4), its words, what it is about, the facts it grew from, whose follow-up it is. */
    public record Question(String n, String text, String tag, String ids, String followupTo) {
        public boolean main() { return followupTo.isEmpty(); }
    }

    /** The interview's two closing questions, asked after the reading's own: what else to look for, and what to keep out. */
    static final String LOOK_FOR = "look for", KEEP_OUT = "keep out";
    /** The tag of a question for a topic of the minimum the reading left open: "the minimum: place". Asked before every other question. */
    static final String MINIMUM = "the minimum";
    static final String LOOK_FOR_Q = "Before the library searches, is there anything else you want it to look for? Companies you would like to work at, kinds of roles, places, anything these questions missed.";
    static final String KEEP_OUT_Q = "And what do you not want? Companies, industries, kinds of work, conditions or places you would rule out.";

    /** A topic an answer settles; {@code changes}: the answer says plainly that a topic decided by an earlier answer has changed. */
    record Topic(String answer, String dimension, String state, String want, boolean changes) { }
    record Named(String answer, String company, boolean wanted) { }
    record KeptOut(String answer, String words) { }
    record FollowUp(String answer, String question) { }
    record Settled(List<Topic> topics, List<FollowUp> followups, List<Named> companies, List<KeptOut> keepOut) { }

    private static final Pattern NUMBERED = Pattern.compile("^\\s*(\\d+)[.)]\\s+(.+)$");
    private static final Pattern TAIL = Pattern.compile("^(.*?)\\s*\\((what drives you|preference|workplace|what you want next|a change)[^)]*\\)\\s*((?:\\[[^\\]]+\\])*)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern IDS_ONLY = Pattern.compile("^(.*?)\\s*((?:\\[[^\\]]+\\])+)\\s*$");

    /** The questions at the end of a reading, in order; an older reading's "Possible wants and changes" are its questions. */
    public static List<Question> fromReading(String md) {
        List<Question> out = new ArrayList<>();
        boolean in = false;
        for (String line : md.split("\\R")) {
            if (line.startsWith("## ")) { in = line.contains("Questions for you") || line.contains("Possible wants"); continue; }
            if (!in) continue;
            Matcher m = NUMBERED.matcher(line);
            if (!m.matches()) continue;
            String body = m.group(2).strip(), text = body, tag = "", ids = "";
            Matcher t = TAIL.matcher(body);
            if (t.matches()) { text = t.group(1).strip(); tag = t.group(2).toLowerCase(Locale.ROOT); ids = t.group(3); }
            else { Matcher i = IDS_ONLY.matcher(body); if (i.matches()) { text = i.group(1).strip(); ids = i.group(2); } }
            out.add(new Question(m.group(1), text, tag, ids, ""));
        }
        return out;
    }

    static Path file(LibraryStore store, Career.Seeker s) { return s.dir(store).resolve("interview.json"); }

    static String readingKey(String reading) { return reading.lines().findFirst().orElse("") + "#" + Integer.toHexString(reading.hashCode()); }

    /**
     * The interview as kept: one interview per person, in rounds. The first round is the first reading's questions, with the two closing
     * questions last. When the reading is written again, with the answers as facts, its questions join as a new round, numbered on from
     * the last: the closing questions first, when they are not yet answered, since the search is the next thing, then the questions that
     * go deeper. Every answer stays. An interview that an earlier version set aside when the reading was written again is brought back as
     * the first round.
     */
    static ObjectNode state(LibraryStore store, Career.Seeker s) throws IOException {
        String reading = Career.readingKept(store, s), key = readingKey(reading);
        Path f = file(store, s);
        if (Files.isRegularFile(f)) {
            ObjectNode o = (ObjectNode) M.readTree(f.toFile());
            boolean changed = restore(store, s, o);
            if (!o.path("reading").asText("").equals(key)) { continueWith(o, reading, key); changed = true; }
            if (closing(o)) changed = true;
            if (minimum(store, s, o)) changed = true;
            if (changed) save(store, s, o);
            return o;
        }
        ObjectNode o = M.createObjectNode().put("reading", key).put("begun", LocalDate.now().toString()).put("held_by", "").put("open", false).put("current", "");
        o.putArray("questions"); o.putObject("answers"); o.putArray("skipped"); o.putObject("taken"); o.putArray("rounds");
        List<Question> qs = fromReading(reading);
        if (!qs.isEmpty()) { append(o, qs, M.createObjectNode(), M.createArrayNode(), M.createObjectNode(), label(reading), LocalDate.now().toString(), false); closing(o); }
        minimum(store, s, o);
        save(store, s, o);
        return o;
    }

    /** The topics of the minimum a wrong guess costs the search on: a guess there is asked like an open topic, with the guess as the question. */
    static final List<String> CONFIRMED = List.of("role", "place", "right to work");

    /**
     * The questions for the topics of the minimum the reading left open, from the wants' draft, join the interview, one each, tagged with
     * their topic, and are asked before every other question; a topic that has since been decided or answered is not asked. A guess on the
     * role, the place or the right to work joins too, as the question the guess already is: a licence the reading supposes the person holds,
     * or a town it supposes they mean, decides what the search looks for, so the person confirms it before anything is searched. Whether any joined.
     */
    static boolean minimum(LibraryStore store, Career.Seeker s, ObjectNode o) throws IOException {
        ObjectNode wants = Career.wantsKept(store, s);
        boolean changed = false;
        ArrayNode all = arr(o, "questions");
        int last = 0;
        for (JsonNode q : all) if (q.path("followup_to").asText("").isEmpty()) last = Math.max(last, number(q.path("n").asText()));
        for (JsonNode a : wants.path("ask")) {
            String topic = a.path("topic").asText(""), text = a.path("text").asText("");
            if (topic.isEmpty() || text.isEmpty() || hasTag(o, MINIMUM + ": " + topic)) continue;
            JsonNode d = Career.find(wants.path("dimensions"), topic);
            if (d != null && !d.path("state").asText("open").equals("open")) continue;
            all.addObject().put("n", String.valueOf(++last)).put("text", text).put("tag", MINIMUM + ": " + topic).put("ids", "").put("followup_to", "");
            changed = true;
        }
        for (String topic : CONFIRMED) {   // a guess on these is a question the person answers before the search runs on it
            if (hasTag(o, MINIMUM + ": " + topic)) continue;
            JsonNode d = Career.find(wants.path("dimensions"), topic);
            if (d == null || !d.path("state").asText("open").equals("leaning")) continue;
            String guess = d.path("want").asText("").strip();
            if (guess.isEmpty()) continue;
            String text = guess.endsWith("?") || guess.endsWith("？") ? guess : "Is this right: " + guess.replaceAll("[.。]$", "") + "?";
            all.addObject().put("n", String.valueOf(++last)).put("text", text).put("tag", MINIMUM + ": " + topic).put("ids", "").put("followup_to", "");
            changed = true;
        }
        return changed;
    }

    /** A round's name: "the reading of 2026-10-28 (88 facts)", from the reading's first line; "the reading" when it says nothing of the kind. */
    static String label(String readingOrKey) {
        Matcher m = Pattern.compile("Read on (\\d{4}-\\d\\d-\\d\\d) from (\\d+) facts").matcher(readingOrKey == null ? "" : readingOrKey);
        return m.find() ? "the reading of " + m.group(1) + " (" + m.group(2) + " facts)" : "the reading";
    }

    /** The reading was written again: its questions join as a new round, the closing questions first when not yet answered. */
    static void continueWith(ObjectNode o, String reading, String key) {
        append(o, fromReading(reading), M.createObjectNode(), M.createArrayNode(), M.createObjectNode(), label(reading), LocalDate.now().toString(), true);
        o.put("reading", key).put("current", "");
    }

    /**
     * A round of questions joins the interview: {@code qs} with their {@code answers}, {@code skipped} and {@code taken} as numbered where they
     * came from, renumbered on from the interview's last question, a follow-up keeping its letter. With {@code closingFirst}, the closing
     * questions not yet answered are taken out of where they were and put first in this round.
     */
    static void append(ObjectNode o, List<Question> qs, JsonNode answers, JsonNode skipped, JsonNode taken, String label, String begun, boolean closingFirst) {
        ArrayNode all = arr(o, "questions");
        ArrayNode rounds = arr(o, "rounds");
        if (closingFirst) {
            for (int i = all.size() - 1; i >= 0; i--) {
                JsonNode q = all.get(i);
                String tag = q.path("tag").asText(""), n = q.path("n").asText();
                if (!(tag.equals(LOOK_FOR) || tag.equals(KEEP_OUT)) || answered(o, n)) continue;
                all.remove(i);
                ArrayNode sk = arr(o, "skipped"); for (int k = sk.size() - 1; k >= 0; k--) if (sk.get(k).asText().equals(n)) sk.remove(k);
                for (JsonNode r : rounds) if (r.isObject() && inRound(r, n)) ((ObjectNode) r).put("count", r.path("count").asInt(1) - 1);
            }
        }
        int last = 0;
        for (JsonNode q : all) if (q.path("followup_to").asText("").isEmpty()) last = Math.max(last, number(q.path("n").asText()));
        int n = last, from = last + 1, deeper = 0;
        if (closingFirst) {
            if (!hasTag(o, LOOK_FOR)) all.addObject().put("n", String.valueOf(++n)).put("text", LOOK_FOR_Q).put("tag", LOOK_FOR).put("ids", "").put("followup_to", "");
            if (!hasTag(o, KEEP_OUT)) all.addObject().put("n", String.valueOf(++n)).put("text", KEEP_OUT_Q).put("tag", KEEP_OUT).put("ids", "").put("followup_to", "");
        }
        Map<String, String> renumbered = new LinkedHashMap<>();
        for (Question q : qs) {
            boolean closingQ = q.tag().equals(LOOK_FOR) || q.tag().equals(KEEP_OUT);
            if (closingQ && (!answers.has(q.n()) || hasTag(o, q.tag()))) continue;   // a closing question of the round as it came: kept only when it was answered there
            if (q.main()) { renumbered.put(q.n(), String.valueOf(++n)); if (!closingQ) deeper++; }
        }
        for (Question q : qs) {
            if (q.main() && !renumbered.containsKey(q.n())) continue;
            String to = q.main() ? renumbered.get(q.n()) : renumbered.getOrDefault(q.followupTo(), q.followupTo()) + q.n().replaceFirst("^\\d+", "");
            if (!q.main()) renumbered.put(q.n(), to);
            all.addObject().put("n", to).put("text", q.text()).put("tag", q.tag()).put("ids", q.ids()).put("followup_to", q.main() ? "" : renumbered.getOrDefault(q.followupTo(), q.followupTo()));
        }
        ObjectNode an = obj(o, "answers"), tk = obj(o, "taken"); ArrayNode sk = arr(o, "skipped");
        for (Map.Entry<String, String> e : renumbered.entrySet()) {
            if (answers.has(e.getKey())) an.set(e.getValue(), answers.get(e.getKey()));
            if (taken.has(e.getKey())) tk.set(e.getValue(), taken.get(e.getKey()));
        }
        for (JsonNode x : skipped) if (renumbered.containsKey(x.asText())) sk.add(renumbered.get(x.asText()));
        rounds.addObject().put("begun", begun).put("from", from).put("count", n - last).put("deeper", deeper).put("label", label);
    }

    static int number(String n) { try { return Integer.parseInt(n.replaceAll("\\D.*$", "")); } catch (NumberFormatException e) { return 0; } }
    static boolean hasTag(JsonNode o, String tag) { for (JsonNode q : o.path("questions")) if (q.path("tag").asText("").equals(tag)) return true; return false; }
    static boolean inRound(JsonNode r, String n) { int k = number(n); return k >= r.path("from").asInt() && k < r.path("from").asInt() + r.path("count").asInt(); }

    /**
     * An interview from before rounds: the interviews an earlier version set aside when the reading was written again, each kept beside it
     * as {@code interview-<begun>-<hash>.json}, come back as the first rounds when they hold answers; the interview as it is becomes the
     * latest round. True when the interview changed.
     */
    static boolean restore(LibraryStore store, Career.Seeker s, ObjectNode o) throws IOException {
        if (o.has("rounds")) return false;
        List<Path> archives = new ArrayList<>();
        try (Stream<Path> fs = Files.list(s.dir(store))) { fs.filter(f -> f.getFileName().toString().matches("interview-.*\\.json")).sorted().forEach(archives::add); }
        ObjectNode merged = null;
        for (Path a : archives) {
            JsonNode old = M.readTree(a.toFile());
            if (!old.isObject() || old.path("answers").isEmpty()) continue;
            if (merged == null) {
                merged = M.createObjectNode().put("begun", old.path("begun").asText(""));
                merged.putArray("questions"); merged.putObject("answers"); merged.putArray("skipped"); merged.putObject("taken"); merged.putArray("rounds");
            }
            append(merged, questions(old), old.path("answers"), old.path("skipped"), old.path("taken"), label(old.path("reading").asText("")), old.path("begun").asText(""), !merged.path("questions").isEmpty());
        }
        if (merged == null) {   // nothing set aside: this interview is its own first round
            ArrayNode rounds = o.putArray("rounds");
            int mains = mains(o).size();
            if (mains > 0) rounds.addObject().put("begun", o.path("begun").asText("")).put("from", 1).put("count", mains).put("deeper", mains - (hasTag(o, LOOK_FOR) ? 1 : 0) - (hasTag(o, KEEP_OUT) ? 1 : 0)).put("label", label(o.path("reading").asText("")));
            return true;
        }
        append(merged, questions(o), o.path("answers"), o.path("skipped"), o.path("taken"), label(o.path("reading").asText("")), o.path("begun").asText(""), true);
        o.set("questions", merged.get("questions")); o.set("answers", merged.get("answers")); o.set("skipped", merged.get("skipped")); o.set("taken", merged.get("taken")); o.set("rounds", merged.get("rounds"));
        o.put("begun", merged.path("begun").asText("")).put("current", "");
        ArrayNode from = o.putArray("restored_from"); for (Path a : archives) from.add(a.getFileName().toString());
        return true;
    }

    /** Adds the two closing questions after the last of the reading's own, when they are not there; true when it added them. */
    static boolean closing(ObjectNode o) {
        ArrayNode qs = arr(o, "questions");
        int last = 0;
        for (JsonNode q : qs) {
            if (q.path("tag").asText("").equals(LOOK_FOR)) return false;
            try { if (q.path("followup_to").asText("").isEmpty()) last = Math.max(last, Integer.parseInt(q.path("n").asText())); } catch (NumberFormatException ignored) { }
        }
        if (last == 0) return false;
        qs.addObject().put("n", String.valueOf(last + 1)).put("text", LOOK_FOR_Q).put("tag", LOOK_FOR).put("ids", "").put("followup_to", "");
        qs.addObject().put("n", String.valueOf(last + 2)).put("text", KEEP_OUT_Q).put("tag", KEEP_OUT).put("ids", "").put("followup_to", "");
        ArrayNode rounds = arr(o, "rounds");
        if (!rounds.isEmpty()) ((ObjectNode) rounds.get(rounds.size() - 1)).put("count", rounds.get(rounds.size() - 1).path("count").asInt(0) + 2);
        return true;
    }

    /** The rounds of the interview: when it began, its first question's number, how many questions, how many of them go deeper, and the reading it came from. */
    public record Round(String begun, int from, int count, int deeper, String label) { }

    public static List<Round> rounds(JsonNode o) {
        List<Round> out = new ArrayList<>();
        for (JsonNode r : o.path("rounds")) out.add(new Round(r.path("begun").asText(""), r.path("from").asInt(), r.path("count").asInt(), r.path("deeper").asInt(), r.path("label").asText("")));
        return out;
    }

    static void save(LibraryStore store, Career.Seeker s, ObjectNode o) throws IOException { M.writerWithDefaultPrettyPrinter().writeValue(file(store, s).toFile(), o); }

    static ObjectNode obj(ObjectNode o, String key) { JsonNode n = o.get(key); return n != null && n.isObject() ? (ObjectNode) n : o.putObject(key); }
    static ArrayNode arr(ObjectNode o, String key) { JsonNode n = o.get(key); return n != null && n.isArray() ? (ArrayNode) n : o.putArray(key); }

    public static List<Question> questions(JsonNode o) {
        List<Question> out = new ArrayList<>();
        for (JsonNode q : o.path("questions")) out.add(new Question(q.path("n").asText(), q.path("text").asText(), q.path("tag").asText(""), q.path("ids").asText(""), q.path("followup_to").asText("")));
        return out;
    }
    static List<Question> mains(JsonNode o) { List<Question> out = new ArrayList<>(); for (Question q : questions(o)) if (q.main()) out.add(q); return out; }
    static Question byN(JsonNode o, String n) { for (Question q : questions(o)) if (q.n().equals(n)) return q; return null; }
    static boolean answered(JsonNode o, String n) { return o.path("answers").has(n); }
    static boolean skipped(JsonNode o, String n) { for (JsonNode x : o.path("skipped")) if (x.asText().equals(n)) return true; return false; }
    static int answeredMains(JsonNode o) { int k = 0; for (Question q : mains(o)) if (answered(o, q.n())) k++; return k; }
    /** The next question to ask: the first, in order, neither answered nor passed over; null when none is left. A follow-up stands right after its question. */
    /** The next question: one for the minimum first, whatever its number, then the rest in order. */
    static Question next(JsonNode o) {
        for (Question q : questions(o)) if (q.tag().startsWith(MINIMUM) && !answered(o, q.n()) && !skipped(o, q.n())) return q;
        for (Question q : questions(o)) if (!answered(o, q.n()) && !skipped(o, q.n())) return q;
        return null;
    }

    static String ask(JsonNode o, Question q) {
        return q.main() ? "Question " + q.n() + " of " + mains(o).size() + ": " + q.text() : "A follow-up to question " + q.followupTo() + ": " + q.text();
    }

    /** The answer given to a question, or "". */
    public static String answerOf(JsonNode o, String n) { return o.path("answers").path(n).path("text").asText(""); }
    /** What the library took from the answer to a question, or "". */
    public static String takenOf(JsonNode o, String n) { return o.path("taken").path(n).asText(""); }

    /** The person whose interview this chat holds, or null. Read without beginning an interview for anyone. */
    public static Career.Seeker holder(LibraryStore store, String chat) throws IOException {
        for (Career.Seeker s : Career.seekers(store)) {
            Path f = file(store, s);
            if (!Files.isRegularFile(f)) continue;
            JsonNode o = M.readTree(f.toFile());
            if (o.path("open").asBoolean(false) && o.path("held_by").asText("").equals(chat)) return s;
        }
        return null;
    }

    /** {@code /career interview} in a chat: how many questions, how to pass or stop, and the first not yet answered. The chat now holds the interview. */
    public static String start(LibraryStore store, Career.Seeker s, String chat) throws IOException {
        if (Career.readingKept(store, s).isBlank()) return "There is no reading yet, and the questions come from it. Type /career reading first; it takes a minute or two.";
        ObjectNode o = state(store, s);
        List<Question> mains = mains(o);
        if (mains.isEmpty()) return "The reading has no questions to ask. Type /career reading to have the library read your working life again.";
        Question q = next(o);
        if (q == null) { o.put("open", false); save(store, s, o); return "Every question has an answer already. " + ending(store, s, o); }
        o.put("open", true).put("held_by", chat).put("current", q.n());
        save(store, s, o);
        int done = answeredMains(o);
        List<Round> rounds = rounds(o);
        Round latest = rounds.size() > 1 ? rounds.get(rounds.size() - 1) : null;
        boolean newRound = latest != null && mains.stream().noneMatch(m -> number(m.n()) >= latest.from() && (answered(o, m.n()) || skipped(o, m.n())));
        String head;
        if (done == 0 && o.path("skipped").isEmpty())
            head = "The library has " + mains.size() + " questions for you, from its reading of your working life. Answer each in your own words. Type skip to pass on one, or stop to finish another time; /career interview picks up where you stopped.";
        else if (newRound) {
            int closingN = latest.count() - latest.deeper();
            head = "You answered " + done + (done == 1 ? " question" : " questions") + " before. The library wrote its reading again with your answers, and has " + latest.count() + " more: "
                    + (closingN > 0 ? closingN + " about the search, then " : "") + latest.deeper() + " that go deeper into what you said. Answer any, skip any, or stop when you like; /career search needs no more than you have given.";
        } else head = "Picking up where you stopped: " + done + " of " + mains.size() + " questions answered. Type skip to pass on one, or stop to finish another time.";
        return head + "\n\n" + ask(o, q);
    }

    /** A plain line in the chat that holds the interview: an answer, skip or stop. Null when this chat holds no interview. */
    public static String take(LibraryStore store, String chat, String line) throws IOException {
        Career.Seeker s = holder(store, chat);
        if (s == null) return null;
        ObjectNode o = state(store, s);
        if (!o.path("open").asBoolean(false)) return null;   // the reading was written again since: the old interview was closed with it
        Question q = byN(o, o.path("current").asText(""));
        if (q == null || answered(o, q.n())) q = next(o);
        if (q == null) { o.put("open", false); save(store, s, o); return ending(store, s, o); }
        String w = line.strip(), lw = w.toLowerCase(Locale.ROOT);
        if (lw.equals("stop")) {
            o.put("open", false); save(store, s, o);
            return "Stopped. " + answeredMains(o) + " of " + mains(o).size() + " questions are answered; /career interview picks up at " + (q.main() ? "question " + q.n() : "the follow-up to question " + q.followupTo()) + ".";
        }
        StringBuilder b = new StringBuilder();
        if (lw.equals("skip")) { arr(o, "skipped").add(q.n()); save(store, s, o); Career.log(store, s, "You passed over question " + q.n() + " of the interview."); b.append("Passed over."); }
        else { Map<Question, String> one = new LinkedHashMap<>(); one.put(q, w); b.append(String.join("\n", answer(store, s, o, one))); }
        o = state(store, s);
        Question nx = next(o);
        if (nx == null) { o.put("open", false).put("current", ""); save(store, s, o); return b + "\n\n" + ending(store, s, o); }
        o.put("current", nx.n()); save(store, s, o);
        return b + "\n\n" + ask(o, nx);
    }

    /**
     * The topics an answer settles, set on what the person wants, each as a line for the reply. A topic an earlier answer decided keeps its
     * words: a later answer replaces them only when it says plainly that the want has changed, so an answer about how the person hired
     * never overwrites what they said they want to be.
     */
    static List<String> settled(LibraryStore store, Career.Seeker s, Question q, Settled st) throws IOException {
        List<String> set = new ArrayList<>();
        for (Topic t : st.topics()) {
            if (!t.answer().equals(q.n())) continue;
            ObjectNode was = Career.find(Career.wantsKept(store, s).path("dimensions"), t.dimension());
            if (was != null && was.path("by").asText("").equals("answer") && was.path("state").asText("").equals("decided") && !t.changes()) continue;
            try { set.add(Career.line(Career.setWantFrom(store, s, t.dimension(), t.state(), t.want(), "answer", "your answer to question " + q.n()), false)); }
            catch (IllegalArgumentException ignored) { }   // a topic the model named that is not one: left out
        }
        return set;
    }

    /**
     * Every answer read again for what it settles, with the topics set from answers cleared first, so a better reading, or a better model,
     * replaces what an earlier one took; the facts, the follow-ups and the search profile stay as they are. Then what the person wants is
     * drafted again around the topics the answers set. A sentence on what was set.
     */
    public static String resettle(LibraryStore store, Career.Seeker s) throws IOException {
        ObjectNode o = state(store, s);
        Map<Question, String> given = new LinkedHashMap<>();
        for (Question q : questions(o)) { String a = answerOf(o, q.n()); if (!a.isEmpty()) given.put(q, a); }
        if (given.isEmpty()) return "There are no answers to read again yet.";
        Career.clearAnswerWants(store, s);
        o.putObject("taken");
        Settled st = settle(store, s, given);
        int topics = 0;
        for (Question q : given.keySet()) {
            List<String> set = settled(store, s, q, st);
            topics += set.size();
            if (!set.isEmpty()) obj(o, "taken").put(q.n(), set.size() == 1 ? "Taken: " + set.get(0) : "Taken:\n- " + String.join("\n- ", set));
        }
        save(store, s, o);
        Career.wants(store, s);
        int decided = 0; for (JsonNode e : Career.wantsKept(store, s).path("dimensions")) if (e.path("by").asText("").equals("answer") && e.path("state").asText("").equals("decided")) decided++;
        String said = "The library read your " + given.size() + " answers again: they set " + topics + (topics == 1 ? " topic" : " topics") + ", " + decided + " of them decided.";
        Career.log(store, s, said);
        return said;
    }

    /**
     * What the person told the library in their own words, read at once for what it settles, as an answer is: the topics it decides or leans to,
     * set "by told" (a plain word of theirs replaces a guess, and a decided topic of theirs unless the words only lean), the companies it names,
     * and what it rules out, kept out. The next search starts from them: the cut is the library's and its basis has moved. A sentence on what
     * was set; "" when the words settle nothing, and when no model answers the words are only kept.
     */
    public static String settleTold(LibraryStore store, Career.Seeker s, String words) throws IOException {
        if (words == null || words.isBlank()) return "";
        Question q = new Question("T", LOOK_FOR_Q + " " + KEEP_OUT_Q, "told", "", "");
        Settled st;
        try {
            if (Career.DRIVE.get() == null) { Career.log(store, s, "No model answers, so what you said is only kept; the library reads it for what it decides when a model answers again."); return ""; }
            st = settle(store, s, Map.of(q, words));
        } catch (RuntimeException e) { Career.log(store, s, "The library could not read what you said for what it decides: " + e.getMessage() + ". Your words are kept."); return ""; }
        List<String> set = new ArrayList<>();
        for (Topic t : st.topics()) {
            if (!t.answer().equals("T")) continue;
            ObjectNode was = Career.find(Career.wantsKept(store, s).path("dimensions"), t.dimension());
            if (was != null && was.path("state").asText("").equals("decided") && t.state().equals("leaning")) continue;   // a lean never undoes a decision
            try { set.add(Career.line(Career.setWantFrom(store, s, t.dimension(), t.state(), t.want(), "told", "what you said on " + LocalDate.now()), false)); }
            catch (IllegalArgumentException ignored) { }
        }
        List<String> named = new ArrayList<>(), out = new ArrayList<>();
        for (Named c : st.companies()) if (c.answer().equals("T")) { if (c.wanted()) { CareerSearch.addCompany(store, s, c.company(), "what you said"); named.add(c.company()); } else { CareerSearch.addKeepOut(store, s, c.company(), "what you said"); out.add(c.company()); } }
        for (KeptOut k : st.keepOut()) if (k.answer().equals("T")) { CareerSearch.addKeepOut(store, s, k.words(), "what you said"); out.add(k.words()); }
        if (set.isEmpty() && named.isEmpty() && out.isEmpty()) { Career.log(store, s, "What you said settles no topic of what you want; it is kept with your words."); return ""; }
        StringBuilder said = new StringBuilder("Read from your words: ");
        if (!set.isEmpty()) said.append(String.join("; ", set)).append(". ");
        if (!named.isEmpty()) said.append("Companies to look at: ").append(Career.and(named)).append(". ");
        if (!out.isEmpty()) said.append("Kept out: ").append(Career.and(out)).append(". ");
        said.append("The next search starts from this");
        said.append(CareerCut.kept(store, s) == null ? "." : "; the openings already found are sorted by what you wanted before, and the openings page can sort them again with no new search.");
        Career.log(store, s, said.toString());
        return said.toString();
    }

    /** The page's answers, saved together: those given or changed are read in one pass. A note of what was taken, for the page. */
    public static String saveAnswers(LibraryStore store, Career.Seeker s, Map<String, String> byN) throws IOException {
        ObjectNode o = state(store, s);
        Map<Question, String> given = new LinkedHashMap<>();
        for (Question q : questions(o)) {
            String a = byN.getOrDefault(q.n(), "").strip();
            if (!a.isEmpty() && !a.equals(answerOf(o, q.n()))) given.put(q, a);
        }
        if (given.isEmpty()) return "";
        return String.join("\n", answer(store, s, o, given));
    }

    /** Answers kept, as facts and in the person's words; the topics they settle set, in their words; follow-ups placed after their questions. */
    static List<String> answer(LibraryStore store, Career.Seeker s, ObjectNode o, Map<Question, String> given) throws IOException {
        String day = LocalDate.now().toString(), begun = o.path("begun").asText(day);
        for (Map.Entry<Question, String> e : given.entrySet()) {
            Question q = e.getKey(); String a = e.getValue().strip();
            obj(o, "answers").putObject(q.n()).put("text", a).put("at", day);
            ArrayNode sk = arr(o, "skipped"); for (int i = sk.size() - 1; i >= 0; i--) if (sk.get(i).asText().equals(q.n())) sk.remove(i);
            Career.answerFact(store, s, "your answer to question " + q.n() + " of the interview of " + begun, "Asked: " + q.text() + " You said: " + a);
            Career.told(store, s, "Answering question " + q.n() + ": " + q.text() + "\n\n" + a);
            Career.log(store, s, "You answered question " + q.n() + " of the interview.");
        }
        save(store, s, o);
        Settled st = settle(store, s, given);
        List<String> out = new ArrayList<>();
        ArrayNode qs = arr(o, "questions");
        for (Map.Entry<Question, String> e : given.entrySet()) {
            Question q = e.getKey();
            List<String> set = settled(store, s, q, st);
            for (FollowUp f : st.followups()) {
                if (!f.answer().equals(q.n())) continue;
                int at = -1, k = 0; String last = q.n();
                for (int i = 0; i < qs.size(); i++) { String n = qs.get(i).path("n").asText(); if (n.equals(q.n()) || qs.get(i).path("followup_to").asText().equals(q.n())) { at = i; last = n; k += qs.get(i).path("followup_to").asText().equals(q.n()) ? 1 : 0; } }
                if (at < 0 || k >= 2) continue;   // at most two follow-ups to one question, over all its answers
                String n = q.n() + (char) ('a' + k);
                ObjectNode fu = M.createObjectNode().put("n", n).put("text", f.question()).put("tag", "follow-up").put("ids", "").put("followup_to", q.n());
                qs.insert(at + 1, fu);
            }
            List<String> looks = new ArrayList<>(), outs = new ArrayList<>();
            for (Named c : st.companies()) {
                if (!c.answer().equals(q.n())) continue;
                if (c.wanted()) { if (CareerSearch.addCompany(store, s, c.company(), "your answer to question " + q.n())) looks.add(c.company()); }
                else if (CareerSearch.addKeepOut(store, s, c.company(), "your answer to question " + q.n())) outs.add(c.company());
            }
            for (KeptOut k : st.keepOut()) if (k.answer().equals(q.n()) && CareerSearch.addKeepOut(store, s, k.words(), "your answer to question " + q.n())) outs.add(k.words());
            if (!looks.isEmpty()) set.add("Companies the search will look at: " + Career.and(looks) + ".");
            if (!outs.isEmpty()) set.add("The search will keep out: " + Career.and(outs) + ".");
            String said = set.isEmpty() ? "Kept your answer. It settles no topic by itself, and the next reading will use it."
                    : set.size() == 1 ? "Taken: " + set.get(0) : "Taken:\n- " + String.join("\n- ", set);
            obj(o, "taken").put(q.n(), said);
            out.add(given.size() > 1 ? "Question " + q.n() + ". " + said : said);
        }
        save(store, s, o);
        return out;
    }

    /** The model reads the answers for the topics they settle, in the person's words, and for a follow-up; its own answer is kept beside the interview. */
    static Settled settle(LibraryStore store, Career.Seeker s, Map<Question, String> given) throws IOException {
        StringBuilder qa = new StringBuilder();
        for (Map.Entry<Question, String> e : given.entrySet()) qa.append("Question ").append(e.getKey().n()).append(e.getKey().tag().startsWith(MINIMUM) ? " (about the topic " + e.getKey().tag().substring(MINIMUM.length() + 2) + ")" : "").append(": ").append(e.getKey().text()).append("\nAnswer: ").append(e.getValue().strip().replace("\n", " ")).append("\n\n");
        List<String> topics = new ArrayList<>(); for (String d : Career.DIMENSIONS) topics.add(d + " (" + Career.label(d) + ")");
        StringBuilder standing = new StringBuilder();
        for (JsonNode e : Career.wantsKept(store, s).path("dimensions"))
            if (e.path("by").asText("").equals("answer") && e.path("state").asText("").equals("decided")) standing.append("- ").append(e.path("dimension").asText()).append(": ").append(e.path("want").asText()).append(" (from ").append(e.path("from").asText("an earlier answer")).append(")\n");
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content",
                "A person looking for work answered questions from the library's reading of their working life. Read each answer for what it settles about "
                + "the person's NEXT job, topic by topic, and for whether it opens a follow-up question worth asking.\n\n"
                + "The topics: " + String.join("; ", topics) + ".\n\n"
                + "An answer settles a topic when it says what the person wants next for that topic, in a form a search for openings can use: a role or a "
                + "title, a kind of work, an area, a kind of employer (a company, a firm, a hospital, a lab, a school, a public body, an agency, clients), a size or a "
                + "stage of company, a culture, a place (the town or region), whether they would move or commute, remote or not, an arrangement (full-time, part-time, "
                + "contract, temporary), a licence or a visa to sort out, a pace, a level of pay, a form of ownership such as founding, employed or advising, or the "
                + "authority they want, such as hiring and firing. An answer to a question marked with its topic settles that topic. One entry for each topic an answer settles: "
                + "{\"answer\": \"the question's number\", \"dimension\": \"the topic, as named before its brackets\", \"state\": \"decided\" or \"leaning\", "
                + "\"want\": \"the want\"}. The want is one short sentence that states what they want so that a stranger would understand it, in the "
                + "person's own words where the answer gives them (\"CTO, CEO, VP or SVP, with executive power\"), never the whole answer copied. For remote, the want says which: "
                + "remote only, remote or in the place, or not remote; \"remote or Boston\" is remote or in the place. Decided "
                + "when the answer says it plainly; leaning when it hedges, with words such as probably, I think or maybe.\n\n"
                + "An answer about the past, about how the person worked, hired, managed or left, about what they did not know then, or one that says all "
                + "of it, not sure, or asks a question back, settles nothing: it gets no entry. Most answers settle nothing or one topic. One entry per "
                + "topic at most over all the answers here, from the answer that states the want most plainly: an answer that names titles, numbers or "
                + "places beats one that only touches the topic, wherever each stands in the order. An answer to the question about what else the search "
                + "should look for is the person's plainest word on their role, the kind of work and where.\n\n"
                + (standing.isEmpty() ? "" : "DECIDED ALREADY, from the person's earlier answers:\n" + standing + "A topic on this list keeps its words. Give an entry for it "
                + "only when an answer says plainly that it has changed, and mark that entry \"changes\": true.\n\n")
                + "A follow-up, at most one for an answer, only when the answer opens something the questions do not already cover, open, asking what, which, why "
                + "or how: {\"answer\": \"the question's number\", \"followup\": \"the question\"}.\n\n"
                + "For an answer to a question about what else to look for, also each company it names: {\"answer\": \"the question's number\", \"company\": "
                + "\"the name\", \"wanted\": true}. For an answer to a question about what the person does not want, each company it rules out, with \"wanted\": "
                + "false, and each other thing it rules out, an industry, a kind of work, a condition or a place, in a few of the person's words: "
                + "{\"answer\": \"the question's number\", \"keep_out\": \"the words\"}.\n\n"
                + "Answer with one JSON object and nothing else: {\"topics\": [...], \"followups\": [...], \"companies\": [...], \"keep_out\": [...]}.\n\nTHE QUESTIONS AND ANSWERS\n" + qa);
        String out = Career.DRIVE.get().classify(msgs, 2500);
        Files.writeString(s.dir(store).resolve("interview-settle.txt"), "# the model's reading of the answers, " + LocalDate.now() + "\n" + (out == null ? "" : out), StandardCharsets.UTF_8);
        List<Topic> ts = new ArrayList<>(); List<FollowUp> fs = new ArrayList<>();
        JsonNode j = parseObject(out);
        for (JsonNode t : j.path("topics")) {
            String n = t.path("answer").asText("").strip(), d = t.path("dimension").asText("").strip().toLowerCase(Locale.ROOT), st = Career.state(t.path("state").asText("")), w = t.path("want").asText("").strip();
            if (!n.isEmpty() && Career.DIMENSIONS.contains(d) && (st.equals("decided") || st.equals("leaning")) && !w.isEmpty()) ts.add(new Topic(n, d, st, w, t.path("changes").asBoolean(false)));
        }
        for (JsonNode f : j.path("followups")) {
            String n = f.path("answer").asText("").strip(), q = f.path("followup").asText("").strip();
            if (!n.isEmpty() && !q.isEmpty()) fs.add(new FollowUp(n, q));
        }
        List<Named> cs = new ArrayList<>(); List<KeptOut> ko = new ArrayList<>();
        for (JsonNode c : j.path("companies")) {
            String n = c.path("answer").asText("").strip(), name = c.path("company").asText("").strip();
            if (!n.isEmpty() && !name.isEmpty()) cs.add(new Named(n, name, c.path("wanted").asBoolean(true)));
        }
        for (JsonNode k : j.path("keep_out")) {
            String n = k.path("answer").asText("").strip(), w = k.path("keep_out").asText("").strip();
            if (!n.isEmpty() && !w.isEmpty()) ko.add(new KeptOut(n, w));
        }
        return new Settled(ts, fs, cs, ko);
    }

    static JsonNode parseObject(String out) {
        if (out == null) return M.createObjectNode();
        int a = out.indexOf('{'), z = out.lastIndexOf('}');
        if (a < 0 || z <= a) return M.createObjectNode();
        try { return M.readTree(out.substring(a, z + 1)); } catch (IOException e) { return M.createObjectNode(); }
    }

    /** What the end of the interview says: how much was answered, what was set, and how to have the reading written again with the answers. */
    static String ending(LibraryStore store, Career.Seeker s, JsonNode o) throws IOException {
        int set = 0;
        for (JsonNode e : Career.wantsKept(store, s).path("dimensions")) if (e.path("by").asText("").equals("answer")) set++;
        return "That was the last question. You answered " + answeredMains(o) + " of " + mains(o).size()
                + (set > 0 ? ", and the library set " + set + (set == 1 ? " topic" : " topics") + " of what you want from your answers" : "")
                + ". To look for openings now, type /career search. To have the library read your working life again with your answers, type /career reading; it takes two minutes or so, and then asks a few more questions that go deeper, which you can skip.";
    }
}
