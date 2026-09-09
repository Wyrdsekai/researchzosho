package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The changes feed — recall notices for patrons. A patron cites "F-0412 in lib_…" and must be
 * able to learn, later, that it was retired, disputed, superseded or edited. Every write to a
 * finding or investigation appends one line to {@code catalog/changes.log}; the protocol's
 * {@code library_changes} returns everything after a cursor. Pull-based, deterministic, and the
 * line number is the cursor: a patron re-checks its citations nightly with one call.
 *
 * <p>TSV: {@code seq  instant  kind  id  event  detail}. Events: {@code added},
 * {@code state:<from>→<to>}, {@code edited} (reviewed content changed), {@code supersedes:<ids>}.
 */
public final class Changes {

    private Changes() { }

    public record Change(long seq, String at, String kind, String id, String event, String detail) { }

    public static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("changes.log"); }

    /** Diff the finding about to be written against what is on disk; log what changed. */
    static void noteFinding(LibraryStore store, Finding before, Finding after) {
        List<String[]> events = new ArrayList<>();
        if (before == null) {
            events.add(new String[]{"added", after.state().name() + " " + after.claimType().name() + " by " + after.writer()});
        } else {
            if (before.state() != after.state()) events.add(new String[]{"state:" + before.state() + "→" + after.state(),
                    after.review() == null ? "" : after.review().decision() + " by " + after.review().reviewer()});
            if (!before.contentHash().equals(after.contentHash())) events.add(new String[]{"edited", "reviewed content changed"});
            if (!before.supersedes().equals(after.supersedes()) && !after.supersedes().isEmpty()) {
                events.add(new String[]{"supersedes:" + String.join(",", after.supersedes()), ""});
            }
        }
        for (String[] e : events) append(store, "finding", after.id(), e[0], e[1]);
    }

    static void noteInvestigation(LibraryStore store, Investigation before, Investigation after) {
        if (before == null) append(store, "investigation", after.id(), "added", after.state().name() + " by " + after.writer());
        else if (before.state() != after.state()) append(store, "investigation", after.id(), "state:" + before.state() + "→" + after.state(), "");
        else if (!before.findings().equals(after.findings())) append(store, "investigation", after.id(), "findings:" + after.findings().size(), String.join(",", after.findings()));
    }

    static void append(LibraryStore store, String kind, String id, String event, String detail) {
        Change written;
        try {
            written = store.locked("changes", () -> {
                Path f = file(store);
                Files.createDirectories(f.getParent());
                // the next sequence number is the LAST line's + 1, read from the tail under the lock — never
                // a count of the whole file, which grew with the log and could hand two writers one number
                long seq = latest(store) + 1;
                String at = Instant.now().toString();
                String d = detail == null ? "" : detail.replaceAll("\\s+", " ");
                String line = seq + "\t" + at + "\t" + kind + "\t" + id + "\t" + event + "\t" + d + "\n";
                Files.writeString(f, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return new Change(seq, at, kind, id, event, d);
            });
        } catch (IOException ignored) {
            // the feed is a courtesy to patrons; it never breaks the write it describes
            return;
        }
        Webhooks.deliver(store, written);   // outside the lock, asynchronous: a slow receiver never slows a write
    }

    /** Everything after {@code since} (0 = from the beginning), at most {@code limit}. */
    public static List<Change> since(LibraryStore store, long since, int limit) throws IOException {
        List<Change> out = new ArrayList<>();
        Path f = file(store);
        if (!Files.exists(f)) return out;
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t", 6);
            if (p.length < 5) continue;
            long seq;
            try { seq = Long.parseLong(p[0]); } catch (NumberFormatException e) { continue; }
            if (seq <= since) continue;
            out.add(new Change(seq, p[1], p[2], p[3], p[4], p.length > 5 ? p[5] : ""));
            if (out.size() >= limit) break;
        }
        return out;
    }

    /**
     * The LAST {@code limit} changes, oldest first, read from the end of the log — the ask's
     * "what changed since <date>" route wants recent history and must not grow with the log
     * (it read up to 5000 lines from the start per call; Wyrdsekai, 2026-09-07).
     */
    public static List<Change> tail(LibraryStore store, int limit) throws IOException {
        Path f = file(store);
        List<Change> out = new ArrayList<>();
        if (!Files.exists(f) || limit <= 0) return out;
        java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f.toFile(), "r")) {
            long pos = raf.length();
            StringBuilder cur = new StringBuilder();
            byte[] buf = new byte[65536];
            java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();
            while (pos > 0 && lines.size() < limit) {
                int n = (int) Math.min(buf.length, pos);
                pos -= n;
                raf.seek(pos);
                raf.readFully(buf, 0, n);
                byte[] chunk = new byte[n + pending.size()];
                System.arraycopy(buf, 0, chunk, 0, n);
                System.arraycopy(pending.toByteArray(), 0, chunk, n, pending.size());
                pending.reset();
                int end = chunk.length;
                for (int i = chunk.length - 1; i >= 0 && lines.size() < limit; i--) {
                    if (chunk[i] == '\n') {
                        if (i + 1 < end) lines.addFirst(new String(chunk, i + 1, end - i - 1, StandardCharsets.UTF_8));
                        end = i;
                    }
                }
                if (end > 0) pending.write(chunk, 0, end);
            }
            if (lines.size() < limit && pending.size() > 0) lines.addFirst(pending.toString(StandardCharsets.UTF_8));
        }
        for (String line : lines) {
            String[] p = line.split("\t", 6);
            if (p.length < 5) continue;
            long seq;
            try { seq = Long.parseLong(p[0]); } catch (NumberFormatException e) { continue; }
            out.add(new Change(seq, p[1], p[2], p[3], p[4], p.length > 5 ? p[5] : ""));
        }
        return out;
    }

    /** The last sequence number in the log (0 when empty) — the tail, not a count. */
    public static long latest(LibraryStore store) throws IOException {
        List<Change> last = tail(store, 1);
        return last.isEmpty() ? 0 : last.get(0).seq();
    }
}
