package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.HomeFind.Row;
import org.researchzosho.librarian.HomeFind.View;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.researchzosho.librarian.Pages.t;
import static org.researchzosho.librarian.Pages.n;

/**
 * The Home page: the searches this library holds, and one search with its stand at the top, the search button, and the sections folded
 * below: what the person decided, their own words, the listings in tiers with like and pass, the ones set aside with why, a box to save
 * a listing they found themselves, the portals for them to open, the cards, and lately.
 */
public final class HomePage {
    private HomePage() { }

    /** The front door: the searches, and a form to start one; with one search, that search's page. */
    public static String front(LibraryStore store, boolean writer) throws IOException {
        List<Home.Search> all = Home.searches(store);
        if (all.size() == 1) return body(store, all.get(0), writer);
        StringBuilder b = new StringBuilder();
        b.append("<p>").append(t("Looking for a home: say where, whether to buy or to rent, the budget and the rest, and the library looks for listings in every source it can read, keeps the ones that fit, and reads the records of the few that fit best.")).append("</p>");
        if (all.isEmpty()) b.append("<p>").append(t("No search is started yet.")).append("</p>");
        else {
            b.append("<ul>");
            for (Home.Search s : all) b.append("<li><a href=\"/homes?search=").append(Pages.esc(s.slug())).append("\">").append(Pages.esc(s.name())).append("</a> <span class=\"k\">").append(Pages.esc(Home.standing(store, s))).append("</span></li>");
            b.append("</ul>");
        }
        if (writer) b.append(startForm());
        b.append(sourcesNote());
        b.append(recordsForm());
        return b.toString();
    }

    /** The records of one address, without a search: the form the records op answers. */
    static String recordsForm() {
        return "<h2 id=\"records\">" + t("Records of an address") + "</h2><p class=\"k\">" + t("Any address in the United States: the line that says which public source answered, the sales or values and the permits on the street, and the tract's figures when a Census key is set.") + "</p>"
                + "<form method=\"post\" action=\"/homes\" class=\"inline\">" + hidden("op", "records") + "<input name=\"address\" size=\"48\" placeholder=\"108 Example St, Cambridge, MA\" aria-label=\"" + Pages.esc(t("the address")) + "\"> <button>" + t("Read the records") + "</button></form>";
    }

    static String startForm() {
        return "<form method=\"post\" action=\"/homes\" class=\"inline\">" + hidden("op", "start") + "<input name=\"name\" size=\"40\" placeholder=\"" + Pages.esc(t("a name for the search: yours, or what it is for")) + "\" aria-label=\"" + Pages.esc(t("the search's name")) + "\"> <button>" + t("Start a search") + "</button></form>";
    }

    static String sourcesNote() {
        return "<p class=\"k\">" + t("Where the listings come from:") + " " + (HomeSources.rentCastKeySet() ? t("RentCast's listings for sale and for rent (a key is set; {0}),", Pages.esc(HomeSources.rentCastMonth())) + " " : "")
                + t("the search engines' own snippets of the portals' pages as leads, the estate agents' and builders' own sites found through the map, and what you save yourself from your browser.") + " "
                + t("The portals (Zillow, Redfin, Realtor.com, Trulia, Apartments.com) and HUD's list of the homes the government sells cannot be read by a program, so the library gives you their search pages to open.")
                + (HomeSources.rentCastKeySet() ? "" : " " + t("A RentCast key ({0}, on the Settings page) adds the listings of a whole city in one call; the first fifty calls a month are free.", HomeSources.RENTCAST_KEY))
                + " " + t("In Japan (a place written as 東京都杉並区, or Suginami, Tokyo): SUUMO's lists, read whole with your conditions as its own filters, each room or home with its own page; AtHome and HOME'S as links to open yourself; and with a key for the ministry's {0} ({1}, free by application), the prices paid nearby, the nearest 地価公示 point and the hazard areas on each card", HomeJapanRecords.NAME, HomeJapanRecords.KEY) + (HomeJapanRecords.keySet() ? " (" + t("a key is set") + ")" : "") + ".</p>";
    }

