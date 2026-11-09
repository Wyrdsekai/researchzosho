package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.Config;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PubMed and PubMed Central, through NCBI's E-utilities: PubMed's articles by best match with their abstracts, the open articles of
 * PubMed Central searched inside their full text, and an article's full text read from its JATS XML into text with its section headings.
 *
 * <p>Why the E-utilities and not Europe PMC's REST API, which also hands out the open articles' XML: PubMed Central's own developer page,
 * read on 2026-11-04, names the E-utilities among the only services that may be used to retrieve its content by program; efetch gives the
 * same JATS XML Europe PMC does, so the full text is no simpler there; NCBI's optional key and its rate (three requests a second from one
 * address, ten with a key) are written on NLM's own support page; and Europe PMC's documentation refused the library's reader (403) that
 * day, so its terms could not be read. Every request carries {@code tool=researchzosho} and no e-mail address, and the requests of the
 * whole program are paced to NCBI's rate.
 */
public final class PubMed {
    private PubMed() { }

    static final ObjectMapper M = new ObjectMapper();
    public static final String KEY = "RESEARCHZOSHO_NCBI_KEY";
    /** The name the tally counts these calls under. */
    public static final String TALLY = "PubMed";
    /** E-utilities' address; a test points it at its own server. */
    public static final Map<String, String> BASE = new HashMap<>();
    static { BASE.put("eutils", "https://eutils.ncbi.nlm.nih.gov/entrez/eutils"); }
    /** Searches and article reads that NCBI answered, session-wide. */
    public static final AtomicInteger USED = new AtomicInteger();

    public static boolean keySet() { String k = Config.get(KEY); return k != null && !k.isBlank(); }

    // ---------------------------------------------------------------- pacing

    /** When the next request may go; a test sets it back. */
    static long nextAt = 0;
    /** The gap between two requests without a key and with one, in milliseconds; a test sets them to nothing. */
    static volatile long GAP_FREE = 350, GAP_KEYED = 110;

    /** How long the next request waits: NCBI takes three requests a second from one address without a key and ten with one, so the program keeps under that. */
    static synchronized long slot(long now, boolean keyed) {
        long gap = keyed ? GAP_KEYED : GAP_FREE;
        long at = Math.max(now, nextAt);
        nextAt = at + gap;
        return at - now;
    }

    /** One E-utilities call: its name (esearch, esummary, efetch) and parameters; the body, or null when NCBI did not answer. A 429 is waited out once. */
    static String eutil(String name, String params, Duration limit) {
        String key = Config.get(KEY);
        boolean keyed = key != null && !key.isBlank();
        String url = BASE.get("eutils") + "/" + name + ".fcgi?" + params + "&tool=researchzosho" + (keyed ? "&api_key=" + URLEncoder.encode(key.strip(), StandardCharsets.UTF_8) : "");
        for (int attempt = 0; attempt < 2; attempt++) {
            PlaceHttp.sleep(slot(System.currentTimeMillis(), keyed));
            PlaceHttp.Res r = PlaceHttp.get(url, Map.of("Accept", "application/json, text/xml"), limit, "NCBI's E-utilities");
            if (r.status() == 429 && attempt == 0) { PlaceHttp.sleep(1100); continue; }
            return r.ok() ? r.body() : null;
        }
        return null;
    }

    // ---------------------------------------------------------------- search

    /**
     * One article: its title, the address it opens at (PubMed Central's when the library can read the full text there, else PubMed's), what it
     * is, and {@code open}: whether PubMed Central gives its full text to programs (true), keeps it from them at the publisher's wish though a
     * person may read it on its site (false), or was not asked (null).
     */
    public record Row(String title, String address, String journal, String year, List<String> authors, List<String> types, String abstractText,
                      String pmid, String pmcid, String doi, Boolean open) {
        public Row {
            authors = authors == null ? List.of() : List.copyOf(authors);
            types = types == null ? List.of() : List.copyOf(types);
        }
        Row opened(Boolean o) { return new Row(title, o != null && !o ? pubmedAddress(pmid) : address, journal, year, authors, types, abstractText, pmid, pmcid, doi, o); }
    }

    /** What a search found: the rows in their order, how many matched in all, and whether NCBI answered at all. */
    public record Found(List<Row> rows, int count, boolean answered) { }

    public static String pmcAddress(String pmcid) { return "https://pmc.ncbi.nlm.nih.gov/articles/" + (pmcid.startsWith("PMC") ? pmcid : "PMC" + pmcid) + "/"; }
    public static String pubmedAddress(String pmid) { return "https://pubmed.ncbi.nlm.nih.gov/" + pmid + "/"; }

