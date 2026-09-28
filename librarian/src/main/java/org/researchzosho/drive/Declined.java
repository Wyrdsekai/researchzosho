package org.researchzosho.drive;

import java.util.Locale;

/**
 * The model declined what it was asked to do.
 *
 * <p>ResearchZosho does not decide for people what they may research: the model the person chose decides. When that model declines,
 * the library says so plainly and does not try to get around it: no rephrasing, no second attempt, no other model (decided
 * 2026-09-23: "if the model refuses then do a clear statement that the model being used is rejecting to act"). This is how a decline
 * travels from where it was seen to where it is said: a model server's own signal (a reply the service filtered, a reply the model
 * marked as a refusal, a guardrail on the person's Bedrock account), or a reply whose words a judge read as the model declining.
 *
 * <p>The word is "declined" on purpose: "refused" already names four other things in this code (the acquisitions gate, a source on the
 * person's refused list, an address the fetcher will not open, Bedrock's own error).
 */
public final class Declined extends RuntimeException {

    /** How the decline was known. */
    public enum How {
        /** The service that runs the model returned the reply as filtered content (finish_reason content_filter). */
        FILTERED,
        /** A guardrail on the person's Amazon Bedrock account stopped the reply (Converse stopReason guardrail_intervened). */
        GUARDRAIL,
        /** The model marked its reply as a refusal (message.refusal, or finish_reason refusal). */
        REFUSAL,
        /** The service's content filter stopped the request before the model answered (Azure OpenAI: HTTP 400, error code content_filter). */
        FILTERED_REQUEST,
        /** The model said so in its own words, and the judge was sure of it. */
        WORDS
    }

    private final String model, said, seat, step;
    private final How how;

    public Declined(String model, String said, How how) { this(model, said, how, "", ""); }

    private Declined(String model, String said, How how, String seat, String step) {
        super(message(model, said, how, step));
        this.model = model == null ? "" : model.strip();
        this.said = said == null ? "" : said.strip();
        this.how = how == null ? How.WORDS : how;
        this.seat = seat == null ? "" : seat;
        this.step = step == null ? "" : step;
    }

    /** The same decline, seen at a seat (workers, judge, chat); a seat already named stays. */
    public Declined seat(String s) { return seat.isEmpty() && s != null && !s.isBlank() ? new Declined(model, said, how, s, step) : this; }

    /** The same decline, at a step of the work, written to follow "declined": "to plan this research". A step already named stays. */
    public Declined at(String s) { return step.isEmpty() && s != null && !s.isBlank() ? new Declined(model, said, how, seat, s) : this; }

    public String model() { return model; }
    /** What the model said, as it said it; "" when it gave no words. */
    public String said() { return said; }
    public How how() { return how; }
    public String seat() { return seat; }
    /** What it declined, as the words after "declined"; "" when the place that saw it did not say. */
    public String step() { return step; }

    /** The sentence that says how the decline was known; "" when the model said it in its own words. */
    public String howSaid() {
        return switch (how) {
            case FILTERED -> "The service that runs the model returned the reply as filtered content.";
            case GUARDRAIL -> "A guardrail on the Amazon Bedrock account stopped the reply.";
            case REFUSAL -> "The model marked its reply as a refusal.";
            case FILTERED_REQUEST -> "The service that runs the model stopped the request with its content filter before the model answered.";
            case WORDS -> "";
        };
    }

    /** The model's words as a quotation: one line, cut at a length a person reads. */
    public String quoted() { return quote(said); }

    /**
     * The plain statement for a person: which model, what it declined, that ResearchZosho did not try to get around it, and what the
     * model said. {@code what} follows "declined" ("to research this question"); null or blank uses the step this decline carries.
     */
    public String statement(String what) {
        String w = what == null || what.isBlank() ? (step.isEmpty() ? "what it was asked to do" : step) : what.strip();
        StringBuilder b = new StringBuilder("The model this library uses").append(model.isEmpty() ? "" : " (" + model + ")").append(" declined ").append(w)
                .append(". ResearchZosho did not try to get around it.");
        if (!howSaid().isEmpty()) b.append(' ').append(howSaid());
        b.append(said.isEmpty() ? " The model gave no words with it." : " What the model said: \"" + quote(said) + "\"");
        return b.toString();
    }

    public String statement() { return statement(null); }

    private static String message(String model, String said, How how, String step) {
        String m = model == null || model.isBlank() ? "the model" : "the model " + model.strip();
        return m + " declined " + (step == null || step.isBlank() ? "what it was asked to do" : step)
                + (said == null || said.isBlank() ? "" : ": \"" + quote(said) + "\"") + " (" + (how == null ? How.WORDS : how).name().toLowerCase(Locale.ROOT) + ")";
    }

    static final int QUOTE_CHARS = 600;

    private static String quote(String s) {
        String one = s == null ? "" : s.strip().replaceAll("\\s+", " ");
        return one.length() <= QUOTE_CHARS ? one : one.substring(0, QUOTE_CHARS - 1).strip() + "…";
    }
}
