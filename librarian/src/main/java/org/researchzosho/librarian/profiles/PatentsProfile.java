package org.researchzosho.librarian.profiles;

import org.researchzosho.librarian.Chrome;
import org.researchzosho.librarian.LibraryProtocol;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.PatentsPage;
import org.researchzosho.librarian.Patrons;
import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.ProtocolError;
import org.researchzosho.librarian.Vocabulary;
import org.researchzosho.records.Ops;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The patents field (0.6.6): prior-art search across the offices, and a patent read by its number: claims, dates, assignee, family,
 * status. What it adds is the offices' own records (the EPO's Open Patent Services with its free key: a class enumerated whole, the
 * abstracts read, the claims read from the office's full text; Google Patents through SerpApi for other runs), and the way a patent claim
 * is judged: from the publication itself. A run of this field is offered the patent instruments and collections; any other run is
 * untouched. Joins on its own when a question is plainly about patents, or when asked with --field patents.
 */
public final class PatentsProfile implements Profile {
    @Override public String name() { return "patents"; }
    @Override public String goalName() { return "prior-art"; }
    @Override public String conventionName() { return "patents"; }
    @Override public String description() { return "patents and applications: prior-art search across the offices, and a patent read by its number: claims, dates, assignee, family, status"; }
    // ── the Patents page and the chat's /patents ────────────────────────────────────────────────────────────

    /** The page's link stands on the rail once the office's key is set: without it the page can only say how to get one, and it is reached by its address. */
    @Override public List<Link> menu() { return Ops.keyed() ? List.of(new Link("/patents", "Patents", false, "℗")) : List.of(); }
    @Override public List<String> pagePaths() { return List.of("/patents"); }

    @Override public Page page(LibraryStore store, Patrons.Patron patron, String path, String method, Map<String, String> query, Map<String, String> form) throws IOException {
        if (!path.equals("/patents")) return null;
        Patrons.check(store, patron, Patrons.Level.read);
        boolean writer;
        try { Patrons.check(store, patron, Patrons.Level.write); writer = true; } catch (RuntimeException e) { writer = false; }
        if ("POST".equals(method)) {
            Patrons.check(store, patron, Patrons.Level.write);
            String op = form.getOrDefault("op", "pool");
            String said;
            try { said = new LibraryProtocol(store).patents(PatentsPage.args(op, form, patron, "web")).path("summary").asText(); } catch (ProtocolError e) { said = e.getMessage(); }
            String title = op.equals("abstracts") ? "Abstracts read" : op.equals("claims") ? "Claims read" : "Patents of the class";
            return new Page(Chrome.now().t(title), "<p>" + PatentsPage.esc(said).replace("\n", "<br>") + "</p><p><a href=\"/patents\">" + Chrome.now().t("Back to the Patents page") + "</a></p>", false, null);
        }
        return new Page(Chrome.now().t("Patents"), PatentsPage.body(store, writer), false, null);
    }

    @Override public List<String> chatCommandWords() { return List.of("/patents"); }
    @Override public String chatCommandHelp() { return "/patents (prior art from the patent office's own records: pool <classes> <from>-<to>: <feature, its words; feature, its words> enumerates every patent of the classes in the years; abstracts [the pool's key] reads their abstracts for the features; claims [the pool's key, or patent numbers] reads the claims and quotes each feature's closest)"; }

    /** "2005-2015" in a /patents pool line. */
    private static final Pattern YEARS = Pattern.compile("\\b((?:18|19|20)\\d{2})\\s*(?:-|–|to)\\s*((?:18|19|20)\\d{2})\\b");
    private static final String POOL_HELP = "Type /patents pool, the classification symbols, the years, a colon, and the invention's features separated by semicolons, each with the words that would name it: /patents pool A47G19/22 2005-2015: lid, cover, cap; straw, drinking tube. The library then lists every patent family of that class in those years, the ones that name more of the features first, and ends with a key: /patents abstracts <that key> reads their abstracts next.";

