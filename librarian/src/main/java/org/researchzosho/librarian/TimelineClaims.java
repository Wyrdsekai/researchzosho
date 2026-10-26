package org.researchzosho.librarian;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A dossier report's Timeline, one claim a dated event (0.6.6). A run notes a handful of claims; its report carries every dated event the
 * workers found, "1984: The Big U [84]", with the references the sentence cites. The dossier and the story are views over the claims, so
 * the events the report alone carried never reached them (a third run listed twenty novels and filed nine claims, none of them a book,
 * 2026-10-23). This reads the Timeline section as the writer was told to write it, year by year, resolves each citation to its reference,
 * and files one claim per event, sourced by those references, with the report named; a second pass over the same report files nothing.
 */
public final class TimelineClaims {
    private TimelineClaims() { }

    /**
     * A date as the Timeline writes it: a year, a year and month, a full date, a decade or a span, with "c.", "~", "Early" or a month's name
     * before it or a month in brackets after it ("1984-12-10", "1994-07", "1999–2006", "1978–79", "c. 1981", "1973 (Jul)", "2016 (Aug–Oct)",
     * "Early 2000s", "~2005–2007", "Jan 2007–Jan 2009", "Sep 2021 – Jun 2024", "July 7, 2020", "7 July 2020"). The model writes dates many
     * ways; the step reads them all.
     */
    static final String MONTH = "(?:(?:\\d{1,2}\\s+)?(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?\\s+(?:\\d{1,2}(?:st|nd|rd|th)?,?\\s+)?)?";
    static final String WHEN = "(?:(?:[Ee]arly|[Mm]id|[Ll]ate|c\\.|ca\\.|~)\\s*)?" + MONTH + "\\d{4}s?(?:-\\d{2}(?:-\\d{2})?)?"
            + "(?:\\s*[–-]\\s*" + MONTH + "\\d{2,4}s?(?:-\\d{2}(?:-\\d{2})?)?)?(?:\\s*\\([^()]{1,14}\\))?";
    /** What stands between the date and the event: a colon, or a dash with a space about it ("1973 (Jul) — Born"). */
    static final String SEP = "\\s*(?:[:：]|\\s[—–-]\\s)\\s*";
    static final Pattern CITE = Pattern.compile("\\[(\\d+)\\]");
    /** A citation of a claim on the shelves, "[F-1338-x]": the event is sourced by that claim's sources. */
    static final Pattern CLAIM_CITE = Pattern.compile("\\[(F-\\d+[A-Za-z0-9-]*)\\]");
    /** A sentence the checks faulted, marked inline by the library: not a claim. */
    static final Pattern FAULT_MARK = Pattern.compile("\\[(?:not supported|number not in|no catalogue record|quotation|name not in)");
    /** A reference's locator: a web address, or a file the person supplied ({@code file://…}, {@code raw/…}), which is a source like any other. */
    static final Pattern REF = Pattern.compile("^\\[(\\d+)\\] (?:.*? — )?((?:https?://|file://|raw/)\\S+)", Pattern.MULTILINE);

    public record Event(String year, String text, List<String> locators) { }

    /** The sections a report's dated events live in: a person's Timeline, an organisation's Filings written as a timeline, a story's turning points, the Decisions of the people in power. */
    static final String[] DATED_SECTIONS = {"Timeline", "Filings", "Turning points", "Decisions", "Statements", "Media"};
    /** How a decision's claim opens, so the dossier can list the decisions apart: "X, 2023-09-11: Decision — the board accepted the grant, because …". */
    public static final String DECISION = "Decision — ";
    /** How a statement's claim opens: "X, 2019-06: Said — at the Science Fiction Book Club: \"Heinlein has stuck with me…\"". */
    public static final String SAID = "Said — ";
    /** How a media record's claim opens: "X, 2019: Media — a portrait at a conference, by C. Michel, Wikimedia Commons, CC BY-SA 4.0". */
    public static final String MEDIA = "Media — ";

