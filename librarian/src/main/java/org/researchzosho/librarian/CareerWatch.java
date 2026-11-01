package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.CareerCut.Cut;
import org.researchzosho.librarian.CareerFind.Row;
import org.researchzosho.librarian.CareerFind.View;
import org.researchzosho.librarian.CareerSources.Feed;
import org.researchzosho.librarian.CareerSources.Found;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The daily watch (docs/DESIGN_CAREER.md, step 3): for a person who asks for it, the night's housekeeping reads the companies on their
 * list again, straight from the feeds the search found, and the open listings; cuts what is new by what they decided; and has the model
 * read the new openings nearest to what they want, up to a setting. What arrived waits on the openings list under "New since you last
 * looked", and the inbox carries one line for it, never one an opening.
 */
public final class CareerWatch {
    private CareerWatch() { }

    private static final ObjectMapper M = new ObjectMapper();

    static Path file(LibraryStore store, Career.Seeker s) { return CareerFind.dir(store, s).resolve("watch.json"); }

    static ObjectNode state(LibraryStore store, Career.Seeker s) throws IOException { return CareerFind.readObject(file(store, s)); }

    public static boolean on(LibraryStore store, Career.Seeker s) throws IOException { return state(store, s).path("on").asBoolean(false); }

    /** The watch turned on or off; a sentence for the person. */
    public static String set(LibraryStore store, Career.Seeker s, boolean on) throws IOException {
        ObjectNode w = state(store, s);
        boolean was = w.path("on").asBoolean(false);
        w.put("on", on);
        if (on && !was) w.put("since", LocalDate.now().toString());
        CareerFind.write(file(store, s), w);
        if (on != was) Career.log(store, s, on ? "You asked the library to watch your companies every night." : "You stopped the nightly watch.");
        return on ? "The library will watch every night: it reads the openings of the companies on your list that are not dropped, and the month's Who is hiring, "
                + "keeps the new ones that pass what you decided, and reads up to " + CareerFind.watchReadingsMax() + " of them. They wait on the openings list under New since you last looked, "
                + "and the inbox says how many."
                : "The nightly watch is off. Your openings and your list stay as they are.";
    }

