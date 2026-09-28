package org.researchzosho.librarian;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A person's number on a family-tree site: a FamilySearch person id, a WikiTree id, a Find a Grave memorial, a Geni profile. Two
 * people with the same number on one site are one person there; two different numbers on one site are two people. A GEDCOM file
 * carries these ({@code _FSFTID}, {@code EXID} with its {@code TYPE}); a person page carries one in its address. Ids a file uses only
 * inside itself ({@code _UID}, {@code REFN}, {@code RIN}, {@code AFN}) and citation ids ({@code _APID}) name nobody outside it and are
 * not kept. Stored in {@code catalog/graph/person-ids.tsv}: name, site, id, where it came from.
 */
public final class PersonIds {

    private PersonIds() { }

    /** One id: the site ("familysearch"), the id on it, and where the library read it. */
    public record Id(String site, String id, String from) {
        public String shown() { return NAMES.getOrDefault(site, site) + " id " + id; }
    }

    /** site → [host, the path part the id follows ("" when it is the last part), a prefix the id carries in the path]. */
    private static final Map<String, String[]> SITES = new LinkedHashMap<>();
    private static final Map<String, String> NAMES = new LinkedHashMap<>();
    static {
        site("familysearch", "FamilySearch", "familysearch.org", "details", "");
        site("wikitree", "WikiTree", "wikitree.com", "wiki", "");
        site("findagrave", "Find a Grave", "findagrave.com", "memorial", "");
        site("geni", "Geni", "geni.com", "", "");
        site("myheritage", "MyHeritage", "myheritage.com", "", "person-");
    }

    private static void site(String id, String name, String host, String after, String prefix) { SITES.put(id, new String[]{host, after, prefix}); NAMES.put(id, name); }

    static Path file(LibraryStore store) { return Graph.dir(store).resolve("person-ids.tsv"); }

    /** The site an address or a GEDCOM 7 {@code TYPE} belongs to, or "" when it is none of the known ones. */
    public static String siteOf(String url) {
        String host = host(url);
        if (host.isEmpty()) return "";
        for (Map.Entry<String, String[]> s : SITES.entrySet()) if (host.equals(s.getValue()[0]) || host.endsWith("." + s.getValue()[0])) return s.getKey();
        return "";
    }

    /** The person id a person page's address carries, or null. */
    public static Id fromUrl(String url) {
        String site = siteOf(url);
        if (site.isEmpty()) return null;
        String[] rule = SITES.get(site);
        List<String> parts = new ArrayList<>();
        try {
            String path = URI.create(url.strip()).getRawPath();
            for (String p : (path == null ? "" : path).split("/")) if (!p.isBlank()) parts.add(URLDecoder.decode(p, StandardCharsets.UTF_8));
        } catch (Exception e) { return null; }
        String found = null;
        if (!rule[1].isEmpty()) { int at = parts.indexOf(rule[1]); if (at >= 0 && at + 1 < parts.size()) found = parts.get(at + 1); }
        else if (!rule[2].isEmpty()) { for (String p : parts) if (p.startsWith(rule[2])) { found = p.substring(rule[2].length()); break; } }
        else if (parts.size() >= 2 && parts.get(0).equals("people") && parts.get(parts.size() - 1).matches("\\d{6,}")) found = parts.get(parts.size() - 1);
        return found == null || found.isBlank() ? null : new Id(site, found, url.strip());
    }

    /** The ids kept for a person, by node id; merges and other names are followed through the graph. */
    public static Map<String, Set<Id>> all(LibraryStore store, Graph g) throws IOException {
        Map<String, Set<Id>> out = new LinkedHashMap<>();
        if (!Files.exists(file(store))) return out;
        for (String line : Files.readAllLines(file(store), StandardCharsets.UTF_8)) {
            String[] p = line.split("\t");
            if (p.length < 3 || line.startsWith("#")) continue;
            out.computeIfAbsent(g.nodeIdOf(p[0]), k -> new LinkedHashSet<>()).add(new Id(p[1], p[2], p.length > 3 ? p[3] : ""));
        }
        return out;
    }

    /** Keep an id for a person, once. */
    public static synchronized void add(LibraryStore store, String person, Id id) throws IOException {
        Path f = file(store);
        Files.createDirectories(f.getParent());
        String line = person.replace('\t', ' ') + "\t" + id.site() + "\t" + id.id().replace('\t', ' ') + "\t" + id.from().replace('\t', ' ');
        if (Files.exists(f)) for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String[] p = l.split("\t");
            if (p.length >= 3 && p[0].equals(person) && p[1].equals(id.site()) && p[2].equals(id.id())) return;
        }
        Files.writeString(f, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Two people's ids: "same" when they share one, "different" when one site gives them two different ids, "" otherwise. */
    static String compare(Set<Id> a, Set<Id> b, List<String> why) {
        if (a == null || b == null) return "";
        for (Id x : a) for (Id y : b) if (x.site().equals(y.site()) && x.id().equalsIgnoreCase(y.id())) { why.add(x.shown()); return "same"; }
        for (Id x : a) for (Id y : b) if (x.site().equals(y.site())) { why.add(x.shown() + " and " + y.id()); return "different"; }
        return "";
    }

    private static String host(String url) {
        try {
            String h = URI.create(url == null ? "" : url.strip()).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (Exception e) { return ""; }
    }
}
