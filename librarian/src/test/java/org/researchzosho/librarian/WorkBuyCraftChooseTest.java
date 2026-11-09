package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.ShoppingProfile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Four goals through the brain's shape (DESIGN_BRAIN §5, stage 3): work, buy, learn-a-craft, and choose for books and podcasts. Each goal's
 * words and decision rules reach the planner and every worker, its report shape reaches the writer and binds the answer to the checks,
 * its instruments are on the table, and its words fire on plain asks in English and Japanese and not on its neighbours'. Nothing here
 * reaches a model or the network: the drive is scripted, the tools are fakes.
 */
class WorkBuyCraftChooseTest {

    static final String WORK = "Find openings for a veterinary technician near Spokane, Washington, at clinics or animal hospitals, and say which employers are worth watching.";
    static final String BUY = "Which electric space heater under $80 should I buy for a small bedroom in the United States? It must switch off if it tips over, and I want to know about recalls.";
    static final String CRAFT = "How do I get into bookbinding from nothing: what do I need to make a simple hardcover notebook, the steps, and where to learn?";
    static final String BOOKS = "Recommend novels about a lighthouse keeper's solitude, slow and quiet in tone, in English, from any era.";
    static final String PODCASTS = "A podcast for someone who likes slow, well-researched history of everyday objects, with episodes under forty minutes, in English, still publishing.";
    static final String HOMES = "Find homes currently for sale in Cambridge, Massachusetts: 2,000 to 4,000 square feet, 3 to 4 bedrooms, under $800 per square foot.";
    static final String PLAIN = "Why did the Saros cycle matter to Babylonian astronomers?";
    static final String LAW = "What does Massachusetts law require of a landlord before raising the rent?";

