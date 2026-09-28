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
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.researchzosho.drive.DeclineJudge;
import org.researchzosho.drive.Declined;

/**
 * A research seat watched for a model that declines. ResearchZosho does not decide for people what they may research; the model the
 * person chose decides, and when it declines the library says so and does not try to get around it (decided 2026-09-23).
 *
 * <p>A model server that says so ({@link Declined} from the drive) is passed on with the seat named. A model that sends no signal (a
 * local llama.cpp model) declines in its words, and the judge is asked about those words only where a reply is suspect: prose with no
 * tool call where one was required (always); and, only when they carry decline wording ({@link DeclineJudge#soundsLikeDecline}), the
 * chat's own prose, a worker's {@code done} summary or a {@code write_section} text that is not recognisably the work
 * ({@link #isWork}: it cites nothing and is short; a worker's done after it noted facts is the work), a classify reply with no JSON where
 * JSON was asked for, and a prose answer that is itself the work ({@link Researcher.Drive#prose}). The writer's own {@code done} is its
 * CAVEATS, what stayed uncertain, and is never read. Every other reply goes by unread. A decline is recorded only when the judge is sure;
 * where it would end a research run or throw away work ({@link #step(String, boolean)}), sure means the typed judge's p(yes) of at least
 * {@link DeclineJudge#ENDS_BAR}. An unsure reply is not called one, and is noted ({@link Notes}): in the run's trace, in the crews log
 * for the nightly explorer, the bridges and the steps outside a run, in the chat's turn log for the chat.
 *
 * <p>The judge reads the reply beside the STEP the model was asked to do, in a few words the caller gives ({@link #step}): "list the
 * checkable factual claims in a text", "research this sub-question: …". Never the first message of the request, which is often the data
 * the step works on (a transcript that itself holds a refusal, a page, the whole ask before a worker's sub-question).
 */
public final class Declines {
    private Declines() { }

    private static final ObjectMapper M = new ObjectMapper();

    /** What the judge is told when no caller named the step. */
    static final String SOME_STEP = "do the step of the work it was asked to do";

    /**
     * A step: what the model is asked to do, whether a decline there ends a research run or throws away work already done, and whether
     * its {@code done} carries the write-up's CAVEATS (what stayed uncertain), which is never read for a decline.
     */
    record Step(String what, boolean ends, boolean caveats) { }

    private static final ThreadLocal<Step> STEP = new ThreadLocal<>();

    /** Closes a {@link #step}: the step named before it is the step again. */
    public interface Scope extends AutoCloseable { @Override void close(); }

    /**
     * Until the returned scope closes, a reply on this thread is read against {@code what}: what the model is asked to do at this step,
     * in a few words that follow "the model was asked to" ("list the checkable factual claims in a text"). Never the data it works on.
     */
    public static Scope step(String what) { Step outer = STEP.get(); return step(what, outer != null && outer.ends()); }

    /**
     * As {@link #step(String)}; {@code ends}: a decline at this step ends a research run or throws away work already done (the plan, a
     * reader's sub-question and its notes, the write-up), and the judge must clear the higher bar. A step inside keeps it.
     */
    public static Scope step(String what, boolean ends) { return scope(new Step(what == null || what.isBlank() ? SOME_STEP : what.strip(), ends, false)); }

    /** The write-up: a decline ends the run, and its {@code done} is the CAVEATS, what stayed uncertain, which is not read for a decline. */
    public static Scope writeUp(String what) { return scope(new Step(what == null || what.isBlank() ? SOME_STEP : what.strip(), true, true)); }

    private static Scope scope(Step step) {
        Step outer = STEP.get();
        STEP.set(step);
        return () -> { if (outer == null) STEP.remove(); else STEP.set(outer); };
    }

    /** The step named on this thread; unnamed, it is read at the higher bar, the side that throws nothing away. */
    static Step currentStep() { Step s = STEP.get(); return s == null ? new Step(SOME_STEP, true, false) : s; }

    /** The finishing tools whose text is the model's own account of its work, and the argument that holds it. */
    static final Map<String, String> ACCOUNT_ARGS = Map.of("done", "summary", "write_section", "text");

    /** Where a seat writes what it saw: the replies the judge was unsure of, and the declines as they pass. */
    public interface Notes {
        /** A reply the judge was not sure of: seat, p_yes, by, step, bar, reply. */
        void unsure(ObjectNode note);
        /** A decline as it passes: seat, model, how, said. */
        default void declined(ObjectNode note) { }
    }

    private static final ThreadLocal<Notes> NOTING = new ThreadLocal<>();

    /** Until the returned scope closes, the watched seats on this thread write their notes to {@code notes} (the chat's turn), not their own. */
    public static Scope noting(Notes notes) {
        Notes outer = NOTING.get();
        NOTING.set(notes);
        return () -> { if (outer == null) NOTING.remove(); else NOTING.set(outer); };
    }

