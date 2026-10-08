package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.Config;
import org.researchzosho.Stopping;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The digitised newspaper archives with a search API: the Library of Congress (Chronicling America, US, 1770–1963), Gallica (France),
 * Delpher (the Netherlands), Papers Past through DigitalNZ (New Zealand), and Trove (Australia, with a key). Each answers in its own
 * shape; this turns them into one row: the newspaper, the date, the headline or page, where to read it, a line of the text.
 * Measured 2026-10-07: all but Trove answer without a key; the Library of Congress takes up to 30 s.
 */
public final class OldNewspapers {
    private OldNewspapers() { }

    private static final ObjectMapper M = new ObjectMapper();
    static volatile String LOC = "https://www.loc.gov", GALLICA = "https://gallica.bnf.fr", DELPHER = "https://jsru.kb.nl", DIGITALNZ = "https://api.digitalnz.org", TROVE = "https://api.trove.nla.gov.au";

    public record Row(String where, String newspaper, String date, String title, String url, String snippet) { }
    /** What one search did: the rows, which archives answered, which did not, which were skipped for want of a key. */
    public record Answer(List<Row> rows, List<String> answered, List<String> silent, List<String> noKey) { }

    /** All the archives named by {@code where} (us, fr, nl, nz, au, or all), each asked once; a year range narrows where the archive allows it. */
    public static Answer search(String query, String where, String from, String to, int limit) {
        List<Row> rows = new ArrayList<>(); List<String> answered = new ArrayList<>(), silent = new ArrayList<>(), noKey = new ArrayList<>();
        boolean all = where == null || where.isBlank() || where.equals("all");
        if (all || where.equals("us")) one("Chronicling America (US)", loc(query, from, to, limit), rows, answered, silent);
        if (all || where.equals("fr")) one("Gallica (France)", gallica(query, from, to, limit), rows, answered, silent);
        if (all || where.equals("nl")) one("Delpher (Netherlands)", delpher(query, from, to, limit), rows, answered, silent);
        if (all || where.equals("nz")) one("Papers Past (New Zealand)", digitalnz(query, from, to, limit), rows, answered, silent);
        if (all || where.equals("au")) {
            String key = Config.get("RESEARCHZOSHO_TROVE_KEY");
            if (key == null || key.isBlank()) noKey.add("Trove (Australia): RESEARCHZOSHO_TROVE_KEY");
            else one("Trove (Australia)", trove(query, from, to, limit, key), rows, answered, silent);
        }
        return new Answer(rows, answered, silent, noKey);
    }

    private static void one(String name, List<Row> got, List<Row> rows, List<String> answered, List<String> silent) {
        if (got == null) silent.add(name); else { answered.add(name); rows.addAll(got); }
    }

    /** The Library of Congress's collection search as JSON: {@code results[]} with title, date, url, partof_title, description[] (the OCR around the words). */
    static List<Row> loc(String q, String from, String to, int limit) {
        String dates = from == null || from.isBlank() ? "" : "&dates=" + Archives.enc(from + "/" + (to == null || to.isBlank() ? from : to));
        String body = Archives.text(LOC + "/collections/chronicling-america/?q=" + Archives.enc(q) + "&fo=json&c=" + Math.max(1, limit) + dates, Duration.ofSeconds(60), "the Library of Congress");
        if (body == null) return null;
        List<Row> out = new ArrayList<>();
        try {
            for (JsonNode r : M.readTree(body).path("results")) {
                String title = r.path("title").asText(""), paper = r.path("partof_title").isArray() ? Archives.first(r.get("partof_title")) : r.path("partof_title").asText("");
                if (paper.isEmpty()) paper = title.replaceAll("^(Image|Page) \\d+ of ", "").replaceAll(", [A-Z][a-z]+ \\d{1,2}, \\d{4}.*$", "");
                out.add(new Row("us", paper, r.path("date").asText(""), title, r.path("url").asText(r.path("id").asText("")), Archives.first(r.get("description"))));
            }
        } catch (Exception e) { return null; }
        return out;
    }

    /** Gallica's SRU: Dublin Core records of press issues ({@code dc.type} fascicule) whose text holds the words. */
    static List<Row> gallica(String q, String from, String to, int limit) {
        String cql = "(gallica all \"" + q.replace("\"", "") + "\") and (dc.type all \"fascicule\")";
        if (from != null && !from.isBlank()) cql += " and (dc.date >= \"" + from + "\")";
        if (to != null && !to.isBlank()) cql += " and (dc.date <= \"" + to + "\")";
        String body = Archives.text(GALLICA + "/SRU?operation=searchRetrieve&version=1.2&query=" + Archives.enc(cql) + "&maximumRecords=" + Math.max(1, limit), Duration.ofSeconds(30), "Gallica");
        if (body == null) return null;
        try {
            List<Row> out = new ArrayList<>();
            for (Element rec : records(body, "srw:record")) {
                String title = text(rec, "dc:title"), date = text(rec, "dc:date"), id = text(rec, "dc:identifier");
                out.add(new Row("fr", title, date, title, id, text(rec, "dc:description")));
            }
            return out;
        } catch (Exception e) { return null; }
    }