    /** One search's page. */
    public static String body(LibraryStore store, Home.Search s, boolean writer) throws IOException {
        StringBuilder b = new StringBuilder();
        if (Home.searches(store).size() > 1) b.append("<p class=\"k\"><a href=\"/homes\">").append(t("All the searches")).append("</a></p>");
        View v = HomeFind.view(store, s);
        Map<String, String> wants = Home.wantsOf(store, s);
        String running = running(store, s);
        b.append("<p>").append(Pages.esc(Home.standing(store, s))).append("</p>");
        if (!running.isEmpty()) b.append("<p class=\"notice\">").append(Pages.esc(running)).append(" ").append(t("{0} shows how far it has come.", "<a href=\"/jobs\">" + t("The runs page") + "</a>")).append("</p>");
        // the stand: the search button, and what it will read
        if (writer && running.isEmpty()) {
            boolean placed = wants.containsKey("place");
            b.append("<form method=\"post\" action=\"/homes\" class=\"inline\">").append(hidden("op", "search")).append(hidden("search", s.slug()))
             .append("<button").append(placed ? "" : " disabled").append(">").append(v.passing().isEmpty() && v.aside().isEmpty() ? t("Search now") : t("Search again")).append("</button></form> ");
            b.append("<span class=\"k\">").append(placed ? (HomeSources.rentCastKeySet() ? t("The search reads RentCast, the web search's leads and the agents' sites for {0}, then keeps what fits and files cards for the three that fit best. A few minutes.", Pages.esc(wants.get("place"))) : t("The search reads the web search's leads and the agents' sites for {0}, then keeps what fits and files cards for the three that fit best. A few minutes.", Pages.esc(wants.get("place"))))
                    : t("Decide where first, below, as a town and a state (\"Somerville, MA\"); the search needs a place.")).append("</span>");
            if (HomeSources.rentCastKeySet() && placed) b.append(" <span class=\"k\">").append(t("RentCast is asked for what you decided, the budget, the rooms, the kind and the radius, so one call brings the homes that fit rather than the first five hundred of the town.")).append("</span>");
        }
        b.append(decided(store, s, wants, writer));
        b.append(tell(store, s, writer));
        b.append(listings(v, s, writer));
        b.append(aside(v, s, writer));
        if (writer) b.append(save(s));
        b.append(portals(v));
        b.append(cards(store, s, v));
        b.append(lately(store, s));
        b.append(sourcesNote());
        return b.toString();
    }

    static String running(LibraryStore store, Home.Search s) throws IOException {
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        for (ObjectNode j : jobs.active()) {
            if (!j.path("kind").asText().equals(HomeFind.JOB_KIND) || !j.path("args").path("search").asText().equals(s.slug())) continue;
            return j.path("state").asText().equals("running") ? t("A search is running: {0}.", RunProgress.stage(j.path("progress"))) : t("A search is waiting to start.");
        }
        return "";
    }

    static String section(String id, String title, String count, boolean open, String inner) {
        return "<details class=\"sec\" id=\"" + id + "\"" + (open ? " open" : "") + "><summary><h2>" + Pages.esc(title) + "</h2>" + (count.isEmpty() ? "" : " <span class=\"k\">" + Pages.esc(count) + "</span>") + "</summary>" + inner + "</details>";
    }

