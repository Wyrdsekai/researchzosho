package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A fresh start takes away the family reader's own drafts and what only they were about. Everything else stays, and a copy is kept. */
class FamilyResetTest {

    private static FamilyAccount.Fact fact(String s, String r, String o) { return new FamilyAccount.Fact(s, r, o, "", "q"); }

    @Test
    void aResetWritesTheListOfPeopleWithTodaysHeading(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        // nodes.md as a 0.5.0 development build wrote it, with the heading that described the per-person marks
        Files.createDirectories(Graph.nodesFile(store).getParent());
        Files.writeString(Graph.nodesFile(store), "# Graph nodes — the things the findings are about\n\n"
                + "One per line: `- <id> — <kind>: <label> | also: other names | wikidata: Qn`. Curated here; every other\n"
                + "node is computed from the findings' triples. `private` in the kind marks a person who may be living, by the\n"
                + "dates; `kept private` and `kept public` are the owner's own decision, which the dates never change. `said to be\n"
                + "living` and `said to have died` are what a source said without a date, which the dates do not undo either. A private\n"
                + "node is never shown to another patron. Merges are the person's act: `researchzosho graph merge`.\n\n"
                + "- tom-ellis — person, private, kept private: Tom Ellis | also: Thomas Ellis\n"
                + "- ann-ellis — person, kept public: Ann Ellis\n");
        FamilyReset.apply(store, new FamilyReset.Plan(List.of(), 0, 0, 0, "letter.txt"));
        String nodes = Files.readString(Graph.nodesFile(store));
        assertTrue(nodes.startsWith(Graph.NODES_PREAMBLE), nodes);
        assertFalse(nodes.contains("never shown to another patron") || nodes.contains("kept private"), nodes);
        assertTrue(nodes.contains("- tom-ellis — person: Tom Ellis | also: Thomas Ellis"), nodes);
        assertFalse(nodes.contains("ann-ellis"), "an entry no fact mentions, with nothing of the owner's on it, goes with a reset of any size: " + nodes);
    }

    /**
     * A read that was stopped halfway writes its people and families before their facts, and a reset that could not tie them to its page leaves
     * them: what no fact mentions any more goes with a reset of any size, and the plan names it first. The owner's own stays: another name with
     * no source written down, a Wikidata id.
     */
    @Test
    void entriesNothingSpeaksOfGoWithAResetOfAnySize(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田勇", "parent-of", "森田一郎")), List.of()), "file:///home/me/notes.txt", "an aunt");
        // a name in Latin letters the evidence links into a name in characters (the reading on file, the same birth year): its facts are its own, and its line stays
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田健二", "born-on", "1905"), fact("Kenji Morita", "born-on", "1905")), List.of()), "file:///home/me/letters.txt", "a cousin");
        Graph.alias(store, "森田健二", List.of("もりた けんじ"));
        assertEquals(FamilyPeople.view(store).nodeIdOf("森田健二"), FamilyPeople.view(store).nodeIdOf("Kenji Morita"), "linked by the reading");
        Path nodes = Graph.nodesFile(store);
        Files.createDirectories(nodes.getParent());
        String had = Files.exists(nodes) ? Files.readString(nodes) : Graph.NODES_PREAMBLE;
        Files.writeString(nodes, (had.endsWith("\n") ? had : had + "\n") + "- 遠藤 family — family: 遠藤 family | also: 遠藤家\n- 遠藤三郎 — person: 遠藤三郎\n"
                + "- 遠藤花子 — person: 遠藤花子 | also: Hanako Endo\n- 遠藤正一 — person: 遠藤正一 | wikidata: Q1\n");
        FamilyReset.Plan plan = FamilyReset.plan(store, List.of("family-account"), "https://example.org/a-page-taken-back-before");
        assertEquals(List.of(), plan.claims(), "the words match no file or page");
        assertEquals(List.of("遠藤 family", "遠藤三郎"), plan.leftOver(), "what nothing speaks of, with nothing of the owner's on it");
        FamilyReset.Done done = FamilyReset.apply(store, plan);
        assertEquals(2, done.nodes());
        String left = Files.readString(nodes);
        assertFalse(left.contains("遠藤 family") || left.contains("遠藤三郎"), left);
        assertTrue(left.contains("- 遠藤花子 — person: 遠藤花子 | also: Hanako Endo") && left.contains("- 遠藤正一 — person: 遠藤正一 | wikidata: Q1"), "the owner's own stays: " + left);
        Graph g = Graph.build(store);
        assertNotNull(g.node(g.nodeIdOf("森田勇")), "what a fact mentions stays");
        assertTrue(left.contains("- kenji morita — person: Kenji Morita"), "a name the links join into another still has facts written under it: " + left);
        assertEquals(List.of(), FamilyReset.plan(store, List.of("family-account"), "https://example.org/a-page-taken-back-before").leftOver(), "nothing is left over now");
    }

