package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The writing in a picture, as text the family's claims rest on. The model's reading of a register page is a draft like any
 * claim: a quote found in it is found in the model's own reading, not in the record. So the reading is kept beside the picture
 * as a machine reading until a person corrects it or accepts it, each claim read from the picture notes which of the two its
 * words were checked against, and a corrected transcript checks those words again.
 */
public final class FamilyTranscript {

    private FamilyTranscript() { }

    public static final String MACHINE = "machine reading", ACCEPTED = "accepted";

    /** The note kind on a claim that says what its quoted words were checked against. */
    public static final String NOTE = "checked-against";

    /**
     * {@code locator}: the picture's file:// address, as its claims cite it. {@code text}: the transcript now; {@code machine}: the
     * model's reading as it came. {@code read}: the date of the model's reading; {@code by} and {@code acceptedOn}: who accepted it, and when.
     */
    public record Transcript(String locator, String file, String state, String text, String machine, String read, String by, String acceptedOn) {
        public boolean accepted() { return ACCEPTED.equals(state); }
        /** What a claim's words were checked against, in words for the claim's note. */
        public String against() {
            return accepted() ? "the transcript of the picture " + file + " that " + by + " accepted on " + acceptedOn
                    : "a machine reading of the picture " + file + " that nobody has checked yet";
        }
    }

    /** A claim read from the picture, checked again against its transcript: {@code holds} is whether its quoted words are still there. */
    public record Recheck(Finding claim, String quote, boolean holds) { }

    private static final ObjectMapper M = new ObjectMapper();

    static Path dir(LibraryStore store) { return store.root().resolve("catalog").resolve("transcripts"); }

    static Path path(LibraryStore store, String locator) {
        try {
            return dir(store).resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(locator.getBytes(StandardCharsets.UTF_8)), 0, 8) + ".json");
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** The transcript kept for a picture, or null. */
    public static Transcript of(LibraryStore store, String locator) throws IOException {
        Path p = path(store, locator);
        return Files.exists(p) ? parse(M.readTree(Files.readString(p, StandardCharsets.UTF_8))) : null;
    }

    /** The transcript of the picture a claim was read from, or null for a claim not read from a picture. */
    public static Transcript of(LibraryStore store, Finding f) throws IOException {
        for (Finding.Source s : f.sources()) if (s.locator().startsWith("file://")) { Transcript t = of(store, s.locator()); if (t != null) return t; }
        return null;
    }

    /**
     * Keep the model's reading of a picture. A transcript a person accepted stays as it is: the picture has not changed, and a new
     * reading by the model is not better than the person's.
     */
    public static Transcript keep(LibraryStore store, String locator, String file, String machine) throws IOException {
        Transcript had = of(store, locator);
        if (had != null && had.accepted()) return had;
        Transcript t = new Transcript(locator, file, MACHINE, machine.strip(), machine.strip(), LocalDate.now().toString(), "", "");
        write(store, t);
        return t;
    }

    /** Accept a transcript: as it stands when {@code corrected} is null, else with the person's corrected text in place of the model's. */
    public static Transcript accept(LibraryStore store, Transcript t, String corrected, String by) throws IOException {
        Transcript a = new Transcript(t.locator(), t.file(), ACCEPTED, corrected == null ? t.text() : corrected.strip(), t.machine(), t.read(), by, LocalDate.now().toString());
        write(store, a);
        return a;
    }

    public static List<Transcript> all(LibraryStore store) throws IOException {
        List<Transcript> out = new ArrayList<>();
        if (!Files.isDirectory(dir(store))) return out;
        try (Stream<Path> s = Files.list(dir(store))) {
            for (Path p : s.filter(x -> x.toString().endsWith(".json")).sorted().toList()) {
                try { out.add(parse(M.readTree(Files.readString(p, StandardCharsets.UTF_8)))); } catch (Exception ignored) { }
            }
        }
        return out;
    }

    /**
     * The transcript a person means: a path to the picture, or its file name, or a part of the name that only one picture has.
     * Null when none or several match; {@code several} gets the file names of the several.
     */
    public static Transcript find(LibraryStore store, String typed, List<String> several) throws IOException {
        String t = typed.strip();
        try {
            Path p = Path.of(t);
            if (Files.exists(p)) { Transcript byPath = of(store, "file://" + p.toAbsolutePath().normalize()); if (byPath != null) return byPath; }
        } catch (RuntimeException ignored) { }
        List<Transcript> all = all(store);
        for (Transcript x : all) if (x.file().equals(t) || x.locator().equals(t)) return x;
        List<Transcript> part = all.stream().filter(x -> x.file().toLowerCase(Locale.ROOT).contains(t.toLowerCase(Locale.ROOT))).toList();
        if (part.size() == 1) return part.get(0);
        if (several != null) part.forEach(x -> several.add(x.file()));
        return null;
    }

    /** Whether a quote stands in a text, the way the family reader checks it. */
    static boolean holds(String text, String quote) {
        String whole = FamilyAccount.flat(text);
        return FamilyAccount.said(whole, quote) || FamilyAccount.stitched(whole, quote) != null;
    }

