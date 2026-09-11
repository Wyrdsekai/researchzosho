package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Who may read and write — the simple allow-list the operator asked for (2026-09-03), kept in
 * {@code catalog/patrons.md} so the person edits it like any other catalog file:
 *
 * <pre>
 * default: read
 * - did:key:z6Mk… — Wyrdsekai household A — write
 * - did:web:example.org — a patron we turned away — deny
 * </pre>
 *
 * <p>Three levels, ordered: {@code deny < read < write}. Reading covers ask, search, get, read,
 * established, subjects, status and listing the frontier; writing covers submit and filing a
 * frontier gap. An unlisted or anonymous patron gets {@code default}. Over stdio the patron's
 * id is self-asserted — the list decides what a NAMED patron may do; proving the name is the
 * transport's job and arrives with http/sse.
 */
public final class Patrons {

    private Patrons() { }

    public enum Level { deny, read, write }

    /** The caller, as carried on every protocol call. Absent fields make an anonymous patron. */
    public record Patron(String did, String name, String runtime) {
        public static final Patron ANONYMOUS = new Patron("", "", "");
        /** The person who keeps the library, acting locally (the CLI, the chat): always write. */
        public static final Patron PERSON = new Patron("person", "the person", "local");
        /** Someone in the browser while the pages are open (no sign-in asked): may do what the person may. */
        public static final Patron WEB = new Patron("web", "someone in the browser", "web");

        public static Patron from(JsonNode args) {
            JsonNode p = args == null ? null : args.get("patron");
            if (p == null || !p.isObject()) return ANONYMOUS;
            return new Patron(p.path("did").asText("").strip(), p.path("name").asText("").strip(),
                    p.path("runtime").asText("").strip());
        }
        public boolean anonymous() { return did.isEmpty(); }
        public boolean person() { return "person".equals(did); }
        public boolean web() { return "web".equals(did); }
        /** The writer label on anything this patron submits. */
        public String writer() { return person() ? "person" : anonymous() ? "patron:anonymous" : "patron:" + did; }
        /** For circulation lines: who asked. */
        public String label() {
            if (anonymous()) return "anonymous";
            return did + (name.isEmpty() ? "" : " (" + name + ")") + (runtime.isEmpty() ? "" : " via " + runtime);
        }
    }

    /** {@code tokenHash} — sha256 hex of the bearer token that PROVES this did over http; null = none issued. */
    public record Entry(String did, String name, Level level, String tokenHash) {
        public Entry(String did, String name, Level level) { this(did, name, level, null); }
    }
    public record Policy(Level dflt, List<Entry> listed) {
        public Level levelFor(Patron p) {
            if (p.anonymous()) return dflt;
            for (Entry e : listed) if (e.did().equals(p.did())) return e.level();
            return dflt;
        }
    }

    public static Path file(LibraryStore store) {
        return store.root().resolve("catalog").resolve("patrons.md");
    }

