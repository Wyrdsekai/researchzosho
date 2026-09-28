package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.GenealogyProfile;
import org.researchzosho.tools.ImageText;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A scan's file name says what is on it, and the pages of one register are read in order, as one text. */
class FamilyPagesTest {

    @AfterEach void restore() { ImageText.use(null); GenealogyProfile.useReader(null); }

    private static void picture(Path p, int w) throws Exception { ImageIO.write(new BufferedImage(w, 20, BufferedImage.TYPE_INT_RGB), "png", p.toFile()); }

    @Test
    void pagesOfOneRecordAreOneItemInPageOrderAndACamerasNamesAreNot(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Path dir = Files.createDirectories(tmp.resolve("scans"));
        picture(dir.resolve("register p10.png"), 10); picture(dir.resolve("register p2.png"), 90); picture(dir.resolve("register p1.png"), 50);
        picture(dir.resolve("IMG_0002.png"), 11); picture(dir.resolve("IMG_0001.png"), 70);
        picture(dir.resolve("Hale family (1).png"), 12); picture(dir.resolve("Hale family (2).png"), 13);
        picture(dir.resolve("letter-1908.png"), 14);
        List<FamilyFolder.Item> plan = FamilyFolder.plan(store, dir);
        assertEquals(List.of("Hale family (1).png", "IMG_0001.png", "IMG_0002.png", "letter-1908.png", "register p1.png"), plan.stream().map(i -> i.file().getFileName().toString()).toList(),
                "pictures by name, not by size: " + plan);
        FamilyFolder.Item register = plan.get(4);
        assertEquals(List.of("register p1.png", "register p2.png", "register p10.png"), register.pages().stream().map(p -> p.getFileName().toString()).toList());
        assertEquals(2, plan.get(0).pages().size());
        assertEquals(1, plan.get(1).pages().size(), "a camera numbers every photograph it takes: those are not pages");
        assertEquals(1, plan.get(3).pages().size(), "a year is not a page number");

        FamilyFolder.Label l = FamilyFolder.label("Takahashi_1908_maybe.jpg", List.of("Takahashi", "髙橋"));
        assertEquals(List.of("1908"), l.years()); assertEquals(List.of("Takahashi"), l.families()); assertEquals(List.of("maybe"), l.doubts());
        FamilyFolder.Label era = FamilyFolder.label("髙橋家 明治40年 戸籍.png", List.of("Takahashi", "髙橋"));
        assertEquals(List.of("明治40年 (1907)"), era.years()); assertEquals(List.of("髙橋"), era.families());
        assertNull(FamilyFolder.label("PXL_20240312_101500.jpg", List.of("Takahashi")), "a phone's own name, whose number is the day the photograph was taken");
        assertNull(FamilyFolder.label("IMG_1908.jpg", List.of()));
        assertNull(FamilyFolder.label("register.jpg", List.of("Takahashi")), "a name that gives nothing");
    }

