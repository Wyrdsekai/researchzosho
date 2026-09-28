package org.researchzosho.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.researchzosho.Config;

/**
 * Whether a reply is the model declining the task it was given, for the models that send no signal of it: a local llama.cpp model
 * (and llama-swap in front of it) declines in plain text, and with a tool call required the text arrives inside the tool call.
 *
 * <p>The typed {@link Judge} answers where the server gives token probabilities, on the same model; a decline is recorded only when it
 * is sure ({@link Judge#SURE}). Where it cannot run (Bedrock, a server that refuses a grammar or token probabilities), one word is asked
 * of the same model instead, and only a plain yes counts. Anything else is not called a decline: the caller notes it as unsure.
 *
 * <p>Where a decline would end a research run or throw away work already done (the plan, a reader's sub-question, the write-up), the
 * bar is higher: the typed judge's own p(yes) of at least {@link #ENDS_BAR}, and a one-word yes is only noted. The judge reads the step
 * the model was asked to do beside the reply, so a model that did the step and reports what it could not find, reach or establish is
 * read as doing the task.
 */
public final class DeclineJudge {
    private static final ObjectMapper M = new ObjectMapper();

    /**
     * The one question the judge is asked about a reply, with the step the model was asked to do in front of it. Measured on the 27B
     * (2026-09-23, 27 cases with the real step descriptions): this wording put all ten plain declines at p(yes) 0.90 or more and no
     * ordinary reply above 0.40; a wording about refusing "on policy or safety grounds, rather than … reporting that it could not find"
     * caught one decline in ten, because the judge read the long exclusion as the answer.
     */
    public static final String QUESTION = "Is this reply the model declining to do the task it was given?";

    /** What the typed judge is asked about a critic's gap in a run with a declined sub-question: is it that sub-question in other words? */
    public static final String SAME_QUESTION = "Does this new question ask for the same thing as this declined question?";

    /**
     * Whether {@code gap} asks for the same thing as the declined {@code declinedQuestion}, in other words: the typed judge only, and only
     * a sure yes ({@link Judge#SURE}). Where the typed judge cannot run, false: the caller's own word-overlap rule stands alone.
     */
    public boolean sameAsk(String declinedQuestion, String gap) {
        if (judge == null) return false;
        Judge.Answer a = judge.noul("THE DECLINED QUESTION:\n" + cut(declinedQuestion, STEP_CHARS) + "\n\nTHE NEW QUESTION:\n" + cut(gap, STEP_CHARS), SAME_QUESTION);
        return a.ran() && a.yes() && a.confidence() >= Judge.SURE;
    }

    /**
     * Where a decline ends a research run or throws work away: the typed judge's p(yes) at least this, or it is not called a decline. The
     * weakest real decline measured scored 0.90 and the highest ordinary reply 0.40.
     */
    public static final double ENDS_BAR = 0.8;

    /**
     * The general rule the judge is asked by: a reply in the model's own prose is read only when it says it will not or cannot do
     * something, in the words people decline in. These are the default words (an apology, an inability, a refusal, in the languages the
     * library is used in); {@code RESEARCHZOSHO_DECLINE_WORDS} adds more, separated by commas. A match is never a decline by itself: it
     * only lets the judge be asked.
     */
    public static final List<String> DECLINE_WORDS = List.of("sorry", "apolog", "can't", "cannot", "can not", "unable", "won't", "will not",
            "not able", "decline", "refuse", "refrain", "not comfortable", "rather not", "prefer not", "not appropriate", "inappropriate",
            "against my", "as an ai", "申し訳", "できません", "お手伝いでき", "お答えでき", "控え", "désolé", "ne peux pas", "je ne peux",
            "lo siento", "no puedo", "tut mir leid", "kann ich nicht", "ich kann nicht");

    /** Whether {@code reply} carries decline wording ({@link #DECLINE_WORDS} and the configured ones): only then is the judge asked about it. */
    public static boolean soundsLikeDecline(String reply) {
        if (reply == null || reply.isBlank()) return false;
        String t = reply.toLowerCase(Locale.ROOT).replace('’', '\'');
        List<String> words = new ArrayList<>(DECLINE_WORDS);
        for (String w : Config.get("RESEARCHZOSHO_DECLINE_WORDS", "").split(",")) if (!w.isBlank()) words.add(w.strip().toLowerCase(Locale.ROOT));
        for (String w : words) if (t.contains(w)) return true;
        return false;
    }

    /** A one-word completion on the same model, for a drive the typed judge cannot read. */
    public interface OneWord { String ask(ArrayNode messages) throws Exception; }

