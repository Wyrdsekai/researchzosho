package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * What the person owns: their purchases, imported from the records they already have — the zip a shop's data request returns
 * (Amazon's order history), a mailbox of order confirmations, a bank or card statement. One line per thing bought, in
 * {@code catalog/owned.jsonl}; one line per imported file in {@code catalog/owned-sources.jsonl}, which is also where
 * "records begin" lives, kept apart from when an account was opened. The ledger is a reference, never a requirement: the
 * shopping field answers without it, and with it knows what is owned, what came with it, and what the person actually kept.
 */
public final class Owned {
    private Owned() { }

    static final ObjectMapper M = new ObjectMapper();

    /** One thing bought. {@code kind} is purchase (an item) or payment (a statement line: where and how much, not what). */
    public record Line(String id, String date, String shop, String domain, String orderId, String title, int qty, String unitPrice,
                       String currency, String total, Map<String, String> ids, String kind, String sourceKind, String sourceFile,
                       String sourceRef, String importedAt, String confidence) {
        ObjectNode json() {
            ObjectNode o = M.createObjectNode();
            o.put("id", id); o.put("date", date); o.put("shop", shop); o.put("domain", domain); o.put("order_id", orderId); o.put("title", title);
            o.put("qty", qty); o.put("unit_price", unitPrice); o.put("currency", currency); o.put("total", total);
            ObjectNode i = o.putObject("ids"); ids.forEach(i::put);
            o.put("kind", kind); o.put("source_kind", sourceKind); o.put("source_file", sourceFile); o.put("source_ref", sourceRef);
            o.put("imported_at", importedAt); o.put("confidence", confidence);
            return o;
        }
        static Line from(JsonNode o) {
            Map<String, String> ids = new LinkedHashMap<>();
            o.path("ids").fields().forEachRemaining(e -> ids.put(e.getKey(), e.getValue().asText()));
            return new Line(o.path("id").asText(), o.path("date").asText(), o.path("shop").asText(), o.path("domain").asText(), o.path("order_id").asText(),
                    o.path("title").asText(), o.path("qty").asInt(1), o.path("unit_price").asText(), o.path("currency").asText(), o.path("total").asText(), ids,
                    o.path("kind").asText("purchase"), o.path("source_kind").asText(), o.path("source_file").asText(), o.path("source_ref").asText(),
                    o.path("imported_at").asText(), o.path("confidence").asText("read"));
        }
    }

    /** What one import did. */
    public record Summary(String file, String kind, int messagesRead, int ordersFound, int linesAdded, int linesAlready, int shipmentsSeen, int ownMail,
                          String firstDate, String lastDate, List<String> notes) { }

    /** One imported file as the sources file records it. */
    public record Source(String file, String kind, int lines, String firstDate, String lastDate, String importedAt) { }

    static Path file(LibraryStore store) { return store.subjectsFile().resolveSibling("owned.jsonl"); }
    static Path sourcesFile(LibraryStore store) { return store.subjectsFile().resolveSibling("owned-sources.jsonl"); }

    public static List<Line> lines(LibraryStore store) {
        List<Line> out = new ArrayList<>();
        Path f = file(store);
        if (!Files.exists(f)) return out;
        try (Stream<String> s = Files.lines(f, StandardCharsets.UTF_8)) {
            s.forEach(l -> { if (!l.isBlank()) { try { out.add(Line.from(M.readTree(l))); } catch (IOException ignored) { } } });
        } catch (IOException ignored) { }
        return out;
    }

    public static List<Source> sources(LibraryStore store) {
        List<Source> out = new ArrayList<>();
        Path f = sourcesFile(store);
        if (!Files.exists(f)) return out;
        try (Stream<String> s = Files.lines(f, StandardCharsets.UTF_8)) {
            s.forEach(l -> { if (!l.isBlank()) { try { JsonNode o = M.readTree(l);
                out.add(new Source(o.path("file").asText(), o.path("kind").asText(), o.path("lines").asInt(), o.path("first_date").asText(), o.path("last_date").asText(), o.path("imported_at").asText())); } catch (IOException ignored) { } } });
        } catch (IOException ignored) { }
        return out;
    }

    // ---- import ----

    /** Reads one file or folder into the ledger. {@code kind}: auto (by the file's shape), amazon, mail or bank. */
    public static Summary importPath(LibraryStore store, Path path, String kind, String currency) throws IOException {
        if (!Files.exists(path)) throw ProtocolError.notFound("There is no file or folder at " + path);
        String k = kind == null || kind.isBlank() || kind.equals("auto") ? detect(path) : kind.toLowerCase(Locale.ROOT);
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atZone(ZoneOffset.UTC).withNano(0));
        Collector c = new Collector(path, k, now);
        switch (k) {
            case "amazon" -> readAmazon(path, c);
            case "mail" -> readMail(path, c, currency);
            case "bank" -> readBank(path, c, currency);
            case "google" -> readGoogle(path, c, currency);
            case "apple" -> readApple(path, c, currency);
            default -> throw ProtocolError.invalidArgs("The kind is amazon (the zip a data request returns), mail (a mailbox file, a folder of them, or .eml files), bank (a statement as CSV), google (Takeout's Play Store orders) or apple (the store transaction history from Apple's data download); " + k + " is none of them.");
        }
        dropFallbacks(c.lines);
        return store.locked("owned", () -> {
            Set<String> have = new LinkedHashSet<>();
            for (Line l : lines(store)) have.add(l.id());
            StringBuilder sb = new StringBuilder();
            int added = 0, already = 0;
            String first = null, last = null;
            for (Line l : c.lines) {
                if (!have.add(l.id())) { already++; continue; }
                sb.append(l.json().toString()).append('\n'); added++;
                if (!l.date().isEmpty()) { if (first == null || l.date().compareTo(first) < 0) first = l.date(); if (last == null || l.date().compareTo(last) > 0) last = l.date(); }
            }
            Files.createDirectories(file(store).getParent());
            Files.writeString(file(store), sb.toString(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            ObjectNode src = M.createObjectNode();
            src.put("file", path.toAbsolutePath().normalize().toString()); src.put("kind", k); src.put("lines", added);
            src.put("first_date", first == null ? "" : first); src.put("last_date", last == null ? "" : last); src.put("imported_at", now);
            Files.writeString(sourcesFile(store), src.toString() + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            return new Summary(path.toString(), k, c.messages, c.orders, added, already, c.shipments, c.own, first == null ? "" : first, last == null ? "" : last, c.notes);
        });
    }

    /** An order's "an order from …" lines go when the same order has a line that names the thing (its confirmation, before or after the notice). */
    static void dropFallbacks(List<Line> lines) {
        Set<String> named = new LinkedHashSet<>();
        for (Line l : lines) if (!l.orderId().isEmpty() && !l.title().startsWith("an order from ")) named.add(l.sourceKind() + "|" + l.orderId());
        lines.removeIf(l -> !l.orderId().isEmpty() && l.title().startsWith("an order from ") && named.contains(l.sourceKind() + "|" + l.orderId()));
    }

    /** Forgets every line that came from one imported file (the path as the sources file has it). */
    public static int forget(LibraryStore store, String sourceFile) throws IOException {
        return store.locked("owned", () -> {
            List<Line> keep = new ArrayList<>(); int gone = 0;
            for (Line l : lines(store)) { if (l.sourceFile().equals(sourceFile)) gone++; else keep.add(l); }
            StringBuilder sb = new StringBuilder();
            for (Line l : keep) sb.append(l.json().toString()).append('\n');
            AtomicWrite.text(file(store), sb.toString());
            StringBuilder ss = new StringBuilder();
            for (Source s : sources(store)) if (!s.file().equals(sourceFile)) {
                ObjectNode o = M.createObjectNode(); o.put("file", s.file()); o.put("kind", s.kind()); o.put("lines", s.lines()); o.put("first_date", s.firstDate()); o.put("last_date", s.lastDate()); o.put("imported_at", s.importedAt());
                ss.append(o).append('\n');
            }
            AtomicWrite.text(sourcesFile(store), ss.toString());
            return gone;
        });
    }

    static String detect(Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) return "amazon";
        if (name.endsWith(".eml") || Files.isDirectory(path) && (Files.isDirectory(path.resolve("cur")) || Files.isDirectory(path.resolve("new")))) return "mail";
        if (Files.isRegularFile(path) && Mime.isMbox(path)) return "mail";
        if (name.endsWith(".json")) return "google";
        if (name.endsWith(".csv")) {
            String head = firstLine(path).toLowerCase(Locale.ROOT);
            if (head.contains("item description") || head.contains("item published") || name.contains("store transaction")) return "apple";
            return head.contains("asin") || head.contains("order id") || head.contains("product name") ? "amazon" : "bank";
        }
        if (Files.isDirectory(path)) {
            try (Stream<Path> s = Files.list(path)) {
                if (s.anyMatch(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).contains("orderhistory") || p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))) return "amazon";
            }
            return "mail";
        }
        throw ProtocolError.invalidArgs("I cannot tell what kind of records " + path.getFileName() + " holds; say --from amazon, mail or bank.");
    }

    private static String firstLine(Path p) throws IOException {
        String t = text(Files.readAllBytes(p));
        int nl = t.indexOf('\n');
        return (nl < 0 ? t : t.substring(0, nl)).replace("\uFEFF", "").replace("\r", "");
    }

    /** A file's text: UTF-8 when it is UTF-8, else Shift-JIS (amazon.co.jp's exports were, until 2026), else Latin-1. */
    static String text(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException e) {
            try { return java.nio.charset.Charset.forName("windows-31j").newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
            catch (Exception e2) { return new String(bytes, StandardCharsets.ISO_8859_1); }
        }
    }

    /** The lines an import gathers before they are written, with the counts the summary reports. */
    static final class Collector {
        final Path path; final String kind, now; final String fileName;
        final List<Line> lines = new ArrayList<>(); final List<String> notes = new ArrayList<>();
        int messages, orders, shipments, own;
        Collector(Path path, String kind, String now) { this.path = path; this.kind = kind; this.now = now; this.fileName = path.toAbsolutePath().normalize().toString(); }
        void add(String date, String shop, String domain, String orderId, String title, int qty, String unitPrice, String currency, String total, Map<String, String> ids, String lineKind, String ref, String confidence) {
            String t = title == null ? "" : title.strip().replaceAll("\\s+", " ");
            if (t.length() > 300) t = t.substring(0, 300);
            boolean byOrder = orderId != null && !orderId.isEmpty();
            String id = "O-" + sha(kind + "|" + (byOrder ? orderId : ref + "|" + date) + "|" + t.toLowerCase(Locale.ROOT)).substring(0, 12);
            lines.add(new Line(id, date == null ? "" : date, shop == null ? "" : shop, domain == null ? "" : domain, orderId == null ? "" : orderId, t, Math.max(1, qty),
                    unitPrice == null ? "" : unitPrice, currency == null ? "" : currency, total == null ? "" : total, ids == null ? Map.of() : ids, lineKind, kind, fileName, ref == null ? "" : ref, now, confidence));
        }
    }

    static String sha(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { return Integer.toHexString(s.hashCode()) + "000000000000"; }
    }

    // ---- Amazon's data export ----

    /** The zip a data request returns (or a folder of its CSVs, or one CSV): every table whose columns name an order and a product. */
    static void readAmazon(Path path, Collector c) throws IOException {
        if (Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            try (ZipInputStream z = new ZipInputStream(Files.newInputStream(path))) {
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    if (e.isDirectory() || !e.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) continue;
                    byte[] bytes = z.readAllBytes();
                    amazonCsv(e.getName(), text(bytes), c);
                }
            }
        } else if (Files.isDirectory(path)) {
            try (Stream<Path> s = Files.walk(path)) {
                for (Path p : s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")).toList()) amazonCsv(p.getFileName().toString(), text(Files.readAllBytes(p)), c);
            }
        } else {
            amazonCsv(path.getFileName().toString(), text(Files.readAllBytes(path)), c);
        }
    }

    private static final String[] ORDER_COLS = {"order id", "orderid", "order number", "order_id"};
    private static final String[] DATE_COLS = {"order date", "orderdate", "purchase date", "order_date", "date"};
    private static final String[] TITLE_COLS = {"product name", "title", "item name", "productname", "name"};

    static void amazonCsv(String name, String text, Collector c) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("return") || lower.contains("refund") || lower.contains("cart") || lower.contains("replacement") || lower.contains("sustainab")) return;   // not purchases
        String lineKind = lower.contains("rental") ? "rental" : "purchase";
        List<List<String>> rows = Csv.parse(text);
        if (rows.size() < 2) return;
        List<String> head = rows.get(0).stream().map(h -> h.replace("﻿", "").strip().toLowerCase(Locale.ROOT)).toList();
        int order = col(head, ORDER_COLS), date = col(head, DATE_COLS), title = col(head, TITLE_COLS);
        if (order < 0 || title < 0) return;   // not a purchase table (addresses, payments, returns...)
        int asin = col(head, new String[]{"asin"}), qty = col(head, new String[]{"quantity", "qty"}), unit = exactCol(head, "unit price", "unitprice", "price"),
            cur = col(head, new String[]{"currency", "currency code", "base currency code"}), total = exactCol(head, "total amount", "total owed", "total", "order total", "shipment item subtotal"), site = col(head, new String[]{"website", "marketplace", "store"}),
            status = col(head, new String[]{"order status", "orderstatus"});
        int found = 0, merged = 0;
        Map<String, Integer> at = new LinkedHashMap<>();   // (order, item) → index in c.lines: a row per shipment or per payment component is one purchase
        for (List<String> r : rows.subList(1, rows.size())) {
            if (r.size() <= Math.max(order, title)) continue;
            String st = status >= 0 && status < r.size() ? r.get(status).toLowerCase(Locale.ROOT) : "";
            if (st.contains("cancel")) continue;
            String shop = siteName(site >= 0 && site < r.size() ? r.get(site) : "");
            Map<String, String> ids = new LinkedHashMap<>();
            if (asin >= 0 && asin < r.size() && !r.get(asin).isBlank()) ids.put("asin", r.get(asin).strip());
            String d = date >= 0 && date < r.size() ? isoDate(r.get(date)) : "";
            int q = qty >= 0 && qty < r.size() ? intOf(r.get(qty)) : 1;
            String tot = total >= 0 && total < r.size() ? number(r.get(total)) : "";
            String key = r.get(order).strip() + "|" + (ids.containsKey("asin") ? ids.get("asin") : r.get(title).strip().toLowerCase(Locale.ROOT));
            Integer seen = at.get(key);
            if (seen != null) {
                Line was = c.lines.get(seen);
                if (was.qty() != q) {   // another shipment of the same order line: the quantities add up
                    String sum = was.total().isEmpty() || tot.isEmpty() ? (was.total().isEmpty() ? tot : was.total()) : new java.math.BigDecimal(was.total()).add(new java.math.BigDecimal(tot)).toPlainString();
                    c.lines.set(seen, new Line(was.id(), was.date(), was.shop(), was.domain(), was.orderId(), was.title(), was.qty() + q, was.unitPrice(), was.currency(), sum, was.ids(), was.kind(), was.sourceKind(), was.sourceFile(), was.sourceRef(), was.importedAt(), was.confidence()));
                }
                merged++;
                continue;
            }
            c.add(d, shop, shop.toLowerCase(Locale.ROOT).replace(" ", ""), r.get(order).strip(), r.get(title), q,
                    unit >= 0 && unit < r.size() ? number(r.get(unit)) : "", cur >= 0 && cur < r.size() ? r.get(cur).strip() : "", tot,
                    ids, lineKind, name + "#" + (found + 2), "exact");
            at.put(key, c.lines.size() - 1);
            found++;
        }
        c.orders += found;
        if (found > 0) c.notes.add(name + ": " + found + " line(s)" + (merged > 0 ? ", " + merged + " repeated row(s) folded in" : ""));
    }

    /** The export's Website column as a shop's name: Amazon.com, www.amazon.co.jp, www.audible.com, wholefoods.com, Audible, CBA... */
    static String siteName(String site) {
        String w = site == null ? "" : site.strip().toLowerCase(Locale.ROOT).replaceFirst("^(https?://)?(www\\.)?", "");
        if (w.isEmpty() || w.equals("not applicable")) return "Amazon";
        if (w.contains("audible")) return "Audible";
        if (w.contains("wholefoods") || w.contains("whole foods")) return "Whole Foods";
        if (w.equals("cba")) return "Amazon (Checkout by Amazon)";
        if (w.contains("amazon.")) { String tld = w.substring(w.indexOf("amazon.") + 7).replaceAll("[^a-z.].*$", ""); return "Amazon." + tld; }
        if (w.contains("kindle")) return "Kindle";
        return "Amazon " + site.strip();
    }

    private static int col(List<String> head, String[] names) {
        for (String n : names) for (int i = 0; i < head.size(); i++) if (head.get(i).equals(n)) return i;
        for (String n : names) for (int i = 0; i < head.size(); i++) if (head.get(i).contains(n) && !head.get(i).contains("tax")) return i;
        return -1;
    }

    private static int exactCol(List<String> head, String... names) {
        for (String n : names) for (int i = 0; i < head.size(); i++) if (head.get(i).equals(n)) return i;
        return -1;
    }

    static int intOf(String s) { try { return (int) Math.round(Double.parseDouble(s.strip().replace(",", ""))); } catch (Exception e) { return 1; } }

    /** A date in any of the usual shapes as YYYY-MM-DD, or the text as given when it is none of them. */
    public static String isoDate(String s) {
        String t = s == null ? "" : s.strip();
        if (t.isEmpty()) return "";
        Matcher m = Pattern.compile("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})").matcher(t);
        if (m.find()) return String.format("%s-%02d-%02d", m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        m = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})").matcher(t);
        if (m.find()) return String.format("%s-%02d-%02d", m.group(3), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        m = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日").matcher(t);
        if (m.find()) return String.format("%s-%02d-%02d", m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        try { return LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t).toString(); } catch (Exception e) { }
        try { return java.time.ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate().toString(); } catch (Exception e) { }
        try { return mailDate(t); } catch (Exception e) { return t; }
    }

    static String mailDate(String s) {
        String t = s.replaceAll("\\s*\\([^)]*\\)\\s*$", "").strip();
        Matcher m = Pattern.compile("(\\d{1,2})\\s+([A-Za-z]{3})[a-z]*\\.?\\s+(\\d{4})").matcher(t);
        if (m.find()) {
            String mon = m.group(2).toLowerCase(Locale.ROOT);
            String[] months = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
            for (int i = 0; i < 12; i++) if (months[i].equals(mon)) return String.format("%s-%02d-%02d", m.group(3), i + 1, Integer.parseInt(m.group(1)));
        }
        m = Pattern.compile("([A-Za-z]{3})[a-z]*\\.?\\s+(\\d{1,2}),?\\s+(\\d{4})").matcher(t);
        if (m.find()) {
            String mon = m.group(1).toLowerCase(Locale.ROOT);
            String[] months = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
            for (int i = 0; i < 12; i++) if (months[i].equals(mon)) return String.format("%s-%02d-%02d", m.group(3), i + 1, Integer.parseInt(m.group(2)));
        }
        return "";
    }

    // ---- a mailbox ----

    static final Map<String, String> SHOPS = new LinkedHashMap<>();
    static final Set<String> CARRIERS = Set.of("fedex.com", "ups.com", "dhl.com", "dhl.de", "usps.com", "kuronekoyamato.co.jp", "sagawa-exp.co.jp", "post.japanpost.jp", "17track.net", "aftership.com", "narvar.com");
    static {
        String[][] s = {{"amazon.com", "Amazon.com"}, {"amazon.co.jp", "Amazon.co.jp"}, {"amazon.co.uk", "Amazon.co.uk"}, {"amazon.de", "Amazon.de"}, {"amazon.ca", "Amazon.ca"},
                {"ebay.com", "eBay"}, {"paypal.com", "PayPal"}, {"rakuten.co.jp", "楽天市場"}, {"shopping.yahoo.co.jp", "Yahoo!ショッピング"}, {"mercari.com", "Mercari"}, {"mercari.jp", "メルカリ"},
                {"kobo.com", "Kobo"}, {"audible.com", "Audible"}, {"apple.com", "Apple"}, {"bestbuy.com", "Best Buy"}, {"walmart.com", "Walmart"}, {"target.com", "Target"}, {"etsy.com", "Etsy"},
                {"steampowered.com", "Steam"}, {"newegg.com", "Newegg"}, {"bhphotovideo.com", "B&H"}, {"costco.com", "Costco"}, {"homedepot.com", "Home Depot"}, {"ikea.com", "IKEA"},
                {"wayfair.com", "Wayfair"}, {"chewy.com", "Chewy"}, {"instacart.com", "Instacart"}, {"doordash.com", "DoorDash"}, {"uber.com", "Uber"}, {"kickstarter.com", "Kickstarter"},
                {"yodobashi.com", "ヨドバシ.com"}, {"biccamera.com", "ビックカメラ"}, {"zozo.jp", "ZOZOTOWN"}, {"uniqlo.com", "UNIQLO"}, {"gog.com", "GOG"}, {"humblebundle.com", "Humble Bundle"},
                {"bandcamp.com", "Bandcamp"}, {"google.com", "Google"}, {"uber.com", "Uber"}, {"lyft.com", "Lyft"}, {"toasttab.com", "Toast"}, {"squareup.com", "Square"}, {"barnesandnoble.com", "Barnes & Noble"}, {"abebooks.com", "AbeBooks"}, {"bookdepository.com", "Book Depository"}};
        for (String[] p : s) SHOPS.put(p[0], p[1]);
    }

    static final Pattern ORDER_SUBJECT = Pattern.compile("(?i)(order|receipt|invoice|purchase|shipped|shipment|your .{0,30}(item|book|ticket)s? |thank you for (your|shopping)|\\b(trip|ride) with\\b|your .{0,24}\\b(trip|ride)\\b|ご注文|注文|発送|出荷|領収|購入|お買い上げ|受注|お届け)");
    static final Pattern NOT_AN_ORDER = Pattern.compile("(?i)(password|sign[- ]in|verify|verification|security|newsletter|survey|review your|rate your|recommend|deal|sale ends|% off|unsubscribe|wishlist|cart reminder|left in your cart|back in stock|price drop|invitation|pricing|announc|introduc|changes to|update to|policy|terms of|how to|tips for|guide to)");
    static final Pattern SUBJECT_SHOP = Pattern.compile("(?i)\\b(?:receipt|invoice|order)\\b.{0,12}?\\b(?:from|at|with)\\s+(.{2,60}?)(?=\\s+(?:has|have|is|was|will|are|arrives?|scheduled|shipped|confirmed|received|on its way)\\b|\\s*[.!:,;(\\-–—|]|$)");
    static final Pattern ORDERED = Pattern.compile("(?i)(\\border\\b|purchase|bought|ご注文|注文|購入|お買い上げ)");
    static final Pattern RECEIPT = Pattern.compile("(?i)(receipt|invoice|trip|ride|fare|subscription|renewal|payment|booking|reservation|ticket|bill|領収|請求|予約)");
    static final Pattern AMAZON_ORDER = Pattern.compile("\\b([A-Z]?\\d{2,3}-\\d{7}-\\d{7})\\b");
    static final Pattern GENERIC_ORDER = Pattern.compile("(?i)(?:order|order\\s*(?:#|no\\.?|number|id)|confirmation\\s*(?:#|number)|注文番号|ご注文番号|受付番号)\\s*[\\]）)]?\\s*[:：#]?\\s*#?\\s*([A-Z0-9][A-Z0-9-]{3,30})\\b");
    static final Pattern MONEY = Pattern.compile("(US\\s?\\$|CA\\$|A\\$|USD|JPY|EUR|GBP|CAD|AUD|\\$|¥|￥|€|£)\\s*([\\d,]+(?:\\.\\d{1,2})?)|([\\d,]+(?:\\.\\d{1,2})?)\\s*\\(?\\s*(円|USD|JPY|EUR|GBP|CAD|AUD)");
    static final Pattern TOTAL_LINE = Pattern.compile("(?i)(grand total|order total|total amount|amount paid|total charged|total paid|total|ご請求金額|お支払い金額|合計金額|合計|小計)");
    static final Pattern LABEL = Pattern.compile("(?i)^(sub\\s?total|tax|shipping|handling|discount|promotion|gift|estimated|total|order|qty|quantity|price|item|items|sold by|ship|deliver|track|payment|billing|visa|mastercard|card|thank|view|manage|return|cancel|learn|help|questions|小計|送料|消費税|税|合計|数量|価格|お届け|配送|支払|注文|商品)\\b.*");

    static void readMail(Path path, Collector c, String currency) throws IOException {
        if (Files.isDirectory(path)) {
            if (Files.isDirectory(path.resolve("cur")) || Files.isDirectory(path.resolve("new"))) {
                for (String sub : new String[]{"cur", "new"}) {
                    Path d = path.resolve(sub);
                    if (!Files.isDirectory(d)) continue;
                    try (Stream<Path> s = Files.list(d)) { for (Path p : s.filter(Files::isRegularFile).sorted().toList()) message(Files.readAllBytes(p), p.getFileName().toString(), c, currency); }
                }
                return;
            }
            try (Stream<Path> s = Files.walk(path)) {
                for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".msf") || n.endsWith(".sqlite") || n.endsWith(".json") || n.endsWith(".jsonlz4") || n.endsWith(".dat") || n.endsWith(".js") || n.endsWith(".ini") || n.endsWith(".db")) continue;
                    if (n.endsWith(".eml")) { message(Files.readAllBytes(p), p.getFileName().toString(), c, currency); continue; }
                    if (Mime.isMbox(p)) Mime.mbox(p, raw -> message(raw, p.getFileName().toString(), c, currency));
                }
            }
            return;
        }
        if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".eml")) { message(Files.readAllBytes(path), path.getFileName().toString(), c, currency); return; }
        if (!Mime.isMbox(path)) throw ProtocolError.invalidArgs(path.getFileName() + " is not a mailbox file (one that starts with a 'From ' line), a Maildir folder, or an .eml file.");
        Mime.mbox(path, raw -> message(raw, path.getFileName().toString(), c, currency));
    }

    /** One message: an order confirmation becomes its lines; a shipment notice is counted; the rest is passed over. */
    static void message(byte[] raw, String where, Collector c, String currency) {
        c.messages++;
        Mime.Message m;
        try { m = Mime.parse(raw); } catch (Exception e) { return; }
        String from = m.header("from"), subject = m.header("subject").replaceFirst("(?i)^(fwd?|re)\\s*:\\s*", "");
        String domain = domainOf(from);
        boolean carrier = matchesAny(CARRIERS, domain);
        if (carrier) { c.shipments++; return; }
        if (m.header("subject").matches("(?i)^re\\s*:.*")) return;   // a reply is a conversation, not an order
        if (!ORDER_SUBJECT.matcher(subject).find() || NOT_AN_ORDER.matcher(subject).find()) return;
        String fromAddress = addressOf(from);
        if (!fromAddress.isEmpty() && (m.header("to") + " " + m.header("cc") + " " + m.header("delivered-to")).toLowerCase(Locale.ROOT).contains(fromAddress)) { c.own++; return; }   // sent to oneself: a forward, a note
        String ref = m.header("message-id").isEmpty() ? where + "#" + c.messages : m.header("message-id");
        String date = isoDate(m.header("date"));
        String text = m.text().isBlank() ? Mime.htmlToText(m.html()) : m.text();
        text = text.replace("\r\n", "\n").replace('\r', '\n');   // a mail's lines end in CRLF, LF or, decoded from some senders, CR alone
        boolean forwarded = m.header("subject").matches("(?i)^(fwd?|re)\\s*:.*");
        if (forwarded) {   // the shop is the one that wrote the mail inside
            Matcher inner = Pattern.compile("(?im)^\\s*>?\\s*(?:From|差出人)\\s*[:：]\\s*(.+)$").matcher(text);
            if (inner.find()) { from = inner.group(1).strip(); domain = domainOf(from); }
        }
        boolean known = matchesAny(SHOPS.keySet(), domain);
        if (!known && !MONEY.matcher(text).find()) return;   // an order from a shop we do not know carries a price; a notice without one is not an order
        String shop = shopOf(from, domain);
        String family = family(domain);
        if (family.equals("generic")) {   // "Your receipt from Tatte Bakery", relayed by a group or a receipts service: the subject names the shop
            Matcher ss = SUBJECT_SHOP.matcher(subject);
            if (ss.find() && !ss.group(1).matches("(?i)(you|us|your .*|order .*|#.*)")) shop = ss.group(1).strip();
        }
        boolean serviceReceipt = family.equals("generic") && RECEIPT.matcher(subject).find() && !ORDERED.matcher(subject).find();
        String orderId = orderId(text, family, subject);
        String[] total = total(text, currency);
        List<Item> items = orderMarkup(m.html());
        String confidence = items.isEmpty() ? "read" : "exact";
        if (items.isEmpty()) items = switch (family) {
            case "paypal" -> labelled(text, "Item Title", "Item name", "Description");
            case "rakuten" -> rakuten(text);
            default -> List.of();
        };
        boolean receipt = serviceReceipt;
        if (serviceReceipt) items = List.of(new Item("a receipt from " + shop, 1, "", total[1]));   // a ride, a meal, a subscription: where and how much, one line
        if (items.isEmpty()) { items = paired(text, total[1]); confidence = "guessed"; }
        if (items.isEmpty()) {
            Item named = subjectTitle(subject, shop);
            if (named != null) items = List.of(named);
        }
        if (items.isEmpty()) items = List.of(new Item("an order from " + shop, 1, "", total[1]));
        c.orders++;
        for (Item it : items) {
            Map<String, String> ids = new LinkedHashMap<>();
            if (family.equals("amazon") && !orderId.isEmpty()) ids.put("amazon_order", orderId);
            ids.put("subject", subject.length() > 160 ? subject.substring(0, 160) : subject);
            c.add(date, shop, domain, orderId, it.title, it.qty, it.price, it.currency.isEmpty() ? total[1] : it.currency, total[0], ids, receipt ? "receipt" : "purchase", ref, confidence);
        }
    }

    record Item(String title, int qty, String price, String currency) { }

    /** schema.org {@code Order} markup as JSON-LD inside a mail's HTML (what Gmail reads): the items it names, exactly. */
    static List<Item> orderMarkup(String html) {
        List<Item> out = new ArrayList<>();
        if (html == null || !html.contains("ld+json")) return out;
        Matcher m = Pattern.compile("(?is)<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>").matcher(html);
        while (m.find()) {
            try {
                JsonNode root = M.readTree(m.group(1).strip());
                List<JsonNode> nodes = new ArrayList<>();
                if (root.isArray()) root.forEach(nodes::add); else nodes.add(root);
                for (JsonNode n : nodes) {
                    if (!n.path("@type").asText("").equalsIgnoreCase("Order")) continue;
                    JsonNode offers = n.path("acceptedOffer");
                    List<JsonNode> list = new ArrayList<>();
                    if (offers.isArray()) offers.forEach(list::add); else if (offers.isObject()) list.add(offers);
                    for (JsonNode o : list) {
                        String title = o.path("itemOffered").path("name").asText("");
                        if (title.isBlank()) continue;
                        int qty = o.path("eligibleQuantity").path("value").asInt(1);
                        out.add(new Item(title, Math.max(1, qty), number(o.path("price").asText("")), o.path("priceCurrency").asText("")));
                    }
                }
            } catch (Exception ignored) { }
        }
        return out;
    }

    static boolean matchesAny(Set<String> domains, String domain) {
        for (String d : domains) if (domain.equals(d) || domain.endsWith("." + d)) return true;
        return false;
    }

    static String addressOf(String from) {
        Matcher m = Pattern.compile("([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+)").matcher(from);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
    }

    static String domainOf(String from) {
        Matcher m = Pattern.compile("@([A-Za-z0-9.-]+)").matcher(from);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
    }

    static String shopOf(String from, String domain) {
        String name = from.replaceAll("<[^>]*>", "").replace("\"", "").strip();
        Matcher via = Pattern.compile("^'?(.+?)'?\\s+via\\s+.+$").matcher(name);   // a Google Groups relay: 'Shop' via the group — the shop, not the group's domain
        if (via.find()) name = via.group(1).strip();
        else for (var e : SHOPS.entrySet()) if (domain.equals(e.getKey()) || domain.endsWith("." + e.getKey())) return e.getValue();
        name = name.replaceAll("(?i)\\b(no[- ]?reply|do[- ]?not[- ]?reply|customer service|customer care|receipts?|orders?|support|team|notifications?)\\b", " ").replaceAll("[\\s|,-]+$", "").replaceAll("\\s{2,}", " ").strip();
        if (!name.isEmpty() && !name.contains("@") && name.matches(".*\\p{L}.*")) return name;
        String[] parts = domain.split("\\.");
        return parts.length >= 2 ? parts[parts.length - 2] : domain;
    }

    static String family(String domain) {
        if (domain.contains("amazon.")) return "amazon";
        if (domain.endsWith("paypal.com") || domain.endsWith("ebay.com")) return "paypal";
        if (domain.endsWith("rakuten.co.jp")) return "rakuten";
        return "generic";
    }

    static String orderId(String text, String family, String subject) {
        String both = subject + "\n" + text;
        if (family.equals("amazon")) { Matcher m = AMAZON_ORDER.matcher(both); if (m.find()) return m.group(1); }
        Matcher m = GENERIC_ORDER.matcher(both);
        while (m.find()) { String id = m.group(1); if (id.matches(".*\\d.*") && !id.matches("(?i)(details|summary|number|status|confirmation|total|date|information)")) return id; }
        return "";
    }

    /** {amount, currency} of the line that reads as the order's total: the last "total"-labelled money on the page, else none. */
    static String[] total(String text, String currency) {
        String amount = "", cur = currency == null ? "" : currency;
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!TOTAL_LINE.matcher(line).find() || line.toLowerCase(Locale.ROOT).contains("subtotal") || line.contains("小計")) continue;
            Matcher m = MONEY.matcher(line);
            boolean here = m.find();
            if (!here && i + 1 < lines.length && !TOTAL_LINE.matcher(lines[i + 1]).find()) { m = MONEY.matcher(lines[i + 1]); here = m.find(); }   // the amount in the next cell, on its own line
            if (here) { String[] a = money(m); amount = a[0]; if (!a[1].isEmpty()) cur = a[1]; }
        }
        return new String[]{amount, cur};
    }

    static String[] money(Matcher m) {
        String sym = m.group(1) != null ? m.group(1) : m.group(4), num = m.group(2) != null ? m.group(2) : m.group(3);
        String cur = switch (sym == null ? "" : sym.replace(" ", "")) {
            case "$", "US$", "USD" -> "USD"; case "¥", "￥", "円", "JPY" -> "JPY"; case "€", "EUR" -> "EUR"; case "£", "GBP" -> "GBP"; case "CA$", "CAD" -> "CAD"; case "A$", "AUD" -> "AUD"; default -> "";
        };
        return new String[]{number(num), cur};
    }

    static String number(String s) {
        if (s == null) return "";
        String t = s.strip().replaceAll("[^0-9.,-]", "").replace(",", "");
        if (t.isEmpty() || t.equals("-") || t.equals(".")) return "";
        return t;
    }

    /** Lines labelled "Item Title:" / "Item name:" with the quantity and price lines that follow (PayPal and eBay receipts). */
    static List<Item> labelled(String text, String... labels) {
        List<Item> out = new ArrayList<>();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].strip();
            for (String label : labels) {
                if (l.regionMatches(true, 0, label + ":", 0, label.length() + 1)) {
                    String title = l.substring(label.length() + 1).strip();
                    int qty = 1; String price = "", cur = "";
                    for (int j = i + 1; j < Math.min(lines.length, i + 8); j++) {
                        String n = lines[j].strip();
                        Matcher q = Pattern.compile("(?i)^(quantity|qty)\\s*[:：]\\s*(\\d+)").matcher(n); if (q.find()) qty = Integer.parseInt(q.group(2));
                        Matcher p = Pattern.compile("(?i)^(sale price|price|unit price|amount)\\s*[:：]\\s*(.+)").matcher(n);
                        if (p.find()) { Matcher mm = MONEY.matcher(p.group(2)); if (mm.find()) { String[] a = money(mm); price = a[0]; cur = a[1]; } }
                    }
                    if (!title.isEmpty()) out.add(new Item(title, qty, price, cur));
                }
            }
        }
        return out;
    }

    /** 楽天市場's confirmation: {@code 価格 8200(円) x 1(個)} under the item's name. */
    static List<Item> rakuten(String text) {
        List<Item> out = new ArrayList<>();
        String[] lines = text.split("\n");
        Pattern p = Pattern.compile("価格\\s*([\\d,]+)\\s*\\(?円\\)?\\s*[x×]\\s*(\\d+)\\s*\\(?個\\)?");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = p.matcher(lines[i]);
            if (!m.find()) continue;
            String title = "";
            for (int j = i - 1; j >= Math.max(0, i - 4); j--) { String t = lines[j].strip(); if (!t.isEmpty() && !t.startsWith("[") && !t.startsWith("■") && !LABEL.matcher(t).matches()) { title = t.replaceFirst("^商品名\\s*[:：]\\s*", ""); break; } }
            if (!title.isEmpty()) out.add(new Item(title, Integer.parseInt(m.group(2)), number(m.group(1)), "JPY"));
        }
        return out;
    }

    static final Pattern SUBJECT_NOISE = Pattern.compile("(?i)\\b(your|the)\\s+(recent\\s+)?(amazon\\.(com|co\\.jp|co\\.uk|de|ca)\\s+)?(order|purchase|receipt|invoice|payment|shipment|package|item|delivery)s?\\b"
            + "|\\b(has|have|was|were|is)\\s+(been\\s+)?(shipped|delivered|dispatched|sent|arrived|received|confirmed|placed|processed|updated|on (its|the) way|out for delivery)\\b!?"
            + "|\\b(order|purchase)\\s+(confirmation|confirmed|received|update|details|summary|placed)\\b|\\breceipt( for your (payment|purchase|order))?\\b"
            + "|\\bthank you for (your|shopping)( (order|purchase|with us))?\\b|\\bthanks for your (order|purchase)\\b|\\b(order|confirmation)\\s*(#|no\\.?|number)?\\s*[:#]?\\s*[A-Z]?\\d[A-Z0-9-]{3,}\\b"
            + "|\\barrives?\\s+(by\\s+)?\\w+\\.?\\s*\\d{0,2}/?\\d{0,2}\\.?|\\bfrom\\s+[A-Z][\\w.&' -]{1,40}$|\\bshipped\\b!?|\\bon the way\\b!?|\\bof\\s+\\d+\\s+items?\\b"
            + "|^\\s*(delivered|shipped|arriving|arrived|out for delivery|on its way|update)\\s*[:：]|#?\\b[A-Z]?\\d{2,3}-\\d{7}-\\d{7}\\b"
            + "|ご注文(の|内容|確認|ありがとうございます)?|注文(確認|内容|完了)|自動配信メール|【[^】]*】|\\[[^\\]]*\\]|発送(のお知らせ|しました|完了)?|お届け(のお知らせ)?|領収書|購入(完了|確認)?");

    /** The subject as the thing's name once the boilerplate is off: "Amazon.com order of Back to Frank Black." names a book; "Your order has shipped" names nothing. */
    static Item subjectTitle(String subject, String shop) {
        String s = subject.replaceAll("[\"“”「」]", "");
        Matcher m = Pattern.compile("(?i)order of (.+?)(?:\\s+and \\d+ (?:more|other) items?)?(?:\\s+(has|have|is)\\s+.*)?\\.?!?$").matcher(s);
        if (m.find()) s = m.group(1);
        int qty = 1;
        Matcher q = Pattern.compile("^\\s*(\\d{1,3})\\s?[x×]\\s+").matcher(s);
        if (q.find()) { qty = Integer.parseInt(q.group(1)); s = s.substring(q.end()); }
        s = SUBJECT_NOISE.matcher(s).replaceAll(" ");
        if (shop != null && !shop.isEmpty()) s = s.replace(shop, " ");
        s = s.replaceAll("[\"“”「」]", "").replaceAll("^[\\s:.,!\\-–—|]+|[\\s:.,!\\-–—|]+$", "").replaceAll("\\s{2,}", " ").strip();
        if (s.length() < 4 || !s.matches(".*\\p{L}{2,}.*") || s.matches("(?i)(you|new|now|update|ready|here|details|info|hi|hello)")) return null;
        return new Item(s, qty, "", "");
    }

    /** The generic reading: a price standing at the end of a line names the item in the nearest line above it that is not a label. */
    static List<Item> paired(String text, String currency) {
        List<Item> out = new ArrayList<>();
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length && out.size() < 20; i++) {
            String l = lines[i].strip();
            if (l.isEmpty() || TOTAL_LINE.matcher(l).find() || LABEL.matcher(l).matches()) continue;
            Matcher m = MONEY.matcher(l);
            if (!m.find() || m.end() < l.length() - 1) continue;
            String[] a = money(m);
            String before = l.substring(0, m.start()).strip().replaceAll("[\\t|:：]+$", "").strip();
            String title = before.length() >= 4 ? before : "";
            if (title.isEmpty()) { int seen = 0; for (int j = i - 1; j >= 0 && seen < 6; j--) { String t = lines[j].strip(); if (t.isEmpty()) continue; seen++; if (t.length() >= 4 && !LABEL.matcher(t).matches() && !MONEY.matcher(t).find() && !t.matches("(?i)^(sold|ships?|fulfilled) by.*") && !t.matches("^[\\d\\s.,x×:-]+$")) { title = t; break; } } }
            if (title.isEmpty() || title.length() > 160) continue;
            int qty = 1;
            Matcher lead = Pattern.compile("^(\\d{1,3})\\s?[x×]\\s+").matcher(title);
            if (lead.find()) { qty = Integer.parseInt(lead.group(1)); title = title.substring(lead.end()); }
            Matcher q = Pattern.compile("(?i)(?:qty|quantity|数量)\\s*[:：]?\\s*(\\d+)|\\b[x×]\\s?(\\d+)\\b").matcher(title + " " + (i + 1 < lines.length ? lines[i + 1] : ""));
            if (q.find()) qty = Integer.parseInt(q.group(1) != null ? q.group(1) : q.group(2));
            out.add(new Item(title.replaceAll("(?i)\\s*(qty|quantity|数量)\\s*[:：]?\\s*\\d+\\s*$", "").strip(), qty, a[0], a[1].isEmpty() ? currency : a[1]));
        }
        return out;
    }

    // ---- a statement ----

    /** A bank or card statement as CSV: where and how much. Debits only when the file tells them apart. */
    static void readBank(Path path, Collector c, String currency) throws IOException {
        List<List<String>> rows = Csv.parse(text(Files.readAllBytes(path)).replace("\uFEFF", ""));
        if (rows.size() < 2) throw ProtocolError.invalidArgs(path.getFileName() + " has no rows under its header.");
        List<String> head = rows.get(0).stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
        int date = col(head, new String[]{"date", "transaction date", "posted date", "取引日", "利用日", "日付"});
        int desc = col(head, new String[]{"description", "merchant", "payee", "name", "memo", "摘要", "利用店名", "内容", "ご利用先"});
        int amount = col(head, new String[]{"amount", "金額", "利用金額"});
        int debit = col(head, new String[]{"debit", "withdrawal", "出金", "お支払い"}), credit = col(head, new String[]{"credit", "deposit", "入金"});
        int cur = col(head, new String[]{"currency", "通貨"});
        if (date < 0 || desc < 0 || (amount < 0 && debit < 0)) throw ProtocolError.invalidArgs("A statement needs a date, a description and an amount column; " + path.getFileName() + " has " + String.join(", ", head) + ".");
        boolean signed = false;
        if (amount >= 0 && debit < 0) for (List<String> r : rows.subList(1, rows.size())) if (amount < r.size() && r.get(amount).strip().startsWith("-")) { signed = true; break; }
        int n = 0;
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.size() <= Math.max(date, desc)) continue;
            String amt;
            if (debit >= 0) { amt = debit < r.size() ? number(r.get(debit)) : ""; if (amt.isEmpty()) continue; }
            else { String raw = r.get(amount).strip(); if (signed && !raw.startsWith("-")) continue; amt = number(raw).replace("-", ""); if (amt.isEmpty()) continue; }
            String shop = r.get(desc).strip().replaceAll("\\s+", " ");
            if (shop.isEmpty()) continue;
            c.add(isoDate(r.get(date)), shop, "", "", "payment to " + shop, 1, "", cur >= 0 && cur < r.size() && !r.get(cur).isBlank() ? r.get(cur).strip() : currency, amt, Map.of(), "payment", path.getFileName() + "#" + (i + 1), "read");
            n++;
        }
        c.orders += n;
    }

    // ---- Google Takeout and Apple's download ----

    /** Takeout's "Google Play Store" folder: Purchase History.json and Order History.json, a list of purchases with a document and a price. */
    static void readGoogle(Path path, Collector c, String currency) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(path)) { try (Stream<Path> s = Files.walk(path)) { files.addAll(s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")).sorted().toList()); } }
        else files.add(path);
        int n = 0;
        for (Path f : files) {
            JsonNode root;
            try { root = M.readTree(text(Files.readAllBytes(f))); } catch (Exception e) { continue; }
            if (!root.isArray()) continue;
            int row = 0;
            for (JsonNode entry : root) {
                row++;
                JsonNode p = entry.has("purchaseHistory") ? entry.path("purchaseHistory") : entry.has("orderHistory") ? entry.path("orderHistory") : entry;
                String title = p.path("doc").path("title").asText(""), when = p.path("purchaseTime").asText(""), price = p.path("invoicePrice").asText("");
                if (title.isBlank() && p.path("lineItem").isArray()) { for (JsonNode li : p.path("lineItem")) { String t = li.path("doc").path("title").asText(""); if (!t.isBlank()) { title = t; break; } } when = p.path("creationTime").asText(when); price = p.path("totalPrice").asText(price); }
                if (title.isBlank()) continue;
                Matcher mm = MONEY.matcher(price);
                String[] money = mm.find() ? money(mm) : new String[]{number(price), ""};
                String kind = p.path("doc").path("documentType").asText("");
                c.add(isoDate(when), "Google Play" + (kind.isEmpty() ? "" : " (" + kind + ")"), "play.google.com", "", title, 1, money[0], money[1].isEmpty() ? currency : money[1], money[0], Map.of(), "purchase", f.getFileName() + "#" + row, "exact");
                n++;
            }
        }
        c.orders += n;
        if (n > 0) c.notes.add(files.size() + " file(s): " + n + " line(s)");
    }

    /** Apple's "Store Transaction History.csv": one row per item bought (apps, music, books, films), with the seller and the amount. */
    static void readApple(Path path, Collector c, String currency) throws IOException {
        List<List<String>> rows = Csv.parse(text(Files.readAllBytes(path)).replace("\uFEFF", ""));
        if (rows.size() < 2) throw ProtocolError.invalidArgs(path.getFileName() + " has no rows under its header.");
        List<String> head = rows.get(0).stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
        int title = col(head, new String[]{"item description", "item", "title", "description", "name"}), date = col(head, new String[]{"order date", "item published", "purchase date", "date"}),
            amount = col(head, new String[]{"item price", "amount", "price", "total"}), seller = col(head, new String[]{"item seller", "seller", "publisher", "artist"}), type = col(head, new String[]{"item type", "type"}),
            cur = col(head, new String[]{"currency"});
        if (title < 0 || date < 0) throw ProtocolError.invalidArgs("Apple's transaction history has an item and a date column; " + path.getFileName() + " has " + String.join(", ", head) + ".");
        int n = 0;
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.size() <= Math.max(title, date) || r.get(title).isBlank()) continue;
            String amt = amount >= 0 && amount < r.size() ? r.get(amount) : "";
            Matcher mm = MONEY.matcher(amt);
            String[] money = mm.find() ? money(mm) : new String[]{number(amt), cur >= 0 && cur < r.size() ? r.get(cur).strip() : ""};
            Map<String, String> ids = new LinkedHashMap<>();
            if (seller >= 0 && seller < r.size() && !r.get(seller).isBlank()) ids.put("seller", r.get(seller).strip());
            String kind = type >= 0 && type < r.size() ? r.get(type).strip() : "";
            c.add(isoDate(r.get(date)), "Apple" + (kind.isEmpty() ? "" : " (" + kind + ")"), "apple.com", "", r.get(title), 1, money[0], money[1].isEmpty() ? currency : money[1], money[0], ids, "purchase", path.getFileName() + "#" + (i + 1), "exact");
            n++;
        }
        c.orders += n;
        if (n > 0) c.notes.add(path.getFileName() + ": " + n + " line(s)");
    }

    // ---- reading the ledger ----

    /** What the ledger holds: lines, shops, the years covered, and where each source's records begin. */
    public static ObjectNode status(LibraryStore store) {
        List<Line> all = lines(store);
        ObjectNode r = M.createObjectNode();
        r.put("lines", all.size());
        Map<String, Integer> shops = new TreeMap<>(); Map<String, Integer> years = new TreeMap<>();
        int purchases = 0, payments = 0, receipts = 0;
        for (Line l : all) {
            shops.merge(l.shop().isEmpty() ? "(unknown shop)" : l.shop(), 1, Integer::sum);
            if (l.date().length() >= 4) years.merge(l.date().substring(0, 4), 1, Integer::sum);
            switch (l.kind()) { case "payment" -> payments++; case "receipt" -> receipts++; default -> purchases++; }
        }
        r.put("purchases", purchases); r.put("receipts", receipts); r.put("payments", payments);
        ObjectNode sh = r.putObject("shops"); shops.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(40).forEach(e -> sh.put(e.getKey(), e.getValue()));
        ObjectNode ys = r.putObject("years"); years.forEach(ys::put);
        var srcs = r.putArray("sources");
        for (Source s : sources(store)) { ObjectNode o = srcs.addObject(); o.put("file", s.file()); o.put("kind", s.kind()); o.put("lines", s.lines()); o.put("records_begin", s.firstDate()); o.put("records_end", s.lastDate()); o.put("imported_at", s.importedAt()); }
        return r;
    }

    /** The lines whose title, shop or order id carry every word, newest first; {@code year} and {@code shop} narrow it. */
    public static List<Line> find(LibraryStore store, String words, String year, String shop, int limit) {
        String[] ws = words == null ? new String[0] : words.toLowerCase(Locale.ROOT).strip().split("\\s+");
        List<Line> out = new ArrayList<>();
        for (Line l : lines(store)) {
            if (year != null && !year.isEmpty() && !l.date().startsWith(year)) continue;
            if (shop != null && !shop.isEmpty() && !l.shop().toLowerCase(Locale.ROOT).contains(shop.toLowerCase(Locale.ROOT))) continue;
            StringBuilder hb = new StringBuilder(l.title()).append(' ').append(l.shop()).append(' ').append(l.orderId());
            boolean fallback = l.title().startsWith("an order from ") || l.title().startsWith("a receipt from ");
            for (var e : l.ids().entrySet()) if (!e.getKey().equals("subject") || fallback) hb.append(' ').append(e.getValue());   // the mail's subject counts only when the line has no name of its own
            String hay = hb.toString().toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : ws) if (!w.isEmpty() && !hay.contains(w)) { all = false; break; }
            if (all) out.add(l);
        }
        out.sort((a, b) -> b.date().compareTo(a.date()));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** True when the person already has something that reads like this title: every word of the shorter title is in the other. */
    public static List<Line> alreadyOwned(LibraryStore store, String title) {
        if (title == null || title.isBlank()) return List.of();
        List<Line> out = new ArrayList<>();
        Set<String> want = wordsOf(title);
        if (want.size() < 2) return out;
        for (Line l : lines(store)) {
            if (!l.kind().equals("purchase")) continue;
            Set<String> have = wordsOf(l.title());
            Set<String> small = want.size() <= have.size() ? want : have, big = small == want ? have : want;
            if (small.size() >= 2 && big.containsAll(small)) out.add(l);
        }
        return out;
    }

    static Set<String> wordsOf(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() >= 2 && !STOP.contains(w)) out.add(w);
        return out;
    }

    static final Set<String> STOP = Set.of("the", "a", "an", "of", "for", "and", "with", "to", "in", "on", "by", "from", "pack", "set", "new", "black", "white");

    /** A reading unit for the Amazon export's CSVs and statements: RFC 4180, quotes and newlines inside quotes included. */
    static final class Csv {
        static List<List<String>> parse(String text) {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>(); StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (quoted) {
                    if (ch == '"') { if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; } else quoted = false; }
                    else cell.append(ch);
                } else if (ch == '"') quoted = true;
                else if (ch == ',') { row.add(cell.toString()); cell.setLength(0); }
                else if (ch == '\n' || ch == '\r') {
                    if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    row.add(cell.toString()); cell.setLength(0);
                    if (row.stream().anyMatch(x -> !x.isBlank())) rows.add(row);
                    row = new ArrayList<>();
                } else cell.append(ch);
            }
            if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); if (row.stream().anyMatch(x -> !x.isBlank())) rows.add(row); }
            return rows;
        }
    }
}
