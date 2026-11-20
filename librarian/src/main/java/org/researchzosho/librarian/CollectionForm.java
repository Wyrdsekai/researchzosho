package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A collection of pictures as the person asked for it (0.8.0): a typed form the model fills from the person's request, in whatever
 * language it was made, and the program then holds every picture to. The model reads the words; the program never does. In the chat
 * the form is shown back in plain words before anything runs ({@link #said}), and the person's yes, or what they add, is the check.
 *
 * <p>What the program measures from each picture's own bytes: its width and height against {@code minWidth} and {@code minHeight} in the
 * orientation asked (with none asked, the minimum holds either way round), its format, and whether the file declares it was made by AI.
 * What the seeing model judges: whether a picture shows {@code shows}, and, when the pictures must differ in something ({@code differBy}),
 * which one it shows. {@code minutes} is the person's own limit; with none the job ends by its own stopping rule, as a research run does.
 */
public record CollectionForm(String words, String shows, String ask, int count, int minWidth, int minHeight, String orientation, String format, String ai, String differBy, int minutes, boolean keepFull,
                             String name, String differQuote, String kind) {

    /** With no number asked, 20 pictures; at most 200 in one collection. */
    public static final int DEFAULT_COUNT = 20, MOST = 200;
    public static final String LANDSCAPE = "landscape", PORTRAIT = "portrait", SQUARE = "square", ANY = "any", AI_OK = "ok", AI_NO = "no";
    /** What the pictures are of: a real person (their likeness counts, in any role), a fictional character (shown as the character), anything else. */
    public static final String PERSON = "person", CHARACTER = "character", OTHER = "other";
    public static final List<String> FORMATS = List.of("jpg", "png", "webp", "gif", "tif", "bmp", "avif");

    /**
     * The form's fields as a call takes them, name → {type, what goes in it}: one list for the chat's tool, the protocol's and MCP's, and
     * the job's own reading of a request that came as words alone. The model fills only what the request says.
     */
    public static final Map<String, String[]> FIELDS;
    static {
        Map<String, String[]> f = new LinkedHashMap<>();
        f.put("words", new String[]{"string", "For collect: what the pictures are of, as a picture search takes it (a person, a thing, a work, a place, a scene)."});
        f.put("kind", new String[]{"string", "For collect: what kind of subject the pictures are of: person (a real person, living or historical, an actor as themselves), character (a fictional character: from a film, a series, a comic, a book or a game), or other (a thing, a place, a work, an event, a kind of thing)."});
        f.put("name", new String[]{"string", "For collect: when the pictures are of a named person, work, place or thing, that name copied exactly as the person wrote it in the request. The library checks the name is there, and holds each picture whose page does not name it to a closer look."});
        f.put("shows", new String[]{"string", "For collect: when the person said more about what one picture must show than what the pictures are of, those words copied exactly from the request, as they wrote them (\"fighting another monster\", \"in a storm\"). The library checks the words are in the request and leaves this out when they are not; how many goes in count, what must not repeat in differ_by."});
        f.put("count", new String[]{"integer", "For collect: how many pictures the person wants (20 when they gave no number; at most 200)."});
        f.put("min_width", new String[]{"integer", "For collect: the least width in pixels the person asked for (4K is 3840 wide by 2160 tall, 1080p 1920 by 1080); leave out when they asked no size."});
        f.put("min_height", new String[]{"integer", "For collect: the least height in pixels the person asked for; leave out when they asked no size."});
        f.put("orientation", new String[]{"string", "For collect: landscape (horizontal), portrait (vertical) or square when the person asked for one; leave out for either. With none, the least size holds either way round."});
        f.put("format", new String[]{"string", "For collect: the file format when the person asked for one (png, jpg, webp)."});
        f.put("ai", new String[]{"string", "For collect: ok when the person allows pictures made by AI; no when they want none made by AI (only pictures with a record of who made them and when count then); leave out when they said nothing of it."});
        f.put("differ_by", new String[]{"string", "For collect: a thing each picture shows that must not repeat from one picture to the next, in the person's own word for it (\"monster\", \"temple\"), when they asked for that. The library keeps every collection's pictures distinct by itself."});
        f.put("differ_quote", new String[]{"string", "For collect: with differ_by, the words of the request that say the thing must not repeat, copied exactly (\"each monster must be different\", \"one per temple\"). The library keeps differ_by only when these words are in the request and name the thing."});
        f.put("minutes", new String[]{"integer", "For collect: the time limit in minutes, only when the person set one."});
        f.put("keep_full", new String[]{"boolean", "For collect: true only when the person asks to keep the picture files themselves in their library; else each is kept as its address and a small copy."});
        f.put("ask", new String[]{"string", "For collect: the person's request in their own words, given to the model that judges each picture as its context, and what name, shows and differ_by are checked against."});
        FIELDS = Collections.unmodifiableMap(f);
    }

    public CollectionForm {
        words = clean(words); shows = clean(shows); ask = ask == null ? "" : ask.strip();
        count = Math.max(0, count); minWidth = Math.max(0, minWidth); minHeight = Math.max(0, minHeight); minutes = Math.max(0, minutes);
        orientation = orientationOf(orientation); format = formatOf(format); ai = aiOf(ai); differBy = clean(differBy);
        name = clean(name); differQuote = clean(differQuote); kind = kindOf(kind);
    }

    /** The same with no kind given: a program's call, or a form from before the kind (0.8.0). */
    public CollectionForm(String words, String shows, String ask, int count, int minWidth, int minHeight, String orientation, String format, String ai, String differBy, int minutes, boolean keepFull, String name, String differQuote) {
        this(words, shows, ask, count, minWidth, minHeight, orientation, format, ai, differBy, minutes, keepFull, name, differQuote, "");
    }

    /** The kind as the form takes it: person, character, other, or "" when none was given. */
    static String kindOf(String k) {
        String v = k == null ? "" : k.strip().toLowerCase(Locale.ROOT);
        return switch (v) { case "person", "real person", "people", "actor", "actress" -> PERSON; case "character", "fictional character", "fictional" -> CHARACTER; case "other", "thing", "place", "work", "event" -> OTHER; default -> ""; };
    }

    /** A form with no name and no words for what must differ: what a program's call that fills the fields itself gives. */
    public CollectionForm(String words, String shows, String ask, int count, int minWidth, int minHeight, String orientation, String format, String ai, String differBy, int minutes, boolean keepFull) {
        this(words, shows, ask, count, minWidth, minHeight, orientation, format, ai, differBy, minutes, keepFull, "", "", "");
    }

    private static String clean(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").strip(); }

    /** The orientation as the form takes it: landscape, portrait, square, or "" for either. */
    static String orientationOf(String o) {
        String v = o == null ? "" : o.strip().toLowerCase(Locale.ROOT);
        return switch (v) { case "landscape", "horizontal", "wide" -> LANDSCAPE; case "portrait", "vertical", "tall" -> PORTRAIT; case "square" -> SQUARE; default -> ""; };
    }
    static String formatOf(String f) {
        String v = f == null ? "" : f.strip().toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");
        return switch (v) { case "jpeg", "jpg" -> "jpg"; case "tiff", "tif" -> "tif"; default -> FORMATS.contains(v) ? v : ""; };
    }
    static String aiOf(String a) {
        String v = a == null ? "" : a.strip().toLowerCase(Locale.ROOT);
        return switch (v) { case "ok", "yes", "allowed", "true", "fine" -> AI_OK; case "no", "none", "false", "not", "never", "provenance" -> AI_NO; default -> ""; };
    }

    /** How many to collect: the number asked, else the default, never over the most. */
    public int target() { return Math.min(MOST, count > 0 ? count : DEFAULT_COUNT); }
    /** What a picture is judged against: what each must show, else what the pictures are of. */
    public String judgedAgainst() { return shows.isEmpty() ? words : shows; }
    /** The most candidates the seeing model judges for one collection. */
    public int judgeCeiling() { return Math.max(60, 8 * target()); }

    /** Whether a picture this size passes the size and orientation rules: null when it does, else why not, with the measure. */
    public String sizeFails(int w, int h) {
        if (w <= 0 || h <= 0) return "its size could not be read";
        String measured = w + "×" + h;
        double r = (double) w / h;
        if (orientation.equals(LANDSCAPE) && r <= 1.02) return "measured " + measured + ", " + (r < 0.98 ? "vertical" : "square") + " where horizontal was asked";
        if (orientation.equals(PORTRAIT) && r >= 0.98) return "measured " + measured + ", " + (r > 1.02 ? "horizontal" : "square") + " where vertical was asked";
        if (orientation.equals(SQUARE) && (r < 0.95 || r > 1.05)) return "measured " + measured + ", not square";
        if (minWidth <= 0 && minHeight <= 0) return null;
        boolean fits = orientation.isEmpty()
                ? Math.max(w, h) >= Math.max(minWidth, minHeight) && Math.min(w, h) >= Math.min(minWidth, minHeight)   // with no orientation asked, the minimum holds either way round
                : w >= minWidth && h >= minHeight;
        return fits ? null : "measured " + measured + ", smaller than " + minWidth + "×" + minHeight;
    }

    // ---- the form as a call carries it ----

    /** The form a call carries, by its fields; the person's own words, when given, ride along as {@code ask} for the judge's context. */
    public static CollectionForm fromArgs(JsonNode a) {
        String words = firstOf(a, "words", "subject", "query");
        String shows = firstOf(a, "shows", "criteria");
        int minutes = a.path("minutes").asInt(0) > 0 ? a.path("minutes").asInt() : a.path("max_minutes").asInt(0);
        String differ = firstOf(a, "differ_by", "each_different");
        return new CollectionForm(words, shows, a.path("ask").asText(""), a.path("count").asInt(0), a.path("min_width").asInt(0), a.path("min_height").asInt(0),
                a.path("orientation").asText(""), a.path("format").asText(""), a.path("ai").asText(""), differ, minutes, a.path("keep_full").asBoolean(false),
                a.path("name").asText(""), a.path("differ_quote").asText(""), a.path("kind").asText(""));
    }

    private static String firstOf(JsonNode a, String... keys) { for (String k : keys) { String v = a.path(k).asText("").strip(); if (!v.isEmpty()) return v; } return ""; }

    /** The same form with the person's own time limit (the chat reads it as it reads a run's, {@link RunLimits}). */
    public CollectionForm withMinutes(int m) { return m <= 0 ? this : new CollectionForm(words, shows, ask, count, minWidth, minHeight, orientation, format, ai, differBy, m, keepFull, name, differQuote, kind); }
    /** The same form with the person's words as the ask's context. */
    public CollectionForm withAsk(String words) { return words == null || words.isBlank() ? this : new CollectionForm(this.words, shows, words, count, minWidth, minHeight, orientation, format, ai, differBy, minutes, keepFull, name, differQuote, kind); }
    /** The same form kept as links and small copies: a full-size keep refused. */
    /** The same form, kept at full size when {@code full} (a request to keep the files stands whoever filled the rest). */
    public CollectionForm withKeepFull(boolean full) { return !full || keepFull ? this : new CollectionForm(words, shows, ask, count, minWidth, minHeight, orientation, format, ai, differBy, minutes, true, name, differQuote, kind); }
    public CollectionForm linksOnly() { return new CollectionForm(words, shows, ask, count, minWidth, minHeight, orientation, format, ai, differBy, minutes, false, name, differQuote, kind); }

    // ---- grounded in the person's own words ----

    /** A form held to the person's words, and the fields that were dropped from it because the words do not say them. */
    public record Grounded(CollectionForm form, List<String> dropped) { }

    /**
     * The fields that narrow a collection, kept only when the person's own message says them (2026-11-19): {@code name} and {@code shows}
     * must stand in the message as written, and {@code differBy} must come with the words of the message that say it ({@code differQuote}),
     * which must stand in the message and name the thing. A field whose words are not there is the model's reading, not the person's ask,
     * and is dropped: read from "20 distinct … images", "a different scene or character in each" kept 2 pictures of 20, and "in the style
     * of the series" let stock photographs through. The check is a substring's, in any language; case, width and spacing aside. Distinctness
     * needs no field: the program keeps every collection distinct. With no message (a program that filled the fields itself), nothing is dropped.
     */
    public Grounded grounded(String message) {
        String m = norm(message);
        if (m.isEmpty()) return new Grounded(this, List.of());
        List<String> dropped = new ArrayList<>();
        String n = name, sh = shows, d = differBy, dq = differQuote;
        if (!n.isEmpty() && !letters(m).contains(letters(n))) { dropped.add("name \"" + n + "\""); n = ""; }   // a name as written, its spaces and hyphens aside ("Hit-Girl" for "hitgirl")
        if (!sh.isEmpty() && !m.contains(norm(sh))) { dropped.add("shows \"" + sh + "\""); sh = ""; }
        if (!d.isEmpty()) {
            String q = norm(dq), thing = norm(thing(d));
            if (q.isEmpty() || !m.contains(q) || thing.isEmpty() || !q.contains(thing)) { dropped.add("differ_by \"" + d + "\"" + (dq.isEmpty() ? " (no words of the request given for it)" : " (\"" + dq + "\")")); d = ""; dq = ""; }
        }
        if (dropped.isEmpty()) return new Grounded(this, List.of());
        return new Grounded(new CollectionForm(words, sh, ask, count, minWidth, minHeight, orientation, format, ai, d, minutes, keepFull, n, dq, kind), dropped);
    }

    /** Letters and digits alone, lower case: how a name is found in the request whatever its spaces, hyphens or stops. */
    static String letters(String s) { return norm(s).replaceAll("[^\\p{L}\\p{N}]+", ""); }

    /** Words as the check compares them: the same width and case, one space, the quotation marks and the end's stop off. */
    static String norm(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ").strip();
        return t.replaceAll("^[\"'“”«»「」『』]+|[\"'“”«»「」『』.,;:!?。、]+$", "").strip();
    }

    /** The form onto a job's arguments, by the protocol's names. */
    public ObjectNode json(ObjectMapper m) {
        ObjectNode o = m.createObjectNode();
        o.put("op", "collect"); o.put("words", words); if (!shows.isEmpty()) o.put("shows", shows); if (!ask.isEmpty()) o.put("ask", ask);
        o.put("count", target());
        if (minWidth > 0) o.put("min_width", minWidth); if (minHeight > 0) o.put("min_height", minHeight);
        if (!orientation.isEmpty()) o.put("orientation", orientation); if (!format.isEmpty()) o.put("format", format); if (!ai.isEmpty()) o.put("ai", ai);
        if (!differBy.isEmpty()) o.put("differ_by", differBy);
        if (!differQuote.isEmpty()) o.put("differ_quote", differQuote);
        if (!name.isEmpty()) o.put("name", name);
        if (!kind.isEmpty()) o.put("kind", kind);
        if (minutes > 0) o.put("minutes", minutes);
        if (keepFull) o.put("keep_full", true);
        return o;
    }

    // ---- in the person's words ----

    /**
     * What must differ, as the thing alone for the sentence it goes into: the model filled it once as "different scene or character in each",
     * and the question read "with a different different scene or character in each in each" (2026-11-19).
     */
    static String thing(String differ) {
        String t = differ.strip().replaceFirst("(?i)^(?:the|a|an)\\s+", "").replaceFirst("(?i)^(?:different|another|other)\\s+", "").replaceFirst("(?i)\\s+(?:in|for|per)\\s+(?:each|every)(?:\\s+(?:one|picture|image|photo))?$", "").strip();
        return t.isEmpty() ? differ.strip() : t;
    }

    /** The form in plain words, for the question the chat asks and for the gallery: "collect up to 20 pictures of Altered Carbon: horizontal, at least 3840×2160, AI-made allowed, …". */
    public String said(Chrome c) {
        List<String> parts = new ArrayList<>();
        // the kind as the model read it, said back so a wrong reading is seen before the yes
        if (kind.equals(CHARACTER)) parts.add(c.t("the character as the character (in costume, drawn or on screen)"));
        else if (kind.equals(PERSON)) parts.add(c.t("the person themselves"));
        if (!shows.isEmpty() && !shows.equalsIgnoreCase(words)) parts.add(c.t("each showing {0}", shows));
        if (orientation.equals(LANDSCAPE)) parts.add(c.t("horizontal")); else if (orientation.equals(PORTRAIT)) parts.add(c.t("vertical")); else if (orientation.equals(SQUARE)) parts.add(c.t("square"));
        if (minWidth > 0 && minHeight > 0) parts.add(orientation.isEmpty() ? c.t("at least {0}, either way round", minWidth + "×" + minHeight) : c.t("at least {0}", minWidth + "×" + minHeight));
        else if (minWidth > 0) parts.add(c.t("at least {0} pixels wide", minWidth));
        else if (minHeight > 0) parts.add(c.t("at least {0} pixels tall", minHeight));
        if (!format.isEmpty()) parts.add(c.t("{0} files only", format.equals("jpg") ? "JPEG" : format.toUpperCase(Locale.ROOT)));
        if (ai.equals(AI_OK)) parts.add(c.t("AI-made allowed"));
        if (ai.equals(AI_NO)) parts.add(c.t("none made by AI: each with a record of who made it and when"));
        parts.add(differBy.isEmpty() ? c.t("each a different picture") : c.t("each a different picture, with a different {0} in each", thing(differBy)));
        if (minutes > 0) parts.add(c.t("for at most {0} minutes", minutes));
        parts.add(keepFull ? c.t("kept at full size in the library") : c.t("kept as links and small copies"));
        String head = target() == 1 ? c.t("collect 1 picture of {0}", words) : c.t("collect up to {0} pictures of {1}", target(), words);
        return head + ": " + String.join(", ", parts);
    }
}
