package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A profile on Geni, read through its API instead of its page: the page needs a login and draws itself with script, so a
 * fetch of it holds nobody. The API's answer is a person and their immediate family as data: unions with partners and
 * children. That is turned into people and relations by rule, with no model in between. What Geni says is a lead like any
 * tree: the claims are drafts, and a research run looks for the records behind them.
 */
public final class GeniFamily {

    private GeniFamily() { }

    private static final Pattern PROFILE = Pattern.compile("(?i)https?://(?:www\\.)?geni\\.com/people/[^/?#]+/(\\d{6,})");

    /** The profile's id in a Geni address (…/people/Name/6000000012345678901), or null when the address is not a profile. */
    public static String guidOf(String url) { Matcher m = PROFILE.matcher(url == null ? "" : url); return m.find() ? m.group(1) : null; }

    public static String api(String guid, String token) { return "https://www.geni.com/api/profile-g" + guid + "/immediate-family?access_token=" + token; }

    /** The profiles named in an answer, by their API address, for reading further out. The focus is not among them. */
    public static List<String> relatives(JsonNode answer) {
        List<String> out = new ArrayList<>();
        answer.path("nodes").properties().forEach(e -> { if (e.getKey().startsWith("profile-") && !e.getKey().equals(answer.path("focus").path("id").asText())) out.add(e.getKey()); });
        return out;
    }

    public static FamilyAccount.Read read(JsonNode answer) { return read(answer, new LinkedHashMap<>(), new ArrayList<>()); }

    /**
     * {@code labels}: the name each Geni profile (by its id) is already filed under, kept from one read to the next. A relative arrives with a
     * first and a last name only, and the same profile read later as the person in focus arrives with its full name ("Isamu Kenji Morita, Jr."):
     * without this the library holds one profile as two people. {@code renamed} gets {old name, full name} for each profile whose full name is now known.
     * A name that is the one the profile is filed under in another order or spacing ("ハル 森田", as an older version wrote a name in characters,
     * and 森田ハル) is no new name: the profile keeps its entry, and the name as it is written now is another name of it. Geni's gender is
     * each person's sex, as a tree file's SEX is.
     */
    public static FamilyAccount.Read read(JsonNode answer, Map<String, String> labels, List<String[]> renamed) {
        JsonNode focus = answer.path("focus"), nodes = answer.path("nodes");
        Map<String, String> nameOf = new LinkedHashMap<>();
        List<FamilyAccount.Person> people = new ArrayList<>();
        List<FamilyAccount.NameRead> names = new ArrayList<>();
        List<String> dead = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        Map<String, String> sexOf = new LinkedHashMap<>();
        nodes.properties().forEach(e -> {
            if (!e.getKey().startsWith("profile-")) return;
            JsonNode p = e.getKey().equals(focus.path("id").asText()) ? focus : e.getValue();
            String name = name(p), now = name;
            if (name.isEmpty()) { dropped.add("a relative Geni keeps private (" + e.getKey() + ")"); return; }
            boolean inFocus = p == focus;
            String held = labels.get(e.getKey());
            if (held != null && !inFocus) name = held;                                   // the name this profile already has here
            else if (held != null && !held.equals(name) && sameWords(held, name)) name = held;   // the same name in another order: the entry it has
            else if (held != null && !held.equals(name)) { renamed.add(new String[]{held, name}); labels.put("retired:" + held, held); }   // its full name is known now; the short name now means this person and is never given to another profile
            else if (held == null && !inFocus && (nameOf.containsValue(name) || labels.containsValue(name))) name = name + " (Geni " + e.getKey().replaceAll("\\D", "").replaceAll("^.*(.{4})$", "$1") + ")";   // a father and a son of one first and last name are two profiles
            labels.put(e.getKey(), name);
            nameOf.put(e.getKey(), name);
            if (p.has("is_alive") && !p.path("is_alive").asBoolean(true)) dead.add(name);
            String sex = FamilyKin.sexWord(text(p, "gender"));
            if (!sex.isEmpty()) sexOf.put(name, sex);
            List<FamilyAccount.NameRead> mine = names(p, name);
            List<String> also = new ArrayList<>(maiden(p));
            if (mine.isEmpty()) for (String f : forms(p, "last_name")) if (!f.equals(name) && !also.contains(f)) also.add(f);   // one name: its other languages are other names of the person
            if (!now.equals(name) && sameWords(name, now) && !also.contains(now)) also.add(now);
            people.add(new FamilyAccount.Person(name, "", also, text(p, "last_name"), text(p, "first_name")));
            names.addAll(mine);
        });
        List<FamilyAccount.Fact> facts = new ArrayList<>();
        sexOf.forEach((who, sex) -> facts.add(new FamilyAccount.Fact(who, "sex", sex, "", "Geni: " + who + " is " + sex)));
        String me = nameOf.get(focus.path("id").asText());
        if (me != null) {
            event(facts, me, focus.path("birth"), "born");
            event(facts, me, focus.path("death"), "died");
            event(facts, me, focus.path("burial"), "buried");
            String work = focus.path("occupation").asText("").strip();
            if (!work.isEmpty()) facts.add(new FamilyAccount.Fact(me, "occupation", work, "", "Geni: " + me + ", occupation " + work));
        }
        nodes.properties().forEach(e -> {
            if (!e.getKey().startsWith("union-")) return;
            List<String> partners = new ArrayList<>(), children = new ArrayList<>(), adopted = new ArrayList<>(), fostered = new ArrayList<>();
            e.getValue().path("edges").properties().forEach(x -> {
                String who = nameOf.get(x.getKey()); if (who == null) return;
                switch (x.getValue().path("rel").asText()) { case "partner" -> partners.add(who); case "child" -> children.add(who); case "adopted_child" -> adopted.add(who); case "foster_child" -> fostered.add(who); default -> { } }
            });
            String status = e.getValue().path("status").asText("");
            if (partners.size() == 2) facts.add(new FamilyAccount.Fact(partners.get(0), "married-to", partners.get(1), "", "Geni: " + partners.get(0) + " and " + partners.get(1) + " are partners" + (status.isEmpty() ? "" : " (" + status + ")")));
            for (String parent : partners) {
                for (String c : children) facts.add(new FamilyAccount.Fact(c, "child-of", parent, "", "Geni: " + c + " is a child of " + String.join(" and ", partners)));
                for (String c : adopted) facts.add(new FamilyAccount.Fact(c, "adopted-by", parent, "", "Geni: " + c + " is an adopted child of " + String.join(" and ", partners)));
                for (String c : fostered) facts.add(new FamilyAccount.Fact(c, "foster-child-of", parent, "", "Geni: " + c + " is a foster child of " + String.join(" and ", partners)));
            }
        });
        // Geni says a person is no longer living without a date or a place: filed as a death, so the library knows they have died
        for (String who : dead) if (facts.stream().noneMatch(x -> x.subject().equals(who) && FamilyLiving.DEATH.contains(x.relation())))
            facts.add(new FamilyAccount.Fact(who, "life-event", "died", "", "Geni: " + who + " is not living"));
        return new FamilyAccount.Read(people, facts, dropped, List.of(), names, List.of());
    }

