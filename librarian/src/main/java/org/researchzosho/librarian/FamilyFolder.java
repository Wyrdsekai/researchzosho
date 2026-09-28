package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import org.researchzosho.librarian.profiles.GenealogyProfile;
/**
 * A folder of family material, read in the order that makes each file help the next: the family's own notes first,
 * because they say who is who; then a tree file; then lists of links; then pictures and short documents; the books last,
 * kept to the family's names, which by then the library knows. A file read before is not read again.
 */
public final class FamilyFolder {

    private FamilyFolder() { }

    /**
     * order: 1 the family's own short texts · 2 a tree file · 3 a list of links · 4 pictures · 5 books and other long documents.
     * {@code pages}: the pictures of one record, in page order, read as one text; a file on its own is its only page.
     */
    public record Item(Path file, int order, String what, long size, boolean readBefore, List<Path> pages) {
        public Item(Path file, int order, String what, long size, boolean readBefore) { this(file, order, what, size, readBefore, List.of(file)); }
    }

    /**
     * Who wrote which file, from the notes in the folder: a line such as "Kimie Hale wrote community.pdf", "… also wrote …", "Kimie Hale -
     * wrote the book - community.pdf", "community.pdf was written by Kimie Hale", "community.pdf: by Kimie Hale", or a line that calls the file
     * somebody's own memoir, autobiography or diary ("Tom Hart - is the one village.epub is about. it is his own memoir", "village.epub is Tom
     * Hart's memoir"). File name → writer. The writer is the name alone: in "Kimie Hale - my father's cousin - wrote community.pdf" the words
     * between the dashes are the owner's account of her, which the notes' own read files. A translator is not the writer ({@link #translators}).
     * A book's writer matters: "my grandfather" in it is that person's grandfather, and the facts are filed as their account.
     */
    public static Map<String, String> writers(List<Item> plan) { return credits(plan, false); }

    /** Who translated which file, from the notes in the folder: "Rose Hart translated village.epub", "village.epub was translated by Rose Hart". File name → translator. */
    public static Map<String, String> translators(List<Item> plan) { return credits(plan, true); }

