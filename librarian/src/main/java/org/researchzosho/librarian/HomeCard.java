package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The card of a home: what the records say, read by the sensors first, and then a bounded research run on the library's model for what
 * no record answers, the street, the block, the neighbourhood, the press, with the records' facts handed to the run so the model judges
 * only what has no sensor. Filed as a run, like a company card; its report lands on the shelves and on the Dossiers page.
 */
public final class HomeCard {
    private HomeCard() { }
    private static final ObjectMapper M = new ObjectMapper();

    public static final int CARD_MINUTES = 12, CARD_TURNS = 24;
    /** How the research is filed; a test puts its own here. */
    static volatile Function<ObjectNode, String> FILE = HomeCard::fileRun;
    static final ThreadLocal<LibraryStore> STORE = new ThreadLocal<>();
    /** The language of the search a card is filed for, "ja" or "en", for the sensors' lines. */
    static final ThreadLocal<String> LANG = new ThreadLocal<>();
    /** The sensors: the American records, or the Japanese ministry's for a listing in Japan; a test puts its own here. */
    static volatile Function<Home.Listing, HomeSensors.Sheet> SENSORS = l -> l.japan() ? HomeJapanRecords.sheet(STORE.get(), l, LANG.get() == null ? "ja" : LANG.get()) : HomeSensors.sheet(STORE.get(), l);

    static Path dir(LibraryStore store, Home.Search s) { return s.dir(store).resolve("search").resolve("cards"); }
    static Path file(LibraryStore store, Home.Search s, String id) { return dir(store, s).resolve(id + ".json"); }

    public static ObjectNode kept(LibraryStore store, Home.Search s, String id) throws IOException {
        Path f = file(store, s, id);
        return Files.isRegularFile(f) ? (ObjectNode) M.readTree(f.toFile()) : null;
    }

    /** The records read and the run filed for one listing. A sentence for the person. */
    public static String file(LibraryStore store, Home.Search s, Home.Listing l, String patronDid) throws IOException {
        ObjectNode had = kept(store, s, l.id());
        if (had != null && !had.path("job_id").asText("").isEmpty() && had.path("built").asText("").isEmpty()) return "The card of " + l.line() + " is already filed as " + had.path("job_id").asText() + "; the runs page shows how far it has come.";
        HomeCut.Cut cut = HomeCut.kept(store, s);
        String lang = cut == null ? "en" : cut.lang();
        boolean ja = lang.equals("ja");
        STORE.set(store); LANG.set(lang);
        HomeSensors.Sheet sheet;
        try { sheet = SENSORS.apply(l); } finally { STORE.remove(); LANG.remove(); }
        String what = l.japan() ? l.addressWords() : l.address() + (l.place().isEmpty() ? "" : ", " + l.place());
        String tenure = l.tenure().equals("rent") ? "renting" : "buying";
        ObjectNode args = M.createObjectNode();
        args.putObject("patron").put("did", patronDid == null || patronDid.isBlank() ? "person" : patronDid).put("name", "home").put("runtime", "home");
        if (l.japan()) args.put("question", (ja ? what + "を" + (l.tenure().equals("rent") ? "借りる" : "買う") + "人が、その街・周辺について知っておくべきことは何ですか。プログラムが読めるページ（報道、区・市の自身のページ、Wikipediaの町のページ、検索で見つかる不動産会社自身の物件ページ）から読んでください。SUUMO、アットホーム、HOME'Sはプログラムを受け付けないので、そこでの検索結果は名前を挙げるだけにし、ページは読まないでください"
                        + (l.price() > 0 ? "。また希望" + (l.tenure().equals("rent") ? "家賃" : "価格") + l.priceText() + "は、近くの取引価格・地価公示と比べて高いか安いか" : "") + "。"
                        + "ライブラリが" + LocalDate.now() + "に読んだ記録を下に示します（取引価格、地価公示、ハザード情報）。これらは既知のものとして引用し、同じことを検索し直さず、街と周辺について1〜2ページ読むことに時間を使ってください。報告は日本語で書いてください。\n\n"
                        : "What should someone " + tenure + " the home at " + what + " know about the street and the neighbourhood, from pages a program can read: the press, the ward's or city's own pages, Wikipedia's page on the district, and an estate agent's own page for the home when a search finds one (SUUMO, AtHome and HOME'S refuse programs; a result on them is a lead to name, not a page to read)"
                        + (l.price() > 0 ? "; and whether the asking " + l.priceText() + " is high or low against the prices paid nearby and the official land price" : "") + "? "
                        + "THE RECORDS, read by the library on " + LocalDate.now() + " from MLIT's 不動産情報ライブラリ, stand below: the sales nearby, the land price point and the hazard areas. Take them as given, cite them as the library's records, and spend the run reading one or two pages about the district rather than searching again for what the records already say.\n\n")
                + Fence.rule("the records") + "\n" + Fence.wrap("RECORDS", sheet.text()));
        else args.put("question", "What should someone " + tenure + " the home at " + what + " know about the street, the block and the neighbourhood, from pages a program can read: the press, the city's own pages, "
                + "Wikipedia's page on the neighbourhood, and an estate agent's own page for the home when a search finds one (Zillow, Redfin, Realtor.com, Trulia and Apartments.com refuse programs; a result on them is a lead to name, not a page to read)"
                + (l.price() > 0 ? "; and whether the asking " + l.priceText() + " is high or low against what was paid nearby" : "") + "? "
                + "THE RECORDS, read by the library on " + LocalDate.now() + " with their sources, stand below: the flood zone, the schools, the stops, the sales and the permits. Take them as given, cite them as the library's records, "
                + "and spend the run reading one or two pages about the street and the neighbourhood rather than searching again for what the records already say.\n\n"
                + Fence.rule("the records") + "\n" + Fence.wrap("RECORDS", sheet.text()));
        args.put("title", (ja ? "物件カード: " : "Home card: ") + what).put("max_minutes", CARD_MINUTES).put("max_turns", CARD_TURNS);
        ArrayNode subs = args.putArray("sub_questions");
        if (ja) {
            subs.add(what + "の周辺の街はどんなところか：雰囲気、建設・再開発の動き、今年の報道や区・市のページが伝えていること。");
            subs.add(l.price() > 0 ? what + "の希望" + (l.tenure().equals("rent") ? "家賃" : "価格") + l.priceText() + "は、下の記録にある近くの取引価格と地価公示から見て高いか安いか。" : what + "のような物件は今いくらで取引されているか、下の記録と周辺の相場から。");
        } else {
            subs.add("The street and the neighbourhood around " + what + ": what it is like, what is being built or changed there, and what the press and the city's own pages have said of it this year.");
            subs.add((l.price() > 0 ? "Is the asking " + l.priceText() + " for " + what + " high or low against what was paid nearby, as the records below and the area's prices say?" : "What do homes like " + what + " go for in " + (l.place().isEmpty() ? "the area" : l.place()) + " now, as the records below and the area's prices say?"));
        }
        STORE.set(store);
        String id;
        try { id = FILE.apply(args); } finally { STORE.remove(); }
        ObjectNode c = M.createObjectNode().put("id", l.id()).put("job_id", id).put("filed", LocalDate.now().toString()).put("built", "").put("line", l.line());
        c.set("facts", sheet.facts()); c.put("records", sheet.text()); c.put("records_line", sheet.facts().path("records_line").asText(""));
        Files.createDirectories(dir(store, s));
        M.writerWithDefaultPrettyPrinter().writeValue(file(store, s, l.id()).toFile(), c);
        String records = sheet.facts().path("records_line").asText("");
        if (ja) {
            Home.log(store, s, l.line() + "のカードを" + id + "として作成しました：記録を読み（" + sheet.facts().size() + "項目）、残りはライブラリのモデルによる約" + CARD_MINUTES + "分の調査です。" + (records.isEmpty() ? "" : " " + records));
            return l.line() + "のカードを" + id + "として作成しました。記録は読み終え、残りはライブラリのモデルが約" + CARD_MINUTES + "分かけて調べます。進み具合は実行一覧のページに、出来上がったカードはDossiersのページに載ります。" + (records.isEmpty() ? "" : " " + records);
        }
        Home.log(store, s, "The card of " + l.line() + " is filed as " + id + ": the records read (" + sheet.facts().size() + " facts), and a research run on the library's model for the rest, about " + CARD_MINUTES + " minutes." + (records.isEmpty() ? "" : " " + records));
        return "The card of " + l.line() + " is filed as " + id + ": the records are read, and a research run on the library's model takes about " + CARD_MINUTES + " minutes for the rest. The runs page shows how far it has come; the Dossiers page holds the card once it lands." + (records.isEmpty() ? "" : " " + records);
    }

