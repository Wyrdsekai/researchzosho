package org.researchzosho.librarian.profiles;

import org.researchzosho.librarian.Chrome;
import org.researchzosho.librarian.Home;
import org.researchzosho.librarian.HomeSensors;
import org.researchzosho.librarian.VideoRecallPage;
import org.researchzosho.librarian.HomeCard;
import org.researchzosho.librarian.HomeCut;
import org.researchzosho.librarian.HomeFind;
import org.researchzosho.librarian.HomePage;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.Patrons;
import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.Vocabulary;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import java.util.stream.Collectors;
/**
 * The home field: a person looking for a home to buy or to rent, in the United States first. Their record is what they decided, topic
 * by topic, or said in their own words; the search reads the listings from every source the library can read and keeps the ones that
 * fit; the cards read a home's records and then the library's model. The field names no relations of its own and acts only on its own
 * work; its answer rules join a research run that asks for homes, by the home goal's words, so such a run lists homes with their links
 * rather than writing about a market. An ordinary library is the same with it on or off.
 */
public final class HomeProfile implements Profile {

    @Override public String name() { return "home"; }

    @Override public String goalName() { return "home"; }

    @Override public String description() { return "a person looking for a home to buy or rent: what they decided, the listings from the sources the library can read, and a card per home from the records"; }

    @Override public List<Vocabulary.Term> predicates() { return List.of(); }

    @Override public boolean joinsOnlyWhenAsked() { return true; }
    @Override public boolean menuAlways() { return true; }

    @Override public Path workFolder(LibraryStore store) { return Home.dir(store); }

    @Override public List<String> pagePaths() { return List.of("/homes"); }

    @Override public List<Link> menu() { return List.of(new Link("/homes", "Home search", false, "⌂")); }

    @Override public List<String> chatCommandWords() { return List.of("/home"); }

