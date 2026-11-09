package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.researchzosho.Stopping;
import org.researchzosho.drive.Declined;

/**
 * Whether a report's answer is about the question it was written for. One call on the judge's seat, given only the question, its
 * sub-questions and the first section of the answer, never the sources: yes, partly or no, and one line that says why.
 *
 * <p>Every later step of a run is handed the question and works on what the step before it wrote, so a write-up that begins on another
 * subject is carried to the shelves by all of them: on 2026-11-05 a run about software agents wrote seven sections about emotional
 * contagion, its analysis and its checks followed, and eight claims on the wrong subject were filed under the question. A reply that
 * belongs to another conversation and a model that lost the thread look the same from here, and both are caught by reading the product
 * against the question. This judges the written report and nothing the model does on the way to it.
 *
 * <p>A check that got no answer is {@link Answer#UNCHECKED}, with why: it is never read as a yes or as a no.
 */
public final class TopicCheck {
    private TopicCheck() { }

    private static final ObjectMapper M = new ObjectMapper();

    public enum Answer { YES, PARTLY, NO, UNCHECKED }

    /** What the judge said, and its one line; for {@link Answer#UNCHECKED} the line says why no verdict came. */
    public record Verdict(Answer answer, String line) {
        public Verdict { line = line == null ? "" : line.strip(); }
        public boolean yes() { return answer == Answer.YES; }
        public boolean partly() { return answer == Answer.PARTLY; }
        public boolean no() { return answer == Answer.NO; }
        /** Whether the judge gave a verdict at all. */
        public boolean checked() { return answer != Answer.UNCHECKED; }
        /** yes, partly, no or unchecked: the word the ledgers and the job record carry. */
        public String word() { return answer.name().toLowerCase(Locale.ROOT); }
        static Verdict unchecked(String why) { return new Verdict(Answer.UNCHECKED, why); }
    }

    /** The one call: the messages in, the model's reply out. A run asks its judge's seat; the review asks the model it reviews with. */
    public interface Asker { String ask(ArrayNode messages, int maxTokens) throws Exception; }

    /** How much of the answer's first section the judge reads, and how many sub-questions. */
    static final int ANSWER_CHARS = 3_000, SUB_QUESTIONS = 12, QUESTION_CHARS = 4_000;

    /** What the model is asked to do, in the words the decline judge reads a suspect reply against. */
    static final String STEP = "say whether a report's answer is about the question it was written for, as JSON";

    /**
     * The judge's prompt: the question, its sub-questions, and the answer's first section inside a fence, since the answer is text a
     * model wrote from pages. Nothing else of the run goes in.
     */
    // The judge reads the opening alone, so it is asked what the opening is about and never whether the report covers everything asked:
    // asked the second, the 27B called nine of forty good reports of the home library "partly" (every dossier among them, whose lists and
    // timelines sit in later sections), and each would have waited as a draft. Told it sees the opening only, it said yes to forty of forty
    // and to forty of forty more it had not seen, and no to all eighty of the same reports under another report's answer (2026-11-06).
    static String prompt(String question, List<String> subQuestions, String answer) {
        StringBuilder b = new StringBuilder("A research report was written to answer a question. You check one thing: whether the report's answer is about what was asked.\n\nTHE QUESTION:\n")
                .append(Acquisitions.compress(question == null ? "" : question.strip(), QUESTION_CHARS)).append('\n');
        List<String> subs = new ArrayList<>();
        if (subQuestions != null) for (String s : subQuestions) if (s != null && !s.isBlank() && subs.size() < SUB_QUESTIONS) subs.add(Acquisitions.compress(s.strip(), 240));
        if (!subs.isEmpty()) { b.append("\nITS SUB-QUESTIONS:\n"); for (String s : subs) b.append("- ").append(s).append('\n'); }
        b.append("\nTHE FIRST SECTION OF THE REPORT'S ANSWER, as its writer wrote it:\n").append(Fence.wrap("ANSWER", cut(answer))).append('\n').append(Fence.rule("ANSWER"))
         .append("\n\nYou are shown the opening section only. The report goes on after it with more sections, tables and lists that are not shown here, so what the opening leaves out may be answered further on. Judge one thing: what the opening is about.\n"
                 + "Read the question's own terms: its subject, the things it names, what it wants to know. Then read the opening.\n"
                 + "yes: the opening is about the question's subject. An opening that sums up, that says little was found, or that the finding is negative, is still about the question.\n"
                 + "partly: the opening starts on the question's subject and gives most of its words to another subject.\n"
                 + "no: the opening is about another subject than the question's.\n"
                 + "Answer with JSON only: {\"verdict\": \"yes|partly|no\", \"why\": \"<one sentence: what the answer is about, and what the question asks>\"}");
        return b.toString();
    }

    private static String cut(String answer) {
        String a = answer == null ? "" : answer.strip();
        return a.length() <= ANSWER_CHARS ? a : a.substring(0, ANSWER_CHARS) + " …";
    }