    /** The person opened the openings list: what arrived before now is no longer new. */
    public static void looked(LibraryStore store, Career.Seeker s) throws IOException {
        ObjectNode w = state(store, s);
        if (!w.path("on").asBoolean(false) && !w.has("looked_at")) return;
        w.put("looked_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        CareerFind.write(file(store, s), w);
    }

    /** The openings that pass and arrived since the person last looked; none before they first looked, since then everything would be new. */
    public static List<Row> fresh(LibraryStore store, Career.Seeker s, View v) throws IOException {
        String since = state(store, s).path("looked_at").asText("");
        List<Row> out = new ArrayList<>();
        if (since.isEmpty()) return out;
        for (Row r : v.passing()) {
            String at = r.kept() == null ? "" : r.kept().path("first_seen_at").asText("");
            if (!at.isEmpty() && at.compareTo(since) > 0 && r.reactionWord().isEmpty()) out.add(r);
        }
        return out;
    }

    /** The night's step: each person who watches, once a day. A sentence for the housekeeping's log. */
    public static String nightly(LibraryStore store, boolean modelAnswers, BooleanSupplier stop) throws IOException {
        List<String> said = new ArrayList<>();
        for (Career.Seeker s : Career.seekers(store)) {
            if (stop.getAsBoolean()) break;
            ObjectNode w = state(store, s);
            if (!w.path("on").asBoolean(false) || LocalDate.now().toString().equals(w.path("last_run").asText(""))) continue;
            said.add(s.name() + ": " + watch(store, s, modelAnswers, stop));
        }
        return said.isEmpty() ? "no watch due" : String.join("; ", said);
    }

    /** One person's watch: the feeds read again, the open listings, the cut, the readings of what is new. */
    static String watch(LibraryStore store, Career.Seeker s, boolean modelAnswers, BooleanSupplier stop) throws IOException {
        String today = LocalDate.now().toString();
        Cut cut = CareerCut.kept(store, s);
        if (cut == null || (!cut.by().equals("you") && !cut.basis().equals(CareerCut.basis(store, s)) && modelAnswers)) cut = CareerCut.current(store, s);
        try { if (modelAnswers && CareerCompanies.refresh(store, s, cut, stop) > 0) CareerCompanies.read(store, s, cut, 40, stop); }   // the companies of the place, weekly, and read when what the person wants changed
        catch (RuntimeException e) { Career.log(store, s, "The companies of your place could not be gathered tonight: " + e.getMessage()); }
        Set<String> wasListed = new HashSet<>();
        for (ObjectNode c : CareerFind.companies(store, s)) wasListed.add(CareerSearch.key(c.path("name").asText()));
        List<ObjectNode> list;
        try { list = modelAnswers ? CareerFind.companyList(store, s, cut) : CareerFind.companies(store, s); }   // the boards and the hiring sites looked at again for companies new tonight
        catch (RuntimeException e) { list = CareerFind.companies(store, s); }
        Map<String, ObjectNode> all = CareerFind.openings(store, s);
        Map<String, String> texts = CareerFind.texts(store, s);
        Set<String> before = new HashSet<>(all.keySet());
        int read = 0;
        for (ObjectNode c : list) {
            if (stop.getAsBoolean()) break;
            if (!CareerFind.lookedAt(c)) continue;
            String name = c.path("name").asText();
            Found f;
            if (c.has("feed") && c.has("read_at")) {   // the feed the search found, a careers page's job list among them, read straight from it
                Feed feed = new Feed(c.path("feed").path("kind").asText(), c.path("feed").path("token").asText(), c.path("feed").path("how").asText());
                f = CareerSources.read(feed, name, cut.titles());
                if (f == null) { c.put("note", "its hiring feed did not answer on " + today + "; the library tries again tomorrow"); continue; }
            } else if (!c.has("read_at") || weekOld(c.path("read_at").asText(""))) f = CareerFind.readCompany(c, cut);   // new tonight, or no feed: looked for again once a week
            else continue;
            read++;
            CareerFind.sizeOf(store, c, f, today);
            c.put("read_at", today).put("openings", f.openings().size()).put("note", f.note());
            CareerFind.stillListed(c, f);
            if (f.feed() != null) c.putObject("feed").put("kind", f.feed().kind()).put("token", f.feed().token()).put("how", f.feed().how()).put("system", f.feed().system());
            CareerFind.merge(all, texts, f.openings(), name, f.feed() != null && f.note().isEmpty(), today);
        }
        CareerFind.saveCompanies(store, s, list);
        if (!stop.getAsBoolean()) {   // the open listings, for the trades whose seats they carry, as the search's shape says
            CareerShape.Shape shape = CareerShape.kept(store, s);
            if (shape == null || shape.reads(CareerShape.READS_HN)) CareerFind.merge(all, texts, CareerSources.hackerNews(), "", false, today);
            if (CareerSources.usaJobsReady() && (shape == null || shape.reads(CareerShape.READS_USAJOBS))) CareerFind.merge(all, texts, CareerSources.usaJobs(cut.titles()), "", false, today);
        }
        CareerFind.saveAll(store, s, all, texts);
        Set<String> arrived = new HashSet<>(all.keySet());
        arrived.removeAll(before);
        View v = CareerFind.view(store, s);
        int passing = 0;
        for (Row r : v.passing()) if (arrived.contains(r.opening().id())) passing++;
        int readings = modelAnswers && !stop.getAsBoolean() ? CareerFind.readSome(store, s, v, CareerFind.watchReadingsMax(), x -> { }, stop, arrived) : 0;
        ObjectNode w = state(store, s);
        w.put("last_run", today).put("last_new", passing);
        CareerFind.write(file(store, s), w);
        int added = 0; for (ObjectNode c : list) if (!wasListed.contains(CareerSearch.key(c.path("name").asText()))) added++;
        String line = read == 0 && !list.isEmpty() ? "The watch read no company again tonight: every one on the list was read today already, and a company with no hiring feed is looked for again after a week; the lists where such employers are listed were read again."
                : "The watch read " + read + (read == 1 ? " company" : " companies") + (added > 0 ? ", " + added + " of them new to the list" : " again") + ": " + passing + (passing == 1 ? " new opening passes" : " new openings pass") + " what you want"
                + (readings > 0 ? ", and the library read " + readings + " of them" : "") + ".";
        Career.log(store, s, line);
        return line;
    }

    static boolean weekOld(String day) {
        try { return LocalDate.parse(day).isBefore(LocalDate.now().minusDays(6)); } catch (Exception e) { return true; }
    }

    /** The inbox's line for each person who watches and has new openings waiting; "" when none. */
    public static String inboxNote(LibraryStore store) throws IOException {
        StringBuilder b = new StringBuilder();
        for (Career.Seeker s : Career.seekers(store)) {
            if (!on(store, s)) continue;
            List<Row> fresh = fresh(store, s, CareerFind.view(store, s));
            if (fresh.isEmpty()) continue;
            Set<String> at = new HashSet<>();
            for (Row r : fresh) at.add(CareerSearch.key(r.opening().company()));
            b.append("<p><strong>").append(fresh.size()).append(fresh.size() == 1 ? " new opening" : " new openings").append(" for ").append(Pages.esc(s.name())).append("</strong> since you last looked, at ")
                    .append(at.size()).append(at.size() == 1 ? " company" : " companies").append(", from the nightly watch. <a href=\"/career/openings?seeker=").append(Pages.esc(s.slug())).append("#new\">See them</a></p>");
        }
        return b.toString();
    }

    /** The watch on the openings list: its state and its switch, and what arrived since the person last looked. */
    static String section(LibraryStore store, Career.Seeker s, View v, Map<String, String> from, boolean writer) throws IOException {
        ObjectNode w = state(store, s);
        boolean on = w.path("on").asBoolean(false);
        StringBuilder b = new StringBuilder("<p>");
        if (on) b.append("The library watches your companies every night, since ").append(Pages.esc(w.path("since").asText(""))).append(w.has("last_run") ? "; it last read them on " + Pages.esc(w.path("last_run").asText("")) : "").append(". ");
        else b.append("The nightly watch is off. With it on, the library reads your companies' openings every night and keeps the new ones that pass, here and in the inbox. ");
        if (writer) b.append("<form method=\"post\" action=\"/career/openings\" class=\"inline\">").append(CareerOpeningsPage.hidden("op", "watch")).append(CareerOpeningsPage.hidden("seeker", s.slug()))
                .append(CareerOpeningsPage.hidden("on", on ? "false" : "true")).append("<button>").append(on ? "Stop watching" : "Watch every night").append("</button></form>");
        b.append("</p>");
        List<Row> fresh = fresh(store, s, v);
        if (!fresh.isEmpty()) {
            b.append("<h2 id=\"new\">New since you last looked</h2>");
            Map<String, ObjectNode> byCompany = new HashMap<>();
            for (ObjectNode c : v.companies()) byCompany.put(CareerSearch.key(c.path("name").asText()), c);
            for (Row r : fresh) b.append(CareerOpeningsPage.card(r, s, from, byCompany, writer));
        }
        return b.toString();
    }

    /** The opening's line as the chat shows it, for what arrived. */
    public static String text(LibraryStore store, Career.Seeker s) throws IOException {
        List<Row> fresh = fresh(store, s, CareerFind.view(store, s));
        if (fresh.isEmpty()) return "";
        StringBuilder b = new StringBuilder("New since you last looked:\n");
        for (Row r : fresh) b.append(CareerFind.line(r)).append('\n');
        return b.toString();
    }

}
