package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shelf reading — what real libraries call inventory. Each night a few ACCEPTED findings, the
 * ones least recently checked, are re-read against the raw captures of their own sources: does
 * the source still support the claim? The reviewer promotes extractions automatically and
 * nobody measured it on the live shelf; sources also drift. A finding the source does not
 * support is marked disputed (reviewer {@code inventory:<model>}, reason in the body, a frontier
 * line) — never retired, never edited. Every check is logged to {@code catalog/inventory.log}.
 */
public final class Inventory {

    private static final ObjectMapper M = new ObjectMapper();
    static final int PER_NIGHT = org.researchzosho.Config.getInt("RESEARCHZOSHO_INVENTORY_PER_NIGHT", 3);
    static final int SOURCE_CHARS = 7000;

    private Inventory() { }

    /** Judges one claim against one source's text: {@code supported | unsupported | cannot-tell}, then a reason. */
    public interface Checker { String check(String claim, String sourceText) throws Exception; }

    public static Checker driveChecker(org.researchzosho.drive.DriveClient drive) {
        return (claim, source) -> {
            var msgs = M.createArrayNode();
            msgs.addObject().put("role", "user").put("content",
                    "You are The Librarian's inventory clerk. A catalogued CLAIM cites the SOURCE below. Read the "
                    + "source and decide whether it supports the claim as written.\n\nCLAIM:\n" + claim
                    + "\n\nSOURCE (excerpt):\n" + Fence.wrap("SOURCE TEXT", source) + "\n" + Fence.rule("SOURCE TEXT")
                    + "\n\nAnswer with JSON ONLY: {\"verdict\": \"supported|unsupported|cannot-tell\", \"reason\": \"one sentence\"}\n"
                    + "supported = the source states or clearly implies it; unsupported = the source contradicts it or says "
                    + "something materially different; cannot-tell = the excerpt does not cover it.");
            return drive.classify(msgs, 200);
        };
    }

    public record Check(String id, String verdict, String reason) { }

    public static Path log(LibraryStore store) { return store.root().resolve("catalog").resolve("inventory.log"); }

    /** Run one night's inventory: at most {@code perNight} findings. */
    public static List<Check> run(LibraryStore store, Checker checker, int perNight) throws IOException {
        List<Check> out = new ArrayList<>();
        if (perNight <= 0) return out;
        Map<String, String> lastChecked = lastChecked(store);
        List<Finding> due = new ArrayList<>();
        for (Finding f : store.scanFindings().findings()) if (f.state() == Finding.State.accepted) due.add(f);
        due.sort((a, b) -> lastChecked.getOrDefault(a.id(), "").compareTo(lastChecked.getOrDefault(b.id(), "")));   // never-checked first
        LibrarianIndex index = new LibrarianIndex(store);
        for (Finding f : due) {
            if (out.size() >= perNight) break;
            Check c = checkOne(store, index, checker, f);
            out.add(c);
            append(store, c);
        }
        return out;
    }

    static Check checkOne(LibraryStore store, LibrarianIndex index, Checker checker, Finding f) {
        String claim = f.title() + "\n" + f.body().strip();
        boolean anyCapture = false;
        String worst = null, worstReason = "";
        for (var s : f.sources()) {
            Path raw;
            try { raw = RawCapture.find(store, s.locator()); } catch (IOException e) { raw = null; }
            if (raw == null) continue;
            anyCapture = true;
            String text;
            try { text = excerpt(RawCapture.read(raw)[2], claim); } catch (IOException e) { continue; }
            String verdict, reason;
            try {
                var j = M.readTree(strip(checker.check(claim, text)));
                verdict = j.path("verdict").asText("cannot-tell").toLowerCase(Locale.ROOT).strip();
                reason = j.path("reason").asText("");
            } catch (Exception e) {
                verdict = "cannot-tell"; reason = "judge did not answer: " + e.getMessage();
            }
            if (verdict.equals("supported")) return new Check(f.id(), "supported", reason + " [" + s.locator() + "]");
            if (verdict.equals("unsupported") || worst == null) { worst = verdict; worstReason = reason + " [" + s.locator() + "]"; }
        }
        if (!anyCapture) return new Check(f.id(), "no-capture", "none of its sources is captured in raw/");
        if ("unsupported".equals(worst)) {
            try { dispute(store, index, f, worstReason); } catch (IOException e) { return new Check(f.id(), "unsupported", worstReason + " (could not mark: " + e.getMessage() + ")"); }
            return new Check(f.id(), "unsupported", worstReason);
        }
        return new Check(f.id(), "cannot-tell", worstReason);
    }

