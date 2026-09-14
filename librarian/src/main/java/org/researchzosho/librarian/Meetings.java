package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A meeting transcript as a starting point: speakers instead of a person and an assistant. The questions
 * raised go to the open questions, the claims made come back to check with who said them, and the decisions
 * are kept with the transcript on the shelves. Reads WebVTT (Zoom, Teams, Otter), "Name: words" lines, and
 * "[00:12:03] Name: words".
 */
public final class Meetings {

    public record Turn(String speaker, String text) { }
    public record Transcript(String title, List<Turn> turns) {
        public List<String> speakers() { Set<String> s = new LinkedHashSet<>(); for (Turn t : turns) s.add(t.speaker()); return new ArrayList<>(s); }
    }
    public record Outcome(String title, String raw, List<String> speakers, List<String> questionsFiled, List<String> questionsHeld, List<String> claims, List<String> decisions) { }

    static final Pattern TIMESTAMP = Pattern.compile("^\\s*(?:\\[?\\d{1,2}:\\d{2}(?::\\d{2})?(?:[.,]\\d{1,3})?\\]?\\s*(?:-->\\s*\\d{1,2}:\\d{2}(?::\\d{2})?(?:[.,]\\d{1,3})?)?)\\s*");
    static final Pattern SPEAKER = Pattern.compile("^\\s*(?:<v\\s+([^>]{1,60})>|([A-Z][\\w.'’-]{0,40}(?:\\s+[A-Z][\\w.'’-]{0,40}){0,3})\\s*[:：]\\s+)");
    static final Pattern DECIDES = Pattern.compile("(?i)\\b(we will|we'll|we’ll|agreed|agree to|decided|decision|action item|action:|let's|let’s|next step|by (?:monday|tuesday|wednesday|thursday|friday|next week|end of)|owner:|assigned to|will own|going to (?:go with|use|ship|do))\\b");
    static final int MAX_DECISIONS = 30;

    private Meetings() { }