    /** The files the notes call their writer's own memoir, autobiography or diary ("it is his own memoir", "village.epub is Tom Hart's diary"). */
    public static Set<String> memoirs(List<Item> plan) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, String> w : writers(plan).entrySet()) {
            String who = Pattern.quote(w.getValue());
            // the writer's own: "his own memoir", "Tom Hart's diary", "the memoir of Tom Hart", 森田勇の自伝; a diary of somebody else is not
            Pattern own = Pattern.compile("(?i)(?:his|her|their)\\s+own\\s+" + MEMOIR + "|" + who + "['’]s\\s+(?:own\\s+)?" + MEMOIR + "|" + MEMOIR + "\\s+of\\s+" + who + "|自身の" + MEMOIR + "|" + who + "\\s*の" + MEMOIR);
            for (String line : notesLines(plan)) if (line.contains(w.getKey()) && own.matcher(line).find()) out.add(w.getKey());
        }
        return out;
    }

    /** The lines of the family's own short texts in a folder. */
    private static List<String> notesLines(List<Item> plan) {
        List<String> out = new ArrayList<>();
        for (Item it : plan) {
            if (it.order() != 1) continue;
            try { out.addAll(List.of(Files.readString(it.file(), StandardCharsets.UTF_8).split("\\R"))); } catch (Exception e) { }
        }
        return out;
    }

    // the words before a file's name that say which work it is: "wrote the book - village.epub"
    private static final String WORK = "(?:the\\s+(?:book|file|text|account|memoirs?|diary|article|letter|translation)s?\\s*[-–—:]?\\s*)?";
    // a text that is somebody's own account of their life
    private static final String MEMOIR = "(?:memoirs?|autobiography|diary|diaries|journal|reminiscences|life story|自伝|自叙伝|回想録|回顧録|手記|日記)";

    private static Map<String, String> credits(List<Item> plan, boolean translated) {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (Item it : plan) names.add(it.file().getFileName().toString());
        for (Item it : plan) {
            if (it.order() != 1) continue;
            String text;
            try { text = Files.readString(it.file(), StandardCharsets.UTF_8); } catch (Exception e) { continue; }
            String notes = it.file().getFileName().toString();
            for (String line : text.split("\\R")) {
                for (String file : names) {
                    if (!line.contains(file) || file.equals(notes)) continue;
                    String who = translated ? translatorIn(line, file) : writerIn(line, file);
                    if (who == null) continue;
                    // "I wrote village.epub": the writer of the notes
                    if (who.matches("(?i)i|me|myself|私|わたし|僕")) who = "the writer of " + notes;
                    out.putIfAbsent(file, who);
                }
            }
        }
        return out;
    }

    /**
     * Whether a file is notes as a folder read takes them: a short text that is no list of web addresses. A file that is no longer there
     * counts by the kind of its name.
     */
    static boolean notes(Path p) {
        if (!TEXT.contains(Corpus.ext(p))) return false;
        if (!Files.isRegularFile(p)) return true;
        try { return Files.size(p) <= BOOK_BYTES && GenealogyProfile.urlList(p).isEmpty(); } catch (IOException e) { return true; }
    }

    /** Who wrote which file, from the notes in a folder as the folder is now ({@link #writers}); empty when the folder is gone. */
    static Map<String, String> writersIn(Path dir) { return creditsIn(dir, false); }

    /** Who translated which file, from the notes in a folder as the folder is now ({@link #translators}); empty when the folder is gone. */
    static Map<String, String> translatorsIn(Path dir) { return creditsIn(dir, true); }

    private static Map<String, String> creditsIn(Path dir, boolean translated) {
        if (dir == null || !Files.isDirectory(dir)) return Map.of();
        List<Item> items = new ArrayList<>();
        try (var s = Files.walk(dir, 3)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) if (reads(p)) items.add(new Item(p, notes(p) ? 1 : 5, "", 0, false));
        } catch (IOException e) {
            return Map.of();
        }
        return credits(items, translated);
    }

    /**
     * The owner's own notes in a library: a notes file (a short text, no list of web addresses) that lives in a folder the library's family
     * reads come from, or that the owner read as a file, with no other writer named for it: neither the folder's notes ({@link #writersIn},
     * {@link #translatorsIn}) nor the read itself (--by) names one. How it was read, with the folder or on its own, makes no difference:
     * taking a notes file back and reading it again on its own is the usual way to refresh it. The link pass reads the owner's own words
     * from these ({@link FamilyLinks}), and the summary labels them "your notes" ({@link FamilySummary}): one rule for both.
     */
    public static final class OwnerNotes {
        private final LibraryStore store;
        private List<FamilyReads.Row> rows;
        private final Set<String> folders = new HashSet<>();
        private final Map<String, Boolean> memo = new HashMap<>();
        private final Map<String, Map<String, String>> credited = new HashMap<>();

        OwnerNotes(LibraryStore store) { this.store = store; }

        /** Whether a source's locator is one of the owner's own notes. */
        public boolean is(String locator) {
            if (locator == null || !locator.startsWith("file:")) return false;
            return memo.computeIfAbsent(locator, this::work);
        }

        /** The last read of a file, by its locator; null when the library never read it. */
        public FamilyReads.Row rowOf(String locator) {
            load();
            String where = norm(pathOf(locator));
            FamilyReads.Row row = null;
            for (FamilyReads.Row r : rows) if (!r.where().isBlank() && norm(r.where()).equals(where)) row = r;
            return row;
        }

        /** A file's path from its locator: "file://" taken off, and the address's %20 for a space read as it is meant; a "%" of the path itself stays. */
        public static String pathOf(String locator) {
            String where = locator.replaceFirst("^file:(?://)?", "");
            try { return URLDecoder.decode(where, StandardCharsets.UTF_8); } catch (IllegalArgumentException e) { return where; }
        }

        private static String norm(String p) {
            try { return Path.of(p).toAbsolutePath().normalize().toString(); } catch (RuntimeException e) { return p; }
        }

        private void load() {
            if (rows != null) return;
            try { rows = FamilyReads.everyRow(store); } catch (IOException e) { rows = List.of(); }
            for (FamilyReads.Row r : rows) {
                if (!r.folder().isBlank()) folders.add(norm(r.folder()));
                else if (r.old() && !r.where().isBlank()) { Path d = Path.of(r.where()).getParent(); if (d != null) folders.add(norm(d.toString())); }
            }
        }

        private boolean work(String locator) {
            FamilyReads.Row row = rowOf(locator);
            Path file = Path.of(norm(pathOf(locator)));
            Path dir = file.getParent();
            boolean inFolder = dir != null && folders.contains(dir.toString());
            boolean readAsFile = row != null && (row.old() || row.kind().equals(FamilyReads.FILE));
            if (!inFolder && !readAsFile) return false;
            if (!notes(file)) return false;
            if (row != null && !row.by().isBlank()) return false;
            String name = String.valueOf(file.getFileName());
            Map<String, String> credits = credited.computeIfAbsent(dir == null ? "" : dir.toString(), d -> {
                Map<String, String> m = new HashMap<>(writersIn(dir));
                m.putAll(translatorsIn(dir));
                return m;
            });
            return !credits.containsKey(name);
        }
    }

    /** The owner's own notes in a library ({@link OwnerNotes}), worked out as they are asked for. */
    public static OwnerNotes ownerNotes(LibraryStore store) { return new OwnerNotes(store); }

    /** The writer a line of the notes gives a file, the name alone; null when the line names none. */
    static String writerIn(String line, String file) {
        String f = Pattern.quote(file);
        if (translatorIn(line, file) != null && !Pattern.compile("(?i)\\bwrote\\b|\\bwritten\\s+by\\b").matcher(line).find()) return null;
        Matcher a = Pattern.compile("(?i)^\\s*(.{1,80}?)\\s+(?:also\\s+)?wrote\\s+" + WORK + f).matcher(line);
        if (a.find()) return nameOnly(a.group(1));
        Matcher b = Pattern.compile("(?i)" + f + "\\s*(?:was\\s+written\\s+by|is\\s+by|:\\s*by|by)\\s+(.{2,80}?)\\s*[.。]?\\s*$").matcher(line);
        if (b.find()) return nameOnly(b.group(1));
        // somebody's own memoir: "village.epub is Tom Hart's memoir", "Tom Hart's diary, village.epub", "village.epub is the memoir of Tom Hart"
        Matcher c = Pattern.compile("(?i)" + f + "\\s*(?:is|was|:)\\s+(.{2,60}?)['’]s\\s+(?:own\\s+)?" + MEMOIR).matcher(line);
        if (c.find()) return nameOnly(c.group(1));
        Matcher d = Pattern.compile("(?i)^\\s*(.{2,60}?)['’]s\\s+(?:own\\s+)?" + MEMOIR + "\\s*[,:：-]?\\s*" + WORK + f).matcher(line);
        if (d.find()) return nameOnly(d.group(1));
        Matcher e = Pattern.compile("(?i)" + f + "\\s*(?:is|was|:)\\s+(?:the\\s+)?" + MEMOIR + "\\s+of\\s+(.{2,60}?)\\s*[.。]?\\s*$").matcher(line);
        if (e.find()) return nameOnly(e.group(1));
        Matcher j = Pattern.compile(f + "\\s*(?:は|：|:)?\\s*(.{1,30}?)の" + MEMOIR).matcher(line);
        if (j.find()) return nameOnly(j.group(1));
        // "Tom Hart - is the one village.epub is about. it is his own memoir": the person the line is about, who is named first
        if (Pattern.compile("(?i)(?:his|her|their)\\s+own\\s+" + MEMOIR + "|自身の" + MEMOIR).matcher(line).find()) return lead(line);
        return null;
    }

    /** The translator a line of the notes gives a file, the name alone; null when the line names none. */
    static String translatorIn(String line, String file) {
        String f = Pattern.quote(file);
        Matcher a = Pattern.compile("(?i)^\\s*(.{2,80}?)\\s+(?:also\\s+)?translated\\s+" + WORK + f).matcher(line);
        if (a.find()) return nameOnly(a.group(1));
        Matcher b = Pattern.compile("(?i)" + f + "\\s*(?:was\\s+translated\\s+by|is\\s+translated\\s+by|:\\s*translated\\s+by|translated\\s+by)\\s+(.{2,80}?)\\s*[.。]?\\s*$").matcher(line);
        if (b.find()) return nameOnly(b.group(1));
        Matcher j = Pattern.compile("^\\s*(.{1,30}?)\\s*(?:が|は)\\s*" + f + "\\s*を?\\s*(?:翻訳|訳)").matcher(line);
        if (j.find()) return nameOnly(j.group(1));
        return null;
    }

    /** The name a line of the notes begins with: the words before its first dash, or before its first verb ("Tom Hart is the one …"). */
    private static String lead(String line) {
        String l = line.strip().replaceFirst("^[-*•・]\\s*", "");
        Matcher dash = Pattern.compile("\\s[-–—]\\s").matcher(l);
        String head = dash.find() ? l.substring(0, dash.start()) : l.split("(?i)\\s+(?:is|was|wrote|also|translated|has|had|kept)\\b", 2)[0];
        String name = nameOnly(head);
        return name == null || name.equals(l.strip()) ? null : name;
    }

    /**
     * The name alone of the words the notes give for a writer: "Kimie Hale - my father's cousin -" is Kimie Hale, "Rose Hart (Rose Mary Hart)"
     * is Rose Hart. Null when nothing is left.
     */
    static String nameOnly(String words) {
        String w = words == null ? "" : words.strip().replaceFirst("^[-*•・]\\s*", "");
        Matcher dash = Pattern.compile("\\s*(?:\\s[-–—]\\s|\\s[-–—]$|[-–—]$)").matcher(w);
        if (dash.find()) w = w.substring(0, dash.start());
        w = w.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip();
        return w.isEmpty() ? null : w;
    }

    static final long BOOK_BYTES = 300_000;
    /** Words that stand before or in place of a name and are nobody's family name. The rule above decides; this only catches what a rule of counting cannot. */
    private static final Set<String> TITLES = Set.of("the", "doctor", "professor", "father", "mother", "brother", "sister", "bishop", "reverend", "sir", "lady", "lord", "count", "countess", "baron", "baroness",
            "viscount", "marquis", "prince", "princess", "general", "admiral", "captain", "colonel", "major", "minister", "president", "san", "sama", "sensei", "junior", "senior");
    private static final Set<String> TEXT = Set.of("txt", "md", "docx", "odt", "rtf", "html", "htm");
    private static final Set<String> BOOK = Set.of("epub", "pdf");

    /**
     * Whether a folder read reads a file as the family's own notes: a short text, not a book (order 1 of {@link #plan}; a list of links is
     * read as a list, and never as a file).
     */
    static boolean notes(String fileName, long bytes) {
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        return TEXT.contains(ext) && bytes <= BOOK_BYTES;
    }

    public static List<Item> plan(LibraryStore store, Path dir) throws IOException {
        Set<String> before = readBefore(store);
        List<Item> out = new ArrayList<>();
        try (var s = Files.walk(dir, 3)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                if (!reads(p)) continue;
                String ext = Corpus.ext(p);
                long size = Files.size(p);
                int order; String what;
                if (ext.equals("ged")) { order = 2; what = "a family-tree file"; }
                else if (TEXT.contains(ext) && !GenealogyProfile.urlList(p).isEmpty()) { order = 3; what = "a list of links"; }
                else if (Corpus.PICTURES.contains(ext)) { order = 4; what = "a picture"; }
                else if (BOOK.contains(ext) || (TEXT.contains(ext) && size > BOOK_BYTES)) { order = 5; what = "a book or a long document"; }
                else if (TEXT.contains(ext)) { order = 1; what = "notes"; }
                else continue;
                out.add(new Item(p, order, what, size, before.contains(sha(p))));
            }
        }
        out = pagesTogether(out);
        // pictures by name, so that page 2 is read after page 1; the rest shortest first
        out.sort(Comparator.comparingInt(Item::order).thenComparing((a, b) -> a.order() == 4 ? natural(a.file().getFileName().toString(), b.file().getFileName().toString()) : Long.compare(a.size(), b.size())));
        return out;
    }

    /**
     * Whether a folder read reads this file: a tree file, notes, a list of links, a picture or a book, and not a file whose name starts
     * with "." or ends with "~", which is hidden or a copy an editor left.
     */
    static boolean reads(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        if (name.startsWith(".") || name.endsWith("~")) return false;
        String ext = Corpus.ext(p);
        return ext.equals("ged") || TEXT.contains(ext) || Corpus.PICTURES.contains(ext) || BOOK.contains(ext);
    }

    /** The end of a file name that numbers a page: p2, page 2, pg.2, _2, -2, (2), 2頁, 2枚目. Group 1 is the rest of the name. */
    private static final Pattern PAGE = Pattern.compile("(?i)^(.*?\\S)(?:[\\s_.-]*(?:pages?|pp|pg|p|頁|ページ)[\\s_.-]*(\\d{1,3})|[\\s_-]+(\\d{1,3})|\\s*[(（](\\d{1,3})[)）]|[\\s_-]*(\\d{1,3})\\s*(?:頁|ページ|枚目))$");

    /** The name a camera, a phone or a scanner gives a file by itself: it says nothing of what is in it, and its numbers are not pages. */
    private static final Pattern CAMERA = Pattern.compile("(?i)^(?:img|dsc[nf]?|dcim|pxl|mvimg|photo|image|scan|scanned|screenshot|screen shot|whatsapp image|signal|gopr|wp|p\\d{3})[\\s_-]*\\d.*|^[\\d\\s_.-]+$");

    static boolean cameraName(String stem) { return CAMERA.matcher(stem.strip()).matches(); }

    /** {rest of the name, page number} for a file name that ends in a page number, else null. */
    static String[] page(String fileName) {
        String stem = fileName.replaceFirst("\\.[A-Za-z0-9]+$", "");
        if (cameraName(stem)) return null;
        Matcher m = PAGE.matcher(stem);
        if (!m.matches()) return null;
        for (int g = 2; g <= 5; g++) if (m.group(g) != null) return new String[]{m.group(1).strip().toLowerCase(Locale.ROOT), m.group(g)};
        return null;
    }

    /** Pictures in one folder whose names differ only by a page number are one record: one item, its pages in order. */
    static List<Item> pagesTogether(List<Item> items) {
        Map<String, List<Item>> records = new LinkedHashMap<>();
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            String[] pg = it.order() == 4 ? page(it.file().getFileName().toString()) : null;
            if (pg == null) { out.add(it); continue; }
            records.computeIfAbsent(it.file().getParent() + "\t" + pg[0], k -> new ArrayList<>()).add(it);
        }
        for (List<Item> pages : records.values()) {
            if (pages.size() == 1) { out.add(pages.get(0)); continue; }
            pages.sort(Comparator.comparingInt(i -> Integer.parseInt(page(i.file().getFileName().toString())[1])));
            out.add(new Item(pages.get(0).file(), 4, pages.size() + " pictures, the pages of one record, read together", pages.stream().mapToLong(Item::size).sum(),
                    pages.stream().allMatch(Item::readBefore), pages.stream().map(Item::file).toList()));
        }
        return out;
    }

    /** Names compared with their numbers as numbers: page 2 before page 10. */
    static int natural(String a, String b) {
        Matcher x = Pattern.compile("\\d+|\\D+").matcher(a.toLowerCase(Locale.ROOT)), y = Pattern.compile("\\d+|\\D+").matcher(b.toLowerCase(Locale.ROOT));
        while (x.find() && y.find()) {
            String p = x.group(), q = y.group();
            int c = Character.isDigit(p.charAt(0)) && Character.isDigit(q.charAt(0)) ? Long.compare(Long.parseLong(p.length() > 18 ? p.substring(0, 18) : p), Long.parseLong(q.length() > 18 ? q.substring(0, 18) : q)) : p.compareTo(q);
            if (c != 0) return c;
        }
        return a.compareTo(b);
    }

    /**
     * What a file's name says of what is in it: the years, the family names the library knows, and a word of doubt. A family's scan
     * folder is often labelled better than its pages can be read. {@code doubts} are the words that say the one who named it was not sure.
     */
    public record Label(String name, List<String> years, List<String> families, List<String> doubts) {
        public boolean isEmpty() { return years.isEmpty() && families.isEmpty() && doubts.isEmpty(); }

        /** For the reader: what the label gives, and that the facts come from the writing. */
        public String forReader() {
            StringBuilder b = new StringBuilder("the file is labelled \"" + name + "\"");
            List<String> gives = new ArrayList<>();
            if (!years.isEmpty()) gives.add((years.size() == 1 ? "the year " : "the years ") + String.join(", ", years));
            if (!families.isEmpty()) gives.add((families.size() == 1 ? "the family name " : "the family names ") + String.join(", ", families));
            if (!gives.isEmpty()) b.append(", which gives ").append(String.join(" and ", gives));
            if (!doubts.isEmpty()) b.append("; the word \"").append(doubts.get(0)).append("\" in it says whoever labelled it was not sure");
            return b.append(". Use the label to see whom the page is about, and take each fact from the page's own writing").toString();
        }

        /** For a claim's note. */
        public String note() {
            List<String> parts = new ArrayList<>();
            if (!years.isEmpty()) parts.add((years.size() == 1 ? "the year " : "the years ") + String.join(", ", years));
            if (!families.isEmpty()) parts.add((families.size() == 1 ? "the family name " : "the family names ") + String.join(", ", families));
            if (!doubts.isEmpty()) parts.add("the word " + doubts.get(0) + ", so whoever labelled it was not sure");
            return "the file is labelled \"" + name + "\"" + (parts.isEmpty() ? "" : ": " + String.join("; ", parts));
        }
    }

    /** Words in a file name that say its maker was not sure. */
    private static final Pattern DOUBT = Pattern.compile("(?i)(?<![\\p{L}])(maybe|perhaps|possibly|probably|prob|unsure|uncertain|unknown|unconfirmed|unverified|guess|poss)(?![\\p{L}])|\\?|？|不明|多分|たぶん|推定|未確認|不確か|かも");

    /** What a file's name says, or null for a name a camera or a scanner gave it, and for a name that gives nothing. */
    public static Label label(String fileName, List<String> families) {
        String stem = fileName.replaceFirst("\\.[A-Za-z0-9]+$", "");
        if (cameraName(stem)) return null;
        List<String> years = new ArrayList<>(), found = new ArrayList<>(), doubts = new ArrayList<>();
        for (String token : stem.split("[\\s_.,()（）\\[\\]-]+")) {
            if (token.isEmpty()) continue;
            Matcher y = Pattern.compile("^(1[5-9]\\d\\d|20\\d\\d)$").matcher(token);
            boolean han = token.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN);
            // an era year is read only where the era is written out: a letter and a number in a file name are more often something else
            int era = han || token.replaceAll("[^\\p{L}]", "").length() >= 4 ? FamilyDate.eraYear(token) : 0;
            String year = y.matches() ? y.group(1) : era > 0 ? token + " (" + era + ")" : null;
            if (year != null && !years.contains(year)) years.add(year);
        }
        String flatStem = FamilyQuestions.plain(KanjiForms.modern(stem));
        for (String f : families) {
            String ff = FamilyQuestions.plain(KanjiForms.modern(f));
            boolean cjk = ff.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN || Character.UnicodeScript.of(c) == Character.UnicodeScript.HIRAGANA || Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA);
            boolean in = cjk ? flatStem.contains(ff) : Pattern.compile("(?<![\\p{L}])" + Pattern.quote(ff) + "(?![\\p{L}])").matcher(flatStem).find();
            if (in && !found.contains(f)) found.add(f);
        }
        Matcher d = DOUBT.matcher(stem);
        while (d.find()) if (!doubts.contains(d.group())) doubts.add(d.group());
        Label l = new Label(fileName, years, found, doubts);
        return l.isEmpty() ? null : l;
    }

    /**
     * The family's names, from what the library already holds: first the family names its claims give (the family part of a name claim, the
     * name of a family and the ways the sources write it), the most borne first; then, for the rest, a part of a name that at least two
     * people share: for a name in Latin letters its last word, for a name written without spaces the longest beginning shared with another.
     */
    public static List<String> familyNames(LibraryStore store) throws IOException { return familyNames(FamilyPeople.view(store)); }

    /** At most this many family names. */
    private static final int FAMILY_NAMES = 24;

    /**
     * The same, from a graph already read in genealogy's view. {@link FamilyNameHistory} asks it for its default when it splits a name in
     * characters that no name claim splits: this reads only the family parts the name history keeps while it reads the claims, never a split
     * the history works out, so the two never ask each other in turn.
     */
    public static List<String> familyNames(Graph g) {
        List<String> out = everyFamilyName(g);
        return out.size() > FAMILY_NAMES ? new ArrayList<>(out.subList(0, FAMILY_NAMES)) : out;
    }

    /**
     * Every family name the library knows or guesses, the most borne first, with no limit: what splits a name in characters that no claim
     * splits ({@link FamilyNameHistory}), where a family name borne by two people must not drop off a list cut short for reading a book.
     */
    public static List<String> everyFamilyName(Graph g) {
        List<String> out = new ArrayList<>(known(g));
        for (String guess : guessed(g)) if (out.stream().noneMatch(k -> sameFamily(k, guess))) out.add(guess);
        return out;
    }

    /** Two ways of writing one family name: 髙橋 and 高橋, Endō and Endo, Morita and morita. */
    private static boolean sameFamily(String a, String b) { return FamilyForms.sameForm(a, b) || FamilyQuestions.plain(a).equals(FamilyQuestions.plain(b)); }

    /**
     * The family names the claims give: the family part of each name claim, counted once for each person who bore it, and each family's name
     * with the ways the sources write it (森田家 is 森田, the Endos Endo), counted once for each member. The most borne first.
     */
    private static List<String> known(Graph g) {
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        Map<String, Set<String>> bearers = new LinkedHashMap<>();
        for (String part : idx.familyParts()) bearers.computeIfAbsent(part, k -> new HashSet<>());
        for (Graph.Edge e : g.edges()) {
            if (!e.predicate().equals(FamilyNameHistory.PREDICATE) || e.disputed()) continue;
            String part = FamilyDetail.get(idx.finding(e.findingId()), "family").strip();
            if (!part.isEmpty()) bearers.computeIfAbsent(part, k -> new HashSet<>()).add(e.from());
        }
        for (String id : FamilyHouses.all(g)) {
            List<String> written = new ArrayList<>(List.of(FamilyHouses.labelOf(g, id)));
            written.addAll(FamilyHouses.aliasesOf(g, id));
            Set<String> members = new HashSet<>();
            for (FamilyHouses.Membership m : FamilyHouses.members(g, id)) members.add(m.person());
            for (String w : written) {
                String name = FamilyHouses.nameOf(w).strip();
                if (!name.isEmpty() && FamilyForms.script(name).length() > 0 && !FamilyHouses.familyWord(name)) bearers.computeIfAbsent(name, k -> new HashSet<>()).addAll(members);
            }
        }
        // one entry for each family name however it is written (髙橋 and 高橋, Endō and Endo), as it was written first
        Map<String, Set<String>> joined = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : bearers.entrySet()) {
            String same = joined.keySet().stream().filter(k -> sameFamily(k, e.getKey())).findFirst().orElse(e.getKey());
            joined.computeIfAbsent(same, k -> new HashSet<>()).addAll(e.getValue());
        }
        List<String> out = new ArrayList<>(joined.keySet());
        out.sort(Comparator.comparingInt((String k) -> -joined.get(k).size()));
        return out;
    }

    /**
     * The written names whose family part a standing name claim gives, each form of them ({@link #written}): the name as written and every
     * form the claim lists. Read from the claims themselves, never from the names the history works out, which ask this class in turn.
     */
    private static Set<String> splitByClaims(Graph g) {
        FamilyNameHistory.Index idx = FamilyNameHistory.of(g);
        Set<String> out = new HashSet<>();
        for (Graph.Edge e : g.edges()) {
            if (!e.predicate().equals(FamilyNameHistory.PREDICATE) || FamilyKin.gone(e)) continue;
            Finding f = idx.finding(e.findingId());
            if (f == null || FamilyDetail.get(f, "family").isBlank()) continue;
            out.add(written(FamilyNameHistory.written(f)));
            for (FamilyNameHistory.Form form : FamilyNameHistory.formsOf(FamilyDetail.get(f, "forms"))) out.add(written(form.text()));
        }
        return out;
    }

    /** A name as the claims and the aliases are compared here: modern characters, no accents, lower case, spaces as one. */
    private static String written(String name) { return KanjiForms.modern(FamilyQuestions.plain(name)); }

    /** The family names guessed from the shapes of the names of the people in a family relation. */
    private static List<String> guessed(Graph g) {
        List<String> people = new ArrayList<>();
        // only people who stand in a family relation, and no stand-in for somebody unnamed: a book names hundreds of others
        Set<String> inFamily = new HashSet<>();
        for (Graph.Edge e : g.edges()) if (FamilyAccount.isKinship(e.predicate())) { inFamily.add(e.from()); inFamily.add(e.to()); }
        // a name whose family part a name claim gives is not guessed at: its family part is known, and its given part is no family name
        Set<String> split = splitByClaims(g);
        for (Graph.Node n : g.nodes()) {
            if (!n.kind().equals("person") || !inFamily.contains(n.id()) || FamilyQuestions.placeholder(n.label())) continue;
            if (!split.contains(written(n.label()))) people.add(n.label());
            for (String a : n.aliases()) if (!FamilyQuestions.placeholder(a) && !split.contains(written(a))) people.add(a);
        }
        Map<String, Integer> count = new LinkedHashMap<>(), latinLast = new LinkedHashMap<>(), latinFirst = new LinkedHashMap<>();
        List<String> joined = new ArrayList<>();   // names written without spaces
        for (String raw : people) {
            String a = raw.replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip();
            // a kana reading with a space between its parts (もりた けんじ) is no name in Latin letters: nothing here splits it
            if (FamilyForms.script(a).equals("kana")) continue;
            if (a.contains(" ")) {
                // a name in Latin letters: its last word, and its first word too, because a Japanese name is written either way round (Morita Isamu, Isamu Morita).
                // Accents are taken off: a book writes Endo where a tree writes Endō
                String[] words = FamilyQuestions.plain(a).split(" ");
                String lastWord = words[words.length - 1], firstWord = words[0];
                if (lastWord.length() >= 3 && lastWord.chars().allMatch(Character::isLetter)) latinLast.merge(lastWord, 1, Integer::sum);
                if (words.length > 1 && firstWord.length() >= 3 && firstWord.chars().allMatch(Character::isLetter)) latinFirst.merge(firstWord, 1, Integer::sum);
            }
            else if (!a.isEmpty() && Character.UnicodeScript.of(a.codePointAt(0)) == Character.UnicodeScript.HAN) joined.add(a);
        }
        // how many people begin with each one-, two- or three-character beginning; a person's family name is the beginning most people share, the longer of equals
        Map<String, Integer> begins = new LinkedHashMap<>();
        for (String a : new LinkedHashSet<>(joined)) for (int k = 1; k <= 3 && k < a.length(); k++) begins.merge(a.substring(0, k), 1, Integer::sum);
        // a family name is the beginning that the most people share, where two characters count for more than one: 森田 before 森, and before 森田一
        for (String a : new LinkedHashSet<>(joined)) {
            String best = ""; double most = 0;
            for (int k = 1; k <= 3 && k < a.length(); k++) {
                int c = begins.getOrDefault(a.substring(0, k), 0);
                if (c < 2 || k == 1) continue;   // one shared character is more often two families (中山, 中川) or a shared given name (源次, 源花子) than a family: --family gives a one-character name
                double score = c * Math.min(k, 2) + (k == 2 ? 0.5 : 0);
                if (score > most) { most = score; best = a.substring(0, k); }
            }
            // when everybody who begins with the two characters also shares the third, the family name is the three (中御門, not 中御)
            if (best.length() == 2 && a.length() > 3 && begins.getOrDefault(a.substring(0, 3), 0) == begins.getOrDefault(best, 0)) best = a.substring(0, 3);
            if (!best.isEmpty()) count.merge(best, 1, Integer::sum);
        }
        // a first word is a family name only when it is also somebody's last word, or several people begin with it: John is not a family
        // a last word two people share is a family name. A first word counts only when it is also somebody's last word (Morita Isamu beside
        // Isamu Morita): a first word alone is a title or a given name (The, Doctor, Father, John)
        latinLast.forEach((w, c) -> { int all = c + latinFirst.getOrDefault(w, 0); if (all >= 2 && !TITLES.contains(w)) count.merge(Character.toUpperCase(w.charAt(0)) + w.substring(1), all, Integer::sum); });
        List<String> out = new ArrayList<>();
        count.entrySet().stream().filter(e -> e.getValue() >= 2).sorted((x, y) -> y.getValue() - x.getValue()).forEach(e -> out.add(e.getKey()));
        return out;
    }

    /** The files read before, by their content: the first column of the list of reads ({@link FamilyReads}). */
    static Set<String> readBefore(LibraryStore store) throws IOException {
        Set<String> out = new HashSet<>();
        for (FamilyReads.Row r : FamilyReads.rows(store)) out.add(r.key());
        return out;
    }

    /** A file of the folder it is in, read: written down so that the next read of the folder skips it while it has not changed. */
    public static void markRead(LibraryStore store, Path file) throws IOException {
        Path dir = file.toAbsolutePath().normalize().getParent();
        markRead(store, file, FamilyReads.FILE, dir == null ? "" : dir.toString());
    }

    /** A file of a folder read as a whole, with what kind of file it is and the folder the read was given. */
    public static void markRead(LibraryStore store, Path file, String kind, String folder) throws IOException {
        FamilyReads.readFile(store, file, kind, folder, "", "");
    }

    static String sha(Path p) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static String size(long bytes) { return bytes < 1_000_000 ? Math.max(1, bytes / 1000) + " KB" : String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0); }
}
