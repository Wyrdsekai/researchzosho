package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.librarian.AtomicWrite;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.Owned;
import org.researchzosho.Version;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The recall watch: the open product-safety feeds (the US CPSC and FDA, the EU Safety Gate, the UK OPSS, the OECD's portal),
 * kept as files under the state folder and refreshed by the housekeeping, matched against what the person owns. A notice that
 * may concern something in the ledger becomes a hit, with the tier that found it: a barcode (exact), the brand and a model
 * number (high), or the maker named in the notice and the words of the name (medium). Hits wait in the inbox and on
 * the Owned page until the person dismisses them. Nothing here is required: with no ledger there is nothing to match.
 */
public final class Recalls {
    private Recalls() { }

    static final ObjectMapper M = new ObjectMapper();
    static final String UA = "ResearchZosho/" + (Version.number() == null ? "dev" : Version.number()) + " (https://researchzosho.org)";
    static final Duration FRESH = Duration.ofHours(20);
    static final int CPSC_YEARS = 3, SAFETY_GATE_WEEKS = 26, FDA_PAGES = 3;

    /** One notice from one feed, in the shape the matcher reads. */
    public record Notice(String feed, String id, String date, String title, String brand, String model, List<String> barcodes, String text, String url, String country) {
        public ObjectNode json() {
            ObjectNode o = M.createObjectNode();
            o.put("feed", feed); o.put("id", id); o.put("date", date); o.put("title", title); o.put("brand", brand); o.put("model", model);
            var b = o.putArray("barcodes"); barcodes.forEach(b::add);
            o.put("text", text); o.put("url", url); o.put("country", country);
            return o;
        }
        static Notice from(JsonNode o) {
            List<String> b = new ArrayList<>(); o.path("barcodes").forEach(x -> b.add(x.asText()));
            return new Notice(o.path("feed").asText(), o.path("id").asText(), o.path("date").asText(), o.path("title").asText(), o.path("brand").asText(), o.path("model").asText(), b, o.path("text").asText(), o.path("url").asText(), o.path("country").asText());
        }
    }

    /** A notice that may concern one line of the ledger. */
    public record Hit(String id, String lineId, String lineTitle, String lineDate, String shop, String feed, String noticeId, String noticeTitle, String noticeUrl, String noticeDate, String tier, String why, String seenAt, String state) {
        public ObjectNode json() {
            ObjectNode o = M.createObjectNode();
            o.put("id", id); o.put("line_id", lineId); o.put("line_title", lineTitle); o.put("line_date", lineDate); o.put("shop", shop); o.put("feed", feed);
            o.put("notice_id", noticeId); o.put("notice_title", noticeTitle); o.put("notice_url", noticeUrl); o.put("notice_date", noticeDate);
            o.put("tier", tier); o.put("why", why); o.put("seen_at", seenAt); o.put("state", state);
            return o;
        }
        static Hit from(JsonNode o) {
            return new Hit(o.path("id").asText(), o.path("line_id").asText(), o.path("line_title").asText(), o.path("line_date").asText(), o.path("shop").asText(), o.path("feed").asText(),
                    o.path("notice_id").asText(), o.path("notice_title").asText(), o.path("notice_url").asText(), o.path("notice_date").asText(), o.path("tier").asText(), o.path("why").asText(), o.path("seen_at").asText(), o.path("state").asText("new"));
        }
    }

    // ---- the feeds ----

    /** The feed addresses; a test points them at its own server. */
    public static final Map<String, String> BASE = new LinkedHashMap<>();
    static {
        BASE.put("cpsc", "https://www.saferproducts.gov/RestWebServices/Recall?format=json");
        BASE.put("safetygate", "https://ec.europa.eu/safety-gate-alerts/api/download/weeklyReport/list/xml/en");
        BASE.put("fda", "https://api.fda.gov");
        BASE.put("opss", "https://www.gov.uk/product-safety-alerts-reports-recalls.atom");
        BASE.put("oecd", "https://globalrecalls.oecd.org/ws/rss.xqy");
    }
    public static final List<String> FEEDS = List.of("cpsc", "safetygate", "fda", "opss", "oecd");
    public static Path override;   // a test points the files at its own folder
    public static Path dir() { return override != null ? override : Config.home().resolve("recalls"); }
    static Path file(String feed) { return dir().resolve(feed + ".jsonl"); }
    static Path stamp(String feed) { return dir().resolve(feed + ".fetched"); }

    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();

    static String get(String url) throws IOException {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90)).header("User-Agent", UA).header("Accept", "application/json, application/xml, text/xml, application/atom+xml, application/rss+xml;q=0.9, */*;q=0.5").GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
            return r.body();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
    }

    /** True when a feed's file is missing or older than {@link #FRESH}. */
    public static boolean due(String feed) {
        try { return !Files.exists(stamp(feed)) || Instant.parse(Files.readString(stamp(feed)).strip()).plus(FRESH).isBefore(Instant.now()); }
        catch (Exception e) { return true; }
    }

    /** Fetches every feed that is due (all of them when forced); a feed that fails keeps its old file. Returns one line per feed. */
    public static String refresh(boolean force) {
        List<String> lines = new ArrayList<>();
        for (String feed : FEEDS) {
            if (!force && !due(feed)) { lines.add(feed + ": " + count(feed) + " (fresh)"); continue; }
            try {
                List<Notice> got = switch (feed) { case "cpsc" -> cpsc(); case "safetygate" -> safetyGate(); case "fda" -> fda(); case "opss" -> opss(); default -> oecd(); };
                Files.createDirectories(dir());
                StringBuilder sb = new StringBuilder();
                for (Notice n : got) sb.append(n.json().toString()).append('\n');
                AtomicWrite.text(file(feed), sb.toString());
                Files.writeString(stamp(feed), Instant.now().toString());
                lines.add(feed + ": " + got.size());
            } catch (Exception e) {
                lines.add(feed + ": not fetched (" + e.getMessage() + ")" + (Files.exists(file(feed)) ? ", the old file stays" : ""));
            }
        }
        return String.join(", ", lines);
    }

    public static int count(String feed) {
        try (Stream<String> s = Files.lines(file(feed), StandardCharsets.UTF_8)) { return (int) s.filter(l -> !l.isBlank()).count(); } catch (IOException e) { return 0; }
    }

    /** Every notice on file, from every feed. */
    public static List<Notice> notices() {
        List<Notice> out = new ArrayList<>();
        for (String feed : FEEDS) {
            if (!Files.exists(file(feed))) continue;
            try (Stream<String> s = Files.lines(file(feed), StandardCharsets.UTF_8)) {
                s.forEach(l -> { if (!l.isBlank()) { try { out.add(Notice.from(M.readTree(l))); } catch (IOException ignored) { } } });
            } catch (IOException ignored) { }
        }
        return out;
    }

    static List<Notice> cpsc() throws IOException {
        String since = LocalDate.now().minusYears(CPSC_YEARS).toString();
        JsonNode arr = M.readTree(get(BASE.get("cpsc") + (BASE.get("cpsc").contains("?") ? "&" : "?") + "RecallDateStart=" + since));
        List<Notice> out = new ArrayList<>();
        for (JsonNode r : arr) {
            StringBuilder models = new StringBuilder(), names = new StringBuilder();
            for (JsonNode p : r.path("Products")) { if (!p.path("Model").asText("").isBlank()) models.append(p.path("Model").asText()).append("; "); names.append(p.path("Name").asText("")).append(". "); }
            List<String> upcs = new ArrayList<>(); for (JsonNode u : r.path("ProductUPCs")) if (!u.path("UPC").asText("").isBlank()) upcs.add(u.path("UPC").asText().replaceAll("\\D", ""));
            String brand = r.path("Manufacturers").size() > 0 ? r.path("Manufacturers").get(0).path("Name").asText("") : "";
            out.add(new Notice("cpsc", r.path("RecallNumber").asText(r.path("RecallID").asText()), r.path("RecallDate").asText("").length() >= 10 ? r.path("RecallDate").asText().substring(0, 10) : "",
                    r.path("Title").asText(""), brand, models.toString().strip(), upcs, names + r.path("Description").asText(""), r.path("URL").asText(""), "US"));
        }
        return out;
    }

    static List<Notice> safetyGate() throws IOException {
        Document list = xml(get(BASE.get("safetygate")));
        NodeList reports = list.getElementsByTagName("weeklyReport");
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < reports.getLength() && urls.size() < SAFETY_GATE_WEEKS; i++) {
            String u = text((Element) reports.item(i), "URL");
            if (!u.isBlank()) urls.add(u.replace("&amp;", "&"));
        }
        List<Notice> out = new ArrayList<>();
        for (String u : urls) {
            Document d;
            try { d = xml(get(u)); } catch (Exception e) { continue; }
            String date = text(d.getDocumentElement(), "report_date");
            NodeList cases = d.getElementsByTagName("caseNumber");
            for (int i = 0; i < cases.getLength(); i++) {
                Element alert = (Element) cases.item(i).getParentNode();
                String barcode = text(alert, "barcode").replaceAll("[^0-9, ]", "");
                List<String> codes = new ArrayList<>(); for (String c : barcode.split("[ ,]+")) if (c.length() >= 8) codes.add(c);
                String ref = text(alert, "reference"), recall = text(alert, "URLrecall");
                out.add(new Notice("safetygate", ref.isBlank() ? text(alert, "caseNumber") : ref, isoDate(date), text(alert, "product") + (text(alert, "name").isBlank() ? "" : " — " + text(alert, "name")),
                        text(alert, "brand"), text(alert, "type_numberOfModel"), codes, text(alert, "description") + " " + text(alert, "danger") + " " + text(alert, "category"),
                        recall.isBlank() ? "https://ec.europa.eu/safety-gate-alerts/screen/search?search=" + enc(ref) : recall, text(alert, "notifyingCountry")));
            }
        }
        return out;
    }

    static List<Notice> fda() throws IOException {
        List<Notice> out = new ArrayList<>();
        Pattern upc = Pattern.compile("\\b\\d{12,14}\\b");
        for (String kind : new String[]{"food", "drug", "device"}) {
            for (int page = 0; page < FDA_PAGES; page++) {
                JsonNode d;
                try { d = M.readTree(get(BASE.get("fda") + "/" + kind + "/enforcement.json?limit=1000&skip=" + (page * 1000) + "&sort=recall_initiation_date:desc")); }
                catch (IOException e) { if (page == 0) throw e; break; }
                for (JsonNode r : d.path("results")) {
                    String desc = r.path("product_description").asText(""), code = r.path("code_info").asText("");
                    List<String> codes = new ArrayList<>(); Matcher m = upc.matcher(code + " " + desc); while (m.find()) codes.add(m.group());
                    String date = r.path("recall_initiation_date").asText("");
                    out.add(new Notice("fda", r.path("recall_number").asText(""), date.length() == 8 ? date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6) : date,
                            desc.length() > 140 ? desc.substring(0, 140) + "…" : desc, r.path("recalling_firm").asText(""), "", codes, desc + " " + r.path("reason_for_recall").asText("") + " " + code,
                            "https://api.fda.gov/" + kind + "/enforcement.json?search=recall_number:%22" + enc(r.path("recall_number").asText("")) + "%22", "US"));
                }
                if (d.path("results").size() < 1000) break;
            }
        }
        return out;
    }

    static List<Notice> opss() throws IOException {
        Document d = xml(get(BASE.get("opss")));
        NodeList entries = d.getElementsByTagName("entry");
        List<Notice> out = new ArrayList<>();
        for (int i = 0; i < entries.getLength(); i++) {
            Element e = (Element) entries.item(i);
            String link = ""; NodeList links = e.getElementsByTagName("link"); for (int j = 0; j < links.getLength(); j++) { Element l = (Element) links.item(j); if (l.getAttribute("rel").isEmpty() || l.getAttribute("rel").equals("alternate")) link = l.getAttribute("href"); }
            String title = text(e, "title"), summary = strip(text(e, "summary") + " " + text(e, "content"));
            out.add(new Notice("opss", text(e, "id"), isoDate(text(e, "updated")), title, "", "", List.of(), summary, link, "GB"));
        }
        return out;
    }

    static List<Notice> oecd() throws IOException {
        Document d = xml(get(BASE.get("oecd")));
        NodeList items = d.getElementsByTagName("item");
        List<Notice> out = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) {
            Element e = (Element) items.item(i);
            String desc = strip(text(e, "description"));
            Matcher c = Pattern.compile("(?i)\\b(country|jurisdiction)\\s*[:：]\\s*([A-Za-z ]{2,30})").matcher(desc);
            out.add(new Notice("oecd", text(e, "guid").isBlank() ? text(e, "link") : text(e, "guid"), isoDate(text(e, "pubDate")), text(e, "title"), "", "", List.of(), desc, text(e, "link"), c.find() ? c.group(2).strip() : ""));
        }
        return out;
    }

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
        for (int i = 0; i < l.getLength(); i++) { Node n = l.item(i); if (n.getParentNode() == e || e == e.getOwnerDocument().getDocumentElement()) { String t = n.getTextContent(); return t == null ? "" : t.strip(); } }
        return l.getLength() > 0 ? String.valueOf(l.item(0).getTextContent()).strip() : "";
    }

    static String strip(String html) { return html == null ? "" : html.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replace("&amp;", "&").replaceAll("\\s+", " ").strip(); }
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    static String isoDate(String s) {
        if (s == null || s.isBlank()) return "";
        String t = s.strip();
        Matcher m = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})").matcher(t); if (m.find()) return m.group();
        m = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})").matcher(t); if (m.find()) return String.format("%s-%02d-%02d", m.group(3), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));   // Safety Gate writes DD/MM/YYYY
        String d = Owned.isoDate(t);
        return d.matches("\\d{4}-\\d{2}-\\d{2}") ? d : "";
    }

    // ---- matching ----

    static final Pattern MODEL = Pattern.compile("\\b(?=[A-Z0-9-]{4,}\\b)(?=[A-Z0-9-]*\\d)(?=[A-Z0-9-]*[A-Z])[A-Z0-9]+(?:-[A-Z0-9]+)*\\b");
    /** First words of a shop's product name that name a kind of thing, a material or a size, never a maker. */
    static final Set<String> NOT_BRANDS = Set.of("the", "a", "an", "new", "pack", "set", "for", "with", "of", "and", "your", "my", "receipt", "amazon", "basics", "generic", "unbranded",
            "usb", "hdmi", "organic", "natural", "wireless", "portable", "premium", "original", "classic", "mini", "pro", "smart", "digital", "electric", "stainless", "cotton", "leather", "wood",
            "wooden", "metal", "plastic", "glass", "kids", "baby", "women", "womens", "men", "mens", "large", "small", "big", "heavy", "ultra", "super", "extra", "fresh", "whole", "pure", "real",
            "black", "white", "blue", "red", "green", "grey", "gray", "silver", "gold", "replacement", "universal", "adjustable", "foldable", "rechargeable", "led", "lcd", "bluetooth", "wifi",
            "kitchen", "garden", "home", "office", "car", "pet", "dog", "cat", "food", "coffee", "tea", "water", "hand", "body", "hair", "face", "vitamin", "protein", "book", "toy", "game");
    static final Set<String> STOP = Set.of("the", "a", "an", "of", "for", "and", "with", "to", "in", "on", "by", "from", "pack", "set", "new", "black", "white", "size", "color", "colour", "count", "piece", "pieces", "inch", "inches", "cm", "mm", "ml", "oz", "lb", "kg", "g", "x", "l", "xl", "s", "m", "large", "small", "medium");

    /** The brand a line's name starts with, as shops write names: "Belkin 12-Outlet…" → belkin; a seller or shop when the name gives none. */
    static String brandOf(Owned.Line l) {
        String seller = l.ids().getOrDefault("seller", "");
        String t = l.title().strip();
        Matcher m = Pattern.compile("^([\\p{L}][\\p{L}\\p{N}&'.-]{1,24})(?:\\s|$)").matcher(t);
        if (m.find()) { String b = m.group(1).toLowerCase(Locale.ROOT).replaceAll("[.,]+$", ""); if (!NOT_BRANDS.contains(b) && !b.matches("^\\d.*")) return b; }
        return seller.isBlank() ? "" : seller.toLowerCase(Locale.ROOT).split("\\s+")[0];
    }

    static Set<String> modelsOf(String title) {
        List<String> first = new ArrayList<>(), rest = new ArrayList<>();
        Matcher m = MODEL.matcher(title.toUpperCase(Locale.ROOT));
        while (m.find()) {
            String t = m.group();
            if (t.length() < 4 || t.matches("^\\d+(?:-\\d+)*$") || t.matches("^\\d+-?[A-Z]{1,5}$")) continue;   // a count with its unit (7-PORT, 6FT, 10L, 4K) names no model
            if (t.matches("^[A-Z]{1,6}-?\\d.*")) first.add(t); else rest.add(t);
        }
        Set<String> out = new LinkedHashSet<>(first); out.addAll(rest);
        return out;
    }

    static Set<String> wordsOf(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() >= 3 && !STOP.contains(w)) out.add(w);
        return out;
    }

    /** The notices that may concern the ledger's purchases, each with the tier that found it. Every notice is indexed once by its words; a line visits only the notices that carry its brand, its model number or its barcode. */
    public static List<Hit> match(List<Owned.Line> lines, List<Notice> notices, String now) {
        int n = notices.size();
        String[] hay = new String[n];
        List<Set<String>> nwords = new ArrayList<>(n);
        Map<String, List<Integer>> byToken = new HashMap<>(), byCode = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Notice x = notices.get(i);
            hay[i] = (x.title() + " " + x.brand() + " " + x.model() + " " + x.text()).toLowerCase(Locale.ROOT);
            nwords.add(wordsOf(x.title() + " " + x.text()));
            for (String t : hay[i].split("[^\\p{L}\\p{N}-]+")) {
                if (t.length() < 3) continue;
                byToken.computeIfAbsent(t, k -> new ArrayList<>()).add(i);
                if (t.contains("-")) for (String piece : t.split("-")) if (piece.length() >= 3) byToken.computeIfAbsent(piece, k -> new ArrayList<>()).add(i);
            }
            for (String c : x.barcodes()) { String k = code(c); if (!k.isEmpty()) byCode.computeIfAbsent(k, z -> new ArrayList<>()).add(i); }
        }
        int common = Math.max(2, n / 50);   // a word in more than two percent of the notices (organic, whole, market, box) tells nothing by itself
        Map<String, Integer> df = new HashMap<>();
        for (Set<String> ws : nwords) for (String w : ws) df.merge(w, 1, Integer::sum);
        List<Hit> out = new ArrayList<>();
        for (Owned.Line l : lines) {
            if (!l.kind().equals("purchase") && !l.kind().equals("rental")) continue;
            String code = code(l.ids().getOrDefault("gtin", l.ids().getOrDefault("upc", l.ids().getOrDefault("ean", l.ids().getOrDefault("code", "")))));
            String brand = brandOf(l);
            String[] titleWords = l.title().toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
            String second = titleWords.length > 1 && titleWords[1].length() >= 3 && titleWords[1].matches("\\p{L}+") && !STOP.contains(titleWords[1]) && !NOT_BRANDS.contains(titleWords[1]) ? titleWords[1] : "";
            String brand2 = brand.isEmpty() || second.isEmpty() ? "" : brand + " " + second;   // King Arthur, Little Leaf, Organic Valley: the name's second word belongs to the maker
            String brandFirst = brand;
            Set<String> brandWords = new LinkedHashSet<>(List.of((brand2.isEmpty() ? brand : brand2).split(" ")));
            Set<String> models = modelsOf(l.title()), words = wordsOf(l.title());
            Set<Integer> seen = new LinkedHashSet<>();
            if (!code.isEmpty()) for (int i : byCode.getOrDefault(code, List.of())) if (after(notices.get(i), l) && seen.add(i)) out.add(hit(l, notices.get(i), "exact", "the barcode " + code + " is in the notice", now));
            if (!brand.isEmpty() && brandFirst.length() >= 3) {
                for (int i : byToken.getOrDefault(brandFirst, List.of())) {
                    Notice x = notices.get(i);
                    if (seen.contains(i) || !after(x, l)) continue;
                    String modelIn = null;
                    for (String mo : models) if (wholeToken(hay[i], mo.toLowerCase(Locale.ROOT))) { modelIn = mo; break; }   // T-500 is not T-5000
                    if (modelIn != null) { seen.add(i); out.add(hit(l, x, "high", "the brand " + brand + " and the model " + modelIn + " are in the notice", now)); continue; }
                    // a match by words alone needs the maker named in the notice's own brand field: both words when the name gives two, else the one as a whole word
                    String nb = x.brand().toLowerCase(Locale.ROOT);
                    boolean named = !brand2.isEmpty() ? nb.contains(brand2) : Pattern.compile("(^|[^\\p{L}])" + Pattern.quote(brand) + "($|[^\\p{L}])").matcher(nb).find();
                    if (!named) continue;
                    int hits = 0; List<String> which = new ArrayList<>();
                    for (String w : words) if (!brandWords.contains(w) && df.getOrDefault(w, 0) <= common && nwords.get(i).contains(w)) { hits++; which.add(w); }
                    if (hits >= 3) { seen.add(i); out.add(hit(l, x, "medium", "the notice names " + (brand2.isEmpty() ? brand : brand2) + " and the words " + String.join(", ", which.subList(0, Math.min(4, which.size()))) + " are in it", now)); }
                }
            }
            // a model number alone, with no brand, matched battery types (CR2032) and spec words on real ledgers and never a product: no tier for it
        }
        return out;
    }

    /** True when the token stands on its own in the text, bounded by anything but a letter, a digit or a hyphen. */
    public static boolean wholeToken(String hay, String token) {
        int from = 0;
        while (true) {
            int at = hay.indexOf(token, from);
            if (at < 0) return false;
            boolean before = at == 0 || !Character.isLetterOrDigit(hay.charAt(at - 1)) && hay.charAt(at - 1) != '-';
            int end = at + token.length();
            boolean after = end >= hay.length() || !Character.isLetterOrDigit(hay.charAt(end)) && hay.charAt(end) != '-';
            if (before && after) return true;
            from = at + 1;
        }
    }

    /** A barcode as a comparable key: digits only, leading zeros off, so a GTIN-13 "0012345678905" and the UPC "012345678905" meet. */
    static String code(String s) { String d = s == null ? "" : s.replaceAll("\\D", "").replaceFirst("^0+", ""); return d.length() >= 7 ? d : ""; }

    static boolean after(Notice n, Owned.Line l) {
        if (n.date().isEmpty() || l.date().length() < 10) return true;
        try { return !LocalDate.parse(n.date()).isBefore(LocalDate.parse(l.date().substring(0, 10)).minusDays(30)); } catch (Exception e) { return true; }
    }

    static Hit hit(Owned.Line l, Notice n, String tier, String why, String now) {
        return new Hit("R-" + sha(l.id() + "|" + n.feed() + "|" + n.id()).substring(0, 12), l.id(), l.title(), l.date(), l.shop(), n.feed(), n.id(), n.title(), n.url(), n.date(), tier, why, now, "new");
    }

    static String sha(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { return Integer.toHexString(s.hashCode()) + "000000000000"; }
    }

    // ---- the hits on file ----

    public static Path hitsFile(LibraryStore store) { return store.subjectsFile().resolveSibling("recall-hits.jsonl"); }

    public static List<Hit> hits(LibraryStore store, boolean openOnly) {
        List<Hit> out = new ArrayList<>();
        Path f = hitsFile(store);
        if (!Files.exists(f)) return out;
        try (Stream<String> s = Files.lines(f, StandardCharsets.UTF_8)) {
            s.forEach(l -> { if (!l.isBlank()) { try { Hit h = Hit.from(M.readTree(l)); if (!openOnly || h.state().equals("new")) out.add(h); } catch (IOException ignored) { } } });
        } catch (IOException ignored) { }
        return out;
    }

    /** What one check did. */
    public record Checked(String feeds, int notices, int lines, int newHits, int openHits) { }

    /** Refreshes the feeds that are due (all when forced), matches them against the ledger, keeps the new hits. */
    public static Checked check(LibraryStore store, boolean forceFetch) throws IOException {
        String feeds = recallsOff() ? "off (researchzosho settings set recalls on turns the watch on)" : refresh(forceFetch);
        List<Notice> all = notices();
        List<Owned.Line> lines = Owned.lines(store);
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atZone(ZoneOffset.UTC).withNano(0));
        List<Hit> found = match(lines, all, now);
        return store.locked("recall-hits", () -> {
            Set<String> have = new LinkedHashSet<>();
            for (Hit h : hits(store, false)) have.add(h.id());
            StringBuilder sb = new StringBuilder(); int added = 0;
            for (Hit h : found) if (have.add(h.id())) { sb.append(h.json().toString()).append('\n'); added++; }
            if (added > 0) { Files.createDirectories(hitsFile(store).getParent()); Files.writeString(hitsFile(store), sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            return new Checked(feeds, all.size(), lines.size(), added, hits(store, true).size());
        });
    }

    static boolean recallsOff() { String v = Config.get("RESEARCHZOSHO_RECALLS"); return v != null && v.strip().equalsIgnoreCase("off"); }

    /** Marks one hit dismissed (not mine, or dealt with); true when it was there. */
    public static boolean dismiss(LibraryStore store, String hitId) throws IOException {
        return store.locked("recall-hits", () -> {
            List<Hit> all = hits(store, false); boolean found = false;
            StringBuilder sb = new StringBuilder();
            for (Hit h : all) {
                if (h.id().equals(hitId) && h.state().equals("new")) { found = true; h = new Hit(h.id(), h.lineId(), h.lineTitle(), h.lineDate(), h.shop(), h.feed(), h.noticeId(), h.noticeTitle(), h.noticeUrl(), h.noticeDate(), h.tier(), h.why(), h.seenAt(), "dismissed"); }
                sb.append(h.json().toString()).append('\n');
            }
            if (found) AtomicWrite.text(hitsFile(store), sb.toString());
            return found;
        });
    }

    /** The housekeeping's line. */
    public static String nightly(LibraryStore store) throws IOException {
        if (Owned.lines(store).isEmpty()) return "nothing owned, nothing to match";
        Checked c = check(store, false);
        return c.notices() + " notice(s) on file (" + c.feeds() + "); " + c.lines() + " line(s) owned; " + c.newHits() + " new hit(s), " + c.openHits() + " waiting";
    }

    /** The inbox's lines about open hits, one each, for the page and the chat. */
    public static List<String> tell(LibraryStore store) {
        List<String> out = new ArrayList<>();
        for (Hit h : hits(store, true)) out.add("A recall notice may concern something you own: " + h.noticeTitle() + " (" + h.feed().toUpperCase(Locale.ROOT) + ", " + h.noticeDate() + ") — you have \"" + h.lineTitle() + "\" from " + h.shop() + ", " + h.lineDate() + "; found by " + h.why() + " (" + h.tier() + ").");
        return out;
    }
}
