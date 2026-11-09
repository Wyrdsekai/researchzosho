package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import java.nio.file.StandardCopyOption;
/**
 * The sources the library mines (docs/DESIGN_SOURCES.md): the places that list the things a field looks for, a register, a
 * directory, a board, a list page, a feed, a saved search, an index. Each is kept with its kind, its address, what it yields, what
 * it is for (a field, a place, a subject, a person), who found it (a run, a field's own table, the person), when it first and last
 * yielded and how much, and its state: active, dry (read for a stretch with nothing new), or off (the person took it off; never read
 * again, however often a search finds it). One list for every field, {@code catalog/sources.json}, so the Sources page, the command
 * {@code researchzosho sources}, the chat's {@code /sources} and the protocol's {@code sources} op show one thing.
 */
public final class Sources {
    private Sources() { }

    private static final ObjectMapper M = new ObjectMapper();
    public static final List<String> KINDS = List.of("register", "directory", "board", "list page", "feed", "saved search", "index", "serial", "podcast");
    /** The id a saved search or a followed podcast has in this list: the shelf's own name after this. They live in the shelves file and are shown here as one list. */
    public static final String SHELF = "shelf-";
    public static final List<String> STATES = List.of("active", "dry", "doubtful", "off");
    /** A source is doubtful when this many of the things it yielded fell at the first check and none survived; it is read first again only when one survives. */
    static final int DOUBTFUL_AFTER = 8;
    /** Days without a new yield after which a source of the kind is dry: read rarely until it yields again. */
    static final Map<String, Integer> DRY_AFTER = Map.of("register", 90, "directory", 90, "board", 30, "list page", 60, "feed", 30, "saved search", 30, "index", 90);

    /** One source as kept; {@code name} is what people call it ("Underscore VC's portfolio board"), "" when it has only its address. */
    public record Source(String id, String kind, String address, String yields, String field, String place, String subject, String forWhom, String foundBy, String note,
                         String firstRead, String lastYield, int yieldTotal, int yieldLast, String state, String at, int survived, int fell, String name) {
        public Source(String id, String kind, String address, String yields, String field, String place, String subject, String forWhom, String foundBy, String note,
                      String firstRead, String lastYield, int yieldTotal, int yieldLast, String state, String at, int survived, int fell) {
            this(id, kind, address, yields, field, place, subject, forWhom, foundBy, note, firstRead, lastYield, yieldTotal, yieldLast, state, at, survived, fell, "");
        }
        public boolean active() { return state.equals("active"); }
        public boolean off() { return state.equals("off"); }
        public boolean doubtful() { return state.equals("doubtful"); }
        /** Whether this is a saved search or a followed podcast, kept in the shelves file and run by the nightly check. */
        public boolean shelf() { return id.startsWith(SHELF); }
        /** The source in a sentence. */
        public String line() {
            StringBuilder b = new StringBuilder(id).append(": ").append(name.isEmpty() ? "" : name + ", ").append(address.isEmpty() ? "(no address)" : address).append(", a ").append(kind);
            if (!yields.isEmpty()) b.append(" that yields ").append(yields);
            if (!field.isEmpty() || !place.isEmpty() || !subject.isEmpty() || !forWhom.isEmpty()) b.append(", for ").append(String.join(", ", nonEmpty(field, subject, place, forWhom)));
            b.append("; found by ").append(foundBy.isEmpty() ? "nobody in particular" : foundBy);
            if (yieldTotal > 0) b.append("; yielded ").append(yieldTotal).append(yieldTotal == 1 ? " thing" : " things").append(" in all, ").append(yieldLast).append(" the last time (").append(lastYield).append(")");
            else b.append("; nothing yielded yet");
            if (survived > 0 || fell > 0) b.append("; of what it yielded, ").append(survived).append(" survived the first checks and ").append(fell).append(" fell");
            b.append("; ").append(state.equals("off") ? (shelf() ? "parked by you, not run until you bring it back" : "taken off by you") : state.equals("dry") ? "dry, read rarely" : state.equals("doubtful") ? "doubtful: not read first until something it yields survives" : "active");
            if (!note.isEmpty()) b.append(". Your note: ").append(note);
            return b.append('.').toString();
        }
    }

