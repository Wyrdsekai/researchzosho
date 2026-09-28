package org.researchzosho.librarian;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where to find help, shown first when a question reads as a person asking about harming themselves, before the library asks whether
 * to research it. In plain words, free lines first, the line for the person's country first when the machine's locale says which
 * country it is, then the worldwide directory, then the others.
 *
 * <p>Checked on 2026-09-23 against each service's own page:
 * <ul>
 *   <li>findahelpline.com, a free public service by ThroughLine: "We partner with verified helplines in 175+ countries" (/about).</li>
 *   <li>United States: 988lifeline.org/get-help, "Using the 988 Lifeline is free", call, text or chat 988. Canada: 988.ca, "Call or Text
 *       9-8-8", "24 hours a day, every day of the year".</li>
 *   <li>United Kingdom and Ireland: samaritans.org, "call us any time, from any phone for FREE. Call 116 123"; samaritans.org/ireland,
 *       "24 hours a day, 365 days a year". Their email service is closing in the UK in 2026, so it is not shown.</li>
 *   <li>Japan: the Ministry of Health, Labour and Welfare's list (mhlw.go.jp/mamorouyokokoro/soudan/tel/): よりそいホットライン
 *       0120-279-338, 24 hours; いのちの電話 0120-783-556, free, every day 16:00 to 21:00 (inochinodenwa.org gives the same hours).</li>
 *   <li>Australia: lifeline.org.au, "Call us 13 11 14", "Support 24/7".</li>
 *   <li>Germany: telefonseelsorge.de, "0800 1110111 / 0800 1110222", "Ihr Anruf ist kostenfrei", "Tag und Nacht erreichbar".</li>
 * </ul>
 */
public final class CrisisHelp {

    private CrisisHelp() { }

    /** One country's line: the ISO country codes it serves, and the sentence. */
    record Line(List<String> countries, String text) { }

    static final List<Line> LINES = List.of(
            new Line(List.of("US", "CA"), "In the United States and Canada: call or text 988, free, at any hour."),
            new Line(List.of("GB", "IE"), "In the United Kingdom and Ireland: call Samaritans on 116 123, free, at any hour."),
            new Line(List.of("JP"), "In Japan: よりそいホットライン 0120-279-338, free, at any hour; or いのちの電話 0120-783-556, free, every day from 16:00 to 21:00."),
            new Line(List.of("AU"), "In Australia: call Lifeline on 13 11 14, at any hour."),
            new Line(List.of("DE"), "In Germany: TelefonSeelsorge, 0800 111 0 111 or 0800 111 0 222, free, day and night."));

    static final String DIRECTORY = "Anywhere in the world: findahelpline.com lists free helplines in more than 175 countries.";

    /** The help, for the machine's own locale. */
    public static String text() { return text(Locale.getDefault()); }

    /** The help, the line for {@code locale}'s country first when there is one, then the directory, then the other lines. */
    public static String text(Locale locale) {
        String country = locale == null ? "" : locale.getCountry().toUpperCase(Locale.ROOT);
        List<String> lines = new ArrayList<>();
        Line own = null;
        for (Line l : LINES) if (l.countries().contains(country)) own = l;
        if (own != null) lines.add(own.text());
        lines.add(DIRECTORY);
        for (Line l : LINES) if (l != own) lines.add(l.text());
        StringBuilder b = new StringBuilder("If you are thinking about harming yourself, you can talk to someone now, in confidence.");
        for (String l : lines) b.append("\n- ").append(l);
        b.append("\nIf you are in danger right now, call your local emergency number.");
        return b.toString();
    }

    /** The question after the help, where a person can answer; no is the answer Enter gives, and no means nothing is researched. */
    public static final String QUESTION = "Do you want the library to research this question? (y/N)";
}