    @Test
    void anEntryThatRunsOntoTheNextPageStaysOneAndEachFactCitesItsPages(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path dir = Files.createDirectories(tmp.resolve("scans"));
        picture(dir.resolve("register_2.png"), 40); picture(dir.resolve("register_1.png"), 60);
        List<String> looked = new ArrayList<>();
        ImageText.use((png, hint) -> { looked.add(hint); return hint.equals("register_1.png") ? "戸主 髙橋源三郎\n長男 正一 明治四十一年" : "三月一日出生\n妻 ハル"; });
        List<String> prompts = new ArrayList<>();
        GenealogyProfile.useReader(prompt -> {
            prompts.add(prompt);
            return "{\"people\": [], \"facts\": [{\"subject\": \"髙橋正一\", \"relation\": \"born-on\", \"object\": \"明治四十一年三月一日\", \"date\": \"\", \"quote\": \"長男 正一 明治四十一年\\n三月一日出生\"},"
                    + " {\"subject\": \"髙橋正一\", \"relation\": \"child-of\", \"object\": \"髙橋源三郎\", \"date\": \"\", \"quote\": \"戸主 髙橋源三郎\\n長男 正一\"},"
                    + " {\"subject\": \"髙橋源三郎\", \"relation\": \"married-to\", \"object\": \"髙橋ハル\", \"date\": \"\", \"quote\": \"妻 ハル\"}]}";
        });
        String said = run(store, "read", dir.toString());
        assertEquals(List.of("register_1.png", "register_2.png"), looked, "page 1 first, whatever the sizes: " + said);
        assertEquals(1, prompts.size(), "the two pages are one text for the reader: " + said);
        assertTrue(prompts.get(0).contains("明治四十一年\n三月一日出生"), prompts.get(0));
        String one = "file://" + dir.resolve("register_1.png").toAbsolutePath().normalize(), two = "file://" + dir.resolve("register_2.png").toAbsolutePath().normalize();
        Finding born = claim(store, "born-on"), son = claim(store, "child-of"), wife = claim(store, "married-to");
        assertEquals(List.of(one, two), born.sources().stream().map(Finding.Source::locator).toList(), "the birth is written across both pages");
        assertEquals(List.of(one), son.sources().stream().map(Finding.Source::locator).toList());
        assertEquals(List.of(two), wife.sources().stream().map(Finding.Source::locator).toList());
        assertTrue(FamilyTranscript.checkedAgainst(born).startsWith("a machine reading of the pictures register_1.png, register_2.png"), born.notes().toString());

        Path fixed = tmp.resolve("page2.txt");
        Files.writeString(fixed, "三月一日出生\n妻 ハル\n", StandardCharsets.UTF_8);
        String out = run(store, "transcript", "register_2.png", "--from", fixed.toString());
        assertTrue(out.contains("The words of all 2 facts read from this picture are in the transcript."), "the birth is checked against both pages together: " + out);
        assertTrue(FamilyTranscript.checkedAgainst(store.finding(born.id())).startsWith("a machine reading of the picture register_1.png that nobody has checked yet, and the transcript of the picture register_2.png"), store.finding(born.id()).notes().toString());
    }

    @Test
    void theFileNameIsToldToTheReaderAndKeptOnEachClaim(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.file(store, new FamilyAccount.Read(List.of(), List.of(new FamilyAccount.Fact("Tom Takahashi", "child-of", "Isamu Takahashi", "", "q")), List.of()), "file:///notes.txt", "an aunt");
        Path scan = tmp.resolve("Takahashi_1908_maybe.png");
        picture(scan, 30);
        ImageText.use((png, hint) -> "Isamu Takahashi, farmer, and his wife Ann.");
        List<String> prompts = new ArrayList<>();
        GenealogyProfile.useReader(prompt -> { prompts.add(prompt); return "{\"people\": [], \"facts\": [{\"subject\": \"Isamu Takahashi\", \"relation\": \"occupation\", \"object\": \"farmer\", \"date\": \"\", \"quote\": \"Isamu Takahashi, farmer\"}]}"; });
        String said = run(store, "read", scan.toString());
        assertTrue(prompts.get(0).contains("the file is labelled \"Takahashi_1908_maybe.png\", which gives the year 1908 and the family name Takahashi; the word \"maybe\" in it says whoever labelled it was not sure. Use the label to see whom the page is about, and take each fact from the page's own writing"), prompts.get(0));
        assertTrue(said.contains("The file's name gives the year 1908; the family name Takahashi; the word maybe, so whoever labelled it was not sure."), said);
        Finding f = claim(store, "occupation");
        assertTrue(f.notes().stream().anyMatch(n -> n.kind().equals("file-label") && n.text().startsWith("the file is labelled \"Takahashi_1908_maybe.png\": the year 1908")), f.notes().toString());
        assertFalse(f.body().contains("1908"), "the year in the name is a guide, not a fact: " + f.body());
    }

    private static Finding claim(LibraryStore store, String predicate) {
        return store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().predicate().equals(predicate)).findFirst().orElseThrow();
    }

    private static String run(LibraryStore store, String... rest) throws Exception {
        String[] args = new String[rest.length + 2];
        args[0] = "researchzosho"; args[1] = "genealogy";
        System.arraycopy(rest, 0, args, 2, rest.length);
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try { new GenealogyProfile().cli(store, args); } finally { System.setOut(was); }
        return out.toString(StandardCharsets.UTF_8);
    }
}