    /**
     * Ask the judge. A model that declined is passed on as that ({@link Declined}), and a stop as a stop; anything else that keeps a
     * verdict from coming, an error, an empty reply, a reply with no verdict in it, is {@link Answer#UNCHECKED} with the reason.
     */
    public static Verdict ask(Asker asker, String question, List<String> subQuestions, String answer) {
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) return Verdict.unchecked("there was no question or no answer to compare");
        ArrayNode msgs = M.createArrayNode();
        msgs.addObject().put("role", "user").put("content", prompt(question, subQuestions, answer));
        String raw;
        try (var seat = RunTrace.stage(RunTrace.TOPIC_CHECK); var step = Declines.step(STEP)) {
            raw = asker.ask(msgs, 300);
        } catch (Declined | Stopping.Requested passedOn) {
            throw passedOn;
        } catch (Exception e) {
            return Verdict.unchecked("the judge could not be asked (" + (e.getMessage() == null ? e.getClass().getSimpleName() : Acquisitions.compress(e.getMessage(), 160)) + ")");
        }
        return parse(raw);
    }

    private static final Pattern WORD = Pattern.compile("(?i)\\b(yes|partly|no)\\b");

    /** The verdict in a reply: the JSON's own, else a reply that is one of the three words and nothing more; anything else is no verdict. */
    static Verdict parse(String raw) {
        String r = raw == null ? "" : raw.strip();
        if (r.isEmpty()) return Verdict.unchecked("the judge gave no answer");
        int a = r.indexOf('{'), b = r.lastIndexOf('}');
        if (a >= 0 && b > a) {
            try {
                JsonNode j = M.readTree(r.substring(a, b + 1));
                String v = j.path("verdict").asText("").strip().toLowerCase(Locale.ROOT);
                String why = Acquisitions.compress(j.path("why").asText("").replaceAll("\\s+", " ").strip(), 300);
                Answer answer = switch (v) { case "yes" -> Answer.YES; case "partly", "partially", "in part" -> Answer.PARTLY; case "no" -> Answer.NO; default -> null; };
                if (answer != null) return new Verdict(answer, why);
            } catch (IOException notJson) {
                // read as words below
            }
        }
        Matcher w = WORD.matcher(r);
        if (r.length() <= 12 && w.find()) return new Verdict(Answer.valueOf(w.group(1).toUpperCase(Locale.ROOT)), "");
        return Verdict.unchecked("the judge's reply held no verdict; it began: " + Acquisitions.compress(r.replaceAll("\\s+", " "), 120));
    }

    /** The first section of a write-up as the writer's tool kept it: the one headed Answer when there is one, else the first. */
    static String firstSection(List<String> sections) {
        if (sections == null || sections.isEmpty()) return "";
        for (String s : sections) if (s.startsWith("## Answer\n") || s.equals("## Answer")) return s;
        return sections.get(0);
    }

    static final String FRAME_ANSWER = "## Answer (as submitted by the run";
    static final String WORKERS_APPENDIX = "\n## Worker findings";

    /**
     * The first section of the answer in a report's body, as it stands on the shelves: what follows the library's own "Answer (as
     * submitted by the run …)" heading, the write-up's own first heading included, up to the next section; for a body with no such
     * frame, its section headed Answer. A line the library put in brackets before the write-up (a synthesis that was cut off) is passed
     * over. "" when the body has no answer of its own: none at all, or the sentence that says the synthesis produced none.
     */
    static String answerIn(String body) {
        if (body == null) return "";
        int at = body.indexOf(FRAME_ANSWER);
        String rest;
        if (at >= 0) { int nl = body.indexOf('\n', at); rest = nl < 0 ? "" : body.substring(nl + 1); }
        else {
            Matcher m = Pattern.compile("(?m)^## Answer[^\\n]*\\n").matcher(body);
            if (!m.find()) return "";
            rest = body.substring(m.end());
        }
        for (String appendix : new String[]{WORKERS_APPENDIX, "\n## Sources cited"}) { int cut = rest.indexOf(appendix); if (cut >= 0) rest = rest.substring(0, cut); }
        rest = rest.stripLeading();
        if (rest.startsWith("(the synthesis step did not produce an answer")) return "";
        while (rest.startsWith("(")) {   // the library's own bracketed line before the write-up
            int para = rest.indexOf("\n\n");
            if (para < 0 || !rest.substring(0, para).strip().endsWith(")")) break;
            rest = rest.substring(para).stripLeading();
        }
        int from = rest.startsWith("## ") ? rest.indexOf('\n') + 1 : 0;   // the write-up's own first heading belongs to its first section
        if (from <= 0 && rest.startsWith("## ")) return "";
        int next = rest.indexOf("\n## ", from);
        return (next < 0 ? rest : rest.substring(0, next)).strip();
    }

    /** The question in a report's body: the first paragraph under its Question heading; "" when the body has none. */
    static String questionIn(String body) {
        if (body == null) return "";
        Matcher m = Pattern.compile("(?m)^## Question[^\\n]*\\n").matcher(body);
        if (!m.find()) return "";
        String rest = body.substring(m.end()).stripLeading();
        int next = rest.indexOf("\n## ");
        if (next >= 0) rest = rest.substring(0, next);
        int para = rest.indexOf("\n\n");   // the lines the library adds under the question (what the run let in, who researched it) are not the question
        return (para < 0 ? rest : rest.substring(0, para)).strip();
    }

    /** The sub-questions a report's workers were given, from the readers' notes attached to it. */
    static List<String> subQuestionsIn(String body) {
        List<String> out = new ArrayList<>();
        if (body == null) return out;
        int at = body.indexOf(WORKERS_APPENDIX);
        Matcher m = Pattern.compile("(?m)^SUB-QUESTION: (.+)$").matcher(at < 0 ? body : body.substring(at));
        while (m.find() && out.size() < SUB_QUESTIONS) { String q = Researcher.untagged(m.group(1).strip()); if (!q.isEmpty() && !out.contains(q)) out.add(q); }
        return out;
    }
}