    /** The topics: where each stands, what the person wants, and a form per topic for writers; the keep-outs after. */
    static String decided(LibraryStore store, Home.Search s, Map<String, String> wants, boolean writer) throws IOException {
        StringBuilder b = new StringBuilder();
        ObjectNode w = Home.wantsKept(store, s);
        b.append("<p class=\"k\">").append(t("Decided means you said it. A guess is what the library read from your words, for you to confirm or correct. Open means nothing is known yet. Only what is decided keeps a listing or drops it; a guess puts them in order.")).append("</p>");
        b.append("<table><tr><th>").append(t("Topic")).append("</th><th>").append(t("Where it stands")).append("</th><th>").append(t("What you want")).append("</th></tr>");
        for (JsonNode e : w.path("dimensions")) {
            String dim = e.path("dimension").asText(), st = e.path("state").asText("open");
            b.append("<tr><td>").append(Pages.esc(Home.label(dim, Chrome.now().code))).append("</td><td>").append(Pages.esc(t(Career.STATE_WORDS.getOrDefault(st, st)))).append("</td><td>").append(Pages.esc(e.path("want").asText("")))
             .append(e.path("from").asText("").isEmpty() ? "" : " <span class=\"k\">(" + Pages.esc(e.path("from").asText()) + ")</span>").append("</td></tr>");
            if (writer) {
                b.append("<tr><td colspan=\"3\"><form method=\"post\" action=\"/homes\" class=\"inline\">").append(hidden("op", "want")).append(hidden("search", s.slug())).append(hidden("dimension", dim)).append("<select name=\"state\" aria-label=\"").append(Pages.esc(t("where it stands"))).append("\">");
                for (String x : Home.STATES) b.append("<option value=\"").append(x).append("\"").append(x.equals(st) ? " selected" : "").append(">").append(t(Career.STATE_WORDS.get(x))).append("</option>");
                b.append("</select> <input name=\"want\" value=\"").append(Pages.esc(e.path("want").asText(""))).append("\" placeholder=\"").append(Pages.esc(placeholder(dim))).append("\" size=\"50\" aria-label=\"").append(Pages.esc(t("what you want"))).append("\"> <button>").append(t("Save")).append("</button></form></td></tr>");
            }
        }
        b.append("</table>");
        List<String> out = Home.keepOut(store, s);
        b.append("<p><b>").append(t("Kept out:")).append("</b> ").append(out.isEmpty() ? t("nothing yet.") : "");
        for (String k : out) {
            b.append(Pages.esc(k));
            if (writer) b.append(" <form method=\"post\" action=\"/homes\" class=\"inline\">").append(hidden("op", "keep-out-drop")).append(hidden("search", s.slug())).append(hidden("words", k)).append("<button class=\"lnk\">").append(t("drop")).append("</button></form>");
            b.append("; ");
        }
        if (writer) b.append("<form method=\"post\" action=\"/homes\" class=\"inline\">").append(hidden("op", "keep-out")).append(hidden("search", s.slug())).append("<input name=\"words\" size=\"30\" placeholder=\"").append(Pages.esc(t("a street, a kind of home, a condition"))).append("\" aria-label=\"").append(Pages.esc(t("keep out"))).append("\"> <button>").append(t("Keep out")).append("</button></form>");
        b.append("</p>");
        return section("decided", t("What you decided"), wants.isEmpty() ? t("nothing yet") : n(wants.size(), "{0} topic set", "{0} topics set"), wants.isEmpty(), b.toString());
    }

    static String placeholder(String dim) {
        return t(placeholderEn(dim));
    }

    static String placeholderEn(String dim) {
        return switch (dim) {
            case "place" -> "a town and its state, as listings write it: Somerville, MA; in Japan, 東京都杉並区 or Suginami, Tokyo";
            case "distance" -> "how far from there still counts: 10 km unless you say, or 5 miles";
            case "tenure" -> "buy, or rent (購入, 賃貸)";
            case "budget" -> "what you would pay: $750,000, $400,000 to $550,000, $2,800 a month, under $850 a square foot; in Japan 家賃15万円以下, 5000万円まで";
            case "bedrooms" -> "how many: 3, 3 to 4, or 2 or more; in Japan the 間取り: 2LDK以上";
            case "bathrooms" -> "how many: 3, 3 to 4, or 2 or more";
            case "size" -> "in square feet: 1,400, or 1,200 to 1,800; in Japan 50㎡以上, or 20坪";
            case "lot" -> "the lot: 5,000 sq ft, or a quarter acre";
            case "built" -> "when it was built: after 2000, or 1920 to 1960; in Japan 築10年以内";
            case "walk" -> "the most minutes on foot to the station: 駅徒歩10分以内";
            case "floor" -> "the floor: 2階以上, or 2nd floor or higher";
            case "kind" -> "house, condo, apartment, townhouse, multi-family, land; in Japan マンション, アパート, 一戸建て, 土地";
            case "commute" -> "the address or the place you travel to";
            case "when" -> "the month or the season";
            default -> "the things a home must have: a yard, parking, a washer";
        };
    }