    /** The events of a report's dated sections, each with the locators its citations name; a bulleted line is an event like any other. */
    public static List<Event> events(String body) {
        Map<String, String> refs = new LinkedHashMap<>();   // the citations' numbers resolve in the References the library wrote, else in the Sources the writer did
        for (String list : new String[] {"References", "Sources", "Sources cited"}) {
            Matcher r = REF.matcher(section(body, list));
            while (r.find()) refs.putIfAbsent(r.group(1), r.group(2).replaceAll("[.,;)]+$", ""));
        }
        List<Event> out = new ArrayList<>();
        for (String name : DATED_SECTIONS) {
            String s = section(body, name);
            if (s.isEmpty()) continue;
            String mark = name.equals("Decisions") ? DECISION : name.equals("Statements") ? SAID : name.equals("Media") ? MEDIA : "";
            for (Event e : eventsOf(s.replaceAll("(?m)^\\s*[-*•]\\s+", ""), refs)) out.add(mark.isEmpty() ? e : new Event(e.year(), mark + e.text(), e.locators()));
        }
        return out;
    }

    /** The events of one section's text: one a line when the writer wrote it so; else one paragraph, split where a date follows the end of a sentence. */
    static List<Event> eventsOf(String sectionText, Map<String, String> refs) {
        String tl = sectionText.strip();
        if (tl.isEmpty()) return List.of();
        Pattern dated = Pattern.compile("^" + WHEN + SEP);
        List<String> lines = new ArrayList<>(); int datedLines = 0;
        for (String l : sectionText.split("\n")) { String s = l.strip(); if (s.isEmpty()) continue; lines.add(s); if (dated.matcher(s).find()) datedLines++; }
        List<String> pieces = datedLines >= 2 ? lines : List.of(tl.replace("\n", " ").split("(?<=[.\\]])\\s*(?=" + WHEN + SEP + ")"));
        List<Event> out = new ArrayList<>();
        for (String piece : pieces) {
            Matcher m = Pattern.compile("^(" + WHEN + ")" + SEP + "(.+)$", Pattern.DOTALL).matcher(piece.strip());
            if (!m.find()) continue;
            String year = m.group(1), text = m.group(2).strip();
            List<String> locs = new ArrayList<>();
            Matcher c = CITE.matcher(text);
            while (c.find()) { String u = refs.get(c.group(1)); if (u != null && !locs.contains(u)) locs.add(u); }
            Matcher cc = CLAIM_CITE.matcher(text);
            while (cc.find()) { String u = "claim:" + cc.group(1); if (!locs.contains(u)) locs.add(u); }
            String clean = CLAIM_CITE.matcher(CITE.matcher(text).replaceAll("")).replaceAll("").replaceAll("\\s+", " ").replaceAll("\\s+([.,;])", "$1").strip();
            if (clean.endsWith(".")) clean = clean.substring(0, clean.length() - 1);
            if (clean.length() < 3) continue;
            out.add(new Event(year, clean, locs));
        }
        return out;
    }

