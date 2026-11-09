package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.tools.Podcasts;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The podcasts page (0.7.0): the shows followed, as they come, and the podcasts reports, as a report of its own shape. */
public final class PodcastsPage {
    public static final String PATH = "/podcasts";

    private PodcastsPage() { }

    /** The shows followed: the shelves that follow a feed. */
    static List<Serials.Shelf> followed(LibraryStore store) {
        List<Serials.Shelf> out = new ArrayList<>();
        for (Serials.Shelf s : Serials.shelves(store)) if (s.podcast()) out.add(s);
        return out;
    }

    /** Whether a show was ever checked: the shelf keeps "-" until the first night. */
    static boolean checked(Serials.Shelf s) { return !s.lastChecked().isEmpty() && Character.isDigit(s.lastChecked().charAt(0)); }

    /** One line of state for the shows followed, on Past's row and at the page's head. */
    static String stateLine(List<Serials.Shelf> shows) {
        if (shows.isEmpty()) return "No show is followed yet.";
        StringBuilder b = new StringBuilder(shows.size() + (shows.size() == 1 ? " show followed: " : " shows followed: "));
        for (int i = 0; i < shows.size() && i < 4; i++) b.append(i > 0 ? ", " : "").append(shows.get(i).slug());
        if (shows.size() > 4) b.append(" and ").append(shows.size() - 4).append(" more");
        String last = ""; for (Serials.Shelf s : shows) if (checked(s) && s.lastChecked().compareTo(last) > 0) last = s.lastChecked();
        return b.append(last.isEmpty() ? "; not checked yet." : "; last checked " + Pages.dayTime(last) + ".").toString();
    }

    public static String body(LibraryStore store, Patrons.Patron patron) throws IOException { return body(store, patron, Map.of()); }

    public static String body(LibraryStore store, Patrons.Patron patron, Map<String, String> q) throws IOException {
        List<Serials.Shelf> shows = followed(store);
        StringBuilder b = new StringBuilder("<p class=\"lede\">Shows and episodes on a subject, followed as they come; what people say on them, in their own words with the time.</p>");
        String note = q.getOrDefault("note", "");
        if (!note.isEmpty()) b.append("<p class=\"note\">").append(Pages.esc(note)).append("</p>");
        b.append("<p class=\"state").append(shows.isEmpty() ? "" : " done").append("\">").append(Pages.esc(stateLine(shows))).append("</p>");
        if (!shows.isEmpty()) {
            b.append("<h2>Followed</h2><div class=\"tablewrap\"><table class=\"stack\"><tr><th>show</th><th>feed</th><th>every</th><th>last checked</th></tr>");
            for (Serials.Shelf s : shows) b.append("<tr><td data-l=\"show\">").append(Pages.esc(s.slug())).append(s.parked() ? " <span class=\"k\">(parked)</span>" : "").append("</td><td data-l=\"feed\">").append(Pages.locatorLink(s.feedUrl())).append("</td><td data-l=\"every\">").append(s.everyDays()).append(s.everyDays() == 1 ? " day" : " days").append("</td><td data-l=\"last checked\">").append(Pages.esc(checked(s) ? Pages.dayTime(s.lastChecked()) : "not yet")).append("</td></tr>");
            b.append("</table></div><p class=\"k\">The nightly housekeeping fetches each show's new episodes on its cadence; what it found is on <a href=\"/sources\">Sources</a>, and the episodes read are searched by the chat.</p>");
        } else b.append("<p class=\"k\">Follow a show below, or from a terminal: <code>researchzosho podcast follow &lt;feed address&gt;</code>; the chat's podcast search finds feeds.</p>");
        if (Pages.mayWrite(store, patron)) {
            b.append("<form method=\"post\" action=\"/podcasts\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"follow\"><label>").append(Pages.t("Follow a show by its feed address")).append(" <input name=\"feed\" size=\"44\" placeholder=\"https://…/feed.xml\" required></label> <label>").append(Pages.t("every")).append(" <input name=\"every\" size=\"3\" value=\"1\"> ").append(Pages.t("days")).append("</label> <button class=\"btn primary\">").append(Pages.t("Follow")).append("</button></form>");
            if (!shows.isEmpty()) {
                b.append("<form method=\"post\" action=\"/podcasts\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"unfollow\"><label>").append(Pages.t("Stop following")).append(" <select name=\"which\">");
                for (Serials.Shelf s : shows) b.append("<option value=\"").append(Pages.esc(s.slug())).append("\">").append(Pages.esc(s.slug())).append("</option>");
                b.append("</select></label> <button class=\"quiet\">").append(Pages.t("Unfollow")).append("</button> <span class=\"k\">").append(Pages.t("the transcripts already in the library stay")).append("</span></form>");
            }
            if (BooksPage.owner(store, patron)) {
                b.append("<h2>").append(Pages.t("The DNA of the shows")).append("</h2>");
                ObjectNode a = M.createObjectNode(); a.set("patron", LibrarianDaemon.patronNode(patron)); a.put("op", "status").put("kind", "podcast");
                try { b.append("<pre>").append(Pages.esc(new LibraryProtocol(store).dna(a).path("summary").asText("").strip())).append("</pre>"); } catch (ProtocolError e) { b.append("<p class=\"k\">").append(Pages.esc(e.getMessage())).append("</p>"); }
                b.append("<form method=\"post\" action=\"/podcasts\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"dna\"><label>").append(Pages.t("Read the transcripts held for their DNA now, up to")).append(" <input name=\"minutes\" size=\"4\" value=\"30\"> ").append(Pages.t("minutes")).append("</label> <button class=\"btn\">").append(Pages.t("Read them")).append("</button></form>");
            }
        }
        b.append(Pages.rowsSection(store, patron, "podcasts", "Podcasts reports", "None yet. Ask in the chat what someone says on a podcast, or about a show: the report it files is the first row here."));
        return b.toString();
    }