    @Test
    void onlyTheReadersOwnDraftsGo(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(new FamilyAccount.Person("森田勇", "", List.of("Isamu Morita"))),
                List.of(fact("森田勇", "parent-of", "森田一郎"), fact("森田勇", "born-in", "津"), fact("森田一郎", "married-to", "森田ふさ")), List.of()), "file:///n.txt", "an aunt");
        List<Finding> all = store.scanFindings().findings();
        Finding accepted = all.stream().filter(f -> f.title().contains("married")).findFirst().orElseThrow();
        new Council(store).accept(accepted.id());
        store.frontier("asked me", "an unrelated question about something else entirely");
        store.frontier("person me (from the family tree)", "森田勇: what records exist for this person?");
        FamilyReset.Plan plan = FamilyReset.plan(store, List.of("family-account", "gedcom-import"));
        assertEquals(2, plan.claims().size());
        assertEquals(1, plan.kept());
        FamilyReset.Done done = FamilyReset.apply(store, plan);
        assertEquals(2, done.claims());
        assertEquals(1, done.waiting());
        assertEquals(List.of("an unrelated question about something else entirely"), Frontier.read(store).stream().filter(Frontier.Line::open).map(Frontier.Line::text).toList(), "a question that did not come from the family tree stays");
        assertEquals(1, store.scanFindings().findings().size(), "the accepted marriage stays");
        assertEquals(2, Files.list(done.backup().resolve("findings")).count(), "a copy of what went is kept");
        Graph g = Graph.build(store);
        assertNull(g.node(g.nodeIdOf("津")), "a place only the drafts spoke of is gone");
        assertNotNull(g.node(g.nodeIdOf("森田ふさ")), "the people of an accepted fact stay");
        assertFalse(Files.readString(Graph.nodesFile(store)).contains("Isamu Morita"), "the other names the reader gave are gone with the drafts");
    }

    @Test
    void whatOneFilePutInIsTakenBackAndTheRestStays(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田勇", "parent-of", "森田一郎")), List.of()), "file:///home/me/notes.txt", "an aunt");
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("Bishop Hale", "born-in", "Leeds"), fact("Doctor Reed", "born-in", "York")), List.of()), "file:///home/me/Town%20History.epub", "a book");
        FamilyReset.Plan plan = FamilyReset.plan(store, List.of("family-account"), "town history");
        assertEquals(2, plan.claims().size());
        FamilyReset.apply(store, plan);
        List<String> left = store.scanFindings().findings().stream().map(Finding::title).toList();
        assertEquals(1, left.size(), left.toString());
        assertTrue(left.get(0).contains("森田勇"), left.toString());
        assertNull(Graph.build(store).node(Graph.build(store).nodeIdOf("Leeds")));
    }

    @Test
    void aPageIsFoundByItsAddressWrittenEitherWay(@TempDir Path tmp) throws Exception {
        // a page a search engine returned was read as the family's, and its read never finished, so no list of reads names it: the reset
        // still finds its claims by their source, whether the address is given as a browser copies it (percent-encoded) or in characters
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String encoded = "https://zh.wikipedia.org/wiki/%E6%9F%B4%E7%94%B0%E5%8B%9D%E8%B1%8A";
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("柴田勝豊", "born-in", "尾張"), fact("柴田勝豊", "married-to", "お市")), List.of()), encoded, "an encyclopaedia");
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("森田勇", "parent-of", "森田一郎")), List.of()), "file:///home/me/notes.txt", "an aunt");
        Graph.alias(store, "柴田勝豊", List.of("Shibata Katsutoyo", "権六"), encoded);   // the other names the reader gave, from that page
        assertEquals(2, FamilyReset.plan(store, List.of("family-account"), encoded).claims().size(), "the address as copied from a browser");
        assertEquals(2, FamilyReset.plan(store, List.of("family-account"), "https://zh.wikipedia.org/wiki/柴田勝豊").claims().size(), "the address in characters");
        FamilyReset.apply(store, FamilyReset.plan(store, List.of("family-account"), encoded));
        List<String> left = store.scanFindings().findings().stream().map(Finding::title).toList();
        assertEquals(1, left.size(), left.toString());
        assertTrue(left.get(0).contains("森田勇"), left.toString());
        assertNull(Graph.build(store).node(Graph.build(store).nodeIdOf("柴田勝豊")), "the people only that page named are gone");
        assertFalse(Files.readString(Graph.nodesFile(store)).contains("柴田勝豊"), "and so is the entry with the other names that page gave");
    }

    @Test
    void entriesOnlyAPageGaveGoEvenWhenItsFactsAreGoneAlready(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        String encoded = "https://zh.wikipedia.org/wiki/%E6%9F%B4%E7%94%B0%E5%8B%9D%E8%B1%8A";
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(fact("柴田勝豊", "born-in", "尾張")), List.of()), encoded, "an encyclopaedia");
        Graph.alias(store, "柴田勝豊", List.of("Shibata Katsutoyo"), encoded);
        // the facts go first by another way (a reset that reached the claims but not the entries); the entry with its other name stands
        for (Finding f : store.scanFindings().findings()) new Council(store).retire(f.id());
        Graph.setKind(store, "柴田勝豊", "person");
        assertTrue(Files.readString(Graph.nodesFile(store)).contains("柴田勝豊"));
        FamilyReset.Plan plan = FamilyReset.plan(store, List.of("family-account"), encoded);
        assertFalse(plan.files().isEmpty(), "the page is found through where the other name came from");
        FamilyReset.apply(store, plan);
        assertFalse(Files.readString(Graph.nodesFile(store)).contains("柴田勝豊"), "the entry only that page gave is gone");
    }

    @Test
    void aListOfWebAddressesTakesBackThePagesItLedTo(@TempDir Path tmp) throws Exception {
        // the facts a list's pages gave cite the pages, never the list: the words that name the list take back each page it led to, and the
        // list itself, so a new read of its folder reads it again; a page read by itself and the notes stay
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path folder = tmp.resolve("family"), list = folder.resolve("links.txt"), old = folder.resolve("old-links.txt");
        Files.createDirectories(folder);
        String hale = "https://example.org/wiki/Bishop_Hale", reed = "https://example.org/wiki/Doctor%20Reed", ellis = "https://example.org/wiki/Tom_Ellis", york = "https://example.org/wiki/York_Directory";
        Files.writeString(list, hale + "\n" + reed + "\n");
        Files.writeString(old, ellis + "\n");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Bishop Hale", "born-in", "Leeds")), List.of()), hale, "an encyclopaedia");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Doctor Reed", "born-in", "York")), List.of()), reed, "an encyclopaedia");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Ellis", "born-in", "Selby")), List.of()), ellis, "an encyclopaedia");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Ann Hart", "born-in", "Hull")), List.of()), york, "a directory");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Ann Hart", "parent-of", "Kimie Hart")), List.of()), "file://" + folder.resolve("notes.txt"), "an aunt");
        FamilyReads.readFile(store, list, FamilyReads.LIST, folder.toString(), "", "");
        FamilyReads.readAddress(store, hale, FamilyReads.PAGE, list.toString(), "", 0, "");
        FamilyReads.readAddress(store, reed, FamilyReads.PAGE, list.toString(), "", 0, "");
        FamilyReads.readAddress(store, york, FamilyReads.PAGE, "", "", 0, "");
        // a Geni profile in the list, and the owner's note beside it, which names the same profile
        String geni = "https://www.geni.com/people/Bishop-Hale/6000000012345678901";
        Files.writeString(list, hale + "\n" + reed + "\n" + geni + "\n");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Bishop Hale", "died-in", "Ripon")), List.of()), geni, "Geni");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Bishop Hale", "occupation", "bishop")), List.of()), "told://link-note/" + geni + "?through=6000000012345678902", "the owner of this library");
        FamilyReads.readAddress(store, geni, FamilyReads.GENI, list.toString(), "", 1, "");
        Path ledger = FamilyReads.ledger(store);
        // an older version wrote only the list's own line, not its pages: the list names them while it is still there
        Files.writeString(folder.resolve("notes.txt"), "Ann Hart is my aunt. She kept the links.\n");
        Files.writeString(ledger, "c3\t2026-09-20\t" + old + "\nd4\t2026-09-20\t" + folder.resolve("notes.txt") + "\n", StandardOpenOption.APPEND);

        FamilyReset.Plan plan = FamilyReset.plan(store, List.of("family-account"), "links.txt");
        assertEquals(3, plan.claims().size(), plan.files().toString());
        assertTrue(plan.files().contains(list.toString()), plan.files().toString());
        assertTrue(plan.files().stream().noneMatch(x -> x.startsWith("told://")), "the owner's note beside a link stays: " + plan.files());
        FamilyReset.apply(store, plan);
        assertEquals(List.of("Hull", "Kimie Hart", "Selby", "bishop"), store.scanFindings().findings().stream().map(f -> f.triple().object()).sorted().toList());
        String reads = Files.readString(ledger);
        assertFalse(reads.contains(list.toString() + "\n") || reads.contains("Bishop_Hale") || reads.contains("Doctor%20Reed"), "the list and its pages are no longer read");
        assertTrue(reads.contains(york) && reads.contains(old.toString()), "the page read by itself and the other list still are");

        assertEquals(List.of(old.toString()), FamilyReset.listsNamed(store, "old-links.txt"));
        assertEquals(1, FamilyReset.plan(store, List.of("family-account"), "old-links.txt").claims().size(), "the page the older list names");
        assertEquals(List.of(), FamilyReset.listsNamed(store, "notes.txt"), "a file of notes is no list");
    }

    @Test
    void aFileReadOnWindowsIsNamedAsWindowsWritesIt(@TempDir Path tmp) throws Exception {
        // a Windows file's address is file:///C:/…: its path has the drive letter first, with no slash before it, and the words after
        // --from still find the file by its own name
        assertEquals("C:/Users/me/aunt/tree.ged", FamilyReads.decoded("file:///C:/Users/me/aunt/tree.ged"));
        assertEquals("C:\\Users\\me\\aunt\\tree.ged", FamilyReads.decoded("file:///C:\\Users\\me\\aunt\\tree.ged"));
        assertEquals("/home/me/aunt/tree.ged", FamilyReads.decoded("file:///home/me/aunt/tree.ged"));
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Ann Hart", "parent-of", "Kimie Hart")), List.of()), "file:///C:/Users/me/aunt/tree.ged", "a tree file");
        FamilyReset.Plan plan = FamilyReset.plan(store, List.of("family-account"), "tree.ged");
        assertEquals(List.of("C:/Users/me/aunt/tree.ged"), plan.files());
        assertEquals(1, plan.claims().size());
    }

    @Test
    void aOneFileResetTakesTheOtherNamesOnlyThatFileGaveFromAPersonAnotherSourceKeeps(@TempDir Path tmp) throws Exception {
        // an older reading of the memoir filed its editor's name as the writer's; the notes give him another name of his own
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Morita Kenji", "born-in", "Leeds")), List.of()), "file:///home/me/memoir.epub", "a book");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Morita Kenji", "occupation", "farmer")), List.of()), "file:///home/me/notes.txt", "an aunt");
        Graph.alias(store, "Morita Kenji", List.of("Tom Hale"), "file:///home/me/memoir.epub");
        Graph.alias(store, "Morita Kenji", List.of("Ken Morita"), "file:///home/me/notes.txt");
        FamilyReset.apply(store, FamilyReset.plan(store, List.of("family-account"), "memoir.epub"));
        String nodes = Files.readString(Graph.nodesFile(store)), sources = Files.readString(Graph.aliasSourcesFile(store));
        assertFalse(nodes.contains("Tom Hale") || sources.contains("Tom Hale"), "the name only the memoir gave goes with it: " + nodes + sources);
        assertTrue(nodes.contains("Ken Morita") && sources.contains("Ken Morita"), "the notes' name stays: " + nodes + sources);
    }

    /** One library, the same each time: a notes file, and a book whose facts go with a reset of it, one of them also in the notes. */
    private static LibraryStore twin(Path dir) throws Exception {
        LibraryStore store = new LibraryStore(dir); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Ann Hart", "parent-of", "Kimie Hart"), fact("Tom Ellis", "born-in", "York")), List.of()), "file:///home/me/notes.txt", "an aunt");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Bishop Hale", "born-in", "Leeds"), fact("Doctor Reed", "born-in", "Hull"), fact("Tom Ellis", "born-in", "York")), List.of()), "file:///home/me/Town%20History.epub", "a book");
        Graph.alias(store, "Bishop Hale", List.of("Bishop of Leeds"), "file:///home/me/Town%20History.epub");
        Path ledger = FamilyReads.ledger(store);
        Files.createDirectories(ledger.getParent());
        Files.writeString(ledger, "a1\t2026-09-20\t/home/me/notes.txt\nb2\t2026-09-20\t/home/me/Town History.epub\n");
        return store;
    }

    /** What a reset leaves that a person would see: each fact with its sources, the list of people, and the list of reads. */
    private static String state(LibraryStore store) throws Exception {
        List<String> out = new ArrayList<>();
        for (Finding f : store.scanFindings().findings())
            out.add(f.id() + " " + f.state() + " " + f.triple().subject() + "/" + f.triple().predicate() + "/" + f.triple().object() + " " + f.sources().stream().map(Finding.Source::locator).sorted().toList());
        out.sort(null);
        for (String l : Files.readAllLines(Graph.nodesFile(store))) if (l.startsWith("- ")) out.add(l);
        out.addAll(Files.readAllLines(FamilyReads.ledger(store)));
        return String.join("\n", out);
    }

    @Test
    void aResetStoppedPartwayIsFinishedByTheNextCommandAsIfItHadNeverStopped(@TempDir Path tmp) throws Exception {
        LibraryStore whole = twin(tmp.resolve("whole")), stopped = twin(tmp.resolve("stopped"));
        FamilyReset.apply(whole, FamilyReset.plan(whole, List.of("family-account"), "town history"));

        // the same reset, stopped after its journal was written, one fact taken out and another cut short while it was being written
        FamilyReset.Plan plan = FamilyReset.plan(stopped, List.of("family-account"), "town history");
        assertEquals(2, plan.claims().size());
        assertEquals(1, plan.thinned().size());
        Path backup = FamilyReset.begin(stopped, plan);
        Path gone = stopped.findingsDir().resolve(plan.claims().get(0) + ".md"), cut = stopped.findingsDir().resolve(plan.thinned().get(0) + ".md");
        Files.copy(gone, backup.resolve("findings").resolve(gone.getFileName()));
        Files.delete(gone);
        Files.copy(cut, backup.resolve("findings").resolve(cut.getFileName()));
        String text = Files.readString(cut);
        Files.writeString(cut, text.substring(0, text.length() / 3));
        assertNotEquals(state(whole), state(stopped));

        String said = FamilyReset.finishStopped(stopped);
        assertTrue(said.contains("was stopped on") && said.contains("It is finished now"), said);
        assertEquals(state(whole), state(stopped), "the stopped reset, finished, leaves what the whole one left");
        assertTrue(Files.exists(backup.resolve(FamilyReset.FINISHED)));
        assertEquals("", FamilyReset.finishStopped(stopped), "a finished reset is not finished again");
        assertEquals("", FamilyReset.finishStopped(whole), "nor is one that never stopped");
    }

    @Test
    void aCopyAnOlderVersionSavedWithoutAJournalIsLeftAlone(@TempDir Path tmp) throws Exception {
        LibraryStore store = twin(tmp.resolve("lib"));
        Files.createDirectories(store.root().resolve("family").resolve("backup-20260920-101010").resolve("findings"));
        String before = state(store);
        assertEquals("", FamilyReset.finishStopped(store));
        assertEquals(before, state(store));
    }

    private static FamilyAccount.Fact dated(String s, String r, String o, String date) { return new FamilyAccount.Fact(s, r, o, date, s + " " + r + " " + o); }

    @Test
    void aFactAnotherSourceAlsoGivesStaysWithThatSourceWhenOneFileIsTakenBack(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(dated("Tom Ellis", "born-in", "York", "1900")), List.of()), "file:///home/me/notes.txt", "an aunt");
        Path ged = tmp.resolve("tree.ged");
        Files.writeString(ged, "0 HEAD\n0 @I1@ INDI\n1 NAME Tom /Ellis/\n1 BIRT\n2 DATE 1900\n2 PLAC York\n1 DEAT\n2 DATE 1970\n2 PLAC Leeds\n0 TRLR\n");
        Gedcom.importFile(store, ged);
        Finding born = store.scanFindings().findings().stream().filter(f -> f.title().contains("born in York")).findFirst().orElseThrow();
        assertEquals(2, born.sources().size(), "the tree file backs the notes' fact as a further source");
        FamilyReset.Plan plan = FamilyReset.plan(store, List.of("family-account", "gedcom-import"), "tree.ged");
        assertEquals(1, plan.claims().size(), "only the death, which the tree file alone gives, goes: " + plan);
        assertEquals(List.of(born.id()), plan.thinned());
        FamilyReset.Done done = FamilyReset.apply(store, plan);
        assertEquals(1, done.claims());
        assertEquals(1, done.thinned());
        Finding kept = store.finding(born.id());
        assertNotNull(kept, "the fact the notes gave stays");
        assertEquals(List.of("file:///home/me/notes.txt"), kept.sources().stream().map(Finding.Source::locator).toList());
        assertTrue(kept.notes().stream().noneMatch(n -> n.kind().equals("source-added") && n.text().contains("tree.ged")), kept.notes().toString());
        assertTrue(store.scanFindings().findings().stream().noneMatch(f -> f.title().contains("died")), "the death only the tree file gave is gone");
    }

    @Test
    void aFreshStartKeepsAFamilyFactThatAResearchRunFoundAgain(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(dated("Tom Ellis", "born-in", "York", "1900"), dated("Tom Ellis", "occupation", "miner", "")), List.of()), "file:///home/me/community-history.pdf", "a book");
        Finding born = store.scanFindings().findings().stream().filter(f -> f.title().contains("born")).findFirst().orElseThrow();
        List<Finding.Source> both = new ArrayList<>(born.sources());
        both.add(new Finding.Source("https://example.org/york-baptisms-1900", "n/a", "corroborates, from I-0007"));
        store.write(new Finding(born.id(), born.title(), born.subjects(), born.state(), born.claimType(), born.confidence(), born.writer(), born.recordedAt(), born.validAsOf(),
                born.volatility(), born.reviewBy(), both, born.supersedes(), born.review(), born.body(), born.triple(), born.notes()));
        FamilyReset.Plan all = FamilyReset.plan(store, List.of("family-account", "gedcom-import"));
        assertEquals(1, all.claims().size(), "the occupation, which only the book gives, goes");
        assertEquals(List.of(born.id()), all.thinned(), "the birth a run found in a register stays");
        FamilyReset.apply(store, all);
        assertEquals(List.of("https://example.org/york-baptisms-1900"), store.finding(born.id()).sources().stream().map(Finding.Source::locator).toList());
        // taken back by the book's name, the same
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(dated("Tom Ellis", "born-in", "York", "1900")), List.of()), "file:///home/me/community-history.pdf", "a book");
        FamilyReset.Plan one = FamilyReset.plan(store, List.of("family-account", "gedcom-import"), "community-history");
        assertTrue(one.claims().isEmpty(), one.toString());
        assertEquals(List.of(born.id()), one.thinned());
    }

    @Test
    void aResetKeepsTheLineOfANameJoinedIntoAnotherPerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(dated("Emma J. Hart", "lived-in", "Leeds", "2015")), List.of()), "file:///family/told-20260920.md", "you");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(dated("Emma Hart", "born-in", "York", "1988")), List.of()), "file:///family/notes.txt", "an aunt");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(dated("Bishop Hale", "born-in", "Hull", "1850")), List.of()), "file:///family/community-history.pdf", "a book");
        Graph.merge(store, "Emma J. Hart", "Emma Hart", "person");
        FamilyReset.apply(store, FamilyReset.plan(store, List.of("family-account", "gedcom-import"), "community-history.pdf"));
        assertTrue(Files.readString(Graph.nodesFile(store)).contains("- emma j. hart — person: Emma J. Hart"), "a book that never names her leaves her line: " + Files.readString(Graph.nodesFile(store)));
        Graph.unmerge(store, "Emma J. Hart", null, "person", "");
        Graph g = Graph.build(store);
        Graph.Node emma = g.node(g.nodeIdOf("Emma J. Hart"));
        assertEquals("person", emma.kind());
        assertTrue(emma.mayBeLiving(), "she may still be living after the unmerge: " + emma);
        assertNull(Graph.build(store).node("bishop hale"), "what only the book was about is gone");
        // the full reset leaves a joined name's line too
        Graph.merge(store, "Emma J. Hart", "Emma Hart", "person");
        FamilyReset.apply(store, FamilyReset.plan(store, List.of("family-account", "gedcom-import")));
        assertTrue(Files.readString(Graph.nodesFile(store)).contains("- emma j. hart — person"), Files.readString(Graph.nodesFile(store)));
    }

    @Test
    void factsAFileNoLongerSaysAreCountedApartFromWhatThePersonDecided(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(dated("Tom Ellis", "born-in", "York", "1900"), dated("Tom Ellis", "occupation", "miner", "")), List.of()), "file:///home/me/tree.txt", "a tree");
        List<Finding> all = store.scanFindings().findings();
        Finding a = all.get(0), b = all.get(1);
        store.write(new Finding(a.id(), a.title(), a.subjects(), Finding.State.superseded, a.claimType(), a.confidence(), a.writer(), a.recordedAt(), a.validAsOf(),
                a.volatility(), a.reviewBy(), a.sources(), a.supersedes(), a.review(), a.body(), a.triple(), a.notes()));
        new Council(store).accept(b.id());
        FamilyReset.Plan plan = FamilyReset.plan(store, List.of("family-account"), "tree.txt");
        assertEquals(1, plan.kept(), "the one the person accepted");
        assertEquals(1, plan.replaced(), "the one a later copy replaced, counted apart");
    }

    @Test
    void whatTheOwnerSplitOffStaysSplitWhenTheTextIsReadAgainAndWhenItsFileIsReset(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        // the notes name the grandfather and, as the same name, a cousin who lives in Leeds; the owner moves the cousin's facts to a name of his own
        FamilyAccount.Read notes = new FamilyAccount.Read(List.of(), List.of(fact("Tom Ellis", "born-on", "1920"), fact("Tom Ellis", "lived-in", "Leeds"), fact("Tom Ellis", "relative-of", "Mari Ellis")), List.of());
        FamilyAccount.file(store, notes, "file:///home/me/notes.txt", "an aunt");
        List<String> cousin = store.scanFindings().findings().stream().filter(f -> !f.triple().predicate().equals("born-on")).map(Finding::id).toList();
        assertEquals(2, FamilySplit.split(store, "Tom Ellis", "Tom Ellis (born 1985)", cousin).moved());
        // the same notes read again
        FamilyAccount.file(store, notes, "file:///home/me/notes.txt", "an aunt");
        assertEquals(List.of("born-on"), about(store, "Tom Ellis"), "nothing the owner moved comes back to the grandfather");
        assertEquals(List.of("lived-in", "relative-of"), about(store, "Tom Ellis (born 1985)"));
        // the notes taken back and read again
        FamilyReset.Plan plan = FamilyReset.plan(store, List.of("family-account", "gedcom-import"), "notes.txt");
        assertEquals(2, plan.kept(), "a split is the owner's act, like an accepted fact");
        FamilyReset.apply(store, plan);
        FamilyAccount.file(store, notes, "file:///home/me/notes.txt", "an aunt");
        assertEquals(List.of("born-on"), about(store, "Tom Ellis"));
        assertEquals(List.of("lived-in", "relative-of"), about(store, "Tom Ellis (born 1985)"));
    }

    @Test
    void twoFilesOfOneNameInTwoFoldersAreTwoFiles(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        // an aunt's tree imported from her folder, and the owner's own tree of the same name read from his
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Ann Hart", "parent-of", "Kimie Hart")), List.of()), "file:///home/me/aunt/tree.ged", "a tree file");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Ellis", "born-in", "York")), List.of()), "file:///home/me/mine/tree.ged", "a tree file");
        Path ledger = store.root().resolve("family").resolve("read-files.tsv");
        Files.createDirectories(ledger.getParent());
        Files.writeString(ledger, "a1\t2026-09-20\t/home/me/aunt/tree.ged\nb2\t2026-09-20\t/home/me/mine/tree.ged\n");
        FamilyReset.Plan both = FamilyReset.plan(store, List.of("family-account"), "tree.ged");
        assertEquals(List.of("/home/me/aunt/tree.ged", "/home/me/mine/tree.ged"), both.files(), "the plan names each file with its folders");
        FamilyReset.Plan mine = FamilyReset.plan(store, List.of("family-account"), "/home/me/mine/tree.ged");
        assertEquals(List.of("/home/me/mine/tree.ged"), mine.files());
        assertEquals(1, mine.claims().size());
        FamilyReset.apply(store, mine);
        assertEquals(List.of("Ann Hart"), store.scanFindings().findings().stream().map(f -> f.triple().subject()).toList(), "the aunt's tree stays");
        assertEquals(List.of("a1\t2026-09-20\t/home/me/aunt/tree.ged"), Files.readAllLines(ledger), "and the library still knows it has read it");
    }

    @Test
    void aFreshStartKeepsWhatTheOwnerToldTheLibrary(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Hale", "born-in", "York")), List.of()), "file:///home/me/notes.txt", "an aunt");
        // told with genealogy tell, kept as a file of the library, and told on the Who is who page
        Path told = store.root().resolve("family").resolve("told-20260923-101010.md");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Hale", "born-in", "Selby")), List.of()), "file://" + told.toAbsolutePath().normalize(), "the owner of this library");
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Tom Hale", "occupation", "teacher")), List.of()), "told://who-is-who/20260923-101500", "the owner of this library");
        FamilyReset.Plan plan = FamilyReset.plan(store, List.of("family-account", "gedcom-import"));
        assertEquals(1, plan.claims().size(), "only the notes' fact goes");
        assertEquals(2, plan.kept());
        FamilyReset.apply(store, plan);
        assertEquals(List.of("Selby", "teacher"), store.scanFindings().findings().stream().map(f -> f.triple().object()).sorted().toList());
    }

    private static List<String> about(LibraryStore store, String subject) {
        return store.scanFindings().findings().stream().filter(f -> f.triple().subject().equals(subject)).map(f -> f.triple().predicate()).sorted().toList();
    }

    /** The claim as the review leaves it when it finds another claim that gives the fact otherwise: disputed by the library, nobody's decision. */
    private static void contradicted(LibraryStore store, Finding f) throws Exception {
        store.write(new Finding(f.id(), f.title(), f.subjects(), Finding.State.disputed, f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(),
                f.volatility(), f.reviewBy(), f.sources(), f.supersedes(), f.review(), f.body(), f.triple(), f.notes())
                .withNote(new Finding.Note("disputed", "librarian:test", "2026-09-23", "contradicted by F-0100-tom-ellis-born-in-york")));
    }

    @Test
    void aTreeFileIsTakenOffAFactTheLibraryDisputedByItselfAndTheFactStaysDisputed(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(dated("Tom Ellis", "born-in", "Leeds", "1851"), dated("Tom Ellis", "occupation", "miner", "")), List.of()),
                "file:///home/me/notes.txt", "an aunt");
        Finding born = store.scanFindings().findings().stream().filter(f -> f.title().contains("born in Leeds")).findFirst().orElseThrow();
        contradicted(store, born);
        Path ged = tmp.resolve("ellis.ged");
        Files.writeString(ged, "0 HEAD\n0 @I1@ INDI\n1 NAME Tom /Ellis/\n1 BIRT\n2 DATE 1851\n2 PLAC Leeds\n0 TRLR\n");
        Gedcom.importFile(store, ged);
        assertEquals(2, store.finding(born.id()).sources().size(), "the tree file is a further source of the disputed fact");

        FamilyReset.Plan plan = FamilyReset.plan(store, List.of("family-account", "gedcom-import"), "ellis.ged");
        assertEquals(List.of(born.id()), plan.thinned(), "the library's dispute is not the owner's decision: the file comes off it " + plan);
        FamilyReset.Done done = FamilyReset.apply(store, plan);
        assertEquals(1, done.thinned());
        Finding kept = store.finding(born.id());
        assertEquals(Finding.State.disputed, kept.state(), "it stays disputed, as the library left it");
        assertEquals(List.of("file:///home/me/notes.txt"), kept.sources().stream().map(Finding.Source::locator).toList());

        // a fact the library disputed that only the named file gives stays whole: nothing else would hold the other side of the dispute
        Finding miner = store.scanFindings().findings().stream().filter(f -> f.title().contains("miner")).findFirst().orElseThrow();
        contradicted(store, miner);
        FamilyReset.Plan notes = FamilyReset.plan(store, List.of("family-account", "gedcom-import"), "notes.txt");
        assertFalse(notes.claims().contains(miner.id()) || notes.thinned().contains(miner.id()), notes.toString());

        // a fresh start keeps what a research run added to such a fact, and takes the family's own material off it
        List<Finding.Source> withRun = new ArrayList<>(store.finding(miner.id()).sources());
        withRun.add(new Finding.Source("https://example.org/leeds-directory-1881", "n/a", "said again in I-0007"));
        Finding m = store.finding(miner.id());
        store.write(new Finding(m.id(), m.title(), m.subjects(), m.state(), m.claimType(), m.confidence(), m.writer(), m.recordedAt(), m.validAsOf(), m.volatility(), m.reviewBy(),
                withRun, m.supersedes(), m.review(), m.body(), m.triple(), m.notes()));
        FamilyReset.Plan all = FamilyReset.plan(store, List.of("family-account", "gedcom-import"));
        assertTrue(all.thinned().contains(miner.id()), all.toString());
        FamilyReset.apply(store, all);
        assertEquals(List.of("https://example.org/leeds-directory-1881"), store.finding(miner.id()).sources().stream().map(Finding.Source::locator).toList());
        assertEquals(Finding.State.disputed, store.finding(miner.id()).state());
    }
}