    /**
     * The pages of a record read as one text that a quote is on: the first page that holds all of it, else every page that holds a line
     * of it (an entry that runs onto the next page), else all of them.
     */
    public static List<Transcript> pagesOf(List<Transcript> pages, String quote) {
        if (pages.size() == 1) return pages;
        for (Transcript t : pages) if (holds(t.text(), quote)) return List.of(t);
        List<Transcript> out = new ArrayList<>();
        for (Transcript t : pages) {
            String flat = FamilyAccount.flat(t.text());
            for (String line : (quote == null ? "" : quote).split("\\R|…|\\s/\\s|\\s\\|\\s")) { String l = FamilyAccount.flat(line); if (l.length() >= 2 && flat.contains(l)) { out.add(t); break; } }
        }
        return out.isEmpty() ? pages : out;
    }

    /** What a claim's words were checked against when it cites these pages: machine readings first, so a note that starts with one says so. */
    public static String against(List<Transcript> pages) {
        if (pages.size() == 1) return pages.get(0).against();
        List<String> machine = pages.stream().filter(t -> !t.accepted()).map(Transcript::file).toList();
        List<String> accepted = pages.stream().filter(Transcript::accepted).map(t -> t.file() + " (accepted by " + t.by() + " on " + t.acceptedOn() + ")").toList();
        String m = machine.isEmpty() ? "" : "a machine reading of the " + (machine.size() == 1 ? "picture " : "pictures ") + String.join(", ", machine) + " that nobody has checked yet";
        String a = accepted.isEmpty() ? "" : "the " + (accepted.size() == 1 ? "transcript of the picture " : "transcripts of the pictures ") + String.join(", ", accepted);
        return m.isEmpty() ? a : a.isEmpty() ? m : m + ", and " + a;
    }

    /** The transcripts of the pictures a claim cites, in the order it cites them. */
    static List<Transcript> cited(LibraryStore store, Finding f) throws IOException {
        List<Transcript> out = new ArrayList<>();
        for (Finding.Source s : f.sources()) if (s.locator().startsWith("file://")) { Transcript t = of(store, s.locator()); if (t != null) out.add(t); }
        return out;
    }

    /**
     * Every claim read from the picture (retired ones aside), with whether its quoted words stand in the transcripts as they are now.
     * A claim from pages read together is checked against those pages together.
     */
    public static List<Recheck> recheck(LibraryStore store, Transcript t) throws IOException {
        List<Recheck> out = new ArrayList<>();
        for (Finding f : store.scanFindings().findings()) {
            if (f.state() == Finding.State.retired || f.sources().stream().noneMatch(s -> s.locator().equals(t.locator()))) continue;
            String quote = quoteFrom(f, t.locator());
            if (quote.isBlank()) continue;
            List<Transcript> pages = cited(store, f).stream().map(x -> x.locator().equals(t.locator()) ? t : x).toList();
            out.add(new Recheck(f, quote, holds(String.join("\n", pages.stream().map(Transcript::text).toList()), quote)));
        }
        return out;
    }

    /**
     * The words a claim was read from in this picture. A claim first read from another source and later backed by the picture keeps
     * the picture's words in the note that added it as a source; its own quote is the first source's words, which the picture need not have.
     */
    static String quoteFrom(Finding f, String locator) {
        for (Finding.Note n : f.notes()) {
            if (!n.kind().equals("source-added") || !n.text().startsWith("also said by ") || !n.text().contains(locator)) continue;
            Matcher m = Pattern.compile(": \"(.*)\"$").matcher(n.text());
            return m.find() ? m.group(1).replaceFirst("…$", "") : "";
        }
        return FamilyChecks.quoteOf(f);
    }

    /**
     * After a transcript is accepted: each claim read from the picture gets a note saying what its words were checked against now,
     * and whether they are still there. Nothing else about the claim changes; a claim whose words are gone is the person's to retire.
     */
    public static List<Recheck> noteRecheck(LibraryStore store, Transcript t) throws IOException {
        List<Recheck> checks = recheck(store, t);
        for (Recheck r : checks) {
            List<Transcript> pages = cited(store, r.claim());
            Finding g = r.claim().withNote(new Finding.Note(NOTE, "person", LocalDate.now().toString(), (pages.isEmpty() ? t.against() : against(pages)) + (r.holds() ? "" : ": the quoted words are not in it")));
            store.write(g);
            try { new LibrarianIndex(store).upsert(g); } catch (Exception ignored) { }
        }
        return checks;
    }

    /** What a claim's words were last checked against, from its notes, or "" for a claim not read from a picture. */
    public static String checkedAgainst(Finding f) {
        String out = "";
        for (Finding.Note n : f.notes()) if (n.kind().equals(NOTE)) out = n.text();
        return out;
    }

    /** Whether a claim's words were last checked against a model's reading that nobody has checked. */
    public static boolean onMachineReading(Finding f) { return checkedAgainst(f).startsWith("a machine reading"); }

    static void write(LibraryStore store, Transcript t) throws IOException {
        ObjectNode o = M.createObjectNode().put("locator", t.locator()).put("file", t.file()).put("state", t.state()).put("read", t.read())
                .put("by", t.by()).put("accepted_on", t.acceptedOn()).put("text", t.text()).put("machine", t.machine());
        Files.createDirectories(dir(store));
        Files.writeString(path(store, t.locator()), M.writerWithDefaultPrettyPrinter().writeValueAsString(o) + "\n", StandardCharsets.UTF_8);
    }

    private static Transcript parse(JsonNode o) {
        return new Transcript(o.path("locator").asText(""), o.path("file").asText(""), o.path("state").asText(MACHINE), o.path("text").asText(""),
                o.path("machine").asText(""), o.path("read").asText(""), o.path("by").asText(""), o.path("accepted_on").asText(""));
    }
}