    /** Delpher's SRU over newspaper articles ({@code DDD_artikel}): the article's title, its paper, the date, the resolver address. */
    static List<Row> delpher(String q, String from, String to, int limit) {
        String cql = q;
        if (from != null && !from.isBlank()) cql += " AND date >= \"" + from + "\"";
        if (to != null && !to.isBlank()) cql += " AND date <= \"" + to + "\"";
        String body = Archives.text(DELPHER + "/sru/sru?operation=searchRetrieve&version=1.2&x-collection=DDD_artikel&query=" + Archives.enc(cql) + "&maximumRecords=" + Math.max(1, limit) + "&recordSchema=ddd", Duration.ofSeconds(30), "Delpher");
        if (body == null) return null;
        try {
            List<Row> out = new ArrayList<>();
            for (Element rec : records(body, "srw:record")) {
                String id = text(rec, "dc:identifier"), paper = text(rec, "ddd:papertitle");
                out.add(new Row("nl", paper, text(rec, "dc:date"), text(rec, "dc:title"), id, ""));
            }
            return out;
        } catch (Exception e) { return null; }
    }

    /** DigitalNZ's records search within the Papers Past collection; a key (RESEARCHZOSHO_DIGITALNZ_KEY) raises the rate when set. */
    static List<Row> digitalnz(String q, String from, String to, int limit) {
        String key = Config.get("RESEARCHZOSHO_DIGITALNZ_KEY");
        String dates = from == null || from.isBlank() ? "" : "&and[year][]=" + Archives.enc(from.length() >= 4 ? from.substring(0, 4) : from);
        String body = Archives.text(DIGITALNZ + "/records.json?text=" + Archives.enc(q) + "&and[collection][]=Papers+Past&per_page=" + Math.max(1, limit) + dates + (key == null || key.isBlank() ? "" : "&api_key=" + Archives.enc(key)), Duration.ofSeconds(30), "DigitalNZ");
        if (body == null) return null;
        List<Row> out = new ArrayList<>();
        try {
            for (JsonNode r : M.readTree(body).path("search").path("results")) {
                String snippet = r.path("description").asText(""); if (snippet.isEmpty()) snippet = Archives.first(r.get("fulltext"));
                out.add(new Row("nz", Archives.first(r.get("publisher")), r.path("display_date").asText(Archives.first(r.get("date"))), r.path("title").asText(""), r.path("landing_url").asText(r.path("source_url").asText("")), snippet.length() > 300 ? snippet.substring(0, 300) : snippet));
            }
        } catch (Exception e) { return null; }
        return out;
    }

    /** Trove's API v3, newspaper category, the key in its header. */
    static List<Row> trove(String q, String from, String to, int limit, String key) {
        String dates = from == null || from.isBlank() ? "" : "&l-decade=" + Archives.enc(from.substring(0, Math.min(3, from.length())));
        try {
            HttpResponse<String> r = Stopping.send(Archives.HTTP, HttpRequest.newBuilder(URI.create(TROVE + "/v3/result?category=newspaper&q=" + Archives.enc(q) + "&n=" + Math.max(1, limit) + "&encoding=json" + dates))
                    .timeout(Duration.ofSeconds(30)).header("User-Agent", Archives.UA).header("X-API-KEY", key).GET().build(), HttpResponse.BodyHandlers.ofString(), Duration.ofSeconds(30), "Trove");
            if (r.statusCode() != 200) return null;
            List<Row> out = new ArrayList<>();
            for (JsonNode cat : M.readTree(r.body()).path("category"))
                for (JsonNode a : cat.path("records").path("article"))
                    out.add(new Row("au", a.path("title").path("title").asText(""), a.path("date").asText(""), a.path("heading").asText(""), a.path("troveUrl").asText(""), a.path("snippet").asText("")));
            return out;
        } catch (Stopping.Requested stop) { throw stop; }
        catch (Exception e) { return null; }
    }

    static List<Element> records(String xml, String tag) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setXIncludeAware(false); f.setExpandEntityReferences(false);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        NodeList items = doc.getElementsByTagName(tag);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) out.add((Element) items.item(i));
        return out;
    }

    static String text(Element e, String tag) {
        NodeList n = e.getElementsByTagName(tag);
        return n.getLength() == 0 ? "" : n.item(0).getTextContent().replaceAll("\\s+", " ").strip();
    }
}
