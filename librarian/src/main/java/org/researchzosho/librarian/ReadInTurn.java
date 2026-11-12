package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Read this in Japanese", said in the chat: the report or the dossier the conversation showed last, or the one named, read in that
 * language through the mechanism the pages use, the translation kept when one is, the translate run started when none is. A turn the
 * library answers itself, with the link; the model is not asked.
 */
final class ReadInTurn {
    private static final ObjectMapper M = new ObjectMapper();
    /**
     * The ask, in every language's words ({@link ChatWords}, "read_in.ask"): "read this in Japanese", "show me the report in English",
     * "translate I-0012-… into French", "これを日本語で読む", "lis ce rapport en japonais"; "this report" ("this_report") and "the dossier on
     * X" ("dossier.named") likewise.
     */
    static final Pattern REPORT_ID = Pattern.compile("\\bI-\\d{4}[A-Za-z0-9-]*");

    private ReadInTurn() { }

    record Ask(String what, String lang) { }

    /** What is said when the words asked to read something in a language and nothing could be made of what. */
    static final String UNRESOLVED = "Which report? Name it by its id, or open it in this conversation first, and say again which language to read it in.";

    /** The places an ask names: what to read (as little as will do), a language by any of its names, the subject of a dossier. */
    static Map<String, String> places() { return Map.of("what", ".*?", "lang", ChatWords.languageNames(), "subject", ".+"); }

    /** The ask in the words, or null when they are not one: a language the library knows must close them. */
    static Ask parse(String words) {
        if (words == null) return null;
        Map<String, String> m = ChatWords.ask("read_in.ask", words, places(), false, g -> !ChatWords.language(g.getOrDefault("lang", "")).isEmpty() && g.get("what") != null);
        return m == null ? null : new Ask(m.get("what").strip(), ChatWords.language(m.get("lang")));
    }

    /** The reply, or null when the words are not such an ask. */
    static String reply(LibraryStore store, Librarian.Session session, Patrons.Patron patron, String words) throws IOException {
        Ask ask = parse(words);
        if (ask == null) return null;
        Chrome c = Chrome.now();
        String name = c.languageName(ask.lang());
        Jobs jobs = new Jobs(store, j -> "");
        boolean writer = Patrons.mayWrite(store, patron);
        Map<String, String> d = ChatWords.ask("dossier.named", ask.what(), places());
        if (d != null && d.get("subject") != null) return dossier(store, jobs, patron, writer, d.get("subject").strip().replaceAll("[.!?。！？]+$", ""), ask.lang(), name);
        Matcher id = REPORT_ID.matcher(ask.what());
        String report = id.find() ? id.group() : ask.what().isBlank() || ChatWords.whole("this_report", ask.what()) ? lastShown(session) : null;
        if (report == null) return null;   // not a report this turn can name: the words may be a file's ("translate this file into Japanese"); {@link #UNRESOLVED} when nobody takes them
        Investigation inv;
        try { inv = store.investigation(report); } catch (Exception e) { inv = null; }
        if (inv == null) return c.t("The library holds no report {0}.", report);
        String written = Translate.writtenIn(inv);
        String link = "/entry/" + report + "?in=" + ask.lang();
        if (ask.lang().equals(written)) return c.t("\"{0}\" is written in {1} already: {2}.", inv.title(), name, "/entry/" + report);
        Translate.Translated tr = Translate.currentReport(store, inv, ask.lang());
        if (tr != null) {
            String title = tr.words() == null ? "" : tr.words().getOrDefault(inv.title(), "");
            return (title.isEmpty() ? c.t("\"{0}\" in {1} — {2}.", inv.title(), name, link) : c.t("\"{0}\" in {1}: \"{2}\" — {3}.", inv.title(), name, title, link)) + " " + c.t("The translation is kept; the cites, the claim ids and the dates stand as they are.");
        }
        String running = Translate.pendingReport(jobs, report, ask.lang());
        if (running != null) return c.t("\"{0}\" is being translated into {1} ({2}): a few minutes on the model. Then open {3}.", inv.title(), name, running, link);
        if (!writer) return c.t("No translation of \"{0}\" into {1} is kept yet; a writer's visit to {2} starts one.", inv.title(), name, link);
        ObjectNode a = M.createObjectNode(); a.put("report", report); a.put("lang", ask.lang());
        String job = jobs.submit("translate", patron == null ? "" : patron.did(), a);
        return c.t("Translating \"{0}\" into {1} now ({2}): a few minutes on the model, section by section. Then open {3}; it is shown as written meanwhile.", inv.title(), name, job, link);
    }

    static String dossier(LibraryStore store, Jobs jobs, Patrons.Patron patron, boolean writer, String subject, String lang, String name) throws IOException {
        Chrome c = Chrome.now();
        String link = "/dossier?subject=" + Pages.enc(subject) + "&in=" + lang;
        Translate.Translated tr = Translate.current(store, subject, lang);
        if (tr != null) return c.t("The dossier on {0} in {1}: {2}. The translation is kept; the cites, the claim ids, the dates and the badges stand as they are.", subject, name, link);
        String running = Translate.pending(jobs, subject, lang);
        if (running != null) return c.t("The dossier on {0} is being translated into {1} ({2}): a few minutes on the model. Then open {3}.", subject, name, running, link);
        if (!writer) return c.t("No translation of the dossier on {0} into {1} is kept yet; a writer's visit to {2} starts one.", subject, name, link);
        ObjectNode a = M.createObjectNode(); a.put("subject", subject); a.put("lang", lang);
        String job = jobs.submit("translate", patron == null ? "" : patron.did(), a);
        return c.t("Translating the dossier on {0} into {1} now ({2}): a few minutes on the model, each piece of prose. Then open {3}.", subject, name, job, link);
    }

    /** The report this conversation showed last: the newest tool answer or reply that carries a report's id. */
    static String lastShown(Librarian.Session session) {
        if (session == null) return null;
        List<ObjectNode> ms = session.messages;
        for (int i = ms.size() - 1; i >= 0; i--) {
            JsonNode m = ms.get(i);
            String role = m.path("role").asText("");
            if (!role.equals("tool") && !role.equals("assistant")) continue;
            String content = m.path("content").asText("");
            Matcher id = REPORT_ID.matcher(content);
            String last = null;
            while (id.find()) { last = id.group(); if (role.equals("tool")) break; }   // a tool's answer names the entry opened first; a reply's last link is the one it ended on
            if (last != null) return last.toLowerCase(Locale.ROOT).equals(last) ? last : last;
        }
        return null;
    }
}
