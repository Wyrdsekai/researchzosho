package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A home search as one of the library's runs: the cut from the decisions, the listings from every source the library can read, each on
 * the Sources page with its yield, the listings kept with the days they were seen, the cards filed for the few that fit best, and a
 * record of the run. The page reads the kept listings through the cut and shows them in tiers.
 */
public final class HomeFind {
    private HomeFind() { }
    private static final ObjectMapper M = new ObjectMapper();
    public static final String JOB_KIND = "home-search";
    /** How many cards a search files for the listings that fit best, and how many agents' sites it reads. */
    static final int CARDS = 3, AGENT_SITES = 10, ENGINE_QUERIES = 5;

    /** One kept listing with its verdict, its tier, its reaction and its card, for the page. */
    public record Row(Home.Listing listing, HomeCut.Verdict verdict, int tier, JsonNode reaction, ObjectNode card) {
        public String reactionWord() { return reaction == null || reaction.isMissingNode() ? "" : reaction.path("reaction").asText(""); }
    }
    /** What RentCast holds beyond the pages fetched: how many in all, how many fetched, and the sentence the page and the log say. */
    public record More(int total, int fetched) {
        public int left() { return Math.max(0, total - fetched); }
        public String sentence() { return "RentCast holds " + String.format(Locale.ROOT, "%,d", total) + " listings that fit what you decided, and " + String.format(Locale.ROOT, "%,d", fetched) + " are kept here, so the search is wide."; }
    }
    public record View(HomeCut.Cut cut, List<Row> passing, List<Row> aside, String searched, List<String[]> portals, More more) { }

    /** Files the search as a run. A sentence for the person. */
    public static String file(LibraryStore store, Home.Search s, String patronDid) throws IOException { return file(store, s, patronDid, false); }

