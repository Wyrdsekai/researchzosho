package org.researchzosho.drive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.researchzosho.Stopping;

/**
 * A typed decision with a probability, from the model the library already runs: a yes/no, a choice among options, or a level on
 * an ordered scale. Code asks a narrow question about some text and gets back a number it can act on, not prose to parse.
 *
 * <p>How: the model's thinking is off, a grammar allows only the answer tokens, one token is generated, and the probabilities the
 * model gave the allowed tokens are read back and normalised. One token is a few tens of milliseconds; the probability is the
 * model's own. {@code confidence} is the margin between the top two answers, so that code can act above a threshold and hand the
 * rest to a person. A server that offers no token probabilities answers with the word alone, and the probability is then 1 or 0.
 *
 * <p>What it does not do: reason in steps, or invent an answer outside the set. It is the seat for the decisions the library used
 * to take from a one-word reply: does a table's label say a relation, which rows are about one person, does a claim state an absence.
 */
public final class Judge {
    private static final ObjectMapper M = new ObjectMapper();

    /**
     * One typed answer: the chosen option, a probability for every option, and the margin between the top two. {@code unanswered}: why the
     * request got no answer at all (it could not be sent, the server did not answer in time, or it answered with an error in place of a
     * reply); null when an answer came.
     */
    public record Answer(String choice, Map<String, Double> probabilities, double confidence, IOException unanswered) {
        public Answer(String choice, Map<String, Double> probabilities, double confidence) { this(choice, probabilities, confidence, null); }
        /** The probability of {@code option}. A question that got no answer has none, and says so ({@link NoAnswer}) instead of a 0 that reads as a no. */
        public double p(String option) {
            if (unanswered != null) throw new NoAnswer(unanswered);
            return probabilities.getOrDefault(option, 0.0);
        }
        public boolean yes() { return choice.equals("yes"); }
        /** Whether the judge answered at all: a server that did not (Bedrock, one that refuses a grammar) leaves no probabilities. */
        public boolean ran() { return !probabilities.isEmpty(); }
    }

    /** A probability asked of a question the model never answered: no answer, which is not a no. Its cause is why no answer came. */
    public static final class NoAnswer extends UncheckedIOException {
        NoAnswer(IOException cause) {
            super("The model did not answer when the library asked it to check a fact it read, so the library added nothing from this text. "
                    + "Give the same command again when the model is running and not busy with other work. What happened: " + cause.getMessage(), cause);
        }
    }

    /** The call behind the judge: a request body in, the response body out (a test passes its own). */
    public interface Post { JsonNode send(ObjectNode body) throws Exception; }

    /**
     * The question asked the plain way, as the one-word questions are ({@link DriveClient#classify}): a few tokens, and once more with room
     * when the model thought them away, all inside the limit that began at {@code began}. What the model said comes back as text.
     */
    public interface Plain { String ask(ArrayNode messages, long began) throws Exception; }

    private final Post post;
    private final String model;
    /** The drive this judge asks ({@link DriveClient#decisionKey}), so that a server that does not keep to a grammar is remembered; null in a test. */
    private final String driveKey;
    /** The plain way on the same drive, for a server that refuses the typed request; null: none (a test, a caller of its own). */
    private final Plain plain;

    public Judge(DriveClient drive) { this(drive.model(), drive::postChat, drive.decisionKey(), (m, began) -> drive.classify(m, 4, began)); }
    public Judge(String model, Post post) { this(model, post, null); }
    public Judge(String model, Post post, String driveKey) { this(model, post, driveKey, null); }
    public Judge(String model, Post post, String driveKey, Plain plain) { this.model = model; this.post = post; this.driveKey = driveKey; this.plain = plain; }

    /**
     * The drives whose server does not keep to the answer's grammar, or refuses the typed request: remembered for as long as the program
     * runs, so that a hosted API is not sent a request it refuses before every one-word question.
     */
    private static final Set<String> NO_GRAMMAR = ConcurrentHashMap.newKeySet();

    /** Whether a typed decision can be read from this drive: not when its server was found not to keep to the grammar. */
    public static boolean grammarWorks(String driveKey) { return driveKey == null || !NO_GRAMMAR.contains(driveKey); }

    /** Tests: forget what was remembered. */
    static void forgetGrammar() { NO_GRAMMAR.clear(); }

    /** Is the proposition true of the state? The probability of yes. */
    public Answer noul(String state, String question) {
        return choice(state, question + " Answer yes or no.", List.of("yes", "no"));
    }

