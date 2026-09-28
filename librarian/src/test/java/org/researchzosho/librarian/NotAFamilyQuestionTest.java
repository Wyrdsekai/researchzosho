package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import org.researchzosho.records.RecordSources;
import org.researchzosho.tools.ImageText;
import org.researchzosho.tools.RecordSearchTool;
import org.researchzosho.tools.Tool;
/**
 * The regression side of the genealogy work: a question of any other field runs exactly as it did. The words a family
 * historian uses are also words of biology, version control and data engineering, and a report about a public figure also
 * says whom he married; neither may turn a run into a family-history run.
 */
class NotAFamilyQuestionTest {

    static final List<String> OTHER_FIELDS = List.of(
            "What is the last common ancestor of whales and hippos, and when did it live?",
            "How is cell lineage traced in zebrafish embryos with CRISPR barcodes?",
            "How does git find the merge base, the best common ancestor of two commits?",
            "What tools track data lineage across a dbt and Airflow pipeline?",
            "How do descendant selectors in CSS differ from child selectors?",
            "How accurate are genetic ancestry estimates from 23andMe-style SNP panels?",
            "What does a dog's pedigree tell a breeder about inbreeding coefficients?",
            "Heart disease runs in my family: what does the evidence say about statins for primary prevention?",
            "共通祖先から分岐した時期を分子時計でどう推定するか",
            "遺伝性疾患の家系解析で連鎖解析はどう使われるか",
            "Wie werden Bahnen von Asteroiden aus wenigen Beobachtungen bestimmt, und was lässt sich daraus ahnen?",
            "Quel est l'ancêtre commun des langues romanes ?",
            "How were the Antikythera gears cut?");

    static final List<String> FAMILY = List.of(
            "Who were my great-grandfather's parents, and where did they farm?",
            "曾祖父の戸籍はどこで請求できますか",
            "How do I start a family tree for a family from Hiroshima?",
            "先祖が広島から移民した記録を探したい",
            "Wie beginne ich mit der Ahnenforschung in Schlesien?");

    @Test
    void theWordsOfOtherFieldsAreNotFamilyWords(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        for (String q : OTHER_FIELDS) assertTrue(Fields.recognised(store, q).isEmpty(), q);
        for (String q : FAMILY) assertEquals(List.of("genealogy"), Fields.recognised(store, q), q);
        for (String q : List.of("Which open source projects read GEDCOM 7 files, and which are still maintained?", "GitHubでOCRの日本語モデルを公開しているリポジトリは?", "What self-hosted note-taking tools exist?"))
            assertEquals(List.of("software"), Fields.recognised(store, q), q);
    }