    /** What the judge made of a reply: declined (and sure of it), or not; {@code sure} false is a reply to note, never a decline. */
    public record Verdict(boolean declined, boolean sure, double pYes, String by) { }

    private final Judge judge;
    private final OneWord oneWord;

    /** {@code judge}: the typed judge, or null where it cannot run; {@code oneWord}: the fallback, or null. */
    public DeclineJudge(Judge judge, OneWord oneWord) { this.judge = judge; this.oneWord = oneWord; }

    /** The judge for a drive: typed where the server gives token probabilities, one word otherwise, both on the drive's own model. */
    public static DeclineJudge of(DriveClient c) {
        return new DeclineJudge(c.givesTokenProbabilities() ? new Judge(c) : null, m -> c.classify(m, 4));
    }

    static final int STEP_CHARS = 400, REPLY_CHARS = 1500;

    /**
     * What the judge reads: the step the model was asked to do, in the caller's few words ("list the checkable factual claims in a
     * text"), then the reply, each cut to a length that keeps the question cheap. The step is never the data the model worked on: a
     * transcript that quotes a refusal, or a whole ask in front of a sub-question, read as "the task" made an ordinary reply look like one.
     */
    static String state(String step, String reply) {
        return "THE STEP THE MODEL WAS ASKED TO DO:\n" + cut(step, STEP_CHARS) + "\n\nTHE MODEL'S REPLY:\n" + cut(reply, REPLY_CHARS);
    }

    /** {@code step}: what the model was asked to do, in a few words; {@code reply}: what it answered. At a step whose decline ends nothing. */
    public Verdict read(String step, String reply) { return read(step, reply, false); }

    /**
     * As above; {@code ends}: a decline here would end a research run or throw away work already done, so only the typed judge's p(yes) of
     * at least {@link #ENDS_BAR} is a decline, and anything short of it (a one-word yes among them) is noted as unsure.
     */
    public Verdict read(String step, String reply, boolean ends) {
        // the usage the caller reads next is the turn's own: the judge's question is not the work, and never counted as it (a trace's
        // tokens, the run ledger's, and the token scale the runner calibrates from the server's count)
        long[] turn = DriveClient.lastUsage();
        try { return judged(step, reply, ends); }
        finally { DriveClient.restoreUsage(turn); }
    }

    private Verdict judged(String step, String reply, boolean ends) {
        String state = state(step, reply);
        if (judge != null) {
            Judge.Answer a = judge.noul(state, QUESTION);
            if (a.ran()) {
                double p = a.p("yes");
                boolean yes = ends ? p >= ENDS_BAR : a.yes() && a.confidence() >= Judge.SURE;
                boolean sureNo = !a.yes() && a.confidence() >= Judge.SURE;
                return new Verdict(yes, yes || sureNo, p, "judge");
            }
        }
        if (oneWord == null) return new Verdict(false, false, 0.5, "none");
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content", state + "\n\nQUESTION: " + QUESTION + " Answer with one word: yes or no.");
        try {
            Matcher w = FIRST_WORD.matcher(String.valueOf(oneWord.ask(msgs)).toLowerCase(Locale.ROOT));
            String word = w.find() ? w.group(1) : "";
            if (word.equals("yes")) return ends ? new Verdict(false, false, 1.0, "one word") : new Verdict(true, true, 1.0, "one word");
            if (word.equals("no")) return new Verdict(false, true, 0.0, "one word");
        } catch (Exception unanswered) {
            // the question itself went unanswered (a filter on the judging request among the ways): not sure, so not a decline
        }
        return new Verdict(false, false, 0.5, "one word");
    }

    /**
     * For a caller that talks to a drive no seat watches: {@code reply} to the step {@code step} (what the model was asked to do, in a
     * few words, never the data) read for a decline, and a {@link Declined} naming {@code model} raised when the judge is sure. An unsure
     * reply goes on as it was.
     */
    public void raise(String model, String step, String reply) {
        if (reply == null || reply.isBlank() || !soundsLikeDecline(reply)) return;   // ordinary work goes by unread: no question is spent on it
        if (read(step, reply).declined()) throw new Declined(model, reply, Declined.How.WORDS);
    }

    private static final Pattern FIRST_WORD = Pattern.compile("^[^\\p{L}]*(\\p{L}+)");

    private static String cut(String s, int n) {
        String t = s == null ? "" : s.strip();
        return t.length() <= n ? t : t.substring(0, n) + " …";
    }
}
