package org.researchzosho.librarian;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.researchzosho.librarian.profiles.GenealogyProfile;

/**
 * A fresh start for what the family reader wrote, and for nothing else. It takes away the reader's own claims that are still drafts,
 * the people, places, events, families and names that only those claims were about, the other names the reader gave to people, and the
 * list of files already read. What a person accepted or disputed, moved to another person of the same name with a split, or told the
 * library in their own words (the family's answers to the questions about names among it), stays, and so does every report, every claim
 * a research run made, every saved page, and every book. A copy of all that is taken away is kept first.
 *
 * <p>Three sizes. The plain reset ({@link #folder}) takes back only what was read from the files of a folder the library read as a whole,
 * which a new read of the folder brings back; drafts read from Geni profiles, web pages and files elsewhere stay, because nothing but
 * their own read brings them back (a Geni read needs a sign-in that lasts a day). {@code --all} ({@link #plan(LibraryStore, List)})
 * takes back every draft of the family's reading. {@code --from} takes back one file or page.
 */
public final class FamilyReset {

    private FamilyReset() { }

    /** How a question that `genealogy research` put on the waiting list is marked. It was written from the facts that are now going. */
    public static final String FROM_TREE = "(from the family tree)";

    /**
     * {@code claims}: drafts that go, because every source they have is what is taken back. {@code thinned}: drafts, and claims the library
     * disputed by itself, that another source also gives, such as a second file or a record a research run found: they stay, without the
     * sources taken back. {@code kept}: what the person accepted, disputed or retired, or moved to another person with a split, and a claim
     * the library disputed by itself that nothing else gives. {@code replaced}: drafts a later copy of a file replaced, which stay on record.
     */
    /**
     * {@code files}: for a reset of one file, the files the words matched, each by its whole path, and the pages by their address; more
     * than one when the words are in several names, or when files in two folders have the same name. {@code folderOnly}: the plain reset,
     * which takes back only what was read from the files of {@code folders}, the folders the library read as a whole. {@code outside}: for
     * the plain reset, the drafts it keeps because no source of the family's reading they have comes back with a new read of the folders:
     * a Geni profile, a web page, a file outside the folders, a file of the folders read on its own, a file that is no longer there.
     * {@code back}: for the plain reset, the files a new read of the folders brings back ({@link FamilyReads#broughtBackByFolder}).
     */
    public record Plan(List<String> claims, int kept, int otherClaims, int waiting, String from, List<String> thinned, int replaced, List<String> files,
                       boolean folderOnly, List<String> folders, List<String> outside, Set<String> back, List<String> leftOver) {
        public Plan(List<String> claims, int kept, int otherClaims, int waiting, String from) { this(claims, kept, otherClaims, waiting, from, List.of(), 0, List.of()); }
        public Plan(List<String> claims, int kept, int otherClaims, int waiting, String from, List<String> thinned, int replaced) { this(claims, kept, otherClaims, waiting, from, thinned, replaced, List.of()); }
        public Plan(List<String> claims, int kept, int otherClaims, int waiting, String from, List<String> thinned, int replaced, List<String> files) {
            this(claims, kept, otherClaims, waiting, from, thinned, replaced, files, false, List.of(), List.of(), Set.of(), List.of());
        }

        /** Every draft of the family's reading: {@code genealogy reset --all}. */
        public boolean all() { return from.isEmpty() && !folderOnly; }

        /** Whether this reset takes back this source of a claim. The plain reset takes a file only when a new read of the folders brings it back. */
        boolean takes(Finding.Source s, Set<String> matched) {
            if (!folderOnly) return takenBack(s, from, matched);
            return takenBack(s, "", matched) && back.contains(FamilyReads.addressKey(s.locator()));
        }
    }
    public record Done(int claims, int nodes, int waiting, Path backup, int thinned) {
        public Done(int claims, int nodes, int waiting, Path backup) { this(claims, nodes, waiting, backup, 0); }
    }

    /** Every draft of the family's reading, from the folder, Geni, web pages and files elsewhere alike: {@code genealogy reset --all}. */
    public static Plan plan(LibraryStore store, List<String> writers) throws IOException { return plan(store, writers, ""); }

    /** {@code from}: only what came from the file or the page whose name or address has these words in it; "" for everything ({@code --all}). */
    public static Plan plan(LibraryStore store, List<String> writers, String from) throws IOException { return plan(store, writers, from, false); }

    /**
     * The plain {@code genealogy reset}: the drafts read from the files of the folders the library read as a whole, which the lists of
     * reads name, now and in the copies earlier resets kept. What came from Geni, web pages and files elsewhere stays ({@link Plan#outside}).
     */
    public static Plan folder(LibraryStore store, List<String> writers) throws IOException { return plan(store, writers, "", true); }