    /** Files the Timeline's events as claims on {@code subject}, skipping the ones already on the shelves; the ids filed, in order. */
    public static List<String> file(LibraryStore store, Investigation inv, String subject) throws IOException {
        List<Event> events = events(inv.body());
        if (events.isEmpty() || subject == null || subject.isBlank()) return List.of();
        Set<String> have = new HashSet<>();
        Map<String, List<Set<String>>> byYear = new HashMap<>();   // the words of every claim already filed on this subject for a year: three reports phrase one event three ways
        String lead = (subject + ", ").toLowerCase(Locale.ROOT);
        for (Finding f : store.scanFindings().findings()) {
            have.add(key(f.title()));
            String t = f.title().toLowerCase(Locale.ROOT);
            if (t.startsWith(lead)) { int colon = t.indexOf(':'); if (colon > lead.length()) byYear.computeIfAbsent(sameKind(yearKey(t.substring(lead.length(), colon)), t.substring(colon + 1)), k -> new ArrayList<>()).add(words(t.substring(colon + 1))); }
        }
        LibrarianIndex index = new LibrarianIndex(store);
        List<String> filed = new ArrayList<>();
        List<String> faulted = faulted(inv.body());
        Map<String, String> titleOf = refTitles(inv.body());   // a media record names the file it cites, so the dossier can show it
        for (Event raw : events) {
            Event e = resolveClaimCites(store, raw);
            if (e.text().startsWith(MEDIA)) {
                List<String> named = new ArrayList<>();
                for (String l : e.locators()) { String t = titleOf.get(l); if (t != null && !t.isEmpty() && !e.text().contains(t) && !named.contains(t)) named.add(t); }
                if (!named.isEmpty()) e = new Event(e.year(), e.text() + " (" + String.join("; ", named) + ")", e.locators());
            }
            if (e.locators().isEmpty()) continue;   // an event the report did not source is not a claim
            String ek = key(e.text());
            if (FAULT_MARK.matcher(e.text()).find() || faulted.stream().anyMatch(ek::contains)) continue;   // a sentence the checks faulted is not a claim
            String title = subject + ", " + e.year() + ": " + Acquisitions.compress(e.text(), 70);
            if (!have.add(key(title))) continue;
            Set<String> w = words(e.text());
            boolean same = false;
            for (Set<String> other : byYear.getOrDefault(sameKind(yearKey(e.year()), e.text()), List.of())) if (overlap(w, other) >= 0.5 && !numbersDiffer(w, other)) { same = true; break; }
            if (same) continue;   // "1992: Snow Crash", "1992: Published Snow Crash" and "July 7, 1992: Snow Crash published" are one event; "6,000 jobs cut" and "4,245 jobs cut" are two
            byYear.computeIfAbsent(sameKind(yearKey(e.year()), e.text()), k -> new ArrayList<>()).add(w);
            List<Finding.Source> sources = new ArrayList<>();
            for (String l : e.locators()) sources.add(new Finding.Source(l, "n/a", "cited for this event in " + inv.id()));
            String id = store.nextFindingId(title);
            Finding f = new Finding(id, title, List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium, "librarian:timeline",
                    Instant.now().toString(), heldDate(e.year()), Finding.Volatility.stable, "", sources, List.of(), null,
                    subject + ", " + e.year() + ": " + e.text() + ". (An event of the Timeline of " + inv.id() + ", with the references that sentence cites.)\n");
            store.write(f); index.upsert(f); filed.add(id);
        }
        if (!filed.isEmpty()) {
            List<String> all = new ArrayList<>(inv.findings()); all.addAll(filed);
            store.write(new Investigation(inv.id(), inv.title(), inv.state(), inv.writer(), inv.recordedAt(), all, inv.open(), inv.body()));
            store.circulate("timeline", inv.id() + ": " + filed.size() + " dated events filed as claims on " + subject);
        }
        return filed;
    }

