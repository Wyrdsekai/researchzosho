package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The kinds of doing (0.6.1, docs/AFTER_0_6.md §1): every human craft or hobby the open taxonomies name, one entry each — English name,
 * Japanese name, parents, the taxonomies it came from with their identifiers, the designations (Japan's 伝統的工芸品 with category and
 * prefecture, the Heritage Crafts Red List with its risk band), and whether it is a sport (doing, not making). Shipped as
 * {@code crafts/kinds.jsonl}, made by {@code scripts/crafts-kinds.py} from Wikidata's class trees, Wikipedia's outline of crafts, the Kōgei
 * association's list and the Red List; never written by hand, and cut by the person who keeps the library ({@code keep}). The crafts field
 * matches an ask against it and tells the run what is known of the kind it is about.
 */
public final class Crafts {
    private Crafts() { }

    static final ObjectMapper M = new ObjectMapper();

    /** One kind of doing. */
    public record Kind(String id, String en, String ja, List<String> aliases, List<String> kinds, List<String> parents, List<JsonNode> sources, String region, String risk, String designation, String desc, boolean sport, boolean olympic, String website, boolean keep, String cut) {
        public String name() { return en.isEmpty() ? ja : ja.isEmpty() ? en : en + " (" + ja + ")"; }
        public boolean martialArt() { return kinds.contains("martial-art"); }
        public boolean trade() { return kinds.contains("trade"); }
        /** A society, a federation, a game system or a reenactment group people join: it has rules, a kit its rules demand, and a way in. */
        public boolean community() { return kinds.contains("community"); }
    }

    private static volatile List<Kind> ALL;