    private static String name(JsonNode p) {
        String n = p.path("name").asText("").strip();
        if (n.isEmpty()) n = full(text(p, "first_name"), text(p, "last_name"));
        return n.isEmpty() || n.startsWith("<private>") ? "" : n;
    }

    /**
     * A profile's names. A birth surname (Geni's "maiden_name", shown as Birth Surname, for a man as for a woman) that is not the last name
     * makes two names of one person: the name they were born with, and the name Geni files them under now, whose reason Geni does not give,
     * so its kind is not known. The name now is the profile's name as Geni writes it, the one the entry is filed under, with a middle name
     * and a suffix where Geni gives them; the birth name is that name with the birth surname in place of the last name. Geni's names in
     * other languages ({@code names}: {"ja": {...}, "en": {...}}) are forms of the matching name. One profile is one record, so both names
     * are one person's. Empty when Geni gives one name.
     */
    static List<FamilyAccount.NameRead> names(JsonNode p, String person) {
        String first = text(p, "first_name"), last = text(p, "last_name"), maiden = text(p, "maiden_name");
        if (first.isEmpty() || last.isEmpty() || maiden.isEmpty() || maiden.equalsIgnoreCase(last)) return List.of();
        String now = written(p), born = born(p, now), given = given(p, now);
        String quote = "Geni: " + person + ", born " + born + " (birth surname " + maiden + "), on Geni now " + now + " (last name " + last + ")";
        List<String> bornForms = new ArrayList<>(), nowForms = new ArrayList<>();
        for (String f : forms(p, "maiden_name")) if (!f.equals(born) && !bornForms.contains(f)) bornForms.add(f);
        for (String f : forms(p, "last_name")) if (!f.equals(now) && !nowForms.contains(f)) nowForms.add(f);
        return List.of(new FamilyAccount.NameRead(person, born, maiden, given, bornForms, "birth", "birth surname", "", quote),
                new FamilyAccount.NameRead(person, now, last, given, nowForms, "unknown", "", "", quote));
    }

    /**
     * The name a person was born under, when Geni gives a birth surname: the profile's name as it is written now ({@code now}), with the
     * birth surname in place of the last name, so a middle name and a suffix stay where Geni writes them; the first name and the birth
     * surname when the name now does not hold the last name. "" when Geni gives no birth surname other than the last name.
     */
    private static String born(JsonNode p, String now) {
        String first = text(p, "first_name"), last = text(p, "last_name"), maiden = text(p, "maiden_name");
        if (first.isEmpty() || last.isEmpty() || maiden.isEmpty() || maiden.equalsIgnoreCase(last)) return "";
        String cjk = FamilyForms.script(last + first);
        if ((cjk.equals("han") || cjk.equals("kana")) && now.startsWith(last)) return maiden + now.substring(last.length());
        Matcher m = Pattern.compile("(?<![\\p{L}])" + Pattern.quote(last) + "(?![\\p{L}])").matcher(now);
        int at = -1;
        while (m.find()) at = m.start();
        return at < 0 ? full(first, maiden) : now.substring(0, at) + maiden + now.substring(at + last.length());
    }