    public static Transcript parse(String text, String title) {
        List<Turn> turns = new ArrayList<>();
        String speaker = null; StringBuilder cur = new StringBuilder();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.equals("WEBVTT") || line.matches("^\\d+$") || line.startsWith("NOTE ")) continue;
            if (line.matches("^\\d{1,2}:\\d{2}(:\\d{2})?([.,]\\d{1,3})?\\s*-->.*")) continue;   // a cue's timing line
            line = TIMESTAMP.matcher(line).replaceFirst("");
            Matcher m = SPEAKER.matcher(line);
            if (m.find()) {
                if (speaker != null && cur.length() > 0) turns.add(new Turn(speaker, cur.toString().strip()));
                speaker = (m.group(1) != null ? m.group(1) : m.group(2)).strip();
                cur.setLength(0); cur.append(line.substring(m.end()).replaceAll("</v>", "")).append(' ');
            } else if (speaker != null) cur.append(line.replaceAll("</v>", "")).append(' ');
        }
        if (speaker != null && cur.length() > 0) turns.add(new Turn(speaker, cur.toString().strip()));
        // the same speaker twice in a row is one turn
        List<Turn> merged = new ArrayList<>();
        for (Turn t : turns) { if (!merged.isEmpty() && merged.get(merged.size() - 1).speaker().equals(t.speaker())) { Turn p = merged.remove(merged.size() - 1); merged.add(new Turn(p.speaker(), p.text() + " " + t.text())); } else merged.add(t); }
        if (merged.isEmpty()) merged = List.of(new Turn("transcript", text.strip()));
        return new Transcript(title, merged);
    }

    /** Questions anyone raised: sentences that ask. */
    public static List<String> questions(Transcript t) {
        List<String> out = new ArrayList<>(); Set<String> seen = new LinkedHashSet<>();
        for (Turn turn : t.turns()) for (String s : CiteCheck.sentences(turn.text())) {
            String q = s.strip().replaceAll("\\s+", " ");
            if (!q.endsWith("?") || q.length() < 12 || q.length() > 300) continue;
            if (seen.add(q.toLowerCase(Locale.ROOT))) out.add(q);
            if (out.size() >= Conversations.MAX_QUESTIONS) return out;
        }
        return out;
    }

    /** Claims made, with who made them: "Name said: …". */
    public static List<String> claims(Transcript t, Function<String, List<String>> extractor) {
        List<String> out = new ArrayList<>(); Set<String> seen = new LinkedHashSet<>();
        for (Turn turn : t.turns()) {
            List<String> found = extractor != null ? extractor.apply(turn.text()) : Conversations.mechanical(turn.text());
            int n = 0;
            for (String c : found) {
                String s = c.strip().replaceAll("\\s+", " ");
                if (s.length() < 20 || s.length() > 400 || DECIDES.matcher(s).find()) continue;
                if (seen.add(s.toLowerCase(Locale.ROOT))) { out.add(turn.speaker() + " said: " + s); n++; }
                if (n >= Conversations.CLAIMS_PER_TURN || out.size() >= Conversations.MAX_CLAIMS) break;
            }
            if (out.size() >= Conversations.MAX_CLAIMS) break;
        }
        return out;
    }

    /** What was decided or assigned, with who said it. */
    public static List<String> decisions(Transcript t) {
        List<String> out = new ArrayList<>();
        for (Turn turn : t.turns()) for (String s : CiteCheck.sentences(turn.text())) {
            String d = s.strip().replaceAll("\\s+", " ");
            if (d.endsWith("?") || d.length() < 12 || !DECIDES.matcher(d).find()) continue;
            out.add(turn.speaker() + ": " + d);
            if (out.size() >= MAX_DECISIONS) return out;
        }
        return out;
    }

    static String render(Transcript t, List<String> decisions) {
        StringBuilder sb = new StringBuilder("# ").append(t.title()).append("\n\nSpeakers: ").append(String.join(", ", t.speakers())).append("\n\n");
        if (!decisions.isEmpty()) { sb.append("## Decisions\n\n"); for (String d : decisions) sb.append("- ").append(d).append('\n'); sb.append('\n'); }
        sb.append("## Transcript\n\n");
        for (Turn turn : t.turns()) sb.append("**").append(turn.speaker()).append(":** ").append(turn.text()).append("\n\n");
        return sb.toString();
    }

    public static Outcome absorb(LibraryStore store, Transcript t, String source, String collection, String who, Function<String, List<String>> extractor) throws IOException {
        List<String> decisions = decisions(t);
        String body = render(t, decisions);
        String locator = "meeting://" + Conversations.hash8(body);
        Path raw = RawCapture.capture(store, locator, body, t.title(), "meeting:" + Acquisitions.compress(source, 120), collection);
        List<String> filed = new ArrayList<>(), held = new ArrayList<>();
        List<Frontier.Line> open = Frontier.read(store);
        for (String q : questions(t)) { if (Items.file(store, open, q, who, "meeting: " + t.title())) filed.add(q); else held.add(q); }
        List<String> claims = claims(t, extractor);
        store.circulate("meeting", who + " :: " + Acquisitions.compress(t.title(), 80) + " — " + t.speakers().size() + " speaker(s), " + filed.size() + " question(s) filed, " + claims.size() + " claim(s) to check, " + decisions.size() + " decision(s)");
        return new Outcome(t.title(), raw == null ? "" : raw.getFileName().toString(), t.speakers(), filed, held, claims, decisions);
    }

    public static String verifyQuestion(String title, List<String> claims) {
        StringBuilder sb = new StringBuilder("Check each of these claims against sources; they were said in a meeting titled \"")
                .append(Acquisitions.compress(title, 80)).append("\" and none of them is established. For each, say whether it holds, holds with corrections, or does not hold, and cite the source.\n");
        for (int i = 0; i < claims.size(); i++) sb.append(i + 1).append(". ").append(claims.get(i)).append('\n');
        return sb.toString().strip();
    }
}