    @Test
    void aRelativeKnownByAFirstNameDoesNotMakeEveryQuestionWithThatWordInItFamilyHistory(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        // a family account names relatives as the family calls them: often by a first name alone
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Frank", "sibling-of", "Ann Ellis", "", "q"),
                new FamilyAccount.Fact("Allen", "child-of", "Ann Ellis", "", "q"), new FamilyAccount.Fact("Ann Ellis", "died-on", "1931", "", "q"),
                new FamilyAccount.Fact("はな", "child-of", "森田清", "", "q")), List.of()), "file:///family/notes.txt", "an aunt");
        for (String q : List.of("How did the Frankfurt School shape critical theory?", "Why has the birth rate fallen in Japan since 1990?", "Is Franklin's kite story true?",
                "Frankly, why is the sky blue?", "What did Frank Lloyd Wright build in Tokyo?", "はなみずきの花はいつ咲くか"))
            assertNull(Fields.suggest(store, q), q);
        for (String q : List.of("When did Ann Ellis die?", "Where did Frank and Allen go to school?", "森田清はどこで働いていたか", "はなと森田清はどこに住んでいたか"))
            assertEquals("genealogy", Fields.suggest(store, q) == null ? null : Fields.suggest(store, q).field(), q);
        // an older library's reports are never recorded as genealogy's by their words, a relative's name included: the rule only suggests
        for (String[] inv : new String[][]{{"I-0001-frankfurt", "How did the Frankfurt School shape critical theory?"}, {"I-0002-ellis", "When did Ann Ellis die, and where?"}})
            store.write(new Investigation(inv[0], inv[1], Finding.State.accepted, "model:research", Instant.now().toString(), List.of(), List.of(), "Notes.\n"));
        Fields.migrate(store);
        assertEquals(Set.of(), Fields.runs(store).keySet());
    }

    @Test
    void aReportThatSaysWhomAChemistMarriedDoesNotMakeChemistryQuestionsFamilyHistory(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        // what a reviewed research report leaves on the shelf: a kinship triple about a public figure, written by the reviewer
        Finding.Triple t = new Finding.Triple("高峰譲吉", "married-to", "Caroline Hitch");
        store.write(new Finding(store.nextFindingId("takamine married"), "高峰譲吉 married Caroline Hitch in 1887", List.of(), Finding.State.accepted, Finding.ClaimType.extraction,
                Finding.Confidence.high, "reviewer", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/bio", "", "")), List.of(), null, "高峰譲吉 married Caroline Hitch in 1887.\n", t, List.of()));
        assertEquals("married-to", Graph.build(store).edges().get(0).predicate());
        assertTrue(Fields.recognised(store, "How did 高峰譲吉 isolate adrenaline, and what was the yield?").isEmpty(), "he is on the map with a marriage, and it is still a chemistry question");
        // the owner's own family account is another matter
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("髙橋正一", "child-of", "髙橋源三郎", "", "q")), List.of()), "file:///notes.txt", "an aunt");
        assertEquals(List.of("genealogy"), Fields.recognised(store, "What did 髙橋正一 do for a living?"));
    }

    @Test
    void aFolderOfPapersDoesNotSendItsFiguresToTheModel(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path folder = Files.createDirectories(tmp.resolve("papers"));
        Files.writeString(folder.resolve("paper.txt"), "A paper about gear trains, long enough to be a document of its own.");
        BufferedImage img = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", folder.resolve("figure-1.png").toFile());
        int[] asked = {0};
        ImageText.use((png, hint) -> { asked[0]++; return "戸主 髙橋源三郎"; });
        try {
            Corpus.Outcome plain = Corpus.addFolder(store, folder, "papers", true);
            assertEquals(0, asked[0], "the figure was never shown to the model");
            assertEquals(1, Corpus.picturesIn(folder), "and the command can say one picture was left alone");
            Corpus.Outcome asScans = Corpus.withPictures(() -> Corpus.addFolder(store, folder, "papers", true));
            assertEquals(1, asked[0], "asked for, the picture is read");
            assertNotNull(plain); assertNotNull(asScans);
            Corpus.addFolder(store, folder, "papers", true);
            assertEquals(1, asked[0], "and the next plain add, a nightly rescan say, leaves pictures alone again");
        } finally { ImageText.use(null); }
    }

    @Test
    void aRunOfAnotherFieldIsOfferedTheSameToolsAndReadsTheSameInstructionsAsBefore() {
        FieldRulesTest.Recording drive = new FieldRulesTest.Recording();
        Researcher.Tools withRecords = new Researcher.Tools() {
            final ResearcherTest.FakeTools base = new ResearcherTest.FakeTools();
            @Override public List<Tool> web(String focus) {
                List<Tool> out = new ArrayList<>(base.web(focus));
                out.add(new RecordSearchTool(RecordSources.all(), url -> "{}"));   // the live adapter always brings it
                return out;
            }
            @Override public BooleanSupplier exhausted() { return base.exhausted(); }
        };
        new Researcher(drive, withRecords, null, 2).run(new Researcher.Ask("What is the last common ancestor of whales and hippos, and when did it live?", "depth", 60, List.of()), "");
        assertTrue(drive.offered.stream().noneMatch(o -> o.contains("record_search")), "not offered to a palaeontology question: " + drive.offered);
        for (ArrayNode h : drive.histories) for (JsonNode m : h) { String c = m.path("content").asText(); assertFalse(c.contains("record_search") || c.contains("GENEALOGY"), c); }
        for (String p : drive.prompts) assertFalse(p.contains("GENEALOGY") || p.contains("record collections searched by kind"), p);
        assertTrue(drive.prompts.stream().anyMatch(p -> p.contains("Name the PERSPECTIVES")), "and it is still asked who studies the question");

        FieldRulesTest.Recording family = new FieldRulesTest.Recording();
        new Researcher(family, withRecords, null, 2).run(new Researcher.Ask("Who were my great-grandfather's parents in Hiroshima?", "depth", 60, List.of()).withFields(List.of("genealogy")), "");
        assertTrue(family.offered.stream().anyMatch(o -> o.contains("record_search")), "and a run asked for in genealogy mode gets it: " + family.offered);
        FieldRulesTest.Recording unasked = new FieldRulesTest.Recording();
        new Researcher(unasked, withRecords, null, 2).run(new Researcher.Ask("Who were my great-grandfather's parents in Hiroshima?", "depth", 60, List.of()), "");
        assertTrue(unasked.offered.stream().noneMatch(o -> o.contains("record_search")), "the same question nobody asked genealogy mode for is ordinary research: " + unasked.offered);
    }
}