    /**
     * Notes on the crews log ({@code catalog/crews.log}) under {@code name}, one line each: the nightly explorer, the bridges, and the
     * steps outside a run. {@code store} null: the library on this machine, when there is one.
     */
    public static Notes toCrewsLog(LibraryStore store, String name) {
        return note -> {
            LibraryStore s = store;
            if (s == null) { if (!Acquisitions.libraryExists()) return; s = LibraryStore.open(); }
            Crews.log(s, name, "decline_unsure: the judge was not sure whether the model declined (seat " + note.path("seat").asText() + ", step \""
                    + note.path("step").asText() + "\", p(yes) " + note.path("p_yes").asText() + " by " + note.path("by").asText() + "): " + note.path("reply").asText(), 0);
        };
    }

    /** {@code d} watched at {@code seat} ("workers", "judge", "chat"), {@code model} named in what is said, {@code judge} reading the words. */
    public static Researcher.Drive watch(Researcher.Drive d, String seat, String model, DeclineJudge judge, Notes trace) {
        if (d == null) return null;
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                ObjectNode reply;
                try { reply = d.chat(messages, tools, maxTokens, toolChoice); }
                catch (Declined x) { throw said(x.seat(seat)); }
                String words = suspect(reply, tools, toolChoice, messages);
                if (words != null) read(messages, words);
                return reply;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) {
                String out;
                try { out = d.classify(messages, maxTokens); }
                catch (Declined x) { throw said(x.seat(seat)); }
                if (out != null && !out.isBlank() && asksForJson(messages) && !hasJson(out) && DeclineJudge.soundsLikeDecline(out)) read(messages, out);
                return out;
            }
            @Override public String prose(ArrayNode messages, int maxTokens) {
                String out;
                try { out = d.prose(messages, maxTokens); }
                catch (Declined x) { throw said(x.seat(seat)); }
                if (out != null && !out.isBlank() && DeclineJudge.soundsLikeDecline(out)) read(messages, out);
                return out;
            }
            @Override public int contextWindow() { return d.contextWindow(); }
            @Override public DeclineJudge declineJudge() { return judge; }

            /** The judge on one suspect reply: a sure decline is raised, an unsure reply noted, anything else passes. */
            private void read(ArrayNode messages, String words) {
                if (judge == null) return;
                Step step = currentStep();
                DeclineJudge.Verdict v = judge.read(step.what(), words, step.ends());
                if (v.declined()) throw said(new Declined(model, words, Declined.How.WORDS).seat(seat));
                Notes notes = NOTING.get() != null ? NOTING.get() : trace;
                if (!v.sure() && notes != null) {
                    ObjectNode o = M.createObjectNode();
                    o.put("seat", seat); o.put("p_yes", Math.round(v.pYes() * 1000) / 1000.0); o.put("by", v.by());
                    o.put("step", Acquisitions.compress(step.what(), 200)); o.put("bar", step.ends() ? "ends" : "ordinary");
                    o.put("reply", Acquisitions.compress(words, 300));
                    notes.unsure(o);
                }
            }

