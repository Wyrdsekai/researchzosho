package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.Config;
import org.researchzosho.Version;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The book catalogues a run may ask, all keyless and under their terms as read on 2026-10-07/09: Open Library (CC0, an identifying
 * User-Agent, a few requests a second), NDL Search's OpenSearch (free for non-commercial use), openBD (for introducing books). A catalogue
 * is flat: a book with one edition is found by its subject the same as one with fifty, which is what reaching the long tail needs.
 */
public final class Books {
    private Books() { }

    static final ObjectMapper M = new ObjectMapper();
    static final String UA = "ResearchZosho/" + (Version.number() == null ? "dev" : Version.number()) + " (https://researchzosho.org; a personal research tool)";
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();

    /** The addresses; a test points them at its own server. */
    public static final Map<String, String> BASE = new LinkedHashMap<>();
    static { BASE.put("openlibrary", "https://openlibrary.org"); BASE.put("ndl", "https://ndlsearch.ndl.go.jp/api/opensearch"); BASE.put("openbd", "https://api.openbd.jp/v1/get"); BASE.put("cinii", "https://ci.nii.ac.jp/books/opensearch/search"); }

    public static final String CINII_KEY = "RESEARCHZOSHO_CINII_APPID";
    static String setting(String key) { String v = Config.get(key); return v == null ? "" : v.strip(); }
    /** Whether CiNii Books may be asked: the person's appid is set. */
    public static boolean ciniiOn() { return !setting(CINII_KEY).isEmpty(); }

    /** One book as a catalogue lists it. */
    public record Book(String source, String title, List<String> authors, String year, List<String> subjects, List<String> isbns, int editions, int ratings, String ratingAverage, String publisher, String language, String url, String note) { }

    /** A work's own record: the description, every subject, the places and times it is about. */
    public record Work(String title, String description, List<String> subjects, List<String> places, List<String> times, String firstPublished, String url) { }

