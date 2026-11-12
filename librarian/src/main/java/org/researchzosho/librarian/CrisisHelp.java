package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where to find help, shown first when a question reads as a person asking about harming themselves, before the library asks whether
 * to research it. In plain words, free lines first, the line for the person's country first when their locale says which country it
 * is (the locale a program sends with the question, else the machine's; in the chat, the chat's language with the machine's country),
 * then the worldwide directory, then the others. Every sentence is the interface's words ({@link Chrome}: the English here is the key,
 * each language's file carries its own), so the help is in every language the program has a file for, by the locale's language, and in
 * English for one it has none for; the names of the services and their numbers stay as they are. A program also gets the lines as data
 * ({@link #data}).
 *
 * <p>Checked against each service's own page:
 * <ul>
 *   <li>2026-09-23: findahelpline.com, a free public service by ThroughLine: "We partner with verified helplines in 175+ countries"
 *       (/about).</li>
 *   <li>2026-09-23: United States: 988lifeline.org/get-help, "Using the 988 Lifeline is free", call, text or chat 988. 2026-09-29:
 *       988lifeline.org/es, the Spanish service: "Llame, envíe un mensaje de texto o chatee con un consejero de la línea 988 … en
 *       cualquier momento, de día o de noche", with a section "Servicios En Español". Canada: 988.ca, "Call or Text 9-8-8", "24 hours
 *       a day, every day of the year".</li>
 *   <li>2026-09-23: United Kingdom and Ireland: samaritans.org, "call us any time, from any phone for FREE. Call 116 123";
 *       samaritans.org/ireland, "24 hours a day, 365 days a year". Their email service is closing in the UK in 2026, so it is not
 *       shown.</li>
 *   <li>2026-09-23: Japan: the Ministry of Health, Labour and Welfare's list (mhlw.go.jp/mamorouyokokoro/soudan/tel/): よりそいホットライン
 *       0120-279-338, 24 hours; いのちの電話 0120-783-556, free, every day 16:00 to 21:00 (inochinodenwa.org gives the same hours).</li>
 *   <li>2026-09-23: Australia: lifeline.org.au, "Call us 13 11 14", "Support 24/7".</li>
 *   <li>2026-09-23: Germany: telefonseelsorge.de, "0800 1110111 / 0800 1110222", "Ihr Anruf ist kostenfrei", "Tag und Nacht
 *       erreichbar".</li>
 *   <li>2026-09-29: Spain: sanidad.gob.es/linea024, "Línea 024 de atención a la conducta suicida", "de alcance nacional …, gratuito,
 *       confidencial y disponible las 24 horas del día, los 365 días del año".</li>
 *   <li>2026-09-29: Argentina: asistenciaalsuicida.org.ar/horarios-de-atencion, Centro de Asistencia al Suicida, "135 (línea gratuita
 *       desde Capital y Gran Buenos Aires) (011) 5275-1135 o 0800 345 1435 (desde todo el país)", "atendemos de 8:00 a 0:00".</li>
 * </ul>
 * Mexico and Chile could not be checked: their government pages answer only a browser that passes a challenge. Until someone checks
 * them by hand they are reached through the directory.
 */
public final class CrisisHelp {

    private CrisisHelp() { }

    private static final ObjectMapper M = new ObjectMapper();

    /** One service: the ISO country codes it serves, its name, how to reach it, when, whether it is free, its page, and its sentence in English (the key of its sentence in every language). */
    record Line(List<String> countries, String name, String contact, String hours, boolean free, String url, String text) { }

    static final List<Line> LINES = List.of(
            new Line(List.of("US", "CA"), "988 Suicide & Crisis Lifeline", "call or text 988", "any hour", true, "https://988lifeline.org",
                    "In the United States and Canada: call or text 988, free, at any hour."),
            new Line(List.of("GB", "IE"), "Samaritans", "call 116 123", "any hour", true, "https://www.samaritans.org",
                    "In the United Kingdom and Ireland: call Samaritans on 116 123, free, at any hour."),
            new Line(List.of("JP"), "よりそいホットライン / いのちの電話", "0120-279-338 / 0120-783-556",
                    "よりそいホットライン any hour; いのちの電話 every day 16:00-21:00", true, "https://www.mhlw.go.jp/mamorouyokokoro/soudan/tel/",
                    "In Japan: よりそいホットライン 0120-279-338, free, at any hour; or いのちの電話 0120-783-556, free, every day from 16:00 to 21:00."),
            new Line(List.of("AU"), "Lifeline", "call 13 11 14", "any hour", false, "https://www.lifeline.org.au",
                    "In Australia: call Lifeline on 13 11 14, at any hour."),
            new Line(List.of("DE"), "TelefonSeelsorge", "call 0800 111 0 111 or 0800 111 0 222", "day and night", true, "https://www.telefonseelsorge.de",
                    "In Germany: TelefonSeelsorge, 0800 111 0 111 or 0800 111 0 222, free, day and night."),
            new Line(List.of("ES"), "Línea 024", "call 024", "any hour", true, "https://www.sanidad.gob.es/linea024/home.htm",
                    "In Spain: call 024, free and confidential, at any hour."),
            new Line(List.of("AR"), "Centro de Asistencia al Suicida", "call 135 (free from Buenos Aires city and Greater Buenos Aires), or (011) 5275-1135 or 0800 345 1435 from anywhere in the country",
                    "every day 8:00-0:00", true, "https://www.asistenciaalsuicida.org.ar",
                    "In Argentina: call 135, free from Buenos Aires city and Greater Buenos Aires; from anywhere in the country, (011) 5275-1135 or 0800 345 1435; from 8:00 to midnight."));

    static final String DIRECTORY = "Anywhere in the world: findahelpline.com lists free helplines in more than 175 countries.";

    static final String OPENING = "If you are thinking about harming yourself, you can talk to someone now, in confidence.";

    static final String EMERGENCY = "If you are in danger right now, call your local emergency number.";

    /** Every sentence of the help, in English: the keys each language's file must carry ({@code ChromeFilesTest} holds every file to them). */
    static List<String> sentences() {
        List<String> out = new ArrayList<>(List.of(OPENING, DIRECTORY, EMERGENCY));
        for (Line l : LINES) out.add(l.text());
        return out;
    }

    /** The language the help is written in for {@code locale}: its language when the program has a file for it, English otherwise. */
    static String language(Locale locale) {
        String l = locale == null ? "" : Chrome.codeOf(locale.toLanguageTag());
        return Chrome.available().contains(l) ? l : Chrome.EN;
    }

    /** A sentence of the help in a language. */
    static String said(String english, String lang) { return Chrome.in(lang).t(english); }

    /** The lines in the order they are shown: the one for {@code locale}'s country first when there is one, then the others. */
    static List<Line> ordered(Locale locale) {
        String country = locale == null ? "" : locale.getCountry().toUpperCase(Locale.ROOT);
        List<Line> out = new ArrayList<>();
        for (Line l : LINES) if (l.countries().contains(country)) out.add(l);
        for (Line l : LINES) if (!out.contains(l)) out.add(l);
        return out;
    }

    static boolean hasOwn(Locale locale) {
        String country = locale == null ? "" : locale.getCountry().toUpperCase(Locale.ROOT);
        for (Line l : LINES) if (l.countries().contains(country)) return true;
        return false;
    }

    /** The help, for the machine's own locale. */
    public static String text() { return text(Locale.getDefault()); }

    /** The help in the language the chat speaks, with the machine's country first when the help has a line for it. */
    public static String text(Chrome c) { return text(Locale.forLanguageTag(c.code + (Locale.getDefault().getCountry().isEmpty() ? "" : "-" + Locale.getDefault().getCountry()))); }

    /** The help in {@code locale}'s language: the line for its country first when there is one, then the directory, then the other lines. */
    public static String text(Locale locale) {
        String lang = language(locale);
        List<Line> lines = ordered(locale);
        boolean own = hasOwn(locale);
        StringBuilder b = new StringBuilder(said(OPENING, lang));
        for (int i = 0; i < lines.size(); i++) {
            if (i == (own ? 1 : 0)) b.append("\n- ").append(said(DIRECTORY, lang));
            b.append("\n- ").append(said(lines.get(i).text(), lang));
        }
        b.append("\n").append(said(EMERGENCY, lang));
        return b.toString();
    }

    /**
     * The same, as data for a program: the language the text is in, and each service in the order shown — the countries it serves,
     * its name, how to reach it, when, whether it is free, its page, and its sentence — with the worldwide directory among them.
     */
    public static ObjectNode data(Locale locale) {
        String lang = language(locale);
        ObjectNode d = M.createObjectNode();
        d.put("language", lang);
        ArrayNode out = d.putArray("helplines");
        List<Line> lines = ordered(locale);
        boolean own = hasOwn(locale);
        for (int i = 0; i < lines.size(); i++) {
            if (i == (own ? 1 : 0)) directory(out.addObject(), lang);
            Line l = lines.get(i);
            ObjectNode o = out.addObject();
            ArrayNode c = o.putArray("countries"); l.countries().forEach(c::add);
            o.put("name", l.name()).put("contact", l.contact()).put("hours", l.hours()).put("free", l.free()).put("url", l.url()).put("text", said(l.text(), lang));
        }
        return d;
    }

    private static void directory(ObjectNode o, String lang) {
        o.putArray("countries").add("*");
        o.put("name", "Find A Helpline").put("contact", "findahelpline.com").put("hours", "").put("free", true).put("url", "https://findahelpline.com").put("text", said(DIRECTORY, lang));
    }

    /** The question after the help, where a person can answer; no is the answer Enter gives, and no means nothing is researched. */
    public static final String ASK = "Do you want the library to research this question?";
    /** The same with its English hint, for the command line, which asks in English. */
    public static final String QUESTION = ASK + " (y/N)";
}
