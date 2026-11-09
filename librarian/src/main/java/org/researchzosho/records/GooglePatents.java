package org.researchzosho.records;

import org.researchzosho.Stopping;
import org.researchzosho.tools.Fetch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google Patents' copy of an office's record, read at the one address a publication has there, patents.google.com/patent/NUMBER/LANG:
 * the fallback for a publication's claims where the European Patent Office's service holds no claims text for it or for any member of
 * its family, which is so for every publication of the United States, China, Japan and Korea (the first live runs, 2026-11-06: 230 of
 * 284 families read by their abstracts alone, and the worker opened these pages by itself). It is never searched and never asked to
 * rank: the pool is the office's enumeration, and a page is read only by a number the program already holds. The page is asked for in
 * the language the office published in, so the claims are the record's own words and not a machine translation, then in English where
 * that page has none. A page whose claims are Google's translation says the language it translated from: the page in that language is
 * read in its place, and where there is none the words the page keeps of the original are taken, never the translation (the third
 * live run, 2026-11-06: an international application published in Chinese was written down as having no claims there, and a US
 * design patent too, which Google keeps under S1 where the office writes S). One page every few seconds; a page that refuses twice
 * running ends the asking for the call.
 */
public final class GooglePatents {
    private GooglePatents() { }

    /** A page as fetched: its status and its text. */
    public record Fetched(int status, String body) { }
    /** The fetch; a test stands in for it. */
    public interface Reader { Fetched read(String address) throws Exception; }
    public static volatile Reader READER = GooglePatents::fetch;
    /** The milliseconds between two pages, and how the pace is scaled (a test sets it to nothing). */
    static final long PACE_MS = 3_000;
    public static volatile double pace = 1.0;
    private static long lastAt = 0;

    /** The site would not answer just now: said as that, and asked again another time. */
    public static final class Refused extends IOException {
        public Refused(String message) { super(message); }
    }

    /**
     * A publication as its page gives it: the number, the address it was read at, the language of the claims, each claim's text in
     * order, and the status the page words ("Pending", "Active", "Expired"), which is Google's own reading and no legal conclusion.
     */
    public record Record(String publication, String address, String lang, List<String> claims, String status) { }

    private static final Pattern US_APPLICATION = Pattern.compile("US(\\d{4})(\\d{6})(A\\d)");
    private static final Pattern US_DESIGN = Pattern.compile("USD\\d+S");

    /**
     * A publication's number as Google Patents writes it: the office's, with the zero after the year that it drops from a US
     * application's number, and S1 where it writes S for a US design.
     */
    public static String number(String id) {
        String n = id == null ? "" : id.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        Matcher us = US_APPLICATION.matcher(n);
        if (us.matches()) return "US" + us.group(1) + "0" + us.group(2) + us.group(3);
        return US_DESIGN.matcher(n).matches() ? n + "1" : n;
    }

    /** The language an office publishes in, by the publication's country: the page asked for first. */
    public static String lang(String id) {
        String country = id == null || id.length() < 2 ? "" : id.substring(0, 2).toUpperCase(Locale.ROOT);
        return switch (country) { case "CN", "TW" -> "zh"; case "JP" -> "ja"; case "KR" -> "ko"; case "DE", "AT" -> "de"; case "FR" -> "fr"; case "ES" -> "es"; case "RU" -> "ru"; case "BR", "PT" -> "pt"; default -> "en"; };
    }

    /** The one address a publication has there, in a language. */
    public static String address(String id, String lang) { return "https://patents.google.com/patent/" + number(id) + "/" + lang; }

    /**
     * A publication's claims from its page, in the office's language and then in English: null when Google keeps no such page or the
     * page has no claims; {@link Refused} when the site would not answer. Where a page's claims are a translation, the page in the
     * language it was translated from is read for them, once.
     */
    public static Record read(String id) throws IOException, InterruptedException {
        String own = lang(id);
        Set<String> asked = new LinkedHashSet<>();
        for (String lang : own.equals("en") ? List.of("en") : List.of(own, "en")) {
            if (!asked.add(lang)) continue;
            String address = address(id, lang);
            Fetched f = paced(address);
            if (f.status() == 404 || f.status() == 410) return null;   // Google keeps no page under this number
            if (f.status() != 200) throw new Refused("Google Patents did not give the page of " + id + " (HTTP " + f.status() + ")");
            Page p = page(f.body(), id, address);
            if (p.translated() && !p.from().isEmpty() && asked.add(p.from())) {
                String original = address(id, p.from());
                Fetched o;
                try { o = paced(original); } catch (IOException e) { o = null; }   // the words the first page keeps of the original stand
                if (o != null && o.status() == 200) {
                    Page q = page(o.body(), id, original);
                    if (q.record() != null && !q.translated()) return q.record();
                }
            }
            if (p.record() != null) return p.record();
        }
        return null;
    }

