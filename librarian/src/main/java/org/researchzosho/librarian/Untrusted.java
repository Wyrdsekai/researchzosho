package org.researchzosho.librarian;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Text a source wrote, made safe to quote to a model: nothing in it can pass for the library's fences or for a chat template's markers,
 * and a sentence shaped like an instruction to the model (ignore the instructions above, you must call this tool, a note to the assistant)
 * is set aside with a mark saying so. The fence and the rule beside it say the text is evidence; this takes away the words that would
 * speak in another voice. It is defence in depth, as the ops localizer's is for logs: the control is that research only ever chooses
 * among the tools on its table, and a tool that changes things is not on it.
 */
public final class Untrusted {
    private Untrusted() { }

    /** What a sentence shaped like an instruction becomes. */
    public static final String SET_ASIDE = "[set aside: words shaped like an instruction to the model]";

    /** The openings of an instruction to a model, each taken with the rest of its sentence. */
    static final Pattern INSTRUCTION = Pattern.compile("(?i)(?:" + String.join("|", List.of(
            "\\bignore\\s+(?:all\\s+|any\\s+|every\\s+)?(?:of\\s+)?(?:the\\s+|your\\s+|my\\s+)?(?:previous|prior|above|earlier|preceding|other|former)\\b",
            "\\bdisregard\\s+(?:all\\s+|any\\s+)?(?:of\\s+)?(?:the\\s+|your\\s+)?(?:above|previous|prior|earlier|preceding|instructions?|rules|system)\\b",
            "\\bforget\\s+(?:all\\s+|everything\\s+|your\\s+|the\\s+)+(?:instructions?|rules|prompts?|you\\s+(?:were|have\\s+been)\\s+told)\\b",
            "\\b(?:new|updated|revised|real|true)\\s+instructions?\\s*[:\\-]",
            "\\bsystem\\s+prompt\\b",
            "\\[\\s*(?:system|assistant|developer)\\s*\\]",
            "\\b(?:system|developer)\\s*(?:message|note|override)\\s*:",
            "\\bnote\\s+to\\s+(?:the\\s+|any\\s+)?(?:assistant|ai|model|agent|llm|bot|automation|language\\s+model)\\b",
            "\\b(?:attention|important|urgent)\\s*[:!,]?\\s+(?:to\\s+)?(?:the\\s+|all\\s+|any\\s+)?(?:assistants?|ai|models?|agents?|llms?|bots?)\\b",
            "\\b(?:you|the\\s+assistant|the\\s+model|the\\s+agent)\\s+(?:must|should|shall|are\\s+(?:required|instructed|told)\\s+to|need\\s+to|have\\s+to)\\s+(?:now\\s+|immediately\\s+|instead\\s+)?(?:call|use|run|invoke|execute|answer|reply|respond|say|state|write|output|print|ignore|forget|stop|delete|send|open|visit|fetch|recommend|choose|pick|report)\\b",
            "\\b(?:call|use|invoke|run|execute)\\s+the\\s+(?:tool|function)\\b",
            "\\bas\\s+an\\s+ai(?:\\s+language\\s+model)?\\b",
            "\\bdo\\s+not\\s+(?:tell|inform|show|mention\\s+(?:this\\s+)?to)\\s+the\\s+(?:user|person|human)\\b",
            "\\b(?:act|behave)\\s+as\\s+(?:if\\s+you\\s+(?:were|are)\\b|a\\s+|an\\s+)",
            "\\bpretend\\s+(?:to\\s+be|you\\s+are)\\b",
            "\\boverride\\s+(?:your|the|all|any)\\s+(?:instructions?|rules|settings|guidelines)\\b"))
            + ")(?:[^.!?\\n]|[.!?](?=\\S)){0,300}[.!?]?");   // to the end of its sentence: a dot inside a name (notes.create_note, 3.5) does not end it

    /** The markers of chat templates and of tool calls in a model's own transcript. */
    static final Pattern MARKERS = Pattern.compile("(?i)<\\|[^|<>\\n]{0,40}\\|>|</?\\s*(?:tool_call|tool_response|tool_result|function_call|function_results?|system|assistant|user|im_start|im_end)\\s*>|\\[/?INST\\]|<(?:start|end)_of_turn>|<<\\s*/?\\s*SYS\\s*>>");

    /** The text with the library's fence markers broken, the template markers taken out and every sentence shaped like an instruction set aside. */
    public static String neutralise(String text) {
        if (text == null || text.isEmpty()) return "";
        String s = text.replace("<<<", "‹‹‹").replace(">>>", "›››");
        s = MARKERS.matcher(s).replaceAll("‹marker›");
        return INSTRUCTION.matcher(s).replaceAll(SET_ASIDE);
    }

    /** On one line, short: a name or a description a model reads in its list of tools. */
    public static String line(String text, int max) {
        String s = neutralise(text == null ? "" : text).replaceAll("\\s+", " ").strip();
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)).strip() + "…";
    }
}