    /** The same about a picture, given as a data address ({@code data:image/png;base64,…}) beside the text: a model that reads pictures. */
    public Answer noul(String state, String question, String imageDataUrl) {
        return choice(state, question + " Answer yes or no.", List.of("yes", "no"), imageDataUrl);
    }

    /** Which option? The options are answered by letter (A, B, C…), so that an option's own words never leak into the answer tokens. */
    public Answer choice(String state, String question, List<String> options) { return choice(state, question, options, null); }

    private Answer choice(String state, String question, List<String> options, String imageDataUrl) {
        if (options.size() < 2 || options.size() > 26) throw new IllegalArgumentException("a choice has two to twenty-six options");
        boolean yesNo = options.equals(List.of("yes", "no"));
        List<String> labels = new ArrayList<>();
        StringBuilder prompt = new StringBuilder();
        if (state != null && !state.isBlank()) prompt.append("STATE:\n").append(state).append("\n\n");
        prompt.append("QUESTION: ").append(question);
        if (!yesNo) {
            prompt.append("\nOPTIONS:");
            for (int i = 0; i < options.size(); i++) { String l = String.valueOf((char) ('A' + i)); labels.add(l); prompt.append('\n').append(l).append(". ").append(options.get(i)); }
            prompt.append("\nAnswer with the letter of the option.");
        } else { labels.add("yes"); labels.add("no"); }
        // the grammar: the allowed tokens, each in the forms a tokenizer may carry them (a leading space, a capital)
        List<String> allowed = new ArrayList<>();
        for (String l : labels) { allowed.add(l); if (yesNo) { allowed.add(cap(l)); } }
        StringBuilder g = new StringBuilder("root ::= ");
        for (int i = 0; i < allowed.size(); i++) g.append(i > 0 ? " | " : "").append('"').append(allowed.get(i)).append('"');
        ObjectNode body = M.createObjectNode();
        body.put("model", model);
        ArrayNode msgs = body.putArray("messages");
        if (imageDataUrl == null) msgs.addObject().put("role", "user").put("content", prompt.toString());
        else {
            ArrayNode content = msgs.addObject().put("role", "user").putArray("content");
            content.addObject().put("type", "text").put("text", prompt.toString());
            content.addObject().put("type", "image_url").putObject("image_url").put("url", imageDataUrl);
        }
        body.put("max_tokens", 1); body.put("stream", false);
        DriveClient.decisionSettings(body);   // the research drive's own temperature and template settings, thinking off
        body.put("grammar", g.toString());
        body.put("logprobs", true); body.put("top_logprobs", 20);
        PROBLEM.set("");
        PLAINLY.set(false);
        long began = System.nanoTime();   // the typed question and the plain one share one limit
        Answer notRan = new Answer(labels.get(labels.size() - 1), Map.of(), 0.0);
        if (!grammarWorks(driveKey)) {
            if (plain != null) return plainly(msgs, labels, options, yesNo, began);   // the server refuses the typed form, as an earlier question found
            PROBLEM.set("the server does not keep to the answer's grammar, as an earlier question found"); return notRan;
        }
        JsonNode resp;
        try { resp = post.send(body); }
        catch (Stopping.Requested stop) { throw stop; }   // a person stopped the run: the decision is not unanswered, the work ends
        catch (Exception e) {
            String said = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            PROBLEM.set("the server answered: " + said);
            if (refusesTheForm(said)) {
                // the server refuses the typed request itself, or cannot give its answer in that form (llama.cpp with gpt-oss-20b: HTTP 500
                // "does not match the expected peg-native format", 372 of 372 times on 2026-11-19): not asked it again, and asked the plain way now
                if (driveKey != null) NO_GRAMMAR.add(driveKey);
                if (plain != null) return plainly(msgs, labels, options, yesNo, began);
            }
            // no answer came at all: the question is unanswered, never answered no
            if (e instanceof IOException io && !(e instanceof JsonProcessingException)) return new Answer(notRan.choice(), Map.of(), 0.0, io);
            return notRan;
        }
        Answer a = read(resp, labels, options, yesNo);
        if (!a.ran()) {
            boolean held = grammarHeld(resp, labels);
            PROBLEM.set(held ? "the model meant to say something else than the answers allowed" : "the server did not keep to the answer's grammar");
            if (!held && driveKey != null) NO_GRAMMAR.add(driveKey);   // a server that ignores the grammar: not asked the typed question again
            if (!held && plain != null) return plainly(msgs, labels, options, yesNo, began);
        }
        return a;
    }