    static final List<String> WORK_EN = List.of(WORK,
            "Looking for a job as a dental hygienist in Burlington, Vermont.",
            "Which employers near Leeds are hiring warehouse supervisors this month?",
            "Jobs for a newly qualified pharmacist in Perth, with the boards to watch.",
            "I want to update my resume and find openings in sound engineering around Montreal.",
            "Is anyone hiring Japanese-to-English translators for remote work?",
            "Vacancies for a school librarian in Wellington, posted since September.",
            "Where are the job postings for park rangers in Alberta?",
            "Roles at a hospice for a social worker near Galway.",
            "Who is hiring line cooks in Asheville right now?");
    static final List<String> WORK_JA = List.of(
            "札幌で歯科衛生士の求人を探して",
            "仙台で看護師として転職したい。候補の病院と採用情報を",
            "大阪の図書館司書の求人情報と募集中の職場",
            "福岡で保育士の仕事を探しています",
            "通訳の職を探しているが、リモート可の会社は",
            "名古屋の自動車整備士の働き口を教えて",
            "広島で調理師の求職活動をしたい",
            "金沢の工芸職人の採用ページがある工房",
            "新卒で就職するならどの出版社か、採用情報を",
            "神戸でパティシエを募集中の店");
    static final List<String> BUY_EN = List.of(BUY,
            "I'm looking to buy a space heater for a small bedroom, under 80 dollars.",
            "What's the best value USB-C hub for a laptop with one port?",
            "Where can I buy a replacement filter for a 2019 Dyson fan?",
            "Is the Kestrel KH-7 hub worth buying?",
            "Recommend me a rice cooker for two people under ¥15,000.",
            "Shopping for a child's bike helmet: which one, and has any been recalled?",
            "Which is cheaper to run, a heat pump dryer or a vented one, and which should I get?",
            "I need a new winter coat for cycling to work; what should I get under €200?",
            "Compare the three cheapest 27-inch monitors for sale now in Canada.");
    static final List<String> BUY_JA = List.of(
            "ノートPC用のUSBハブ、5000円以下でおすすめは？",
            "一人暮らし向けの炊飯器を買いたい。2万円以内で。",
            "電動ドリルを購入するならどれがいい？",
            "空気清浄機の選び方と、コスパのいい機種を教えて",
            "子ども用のヘルメット、安全なものを買うなら？",
            "どこで買えますか、2019年製の扇風機の交換フィルター",
            "加湿器を買い替えたい、静かなものを",
            "掃除機を比較して、価格と性能で選んで",
            "一万円以下のワイヤレスイヤホンでおすすめの機種",
            "家電量販店とネットではどちらが安い、同じ冷蔵庫で");
    static final List<String> CRAFT_EN = List.of(CRAFT,
            "How do I make a canvas tote bag with an inside pocket?",
            "What do I need to start knitting socks, and where can I learn?",
            "A sewing pattern for a linen apron, and the tools for it.",
            "How to forge a small kitchen knife from an old file.",
            "What materials do I need for silversmithing a plain ring?",
            "How do I build a cold frame for seedlings from scrap wood?",
            "How to weave a medieval tapestry, and what do I need to do it today?",
            "Where can I learn iaido, and what equipment do I need for it?",
            "How to dye wool with onion skins at home.");
    static final List<String> CRAFT_JA = List.of(
            "帆布のトートバッグの作り方と必要な道具",
            "靴下の編み方を一から教えて",
            "藍染めを始めるには何が必要か、必要な材料は",
            "小さな包丁を作るにはどんな工具がいる",
            "キルトの作り方と必要な道具",
            "手作りの革財布に必要な道具と手順",
            "居合道の始め方",
            "陶芸を自宅で始めるには、必要な道具一式",
            "麻のエプロンを手芸で作る手順",
            "裂き織りの織り方と道具");
    static final List<String> CHOOSE_EN = List.of(BOOKS, PODCASTS,
            "Which book on information retrieval should I read first, as a beginner?",
            "A novel like the two I read last month, quieter and shorter.",
            "What should I read next after a run of Arctic exploration memoirs?",
            "Recommend a podcast on the history of the Tokyo subway with episodes under an hour.",
            "Podcasts about early radio, hosted by historians, still publishing.",
            "Which podcast episodes feature a working lighthouse keeper?",
            "A reading list on the Antikythera mechanism for a non-specialist.",
            "Which textbook on tidal harmonics is written for a first-year student?");
    static final List<String> CHOOSE_JA = List.of(
            "灯台守の孤独を描いた静かな小説のおすすめの本は",
            "東京の地下鉄の歴史を扱うポッドキャストで、一話が一時間以内のもの",
            "機械学習のおすすめの本は？",
            "初心者向けの情報検索の入門書",
            "日常の道具の歴史を語るポッドキャストを探している",
            "次に何を読めばいいか、北極探検の手記を読み終えた",
            "潮汐の教科書で一年生向けのもの",
            "ラジオ黎明期についてのポッドキャスト、歴史家が司会のもの",
            "静かで短い小説を、先月読んだ二冊に似たものを",
            "書籍のおすすめ、アンティキティラ島の機械について");
    /** What each goal must not take: the other goals' plain asks, a home, a point of law, an ordinary question, and the words that fooled the old lists. */
    static final List<String> OTHERS = List.of(HOMES, PLAIN, LAW, "How were the Antikythera gears cut?",
            "Why did Disney buy Pixar in 2006?", "The price of oil in 1973 and why it rose.", "How much does Harvard's endowment pay out each year?",
            "The role of iron in the blood, and the position of the moon in the sky.", "I'm hiring a lawyer for my divorce; what does the retainer cover?", "Resume the download where it stopped.",
            "江戸時代の米の価格はいくらだったか", "この方式を採用した理由は何か", "鉄の役割を血液の中で説明して");

    static List<String> neighbours(List<String>... own) {
        List<String> out = new ArrayList<>(OTHERS);
        for (List<String> l : own) out.addAll(l);
        return out;
    }

    static void fires(String goal, List<String> yes, List<String> no) {
        Goal g = Goals.named(goal);
        for (String q : yes) assertTrue(g.appliesTo(q).yes(), goal + " should take: " + q + " — " + g.appliesTo(q).why());
        for (String q : no) assertFalse(g.appliesTo(q).yes(), goal + " should leave: " + q + " — " + g.appliesTo(q).why());
    }

