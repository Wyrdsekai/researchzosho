package org.researchzosho.librarian;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A reading list as a starting point: BibTeX, RIS (Zotero, EndNote), a CSV export, or plain lines of
 * DOIs, urls and titles. Every entry with a locator is fetched and shelved into a collection; an entry
 * with a title only is reported so the person can give it a locator or research it by title.
 */
public final class Reading {

    public record Entry(String title, String locator, String note) {
        public boolean located() { return !locator.isEmpty(); }
    }

    static final int MAX_ENTRIES = org.researchzosho.Config.getInt("RESEARCHZOSHO_READING_MAX", 200);
    static final Pattern BIB_FIELD = Pattern.compile("(\\w+)\\s*=\\s*(?:\\{((?:[^{}]|\\{[^{}]*\\})*)\\}|\"([^\"]*)\"|(\\S+?))\\s*(?:,|$)", Pattern.MULTILINE);
    static final Pattern BARE_DOI = Pattern.compile("\\b(10\\.\\d{4,9}/[^\\s\"'<>)\\]]+)");

    private Reading() { }

    public static List<Entry> parse(String text) {
        String head = text.stripLeading();
        List<Entry> out;
        if (head.startsWith("@")) out = bibtex(text);
        else if (text.contains("\nTY  - ") || head.startsWith("TY  - ")) out = ris(text);
        else if (Items.looksCsv(text.split("\\r?\\n"))) out = csv(text);
        else out = lines(text);
        Set<String> seen = new LinkedHashSet<>();
        List<Entry> uniq = new ArrayList<>();
        for (Entry e : out) { String k = (e.locator().isEmpty() ? e.title() : e.locator()).toLowerCase(Locale.ROOT); if (!k.isEmpty() && seen.add(k)) uniq.add(e); if (uniq.size() >= MAX_ENTRIES) break; }
        return uniq;
    }

    /** Entries by brace depth: {@code @type{key, field = {…}, …}} on one line or many. */
    static List<Entry> bibtex(String text) {
        List<Entry> out = new ArrayList<>();
        int i = 0;
        while ((i = text.indexOf('@', i)) >= 0) {
            int open = text.indexOf('{', i);
            if (open < 0) break;
            String type = text.substring(i + 1, open).strip();
            if (!type.matches("\\w+") || type.equalsIgnoreCase("comment") || type.equalsIgnoreCase("preamble") || type.equalsIgnoreCase("string")) { i = open + 1; continue; }
            int depth = 1, j = open + 1;
            while (j < text.length() && depth > 0) { char c = text.charAt(j); if (c == '{') depth++; else if (c == '}') depth--; j++; }
            String inner = text.substring(open + 1, Math.max(open + 1, j - 1));
            int comma = inner.indexOf(',');
            String key = comma < 0 ? inner.strip() : inner.substring(0, comma).strip();
            java.util.Map<String, String> f = new java.util.HashMap<>();
            Matcher fm = BIB_FIELD.matcher(comma < 0 ? "" : inner.substring(comma + 1));
            while (fm.find()) { String v = fm.group(2) != null ? fm.group(2) : fm.group(3) != null ? fm.group(3) : fm.group(4); f.put(fm.group(1).toLowerCase(Locale.ROOT), clean(v)); }
            String title = f.getOrDefault("title", key);
            String loc = locator(f.getOrDefault("doi", ""), f.getOrDefault("url", ""), f.getOrDefault("eprint", ""));
            out.add(new Entry(title, loc, note(f.get("author"), f.get("year"), f.get("journal"), f.get("booktitle"))));
            i = j;
        }
        return out;
    }

    static List<Entry> ris(String text) {
        List<Entry> out = new ArrayList<>();
        String title = "", doi = "", url = "", author = "", year = "", venue = "";
        for (String line : text.split("\\r?\\n")) {
            if (line.length() < 6 || line.charAt(2) != ' ' || line.charAt(4) != '-') continue;
            String tag = line.substring(0, 2), v = line.substring(6).strip();
            switch (tag) {
                case "TY" -> { title = ""; doi = ""; url = ""; author = ""; year = ""; venue = ""; }
                case "TI", "T1" -> title = title.isEmpty() ? v : title;
                case "DO" -> doi = v;
                case "UR", "L1", "L2" -> url = url.isEmpty() ? v : url;
                case "AU", "A1" -> author = author.isEmpty() ? v : author;
                case "PY", "Y1" -> year = v.replaceAll("[/-].*$", "");
                case "JO", "JF", "T2" -> venue = venue.isEmpty() ? v : venue;
                case "ER" -> { if (!title.isEmpty() || !doi.isEmpty() || !url.isEmpty()) out.add(new Entry(title.isEmpty() ? (doi.isEmpty() ? url : doi) : title, locator(doi, url, ""), note(author, year, venue, null))); title = ""; }
                default -> { }
            }
        }
        return out;
    }

