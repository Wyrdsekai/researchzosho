package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The person's own word on sources: {@code catalog/source-rules.md}, one line each — {@code - trust <host> — why} or
 * {@code - refuse <host> — why}. A trusted host is read as a primary source and may carry a claim into canon; a refused
 * host is dropped from search results, and a claim resting only on refused sources is dropped at review, and the
 * write-up says so. Nobody else's list: no vendor ratings, no outlet politics. A host rule covers its subdomains.
 */
public final class SourceRules {

    public record Rule(String kind, String host, String why) { }

    private static final String PREAMBLE = "# Source rules — your own word on sources\n\n"
            + "One per line: `- trust <host> — why` or `- refuse <host> — why`. A trusted host counts as a primary source; a refused\n"
            + "host is dropped from searches and a claim resting only on refused sources is dropped at review. A rule covers subdomains.\n"
            + "`researchzosho sources trust|refuse|forget <host>` edits this file.\n\n";

    private final Map<String, Rule> byHost = new LinkedHashMap<>();

    private SourceRules() { }

    public static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("source-rules.md"); }

    public static SourceRules load(LibraryStore store) {
        SourceRules r = new SourceRules();
        Path f = file(store);
        if (!Files.exists(f)) return r;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (!line.startsWith("- ")) continue;
                String rest = line.substring(2).strip();
                String why = "";
                int dash = rest.indexOf(" — ");
                if (dash > 0) { why = rest.substring(dash + 3).strip(); rest = rest.substring(0, dash).strip(); }
                String[] p = rest.split("\\s+", 2);
                if (p.length < 2) continue;
                String kind = p[0].toLowerCase(Locale.ROOT);
                if (!kind.equals("trust") && !kind.equals("refuse")) continue;
                String host = norm(p[1]);
                if (!host.isEmpty()) r.byHost.put(host, new Rule(kind, host, why));
            }
        } catch (IOException ignored) { }
        return r;
    }

    public List<Rule> rules() { return new ArrayList<>(byHost.values()); }
    public boolean isEmpty() { return byHost.isEmpty(); }

    /** The rule that covers a host or a URL, or null: the host itself, then each parent domain. */
    public Rule ruleFor(String hostOrUrl) {
        String h = hostOf(hostOrUrl);
        if (h == null) return null;
        String cur = h;
        while (!cur.isEmpty()) {
            Rule r = byHost.get(cur);
            if (r != null) return r;
            int dot = cur.indexOf('.');
            if (dot < 0) break;
            cur = cur.substring(dot + 1);
            if (!cur.contains(".")) break;   // never match a bare TLD
        }
        return null;
    }
    public boolean trusted(String hostOrUrl) { Rule r = ruleFor(hostOrUrl); return r != null && r.kind().equals("trust"); }
    public boolean refused(String hostOrUrl) { Rule r = ruleFor(hostOrUrl); return r != null && r.kind().equals("refuse"); }

    public static synchronized void set(LibraryStore store, String kind, String host, String why) throws IOException {
        SourceRules r = load(store);
        String h = norm(host);
        if (h.isEmpty()) throw new IllegalArgumentException("not a host: " + host);
        if (kind.equals("forget")) r.byHost.remove(h); else r.byHost.put(h, new Rule(kind, h, why == null ? "" : why.strip()));
        StringBuilder sb = new StringBuilder(PREAMBLE);
        for (Rule x : r.byHost.values()) sb.append("- ").append(x.kind()).append(' ').append(x.host()).append(x.why().isEmpty() ? "" : " — " + x.why()).append('\n');
        Files.createDirectories(file(store).getParent());
        Files.writeString(file(store), sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        store.circulate("source-rule", kind + " " + h + (why == null || why.isBlank() ? "" : " — " + why));
    }

    /** A host, lowercased, without www. or a scheme; from a URL or a bare host. */
    public static String norm(String s) {
        String h = hostOf(s);
        return h == null ? "" : h;
    }

    static String hostOf(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip().toLowerCase(Locale.ROOT);
        if (t.contains("://")) {
            try { String h = java.net.URI.create(t).getHost(); t = h == null ? "" : h; } catch (Exception e) { return null; }
        } else {
            int slash = t.indexOf('/'); if (slash >= 0) t = t.substring(0, slash);
        }
        if (t.startsWith("www.")) t = t.substring(4);
        return t.isEmpty() || !t.contains(".") ? null : t;
    }

    // ---- the live rules, for the tools that have no store in hand (search results, the tier) ----

    private static volatile SourceRules cached;
    private static volatile long cachedAt;
    private static volatile long cachedMtime;

    /** The configured library's rules, reloaded when the file changes; empty rules when there is no library. */
    public static SourceRules current() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAt < 2_000) return cached;
        try {
            if (!Acquisitions.libraryExists()) { cached = new SourceRules(); cachedAt = now; return cached; }
            LibraryStore store = LibraryStore.open();
            Path f = file(store);
            long m = Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
            if (cached == null || m != cachedMtime) { cached = load(store); cachedMtime = m; }
        } catch (Exception e) { if (cached == null) cached = new SourceRules(); }
        cachedAt = now;
        return cached;
    }

    /** Tests point the live rules at a store of their own; null goes back to the configured library. */
    static volatile LibraryStore OVERRIDE;
    static { }
    public static SourceRules live() { return OVERRIDE != null ? load(OVERRIDE) : current(); }
}