    /**
     * PubMed by best match, each article with its abstract ({@code fullText} false); or PubMed Central's open articles searched inside their
     * full text, best match first ({@code fullText} true): the articles under an open licence and the authors' manuscripts, whose text
     * NCBI gives out.
     */
    public static Found search(String query, int limit, boolean fullText) {
        int n = Math.min(Math.max(limit, 1), 20);
        String term = fullText ? "(" + query + ") AND (open access[filter] OR author manuscript[filter])" : query;
        String es = eutil("esearch", "db=" + (fullText ? "pmc" : "pubmed") + "&retmode=json&sort=relevance&retmax=" + n + "&term=" + URLEncoder.encode(term, StandardCharsets.UTF_8), Duration.ofSeconds(20));
        if (es == null) return new Found(List.of(), 0, false);
        List<String> ids = new ArrayList<>();
        int count;
        try {
            JsonNode r = M.readTree(es).path("esearchresult");
            if (r.isMissingNode()) return new Found(List.of(), 0, false);
            count = r.path("count").asInt(0);
            for (JsonNode id : r.path("idlist")) ids.add(id.asText());
        } catch (Exception e) { return new Found(List.of(), 0, false); }
        USED.incrementAndGet();
        if (ids.isEmpty()) return new Found(List.of(), count, true);
        if (!fullText) {
            String xml = eutil("efetch", "db=pubmed&retmode=xml&id=" + String.join(",", ids), Duration.ofSeconds(30));
            if (xml == null) return new Found(List.of(), count, false);
            Map<String, Row> by = new LinkedHashMap<>();
            for (Row r : parsePubmed(xml)) by.put(r.pmid(), r);
            List<Row> rows = new ArrayList<>();
            for (String id : ids) if (by.containsKey(id)) rows.add(by.get(id));
            return new Found(opened(rows), count, true);
        }
        String sum = eutil("esummary", "db=pmc&retmode=json&id=" + String.join(",", ids), Duration.ofSeconds(20));
        if (sum == null) return new Found(List.of(), count, false);
        List<Row> rows = parsePmcSummary(sum);
        // the abstracts, in one more call, for the articles PubMed also holds
        List<String> pmids = new ArrayList<>();
        for (Row r : rows) if (!r.pmid().isEmpty()) pmids.add(r.pmid());
        if (!pmids.isEmpty()) {
            String xml = eutil("efetch", "db=pubmed&retmode=xml&id=" + String.join(",", pmids), Duration.ofSeconds(30));
            if (xml != null) {
                Map<String, Row> by = new HashMap<>();
                for (Row r : parsePubmed(xml)) by.put(r.pmid(), r);
                List<Row> merged = new ArrayList<>();
                for (Row r : rows) {
                    Row p = by.get(r.pmid());
                    merged.add(p == null ? r : new Row(r.title(), r.address(), r.journal(), r.year(), r.authors(), p.types(), p.abstractText(), r.pmid(), r.pmcid(), r.doi().isEmpty() ? p.doi() : r.doi(), true));
                }
                rows = merged;
            }
        }
        return new Found(rows, count, true);
    }

    /**
     * Which of the rows with a PubMed Central copy have a full text the library can read, asked of PubMed Central's own filters in one
     * call: the open articles and the authors' manuscripts. A copy the publisher keeps from programs is opened at its PubMed record;
     * when the call goes unanswered, the rows stay as they are, and opening an address says what it holds.
     */
    static List<Row> opened(List<Row> rows) {
        List<String> numbers = new ArrayList<>();
        for (Row r : rows) if (!r.pmcid().isEmpty()) numbers.add(r.pmcid().replaceFirst("^PMC", "") + "[uid]");
        if (numbers.isEmpty()) return rows;
        String term = "(" + String.join(" OR ", numbers) + ") AND (open access[filter] OR author manuscript[filter])";
        String es = eutil("esearch", "db=pmc&retmode=json&retmax=" + numbers.size() + "&term=" + URLEncoder.encode(term, StandardCharsets.UTF_8), Duration.ofSeconds(20));
        if (es == null) return rows;
        Set<String> open = new HashSet<>();
        try { for (JsonNode id : M.readTree(es).path("esearchresult").path("idlist")) open.add(id.asText()); } catch (Exception e) { return rows; }
        List<Row> out = new ArrayList<>();
        for (Row r : rows) out.add(r.pmcid().isEmpty() ? r : r.opened(open.contains(r.pmcid().replaceFirst("^PMC", ""))));
        return out;
    }