    static final ObjectMapper M = new ObjectMapper();

    /** A form of the page: follow a feed, stop following a show, read the transcripts for their DNA. The note shown back. */
    public static String post(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Patrons.check(store, patron, Patrons.Level.write);
        String op = form.getOrDefault("op", "");
        switch (op) {
            case "follow" -> {
                String feed = form.getOrDefault("feed", "").strip();
                int every = form.getOrDefault("every", "").matches("\\d+") ? Math.max(1, Integer.parseInt(form.get("every"))) : 1;
                if (feed.isEmpty()) return Pages.t("Give the feed's address.");
                var fe = Podcasts.feed(feed, 1);
                if (fe == null) return Pages.t("The feed at {0} did not answer or is not a podcast feed.", feed);
                List<Serials.Shelf> shelves = new ArrayList<>(Serials.shelves(store));
                for (Serials.Shelf sh : shelves) if (sh.podcast() && sh.feedUrl().equalsIgnoreCase(feed)) return Pages.t("Already following {0} ({1}).", fe.getKey().title(), sh.slug());
                String slug = fe.getKey().title().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-|-$", "");
                if (slug.isEmpty()) slug = "podcast";
                String base = slug; int n = 2;
                while (true) { String want = slug; if (shelves.stream().noneMatch(sh -> sh.slug().equals(want))) break; slug = base + "-" + n++; }
                shelves.add(new Serials.Shelf(slug, Serials.PODCAST + feed, every, "", false));
                Serials.writeShelves(store, shelves);
                return Pages.t("Following {0} as {1}: its new episodes arrive every {2} days and the housekeeping transcribes them at night.", fe.getKey().title(), slug, every);
            }
            case "unfollow" -> {
                String which = form.getOrDefault("which", "").strip();
                List<Serials.Shelf> shelves = new ArrayList<>(Serials.shelves(store));
                boolean removed = shelves.removeIf(sh -> sh.podcast() && (sh.slug().equalsIgnoreCase(which) || sh.feedUrl().equalsIgnoreCase(which)));
                if (!removed) return Pages.t("No followed show is {0}.", which);
                Serials.writeShelves(store, shelves);
                return Pages.t("No longer following {0}. The transcripts already in the library stay.", which);
            }
            case "dna" -> {
                if (!BooksPage.owner(store, patron)) return Pages.t("The DNA is the owner's own.");
                ObjectNode a = M.createObjectNode(); a.set("patron", LibrarianDaemon.patronNode(patron)); a.put("op", "podcast-read").put("kind", "podcast");
                if (form.getOrDefault("minutes", "").matches("\\d+")) a.put("minutes", Integer.parseInt(form.get("minutes")));
                try { return new LibraryProtocol(store).dna(a).path("summary").asText(Pages.t("The transcripts are being read.")); } catch (ProtocolError e) { return e.getMessage(); }
            }
            default -> { return Pages.t("That is not something the Podcasts page does."); }
        }
    }
}