    /** The profile's name as Geni writes it now, without a note in brackets after it. */
    private static String written(JsonNode p) { return name(p).replaceAll("\\s*[(（][^)）]*[)）]\\s*$", "").strip(); }

    /** The given part of a profile's name: the first name, and the middle name where the name as written holds it. */
    private static String given(JsonNode p, String now) {
        String first = text(p, "first_name"), middle = text(p, "middle_name");
        return middle.isEmpty() || !now.contains(middle) ? first : first + " " + middle;
    }

    /**
     * Whether two names are the same words in another order or spacing: "ハル 森田" and 森田ハル, "Kenji Morita" and "Morita Kenji". The
     * words of one, in some order, spell the other.
     */
    static boolean sameWords(String a, String b) {
        String x = a == null ? "" : a.strip().toLowerCase(Locale.ROOT), y = b == null ? "" : b.strip().toLowerCase(Locale.ROOT);
        return !x.isEmpty() && !y.isEmpty() && (spelledBy(x, y) || spelledBy(y, x));
    }

    /** Whether the words of {@code a}, each once, in some order, spell {@code b} with its spaces taken out. */
    private static boolean spelledBy(String a, String b) {
        List<String> words = new ArrayList<>(List.of(a.split("[\\s　]+")));
        return words.size() <= 6 && spells(b.replaceAll("[\\s　]+", ""), words);
    }

    /** Whether the words, each once, in some order, make up the text. */
    private static boolean spells(String text, List<String> words) {
        if (words.isEmpty()) return text.isEmpty();
        for (int k = 0; k < words.size(); k++) {
            String w = words.get(k);
            if (!text.startsWith(w)) continue;
            List<String> rest = new ArrayList<>(words);
            rest.remove(k);
            if (spells(text.substring(w.length()), rest)) return true;
        }
        return false;
    }

    /** The name in each of Geni's other languages, with the family name of this field ("last_name" or "maiden_name"): the first name and that family name of each language that has both. */
    private static List<String> forms(JsonNode p, String familyField) {
        List<String> out = new ArrayList<>();
        p.path("names").properties().forEach(l -> {
            String f = text(l.getValue(), "first_name"), family = text(l.getValue(), familyField);
            if (!f.isEmpty() && !family.isEmpty() && !out.contains(full(f, family))) out.add(full(f, family));
        });
        return out;
    }

    /** A name from its parts, as it is written: family name first with no space in Chinese characters or kana (森田健二), the given name first otherwise (Kenji Morita). */
    static String full(String given, String family) {
        return FamilyForms.script(family + given).equals("han") || FamilyForms.script(family + given).equals("kana") ? family + given : (given + " " + family).strip();
    }

    private static String text(JsonNode n, String field) { return n.path(field).asText("").strip(); }

    /** The name a person was born under, when Geni gives a birth surname: kept as another name too, so a record of their childhood finds them. */
    private static List<String> maiden(JsonNode p) {
        String m = p.path("maiden_name").asText("").strip(), first = p.path("first_name").asText("").strip();
        if (m.isEmpty() || first.isEmpty() || m.equalsIgnoreCase(p.path("last_name").asText("").strip())) return List.of();
        String born = born(p, written(p));
        return List.of(born.isEmpty() ? full(first, m) : born);
    }

    private static void event(List<FamilyAccount.Fact> facts, String who, JsonNode ev, String verb) {
        if (ev.isMissingNode() || ev.isNull()) return;
        String date = ev.path("date").path("formatted_date").asText("").strip();
        if (ev.path("date").path("circa").asBoolean(false) && !date.isEmpty()) date = "about " + date.replaceFirst("(?i)^(circa|c\\.)\\s*", "");
        JsonNode loc = ev.path("location");
        List<String> parts = new ArrayList<>();
        for (String k : new String[]{"place_name", "city", "county", "state", "country"}) { String v = loc.path(k).asText("").strip(); if (!v.isEmpty() && !parts.contains(v)) parts.add(v); }
        String place = String.join(", ", parts);
        String quote = "Geni: " + who + " " + verb + (date.isEmpty() ? "" : " " + date) + (place.isEmpty() ? "" : " in " + place);
        if (!place.isEmpty()) facts.add(new FamilyAccount.Fact(who, verb.equals("born") ? "born-in" : verb.equals("died") ? "died-in" : "buried-in", place, date, quote));
        else if (!date.isEmpty() && !verb.equals("buried")) facts.add(new FamilyAccount.Fact(who, verb.equals("born") ? "born-on" : "died-on", date, "", quote));
    }
}
