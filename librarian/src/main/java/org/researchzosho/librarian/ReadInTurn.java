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
    /** "read this in Japanese", "show me the report in English", "translate I-0012-… into French", "open the dossier on Ada Example in German". */
    static final Pattern EN = Pattern.compile("(?i)^\\s*(?:please\\s+)?(?:read|show|give|open|translate)\\s+(?:me\\s+)?(?<what>.*?)\\s+(?:in|into)\\s+(?<lang>[\\p{L}-]+)\\s*[.!?]?\\s*$");
    /** "これを日本語で読む", "この報告書を英語で", "I-0012-… を日本語でお願い". */
    static final Pattern JA = Pattern.compile("^\\s*(?<what>.*?)\\s*を?\\s*(?<lang>日本語|英語|中国語|韓国語|ドイツ語|フランス語|スペイン語|ポルトガル語|イタリア語)で(?:読|見|訳|表示|お願い|ください|下さい|$).*$");
    static final Map<String, String> JA_NAMES = Map.of("日本語", "ja", "英語", "en", "中国語", "zh", "韓国語", "ko", "ドイツ語", "de", "フランス語", "fr", "スペイン語", "es", "ポルトガル語", "pt", "イタリア語", "it");
    static final Pattern REPORT_ID = Pattern.compile("\\bI-\\d{4}[A-Za-z0-9-]*");
    static final Pattern DOSSIER = Pattern.compile("(?i)^(?:the\\s+)?dossier\\s+(?:on|of|for)\\s+(?<subject>.+)$");
    static final Pattern THIS = Pattern.compile("(?i)^(?:this|it|that|this one|the report|the last report|that report|the answer|the write-?up|これ|それ|この報告書|その報告書|報告書)?$");

    private ReadInTurn() { }

    record Ask(String what, String lang) { }

    /** What is said when the words asked to read something in a language and nothing could be made of what. */
    static final String UNRESOLVED = "Which report? Name it by its id, or open it in this conversation first, and say again which language to read it in.";

    /** The ask in the words, or null when they are not one: a language the library knows must close them. */
    static Ask parse(String words) {
        if (words == null) return null;
        Matcher m = EN.matcher(words);
        if (m.matches()) { String code = Translate.code(m.group("lang")); return Translate.LANGUAGES.containsKey(code) ? new Ask(m.group("what").strip(), code) : null; }
        m = JA.matcher(words);
        if (m.matches()) return new Ask(m.group("what").strip(), JA_NAMES.get(m.group("lang")));
        return null;
    }

    /** The reply, or null when the words are not such an ask. */
    static String reply(LibraryStore store, Librarian.Session session, Patrons.Patron patron, String words) throws IOException {
        Ask ask = parse(words);
        if (ask == null) return null;
        String name = ReadIn.name(ask.lang());
        Jobs jobs = new Jobs(store, j -> "");
        boolean writer = Patrons.mayWrite(store, patron);
        Matcher d = DOSSIER.matcher(ask.what());
        if (d.matches()) return dossier(store, jobs, patron, writer, d.group("subject").strip().replaceAll("[.!?]+$", ""), ask.lang(), name);
        Matcher id = REPORT_ID.matcher(ask.what());
        String report = id.find() ? id.group() : THIS.matcher(ask.what()).matches() ? lastShown(session) : null;
        if (report == null) return null;   // not a report this turn can name: the words may be a file's ("translate this file into Japanese"); {@link #UNRESOLVED} when nobody takes them
        Investigation inv;
        try { inv = store.investigation(report); } catch (Exception e) { inv = null; }
        if (inv == null) return "The library holds no report " + report + ".";
        String written = Translate.writtenIn(inv);
        String link = "/entry/" + report + "?in=" + ask.lang();
        if (ask.lang().equals(written)) return "\"" + inv.title() + "\" is written in " + name + " already: /entry/" + report + ".";
        Translate.Translated tr = Translate.currentReport(store, inv, ask.lang());
        if (tr != null) {
            String title = tr.words() == null ? "" : tr.words().getOrDefault(inv.title(), "");
            return "\"" + inv.title() + "\" in " + name + (title.isEmpty() ? "" : ": \"" + title + "\"") + " — " + link + ". The translation is kept; the cites, the claim ids and the dates stand as they are.";
        }
        String running = Translate.pendingReport(jobs, report, ask.lang());
        if (running != null) return "\"" + inv.title() + "\" is being translated into " + name + " (" + running + "): a few minutes on the model. Then open " + link + ".";
        if (!writer) return "No translation of \"" + inv.title() + "\" into " + name + " is kept yet; a writer's visit to " + link + " starts one.";
        ObjectNode a = M.createObjectNode(); a.put("report", report); a.put("lang", ask.lang());
        String job = jobs.submit("translate", patron == null ? "" : patron.did(), a);
        return "Translating \"" + inv.title() + "\" into " + name + " now (" + job + "): a few minutes on the model, section by section. Then open " + link + "; it is shown as written meanwhile.";
    }

    static String dossier(LibraryStore store, Jobs jobs, Patrons.Patron patron, boolean writer, String subject, String lang, String name) throws IOException {
        String link = "/dossier?subject=" + Pages.enc(subject) + "&in=" + lang;
        Translate.Translated tr = Translate.current(store, subject, lang);
        if (tr != null) return "The dossier on " + subject + " in " + name + ": " + link + ". The translation is kept; the cites, the claim ids, the dates and the badges stand as they are.";
        String running = Translate.pending(jobs, subject, lang);
        if (running != null) return "The dossier on " + subject + " is being translated into " + name + " (" + running + "): a few minutes on the model. Then open " + link + ".";
        if (!writer) return "No translation of the dossier on " + subject + " into " + name + " is kept yet; a writer's visit to " + link + " starts one.";
        ObjectNode a = M.createObjectNode(); a.put("subject", subject); a.put("lang", lang);
        String job = jobs.submit("translate", patron == null ? "" : patron.did(), a);
        return "Translating the dossier on " + subject + " into " + name + " now (" + job + "): a few minutes on the model, each piece of prose. Then open " + link + ".";
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
