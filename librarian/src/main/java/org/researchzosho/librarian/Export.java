package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.ExportDoc.Block;
import org.researchzosho.librarian.ExportDoc.Heading;
import org.researchzosho.librarian.ExportDoc.Para;
import org.researchzosho.librarian.ExportDoc.Span;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An entry, a report or a dossier as a file to keep or send. An entry, or a reading of it: Markdown as it is, or a PDF. A report or a
 * dossier (0.7.0): a PDF ({@link ExportPdf}) or a Word file ({@link ExportDocx}) that holds what its page shows, in the language the page
 * is read in: a report's title, question, the answer's sections, how each point was checked, the open questions and sub-goals, the
 * library's checks, the claims with their marks and the sources with links the reader clicks; a dossier's sections, the Reading first.
 * The file is a rendering of the record; the record stays the file in the library. The protocol's export keeps the file under
 * catalog/exports/, where the web pages serve it at /exports/&lt;name&gt;.
 */
public final class Export {

    private Export() { }

    public record File(String name, String contentType, byte[] bytes) { }

    /**
     * A report or a dossier made into a file, with what a person is told of it: the language it was written in, the language of the file
     * when it is a translation, a sentence when the file is not what was asked (no translation kept yet), and how many different characters
     * no font on this computer could show (a PDF says which on its first page).
     */
    public record Made(File file, String kind, String writtenIn, String readIn, String note, int unshown) {
        public boolean translated() { return !readIn.isEmpty(); }
    }

    public static final String PDF = "pdf", DOCX = "docx", MARKDOWN = "md";

    /** A format as a person or a program names it: pdf, docx (word, doc), or md (markdown); null when it is none of them. */
    public static String format(String f) {
        String t = f == null ? "" : f.strip().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        return switch (t) { case "pdf" -> PDF; case "docx", "word", "doc", "msword" -> DOCX; case "md", "markdown" -> MARKDOWN; default -> null; };
    }

    /** The three formats, as the error that refuses another names them. */
    static final String FORMATS = "pdf, docx (Word) or md (Markdown)";