    static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }

    static String get(String url) throws IOException {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45)).header("User-Agent", UA).header("Accept", "application/json, application/xml, text/xml;q=0.9, */*;q=0.5").GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            return r.body();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
    }

    // ---- Open Library ----

    static final String FIELDS = "key,title,author_name,first_publish_year,edition_count,ratings_count,ratings_average,subject,language,isbn,publisher,number_of_pages_median";

    /** Open Library by words ({@code q}), by subject heading, by author, or any of them together; {@code language} as ISO 639-2 (eng, jpn). */
    public static List<Book> openLibrary(String q, String subject, String author, String language, int limit, String sort) throws IOException {
        StringBuilder u = new StringBuilder(BASE.get("openlibrary") + "/search.json?fields=" + FIELDS + "&limit=" + Math.max(1, Math.min(limit, 50)));
        if (q != null && !q.isBlank()) u.append("&q=").append(enc(q));
        if (subject != null && !subject.isBlank()) u.append("&subject=").append(enc(subject));
        if (author != null && !author.isBlank()) u.append("&author=").append(enc(author));
        if (language != null && !language.isBlank()) u.append("&language=").append(enc(language));
        if (sort != null && !sort.isBlank()) u.append("&sort=").append(enc(sort));
        JsonNode d = M.readTree(get(u.toString()));
        List<Book> out = new ArrayList<>();
        for (JsonNode doc : d.path("docs")) {
            List<String> authors = new ArrayList<>(), subjects = new ArrayList<>(), isbns = new ArrayList<>();
            doc.path("author_name").forEach(a -> authors.add(a.asText()));
            int n = 0; for (JsonNode s : doc.path("subject")) { if (n++ >= 12) break; subjects.add(s.asText()); }
            n = 0; for (JsonNode i : doc.path("isbn")) { if (n++ >= 3) break; isbns.add(i.asText()); }
            String lang = doc.path("language").size() > 0 ? doc.path("language").get(0).asText("") : "";
            String publisher = doc.path("publisher").size() > 0 ? doc.path("publisher").get(0).asText("") : "";
            out.add(new Book("Open Library", doc.path("title").asText(""), authors, doc.path("first_publish_year").asText(""), subjects, isbns, doc.path("edition_count").asInt(0),
                    doc.path("ratings_count").asInt(0), doc.has("ratings_average") ? String.format(Locale.ROOT, "%.1f", doc.path("ratings_average").asDouble()) : "", publisher, lang,
                    BASE.get("openlibrary") + doc.path("key").asText(""), doc.has("number_of_pages_median") ? doc.path("number_of_pages_median").asInt() + " pages" : ""));
        }
        return out;
    }

    /** A work's record by its key ({@code /works/OL45804W} or {@code OL45804W}). */
    public static Work work(String key) throws IOException {
        String k = key.strip(); if (!k.startsWith("/works/")) k = "/works/" + k.replaceFirst("^/?works/", "");
        JsonNode d = M.readTree(get(BASE.get("openlibrary") + k + ".json"));
        String desc = d.path("description").isObject() ? d.path("description").path("value").asText("") : d.path("description").asText("");
        List<String> subjects = new ArrayList<>(), places = new ArrayList<>(), times = new ArrayList<>();
        d.path("subjects").forEach(s -> { if (subjects.size() < 40) subjects.add(s.asText()); });
        d.path("subject_places").forEach(s -> { if (places.size() < 12) places.add(s.asText()); });
        d.path("subject_times").forEach(s -> { if (times.size() < 12) times.add(s.asText()); });
        return new Work(d.path("title").asText(""), desc.length() > 1500 ? desc.substring(0, 1500) + "…" : desc, subjects, places, times, d.path("first_publish_date").asText(""), BASE.get("openlibrary") + k);
    }

    // ---- NDL Search (国立国会図書館サーチ) ----

    /** NDL Search by title words, by 件名 (subject) or by NDC class prefix; books only. */
    public static List<Book> ndl(String title, String subject, String ndc, String any, int limit) throws IOException {
        StringBuilder u = new StringBuilder(BASE.get("ndl") + "?cnt=" + Math.max(1, Math.min(limit, 50)) + "&mediatype=1");
        if (title != null && !title.isBlank()) u.append("&title=").append(enc(title));
        if (subject != null && !subject.isBlank()) u.append("&subject=").append(enc(subject));
        if (ndc != null && !ndc.isBlank()) u.append("&ndc=").append(enc(ndc));
        if (any != null && !any.isBlank()) u.append("&any=").append(enc(any));
        Document doc = xml(get(u.toString()));
        NodeList items = doc.getElementsByTagName("item");
        List<Book> out = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Element e = (Element) items.item(i);
            List<String> authors = new ArrayList<>(), subjects = new ArrayList<>(), isbns = new ArrayList<>();
            String ndcClass = "";
            NodeList cs = e.getElementsByTagName("dc:creator"); for (int j = 0; j < cs.getLength(); j++) { String c = cs.item(j).getTextContent().strip(); if (!c.isEmpty() && !authors.contains(c)) authors.add(c); }
            NodeList ss = e.getElementsByTagName("dc:subject");
            for (int j = 0; j < ss.getLength(); j++) { Element s = (Element) ss.item(j); String type = s.getAttribute("xsi:type"); String v = s.getTextContent().strip(); if (type.contains("NDC")) { if (ndcClass.isEmpty()) ndcClass = v; } else if (!v.isEmpty() && subjects.size() < 12) subjects.add(v); }
            NodeList ids = e.getElementsByTagName("dc:identifier");
            for (int j = 0; j < ids.getLength(); j++) { Element x = (Element) ids.item(j); if (x.getAttribute("xsi:type").contains("ISBN") && isbns.size() < 3) isbns.add(x.getTextContent().strip()); }
            String issued = text(e, "dcterms:issued"); if (issued.isEmpty()) issued = text(e, "dc:date");
            String link = text(e, "guid"); if (link.isEmpty()) link = text(e, "link");
            out.add(new Book("NDL Search", text(e, "dc:title").isEmpty() ? text(e, "title") : text(e, "dc:title"), authors, issued.length() >= 4 ? issued.substring(0, 4) : issued, subjects, isbns, 0, 0, "",
                    text(e, "dc:publisher"), "jpn", link, ndcClass.isEmpty() ? "" : "NDC " + ndcClass));
        }
        return out;
    }

    // ---- CiNii Books (NII; the person's appid) ----

    /**
     * CiNii Books by words ({@code q}), by 件名 (subject heading) or by author: Japanese books as the university libraries catalogue them,
     * each with how many libraries hold it (a cue of reach, never a rank). {@code lang} as CiNii spells it (jpn, eng) or blank.
     */
    public static List<Book> cinii(String q, String subject, String author, String lang, int limit) throws IOException {
        StringBuilder u = new StringBuilder(BASE.get("cinii") + "?format=json&count=" + Math.max(1, Math.min(limit, 50)) + "&appid=" + enc(setting(CINII_KEY)));
        if (q != null && !q.isBlank()) u.append("&q=").append(enc(q));
        if (subject != null && !subject.isBlank()) u.append("&subject=").append(enc(subject));
        if (author != null && !author.isBlank()) u.append("&author=").append(enc(author));
        if (lang != null && !lang.isBlank()) u.append("&lang=").append(enc(lang));
        JsonNode d = M.readTree(get(u.toString()));
        List<Book> out = new ArrayList<>();
        JsonNode graph = d.path("@graph");
        JsonNode items = graph.isArray() && graph.size() > 0 ? graph.get(0).path("items") : d.path("items");
        for (JsonNode it : items) {
            List<String> authors = new ArrayList<>(), isbns = new ArrayList<>();
            JsonNode cr = it.path("dc:creator");
            if (cr.isArray()) cr.forEach(c -> authors.add(c.asText())); else if (!cr.asText("").isEmpty()) authors.add(cr.asText());
            for (JsonNode part : it.path("dcterms:hasPart")) { String id = part.path("@id").asText(""); if (id.startsWith("urn:isbn:") && isbns.size() < 3) isbns.add(id.substring("urn:isbn:".length())); }
            JsonNode pub = it.path("dc:publisher");
            String publisher = pub.isArray() ? (pub.size() > 0 ? pub.get(0).asText("") : "") : pub.asText("");
            String year = it.path("dc:date").asText(""); if (year.isEmpty()) year = it.path("prism:publicationDate").asText("");
            int owners = it.path("cinii:ownerCount").asInt(0);
            out.add(new Book("CiNii Books", it.path("title").asText(""), authors, year.length() >= 4 ? year.substring(0, 4) : year, List.of(), isbns, 0, 0, "", publisher, "jpn",
                    it.path("@id").asText(""), owners > 0 ? "held by " + owners + " Japanese librar" + (owners == 1 ? "y" : "ies") : ""));
        }
        return out;
    }

    // ---- openBD ----

    /** openBD by ISBN: the publisher's description (内容紹介) and table of contents (目次) where the publisher gave them; null when unknown. */
    public static Work openBd(String isbn) throws IOException {
        JsonNode d = M.readTree(get(BASE.get("openbd") + "?isbn=" + enc(isbn.replaceAll("[^0-9Xx]", ""))));
        if (!d.isArray() || d.size() == 0 || d.get(0).isNull()) return null;
        JsonNode b = d.get(0), s = b.path("summary");
        String desc = "", toc = "";
        for (JsonNode t : b.path("onix").path("CollateralDetail").path("TextContent")) {
            String type = t.path("TextType").asText(""), text = t.path("Text").asText("");
            if (type.equals("03") && desc.isEmpty()) desc = text; else if (type.equals("04") && toc.isEmpty()) toc = text;
        }
        List<String> subjects = new ArrayList<>();
        if (!toc.isEmpty()) subjects.add("目次: " + (toc.length() > 600 ? toc.substring(0, 600) + "…" : toc).replaceAll("\\s+", " "));
        return new Work(s.path("title").asText("") + (s.path("volume").asText("").isBlank() ? "" : " " + s.path("volume").asText()), desc.length() > 1500 ? desc.substring(0, 1500) + "…" : desc, subjects, List.of(), List.of(),
                s.path("pubdate").asText(""), "https://openbd.jp/" + s.path("isbn").asText(isbn));
    }

    // ---- helpers ----

    static Document xml(String s) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false); f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IOException("not XML: " + e.getMessage()); }
    }

    static String text(Element e, String tag) {
        NodeList l = e.getElementsByTagName(tag);
        return l.getLength() > 0 && l.item(0).getTextContent() != null ? l.item(0).getTextContent().strip() : "";
    }

    /** True when the words are mostly Japanese script. */
    public static boolean japanese(String s) {
        if (s == null) return false;
        int cjk = 0, letters = 0;
        for (char c : s.toCharArray()) { if (Character.isLetter(c)) { letters++; if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HIRAGANA || Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA || Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) cjk++; } }
        return letters > 0 && cjk * 2 >= letters;
    }
}
