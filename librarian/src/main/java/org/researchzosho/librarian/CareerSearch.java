package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the career field's search uses besides the person's topics (docs/DESIGN_CAREER.md, step 3): the companies they want looked at, and
 * what they want kept out, a company, an industry, a kind of work or a condition. From the interview's two closing questions, from the
 * Career page and from the chat. Kept in {@code career/<person>/search.json}.
 */
public final class CareerSearch {
    private CareerSearch() { }

    private static final ObjectMapper M = new ObjectMapper();

    static Path file(LibraryStore store, Career.Seeker s) { return s.dir(store).resolve("search.json"); }

    /** The search profile as kept; empty lists when there is none yet. */
    public static ObjectNode profile(LibraryStore store, Career.Seeker s) throws IOException {
        Path f = file(store, s);
        ObjectNode o = Files.isRegularFile(f) && M.readTree(f.toFile()).isObject() ? (ObjectNode) M.readTree(f.toFile()) : M.createObjectNode();
        if (!o.path("companies").isArray()) o.putArray("companies");
        if (!o.path("keep_out").isArray()) o.putArray("keep_out");
        return o;
    }

    static void save(LibraryStore store, Career.Seeker s, ObjectNode o) throws IOException { M.writerWithDefaultPrettyPrinter().writeValue(file(store, s).toFile(), o); }

    static String key(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", ""); }

    /** A company the person wants looked at; said again, kept once. */
    public static boolean addCompany(LibraryStore store, Career.Seeker s, String name, String from) throws IOException {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) return false;
        ObjectNode o = profile(store, s);
        for (JsonNode c : o.path("companies")) if (key(c.path("name").asText()).equals(key(n))) return false;
        ((ArrayNode) o.get("companies")).addObject().put("name", n).put("by", "you").put("from", from == null ? "" : from).put("at", LocalDate.now().toString());
        save(store, s, o);
        Career.log(store, s, "The search will look at " + n + (from == null || from.isBlank() ? "." : ", from " + from + "."));
        return true;
    }

    /** Something the person wants kept out: a company, an industry, a kind of work, a condition; said again, kept once. */
    public static boolean addKeepOut(LibraryStore store, Career.Seeker s, String words, String from) throws IOException {
        String w = words == null ? "" : words.strip();
        if (w.isEmpty()) return false;
        ObjectNode o = profile(store, s);
        for (JsonNode k : o.path("keep_out")) if (key(k.path("text").asText()).equals(key(w))) return false;
        ((ArrayNode) o.get("keep_out")).addObject().put("text", w).put("from", from == null ? "" : from).put("at", LocalDate.now().toString());
        save(store, s, o);
        Career.log(store, s, "The search will keep out " + w + (from == null || from.isBlank() ? "." : ", from " + from + "."));
        return true;
    }

    /** A person or an employer the person named, with how: an employer they would want, one where they know people, or a person; said again, kept once. */
    public static boolean addNamed(LibraryStore store, Career.Seeker s, String name, String kind, String from) throws IOException {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty()) return false;
        ObjectNode o = profile(store, s);
        if (!o.has("named")) o.putArray("named");
        for (JsonNode c : o.path("named")) if (key(c.path("name").asText()).equals(key(n))) return false;
        ((ArrayNode) o.get("named")).addObject().put("name", n).put("kind", kind == null ? "" : kind).put("from", from == null ? "" : from).put("at", LocalDate.now().toString());
        save(store, s, o);
        return true;
    }

    /** The names the person gave: each with its kind. */
    public static List<ObjectNode> named(LibraryStore store, Career.Seeker s) throws IOException {
        List<ObjectNode> out = new ArrayList<>(); for (JsonNode c : profile(store, s).path("named")) if (c.isObject()) out.add((ObjectNode) c); return out;
    }

    /** Takes a company or a thing kept out off the profile, by its words. */
    public static boolean remove(LibraryStore store, Career.Seeker s, String list, String words) throws IOException {
        ObjectNode o = profile(store, s);
        ArrayNode a = (ArrayNode) o.get(list.equals("companies") ? "companies" : "keep_out");
        String field = list.equals("companies") ? "name" : "text";
        for (int i = a.size() - 1; i >= 0; i--) if (key(a.get(i).path(field).asText()).equals(key(words))) {
            a.remove(i); save(store, s, o);
            Career.log(store, s, list.equals("companies") ? "The search no longer looks at " + words.strip() + " for you." : "The search no longer keeps out " + words.strip() + ".");
            return true;
        }
        return false;
    }

    /** A company or a thing kept out as the profile keeps it, matched by its words; "" when it is not there. */
    public static String kept(LibraryStore store, Career.Seeker s, String list, String words) throws IOException {
        String field = list.equals("companies") ? "name" : "text";
        for (JsonNode x : profile(store, s).path(list.equals("companies") ? "companies" : "keep_out")) if (key(x.path(field).asText()).equals(key(words))) return x.path(field).asText();
        return "";
    }

    /** The companies the person named, in their order. */
    public static List<String> companies(LibraryStore store, Career.Seeker s) throws IOException {
        List<String> out = new ArrayList<>(); for (JsonNode c : profile(store, s).path("companies")) out.add(c.path("name").asText()); return out;
    }

    /** What is kept out, in the person's words. */
    public static List<String> keepOut(LibraryStore store, Career.Seeker s) throws IOException {
        List<String> out = new ArrayList<>(); for (JsonNode k : profile(store, s).path("keep_out")) out.add(k.path("text").asText()); return out;
    }

    /** The profile in sentences, for the chat and the record's text; "" when it is empty. */
    public static String text(LibraryStore store, Career.Seeker s, String how) throws IOException {
        List<String> cs = companies(store, s), ko = keepOut(store, s);
        if (cs.isEmpty() && ko.isEmpty()) return "";
        StringBuilder b = new StringBuilder("For the search:\n");
        if (!cs.isEmpty()) b.append("  Companies you want looked at: ").append(Career.and(cs)).append(".\n");
        if (!ko.isEmpty()) b.append("  Kept out: ").append(Career.and(ko)).append(".\n");
        b.append("  To add a company, type ").append(how).append(" company and its name; to keep something out, ").append(how).append(" keep-out and the words.\n");
        return b.toString();
    }
}