    public static Policy load(LibraryStore store) throws IOException {
        Path p = file(store);
        Level dflt = Level.write;   // open as shipped, like the pages; `researchzosho reader default read|deny` restricts
        List<Entry> listed = new ArrayList<>();
        if (!Files.exists(p)) return new Policy(dflt, listed);
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String s = line.strip();
            if (s.startsWith("default:")) {
                dflt = level(s.substring("default:".length()).strip(), line);
            } else if (s.startsWith("- ")) {
                // "- <did> — <name> — <level>[ — token:<sha256hex>]"; the name may itself hold a dash
                String[] parts = s.substring(2).split(" — | \\| ");
                if (parts.length < 2) throw new IOException("patrons.md line needs '<did> — <name> — <level>': " + line);
                Level lvl = null; String hash = null; List<String> nameParts = new ArrayList<>();
                for (int i = 1; i < parts.length; i++) {
                    String part = parts[i].strip();
                    if (part.startsWith("token:")) hash = part.substring("token:".length()).strip();
                    else if (part.matches("(?i)deny|read|write") && lvl == null) lvl = level(part, line);
                    else nameParts.add(part);
                }
                if (lvl == null) throw new IOException("patrons.md level must be deny|read|write: " + line);
                listed.add(new Entry(parts[0].strip(), String.join(" — ", nameParts), lvl, hash));
            }
        }
        return new Policy(dflt, listed);
    }

    private static Level level(String s, String line) throws IOException {
        try { return Level.valueOf(s.toLowerCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IOException("patrons.md level must be deny|read|write: " + line); }
    }

    /** Throws {@code forbidden} when the patron's level is below {@code need}. */
    public static void check(LibraryStore store, Patron patron, Level need) throws IOException {
        if (patron.person()) return;   // the keeper of the library, on their own machine
        if (patron.web() && !WebAccess.signInRequired()) return;   // the pages are open: a browser counts as the person
        Level have = load(store).levelFor(patron);
        // the sign-in is on: a browser that has not signed in reads at most, whatever the default says — that is what
        // "web signin on" promises (with the default at write it used to let anyone send questions anyway)
        if (patron.web() && have.ordinal() > Level.read.ordinal()) have = Level.read;   // "web" is the browser that has not signed in
        if (have.ordinal() >= need.ordinal()) return;
        String who = patron.anonymous() ? "An anonymous patron" : "Patron " + patron.did();
        throw ProtocolError.forbidden(who + " may not " + (need == Level.write ? "write to" : "read")
                + " this library; the person who keeps it edits catalog/patrons.md.");
    }

    // ---- editing (the CLI) ----

    public static synchronized void set(LibraryStore store, String did, String name, Level level) throws IOException {
        Policy pol = load(store);
        Map<String, Entry> byDid = new LinkedHashMap<>();
        for (Entry e : pol.listed()) byDid.put(e.did(), e);
        Entry old = byDid.get(did);
        byDid.put(did, new Entry(did, name == null || name.isBlank() ? (old == null ? "" : old.name()) : name, level,
                old == null ? null : old.tokenHash()));
        write(store, new Policy(pol.dflt(), new ArrayList<>(byDid.values())));
    }

    public static synchronized void remove(LibraryStore store, String did) throws IOException {
        Policy pol = load(store);
        List<Entry> kept = new ArrayList<>();
        for (Entry e : pol.listed()) if (!e.did().equals(did)) kept.add(e);
        write(store, new Policy(pol.dflt(), kept));
    }

    public static synchronized void setDefault(LibraryStore store, Level level) throws IOException {
        write(store, new Policy(level, load(store).listed()));
    }

    /**
     * Issue a bearer token for a listed patron — returned ONCE in the clear, stored as a sha256 hash.
     * Over http the token is what proves the did (stdio has no such proof).
     */
    public static synchronized String issueToken(LibraryStore store, String did) throws IOException {
        Policy pol = load(store);
        List<Entry> out = new ArrayList<>();
        Entry found = null;
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        for (Entry e : pol.listed()) {
            if (e.did().equals(did)) { found = new Entry(e.did(), e.name(), e.level(), sha256(token)); out.add(found); }
            else out.add(e);
        }
        if (found == null) throw new IOException("patron " + did + " is not listed — `librarian patron allow " + did + " <level>` first");
        write(store, new Policy(pol.dflt(), out));
        return token;
    }

    /** The listed patron a bearer token proves, or null. */
    public static Entry resolve(LibraryStore store, String token) throws IOException {
        if (token == null || token.isBlank()) return null;
        String h = sha256(token.strip());
        for (Entry e : load(store).listed()) if (h.equals(e.tokenHash())) return e;
        return null;
    }

    static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static void write(LibraryStore store, Policy pol) throws IOException {
        StringBuilder sb = new StringBuilder("# Patrons — who may read and write this library\n\n"
                + "Levels: deny < read < write. `default:` applies to anonymous and unlisted patrons.\n"
                + "One patron per line: `- <did> — <name> — <level>[ — token:<sha256 of the bearer token>]`.\n"
                + "`researchzosho reader token <did>` issues a token (shown once); over http it proves the did.\n\n");
        sb.append("default: ").append(pol.dflt()).append("\n\n");
        for (Entry e : pol.listed()) {
            sb.append("- ").append(e.did()).append(" — ").append(e.name().isEmpty() ? "(unnamed)" : e.name())
              .append(" — ").append(e.level());
            if (e.tokenHash() != null) sb.append(" — token:").append(e.tokenHash());
            sb.append('\n');
        }
        Files.createDirectories(file(store).getParent());
        Files.writeString(file(store), sb.toString(), StandardCharsets.UTF_8);
    }
}
