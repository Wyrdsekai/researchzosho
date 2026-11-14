package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.researchzosho.Config;
import org.researchzosho.drive.Declined;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.librarian.DocUnits.Unit;
import org.researchzosho.librarian.TranslationScores.Thresholds;

import java.io.IOException;
import java.lang.Character.UnicodeScript;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A comic translated (0.7.5): manga, manhwa, manhua, a web comic's strips, a graphic novel, a comic book, as a CBZ, a CBR, a ZIP or a
 * folder of page pictures, or a PDF whose pages are pictures. The pages are read by the comic helper on this machine
 * ({@link ComicHelper}: the lettering, the balloons, and for Japanese the readings printed beside the words); each speech balloon's
 * text is a unit of the translation, as a paragraph is a document's; the program's own translator translates a page at a call, and
 * the helper sets the translation back into the balloons. The same job as any document's translation ({@link Translation}), which
 * hands a comic here at the top of its plan and of its run.
 *
 * <p>What is a comic ({@link #taken}): an archive or a folder of page pictures by itself; a PDF on the person's word, or when the comic
 * helper, set up already, finds speech balloons on a sample of its pages. A person says otherwise in both directions.
 *
 * <p>The languages. A comic is read from Japanese, Chinese, Korean and the languages written in the Latin, the Cyrillic or the Greek
 * alphabet, and lettered in the same ({@link #script}); a language written from right to left, Thai and the scripts of India are
 * refused before any work, with the reason. The source is the one the person names, or the one the program finds ({@link Source}):
 * the helper says which writing system a sample of the pages is lettered in, kana meaning Japanese, hangul Korean and han alone
 * Chinese, and for an alphabet the program's own {@link Languages} tells the language from the text read. A block the original
 * lettered in columns is set in columns when the target is Chinese or Japanese; everything else is set in lines.
 *
 * <p>A web comic's strip, one very tall picture, is cut by the helper into pieces ({@link #cut}); each piece is a page of the job, and
 * the set pieces are joined into one picture of the strip's name, size and format for the output.
 *
 * <p>The lettering read twice ({@link #readTwice}). The helper's readers of every writing but Japanese misread more letters than a
 * model that sees does, as the helper's side measured on the same pictures; its reader of Japanese misreads fewer. So for a comic
 * that is not Japanese, at the tiers that check, a translator that sees reads each speech block again from a picture of it the helper
 * cuts out, one block a call; an answer that is a reading ({@link #notAReading}) is the block's text, and both readings are kept.
 */
final class ComicTranslation {
    private ComicTranslation() { }

    private static final ObjectMapper M = new ObjectMapper();

    /** How a file was taken for a comic: it is one by itself; the person said so; the comic helper found balloons on its pages. */
    static final String ITSELF = "itself", SAID = "said", FOUND = "found";
    /** How the comic helper stands for a plan: set up; to be set up, on the person's yes; or not usable on this machine, and why. */
    static final String READY = "ready", NEEDS = "needs", BARRED = "barred";
    /** How the pages reach the translator: as pictures; as their text alone, because it does not see; found when the run starts; not asked, a hosted model. */
    static final String SEES = "sees", BLIND = "blind", UNPROBED = "unprobed", UNPROBED_HOSTED = "unprobed-hosted";

    /**
     * The languages of an alphabet a comic is lettered in: those written in the Latin alphabet whose letters the lettering fonts have, and
     * those written in Cyrillic or Greek that the second font covers whole, each alphabet checked letter by letter against the font
     * (2026-11-13: Russian 66 letters, Ukrainian 68, Belarusian 66, Bulgarian 62, Serbian 60, Macedonian 66, Greek 71 with its accented
     * letters). Beside them {@link #CJK}: Japanese, Chinese and Korean, each in a font of its own.
     */
    static final Set<String> LATIN = Set.of("en", "de", "fr", "es", "pt", "it", "nl", "pl", "sv", "da", "no", "nb", "nn", "fi", "cs", "sk", "ro", "hu", "tr", "id", "ms", "hr", "sl", "lt", "lv", "et", "is", "ca", "sq", "ga", "cy", "eo", "mt", "sw", "af", "eu", "gl", "tl", "la");
    static final Set<String> CYRILLIC = Set.of("ru", "uk", "be", "bg", "sr", "mk");
    static final Set<String> GREEK = Set.of("el");
    static final Set<String> CJK = Set.of("ja", "zh", "ko");
    /** The targets a block lettered in columns is set in columns for; Korean, as every alphabet, is always set in lines. */
    static final Set<String> IN_COLUMNS = Set.of("ja", "zh");
    static final Set<String> RIGHT_TO_LEFT = Set.of("ar", "fa", "ur", "he", "yi", "ps", "sd", "ug", "dv", "ckb");
    /** The formats a comic's translation is written in. */
    static final Set<String> OUT = Set.of(ComicPages.CBZ, ComicPages.PDF);

    /**
     * What the person said of a comic when they asked, and what the plan knew when the job was filed: whether the file is one (on, off,
     * "" for what the program finds), how it was taken for one, their yes to setting the comic helper up, the language the plan found
     * its lettering in (null when it found none: the person named it, or the helper was not set up), and their word for the way its
     * pages are read ({@link #readsWord}: rtl, ltr, manga, or "" when they said nothing). The job does not look again, and a job that
     * resumes reads its pages the way it began.
     */
    record Said(String is, String why, boolean setup, Source source, String reads) {
        Said(String is, String why, boolean setup) { this(is, why, setup, null, ""); }
        Said(String is, String why, boolean setup, Source source) { this(is, why, setup, source, ""); }
        static final Said NOTHING = new Said("", "", false, null, "");
        static Said of(JsonNode args) {
            Source found = Source.of(args.path("comic_plan").path("source"));
            String reads = readsWord(args.path("reads").asText(""));
            return new Said(args.path("comic").asText(""), args.path("comic_why").asText(""), args.path("comic_setup").asText("").equals("yes"), found.known() && found.how().equals(SAMPLED) ? found : null, reads == null ? "" : reads);
        }
    }

    // ---- which way the pages are read ----

    /** The two ways a comic's pages are read, as the helper takes them. */
    static final String RTL = "rtl", LTR = "ltr";
    /** How the way is known: the person said it; the person called the comic a manga; the program found it (by the language, and for Chinese by the columns); or it is found when the run starts. */
    static final String READS_SAID = "said", READS_MANGA = "manga", READS_FOUND = "found", READS_LATER = "at-start";

    /**
     * The person's word for the way a comic's pages are read, as the program keeps it: rtl ("right-to-left", "right to left", "rtl",
     * 右から左), ltr (the same from the left), manga (they called the comic a manga, whose pages run from the right in whatever
     * language it is lettered), "" for no word; null for a word that is none of these.
     */
    static String readsWord(String said) {
        String w = said == null ? "" : said.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-");
        return switch (w) {
            case "" -> "";
            case "rtl", "right-to-left", "from-right-to-left", "右から左" -> RTL;
            case "ltr", "left-to-right", "from-left-to-right", "左から右" -> LTR;
            case "manga" -> READS_MANGA;
            default -> null;
        };
    }

    /** What a surface says of a word for the direction that is neither. */
    static final String READS_ARE = "The way a comic's pages are read is right-to-left or left-to-right (rtl and ltr are taken too).";

    /** The way a comic's pages are read (rtl, ltr; "" while it is not known), and how that is known. */
    public record Reads(String order, String how) {
        boolean known() { return !order.isEmpty(); }
        boolean rtl() { return order.equals(RTL); }
        /** The person's word, as the job keeps it: rtl or ltr as they said it, manga when they called the comic one, "" when the program found the way itself. */
        String word() { return how.equals(READS_SAID) ? order : how.equals(READS_MANGA) ? READS_MANGA : ""; }
    }

    /**
     * The way a comic's pages are read. The person's word decides, for any comic, a Japanese one too. Without it: a Japanese comic is
     * read from the right; a comic the person called a manga from the right, whatever its language (an English, French or Korean
     * edition of a manga keeps its pages, and nothing on a page says so); a Chinese comic from the right when the look at its pages
     * found its lettering in columns, and not known before a look; every other comic from the left. A comic whose language is still
     * to be found is not known either, unless it was called a manga.
     */
    static Reads reads(String word, Source source) {
        if (RTL.equals(word) || LTR.equals(word)) return new Reads(word, READS_SAID);
        boolean manga = READS_MANGA.equals(word);
        if (!source.known()) return manga ? new Reads(RTL, READS_MANGA) : new Reads("", READS_LATER);
        if (source.code().equals("ja")) return new Reads(RTL, READS_FOUND);
        if (manga) return new Reads(RTL, READS_MANGA);
        if (source.code().equals("zh")) return source.how().equals(SAMPLED) ? new Reads(source.vertical() ? RTL : LTR, READS_FOUND) : new Reads("", READS_LATER);
        return new Reads(LTR, READS_FOUND);
    }

    // ---- the language a comic is lettered in ----

    /** How a comic's language is known: the person named it; the program found it on a sample of the pages; or it is found when the run starts, the comic helper not being set up when the plan was made. */
    static final String BY_PERSON = "named", SAMPLED = "sampled", AT_START = "at-start";

    /**
     * The language a comic is lettered in: its code ("" while it is still to be found), how it is known, and for one the program found,
     * the pages the helper looked at, the writing system it said, the share of the letters read that are in it, whether most blocks run
     * in columns, and the guess with its runner-up when an alphabet's language was told from the text.
     */
    record Source(String code, String how, int sampled, String script, double share, boolean vertical, Languages.Guess guess) {
        static final Source LATER = new Source("", AT_START, 0, "", 0, false, null);
        static Source named(String code) { return new Source(code, BY_PERSON, 0, "", 0, false, new Languages.Guess(code, "", 1, 0, Languages.Guess.PERSON)); }
        boolean known() { return !code.isEmpty(); }
        /** Whether the language is plain: the person's word, a writing system that is one language, or an alphabet whose words say it clearly. */
        boolean clear() { return guess == null || guess.clear(); }
        /** The other language it could be, when the words do not say clearly; "" otherwise. */
        String other() { return clear() || guess.second().isEmpty() || guess.second().equals(code) ? "" : guess.second(); }
        Source as(String other) { return new Source(other, how, sampled, script, share, vertical, new Languages.Guess(other, code, guess.margin(), guess.evidence(), guess.by())); }

        /**
         * What the helper said of a sample of {@code sampled} pages, as a language: kana are Japanese, hangul Korean, han alone Chinese,
         * Greek letters Greek; for the Latin and the Cyrillic alphabet the function words and the letters of the text read say which
         * language ({@link Languages#byWords}), clearly or not. Lettering the helper found none of leaves the language to be found.
         */
        static Source found(JsonNode said, int sampled) {
            String script = said.path("script").asText("none");
            StringBuilder text = new StringBuilder();
            for (JsonNode t : said.path("texts")) text.append(t.asText("")).append('\n');
            int letters = (int) text.codePoints().filter(Character::isLetter).count();
            Languages.Guess g = switch (script) {
                case "japanese" -> new Languages.Guess("ja", "", 1, letters, Languages.Guess.SCRIPT);
                case "korean" -> new Languages.Guess("ko", "", 1, letters, Languages.Guess.SCRIPT);
                case "chinese" -> new Languages.Guess("zh", "", 1, letters, Languages.Guess.SCRIPT);
                case "greek" -> new Languages.Guess("el", "", 1, letters, Languages.Guess.SCRIPT);
                case "latin" -> Languages.byWords(text.toString(), Languages.LATIN_WORDS, Languages.LATIN_LETTERS);
                case "cyrillic" -> Languages.byWords(text.toString(), Languages.CYRILLIC_WORDS, Languages.CYRILLIC_LETTERS);
                default -> null;
            };
            if (g == null || g.code().isEmpty()) return new Source("", AT_START, sampled, script, 0, false, null);
            return new Source(g.code(), SAMPLED, sampled, script, said.path("share").asDouble(0), said.path("vertical").asBoolean(false), g);
        }

        ObjectNode json() {
            ObjectNode o = M.createObjectNode();
            o.put("code", code); o.put("how", how);
            if (how.equals(SAMPLED)) {
                o.put("pages_looked_at", sampled); o.put("script", script); o.put("share", share); o.put("vertical", vertical);
                if (guess != null) { o.put("could_be", guess.second()); o.put("margin", guess.margin()); o.put("evidence", guess.evidence()); o.put("by", guess.by()); }
            }
            return o;
        }

        /** As {@link #json} wrote it; {@link #LATER} for anything else. */
        static Source of(JsonNode j) {
            if (j == null || !j.isObject() || j.path("code").asText("").isEmpty()) return LATER;
            String code = j.path("code").asText(), how = j.path("how").asText(SAMPLED);
            if (how.equals(BY_PERSON)) return named(code);
            return new Source(code, how, j.path("pages_looked_at").asInt(0), j.path("script").asText(""), j.path("share").asDouble(0), j.path("vertical").asBoolean(false),
                    new Languages.Guess(code, j.path("could_be").asText(""), j.path("margin").asDouble(1), j.path("evidence").asInt(0), j.path("by").asText(Languages.Guess.SCRIPT)));
        }
    }

    /** How to name a comic's language, as each sentence that says what the program found ends. */
    static final String NAME_IT = "--from <language> on the command line, \"from <language>\" in the chat";

    /** A file taken for a comic: how ({@link #ITSELF}, {@link #SAID}, {@link #FOUND}), and for a PDF the helper looked at, the pages looked at and those with a speech balloon. */
    record Taken(String why, int sampled, int withBalloons) { }

    /**
     * What a comic's plan knows before any page is read: its pages, what holds them, how it was taken for a comic, how the helper stands,
     * how the pages reach the translator, the language of its lettering, the way its pages are read, and whether its lettering is read
     * a second time by the translator ({@link #TWICE}; once, and why: {@link #ONCE_QUICK}, {@link #ONCE_BLIND}, {@link #ONCE_JAPANESE};
     * or {@link #TWICE_LATER}, found when the run starts).
     */
    public record Planned(int pages, String container, String why, int sampled, int withBalloons, String helper, String picture, String translator, Source source, Reads reads, String second) {
        /** What the plan says of who reads the lettering: the translator a second time, or the helper alone and why; "" for a Japanese comic, which the helper alone reads in every run. */
        public String secondSaid(Chrome c) {
            return switch (second) {
                case TWICE -> c.t("each balloon's lettering is read a second time, by the translator from a picture of the balloon; where the two readings differ, the translator's is the one used and the note lists both");
                case TWICE_LATER -> c.t("whether the lettering is read a second time, by the translator from a picture of each balloon, is found when the run starts: it is for a comic that is not Japanese when the translator sees pictures");
                case ONCE_QUICK -> c.t("the lettering is read once, by the comic helper: the quick tier has no second reading of it");
                case ONCE_BLIND -> c.t("the lettering is read once, by the comic helper: a second reading takes a translator that sees pictures");
                default -> "";
            };
        }

        /**
         * What the plan says of the way the pages are read, and how that is known: as the person said; as a manga's are, when they called
         * it one; as the program found, with the words that say otherwise; or that it is found when the run starts.
         */
        public String readsSaid(Chrome c) {
            return switch (reads.how()) {
                case READS_SAID -> reads.rtl() ? c.t("its pages are read from the right to the left, as you said") : c.t("its pages are read from the left to the right, as you said");
                case READS_MANGA -> c.t("its pages are read from the right to the left, as a manga's are (if they run from the left, say so: --reads left-to-right on the command line, \"it reads left to right\" in the chat)");
                case READS_FOUND -> reads.rtl() ? c.t("its pages are read from the right to the left (if they run from the left, say so: --reads left-to-right on the command line, \"it reads left to right\" in the chat)")
                        : c.t("its pages are read from the left to the right (if they run from the right, as a manga's do in any language, say so: --reads right-to-left on the command line, \"it reads right to left\" in the chat)");
                default -> c.t("which way its pages are read is found when the run starts (to say it yourself: --reads right-to-left or --reads left-to-right on the command line, \"it reads right to left\" in the chat)");
            };
        }

        /** The language of the lettering in a few words, for the sentence that says what the file is: "its lettering read as Korean". */
        public String lettering(Chrome c) { return source.known() ? c.t("its lettering read as {0}", c.languageName(source.code())) : c.t("the language of its lettering not read yet"); }

        /**
         * What the plan says of the language when the person did not name it: what the helper read on the pages it looked at, the other
         * language it could be when the words do not say clearly, and how to name another; or that it is found when the run starts.
         */
        public String sourceSaid(Chrome c) {
            if (source.how().equals(BY_PERSON)) return "";
            if (!source.known()) return c.t("the language of its lettering is found on a sample of its pages when the run starts (to name it yourself: --from <language> on the command line, \"from <language>\" in the chat)");
            return source.other().isEmpty() ? c.t("the comic helper read its lettering as {0} (pages looked at: {1}; if it is in another language, name it: --from <language> on the command line, \"from <language>\" in the chat)", c.languageName(source.code()), source.sampled())
                    : c.t("the comic helper read its lettering as {0}, though it could be {2} (pages looked at: {1}; if it is in another language, name it: --from <language> on the command line, \"from <language>\" in the chat)", c.languageName(source.code()), source.sampled(), c.languageName(source.other()));
        }

        /** What the plan says beside its time: the language the program found, how the pages reach the translator, and how the file was taken for a comic when the program found it; clauses. */
        public String said(Chrome c) {
            String found = !why.equals(FOUND) ? "" : c.t("it is taken for a comic, because the comic helper found speech balloons on {0} of the {1} pages it looked at (if it is a document, say so: --comic off on the command line, \"it is not a comic\" in the chat)", withBalloons, sampled);
            String pages = switch (picture) {
                case SEES -> c.t("each page goes to the translator as a picture, beside the text read from it");
                case BLIND -> c.t("the translator does not see pictures, so each page is translated from the text read from it alone");
                case UNPROBED_HOSTED -> c.t("the translator has not been asked whether it sees pictures (researchzosho models test {0} asks it), so each page is translated from the text read from it alone", translator);
                default -> c.t("whether the translator sees pictures is found when the run starts: if it does, each page goes to it as a picture beside the text read from it");
            };
            return Translation.clauses(c, found, sourceSaid(c), readsSaid(c), pages, secondSaid(c));
        }
        public ObjectNode json() {
            ObjectNode o = M.createObjectNode();
            o.put("pages", pages); o.put("container", container); o.put("taken", why); o.put("helper", helper); o.put("pictures", picture);
            if (why.equals(FOUND)) { o.put("pages_looked_at", sampled); o.put("pages_with_balloons", withBalloons); }
            o.put("from", source.code()); o.put("from_how", source.how());   // the language of the lettering: named by the person, read from a sample, or to be found when the run starts
            o.set("source", source.json());
            o.put("reads", reads.order()); o.put("reads_how", reads.how());   // the way the pages are read (rtl, ltr, or "" while it is to be found), and how that is known: said, manga, found, at-start
            o.put("second_reading", second);   // whether the translator reads the lettering a second time: twice; at-start; or once and why (quick, blind, japanese)
            return o;
        }
    }

    // ---- what is a comic ----

    /** A PDF is looked at by the helper only when nearly all its pages are pictures with no text of their own, and it has this many pages or more. */
    static final double PICTURE_SHARE = 0.8;
    static final int SAMPLE = 3;
    /** What the helper found on the PDFs looked at in this process, by the file's hash: a plan, the form's and then the job's, looks once. */
    private static final Map<String, int[]> LOOKED = new LinkedHashMap<>();

    /**
     * Whether a file is translated as a comic, and how it was taken for one; null for a document. {@code comic}: the person's word, on
     * or off, or "" for what the program finds. An archive or a folder of page pictures is a comic by itself, and nothing else: said to
     * be a document, it is refused, since there is no document in it to translate. A PDF is a document unless the person says it is a
     * comic, or the comic helper is set up already and finds speech balloons on most of a sample of its pages; a person on a machine
     * with no helper is never asked to fetch one for a PDF.
     */
    static Taken taken(Path file, String comic) throws IOException {
        String word = comic == null ? "" : comic.strip().toLowerCase(Locale.ROOT);
        boolean on = word.equals("on") || word.equals("yes") || word.equals("true"), off = word.equals("off") || word.equals("no") || word.equals("false");
        String kind = ComicPages.container(file);
        String name = file.getFileName().toString();
        if (kind == null) {
            if (on) throw new Translation.Refused(c -> c.t("\"{0}\" is not a comic this program opens: a CBZ, a CBR, a ZIP or a folder of page pictures, or a PDF whose pages are pictures.", name));
            return null;
        }
        if (kind.equals(ComicPages.PDF)) {
            if (on) return new Taken(SAID, 0, 0);
            if (off || !ComicHelper.on() || !Helpers.localAllowed() || !ComicHelper.installed()) return null;
            int[] found = looked(file);
            return found != null && found[0] > 0 && found[1] * 2 > found[0] ? new Taken(FOUND, found[0], found[1]) : null;
        }
        if (!ComicPages.byItself(file)) {
            if (kind.equals(ComicPages.FOLDER)) throw new Translation.Refused(c -> c.t("\"{0}\" is a folder with no page pictures in it (jpg, png, webp or bmp): a folder is translated only as a comic, its pictures the pages.", name));
            if (on) throw new Translation.Refused(c -> c.t("\"{0}\" holds no page pictures (jpg, png, webp or bmp), so it is no comic this program opens.", name));
            return null;   // a zip that is a Word file, an EPUB, or nothing the translation reads: the document's path says which
        }
        if (off) throw new Translation.Refused(c -> c.t("\"{0}\" holds page pictures and no text of its own: it is translated as a comic, or not at all.", name));
        return new Taken(on ? SAID : ITSELF, 0, 0);
    }

    /**
     * What the helper finds on a sample of a PDF's pages: {pages looked at, pages with a speech balloon}, or null when the PDF is not one
     * to look at (fewer than four in five of its pages are pictures with no text) or could not be looked at. Three pages, spread through
     * the PDF past its cover; kept for the process by the file's hash.
     */
    static int[] looked(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            String key = PdfReading.sha(bytes);
            synchronized (LOOKED) { if (LOOKED.containsKey(key)) return LOOKED.get(key); }
            PdfReading.Preflight pre = PdfReading.preflight(bytes);
            int content = pre.content(), pictures = pre.eyePagesFor(PdfReading.NO_TEXT).size();
            int[] found = null;
            if (content >= 2 && pictures >= PICTURE_SHARE * content) {
                List<Integer> eye = pre.eyePagesFor(PdfReading.NO_TEXT);
                eye.removeAll(pre.undrawable());
                List<Integer> sample = new ArrayList<>();
                for (int k = 1; k <= SAMPLE && !eye.isEmpty(); k++) { int p = eye.get(Math.min(eye.size() - 1, (int) Math.round(k * eye.size() / (SAMPLE + 1.0)))); if (!sample.contains(p)) sample.add(p); }
                Path dir = tempWork(temp(), WORK + "-look");
                try (PDDocument doc = Loader.loadPDF(bytes); ComicHelper.Reader reader = ComicHelper.reader()) {
                    List<Path> drawn = new ArrayList<>();
                    for (int p : sample) {
                        byte[] png = PdfReading.drawn(doc, bytes, p, !PdfGlyphs.undecodable(doc.getPage(p - 1)).isEmpty());
                        if (png == null) continue;
                        Path f = dir.resolve("p" + p + ".png");
                        Files.write(f, png);
                        drawn.add(f);
                    }
                    // the same look says which language the lettering is in, so that the plan that follows starts no second helper for it
                    Source source = Source.LATER;
                    if (!drawn.isEmpty()) try { source = Source.found(reader.script(drawn), drawn.size()); } catch (ComicHelper.Failed f) { source = Source.LATER; }
                    int with = 0, read = 0;
                    for (Path f : drawn) {
                        JsonNode page = reader.read(f, source.code());
                        read++;
                        for (JsonNode b : page.path("blocks")) if (b.path("kind").asText("").equals("speech") && b.path("balloon").isObject()) { with++; break; }
                    }
                    found = new int[]{read, with};
                    if (with > 0) synchronized (SOURCES) { if (SOURCES.size() > 64) SOURCES.clear(); SOURCES.put(sourceKey(file), source); }
                } finally { dropWork(dir); }
            }
            synchronized (LOOKED) { if (LOOKED.size() > 64) LOOKED.clear(); LOOKED.put(key, found); }
            return found;
        } catch (IOException | RuntimeException e) { return null; }
    }

    /** What the helper found on the comics looked at for a plan in this process, by the file: a plan, the form's and then the job's, looks once. */
    private static final Map<String, Source> SOURCES = new LinkedHashMap<>();

    /** A file as the look's memory knows it: where it is, how large and when it was last written; a folder by its pages' names and sizes. */
    static String sourceKey(Path file) throws IOException {
        Path at = file.toAbsolutePath().normalize();
        return Files.isDirectory(at) ? at + "|" + ComicPages.sha(at) : at + "|" + Files.size(at) + "|" + Files.getLastModifiedTime(at).toMillis();
    }

    /**
     * The language a comic's lettering is in, found for a plan: the helper, when it is set up, says the writing system of {@link #SAMPLE}
     * pages spread through the volume ({@link ComicPages#sample}), and the text it read there says the language of an alphabet. Looked
     * at once a file in this process. {@link Source#LATER} when the helper is not set up or may not run here, for a CBR (which a plan
     * does not unpack), when the look fails, and when it finds no lettering: the run then looks itself, at its start, on its own pages.
     */
    static Source sampled(Path file) {
        if (!ComicHelper.on() || !Helpers.localAllowed() || !ComicHelper.installed()) return Source.LATER;
        Path dir = null;
        try {
            String key = sourceKey(file);
            synchronized (SOURCES) { Source kept = SOURCES.get(key); if (kept != null) return kept; }
            dir = tempWork(temp(), WORK + "-look");
            List<Path> sample = ComicPages.sample(file, dir, SAMPLE);
            Source found = Source.LATER;
            if (!sample.isEmpty()) try (ComicHelper.Reader reader = ComicHelper.reader()) { found = Source.found(reader.script(sample), sample.size()); }
            synchronized (SOURCES) { if (SOURCES.size() > 64) SOURCES.clear(); SOURCES.put(key, found); }
            return found;
        } catch (IOException | RuntimeException e) { return Source.LATER; }
        finally { if (dir != null) try { dropWork(dir); } catch (IOException ignored) { /* the look's own folder, left for the next sweep or the system to clear */ } }
    }

    /**
     * The language as the run takes it, or the refusal. A comic the person says is lettered in the language asked for is refused, and so
     * is one the program read as that language when the reading is plain; one whose words do not say clearly is taken to be in the
     * runner-up, the person's asking being the evidence, as a document is ({@link Translation#source}).
     */
    static Source settled(Source s, String target, String name) throws Translation.Refused {
        if (!s.known()) return s;
        if (s.how().equals(SAMPLED) && (s.code().equals(target) || !readable(s.code())) && !s.other().isEmpty() && !s.other().equals(target) && readable(s.other())) s = s.as(s.other());
        if (!readable(s.code())) {   // found, never named (a named one is refused before any look): a language of an alphabet the readers have, whose own letters they lack
            String found = s.code();
            throw new Translation.Refused(c -> c.t("The comic helper read the lettering of \"{0}\" as {1}, which a comic is not read from in this version; if it is in another language, name it: --from <language> on the command line, \"from <language>\" in the chat.", name, c.languageName(found)));
        }
        if (!s.code().equals(target)) return s;
        if (s.how().equals(BY_PERSON)) throw new Translation.Refused(c -> c.t("\"{0}\" is written in {1} already.", name, c.languageName(target)));
        if (s.clear()) throw new Translation.Refused(c -> c.t("The comic helper read the lettering of \"{0}\" as {1}, the language asked for; if it is in another language, name it: --from <language> on the command line, \"from <language>\" in the chat.", name, c.languageName(target)));
        return s;
    }

    // ---- the languages ----

    /** What a refusal of a target begins with: what is lettered. */
    static final String LETTERED = "A comic's balloons are lettered in Japanese, Chinese, Korean and the languages written in the Latin, Cyrillic or Greek alphabet in this version.";
    /** What a refusal of a source begins with: what is read. */
    static final String READ_FROM = "A comic's lettering is read in Japanese, Chinese, Korean and the languages written in the Latin, Cyrillic or Greek alphabet in this version.";

    /**
     * The languages of the Latin and of the Cyrillic alphabet a comic is read from, as the helper's readers list them: more than are
     * lettered, since a reader has the letters of languages the lettering fonts were not checked for. Vietnamese is not among them:
     * the Latin reader lacks its toned vowels (the helper's side measured 15.5 % of the characters wrong).
     */
    static final Set<String> READ_LATIN = Set.of("en", "de", "fr", "es", "pt", "it", "nl", "sv", "da", "no", "nb", "nn", "fi", "is", "pl", "cs", "sk", "sl", "hr", "bs", "hu", "ro", "tr", "id", "ms", "tl", "ca", "gl", "eu", "la", "et", "lv", "lt",
            "sq", "af", "sw", "ga", "cy", "mt", "eo", "oc", "lb", "rm", "az", "uz", "ku", "mi", "qu"),
            READ_CYRILLIC = Set.of("ru", "uk", "be", "bg", "sr", "mk", "kk", "ky", "tg", "mn", "tt", "ba", "cv");

    /** Whether a comic lettered in a language is read: the three of East Asia, Greek, and the languages of the Latin and the Cyrillic alphabet the helper's readers list. The helper's own list, said when it starts, has the last word in a run. */
    static boolean readable(String code) { return CJK.contains(code) || READ_LATIN.contains(code) || READ_CYRILLIC.contains(code) || GREEK.contains(code); }

    /**
     * The writing system a target is lettered in, as the helper's fonts name it: latin, cyrillic, greek, japanese, chinese-simplified
     * or korean; a refusal with the reason for a language the lettering does not do. Chinese is written in simplified characters, as
     * every translation of the program into Chinese is: nothing in it asks for the traditional ones.
     */
    static String script(String code) throws Translation.Refused {
        if (LATIN.contains(code)) return "latin";
        if (CYRILLIC.contains(code)) return "cyrillic";
        if (GREEK.contains(code)) return "greek";
        if (code.equals("ja")) return "japanese";
        if (code.equals("zh")) return "chinese-simplified";
        if (code.equals("ko")) return "korean";
        if (RIGHT_TO_LEFT.contains(code)) throw new Translation.Refused(c -> PdfReading.sentences(c, List.of(c.t(LETTERED), c.t("{0} is written from right to left, which the lettering does not do yet.", c.languageName(code)))));
        if (code.equals("vi")) throw new Translation.Refused(c -> PdfReading.sentences(c, List.of(c.t(LETTERED), c.t("{0} needs letters the lettering fonts do not have.", c.languageName(code)))));
        throw new Translation.Refused(c -> PdfReading.sentences(c, List.of(c.t(LETTERED), c.t("{0} is not lettered yet.", c.languageName(code)))));
    }

    // ---- the plan ----

    /** Seconds a page takes when nothing has been timed on the model: the helper's reading (2 to 3 s measured on a processor) and one call to a local model (7 s measured), and what the checks, the fit and a second reading add. */
    static final double QUICK_PAGE = 11, CHECKED_PAGE = 14, REVIEWED_PAGE = 22, THOROUGH_PAGE = 30;
    /** Tokens a page's call sends and writes, as a local model counted them with the page's picture: for a hosted model's estimate. */
    static final int PAGE_SENT = 2000, PAGE_WRITTEN = 150;
    /** The key a comic's measured speed is kept under, beside a document's tiers: pages a second, not characters. */
    static String speedKey(String tier) { return "comic-" + tier; }
    /** The same for a comic whose lettering was read a second time: a call more for each speech block makes it another speed, kept apart. */
    static String speedKey(String tier, boolean twice) { return speedKey(tier) + (twice ? "-twice" : ""); }
    /**
     * What the second reading adds to a page when no comic read twice has been timed on the model: the seconds one block's call took
     * (1.1 to 1.6 on a local 27B, as the helper's side measured it) and the speech blocks a page holds (5 to 8 on the pages measured).
     */
    static final double CROP_SECONDS = 1.35, CROPS_A_PAGE = 6.5;
    /** Tokens one block's call sends and writes, for a hosted model's estimate: the picture of a block and the question; a balloon's lettering back. Reckoned, not measured: no hosted model has read a block yet. */
    static final int CROP_SENT = 900, CROP_WRITTEN = 40;
    /** Whether a comic's lettering is read a second time, as a plan and a run say it: by the translator too; or by the helper alone, since the tier is quick, the translator does not see pictures, or the comic is Japanese; or found when the run starts. */
    static final String TWICE = "twice", ONCE_QUICK = "quick", ONCE_BLIND = "blind", ONCE_JAPANESE = "japanese", TWICE_LATER = "at-start";

    /**
     * Whether a comic's lettering will be read a second time, from what a plan knows: never for a Japanese comic (the helper's reader
     * of Japanese is the better one: a model that sees rewrites what it reads), not at the quick tier (read, translate, set), not by
     * a translator that does not see pictures; found when the run starts while the language or the translator's sight is.
     */
    static String second(Source source, String quality, String picture) {
        if (source.known() && source.code().equals("ja")) return ONCE_JAPANESE;
        if (quality.equals(Translation.QUICK)) return ONCE_QUICK;
        if (picture.equals(BLIND) || picture.equals(UNPROBED_HOSTED)) return ONCE_BLIND;
        return source.known() && picture.equals(SEES) ? TWICE : TWICE_LATER;
    }

    /**
     * A comic's plan: its pages counted, none read (reading takes seconds a page, so a plan cannot count the balloons; a web comic's
     * strip counts as the one picture it is, its pieces being known when the run cuts it); the language of its lettering, named by the
     * person or found on a sample of the pages when the helper is set up, else left to the run's start; the time from the pages and the
     * model's measured speed on comics; the refusals said before any work: a source the helper does not read, a target the lettering
     * does not do, a comic lettered in the language asked for, a format that is neither cbz nor pdf, a helper this machine may not run.
     * The helper not being set up is no refusal: the plan says so ({@link Planned#helper}) and each surface asks the one question.
     */
    static Translation.Plan plan(LibraryStore store, Path file, String to, String as, String quality, String model, String translatorEntry, String judge, String from, Taken taken) throws IOException {
        return plan(store, file, to, as, quality, model, translatorEntry, judge, from, taken, Said.NOTHING, true);
    }

    /**
     * The same for a job: {@code said} holds the language the plan found when the job was filed, which is taken as found; {@code look}
     * false makes no look of its own at the pages (the run looks with the helper it has started, {@link #atStart}).
     */
    static Translation.Plan plan(LibraryStore store, Path file, String to, String as, String quality, String model, String translatorEntry, String judge, String from, Taken taken, Said said, boolean look) throws IOException {
        String name = file.getFileName().toString();
        String code = Translate.code(to);
        if (!Translate.LANGUAGES.containsKey(code) && code.length() != 2) throw new Translation.Refused(c -> c.t("Which language? Name one, such as Japanese or ja."));
        String named = from == null || from.isBlank() ? "" : Translate.code(from);
        if (!named.isEmpty() && !readable(named)) throw new Translation.Refused(c -> PdfReading.sentences(c, List.of(c.t(READ_FROM), c.t("{0} is not read.", c.languageName(named)))));
        script(code);
        String container = ComicPages.container(file);
        String fmt = as == null || as.isBlank() ? (ComicPages.PDF.equals(container) ? ComicPages.PDF : ComicPages.CBZ) : as.strip().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        if (!OUT.contains(fmt)) throw new Translation.Refused(c -> c.t("A comic's translation is written as cbz or pdf, not {0}.", fmt));
        String q = Translation.tier(quality);
        if (q == null) throw new Translation.Refused(c -> c.t("The quality is quick, checked, reviewed or thorough."));
        String barred = ComicHelper.barred(Chrome.ENGLISH);
        if (!barred.isEmpty()) throw new Translation.Refused(ComicHelper::barred);
        int pages = ComicPages.count(file);   // a RAR on a machine with no program for it says here what to install
        if (pages == 0) throw new Translation.Refused(c -> c.t("\"{0}\" holds no page pictures.", name));
        // the language of the lettering: the person's word; else what the plan found when the job was filed; else a look now, or the run's own
        Source source = settled(!named.isEmpty() ? Source.named(named) : said != null && said.source() != null ? said.source() : look ? sampled(file) : Source.LATER, code, name);
        String tName = translatorEntry == null ? "" : translatorEntry.strip();
        ModelEntries.Entry t = null;
        if (!tName.isEmpty() && !tName.equalsIgnoreCase(Auto.NAME)) { t = ModelEntries.get(tName); if (t == null) throw new Translation.Refused(c -> ModelEntries.noMatch(tName, c)); }
        String judgeName = judge == null ? "" : judge.strip();
        ModelEntries.Entry e = null;
        if (judgeName.equalsIgnoreCase(Auto.NAME)) e = Translation.pickJudge(t == null ? Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8200") : t.drive(), t == null ? model : t.model()).entry();
        else if (!judgeName.isEmpty()) { try { e = Translation.judgeEntry(judgeName); } catch (Jobs.Failure f) { throw new IOException(f.getMessage()); } }
        final ModelEntries.Entry tEntry = t, eEntry = e;
        if (t != null && !ModelEntries.keyKept(t)) throw new Translation.Refused(c -> ModelEntries.noKey(tEntry, c));
        if (e != null && !ModelEntries.keyKept(e)) throw new Translation.Refused(c -> ModelEntries.noKey(eEntry, c));
        boolean hosted = e != null && e.hosted(), tHosted = t != null && t.hosted();
        String speedModel = t == null ? model : t.model();
        double quickSpeed = Translation.charsPerSecond(store, speedModel, speedKey(Translation.QUICK)), checkedSpeed = Translation.charsPerSecond(store, speedModel, speedKey(Translation.CHECKED)),
                reviewedSpeed = Translation.charsPerSecond(store, speedModel, speedKey(Translation.REVIEWED)), thoroughSpeed = Translation.charsPerSecond(store, speedModel, speedKey(Translation.THOROUGH));
        boolean measured = quickSpeed > 0 || checkedSpeed > 0;
        double quickPage = quickSpeed > 0 ? 1 / quickSpeed : checkedSpeed > 0 ? QUICK_PAGE / CHECKED_PAGE / checkedSpeed : QUICK_PAGE;
        double checkedPage = checkedSpeed > 0 ? 1 / checkedSpeed : quickPage * CHECKED_PAGE / QUICK_PAGE;
        double reviewedPage = reviewedSpeed > 0 ? 1 / reviewedSpeed : quickPage * REVIEWED_PAGE / QUICK_PAGE, thoroughPage = thoroughSpeed > 0 ? 1 / thoroughSpeed : quickPage * THOROUGH_PAGE / QUICK_PAGE;
        ModelEntries.Entry seeing = t != null ? t : ModelEntries.defaultEntry();
        String picture = Boolean.TRUE.equals(seeing.sees()) ? SEES : Boolean.FALSE.equals(seeing.sees()) ? BLIND : seeing.hosted() ? UNPROBED_HOSTED : UNPROBED;
        // a comic whose lettering will be read twice takes a call more for each speech block at the tiers that check: the speed of a comic
        // read twice on this model when one has run, else the page as reckoned so far and the blocks' calls as the helper's side timed them
        boolean twice = TWICE.equals(second(source, Translation.CHECKED, picture));
        boolean measuredTwice = false;
        if (twice) {
            double crops = CROP_SECONDS * CROPS_A_PAGE;
            double checkedTwice = Translation.charsPerSecond(store, speedModel, speedKey(Translation.CHECKED, true)), reviewedTwice = Translation.charsPerSecond(store, speedModel, speedKey(Translation.REVIEWED, true)), thoroughTwice = Translation.charsPerSecond(store, speedModel, speedKey(Translation.THOROUGH, true));
            checkedPage = checkedTwice > 0 ? 1 / checkedTwice : checkedPage + crops;
            reviewedPage = reviewedTwice > 0 ? 1 / reviewedTwice : reviewedPage + crops;
            thoroughPage = thoroughTwice > 0 ? 1 / thoroughTwice : thoroughPage + crops;
            measuredTwice = checkedTwice > 0 || reviewedTwice > 0 || thoroughTwice > 0;
        }
        measured = measured || measuredTwice;   // a comic read twice timed on this model is a measured time too: the plan does not call it a first guess
        long quick = Math.max(5, Math.round(pages * quickPage)), checked = Math.max(8, Math.round(pages * checkedPage)), reviewed = Math.max(10, Math.round(pages * reviewedPage)), thorough = Math.max(10, Math.round(pages * thoroughPage));
        boolean blocksAsked = twice && !q.equals(Translation.QUICK);   // the blocks' calls are the translator's: a hosted one's estimate counts them
        long tTokens = (long) pages * PAGE_SENT + (blocksAsked ? Math.round(pages * CROPS_A_PAGE * CROP_SENT) : 0), tWritten = (long) pages * PAGE_WRITTEN + (blocksAsked ? Math.round(pages * CROPS_A_PAGE * CROP_WRITTEN) : 0);
        double tCost = tHosted ? t.cost(tTokens, 0, tWritten) : 0;
        boolean every = q.equals(Translation.THOROUGH);
        long rTokens = !Translation.reads(q) ? 0 : (long) (pages * PAGE_SENT * (every ? 1.0 : 0.6)), rWritten = !Translation.reads(q) ? 0 : (long) (pages * PAGE_WRITTEN * (every ? 1.0 : 0.6));
        double cost = hosted ? e.cost(rTokens, 0, rWritten) : 0;
        // the helper needs the person's yes only where a set-up is theirs to say yes to: one that an earlier release set up fetches what this one adds as the run's first step
        Planned comic = new Planned(pages, container, taken.why(), taken.sampled(), taken.withBalloons(), ComicHelper.readyOrQuiet() ? READY : NEEDS, picture, t != null ? t.name() : ModelEntries.DEFAULT, source, reads(said == null ? "" : said.reads(), source), second(source, q, picture));
        return new Translation.Plan(name, container, source.code(), code, fmt, q, 0, 0, quick, checked, reviewed, thorough, measured, e == null ? "" : e.name(), hosted, cost, rTokens, t == null ? "" : t.name(), tHosted, tCost, tTokens, "",
                null, null, 0, 0, null, rWritten, List.of(), "", comic);
    }

    // ---- the run ----

    /** The key a page's reading is kept under in a job's work: the picture's own hash, so a page read once is never read again, whatever its place; and the language it was read in, when that is not Japanese. */
    static String pageKey(Path picture) throws IOException { return pageKey(picture, ""); }
    static String pageKey(Path picture, String language) throws IOException { return pageKey(picture, language, ""); }
    /** The same, and the order it was read in when the program gave one: another order numbers the blocks otherwise. */
    static String pageKey(Path picture, String language, String order) throws IOException { return "comic-page|" + ComicPages.sha(picture) + (language == null || language.isEmpty() || language.equals("ja") ? "" : "|" + language) + (order == null || order.isEmpty() ? "" : "|" + order); }

    /**
     * The comic helper for a run: set up now when it is not and the person said yes ({@link Said#setup}), each step said through the
     * run's progress; an IOException that says what a set-up takes when they did not. A helper with everything fetched and only its
     * last step undone (a set-up that was interrupted, a release that words its list otherwise) is finished without a question: there is
     * nothing to say yes to. So is one an earlier release set up, when this release adds to it less than a question is asked for
     * ({@link ComicHelper.Needs#asks}): the fonts and readers it lacks are fetched as the run's first step, and the progress says so.
     */
    static void helperReady(Said said, Consumer<ObjectNode> progress, String quality) throws IOException {
        if (ComicHelper.installed()) return;
        ComicHelper.Needs needs = ComicHelper.needs();
        if (!said.setup() && needs.asks()) throw new IOException("The comic helper is not set up on this machine, and this comic was sent without a yes to setting it up. " + ComicHelper.questionSaid(Chrome.ENGLISH)
                + " researchzosho translate comics install sets it up; then send the comic again.");
        // a helper an earlier release set up: what this release adds is fetched now, said and not asked (ComicHelper.Needs.asks)
        setupSaid(progress, quality, needs.asks() || needs.nothing() || !ComicHelper.setUpBefore() ? "Setting the comic helper up." : "The comic helper was set up for an earlier release; fetching what this release adds: " + needs.addsSaid() + ".");
        String r = ComicHelper.install(line -> setupSaid(progress, quality, line));
        if (!r.equals("ready")) throw new IOException("The comic helper could not be set up: " + r.replaceFirst("^!", "") + ".");
        setupSaid(progress, quality, "The comic helper is set up.");
    }

    private static void setupSaid(Consumer<ObjectNode> progress, String quality, String line) {
        if (progress == null) return;
        ObjectNode p = M.createObjectNode(); p.put("phase", "setting-up"); p.put("units_done", 0); p.put("units_total", 0); p.put("quality", quality); p.put("said", line);
        progress.accept(p);
    }

    /** One page as the helper read it, or why it could not be read. */
    record Read(ComicPages.Page page, JsonNode read, String unread) {
        /** The page as the helper gave it, for a request that sends it back to the helper: what the program keeps beside it ({@link #SECOND}) left out. */
        JsonNode asRead() { if (!read.has(SECOND)) return read; ObjectNode own = read.deepCopy(); own.remove(SECOND); return own; }
        int blocks(String kind) { int n = 0; for (JsonNode b : read.path("blocks")) if (b.path("kind").asText("").equals(kind)) n++; return n; }
        int readings() { int n = 0; for (JsonNode b : read.path("blocks")) n += b.path("readings").size(); return n; }
    }

    // ---- a web comic's strips ----

    /** A web comic's strip as the job holds it: the picture it was, its size, its pieces from the top down, each a page of the job, and what each cut between two pieces runs through, as the helper says it (gutter, art, balloon, lettering; empty for a helper that does not say). */
    record Strip(ComicPages.Page whole, int width, int height, List<ComicPages.Page> pieces, List<String> cuts) { }

    /** The volume as the job works on it: its pages in order, a strip's pieces standing where the strip was; the strips, each by the number of the picture it was; and the files the helper could not open as pictures, which no look at the language takes. */
    record Cut(List<ComicPages.Page> pages, Map<Integer, Strip> strips, Set<Path> unopened) {
        int pieces() { int n = 0; for (Strip s : strips.values()) n += s.pieces().size(); return n; }
        /** What the cuts of every strip run through, counted by kind, gutters first. */
        Map<String, Integer> cuts() { Map<String, Integer> out = new LinkedHashMap<>(); for (String k : List.of("gutter", "art", "balloon", "lettering")) out.put(k, 0); for (Strip s : strips.values()) for (String c : s.cuts()) out.merge(c, 1, Integer::sum); out.values().removeIf(v -> v == 0); return out; }
        /** The pages a look at the language may take: those the helper could open. */
        List<ComicPages.Page> opened() { List<ComicPages.Page> out = new ArrayList<>(); for (ComicPages.Page p : pages) if (!unopened.contains(p.file())) out.add(p); return out; }
    }

    /**
     * The volume's pictures, each asked of the helper whether it is a web comic's strip: one that is comes back cut into pieces under the
     * job's folder, each piece a page of the job from here on (read, translated, set, checkpointed and counted as a page), and an
     * ordinary page stays the page it is. What the helper said of a picture is kept with the job by the picture's hash, so a job that
     * resumes cuts nothing a second time while the pieces are still in its folder. The progress says the cutting once a strip is met.
     * A picture the helper cannot open is no strip: the reading says why it cannot be read.
     */
    static Cut cut(ComicPages.Volume volume, ComicHelper.Reader reader, Checkpoint cp, Path work, Consumer<ObjectNode> progress, BooleanSupplier stop, String quality) throws IOException {
        List<ComicPages.Page> pages = new ArrayList<>();
        Map<Integer, Strip> strips = new LinkedHashMap<>();
        Set<Path> unopened = new LinkedHashSet<>();
        int done = 0, total = volume.pages().size();
        for (ComicPages.Page page : volume.pages()) {
            if (stop.getAsBoolean()) throw new IOException("Stopped while the pages were being read; nothing was written.");
            String key = "comic-cut|" + ComicPages.sha(page.file());
            JsonNode kept = cp.get(key);
            if (kept != null && kept.path("strip").asBoolean(false)) for (JsonNode piece : kept.path("pieces")) if (!Files.isRegularFile(Path.of(piece.path("file").asText()))) { kept = null; break; }   // the pieces are gone from the job's folder: cut again
            if (kept == null) {
                Path dir = work.resolve("cut").resolve(String.format(Locale.ROOT, "p%04d", page.number()));
                ObjectNode said = M.createObjectNode();
                try {
                    Files.createDirectories(dir.getParent());
                    JsonNode cut = reader.cut(page.file(), dir);
                    boolean strip = cut.path("strip").asBoolean(false) && cut.path("pieces").size() > 0;
                    said.put("strip", strip);
                    if (strip) {
                        said.put("width", cut.path("width").asInt()); said.put("height", cut.path("height").asInt());
                        ArrayNode ps = said.putArray("pieces"); for (JsonNode piece : cut.path("pieces")) ps.addObject().put("file", piece.path("file").asText()).put("top", piece.path("top").asInt()).put("height", piece.path("height").asInt());
                        ArrayNode through = said.putArray("cut_through"); for (JsonNode c : cut.path("cut_through")) through.add(c.asText());
                    }
                } catch (ComicHelper.Failed f) { said.put("strip", false); said.put("unopened", true); }
                kept = said;
                cp.put(key, kept);
            }
            done++;
            if (kept.path("unopened").asBoolean(false)) unopened.add(page.file());
            if (!kept.path("strip").asBoolean(false)) { pages.add(page); continue; }
            List<ComicPages.Page> pieces = new ArrayList<>();
            int n = kept.path("pieces").size();
            for (JsonNode piece : kept.path("pieces")) pieces.add(new ComicPages.Page(page.number(), page.name(), Path.of(piece.path("file").asText()), pieces.size() + 1, n, piece.path("top").asInt()));
            List<String> through = new ArrayList<>(); for (JsonNode c : kept.path("cut_through")) through.add(c.asText());
            strips.put(page.number(), new Strip(page, kept.path("width").asInt(), kept.path("height").asInt(), List.copyOf(pieces), List.copyOf(through)));
            pages.addAll(pieces);
            Translation.tell(progress, "strips", done, total, quality);
        }
        return new Cut(List.copyOf(pages), strips, unopened);
    }

    /** The pages a look at the language takes: {@link #SAMPLE} of them spread through the volume, and, should those hold no lettering, as many again twice over from between them. */
    static List<List<Path>> samples(List<ComicPages.Page> pages) {
        List<Path> first = new ArrayList<>(), second = new ArrayList<>();
        int n = pages.size();
        for (int i : ComicPages.spread(n, SAMPLE)) first.add(pages.get(i).file());
        for (int k = 1; k <= 2 * SAMPLE && n > first.size(); k++) { Path f = pages.get(Math.min(n - 1, (int) Math.floor((k - 0.5) * n / (2.0 * SAMPLE)))).file(); if (!first.contains(f) && !second.contains(f)) second.add(f); }
        return second.isEmpty() ? List.of(first) : List.of(first, second);
    }

    /**
     * The language of the lettering, found by the run itself when its plan could not say: the helper the run has started says the
     * writing system of a sample of the job's own pages (a strip's pieces among them), a second sample when the first holds no
     * lettering; kept with the job, so a job that resumes reads in the language it began in.
     */
    static Source atStart(List<ComicPages.Page> pages, ComicHelper.Reader reader, Checkpoint cp) throws IOException {
        Source before = Source.of(cp.get("comic-source"));
        if (before.known()) return before;
        Source found = Source.LATER;
        int looked = 0;
        for (List<Path> sample : samples(pages)) {
            if (sample.isEmpty()) continue;
            try {
                looked += sample.size();
                found = Source.found(reader.script(sample), looked);
            } catch (ComicHelper.Failed whole) {   // one picture of the sample stopped the look: each is asked alone, and the first that says a language is taken
                if (whole.unloadable()) throw new IOException(unloadableSaid(whole));   // not a picture's failure: naming the language would change nothing
                ComicHelper.Failed last = whole;
                int answered = 0;
                for (Path one : sample) {
                    try { found = Source.found(reader.script(List.of(one)), looked); answered++; } catch (ComicHelper.Failed f) { last = f; continue; }
                    if (found.known()) break;
                }
                if (answered == 0) throw new IOException("The comic helper could not say which language the comic is lettered in (" + last.getMessage() + "). Name the language and send it again: " + NAME_IT + ", the form's choice, from in a program's call.");
            }
            if (found.known()) break;
        }
        if (found.known()) cp.put("comic-source", found.json());
        return found.known() ? found : new Source("", AT_START, looked, found.script(), 0, false, null);
    }

    /**
     * Whether a Chinese comic is lettered in columns, for one whose plan held no look (the person named the language): one look at a
     * sample of its pages, kept with the job. Null when the look finds no lettering or fails: the helper then goes page by page.
     */
    static Boolean columns(List<ComicPages.Page> pages, ComicHelper.Reader reader, Checkpoint cp) throws IOException {
        JsonNode kept = cp.get("comic-columns");
        if (kept != null && kept.has("vertical")) return kept.path("vertical").asBoolean();
        List<List<Path>> samples = samples(pages);
        if (samples.get(0).isEmpty()) return null;
        try {
            JsonNode said = reader.script(samples.get(0));
            if (said.path("script").asText("none").equals("none")) return null;
            boolean vertical = said.path("vertical").asBoolean(false);
            cp.put("comic-columns", M.createObjectNode().put("vertical", vertical));
            return vertical;
        } catch (ComicHelper.Failed f) {
            if (f.unloadable()) throw new IOException(unloadableSaid(f));
            return null;
        }
    }

    /**
     * What a run says when a piece of the helper itself does not load (a reader's file that is missing or is not as it was fetched, a
     * Python package that does not import): the helper's own words for which, and the command that loads every piece and puts right
     * what does not. Said once and the run ends: every page would fail the same way. It was said as a comic whose language could not
     * be told ("name the language and send it again"), or with a pointer to a status that said "set up" (macOS, 2026-11-14).
     */
    static String unloadableSaid(ComicHelper.Failed f) {
        return "The comic helper cannot read this comic: " + f.getMessage() + ". researchzosho translate comics install loads every piece of the helper once and puts right what does not load; then send the comic again.";
    }

    /**
     * Every page read by the helper with the reader of the comic's language, in order: a page read before, in this job or a process of
     * it that was interrupted, comes back from the job's kept work by the picture's hash; the progress is told after each page, and the
     * run's stop ends the helper within a quarter second. A page the helper cannot read (a file that is no picture) is kept as unread
     * with its reason, and the run goes on.
     */
    static List<Read> read(List<ComicPages.Page> all, ComicHelper.Reader reader, Checkpoint cp, Consumer<ObjectNode> progress, BooleanSupplier stop, String quality, String language) throws IOException {
        return read(all, reader, cp, progress, stop, quality, language, "");
    }

    /** The same, every page read in {@code order} (rtl, ltr) when the program has decided the direction for the whole comic; "" leaves it to the helper. */
    static List<Read> read(List<ComicPages.Page> all, ComicHelper.Reader reader, Checkpoint cp, Consumer<ObjectNode> progress, BooleanSupplier stop, String quality, String language, String order) throws IOException {
        List<Read> out = new ArrayList<>();
        int total = all.size();
        Translation.tell(progress, "pages", 0, total, quality);
        for (ComicPages.Page page : all) {
            if (stop.getAsBoolean()) throw new IOException("Stopped while the pages were being read; nothing was written.");
            String key = pageKey(page.file(), language, order);
            JsonNode kept = cp.get(key);
            if (kept == null) {
                try { kept = reader.read(page.file(), language, order); }
                catch (ComicHelper.Failed f) {
                    if (f.unloadable()) throw new IOException(unloadableSaid(f));   // the helper's failure, not this page's: nothing of it is kept as the page's, and no page after it is asked
                    kept = M.createObjectNode().put("unread", f.getMessage());
                }
                cp.put(key, kept);
            }
            out.add(kept.has("unread") ? new Read(page, M.createObjectNode(), kept.path("unread").asText()) : new Read(page, kept, ""));
            Translation.tell(progress, "pages", out.size(), total, quality);
        }
        return out;
    }

    /** Where a run keeps its pages while it works: the job's own folder when it is a job, so a resumed job finds them; a folder of the moment otherwise. */
    static Path workFolder(LibraryStore store, Checkpoint cp) throws IOException {
        if (cp.keeps()) { Path d = Checkpoint.dir(store, cp.jobId()).resolve("comic"); Files.createDirectories(d); return d; }
        sweepWork();
        return tempWork(temp(), WORK);
    }

    // ---- a run's folder of the moment, and the ones runs that were killed left ----

    /** What the name of a comic run's folder in the machine's temporary folder begins with, a look's too; and the file inside that the run holds locked while it lives. */
    static final String WORK = "researchzosho-comic", ALIVE = ".alive";
    /** A folder younger than this is never swept: between its making and the lock on its file lies a moment in which its run holds nothing. */
    static final long SWEEP_OLDER_MS = 60_000;
    /** The locks this process holds, by folder: released when the run removes its folder, and by the system when the process ends, however it ends. */
    private static final Map<Path, FileChannel> HELD = new ConcurrentHashMap<>();

    static Path temp() { return Path.of(System.getProperty("java.io.tmpdir")); }

    /**
     * A folder of the moment under {@code tmp}, with a file in it that this process holds locked for as long as the folder is in use.
     * The lock is how a later start of the program tells a run that lives from one that was killed: a process id in the name would
     * be another process's by then, or mean nothing where two machines' temporary folder is one; a lock the system itself lets go
     * when the process ends, by whatever means.
     */
    static Path tempWork(Path tmp, String prefix) throws IOException {
        Path dir = Files.createTempDirectory(tmp, prefix);
        try {
            FileChannel ch = FileChannel.open(dir.resolve(ALIVE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try { if (ch.tryLock() != null) HELD.put(dir, ch); else ch.close(); }
            catch (IOException | RuntimeException noLock) { ch.close(); }   // a file system with no locks: the folder is used all the same, and a sweep finds its file free only after a minute
        } catch (IOException ignored) { /* the folder is used all the same; without its file no sweep ever touches it */ }
        return dir;
    }

    /** A folder of the moment removed by the run that made it: its lock let go first, since a file held open is not deleted on Windows. */
    static void dropWork(Path dir) throws IOException {
        if (dir == null) return;
        FileChannel ch = HELD.remove(dir);
        if (ch != null) try { ch.close(); } catch (IOException ignored) { }
        Checkpoint.deleteTree(dir);
    }

    /** The sweep of the machine's temporary folder; whatever stops it is passed over, a comic is translated all the same. */
    static int sweepWork() { try { return sweepWork(temp(), SWEEP_OLDER_MS); } catch (RuntimeException e) { return 0; } }

    /**
     * Removes the folders that comic runs which no longer live left under {@code tmp}: a run killed by force (the machine switched
     * off, the process ended from outside) cannot remove its own, up to some megabytes of page pictures each (macOS and Windows,
     * 2026-11-14). A folder is removed only when all of this holds: its name begins as a run's does; it holds the file a run locks;
     * that file is older than {@code olderMs}; and its lock can be taken, which it cannot while the run that made the folder lives,
     * in this process or any other. A folder with no such file (one an earlier build left, or somebody else's) is left as it is.
     * How many were removed.
     */
    static int sweepWork(Path tmp, long olderMs) {
        int removed = 0;
        try (DirectoryStream<Path> all = Files.newDirectoryStream(tmp, WORK + "*")) {
            for (Path dir : all) {
                Path alive = dir.resolve(ALIVE);
                try {
                    if (HELD.containsKey(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(alive, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (System.currentTimeMillis() - Files.getLastModifiedTime(alive).toMillis() < olderMs) continue;
                    boolean dead;
                    try (FileChannel ch = FileChannel.open(alive, StandardOpenOption.WRITE); FileLock lock = ch.tryLock()) { dead = lock != null; }
                    catch (OverlappingFileLockException held) { dead = false; }   // held by a run of this very process
                    if (dead) { Checkpoint.deleteTree(dir); removed++; }
                } catch (IOException | RuntimeException one) { /* this folder is left; the next is looked at */ }
            }
        } catch (IOException | RuntimeException none) { /* no temporary folder to look in */ }
        return removed;
    }

    // ---- the lettering read a second time ----

    /** Where a page's reading keeps the second reading beside the helper's: {@code {"by": the translator, "blocks": {"b01": {"read": …, "taken": true}, "b02": {"read": …, "taken": false, "why": …}}}}, or {@code failed} with the helper's reason when it could not cut the page's blocks. */
    static final String SECOND = "second_reading";
    /** The most a block's second reading may write: an answer that ran this far is not a reading. */
    static final int SECOND_TOKENS = 300;
    /**
     * How sure the helper's reader is of a block (the lowest of its lines', 0 to 1) from which its letters stand: an answer that
     * differs from the helper's reading in a letter or a digit is then not taken; one that differs in marks alone still is, the
     * translator reading the marks better. Measured where the text is known (80 pages of one comic in four languages, characters
     * wrong of the edition's own): with every answer taken 107 (Korean in a brush face), 19 (French), 18 (Chinese), 16 (English);
     * with this rule 103, 18, 14, 16. Then seen on a comic not measured with, a manga in Korean in a printed face: of the blocks
     * the two read differently in a letter where the helper was this sure, a reader found the helper's the page's in 8 and the
     * translator's in 1 ("came up" had become "crashed"). Not asking for such a block at all was measured too and loses the marks:
     * 106, 36, 26, 26.
     */
    static final double SECOND_SURE = 0.93;
    /**
     * When the translator's answer is a reading of a block: at least this share of its letters is in the comic's writing; it is at
     * most {@code SECOND_LONGEST} times as long as what the helper read and at least {@code SECOND_SHORTEST} times, with
     * {@code SECOND_SLACK} characters either way allowed to any block (a balloon of two letters read as five is no runaway); and for a
     * block the helper read nothing in, which has no length to hold an answer to, at most {@code SECOND_EMPTY_MOST} characters.
     */
    static final double SECOND_SCRIPT_SHARE = 0.5, SECOND_LONGEST = 2.0, SECOND_SHORTEST = 0.5;
    static final int SECOND_SLACK = 4, SECOND_EMPTY_MOST = 200;
    /**
     * How sure the helper's reader must be of a block for its reading to be a measure of how long the block is: under it an answer
     * is not held to twice the helper's length, only to {@link #SECOND_EMPTY_MOST} letters. Read live (2026-11-14): six right
     * answers on three comics were refused as much longer than "N", "o B u" or one character of a column, the helper 0.11 to 0.77
     * sure of those; no answer rightly refused as too long was seen under 0.8.
     */
    static final double SECOND_LONG_SURE = 0.8;
    /** The rule in a sentence, for the note. */
    static final String SECOND_RULE = "An answer is taken as the block's text when it was not cut off at " + SECOND_TOKENS + " tokens, has at least half its letters in the comic's writing, and is neither more than twice nor less than half as long as what the comic helper read"
            + ", counted in letters and digits (" + SECOND_SLACK + " either way are allowed to any block; where the helper read no letter, any answer of up to " + SECOND_EMPTY_MOST + " is taken; the upper bound is held only where the helper's reader is at least " + SECOND_LONG_SURE + " sure of the block (under that, any answer of up to " + SECOND_EMPTY_MOST + " letters is taken)); an answer that keeps the balloon's own line breaks is joined into one line. Otherwise the comic helper's reading stands; and it stands where the helper's reader is at least " + SECOND_SURE + " sure of a block and the answer differs from its reading in a letter or a digit (there the helper's letters were the right ones more often when measured; an answer that differs in marks alone is taken).";

    /**
     * An answer on one line: the lines it came in joined as the helper joins the lines it reads in a block, with a space between
     * them, and with nothing for Chinese and Japanese, which write none. A model asked for one line keeps a balloon's own line breaks
     * often (read live, and in the helper's side's own measurement: 16 of 45 Korean answers, 6 of 46 Chinese ones), and the readers
     * were measured on such answers joined: to refuse them would throw away the reading where the helper's own is weakest.
     */
    static String joined(String answer, String writing) {
        if (answer == null) return "";
        List<String> lines = new ArrayList<>(); for (String l : answer.split("\\R")) if (!l.isBlank()) lines.add(flat(l));
        return String.join(writing.equals("chinese") || writing.equals("japanese") ? "" : " ", lines);
    }

    /** A text on one line of single spaces, as a block's text is held. */
    static String flat(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").strip(); }

    /** The letters and digits of a reading, in one form and one case: what two readings are compared by when the question is whether they differ in letters (not in spacing, punctuation or capitals). */
    static String letters(String s) {
        StringBuilder b = new StringBuilder();
        ChatWords.nfkc(s).codePoints().filter(Character::isLetterOrDigit).map(c -> Character.toLowerCase(Character.toUpperCase(c))).forEach(b::appendCodePoint);
        return b.toString();
    }

    /**
     * A block's two readings: the helper's; the translator's answer, on one line; whether the answer was taken as the block's text;
     * and why not when it was not.
     */
    record Second(String helper, String read, boolean taken, String why) {
        /** Whether the translator answered anything. */
        boolean answered() { return !read.isBlank(); }
        /** Whether the two readings are not the same text. */
        boolean differs() { return answered() && !read.equals(helper); }
        /** Whether they differ in letters: a doubt about what the page says. */
        boolean inLetters() { return answered() && !letters(read).equals(letters(helper)); }
    }

    /**
     * One block's answer as it came: the model's words, whether its server said the answer ran to the limit, whether the model
     * declined. A decline the seat's watch told by the answer's words keeps the words (read live, 2026-11-14: a balloon that says
     * "NO! I cannot, it is too dangerous SORRY!" was taken for the model's own refusal in every edition of the page).
     */
    record Answer(String text, boolean atLimit, boolean declined) { }

    /** What the translator is asked of one block: the words the helper's side measured the readers with, in English whatever the comic's language. */
    static String secondAsk(String languageName, boolean columns) {
        return "This image is one block of text cut from a comic page in " + languageName + ". Write out the text exactly as it is written, keeping its own spelling, capitals and punctuation. "
                + (columns ? "The text is set in columns: each column reads from top to bottom, and the columns from right to left. " : "") + "Output only the text, on one line, and nothing else.";
    }

    /**
     * One block read by the translator from its picture: one call through the job's seat, so the job's spend, its stop and its record
     * of calls cover it; cold and without thinking, {@link #SECOND_TOKENS} tokens at most. An answer that used them all ran to the
     * limit, which the server's own count says where it gives one.
     */
    static Answer second(Translation.Seats seats, String crop, String languageName, boolean columns) {
        ArrayNode messages = M.createArrayNode();
        ArrayNode content = messages.addObject().put("role", "user").putArray("content");
        content.addObject().put("type", "image_url").putObject("image_url").put("url", crop);
        content.addObject().put("type", "text").put("text", secondAsk(languageName, columns));
        try (var step = Declines.step("read one block of a comic's lettering a second time")) {
            DriveClient.forgetUsage();   // what the server says of this call, never of an earlier one on the thread (a call a resumed job gives back says nothing)
            String answer = seats.translator().prose(messages, SECOND_TOKENS);
            long[] used = DriveClient.lastUsage();
            return new Answer(answer == null ? "" : answer.strip(), used != null && used.length > 1 && used[1] >= SECOND_TOKENS, false);
        } catch (Declined x) { return new Answer(x.how() == Declined.How.WORDS ? x.said() : "", false, true); }   // a decline known by its words alone comes with them: they may be the balloon's own ("I cannot… sorry!")
    }

    /** The writing a comic's lettering is in: what the helper found on the pages it looked at, else what its language is written in. */
    static String writing(Source source) {
        if (!source.script().isEmpty() && !source.script().equals("none")) return source.script();
        String code = source.code();
        return code.equals("ja") ? "japanese" : code.equals("zh") ? "chinese" : code.equals("ko") ? "korean" : GREEK.contains(code) ? "greek" : READ_CYRILLIC.contains(code) ? "cyrillic" : "latin";
    }

    /** Whether a letter belongs to a writing: Chinese characters for Chinese, hangul and the characters for Korean, kana and the characters for Japanese, else the alphabet's own letters. */
    static boolean ownLetter(int c, String writing) {
        UnicodeScript s = UnicodeScript.of(c);
        return switch (writing) {
            case "chinese" -> s == UnicodeScript.HAN || s == UnicodeScript.BOPOMOFO;
            case "korean" -> s == UnicodeScript.HANGUL || s == UnicodeScript.HAN;
            case "japanese" -> s == UnicodeScript.HAN || s == UnicodeScript.HIRAGANA || s == UnicodeScript.KATAKANA;
            case "greek" -> s == UnicodeScript.GREEK;
            case "cyrillic" -> s == UnicodeScript.CYRILLIC;
            default -> s == UnicodeScript.LATIN;
        };
    }

    /**
     * Why an answer is not a reading of a block, "" when it is one: the model declined (its words not being what the helper read) or said nothing; the answer ran to the limit;
     * most of its letters are not in the comic's writing; or it is wildly longer or shorter than what the
     * helper read ({@link #SECOND_LONGEST}, {@link #SECOND_SHORTEST}, {@link #SECOND_SLACK}), a block the helper read nothing in being
     * held to {@link #SECOND_EMPTY_MOST} instead, and so is one the helper is under {@link #SECOND_LONG_SURE} sure of. Lengths count
     * letters and digits ({@link #letters}).
     */
    static String notAReading(String helper, Answer a, String writing) { return notAReading(helper, a, writing, -1); }

    /** Why the answer is not taken where the helper is sure of its letters and the answer differs from them in one. */
    static final String SURE_OF_ITS_LETTERS = "the comic helper is at least " + SECOND_SURE + " sure of its own reading, and the answer differs from it in a letter or a digit";

    /**
     * The same, with how sure the helper's reader is of the block (-1: it gave no number): an answer that is a reading and differs
     * from the helper's in a letter or a digit is not taken where the helper is at least {@link #SECOND_SURE} sure; one that
     * differs in marks, spaces or capitals alone is.
     */
    static String notAReading(String helper, Answer a, String writing, double sure) {
        String why = notAReadingAtAll(helper, a, writing, sure);
        if (!why.isEmpty() || sure < SECOND_SURE) return why;
        String its = letters(helper == null ? "" : helper);
        return its.isEmpty() || letters(joined(a.text(), writing)).equalsIgnoreCase(its) ? "" : SURE_OF_ITS_LETTERS;
    }

    private static String notAReadingAtAll(String helper, Answer a, String writing, double sure) {
        // a decline, unless it was told by the answer's words alone and those are letter for letter what the helper read: a balloon that says "I cannot, sorry!" is read, not refused
        if (a.declined() && (a.text().isBlank() || !letters(a.text()).equals(letters(helper)))) return "the translator declined to read it";
        if (a.text().isBlank()) return "no answer came";
        if (a.atLimit()) return "the answer ran to the limit of " + SECOND_TOKENS + " tokens";
        String read = joined(a.text(), writing);
        long all = read.codePoints().filter(Character::isLetter).count(), own = read.codePoints().filter(c -> Character.isLetter(c) && ownLetter(c, writing)).count();
        if (all > 0 && own < SECOND_SCRIPT_SHARE * all) return "most of the answer's letters are not in the comic's writing";
        // the length of each reading is its letters and digits: the dots and marks one reader drops and another keeps are no length
        String mine = letters(read), its = letters(helper == null ? "" : helper);
        long m = mine.codePointCount(0, mine.length()), h = its.codePointCount(0, its.length());
        if (h == 0) return m > SECOND_EMPTY_MOST ? "the answer is over " + SECOND_EMPTY_MOST + " letters for a block the comic helper read nothing in" : "";
        // the upper bound, held against a reading the helper is sure of (or gave no number for); a reading it is not sure of is no measure of the block's length
        if (sure >= 0 && sure < SECOND_LONG_SURE) { if (m > SECOND_EMPTY_MOST) return "the answer is over " + SECOND_EMPTY_MOST + " letters for a block the comic helper was under " + SECOND_LONG_SURE + " sure of"; }
        else if (m > Math.max(SECOND_LONGEST * h, h + SECOND_SLACK)) return "the answer is much longer than what the comic helper read";
        if (m < Math.min(SECOND_SHORTEST * h, h - SECOND_SLACK)) return "the answer is much shorter than what the comic helper read";
        return "";
    }

    /**
     * The lettering read a second time: for each page the helper read, its speech blocks cut out by the helper ({@code crops}) and
     * each given to the translator in a call of its own ({@link #second}); an answer that is a reading ({@link #notAReading}) becomes
     * the block's text, and otherwise the helper's stands. Both readings are kept in the page's reading, under the page's own key,
     * so a page read twice is never read twice again; a page a restart interrupted gets the answers it had back in their order, its
     * calls being kept on a lane of the page's own. A page whose blocks the helper cannot cut stands as the helper read it, with the
     * helper's reason kept.
     */
    static List<Read> readTwice(List<Read> pages, ComicHelper.Reader reader, Translation.Seats seats, Checkpoint cp, Path work, Consumer<ObjectNode> progress, BooleanSupplier stop, String quality, Source source, String order) throws IOException {
        List<Read> out = new ArrayList<>();
        String language = source.code(), languageName = ReadIn.name(language), writing = writing(source);
        Path dir = work.resolve("crops");
        Translation.tell(progress, "reading-twice", 0, pages.size(), quality);
        for (int p = 0; p < pages.size(); p++) {
            if (stop.getAsBoolean()) throw new IOException("Stopped while the lettering was being read a second time; nothing was written.");
            Read page = pages.get(p);
            if (!page.unread().isEmpty() || page.read().has(SECOND)) { out.add(page); Translation.tell(progress, "reading-twice", p + 1, pages.size(), quality); continue; }
            List<Block> speech = new ArrayList<>(); for (Block b : blocks(p, page.read())) if (b.speech()) speech.add(b);
            String key = pageKey(page.page().file(), language, order);
            ObjectNode second = M.createObjectNode(); second.put("by", seats.translatorName());
            ObjectNode by = second.putObject("blocks");
            if (!speech.isEmpty()) {
                try (var lane = Checkpoint.lane(Checkpoint.laneFor("lettering-", key))) {
                    List<String> ids = new ArrayList<>(); for (Block b : speech) ids.add(b.id());
                    Map<String, Path> cut = new LinkedHashMap<>();
                    for (ComicHelper.Crop c : reader.crops(page.page().file(), page.asRead(), ids, dir.resolve(String.format(Locale.ROOT, "p%04d", p + 1)))) cut.put(c.id(), c.file());
                    for (Block b : speech) {
                        if (stop.getAsBoolean()) throw new IOException("Stopped while the lettering was being read a second time; nothing was written.");
                        Path crop = cut.get(b.id());
                        if (crop == null || !Files.isRegularFile(crop)) continue;   // no picture of the block came: it is not read a second time, and the helper's reading stands
                        Answer a = second(seats, Vision.dataUrl(Files.readAllBytes(crop), "image/png"), languageName, b.vertical());
                        String why = notAReading(b.text(), a, writing, b.sure());
                        ObjectNode one = by.putObject(b.id()); one.put("read", joined(a.text(), writing)); one.put("taken", why.isEmpty());
                        if (!why.isEmpty()) one.put("why", why);
                    }
                } catch (ComicHelper.Failed f) { second.put("failed", f.getMessage()); }   // the helper cuts no blocks out of this page: it stands as read
            }
            ObjectNode kept = page.read().deepCopy(); kept.set(SECOND, second);
            cp.put(key, kept);
            out.add(new Read(page.page(), kept, ""));
            Translation.tell(progress, "reading-twice", p + 1, pages.size(), quality);
        }
        return out;
    }

    // ---- a page's lettering, as the run holds it ----

    static final String SPEECH = "speech", OUTSIDE = "outside", SOUND = "sound";

    /** A reading printed beside a word (furigana): the word, its reading, and where the word ends in its block's text. */
    record Reading(String base, String reading, int end) { }

    /**
     * One block of a page's lettering: the page it is on (from 0) and its number there in reading order (from 1, the number its box
     * carries on the picture the translator is shown); the helper's id for it; its kind (speech in a balloon, text outside a balloon, a
     * drawn sound word); its text as read; the readings printed beside its words (Japanese alone has them); its box; why a person
     * should look at it; whether it is lettered in columns; what the helper found it in (a balloon, or a caption box: the
     * narration box of a comic that is not Japanese, which is speech and lettered as a balloon is); and, when the lettering was read
     * a second time, both readings ({@link Second}): the text is then the translator's reading where that was taken, the helper's
     * where it was not. Null for a block read once. Last, the helper's reader's own confidence in its reading, 0 to 1 (the lowest
     * of its lines'), -1 where the helper gives none (Japanese, a block no line was read in, an older helper): printed in the note
     * beside the reading; where it is at least {@link #SECOND_SURE} the helper's letters stand against a second reading that differs
     * in one, and under that it is a hint that decides nothing.
     */
    record Block(int page, int number, String id, String kind, String text, List<Reading> readings, double[] box, List<String> look, boolean vertical, String found, Second second, double sure) {
        /** Speech lettered on open flat ground outside any balloon, which the helper of a comic that is not Japanese reads and letters as a balloon. */
        boolean openGround() { return speech() && found.equals("open ground"); }
        /** Speech that stands in a box of narration, not in a balloon. */
        boolean caption() { return speech() && found.equals("caption box"); }
        boolean speech() { return kind.equals(SPEECH); }
        /** Whether the block holds words to translate: a letter, not only marks ("……", "！？"). */
        boolean words() { return text.codePoints().anyMatch(Character::isLetter); }
        /** The text with each reading in parentheses after its word, as the translator and the note are given it: 帝都(ていと)大学. */
        String shown() {
            StringBuilder b = new StringBuilder(text);
            List<Reading> rs = new ArrayList<>(readings);
            rs.sort((x, y) -> y.end() - x.end());
            for (Reading r : rs) if (r.end() >= 0 && r.end() <= b.length() && !r.reading().isBlank()) b.insert(r.end(), "(" + r.reading() + ")");
            return b.toString().replaceAll("\\s+", " ").strip();
        }
    }

    /** A page's blocks from what the helper read, in its reading order. */
    static List<Block> blocks(int page, JsonNode read) {
        List<Block> out = new ArrayList<>();
        for (JsonNode b : read.path("blocks")) {
            String text = b.path("text").asText("").replaceAll("\\s+", " ").strip();
            List<Reading> readings = new ArrayList<>();
            for (JsonNode r : b.path("readings")) { String base = r.path("base").asText(""), rd = r.path("reading").asText(""); if (!base.isEmpty() && !rd.isEmpty()) readings.add(new Reading(base, rd, r.has("end") ? r.path("end").asInt() : text.indexOf(base) < 0 ? -1 : text.indexOf(base) + base.length())); }
            double[] box = new double[4]; for (int k = 0; k < 4; k++) box[k] = b.path("box").path(k).asDouble();
            List<String> look = new ArrayList<>(); for (JsonNode l : b.path("look")) look.add(l.asText());
            String kind = b.path("kind").asText(SPEECH), id = b.path("id").asText("b" + (out.size() + 1));
            JsonNode s = read.path(SECOND).path("blocks").path(id);   // the translator's own reading of the block, kept beside the helper's when the lettering was read a second time
            Second second = !s.isObject() ? null : new Second(text, flat(s.path("read").asText("")), s.path("taken").asBoolean(false), s.path("why").asText(""));
            out.add(new Block(page, out.size() + 1, id, kind.equals(SOUND) || kind.equals(OUTSIDE) ? kind : SPEECH, second != null && second.taken() ? second.read() : text, List.copyOf(readings), box, List.copyOf(look), b.path("vertical").asBoolean(false), b.path("balloon").path("found").asText(""), second, b.path("sure").isNumber() ? b.path("sure").asDouble() : -1));
        }
        return out;
    }

    // ---- one page translated ----

    /** What a page's call gave back: the lines by block number, a gloss for each sound word, a translation for each text outside a balloon, the words written for each string asked about (by its number among those asked), and where the direction could not be kept. */
    record Paged(Map<Integer, String> lines, Map<Integer, String> sounds, Map<Integer, String> outside, Map<Integer, DocNames.Answer> names, Map<Integer, String> notKept, List<Integer> leftOut) {
        static final Paged NOTHING = new Paged(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.of());
        /**
         * Whether every balloon of the page with words has its line. A reply that leaves a number out is not taken as it stands: read live
         * (2026-11-13), a page whose balloons were 2 to 6 came back as lines 1 to 5, and one where two balloons made a sentence came back a
         * line short with the rest moved up, so that four balloons of each page held their neighbour's words and no check saw it.
         */
        boolean whole() { return leftOut.isEmpty(); }
    }

    /**
     * A reply's lines as a model writes them, read live (2026-11-13): the numbered line with a full stop, a bracket or a colon after its
     * number ("1. …", "1) …", "1: …"); the marked line with or without a comma after its word ("SOUND 2: …", "SOUND, 2: …"); and a
     * heading of its own copied from the page's text ("OUTSIDE:", "NAMES:") with plain numbered lines under it. For a translation into
     * Chinese or Japanese the number may come in full-width digits and with the mark those languages set after one ("1、…", "１．…"):
     * no run has shown these yet; they are read so that a line's number is never lettered into its balloon.
     */
    private static final Pattern LINE = Pattern.compile("^\\s*([\\d０-９]+)\\s*[.):：、．]\\s*(.*)$"),
            MARKED = Pattern.compile("(?i)^\\s*(SOUND(?:\\s+WORDS?)?|OUTSIDE(?:\\s+THE\\s+BALLOONS?)?|NAMES?)\\s*,?\\s*([\\d０-９]+)\\s*[.):：、．]\\s*(.*)$"),
            HEADING = Pattern.compile("(?i)^\\s*[#*]*\\s*(LINES|SOUND(?:\\s+WORDS?)?|SOUNDS|OUTSIDE(?:\\s+THE\\s+BALLOONS?)?|NAMES?(?:\\s+AND\\s+TERMS)?)\\s*[:：]?\\s*[*]*\\s*$"),
            NAMED = Pattern.compile("(?i)^\\s*(person|organi[sz]ation|place|work|term|none)\\b\\s*(?:[|｜:：,-]\\s*(.*))?$");

    /**
     * Whether a comic's pages are read from the right, said once for the run in the page's call: what the helper says it did on most
     * of the pages with lettering ({@code direction}: rtl for Japanese and for a Chinese page lettered in columns, ltr for everything
     * else). For a helper that does not say: Japanese is, Chinese is when most of its blocks are lettered in columns, nothing else is.
     */
    static boolean fromTheRight(String source, List<Read> pages, List<List<Block>> byPage) {
        int rtl = 0, ltr = 0, vertical = 0, blocks = 0;
        for (int p = 0; p < pages.size(); p++) {
            if (byPage.get(p).isEmpty()) continue;
            String direction = pages.get(p).read().path("direction").asText("");
            if (direction.equals("rtl")) rtl++; else if (direction.equals("ltr")) ltr++;
            for (Block b : byPage.get(p)) { blocks++; if (b.vertical()) vertical++; }
        }
        if (rtl + ltr > 0) return rtl > ltr;
        return source.equals("ja") || (source.equals("zh") && vertical * 2 > blocks);
    }

    /**
     * The two languages of a run, as the page's call, the names and the checks need them: the source's code and name, the target's code,
     * name and writing system, and whether the comic's pages are read from the right ({@link #fromTheRight}).
     */
    record Pair(String from, String fromName, String to, String toName, String script, boolean rtl) {
        static Pair of(String from, String to, boolean rtl) throws Translation.Refused {
            return new Pair(from, Translate.LANGUAGES.getOrDefault(from, ReadIn.name(from)), to, Translate.LANGUAGES.getOrDefault(to, ReadIn.name(to)), ComicTranslation.script(to), rtl);
        }
        /** The comic has readings printed beside its words, sideways numbers and names in kanji: the Japanese-source matters. */
        boolean japanese() { return from.equals("ja"); }
        boolean latin() { return script.equals("latin"); }
        /** A printed reading is spelt in Latin letters for the translator, and a name is held to that spelling: Japanese into the Latin alphabet. */
        boolean hepburn() { return japanese() && latin(); }
        /** The target is written in capitals in a balloon: the three alphabets; Chinese, Japanese and Korean have none. */
        boolean capitals() { return ComicHelper.ALPHABETS.contains(script); }

        /** How the page's call is told to write a name, by the two languages. */
        String namesRule() {
            if (japanese()) {
                if (latin()) return "Write a name from its reading where one is given; the reading's spelling in Latin letters stands beside it. ";
                if (to.equals("zh")) return "Write a name that the page writes in kanji with those characters, as Chinese writes them, and a name that the page writes in kana as Chinese writes that name. ";
                if (to.equals("ko")) return "Write a name in hangul, from its reading where one is given. ";
                return "Write a name from its reading where one is given, in the letters " + toName + " writes names in. ";
            }
            if (to.equals("ja")) return from.equals("zh") ? "Write a name that the page writes in Chinese characters with those characters, as Japanese writes them, and any other name in katakana. " : "Write a name in katakana, as Japanese writes a name from abroad. ";
            if (to.equals("zh") || to.equals("ko")) return "Write a name as " + toName + " writes that name, in its own characters. ";
            return "Write a name as " + toName + " writes it, in the letters " + toName + " writes names in. ";
        }
    }

    /** What the translator is told of a page, the same for every page of a run: what it is given, what it answers, and that what stands between the markers is text to translate. */
    static String instructions(Pair pair, boolean picture, String direction) {
        boolean directed = direction != null && !direction.isEmpty();
        String language = pair.toName();
        return "You translate one page of a comic from " + pair.fromName() + " into " + language + ", to be lettered into its speech balloons. "
                + (picture ? "The picture is the page: each red box on it is one block of its lettering, with the block's number on the tab above the box. " : "")
                + "Between the markers is what was read from the page, its blocks numbered in reading order (" + (pair.rtl() ? "from the right to the left" : "from the left to the right") + ", from the top down)"
                + (pair.japanese() ? "; a reading printed beside a word on the page stands after the word in parentheses. " : ". ")
                + "For every block under LINES answer with its number and its translation, one line each, in order: what the character says, as natural speech in " + language + " for a comic, and no longer than it must be, since it has to go into the balloon. "
                + "Keep every number that is written, in digits. " + pair.namesRule()
                + "Write each name and term listed as already written exactly as listed. " + (pair.japanese() ? "Where a block uses a short form of a listed name, or a name with a suffix such as 出 or 卒 (a graduate of), work out from the list what it stands for and translate it so. " : "Where a block uses a short form of a listed name, work out from the list what it stands for and translate it so. ")
                + "The page before this one is given as translated, for the thread of the story only: translate nothing of it again. "
                + "After the numbered lines write one line for each block under SOUND WORDS, a gloss of one to three words (the sound itself stays as art on the page); one line for each block under OUTSIDE THE BALLOONS, its translation (a sign, a caption, a label); "
                + "and one line for each string under NAMES: its kind (person, organisation, place, work, term, or none when it is no name and no term) and the words your lines write for it, exactly as they write them. "
                + "Write them in this form, with your own words in the place of these, for a page whose lines are 1 and 3, with a sound word 2, a sign 4 and two strings under NAMES:\n1. Where did he go?\n3. Back by 5, he said.\nSOUND 2: door slams\nOUTSIDE 4: Staff only\nNAME 1: person | Tanaka\nNAME 2: none\n"
                + "A block keeps its own number: the number on its box and in the text. Where one sentence runs over two balloons, each balloon keeps its own part of it under its own number. After the text you are given the beginning of every line of your answer; write those lines and no others. "
                + (directed ? Translate.directed(direction, language, true) : "Write every line in the register its speaker would use.") + " " + Translate.fenceRule(Translation.FENCE);
    }

    /**
     * One page's call. {@code page}: its blocks; {@code picture}: the numbered picture as a data address, or null for a translator that
     * does not see; {@code elsewhere}: readings printed elsewhere in the comic for words that stand on this page without one, each as
     * {word, reading, spelling}; {@code written}: the names and terms of this page the pages before wrote, string to form; {@code asked}:
     * the strings of this page to name, each as {string, a note on its reading or ""}; {@code before}: the page before as translated.
     * A reply with none of the page's lines is no reply ({@link Paged#NOTHING}).
     */
    static Paged ask(Translation.Seats seats, Pair pair, int pageNumber, List<Block> page, String picture, List<String[]> elsewhere, Map<String, String> written, List<String[]> asked, List<String> before, Map<String, String> glossary) {
        return ask(seats, pair, pageNumber, page, picture, elsewhere, written, asked, before, glossary, List.of(), 0);
    }

    /**
     * The same call, asked again or for one balloon. {@code leftOut}: the numbers an earlier answer to this page left out, said to the
     * translator in a sentence; {@code only}: the number of the one balloon to translate, the page's other lines standing for the thread
     * (0: the whole page). After the text read from the page the call gives the beginning of every line of the answer, with the page's
     * own numbers: a model copies a number written out for it, and counts from 1 where it has to carry one over.
     */
    static Paged ask(Translation.Seats seats, Pair pair, int pageNumber, List<Block> page, String picture, List<String[]> elsewhere, Map<String, String> written, List<String[]> asked, List<String> before, Map<String, String> glossary,
                     List<Integer> leftOut, int only) {
        String language = pair.toName();
        if (only > 0) asked = List.of();
        StringBuilder u = new StringBuilder(), all = new StringBuilder();
        if (!elsewhere.isEmpty()) { u.append("READINGS printed elsewhere in this comic beside words of this page:\n"); for (String[] r : elsewhere) u.append(r[0]).append(" = ").append(r[1]).append(r[2].isEmpty() ? "" : " (" + r[2] + ")").append('\n'); u.append('\n'); }
        if (!written.isEmpty()) { u.append("NAMES AND TERMS ALREADY WRITTEN by the pages before this one:\n"); for (var e : written.entrySet()) u.append(e.getKey()).append(" = ").append(e.getValue()).append('\n'); u.append('\n'); }
        if (!before.isEmpty()) { u.append("THE PAGE BEFORE THIS ONE, as translated:\n"); for (String l : before) u.append(l).append('\n'); u.append('\n'); }
        u.append("LINES:\n");
        for (Block b : page) if (b.speech() && b.words()) { u.append(b.number()).append(". ").append(b.shown()).append('\n'); all.append(b.text()).append('\n'); }
        boolean sounds = false, outside = false;
        for (Block b : page) if (b.kind().equals(SOUND) && b.words()) { u.append(sounds ? "" : "\nSOUND WORDS:\n").append(b.number()).append(". ").append(b.text()).append('\n'); sounds = true; }
        for (Block b : page) if (b.kind().equals(OUTSIDE) && b.words()) { u.append(outside ? "" : "\nOUTSIDE THE BALLOONS:\n").append(b.number()).append(". ").append(b.shown()).append('\n'); outside = true; all.append(b.text()).append('\n'); }
        if (!asked.isEmpty()) { u.append("\nNAMES:\n"); for (int k = 0; k < asked.size(); k++) u.append(k + 1).append(". ").append(asked.get(k)[0]).append(asked.get(k)[1].isEmpty() ? "" : " (" + asked.get(k)[1] + ")").append('\n'); }
        ArrayNode messages = M.createArrayNode();
        messages.addObject().put("role", "system").put("content", instructions(pair, picture != null, seats.direction()) + Translate.renderings(glossary, all.toString()));
        StringBuilder form = new StringBuilder();
        List<Integer> wanted = new ArrayList<>();
        for (Block b : page) if (b.speech() && b.words()) wanted.add(b.number());
        if (only > 0) form.append("Translate the line numbered ").append(only).append(" alone; the other lines are given for the thread of the page. Your answer is this one line:\n").append(only).append(". ");
        else {
            if (!leftOut.isEmpty()) form.append("An answer to this page left out ").append(leftOut.size() == 1 ? "the line numbered " : "the lines numbered ").append(numbersSaid(leftOut)).append(", or wrote the lines under other numbers than their own. ");
            form.append("The lines of your answer begin as follows, ").append(wanted.size() == 1 ? "the one line of speech numbered " : "the " + wanted.size() + " lines of speech numbered ").append(numbersSaid(wanted)).append(":\n");
            for (int n : wanted) form.append(n).append(". \n");
            for (Block b : page) if (b.kind().equals(SOUND) && b.words()) form.append("SOUND ").append(b.number()).append(": \n");
            for (Block b : page) if (b.kind().equals(OUTSIDE) && b.words()) form.append("OUTSIDE ").append(b.number()).append(": \n");
            for (int k = 0; k < asked.size(); k++) form.append("NAME ").append(k + 1).append(": \n");
        }
        String text = Fence.wrap(Translation.FENCE, u.toString().stripTrailing()) + "\n\n" + form.toString().stripTrailing();
        if (picture == null) messages.addObject().put("role", "user").put("content", text);
        else { ArrayNode content = messages.addObject().put("role", "user").putArray("content"); content.addObject().put("type", "image_url").putObject("image_url").put("url", picture); content.addObject().put("type", "text").put("text", text); }
        String answer;
        try (var step = Declines.step("translate page " + pageNumber + " of a comic into " + language)) { answer = seats.translator().prose(messages, Math.min(6000, Math.max(900, u.length() * 2 + 500))); }
        catch (Declined x) { return Paged.NOTHING; }   // a call the model declines is not asked again: the balloons go to the checks as untranslated
        if (answer == null || answer.isBlank()) return wanted.isEmpty() ? Paged.NOTHING : new Paged(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.copyOf(wanted));
        Map<Integer, String> lines = new LinkedHashMap<>(), sound = new LinkedHashMap<>(), out = new LinkedHashMap<>(), notKept = new LinkedHashMap<>();
        Map<Integer, DocNames.Answer> names = new LinkedHashMap<>();
        Map<Integer, Block> byNumber = new LinkedHashMap<>(); for (Block b : page) byNumber.put(b.number(), b);
        if (only > 0) {   // one balloon: the first line that says anything is its translation, whatever number stands before it
            for (String raw : answer.split("\n")) {
                Matcher m = LINE.matcher(raw.strip());
                String tr = unquoted(m.matches() ? m.group(2) : raw.strip());
                if (tr.isBlank() || HEADING.matcher(raw.strip()).matches()) continue;
                return new Paged(Map.of(only, Translation.unparenthesised(tr, glossary)), Map.of(), Map.of(), Map.of(), Map.of(), List.of());
            }
            return Paged.NOTHING;
        }
        String section = "LINES";
        for (String raw : answer.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            Matcher m;
            if ((m = HEADING.matcher(line)).matches()) { section = sectionOf(m.group(1)); continue; }
            if ((m = Translate.NOT_KEPT_LINE.matcher(line)).matches()) { int n = Integer.parseInt(m.group(1)); if (byNumber.containsKey(n) && !m.group(2).isBlank()) notKept.merge(n, m.group(2).strip(), (a, b) -> a + "; " + b); continue; }
            String in = section, rest;
            int n;
            boolean marked = false;
            if ((m = MARKED.matcher(line)).matches()) { in = sectionOf(m.group(1)); n = Integer.parseInt(m.group(2)); rest = m.group(3); marked = true; }
            else if ((m = LINE.matcher(line)).matches()) { n = Integer.parseInt(m.group(1)); rest = m.group(2); }
            else continue;
            if (in.equals("NAMES")) {   // by its number among the strings asked about: a kind, and the words written
                Matcher k = NAMED.matcher(rest.strip());
                if (k.matches() && n >= 1 && n <= asked.size()) names.putIfAbsent(n, new DocNames.Answer(k.group(1).toLowerCase(Locale.ROOT).replace("organization", "organisation"), k.group(2) == null ? "" : unquoted(k.group(2))));
                continue;
            }
            Block b = byNumber.get(n);
            String tr = unquoted(rest);
            if (b == null || tr.isBlank() || NAMED.matcher(tr).matches() || tr.matches("(?i)\\((?:none|sound|sound word|no text|blank)\\)")) continue;   // a number the page has not, an empty line, a name's line under a line's number, or a filler
            if (b.speech()) lines.putIfAbsent(n, Translation.unparenthesised(tr, glossary));   // under whatever heading: the block's own kind says what the line is
            else if (marked) (b.kind().equals(SOUND) ? sound : out).put(n, tr);   // a line marked SOUND or OUTSIDE is the model's own word for it, over the same number among the lines
            else (b.kind().equals(SOUND) ? sound : out).putIfAbsent(n, tr);
        }
        List<Integer> lacking = new ArrayList<>(wanted); lacking.removeAll(lines.keySet());
        return lines.isEmpty() && sound.isEmpty() && out.isEmpty() ? new Paged(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), List.copyOf(wanted)) : new Paged(lines, sound, out, names, notKept, List.copyOf(lacking));
    }

    /** Numbers in a row, as a sentence names them: "2, 3 and 6". */
    static String numbersSaid(List<Integer> numbers) {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < numbers.size(); k++) b.append(k == 0 ? "" : k == numbers.size() - 1 ? " and " : ", ").append(numbers.get(k));
        return b.toString();
    }

    /** The part of a reply a heading or a line's first word names: LINES, SOUND, OUTSIDE or NAMES. */
    private static String sectionOf(String word) {
        String w = word.toUpperCase(Locale.ROOT);
        return w.startsWith("SOUND") ? "SOUND" : w.startsWith("OUTSIDE") ? "OUTSIDE" : w.startsWith("NAME") ? "NAMES" : "LINES";
    }

    /** A line without the quotation marks a model sets round a whole line of speech. */
    static String unquoted(String s) {
        String t = s == null ? "" : s.strip();
        if (t.length() >= 2 && ((t.startsWith("「") && t.endsWith("」")) || (t.startsWith("\"") && t.endsWith("\"") && t.indexOf('"', 1) == t.length() - 1) || (t.startsWith("“") && t.endsWith("”") && t.indexOf('“', 1) < 0))) t = t.substring(1, t.length() - 1).strip();
        return t;
    }

    // ---- the names: found by the program, written by the pages, decided by the document's own mechanism ----

    /**
     * The strings a comic's pages are held to write one way, found by the program before page 1 from every page's reading, never named by
     * the model: the strings the speech repeats ({@link DocNames#candidates}), and the words that carry a printed reading, since a
     * reading printed beside a word marks a name or a word the author thought hard, even where it stands once. A string is put to a
     * page's call only once it has a reading or stands on a second page: one seen once, with no reading, is not named from one look.
     */
    static final class Names {
        final List<DocNames.Candidate> candidates = new ArrayList<>();
        /** A word's printed reading, the first met in the comic. */
        final Map<String, String> reading = new LinkedHashMap<>();
        /** For each candidate, the pages (from 0) whose speech holds it. */
        final Map<String, List<Integer>> pages = new LinkedHashMap<>();
        /** What the pages' calls said so far: a string, the words written for it and its kind, accepted by {@link #accept}. */
        final Map<String, DocNames.Answer> written = new LinkedHashMap<>();
        /** How often a page's call was asked about a string and named no words for it: after the second time it is not asked again. */
        final Map<String, Integer> unnamed = new LinkedHashMap<>();
        /** The answers turned down, each with why: for the note. */
        final List<String> refused = new ArrayList<>();

        Names(List<List<Block>> byPage, List<String> unitTexts) {
            Set<String> seen = new LinkedHashSet<>();
            for (List<Block> page : byPage) for (Block b : page) if (!b.kind().equals(SOUND)) for (Reading r : b.readings()) reading.putIfAbsent(r.base(), r.reading());
            for (DocNames.Candidate c : DocNames.candidates(unitTexts, "")) if (seen.add(c.text())) candidates.add(c);
            for (String base : reading.keySet()) {
                if (base.codePointCount(0, base.length()) < 2 || !base.codePoints().allMatch(DocNames::nameChar) || !seen.add(base)) continue;
                int[] n = DocNames.count(unitTexts, base);
                if (n[0] > 0) candidates.add(new DocNames.Candidate(base, DocNames.sentence(unitTexts, base), n[0], n[1], false));
            }
            for (DocNames.Candidate c : candidates) {
                List<Integer> at = new ArrayList<>();
                for (int p = 0; p < byPage.size(); p++) { int page = p; if (byPage.get(p).stream().anyMatch(b -> b.speech() && b.text().contains(c.text()))) at.add(page); }
                pages.put(c.text(), at);
            }
        }

        /** The reading that covers a string, whole or a part of it of two characters or more: {word, reading}, or null. */
        String[] readingFor(String s) {
            if (reading.containsKey(s)) return new String[]{s, reading.get(s)};
            for (var e : reading.entrySet()) if (e.getKey().codePointCount(0, e.getKey().length()) >= 2 && s.contains(e.getKey())) return new String[]{e.getKey(), e.getValue()};
            return null;
        }

        /** The strings of a page to put to its call: on this page, not yet written, and with a reading or on its second page or later; at most twelve. */
        List<String> toAsk(int page) {
            List<String> out = new ArrayList<>();
            for (DocNames.Candidate c : candidates) {
                List<Integer> at = pages.get(c.text());
                if (written.containsKey(c.text()) || unnamed.getOrDefault(c.text(), 0) >= 2 || !at.contains(page) || out.size() >= 12) continue;
                if (readingFor(c.text()) != null || at.indexOf(page) >= 1) out.add(c.text());
            }
            return out;
        }

        /** The strings of a page the pages before wrote, each with the words written. */
        Map<String, String> writtenOn(int page) {
            Map<String, String> out = new LinkedHashMap<>();
            for (var e : written.entrySet()) if (pages.getOrDefault(e.getKey(), List.of()).contains(page) && !e.getValue().words().isBlank()) out.put(e.getKey(), e.getValue().words());
            return out;
        }

        /**
         * An answer of a page's call taken or turned down: taken only when the words stand in the translation of a block of that page that
         * holds the string, and, for a name with a printed reading and a target in Latin letters ({@code hepburn}: a Japanese comic into
         * the Latin alphabet), hold that reading's spelling. A string the call names no words for (none, or a name turned down for its
         * spelling) is asked once more on its next page, and then left.
         */
        void accept(String s, DocNames.Answer a, List<Block> page, Map<Integer, String> lines, boolean hepburn) {
            String words = a.words().strip();
            if (a.kind().equals(DocNames.NONE) || words.isEmpty()) { unnamed.merge(s, 1, Integer::sum); return; }
            boolean stands = false;
            for (Block b : page) if (b.speech() && b.text().contains(s) && lines.get(b.number()) != null && Translation.usesRendering(lines.get(b.number()), words)) { stands = true; break; }
            if (!stands) { refused.add(s + " → " + words + ": the page's own lines do not write it"); return; }
            String[] r = readingFor(s);   // a name is held to its printed reading; a term's reading says how the word is said, and the word is translated
            if (hepburn && r != null && DocNames.NAMES.contains(a.kind()) && !holds(words, r[0])) { unnamed.merge(s, 1, Integer::sum); refused.add(s + " → " + words + ": a name, and it does not hold the printed reading " + r[1] + " (" + spelling(r[0]) + ")"); return; }
            written.put(s, new DocNames.Answer(a.kind(), words));
        }

        /** The answers as the document's own names step takes them: every string a page's call named words for. */
        Map<String, DocNames.Answer> answers() { return new LinkedHashMap<>(written); }

        /**
         * A word's printed reading as a name is spelt in Latin letters. A full name is spelt by its parts where the comic prints a
         * reading for the part too: 斉藤健太郎 (さいとうけんたろう), with 斉藤 (さいとう) printed elsewhere, is Saito Kentaro, where the kana
         * alone would run the two together.
         */
        String spelling(String base) {
            String r = reading.get(base);
            if (r == null) return "";
            for (var e : reading.entrySet()) {
                String part = e.getKey(), pr = e.getValue();
                if (part.length() >= 2 && part.length() < base.length() && base.startsWith(part) && r.startsWith(pr) && r.length() > pr.length())
                    return Hepburn.of(pr, hasKanji(part)) + " " + Hepburn.of(r.substring(pr.length()), hasKanji(base.substring(part.length())));
            }
            return Hepburn.of(r, hasKanji(base));
        }

        /** Whether words in Latin letters hold a word's printed reading: its spelling ({@link Hepburn#held}), or each part of it where it is spelt by its parts (Kentaro Saito for さいとうけんたろう). */
        boolean holds(String words, String base) {
            if (Hepburn.held(words, reading.get(base), hasKanji(base))) return true;
            String[] parts = spelling(base).split(" ");
            if (parts.length < 2) return false;
            for (String part : parts) if (!Hepburn.letters(words).contains(Hepburn.letters(part))) return false;
            return true;
        }

        /** The words the pages wrote for the strings that stand in a text: what a balloon's revision is given beside the names decided. */
        Map<String, String> writtenIn(String text) {
            Map<String, String> out = new LinkedHashMap<>();
            for (var e : written.entrySet()) if (text.contains(e.getKey()) && !e.getValue().words().isBlank()) out.put(e.getKey(), e.getValue().words());
            return out;
        }
    }

    static boolean hasKanji(String s) { return s.codePoints().anyMatch(DocNames::han); }

    // ---- the fit: a line too long for its balloon said shorter, once ----

    /**
     * The sizes the fit goes by, for a target's writing: a line is too small when it is set under {@code pageShare} of the size the rest
     * of its page is set at (a page is lettered at one size, and a line much smaller than its neighbours is the one a reader stumbles
     * on), or under {@code glyphShare} of the size of the lettering it replaces, or under {@code readable} pixels on a page 2,000
     * pixels high (0: no such size is held); {@code smallestSet} is the smallest size the helper sets at all, and
     * {@code smallestBudget} the fewest characters a shorter line is ever asked for.
     */
    record Fit(double pageShare, double glyphShare, int smallestSet, int smallestBudget, double readable) { }

    /** The height of the page the {@link Fit#readable} size is said for. */
    static final double READABLE_ON = 2000.0;

    /**
     * THE one place the fit's values stand, by the target's writing.
     *
     * <p>The alphabets (Latin, Cyrillic, Greek): four fifths of the page's size, and 0.65 of the Japanese it replaces (Latin capitals
     * that small are not read on a screen that shows the Japanese well); never asked for under twelve characters. Chosen on one volume
     * of Japanese into English, where the first sends 4 of 155 lines back and the second none; neither has been measured on many books.
     *
     * <p>Chinese, Japanese and Korean: the same two shares, and one size more, 20 px on a page 2,000 px high, the smallest at which
     * the helper's font for them (Noto Sans CJK Medium) reads without effort: on a ladder of sizes 18 px still reads and 16 px does
     * not for dense characters, and with this floor none of the 319 blocks of the lettering side's proof pages goes back (its notes,
     * 2026-11-14). How large a page is set at all is the helper's own cap. The fewest characters asked for is five, a character of
     * these writings saying what several letters say: the lettering side's figure too, and not measured.
     */
    static final Fit FIT_ALPHABET = new Fit(0.8, 0.65, 8, 12, 0), FIT_CJK = new Fit(0.8, 0.65, 8, 5, 20.0);

    /** The fit's values for a target's writing system (as {@link #script} names it). */
    static Fit fit(String script) { return ComicHelper.CJK_SCRIPTS.contains(script) ? FIT_CJK : FIT_ALPHABET; }

    /** The share of the room a shorter line is asked to fill: a line fits a balloon by its words' breaks as well as its length, so a little is left. */
    static final double BUDGET_SHARE = 0.9;

    /**
     * The smallest size a line of a page may be set at before it goes back to be said shorter, in pixels: the largest of the fit's
     * shares, from the page as read ({@code dialogue_glyph_px}) and as set ({@code page_px}), and of its readable size at the page's
     * own height ({@code height}).
     */
    static int floor(Fit fit, JsonNode read, JsonNode report) {
        double glyph = read.path("dialogue_glyph_px").asDouble(0), page = report == null ? 0 : report.path("page_px").asDouble(0), height = read.path("height").asDouble(0);
        return (int) Math.max(fit.smallestSet() + 1, Math.round(Math.max(Math.max(fit.pageShare() * page, fit.glyphShare() * glyph), fit.readable() * height / READABLE_ON)));
    }

    /** What the helper's reason begins with when it leaves a block as it is because its lettering joins the art: such a block is listed with the sound words, never sent back to be said shorter. */
    static final String DRAWN_INTO_ART = "the lettering is drawn into the art";

    /** Whether a block is set under the floor only because its page is: its own text would take the floor's size or more in its balloon, as the helper says ({@code largest_px}). */
    static boolean heldByPage(JsonNode rec, int floor) {
        return rec != null && rec.path("set").asBoolean(false) && rec.path("px").asInt(floor) < floor && rec.has("largest_px") && rec.path("largest_px").asInt(0) >= floor;
    }

    /** Whether a block's setting came out too small for its own text: set under the floor with its own balloon holding it there, or not set because the text does not go into the balloon at the smallest size. */
    static boolean tooSmall(JsonNode rec, int floor) {
        if (rec == null || rec.isMissingNode()) return false;
        // set under the floor, and its own text is what holds it there: the largest size the text takes in its balloon (largest_px, where
        // the helper says it) is under the floor too. A line small only because its page's one size is small (a page is set no larger than
        // its original lettering) would be set no larger for being said shorter: it is not sent back (read live, 2026-11-14: 61 of 126)
        if (rec.path("set").asBoolean(false)) return rec.path("px").asInt(floor) < floor && (!rec.has("largest_px") || rec.path("largest_px").asInt(floor) < floor);
        return rec.path("why_not").asText("").startsWith("the text does not go into the balloon");
    }

    /** The characters of the target a line may have to be set at the floor: its own length scaled by the room it took (a text's room goes with the square of its size), a little kept back; never under the fit's fewest. */
    static int budget(Fit fit, String text, JsonNode rec, int floor) {
        int px = rec.path("set").asBoolean(false) ? Math.max(1, rec.path("px").asInt(fit.smallestSet())) : fit.smallestSet() - 1;
        double share = (double) px * px / ((double) floor * floor);
        return (int) Math.max(fit.smallestBudget(), Math.floor(text.codePointCount(0, text.length()) * share * BUDGET_SHARE));
    }

    /** One line said shorter by the translator, in a fresh call: the same meaning in at most {@code budget} characters; null when it gives none. */
    static String shorter(Translation.Seats seats, String source, String line, int budget, String language, Map<String, String> glossary) {
        ArrayNode messages = M.createArrayNode();
        messages.addObject().put("role", "system").put("content", "You shorten one line of a comic's translation into " + language + ", because it is too long to be lettered into its speech balloon at a size a reader can read. "
                + "Say what the line says in at most " + budget + " characters, spaces counted: the same meaning, as the character would say it, every number as it stands and every name as the line writes it. "
                + (seats.directed() ? "Keep the voice the person who handed over the comic asked for: \"" + seats.direction() + "\". " : "")
                + "Write the shorter line and nothing else. " + Translate.fenceRule(Translation.FENCE) + Translate.renderings(glossary, source));
        messages.addObject().put("role", "user").put("content", Fence.wrap(Translation.FENCE, "SOURCE:\n" + source + "\n\nTRANSLATION:\n" + line));
        try (var step = Declines.step("shorten one line of a comic's translation into " + language)) { String r = seats.translator().prose(messages, 400); return r == null || r.isBlank() ? null : unquoted(r.strip().split("\n")[0].replaceFirst("(?i)^(?:shorter(?: line)?|translation)\\s*[:：]\\s*", "")); }
        catch (Declined x) { return null; }
    }

    /** A line that went back for the fit: its unit, what it was and at what size, what it became and at what size (0 for not set), and whether the shorter one was kept. */
    record Fitted(int unit, String before, int beforePx, int budget, String after, int afterPx, boolean kept, String why) { }

    // ---- what a comic's translation came to ----

    /**
     * What the second reading of the lettering came to: whether it was made ({@link #TWICE}) or why the helper alone read
     * ({@link #ONCE_QUICK}, {@link #ONCE_BLIND}, {@link #ONCE_JAPANESE}); the blocks the translator was asked, the readings taken as
     * the block's text, those that differ from the helper's and those that differ in letters; the answers not taken, counted by why;
     * and the pages whose blocks the helper could not cut out, each with its reason.
     */
    public record Twice(String how, int asked, int taken, int differed, int inLetters, Map<String, Integer> notTaken, List<String> notCut) {
        public boolean on() { return how.equals(TWICE); }
        /** Whether any block was in fact read a second time: a run that was to, and whose helper cut no block out, read the lettering once. */
        public boolean made() { return on() && asked > 0; }
    }

    /** One block as the note's table holds it: what was read, what it was translated as, how it was set, and whether that was in columns. */
    record Row(Block block, String translation, String set, boolean columns) { }

    /**
     * What a comic's translation came to, beside what any translation's outcome holds: its pages and those that could not be read, its
     * blocks by kind, the readings found, whether the pages went to the translator as pictures (and why not), the balloons set and not
     * set (with why, counted), the sizes, the lines that went back for the fit, the answers of the names turned down, the helper as it
     * said itself, what of the volume was left out, whether the target has hyphenation patterns, every page's rows for the note, the
     * units in order (a unit's page and block), how many strings the pages' calls named, whether the second seat saw the pages, the
     * pages asked a second time because their answer left a balloon's number out, and those of them translated a balloon at a call;
     * the language of the lettering and how it was known; each page of the job as a person names it ("3", or "3, part 2 of 7" for a
     * piece of a web comic's strip), the strips there were and the pieces they were cut into, the balloons set in columns, the
     * fit's values the run went by, what the cuts of the strips run through, counted by kind, the lines that end inside a phrase
     * (Chinese, Japanese, Korean), what the helper says when it has nothing to tell a phrase by, how many names and terms it was
     * given to keep whole on a line, the way the pages were read with how that was known, the balloons in which a line ends
     * inside a name or a term all the same, what the second reading of the lettering came to ({@link Twice}), the balloons left
     * as art because their lettering is drawn into it, the lines set under the floor only because their page's one size is, and the
     * balloons set at a size of their own under their page's, and the words broken at a line's end. {@code pages} counts the volume's pictures, a strip as the one picture it is; the rows, the names
     * and the labels go by the job's pages, a strip's pieces each one.
     */
    public record Done(int pages, List<String> unread, int speech, int outside, int sound, int readings, boolean pictures, String picturesWhy, int set, Map<String, Integer> notSet, int smallestPx, int largestPx,
                       List<Fitted> fitted, List<String> namesRefused, Map<String, String> readingSpellings, String helper, List<String> leftOut, boolean hyphenated, List<List<Row>> rows, List<String> pageNames, String container, String taken,
                       int otherFont, int belowFloor, List<Block> units, int namesWritten, boolean readerPictures, List<Integer> askedAgain, List<Integer> byBalloon,
                       Source source, List<String> pageLabels, int strips, int pieces, int inColumns, Fit fit, boolean capitals, Map<String, Integer> cuts, int insidePhrase, String phrases, int kept, Reads reads, int keepBroken, Twice twice, int leftAsArt, int heldByPage, int ownSize, int brokenWords) {
        /** A page of the job as a sentence names it: "page 4", or "page 3, part 2 of 7". {@code p} from 0. */
        String pageSaid(int p) { return "page " + pageLabels.get(p); }
        /** Pages of the job in a row, by their numbers from 1: "pages 2, 3 and 6" as before for a volume of pages, each named whole where a strip's pieces are among them. */
        String pagesSaid(List<Integer> numbers) {
            if (strips == 0) return (numbers.size() == 1 ? "page " : "pages ") + numbersSaid(numbers);
            List<String> out = new ArrayList<>(); for (int n : numbers) out.add(pageSaid(n - 1));
            return String.join("; ", out);
        }
        /** The strips among the volume's pictures, after their count is said: "a web comic's strip" for a volume that is one, "1 of them a web comic's strip", "3 of them web comic strips", "all web comic strips". */
        String stripsSaid() { return strips == pages ? (strips == 1 ? "a web comic's strip" : "all web comic strips") : strips == 1 ? "1 of them a web comic's strip" : strips + " of them web comic strips"; }
        int fittedKept() { return (int) fitted.stream().filter(Fitted::kept).count(); }
        int notSetCount() { int n = 0; for (int v : notSet.values()) n += v; return n; }

        private static String keptSaid(long kept) { return kept == 1 ? "1 revision kept" : kept + " revisions kept"; }

        /** The result in sentences, as a document's summary is: what was read and translated, what was lettered, what went back for the fit, what the checks found. */
        String summary(Translation.Outcome o, Chrome c) {
            String name = Translate.LANGUAGES.getOrDefault(o.to(), ReadIn.name(o.to()));
            StringBuilder b = new StringBuilder("Translated \"" + o.title() + "\" into " + name + " (" + o.quality() + "): " + pages + (pages == 1 ? " page" : " pages")
                    + (strips == 0 ? "" : " (" + stripsSaid() + ", read and lettered in " + pieces + (pieces == 1 ? " piece" : " pieces") + ")")
                    + ", " + o.units() + (o.units() == 1 ? " speech balloon" : " speech balloons") + ", " + Translation.duration(o.seconds()) + " on " + o.translatorName());
            if (o.read()) b.append(o.twoModels() ? ", " + o.readUnits() + " balloons read by " + o.checkerName() : ", " + o.readUnits() + " balloons read by the same model, the only one set");
            b.append(". ").append(pictures ? "Each page went to the translator as a picture beside the text read from it." : "The pages were translated from the text read from them alone: " + picturesWhy + ".");
            if (twice.made()) b.append(" The translator read the lettering of ").append(twice.asked()).append(twice.asked() == 1 ? " balloon" : " balloons").append(" a second time, from a picture of each: ")
                    .append(twice.inLetters() == 0 ? "the two readings agree in every letter." : twice.inLetters() == 1 ? "1 was read differently in letters, and the note lists it with both readings." : twice.inLetters() + " were read differently in letters, and the note lists each with both readings.");
            if (!unread.isEmpty()) b.append(" ").append(unread.size() == 1 ? "One page could not be read and stands as it was: " : unread.size() + " pages could not be read and stand as they were: ").append(String.join(strips == 0 ? ", " : "; ", unread)).append(".");
            b.append(" Lettered into their balloons: ").append(set).append(" of ").append(o.units()).append(notSetCount() == 0 ? "" : "; the note says why for each of the other " + notSetCount())
                    .append(leftAsArt == 0 ? "" : "; " + leftAsArt + (leftAsArt == 1 ? " is left as art, its lettering being drawn into the art, and translated in the note" : " are left as art, their lettering being drawn into the art, and translated in the note")).append(".");
            if (!fitted.isEmpty()) b.append(" ").append(fitted.size() == 1 ? "One line was too long for its balloon and went back to be said shorter" : fitted.size() + " lines were too long for their balloons and went back to be said shorter").append(": ").append(fittedKept()).append(" kept.");
            if (outside + sound > 0) b.append(" Not lettered in this version, and translated in the note: ").append(outside).append(outside == 1 ? " text outside a balloon, " : " texts outside a balloon, ").append(sound).append(sound == 1 ? " drawn sound word." : " drawn sound words.");
            if (o.keptOriginal() > 0) b.append(" ").append(o.keptOriginalSaid(c));
            if (o.decided() != null && (!o.decided().entries().isEmpty() || !o.decided().doubted().isEmpty())) b.append(" ").append(o.namesSaid(c));
            if (!o.quality().equals(Translation.QUICK)) {
                long serious = o.flags().stream().filter(Translation.Flag::serious).count();
                if (o.backTranslated() > 0) b.append(" ").append(o.backTranslated()).append(" balloons translated back and compared.");
                b.append(" ").append(o.flags().isEmpty() ? "Nothing was flagged." : o.flags().size() + (o.flags().size() == 1 ? " flag" : " flags") + ": " + serious + " major or critical, " + (o.flags().size() - serious) + " minor; " + keptSaid(o.changes().stream().filter(Translation.Change::kept).count()) + "; " + o.marked() + " still standing.");
            }
            return b.toString();
        }
    }

    /** The probe of whether a model sees pictures, as the models' own command makes it; a test stands it in. */
    static boolean sees(ModelEntries.Entry e, String[] why) {
        Boolean known = e.sees();
        if (known == null && !e.hosted()) {   // a local model not asked yet: asked now, two small squares, and the answer kept on the entry
            Vision.Probe p = LibraryProtocol.VISION_PROBE.apply(e);
            known = p.sees();
            try { ModelEntries.sees(e.name(), known); } catch (IOException ignored) { /* asked again next time */ }
            if (!Boolean.TRUE.equals(known)) why[0] = "asked what colour a square was, the translator " + (p.why() == null || p.why().isBlank() ? "did not say" : p.why());
        } else if (known == null) why[0] = "the translator is a hosted model that has not been asked whether it sees pictures (researchzosho models test " + e.name() + " asks it)";
        else if (!known) why[0] = "the translator does not see pictures";
        return Boolean.TRUE.equals(known);
    }

    /**
     * The run of a comic: the comic helper set up on the person's yes; the pages out of what holds them, a web comic's strips cut into
     * pieces, each a page from there on; the language of the lettering, as the plan has it or found now on a sample of the pages; the
     * pages read with that language's reader, each page's reading kept with the job; for a comic that is not Japanese, at the tiers
     * that check, each speech block read a second time by a translator that sees ({@link #readTwice}), before anything is made of the
     * readings; the names found by the program from every page's
     * reading; one call a page to the translator, with the page's picture when it sees, the names the pages before wrote and the page
     * before as translated; the names decided as any document's are; the program's checks and the revision of what they flag, the
     * back-translation sample, and at the tiers that read, the second seat's reading with the page's picture; the pages set by the
     * helper, in columns where the original was and the target is written so, a line too long for its balloon said shorter once and set
     * again; a strip's pieces joined into the picture the strip was; the output written, both entries shelved, the note written. A job
     * a restart interrupts picks up where it was: what the helper said of each picture and each page read are kept by the picture's
     * hash, the model's calls come back in their order.
     */
    static Translation.Outcome running(LibraryStore store, Path in, String to, String format, String quality, Path out, String collection, Translation.Seats seats, Consumer<ObjectNode> progress, BooleanSupplier stop,
                                       Checkpoint cp, boolean notesAtEnd, String jobId, String from, Taken taken, Said said) throws IOException {
        long t0 = System.currentTimeMillis() - cp.elapsedBeforeMs();
        Translation.Plan plan = plan(store, in, to, format, quality, seats.translatorModel(), seats.translatorEntry() == null ? "" : seats.translatorEntry().name(), seats.judgeEntry() == null ? "" : seats.judgeEntry().name(), from, taken, said, false);
        String code = plan.to(), language = Translate.LANGUAGES.getOrDefault(code, ReadIn.name(code)), q = plan.quality(), fmt = plan.as();
        String script = script(code);
        Fit fit = fit(script);
        helperReady(said, progress, q);
        Path work = workFolder(store, cp);
        try (ComicHelper.Reader reader = ComicHelper.reader()) {
            ComicPages.Volume volume = ComicPages.open(in, work);
            if (volume.pages().isEmpty()) throw new IOException("No page picture could be taken out of " + in.getFileName() + ".");
            Cut cut = cut(volume, reader, cp, work, progress, stop, q);
            // the language of the lettering: the plan's (the person's word, or what a look found when the job was filed), else found now
            Source source = plan.comic().source();
            if (!source.known()) {
                source = settled(atStart(cut.opened(), reader, cp), code, in.getFileName().toString());
                if (!source.known()) throw new IOException((source.sampled() == 0 ? "No picture of " + in.getFileName() + " could be opened by the comic helper" : "No lettering was found on the " + source.sampled() + (source.sampled() == 1 ? " page" : " pages") + " of " + in.getFileName() + " that the comic helper looked at")
                        + ", so the language of the comic is not known. If it has lettering, name its language and send it again: " + NAME_IT + ", the form's choice, from in a program's call.");
                // found now, and said now: the person watching learns which language the pages are read in before any is read, and the job's record holds it from here on
                if (progress != null) { ObjectNode found = M.createObjectNode(); found.put("phase", "pages"); found.put("units_done", 0); found.put("units_total", cut.pages().size()); found.put("quality", q); found.put("found", source.code()); progress.accept(found); }
            }
            String sourceCode = source.code();
            if (!reader.languages().isEmpty() && !reader.languages().contains(sourceCode)) {
                List<String> reads = new ArrayList<>(); for (String l : reader.languages()) reads.add(ReadIn.name(l));
                throw new IOException("The comic helper on this machine has no reader for " + ReadIn.name(sourceCode) + (source.how().equals(BY_PERSON) ? "" : ", the language it found the lettering in") + ". It reads: " + Chrome.ENGLISH.and(reads) + ". If the comic is in one of those, name it: " + NAME_IT + ".");
            }
            // the way the pages are read: the person's word for any comic; else by the language, and for a Chinese comic by whether its lettering
            // runs in columns, decided once for the comic from the look at a sample (left to the helper each Chinese page votes by its own
            // balloons, and a page with two of them can vote wrong). The helper is told the way on every page where the program decided it;
            // where the language alone decides (Japanese from the right, an alphabet or Korean from the left) the helper's own way is that way
            Reads reads = reads(said.reads(), source);
            if (!reads.known() && sourceCode.equals("zh")) {   // a Chinese comic the person named, which no look has seen
                Boolean columns = columns(cut.opened(), reader, cp);
                if (columns != null) reads = new Reads(columns ? RTL : LTR, READS_FOUND);
            }
            String order = reads.how().equals(READS_FOUND) && !sourceCode.equals("zh") ? "" : reads.order();
            List<Read> once = read(cut.pages(), reader, cp, progress, stop, q, sourceCode, order);
            // no page read at all is the helper's failure, said with its own reason (a reader's file that is missing, loaded at the first page), never "nothing to translate"
            if (once.stream().noneMatch(r -> r.unread().isEmpty())) throw new IOException("The comic helper could not read any of the " + once.size() + (once.size() == 1 ? " page" : " pages") + " of " + in.getFileName() + " as " + ReadIn.name(sourceCode) + ": " + once.get(0).unread() + ". researchzosho translate comics status says how the helper stands.");
            // the lettering read a second time, by the translator on a picture of each speech block: for a comic that is not Japanese, at the
            // tiers that check, when the translator sees pictures (asked here where that decides it; else before the first page's call, as it was)
            String[] blindWhy = {""};
            ModelEntries.Entry sight = seats.translatorEntry() != null ? seats.translatorEntry() : ModelEntries.defaultEntry();
            Boolean saw = sourceCode.equals("ja") || q.equals(Translation.QUICK) ? null : sees(sight, blindWhy);
            String lettering = sourceCode.equals("ja") ? ONCE_JAPANESE : q.equals(Translation.QUICK) ? ONCE_QUICK : saw ? TWICE : ONCE_BLIND;
            List<Read> pages = lettering.equals(TWICE) ? readTwice(once, reader, seats, cp, work, progress, stop, q, source, order) : once;
            // the volume's lettering: every block of every page, and the speech blocks with words as the units of the translation
            List<List<Block>> byPage = new ArrayList<>();
            List<Block> units = new ArrayList<>();
            for (int p = 0; p < pages.size(); p++) { List<Block> bs = blocks(p, pages.get(p).read()); byPage.add(bs); for (Block b : bs) if (b.speech() && b.words()) units.add(b); }
            if (units.isEmpty()) throw new IOException("No speech balloon with words was found on the " + pages.size() + (pages.size() == 1 ? " page" : " pages") + " of " + in.getFileName() + ": there is nothing to translate. Its lettering was read as " + ReadIn.name(sourceCode)
                    + (source.how().equals(BY_PERSON) ? ", as named" : "") + "; if it is in another language, name it: " + NAME_IT + ".");
            Pair pair = Pair.of(sourceCode, code, fromTheRight(sourceCode, pages, byPage));
            List<String> labels = new ArrayList<>(); for (Read r : pages) labels.add(r.page().label());
            List<Unit> asUnits = new ArrayList<>(); List<String> texts = new ArrayList<>();
            Map<String, Integer> unitOf = new LinkedHashMap<>();   // a block by its page and number, to its unit
            for (int i = 0; i < units.size(); i++) { Block b = units.get(i); asUnits.add(Unit.of(DocUnits.PARA, b.text())); texts.add(b.text()); unitOf.put(b.page() + ":" + b.number(), i); }
            // the glossary: the library's entries for the language that the comic uses, as for any document
            Map<String, String> glossary = new LinkedHashMap<>();
            String allText = String.join("\n", texts);
            for (var e : Translation.libraryGlossary(store, code, cp).entrySet()) if (allText.contains(e.getKey())) glossary.put(e.getKey(), e.getValue());
            Set<String> binding = glossary.keySet();
            Names names = new Names(byPage, texts);
            // whether the translator sees: asked once when the entry does not say, and then each page goes to it as a picture
            boolean pictures = saw != null ? saw : sees(sight, blindWhy);
            // what the second reading came to: the blocks asked, the readings taken, those that differ from the helper's, and in letters
            int askedTwice = 0, takenTwice = 0, differ = 0, differInLetters = 0;
            Map<String, Integer> notTaken = new LinkedHashMap<>();
            List<String> notCut = new ArrayList<>();
            for (int p = 0; p < pages.size(); p++) {
                JsonNode s = pages.get(p).read().path(SECOND);
                if (s.hasNonNull("failed")) notCut.add("page " + labels.get(p) + " (" + s.path("failed").asText() + ")");
                for (Block b : byPage.get(p)) {
                    Second x = b.second();
                    if (x == null) continue;
                    askedTwice++;
                    if (x.taken()) takenTwice++; else notTaken.merge(x.why(), 1, Integer::sum);
                    if (x.differs()) differ++;
                    if (x.inLetters()) differInLetters++;
                }
            }
            Twice twice = new Twice(lettering, askedTwice, takenTwice, differ, differInLetters, notTaken, notCut);
            String pass = twice.made() ? "twice" : "";   // told with the pages' progress: a run that read the lettering twice has spent a part of its time before the first page's call
            Path pics = Files.createDirectories(work.resolve("shown"));
            // the first pass: a page a call
            Map<Integer, String> words = new LinkedHashMap<>(), notKept = new TreeMap<>();
            Map<String, String> glosses = new LinkedHashMap<>();   // a sound word's gloss and a text outside a balloon's translation, by page and number
            List<String> before = new ArrayList<>();
            List<Integer> askedAgain = new ArrayList<>(), byBalloon = new ArrayList<>();
            Translation.tell(progress, "page-translating", 0, pages.size(), q, pass);
            for (int p = 0; p < pages.size(); p++) {
                if (stop.getAsBoolean()) throw new IOException("Stopped before the translation was written.");
                List<Block> page = byPage.get(p);
                if (page.stream().anyMatch(Block::words)) {
                    String picture = null;
                    if (pictures) {
                        Path pic = pics.resolve(String.format(Locale.ROOT, "p%04d.jpg", p + 1));
                        List<Map.Entry<String, double[]>> boxes = new ArrayList<>(); for (Block b : page) if (b.words()) boxes.add(Map.entry(String.valueOf(b.number()), b.box()));
                        try { reader.picture(pages.get(p).page().file(), boxes, pic); picture = Vision.dataUrl(Files.readAllBytes(pic), "image/jpeg"); }
                        catch (ComicHelper.Failed f) { picture = null; }   // a page the helper cannot draw is translated from its text
                    }
                    List<String> ask = names.toAsk(p);
                    List<String[]> asked = new ArrayList<>();
                    for (String s : ask) { String[] r = names.readingFor(s); asked.add(new String[]{s, r == null ? "" : (r[0].equals(s) ? "read " : r[0] + " is read ") + r[1] + (pair.hepburn() ? "; as a name, " + names.spelling(r[0]) : "")}); }
                    Map<String, String> already = names.writtenOn(p);
                    List<String[]> elsewhere = new ArrayList<>();
                    for (var e : names.reading.entrySet()) {   // a word of this page whose reading is printed on another page, and which is neither written already nor asked about here
                        boolean here = page.stream().anyMatch(b -> !b.kind().equals(SOUND) && b.text().contains(e.getKey())), printedHere = page.stream().anyMatch(b -> b.readings().stream().anyMatch(r -> r.base().equals(e.getKey())));
                        if (here && !printedHere && e.getKey().codePointCount(0, e.getKey().length()) >= 2 && !already.containsKey(e.getKey()) && !ask.contains(e.getKey()) && elsewhere.size() < 20) elsewhere.add(new String[]{e.getKey(), e.getValue(), pair.hepburn() ? "as a name, " + names.spelling(e.getKey()) : ""});
                    }
                    Paged got = ask(seats, pair, p + 1, page, picture, elsewhere, already, asked, before, glossary);
                    if (!got.whole()) {   // a balloon's number left out: the lines that came may stand under their neighbours' numbers, so none of them is taken
                        askedAgain.add(p + 1);
                        Paged again = ask(seats, pair, p + 1, page, picture, elsewhere, already, asked, before, glossary, got.leftOut(), 0);
                        if (again.whole()) got = again;
                        else {   // twice: each balloon in a call of its own, the page's other lines beside it for the thread
                            byBalloon.add(p + 1);
                            Map<Integer, String> lines = new LinkedHashMap<>();
                            for (Block b : page) if (b.speech() && b.words()) {
                                if (stop.getAsBoolean()) throw new IOException("Stopped before the translation was written.");
                                String one = ask(seats, pair, p + 1, page, picture, elsewhere, already, List.of(), before, glossary, List.of(), b.number()).lines().get(b.number());
                                if (one != null) lines.put(b.number(), one);
                            }
                            Paged kept = again.sounds().size() + again.outside().size() >= got.sounds().size() + got.outside().size() ? again : got;
                            got = new Paged(lines, kept.sounds(), kept.outside(), Map.of(), Map.of(), List.of());
                        }
                    }
                    for (var e : got.lines().entrySet()) { Integer i = unitOf.get(p + ":" + e.getKey()); if (i != null) words.put(i, e.getValue()); }
                    for (var e : got.sounds().entrySet()) glosses.put(p + ":" + e.getKey(), e.getValue());
                    for (var e : got.outside().entrySet()) glosses.put(p + ":" + e.getKey(), e.getValue());
                    for (var e : got.notKept().entrySet()) { Integer i = unitOf.get(p + ":" + e.getKey()); if (i != null) notKept.put(i, e.getValue()); }
                    for (var e : got.names().entrySet()) names.accept(ask.get(e.getKey() - 1), e.getValue(), page, got.lines(), pair.hepburn());
                    List<String> thisPage = new ArrayList<>();
                    for (Block b : page) if (b.speech() && got.lines().get(b.number()) != null) thisPage.add(b.number() + ". " + got.lines().get(b.number()));
                    if (!thisPage.isEmpty()) before = thisPage;   // a page with no speech leaves the thread where it was
                }
                Translation.tell(progress, "page-translating", p + 1, pages.size(), q, pass);
            }
            // the names decided as a document's are: what most of the balloons holding a string write, the pages' own answers verified with the rest
            Map<Integer, String> sources = new LinkedHashMap<>(); for (int i = 0; i < units.size(); i++) sources.put(i, units.get(i).text());
            String volumeSha = ComicPages.sha(in);
            DocNames.Decided decided;
            try (var namesLane = Checkpoint.lane("names")) { decided = DocNames.forRun(store, cp, seats.translator(), names.candidates, binding, volumeSha, code, language, sources, words, names.answers()); }
            if (Translation.reads(q)) try (var readerLane = Checkpoint.lane("reader")) { decided = DocNames.reader(store, cp, seats.checker(), seats.checkerName(), decided, volumeSha, code, language, sources, words); }
            Map<String, String> fixed = new LinkedHashMap<>(decided.renderings());
            fixed.keySet().removeAll(binding);
            // the checks: the program's on every balloon, the revision of what they flag, the back-translation sample, the second seat's reading
            List<Integer> todo = new ArrayList<>(); for (int i = 0; i < units.size(); i++) todo.add(i);
            Checks checks = new Checks();
            if (!q.equals(Translation.QUICK)) checks = check(asUnits, units, pages, todo, words, glossary, binding, decided, names, pair, seats, q, progress, stop, reader, pics, pictures && readerSees(seats));
            // the lettering: every page set by the helper; then a line too long for its balloon said shorter, once, and its page set again
            Path outDir = work.resolve("out");
            Checkpoint.deleteTree(outDir);
            Files.createDirectories(outDir);
            List<Path> outFiles = new ArrayList<>(); List<JsonNode> reports = new ArrayList<>();
            List<String> keep = keep(decided, names, glossary);   // the names and terms as the translation writes them: the helper ends no line inside one
            for (int p = 0; p < pages.size(); p++) {
                if (stop.getAsBoolean()) throw new IOException("Stopped while the pages were being lettered; nothing was written.");
                Path target = outDir.resolve(String.format(Locale.ROOT, "p%04d.%s", p + 1, pages.get(p).page().format()));
                outFiles.add(target);
                reports.add(set(reader, pages.get(p), byPage.get(p), unitOf, words, code, script, keep, target));
                Translation.tell(progress, "lettering", p + 1, pages.size(), q);
            }
            List<Fitted> fitted = new ArrayList<>();
            if (!q.equals(Translation.QUICK)) {
                List<int[]> small = new ArrayList<>();   // {page, unit}
                for (int p = 0; p < pages.size(); p++) { int floor = floor(fit, pages.get(p).read(), reports.get(p)); for (Block b : byPage.get(p)) { Integer i = unitOf.get(p + ":" + b.number()); if (i != null && words.get(i) != null && tooSmall(reports.get(p).path("blocks").path(b.id()), floor)) small.add(new int[]{p, i}); } }
                int done = 0;
                Set<Integer> again = new TreeSet<>();
                Map<Integer, Fitted> pending = new LinkedHashMap<>();
                for (int[] pu : small) {
                    if (stop.getAsBoolean()) throw new IOException("Stopped while the lines were being fitted; nothing was written.");
                    int p = pu[0], i = pu[1], floor = floor(fit, pages.get(p).read(), reports.get(p));
                    Block b = units.get(i);
                    JsonNode rec = reports.get(p).path("blocks").path(b.id());
                    String line = words.get(i);
                    int budget = budget(fit, line, rec, floor), px = rec.path("set").asBoolean(false) ? rec.path("px").asInt() : 0;
                    String shorter = shorter(seats, b.text(), line, budget, language, Translation.promptGlossary(glossary, decided, i));
                    String why = shorter == null ? "no shorter line came back" : shorter.codePointCount(0, shorter.length()) >= line.codePointCount(0, line.length()) ? "what came back was no shorter" : seriousAfter(i, b.text(), shorter, line, glossary, binding, decided, code);
                    if (why.isEmpty()) { words.put(i, shorter); again.add(p); pending.put(i, new Fitted(i, line, px, budget, shorter, 0, true, "")); }
                    else fitted.add(new Fitted(i, line, px, budget, shorter == null ? "" : shorter, px, false, why));
                    Translation.tell(progress, "fitting", ++done, small.size(), q);
                }
                for (int p : again) reports.set(p, set(reader, pages.get(p), byPage.get(p), unitOf, words, code, script, keep, outFiles.get(p)));
                // a shorter line is kept for the size it gains: one set no larger than the line it replaces lost words for nothing, so the line
                // stands as it was translated and its page is set once more with it
                Set<Integer> restored = new TreeSet<>();
                for (var e : pending.entrySet()) {
                    Block b = units.get(e.getKey()); JsonNode rec = reports.get(b.page()).path("blocks").path(b.id()); Fitted f = e.getValue();
                    int afterPx = rec.path("set").asBoolean(false) ? rec.path("px").asInt() : 0;
                    if (afterPx > f.beforePx()) { fitted.add(new Fitted(f.unit(), f.before(), f.beforePx(), f.budget(), f.after(), afterPx, true, "")); continue; }
                    words.put(e.getKey(), f.before()); restored.add(b.page());
                    fitted.add(new Fitted(f.unit(), f.before(), f.beforePx(), f.budget(), f.after(), afterPx, false, afterPx == 0 ? "the shorter line did not go into its balloon either, so the line stands as it was translated" : "the shorter line was set no larger (" + afterPx + " px), so the line stands as it was translated"));
                }
                for (int p : restored) reports.set(p, set(reader, pages.get(p), byPage.get(p), unitOf, words, code, script, keep, outFiles.get(p)));
                fitted.sort((x, y) -> Integer.compare(x.unit(), y.unit()));
            }
            // what the checks still doubt, numbered in the volume's order
            Map<Integer, List<Translation.Flag>> standing = new TreeMap<>();
            for (Translation.Flag f : checks.flags) if (f.stands()) standing.computeIfAbsent(f.unit(), x -> new ArrayList<>()).add(f);
            List<Translation.Doubt> doubts = new ArrayList<>();
            for (var e : standing.entrySet()) { String tr = words.get(e.getKey()); doubts.add(new Translation.Doubt(doubts.size() + 1, e.getKey(), "[" + (doubts.size() + 1) + "]", Translation.begins(tr != null ? tr : units.get(e.getKey()).text()), Translation.whyOf(e.getValue()), tr == null)); }
            int keptOriginal = 0; for (int i : todo) if (!words.containsKey(i)) keptOriginal++;
            Translation.tell(progress, "writing", pages.size(), pages.size(), q);
            // the rows of the note, the counts of what was set, and the output
            Map<String, Integer> notSet = new LinkedHashMap<>();
            List<List<Row>> rows = new ArrayList<>(); List<String> pageNames = new ArrayList<>(), unread = new ArrayList<>();
            int setCount = 0, smallest = Integer.MAX_VALUE, largest = 0, speech = 0, outside = 0, sound = 0, readings = 0, otherFont = 0, belowFloor = 0, inColumns = 0, insidePhrase = 0, keepBroken = 0, leftAsArt = 0, heldByPage = 0, ownSize = 0, brokenWords = 0;
            String phrases = "";
            boolean hyphenated = true;
            for (int p = 0; p < pages.size(); p++) {
                pageNames.add(pages.get(p).page().name());
                if (!pages.get(p).unread().isEmpty()) unread.add("page " + labels.get(p) + " (" + pages.get(p).unread() + ")");
                List<Row> pageRows = new ArrayList<>();
                int floor = floor(fit, pages.get(p).read(), reports.get(p));
                for (Block b : byPage.get(p)) {
                    readings += b.readings().size();
                    Integer i = unitOf.get(p + ":" + b.number());
                    JsonNode rec = reports.get(p).path("blocks").path(b.id());
                    String how;
                    boolean columns = false;
                    if (b.kind().equals(SOUND)) { sound++; how = "a drawn sound word: left as art"; }
                    else if (b.kind().equals(OUTSIDE)) { outside++; how = "outside a balloon: not lettered"; }
                    else {
                        speech++;
                        if (i == null) how = "no words: left as it is";
                        else if (words.get(i) == null) how = "no translation: the " + pair.fromName() + " stands";
                        else if (rec.path("set").asBoolean(false)) {
                            int px = rec.path("px").asInt(), ofPage = rec.path("page_px").asInt(px); setCount++; smallest = Math.min(smallest, ofPage); largest = Math.max(largest, ofPage);   // the range the note gives is of the pages' sizes: a balloon set smaller than its page is counted beside it
                            if (rec.has("other_font")) otherFont++;
                            if (px < floor) belowFloor++;
                            if (rec.path("own_size").asBoolean(false)) ownSize++;
                            brokenWords += rec.path("hyphenations").asInt(0);   // words broken at a line's end: hyphenated in an alphabet, between two syllables in Korean   // set at a size of its own, under its page's: its text takes no more, or the helper spared a bad line end
                            boolean asItsPage = heldByPage(rec, floor);   // small as its whole page is, its own balloon taking more
                            if (asItsPage) heldByPage++;
                            if (rec.has("hyphenation")) hyphenated = false;
                            columns = rec.path("vertical").asBoolean(false);   // as the helper says it set the block: it says so only of one set in columns
                            if (columns) inColumns++;
                            int inside = rec.path("inside_phrase").asInt(0);   // Chinese, Japanese, Korean: the lines of the block that end inside a phrase
                            insidePhrase += inside;
                            if (phrases.isEmpty() && rec.hasNonNull("phrases")) phrases = rec.path("phrases").asText("");
                            List<String> broken = new ArrayList<>(); for (JsonNode k : rec.path("keep_broken")) broken.add("\"" + k.asText() + "\"");   // the names and terms a line of this block ends inside, the balloon leaving no other way
                            if (!broken.isEmpty()) keepBroken++;
                            how = px + " px" + (columns ? ", in columns" : "") + (rec.path("hyphenations").asInt(0) > 0 ? ", " + rec.path("hyphenations").asInt() + (rec.path("hyphenations").asInt() == 1 ? " word broken" : " words broken") : "")
                                    + (inside > 0 ? ", " + inside + (inside == 1 ? " line ends" : " lines end") + " inside a phrase" : "") + (broken.isEmpty() ? "" : ", a line ends inside " + String.join(" and ", broken)) + (asItsPage ? ", the size of its whole page (a line goes back under " + floor + " px when its own balloon is what holds it down)" : px < floor ? ", small beside the rest of its page (under " + floor + " px)" : "");
                        } else {
                            String why = rec.path("why_not").asText("the helper gave no reason");
                            // lettering that joins the art (a sound drawn through a balloon the helper took for speech) is neither painted over nor set: art that stays, as a sound word does, and no line that did not fit
                            if (why.startsWith(DRAWN_INTO_ART)) { leftAsArt++; how = "lettering drawn into the art: left as art"; }
                            else { notSet.merge(why, 1, Integer::sum); how = "not set: " + why; }
                        }
                    }
                    pageRows.add(new Row(b, i != null ? words.get(i) : glosses.get(p + ":" + b.number()), how, columns));
                }
                rows.add(pageRows);
            }
            // the pictures of the output, one for each the volume had: a page as it was set, a strip joined again from its set pieces
            Map<Path, Integer> pageOf = new LinkedHashMap<>(); for (int p = 0; p < pages.size(); p++) pageOf.put(pages.get(p).page().file(), p);
            List<Path> finals = new ArrayList<>();
            List<String> finalNames = new ArrayList<>();   // each picture's name in the volume: a PDF's page by its number, for the size the page had
            List<String> leftOut = new ArrayList<>(volume.leftOut());
            for (ComicPages.Page picture : volume.pages()) {
                if (stop.getAsBoolean()) throw new IOException("Stopped while the file was being written; nothing was written.");
                Strip strip = cut.strips().get(picture.number());
                finalNames.add(picture.name());
                if (strip == null) { finals.add(outFiles.get(pageOf.get(picture.file()))); continue; }
                Path joined = outDir.resolve(String.format(Locale.ROOT, "s%04d.%s", picture.number(), picture.format()));
                List<ComicHelper.Piece> set = new ArrayList<>(); for (ComicPages.Page piece : strip.pieces()) set.add(new ComicHelper.Piece(outFiles.get(pageOf.get(piece.file())), piece.top()));
                try { reader.join(set, strip.width(), strip.height(), joined); }
                catch (ComicHelper.Failed f) {   // the strip stands as it was, and the note says so: a half-joined picture is no page
                    Files.copy(picture.file(), joined, StandardCopyOption.REPLACE_EXISTING);
                    leftOut.add("\"" + picture.name() + "\": its " + strip.pieces().size() + " pieces were translated and set, and could not be joined into one picture again (" + f.getMessage() + "), so the strip stands as it was");
                }
                finals.add(joined);
            }
            Path output = out != null ? out : Translation.keptPath(cp, "output", () -> Translation.outputPath(in, null, code, fmt));
            Files.createDirectories(output.toAbsolutePath().getParent());
            if (fmt.equals(ComicPages.PDF)) {   // a PDF that came in goes out with its pages the size they were
                Map<String, float[]> had = ComicPages.PDF.equals(volume.container()) ? ComicPages.pagePoints(in) : Map.of();
                List<float[]> points = new ArrayList<>();
                for (String name : finalNames) points.add(had.get(name));
                ComicPages.writePdf(finals, output, points);
            }
            else ComicPages.writeCbz(finals, cbzNames(volume.pages()), output);
            long seconds = Math.max(1, (System.currentTimeMillis() - t0) / 1000);
            // onto the shelves: the volume's script, page by page and block by block, as the translation's entry with the archive as its file; the original's the same way when it was not there
            String title = DocUnits.stem(in.getFileName().toString());
            Path original = RawCapture.find(store, "file://" + in.toAbsolutePath().normalize());
            if (original == null) original = RawCapture.capture(store, "file://" + in.toAbsolutePath().normalize(), ComicNote.script(title, rows, labels, false), title, collection == null || collection.isBlank() ? "researchzosho-add" : "corpus:" + collection, collection == null ? "" : collection, "", Map.of("language", sourceCode, "comic", volume.container()));
            String coll = collection != null && !collection.isBlank() ? collection : original == null ? "" : RawCapture.collectionOf(original);
            Map<String, String> more = new LinkedHashMap<>();
            if (original != null) more.put("translation_of", original.getFileName().toString());
            more.put("language", code); more.put("translated_from", sourceCode); more.put("quality", q); more.put("comic", fmt);
            Path shelved = RawCapture.capture(store, "file://" + output.toAbsolutePath().normalize(), ComicNote.script(title, rows, labels, true), title, "researchzosho-translate", coll, "", more);
            int marked = 0; for (Translation.Flag f : checks.flags) if (f.stands()) marked++;
            Map<String, String> spellings = new LinkedHashMap<>();
            for (var e : names.reading.entrySet()) spellings.put(e.getKey(), e.getValue() + (pair.hepburn() ? " → " + names.spelling(e.getKey()) + (names.spelling(e.getKey()).replace(" ", "").equalsIgnoreCase(Hepburn.letterByLetter(e.getValue())) ? "" : " (letter by letter " + Hepburn.letterByLetter(e.getValue()) + ")") : ""));
            Done comic = new Done(volume.pages().size(), unread, speech, outside, sound, readings, pictures, blindWhy[0], setCount, notSet, setCount == 0 ? 0 : smallest, largest, List.copyOf(fitted), List.copyOf(names.refused), spellings, reader.loaded(), leftOut,
                    hyphenated && ComicHelper.hyphenated().contains(code), rows, pageNames, volume.container(), taken.why(), otherFont, belowFloor, List.copyOf(units),
                    (int) names.written.values().stream().filter(a -> !a.kind().equals(DocNames.NONE)).count(), Translation.reads(q) && pictures && readerSees(seats), List.copyOf(askedAgain), List.copyOf(byBalloon),
                    source, List.copyOf(labels), cut.strips().size(), cut.pieces(), inColumns, fit, pair.capitals(), cut.cuts(), insidePhrase, phrases, keep.size(), new Reads(pair.rtl() ? RTL : LTR, reads.known() ? reads.how() : READS_FOUND), keepBroken, twice, leftAsArt, heldByPage, ownSize, brokenWords);
            Path noteFile = Translation.noteFor(output);
            Translation.Outcome o = new Translation.Outcome(output, noteFile, title, title, sourceCode, code, fmt, q, units.size(), words.size(), keptOriginal, checks.flags, marked, seconds,
                    seats.translatorModel(), seats.translatorName(), seats.checkerModel(), seats.checkerName(), seats.sameModel(), seats.why(), checks.backTranslated,
                    Map.of(), Map.of(), new Thresholds("", 0, 0, null, null), "", checks.changes, 0, checks.readUnits, checks.readSaid, shelved, original, coll, glossary, fixed, 0,
                    List.copyOf(doubts), false, null, null, new TranslationLengths.Band("", "", null, null, TranslationLengths.Band.NONE, 0), jobId, "", Translation.scriptLeft(texts, words.values()), decided, List.of(),
                    0, 0, checks.readerAside.clone(), seats.direction(), notKept, comic);
            AtomicWrite.text(noteFile, ComicNote.note(o, in, plan));
            Translation.recordSpeed(store, seats.translatorModel(), speedKey(q, twice.made()), pages.size(), seconds);   // a comic read twice is another speed: a call more for each speech block
            return o;
        } finally { if (!cp.keeps()) dropWork(work); }
    }

    /** Whether a block is asked to be set in columns: it was lettered in columns, and the target is one written so (Chinese, Japanese). */
    static boolean inColumns(Block b, String code) { return b.vertical() && IN_COLUMNS.contains(code); }

    /** The names a CBZ's entries carry: each picture's own name in the volume it came from, a strip's as the one picture it was, with the ending of the format it now has (a PDF's pages are p0001.png…). */
    static List<String> cbzNames(List<ComicPages.Page> pictures) {
        List<String> out = new ArrayList<>();
        for (int p = 0; p < pictures.size(); p++) { String n = pictures.get(p).name(); out.add(ComicPages.PICTURE.matcher(n).matches() ? n : String.format(Locale.ROOT, "p%04d.%s", p + 1, pictures.get(p).format())); }
        return out;
    }

    /** Whether the second seat sees pictures, as its entry says: the translator's own answer when it is the same model. A seat no entry describes is taken not to. */
    static boolean readerSees(Translation.Seats seats) {
        if (seats.sameModel()) return true;
        return seats.judgeEntry() != null && Boolean.TRUE.equals(seats.judgeEntry().sees());
    }

    /**
     * The strings no line or column of the lettering may end inside, and no hyphen fall in: the names and terms of the volume in the
     * target language, as the translated text writes them. First those decided for the volume (what most balloons that hold the
     * string write), then the forms the pages' own calls named and the names mechanism took ({@link Names#accept}) for the strings
     * it did not come to decide, then the glossary's renderings of the terms the comic uses. The helper keeps each whole where the
     * balloon lets it, and says in a block's report where it could not ({@code keep_broken}); a string a page's text does not hold
     * changes nothing there.
     */
    static List<String> keep(DocNames.Decided decided, Names written, Map<String, String> glossary) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (DocNames.Entry e : decided.entries()) out.add(e.rendering().strip());
        for (DocNames.Answer a : written.written.values()) if (!a.kind().equals(DocNames.NONE)) out.add(a.words().strip());
        for (String rendering : glossary.values()) out.add(rendering.strip());
        out.removeIf(s -> s.codePoints().filter(Character::isLetter).count() < 2);   // a string of one letter, or of none, is nothing to keep whole
        return List.copyOf(out);
    }

    /**
     * One page set by the helper into {@code target}: the translations of its speech blocks by the helper's ids, in the target's language
     * and writing system, a block asked for in columns only where it was lettered in columns and the target is Chinese or Japanese
     * ({@link #inColumns}), and with them the names no line may end inside ({@link #keep}). A page with nothing to set, or one the
     * helper could not read, is copied as it is; a page the helper cannot set is copied too, and every block of it says why.
     */
    static JsonNode set(ComicHelper.Reader reader, Read page, List<Block> blocks, Map<String, Integer> unitOf, Map<Integer, String> words, String code, String script, List<String> keep, Path target) throws IOException {
        ObjectNode texts = M.createObjectNode();
        for (Block b : blocks) {
            Integer i = unitOf.get(b.page() + ":" + b.number());
            if (i == null || words.get(i) == null) continue;
            ObjectNode t = texts.putObject(b.id()).put("text", words.get(i)).put("lang", code);
            if (inColumns(b, code)) t.put("vertical", true);
        }
        if (texts.isEmpty() || !page.unread().isEmpty()) { Files.copy(page.page().file(), target, StandardCopyOption.REPLACE_EXISTING); return M.createObjectNode(); }
        try { return reader.set(page.page().file(), page.asRead(), texts, script, keep, target); }
        catch (ComicHelper.Failed f) {
            Files.copy(page.page().file(), target, StandardCopyOption.REPLACE_EXISTING);
            ObjectNode report = M.createObjectNode(); ObjectNode bs = report.putObject("blocks");
            texts.fieldNames().forEachRemaining(id -> bs.putObject(id).put("set", false).put("why_not", "the helper could not set this page (" + f.getMessage() + ")"));
            return report;
        }
    }

    /**
     * The words a language says a small number with, where a balloon's line says "two years" for ２年 or "four times" for ４倍: a line of
     * speech writes a small number as a word, and the number is there. Only the numbers to twelve, the round tens of English, and the
     * words for twice and three times; a word that is also an article (a, ein, un) is no number here.
     */
    static final Map<String, Map<String, Integer>> NUMBER_WORDS = Map.of(
            "en", Map.ofEntries(Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("eleven", 11), Map.entry("twelve", 12),
                    Map.entry("twice", 2), Map.entry("double", 2), Map.entry("both", 2), Map.entry("couple", 2), Map.entry("triple", 3), Map.entry("thrice", 3), Map.entry("quadruple", 4), Map.entry("dozen", 12),
                    Map.entry("twenty", 20), Map.entry("thirty", 30), Map.entry("forty", 40), Map.entry("fifty", 50), Map.entry("sixty", 60), Map.entry("seventy", 70), Map.entry("eighty", 80), Map.entry("ninety", 90), Map.entry("hundred", 100), Map.entry("thousand", 1000)),
            "de", Map.ofEntries(Map.entry("eins", 1), Map.entry("zwei", 2), Map.entry("drei", 3), Map.entry("vier", 4), Map.entry("fünf", 5), Map.entry("sechs", 6), Map.entry("sieben", 7), Map.entry("acht", 8), Map.entry("neun", 9), Map.entry("zehn", 10), Map.entry("elf", 11), Map.entry("zwölf", 12), Map.entry("beide", 2), Map.entry("doppelt", 2), Map.entry("doppelte", 2)),
            "fr", Map.ofEntries(Map.entry("deux", 2), Map.entry("trois", 3), Map.entry("quatre", 4), Map.entry("cinq", 5), Map.entry("six", 6), Map.entry("sept", 7), Map.entry("huit", 8), Map.entry("neuf", 9), Map.entry("dix", 10), Map.entry("onze", 11), Map.entry("douze", 12), Map.entry("double", 2)),
            "es", Map.ofEntries(Map.entry("dos", 2), Map.entry("tres", 3), Map.entry("cuatro", 4), Map.entry("cinco", 5), Map.entry("seis", 6), Map.entry("siete", 7), Map.entry("ocho", 8), Map.entry("nueve", 9), Map.entry("diez", 10), Map.entry("once", 11), Map.entry("doce", 12), Map.entry("doble", 2)),
            "it", Map.ofEntries(Map.entry("due", 2), Map.entry("tre", 3), Map.entry("quattro", 4), Map.entry("cinque", 5), Map.entry("sei", 6), Map.entry("sette", 7), Map.entry("otto", 8), Map.entry("nove", 9), Map.entry("dieci", 10), Map.entry("undici", 11), Map.entry("dodici", 12), Map.entry("doppio", 2)),
            "pt", Map.ofEntries(Map.entry("dois", 2), Map.entry("duas", 2), Map.entry("três", 3), Map.entry("quatro", 4), Map.entry("cinco", 5), Map.entry("seis", 6), Map.entry("sete", 7), Map.entry("oito", 8), Map.entry("nove", 9), Map.entry("dez", 10), Map.entry("onze", 11), Map.entry("doze", 12), Map.entry("dobro", 2)),
            "ru", Map.ofEntries(Map.entry("два", 2), Map.entry("две", 2), Map.entry("двое", 2), Map.entry("три", 3), Map.entry("трое", 3), Map.entry("четыре", 4), Map.entry("пять", 5), Map.entry("шесть", 6), Map.entry("семь", 7), Map.entry("восемь", 8), Map.entry("девять", 9), Map.entry("десять", 10), Map.entry("одиннадцать", 11), Map.entry("двенадцать", 12), Map.entry("вдвое", 2), Map.entry("вдвойне", 2)));

    private static final Pattern WORD = Pattern.compile("[\\p{L}]+");

    /**
     * The numerals Chinese and Japanese write a small or a round number with, where a line says 两年 or 二年 for 2 years: the number is
     * there. A line of these languages has no spaces to tell a word by, so the numeral is looked for anywhere in it. Not 一, which
     * stands in too many words that are no number (一起, 一緒), as the articles of the alphabets' tables do.
     */
    static final Map<String, Integer> HAN_NUMERALS = Map.ofEntries(Map.entry("二", 2), Map.entry("两", 2), Map.entry("兩", 2), Map.entry("三", 3), Map.entry("四", 4), Map.entry("五", 5), Map.entry("六", 6), Map.entry("七", 7), Map.entry("八", 8), Map.entry("九", 9),
            Map.entry("十", 10), Map.entry("百", 100), Map.entry("千", 1000), Map.entry("万", 10000), Map.entry("萬", 10000));

    private static final Pattern HAN_RUN = Pattern.compile("[〇零一二两兩三四五六七八九十百千万萬億亿]{2,}");

    /**
     * The number a run of Chinese or Japanese numerals writes: 八十 is 80, 一百零五 105, 三万八千 38,000, 二〇二五 2025; 0 where the run
     * writes none. Read by place: a numeral before 十, 百 or 千 is that many of them, 万 and 億 close a group of four places.
     */
    static long hanNumber(String s) {
        boolean byPlace = s.chars().anyMatch(c -> "十百千万萬億亿".indexOf(c) >= 0);   // 二〇二五 writes its digits in a row, 二千二十五 by place
        long total = 0, group = 0, digit = 0;
        for (char c : s.toCharArray()) {
            int d = "〇一二三四五六七八九".indexOf(c == '零' ? '〇' : c == '两' || c == '兩' ? '二' : c);
            if (d >= 0) { digit = byPlace ? d : digit * 10 + d; continue; }
            long place = c == '十' ? 10 : c == '百' ? 100 : c == '千' ? 1000 : 0;
            if (place > 0) { group += (digit == 0 ? 1 : digit) * place; digit = 0; continue; }
            long big = c == '万' || c == '萬' ? 10_000L : 100_000_000L;
            total += (group + digit == 0 ? 1 : group + digit) * big; group = 0; digit = 0;
        }
        return total + group + digit;
    }

    /** The numbers a line lacks in digits, without each it says in a word of its language (a numeral, for Chinese and Japanese; Korean has no table, and excuses nothing). */
    static List<String> lackedAfterWords(List<String> missing, String translation, String code) {
        Map<String, Integer> words = NUMBER_WORDS.get(code);
        List<String> out = new ArrayList<>(missing);
        if (out.isEmpty()) return out;
        if (code.equals("zh") || code.equals("ja")) {
            for (var e : HAN_NUMERALS.entrySet()) if (translation.contains(e.getKey())) out.remove(String.valueOf(e.getValue()));
            Matcher run = HAN_RUN.matcher(translation);   // a number of several numerals: 八十 is 80, 三万八千 is 38,000
            while (run.find()) { long v = hanNumber(run.group()); if (v > 0) out.remove(String.valueOf(v)); }
            return out;
        }
        if (words == null) return out;
        Matcher m = WORD.matcher(translation.toLowerCase(Locale.ROOT));
        while (m.find()) { Integer v = words.get(m.group()); if (v != null) out.remove(String.valueOf(v)); }
        return out;
    }

    /**
     * The earlier translation as the reviser is shown it: none where it was written in another language than the one asked for. With
     * its own words in that language before it the model wrote them again (read live, 2026-11-14: two of three such balloons came
     * back in Chinese a second time); with the source alone it is asked for the language once more.
     */
    static String shownEarlier(List<Translation.Flag> found, String earlier) { return found.stream().anyMatch(f -> f.kind().equals("language")) ? null : earlier; }

    /**
     * Whether a line came back as its source, letter for letter (capitals, marks and spaces aside): it was copied, not translated.
     * Read live (2026-11-14): "Ma-gni-fi-que !" set into an English page as "MA-GNI-FI-QUE!". Held to a line of two words or word
     * parts and eight letters or more once the names decided for the volume are taken out of it: a name alone is the same in both
     * languages, and so is many a single word two languages share ("Excellent !").
     */
    static boolean sameAsSource(String source, String translation, DocNames.Decided decided) {
        if (source == null || translation == null || !letters(source).equals(letters(translation))) return false;
        String rest = source;
        if (decided != null) for (DocNames.Entry e : decided.entries()) if (e.name() && !e.source().isBlank()) rest = rest.replace(e.source(), " ");
        long parts = Pattern.compile("[\\p{L}\\p{N}]+").matcher(rest).results().count();
        return parts >= 2 && letters(rest).codePointCount(0, letters(rest).length()) >= 8;
    }

    /**
     * Why a line is plainly not written in the language asked for, by its writing alone; "" when its writing is the language's, or
     * says nothing. Japanese holds kana in any line of six characters or more, and no hangul; Chinese holds no kana and no hangul;
     * Korean holds hangul, and no kana; a language of an alphabet is not written in Chinese characters, kana or hangul (a name's
     * original beside it aside), nor a whole line of eight letters or more in another alphabet. Read live (2026-11-14): a page of a
     * Korean comic asked into Japanese came back in Chinese, balloon after balloon, and every check passed it.
     */
    static String otherWriting(String line, String to) {
        if (line == null || line.isBlank() || to == null) return "";
        int han = 0, kana = 0, hangul = 0, latin = 0, cyrillic = 0, greek = 0;
        for (int i = 0; i < line.length(); ) {
            int c = line.codePointAt(i); i += Character.charCount(c);
            if (!Character.isLetter(c)) continue;
            UnicodeScript s = UnicodeScript.of(c);
            if (s == UnicodeScript.HAN) han++; else if (s == UnicodeScript.HIRAGANA || s == UnicodeScript.KATAKANA) kana++; else if (s == UnicodeScript.HANGUL) hangul++;
            else if (s == UnicodeScript.LATIN) latin++; else if (s == UnicodeScript.CYRILLIC) cyrillic++; else if (s == UnicodeScript.GREEK) greek++;
        }
        String name = ReadIn.name(to);
        switch (to) {
            case "ja" -> { if (hangul > 0) return "the line holds hangul, not Japanese"; if (kana == 0 && han >= 6) return "the line is written in Chinese characters alone (" + han + " of them, no kana), not in Japanese"; return ""; }
            case "zh" -> { return kana > 0 ? "the line holds kana, not Chinese" : hangul > 0 ? "the line holds hangul, not Chinese" : ""; }
            case "ko" -> { if (kana > 0) return "the line holds kana, not Korean"; if (hangul == 0 && han >= 2) return "the line is written in Chinese characters (" + han + " of them) with no hangul, not in Korean"; return ""; }
            default -> {
                int own = READ_CYRILLIC.contains(to) ? cyrillic : GREEK.contains(to) ? greek : latin, east = han + kana + hangul, others = latin + cyrillic + greek - own;
                if (east >= 2 && east >= own) return "the line is written in Chinese characters, kana or hangul (" + east + " letters of " + (east + own + others) + "), not in " + name;
                if (own == 0 && others >= 8) return "the line is written in another alphabet (" + others + " letters, none of " + name + "'s), not in " + name;
                return "";
            }
        }
    }

    /**
     * The program's checks on one balloon ({@link Translation#mechanical}: a translation present, its numbers, the glossary's terms, the
     * names as decided; no length band), with a number the line says in a word counted as present; and that the line is written in the
     * language asked for ({@link #otherWriting}).
     */
    static List<Translation.Flag> flagsOf(int unit, String source, String translation, Map<String, String> glossary, Set<String> binding, DocNames.Decided decided, String code) {
        List<Translation.Flag> out = new ArrayList<>();
        String other = otherWriting(translation, code);   // a line in another language than the one asked for: translated again, told why
        if (!other.isEmpty()) out.add(new Translation.Flag(unit, "accuracy", "language", Translation.MAJOR, "", other, "program", false, false));
        if (sameAsSource(source, translation, decided)) out.add(new Translation.Flag(unit, "accuracy", "untranslated", Translation.MAJOR, "", "the line is its source letter for letter: it was not translated", "program", false, false));
        for (Translation.Flag f : Translation.mechanical(unit, source, translation, Translation.unitGlossary(glossary, decided, unit), binding, decided, null, "", code)) {
            if (!f.kind().equals("number") || translation == null) { out.add(f); continue; }
            List<String> missing = lackedAfterWords(List.of(f.words().split(", ")), translation, code);
            if (!missing.isEmpty()) out.add(new Translation.Flag(unit, f.category(), f.kind(), f.severity(), String.join(", ", missing), "the number" + (missing.size() == 1 ? " " : "s ") + String.join(", ", missing) + (missing.size() == 1 ? " is" : " are") + " not in the translation", f.by(), false, false));
        }
        return out;
    }

    /** Why a shorter line is not taken: "" when the program's checks find nothing serious in it that the longer line did not have; else the first such flag's reason. */
    static String seriousAfter(int unit, String source, String shorter, String longer, Map<String, String> glossary, Set<String> binding, DocNames.Decided decided, String code) {
        List<Translation.Flag> was = flagsOf(unit, source, longer, glossary, binding, decided, code);
        for (Translation.Flag f : flagsOf(unit, source, shorter, glossary, binding, decided, code))
            if (f.serious() && was.stream().noneMatch(w -> w.kind().equals(f.kind()))) return "the shorter line lost what the program checks: " + f.why();
        return "";
    }

    // ---- the checks ----

    /** What a balloon's revision is told to write: the words the pages wrote for the strings in its text, and over them the glossary's terms and the names decided for the unit. */
    static Map<String, String> revisionGlossary(Map<String, String> glossary, DocNames.Decided decided, Names written, int unit, String text) {
        Map<String, String> out = new LinkedHashMap<>(written.writtenIn(text));
        out.putAll(Translation.promptGlossary(glossary, decided, unit));
        return out;
    }

    /** What a comic's checks came to: as a document's, without the scores. */
    static final class Checks {
        final List<Translation.Flag> flags = new ArrayList<>();
        final List<Translation.Change> changes = new ArrayList<>();
        int backTranslated = 0, readUnits = 0;
        String readSaid = "";
        final int[] readerAside = new int[3];
    }

    /**
     * A comic's checks, each balloon a unit. The program's first, with no model: a translation present, every number of the balloon in
     * it, every glossary term and every decided name as decided; no length band and no score, which were measured on paragraphs and say
     * nothing true of a line of a few words. A flagged balloon is translated again in a fresh call and the revision kept when its flags
     * clear. A sample is translated back into the comic's own language and compared when the only model checks its own work. At the reviewed and thorough tiers the
     * second seat reads, a page at a call with the page's picture when it sees, the balloons the checks flagged and a sample of the rest
     * (every one at thorough, and in a short volume), with the document's own typology; what it flags major or critical is revised and
     * read again.
     */
    static Checks check(List<Unit> asUnits, List<Block> units, List<Read> pages, List<Integer> todo, Map<Integer, String> words, Map<String, String> glossary, Set<String> binding, DocNames.Decided names, Names written, Pair pair,
                        Translation.Seats seats, String q, Consumer<ObjectNode> progress, BooleanSupplier stop, ComicHelper.Reader reader, Path pics, boolean readerPictures) throws IOException {
        Checks c = new Checks();
        String code = pair.to(), language = pair.toName();
        Map<Integer, List<Translation.Flag>> byUnit = new TreeMap<>();
        for (int i : todo) for (Translation.Flag f : flagsOf(i, units.get(i).text(), words.get(i), glossary, binding, names, code)) byUnit.computeIfAbsent(i, x -> new ArrayList<>()).add(f);
        Set<Integer> flaggedByProgram = new TreeSet<>(byUnit.keySet());
        int n = 0;
        for (int i : new ArrayList<>(byUnit.keySet())) {
            if (stop.getAsBoolean()) throw new IOException("Stopped during the revision; nothing was written.");
            List<Translation.Flag> found = byUnit.get(i);
            String source = units.get(i).text(), earlier = words.get(i);   // the balloon's text without its readings: a reading in parentheses comes back as one
            StringBuilder why = new StringBuilder(); for (Translation.Flag f : found) why.append(why.length() == 0 ? "" : " ").append(f.line()).append('.');
            String revised = Translation.revise(seats, source, shownEarlier(found, earlier), why.toString(), language, revisionGlossary(glossary, names, written, i, source));
            Translation.tell(progress, "revising", ++n, byUnit.size(), q);
            if (revised != null) revised = unquoted(revised.split("\n")[0]);
            if (revised == null || revised.isBlank()) { List<Translation.Flag> st = new ArrayList<>(); for (Translation.Flag f : found) st.add(f.revisedTo(true)); byUnit.put(i, st); continue; }
            List<Translation.Flag> after = flagsOf(i, units.get(i).text(), revised, glossary, binding, names, code);
            boolean keep = after.stream().noneMatch(Translation.Flag::serious) || after.size() < found.size();
            c.changes.add(new Translation.Change(i, null, null, null, null, keep, "program"));
            if (keep) words.put(i, revised);
            List<Translation.Flag> now = keep ? after : found, settled = new ArrayList<>();
            for (Translation.Flag f : found) settled.add(f.revisedTo(now.stream().anyMatch(a -> a.kind().equals(f.kind()))));
            if (keep) for (Translation.Flag a : after) if (found.stream().noneMatch(f -> f.kind().equals(a.kind()))) settled.add(a.revisedTo(true));
            byUnit.put(i, settled);
        }
        List<Integer> read = new ArrayList<>(); for (int i : todo) if (words.get(i) != null) read.add(i);
        // a sample translated back into the comic's own language by the checker and compared with the source, as a document's is
        if (seats.backTranslate() && !read.isEmpty()) {
            List<Integer> sample = Translation.sample(read);
            List<String> lines = new ArrayList<>(); for (int i : sample) lines.add(words.get(i));
            List<String> back = Translate.translateLines(seats.checker(), lines, pair.fromName(), "a translation, back into the language it was translated from", Map.of(), Translation.FENCE);
            Map<Integer, String> backWords = new LinkedHashMap<>();
            for (int k = 0; k < sample.size(); k++) if (back.get(k) != null && !back.get(k).isBlank()) backWords.put(sample.get(k), back.get(k).strip());
            String by = "back-translation (" + seats.checkerName() + ")";
            for (int i : backWords.keySet()) for (Translation.Flag f : Translation.mechanical(i, units.get(i).text(), backWords.get(i), Map.of(), Set.of(), null, null, by, pair.from())) byUnit.computeIfAbsent(i, x -> new ArrayList<>()).add(f);
            for (List<Integer> b : Translation.batches(new ArrayList<>(backWords.keySet()), asUnits)) for (Translation.Flag f : Translation.judge(seats, asUnits, backWords, b, pair.fromName(), Map.of(), true)) byUnit.computeIfAbsent(f.unit(), x -> new ArrayList<>()).add(new Translation.Flag(f.unit(), f.category(), f.kind(), f.severity(), f.words(), f.why(), by, false, false));
            c.backTranslated = backWords.size();
        }
        // the tiers that read: the second seat, a page at a call
        if (Translation.reads(q)) {
            Set<Integer> chosen = new TreeSet<>();
            boolean all = q.equals(Translation.THOROUGH) || read.size() < Translation.SHORT;
            if (all) chosen.addAll(read);
            else { chosen.addAll(flaggedByProgram); chosen.retainAll(read); List<Integer> rest = new ArrayList<>(read); rest.removeAll(chosen); int want = (int) Math.ceil(rest.size() / 5.0); for (int k = 0; k < want; k++) chosen.add(rest.get((int) Math.floor(k * (rest.size() / (double) want)))); }
            c.readUnits = chosen.size();
            c.readSaid = q.equals(Translation.THOROUGH) ? "every balloon" : all ? "every balloon, since the volume is short (under " + Translation.SHORT + " balloons)" : "the balloons the checks flagged and a sample of the rest, a fifth spread through the volume";
            Map<Integer, List<Integer>> byPage = new TreeMap<>();
            for (int i : chosen) byPage.computeIfAbsent(units.get(i).page(), x -> new ArrayList<>()).add(i);
            int done = 0;
            Map<Integer, List<Translation.Flag>> judged = new TreeMap<>();
            Map<Integer, String> pictureOf = new LinkedHashMap<>();
            for (var e : byPage.entrySet()) {
                if (stop.getAsBoolean()) throw new IOException("Stopped during the reading; nothing was written.");
                List<Integer> batch = e.getValue();
                String picture = null;
                if (readerPictures) {
                    Path pic = pics.resolve(String.format(Locale.ROOT, "r%04d.jpg", e.getKey() + 1));
                    List<Map.Entry<String, double[]>> boxes = new ArrayList<>(); for (int k = 0; k < batch.size(); k++) boxes.add(Map.entry(String.valueOf(k + 1), units.get(batch.get(k)).box()));
                    try { reader.picture(pages.get(e.getKey()).page().file(), boxes, pic); picture = Vision.dataUrl(Files.readAllBytes(pic), "image/jpeg"); } catch (ComicHelper.Failed f) { picture = null; }
                }
                pictureOf.put(e.getKey(), picture);
                for (Translation.Flag f : Translation.judge(seats, asUnits, words, batch, language, glossary, binding, names, false, c.readerAside, picture)) judged.computeIfAbsent(f.unit(), x -> new ArrayList<>()).add(f);
                done += batch.size();
                Translation.tell(progress, "checking", done, chosen.size(), q);
            }
            int m = 0;
            for (var e : judged.entrySet()) {
                int i = e.getKey();
                List<Translation.Flag> found = e.getValue();
                if (found.stream().noneMatch(Translation.Flag::revises)) { byUnit.computeIfAbsent(i, x -> new ArrayList<>()).addAll(found); continue; }
                if (stop.getAsBoolean()) throw new IOException("Stopped during the revision; nothing was written.");
                StringBuilder why = new StringBuilder(); for (Translation.Flag f : found) why.append(why.length() == 0 ? "" : " ").append(f.line()).append('.');
                String revised = Translation.revise(seats, units.get(i).text(), words.get(i), why.toString(), language, revisionGlossary(glossary, names, written, i, units.get(i).text()));
                Translation.tell(progress, "revising", ++m, judged.size(), q, "reader");
                if (revised != null) revised = unquoted(revised.split("\n")[0]);
                List<Translation.Flag> settled = new ArrayList<>();
                if (revised == null || revised.isBlank()) { for (Translation.Flag f : found) settled.add(f.revises() ? f.revisedTo(true) : f); byUnit.computeIfAbsent(i, x -> new ArrayList<>()).addAll(settled); continue; }
                List<Translation.Flag> after = new ArrayList<>(flagsOf(i, units.get(i).text(), revised, glossary, binding, names, code));
                after.addAll(Translation.judge(seats, asUnits, Map.of(i, revised), List.of(i), language, glossary, binding, names, false, c.readerAside, null));
                words.put(i, revised);
                c.changes.add(new Translation.Change(i, null, null, null, null, true, seats.checkerName()));
                for (Translation.Flag f : found) { if (f.revises()) settled.add(f.revisedTo(after.stream().anyMatch(a -> a.revises() && a.kind().equals(f.kind())))); else settled.add(f); }
                for (Translation.Flag a : after) if (found.stream().noneMatch(f -> f.kind().equals(a.kind()))) settled.add(a.revisedTo(a.revises()));
                byUnit.computeIfAbsent(i, x -> new ArrayList<>()).addAll(settled);
            }
        }
        for (List<Translation.Flag> fs : byUnit.values()) c.flags.addAll(fs);
        c.flags.sort((a, b) -> a.unit() != b.unit() ? Integer.compare(a.unit(), b.unit()) : a.kind().compareTo(b.kind()));
        return c;
    }
}