    static String tell(LibraryStore store, Home.Search s, boolean writer) {
        if (!writer) return "";
        String inner = "<p class=\"k\">" + t("Say it as you would to a friend: where, what you can pay, how many bedrooms, what you will not have. The library reads your words for what they settle and sets the topics above; a decision you made is never undone by a hedge.") + "</p>"
                + "<form method=\"post\" action=\"/homes\">" + hidden("op", "tell") + hidden("search", s.slug()) + "<textarea name=\"words\" rows=\"4\" cols=\"80\" placeholder=\"" + Pages.esc(t("We want to buy a 3-bedroom house in Somerville, MA or the next towns over, up to $900,000, with a yard; nothing on a main road.")) + "\" aria-label=\"" + Pages.esc(t("your words")) + "\"></textarea><br><button>" + t("Tell the library") + "</button></form>";
        return section("tell", t("Tell the library more"), "", false, inner);
    }

    /** The listings that fit, in tiers: the ones that match the place and the figures open, the nearer misses closed, the rest closed. */
    static String listings(View v, Home.Search s, boolean writer) {
        StringBuilder b = new StringBuilder();
        if (v.passing().isEmpty() && v.aside().isEmpty()) {
            b.append("<p>").append(t("No listings yet.")).append(" ").append(v.cut().places().isEmpty() ? t("Decide where, and press Search.") : t("Press Search now.")).append("</p>");
            return section("listings", t("Listings"), t("none yet"), true, b.toString());
        }
        b.append("<p>").append(t("What counts as a fit: {0}.", Pages.esc(HomeCut.summary(v.cut())))).append(" ").append(v.searched().isEmpty() ? t("Not searched yet.") : t("Searched last on {0}.", Pages.esc(Chrome.now().day(v.searched())))).append("</p>");
        if (v.more() != null && v.more().left() > 0) {
            b.append("<p class=\"notice\">").append(Pages.esc(v.more().sentence())).append(" ").append(t("Narrow the search, the radius, the budget or the kind, to see fewer and nearer"));
            if (writer) b.append(", ").append(t("or")).append(" <form method=\"post\" action=\"/homes\" class=\"inline\">").append(hidden("op", "more")).append(hidden("search", s.slug())).append("<button>").append(t("Fetch the next {0} from RentCast", Math.min(500, v.more().left()))).append("</button></form> (").append(t("one call")).append(")");
            b.append(".</p>");
        }
        if (v.passing().isEmpty()) b.append("<p class=\"notice\">").append(t("None of the kept listings fits what you decided; the ones set aside are below, each with why. Widen what you decided, or save a listing you found yourself.")).append("</p>");
        Map<Integer, List<Row>> tiers = new LinkedHashMap<>();
        for (Row r : v.passing()) tiers.computeIfAbsent(r.tier(), k -> new ArrayList<>()).add(r);
        String[] names = new String[HomeCut.TIERS.length];
        for (int i = 0; i < names.length; i++) names[i] = t(HomeCut.TIERS[i]);
        for (int t = 0; t < names.length; t++) {
            List<Row> rows = tiers.getOrDefault(t, List.of());
            if (rows.isEmpty()) continue;
            boolean open = t == 0 || (t == 1 && !tiers.containsKey(0));
            b.append(open ? "<details open>" : "<details>").append("<summary>").append(names[t]).append(" <span class=\"k\">").append(rows.size()).append("</span></summary><ul>");
            int shown = 0;
            for (Row r : rows) {
                if (shown++ >= 60) { b.append("<li class=\"k\">").append(t("and {0} more; narrow what you decided to see fewer and nearer.", rows.size() - 60)).append("</li>"); break; }
                List<String> notes = HomeFind.notes(r, v.cut());
                b.append("<li id=\"l-").append(Pages.esc(r.listing().id())).append("\">").append(line(r)).append(notes.isEmpty() ? "" : " <span class=\"k\">(" + Pages.esc(String.join("; ", notes)) + ")</span>").append(writer ? react(r, s) : "").append("</li>");
            }
            b.append("</ul></details>");
        }
        return section("listings", t("Listings that fit"), n(v.passing().size(), "{0} listing", "{0} listings"), true, b.toString());
    }

