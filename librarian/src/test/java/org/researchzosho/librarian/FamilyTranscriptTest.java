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

/**
 * A picture's writing as the model read it is a draft, like the claims read from it. A character nobody could read makes no key,
 * no merge and no search, and a transcript a person corrected checks the claims' words again.
 */
class FamilyTranscriptTest {

    @AfterEach void restore() { ImageText.use(null); GenealogyProfile.useReader(null); }

    @Test
    void aNameWithACharacterNobodyCouldReadIsKeptButNeverAKeyOrASearch() {
        assertTrue(FamilyNames.unreadable("髙橋□三郎") && FamilyNames.unreadable("Tom [unclear] Hale") && FamilyNames.unreadable("遠藤〓三郎") && FamilyNames.unreadable("Ann [?] Hart"));
        assertFalse(FamilyNames.unreadable("髙橋源三郎") || FamilyNames.unreadable("Tom Hale (born 1850)"));
        assertTrue(FamilyNames.keys("髙橋□三郎").isEmpty() && FamilyNames.keys("Tom [illegible] Hale").isEmpty(), "no key, so no merge by name");
        assertFalse(FamilyNames.keys("髙橋源三郎").isEmpty());
        assertNull(FamilyNames.sound("Tom H□le"));
        Graph.Node n = new Graph.Node("x", "person", "髙橋□三郎", List.of("Genzaburo Takahashi", "Tom [unclear] Hale"), "", 1);
        assertEquals(List.of("Genzaburo Takahashi"), FamilyIdentity.forms(n), "only the form that can be read is searched");
        assertFalse(FamilyIdentity.fullName("髙橋□三郎"), "a relative with an unread character is not matched on a page");
    }

    @Test
    void aPicturesReadingIsADraftTranscriptAndACorrectionChecksTheClaimsAgain(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        Path scan = Files.createDirectories(tmp.resolve("scans")).resolve("register-1.png");
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", scan.toFile());
        int[] looked = {0};
        ImageText.use((png, hint) -> { looked[0]++; return "戸主 髙橋□三郎 妻 ハル\n長男 正一 明治四十一年三月一日出生"; });
        GenealogyProfile.useReader(prompt -> prompt.contains("髙橋源三郎")
                ? "{\"people\": [], \"facts\": [{\"subject\": \"髙橋源三郎\", \"relation\": \"married-to\", \"object\": \"髙橋ハル\", \"date\": \"\", \"quote\": \"髙橋源三郎 妻 ハル\"}]}"
                : "{\"people\": [], \"facts\": [{\"subject\": \"髙橋□三郎\", \"relation\": \"married-to\", \"object\": \"髙橋ハル\", \"date\": \"\", \"quote\": \"髙橋□三郎 妻 ハル\"},"
                + " {\"subject\": \"髙橋正一\", \"relation\": \"child-of\", \"object\": \"髙橋□三郎\", \"date\": \"\", \"quote\": \"長男 正一\"},"
                + " {\"subject\": \"髙橋正一\", \"relation\": \"born-on\", \"object\": \"明治四十一年三月一日\", \"date\": \"\", \"quote\": \"正一 明治四十一年三月一日出生\"}]}");
        String said = run(store, "read", scan.toString());
        assertEquals(1, looked[0], "the model reads the picture once: " + said);
        assertTrue(said.contains("researchzosho genealogy transcript \"register-1.png\""), said);
        String locator = "file://" + scan.toAbsolutePath().normalize();
        FamilyTranscript.Transcript t = FamilyTranscript.of(store, locator);
        assertEquals(FamilyTranscript.MACHINE, t.state());
        List<Finding> read = store.scanFindings().findings().stream().filter(f -> f.sources().stream().anyMatch(s -> s.locator().equals(locator))).toList();
        assertTrue(read.size() >= 3, read.toString());
        assertTrue(read.stream().allMatch(FamilyTranscript::onMachineReading), "every claim notes that its words were checked against the model's own reading");
        assertTrue(new LibraryProtocol(store).inboxList().stream().anyMatch(o -> o.path("checked_against").asText().startsWith("a machine reading of the picture register-1.png")));

        Graph g = Graph.build(store);
        Graph.Node boxed = g.node(g.nodeIdOf("髙橋□三郎"));
        assertNotNull(boxed, "the name is kept as the register gives it");
        List<String> searched = new ArrayList<>();
        FamilyWho.findAll(store, List.of("髙橋□三郎"), q -> { searched.add(q); return List.of(); }, null, null);
        assertTrue(searched.isEmpty(), "nobody searches the web for a character nobody could read: " + searched);
        assertTrue(FamilyQuestions.around(store, "髙橋正一", 2, 2).stream().noneMatch(a -> a.person().equals("髙橋□三郎")));

        assertEquals("戸主 髙橋□三郎 妻 ハル\n長男 正一 明治四十一年三月一日出生", run(store, "transcript", "register-1.png", "--text").strip());
        Path corrected = tmp.resolve("corrected.txt");
        Files.writeString(corrected, "戸主 髙橋源三郎 妻 ハル\n長男 正一 明治四十一年三月一日出生\n", StandardCharsets.UTF_8);
        String fixed = run(store, "transcript", "register-1", "--from", corrected.toString());
        Finding boxedMarriage = read.stream().filter(f -> f.triple().predicate().equals("married-to")).findFirst().orElseThrow();
        assertTrue(fixed.contains("The words of this one are not in it any more:") && fixed.contains(boxedMarriage.id()) && fixed.contains("researchzosho retire " + boxedMarriage.id()), fixed);
        assertTrue(FamilyTranscript.checkedAgainst(store.finding(boxedMarriage.id())).endsWith("the quoted words are not in it"));
        Finding son = read.stream().filter(f -> f.triple().predicate().equals("child-of")).findFirst().orElseThrow();
        assertTrue(FamilyTranscript.checkedAgainst(store.finding(son.id())).startsWith("the transcript of the picture register-1.png that "), "its words are in the corrected transcript too");
        assertFalse(store.finding(son.id()).reviewStale(), "a note is not the claim's substance");

        run(store, "read", scan.toString());
        assertEquals(1, looked[0], "an accepted transcript is read as it stands, and the model does not read the picture again");
        Finding fixedMarriage = store.scanFindings().findings().stream().filter(f -> f.triple() != null && f.triple().subject().equals("髙橋源三郎")).findFirst().orElseThrow();
        assertTrue(FamilyTranscript.checkedAgainst(fixedMarriage).startsWith("the transcript of the picture register-1.png that"), fixedMarriage.notes().toString());
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
