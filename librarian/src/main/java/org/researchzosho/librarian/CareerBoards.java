package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.Config;
import org.researchzosho.librarian.CareerSources.Discovered;
import org.researchzosho.librarian.CareerSources.Feed;
import org.researchzosho.librarian.CareerSources.Opening;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The portfolio job boards that venture firms run for the companies they fund (docs/DESIGN_CAREER.md, step 3). A board lists its
 * companies' own openings, pulled from each company's hiring system, with the place and the company's stage; so for a decided place it
 * is the one index of the small companies there that a web search does not reach. The library reads a board the way a search engine
 * may: its sitemap names every job page, the page's address names the job, and the page carries the job data (schema.org JobPosting);
 * only the pages whose address names a role that passes the cut are read, at the polite pace, and the board's data service, which
 * refuses every reader in its robots file, is never called. Each opening is then read from the company's own board, as always.
 *
 * <p>The boards are sources of the one list (docs/DESIGN_SOURCES.md): the few the library knows are seeded into it once, each with the
 * place it is for, and the person's own from the setting {@code RESEARCHZOSHO_CAREER_BOARDS} (each as a name and the board's address) are
 * seeded as theirs; from then on the list is read, so a board the person took off on the Sources page is never read again, and one they
 * added there is read like any other. {@link #KNOWN} is only the seed.
 */
public final class CareerBoards {
    private CareerBoards() { }

    private static final ObjectMapper M = new ObjectMapper();
    public static final String BOARDS_KEY = "RESEARCHZOSHO_CAREER_BOARDS";
    /** Who found the seeded boards, as the Sources list says it. */
    public static final String TABLE = "the boards table";
    /** The most job pages read from one board in one search: the pages whose address names a role that passes, newest first. */
    static final int PAGES_PER_BOARD = 60;

    /** A board: the firm's name, the board's address, the words a decided place must carry for it to be read ([] for any place), and its id in the Sources list ("" when not from it). */
    public record Board(String name, String site, List<String> metro, String id) {
        public Board(String name, String site, List<String> metro) { this(name, site, metro, ""); }
    }

    /** The seed: the boards the library knows, each with the words of the place it is for. Read from the Sources list once seeded. */
    static final List<Board> KNOWN = List.of(
            new Board("Underscore VC", "https://jobs.underscore.vc", List.of("boston", "cambridge", "massachusetts", "ma")),
            new Board("Glasswing Ventures", "https://jobs.glasswing.vc", List.of("boston", "cambridge", "massachusetts", "ma")),
            new Board(".406 Ventures", "https://jobs.406ventures.com", List.of("boston", "cambridge", "massachusetts", "ma")),
            new Board("General Catalyst", "https://jobs.generalcatalyst.com", List.of()));

    /** What a board says of a company: its stage, and the board's name. */
    public record Known(String stage, String board) { }

    /** What the boards gave: the companies hiring for a title that passes, in the places that count, each with its posting; what is known of each; a note per board. */
    public record Found(List<Discovered> companies, Map<String, Known> known, Map<String, Opening> postings, List<String> notes) { }

    /** The boards from the setting: each entry a name and an address, or an address alone. */
    static List<Board> fromSetting() {
        List<Board> out = new ArrayList<>();
        for (String entry : Config.get(BOARDS_KEY, "").split(",")) {
            String e = entry.strip();
            if (e.isEmpty()) continue;
            int eq = e.indexOf('=');
            out.add(new Board(eq > 0 ? e.substring(0, eq).strip() : host(e), (eq > 0 ? e.substring(eq + 1).strip() : e).replaceFirst("/+$", ""), List.of()));
        }
        return out;
    }

    /** The boards to read for a person's decided places, with no library to keep them in: the known ones whose metro the places name, the ones for any place, and the setting's. */
    static List<Board> boards(List<String> places) {
        List<Board> out = new ArrayList<>();
        Set<String> keys = placeKeys(places);
        for (Board b : KNOWN) if (b.metro().isEmpty() || b.metro().stream().anyMatch(keys::contains)) out.add(b);
        out.addAll(fromSetting());
        return out;
    }

    static Set<String> placeKeys(List<String> places) {
        Set<String> keys = new LinkedHashSet<>();
        for (String p : places) keys.add(p.toLowerCase(Locale.ROOT).strip());
        return keys;
    }

    /**
     * The seed, once: the known boards go into the Sources list as the table's, with the place each is for; the setting's go in as the
     * person's own. An address already there is left as it is, so a board taken off stays off, and a board added on the page is theirs.
     */
    public static void seed(LibraryStore store) throws IOException {
        Set<String> have = new LinkedHashSet<>();
        for (Sources.Source s : Sources.kept(store)) have.add(Sources.key(s.address()));
        for (Board b : KNOWN) if (have.add(Sources.key(b.site()))) Sources.add(store, "board", b.site(), "seats", "career", b.metro().isEmpty() ? "" : placeWords(b.metro()), "", "", TABLE, "", b.name() + (b.name().endsWith("s") ? "' portfolio board" : "'s portfolio board"));
        for (Board b : fromSetting()) if (have.add(Sources.key(b.site()))) Sources.add(store, "board", b.site(), "seats", "career", "", "", "", "you", "from your " + BOARDS_KEY.toLowerCase(Locale.ROOT).replace("researchzosho_", "") + " setting", b.name());
    }

    /** The place words as the list keeps them: "Boston, Cambridge, Massachusetts, MA". */
    static String placeWords(List<String> metro) {
        List<String> out = new ArrayList<>();
        for (String m : metro) out.add(m.length() <= 2 ? m.toUpperCase(Locale.ROOT) : Character.toUpperCase(m.charAt(0)) + m.substring(1));
        return String.join(", ", out);
    }

    /**
     * The boards to read for a person's decided places, from the Sources list: every board there that is not off, whose place, when it
     * has one, the places name; a board for any place, and the person's own, are read everywhere.
     */
    static List<Board> boards(LibraryStore store, List<String> places) throws IOException {
        seed(store);
        List<Board> out = new ArrayList<>();
        Set<String> keys = placeKeys(places);
        for (Sources.Source src : Sources.kept(store)) {
            if (!src.kind().equals("board") || src.off() || !src.address().matches("(?i)https?://.*")) continue;
            List<String> metro = new ArrayList<>();
            for (String w : src.place().split(",")) if (!w.isBlank()) metro.add(w.strip().toLowerCase(Locale.ROOT));
            if (!metro.isEmpty() && metro.stream().noneMatch(keys::contains)) continue;   // a board for another place
            out.add(new Board(src.name().isEmpty() ? host(src.address()) : src.name().replaceFirst("'s? portfolio board$", ""), src.address().replaceFirst("/+$", ""), metro, src.id()));
        }
        return out;
    }

    static String host(String url) {
        try { String h = URI.create(url.strip()).getHost(); return h == null ? url : h.replaceFirst("^www\\.", ""); } catch (RuntimeException e) { return url; }
    }

    /** Every job page a board's sitemap names, the sitemaps it points at included. */
    static List<String> jobPages(Board b) {
        List<String> out = new ArrayList<>();
        String sm = CareerSources.get(b.site() + "/sitemap.xml");
        if (sm == null) return out;
        List<String> locs = locs(sm);
        for (String u : locs) {
            if (u.endsWith(".xml")) { String more = CareerSources.get(u); if (more != null) for (String v : locs(more)) if (v.contains("/jobs/")) out.add(v); }
            else if (u.contains("/jobs/")) out.add(u);
        }
        return out;
    }

    static List<String> locs(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<loc>\\s*(.*?)\\s*</loc>").matcher(xml);
        while (m.find()) out.add(m.group(1).strip());
        return out;
    }

    /** The job's name as its page's address carries it: the last part, its number dropped, its hyphens spaces. */
    static String slugWords(String url) {
        String last = url.substring(url.lastIndexOf('/') + 1).replaceFirst("^\\d+-", "");
        return last.replace('-', ' ').replace('_', ' ').strip();
    }

    /**
     * The companies on the boards hiring, in the places given or remotely when remote counts, for a title {@code titleOk} lets through; at
     * most {@code max}. The posting read from the job page comes with each, so the company's own board is read next and the posting
     * stands even when that board cannot be found.
     */
    public static Found find(List<String> places, boolean remoteCounts, Predicate<String> titleOk, int max) { return find(null, places, remoteCounts, titleOk, max); }

    /** As above; with a library, the boards come from its Sources list, seeded first, and each one read is kept there with its yield. A host the person refused is never read. */
    public static Found find(LibraryStore store, List<String> places, boolean remoteCounts, Predicate<String> titleOk, int max) {
        List<Discovered> out = new ArrayList<>();
        Map<String, Known> known = new LinkedHashMap<>();
        Map<String, Opening> postings = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Board> all;
        if (store == null) all = boards(places);
        else { try { all = boards(store, places); } catch (IOException e) { all = boards(places); notes.add("the Sources list could not be read, so the known boards were used: " + e.getMessage()); } }
        SourceRules rules = store == null ? SourceRules.live() : SourceRules.load(store);
        for (Board b : all) {
            if (out.size() >= max) break;
            if (rules.refused(b.site())) { notes.add(b.name() + ": not read, since you refused its host"); continue; }
            int before = out.size(), read = 0;
            List<String> pages = jobPages(b);
            List<String> candidates = new ArrayList<>();
            for (int i = pages.size() - 1; i >= 0; i--) if (titleOk.test(slugWords(pages.get(i)))) candidates.add(pages.get(i));   // the sitemap lists the oldest first
            for (String u : candidates) {
                if (out.size() >= max || read >= PAGES_PER_BOARD) break;
                String html = CareerSources.get(u);
                read++;
                if (html == null) continue;
                List<Opening> posted = CareerSources.jobPostings(html, u, "");
                if (posted.isEmpty()) continue;
                Opening o = posted.get(0);
                if (o.company().isBlank() || !titleOk.test(o.title())) continue;
                boolean inPlace = places.stream().anyMatch(p -> CareerCut.has(o.location(), p)), remote = o.remote() || html.contains("TELECOMMUTE");
                if (!inPlace && !(remoteCounts && remote)) continue;
                String key = CareerSearch.key(o.company());
                if (key.isEmpty() || !seen.add(key)) continue;
                Matcher st = Pattern.compile("\"stage\"\\s*:\\s*\"([a-z_]+)\"").matcher(html);
                known.put(key, new Known(stage(st.find() ? st.group(1) : ""), b.name()));
                List<Feed> feeds = CareerSources.boardsIn(html, "");
                Feed f = feeds.isEmpty() ? null : feeds.get(0);
                String how = "on " + b.name() + "'s portfolio board, hiring for " + CareerSources.clip(o.title(), 90) + (o.location().isBlank() ? (remote ? ", remote" : "") : " in " + o.location());
                out.add(new Discovered(o.company(), f == null ? null : new Feed(f.kind(), f.token(), how), o.title(), u));
                postings.put(key, new Opening(o.id(), o.company(), o.title(), o.location(), remote, u, o.posted(), o.postedNote(), "board:" + b.name(), o.text(), "", o.pay()));
            }
            notes.add(b.name() + (b.metro().isEmpty() ? " (read for any place)" : "") + ": " + pages.size() + " job pages, " + candidates.size() + " named a role that passes, " + read + " read, " + (out.size() - before) + (out.size() - before == 1 ? " company" : " companies"));
            if (store != null) try { Sources.read(store, b.site(), "board", "seats", "career", b.metro().isEmpty() ? "" : String.join(", ", places), "", TABLE, out.size() - before); } catch (IOException ignored) { }
        }
        return new Found(out, known, postings, notes);
    }

    /** A board's stage code as people say it. */
    static String stage(String code) {
        return switch (code == null ? "" : code.toLowerCase(Locale.ROOT)) {
            case "pre_seed", "preseed" -> "pre-seed"; case "seed" -> "seed"; case "series_a" -> "series A"; case "series_b" -> "series B"; case "series_c" -> "series C";
            case "series_c_plus", "series_d", "series_e", "series_f" -> "series C or later"; case "ipo", "public" -> "public"; case "acquisition", "acquired" -> "acquired";
            case "series_unknown", "other", "", "null" -> ""; default -> code.replace('_', ' ');
        };
    }

    static JsonNode json(String s) { try { return M.readTree(s); } catch (Exception e) { return M.createObjectNode(); } }

}