    @Override public String chatReply(LibraryStore store, Patrons.Patron patron, String chat, String line) throws IOException {
        if (line == null) return null;
        String l = line.strip();
        if (!(l.equals("/patents") || l.startsWith("/patents "))) return null;
        String rest = l.length() > 8 ? l.substring(8).strip() : "";
        String[] head = rest.split("\\s+", 2);
        String verb = head[0], tail = head.length > 1 ? head[1].strip() : "";
        Map<String, String> form = new LinkedHashMap<>();
        switch (verb) {
            case "pool" -> {
                int colon = tail.indexOf(':');
                if (tail.isEmpty() || colon < 0) return POOL_HELP;
                String before = tail.substring(0, colon);
                Matcher y = YEARS.matcher(before);
                if (y.find()) { form.put("from_year", y.group(1)); form.put("to_year", y.group(2)); before = before.replace(y.group(0), " "); }
                form.put("classes", before.strip().replaceAll("\\s*,\\s*|\\s{2,}", ","));
                form.put("features", tail.substring(colon + 1).strip());
            }
            case "abstracts" -> { if (!tail.isEmpty()) form.put("from", tail); }
            case "claims" -> { if (tail.matches("(\\d{4}-\\d{2}-\\d{2}-)?[0-9a-f]{16}")) form.put("from", tail); else if (!tail.isEmpty()) form.put("publications", tail); }
            case "" -> { return POOL_HELP + " /patents claims <patent numbers> reads what a numbered patent claims."; }
            default -> { return "That is not one of the patents commands. They are: /patents pool <classes> <from>-<to>: <feature, its words; feature, its words>, /patents abstracts <the pool's key>, /patents claims <the pool's key, or patent numbers>."; }
        }
        if (!patron.person() && !Patrons.mayWrite(store, patron)) return "Reading patents runs the library's model and the patent office's allowance, so it is for those who may write to this library.";
        try { return new LibraryProtocol(store).patents(PatentsPage.args(verb, form, patron, "chat")).path("summary").asText(); } catch (ProtocolError e) { return e.getMessage(); }
    }

    @Override public List<Vocabulary.Term> predicates() {
        return List.of(
                new Vocabulary.Term("has-inventor", "has the inventor", List.of("inventor", "発明者"), ""),
                new Vocabulary.Term("assigned-to", "assigned to", List.of("assignee", "applicant", "owned by", "出願人", "権利者"), ""),
                new Vocabulary.Term("filed-on", "filed on", List.of("filing date", "出願日"), ""),
                new Vocabulary.Term("published-on", "published on", List.of("publication date", "公開日"), ""),
                new Vocabulary.Term("granted-on", "granted on", List.of("grant date", "issued on", "登録日"), ""),
                new Vocabulary.Term("claims-priority-to", "claims priority to", List.of("priority of", "優先権"), ""),
                new Vocabulary.Term("same-family-as", "in the same family as", List.of("counterpart of", "パテントファミリー"), ""),
                new Vocabulary.Term("cites-prior-art", "cites as prior art", List.of("cited reference", "引用文献"), ""),
                new Vocabulary.Term("classified-as", "classified as", List.of("cpc class", "ipc class", "分類"), ""),
                new Vocabulary.Term("expires-on", "expires on", List.of("expiry", "存続期間満了"), ""),
                new Vocabulary.Term("has-status", "has the status", List.of("legal status", "権利状態"), ""));
    }