    /** PubMed's XML (efetch db=pubmed retmode=xml): one row per article, its PubMed Central address when it has one. */
    static List<Row> parsePubmed(String xml) {
        List<Row> out = new ArrayList<>();
        Document d = parse(xml);
        if (d == null) return out;
        for (Element pa : kids(d.getDocumentElement(), "PubmedArticle")) {
            Element mc = kid(pa, "MedlineCitation"), art = kid(mc, "Article");
            String pmid = text(kid(mc, "PMID"));
            if (pmid.isEmpty() || art == null) continue;
            String title = inline(kid(art, "ArticleTitle"));
            StringBuilder abs = new StringBuilder();
            for (Element t : kids(kid(art, "Abstract"), "AbstractText")) {
                String label = t.getAttribute("Label"), body = inline(t);
                if (body.isEmpty()) continue;
                if (!abs.isEmpty()) abs.append(' ');
                abs.append(label.isEmpty() ? "" : capitalised(label) + ": ").append(body);
            }
            List<String> authors = new ArrayList<>();
            for (Element a : kids(kid(art, "AuthorList"), "Author")) {
                String coll = text(kid(a, "CollectiveName")), last = text(kid(a, "LastName")), ini = text(kid(a, "Initials"));
                if (!coll.isEmpty()) authors.add(coll); else if (!last.isEmpty()) authors.add(ini.isEmpty() ? last : last + " " + ini);
            }
            Element journal = kid(art, "Journal");
            String jname = text(kid(journal, "ISOAbbreviation"));
            if (jname.isEmpty()) jname = text(kid(journal, "Title"));
            Element pub = kid(kid(kid(journal, "JournalIssue"), "PubDate"), "Year");
            String year = text(pub);
            if (year.isEmpty()) { Matcher y = Pattern.compile("\\b(1[89]|20)\\d{2}\\b").matcher(text(kid(kid(kid(journal, "JournalIssue"), "PubDate"), "MedlineDate"))); if (y.find()) year = y.group(); }
            List<String> types = new ArrayList<>();
            for (Element t : kids(kid(art, "PublicationTypeList"), "PublicationType")) {
                String ty = text(t);
                if (ty.isEmpty() || ty.equals("Journal Article") || ty.startsWith("Research Support") || ty.equals("English Abstract")) continue;
                if (types.size() < 3) types.add(ty);
            }
            String pmc = "", doi = "";
            for (Element id : kids(kid(kid(pa, "PubmedData"), "ArticleIdList"), "ArticleId")) {   // the article's own ids, never its references'
                String ty = id.getAttribute("IdType");
                if (ty.equals("pmc")) pmc = text(id); else if (ty.equals("doi")) doi = text(id);
            }
            if (doi.isEmpty()) for (Element e : kids(art, "ELocationID")) if ("doi".equals(e.getAttribute("EIdType"))) doi = text(e);
            out.add(new Row(title, pmc.isEmpty() ? pubmedAddress(pmid) : pmcAddress(pmc), jname, year, authors, types, abs.toString(), pmid, pmc, doi, null));
        }
        return out;
    }