    private static Plan plan(LibraryStore store, List<String> writers, String from, boolean folderOnly) throws IOException {
        // the words are compared as the sources are: an address given percent-encoded, as a browser copies it, is the same page as its
        // address written in characters
        String want = from == null ? "" : plain(from.strip()).toLowerCase(Locale.ROOT);
        List<FamilyReads.Row> rows = folderOnly ? FamilyReads.everyRow(store) : List.of();
        List<String> folders = FamilyReads.folders(rows);
        Set<String> back = folderOnly ? FamilyReads.broughtBackByFolder(rows) : Set.of();
        Plan scope = new Plan(List.of(), 0, 0, 0, want, List.of(), 0, List.of(), folderOnly, folders, List.of(), back, List.of());
        List<Finding> all = store.scanFindings().findings();
        // one file: the words are matched against each file's own name, not the folders it is in, or against its whole path; a name that is
        // exactly the words is that file alone, and two files of that name in two folders are two files
        List<String> files = new ArrayList<>();
        if (!want.isEmpty()) {
            Set<String> exact = new LinkedHashSet<>(), part = new LinkedHashSet<>();
            for (Finding f : all) if (writers.contains(f.writer())) for (Finding.Source src : f.sources()) {
                String w = where(src).toLowerCase(Locale.ROOT), whole = path(src);
                if (w.equals(want) || key(whole).equals(key(want))) exact.add(whole); else if (w.contains(want)) part.add(whole);
            }
            files.addAll(exact.isEmpty() ? part : exact);
            // a list of web addresses: the facts its pages and Geni profiles gave cite them, never the list, so words that name a list take
            // back each page and profile it led to, and the list itself, so that a new read of its folder reads it again
            List<String> lists = listsNamed(store, want);
            if (!lists.isEmpty()) {
                Set<String> pages = pagesOf(store, lists);
                for (String l : lists) if (!files.contains(l)) files.add(l);
                // the owner's note beside a link names the page too, and stays: it is what the owner said, and the list brings it back
                for (Finding f : all) if (writers.contains(f.writer())) for (Finding.Source src : f.sources()) {
                    String whole = path(src);
                    if (!whole.startsWith("told://") && pages.contains(FamilyReads.addressKey(whole)) && !files.contains(whole)) files.add(whole);
                }
            }
            // a page whose facts are gone already, but whose other names still stand in the list of names (a read killed halfway; an
            // earlier reset that could not reach them): the record of where each other name came from names the page too
            if (files.isEmpty()) {
                Path als = Graph.aliasSourcesFile(store);
                if (Files.exists(als)) for (String line : Files.readAllLines(als, StandardCharsets.UTF_8)) {
                    String[] c = line.split("\t");
                    if (c.length < 3) continue;
                    Finding.Source src = new Finding.Source(c[2], "", "");
                    String w = where(src).toLowerCase(Locale.ROOT), whole = path(src);
                    if (w.equals(want) || key(whole).equals(key(want))) exact.add(whole); else if (w.contains(want)) part.add(whole);
                }
                files.addAll(exact.isEmpty() ? part : exact);
            }
        }
        Set<String> matched = matchedSet(files);
        List<String> go = new ArrayList<>(), thinned = new ArrayList<>(), outside = new ArrayList<>();
        int kept = 0, other = 0, replaced = 0;
        for (Finding f : all) {
            boolean ours = writers.contains(f.writer()) && (want.isEmpty() || f.sources().stream().anyMatch(x -> takenBack(x, want, matched)));
            if (!ours) { other++; continue; }
            if (f.state() == Finding.State.superseded) { replaced++; continue; }
            // what nobody can read in again stays: the owner's decisions, and what the owner told the library, which no read of a folder brings back.
            // A claim the library disputed by itself is not the owner's decision: the file is taken off it when another source still gives it,
            // and it stays disputed, as the library left it
            boolean library = f.setAsideBy() == Finding.SetAside.library;
            if ((f.state() != Finding.State.draft && !library) || FamilySplit.moved(f) != null) { kept++; continue; }
            if (f.sources().stream().noneMatch(x -> scope.takes(x, matched))) {
                // the plain reset keeps a draft that no new read of the folder brings back: from Geni, a web page, a file elsewhere, a file read on its own, a file no longer there
                if (folderOnly && f.sources().stream().anyMatch(x -> takenBack(x, "", matched))) outside.add(f.id()); else kept++;
                continue;
            }
            if (!f.sources().stream().allMatch(x -> scope.takes(x, matched))) thinned.add(f.id());
            else if (library) kept++;
            else go.add(f.id());
        }
        int waiting = 0;
        if (want.isEmpty() && !folderOnly) { for (Frontier.Line l : Frontier.read(store)) if (l.open() && l.kind().contains(FROM_TREE)) waiting++; }
        else waiting = waitingAbout(store, go).size();
        Plan plan = new Plan(go, kept, other, waiting, want, thinned, replaced, files, folderOnly, folders, outside, back, List.of());
        return new Plan(go, kept, other, waiting, want, thinned, replaced, files, folderOnly, folders, outside, back, leftOver(store, plan));
    }