    /**
     * Words that mean a patent question, each as a whole word. Not the bare verb ("what did she patent"): that is a person's doing, and the
     * family-history field's when she is known. Not 発明 or 出願 alone either: the one names an invention of history (電話を発明したのは誰), the
     * other an application to a university.
     */
    private static final List<String> WORDS = List.of(
            "patents", "patented", "patentable", "a patent", "the patent", "patent for", "patent on", "patent application", "patent claim", "patent number", "prior art", "prior-art", "uspto", "espacenet", "patentscope", "j-platpat", "patent office",
            "provisional application", "pct application", "freedom to operate", "claim 1", "independent claim", "cpc class",
            "特許", "特許出願", "出願番号", "出願人", "出願公開", "先行技術", "実用新案", "意匠登録", "特許庁", "請求項", "職務発明", "発明の名称", "新規性", "進歩性");
    /** The words of the Latin script, each as a whole word: "patents" is not in "patents' leather"; the words of a script without spaces are matched as they are. */
    private static final List<Pattern> LATIN = WORDS.stream().filter(w -> w.codePoints().noneMatch(c -> c >= 0x2E80)).map(w -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(w) + "(?![\\p{L}\\p{N}])")).toList();
    private static final List<String> OTHER_SCRIPTS = WORDS.stream().filter(w -> w.codePoints().anyMatch(c -> c >= 0x2E80)).toList();
    /** A word that is a patent in spelling only: the leather, the adverb. */
    private static final Pattern NOT_THESE = Pattern.compile("patent[- ]leather|patently");
    /** A publication number as an office writes it: US12450652B1, EP1000000, WO2024123456, JP2020123456A. */
    private static final Pattern NUMBER = Pattern.compile("\\b(US|EP|WO|JP|CN|KR|DE|GB|FR|CA|AU)\\s?\\d{6,12}\\s?[A-C]?\\d?\\b");
    @Override public boolean applies(String question) {
        String q = NOT_THESE.matcher(Vocabulary.norm(question)).replaceAll(" ");
        for (Pattern p : LATIN) if (p.matcher(q).find()) return true;
        for (String w : OTHER_SCRIPTS) if (q.contains(w)) return true;
        return NUMBER.matcher(question).find();
    }
    @Override public boolean wantsRecords() { return true; }
    @Override public String register() {
        return "Prior art is found by enumerating a class from the office's own records, never from a search engine's first page: patents takes the "
                + "classification symbols, the years and the invention's features and enumerates every publication of each class through the European "
                + "Patent Office's Open Patent Services; patent_abstracts reads the pool's abstracts; patent_claims reads the claims from the office's own "
                + "full text, for a pool by its key or for a patent by its number (publications), and prints its classes, its day, its applicant and the "
                + "member of its family that was read; where the office's service holds no claims text, as for the United States, China, Japan and Korea, "
                + "it reads the claims from Google Patents' copy of the office's record and says so. A patent is read with patent_claims and never "
                + "by opening its page yourself. By a person's or a firm's name the office's collections epo-inventor and epo-applicant "
                + "search (record_search). A provisional application is never published: it shows as the "
                + "priority claim of a later application, and its text is in that application's file wrapper at the USPTO. An application filed "
                + "with a non-publication request is unseen until grant: its assignment record is public even so (https://assignment.uspto.gov/patent/ "
                + "by assignee), and any foreign family publishes at eighteen months in Espacenet or PATENTSCOPE. Japan publishes every "
                + "application at eighteen months; J-PlatPat (https://www.j-platpat.inpit.go.jp/) reads one by number. For each patent note its "
                + "number, office, filing and publication or grant dates, assignee, and status when read.";
    }
    @Override public String planRules() {
        return "This is a question about patents. " + ONE_CHAIN + " When the question names a patent by number, one sub-question reads that patent "
                + "itself with patent_claims and its number.";
    }

    /**
     * Prior art is one chain judged whole: a run asked whether a folding dog bowl was patented split it into eight sub-questions, one found the
     * class, another searched it and read its first twenty records, and nobody enumerated it or read a claim (2026-11-05).
     */
    public static final String ONE_CHAIN = "When it asks whether something was already patented or invented, or for the prior art of an invention, "
            + "this is the exception to splitting by facet: plan one sub-question carrying the whole ask, and one only, since the class, the pool, the "
            + "abstracts and the claims are one chain (find the class, then patents, then patent_abstracts, then patent_claims) and two workers would "
            + "enumerate and read the same pool twice. The pool comes from patents, never from a search: it is called with the classification symbols, "
            + "the years and the invention's features, each a short phrase with the words that would name it in a title or an abstract, such as "
            + "{\"classes\": [\"A47G19/22\"], \"from_year\": 2005, \"to_year\": 2015, \"features\": [\"lid, cover, cap\", \"straw, drinking tube\"]}, and it "
            + "enumerates every publication of the class in those years from the patent office's own records.";

    @Override public String criticRules() {
        return "For a patent question the evidence is enough when the pool was enumerated from the office's own records by class and years (patents), "
                + "its abstracts were read (patent_abstracts) and the claims of the families that pass were read and quoted (patent_claims). A gap worth "
                + "another round names what the last call said was not read, to go on with the same key and page=2, or another class, a subgroup or "
                + "other years to enumerate; never a search engine.";
    }
    @Override public String writerRules() {
        return "Open with each feature's closest claim, quoted with its number and the office's link, or that none that was read meets it. Give the "
                + "counts as the readings said them: the office's own count, the families enumerated, the abstracts read, the claims read; and what was "
                + "not read. Then the patents in a table: number, title, applicant, the earliest date as its line gives it, the claim that matters, why "
                + "it fits; oldest first; a date or an applicant no line gives is left out. Say of each quoted claim where it was read, the office's full text or Google Patents' copy of the office's record. "
                + "A status such as pending or granted is given only in the words of the page that words it, as that page's own reading, never as a "
                + "fact of the report. Say plainly that unpublished applications cannot be seen, and what their tracks showed if any.";
    }
    @Override public String extractionRule() {
        return "A claim about a patent carries its publication number as the office writes it and the date of the publication it was read from.";
    }
}