    static String line(Row r) {
        Home.Listing l = r.listing();
        StringBuilder b = new StringBuilder();
        b.append("<b>").append(Pages.esc(l.addressWords())).append("</b>").append(l.japan() || l.place().isEmpty() ? "" : ", " + Pages.esc(l.place()));
        for (String f : l.figures()) b.append(" · ").append(Pages.esc(f));
        Chrome c = Chrome.now();
        b.append(" <span class=\"k\">— ").append(Pages.esc(l.source()));
        if (!l.listed().isEmpty()) b.append(", ").append(t("listed {0}", Pages.esc(c.day(l.listed()))));
        b.append(", ").append(l.firstSeen().equals(l.lastSeen()) ? t("seen {0}", Pages.esc(c.day(l.lastSeen()))) : t("seen {0} to {1}", Pages.esc(c.day(l.firstSeen())), Pages.esc(c.day(l.lastSeen())))).append("</span>");
        if (!l.link().isEmpty()) b.append(" <a href=\"").append(Pages.esc(l.link())).append("\">").append(l.ownPage() && !l.url().contains("/search?q=") ? t("open its page") : t("search the web for it")).append("</a>");   // the home itself: its own page, or a search for its full address
        if (!l.seenOn().isEmpty()) b.append(" · <a href=\"").append(Pages.esc(l.seenOn())).append("\">").append(c.english() ? "seen on " + Pages.esc(HomeFind.possessive(l.seenOnName())) + " page" : t("seen on the page of {0}", Pages.esc(l.seenOnName()))).append("</a>");
        if (!r.reactionWord().isEmpty()) b.append(" <span class=\"k\">").append(r.reactionWord().equals("like") ? t("liked") : t("passed")).append(r.reaction().path("why").asText("").isBlank() ? "" : ": " + Pages.esc(r.reaction().path("why").asText())).append("</span>");
        if (r.card() != null) b.append(" <span class=\"k\">· ").append(Chrome.now().english() ? t("card {0}", Pages.esc(HomeCard.state(r.card()))) : Pages.esc(HomeCard.state(r.card(), Chrome.now()))).append("</span>");
        return b.toString();
    }

    static String react(Row r, Home.Search s) {
        StringBuilder b = new StringBuilder(" <form method=\"post\" action=\"/homes\" class=\"inline\">").append(hidden("op", "react")).append(hidden("search", s.slug())).append(hidden("id", r.listing().id()));
        if (r.reactionWord().isEmpty()) b.append("<input name=\"why\" placeholder=\"").append(Pages.esc(t("why, in a few words (optional)"))).append("\" size=\"28\" aria-label=\"").append(Pages.esc(t("why"))).append("\"> <button name=\"reaction\" value=\"like\">").append(t("Like")).append("</button> <button name=\"reaction\" value=\"pass\">").append(t("Pass")).append("</button>");
        else b.append(hidden("reaction", "")).append("<button class=\"lnk\">").append(t("undo")).append("</button>");
        b.append("</form>");
        if (r.card() == null && !r.listing().address().isEmpty()) b.append(" <form method=\"post\" action=\"/homes\" class=\"inline\">").append(hidden("op", "card")).append(hidden("search", s.slug())).append(hidden("id", r.listing().id())).append("<button class=\"lnk\">").append(t("card")).append("</button></form>");
        return b.toString();
    }

    static String aside(View v, Home.Search s, boolean writer) {
        if (v.aside().isEmpty()) return "";
        StringBuilder b = new StringBuilder("<ul>");
        int shown = 0;
        for (Row r : v.aside()) {
            if (shown++ >= 80) { b.append("<li class=\"k\">").append(t("and {0} more.", v.aside().size() - 80)).append("</li>"); break; }
            b.append("<li>").append(line(r)).append(" <span class=\"k\">— ").append(t("set aside: {0}", Pages.esc(r.verdict().why()))).append("</span></li>");
        }
        b.append("</ul>");
        return section("aside", t("Set aside"), t("{0} by what you decided", v.aside().size()), false, b.toString());
    }