    /**
     * Whose dossier a question is, from its words: "Who is X", "corporate history of X", "dossier on X"; "The Index, the Institute's flagship
     * project: …" is the Institute's, a product or project being a facet of its maker; "Acme Corp, the Needham maker of widgets: who runs it"
     * opens with its subject. Null when the words do not say.
     */
    public static String subjectOf(String question) {
        if (question == null) return null;
        Matcher m = Pattern.compile("[Ww]ho (?:is|was) ([A-Z][^?,:;]+?)[?,:;]").matcher(question);
        if (m.find()) return m.group(1).strip();
        Matcher d = Pattern.compile("(?i:dossier on|background on|profile of|biography of|corporate history of|history of) ((?:the )?[A-Z\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}][^?,:;.]+?)[?,:;.]").matcher(question + ".");
        if (d.find()) return d.group(1).strip();
        Matcher j = Pattern.compile("^([\\p{IsHan}\\p{IsKatakana}ー]{2,24}?)(?:の|について|とは|、|：)").matcher(question.strip());   // 高市早苗の経歴と人物像 → 高市早苗
        if (j.find()) return j.group(1);
        Matcher o = Pattern.compile(", ((?:the )?[A-Z][^,:;?'’]{2,60}?)['’]s [^,:;?]*?\\b(?:project|product|programme|program|index|ranking|brand|service|division|unit|subsidiary|venture|initiative|imprint|label|series|book|novel|album|film|show|flagship)\\b[^:]*:").matcher(question);
        if (o.find() && nameLike(o.group(1))) return o.group(1).strip();
        Matcher a = Pattern.compile("^((?:[Tt]he )?[A-Z][^,:;?]{2,60}?), [^:;?]*:").matcher(question.strip());
        return a.find() && nameLike(a.group(1)) ? a.group(1).strip() : null;
    }