    static String fileRun(ObjectNode args) {
        try { return new LibraryProtocol(STORE.get()).research(args).path("job_id").asText(""); } catch (IOException e) { throw new RuntimeException(e.getMessage(), e); }
    }

    /** The cards of a search, each with where its run stands. */
    public static List<ObjectNode> cards(LibraryStore store, Home.Search s) throws IOException {
        List<ObjectNode> out = new ArrayList<>();
        Path d = dir(store, s);
        if (!Files.isDirectory(d)) return out;
        try (var st = Files.list(d)) {
            for (Path p : st.sorted().toList()) {
                if (!p.getFileName().toString().endsWith(".json")) continue;
                ObjectNode c = (ObjectNode) M.readTree(p.toFile());
                String jid = c.path("job_id").asText("");
                if (!jid.isEmpty() && c.path("built").asText("").isEmpty()) {
                    try {
                        ObjectNode job = new Jobs(store, j -> { throw new IllegalStateException("read only"); }).get(jid);
                        String state = job == null ? "" : job.path("state").asText("");
                        if (state.equals("done")) { c.put("built", LocalDate.now().toString()); M.writerWithDefaultPrettyPrinter().writeValue(p.toFile(), c); }
                        else if (!state.isEmpty() && !state.equals("running") && !state.equals("queued") && !state.equals(Jobs.OFFERED)) c.put("failed", state);
                    } catch (IOException ignored) { }
                }
                out.add(c);
            }
        }
        return out;
    }

    /** Where a card stands, in a few words: none, filed (running), landed, or failed. */
    public static String state(ObjectNode c) {
        if (c == null) return "";
        if (!c.path("failed").asText("").isEmpty()) return "the run " + c.path("failed").asText();
        if (!c.path("built").asText("").isEmpty()) return "landed " + c.path("built").asText();
        return "filed as " + c.path("job_id").asText("") + ", running";
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