    static String save(Home.Search s) {
        String inner = "<p class=\"k\">" + t("A listing you saw in your browser: paste its line as the portal shows it, the address with the town and the state, the price, the beds and baths, and the page's address. The library keeps it with the rest and judges it by what you decided.") + "</p>"
                + "<form method=\"post\" action=\"/homes\">" + hidden("op", "add") + hidden("search", s.slug())
                + "<input name=\"words\" size=\"70\" placeholder=\"12 Elm St, Somerville, MA 02143 · $875,000 · 3 bd · 2 ba · 1,600 sqft · house\" aria-label=\"" + Pages.esc(t("the listing")) + "\"> <input name=\"url\" size=\"40\" placeholder=\"" + Pages.esc(t("the page's address (optional)")) + "\" aria-label=\"" + Pages.esc(t("the page's address")) + "\"> "
                + "<select name=\"tenure\" aria-label=\"" + Pages.esc(t("for sale or for rent")) + "\"><option value=\"\">" + t("as the price says") + "</option><option value=\"sale\">" + t("for sale") + "</option><option value=\"rent\">" + t("for rent") + "</option></select> <button>" + t("Save it") + "</button></form>";
        return section("save", t("Save a listing you found yourself"), "", false, inner);
    }

    static String portals(View v) {
        if (v.portals().isEmpty()) return "";
        StringBuilder b = new StringBuilder("<p>").append(t("These pages cannot be read by a program, so the library cannot list what is on them; open them yourself.")).append(" ").append(v.cut().minBeds() > 0 ? t("Each link already carries your place and bedrooms.") : t("Each link already carries your place.")).append("</p><ul>");
        for (String[] p : v.portals()) b.append("<li><a href=\"").append(Pages.esc(p[1])).append("\">").append(Pages.esc(p[0])).append("</a></li>");
        b.append("</ul>");
        return section("portals", t("The portals, for you to open"), t("{0} links", v.portals().size()), false, b.toString());
    }

    static String cards(LibraryStore store, Home.Search s, View v) throws IOException {
        List<ObjectNode> cs = HomeCard.cards(store, s);
        if (cs.isEmpty()) return "";
        StringBuilder b = new StringBuilder("<p class=\"k\">").append(t("A card: the records of the home read first, the flood zone, the schools and the stops around it, and where a city's own portal answers, what was paid on the street and the permits; then a research run on the library's model for the rest. The run's report lands on the Dossiers page.")).append("</p><ul>");
        for (ObjectNode c : cs) {
            b.append("<li><b>").append(Pages.esc(c.path("line").asText(""))).append("</b> <span class=\"k\">").append(Pages.esc(HomeCard.state(c, Chrome.now()))).append("</span>");
            String recordsLine = c.path("records_line").asText("");
            if (!recordsLine.isEmpty()) b.append("<br><span class=\"k\">").append(linked(recordsLine)).append("</span>");
            String records = c.path("records").asText("");
            if (!records.isEmpty()) { b.append("<details><summary>").append(t("The records read on {0}", Pages.esc(Chrome.now().day(c.path("filed").asText(""))))).append("</summary><ul>"); for (String ln : records.split("\n")) if (!ln.isBlank()) b.append("<li>").append(Pages.esc(ln)).append("</li>"); b.append("</ul></details>"); }
            b.append("</li>");
        }
        b.append("</ul>");
        return section("cards", t("Cards"), n(cs.size(), "{0} card", "{0} cards"), false, b.toString());
    }

    static String lately(LibraryStore store, Home.Search s) throws IOException {
        List<String> lines = Home.lately(store, s, 12);
        if (lines.isEmpty()) return "";
        StringBuilder b = new StringBuilder("<ul>");
        for (int i = lines.size() - 1; i >= 0; i--) b.append("<li class=\"k\">").append(Pages.esc(lines.get(i))).append("</li>");
        b.append("</ul>");
        return section("lately", t("Lately"), "", false, b.toString());
    }

    /** A line escaped for the page, with an address in it made a link. */
    static String linked(String line) {
        Matcher m = Pattern.compile("https?://\\S+?(?=[.,;]?(\\s|$))").matcher(line);
        StringBuilder b = new StringBuilder(); int at = 0;
        while (m.find()) { b.append(Pages.esc(line.substring(at, m.start()))).append("<a href=\"").append(Pages.esc(m.group())).append("\">").append(Pages.esc(m.group())).append("</a>"); at = m.end(); }
        return b.append(Pages.esc(line.substring(at))).toString();
    }

    static String hidden(String name, String value) { return "<input type=\"hidden\" name=\"" + name + "\" value=\"" + Pages.esc(value) + "\">"; }
}