    @Test void workFiresOnPlainAsksInEnglishAndJapaneseAndNotOnItsNeighbours() {
        fires("work", WORK_EN, neighbours(BUY_EN, BUY_JA, CRAFT_EN, CRAFT_JA, CHOOSE_EN, CHOOSE_JA));
        fires("work", WORK_JA, List.of());
        assertEquals(List.of("work"), Goals.forQuestion(WORK).stream().map(Goal::name).toList());
    }

    @Test void buyFiresOnPlainAsksInEnglishAndJapaneseAndNotOnItsNeighbours() {
        fires("buy", BUY_EN, neighbours(WORK_EN, WORK_JA, CRAFT_EN, CRAFT_JA, CHOOSE_EN, CHOOSE_JA));
        fires("buy", BUY_JA, List.of());
        assertEquals(List.of("buy"), Goals.forQuestion(BUY).stream().map(Goal::name).toList());
        ShoppingProfile p = new ShoppingProfile();
        assertTrue(p.applies(BUY) && p.applies("ノートPC用のUSBハブ、5000円以下でおすすめは？") && !p.applies("Why did Disney buy Pixar in 2006?") && !p.applies(HOMES), "the field's own words are the goal's");
    }

    @Test void learnACraftFiresOnPlainAsksInEnglishAndJapaneseAndNotOnItsNeighbours() {
        fires("learn-a-craft", CRAFT_EN, neighbours(WORK_EN, WORK_JA, BUY_EN, BUY_JA, CHOOSE_EN, CHOOSE_JA));
        fires("learn-a-craft", CRAFT_JA, List.of());
        assertEquals(List.of("learn-a-craft"), Goals.forQuestion(CRAFT).stream().map(Goal::name).toList());
    }

    @Test void chooseFiresOnBookAndPodcastAsksInEnglishAndJapaneseAndNotOnItsNeighbours() {
        fires("choose", CHOOSE_EN, neighbours(WORK_EN, WORK_JA, BUY_EN, BUY_JA, CRAFT_EN, CRAFT_JA));
        fires("choose", CHOOSE_JA, List.of());
        assertEquals(List.of("choose"), Goals.forQuestion(BOOKS).stream().map(Goal::name).toList());
        assertEquals(List.of("choose"), Goals.forQuestion(PODCASTS).stream().map(Goal::name).toList());
    }

    /** The planner's prompt, the workers' system prompts and the writer's prompt of a scripted run of the goal. */
    record Seen(String plan, List<String> workers, String writer, List<String> offered) { }

    static Seen run(String question, String goal, LibraryStore store) {
        GoalRunTest.PlanSeen drive = new GoalRunTest.PlanSeen();
        Researcher.Ask ask = new Researcher.Ask(question, "depth", 60, List.of());
        if (goal != null) ask = ask.withGoals(List.of(goal));
        new Researcher(drive, drive, new ResearcherTest.FakeTools(), s -> { }, 1, store).run(ask, "");
        List<String> workers = new ArrayList<>(); String writer = "";
        for (ArrayNode h : drive.histories) {
            String sys = h.get(0).path("content").asText();
            if (sys.contains("Then call done.")) writer = sys; else workers.add(sys);
        }
        List<String> offered = List.of();
        for (List<String> o : drive.offered) if (o.contains("note")) { offered = o; break; }
        return new Seen(drive.plans.isEmpty() ? "" : drive.plans.get(0), workers, writer, offered);
    }

    static void reaches(Seen s, String goal) {
        Goal g = Goals.named(goal);
        String block = "THIS RUN SERVES THE GOAL " + goal + ": " + g.words() + " " + g.decisionRules();
        assertTrue(s.plan().contains(block), "the planner reads the goal's words and decision rules: " + s.plan());
        assertFalse(s.workers().isEmpty(), "the run had workers");
        for (String w : s.workers()) assertTrue(w.contains(block), "every worker reads the goal's words and decision rules: " + w);
        assertTrue(s.writer().contains("THE REPORT SERVES THE GOAL " + goal + ": " + g.shape()), "the writer reads the goal's report shape: " + s.writer());
    }