            /** A decline goes on the trace as it passes. */
            private Declined said(Declined x) {
                Notes notes = NOTING.get() != null ? NOTING.get() : trace;
                if (notes != null) {
                    ObjectNode o = M.createObjectNode();
                    o.put("seat", x.seat()); o.put("model", x.model()); o.put("how", x.how().name().toLowerCase(Locale.ROOT));
                    o.put("said", Acquisitions.compress(x.said(), 300));
                    notes.declined(o);
                }
                return x;
            }
        };
    }

    /** The words of a reply worth asking the judge about, or null when the reply is not suspect. */
    static String suspect(ObjectNode reply, ArrayNode tools, String toolChoice) { return suspect(reply, tools, toolChoice, null); }

    /** As above, {@code messages} the request: a worker's done after it noted facts is an account of work done. */
    static String suspect(ObjectNode reply, ArrayNode tools, String toolChoice, ArrayNode messages) {
        if (reply == null) return null;
        JsonNode calls = reply.path("tool_calls");
        if (!calls.isArray() || calls.isEmpty()) {
            String content = reply.path("content").asText("").strip();
            boolean required = tools != null && !tools.isEmpty() && "required".equals(toolChoice);
            // words where a tool call was required are always read; words that are the reply itself (the chat) only when they sound like a decline
            return !content.isEmpty() && (required || DeclineJudge.soundsLikeDecline(content)) ? content : null;
        }
        boolean writer = offers(tools, "write_section") || currentStep().caveats();
        StringBuilder words = new StringBuilder();
        for (JsonNode c : calls) {
            String name = c.path("function").path("name").asText("");
            String arg = ACCOUNT_ARGS.get(name);
            if (arg == null) continue;
            if (name.equals("done") && (writer || called(messages, "note"))) continue;   // the writer's CAVEATS; a worker's account after its notes
            String text = Researcher.parseArgs(c.path("function").path("arguments")).path(arg).asText("").strip();
            if (text.isEmpty() || isWork(text) || !DeclineJudge.soundsLikeDecline(text)) continue;
            words.append(words.isEmpty() ? "" : "\n\n").append(text);
        }
        return words.isEmpty() ? null : words.toString();
    }

    /** Past this length, a section or summary is the work written out: a decline is a sentence or two. */
    static final int WORK_CHARS = 800;
    /** A citation: a bracketed number ([3], [S2], [1, 4]), a web address, an entry id. */
    static final Pattern CITES = Pattern.compile("\\[[^\\]\\n]{0,12}\\d[^\\]\\n]{0,12}\\]|https?://|\\b[FI]-\\d{3,}");

    /** Text that is recognisably the work: it cites a source, or it runs to a length no decline does. It is never asked about. */
    static boolean isWork(String text) { return text != null && (text.length() >= WORK_CHARS || CITES.matcher(text).find()); }

    /** Whether the request offered a tool of this name. */
    private static boolean offers(ArrayNode tools, String name) {
        if (tools != null) for (JsonNode t : tools) if (name.equals(t.path("function").path("name").asText())) return true;
        return false;
    }

    /** Whether the model already called a tool of this name in this request's history. */
    private static boolean called(ArrayNode messages, String name) {
        if (messages != null) for (JsonNode m : messages) for (JsonNode c : m.path("tool_calls")) if (name.equals(c.path("function").path("name").asText())) return true;
        return false;
    }

    // ---- what a housekeeping crew was declined, so the same unchanged input is not sent again ----

    /** The ledger: one line per decline, date, crew, what it was about, the hash of what was sent, the model. */
    static Path ledger(LibraryStore store) { return store.root().resolve("catalog").resolve("declined.tsv"); }

    /**
     * Whether {@code crew} was declined for {@code key} (a report's id, a subject, a claim's id, a document's part) when its input had
     * {@code hash}. The crew then does not send the same input again; an input that changed has another hash and is asked about.
     */
    static boolean declinedBefore(LibraryStore store, String crew, String key, String hash) {
        try {
            Path f = ledger(store);
            if (!Files.exists(f)) return false;
            String want = "\t" + crew + "\t" + key.replaceAll("\\s+", " ") + "\t" + hash + "\t";
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) if (line.contains(want)) return true;
        } catch (IOException ignored) { }
        return false;
    }

    /** {@code crew} was declined for {@code key} with this input: written so that the same input is not sent again. */
    static void rememberDeclined(LibraryStore store, String crew, String key, String hash, Declined d) {
        try {
            Path f = ledger(store);
            Files.createDirectories(f.getParent());
            Files.writeString(f, LocalDate.now() + "\t" + crew + "\t" + key.replaceAll("\\s+", " ") + "\t" + hash + "\t" + d.model().replaceAll("\\s+", " ") + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // the ledger saves a request; without it the crew only asks again
        }
    }

    /** What a crew says of an input it leaves because the model declined it before and it has not changed. */
    static String notAskedAgain(String what) {
        return "the model declined " + what + " before, and it has not changed since, so it was not asked again";
    }

    /** Whether the request asked for JSON: its last message from the person says so. */
    static boolean asksForJson(ArrayNode messages) {
        for (int i = messages == null ? -1 : messages.size() - 1; i >= 0; i--) {
            JsonNode m = messages.get(i);
            if ("user".equals(m.path("role").asText())) return text(m.path("content")).contains("JSON");
        }
        return false;
    }

    /**
     * Whether a reply holds JSON: an object or an array that parses as one. A bracket alone is not JSON: "I can't help with [that]" and
     * "[Note: I cannot do this.]" are words, and are read for a decline.
     */
    public static boolean hasJson(String reply) {
        if (reply == null) return false;
        int a = reply.indexOf('{'), b = reply.lastIndexOf('}'), c = reply.indexOf('['), e = reply.lastIndexOf(']');
        return (a >= 0 && b > a && parses(reply.substring(a, b + 1), true)) || (c >= 0 && e > c && parses(reply.substring(c, e + 1), false));
    }

    private static boolean parses(String json, boolean object) {
        try { JsonNode n = M.readTree(json); return object ? n.isObject() : n.isArray(); }
        catch (Exception notJson) { return false; }
    }

    private static String text(JsonNode content) {
        if (content.isTextual()) return content.asText();
        StringBuilder b = new StringBuilder();
        for (JsonNode part : content) if ("text".equals(part.path("type").asText())) b.append(part.path("text").asText(""));
        return b.toString();
    }
}
