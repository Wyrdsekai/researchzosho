package org.researchzosho.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.researchzosho.Config;
import org.researchzosho.Stopping;

/**
 * One narrow yes-or-no question about a text or a picture, asked of the library's own model: is this page one of the kinds the library
 * leaves out, does this question need such material, does this picture show what is always dropped. The same two ways the decline
 * judge has ({@link DeclineJudge}): the typed {@link Judge} where the server gives token probabilities, and one word on the same model
 * where it does not (Amazon Bedrock, a server whose grammar does not hold). What to do with the answer is the caller's.
 */
public final class ContentJudge {
    private static final ObjectMapper M = new ObjectMapper();

    /** The answer: yes or no when the judge is sure ({@link Judge#SURE}), unsure between, unjudged when nothing could answer. */
    public enum Verdict { YES, NO, UNSURE, UNJUDGED }

    /**
     * A verdict, the probability of yes (1 or 0 for a one-word answer, 0.5 when nothing answered), which way it was read, and {@code why}:
     * when nothing judged it, what the server did instead, in words a person reads ("" otherwise).
     */
    public record Reading(Verdict verdict, double pYes, String by, String why) {
        public Reading(Verdict verdict, double pYes, String by) { this(verdict, pYes, by, ""); }
        /** Whether the model's own answer leans yes: a sure yes, or an unsure one at 0.5 or more. */
        public boolean leansYes() { return verdict == Verdict.YES || (verdict == Verdict.UNSURE && pYes >= 0.5); }
        public boolean judged() { return verdict != Verdict.UNJUDGED; }
    }

    private final Judge judge;
    private final DeclineJudge.OneWord oneWord;

    /** {@code judge}: the typed judge, or null where it cannot run; {@code oneWord}: the fallback, or null. */
    public ContentJudge(Judge judge, DeclineJudge.OneWord oneWord) { this.judge = judge; this.oneWord = oneWord; }

    private static volatile ContentJudge override;

    /** Tests answer every content question here, and no model is asked; null goes back to the drives. */
    public static void use(ContentJudge j) { override = j; }

    /** The judge on {@code c}'s model: typed where the server gives token probabilities, one word otherwise. */
    public static ContentJudge of(DriveClient c) {
        ContentJudge o = override;
        if (o != null) return o;
        return new ContentJudge(c.givesTokenProbabilities() ? new Judge(c) : null, m -> c.classify(m, 4));
    }

    /**
     * How long one question may take when it is asked inside a request a person or a program waits on (an address added, a question
     * filed, a page shown): tens of seconds, not the drive's five minutes. A question that takes longer is unanswered, and what the caller
     * does then is its own: a page the person gave is saved and checked later, a question is filed without an offer.
     */
    public static volatile Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * The judge on the library's configured model (RESEARCHZOSHO_DRIVE, RESEARCHZOSHO_MODEL), for the steps that have no run's drive: they
     * run inside a request, so each question has {@link #REQUEST_TIMEOUT}.
     */
    public static ContentJudge configured() {
        ContentJudge o = override;
        if (o != null) return o;
        return of(new DriveClient(Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200"), Config.get("RESEARCHZOSHO_MODEL", "local-model")).decisionTimeout(REQUEST_TIMEOUT));
    }

    /** The judge on the model that reads pictures (RESEARCHZOSHO_VISION_DRIVE and RESEARCHZOSHO_VISION_MODEL, else the library's). */
    public static ContentJudge vision() {
        ContentJudge o = override;
        if (o != null) return o;
        return of(new DriveClient(Config.get("RESEARCHZOSHO_VISION_DRIVE", Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200")),
                Config.get("RESEARCHZOSHO_VISION_MODEL", Config.get("RESEARCHZOSHO_MODEL", "local-model"))));
    }

    /** {@code question} about {@code state} (the text the question is about, already cut to what it needs). */
    public Reading ask(String state, String question) { return asked(state, null, question); }

    /** {@code question} about a picture, given as a data address ({@code data:image/png;base64,…}). */
    public Reading askImage(String imageDataUrl, String question) { return asked("", imageDataUrl, question); }

    private Reading asked(String state, String image, String question) {
        // the usage the caller reads next is its own turn's: a check is not the work, and is never counted as it
        long[] turn = DriveClient.lastUsage();
        try { return judged(state, image, question); }
        finally { DriveClient.restoreUsage(turn); }
    }

    private Reading judged(String state, String image, String question) {
        String typedWhy = "";
        if (judge != null) {
            Judge.Answer a = image == null ? judge.noul(state, question) : judge.noul(state, question, image);
            if (a.ran()) {
                Verdict v = a.confidence() < Judge.SURE ? Verdict.UNSURE : a.yes() ? Verdict.YES : Verdict.NO;
                return new Reading(v, a.p("yes"), "judge");
            }
            typedWhy = "the typed question: " + (Judge.lastProblem().isBlank() ? "it did not run" : Judge.lastProblem());
        }
        if (oneWord == null) return new Reading(Verdict.UNJUDGED, 0.5, "none", typedWhy.isEmpty() ? "no model is set to answer it" : typedWhy);
        ArrayNode msgs = M.createArrayNode();
        String text = (state == null || state.isBlank() ? "" : state + "\n\n") + "QUESTION: " + question + " Answer with one word: yes or no.";
        if (image == null) msgs.addObject().put("role", "user").put("content", text);
        else {
            ArrayNode content = msgs.addObject().put("role", "user").putArray("content");
            content.addObject().put("type", "text").put("text", text);
            ObjectNode img = content.addObject().put("type", "image_url");
            img.putObject("image_url").put("url", image);
        }
        String said = "", failed = "";
        DriveClient.clearClassifyProblem();
        try {
            said = String.valueOf(oneWord.ask(msgs)).strip();
            Matcher w = FIRST_WORD.matcher(said.toLowerCase(Locale.ROOT));
            String word = w.find() ? w.group(1) : "";
            if (word.equals("yes")) return new Reading(Verdict.YES, 1.0, "one word");
            if (word.equals("no")) return new Reading(Verdict.NO, 0.0, "one word");
        } catch (Stopping.Requested stop) {
            throw stop;   // a person stopped the run: the page is not left unjudged, the run ends
        } catch (Exception unanswered) {
            // no answer, or a filter on the question itself: nothing judged it
            failed = unanswered.getMessage() == null ? unanswered.getClass().getSimpleName() : unanswered.getMessage();
        }
        String problem = DriveClient.lastClassifyProblem();
        String oneWordWhy = !failed.isEmpty() ? "the server answered: " + failed
                : !problem.isBlank() ? problem
                : said.isEmpty() ? "the server gave an empty answer"
                : "the model answered \"" + (said.length() > 60 ? said.substring(0, 60) + "…" : said) + "\" instead of yes or no";
        return new Reading(Verdict.UNJUDGED, 0.5, "one word", (typedWhy.isEmpty() ? "" : typedWhy + "; ") + "the one-word question: " + oneWordWhy);
    }

    private static final Pattern FIRST_WORD = Pattern.compile("^[^\\p{L}]*(\\p{L}+)");
}
