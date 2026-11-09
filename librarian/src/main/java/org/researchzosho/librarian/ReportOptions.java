package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The options a report is written with (0.7.0): its length, the style of its citations and its shape. A run takes them from its own ask
 * (the chat's words, the command line, the protocol's research op, the web's research form) and, for what the ask leaves out, from the
 * library's settings report_length and citation_style. The writer gets each option's rules on top of the report's basis ({@link
 * ReportBasis#RULES}): a reading list's items carry their links, a short report has its checks and its sources, and the numbers in the
 * text stay whatever the style of the sources, since the checks read a cited sentence by its number. A question that asks for a format in
 * its own words wins over an option of the same kind. The report records the options it was written with in its front matter.
 */
public record ReportOptions(String length, String citations, String shape) {

    public static final ReportOptions NONE = new ReportOptions("", "", "");
    public static final String LENGTH_SETTING = "RESEARCHZOSHO_REPORT_LENGTH", CITATIONS_SETTING = "RESEARCHZOSHO_CITATION_STYLE";
    public static final List<String> LENGTHS = List.of("short", "medium", "long");
    public static final List<String> CITATIONS = List.of("numbered", "apa", "mla", "chicago", "ieee");
    public static final List<String> SHAPES = List.of("report", "reading-list", "outline");
    static final int MIN_WORDS = 100, MAX_WORDS = 20_000;

    static final String LENGTH_TAKES = "length is short (about 400 words), medium (about 1,200), long (about 3,000), or a number of words from 100 to 20,000, for example 800.";
    static final String CITATIONS_TAKES = "citations is numbered (the default), apa, mla, chicago or ieee.";
    static final String SHAPE_TAKES = "shape is report (the default), reading-list (each source with what it is, why it matters and how strong it is) or outline (headings with one line each).";

    public ReportOptions {
        length = length == null ? "" : length; citations = citations == null ? "" : citations; shape = shape == null ? "" : shape;
    }

    public boolean isEmpty() { return length.isEmpty() && citations.isEmpty() && shape.isEmpty(); }

    // ---- the values, as a person or a program writes them ----

    /** A length: short, medium, long, or a number of words ("800", "800 words", "about 800"); "" for none; null when it is none of these. */
    public static String length(String v) {
        String t = v == null ? "" : v.strip().toLowerCase(Locale.ROOT).replace(",", "");
        if (t.isEmpty()) return "";
        if (LENGTHS.contains(t)) return t;
        Matcher m = Pattern.compile("^(?:about |around |roughly )?(\\d{1,6})(?:\\s*words?)?$").matcher(t);
        if (m.matches()) { int n = Integer.parseInt(m.group(1)); return n >= MIN_WORDS && n <= MAX_WORDS ? String.valueOf(n) : null; }
        return switch (t) { case "brief", "concise" -> "short"; case "medium-length", "normal" -> "medium"; case "detailed", "in-depth", "full" -> "long"; default -> null; };
    }

    /** A citation style: numbered, apa, mla, chicago or ieee ("APA 7th", "Chicago style"); "" for none; null when it is another. */
    public static String citations(String v) {
        String t = v == null ? "" : v.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "").replaceAll("(?:style|format|citations?|references?|edition|th)+$", "");
        if (t.isEmpty()) return "";
        if (CITATIONS.contains(t)) return t;
        return t.equals("number") || t.equals("numbers") || t.equals("numeric") ? "numbered" : null;
    }

    /** A shape: report, reading-list (an annotated bibliography) or outline; "" for none; null when it is another. */
    public static String shape(String v) {
        String t = v == null ? "" : v.strip().toLowerCase(Locale.ROOT).replace('_', '-').replaceAll("\\s+", "-").replaceFirst("^(?:a|an)-", "");
        if (t.isEmpty()) return "";
        return switch (t) { case "report" -> "report"; case "reading-list", "readinglist", "annotated-bibliography", "bibliography" -> "reading-list"; case "outline" -> "outline"; default -> null; };
    }

    /** The options a protocol call names, checked: a value that is none of its option's is refused with a sentence that says what it takes. */
    public static ReportOptions of(JsonNode args) {
        String l = length(text(args, "length")), c = citations(text(args, "citations")), s = shape(text(args, "shape"));
        if (l == null) throw ProtocolError.invalidArgs(LENGTH_TAKES);
        if (c == null) throw ProtocolError.invalidArgs(CITATIONS_TAKES);
        if (s == null) throw ProtocolError.invalidArgs(SHAPE_TAKES);
        return new ReportOptions(l, c, s);
    }

    private static String text(JsonNode a, String key) {
        JsonNode v = a == null ? null : a.get(key);
        return v == null || v.isNull() || v.isMissingNode() ? "" : v.isNumber() ? String.valueOf(v.asLong()) : v.asText("");
    }

    /** The library's own defaults: the settings report_length and citation_style; a value neither can be is left out. */
    public static ReportOptions defaults() {
        String l = length(Config.get(LENGTH_SETTING, "")), c = citations(Config.get(CITATIONS_SETTING, ""));
        return new ReportOptions(l == null ? "" : l, c == null ? "" : c, "");
    }

    /** The options a run is written with: what its arguments name, and for what they leave out, the library's defaults. */
    public static ReportOptions forRun(JsonNode args) {
        ReportOptions asked;
        try { asked = of(args); } catch (ProtocolError e) { asked = NONE; }   // a job filed with a value the checks refuse now: the defaults
        return asked.over(defaults());
    }

    /** These options, and for what they leave out, the other's. */
    public ReportOptions over(ReportOptions under) {
        return new ReportOptions(length.isEmpty() ? under.length : length, citations.isEmpty() ? under.citations : citations, shape.isEmpty() ? under.shape : shape);
    }

    /** The options into a job's arguments or a result: each one set, by its name. */
    public void put(ObjectNode o) {
        if (!length.isEmpty()) o.put("length", length);
        if (!citations.isEmpty()) o.put("citations", citations);
        if (!shape.isEmpty()) o.put("shape", shape);
    }

    public ObjectNode json() { ObjectNode o = new ObjectMapper().createObjectNode(); put(o); return o; }

    // ---- the person's own words in the chat ----

    private static final Pattern SHORT = Pattern.compile("(?iu)\\b(?:short|shorter|brief|quick|concise)\\s+(?:report|answer|summary|write-?up|overview|version|one)\\b|\\bkeep it (?:short|brief)\\b"
            + "|短い(?:レポート|報告|まとめ)|簡潔に|手短に");
    private static final Pattern LONG = Pattern.compile("(?iu)\\b(?:long|longer|detailed|thorough|comprehensive|in-depth|lengthy|full)\\s+(?:report|answer|write-?up|overview)\\b"
            + "|詳しい(?:レポート|報告)|詳細な(?:レポート|報告)");
    private static final Pattern MEDIUM = Pattern.compile("(?iu)\\bmedium[- ](?:length |sized )?(?:report|answer|write-?up)\\b");
    private static final Pattern WORDS = Pattern.compile("(?iu)\\b(?:in|under|at most|no more than|maximum of|max|about|around|roughly|of)\\s+(\\d{1,2},\\d{3}|\\d{2,5})\\s+words\\b"
            + "|\\b(\\d{1,2},\\d{3}|\\d{2,5})[- ]word\\s+(?:report|summary|answer|write-?up|overview|essay)\\b");
    private static final Pattern READING = Pattern.compile("(?iu)\\breading list\\b|\\bannotated bibliography\\b|\\bwhat (?:should|could|can) i read\\b|読書リスト|文献リスト");
    private static final Pattern OUTLINE = Pattern.compile("(?iu)\\b(?:an|just an|only an|as an|in)\\s+outline\\b|\\boutline (?:only|form)\\b|アウトライン");
    private static final Pattern STYLE = Pattern.compile("(?iu)\\b(apa|mla|chicago|ieee)\\b(?:[- ](?:style|format))?\\s*(?:citations?|references?|referencing|bibliography|format|style)\\b"
            + "|\\b(?:cite|citations?|references?|referencing|bibliography)\\b[^.?!\\n]{0,30}?\\b(apa|mla|chicago|ieee)\\b"
            + "|\\b(numbered) (?:citations?|references?)\\b");

    /**
     * What a person's own words ask of a report: "a short report", "give me a reading list on …", "just an outline", "use APA citations",
     * "in under 500 words". Only the phrases that ask for a report's form count: a city named Chicago, or "outline the history", asks nothing.
     */
    public static ReportOptions fromWords(String words) {
        String w = words == null ? "" : words;
        String length = "";
        Matcher n = WORDS.matcher(w);
        if (n.find()) { String l = length((n.group(1) != null ? n.group(1) : n.group(2))); if (l != null) length = l; }
        if (length.isEmpty()) length = SHORT.matcher(w).find() ? "short" : LONG.matcher(w).find() ? "long" : MEDIUM.matcher(w).find() ? "medium" : "";
        String shape = READING.matcher(w).find() ? "reading-list" : OUTLINE.matcher(w).find() ? "outline" : "";
        String style = "";
        Matcher s = STYLE.matcher(w);
        if (s.find()) style = (s.group(1) != null ? s.group(1) : s.group(2) != null ? s.group(2) : s.group(3)).toLowerCase(Locale.ROOT);
        return new ReportOptions(length, style, shape);
    }

    // ---- a question that asks for a format itself ----

    /**
     * The options a question's own format words leave standing ({@code asked}: the format the question asked for, as the run read it off
     * the question). The question wins: an option is left out when the question asks for another value of its kind.
     */
    public ReportOptions under(String asked) {
        if (asked == null || asked.isBlank()) return this;
        String a = asked.toLowerCase(Locale.ROOT), al = lengthIn(a), as = shapeIn(a), ac = styleIn(a);
        return new ReportOptions(length.isEmpty() || al.isEmpty() || al.equals(length) ? length : "",
                citations.isEmpty() || ac.isEmpty() || ac.equals(citations) ? citations : "",
                shape.isEmpty() || as.isEmpty() || as.equals(shape) ? shape : "");
    }

    static String lengthIn(String a) {
        Matcher m = Pattern.compile("(\\d{1,2},\\d{3}|\\d{2,5})\\s*words?").matcher(a);
        if (m.find()) { String l = length(m.group(1)); return l == null ? "other" : l; }
        if (Pattern.compile("\\b(?:short|brief|concise|succinct|summary|one paragraph|a paragraph|one page|tl;?dr|quick)\\b").matcher(a).find()) return "short";
        if (Pattern.compile("\\b(?:long|detailed|in[- ]depth|comprehensive|thorough|lengthy|extensive|two pages|several pages)\\b").matcher(a).find()) return "long";
        if (a.contains("medium")) return "medium";
        return "";
    }

    static String shapeIn(String a) {
        if (Pattern.compile("reading list|bibliograph").matcher(a).find()) return "reading-list";
        if (a.contains("outline")) return "outline";
        if (Pattern.compile("\\b(?:table|tabular|list|bullets?|timeline|chronolog\\w*|by year|per year|compar\\w*|side by side|rank\\w*|essay|memo|faq|q&a|step by step|steps)\\b").matcher(a).find()) return "other";
        return "";
    }

    static String styleIn(String a) {
        Matcher m = Pattern.compile("\\b(apa|mla|chicago|ieee|harvard|vancouver|footnotes?|numbered)\\b").matcher(a);
        if (!m.find()) return "";
        String s = m.group(1);
        return CITATIONS.contains(s) ? s : "other";
    }

    // ---- the writer's rules ----

    /** About how many words the options ask for, before the checks and the sources; 0 for no length. */
    public int words() {
        return switch (length) { case "" -> 0; case "short" -> 400; case "medium" -> 1200; case "long" -> 3000; default -> Integer.parseInt(length); };
    }

    /** The most characters a section of the report takes: shorter for a short report, longer for a long one. */
    public int sectionChars() {
        int w = words();
        return w == 0 ? 1500 : w <= 600 ? 900 : w > 2000 ? 2500 : 1500;
    }

    /** The writer's rules for these options, on top of the report's basis, as positive directives; "" when there are none. */
    public String rules() {
        StringBuilder b = new StringBuilder();
        int w = words();
        if (w > 0) {
            String parts = w <= 600 ? "the Answer in two to four sentences, then at most three short sections" : w <= 2000 ? "in four to eight sections" : "in eight to fourteen sections that go into each part of the ask in depth, each up to 2,500 characters";
            String what = switch (length) { case "short" -> "a short report, about 400 words"; case "medium" -> "a report of medium length, about 1,200 words"; case "long" -> "a long report, about 3,000 words"; default -> "a report of about " + String.format(Locale.ROOT, "%,d", w) + " words"; };
            b.append(" THE LENGTH THE PERSON ASKED FOR: ").append(what).append(" in all the sections before '").append(ReportBasis.CHECKED_HEADING).append("', ").append(parts)
             .append(". '").append(ReportBasis.CHECKED_HEADING).append("' and the Sources section come after them as always, with every point the ask named and every source you cite.");
        }
        switch (shape) {
            case "reading-list" -> b.append(" THE SHAPE THE PERSON ASKED FOR: a reading list, an annotated bibliography, in place of the sections per sub-question above. Write 'Answer' first, in two or three sentences: ")
                    .append("what the list covers and where to start. Then a section 'Reading list': one list line per source worth reading, the most useful first, each line starting with the source's title as its link, ")
                    .append("then what it is (a study, an official page, a news story, a book, a talk), why it matters for the question, how strong it is (a primary source, peer reviewed, an official record, ")
                    .append("a company's own claim, an opinion) and its number in square brackets. For example: - [How the gears were cut](https://example.org/gears) — a museum's page on the workshop; it shows how the ")
                    .append("teeth were made; strong: it describes the object itself [3]. Then '").append(ReportBasis.CHECKED_HEADING).append("' and the Sources section, as always.");
            case "outline" -> b.append(" THE SHAPE THE PERSON ASKED FOR: an outline, in place of the sections per sub-question above. Write 'Answer' first, in one or two sentences. Then one section per part of the ")
                    .append("answer: its heading, and one line under it that says what that part holds, with its source numbers in square brackets. Then '").append(ReportBasis.CHECKED_HEADING)
                    .append("' and the Sources section, as always.");
            default -> { }
        }
        String keep = "In the text, keep the source's number in square brackets, [3], right after the clause it supports: the library checks every cited sentence against its source by that number and links it. "
                + "In the Sources section write one line per source you relied on, starting with its number, then the full reference in ";
        switch (citations) {
            case "numbered" -> b.append(" THE CITATIONS THE PERSON ASKED FOR: numbered. In the text, the source's number in square brackets, [3], right after the clause it supports; in the Sources section one line ")
                    .append("per source: its number, its title and its address. For example: [3] How the gears were cut — https://example.org/gears");
            case "apa" -> b.append(" THE CITATIONS THE PERSON ASKED FOR: APA style (7th edition) in the Sources section. ").append(keep).append("APA style: the author or the organisation, the date in brackets ")
                    .append("(year, month day), the title, the site's name, and the address. A source with no date gets (n.d.). For example: [3] Example Museum. (2025, March 2). How the gears were cut. Example Museum. https://example.org/gears");
            case "mla" -> b.append(" THE CITATIONS THE PERSON ASKED FOR: MLA style (9th edition) in the Sources section. ").append(keep).append("MLA style: the author or the organisation, the title in quotation ")
                    .append("marks, the site's name, the day month year, and the address. For example: [3] Example Museum. \"How the Gears Were Cut.\" Example Museum, 2 Mar. 2025, https://example.org/gears.");
            case "chicago" -> b.append(" THE CITATIONS THE PERSON ASKED FOR: Chicago style (notes and bibliography) in the Sources section. ").append(keep).append("Chicago style: the author or the organisation, ")
                    .append("the title in quotation marks, the site's name, the month day, year, and the address. For example: [3] Example Museum. \"How the Gears Were Cut.\" Example Museum. March 2, 2025. https://example.org/gears.");
            case "ieee" -> b.append(" THE CITATIONS THE PERSON ASKED FOR: IEEE style in the Sources section. ").append(keep).append("IEEE style: the author or the organisation, the title in quotation marks, ")
                    .append("the site's name, the month day, year, then [Online] and Available: with the address. For example: [3] Example Museum, \"How the gears were cut,\" Example Museum, Mar. 2, 2025. [Online]. Available: https://example.org/gears");
            default -> { }
        }
        return b.toString();
    }

    /** The options as a person reads them: "short, with APA citations, as a reading list"; "" when there are none. */
    public String described() {
        List<String> p = new ArrayList<>();
        if (!length.isEmpty()) p.add(switch (length) { case "short" -> "short"; case "medium" -> "of medium length"; case "long" -> "long"; default -> "about " + String.format(Locale.ROOT, "%,d", words()) + " words"; });
        if (!citations.isEmpty()) p.add("with " + (citations.equals("numbered") ? "numbered" : citations.equals("chicago") ? "Chicago" : citations.toUpperCase(Locale.ROOT)) + " citations");
        if (!shape.isEmpty()) p.add(switch (shape) { case "reading-list" -> "as a reading list"; case "outline" -> "as an outline"; default -> "as a report"; });
        return String.join(", ", p);
    }
}
