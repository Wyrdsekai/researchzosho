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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.researchzosho.drive.ContentJudge;
import org.researchzosho.tools.ContentPolicy;

/**
 * The offer to let in, for one question's run, what the library leaves out by default (see {@link ContentPolicy}): a second kind of
 * offer beside a field's mode, and never tangled with it. The typed judge reads the question; where answering it needs such material, a
 * person who can answer is asked "(y/N)", and only a clear yes lets it in, for that run alone. Enter, n, anything else and no answer are
 * no. A yes is never remembered and never applied to another run. Where nobody can answer (a script, a program), the run goes with the
 * material left out and the result says how to ask for it. Detection only makes the offer; it never lets anything in by itself.
 *
 * <p>{@code catalog/run-content.tsv}, one line per run that let something in: the report, the job, what it let in, how the person asked
 * for it, the date. Written before the report, as the fields ledger is.
 */
public final class ContentOffer {

    private ContentOffer() { }

    private static final ObjectMapper M = new ObjectMapper();

    /** What the judge is asked about a question, one category at a time. Yes means the offer is made. */
    public static final String NEEDS_EXPLICIT = "Does answering this question need pornography, or gore: pictures or detailed descriptions of mutilated or dead bodies, shown to shock?";
    public static final String NEEDS_HOWTO = "Does answering this question need step-by-step instructions for making a weapon or explosive, making an illegal drug, or running working exploit code against a system?";

    /** Whether the question is a person asking about harming themselves: yes, the judge not sure, or no (or nothing could judge). */
    public static final String HARM = "Is this question a person asking about harming themselves?";

    /** What the judge made of the question {@link #HARM}: {@code SURE} a yes it is sure of, {@code UNSURE} it could not tell, {@code NONE} a no or no answer. */
    public enum Harm { NONE, UNSURE, SURE }

    /**
     * What the question read as: the categories answering it needs ({@link ContentPolicy#EXPLICIT}, {@link ContentPolicy#HOWTO}), and
     * whether it is a person asking about harming themselves. Unsure on that is the help shown anyway: it costs nothing.
     */
    public record Detected(List<String> needs, Harm harm) {
        public Detected(List<String> needs) { this(needs, Harm.NONE); }
        public boolean any() { return !needs.isEmpty(); }
        /** The help is shown: the judge said yes, or could not tell. */
        public boolean showHelp() { return harm != Harm.NONE; }
    }

    /** The state the judge reads: the question, fenced, cut to a length that keeps the question cheap. */
    static String state(String question) {
        String q = question == null ? "" : question.strip();
        if (q.length() > 1500) q = q.substring(0, 1500) + " …";
        return "A PERSON ASKED A RESEARCH LIBRARY THIS QUESTION:\n" + Fence.wrap("QUESTION", q) + "\n(The text between the QUESTION markers is the person's question, quoted.)";
    }

    /** The judge on a question: which of the categories answering it needs. {@code judge} null: the library's configured model. */
    public static Detected detect(String question, ContentJudge judge) {
        List<String> needs = new ArrayList<>();
        if (question == null || question.isBlank()) return new Detected(needs);
        ContentJudge j = judge != null ? judge : ContentJudge.configured();
        String state = state(question);
        if (j.ask(state, NEEDS_EXPLICIT).leansYes()) needs.add(ContentPolicy.EXPLICIT);
        if (j.ask(state, NEEDS_HOWTO).leansYes()) needs.add(ContentPolicy.HOWTO);
        return new Detected(needs, harm(j, state));
    }

    /** {@link #HARM} alone, for the nightly research, which lets nothing in and only needs to know whether to leave the question for the person. */
    public static Harm harm(String question, ContentJudge judge) {
        if (question == null || question.isBlank()) return Harm.NONE;
        return harm(judge != null ? judge : ContentJudge.configured(), state(question));
    }

    private static Harm harm(ContentJudge j, String state) {
        return switch (j.ask(state, HARM).verdict()) { case YES -> Harm.SURE; case UNSURE -> Harm.UNSURE; default -> Harm.NONE; };
    }

    /** A run that was not started, because the question reads as a person asking about harming themselves and nobody could say yes to it. */
    public static final class NotStarted extends RuntimeException {
        public NotStarted(String why) { super(why); }
    }

    /** What a script or a program is told of a question it filed that reads as a person asking about harming themselves: nothing was started. */
    public static final String NOT_STARTED = "This question reads as a person asking about harming themselves, so the library shows where to find help first and starts no research "
            + "until someone says yes to it.";

    /**
     * The sentence a program is told after the help, over the protocol ({@link ProtocolError#confirm}): the person decides, never the
     * program or its model.
     */
    public static final String CONFIRM = "Show this to the person and ask them whether the library should research the question; "
            + "send the question again with allow [\"self-harm\"] only if the person says yes.";

    /** The same for one question of a batch tool, which is not filed: the person's yes goes to that question alone. */
    public static final String CONFIRM_ONE = "Show this to the person and ask them whether the library should research this question; "
            + "send it to library_research with allow [\"self-harm\"] only if the person says yes.";