    /** Every kind the list holds, kept or not; loaded once. */
    public static List<Kind> all() {
        List<Kind> a = ALL;
        if (a != null) return a;
        synchronized (Crafts.class) {
            if (ALL != null) return ALL;
            List<Kind> out = new ArrayList<>();
            try (InputStream in = Crafts.class.getResourceAsStream("/crafts/kinds.jsonl")) {
                if (in != null) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.isBlank()) continue;
                        JsonNode j = M.readTree(line);
                        List<String> kinds = new ArrayList<>(), parents = new ArrayList<>(), aliases = new ArrayList<>(); List<JsonNode> sources = new ArrayList<>();
                        j.path("kind").forEach(x -> kinds.add(x.asText())); j.path("parents").forEach(x -> parents.add(x.asText())); j.path("aliases").forEach(x -> aliases.add(x.asText())); j.path("sources").forEach(sources::add);
                        out.add(new Kind(j.path("id").asText(), j.path("en").asText(""), j.path("ja").asText(""), aliases, kinds, parents, sources, j.path("region").asText(""), j.path("risk").asText(""),
                                j.path("designation").asText(""), j.path("desc").asText(""), j.path("sport").asBoolean(false), j.path("olympic").asBoolean(false), j.path("website").asText(""), j.path("keep").asBoolean(true), j.path("cut").asText("")));
                    }
                }
            } catch (IOException e) { throw new IllegalStateException("the kinds of doing could not be read: " + e.getMessage(), e); }
            ALL = List.copyOf(out);
            return ALL;
        }
    }

    public static Kind byId(String id) { for (Kind k : all()) if (k.id().equals(id)) return k; return null; }

    /** The kinds a text names: whose English name or other name (a word of three letters or more, whole; a short capitalised name such as PRS or
     *  IPSC as it is written) or Japanese name (two characters or more) appears in it; the kept ones, longest names first. */
    public static List<Kind> find(String text) {
        if (text == null || text.isBlank()) return List.of();
        String t = " " + loose(text) + " ";
        Map<String, Kind> hits = new LinkedHashMap<>();
        List<Kind> sorted = new ArrayList<>(all());
        sorted.sort((a, b) -> Integer.compare(b.name().length(), a.name().length()));
        for (Kind k : sorted) {
            if (!k.keep()) continue;
            boolean hit = named(t, text, k.en());
            for (String a : k.aliases()) if (!hit) hit = named(t, text, a);
            if (!hit && k.ja().length() >= 2 && text.contains(k.ja())) hit = true;
            if (hit) hits.putIfAbsent(k.id(), k);
            if (hits.size() >= 12) break;
        }
        return new ArrayList<>(hits.values());
    }

    private static final Pattern SHORT_NAME = Pattern.compile("[A-Z][A-Z0-9-]{1,5}");

    /** Lower case, with every punctuation mark a space, so "iaido," and "F-Class" meet "iaido" and "f class" on word boundaries. */
    static String loose(String x) { return Vocabulary.norm(x).replaceAll("[^\\p{L}\\p{N}]+", " ").strip(); }

    /** Whether a name is in the text: a short capitalised name (PRS, IPSC, F-Class is not one) whole and as written, any other name whole after normalising, a plural allowed. */
    static boolean named(String normalised, String text, String name) {
        if (name == null || name.isBlank()) return false;
        if (SHORT_NAME.matcher(name).matches()) return Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(name) + "(?![A-Za-z0-9])").matcher(text).find();
        String n = loose(name);
        if (n.length() < 3) return false;
        if (normalised.contains(" " + n + " ") || (!n.contains(" ") && normalised.contains(" " + n + "s "))) return true;
        Matcher art = ART_SUFFIX.matcher(n);   // a one-word art name by its stem: "wudang sword" finds Wudangquan, "naginata" Naginatajutsu
        return !n.contains(" ") && art.find() && art.start() >= 5 && normalised.contains(" " + n.substring(0, art.start()) + " ");
    }

    /** The word for the art at the end of an East Asian name: the stem before it names the implement, the place or the founder. */
    private static final Pattern ART_SUFFIX = Pattern.compile("(quan|jutsu|jitsu|do|ryu|ryū|kai|kan|kempo|kenpo|zhang|dao|jian)$");

    /** What the list knows of a kind, in lines a run can read: names, what it is, where it sits, the designations, the sources. */
    public static String describe(Kind k) {
        StringBuilder b = new StringBuilder("- ").append(k.name());
        if (!k.desc().isEmpty()) b.append(": ").append(k.desc());
        if (!k.keep()) b.append(" [cut: ").append(k.cut().isEmpty() ? "by hand" : k.cut()).append("]");
        if (k.community()) b.append(" (a community people join: it has its rules, the kit its rules demand, and a way in").append(k.website().isEmpty() ? ")" : "; its site " + k.website() + ")");
        else if (k.martialArt()) b.append(" (a martial art: doing, not making").append(k.olympic() ? ", an Olympic sport)" : ")");
        else if (k.sport()) b.append(" (a sport: doing, not making").append(k.olympic() ? ", an Olympic sport)" : ")");
        else if (k.trade()) b.append(" (a trade)");
        if (!k.aliases().isEmpty()) b.append("; also ").append(String.join(", ", k.aliases()));
        List<String> parents = new ArrayList<>();
        for (String p : k.parents()) { Kind pk = byId(p); if (pk != null) parents.add(pk.name()); }
        if (!parents.isEmpty()) {
            b.append(k.community() ? "; its members do " : "; a kind of ").append(String.join(", ", parents));
            Kind up = byId(k.parents().get(0));   // the chain above the first parent: a discipline under its sport under its family
            for (int i = 0; i < 3 && up != null && !up.parents().isEmpty(); i++) { up = byId(up.parents().get(0)); if (up != null) b.append(", under ").append(up.name()); }
        }
        if (!k.designation().isEmpty()) b.append("; ").append(k.designation()).append(k.region().isEmpty() ? "" : ", " + k.region());
        if (!k.risk().isEmpty()) b.append("; on the Heritage Crafts Red List: ").append(k.risk().toLowerCase(Locale.ROOT)).append(k.region().isEmpty() ? "" : " (" + k.region() + ")");
        List<String> src = new ArrayList<>();
        for (JsonNode s : k.sources()) { String u = s.path("url").asText(""); if (!u.isEmpty()) src.add(u); }
        if (!src.isEmpty()) b.append("; ").append(String.join(" ", src));
        return b.toString();
    }
}