    /** Mark disputed by the inventory clerk: reason in the body, a new review round, a frontier line. */
    static void dispute(LibraryStore store, LibrarianIndex index, Finding f, String reason) throws IOException {
        int round = f.review() == null ? 1 : f.review().round() + 1;
        String body = f.body() + "\nDISPUTED-BY: inventory (" + LocalDate.now() + "): source does not support the claim — " + reason.strip() + "\n";
        List<Finding.Note> notes = new ArrayList<>(f.notes());
        notes.add(new Finding.Note("inventory", "inventory", LocalDate.now().toString(), "source does not support the claim — " + reason.strip()));
        Finding d = new Finding(f.id(), f.title(), f.subjects(), Finding.State.disputed, f.claimType(), f.confidence(),
                f.writer(), f.recordedAt(), f.validAsOf(), f.volatility(), f.reviewBy(), f.sources(), f.supersedes(), null, body, f.triple(), notes);
        Finding signed = new Finding(d.id(), d.title(), d.subjects(), d.state(), d.claimType(), d.confidence(), d.writer(),
                d.recordedAt(), d.validAsOf(), d.volatility(), d.reviewBy(), d.sources(), d.supersedes(),
                new Finding.Review(round, "inventory", "disputed", d.contentHash(), Instant.now().toString()), d.body(), d.triple(), d.notes());
        store.write(signed);
        index.upsert(signed);
        store.frontier("check", f.id() + " — the cited source does not support it: " + reason.strip() + " (re-read the source; retire or re-source)");
        store.circulate("inventory-disputed", f.id());
    }

    /** The window of the source most lexically like the claim, capped; the whole text when short. */
    static String excerpt(String text, String claim) {
        if (text.length() <= SOURCE_CHARS) return text;
        var terms = Frontier.terms(claim);
        int best = 0; double bestScore = -1;
        int step = 1500;
        for (int i = 0; i + step <= text.length(); i += step / 2) {
            var t = Frontier.terms(text.substring(i, Math.min(text.length(), i + step)));
            double sc = Frontier.jaccard(terms, t);
            if (sc > bestScore) { bestScore = sc; best = i; }
        }
        int from = Math.max(0, best - (SOURCE_CHARS - step) / 2);
        return text.substring(from, Math.min(text.length(), from + SOURCE_CHARS));
    }

    private static String strip(String s) {
        if (s == null) return "{}";
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        return a >= 0 && b > a ? s.substring(a, b + 1) : "{}";
    }

    static Map<String, String> lastChecked(LibraryStore store) throws IOException {
        Map<String, String> m = new HashMap<>();
        if (!Files.exists(log(store))) return m;
        for (String line : Files.readAllLines(log(store), StandardCharsets.UTF_8)) {
            String[] p = line.split("\t", 4);
            if (p.length >= 2) m.put(p[1], p[0]);
        }
        return m;
    }

    /** id → "date\tverdict" of the last time the inventory read the claim against its source; for the pages and the JSON. */
    public static Map<String, String> lastChecks(LibraryStore store) {
        Map<String, String> m = new HashMap<>();
        try {
            if (!Files.exists(log(store))) return m;
            for (String line : Files.readAllLines(log(store), StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", 4);
                if (p.length >= 3) m.put(p[1], p[0] + "\t" + p[2]);
            }
        } catch (IOException ignored) { }
        return m;
    }

    private static void append(LibraryStore store, Check c) throws IOException {
        Files.createDirectories(log(store).getParent());
        Files.writeString(log(store), Instant.now() + "\t" + c.id() + "\t" + c.verdict() + "\t" + c.reason().replaceAll("\\s+", " ") + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