    static String contentType(String format) {
        return PDF.equals(format) ? "application/pdf" : MARKDOWN.equals(format) ? "text/markdown; charset=utf-8" : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    /** The Markdown document for an entry (as written) or a reading of it at a rung. */
    /** {@code brief}: the answer alone — the report's sections without the run's own checks, evidence and worker findings — and no claims list. */
    public static File markdown(LibraryStore store, String id, Explain.Reading reading, boolean brief) throws IOException {
        BRIEF.set(brief);
        try { return markdown(store, id, reading); } finally { BRIEF.remove(); }
    }

    public static File pdf(LibraryStore store, String id, Explain.Reading reading, boolean brief) throws IOException {
        BRIEF.set(brief);
        try { return pdf(store, id, reading); } finally { BRIEF.remove(); }
    }

    public static File docx(LibraryStore store, String id, Explain.Reading reading, boolean brief) throws IOException {
        BRIEF.set(brief);
        try { return docx(store, id, reading); } finally { BRIEF.remove(); }
    }

    private static final ThreadLocal<Boolean> BRIEF = new ThreadLocal<>();

    public static File markdown(LibraryStore store, String id, Explain.Reading reading) throws IOException {
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode e = p.entry(id, p.kindOf(id), true);
        if (e == null) throw ProtocolError.notFound("entry " + id);
        String md = document(store, e, reading);
        String stem = id + (reading == null || reading.rung() == Explain.Rung.written ? "" : "-" + reading.rung().name());
        return new File(stem + ".md", "text/markdown; charset=utf-8", forAViewer(md).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A picture of this library's own, written whole with its address: its words (no picture of their own in them, and nothing that
     * begins a tag), a path of the library's with no backslash and no second slash before the host's place, and after the path the
     * title an ordinary writer of Markdown may give a picture, in quotes. Whether it is kept is decided on what was matched
     * ({@link #own}). Only this form is kept: a picture of the library's own written by a reference ({@code ![words][ref]}, the
     * address on a line of its own elsewhere) comes out as a link to the same file.
     *
     * <p>The words are read by hand ({@link #wordsEnd}) and the rest by this pattern. One pattern read both until the second reading
     * of 0.8.1, and it read the words one level deeper for each character: a picture's words of some 1,800 characters, or as many
     * after a mark on a line with no closing bracket, used the thread's stack up and no file was written.
     */
    private static final Pattern OWN_PICTURE_ADDRESS = Pattern.compile("\\]\\([ \\t]*(/(?![/\\\\])[^)\\s\\\\]*)(?:[ \\t]+(?:\"[^\"<\\[\\]\\n]*\"|'[^'<\\[\\]\\n]*'))?[ \\t]*\\)");

    /**
     * Where the words of a picture end whose mark stands at {@code mark} ("![" there): the place of the bracket that closes them, or
     * -1 when what follows is no picture's words of the kind an own picture has. The words hold no line's end and nothing that
     * begins a tag; no picture of their own (a mark before a bracket); a pair of brackets with no bracket inside it is part of them.
     */
    private static int wordsEnd(String text, int mark) {
        for (int i = mark + 2; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ']') return i;
            if (c == '\n' || c == '<') return -1;
            if (c == '!' && i + 1 < text.length() && text.charAt(i + 1) == '[') return -1;
            if (c == '[') {
                int j = i + 1;
                while (j < text.length() && "[]<\n".indexOf(text.charAt(j)) < 0) j++;
                if (j >= text.length() || text.charAt(j) != ']') return -1;
                i = j;
            }
        }
        return -1;
    }
    /** A run of the marks that make what follows a picture. */
    private static final Pattern PICTURE_MARK = Pattern.compile("!+(?=\\[)");
    /** A bracket's end after which a picture's address or its reference may stand (blanks or one line's end between, as the older readers allow). */
    private static final Pattern COULD_CLOSE_A_PICTURE = Pattern.compile("\\][ \\t]*\\n?[ \\t]*[(\\[]");
    /**
     * A bracket that could begin a tag, a comment or an instruction; and the one kind of tag that is left as it stands: one that
     * holds no attribute's value, read as a browser reads a tag (from the bracket to the first closing one, the name up to a space
     * or a slash). A tag asks an address only through a value, and one without a value has none: the {@code <String>} of
     * {@code List<String>}, {@code <br>}, {@code </p>}, {@code <String, List<String>}. A name whose own content is read as code or
     * as a style sheet, or that sets where a page's addresses lead, is never left; nor is a bracket that no closing one follows.
     */
    private static final Pattern TAG_START = Pattern.compile("<(?=[A-Za-z/!?])"), BARE_TAG = Pattern.compile("</?([A-Za-z][^\\s/>=]*)[^>=]*>");
    private static final Set<String> NEVER_BARE = Set.of("script", "style", "base", "link", "meta", "iframe", "frame", "frameset", "object", "embed", "applet", "svg", "math", "template",
            "plaintext", "xmp", "textarea", "title", "noscript", "noembed", "noframes", "html", "head", "body");

    /**
     * Whether a picture written as the library's own ({@link #OWN_PICTURE_ADDRESS}) is one the file keeps: its path is a kept picture's by its form
     * ({@code Pages.keptPicturePath}: under {@code /images/}, no query, nothing that climbs out), the one kind of path of this
     * library that is a picture on its own pages too; and the path holds nothing a reader of markup could take for the beginning of
     * a tag or the end of a value. Anything else that is dressed as the library's own picture is written as every other picture and
     * tag is (the second reading of 0.8.1: the match was copied as it stood, so a tag written into the path came out whole).
     */
    private static boolean own(Matcher m) {
        String path = m.group(1);
        return Pages.keptPicturePath(path) && path.indexOf('<') < 0 && path.indexOf('>') < 0 && path.indexOf('"') < 0 && path.indexOf('\'') < 0 && path.indexOf('`') < 0;
    }

    /**
     * Markdown as it is written into a file another program will open (an export, the vault an editor reads). Such a program draws a
     * picture, and reads a tag, the moment the file is shown, so the address of either goes out with nobody choosing to follow it; the
     * words are a model's or a page's. A picture that is not one of this library's by its own path is written as the link it holds
     * (the mark before its bracket is left out, whichever way the rest is written: an address, a reference, a title beside it), and the
     * bracket that would begin a tag with an attribute is written as the character it is ({@code &lt;}).
     *
     * <p>This is done wherever the mark or the bracket stands, in code as outside it. Until 0.8.1 code was left as it stood, and what
     * is code was read here by a rule of this method's own (a line of three backticks opens or closes a fence, one backtick to the
     * next is a span): a program that reads Markdown by the CommonMark rules reads it otherwise (a fence of four backticks holds a
     * line of three, a backtick fence holds a line of tildes, a span closes on a run of the same length), and a picture or a tag after
     * such "code" was drawn. No reader of Markdown is on this program's class path, so nothing here decides what is code. The cost
     * is in a sample of code that holds a picture's mark or a tag with attributes: it reads {@code [s](…)} for {@code ![s](…)} and
     * {@code &lt;a href=…>} for {@code <a href=…>}.
     *
     * <p>A mark that can begin no picture stays, for it is the writer's own exclamation mark ("What a crossing![1]"). It can
     * begin none when two things hold, whoever reads the file: the file holds no reference's definition ({@code ]:} nowhere in it),
     * and from the mark to the end of its paragraph no bracket closes before a round or a square one. A picture needs one of the
     * two after its mark: its address in round brackets, or a reference that is defined. In every other case the mark is left out.
     */
    static String forAViewer(String markdown) {
        if (markdown == null) return "";
        if (markdown.indexOf("![") < 0 && markdown.indexOf('<') < 0) return markdown;
        boolean references = markdown.contains("]:");
        StringBuilder out = new StringBuilder(markdown.length() + 64);
        int at = 0;
        for (int[] own : ownPictures(markdown)) { out.append(plain(markdown, at, own[0], references)).append(markdown, own[0], own[1]); at = own[1]; }
        return out.append(plain(markdown, at, markdown.length(), references)).toString();
    }

    /** Where the pictures a file keeps stand in a text, each from its mark to the bracket that closes its address, in order. */
    static List<int[]> ownPictures(String markdown) {
        List<int[]> out = new ArrayList<>();
        Matcher address = OWN_PICTURE_ADDRESS.matcher(markdown);
        for (int mark = markdown.indexOf("!["); mark >= 0; ) {
            int words = wordsEnd(markdown, mark);
            if (words < 0 || !address.region(words, markdown.length()).lookingAt()) { mark = markdown.indexOf("![", mark + 1); continue; }   // no picture of that form
            if (own(address)) out.add(new int[]{mark, address.end()});   // one dressed as the library's own stays in the words around it, and is written as they are
            mark = markdown.indexOf("![", address.end());
        }
        return out;
    }

    /** The words of {@code text} from {@code from} to {@code to}, with no picture's mark and no tag that holds a value; {@code references}: the whole text defines a reference somewhere. */
    private static String plain(String text, int from, int to, boolean references) {
        String words = text.substring(from, to);
        Matcher mark = PICTURE_MARK.matcher(words);
        StringBuilder kept = new StringBuilder(words.length());
        int done = 0;
        while (mark.find()) {
            kept.append(words, done, mark.start());
            if (!references && beginsNoPicture(text, from + mark.end())) kept.append(mark.group());
            done = mark.end();
        }
        String s = kept.append(words, done, words.length()).toString();
        if (s.indexOf('<') < 0) return s;
        StringBuilder out = new StringBuilder(s.length() + 16);
        Matcher tag = TAG_START.matcher(s), bare = BARE_TAG.matcher(s);
        int at = 0;
        while (tag.find()) {
            boolean harmless = bare.region(tag.start(), s.length()).lookingAt() && !NEVER_BARE.contains(bare.group(1).toLowerCase(Locale.ROOT));
            out.append(s, at, tag.start()).append(harmless ? "<" : "&lt;");
            at = tag.end();
        }
        return out.append(s, at, s.length()).toString();
    }

    /**
     * Whether the bracket that opens at {@code bracket} (a mark stands before it) can begin no picture: it is not doubled (the
     * embedding some editors read, {@code ![[…]]}), and from it to the end of its paragraph (an empty line, or the text's end) no
     * bracket closes before a round or a square one. Read on the whole text, not on a piece of it: the words of a kept picture
     * that stands between are words too.
     */
    private static boolean beginsNoPicture(String text, int bracket) {
        if (bracket + 1 < text.length() && text.charAt(bracket + 1) == '[') return false;
        Matcher end = PARAGRAPH_END.matcher(text);
        int until = end.find(bracket) ? end.start() : text.length();
        return !COULD_CLOSE_A_PICTURE.matcher(text).region(bracket, until).find();
    }

    private static final Pattern PARAGRAPH_END = Pattern.compile("\\n[ \\t]*\\n");

    public static File pdf(LibraryStore store, String id, Explain.Reading reading) throws IOException { return laidOut(store, id, reading, PDF); }

    public static File docx(LibraryStore store, String id, Explain.Reading reading) throws IOException { return laidOut(store, id, reading, DOCX); }

    private static File laidOut(LibraryStore store, String id, Explain.Reading reading, String format) throws IOException {
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode e = p.entry(id, p.kindOf(id), true);
        if (e == null) throw ProtocolError.notFound("entry " + id);
        String md = document(store, e, reading);
        String stem = id + (reading == null || reading.rung() == Explain.Rung.written ? "" : "-" + reading.rung().name());
        String footer = store.identity().name() + " · " + id + " · " + LocalDate.now();
        ExportDoc d = ExportDoc.of(e.path("title").asText(id), Translate.languageOf(md), footer, md, Map.of(), src -> picture(store, src));
        return new File(stem + "." + format, contentType(format), PDF.equals(format) ? ExportPdf.write(d).bytes() : ExportDocx.write(d));
    }

    /** A kept picture by its address under this library, with its bytes and the page it came from, for the writers to embed; null for any other address. */
    static ExportDoc.Image picture(LibraryStore store, String src) {
        if (src == null || !src.startsWith("/images/")) return null;
        Path f = Images.kept(store, src.substring("/images/".length()));
        if (f == null) return null;
        Images.Shelved s = Images.entryFor(store, src);
        try { return new ExportDoc.Image(s == null ? "" : s.title(), src, Files.readAllBytes(f), s == null ? "" : s.page()); } catch (IOException e) { return null; }
    }

    /** The document: a title, a line of facts, the text, then the sources; a reading says what it is and rests on. */
    static String document(LibraryStore store, ObjectNode e, Explain.Reading reading) throws IOException {
        StringBuilder sb = new StringBuilder();
        String kind = e.path("kind").asText();
        sb.append("# ").append(e.path("title").asText()).append("\n\n");
        if (reading != null && reading.rung() != Explain.Rung.written) {
            sb.append("*A ").append(reading.rung().name()).append(" reading of ").append(e.path("id").asText()).append(", written by the library from what it holds, ")
              .append("based on the library: ").append("shelves".equals(reading.grounding()) ? "fully" : "thin".equals(reading.grounding()) ? "partly" : reading.grounding())
              .append(reading.checked() > 0 ? " (" + reading.checked() + " paragraphs checked against their sources, " + reading.unsupported() + " not backed up)" : "")
              .append(". A plain-language version, not a record.*\n\n");
            sb.append(reading.text()).append("\n\n");
            if (!reading.terms().isEmpty()) {
                sb.append("## Words you may meet next\n\n");
                for (Explain.Term t : reading.terms()) sb.append("- ").append(t.term()).append(" — ").append(t.gloss()).append('\n');
                sb.append('\n');
            }
            sb.append("## The record it rests on\n\n");
        }
        sb.append("*").append(e.path("id").asText()).append(" · ").append(kind).append(" · ").append(e.path("state").asText());
        if (!"raw".equals(kind)) sb.append(" · confidence ").append(e.path("confidence").asText());
        sb.append(" · by ").append(e.path("writer").asText()).append(" · ").append(e.path("recorded_at").asText().replace('T', ' ').replaceAll("\\.\\d+Z$", " UTC"));
        if (e.path("subjects").size() > 0) { sb.append(" · subjects: "); for (JsonNode s : e.path("subjects")) sb.append(s.asText()).append(' '); }
        sb.append("*\n\n");
        boolean brief = Boolean.TRUE.equals(BRIEF.get());
        String bodyText = e.path("body").asText().strip();
        if (brief) { String answer = LibraryProtocol.sectionOf(bodyText, "answer"); if (answer != null && !answer.isBlank()) bodyText = answer; }
        if (reading == null || reading.rung() == Explain.Rung.written) sb.append(bodyText).append("\n\n");
        else sb.append(Explain.cap(bodyText, 1_200)).append("\n\n");
        if (e.path("sources").size() > 0 && !"raw".equals(kind)) {
            sb.append("## Sources\n\n");
            for (JsonNode s : e.path("sources")) {
                sb.append("- ").append(s.path("locator").asText());
                if (s.hasNonNull("edition") && !s.path("edition").asText().isEmpty()) sb.append(" (").append(s.path("edition").asText()).append(")");
                if (!s.path("why").asText().isEmpty()) sb.append(" — ").append(s.path("why").asText());
                sb.append('\n');
            }
            sb.append('\n');
        }
        if (e.path("findings").size() > 0 && !brief) {
            sb.append("## Claims that came out of it\n\n");
            LibraryProtocol p = new LibraryProtocol(store);
            for (JsonNode f : e.path("findings")) {
                ObjectNode fe = p.entry(f.asText(), "finding", false);
                sb.append("- ").append(f.asText()).append(fe == null ? "" : " — " + fe.path("title").asText() + " (" + fe.path("state").asText() + ")").append('\n');
            }
            sb.append('\n');
        }
        sb.append("---\n\n*From the ResearchZosho library \"").append(store.identity().name()).append("\", ").append(LocalDate.now()).append(". Every claim above names its sources; nothing is from a model's memory.*\n");
        return sb.toString();
    }

    // ---- a report and a dossier, as their pages show them (0.7.0) ----

    /** The sections of a report a file leaves out: the run's own working, which the page folds away or lists for the record. */
    static final List<String> WORKING = List.of("evidence", "worker findings", "sources cited", "languages of the sources", "web search", "source requests", "archived copies");
    /** The library's own checks of a report, in the order the report carries them. */
    static final List<String> CHECKS = List.of("revision after the checks", "checks", "cite-check", "coverage check", "declined");

    /**
     * A report as a file: {@code format} pdf or docx; {@code in} the language it is read in ("" or "written": as written). A translation is
     * the one the library keeps; when none is kept yet the file is the report as written, and says so under its title.
     */
    public static Made report(LibraryStore store, String id, String format, String in) throws IOException {
        String fmt = format(format);
        if (fmt == null) throw ProtocolError.invalidArgs("A report is saved as " + FORMATS + ".");
        Investigation inv = id == null || !LibraryStore.safeName(id.strip()) ? null : store.investigation(id.strip());
        if (inv == null) throw ProtocolError.notFound("No report has the id " + id + ".");
        String written = Translate.writtenIn(inv), lang = language(in);
        Translate.Translated tr = !lang.isEmpty() && !lang.equals(written) ? Translate.currentReport(store, inv, lang) : null;
        String note = !lang.isEmpty() && !lang.equals(written) && tr == null
                ? "There is no translation of this report into " + ReadIn.name(lang) + " yet, so this file is the report as it was written. Open the report's page and read it in " + ReadIn.name(lang) + " to have one made, then save the file again." : "";
        UnaryOperator<String> t = s -> tr == null ? s : tr.words().getOrDefault(s, s);
        String title = t.apply(inv.title());
        if (MARKDOWN.equals(fmt)) {   // the same document as text: the title, the line of facts, the note, then the sections as the page shows them
            String md = "# " + title.replace('\n', ' ') + "\n\n*" + facts(store, inv, written, tr == null ? "" : lang) + "*\n\n" + (note.isEmpty() ? "" : note + "\n\n") + reportMarkdown(store, inv, t);
            return new Made(new File(inv.id() + (tr == null ? "" : "." + lang) + "." + MARKDOWN, contentType(MARKDOWN), forAViewer(md).getBytes(StandardCharsets.UTF_8)), "report", written, tr == null ? "" : lang, note, 0);
        }
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Heading(1, List.of(Span.plain(title.replace('\n', ' ')))));
        blocks.add(new Para(List.of(Span.plain(facts(store, inv, written, tr == null ? "" : lang))), true));
        if (!note.isEmpty()) blocks.add(new Para(List.of(Span.plain(note)), false));
        blocks.addAll(ExportDoc.parse(reportMarkdown(store, inv, t), cites(inv.body())));
        ExportDoc d = new ExportDoc(title, tr == null ? written : lang, store.identity().name() + " · " + shortId(inv.id()) + " · " + LocalDate.now(), blocks);
        return made(d, inv.id() + (tr == null ? "" : "." + lang), fmt, "report", written, tr == null ? "" : lang, note);
    }

    /** A report's full id from what a person types: the full id, or its number alone ("I-0265", "0265"); as given when nothing matches. */
    static String reportId(LibraryStore store, String typed) throws IOException {
        String t = typed == null ? "" : typed.strip();
        if (t.matches("\\d{1,5}")) t = "I-" + "0".repeat(Math.max(0, 4 - t.length())) + t;
        if (!t.matches("I-\\d{4,5}") || !Files.isDirectory(store.investigationsDir())) return t;
        try (var files = Files.list(store.investigationsDir())) {
            String prefix = t + "-";
            for (Path p : files.sorted().toList()) { String n = p.getFileName().toString(); if (n.startsWith(prefix) && n.endsWith(".md")) return n.substring(0, n.length() - 3); }
        }
        return t;
    }

    /** The language a file is asked in, as a code; "" for the language it was written in. */
    static String language(String in) {
        String s = in == null ? "" : in.strip();
        return s.isEmpty() || s.equalsIgnoreCase("written") ? "" : Translate.code(s);
    }

    private static Made made(ExportDoc d, String stem, String fmt, String kind, String written, String readIn, String note) throws IOException {
        byte[] bytes; int unshown = 0;
        if (PDF.equals(fmt)) { ExportPdf.Result r = ExportPdf.write(d); bytes = r.bytes(); unshown = r.unshown(); }
        else bytes = ExportDocx.write(d);
        return new Made(new File(stem + "." + fmt, contentType(fmt), bytes), kind, written, readIn, note, unshown);
    }

    /** A claim's state in the words its page uses. */
    static String stateWord(Finding.State s) {
        return switch (s) { case accepted -> "accepted"; case draft -> "waiting for review"; case disputed -> "disputed"; case retired -> "retired"; case superseded -> "replaced by a newer claim"; };
    }

    /** "I-0265" of "I-0265-find-homes-…". */
    static String shortId(String id) { String[] p = id.split("-"); return p.length >= 2 ? p[0] + "-" + p[1] : id; }

    /** The line of facts under a report's title: its id, when it was filed, its claims and open questions, its checks, its language. */
    static String facts(LibraryStore store, Investigation inv, String written, String readIn) {
        List<String> f = new ArrayList<>();
        f.add("Report " + shortId(inv.id()));
        String day = day(inv.recordedAt());
        if (!day.isEmpty()) f.add("filed " + day);
        f.add(inv.findings().size() + (inv.findings().size() == 1 ? " claim" : " claims") + ", " + inv.open().size() + (inv.open().size() == 1 ? " open question" : " open questions"));
        JsonNode led = Pages.reportRun(store, inv.id()).ledger();
        if (led != null && led.path("cite_checked").asInt(0) > 0)
            f.add(led.path("cite_supported").asInt() + " of " + led.path("cite_checked").asInt() + " cited sentences checked against their sources" + (led.path("cite_unsupported").asInt(0) > 0 ? ", " + led.path("cite_unsupported").asInt() + " marked" : ""));
        if (!inv.options().isEmpty()) f.add("written as asked: " + inv.options().described());
        if (!readIn.isEmpty()) f.add("written in " + ReadIn.name(written) + ", read in " + ReadIn.name(readIn) + ": translated by the model, the cites, the claim ids, the dates and the sources as they stand");
        else if (!written.equals("en")) f.add("written in " + ReadIn.name(written));
        return String.join(" · ", f) + ".";
    }

    static String day(String iso) {
        try { return Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)); }
        catch (RuntimeException e) { return iso == null || iso.length() < 10 ? "" : iso.substring(0, 10); }
    }

    private static final Pattern REF = Pattern.compile("^\\[(\\d{1,5})\\]\\s+(.*)$");
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"\\]\\[]+");

