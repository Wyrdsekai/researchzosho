package org.researchzosho.librarian;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Words that were given (0.8.1): what the person wrote, and text that came from outside in a run or a chat (a search's results, a page
 * that was opened, a tool's answer from outside). {@link GivenAddresses} holds the web addresses of the same texts; this holds the
 * words, for the one tool whose words go to hosts it finds while it runs: the records lookup ({@code Outbound.FINDS}), which asks a
 * county's data portals for a street. The street it asks for has to be one the person gave or an outside text stated, never one the
 * model made up and never words out of what the library holds: a made-up street is a sentence of the model's own sent to whichever
 * portal a search returned.
 *
 * <p>The comparison is made after both sides are written the same way, so the ordinary case is not refused: small letters, no
 * punctuation, a postal word in its short form (Street and St, Avenue and Ave, North and N, Fifth and 5th), a state's name as its two
 * letters. A unit (Apt 4B) and a ZIP code are no part of it: the lookup sends neither to a portal. In the person's own words the house
 * number and the street's words may stand in any order close together ("on Oak Street, number 12"); in an outside text, which may be a
 * page of ten thousand words, they must stand together as an address does, and a text that came back from a call whose own words held
 * that street does not make it given (a search for a made-up street answers "results for …"). The city and the state are held to
 * the same as the street: it has to stand in the person's words or in an outside text that its own call's words did not bring, or
 * right after the street in an outside text that states the whole address (a listing a search for the town's houses found). The
 * state is one of the lookup's own list, and is refused only when it contradicts a state that was given ({@link #streetNotGiven}).
 *
 * <p>Like the given addresses, the texts are the process's, the newest kept up to a limit; nothing is written to disk.
 */
public final class GivenWords {
    private GivenWords() { }

    /**
     * One text as it is compared, the words of the call that brought it ("" for the person's own), and where in it a state is named
     * as a state ({@link #read}): the places, in {@code words}, at which such a name begins, in order.
     */
    private record Text(String words, String callsOwn, boolean person, int[] states) { }

    /** A text as it is compared, with the places in it where a state is named as one. */
    record Read(String words, int[] states) { }

    private static final Deque<Text> TEXTS = new ArrayDeque<>();
    private static long held;
    /** The most kept in all, and of one text: in characters as compared. */
    static final long KEEP = 24_000_000;
    static final int ONE = 1_000_000;
    /** How far apart, in words, the parts of a street may stand in the person's own words. */
    static final int NEAR = 16;

    private static final Map<String, String> SHORT = Map.ofEntries(
            Map.entry("street", "st"), Map.entry("str", "st"), Map.entry("avenue", "ave"), Map.entry("av", "ave"), Map.entry("boulevard", "blvd"), Map.entry("road", "rd"),
            Map.entry("drive", "dr"), Map.entry("lane", "ln"), Map.entry("court", "ct"), Map.entry("place", "pl"), Map.entry("square", "sq"), Map.entry("terrace", "ter"),
            Map.entry("parkway", "pkwy"), Map.entry("highway", "hwy"), Map.entry("circle", "cir"), Map.entry("trail", "trl"), Map.entry("alley", "aly"), Map.entry("crescent", "cres"),
            Map.entry("heights", "hts"), Map.entry("junction", "jct"), Map.entry("mount", "mt"), Map.entry("mountain", "mtn"), Map.entry("point", "pt"), Map.entry("ridge", "rdg"),
            Map.entry("route", "rte"), Map.entry("saint", "st"), Map.entry("fort", "ft"), Map.entry("apartment", "apt"), Map.entry("suite", "ste"), Map.entry("plaza", "plz"),
            Map.entry("turnpike", "tpke"), Map.entry("expressway", "expy"), Map.entry("freeway", "fwy"), Map.entry("center", "ctr"), Map.entry("centre", "ctr"),
            Map.entry("north", "n"), Map.entry("south", "s"), Map.entry("east", "e"), Map.entry("west", "w"), Map.entry("northeast", "ne"), Map.entry("northwest", "nw"),
            Map.entry("southeast", "se"), Map.entry("southwest", "sw"),
            Map.entry("first", "1st"), Map.entry("second", "2nd"), Map.entry("third", "3rd"), Map.entry("fourth", "4th"), Map.entry("fifth", "5th"), Map.entry("sixth", "6th"),
            Map.entry("seventh", "7th"), Map.entry("eighth", "8th"), Map.entry("ninth", "9th"), Map.entry("tenth", "10th"));

    /** A state's name as it is written out, to its two letters; the longer names first, so that West Virginia is not Virginia. */
    private static final String[][] STATES = {
            {"district of columbia", "dc"}, {"north carolina", "nc"}, {"south carolina", "sc"}, {"north dakota", "nd"}, {"south dakota", "sd"}, {"new hampshire", "nh"},
            {"new jersey", "nj"}, {"new mexico", "nm"}, {"new york", "ny"}, {"rhode island", "ri"}, {"west virginia", "wv"}, {"puerto rico", "pr"},
            {"alabama", "al"}, {"alaska", "ak"}, {"arizona", "az"}, {"arkansas", "ar"}, {"california", "ca"}, {"colorado", "co"}, {"connecticut", "ct"}, {"delaware", "de"},
            {"florida", "fl"}, {"georgia", "ga"}, {"hawaii", "hi"}, {"idaho", "id"}, {"illinois", "il"}, {"indiana", "in"}, {"iowa", "ia"}, {"kansas", "ks"}, {"kentucky", "ky"},
            {"louisiana", "la"}, {"maine", "me"}, {"maryland", "md"}, {"massachusetts", "ma"}, {"michigan", "mi"}, {"minnesota", "mn"}, {"mississippi", "ms"}, {"missouri", "mo"},
            {"montana", "mt"}, {"nebraska", "ne"}, {"nevada", "nv"}, {"ohio", "oh"}, {"oklahoma", "ok"}, {"oregon", "or"}, {"pennsylvania", "pa"}, {"tennessee", "tn"},
            {"texas", "tx"}, {"utah", "ut"}, {"vermont", "vt"}, {"virginia", "va"}, {"washington", "wa"}, {"wisconsin", "wi"}, {"wyoming", "wy"}};

    /** A state's name written out, to its two letters; the two letters of every state; the first words of the names of more than one word. */
    private static final Map<String, String> STATE_NAMES = new HashMap<>();
    private static final Set<String> STATE_CODES = new TreeSet<>(), FIRST_OF_SEVERAL = new TreeSet<>();
    static {
        for (String[] st : STATES) { STATE_NAMES.put(st[0], st[1]); STATE_CODES.add(st[1]); if (st[0].indexOf(' ') > 0) FIRST_OF_SEVERAL.add(st[0].substring(0, st[0].indexOf(' '))); }
    }
    /** The states' letters that are also a word of English, or a postal word's short form: in a text they name a state only where an address would (after a comma, before a ZIP code). */
    private static final Set<String> ALSO_WORDS = Set.of("in", "or", "me", "ok", "hi", "oh", "id", "la", "pa", "ma", "de", "co", "al", "mt", "ct", "ne", "ms", "md");

    /**
     * A text as it is compared: its words in small letters with one space between and one at each end, nothing that is not a letter or
     * a digit, each postal word in its short form and each state's name as its two letters.
     */
    static String normal(String s) { return read(s).words(); }

    /**
     * The same, with where a state is named as a state: its name written out (Illinois, North Carolina), or its two letters in
     * capitals (IL); letters that are also a word (IN, OR, ME, OK) only after a comma or before a ZIP code, as an address has them.
     * A state's name is read before the postal words are shortened, so that West Virginia is not "W" and Virginia.
     */
    static Read read(String s) {
        if (s == null || s.isEmpty()) return new Read(" ", new int[0]);
        String t = Normalizer.normalize(s.length() > ONE ? s.substring(0, ONE) : s, Normalizer.Form.NFKC);
        List<String> written = new ArrayList<>();
        BitSet afterComma = new BitSet();
        StringBuilder word = new StringBuilder();
        boolean comma = false;
        for (int i = 0; i <= t.length(); i++) {
            int c = i < t.length() ? t.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c)) { word.append((char) c); continue; }
            if (c == '\'' || c == '’') continue;   // O'Brien and OBrien are one word
            if (word.length() > 0) { if (comma) afterComma.set(written.size()); written.add(word.toString()); word.setLength(0); comma = false; }
            if (c == ',') comma = true;
        }
        int n = written.size();
        String[] low = new String[n];
        for (int i = 0; i < n; i++) low[i] = written.get(i).toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(t.length() + 2).append(' ');
        int[] states = new int[8];
        int found = 0;
        for (int i = 0; i < n; i++) {
            String code = STATE_NAMES.get(low[i]);
            int took = code == null ? 0 : 1;
            if (FIRST_OF_SEVERAL.contains(low[i])) for (int k = Math.min(3, n - i); k >= 2; k--) {   // the longer names first: West Virginia before Virginia
                String several = STATE_NAMES.get(String.join(" ", Arrays.copyOfRange(low, i, i + k)));
                if (several != null) { code = several; took = k; break; }
            }
            boolean named = code != null;
            if (!named) {
                String w = written.get(i);
                named = w.length() == 2 && Character.isUpperCase(w.charAt(0)) && Character.isUpperCase(w.charAt(1)) && STATE_CODES.contains(low[i])
                        && (!ALSO_WORDS.contains(low[i]) || afterComma.get(i) || (i + 1 < n && low[i + 1].matches("\\d{5}")));
            }
            if (named) { if (found == states.length) states = Arrays.copyOf(states, found * 2); states[found++] = b.length(); }
            if (code != null) { b.append(code).append(' '); i += took - 1; }
            else b.append(SHORT.getOrDefault(low[i], low[i])).append(' ');
        }
        return new Read(b.toString(), Arrays.copyOf(states, found));
    }

    /**
     * A state as the records lookup takes it: its two letters in capitals, from the letters or from the name written out, a ZIP code
     * after it left off; null for anything that is not one of the states of the list here ({@link #STATES}).
     */
    static String stateCode(String written) {
        if (written == null) return null;
        String w = written.strip().replaceFirst("[\\s,]*\\d{5}(?:-\\d{4})?$", "").replace(".", "").replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
        String code = STATE_CODES.contains(w) ? w : STATE_NAMES.get(w);
        return code == null ? null : code.toUpperCase(Locale.ROOT);
    }

    private static void keep(Text t) {
        synchronized (TEXTS) {
            TEXTS.addLast(t);
            held += t.words().length() + t.callsOwn().length();
            while (held > KEEP && TEXTS.size() > 1) { Text gone = TEXTS.removeFirst(); held -= gone.words().length() + gone.callsOwn().length(); }
        }
    }

    /** What the person wrote. */
    public static void person(String words) { if (words != null && !words.isBlank()) { Read r = read(words); keep(new Text(r.words(), "", true, r.states())); } }

    /** A text that came from outside; {@code callsOwn} the words of the call that brought it (its arguments), or null. */
    public static void outside(String text, String callsOwn) { if (text != null && !text.isBlank()) { Read r = read(text); keep(new Text(r.words(), callsOwn == null || callsOwn.isBlank() ? "" : normal(callsOwn), false, r.states())); } }

    /**
     * Whether a name or a number was given, as a street is: its letters and digits stand together and in order in the person's words
     * or in a text from outside that its own call's words did not bring; in the person's own words also close together in any order
     * ("the ferry-tables repository of harbour-works"). For the addresses the library builds itself ({@link GivenIdentifiers}).
     */
    public static boolean stated(String words) { return words != null && !normal(words).isBlank() && given(words, true); }

    /** How the sentence a model reads begins when a street was not given. */
    public static final String NOT_LOOKED_UP = "NOT LOOKED UP: ";

    /** Forgets everything: a test's way to a clean start. */
    static void forget() { synchronized (TEXTS) { TEXTS.clear(); held = 0; } }

    private static List<Text> texts() { synchronized (TEXTS) { return new ArrayList<>(TEXTS); } }

    /** Whether these words stand together in a text that was given; a street's, in the person's own words, close together in any order. */
    static boolean given(String words, boolean street) {
        String w = normal(words);
        if (w.isBlank()) return true;
        String[] parts = w.strip().split(" ");
        for (Text t : texts()) {
            // what an outside text states is not given by it when the call that brought the text held those words itself: a search's
            // answer begins by repeating its words ("results for …"), so a street, a city or a state the model searched for would
            // otherwise be one "an outside text stated" (0.8.1: a city was held to no such thing, and a made-up one was looked up
            // after the model's own search for it)
            if (t.words().contains(w)) { if (t.person() || !t.callsOwn().contains(w)) return true; continue; }
            if (!street || !t.person()) continue;
            if (near(t.words(), parts)) return true;
        }
        return false;
    }

    /** How many words after a street its city and its state may stand in an outside text that states the address (a unit, a ZIP code between them). */
    static final int AFTER = 8;

    /**
     * Whether a city or a state was given: it stands in the person's words, or in an outside text that its own call's words did not
     * bring. A text whose call did hold it (a search for "homes for sale portsmouth") still gives it where the text states it as part
     * of the address: right after the street, as an address is written, the street being one the call's words did not hold. So a
     * listing a search found gives its whole address, and a search's answer that only repeats the words it was asked for gives none.
     */
    static boolean placeGiven(String place, String street) {
        String w = normal(place), s = normal(street);
        if (w.isBlank()) return true;
        for (Text t : texts()) {
            if (!t.words().contains(w)) continue;
            if (t.person() || !t.callsOwn().contains(w)) return true;
            if (!s.isBlank() && !t.callsOwn().contains(s) && follows(t.words(), s, w)) return true;
        }
        return false;
    }

    /** Whether {@code place} stands within {@link #AFTER} words after {@code street} somewhere in {@code text} (all three as compared). */
    private static boolean follows(String text, String street, String place) {
        for (int at = text.indexOf(street); at >= 0; at = text.indexOf(street, at + 1)) {
            int from = at + street.length() - 1, to = from;   // the space that ends the street
            for (int words = 0; words < AFTER && to < text.length() - 1; words++) { int next = text.indexOf(' ', to + 1); if (next < 0) { to = text.length() - 1; break; } to = next; }
            if (text.substring(from, Math.min(text.length(), to + 1)).contains(place)) return true;
        }
        return false;
    }

    /** Whether every one of {@code parts} is a word of {@code text} within {@link #NEAR} words of the first of them. */
    private static boolean near(String text, String[] parts) {
        String[] words = text.strip().split(" ");
        for (int i = 0; i < words.length; i++) {
            if (!words[i].equals(parts[0])) continue;
            int from = Math.max(0, i - NEAR), to = Math.min(words.length, i + NEAR + 1);
            boolean all = true;
            for (String p : parts) { boolean here = false; for (int k = from; k < to && !here; k++) here = words[k].equals(p); if (!here) { all = false; break; } }
            if (all) return true;
        }
        return false;
    }

    /**
     * The states that the texts which give this street name for this city: a state named as one right after the city, as an address
     * is written ("Springfield, Illinois", "Springfield IL 62701"). In an outside text the city has to follow the street within a
     * few words; in the person's own words the two stand close together in either order.
     */
    static Set<String> statesGivenFor(String street, String city) {
        String s = normal(street), c = normal(city);
        Set<String> out = new TreeSet<>();
        if (c.isBlank() || s.isBlank()) return out;
        String[] parts = s.strip().split(" ");
        for (Text t : texts()) {
            if (t.states().length == 0 || !t.words().contains(c)) continue;
            // where the street stands in this text, when this text gives it
            List<Integer> streets = new ArrayList<>();
            if (t.words().contains(s)) { if (t.person() || !t.callsOwn().contains(s)) for (int i = t.words().indexOf(s); i >= 0; i = t.words().indexOf(s, i + 1)) streets.add(i); }
            else if (t.person() && near(t.words(), parts)) { String first = " " + parts[0] + " "; for (int i = t.words().indexOf(first); i >= 0; i = t.words().indexOf(first, i + 1)) streets.add(i); }
            if (streets.isEmpty()) continue;
            for (int i = t.words().indexOf(c); i >= 0; i = t.words().indexOf(c, i + 1)) {
                int next = i + c.length();   // where the word after the city begins
                if (Arrays.binarySearch(t.states(), next) < 0) continue;
                for (int at : streets) {
                    if (!t.person() && i < at) continue;
                    int from = Math.min(at, i), to = Math.max(at, i), between = 0;
                    for (int k = from; k < to; k++) if (t.words().charAt(k) == ' ') between++;
                    if (between <= (t.person() ? NEAR : AFTER) + parts.length) { out.add(t.words().substring(next, next + 2).toUpperCase(Locale.ROOT)); break; }
                }
            }
        }
        return out;
    }

    /**
     * The sentence a model reads when this address is not one to look up, or null when it is. The house number and the street's name,
     * as the records lookup itself takes them out of the address ({@code Instruments.streetParts}, {@code HomeSensors.street}), stand
     * in the person's words or in an outside text, and so does the city ({@link #placeGiven}).
     *
     * <p>The state has a rule of its own. It has to be one of the states of the lookup's own list, by its letters or its name:
     * anything else in its place is refused. A state that contradicts one the person or an outside text gave for this address is
     * refused. Where none was given, a real state is accepted: people say "1 City Hall Square, Boston" and leave the state to
     * whoever reads it, and asking them for it would refuse the ordinary case. This is enough because a state out of a list of
     * about fifty carries under six bits, and it goes only to hosts of the program's own list and to the portals the lookup's own
     * search names for the given city in that state; the street and the city stay held to the person's words or an outside text's.
     */
    public static String streetNotGiven(String address) {
        String[] written = address == null ? new String[0] : address.split("\\s*,\\s*");
        if (written.length >= 3 && !written[0].isBlank() && !written[1].isBlank() && stateCode(written[2]) == null)
            return NOT_LOOKED_UP + "the records lookup takes an address as street, city, state, and \"" + written[2].strip() + "\" is not a state it knows. Give the state by its name or its two letters, as the person wrote it; when they named none, give the state the city is in.";
        String[] parts = Instruments.streetParts(address);
        if (parts == null) return null;   // not an address the lookup takes: it says so itself, and nothing is asked
        int number = HomeSensors.houseNumber(parts[0]);
        String street = (number > 0 ? number + " " : "") + HomeSensors.street(parts[0]);
        boolean streetAndCity = given(street, true) && placeGiven(parts[1], street);
        Set<String> states = streetAndCity ? statesGivenFor(street, parts[1]) : Set.of();
        if (streetAndCity && (states.isEmpty() || states.contains(parts[2]))) return null;
        if (streetAndCity) return NOT_LOOKED_UP + "this address was given in " + String.join(" or ", states) + ", and the call says " + parts[2] + ". Give the state as the person or the page gave it.";
        return NOT_LOOKED_UP + "the records lookup asks for a street address that the person gave or that a page or a search result stated, and \"" + parts[0].strip() + ", " + parts[1].strip()
                + "\" is in neither. Give the address as the person wrote it (the house number, the street's name and the city), or ask them for it.";
    }
}