    /** The words a question opens with that are not a name: "As of October 2026, …", "For anime, …", "What does a fully agentic, …". */
    static final Set<String> NOT_A_NAME = Set.of("as", "for", "what", "how", "why", "when", "where", "who", "which", "whom", "whose", "in", "on", "at", "by", "from", "with", "after",
            "before", "since", "until", "given", "during", "among", "if", "is", "are", "was", "were", "does", "do", "did", "can", "could", "should", "would", "will", "has", "have", "had",
            "about", "regarding", "concerning", "list", "compare", "explain", "describe", "find", "summarize", "summarise", "tell", "give", "write", "research", "investigate", "to", "of",
            "and", "or", "but", "so", "not", "no", "yes", "there", "this", "that", "these", "those", "it", "its", "my", "our", "your", "their", "all", "any", "some", "every", "each", "most",
            "many", "few", "one", "two", "three", "first", "last", "today", "now", "please", "also", "just", "only", "between", "over", "under", "into", "through", "across", "without",
            "within", "beyond", "against", "per", "via", "versus", "vs");
    /** Whether a lead reads as a name: a few words, each capitalised, a number or CJK, bar the small words inside a name (Bank of America); a lowercase word makes it a sentence. */
    static boolean nameLike(String lead) {
        String s = lead == null ? "" : lead.strip().replaceFirst("^(?i:the )", "");
        String[] w = s.split("\\s+");
        if (s.isEmpty() || w.length > 8) return false;
        if (NOT_A_NAME.contains(w[0].toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", ""))) return false;
        int named = 0;
        for (String x : w) {
            String y = x.replaceAll("^[(\"'“‘]+|[)\"'”’.,]+$", "");
            if (y.isEmpty()) continue;
            if (y.matches("(?i)of|and|the|for|de|du|von|van|der|den|la|le|y|&|at|to|in|on")) continue;
            int c = y.codePointAt(0);
            Character.UnicodeScript sc = Character.UnicodeScript.of(c);
            if (Character.isUpperCase(c) || Character.isDigit(c) || sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA || sc == Character.UnicodeScript.KATAKANA || sc == Character.UnicodeScript.HANGUL) named++;
            else return false;
        }
        return named >= 1;
    }

    /** Whose dossier a report is: its question's words, else the name most of the run's claims lead with ("X: …"); null when neither says. */
    public static String subjectOf(LibraryStore store, Investigation inv) throws IOException {
        String fromWords = subjectOf(inv.title());
        if (fromWords != null) return fromWords;
        Map<String, Integer> lead = new LinkedHashMap<>();
        for (String id : inv.findings()) {
            Finding f; try { f = store.finding(id); } catch (IOException | RuntimeException e) { continue; }
            if (f == null) continue;
            int colon = f.title().indexOf(": "), comma = f.title().indexOf(", ");
            int cut = colon < 0 ? comma : comma < 0 ? colon : Math.min(colon, comma);   // "X: …" and "X, 1984: …" both lead with X
            if (cut > 2 && cut < 60) lead.merge(f.title().substring(0, cut).strip(), 1, Integer::sum);
        }
        String best = null; int n = 0;
        for (var e : lead.entrySet()) if (e.getValue() > n) { best = e.getKey(); n = e.getValue(); }
        return n >= 2 ? best : null;
    }

    static String key(String title) { return title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip(); }
    /** A year's events are compared among their own kind: a decision of 2012 is not the same event as what happened in 2012, however many words they share. */
    static String sameKind(String year, String text) {
        String t = text.strip().toLowerCase(Locale.ROOT);
        return t.startsWith(DECISION.toLowerCase(Locale.ROOT)) ? year + " decision" : t.startsWith(SAID.toLowerCase(Locale.ROOT)) ? year + " said" : t.startsWith(MEDIA.toLowerCase(Locale.ROOT)) ? year + " media" : year;
    }

    static final Pattern MONTH_NAME = Pattern.compile("(?i)\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?");
    /** The date an event held, as a claim's valid_as_of: "Feb 17, 2023" → 2023-02-17, "2021 (Feb 2)" → 2021-02-02, "1996 (Jan)" → 1996-01, "1984-12-10" as it is, "2012" → 2012, a span by its first year. */
    static String heldDate(String when) {
        if (when == null) return LocalDate.now().toString();
        String w = when.strip();
        Matcher iso = Pattern.compile("\\b(1[5-9]\\d\\d|20\\d\\d)-(0[1-9]|1[0-2])(?:-(0[1-9]|[12]\\d|3[01]))?").matcher(w);
        if (iso.find()) return iso.group(3) == null ? iso.group(1) + "-" + iso.group(2) : iso.group();
        Matcher y = Pattern.compile("\\b(1[5-9]\\d\\d|20\\d\\d)").matcher(w);
        if (!y.find()) return LocalDate.now().toString();
        String year = y.group(1);
        Matcher m = MONTH_NAME.matcher(w);
        if (!m.find()) return year;
        String month = String.format(Locale.ROOT, "%02d", List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(m.group(1).toLowerCase(Locale.ROOT)) + 1);
        Matcher d = Pattern.compile("(?<![\\d-])(0?[1-9]|[12]\\d|3[01])(?:st|nd|rd|th)?(?![\\d])").matcher(w.replace(year, "    "));
        String day = null;
        while (d.find()) {   // the day stands beside the month's name, before it ("24 June") or after ("February 17", "(Feb 2)")
            int gap = Math.min(Math.abs(d.start() - m.end()), Math.abs(m.start() - d.end()));
            if (gap <= 3) { day = String.format(Locale.ROOT, "%02d", Integer.parseInt(d.group(1))); break; }
        }
        return year + "-" + month + (day == null ? "" : "-" + day);
    }
    static final Pattern REF_TITLED = Pattern.compile("^\\[(\\d+)\\] (.*?) — ((?:https?://|file://|raw/)\\S+)", Pattern.MULTILINE);
    /** The title each reference address carries in the References or Sources list ("[14] File:Neal Stephenson 2008 crop.jpg, Wikimedia Commons — https://…"): address → title. */
    static Map<String, String> refTitles(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String list : new String[] {"References", "Sources"}) {
            Matcher r = REF_TITLED.matcher(section(body, list));
            while (r.find()) out.putIfAbsent(r.group(3).replaceAll("[.,;)]+$", ""), r.group(2).replaceAll("\\s*\\(.*$", "").strip());
        }
        return out;
    }

    /** A citation of a claim ("[F-1338-x]") stands for that claim's sources: the event is sourced by what sourced the claim. A claim not on the shelves sources nothing. */
    static Event resolveClaimCites(LibraryStore store, Event e) {
        if (e.locators().stream().noneMatch(l -> l.startsWith("claim:"))) return e;
        List<String> out = new ArrayList<>();
        for (String l : e.locators()) {
            if (!l.startsWith("claim:")) { if (!out.contains(l)) out.add(l); continue; }
            String id = l.substring(6);
            Finding f; try { f = id.matches("F-\\d+") ? store.findingByCode(id) : store.finding(id); } catch (IOException | RuntimeException x) { f = null; }   // the writer cites a claim by its code, "[F-1338]"
            if (f == null) continue;
            for (Finding.Source s : f.sources()) if (s.locator() != null && !s.locator().startsWith("claim:") && !out.contains(s.locator())) out.add(s.locator());
        }
        return new Event(e.year(), e.text(), out);
    }

    /** What the library's checks faulted in this report: the numbers, quotations and names no source states, as the Checks section lists them, folded for matching. */
    static List<String> faulted(String body) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^- (?:number|quotation|name) (.+?) — (?:not in any note or source read this run|in no source read this run)(?::\\s*\"(.+?)\")?\\s*$").matcher(section(body, "Checks"));
        while (m.find()) {
            String frag = m.group(2) != null ? m.group(2) : m.group(1).replaceAll("^\"|\"$", "");
            String k = key(frag.replaceAll("…$", ""));
            if (k.length() >= 6) out.add(k);
        }
        return out;
    }