    /** A source's number → its address on the web, from the report's References, else its Sources section, wherever in the body they stand. */
    static Map<Integer, String> cites(String body) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (String want : List.of("references", "sources")) {
            for (String sec : Translate.sections(body)) {
                if (!sec.toLowerCase(Locale.ROOT).startsWith("## " + want + "\n")) continue;
                for (String line : sec.split("\n")) {
                    Matcher m = REF.matcher(line.strip());
                    if (!m.matches()) continue;
                    Matcher u = URL.matcher(m.group(2));
                    if (u.find()) out.putIfAbsent(Integer.parseInt(m.group(1)), u.group().replaceAll("[).,;:!?'\"]+$", ""));
                }
            }
        }
        return out;
    }

    /**
     * The body of a report's file, each section as the page shows it ({@code t}: the section's translation, when it is read in another
     * language): the question; the answer's own sections; how each point was checked; the open questions and sub-goals; the library's
     * checks; what was found and left out of the answer; the claims with their marks; the sources. The run's working is left out.
     */
    static String reportMarkdown(LibraryStore store, Investigation inv, UnaryOperator<String> t) throws IOException {
        StringBuilder question = new StringBuilder(), answer = new StringBuilder(), checked = new StringBuilder(), open = new StringBuilder(), checks = new StringBuilder(), found = new StringBuilder();
        String references = "", sources = "";
        for (String sec : Translate.sections(inv.body())) {
            String s = sec.strip();
            if (s.isEmpty()) continue;
            int nl = s.indexOf('\n');
            String heading = s.startsWith("## ") ? (nl < 0 ? s : s.substring(0, nl)).substring(3).strip().toLowerCase(Locale.ROOT) : "";
            if (s.startsWith("## ") && (nl < 0 || s.substring(nl).isBlank())) continue;   // a heading with nothing under it
            String shown = t.apply(s).strip() + "\n\n";
            if (WORKING.stream().anyMatch(heading::startsWith)) continue;
            if (heading.equals("question")) question.append(shown);
            else if (heading.equals(ReportBasis.CHECKED_HEADING.toLowerCase(Locale.ROOT))) checked.append(shown);
            else if (heading.equals(ReportBasis.OPEN_HEADING.toLowerCase(Locale.ROOT))) open.append(shown);
            else if (CHECKS.contains(heading)) checks.append(shown);
            else if (heading.startsWith("found and not in the answer")) found.append(shown);
            else if (heading.equals("references")) references = shown;
            else if (heading.equals("sources")) sources = shown;
            else answer.append(shown);
        }
        StringBuilder b = new StringBuilder();
        b.append(question.length() > 0 ? question : "## Question\n\n" + inv.title() + "\n\n");
        b.append(answer).append(checked);
        if (open.length() > 0) b.append(open);
        else if (!inv.open().isEmpty()) { b.append("## Open questions\n\n"); for (String q : inv.open()) b.append("- ").append(q.replace('\n', ' ')).append('\n'); b.append('\n'); }
        b.append(checks).append(found);
        if (!inv.findings().isEmpty()) {
            b.append("## Claims\n\n● established: accepted · ○ not yet: waiting for review, disputed or retired.\n\n");
            for (String fid : inv.findings()) {
                Finding f; try { f = store.finding(fid); } catch (IOException | RuntimeException e) { f = null; }
                if (f == null) { b.append("- ").append(fid).append('\n'); continue; }
                boolean est = f.state() == Finding.State.accepted;
                b.append("- ").append(est ? "● " : "○ ").append(f.title().replace('\n', ' ')).append(" — ").append(stateWord(f.state())).append(" (").append(shortId(f.id())).append(")\n");
            }
            b.append('\n');
        }
        String list = !references.isEmpty() ? references : sources;
        if (!list.isEmpty()) b.append("## Sources\n\n").append(list.replaceFirst("^## [^\\n]*\\n", "").strip()).append("\n\n");
        else {
            List<ObjectNode> rows = new LibraryProtocol(store).investigationSources(inv.body());
            if (!rows.isEmpty()) {
                b.append("## Sources\n\n");
                for (ObjectNode r : rows) b.append("[").append(r.path("n").asInt()).append("] ").append(r.path("title").asText("").isBlank() ? "" : r.path("title").asText() + " — ").append(r.path("locator").asText()).append('\n');
                b.append('\n');
            }
        }
        return b.toString();
    }

    /**
     * A dossier as a file: its sections as the printout writes them, with the Reading for the latest run's question first, as the page
     * opens with it; {@code in} the language it is read in, a translation the library keeps, else the dossier as written with a sentence.
     */
    public static Made dossier(LibraryStore store, String subject, String format, String in) throws IOException {
        String fmt = format(format);
        if (fmt == null) throw ProtocolError.invalidArgs("A dossier is saved as " + FORMATS + ".");
        String asked = subject == null ? "" : subject.strip();
        if (asked.isEmpty()) throw ProtocolError.invalidArgs("A dossier is on somebody or something: give the name.");
        ObjectNode plain = Dossier.of(store, asked);
        if (!plain.path("found").asBoolean(false)) throw ProtocolError.notFound("The library holds nothing on " + asked + " yet, so there is no dossier to save. A research run starts one: researchzosho research ask \"Who is " + asked + "?\"");
        String label = plain.path("subject").asText(asked), lang = language(in);
        Translate.Translated tr = lang.isEmpty() ? null : Translate.current(store, label, lang);
        Translate.Words w = tr == null ? Translate.AS_IS : tr.lookup();
        String note = !lang.isEmpty() && tr == null ? "There is no translation of this dossier into " + ReadIn.name(lang) + " yet, so this file is the dossier as it was written. Open the dossier's page and read it in " + ReadIn.name(lang) + " to have one made, then save the file again." : "";
        String md = tr == null ? plain.path("markdown").asText() : tr.markdown();
        // the printout's head: "# Dossier: X", then its line of facts; the Reading goes in before its first section
        String[] lines = md.split("\n", 3);
        String title = lines[0].replaceFirst("^#\\s*", "").strip();
        String rest = lines.length > 2 ? lines[2] : "";
        int cut = rest.indexOf("\n## ");
        String head = (cut < 0 ? rest : rest.substring(0, cut)).strip(), sections = cut < 0 ? "" : rest.substring(cut + 1);
        if (MARKDOWN.equals(fmt)) {   // the dossier as text, the Reading first as its page opens
            String text = "# " + title + "\n\n" + (head.isEmpty() ? "" : head + "\n\n") + (note.isEmpty() ? "" : note + "\n\n") + reading(store, label, w) + sections;
            return new Made(new File("dossier-" + fileSlug(label) + (tr == null ? "" : "." + lang) + "." + MARKDOWN, contentType(MARKDOWN), forAViewer(text).getBytes(StandardCharsets.UTF_8)), "dossier", Translate.languageOf(plain.path("markdown").asText()), tr == null ? "" : lang, note, 0);
        }
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Heading(1, List.of(Span.plain(title))));
        if (!head.isEmpty() && !head.startsWith("The library holds nothing")) blocks.add(new Para(List.of(Span.plain(head)), true));
        else if (!head.isEmpty()) blocks.addAll(ExportDoc.parse(head, Map.of()));
        if (!note.isEmpty()) blocks.add(new Para(List.of(Span.plain(note)), false));
        blocks.addAll(ExportDoc.parse(reading(store, label, w) + sections, Map.of()));
        ExportDoc d = new ExportDoc(title, tr == null ? Translate.languageOf(md) : lang, store.identity().name() + " · " + title + " · " + LocalDate.now(), blocks);
        return made(d, "dossier-" + fileSlug(label) + (tr == null ? "" : "." + lang), fmt, "dossier", Translate.languageOf(plain.path("markdown").asText()), tr == null ? "" : lang, note);
    }

    /** The Reading for the latest run's question, as the dossier's page opens with it, with the sources it cites; "" when no report has one. */
    static String reading(LibraryStore store, String label, Translate.Words w) throws IOException {
        Dossier.Gathered g = Dossier.gather(store, label);
        Investigation latest = null;
        for (Investigation inv : g.reports()) if (!inv.title().equals("The story of " + g.label())) latest = inv;
        if (latest == null) return "";
        String body = latest.body();
        String r = DossierPage.section(body, "Reading");
        if (r.isEmpty()) r = DossierPage.section(body, "Answer");
        if (r.isEmpty()) r = DossierPage.sectionStarting(body, "Answer (");
        if (r.isEmpty()) return "";
        String src = Dossier.sourcesLine(r, latest);
        return "## Reading, for that question\n\n" + w.tr(r) + "\n\n" + (src.isEmpty() ? "" : src + "\n\n");
    }

    /** A name a file can carry on every system: the subject's letters and digits in ASCII, or a short hash of it when it has none. */
    static String fileSlug(String subject) {
        String s = Translate.slug(subject).replaceAll("[^a-z0-9-]", "").replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        if (s.length() > 60) s = s.substring(0, 60).replaceAll("-$", "");
        return s.isEmpty() ? "x" + Translate.sha256(subject).substring(0, 10) : s;
    }

    /** Where the protocol's exports are kept: catalog/exports/ under the library. */
    static Path exportsDir(LibraryStore store) { return store.root().resolve("catalog").resolve("exports"); }

    /** The file written under catalog/exports/, whole or not at all; the path it was written to. */
    public static Path save(LibraryStore store, File f) throws IOException {
        Path p = LibraryStore.under(exportsDir(store), f.name());
        if (p == null) throw ProtocolError.invalidArgs("The file's name cannot be kept: " + f.name());
        Files.createDirectories(p.getParent());
        AtomicWrite.bytes(p, f.bytes());
        return p;
    }

    /** A kept export by its name, or null when the name is not one of catalog/exports/'s files. */
    static Path kept(LibraryStore store, String name) {
        Path p = LibraryStore.under(exportsDir(store), name == null ? "" : name);
        return p != null && Files.isRegularFile(p) ? p : null;
    }
}
