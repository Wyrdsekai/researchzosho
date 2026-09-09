package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.tools.Fetch;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Citation metadata from the record, not from the model: a DOI resolves at Crossref, an arXiv id at
 * the arXiv API, a PubMed id at NCBI's esummary. The result becomes a source's {@code edition} — the
 * slot the contract already carries for "which version of this is it" — as {@code Freeth T. et al.,
 * Nature 444:587 (2006)} or {@code Szigety M., Arenas J., arXiv:2504.00327v2 (2025)}. Resolved once per
 * locator and cached in {@code catalog/citations.tsv}; a lookup that fails caches nothing and is tried
 * again next time. Every request goes through {@link Fetch}'s address check.
 *
 * <p>Built 2026-09-07 when the operator asked why the science affordances were not simply added: this is
 * the one every field wants — the citation as the venue printed it.
 */
public final class Citations {

    private static final ObjectMapper M = new ObjectMapper();
    static final Pattern DOI = Pattern.compile("(?i)(?:https?://(?:dx\\.)?doi\\.org/|\\bdoi:\\s*)(10\\.\\d{4,9}/[^\\s\"'<>)\\]]+)");
    static final Pattern ARXIV = Pattern.compile("(?i)arxiv\\.org/(?:abs|pdf)/(\\d{4}\\.\\d{4,5})(v\\d+)?|\\barxiv:\\s*(\\d{4}\\.\\d{4,5})(v\\d+)?");
    static final Pattern PUBMED = Pattern.compile("(?i)pubmed\\.ncbi\\.nlm\\.nih\\.gov/(\\d{5,9})");

    /** The resolved record. {@code version} is the arXiv version when one was reported, else "". */
    public record Meta(String kind, String id, String title, List<String> authors, String venue, String year,
                       String volume, String pages, String doi, String version) {
        /** The edition line: authors, venue, year — what a bibliography prints. */
        public String edition() {
            StringBuilder sb = new StringBuilder();
            if (!authors.isEmpty()) {
                sb.append(authors.get(0));
                if (authors.size() == 2) sb.append(", ").append(authors.get(1));
                else if (authors.size() > 2) sb.append(" et al.");
                sb.append(", ");
            }
            if (!venue.isEmpty()) sb.append(venue);
            else if (kind.equals("arxiv")) sb.append("arXiv:").append(id).append(version);
            if (!volume.isEmpty()) sb.append(' ').append(volume);
            if (!pages.isEmpty()) sb.append(':').append(pages);
            if (!year.isEmpty()) sb.append(" (").append(year).append(')');
            if (kind.equals("arxiv") && !venue.isEmpty()) sb.append("; arXiv:").append(id).append(version);
            if (!doi.isEmpty() && !kind.equals("doi")) sb.append("; doi:").append(doi);
            return sb.toString().strip();
        }
    }

    /** Where the JSON comes from — the live APIs, or a test's canned server. */
    public interface Source { String get(String url) throws Exception; }

    public static final Source LIVE = url -> {
        Fetch.Result r = Fetch.get(url, Duration.ofSeconds(20));
        if (r.status() != 200) throw new IOException("HTTP " + r.status() + " for " + url);
        return new String(r.body(), StandardCharsets.UTF_8);
    };

    /** Test seam: base URLs for the three services. */
    static volatile String crossrefBase = "https://api.crossref.org";
    static volatile String arxivBase = "https://export.arxiv.org";
    static volatile String pubmedBase = "https://eutils.ncbi.nlm.nih.gov";

    private Citations() { }

    /** The identifier a locator names: {@code doi:…}, {@code arxiv:…}, {@code pmid:…}, or null. */
    public static String identify(String locator) {
        if (locator == null) return null;
        Matcher d = DOI.matcher(locator);
        if (d.find()) return "doi:" + d.group(1).replaceAll("[.,;]+$", "");
        Matcher a = ARXIV.matcher(locator);
        if (a.find()) return "arxiv:" + (a.group(1) != null ? a.group(1) + (a.group(2) == null ? "" : a.group(2)) : a.group(3) + (a.group(4) == null ? "" : a.group(4)));
        Matcher p = PUBMED.matcher(locator);
        if (p.find()) return "pmid:" + p.group(1);
        return null;
    }