    /** The year a date is placed in: "2020", "2020 (Jul)", "July 7, 2020" and "2020-07-07" are one year, so the same event dated three ways is one claim. */
    static String yearKey(String when) { Matcher y = Pattern.compile("\\d{4}").matcher(when); return y.find() ? y.group() : when.strip().toLowerCase(Locale.ROOT); }

    /** The words that tell one event from another: not the ones every event has (published, released, the novel). */
    static final Set<String> STOP = Set.of("the", "a", "an", "of", "as", "in", "at", "to", "and", "published", "publishes", "released", "release", "publication", "novel", "book", "his", "her", "their", "by", "with", "is", "was");
    static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").split(" ")) if (w.length() > 1 && !STOP.contains(w)) out.add(w);
        return out;
    }
    /** Shared words over the smaller set; 0 when either has none. */
    /** Two texts that both carry numbers and share none speak of different things, however many words they share: the February cut of 4,245 jobs and the August cut of 6,000. */
    static boolean numbersDiffer(Set<String> a, Set<String> b) {
        Set<String> na = new HashSet<>(), nb = new HashSet<>();
        for (String w : a) if (w.matches("\\d+")) na.add(w);
        for (String w : b) if (w.matches("\\d+")) nb.add(w);
        if (na.isEmpty() || nb.isEmpty()) return false;
        for (String n : na) if (nb.contains(n)) return false;
        return true;
    }

    static double overlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int n = 0; for (String w : a) if (b.contains(w)) n++;
        return (double) n / Math.min(a.size(), b.size());
    }

    /** The appendix of verbatim worker notes that closes a fan run's record: what follows is the workers' own text, which echoes whatever they read, the dossier's timeline among it. */
    static final String APPENDIX = "\n## Worker findings";

    /** The report proper: the write-up and the library's own sections, without the appendix of worker notes. */
    static String proper(String body) {
        if (body == null) return "";
        int at = ("\n" + body).indexOf(APPENDIX);
        return at < 0 ? body : body.substring(0, at);
    }

    static String section(String body, String name) {
        if (body == null || body.isEmpty()) return "";
        body = "\n" + proper(body);   // a section may be the first line of the body; the appendix of worker notes is not read
        int at = body.indexOf("\n## " + name + "\n");
        if (at < 0) return "";
        String rest = body.substring(at + name.length() + 5);
        int end = rest.indexOf("\n## "); return (end < 0 ? rest : rest.substring(0, end)).strip();
    }
}