    /** A line of the list of people, places, events and families: the id, the kind, the marks older versions wrote, the label, the other names, the Wikidata id. */
    private static final Pattern LINE = Pattern.compile("^- (.+?) — (person|place|event|family|value)((?:, (?:private|kept private|kept public|said to be living|said to have died))*): (.*?)( \\| also: .*?)?( \\| wikidata: .*)?$");

    /**
     * The entries of the list that nothing speaks of any more and that carry nothing of the owner's, which this reset takes out though none of
     * its facts mentions them: no fact names them, no joining, no Wikidata id, and for a person no other name but what the sources taken back
     * gave. A read that was stopped halfway writes its people and families before their facts, and a reset that could not tie them to its file
     * or page leaves them; a read that finds them again writes them again.
     */
    static List<String> leftOver(LibraryStore store, Plan plan) throws IOException {
        Path nodes = Graph.nodesFile(store);
        if (!Files.exists(nodes)) return List.of();
        Set<String> linked = linked(store), joined = Graph.merges(store).keySet(), fromFiles = matchedSet(plan.files());
        Map<String, Map<String, List<String>>> nameSources = aliasSources(store, FamilyPeople.view(store));
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(nodes, StandardCharsets.UTF_8)) {
            Matcher m = LINE.matcher(line);
            if (m.matches() && lineLeft(m, plan, linked, joined, nameSources, fromFiles) == null) out.add(m.group(4).strip());
        }
        return out;
    }

    /**
     * The entries a fact, checked or not, speaks of: under the name the fact writes ({@link FamilyPeople#unlinkedView}) and under the person the
     * evidence links that name to ({@link FamilyPeople#view}). A name in Latin letters the links join into a name in characters keeps its
     * line: the facts written under it are its own.
     */
    private static Set<String> linked(LibraryStore store) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        for (Graph g : List.of(FamilyPeople.view(store), FamilyPeople.unlinkedView(store))) for (Graph.Edge e : g.edges()) { out.add(e.from()); out.add(e.to()); }
        return out;
    }

    /**
     * A line of the list as this reset writes it back, or null when the entry goes. An entry nothing speaks of any more goes, whatever the size
     * of the reset, unless it carries the owner's own: another name with no source written down (researchzosho graph alias, or a merge that
     * stands) or one the owner told, or a Wikidata id (researchzosho graph link). For the folder, a person who stays through another source keeps
     * every other name: a name only the folder gave may be all that ties a Geni entry to the folder's person, and the new read of the folder finds
     * that person by it. A person the reset leaves with nothing else loses the other names the family's reading gave: for the folder, the ones
     * only files a new read brings back gave, as the list of where each came from says. A name joined into another person keeps its own line:
     * its claims are about that person, and an unmerge gives it back its kind.
     */
    private static String lineLeft(Matcher m, Plan plan, Set<String> linked, Set<String> joined, Map<String, Map<String, List<String>>> nameSources, Set<String> fromFiles) {
        String id = m.group(1).strip();
        boolean all = plan.all(), person = m.group(2).equals("person");
        String also = m.group(5) == null ? "" : m.group(5);
        if (person && !also.isEmpty() && plan.from().isEmpty() && !(plan.folderOnly() && linked.contains(id)))
            also = all ? ownersNames(also, nameSources.getOrDefault(id, Map.of())) : otherNamesLeft(also, nameSources.getOrDefault(id, Map.of()), plan.back());
        // one file or page: the other names only it gave go with its facts, linked or not. The read that follows gives them again if its
        // text still says so, and a name an older reading misfiled (the editor's name as the writer's) goes for good
        else if (person && !also.isEmpty() && !plan.from().isEmpty())
            also = otherNamesNotFrom(also, nameSources.getOrDefault(id, Map.of()), fromFiles);
        boolean stays = linked.contains(id) || joined.contains(id);
        if (!stays && (!person || also.isEmpty()) && m.group(6) == null) return null;
        return "- " + m.group(1) + " — " + m.group(2) + ": " + m.group(4) + also + (m.group(6) == null ? "" : m.group(6));
    }

    /**
     * The lists of web addresses the words name, by the list's own name or by its whole path, as a file is named; each by its path as the
     * list of reads writes it. A name that is exactly the words is that list alone. "" names none.
     */
    public static List<String> listsNamed(LibraryStore store, String from) throws IOException {
        String want = from == null ? "" : plain(from.strip()).toLowerCase(Locale.ROOT);
        if (want.isEmpty()) return List.of();
        Set<String> exact = new LinkedHashSet<>(), part = new LinkedHashSet<>();
        for (FamilyReads.Row r : FamilyReads.everyRow(store)) {
            String list = !r.list().isEmpty() ? r.list() : r.kind().equals(FamilyReads.LIST) || r.old() ? r.where() : "";
            if (list.isEmpty()) continue;
            String whole = plain(list), name = whole.substring(Math.max(whole.lastIndexOf('/'), whole.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
            boolean same = name.equals(want) || key(whole).equals(key(want));
            if (!same && !name.contains(want)) continue;
            // a line an older version wrote does not say it was a list: it is one when the file is still a list of addresses
            if (r.list().isEmpty() && !isList(r)) continue;
            (same ? exact : part).add(whole);
        }
        return new ArrayList<>(exact.isEmpty() ? part : exact);
    }

    /** Every page and Geni profile these lists led to, as {@link FamilyReads#addressKey}: as the list of reads writes them, and as each list still names them. */
    public static Set<String> pagesOf(LibraryStore store, List<String> lists) throws IOException {
        Set<String> named = new LinkedHashSet<>(), out = new LinkedHashSet<>();
        for (String l : lists) named.add(key(l));
        for (FamilyReads.Row r : FamilyReads.everyRow(store)) if (!r.list().isEmpty() && named.contains(key(plain(r.list())))) out.add(FamilyReads.addressKey(r.where()));
        for (String l : lists) {
            try {
                Path p = Path.of(l);
                if (Files.isRegularFile(p)) for (String a : GenealogyProfile.urlList(p)) out.add(FamilyReads.addressKey(a.split("\t")[0]));
            } catch (RuntimeException notAPath) { }
        }
        return out;
    }

    static Set<String> matchedSet(List<String> files) { Set<String> m = new LinkedHashSet<>(); for (String f : files) m.add(key(f)); return m; }

    /** Where a source is, as the owner names it: a file by its own name, without its folders; a page by its address. */
    static String where(Finding.Source s) {
        String path = path(s);
        return FamilyReads.isFile(path) ? path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1) : path;
    }

    /** A source's file by its whole path ("/home/me/aunt/tree.ged"), or a page by its address. */
    static String path(Finding.Source s) {
        String loc = plain(s.locator() == null ? "" : s.locator());
        return loc.startsWith("file:") ? FamilyReads.filePath(loc) : loc;
    }

    /** An address or a path with its %-escapes read: a file's name may have a % of its own ("100% sure notes.txt"), and what is no escape stays as written. */
    static String plain(String s) {
        try { return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8); } catch (IllegalArgumentException stray) { return s; }
    }

    /** A path or an address as it is compared: a Windows path with forward slashes, capitals as small letters. */
    static String key(String path) {
        String p = path.replace('\\', '/');
        return (p.matches("^[A-Za-z]:/.*") ? "/" + p : p).toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a reset takes this source back: for one file, a source that is one of the files the words matched; for a fresh start,
     * every source of the family's own material, and never a record a research run added to the claim, nor what the owner told the
     * library ({@code genealogy tell}, the Who is who page, a note beside a link), which is kept in the library and no read brings back.
     */
    static boolean takenBack(Finding.Source s, String from, Set<String> matched) {
        if (!from.isEmpty()) return matched.contains(key(path(s)));
        String loc = s.locator() == null ? "" : s.locator();
        if (loc.startsWith("told://") || TOLD_FILE.matcher(loc).find()) return false;
        String why = s.whyItMatters() == null ? "" : s.whyItMatters();
        return !why.matches("^(corroborates, from|cited by|said again in) I-\\d+.*");
    }

    /**
     * The claims a link the library made rests on: the ones its reason names after "The words are in", which wrote the person or the family
     * down; the whole reason when it names none. A fact that agrees, or a source that reads a family name, is a reason for the link, not
     * its words: a new read gives it back, and the link stays with the words.
     */
    static String words(String reason) {
        if (reason == null) return "";
        int at = reason.lastIndexOf("The words are in ");
        return at < 0 ? reason : reason.substring(at);
    }

    /** Whether a merge's reason cites one of these claims by its short code (F-0012). */
    static boolean citesAny(String reason, Set<String> codes) {
        if (codes.isEmpty() || reason == null) return false;
        Matcher m = Pattern.compile("F-\\d+").matcher(reason);
        while (m.find()) if (codes.contains(m.group())) return true;
        return false;
    }

    /** The file {@code genealogy tell} keeps what the owner told in, inside the library. */
    private static final Pattern TOLD_FILE = Pattern.compile("^file:.*[/\\\\]family[/\\\\]told-\\d{8}-\\d{6}\\.md$");

    /** The open questions `genealogy research` wrote about the people these claims are about: they were written from the facts going. */
    static List<String> waitingAbout(LibraryStore store, List<String> claims) throws IOException {
        Graph g = FamilyPeople.view(store);
        Set<String> people = new LinkedHashSet<>();
        for (String id : claims) {
            Finding f = store.finding(id);
            if (f == null || f.triple() == null) continue;
            for (boolean subject : new boolean[]{true, false}) { Graph.Node node = g.node(g.nodeOf(f, subject)); if (node != null && node.kind().equals("person")) people.add(node.label()); }
        }
        List<String> out = new ArrayList<>();
        for (Frontier.Line l : Frontier.read(store)) {
            if (!l.open() || !l.kind().contains(FROM_TREE)) continue;
            String t = Frontier.strip(l.text());
            for (String p : people) if (FamilyQuestions.about(t, p)) { out.add(l.text()); break; }
        }
        return out;
    }

    /**
     * Whether the plain reset forgets a line of the list of reads: a file of a folder read as a whole that a new read of the folder brings
     * back ({@code back}), so that the read reads it again. A list of web addresses stays read: its pages and Geni profiles stay in the
     * library, and a new read would fetch them all again. A file read on its own stays read, as its facts stay.
     */
    static boolean forgets(FamilyReads.Row r, Set<String> back) {
        if (r == null || !r.ofFolder() || !back.contains(FamilyReads.addressKey(r.where()))) return false;
        return !isList(r);
    }

    /** Whether a line of the list of reads is a list of web addresses. A line an older version wrote does not say: a list is a file whose every line is a web address. */
    private static boolean isList(FamilyReads.Row r) {
        if (r.kind().equals(FamilyReads.LIST)) return true;
        try { return r.old() && Files.isRegularFile(Path.of(r.where())) && !GenealogyProfile.urlList(Path.of(r.where())).isEmpty(); }
        catch (RuntimeException unreadable) { return false; }
    }

    /** How many lines of the list of reads the plain reset forgets ({@link #forgets}). */
    public static int forgets(LibraryStore store, Plan plan) throws IOException {
        int n = 0;
        for (FamilyReads.Row r : FamilyReads.rows(store)) if (forgets(r, plan.back())) n++;
        return n;
    }

    /** Whether the plain reset leaves a list of web addresses in these folders marked as read ({@link #forgets}). */
    public static boolean keepsAListRead(LibraryStore store, List<String> folders) throws IOException {
        for (FamilyReads.Row r : FamilyReads.rows(store)) if (r.ofFolder() && FamilyReads.folderOf(r.where(), folders) != null && isList(r)) return true;
        return false;
    }

    /** Where each person's other names came from ({@link Graph#aliasSources}), read once for every person: node → other name → sources. */
    private static Map<String, Map<String, List<String>>> aliasSources(LibraryStore store, Graph before) throws IOException {
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        Path f = Graph.aliasSourcesFile(store);
        if (!Files.exists(f)) return out;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t");
            if (p.length < 3) continue;
            out.computeIfAbsent(before.nodeIdOf(p[0]), k -> new LinkedHashMap<>()).computeIfAbsent(Vocabulary.norm(p[1]), k -> new ArrayList<>()).add(p[2]);
        }
        return out;
    }

    /**
     * A person's " | also: …" without the other names that only files a new read of the folders brings back ({@code back}) gave: another
     * name with no source written down, or with another source (Geni, a web page, a file elsewhere or read on its own), stays. "" when none
     * is left.
     */
    private static String otherNamesLeft(String also, Map<String, List<String>> sources, Set<String> back) {
        List<String> keep = new ArrayList<>();
        for (String a : Vocabulary.names(also.replaceFirst("^ \\| also: ", ""))) {
            List<String> from = sources.getOrDefault(Vocabulary.norm(a), List.of());
            boolean onlyFolder = !from.isEmpty() && from.stream().allMatch(src -> back.contains(FamilyReads.addressKey(src)));
            if (!onlyFolder) keep.add(a);
        }
        return keep.isEmpty() ? "" : " | also: " + Vocabulary.list(keep);
    }

    /** A person's " | also: …" without the other names that only the files or pages being taken back gave. "" when none is left. */
    private static String otherNamesNotFrom(String also, Map<String, List<String>> sources, Set<String> files) {
        List<String> keep = new ArrayList<>();
        for (String a : Vocabulary.names(also.replaceFirst("^ \\| also: ", ""))) {
            List<String> from = sources.getOrDefault(Vocabulary.norm(a), List.of());
            boolean onlyThese = !from.isEmpty() && from.stream().allMatch(src -> files.contains(key(path(new Finding.Source(src, "", "")))));
            if (!onlyThese) keep.add(a);
        }
        return keep.isEmpty() ? "" : " | also: " + Vocabulary.list(keep);
    }

    /**
     * A person's " | also: …" with only what the owner gave: another name with no source written down (researchzosho graph alias, or a merge
     * that stands), or one a source no reset takes gave, such as what the owner told. "" when none is left.
     */
    private static String ownersNames(String also, Map<String, List<String>> sources) {
        List<String> keep = new ArrayList<>();
        for (String a : Vocabulary.names(also.replaceFirst("^ \\| also: ", ""))) {
            List<String> from = sources.getOrDefault(Vocabulary.norm(a), List.of());
            if (from.isEmpty() || from.stream().anyMatch(src -> !takenBack(new Finding.Source(src, "", ""), "", Set.of()))) keep.add(a);
        }
        return keep.isEmpty() ? "" : " | also: " + Vocabulary.list(keep);
    }

    // ── a reset that finishes itself ─────────────────────────────────────────────────────────────────────────────────────

    /** What a reset writes in its copy folder before it changes anything, and the mark it leaves there when it has finished. */
    static final String JOURNAL = "reset.json", FINISHED = "finished";
    private static final String LOCK = "family-reset";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Takes out what the plan says. It saves a copy of everything it changes first, and before it changes anything it writes down in that
     * copy what it is going to do: a reset that was stopped halfway (the computer went off, the command was interrupted) is finished by
     * the next genealogy command ({@link #finishStopped}).
     */
    public static Done apply(LibraryStore store, Plan plan) throws IOException {
        return store.locked(LOCK, () -> {
            Path backup = begin(store, plan);
            return run(store, backup, journal(backup), false);
        });
    }

    /** A new copy folder, with the journal of what the reset is going to do in it: the plan, and what its steps need from the library as it is now. */
    static Path begin(LibraryStore store, Plan plan) throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path backup = store.root().resolve("family").resolve("backup-" + stamp);
        for (int n = 2; Files.exists(backup); n++) backup = store.root().resolve("family").resolve("backup-" + stamp + "-" + n);   // two resets in one second keep two copies
        Files.createDirectories(backup.resolve("findings"));
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("started", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));
        j.put("from", plan.from()); j.put("folderOnly", plan.folderOnly());
        j.put("claims", plan.claims()); j.put("thinned", plan.thinned()); j.put("files", plan.files()); j.put("folders", plan.folders());
        j.put("outside", plan.outside()); j.put("back", plan.back()); j.put("leftOver", plan.leftOver());
        j.put("kept", plan.kept()); j.put("otherClaims", plan.otherClaims()); j.put("waiting", plan.waiting()); j.put("replaced", plan.replaced());
        // for one file and for the folder, the waiting questions about the people their facts were about go with the facts, and are written
        // again from what stays; a reset of everything takes every question written from the family tree
        j.put("waitingLines", plan.all() ? List.of() : waitingAbout(store, plan.claims()));
        // for one file or page, the other names it gave go with its facts, and a person it alone named goes with them
        j.put("nameSources", aliasSources(store, FamilyPeople.view(store)));
        AtomicWrite.text(backup.resolve(JOURNAL), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(j) + "\n");
        return backup;
    }

    /** What a journal says: the plan, and what the steps needed from the library before anything changed. */
    record Journal(Plan plan, List<String> waitingLines, Map<String, Map<String, List<String>>> nameSources, String started) { }

    static Journal journal(Path backup) throws IOException {
        JsonNode n = JSON.readTree(Files.readString(backup.resolve(JOURNAL), StandardCharsets.UTF_8));
        Plan p = new Plan(strings(n, "claims"), n.path("kept").asInt(), n.path("otherClaims").asInt(), n.path("waiting").asInt(), n.path("from").asText(""),
                strings(n, "thinned"), n.path("replaced").asInt(), strings(n, "files"), n.path("folderOnly").asBoolean(), strings(n, "folders"),
                strings(n, "outside"), new LinkedHashSet<>(strings(n, "back")), strings(n, "leftOver"));
        Map<String, Map<String, List<String>>> names = n.has("nameSources") ? JSON.convertValue(n.get("nameSources"), new TypeReference<Map<String, Map<String, List<String>>>>() { }) : null;
        return new Journal(p, strings(n, "waitingLines"), names == null ? Map.of() : names, n.path("started").asText(""));
    }

    private static List<String> strings(JsonNode n, String field) { List<String> out = new ArrayList<>(); for (JsonNode x : n.path(field)) out.add(x.asText()); return out; }

    /** The copy folders of resets that were stopped before they had finished, the oldest first. */
    static List<Path> stopped(LibraryStore store) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path b : FamilyReads.backups(store)) if (Files.exists(b.resolve(JOURNAL)) && !Files.exists(b.resolve(FINISHED))) out.add(0, b);
        return out;
    }

    /**
     * Finishes each reset that was stopped before it had finished, the oldest first: the steps its journal names, done again from the start,
     * each skipping what is done already. What to tell the person; "" when there was none. A reset still going in another program holds
     * the lock: this waits for it, and then finds it finished.
     */
    public static String finishStopped(LibraryStore store) {
        try { if (stopped(store).isEmpty()) return ""; } catch (IOException unreadable) { return ""; }
        List<String> said = new ArrayList<>();
        try {
            store.locked(LOCK, () -> {
                for (Path b : stopped(store)) {
                    String when = FamilyReads.day(b), copy = b.toAbsolutePath().normalize().toString();
                    try {
                        Journal j = journal(b);
                        run(store, b, j, true);
                        said.add("A reset " + what(j.plan()) + " was stopped on " + when + " before it had finished. It is finished now. The copy it saved first is in " + copy + ".");
                    } catch (IOException | RuntimeException e) {
                        said.add("A reset that was stopped on " + when + " before it had finished could not be finished (" + e.getMessage() + "). Everything it changed is saved in " + copy + ".");
                    }
                }
                return null;
            });
        } catch (IOException e) { return ""; }
        return String.join("\n", said);
    }

    private static String what(Plan p) {
        if (!p.from().isEmpty()) return "of what the library read from " + (p.files().size() == 1 ? p.files().get(0) : "\"" + p.from() + "\"");
        return p.folderOnly() ? "of what the library read from your folder" : "of everything the library read for your family";
    }

    /** A copy that is whole or not there, and never made over the copy an earlier start of the same reset made: that one is the library as it was. */
    private static void copyOnce(Path from, Path to) throws IOException {
        if (from == null || to == null || !Files.exists(from) || Files.exists(to)) return;
        AtomicWrite.copy(from, to);
    }

    /**
     * The steps of a reset, in order. Each skips what is done already, and a rewritten file replaces the old one whole, so a reset that was
     * stopped anywhere and is run again from the start finishes as if it had never stopped.
     */
    static Done run(LibraryStore store, Path backup, Journal journal, boolean resumed) throws IOException {
        Plan plan = journal.plan();
        Path nodes = Graph.nodesFile(store), ledger = store.root().resolve("family").resolve("read-files.tsv");
        copyOnce(nodes, backup.resolve("nodes.md"));
        copyOnce(ledger, backup.resolve("read-files.tsv"));
        LibrarianIndex index = new LibrarianIndex(store);
        Set<String> matched = matchedSet(plan.files());
        boolean all = plan.all();
        List<String> waitingLines = journal.waitingLines();
        Map<String, Map<String, List<String>>> nameSources = journal.nameSources();
        Set<String> fromFiles = matchedSet(plan.files());
        int removed = 0;
        for (String id : plan.claims()) {
            Path p = LibraryStore.under(store.findingsDir(), id + ".md");
            if (p == null || !Files.exists(p)) continue;
            copyOnce(p, backup.resolve("findings").resolve(p.getFileName()));
            Files.delete(p);
            try { index.remove(id); } catch (IOException ignored) { }
            removed++;
        }
        Changes.append(store, "finding", "family reader", "removed", removed + " draft claim(s) of the family reader, for a fresh read"
                + (resumed ? ", finishing a reset that was stopped" : "") + "; a copy is in " + store.root().relativize(backup));
        // a claim another source also gives stays, without the sources taken back and the notes that said they were added
        int thinned = 0;
        for (String id : plan.thinned()) {
            Path p = LibraryStore.under(store.findingsDir(), id + ".md");
            if (p == null) continue;
            Path copy = backup.resolve("findings").resolve(p.getFileName());
            copyOnce(p, copy);
            // the claim as it was before this reset, from the copy: whole even when a stop cut the last write of it short
            Finding f = null;
            if (Files.exists(copy)) try { f = Finding.parse(Files.readString(copy, StandardCharsets.UTF_8)); } catch (RuntimeException unreadable) { }
            if (f == null) f = store.finding(id);
            if (f == null) continue;
            List<Finding.Source> rest = new ArrayList<>();
            List<String> gone = new ArrayList<>();
            for (Finding.Source src : f.sources()) if (plan.takes(src, matched)) gone.add(src.locator()); else rest.add(src);
            if (rest.isEmpty() || gone.isEmpty()) continue;
            List<Finding.Note> notes = new ArrayList<>();
            for (Finding.Note n : f.notes()) if (!(n.kind().equals("source-added") && gone.stream().anyMatch(n.text()::contains))) notes.add(n);
            notes.add(new Finding.Note("source-removed", "genealogy reset", LocalDate.now().toString(), "taken back for a fresh read: " + String.join(", ", gone) + "; the claim stays, because another source gives it"));
            Finding now = new Finding(f.id(), f.title(), f.subjects(), f.state(), f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(), f.volatility(), f.reviewBy(),
                    rest, f.supersedes(), f.review(), f.body(), f.triple(), notes);
            store.write(now);
            try { index.upsert(now); } catch (IOException ignored) { }
            thinned++;
        }
        // joinings the family reader made ("this name is that person") outlive the facts and would hand the next read's people to the wrong person.
        // A person written only by a family name that the library linked to somebody (FamilyMentions) goes back with a reset of everything, and
        // with one file or the folder when the words the link rests on go with them. The plain reset keeps what Geni joined, as it keeps Geni's
        // facts. What you joined with genealogy tidy, by saying yes, stays through every reset
        boolean oneGeni = !plan.folderOnly() && plan.from().contains("geni");
        Set<String> by = all ? Set.of("geni", FamilyMentions.BY) : oneGeni ? Set.of("geni") : Set.of();
        Set<String> goneCodes = new LinkedHashSet<>();
        if (!all) for (String id : plan.claims()) goneCodes.add(FamilyMentions.code(id));
        Path merges = Graph.mergesFile(store);
        if ((!by.isEmpty() || !goneCodes.isEmpty()) && Files.exists(merges)) {
            copyOnce(merges, backup.resolve("merges.tsv"));
            // each is taken back by a line of its own, so the file still tells what was joined and when
            Map<String, String> active = Graph.merges(store);
            for (String l : Files.readAllLines(merges, StandardCharsets.UTF_8)) {
                String[] c = l.split("\t");
                if (c.length < 3 || !c[1].equals(active.get(c[0]))) continue;
                boolean cites = c[2].equals(FamilyMentions.BY) && c.length >= 5 && citesAny(words(c[4]), goneCodes);
                if (by.contains(c[2]) || cites) { Graph.takeBack(store, c[0], c[1], "genealogy reset", "a fresh start for what the family reader wrote", c[0]); active.remove(c[0]); }
            }
        }
        if (all || oneGeni) {
            Path geniPeople = store.root().resolve("family").resolve("geni-people.tsv");
            copyOnce(geniPeople, backup.resolve("geni-people.tsv"));
            Files.deleteIfExists(geniPeople);
        }
        if (all) Files.deleteIfExists(ledger);
        else if (Files.exists(ledger)) {
            // one file: the list of reads forgets the files matched, by their whole paths, and no other file of the same name or in the same
            // folder. The folder: it forgets every file in the folders, so a new read of them reads each again, but a list of web addresses,
            // whose pages and Geni profiles stay, is still read, and nothing outside the folders is forgotten
            Set<String> matchedAddresses = new LinkedHashSet<>();
            for (String f : plan.files()) matchedAddresses.add(FamilyReads.addressKey(f));
            List<String> keepLines = new ArrayList<>();
            for (String l : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
                String path = l.contains("\t") ? l.substring(l.lastIndexOf('\t') + 1) : l;
                boolean forget = plan.folderOnly() ? forgets(FamilyReads.parse(l), plan.back()) : matched.contains(key(path)) || matchedAddresses.contains(FamilyReads.addressKey(path));
                if (!forget) keepLines.add(l);
            }
            AtomicWrite.lines(ledger, keepLines);
        }
        // the waiting questions were written from those facts: they go, and the next `genealogy research` writes them again from what the fresh read finds
        int waiting = 0;
        Path open = store.frontierFile();
        if ((all || !waitingLines.isEmpty()) && Files.exists(open)) {
            copyOnce(open, backup.resolve("waiting-questions.md"));
            Set<String> these = new LinkedHashSet<>(waitingLines);
            // read and written under one lock, so a question another program adds meanwhile is not lost
            waiting = store.locked("frontier", () -> {
                int n = 0;
                List<String> keep = new ArrayList<>();
                for (String line : Files.readAllLines(open, StandardCharsets.UTF_8)) {
                    Matcher lm = Frontier.LINE.matcher(line);
                    boolean waitingLine = line.contains("[person ") && lm.matches() && Fields.unmarked(lm.group(2).replace("·parked", "")).endsWith(FROM_TREE) && !line.contains(" ⇒ explored ");
                    if (waitingLine && (all || (lm.matches() && these.contains(lm.group(3))))) n++; else keep.add(line);
                }
                AtomicWrite.lines(open, keep);
                return n;
            });
        }
        // the people, places, events and families nothing speaks of any more go too, whatever the size of the reset ({@link #lineLeft}): what
        // this reset's facts alone mentioned, and what a read that was stopped or an earlier reset that could not reach them left
        Set<String> linked = linked(store), joined = Graph.merges(store).keySet();
        int gone = 0;
        if (Files.exists(nodes)) {
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(nodes, StandardCharsets.UTF_8)) {
                // the words older versions wrote after the kind are read and left out of the line written back
                Matcher m = LINE.matcher(line);
                if (!m.matches()) { out.add(line); continue; }
                String left = lineLeft(m, plan, linked, joined, nameSources, fromFiles);
                if (left == null) { gone++; continue; }
                out.add(left);
            }
            // written beside the file and read back from there, so the list itself is only ever replaced whole
            Path beside = nodes.resolveSibling("." + nodes.getFileName() + ".reset");
            Files.write(beside, out, StandardCharsets.UTF_8);
            try { Graph.writeNodes(store, Vocabulary.read(beside)); }   // with today's heading, as every other write of the file has
            finally { Files.deleteIfExists(beside); }
        }
        // one file or page: the record of where each other name came from forgets what it gave, as the list of people did; the read that
        // follows writes down again the names it still gives
        Path als = Graph.aliasSourcesFile(store);
        if (!plan.folderOnly() && !plan.from().isEmpty() && Files.exists(als)) {
            copyOnce(als, backup.resolve("alias-sources.tsv"));
            List<String> keepAliases = new ArrayList<>();
            for (String line : Files.readAllLines(als, StandardCharsets.UTF_8)) {
                String[] c = line.split("\t");
                if (c.length >= 3 && fromFiles.contains(key(path(new Finding.Source(c[2], "", ""))))) continue;
                keepAliases.add(line);
            }
            AtomicWrite.lines(als, keepAliases);
        }
        Files.writeString(backup.resolve(FINISHED), LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + "\n", StandardCharsets.UTF_8);
        return new Done(removed, gone, waiting, backup, thinned);
    }
}
