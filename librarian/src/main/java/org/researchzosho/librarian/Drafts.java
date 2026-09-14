package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Check my draft": the person's own text — a memo, a chapter, notes — as a starting point. One voice, so
 * every definite statement is a claim to check and every question in it is theirs. The citations it
 * carries (urls, DOIs, arXiv ids) are fetched and shelved so the check reads what the draft rests on.
 */
public final class Drafts {

    static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'()\\[\\]]+[^\\s<>\"'()\\[\\].,;:]");
    static final int MAX_CITATIONS = org.researchzosho.Config.getInt("RESEARCHZOSHO_DRAFT_CITATIONS", 40);

    public record Outcome(String title, String raw, List<String> claims, List<String> questionsFiled, List<String> questionsHeld, List<Shelving.Got> citations) { }

    private Drafts() { }

    /** The locators a text cites: urls as written, DOIs and arXiv ids as the urls that resolve them. */
    public static List<String> citations(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Citations.DOI.matcher(text);
        while (m.find()) out.add(Shelving.doiUrl(m.group(1)));
        m = Citations.ARXIV.matcher(text);
        while (m.find()) { String id = m.group(1) != null ? m.group(1) : m.group(3); if (id != null) out.add("https://arxiv.org/abs/" + id); }
        m = URL.matcher(text);
        while (m.find()) { String u = m.group(); if (!u.contains("doi.org/") && !u.contains("arxiv.org/")) out.add(u); }
        return new ArrayList<>(out).subList(0, Math.min(out.size(), MAX_CITATIONS));
    }

    public static Outcome check(LibraryStore store, String text, String title, String source, String collection, String who, Function<String, List<String>> extractor, boolean fetchCitations) throws IOException {
        Conversations.Thread t = new Conversations.Thread(title, List.of(new Conversations.Turn("author", text.strip())));
        String locator = "draft://" + Conversations.hash8(text);
        Path raw = RawCapture.capture(store, locator, text, title, "draft:" + Acquisitions.compress(source, 120), collection);
        List<String> filed = new ArrayList<>(), held = new ArrayList<>();
        List<Frontier.Line> open = Frontier.read(store);
        for (String q : Conversations.questions(t)) {
            if (Items.file(store, open, q, who, title)) filed.add(q); else held.add(q);
        }
        List<String> claims = Conversations.claims(t, extractor);
        List<Shelving.Got> cites = new ArrayList<>();
        if (fetchCitations) for (String u : citations(text)) cites.add(Shelving.fetch(store, u, collection, "cited by the draft \"" + Acquisitions.compress(title, 60) + "\""));
        store.circulate("draft", who + " :: " + Acquisitions.compress(title, 80) + " — " + claims.size() + " claim(s) to check, " + cites.size() + " citation(s) looked at, " + filed.size() + " question(s) filed");
        return new Outcome(title, raw == null ? "" : raw.getFileName().toString(), claims, filed, held, cites);
    }

    /** The question for the run that checks a draft's claims. */
    public static String verifyQuestion(String title, List<String> claims) {
        StringBuilder sb = new StringBuilder("Check each of these claims against sources; they come from a draft titled \"")
                .append(Acquisitions.compress(title, 80)).append("\" and the draft's own citations are on the shelves. For each, say whether it holds, holds with corrections, or does not hold, and cite the source.\n");
        for (int i = 0; i < claims.size(); i++) sb.append(i + 1).append(". ").append(claims.get(i)).append('\n');
        return sb.toString().strip();
    }
}