    @Override public Page page(LibraryStore store, Patrons.Patron patron, String path, String method, Map<String, String> query, Map<String, String> form) throws IOException {
        if (!path.equals("/homes")) return null;
        Patrons.check(store, patron, Patrons.Level.read);
        boolean writer;
        try { Patrons.check(store, patron, Patrons.Level.write); writer = true; } catch (RuntimeException e) { writer = false; }
        if ("POST".equals(method)) {
            Patrons.check(store, patron, Patrons.Level.write);
            String op = form.getOrDefault("op", ""), anchor = "";
            Home.Search s = Home.search(store, form.getOrDefault("search", ""));
            try {
                switch (op) {
                    case "start" -> s = Home.start(store, form.getOrDefault("name", ""));
                    case "want" -> { if (s != null) Home.setWant(store, s, form.getOrDefault("dimension", ""), form.getOrDefault("state", ""), form.getOrDefault("want", ""), "you", "set on the page"); anchor = "#decided"; }
                    case "tell" -> { if (s != null) Home.tell(store, s, form.getOrDefault("words", "")); anchor = "#decided"; }
                    case "keep-out" -> { if (s != null) Home.addKeepOut(store, s, form.getOrDefault("words", "")); anchor = "#decided"; }
                    case "keep-out-drop" -> { if (s != null) Home.removeKeepOut(store, s, form.getOrDefault("words", "")); anchor = "#decided"; }
                    case "search" -> { if (s != null) HomeFind.file(store, s, patron.did()); }
                    case "more" -> { if (s != null) HomeFind.file(store, s, patron.did(), true); anchor = "#listings"; }
                    case "react" -> { if (s != null) Home.react(store, s, form.getOrDefault("id", ""), form.getOrDefault("reaction", ""), form.getOrDefault("why", "")); anchor = "#l-" + form.getOrDefault("id", ""); }
                    case "card" -> { if (s != null) HomeFind.card(store, s, form.getOrDefault("id", ""), patron.did()); anchor = "#cards"; }
                    case "add" -> { if (s != null) HomeFind.save(store, s, form.getOrDefault("words", ""), form.getOrDefault("url", ""), form.getOrDefault("tenure", "")); anchor = "#listings"; }
                    case "records" -> {   // the records of one address, on their own page
                        HomeSensors.Records rec = HomeSensors.records(store, form.getOrDefault("address", ""));
                        StringBuilder b = new StringBuilder("<p>").append(VideoRecallPage.esc(rec.line())).append("</p>");
                        b.append("<h2>").append(Chrome.now().t("Sales or values on the street")).append("</h2>").append(rec.sales().isEmpty() ? "<p>" + Chrome.now().t("None found.") + "</p>" : "<ul>" + rec.sales().stream().map(x -> "<li>" + VideoRecallPage.esc(x) + "</li>").collect(Collectors.joining()) + "</ul>");
                        b.append("<h2>").append(Chrome.now().t("Permits on the street")).append("</h2>").append(rec.permits().isEmpty() ? "<p>" + Chrome.now().t("None found.") + "</p>" : "<ul>" + rec.permits().stream().map(x -> "<li>" + VideoRecallPage.esc(x) + "</li>").collect(Collectors.joining()) + "</ul>");
                        if (!rec.census().isEmpty()) b.append("<p>").append(VideoRecallPage.esc(rec.census())).append("</p>");
                        if (!rec.looked().isEmpty()) b.append("<details><summary class=\"k\">").append(Chrome.now().t("What was looked at")).append("</summary><ul>").append(rec.looked().stream().map(x -> "<li>" + VideoRecallPage.esc(x) + "</li>").collect(Collectors.joining())).append("</ul></details>");
                        return new Page(Chrome.now().t("Records of {0}", form.getOrDefault("address", "").strip()), b.append("<p><a href=\"/homes\">").append(Chrome.now().t("Back to the Home page")).append("</a></p>").toString(), false, null);
                    }
                    default -> { }
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                return new Page(Chrome.now().t("Home search"), "<p>" + e.getMessage().replace("&", "&amp;").replace("<", "&lt;") + "</p><p><a href=\"/homes" + (s == null ? "" : "?search=" + s.slug()) + "\">" + Chrome.now().t("Back") + "</a></p>", false, null);
            }
            return new Page(Chrome.now().t("Home search"), "", false, "/homes" + (s == null ? "" : "?search=" + s.slug() + anchor));
        }
        String which = query.getOrDefault("search", "").strip();
        Home.Search s = which.isEmpty() ? null : Home.search(store, which);
        if (s == null) return new Page(Chrome.now().t("Home search"), HomePage.front(store, writer), false, null);
        return new Page(s.name() + " · " + Chrome.now().t("Home search"), HomePage.body(store, s, writer), false, null);
    }

    /** At the terminal: the reply printed. */
    @Override public boolean chatCommand(LibraryStore store, String line) {
        String l = line.strip();
        if (!(l.equals("/home") || l.startsWith("/home "))) return false;
        try {
            String r = chatReply(store, Patrons.Patron.PERSON, "terminal", l);
            if (r != null) for (String x : r.split("\n")) System.out.println("  " + x);
        } catch (IOException e) { System.out.println("  That did not work: " + e.getMessage()); }
        return true;
    }

    /** The home commands in any chat. */
    @Override public String chatReply(LibraryStore store, Patrons.Patron patron, String chat, String line) throws IOException {
        if (line == null) return null;
        String l = line.strip();
        if (!(l.equals("/home") || l.startsWith("/home "))) return null;
        String rest = l.length() > 5 ? l.substring(5).strip() : "";
        String[] head = rest.split("\\s+", 2);
        String verb = head[0], tail = head.length > 1 ? head[1].strip() : "";
        boolean writes = !List.of("read", "list", "listings", "cut", "").contains(verb);
        if (writes && !patron.person() && !Patrons.mayWrite(store, patron)) return "That changes the record, so it is for those who may write to this library. /home read shows it.";
        try {
            switch (verb) {
                case "", "read", "list" -> {
                    if (verb.equals("list") || (tail.isEmpty() && Home.searches(store).size() != 1)) return listText(store);
                    Home.Search s = who(store, tail); if (s == null) return nobody(store, tail);
                    return Home.standing(store, s) + "\n" + Home.wantsText(store, s) + (Home.keepOut(store, s).isEmpty() ? "" : "Kept out: " + String.join("; ", Home.keepOut(store, s)) + "\n") + "Open the Home page, or type /home listings, for the listings.";
                }
                case "start" -> {
                    if (tail.isEmpty()) return "Type /home start and a name for the search: yours, or what it is for.";
                    Home.Search s = Home.start(store, tail);
                    return "The search \"" + s.name() + "\" is started. Say what you want with /home tell and your own words, or topic by topic with /home want; then /home search.";
                }
                case "tell" -> {
                    if (tail.isEmpty()) return "Type /home tell and what you want, in your own words: where, buy or rent, the budget, the bedrooms, what you will not have.";
                    Home.Search s = who(store, ""); if (s == null) return nobody(store, "");
                    String said = Home.tell(store, s, tail);
                    return said.isEmpty() ? "Your words are kept. No topic was settled from them; set one with /home want, or say it more plainly." : said;
                }
                case "want" -> {
                    String[] p = tail.split("\\s+");
                    int at = -1; for (int i = 1; i < p.length; i++) if (!Home.state(p[i]).isEmpty()) { at = i; break; }
                    if (at < 1) return "To set a topic yourself, type /home want, then the topic, then decided, guess or open, then what you want. For example: /home want place decided Somerville, MA. The topics are: " + String.join(", ", Home.DIMENSIONS) + ".";
                    Home.Search s = who(store, ""); if (s == null) return nobody(store, "");
                    String dim = String.join(" ", Arrays.copyOfRange(p, 0, at)), want = String.join(" ", Arrays.copyOfRange(p, at + 1, p.length));
                    Home.setWant(store, s, dim, p[at], want, "you", "what you typed");
                    return "Saved. " + Home.label(dim) + ": " + (Home.state(p[at]).equals("open") ? "open" : Home.state(p[at]).equals("decided") ? "decided, " + want : "a guess, " + want) + ".";
                }
                case "keep-out" -> {
                    if (tail.isEmpty()) return "To keep something out of the search, a street, a kind of home, a condition, type /home keep-out and the words. /home keep-out drop and the words takes it off.";
                    Home.Search s = who(store, ""); if (s == null) return nobody(store, "");
                    if (tail.startsWith("drop ")) return Home.removeKeepOut(store, s, tail.substring(5)) ? "The search no longer keeps out " + tail.substring(5).strip() + "." : "That is not kept out.";
                    return Home.addKeepOut(store, s, tail) ? "The search will keep out " + tail + "." : tail + " is kept out already.";
                }
                case "search" -> { boolean more = tail.contains("--more"); String t = tail.replace("--more", "").strip(); Home.Search s = who(store, t); if (s == null) return nobody(store, t); return HomeFind.file(store, s, patron.did(), more); }
                case "listings" -> { Home.Search s = who(store, tail); if (s == null) return nobody(store, tail); return HomeFind.text(store, s, 12); }
                case "cut" -> { Home.Search s = who(store, tail); if (s == null) return nobody(store, tail); return "What counts as a fit: " + HomeCut.summary(HomeCut.current(store, s)) + ". It is made from what you decided; change a topic to change it."; }
                case "like", "pass" -> {
                    if (tail.isEmpty()) return "Type /home " + verb + ", then the listing's short name in brackets from /home listings, then why in a few words.";
                    Home.Search s = who(store, ""); if (s == null) return nobody(store, "");
                    String[] w = tail.split("\\s+", 2);
                    return Home.react(store, s, w[0].replace("[", "").replace("]", ""), verb, w.length > 1 ? w[1] : "");
                }
                case "card" -> {
                    if (tail.isEmpty()) return "Type /home card and a listing's short name in brackets from /home listings: the records read, then a research run of about " + HomeCard.CARD_MINUTES + " minutes.";
                    Home.Search s = who(store, ""); if (s == null) return nobody(store, "");
                    return HomeFind.card(store, s, tail.replace("[", "").replace("]", ""), patron.did());
                }
                case "add" -> {
                    if (tail.isEmpty()) return "Type /home add and the listing as the portal shows it: the address with the town and the state, the price, the beds and the baths, and the page's address if you have it.";
                    Home.Search s = who(store, ""); if (s == null) return nobody(store, "");
                    String url = ""; for (String w : tail.split("\\s+")) if (w.startsWith("http://") || w.startsWith("https://")) url = w;
                    return HomeFind.save(store, s, tail.replace(url, "").strip(), url, "");
                }
                default -> { return "That is not one of the home commands. They are: /home start, to start a search; /home tell, to say what you want in your own words; /home want, to set one topic; /home keep-out, to keep a street, a kind of home or a condition out; /home search, to look for listings; /home listings, to read what fits; /home like and /home pass, to say what you think of one; /home card, to have the records of one read and the library research it; /home add, to save a listing you found yourself; /home cut, to see what counts as a fit; /home read, where things stand; /home list, the searches."; }
            }
        } catch (IllegalArgumentException e) { return e.getMessage(); }
    }

    private static String listText(LibraryStore store) throws IOException {
        List<Home.Search> all = Home.searches(store);
        if (all.isEmpty()) return "No home search is started yet. Type /home start and a name for one.";
        StringBuilder b = new StringBuilder();
        for (Home.Search s : all) b.append("- ").append(Home.standing(store, s)).append('\n');
        return b.toString();
    }

    private static Home.Search who(LibraryStore store, String name) throws IOException { return Home.search(store, name); }

    private static String nobody(LibraryStore store, String name) throws IOException {
        List<Home.Search> all = Home.searches(store);
        if (all.isEmpty()) return "No home search is started yet. Type /home start and a name for one.";
        if (!name.isBlank()) return "There is no search called " + name + ". The searches are: " + String.join(", ", all.stream().map(Home.Search::name).toList()) + ".";
        return "This library holds more than one home search: " + String.join(", ", all.stream().map(Home.Search::name).toList()) + ". Add the name, for example /home read " + all.get(0).name() + ".";
    }

    /** The answer rules for a run that asks for homes (DESIGN_BRAIN §5): a list of homes that fit, each read from its own listing page, each with its link. */
    @Override public String planRules() {
        return "This is a question about homes to buy or to rent. First restate what the person asked for as the cut the answer will judge by: the place, "
                + "buy or rent, the budget as a range, the size, the rooms, the kind of home, and the price by the unit they asked by, a total, per square "
                + "foot or per month. Then one sub-question finds the listings now for sale or for rent that fit, by their own listing pages, an estate "
                + "agent's page, a portal's listing page, a public listing feed, with two or three different wordings of the place and the kind; one "
                + "sub-question per home that fits, eight at most, reads its listing page for the address, the price, the size, the rooms, the year, the "
                + "days on the market and the page's address; one sub-question may read the area's figures from a market page only to say where the fits "
                + "sit. A market page is never a home.";
    }

    @Override public String criticRules() {
        return "For a question about homes the evidence is enough when every home named came from its own listing page read in this run, with its "
                + "address, its price, its size, the date it was seen and the page's address kept with it; a home known only from a snippet of a list page "
                + "or from a market page is dropped, not kept with a guess. A gap worth another search names a part of the cut no home was checked "
                + "against, a place the person named that was not searched, or a source the person named that was not read.";
    }

    @Override public String writerRules() {
        return "Open with \"as of\" and today's date, the place, buy or rent, and the cut as restated. Then the homes that fit, best first, each on its "
                + "own line: the address linked to its listing page, the price, the size, the rooms, the price by the unit the person asked by, the kind, "
                + "the year, where it was seen and when. Then the near misses the same way, each with the one thing it misses. Then what could not be "
                + "verified, and the sources the run could not read, a portal that forbids reading named with a link that searches it by hand. Every home "
                + "named carries its link on its line, not only in the sources.";
    }

    @Override public String chatCommandHelp() { return "/home (looking for a home: start, tell, want, keep-out, search, listings, like, pass, card, add, cut, read, list)"; }
}