    @Test void workReachesThePlannerEveryWorkerAndTheWriterWithTheBoardOnTheTable(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Seen s = run(WORK, null, store);   // by its words alone: the person did not name the goal
        reaches(s, "work");
        assertTrue(s.plan().contains("This is a question about work: openings, employers or boards."), "the career field's own plan rules join by the goal's words: " + s.plan());
        assertTrue(s.offered().contains("board") && s.offered().contains("search"), "the board and the search are on the table: " + s.offered());
        String shape = Goals.named("work").shape();
        assertTrue(shape.contains("says which boards were read whole and which could not be read") && shape.contains("whether the posting states it or it was not checked") && shape.contains("an opening the run did not read is not a fit"), "the shape binds the answer to what was read: " + shape);
        String rules = Goals.named("work").decisionRules();
        assertTrue(rules.contains("enumerated, never an engine's first page") && rules.contains("never taken from a board's featured, top or sponsored slice") && rules.contains("ordered by fit to what the person asked, never by a board's own order"), rules);
    }

    @Test void buyReachesThePlannerEveryWorkerAndTheWriterWithTheShopsAndTheRecallsOnTheTable(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Seen s = run(BUY, null, store);
        reaches(s, "buy");
        assertTrue(s.plan().contains("This is a shopping question."), "the shopping field's plan rules join: " + s.plan());
        assertTrue(s.offered().contains("product_safety") && s.offered().contains("search") && s.offered().contains("my_shelf"), "the recall feeds, the search with its shops and the person's own shelf are on the table: " + s.offered());
        assertEquals("product_search", Places.named("shops").tools("a thing to buy", null).get(0).name(), "the shops are a place the search reaches");
        String shape = Goals.named("buy").shape();
        assertTrue(shape.contains("says of each candidate which checks were made") && shape.contains("\"no notice on file\", never safe") && shape.contains("a shop the library could not read is a link, never a price"), shape);
        String rules = Goals.named("buy").decisionRules();
        assertTrue(rules.contains("never a shop's best sellers, top rated, sponsored or choice slice") && rules.contains("price_max and page") && rules.contains("a web hit is a lead whose own listing page is then opened") && rules.contains("cues to mention, never a reason"), rules);
    }

    @Test void learnACraftReachesThePlannerEveryWorkerAndTheWriterWithTheLookOnTheTable(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Seen s = run(CRAFT, null, store);
        reaches(s, "learn-a-craft");
        assertTrue(s.plan().contains("This is a crafts question."), "the crafts field's plan rules join: " + s.plan());
        assertTrue(s.offered().contains("look") && s.offered().contains("search") && s.offered().contains("open"), "the look, the search and the open are on the table: " + s.offered());
        assertEquals(List.of("look"), Goals.named("learn-a-craft").instruments());
        String shape = Goals.named("learn-a-craft").shape();
        assertTrue(shape.contains("names the instruction, pattern, manual or video it was read from") && shape.contains("said to stand unverified") && shape.contains("names first what the person asked for that was not checked"), shape);
        String rules = Goals.named("learn-a-craft").decisionRules();
        assertTrue(rules.contains("never by views, likes, subscribers or a tutorial's rank on the engine") && rules.contains("read with look when the steps or the sizes are on screen") && rules.contains("each material by its working property, never by a brand alone"), rules);
    }