    /** Files the search as a run; with {@code more}, a run that fetches the next page from RentCast and nothing else. A sentence for the person. */
    public static String file(LibraryStore store, Home.Search s, String patronDid, boolean more) throws IOException {
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("filed only"); });
        for (ObjectNode j : jobs.active()) if (j.path("kind").asText().equals(JOB_KIND) && j.path("args").path("search").asText().equals(s.slug()))
            return "A search for \"" + s.name() + "\" is already " + (j.path("state").asText().equals("running") ? "running" : "waiting to start") + " (" + j.path("job_id").asText() + "); the runs page shows how far it has come.";
        if (HomeJapan.looks(Home.wantsOf(store, s).getOrDefault("place", ""))) {   // a place in Japan: SUUMO's lists whole, the ministry's records with a key; no next page to fetch
            boolean ja = Home.lang(store, s).equals("ja"), service = Share.serviceRunning.getAsBoolean(), key = HomeJapanRecords.keySet();
            if (more) return ja ? "日本の検索に次のページはありません：SUUMOの一覧は毎回すべてのページを読みます。もう一度検索してください。" : "A search in Japan has no next page to fetch: SUUMO's lists are read whole each time. Search again to look afresh.";
            String id = jobs.submit(JOB_KIND, patronDid, M.createObjectNode().put("search", s.slug()));
            return ja ? "検索はSUUMOの一覧を、決めた条件で絞ってすべてのページを読み、条件に合う物件を残し、最もよく合う" + CARDS + "件のカードを作ります" + (key ? "（国土交通省の記録を読んでから）" : "（不動産情報ライブラリのキーがないため、記録なしで）") + "。実行" + id + "として登録しました。進み具合は実行一覧のページに表示され、Homeページは進むにつれて埋まります。" + (service ? "" : " ライブラリのサービスが動いていないため、サービスが始まり次第実行されます：researchzosho service start。")
                    : "The search reads SUUMO's lists for the municipality with what you decided as its own filters, every page, keeps the ones that fit, and files a card for the " + CARDS + " that fit best" + (key ? ", the ministry's records read first" : ", with no " + HomeJapanRecords.NAME + " key, so without the records") + ". It is filed as " + id + "; the runs page shows how far it has come, and the Home page fills as it goes." + (service ? "" : " The library's service is not running, so it starts when the service does: researchzosho service start.");
        }
        if (more) {
            if (!HomeSources.rentCastKeySet()) return "The next page is RentCast's, and no RentCast key is set (" + HomeSources.RENTCAST_KEY + ").";
            More m = more(store, s);
            if (m == null || m.left() <= 0) return "RentCast gave everything that fits what you decided; there is no next page. Search again to look afresh.";
            String id = jobs.submit(JOB_KIND, patronDid, M.createObjectNode().put("search", s.slug()).put("more", true));
            return "The run fetches the next " + Math.min(HomeSources.RENTCAST_PAGE, m.left()) + " listings from RentCast, one call, keeps the ones that fit, and files a card for new best fits. It is filed as " + id + "."
                    + (Share.serviceRunning.getAsBoolean() ? "" : " The library's service is not running, so it starts when the service does: researchzosho service start.");
        }
        String id = jobs.submit(JOB_KIND, patronDid, M.createObjectNode().put("search", s.slug()));
        return "The search looks for listings from every source the library can read" + (HomeSources.rentCastKeySet() ? ", RentCast first, asked for what you decided" : ", with no RentCast key, so from the web search's leads and the agents' own sites") + ", keeps the ones that fit what you decided, and files a card for the " + CARDS + " that fit best. It is filed as " + id + "; the runs page shows how far it has come, and the Home page fills as it goes."
                + (Share.serviceRunning.getAsBoolean() ? "" : " The library's service is not running, so it starts when the service does: researchzosho service start.");
    }

    public static String runJob(LibraryStore store, ObjectNode job, Consumer<ObjectNode> progress, BooleanSupplier stop) throws IOException {
        Home.Search s = Home.search(store, job.path("args").path("search").asText(""));
        if (s == null) throw new IllegalArgumentException("The search this run was for is no longer in the library.");
        return run(store, s, sentence -> progress.accept(M.createObjectNode().put("phase", sentence).put("at", Instant.now().toString())), stop, job.path("patron").asText("person"), job.path("args").path("more").asBoolean(false));
    }

    public static String run(LibraryStore store, Home.Search s, CareerFind.Progress progress, BooleanSupplier stop, String patronDid) throws IOException { return run(store, s, progress, stop, patronDid, false); }

    /** Where RentCast's pages stand for a search: the cut they were fetched for, and per kind of listing how many fit and how many are fetched. */
    static Path pagesFile(LibraryStore store, Home.Search s) { return s.dir(store).resolve("search").resolve("rentcast.json"); }

    static ObjectNode pages(LibraryStore store, Home.Search s) throws IOException {
        Path f = pagesFile(store, s);
        return Files.isRegularFile(f) ? (ObjectNode) M.readTree(f.toFile()) : M.createObjectNode();
    }

    /** What RentCast holds beyond what is fetched, for the cut as it stands; null when RentCast was not asked, or the cut has changed since. */
    public static More more(LibraryStore store, Home.Search s) throws IOException {
        ObjectNode p = pages(store, s);
        HomeCut.Cut cut = HomeCut.kept(store, s);
        if (cut == null || !p.path("basis").asText("").equals(cut.basis())) return null;
        int total = 0, fetched = 0; boolean any = false;
        for (JsonNode k : p.path("kinds")) { if (k.path("total").asInt(-1) < 0) continue; any = true; total += k.path("total").asInt(0); fetched += k.path("fetched").asInt(0); }
        return any ? new More(total, fetched) : null;
    }

    public static String run(LibraryStore store, Home.Search s, CareerFind.Progress progress, BooleanSupplier stop, String patronDid, boolean more) throws IOException {
        progress.at("Making the cut from what you decided");
        HomeCut.Cut cut = HomeCut.current(store, s);
        String[] cs = cityState(store, s, cut);
        String city = cs[0], state = cs[1];
        if (city.isEmpty()) {
            String said = cut.places().isEmpty() && cut.preferPlaces().isEmpty() ? "The search needs a place first: decide where, as \"Somerville, MA\", on the Home page or in your own words."
                    : "The search needs a town to look in: a state alone judges the listings, but the sources are read town by town. Decide where as a town and its state, \"Somerville, MA\", on the Home page or in your own words.";
            Home.log(store, s, said);
            return said;
        }
        List<String> tenures = cut.tenure().isEmpty() ? List.of("sale", "rent") : List.of(cut.tenure());
        List<Home.Listing> found = new ArrayList<>();
        List<String[]> yields = new ArrayList<>();   // {source, kind, address, count, note}
        boolean ja = cut.lang().equals("ja");
        if (cut.inJapan()) {
            // Japan: SUUMO's lists enumerated by municipality with the cut as its own filters, every page; the portals a program cannot read are links; RentCast, the engines' snippets and the map's agents are American
            if (cut.japan().code().isEmpty()) {
                String said = ja ? "市区町村コードが分からないため、物件一覧を読めません。場所を「東京都杉並区」のように、都道府県と市区町村名で決めてください。" : "The municipality's code is not known, so the listings cannot be read: decide the place as a municipality with its prefecture, as 杉並区, 東京都 or Suginami, Tokyo.";
                Home.log(store, s, said);
                return said;
            }
            if (more) Home.log(store, s, ja ? "日本の検索では次のページという概念はありません：SUUMOの一覧は毎回すべてのページを読みます。" : "A search in Japan has no next page to fetch: SUUMO's lists are read whole each time.");
            for (String t : tenures) {
                if (stop.getAsBoolean()) break;
                progress.at(ja ? "SUUMOの" + (t.equals("rent") ? "賃貸" : "売買") + "一覧を" + city + "について読んでいます" : "Reading SUUMO's " + (t.equals("rent") ? "rentals" : "homes for sale") + " for " + city + ", every page");
                HomeSources.Yield y = HomeJapanListings.suumo(store, cut, t, progress);
                found.addAll(y.listings()); yields.add(new String[]{y.name(), y.kind(), y.address(), String.valueOf(y.listings().size()), y.note()});
                Sources.add(store, "list page", y.address(), "listings", "home", city + ", " + state, t, "", "the home search for " + s.name(), "", HomeJapanListings.SUUMO);
                Sources.read(store, y.address(), "list page", "listings", "home", city + ", " + state, t, "the home search for " + s.name(), y.listings().size());
                Home.log(store, s, "SUUMO" + (ja ? "：" : ": ") + y.note() + (ja ? "。" : "."));
            }
            Home.log(store, s, ja ? "アットホームはプログラムに認証ページを返し、LIFULL HOME'Sはプログラムを受け付けないため、その一覧は読んでいません。ご自身で開けるリンクをHomeページに置いています。" : "AtHome answers a program with a verification page and LIFULL HOME'S refuses programs, so their lists are not read; the Home page links them for you to open.");
            if (!HomeJapanRecords.keySet()) Home.log(store, s, ja ? HomeJapanRecords.NAME + "のキー（" + HomeJapanRecords.KEY + "）が設定されていないため、カードには取引価格・地価公示・ハザード情報が付きません。キーは国土交通省のサイトで無料で申請できます。" : "No " + HomeJapanRecords.NAME + " key is set (" + HomeJapanRecords.KEY + "), so the cards carry no prices paid nearby, land price point or hazard areas; the key is free, by application on the ministry's site.");
        } else
        // RentCast, when the person has a key: one call per kind of listing, with the cut as the service's own filters; a next page only on the person's word
        if (HomeSources.rentCastKeySet()) {
            ObjectNode p = pages(store, s);
            if (!p.path("basis").asText("").equals(cut.basis())) { if (more && p.size() > 0) Home.log(store, s, "What you decided has changed since RentCast was last asked, so the search asks it afresh rather than for a next page."); p = M.createObjectNode().put("basis", cut.basis()); p.putObject("kinds"); more = false; }
            for (String t : tenures) {
                if (stop.getAsBoolean()) break;
                ObjectNode k = p.withObject("/kinds/" + t);
                int offset = more ? k.path("fetched").asInt(0) : 0;
                if (more && k.path("total").asInt(-1) >= 0 && offset >= k.path("total").asInt(0)) { Home.log(store, s, "RentCast gave every " + (t.equals("rent") ? "rental" : "home for sale") + " that fits; there is no next page."); continue; }
                progress.at("Asking RentCast for " + (t.equals("rent") ? "rentals" : "homes for sale") + " that fit, " + (cut.placed() && cut.nearKm() > 0 ? "within " + cut.nearKm() + " km of " : "in ") + city + ", " + state + (offset > 0 ? ", the next page" : ""));
                HomeSources.Yield y = HomeSources.rentCast(cut, city, state, t, offset);
                found.addAll(y.listings()); yields.add(new String[]{y.name(), y.kind(), y.address(), String.valueOf(y.listings().size()), y.note()});
                Sources.read(store, y.address(), y.kind(), "listings", "home", city + ", " + state, t, "the home search for " + s.name(), y.listings().size());
                if (y.total() >= 0) k.put("total", y.total()); else if (!more) k.remove("total");
                k.put("fetched", offset + y.listings().size()).put("at", LocalDate.now().toString());
                Home.log(store, s, "RentCast: " + y.note() + ". " + HomeSources.rentCastMonth());
                if (y.left() > 0) Home.log(store, s, new More(y.total(), offset + y.listings().size()).sentence() + " Narrow the radius (" + (cut.nearKm() > 0 ? cut.nearKm() + " km now" : "none set") + "), the budget or the kind to see fewer and nearer, or fetch the next " + Math.min(HomeSources.RENTCAST_PAGE, y.left()) + " with one more call: researchzosho home search --more, or the button on the Home page.");
            }
            Files.createDirectories(pagesFile(store, s).getParent());
            M.writerWithDefaultPrettyPrinter().writeValue(pagesFile(store, s).toFile(), p);
        } else Home.log(store, s, "No RentCast key is set (" + HomeSources.RENTCAST_KEY + "), so the listings come from the web search's leads, the agents' own sites and what you save yourself; the portals' pages cannot be read by a program.");
        // the search engines' snippets: not again for a next page, and not in Japan
        for (String t : more || cut.inJapan() ? List.<String>of() : tenures) {
            if (stop.getAsBoolean()) break;
            progress.at("Reading the web search's leads for " + (t.equals("rent") ? "rentals" : "homes for sale") + " in " + city);
            HomeSources.Yield y = HomeSources.engineLeads(cut, city, state, t, ENGINE_QUERIES);
            found.addAll(y.listings()); yields.add(new String[]{y.name(), y.kind(), y.address(), String.valueOf(y.listings().size()), y.note()});
            Home.log(store, s, "The web search's leads (" + (t.equals("rent") ? "rentals" : "for sale") + "): " + y.note() + ". The library reads the engine's snippet and never the portal's page; open a lead yourself.");
        }
        // the agents' and builders' own sites, through the map: not again for a next page, and not in Japan, where the readers are American
        if (!stop.getAsBoolean() && !more && !cut.inJapan()) {
            progress.at("Reading the estate agents' own sites in " + city + ", from the map");
            HomeSources.Agents agents = HomeSources.agentSites(city + ", " + state, city, state, tenures.get(0), AGENT_SITES);
            List<HomeSources.Yield> ys = agents.read();
            int withListings = 0, scripted = 0;
            for (HomeSources.Yield y : ys) {
                found.addAll(y.listings()); yields.add(new String[]{y.name(), y.kind(), y.address(), String.valueOf(y.listings().size()), y.note()});
                if (!y.address().isEmpty()) {
                    Sources.add(store, "list page", y.address(), "listings", "home", city + ", " + state, tenures.get(0), "", "the home search for " + s.name(), "", y.name());   // the agent's name beside its address
                    Sources.read(store, y.address(), "list page", "listings", "home", city + ", " + state, tenures.get(0), "the home search for " + s.name(), y.listings().size());
                }
                if (!y.listings().isEmpty()) withListings++;
                if (y.note().contains("scripts")) scripted++;
            }
            int none = agents.withoutSite();
            String noSite = none == 0 ? "" : none == 1 ? "1 agent the map names has no website of its own, only its entry on the map, so it was not read." : none + " agents the map names have no website of their own, only their entry on the map, so they were not read.";
            Home.log(store, s, ys.isEmpty() ? (none > 0 ? "The map names no estate agent with a website of its own in " + city + ". " + noSite : "The map names no estate agent with a site in " + city + ", or the map did not answer.")
                    : "The estate agents' own sites, from the map: " + ys.size() + " read, " + withListings + " with listings the library could read" + (scripted > 0 ? ", " + scripted + " drawn by scripts" : "") + "." + (noSite.isEmpty() ? "" : " " + noSite));
        }
        int added = Home.keep(store, s, found);
        Home.log(store, s, ja ? "今回" + found.size() + "件の物件を読み、" + added + "件が新しい物件です。各ポータルの検索ページへのリンクはHomeページにあります。" : found.size() + (found.size() == 1 ? " listing" : " listings") + " found this time, " + added + " new; the portals' own pages are linked on the Home page for you to open.");
        // the view through the cut, the cards for the best fits, the record of the run
        View v = view(store, s);
        int cards = 0;
        if (!stop.getAsBoolean()) {
            List<Row> best = new ArrayList<>(v.passing());   // the fits first, then the near misses: a card only for a home whose place is shown to be the one wanted
            HomeCut.Cut seen = v.cut();
            best.removeIf(r -> r.card() != null || r.reactionWord().equals("pass") || r.listing().address().isEmpty() || !HomeCut.placeShown(seen, r.listing()));
            for (Row r : best.subList(0, Math.min(CARDS, best.size()))) {
                progress.at(ja ? r.listing().addressWords() + "の記録を読み、カードを作成しています" : "Reading the records and filing the card of " + r.listing().address());
                try { HomeCard.file(store, s, r.listing(), patronDid); cards++; }
                catch (RuntimeException e) { Home.log(store, s, "The card of " + r.listing().address() + " could not be filed: " + e.getMessage()); }
            }
        }
        v = view(store, s);
        snapshot(store, s, v, yields);
        String said = summary(v, yields, cards);
        if (!stop.getAsBoolean()) {
            // the report of the fits, the goal's shape: the homes that fit as a list with a link on each line, for Past and the chat
            try { String id = fileFits(store, s, v, patronDid); if (!id.isEmpty()) said = said + (ja ? " 条件に合う物件の一覧は報告" + id + "として保存しました。" : " The fits are filed as the report " + id + "."); }
            catch (RuntimeException | IOException e) { Home.log(store, s, "The report of the fits could not be filed: " + e.getMessage()); }
        }
        Home.log(store, s, said);
        return said + (stop.getAsBoolean() ? (ja ? " 検索は途中で止められました。" : " The search was stopped before it finished.") : "");
    }

    /**
     * A home's line for the report: the address linked to the home itself, its own page or a web search for its full address, the figures,
     * where it was seen with that page's link, and for a near miss what it does not show of what was decided.
     */
    static String fitLine(Row r, HomeCut.Cut cut) {
        Home.Listing l = r.listing();
        boolean ja = cut.lang().equals("ja");
        String name = l.japan() ? l.addressWords() : (l.address().isEmpty() ? "a home" : l.address()) + (l.place().isEmpty() ? "" : ", " + l.place());
        String rest = l.line().startsWith(name) ? l.line().substring(name.length()) : " · " + l.line();
        String link = l.link().isBlank() ? name : "[" + name + "](" + l.link() + ")";
        String from = l.seenOn().isEmpty() ? (l.source().isEmpty() ? (ja ? "出典不明" : "source not kept") : l.source())
                : (ja ? "[" + l.seenOnName() + "の一覧ページ](" + l.seenOn() + ")に掲載" : "seen on [" + possessive(l.seenOnName()) + " page](" + l.seenOn() + ")" + (l.source().startsWith("the web search") ? ", in the web search's snippet of it" : ""));
        List<String> notes = notes(r, cut);
        return "- " + link + rest + " — " + from + (notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")");
    }

    /** What a passing home's line says beside it: what it does not show of what was decided, and a guessed place it is not in. */
    static List<String> notes(Row r, HomeCut.Cut cut) {
        List<String> out = new ArrayList<>();
        if (r.verdict().why() != null && !r.verdict().why().isBlank()) out.add(r.verdict().why());
        if (r.tier() >= 2) { List<String> ps = new ArrayList<>(); for (HomeCut.Place p : HomeCut.places(cut.preferPlaces())) ps.add(p.words()); out.add(cut.lang().equals("ja") ? "ライブラリが推測した場所（" + String.join("、", ps) + "）にはありません" : "not in " + HomeCut.or(ps) + ", where the library guesses you want to be"); }
        out.addAll(HomeCut.unknown(cut, r.listing()));
        return out;
    }

    /** "zillow.com's", "Union Square Homes'". */
    static String possessive(String name) { return name.endsWith("s") ? name + "'" : name + "'s"; }

    /**
     * The report of a search's fits, filed on the shelves under the home goal's shape: tiers of fits, best first, each home one line with
     * a link to its page, the near misses, and what was set aside. The id of the report; "" when nothing was found to list.
     */
    public static String fileFits(LibraryStore store, Home.Search s, View v, String patronDid) throws IOException {
        if (v.passing().isEmpty() && v.aside().isEmpty()) return "";
        Map<String, String> wants = Home.wantsOf(store, s);
        boolean ja = v.cut().lang().equals("ja");
        StringBuilder q = new StringBuilder(ja ? "検索「" + s.name() + "」の条件に合う物件" : "Homes that fit what was decided for the search \"" + s.name() + "\"");
        List<String> decided = new ArrayList<>();
        for (var e : wants.entrySet()) if (!e.getValue().isBlank()) decided.add(Home.label(e.getKey(), v.cut().lang()) + (ja ? "：" : ": ") + e.getValue());
        if (!decided.isEmpty()) q.append(ja ? "（" : " (").append(String.join(ja ? "；" : "; ", decided)).append(ja ? "）" : ")");
        StringBuilder b = new StringBuilder();
        List<Row> fits = new ArrayList<>(), near = new ArrayList<>();
        for (Row r : v.passing()) (r.tier() <= 0 ? fits : near).add(r);
        b.append(ja ? "## 条件に合う物件\n\n" : "## Homes that fit\n\n");
        if (fits.isEmpty()) b.append(ja ? "決めた条件のすべてに合う物件はありません。惜しい物件を下に示します。\n" : "None fits every point decided; the near misses are below.\n");
        for (Row r : fits) b.append(fitLine(r, v.cut())).append('\n');
        if (!near.isEmpty()) { b.append(ja ? "\n## 惜しい物件\n\n記載のある条件には合いますが、記載のない条件があります。何の記載がないかを各行に添えています。\n\n" : "\n## Near misses\n\nEach fits what it shows of what was decided; what it does not show is said beside it.\n\n"); for (Row r : near) b.append(fitLine(r, v.cut())).append('\n'); }
        if (!v.aside().isEmpty()) {
            b.append(ja ? "\n## 除外した物件\n\n決めた条件により" + v.aside().size() + "件を除外しました" : "\n## Set aside\n\n" + v.aside().size() + (v.aside().size() == 1 ? " listing was" : " listings were") + " set aside by what was decided");
            int n = 0; for (Row r : v.aside()) { if (n++ >= 12) { b.append(ja ? "；ほか" + (v.aside().size() - 12) + "件" : "; and " + (v.aside().size() - 12) + " more"); break; } b.append(n == 1 ? (ja ? "：" : ": ") : (ja ? "；" : "; ")).append(r.listing().japan() ? r.listing().addressWords() : r.listing().address().isEmpty() ? "a home" : r.listing().address()).append(r.verdict().why() == null || r.verdict().why().isBlank() ? "" : (ja ? "（" + r.verdict().why() + "）" : " (" + r.verdict().why() + ")")); }
            b.append(ja ? "。\n" : ".\n");
        }
        b.append(ja ? "\n## 作り方\n\nライブラリは読めるすべての出典を読み、決めた条件から基準（" + HomeCut.summary(v.cut()) + "）を作り、各物件をそれで判定しました。各行の数字は、その物件が掲載されていたページの記載そのものです。物件のリンクはその物件自身のページを開きます。Homeページには検索の出典と、最もよく合う物件のカードがあります。\n"
                : "\n## How this was made\n\nThe home search read every source it could, made the cut from what was decided (" + HomeCut.summary(v.cut()) + "), and judged each listing by it; the figures on each line are the listing's own, from the page it was seen on. A home's link opens its own page, or searches the web for its address when the library has only a page of listings it was seen on. The Home page keeps the search with its sources and the cards of the best fits.\n");
        String title = (ja ? "条件に合う物件: " : "Homes that fit: ") + s.name();
        Investigation inv = Acquisitions.admit(store, new LibrarianIndex(store), q.toString(), b.toString(), "patron:home", "", id -> { }, "", title, "");
        Fields.record(store, inv.id(), "", "home", "home-search");
        Home.log(store, s, ja ? "条件に合う物件の一覧を報告" + inv.id() + "として保存しました。各行に物件へのリンクがあります。" : "The fits are filed as the report " + inv.id() + ", with a link on each home's line.");
        return inv.id();
    }

    /** The town and the state the search reads its sources for: the first place with a town, decided or else guessed ("Cambridge, Massachusetts" is Cambridge, MA); the state's code from the place want when the place gives none. In Japan, the municipality and its prefecture. */
    static String[] cityState(LibraryStore store, Home.Search s, HomeCut.Cut cut) throws IOException {
        List<String> places = new ArrayList<>(cut.places()); places.addAll(cut.preferPlaces());
        if (cut.inJapan()) {
            String city = cut.japan().name();
            if (city.isEmpty()) for (HomeCut.Place p : HomeCut.places(places)) if (!p.regionAlone()) { city = p.town(); break; }
            return new String[]{city, cut.japan().pref()};
        }
        String city = "", state = "";
        for (HomeCut.Place p : HomeCut.places(places)) {
            if (p.regionAlone()) { if (state.isEmpty() && p.region().length() == 2) state = p.region(); continue; }
            city = p.town(); state = p.region().length() == 2 ? p.region() : "";
            break;
        }
        if (state.isEmpty()) {
            String want = Home.wantsOf(store, s).getOrDefault("place", "");
            Matcher m = Pattern.compile("\\b([A-Z]{2})\\b").matcher(want);
            if (m.find()) state = m.group(1);
        }
        return new String[]{city, state};
    }

    /** The kept listings through the cut: the ones that pass, in tiers and nearest first; the ones set aside with the reason; the portal links. */
    public static View view(LibraryStore store, Home.Search s) throws IOException {
        HomeCut.Cut cut = HomeCut.kept(store, s);
        if (cut == null) cut = HomeCut.Cut.NONE;
        ObjectNode rx = Home.reactions(store, s);
        Map<String, ObjectNode> cards = new HashMap<>();
        for (ObjectNode c : HomeCard.cards(store, s)) cards.put(c.path("id").asText(""), c);
        List<Row> passing = new ArrayList<>(), aside = new ArrayList<>();
        String searched = "";
        for (Home.Listing l : Home.listings(store, s).values()) {
            HomeCut.Verdict v = HomeCut.judge(cut, l);
            Row r = new Row(l, v, HomeCut.tier(cut, l), rx.get(l.id()), cards.get(l.id()));
            (v.passes() ? passing : aside).add(r);
            if (l.lastSeen().compareTo(searched) > 0) searched = l.lastSeen();
        }
        HomeCut.Cut order = cut;
        Map<String, Integer> unshown = new HashMap<>();   // a near miss that shows more of what was decided comes first: a home short of its size before a plot with no figures at all
        for (Row r : passing) unshown.put(r.listing().id(), HomeCut.notShown(order, r.listing()));
        passing.sort(Comparator.comparingInt((Row r) -> r.reactionWord().equals("like") ? -1 : r.reactionWord().equals("pass") ? 1 : 0).thenComparingInt(Row::tier)
                .thenComparingInt(r -> unshown.getOrDefault(r.listing().id(), 0)).thenComparingDouble(r -> HomeCut.offBudget(order, r.listing())));   // then nearest the budget, not cheapest first
        aside.sort(Comparator.comparing((Row r) -> r.listing().city(), String.CASE_INSENSITIVE_ORDER).thenComparing(r -> r.listing().address(), String.CASE_INSENSITIVE_ORDER));
        String[] cs = cityState(store, s, cut);
        List<String[]> portals = cs[0].isEmpty() ? List.of() : cut.inJapan() ? HomeJapanListings.portalLinks(store, cut, cut.tenure().isEmpty() ? "sale" : cut.tenure()) : HomeSources.portalLinks(cut, cs[0], cs[1], cut.tenure().isEmpty() ? "sale" : cut.tenure());
        return new View(cut, passing, aside, searched, portals, more(store, s));
    }

    static void snapshot(LibraryStore store, Home.Search s, View v, List<String[]> yields) throws IOException {
        ObjectNode o = M.createObjectNode().put("at", LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()).put("cut", HomeCut.summary(v.cut())).put("rentcast_key", HomeSources.rentCastKeySet());
        if (v.more() != null) o.put("rentcast_total", v.more().total()).put("rentcast_fetched", v.more().fetched());
        ArrayNode ys = o.putArray("sources");
        for (String[] y : yields) ys.addObject().put("source", y[0]).put("kind", y[1]).put("address", y[2]).put("listings", Integer.parseInt(y[3])).put("note", y[4]);
        ArrayNode ps = o.putArray("passing"); for (Row r : v.passing()) ps.add(r.listing().id());
        o.put("aside", v.aside().size());
        Path d = s.dir(store).resolve("search").resolve("runs");
        Files.createDirectories(d);
        M.writerWithDefaultPrettyPrinter().writeValue(d.resolve(o.path("at").asText().replace(":", "") + ".json").toFile(), o);
    }

    static String summary(View v, List<String[]> yields, int cards) {
        int sources = 0, withListings = 0, total = 0;
        for (String[] y : yields) { sources++; int n = Integer.parseInt(y[3]); total += n; if (n > 0) withListings++; }
        int fits = (int) v.passing().stream().filter(r -> r.tier() == 0).count(), near = v.passing().size() - fits;
        if (v.cut().lang().equals("ja")) {
            StringBuilder b = new StringBuilder("検索は" + sources + "つの出典を読み、物件のあった出典は" + withListings + "つ、物件は合計" + total + "件でした。");
            b.append("決めた条件に合う物件は").append(fits).append("件");
            if (near > 0) b.append("、惜しい物件（決めた条件の一部の記載がないもの）は").append(near).append("件");
            b.append(v.aside().isEmpty() ? "" : "、除外した物件は" + v.aside().size() + "件").append("です。");
            if (cards > 0) b.append("最もよく合う").append(cards).append("件についてカードを作成しました：記録を読み、それぞれ調査を進めています。");
            return b.toString();
        }
        StringBuilder b = new StringBuilder("The search read " + sources + (sources == 1 ? " source" : " sources") + ", " + withListings + " with listings, " + total + " listings in all. ");
        b.append(fits).append(fits == 1 ? " listing fits" : " listings fit").append(" what you decided");
        if (near > 0) b.append(", and ").append(near).append(near == 1 ? " is a near miss, which does not show everything you decided" : " are near misses, which do not show everything you decided");
        b.append(v.aside().isEmpty() ? "" : "; " + v.aside().size() + " set aside").append('.');
        if (cards > 0) b.append(" The library filed ").append(cards).append(cards == 1 ? " card" : " cards").append(" for the ones that fit best: the records read, and a research run each.");
        return b.toString();
    }

    /** The card of one kept listing, by its id or the start of it, filed now. A sentence. */
    public static String card(LibraryStore store, Home.Search s, String id, String patronDid) throws IOException {
        Home.Listing l = listing(store, s, id);
        if (l.address().isEmpty()) return "That listing has no address, so its records cannot be read; the card needs one.";
        return HomeCard.file(store, s, l, patronDid);
    }

    static Home.Listing listing(LibraryStore store, Home.Search s, String id) throws IOException {
        String k = id == null ? "" : id.strip();
        Map<String, Home.Listing> all = Home.listings(store, s);
        Home.Listing l = all.get(k);
        if (l == null) for (Home.Listing x : all.values()) if (!k.isEmpty() && x.id().startsWith(k)) { l = x; break; }
        if (l == null) throw new IllegalArgumentException("There is no listing " + k + ". The short names in brackets on the Home page and in /home listings are the ones to use.");
        return l;
    }

    /** A listing the person found themselves, from the line they typed and the page's address; kept with the rest and judged. A sentence. */
    public static String save(LibraryStore store, Home.Search s, String words, String url, String tenure) throws IOException {
        HomeCut.Cut cut = HomeCut.current(store, s);
        String[] cs = cityState(store, s, cut);
        Home.Listing l = HomeSources.fromText("saved by you", words, url == null ? "" : url.strip(), cs[0], cs[1], tenure == null ? "" : tenure.strip(), "saved from your own browser on " + today());
        if (l == null) throw new IllegalArgumentException("The library could not read a street address in that. Write the address with the town and the state first, then the price: 12 Elm St, Somerville, MA · $875,000 · 3 bd · 2 ba.");
        int added = Home.keep(store, s, List.of(l));
        HomeCut.Verdict v = HomeCut.judge(cut, l);
        List<String> unknown = HomeCut.unknown(cut, l);
        String said = (added > 0 ? "Saved: " : "Known already, updated: ") + l.line() + ". " + (!v.passes() ? "It is set aside: " + v.why() + "." : unknown.isEmpty() ? "It fits what you decided."
                : "It fits what it shows of what you decided, and is a near miss: " + String.join("; ", unknown) + ".");
        Home.log(store, s, said);
        return said;
    }

    /** What the chat and the command line say for a search: where it stands, then the listings that fit, a line each. */
    public static String text(LibraryStore store, Home.Search s, int max) throws IOException {
        View v = view(store, s);
        boolean ja = v.cut().lang().equals("ja");
        StringBuilder b = new StringBuilder(Home.standing(store, s)).append('\n');
        b.append(ja ? "条件：" : "What counts as a fit: ").append(HomeCut.summary(v.cut())).append(ja ? "。\n" : ".\n");
        if (v.more() != null && v.more().left() > 0) b.append(v.more().sentence()).append(" Narrow the radius, the budget or the kind, or fetch the next ").append(Math.min(HomeSources.RENTCAST_PAGE, v.more().left())).append(" with researchzosho home search --more (one RentCast call).\n");
        if (v.passing().isEmpty()) b.append(Home.listings(store, s).isEmpty() ? (ja ? "まだ物件がありません。検索を始めてください。" : "No listings yet: start a search.") : (ja ? "保存した物件に条件に合うものはありません。" + v.aside().size() + "件を除外しました。" : "None of the kept listings fits; " + v.aside().size() + " are set aside.")).append('\n');
        int n = 0;
        for (Row r : v.passing()) {
            if (n++ >= max) { b.append(ja ? "ほか" + (v.passing().size() - max) + "件はHomeページにあります。\n" : "and " + (v.passing().size() - max) + " more on the Home page.\n"); break; }
            Home.Listing l = r.listing();
            List<String> notes = notes(r, v.cut());
            b.append("- [").append(l.id(), 0, 6).append("] ").append(l.line()).append(" — ").append(l.source()).append(l.link().isEmpty() ? "" : " " + l.link())
             .append(l.seenOn().isEmpty() ? "" : ja ? "（掲載ページ: " + l.seenOn() + "）" : " (seen on " + possessive(l.seenOnName()) + " page: " + l.seenOn() + ")")
             .append(notes.isEmpty() ? "" : ja ? "（" + String.join("；", notes) + "）" : " (" + String.join("; ", notes) + ")")
             .append(r.reactionWord().isEmpty() ? "" : ja ? (r.reactionWord().equals("like") ? "（気に入り）" : "（見送り）") : " (" + (r.reactionWord().equals("like") ? "liked" : "passed") + ")").append(r.card() == null ? "" : (ja ? " · カード " : " · card ") + HomeCard.state(r.card())).append('\n');
        }
        if (!v.portals().isEmpty()) { b.append(ja ? "ご自身で開くポータル（プログラムでは読めません）：" : "The portals, for you to open (the library cannot read their pages): "); for (String[] p : v.portals()) b.append(p[0]).append(" ").append(p[1]).append(ja ? "；" : "; "); b.append('\n'); }
        return b.toString();
    }

    static String today() { return LocalDate.now().toString(); }
}