    static List<Entry> csv(String text) {
        List<Entry> out = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        String[] header = Items.splitCsv(lines[0]);
        int ti = -1, di = -1, ui = -1, ai = -1, yi = -1;
        for (int i = 0; i < header.length; i++) {
            String h = header[i].strip().toLowerCase(Locale.ROOT);
            if (ti < 0 && (h.equals("title") || h.equals("name"))) ti = i;
            else if (h.equals("doi")) di = i;
            else if (ui < 0 && (h.equals("url") || h.equals("link"))) ui = i;
            else if (ai < 0 && h.startsWith("author")) ai = i;
            else if (yi < 0 && (h.equals("year") || h.equals("publication year") || h.equals("date"))) yi = i;
        }
        for (int r = 1; r < lines.length; r++) {
            if (lines[r].isBlank()) continue;
            String[] c = Items.splitCsv(lines[r]);
            String title = ti >= 0 && ti < c.length ? c[ti].strip() : c[0].strip();
            String loc = locator(di >= 0 && di < c.length ? c[di] : "", ui >= 0 && ui < c.length ? c[ui] : "", "");
            if (title.isEmpty() && loc.isEmpty()) continue;
            out.add(new Entry(title.isEmpty() ? loc : title, loc, note(ai >= 0 && ai < c.length ? c[ai] : null, yi >= 0 && yi < c.length ? c[yi] : null, null, null)));
        }
        return out;
    }

    /** Plain lines: a url, a DOI, or a title with an optional note after a dash. */
    static List<Entry> lines(String text) {
        List<Entry> out = new ArrayList<>();
        for (Items.Item it : Items.parse(text, null)) {
            String s = it.name();
            String loc = "";
            Matcher u = Drafts.URL.matcher(s); Matcher d = Citations.DOI.matcher(s); Matcher b = BARE_DOI.matcher(s);
            if (u.find()) loc = u.group();
            else if (d.find()) loc = Shelving.doiUrl(d.group(1));
            else if (b.find()) loc = Shelving.doiUrl(b.group(1));
            String title = loc.isEmpty() ? s : s.replace(loc, "").replaceAll("^\\s*[—–-]\\s*|\\s*[—–-]\\s*$", "").strip();
            if (!loc.isEmpty() && s.contains("doi:")) title = title.replaceAll("(?i)doi:\\s*\\S+", "").strip();
            String note = it.note();
            if (title.isEmpty() && !note.isEmpty()) { title = note; note = ""; }   // "url — the title" form
            if (title.isEmpty()) title = loc;
            out.add(new Entry(title, loc, note));
        }
        return out;
    }

    static String locator(String doi, String url, String eprint) {
        String d = clean(doi);
        if (!d.isEmpty()) { Matcher m = BARE_DOI.matcher(d); if (m.find()) return Shelving.doiUrl(m.group(1)); }
        String u = clean(url);
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        String e = clean(eprint);
        if (e.matches("\\d{4}\\.\\d{4,5}(v\\d+)?")) return "https://arxiv.org/abs/" + e;
        return "";
    }

    static String note(String author, String year, String venue, String booktitle) {
        StringBuilder sb = new StringBuilder();
        String a = clean(author);
        if (!a.isEmpty()) sb.append(a.split(" and ")[0].strip()).append(a.contains(" and ") ? " et al." : "");
        String y = clean(year); if (!y.isEmpty()) sb.append(sb.length() > 0 ? ", " : "").append(y);
        String v = clean(venue); if (v.isEmpty()) v = clean(booktitle);
        if (!v.isEmpty()) sb.append(sb.length() > 0 ? ", " : "").append(v);
        return sb.toString();
    }

    static String clean(String s) { return s == null ? "" : s.replaceAll("[{}]", "").replaceAll("\\s+", " ").strip(); }
}