    @Test void chooseReachesThePlannerEveryWorkerAndTheWriterForBooksAndForPodcasts(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Seen books = run(BOOKS, null, store);
        reaches(books, "choose");
        assertTrue(books.plan().contains("This is a books question."), "the books field's plan rules join: " + books.plan());
        assertTrue(books.offered().contains("search") && books.offered().contains("shelf_search") && books.offered().contains("my_shelf"), "the search with its catalogues, the shelves and the person's own shelf are on the table: " + books.offered());
        assertEquals("book_search", Places.named("books").tools("a book", null).get(0).name());
        assertFalse(books.offered().contains("candidates") && !books.offered().contains("web_search"), "a books ask keeps the web search: the video pool is for a remembered video");
        Seen shows = run(PODCASTS, null, store);
        reaches(shows, "choose");
        assertTrue(shows.plan().contains("This is a question about what is said on a podcast"), "the podcasts field's plan rules join: " + shows.plan());
        assertEquals("podcast_search", Places.named("podcasts").tools("a show", null).get(0).name());
        String shape = Goals.named("choose").shape();
        assertTrue(shape.contains("A book: its catalogue record read with book_search kind=work") && shape.contains("a show: its feed read with podcast_search kind=episodes")
                && shape.contains("says which records and feeds were read and which candidates stand on a directory entry alone") && shape.contains("never calls a candidate a fit on a chart, a rating or a count"), shape);
        String rules = Goals.named("choose").decisionRules();
        assertTrue(rules.contains("A book's pool is enumerated") && rules.contains("paged through with page until it gives no more") && rules.contains("never the catalogue's relevance order, its ratings or its edition counts")
                && rules.contains("A show's pool is every row the directory holds") && rules.contains("never its trending list and never a browse by popularity") && rules.contains("never by its chart position, its subscribers"), rules);
        // the video side is untouched: its words stand as the video tests pin them
        assertTrue(rules.contains("begins with candidates") && rules.contains("Then sift") && shape.contains("When more than one candidate has every remembered thing found"));
    }

    @Test void anOrdinaryRunCarriesNoneOfTheFourGoals(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Seen s = run(PLAIN, null, store);
        for (String w : s.workers()) assertFalse(w.contains("THIS RUN SERVES THE GOAL"), w);
        assertFalse(s.writer().contains("THE REPORT SERVES THE GOAL"));
    }

    static CareerSources.Opening opening(String title, String town) {
        return new CareerSources.Opening("id-" + title.hashCode(), "Heron Animal Hospital", title, town, false, "https://jobs.example.org/" + title.hashCode(), "2026-10-30", "", "board", "", "", "");
    }

    @Test void theBoardIsReadWholeAndCutByTheTitleWordsNeverByItsFirstPage() {
        List<CareerSources.Opening> all = new ArrayList<>();
        for (int i = 1; i <= 70; i++) all.add(opening("Receptionist " + i, "Spokane, WA"));
        all.add(opening("Veterinary Technician, nights", "Spokane, WA"));
        all.add(opening("Lead Vet Tech", "Spokane Valley, WA"));
        String cut = Instruments.boardText("Heron Animal Hospital", all, "", List.of("Spokane, WA"), List.of("veterinary technician", "vet tech"));
        assertTrue(cut.startsWith("Heron Animal Hospital: 72 opening(s) on its board in Spokane, WA; 2 with veterinary technician or vet tech in the title, listed here; the other 70 are not of those words."), cut);
        assertTrue(cut.contains("Veterinary Technician, nights · Spokane, WA · posted 2026-10-30 · https://jobs.example.org/") && cut.contains("Lead Vet Tech · Spokane Valley, WA"), "the two wanted titles, at the end of the board, are the two listed: " + cut);
        assertFalse(cut.contains("Receptionist"), "the seventy other titles are counted, not listed: " + cut);
        String whole = Instruments.boardText("Heron Animal Hospital", all, "", List.of(), List.of());
        assertTrue(whole.contains("Receptionist 40") && !whole.contains("Receptionist 41") && whole.contains("… and 32 more; give words to cut the board to the titles wanted"), "with no words the first forty are listed and the rest counted, with the way to cut: " + whole);
        String none = Instruments.boardText("Heron Animal Hospital", all, "", List.of(), List.of("surgeon"));
        assertTrue(none.contains("none of the 72 titles holds surgeon"), none);
    }
}