    /** PubMed Central's summaries (esummary db=pmc retmode=json): the rows in the order of {@code uids}, each at its PubMed Central address. */
    static List<Row> parsePmcSummary(String json) {
        List<Row> out = new ArrayList<>();
        try {
            JsonNode r = M.readTree(json).path("result");
            for (JsonNode uid : r.path("uids")) {
                JsonNode d = r.path(uid.asText());
                String title = d.path("title").asText("").replaceAll("<[^>]+>", "").replaceAll(PlaceRows.SPACES, " ").strip();
                if (title.isEmpty()) continue;
                String pmid = "", pmc = "PMC" + uid.asText(), doi = "";
                for (JsonNode a : d.path("articleids")) {
                    String ty = a.path("idtype").asText(""), v = a.path("value").asText("");
                    if (ty.equals("pmid") && !v.equals("0")) pmid = v; else if (ty.equals("pmcid") && v.startsWith("PMC")) pmc = v; else if (ty.equals("doi")) doi = v;
                }
                List<String> authors = new ArrayList<>();
                for (JsonNode a : d.path("authors")) { String nm = a.path("name").asText(""); if (!nm.isEmpty()) authors.add(nm); }
                Matcher y = Pattern.compile("\\b(1[89]|20)\\d{2}\\b").matcher(d.path("pubdate").asText(d.path("epubdate").asText("")));
                out.add(new Row(title, pmcAddress(pmc), d.path("source").asText(""), y.find() ? y.group() : "", authors, List.of(), "", pmid, pmc, doi, true));
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ---------------------------------------------------------------- reading an article

    static final Pattern PMC_ADDRESS = Pattern.compile("(?i)^https?://(?:www\\.)?(?:pmc\\.ncbi\\.nlm\\.nih\\.gov/articles/|ncbi\\.nlm\\.nih\\.gov/pmc/articles/|europepmc\\.org/articles?/(?:PMC/)?)PMC(\\d{1,9})(?:[/?#.]|$)");
    static final Pattern PUBMED_ADDRESS = Pattern.compile("(?i)^https?://(?:www\\.)?(?:pubmed\\.ncbi\\.nlm\\.nih\\.gov/|ncbi\\.nlm\\.nih\\.gov/pubmed/|europepmc\\.org/(?:article|abstract)/MED/)(\\d{1,9})(?:[/?#]|$)");

    /** The PubMed Central number an address names ("8198407" for …/articles/PMC8198407/), or null. */
    public static String pmcOf(String url) { if (url == null) return null; Matcher m = PMC_ADDRESS.matcher(url.strip()); return m.find() ? m.group(1) : null; }

    /** The PubMed id an address names, or null. */
    public static String pmidOf(String url) { if (url == null) return null; Matcher m = PUBMED_ADDRESS.matcher(url.strip()); return m.find() ? m.group(1) : null; }

    /** Whether an address is read through the E-utilities rather than fetched as a page. */
    public static boolean reads(String url) { return pmcOf(url) != null || pmidOf(url) != null; }

    /** An article read: the address it is cited at, its title, its text, the first line said above it, and whether that is the full text. */
    public record Read(String address, String title, String text, String firstLine, boolean full) { }

    /**
     * A PubMed Central article as its full text with its section headings, or a PubMed record as its abstract (and as the article's full
     * text when PubMed Central has it). Null when the address is neither, or NCBI did not answer: the caller fetches it as a page.
     */
    public static Read read(String url) {
        String pmc = pmcOf(url);
        if (pmc != null) { Read r = readPmc(pmc); if (r != null) { USED.incrementAndGet(); SearchTally.count(TALLY, 0); } return r; }
        String pmid = pmidOf(url);
        if (pmid == null) return null;
        String xml = eutil("efetch", "db=pubmed&retmode=xml&id=" + pmid, Duration.ofSeconds(30));
        List<Row> rows = xml == null ? List.of() : parsePubmed(xml);
        if (rows.isEmpty()) return null;
        USED.incrementAndGet();
        SearchTally.count(TALLY, 0);
        Row r = rows.get(0);
        if (!r.pmcid().isEmpty()) {
            Read full = readPmc(r.pmcid().replaceFirst("^PMC", ""));
            if (full != null && full.full()) return new Read(full.address(), full.title(), full.text(), "PubMed's record of this article links to its full text in PubMed Central; this is that text, read through NCBI's E-utilities.\n", true);
        }
        StringBuilder t = new StringBuilder("# ").append(r.title()).append("\n\n");
        String cite = citeLine(r.authors(), r.journal(), r.year(), "", r.doi(), r.pmid(), r.pmcid());
        if (!cite.isEmpty()) t.append(cite).append("\n\n");
        if (!r.types().isEmpty()) t.append("Kind: ").append(String.join(", ", r.types())).append(".\n\n");
        t.append("## Abstract\n\n").append(r.abstractText().isEmpty() ? "PubMed holds no abstract for this article." : r.abstractText()).append('\n');
        String where = r.doi().isEmpty() ? "" : " The article itself is on its publisher's page, https://doi.org/" + r.doi() + ".";
        String pmcSays = r.pmcid().isEmpty() ? " PubMed Central has no copy of it." : " PubMed Central keeps its full text from programs at the publisher's wish; a person can read it at " + pmcAddress(r.pmcid()) + ".";
        return new Read(pubmedAddress(r.pmid()), r.title(), t.toString(), "PubMed's record of this article, read through NCBI's E-utilities: the abstract." + pmcSays + where + "\n", false);
    }

    /** An article of PubMed Central from its JATS XML: the full text, or the abstract alone when the publisher keeps the text from programs. */
    static Read readPmc(String number) {
        String xml = eutil("efetch", "db=pmc&id=" + number, Duration.ofSeconds(40));
        if (xml == null) return null;
        Article a = jats(xml);
        if (a == null || a.title().isEmpty()) return null;
        String at = pmcAddress(number);
        if (a.hasBody()) return new Read(at, a.title(), a.text(), "The full text of this article, read from PubMed Central through NCBI's E-utilities" + (a.license().isEmpty() ? "" : "; " + a.license()) + ".\n", true);
        String where = a.doi().isEmpty() ? "" : " The article itself is on its publisher's page, https://doi.org/" + a.doi() + ".";
        return new Read(at, a.title(), a.text(), "Only the abstract: the publisher of this article does not let PubMed Central give its full text to programs; a person can read it at this address." + where + "\n", false);
    }

    // ---------------------------------------------------------------- JATS into text

    /** An article read from JATS: its title and citation fields, its licence, the text with headings, and whether it carried a body. */
    record Article(String title, String doi, String license, String text, boolean hasBody) { }

    static Article jats(String xml) {
        Document d = parse(xml);
        if (d == null) return null;
        Element root = d.getDocumentElement();
        Element article = root.getTagName().equals("article") ? root : kid(root, "article");
        if (article == null) return null;
        Element front = kid(article, "front"), meta = kid(front, "article-meta"), jmeta = kid(front, "journal-meta");
        String title = inline(kid(kid(meta, "title-group"), "article-title"));
        List<String> authors = new ArrayList<>();
        for (Element g : kids(meta, "contrib-group"))
            for (Element c : kids(g, "contrib")) {
                if (!c.getAttribute("contrib-type").isEmpty() && !"author".equals(c.getAttribute("contrib-type"))) continue;
                Element nm = kid(c, "name");
                String coll = inline(kid(c, "collab"));
                if (nm != null) { String sur = text(kid(nm, "surname")), giv = text(kid(nm, "given-names")); if (!sur.isEmpty()) authors.add(giv.isEmpty() ? sur : sur + " " + initials(giv)); }
                else if (!coll.isEmpty()) authors.add(coll);
            }
        String journal = text(kid(kid(jmeta, "journal-title-group"), "journal-title"));
        for (Element id : kids(jmeta, "journal-id")) if ("nlm-ta".equals(id.getAttribute("journal-id-type"))) { journal = text(id); break; }
        String year = "";
        for (Element pd : kids(meta, "pub-date")) { String y = text(kid(pd, "year")); if (!y.isEmpty()) { year = y; if ("epub".equals(pd.getAttribute("pub-type")) || "electronic".equals(pd.getAttribute("publication-format"))) break; } }
        String volume = text(kid(meta, "volume")), first = text(kid(meta, "fpage")), last = text(kid(meta, "lpage")), eloc = text(kid(meta, "elocation-id"));
        String pages = !first.isEmpty() ? first + (last.isEmpty() || last.equals(first) ? "" : "–" + last) : eloc;
        String doi = "", pmid = "", pmcid = "";
        for (Element id : kids(meta, "article-id")) {
            switch (id.getAttribute("pub-id-type")) { case "doi" -> doi = text(id); case "pmid" -> pmid = text(id); case "pmcid", "pmc" -> { String v = text(id); pmcid = v.startsWith("PMC") ? v : "PMC" + v; } default -> { } }
        }
        String license = "";
        Element lic = kid(kid(meta, "permissions"), "license");
        if (lic != null) {
            String href = lic.getAttribute("xlink:href");
            if (!href.matches("(?i)https?://creativecommons\\.org/.*")) {
                // a licence given in words, with the Creative Commons address in a link inside them or in a licence reference
                StringBuilder hay = new StringBuilder(lic.getTextContent());
                for (Element x : all(lic, "ext-link")) hay.append(' ').append(x.getAttribute("xlink:href"));
                Matcher cc = Pattern.compile("(?i)https?://creativecommons\\.org/(?:licenses|publicdomain)/[a-z-]+/\\d\\.\\d").matcher(hay);
                if (cc.find()) href = cc.group();
            }
            String words = PlaceRows.cut(inline(kid(lic, "license-p")), 160);
            license = href.matches("(?i)https?://creativecommons\\.org/.*") ? "licence " + ccName(href) : words.isEmpty() ? "" : "licence: " + words;
        }
        StringBuilder out = new StringBuilder("# ").append(title).append("\n\n");
        String cite = citeLine(authors, journal, year, volume.isEmpty() ? "" : volume + (pages.isEmpty() ? "" : ":" + pages), doi, pmid, pmcid);
        if (!cite.isEmpty()) out.append(cite).append("\n\n");
        if (!license.isEmpty()) out.append(capitalised(license)).append(".\n\n");
        for (Element abs : kids(meta, "abstract")) {
            String kind = abs.getAttribute("abstract-type");
            if (kind.equals("graphical") || kind.equals("teaser")) continue;
            String head = inline(kid(abs, "title"));
            heading(out, 0, !head.isEmpty() ? head : kind.equals("summary") ? "Author summary" : "Abstract");
            block(abs, 1, out);
        }
        Element body = kid(article, "body");
        boolean hasBody = body != null && !inline(body).isBlank();
        if (hasBody) block(body, 0, out);
        Element back = kid(article, "back");
        if (back != null && hasBody) block(back, 0, out);
        return new Article(title, doi, license, out.toString().replaceAll("\n{3,}", "\n\n").strip() + "\n", hasBody);
    }

    /** "Kim J, Lee S, Park H et al. Int J Mol Sci 2021;22:5759. doi:10.3390/ijms22115759. PMID 34071182. PMCID PMC8198407." */
    static String citeLine(List<String> authors, String journal, String year, String volPages, String doi, String pmid, String pmcid) {
        List<String> parts = new ArrayList<>();
        if (!authors.isEmpty()) parts.add(PlaceRows.authors(authors));
        String src = (journal + (year.isEmpty() ? "" : " " + year) + (volPages.isEmpty() ? "" : ";" + volPages)).strip();
        if (!src.isEmpty()) parts.add(src);
        if (!doi.isEmpty()) parts.add("doi:" + doi);
        if (!pmid.isEmpty()) parts.add("PMID " + pmid);
        if (!pmcid.isEmpty()) parts.add("PMCID " + pmcid);
        StringBuilder b = new StringBuilder();
        for (String p : parts) b.append(b.isEmpty() ? "" : " ").append(p).append(p.endsWith(".") ? "" : ".");   // "et al." takes no second stop
        return b.toString();
    }

    static String ccName(String href) {
        Matcher m = Pattern.compile("(?i)creativecommons\\.org/(licenses|publicdomain)/([a-z-]+)/(\\d\\.\\d)").matcher(href);
        if (!m.find()) return href;
        return m.group(1).equalsIgnoreCase("publicdomain") ? "CC0 (public domain)" : "CC " + m.group(2).toUpperCase(Locale.ROOT) + " " + m.group(3);
    }

    static String initials(String given) {
        StringBuilder b = new StringBuilder();
        for (String w : given.split("[\\s.-]+")) if (!w.isEmpty() && Character.isLetter(w.charAt(0))) b.append(Character.toUpperCase(w.charAt(0)));
        return b.toString();
    }

    static String capitalised(String s) {
        if (s == null || s.isEmpty()) return "";
        String low = s.equals(s.toUpperCase(Locale.ROOT)) ? s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT) : s;
        return Character.toUpperCase(low.charAt(0)) + low.substring(1);
    }

    static void heading(StringBuilder out, int depth, String text) {
        if (text == null || text.isBlank()) return;
        out.append('\n').append("#".repeat(Math.min(depth + 2, 4))).append(' ').append(text.strip()).append("\n\n");
    }

    /** The elements a block holds, written in order: sections with their headings, paragraphs, lists, figures, tables, references. */
    static void block(Element e, int depth, StringBuilder out) {
        if (e == null) return;
        for (Element c : kids(e, null)) blockOne(c, depth, out);
    }

    static final Set<String> SKIPPED = Set.of("title", "label", "sec-meta", "object-id", "graphic", "alternatives", "media", "permissions", "fn-group", "glossary", "notes-title");

    static void blockOne(Element c, int depth, StringBuilder out) {
        String tag = c.getTagName();
        if (SKIPPED.contains(tag)) return;
        switch (tag) {
            case "sec", "app", "ack", "notes", "bio" -> {
                String head = (inline(kid(c, "label")) + " " + inline(kid(c, "title"))).strip();
                if (head.isEmpty() && tag.equals("ack")) head = "Acknowledgements";
                heading(out, depth, head);
                block(c, depth + 1, out);
            }
            case "p" -> paragraph(c, depth, out);
            case "list" -> list(c, out);
            case "fig" -> out.append(caption("Figure", c)).append("\n\n");
            case "table-wrap" -> table(c, out);
            case "disp-formula" -> { String f = inline(c); if (!f.isEmpty()) out.append(f).append("\n\n"); }
            case "supplementary-material" -> { String s = caption("Supplementary material", c); if (!s.isEmpty()) out.append(s).append("\n\n"); }
            case "def-list" -> { for (Element item : kids(c, "def-item")) out.append("- ").append(inline(kid(item, "term"))).append(": ").append(inline(kid(item, "def"))).append('\n'); out.append('\n'); }
            case "ref-list" -> references(c, depth, out);
            default -> block(c, depth, out);   // boxed text, quotes, statements, appendix groups: their own paragraphs
        }
    }

    static void paragraph(Element p, int depth, StringBuilder out) {
        StringBuilder b = new StringBuilder();
        List<Element> blocks = new ArrayList<>();
        inline(p, b, blocks);
        String t = b.toString().replaceAll(PlaceRows.SPACES, " ").strip();
        if (!t.isEmpty()) out.append(t).append("\n\n");
        for (Element x : blocks) blockOne(x, depth, out);   // a table or a list written inside a paragraph comes after it
    }

    static void list(Element l, StringBuilder out) {
        for (Element item : kids(l, "list-item")) {
            StringBuilder b = new StringBuilder();
            for (Element c : kids(item, null)) { if (c.getTagName().equals("label")) continue; String t = inline(c); if (!t.isEmpty()) b.append(b.isEmpty() ? "" : " ").append(t); }
            String label = inline(kid(item, "label"));
            if (!b.isEmpty()) out.append(label.isEmpty() ? "- " : label + " ").append(b).append('\n');
        }
        out.append('\n');
    }

    /** "Figure 2. Its title. Its caption." */
    static String caption(String kind, Element e) {
        String label = inline(kid(e, "label"));
        Element cap = kid(e, "caption");
        String words = cap == null ? "" : (inline(kid(cap, "title")) + " " + kidsText(cap, "p")).strip();
        String head = label.isEmpty() ? kind : label;
        return words.isEmpty() ? (label.isEmpty() ? "" : head + ".") : head + ". " + words;
    }

    static String kidsText(Element e, String tag) {
        StringBuilder b = new StringBuilder();
        for (Element c : kids(e, tag)) { String t = inline(c); if (!t.isEmpty()) b.append(b.isEmpty() ? "" : " ").append(t); }
        return b.toString();
    }

    /** A table as its caption, then one line a row with the cells between bars, then its footnotes. */
    static void table(Element tw, StringBuilder out) {
        String cap = caption("Table", tw);
        if (!cap.isEmpty()) out.append(cap).append('\n');
        int rows = 0;
        for (Element tr : all(tw, "tr")) {
            if (++rows > 60) { out.append("(the table goes on)\n"); break; }
            List<String> cells = new ArrayList<>();
            for (Element cell : kids(tr, null)) if (cell.getTagName().equals("td") || cell.getTagName().equals("th")) cells.add(PlaceRows.cut(inline(cell), 120));
            if (!cells.isEmpty()) out.append("| ").append(String.join(" | ", cells)).append(" |\n");
        }
        Element foot = kid(tw, "table-wrap-foot");
        if (foot != null) { String f = inline(foot); if (!f.isEmpty()) out.append(PlaceRows.cut(f, 1200)).append('\n'); }
        out.append('\n');
    }

    /** The reference list, one line a work: the citation as printed, or built from its parts. */
    static void references(Element refList, int depth, StringBuilder out) {
        String head = inline(kid(refList, "title"));
        heading(out, depth, head.isEmpty() ? "References" : head);
        int n = 0;
        for (Element ref : kids(refList, "ref")) {
            if (++n > 300) { out.append("(and more)\n"); break; }
            String label = inline(kid(ref, "label"));
            Element cit = kid(ref, "mixed-citation");
            String text;
            if (cit != null) text = inline(cit);
            else {
                cit = kid(ref, "element-citation");
                if (cit == null) cit = kid(ref, "citation");
                if (cit == null) cit = kid(ref, "nlm-citation");
                text = cit == null ? inline(ref) : parts(cit);
            }
            if (text.isEmpty()) continue;
            out.append(label.isEmpty() ? n + "." : label.matches(".*[.)\\]]$") ? label : label + ".").append(' ').append(text).append('\n');
        }
        out.append('\n');
    }

    /** A citation given as parts (element-citation): the authors, the title, the source, the year, the volume, the pages, the DOI. */
    static String parts(Element cit) {
        List<String> names = new ArrayList<>();
        for (Element g : kids(cit, "person-group")) for (Element nm : kids(g, "name")) { String s = text(kid(nm, "surname")), giv = text(kid(nm, "given-names")); if (!s.isEmpty()) names.add(giv.isEmpty() ? s : s + " " + initials(giv)); }
        List<String> out = new ArrayList<>();
        if (!names.isEmpty()) out.add(PlaceRows.authors(names));
        for (String t : List.of("article-title", "chapter-title", "source")) { String v = inline(kid(cit, t)); if (!v.isEmpty()) out.add(v); }
        String y = text(kid(cit, "year")), v = text(kid(cit, "volume")), f = text(kid(cit, "fpage")), l = text(kid(cit, "lpage"));
        String where = (y + (v.isEmpty() ? "" : ";" + v) + (f.isEmpty() ? "" : ":" + f + (l.isEmpty() ? "" : "–" + l))).strip();
        if (!where.isEmpty()) out.add(where);
        for (Element id : kids(cit, "pub-id")) if ("doi".equals(id.getAttribute("pub-id-type"))) out.add("doi:" + text(id));
        return String.join(". ", out);
    }

    // ---------------------------------------------------------------- XML

    /** The XML parsed with no DTD and no outside entity read: the doctype taken off first, the HTML-style entities NCBI's files may use replaced by their characters. */
    static Document parse(String xml) {
        if (xml == null || xml.isBlank()) return null;
        String s = xml.replaceFirst("(?s)<!DOCTYPE[^>\\[]*(\\[.*?\\])?\\s*>", "");
        s = entities(s);
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setValidating(false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(new DefaultHandler());
            return b.parse(new InputSource(new StringReader(s)));
        } catch (Exception e) { return null; }
    }

    static final Map<String, String> NAMED = Map.ofEntries(Map.entry("nbsp", " "), Map.entry("thinsp", " "), Map.entry("ensp", " "), Map.entry("emsp", " "),
            Map.entry("ndash", "–"), Map.entry("mdash", "—"), Map.entry("minus", "−"), Map.entry("hellip", "…"), Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"),
            Map.entry("ldquo", "“"), Map.entry("rdquo", "”"), Map.entry("times", "×"), Map.entry("plusmn", "±"), Map.entry("deg", "°"), Map.entry("micro", "µ"),
            Map.entry("le", "≤"), Map.entry("ge", "≥"), Map.entry("alpha", "α"), Map.entry("beta", "β"), Map.entry("gamma", "γ"), Map.entry("delta", "δ"), Map.entry("mu", "μ"));

    static String entities(String s) {
        Matcher m = Pattern.compile("&([A-Za-z][A-Za-z0-9]*);").matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String n = m.group(1);
            String rep = switch (n) { case "amp", "lt", "gt", "quot", "apos" -> m.group(); default -> NAMED.getOrDefault(n, " "); };
            m.appendReplacement(b, Matcher.quoteReplacement(rep));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** The child elements of {@code e} with this tag, or all of them when {@code tag} is null; none for a null element. */
    static List<Element> kids(Element e, String tag) {
        List<Element> out = new ArrayList<>();
        if (e == null) return out;
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling())
            if (c instanceof Element x && (tag == null || x.getTagName().equals(tag))) out.add(x);
        return out;
    }

    static Element kid(Element e, String tag) { List<Element> k = kids(e, tag); return k.isEmpty() ? null : k.get(0); }

    /** Every element under {@code e} with this tag, at any depth, in document order. */
    static List<Element> all(Element e, String tag) {
        List<Element> out = new ArrayList<>();
        var nl = e.getElementsByTagName(tag);
        for (int i = 0; i < nl.getLength(); i++) out.add((Element) nl.item(i));
        return out;
    }

    static String text(Element e) { return e == null ? "" : e.getTextContent().replaceAll(PlaceRows.SPACES, " ").strip(); }

    /** An element's words with its inline markup read: a superscript number after a number as a power, a superscript citation in brackets. */
    static String inline(Element e) {
        if (e == null) return "";
        StringBuilder b = new StringBuilder();
        inline(e, b, null);
        return b.toString().replaceAll(PlaceRows.SPACES, " ").strip();
    }

    static final Set<String> BLOCKS_IN_TEXT = Set.of("fig", "table-wrap", "list", "disp-formula", "def-list", "boxed-text", "disp-quote", "supplementary-material", "fig-group", "table-wrap-group");

    static void inline(Node n, StringBuilder b, List<Element> blocks) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) { b.append(c.getNodeValue()); continue; }
            if (!(c instanceof Element e)) continue;
            String tag = e.getTagName();
            if (blocks != null && BLOCKS_IN_TEXT.contains(tag)) { blocks.add(e); continue; }
            switch (tag) {
                case "sup" -> {
                    String t = inline(e);
                    boolean cites = !kids(e, "xref").isEmpty();
                    char before = b.isEmpty() ? ' ' : b.charAt(b.length() - 1);
                    if (cites) b.append(" [").append(t).append(']');
                    else if ((Character.isDigit(before) || before == ')') && t.matches("[-−+]?\\d+")) b.append('^').append(t);
                    else b.append(t);
                }
                case "break" -> b.append(' ');
                case "label", "title" -> { if (blocks != null) b.append(' '); inline(e, b, blocks); b.append(' '); }
                default -> inline(e, b, blocks);
            }
        }
    }
}