    static List<String> nonEmpty(String... xs) { List<String> out = new ArrayList<>(); for (String x : xs) if (x != null && !x.isEmpty()) out.add(x); return out; }

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("sources.json"); }

    static synchronized ObjectNode doc(LibraryStore store) throws IOException {
        Path f = file(store);
        if (!Files.isRegularFile(f)) { ObjectNode o = M.createObjectNode(); o.putArray("sources"); o.put("next", 1); return o; }
        JsonNode n = M.readTree(f.toFile());
        if (!n.isObject() || !n.has("sources")) { ObjectNode o = M.createObjectNode(); o.putArray("sources"); o.put("next", 1); return o; }
        return (ObjectNode) n;
    }

    static synchronized void save(LibraryStore store, ObjectNode doc) throws IOException {
        Files.createDirectories(file(store).getParent());
        Path tmp = file(store).resolveSibling("sources.json.tmp");
        M.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), doc);
        Files.move(tmp, file(store), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static Source of(JsonNode n) {
        return new Source(n.path("id").asText(""), n.path("kind").asText("list page"), n.path("address").asText(""), n.path("yields").asText(""), n.path("field").asText(""), n.path("place").asText(""),
                n.path("subject").asText(""), n.path("for").asText(""), n.path("found_by").asText(""), n.path("note").asText(""), n.path("first_read").asText(""), n.path("last_yield").asText(""),
                n.path("yield_total").asInt(0), n.path("yield_last").asInt(0), n.path("state").asText("active"), n.path("at").asText(""), n.path("survived").asInt(0), n.path("fell").asInt(0), n.path("name").asText(""));
    }

    static ObjectNode put(ObjectNode n, Source s) {
        return n.put("id", s.id()).put("kind", s.kind()).put("address", s.address()).put("yields", s.yields()).put("field", s.field()).put("place", s.place()).put("subject", s.subject()).put("for", s.forWhom())
                .put("found_by", s.foundBy()).put("note", s.note()).put("first_read", s.firstRead()).put("last_yield", s.lastYield()).put("yield_total", s.yieldTotal()).put("yield_last", s.yieldLast()).put("state", s.state()).put("at", s.at())
                .put("survived", s.survived()).put("fell", s.fell()).put("name", s.name());
    }

    /** An address as compared: lower-case host, no scheme, no trailing slash; a query is kept as its words. */
    static String key(String address) {
        String a = address == null ? "" : address.strip();
        if (a.isEmpty()) return "";
        try {
            if (a.matches("(?i)https?://.*")) {
                URI u = URI.create(a);
                String h = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT), p = u.getRawPath() == null ? "" : u.getRawPath();
                return (h.startsWith("www.") ? h.substring(4) : h) + p.replaceFirst("/+$", "") + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            }
        } catch (IllegalArgumentException ignored) { }
        return a.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** The kind an address looks like, when the person gives none: a feed by its ending, a board by its hiring system, else a list page; words are a saved search. */
    public static String kindOf(String address) {
        String a = address == null ? "" : address.strip().toLowerCase(Locale.ROOT);
        if (!a.matches("https?://.*")) return "saved search";
        if (a.matches(".*(\\.rss|\\.atom|/feed/?|/rss/?|\\.xml)(\\?.*)?$")) return "feed";
        if (!CareerSources.boardsIn(a, "").isEmpty()) return "board";
        return "list page";
    }

    /** Every source, off ones included, in the order they were kept; then the saved searches and the followed podcasts from the shelves file, so this is the one list. */
    public static List<Source> all(LibraryStore store) throws IOException {
        List<Source> out = kept(store);
        out.addAll(Serials.asSources(store));
        return out;
    }

    /** The sources of this file alone, without the shelves. */
    static List<Source> kept(LibraryStore store) throws IOException {
        List<Source> out = new ArrayList<>(); for (JsonNode n : doc(store).path("sources")) out.add(of(n)); return out;
    }

    /** One by id; null when none. */
    public static Source get(LibraryStore store, String id) throws IOException {
        for (Source s : all(store)) if (s.id().equalsIgnoreCase(id == null ? "" : id.strip())) return s; return null;
    }

    /** The sources a run of {@code field} reads first: active, for that field or for every field, and for the place and subject when the source names one. */
    public static List<Source> active(LibraryStore store, String field, String place, String subject) throws IOException {
        List<Source> out = new ArrayList<>();
        for (Source s : all(store)) {
            if (!s.active() || s.shelf()) continue;   // a saved search or a podcast is the nightly check's to run, not a search's to read
            if (!s.field().isEmpty() && !s.field().equalsIgnoreCase(field)) continue;
            boolean mine = s.foundBy().equals("you"), forPlace = place != null && !place.isEmpty();
            if (!s.place().isEmpty() && forPlace && !overlap(place, s.place())) continue;   // found for another place
            if (!s.place().isEmpty() && !forPlace && !mine) continue;   // found for a place, read by a search with none: no
            if (s.place().isEmpty() && forPlace && !mine && !overlap(place, s.forWhom())) continue;   // found for no place: not read for a place, unless the person added it
            if (!s.subject().isEmpty() && subject != null && !subject.isEmpty() && !overlap(subject, s.subject())) continue;
            out.add(s);
        }
        return out;
    }

    /** Whether two sets of place or subject words share a word of three letters or more ("Boston, MA" and "Boston"; "nurses in Lyon" and "Lyon"). */
    static boolean overlap(String a, String b) {
        for (String w : (a == null ? "" : a).split("[^\\p{L}\\p{N}]+")) if (w.length() >= 3 && CareerCut.has(b, w)) return true;
        return false;
    }

    /**
     * A source kept, by the person or by a run: the same address again is the same source, its blanks filled in; one the person took
     * off stays off. {@code foundBy} names who: "you", "the career search", "run J-0012", "the field's table".
     */
    public static Source add(LibraryStore store, String kind, String address, String yields, String field, String place, String subject, String forWhom, String foundBy, String note) throws IOException {
        return add(store, kind, address, yields, field, place, subject, forWhom, foundBy, note, "");
    }

    /** As above, with what people call the source. Words instead of an address are a search to run again: they go to the shelves and come back as a serial. */
    public static Source add(LibraryStore store, String kind, String address, String yields, String field, String place, String subject, String forWhom, String foundBy, String note, String name) throws IOException {
        String k = key(address);
        if (k.isEmpty()) throw new IllegalArgumentException("A source needs an address, or the words of a search.");
        if (kindOr(kind, address).equals("saved search") && !address.strip().matches("(?i)https?://.*")) {
            String slug = Serials.slugFor(store, address.strip());
            for (Source s : Serials.asSources(store)) if (key(s.address()).equals(k)) return s;   // the same words again: the same search
            Serials.add(store, slug, address.strip(), 7);
            return Serials.asSource(store, slug);
        }
        ObjectNode d = doc(store);
        ArrayNode arr = (ArrayNode) d.get("sources");
        for (int i = 0; i < arr.size(); i++) {
            Source s = of(arr.get(i));
            if (!key(s.address()).equals(k)) continue;
            Source merged = new Source(s.id(), s.kind().isEmpty() ? kindOr(kind, address) : s.kind(), s.address(), s.yields().isEmpty() ? nz(yields) : s.yields(), s.field().isEmpty() ? nz(field) : s.field(),
                    s.place().isEmpty() ? nz(place) : s.place(), s.subject().isEmpty() ? nz(subject) : s.subject(), s.forWhom().isEmpty() ? nz(forWhom) : s.forWhom(), s.foundBy(),
                    s.note().isEmpty() ? nz(note) : s.note(), s.firstRead(), s.lastYield(), s.yieldTotal(), s.yieldLast(), s.state(), s.at(), s.survived(), s.fell(), s.name().isEmpty() ? nz(name) : s.name());
            arr.set(i, put(M.createObjectNode(), merged));
            save(store, d);
            return merged;
        }
        int n = d.path("next").asInt(1);
        Source s = new Source(String.format("S-%04d", n), kindOr(kind, address), address.strip(), nz(yields), nz(field), nz(place), nz(subject), nz(forWhom), nz(foundBy), nz(note), "", "", 0, 0, "active", LocalDate.now().toString(), 0, 0, nz(name));
        arr.add(put(M.createObjectNode(), s));
        d.put("next", n + 1);
        save(store, d);
        return s;
    }

    static String kindOr(String kind, String address) { String k = kind == null ? "" : kind.strip().toLowerCase(Locale.ROOT); return KINDS.contains(k) ? k : kindOf(address); }
    static String nz(String s) { return s == null ? "" : s.strip(); }

    /**
     * A run read a source: what it yielded this time goes on the record, and the state follows, active while it yields, dry when nothing
     * new came for the kind's stretch. A source the person took off is left as it is and not read: the caller asks {@link #active} first.
     */
    public static Source read(LibraryStore store, String address, String kind, String yields, String field, String place, String subject, String foundBy, int yielded) throws IOException {
        if (SourceRules.load(store).refused(address)) return null;   // a host the person refused is never kept as a source, whatever it yields
        Source s = add(store, kind, address, yields, field, place, subject, "", foundBy, "");
        ObjectNode d = doc(store);
        ArrayNode arr = (ArrayNode) d.get("sources");
        for (int i = 0; i < arr.size(); i++) {
            Source k = of(arr.get(i));
            if (!k.id().equals(s.id())) continue;
            if (k.off()) return k;
            String today = LocalDate.now().toString();
            String first = k.firstRead().isEmpty() ? today : k.firstRead(), last = yielded > 0 ? today : k.lastYield();
            String state = k.doubtful() ? "doubtful" : yielded > 0 ? "active" : dry(k.kind(), last.isEmpty() ? first : last) ? "dry" : k.state();   // volume never promotes: a doubtful source stays so until something it yields survives
            Source r = new Source(k.id(), k.kind(), k.address(), k.yields(), k.field(), k.place(), k.subject(), k.forWhom(), k.foundBy().isEmpty() ? nz(foundBy) : k.foundBy(), k.note(), first, last, k.yieldTotal() + Math.max(0, yielded), Math.max(0, yielded), state, k.at(), k.survived(), k.fell(), k.name());
            arr.set(i, put(M.createObjectNode(), r));
            save(store, d);
            return r;
        }
        return s;
    }

    /**
     * What a source's yield survived, from the checks downstream: {@code survived} things that passed the first checks (an opening that
     * passes, a reading worth approaching, a like), {@code fell} things cut or set aside. A source that yielded many and none survived is
     * doubtful and not read first; one survivor brings it back. The state after.
     */
    public static Source score(LibraryStore store, String id, int survived, int fell) throws IOException {
        ObjectNode d = doc(store);
        ArrayNode arr = (ArrayNode) d.get("sources");
        for (int i = 0; i < arr.size(); i++) {
            Source k = of(arr.get(i));
            if (!k.id().equalsIgnoreCase(id == null ? "" : id.strip())) continue;
            int sv = k.survived() + Math.max(0, survived), fl = k.fell() + Math.max(0, fell);
            String state = k.off() ? "off" : sv > 0 && k.doubtful() ? "active" : sv == 0 && fl >= DOUBTFUL_AFTER ? "doubtful" : k.state();
            Source r = new Source(k.id(), k.kind(), k.address(), k.yields(), k.field(), k.place(), k.subject(), k.forWhom(), k.foundBy(), k.note(), k.firstRead(), k.lastYield(), k.yieldTotal(), k.yieldLast(), state, k.at(), sv, fl, k.name());
            arr.set(i, put(M.createObjectNode(), r));
            save(store, d);
            return r;
        }
        return null;
    }

    static boolean dry(String kind, String since) {
        try { return LocalDate.parse(since).isBefore(LocalDate.now().minusDays(DRY_AFTER.getOrDefault(kind, 60))); } catch (Exception e) { return false; }
    }

    /** The person takes a source off: never read again until brought back. Whether it was there. */
    public static boolean remove(LibraryStore store, String id) throws IOException { return state(store, id, "off"); }

    /** The person brings a source back. */
    public static boolean restore(LibraryStore store, String id) throws IOException { return state(store, id, "active"); }

    static boolean state(LibraryStore store, String id, String state) throws IOException {
        String want = id == null ? "" : id.strip();
        if (want.toLowerCase(Locale.ROOT).startsWith(SHELF)) {   // a saved search or a podcast: taken off is parked, brought back is in the rotation again
            String slug = want.substring(SHELF.length());
            if (Serials.shelves(store).stream().noneMatch(x -> x.slug().equalsIgnoreCase(slug))) return false;
            Serials.setParked(store, slug, state.equals("off"));
            return true;
        }
        ObjectNode d = doc(store);
        ArrayNode arr = (ArrayNode) d.get("sources");
        for (int i = 0; i < arr.size(); i++) {
            Source s = of(arr.get(i));
            if (!s.id().equalsIgnoreCase(id == null ? "" : id.strip())) continue;
            arr.set(i, put(M.createObjectNode(), new Source(s.id(), s.kind(), s.address(), s.yields(), s.field(), s.place(), s.subject(), s.forWhom(), s.foundBy(), s.note(), s.firstRead(), s.lastYield(), s.yieldTotal(), s.yieldLast(), state, s.at(), s.survived(), s.fell(), s.name())));
            save(store, d);
            return true;
        }
        return false;
    }

    /** The sources as a document for the protocol and the page. */
    public static ArrayNode json(List<Source> xs) { ArrayNode a = M.createArrayNode(); for (Source s : xs) put(a.addObject(), s); return a; }

    /** The sources in sentences, for the command line and the chat; {@code how} is how the person types the command here. */
    public static String text(List<Source> xs, boolean withOff, String how) {
        List<Source> shown = new ArrayList<>(); for (Source s : xs) if (withOff || !s.off()) shown.add(s);
        if (shown.isEmpty()) return "The library has no sources of its own yet. A search keeps the boards, registers and list pages it reads; " + how + " add <address> adds one of yours.";
        StringBuilder b = new StringBuilder("The sources the library mines, " + shown.size() + " in all:\n");
        for (Source s : shown) b.append("- ").append(s.line()).append('\n');
        b.append(how).append(" add <address> --for <words> adds one, and ").append(how).append(" add <words> keeps a search to run again; ").append(how).append(" remove <id> takes one off, or parks a saved search; ").append(how).append(" restore <id> brings it back.");
        return b.toString();
    }
}