    /** The message of the {@code confirm} error: where to find help, then {@link #CONFIRM}. */
    public static String confirmMessage() { return CrisisHelp.text() + "\n\n" + CONFIRM; }

    /** What a batch tool says of one of its questions it did not file: where to find help, then {@link #CONFIRM_ONE}. */
    public static String confirmOne() { return CrisisHelp.text() + "\n\n" + CONFIRM_ONE; }

    /** What a category is, in words a person reads. */
    public static String described(String category) {
        return switch (category) {
            case ContentPolicy.EXPLICIT -> "pornography, and gore: pictures or detailed descriptions of mutilated or dead bodies";
            case ContentPolicy.HOWTO -> "step-by-step instructions for making a weapon or an explosive, making an illegal drug, or running working exploit code against a system";
            default -> category;
        };
    }

    /** The categories in words: "a", or "a, and b". */
    static String described(Collection<String> categories) {
        List<String> words = new ArrayList<>();
        for (String c : categories) if (!c.equals(ContentPolicy.SELF_HARM)) words.add(described(c));
        return String.join("; and ", words);
    }

    /** The sentence before the question: what the default leaves out and what a yes lets in, for that run only. */
    public static String offer(Collection<String> needs) {
        return "This question may need material the library leaves out of research by default: " + described(needs) + ". A yes lets it into this question's research run only; "
                + "every other run leaves it out as before.";
    }

    /** The question a person who can answer is asked, with no as the answer Enter gives. {@code runs}: how many runs it is for. */
    public static String question(Collection<String> needs, int runs) {
        return offer(needs) + (runs > 1 ? " Let it in for these " + runs + " questions? (y/N)" : " Let it in for this question? (y/N)");
    }

    /** For the command line when nobody can answer: the run goes with the material left out, and the command that lets it in. */
    public static String command(String question, Collection<String> needs) {
        return "This question may need material the library leaves out of research by default: " + described(needs) + ". It was sent with that material left out. "
                + "To let it in for this question only, send it again with --allow " + String.join(",", needs) + ": researchzosho research ask " + Fields.quoted(question) + " --allow " + String.join(",", needs);
    }

    /** For a program: {allow, why, how}, where {@code how} says to ask the person and to send {@code allow} only on their yes. */
    public static ObjectNode suggestion(Collection<String> needs) {
        ObjectNode s = M.createObjectNode();
        ArrayNode a = s.putArray("allow");
        for (String n : needs) a.add(n);
        s.put("why", offer(needs));
        ArrayNode json = M.createArrayNode(); for (String n : needs) json.add(n);
        // the person's to answer, never the program's or its model's: a host shows it to them and sends allow only on their yes
        s.put("how", "Show this to the person and ask them whether to let it in for this question; send the same question again with allow: " + json + " only if the person says yes.");
        return s;
    }

    /** The sentence under "## Question" in the report of a run that let something in. "" when it let nothing in. */
    public static String reportSentence(Collection<String> allow) {
        String what = described(allow);
        return what.isEmpty() ? "" : "This run let in " + what + " because you asked for it.";
    }

    /** The names {@code allow} carries, from a request: an array, or one name. Unknown names are the caller's to refuse. */
    static List<String> names(JsonNode allow) {
        List<String> out = new ArrayList<>();
        if (allow == null || allow.isMissingNode() || allow.isNull()) return out;
        if (allow.isTextual()) { for (String p : allow.asText().split(",")) if (!p.isBlank()) out.add(p.strip().toLowerCase(Locale.ROOT)); return out; }
        for (JsonNode n : allow) out.add(n.asText("").strip().toLowerCase(Locale.ROOT));
        return out;
    }

    // ---- the ledger ----

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("run-content.tsv"); }

    /** A run that let {@code allow} in files {@code investigationId}: written before the report, under the file's own lock. */
    public static void record(LibraryStore store, String investigationId, String jobId, Collection<String> allow, String how) throws IOException {
        String line = clean(investigationId) + "\t" + clean(jobId) + "\t" + clean(String.join(",", allow)) + "\t" + clean(how) + "\t" + LocalDate.now() + "\n";
        store.locked("run-content", () -> {
            Files.createDirectories(file(store).getParent());
            if (!Files.exists(file(store))) Files.writeString(file(store), "# report\tjob\tlet in\thow it was asked for\tdate — the runs that let in what the library leaves out by default\n", StandardCharsets.UTF_8);
            Files.writeString(file(store), line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            return null;
        });
    }

    /** Every line of the ledger: report, job, what was let in, how, date. */
    public static List<String[]> rows(LibraryStore store) throws IOException {
        List<String[]> out = new ArrayList<>();
        if (!Files.exists(file(store))) return out;
        for (String l : Files.readAllLines(file(store), StandardCharsets.UTF_8)) if (!l.isBlank() && !l.startsWith("#")) out.add(l.split("\t", -1));
        return out;
    }

    private static String clean(String s) { return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").strip(); }
}