    private static Fetched paced(String address) throws IOException, InterruptedException {
        long wait;
        synchronized (GooglePatents.class) {
            long now = System.currentTimeMillis(), due = lastAt + (long) (PACE_MS * pace);
            wait = Math.max(0, due - now);
            lastAt = now + wait;
        }
        if (wait > 0) Stopping.sleep(wait);
        try { return READER.read(address); }
        catch (IOException | InterruptedException e) { throw e; }
        catch (Exception e) { throw new Refused("Google Patents did not answer (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")"); }
    }

    static Fetched fetch(String address) throws Exception {
        Fetch.Result r = Fetch.get(address, Duration.ofSeconds(30));
        return new Fetched(r.status(), r.body() == null ? "" : new String(r.body(), StandardCharsets.UTF_8));
    }

    private static final Pattern CLAIMS = Pattern.compile("(?s)<section[^>]*itemprop=\"claims\"[^>]*>(.*?)</section>");
    /** The element that holds the claims says their language and who made the text: an office, WIPO's reading of the page image, or Google (a translation). */
    private static final Pattern CLAIMS_FROM = Pattern.compile("\\blang=\"([A-Za-z-]{2,8})\"[^>]*\\bload-source=\"([^\"]*)\"");
    private static final Pattern CLAIMS_LANG = Pattern.compile("<div[^>]*\\blang=\"([A-Za-z-]{2,8})\"[^>]*class=\"claims\"|<div[^>]*class=\"claims\"[^>]*\\blang=\"([A-Za-z-]{2,8})\"");
    /** A claim as an office's text has it, a block with its number, and as WIPO's and a translated text have it, an element of its own. */
    private static final Pattern CLAIM = Pattern.compile("(?s)<div[^>]*\\bnum=\"(\\d+)[^\"]*\"[^>]*class=\"claim\"[^>]*>(.*?)(?=<div[^>]*\\bnum=\"\\d+[^\"]*\"[^>]*class=\"claim\"|\\z)");
    private static final Pattern CLAIM_ELEMENT = Pattern.compile("(?s)<claim\\b[^>]*\\bnum=\"(\\d+)[^\"]*\"[^>]*>(.*?)</claim>");
    private static final Pattern CLAIM_TEXT = Pattern.compile("<div[^>]*class=\"claim-text\"|<claim-text\\b");
    private static final Pattern ORIGINAL = Pattern.compile("(?s)<span[^>]*class=\"google-src-text\"[^>]*>(.*?)</span>");
    private static final Pattern TRANSLATED_FROM = Pattern.compile("itemprop=\"translatedLanguage\"[^>]*>([^<]{2,30})<");
    private static final Pattern STATUS = Pattern.compile("(?s)itemprop=\"legalStatusIfi\".*?itemprop=\"status\"[^>]*>([^<]{1,60})<|itemprop=\"ifiStatus\"[^>]*>([^<]{1,60})<");

    /** What a page holds: its claims in the office's own words, if any; whether the page's claims are Google's translation; and the language translated from, "" when the page does not name one this program knows. */
    record Page(Record record, boolean translated, String from) { }

    /** The claims and the status off a page's text; null when the page has no claims section or no claim in the office's own words. */
    static Record parse(String html, String id, String address) { return page(html, id, address).record(); }

    static Page page(String html, String id, String address) {
        Matcher section = CLAIMS.matcher(html == null ? "" : html);
        if (!section.find()) return new Page(null, false, "");
        String body = section.group(1);
        Matcher by = CLAIMS_FROM.matcher(body);
        boolean said = by.find();
        Matcher named = TRANSLATED_FROM.matcher(body);
        boolean wasNamed = named.find();
        boolean translated = wasNamed || said && by.group(2).equalsIgnoreCase("google");
        String from = wasNamed ? code(named.group(1)) : "";
        List<String> claims = new ArrayList<>();
        Matcher c = CLAIM.matcher(body);
        if (!c.find()) c = CLAIM_ELEMENT.matcher(body);
        c.reset();
        while (c.find()) {
            String whole = c.group(2), text;
            if (translated) {
                // a translated claim keeps the original's words beside the translation: those are the record's, the translation is never taken
                StringBuilder original = new StringBuilder();
                Matcher o = ORIGINAL.matcher(whole);
                while (o.find()) original.append(' ').append(o.group(1));
                text = original.toString();
            } else {
                // a claim's own lines; the nested ones repeat inside their parent, so the tags are taken off the whole and the pieces joined once
                if (!CLAIM_TEXT.matcher(whole).find()) continue;
                text = whole;
            }
            String plain = unescape(text.replaceAll("(?s)<[^>]+>", " ")).replaceAll("\\s+", " ").strip().replaceFirst("^\\d{1,3}\\s*[.．、)]\\s*", "");
            if (!plain.isEmpty()) claims.add(plain);
        }
        if (claims.isEmpty()) return new Page(null, translated, from);
        String lang;
        if (translated) lang = from;
        else if (said) lang = by.group(1).toLowerCase(Locale.ROOT);
        else { Matcher l = CLAIMS_LANG.matcher(body); lang = l.find() ? (l.group(1) != null ? l.group(1) : l.group(2)).toLowerCase(Locale.ROOT) : ""; }
        Matcher s = STATUS.matcher(html);
        String status = s.find() ? unescape(s.group(1) != null ? s.group(1) : s.group(2)).strip() : "";
        return new Page(new Record(id, address, lang, claims, status), translated, from);
    }

    /** The page's name for a language as the code its address takes; "" for one this program does not know. */
    static String code(String language) {
        return switch (language == null ? "" : language.strip().toLowerCase(Locale.ROOT)) {
            case "chinese" -> "zh"; case "japanese" -> "ja"; case "korean" -> "ko"; case "german" -> "de"; case "french" -> "fr";
            case "spanish" -> "es"; case "russian" -> "ru"; case "portuguese" -> "pt"; case "italian" -> "it"; case "dutch" -> "nl";
            default -> "";
        };
    }

    static String unescape(String s) { return s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&"); }

    /** The pace's clock back to nothing, for a test. */
    public static void forget() { synchronized (GooglePatents.class) { lastAt = 0; } }
}