    /** The identifier a locator names, else the one its CAPTURED page names in its head (a journal page prints its DOI). */
    public static String identifyCaptured(LibraryStore store, String locator) {
        String ident = identify(locator);
        if (ident != null || store == null) return ident;
        try {
            java.nio.file.Path p = RawCapture.find(store, locator);
            if (p == null) return null;
            String[] r = RawCapture.read(p);
            String head = r[2].substring(0, Math.min(6000, r[2].length()));
            Matcher d = Pattern.compile("(?i)(?:https?://(?:dx\\.)?doi\\.org/|\\bdoi[:\\s]+)(10\\.\\d{4,9}/[^\\s\"'<>)\\]]+)").matcher(head);
            if (d.find()) return "doi:" + d.group(1).replaceAll("[.,;]+$", "");
            String a = identify(head);
            return a != null && a.startsWith("arxiv:") ? a : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Resolve one locator, through the cache. Null when it names no known identifier or the lookup failed. */
    public static Meta resolve(LibraryStore store, String locator, Source source) {
        String ident = identifyCaptured(store, locator);
        if (ident == null) return null;
        try {
            Map<String, String> cache = readCache(store);
            if (cache.containsKey(ident)) return fromJson(M.readTree(cache.get(ident)));
            Meta m = lookup(ident, source);
            if (m == null) return null;
            writeCache(store, ident, m);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    static Meta lookup(String ident, Source source) throws Exception {
        String kind = ident.substring(0, ident.indexOf(':'));
        String id = ident.substring(ident.indexOf(':') + 1);
        switch (kind) {
            case "doi" -> {
                JsonNode msg = M.readTree(source.get(crossrefBase + "/works/" + URLEncoder.encode(id, StandardCharsets.UTF_8).replace("%2F", "/"))).path("message");
                if (msg.isMissingNode()) return null;
                List<String> authors = new ArrayList<>();
                for (JsonNode a : msg.path("author")) authors.add(name(a.path("family").asText(""), a.path("given").asText("")));
                String year = msg.path("issued").path("date-parts").path(0).path(0).asText("");
                if (year.isEmpty()) year = msg.path("published-print").path("date-parts").path(0).path(0).asText("");
                return new Meta("doi", id, msg.path("title").path(0).asText(""), authors,
                        msg.path("container-title").path(0).asText(""), year, msg.path("volume").asText(""),
                        msg.path("page").asText(""), id, "");
            }
            case "arxiv" -> {
                String bare = id.replaceAll("v\\d+$", "");
                String atom = source.get(arxivBase + "/api/query?id_list=" + bare);
                String title = tag(atom, "title", 2).replaceAll("\\s+", " ").strip();
                List<String> authors = new ArrayList<>();
                Matcher am = Pattern.compile("<author>\\s*<name>([^<]+)</name>").matcher(atom);
                while (am.find()) for (String one : splitAuthors(am.group(1))) authors.add(shortName(one));
                Matcher idm = Pattern.compile("<id>\\s*https?://arxiv\\.org/abs/([\\d.]+)(v\\d+)?\\s*</id>").matcher(atom);
                String version = idm.find() && idm.group(2) != null ? idm.group(2) : "";
                String published = tag(atom, "published", 1);
                String year = published.length() >= 4 ? published.substring(0, 4) : "";
                String journal = tag(atom, "arxiv:journal_ref", 1).replaceAll("\\s+", " ").strip();
                String doi = tag(atom, "arxiv:doi", 1).strip();
                if (title.isEmpty() && authors.isEmpty()) return null;
                return new Meta("arxiv", bare, title, authors, journal, year, "", "", doi, version);
            }
            case "pmid" -> {
                JsonNode r = M.readTree(source.get(pubmedBase + "/entrez/eutils/esummary.fcgi?db=pubmed&retmode=json&id=" + id)).path("result").path(id);
                if (r.isMissingNode() || r.has("error")) return null;
                List<String> authors = new ArrayList<>();
                for (JsonNode a : r.path("authors")) authors.add(a.path("name").asText(""));
                String doi = "";
                for (JsonNode a : r.path("articleids")) if ("doi".equals(a.path("idtype").asText())) doi = a.path("value").asText("");
                String pub = r.path("pubdate").asText("");
                return new Meta("pmid", id, r.path("title").asText(""), authors, r.path("source").asText(""),
                        pub.length() >= 4 ? pub.substring(0, 4) : "", r.path("volume").asText(""), r.path("pages").asText(""), doi, "");
            }
            default -> { return null; }
        }
    }

    /** The nth <tag>…</tag> in a small Atom document (the first is the feed's own title). */
    private static String tag(String xml, String tag, int nth) {
        Matcher m = Pattern.compile("<" + Pattern.quote(tag) + "[^>]*>(.*?)</" + Pattern.quote(tag) + ">", Pattern.DOTALL).matcher(xml);
        String last = "";
        int n = 0;
        while (m.find()) { n++; last = m.group(1); if (n == nth) return last; }
        return n >= 1 && nth > n ? last : "";
    }

    private static String name(String family, String given) {
        if (family.isEmpty()) return given;
        String initials = given.isEmpty() ? "" : " " + given.replaceAll("[^A-Z\\p{Lu}]", "");
        return family + initials;
    }

    /**
     * One author element sometimes holds several names — arXiv:2504.00327 lists "Esteban Guillermo Szigety y
     * Gustavo Francisco Arenas" as ONE author (the submitter's data, measured live 2026-09-07). Split on the
     * conjunctions a name never contains.
     */
    static List<String> splitAuthors(String element) {
        List<String> out = new ArrayList<>();
        for (String s : element.strip().split("\\s+(?:y|and|und|et|e)\\s+|\\s*&\\s*|\\s*;\\s*")) if (!s.isBlank()) out.add(s.strip());
        return out;
    }

    /** "Miguel Szigety" → "Szigety M" — Crossref's shape, so the two APIs print alike. */
    static String shortName(String full) {
        String[] parts = full.trim().split("\\s+");
        if (parts.length < 2) return full.trim();
        StringBuilder initials = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) initials.append(parts[i].charAt(0));
        return parts[parts.length - 1] + " " + initials;
    }

    // ---- the cache ----

    static Path cacheFile(LibraryStore store) { return store.root().resolve("catalog").resolve("citations.tsv"); }

    static Map<String, String> readCache(LibraryStore store) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        Path f = cacheFile(store);
        if (!Files.exists(f)) return out;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            int t = line.indexOf('\t');
            if (t > 0) out.put(line.substring(0, t), line.substring(t + 1));
        }
        return out;
    }

    static void writeCache(LibraryStore store, String ident, Meta m) throws IOException {
        Path f = cacheFile(store);
        Files.createDirectories(f.getParent());
        var o = M.createObjectNode();
        o.put("kind", m.kind()); o.put("id", m.id()); o.put("title", m.title()); o.put("venue", m.venue());
        o.put("year", m.year()); o.put("volume", m.volume()); o.put("pages", m.pages()); o.put("doi", m.doi()); o.put("version", m.version());
        var a = o.putArray("authors"); for (String s : m.authors()) a.add(s);
        Files.writeString(f, ident + "\t" + M.writeValueAsString(o) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static Meta fromJson(JsonNode o) {
        List<String> authors = new ArrayList<>();
        for (JsonNode a : o.path("authors")) authors.add(a.asText());
        return new Meta(o.path("kind").asText(""), o.path("id").asText(""), o.path("title").asText(""), authors,
                o.path("venue").asText(""), o.path("year").asText(""), o.path("volume").asText(""), o.path("pages").asText(""),
                o.path("doi").asText(""), o.path("version").asText(""));
    }

    /** Fill the {@code n/a} editions of {@code sources} from the record, where a record exists. */
    public static List<Finding.Source> enrich(LibraryStore store, List<Finding.Source> sources, Source source) {
        List<Finding.Source> out = new ArrayList<>();
        for (Finding.Source s : sources) {
            if (s.edition() != null && !s.edition().isBlank() && !s.edition().equals("n/a")) { out.add(s); continue; }
            Meta m = resolve(store, s.locator(), source);
            out.add(m == null ? s : new Finding.Source(s.locator(), m.edition(), s.whyItMatters()));
        }
        return out;
    }

    /** The bare arXiv id and version a locator or edition names, or null. */
    static String[] arxivOf(String text) {
        if (text == null) return null;
        Matcher a = ARXIV.matcher(text);
        if (!a.find()) return null;
        String id = a.group(1) != null ? a.group(1) : a.group(3);
        String v = a.group(1) != null ? a.group(2) : a.group(4);
        return new String[]{id, v == null ? "" : v.toLowerCase(Locale.ROOT)};
    }
}