    /** Whether a refusal is the server's answer to the request's shape (400, 404, 405, 415, 422, 501), not a passing failure (a timeout, 429, 5xx). */
    static boolean refusesTheRequest(String said) {
        return said != null && said.matches("(?s)^HTTP (400|404|405|415|422|501)\\b.*");
    }

    /**
     * Whether the server refuses the typed form: the request's shape ({@link #refusesTheRequest}), or a server error that says the answer
     * could not be given in the form asked (a grammar, the model's own output format, token probabilities). A server that is down, busy or
     * slow is none of these: its question is unanswered, and asked the same way next time.
     */
    static boolean refusesTheForm(String said) {
        return refusesTheRequest(said) || said != null && said.matches("(?is).*\\bHTTP 5\\d\\d\\b.*\\b(grammar|does not match the expected|peg-native|logprobs?)\\b.*");
    }

    /** Whether the last question this thread asked was asked the plain way: a caller with a plain question of its own does not ask it again. */
    private static final ThreadLocal<Boolean> PLAINLY = ThreadLocal.withInitial(() -> false);
    public static boolean askedPlainly() { return PLAINLY.get(); }

    /**
     * The question asked the plain way, inside the limit that began at {@code began}: the answer is the word or the letter the model gave,
     * its probability 1, the others 0. Nothing usable said: the question did not run, and {@link #lastProblem} says why.
     */
    private Answer plainly(ArrayNode msgs, List<String> labels, List<String> options, boolean yesNo, long began) {
        PLAINLY.set(true);
        Answer notRan = new Answer(labels.get(labels.size() - 1), Map.of(), 0.0);
        ArrayNode asked = msgs.deepCopy();
        JsonNode last = asked.path(asked.size() - 1);
        String more = yesNo ? " Answer with one word: yes or no." : "";
        if (last.path("content").isTextual()) ((ObjectNode) last).put("content", last.path("content").asText() + more);
        else if (last.path("content").path(0).path("text").isTextual()) ((ObjectNode) last.path("content").path(0)).put("text", last.path("content").path(0).path("text").asText() + more);
        String said;
        DriveClient.forgetClassify();
        try { said = String.valueOf(plain.ask(asked, began)).strip(); }
        catch (Stopping.Requested stop) { throw stop; }
        catch (Exception e) { PROBLEM.set("the server answered: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); return e instanceof IOException io ? new Answer(notRan.choice(), Map.of(), 0.0, io) : notRan; }
        String answer = plainAnswer(said, labels);
        if (answer == null) {
            IOException none = DriveClient.lastClassifyUnanswered();
            String problem = DriveClient.lastClassifyProblem();
            PROBLEM.set(!problem.isBlank() ? problem : said.isEmpty() ? "the server gave an empty answer" : "the model answered \"" + (said.length() > 60 ? said.substring(0, 60) + "…" : said) + "\" instead of " + (yesNo ? "yes or no" : "a letter"));
            return none != null ? new Answer(notRan.choice(), Map.of(), 0.0, none) : notRan;
        }
        PROBLEM.set("");
        Map<String, Double> probs = new LinkedHashMap<>();
        String best = null;
        for (int i = 0; i < labels.size(); i++) {
            String option = yesNo ? labels.get(i) : options.get(i);
            boolean it = labels.get(i).equals(answer);
            probs.put(option, it ? 1.0 : 0.0);
            if (it) best = option;
        }
        return new Answer(best, probs, 1.0);
    }

    /** The answer in what a model said the plain way: its first word when it is yes or no, or a letter of the options standing alone. */
    private static final Pattern FIRST_WORD = Pattern.compile("[^\\p{L}]*(\\p{L}+)");
    static String plainAnswer(String said, List<String> labels) {
        if (said == null) return null;
        String s = said.strip();
        Matcher w = FIRST_WORD.matcher(s);   // as the one-word questions read it: the first word, whatever marks come before it
        if (!w.find()) return null;
        String word = w.group(1);
        if (labels.equals(List.of("yes", "no"))) { word = word.toLowerCase(Locale.ROOT); return labels.contains(word) ? word : null; }
        if (word.equalsIgnoreCase("option") && w.find()) word = w.group(1);
        word = word.toUpperCase(Locale.ROOT);
        return word.length() == 1 && labels.contains(word) ? word : null;
    }

    /** Whether the token the server generated is one of the allowed answers: a server that ignores the grammar generates another. */
    static boolean grammarHeld(JsonNode resp, List<String> labels) {
        JsonNode choice0 = resp.path("choices").path(0);
        JsonNode chosen = choice0.path("logprobs").path("content").path(0);
        String generated = chosen.has("token") ? chosen.path("token").asText("").strip() : choice0.path("message").path("content").asText("").strip();
        return labels.stream().anyMatch(generated::equalsIgnoreCase);
    }

    /** Why the last question this thread asked did not run ({@link Answer#ran} false), in words a person reads; "" when it ran. */
    private static final ThreadLocal<String> PROBLEM = ThreadLocal.withInitial(() -> "");
    public static String lastProblem() { return PROBLEM.get(); }

    /** A level on an ordered scale, from the first to the last. */
    public Answer score(String state, String question, List<String> levels) { return choice(state, question + " Which level fits best?", levels); }

    /** Below this margin between yes and no, the judge is not sure, and the code asks a person or takes the safe side. */
    public static final double SURE = 0.3;

    /**
     * Below this, the allowed answers together held too little of the model's probability to mean anything: it was going to say something
     * else, and a renormalised sliver would read as a sure answer.
     */
    static final double MIN_MASS = 0.05;

    /**
     * The token probabilities the server gave the allowed answers, normalised over them; the word alone when it gave none. The judge did
     * not run ({@link Answer#ran} false) when the grammar did not hold, which a server that accepts the field and ignores it shows by
     * generating a token that is not an allowed answer, or when the allowed answers hold less than {@link #MIN_MASS} of the probability.
     */
    static Answer read(JsonNode resp, List<String> labels, List<String> options, boolean yesNo) {
        JsonNode choice0 = resp.path("choices").path(0);
        String text = choice0.path("message").path("content").asText("").strip();
        Answer notRan = new Answer(labels.get(labels.size() - 1), Map.of(), 0.0);
        Map<String, Double> mass = new LinkedHashMap<>();
        for (String l : labels) mass.put(l, 0.0);
        // the token the server chose under the grammar carries its own probability, which is not always among the top ones listed:
        // a model that wanted to begin with "The" lists that first, and the allowed token may sit below the list's cut
        JsonNode chosen = choice0.path("logprobs").path("content").path(0);
        boolean listed = chosen.has("token") || chosen.path("top_logprobs").isArray();
        String generated = chosen.has("token") ? chosen.path("token").asText("").strip() : text;
        if (labels.stream().noneMatch(generated::equalsIgnoreCase)) return notRan;   // the grammar did not hold: this is no answer at all
        List<JsonNode> seen = new ArrayList<>();
        if (chosen.path("top_logprobs").isArray()) chosen.path("top_logprobs").forEach(seen::add);
        if (chosen.has("token") && seen.stream().noneMatch(t -> t.path("token").asText().equals(chosen.path("token").asText()))) seen.add(chosen);
        for (JsonNode t : seen) {
            String tok = t.path("token").asText("").strip();
            for (String l : labels) if (tok.equalsIgnoreCase(l)) mass.merge(l, Math.exp(t.path("logprob").asDouble(-99)), Double::sum);
        }
        double total = mass.values().stream().mapToDouble(Double::doubleValue).sum();
        if (listed && total < MIN_MASS) return notRan;   // the model meant something else; a renormalised sliver is noise
        if (total <= 0) { for (String l : labels) mass.put(l, text.equalsIgnoreCase(l) ? 1.0 : 0.0); total = mass.values().stream().mapToDouble(Double::doubleValue).sum(); }
        Map<String, Double> probs = new LinkedHashMap<>();
        String best = labels.get(0); double first = -1, second = -1;
        for (int i = 0; i < labels.size(); i++) {
            double p = total > 0 ? mass.get(labels.get(i)) / total : 1.0 / labels.size();
            probs.put(yesNo ? labels.get(i) : options.get(i), p);
            if (p > first) { second = first; first = p; best = yesNo ? labels.get(i) : options.get(i); } else if (p > second) second = p;
        }
        return new Answer(best, probs, Math.max(0, first - Math.max(0, second)));
    }

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    /** A function form for the places that take one: the question about the text, answered "yes" or "no". */
    public static Function<String, String> yesNo(Judge j) { return prompt -> j.noul("", prompt).choice().toLowerCase(Locale.ROOT); }
}
