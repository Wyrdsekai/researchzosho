package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The comic helper as it is shipped, asked about and set up (0.7.5): the files the build carries are the ones the program installs; the
 * manifest names every model, font and pattern file with a fixed source, a size, a checksum and a licence; the one question says each
 * of them; a set-up makes the environment, fetches, loads every piece once and leaves its marker only when each loaded; an earlier
 * release's files are refreshed; a
 * release that adds to the manifest fetches what is missing without a second question, and asks again only for a large download or a
 * new environment; the fonts come in the order of the target's writing. What is said of a manifest (the sizes, the list, the order)
 * is made from the manifest, so the sentences are held to the one in use and to a grown one of the test's own
 * ({@link ComicStandIn#GROWN}). Nothing here starts Python, reaches a network or needs the helper on the machine.
 */
class ComicHelperTest {
    String home; ComicHelper.Run run; LongSupplier free; BooleanSupplier installed;
    @BeforeEach void isolate(@TempDir Path tmp) { home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); run = ComicHelper.RUN; free = ComicHelper.FREE; installed = ComicHelper.INSTALLED; ComicHelper.FREE = () -> 100L << 30; }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); ComicHelper.RUN = run; ComicHelper.FREE = free; ComicHelper.INSTALLED = installed; ComicHelper.manifestFrom(null); }

    /** The bytes of the manifest's things of the kinds given (all kinds when none is given), as the question adds them up. */
    static long bytes(String... kinds) { long n = 0; for (ComicHelper.Item i : ComicHelper.manifest()) if (kinds.length == 0 || List.of(kinds).contains(i.kind())) n += i.bytes(); return n; }
    /** The question's word for the fonts and patterns together: their size, or "under 1 MB". */
    static String small() { long n = bytes("font", "hyphenation"); return n < 1_000_000 ? "under 1 MB" : ComicHelper.size(n); }

    static Path resources() { return GenealogyBoundaryTest.mainJava().resolveSibling("resources").resolve("org/researchzosho/librarian/comic"); }

    @Test void theFilesTheProgramInstallsAreTheOnesTheBuildCarries() throws Exception {
        TreeSet<String> onDisk = new TreeSet<>();
        try (Stream<Path> all = Files.walk(resources())) {
            for (Path p : all.filter(Files::isRegularFile).toList()) { String rel = resources().relativize(p).toString().replace('\\', '/'); if (!rel.contains("__pycache__")) onDisk.add(rel); }
        }
        assertEquals(onDisk, new TreeSet<>(ComicHelper.FILES), "a file added to the helper is added to ComicHelper.FILES, or it never reaches an install");
        for (String f : ComicHelper.FILES) assertNotNull(ComicHelper.resource(f), f + " is on the class path");
        String launcher = new String(ComicHelper.resource("comic_helper.py"), StandardCharsets.UTF_8);
        assertTrue(launcher.contains("sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))"), "run with python -I, the launcher puts its own folder on the path itself");
    }

    @Test void theManifestNamesEveryThingFetchedWithAFixedSourceASizeAChecksumAndALicence() throws Exception {
        JsonNode m = new ObjectMapper().readTree(ComicHelper.resource(ComicHelper.MANIFEST));
        List<String> names = new ArrayList<>();
        for (JsonNode e : m.path("models")) {
            String name = e.path("name").asText(); names.add(name);
            assertTrue(List.of("model", "font", "hyphenation", "words").contains(e.path("kind").asText()), name);
            if (e.path("kind").asText().equals("font")) for (String script : e.path("script").asText().split(" ")) assertTrue(ComicHelper.ALPHABETS.contains(script) || ComicHelper.CJK_SCRIPTS.contains(script), name + " names its writing systems by the words the program orders fonts by: " + script);
            assertFalse(e.path("licence").asText().isBlank(), name + " states its licence"); assertFalse(e.path("licence_said").asText().isBlank(), name);
            assertTrue(e.path("revision").asText().matches("[0-9a-f]{40}"), name + " is pinned to a revision");
            assertTrue(e.path("files").size() > 0, name);
            for (JsonNode f : e.path("files")) {
                assertTrue(f.path("url").asText().startsWith("https://") && f.path("url").asText().contains(e.path("revision").asText()), name + ": " + f.path("url").asText() + " is fetched at the revision, never at a branch's head");
                assertTrue(f.path("sha256").asText().matches("[0-9a-f]{64}"), name); assertTrue(f.path("size").asLong() > 0, name);
                assertFalse(f.path("path").asText().contains(".."), name);
            }
        }
        assertTrue(names.containsAll(List.of("detector", "reader", "hyphenation-en-us", "hyphenation-de", "hyphenation-fr", "hyphenation-es", "hyphenation-it", "hyphenation-pt", "hyphenation-ru", "font-latin", "font-cyrillic")), "what the helper was first shipped with stays, whatever a release adds: " + names);
        assertEquals(names.size(), new HashSet<>(names).size(), "no name twice");
        assertTrue(ComicHelper.hyphenated().containsAll(List.of("en", "de", "fr", "es", "it", "pt", "ru")), ComicHelper.hyphenated().toString());
        ComicHelper.Item reader = ComicHelper.byName().get("reader");
        assertEquals("manga-ocr-base", reader.title()); assertEquals("Apache 2.0", reader.licence()); assertEquals(444_237_919L, reader.bytes()); assertEquals(6, reader.paths().size());
        assertEquals("SIL Open Font License 1.1", ComicHelper.byName().get("font-cyrillic").licence());
        assertEquals("LPPL 1.2 or later", ComicHelper.byName().get("hyphenation-ru").licence(), "the one pattern file that is not under a permissive licence is said as it is");
        // the font a target is set in comes first, the other after it for a letter the first lacks
        assertEquals(List.of("ComicNeue-Bold.ttf", "ComicRelief-Bold.ttf"), ComicHelper.fonts("latin").stream().map(p -> p.getFileName().toString()).toList().subList(0, 2));
        assertEquals(List.of("ComicRelief-Bold.ttf", "ComicNeue-Bold.ttf"), ComicHelper.fonts("cyrillic").stream().map(p -> p.getFileName().toString()).toList().subList(0, 2));
        assertEquals(List.of("ComicRelief-Bold.ttf", "ComicNeue-Bold.ttf"), ComicHelper.fonts("greek").stream().map(p -> p.getFileName().toString()).toList().subList(0, 2));
    }

    @Test void theFontsComeInTheOrderOfTheTargetsWritingItsOwnFirst() throws Exception {
        ComicStandIn.grown();
        Function<String, List<String>> fonts = script -> ComicHelper.fonts(script).stream().map(p -> p.getFileName().toString()).toList();
        // the target's own font first; for Chinese the font of its other character set next; then the rest of its family; then the alphabets
        assertEquals(List.of("SansSC-Bold.otf", "SansTC-Bold.otf", "SansJP-Bold.otf", "SansKR-Bold.otf", "Latin-Bold.ttf", "Cyrillic-Bold.ttf"), fonts.apply("chinese-simplified"));
        assertEquals(List.of("SansTC-Bold.otf", "SansSC-Bold.otf", "SansJP-Bold.otf", "SansKR-Bold.otf", "Latin-Bold.ttf", "Cyrillic-Bold.ttf"), fonts.apply("chinese-traditional"));
        assertEquals(List.of("SansJP-Bold.otf", "SansSC-Bold.otf", "SansTC-Bold.otf", "SansKR-Bold.otf", "Latin-Bold.ttf", "Cyrillic-Bold.ttf"), fonts.apply("japanese"));
        assertEquals(List.of("SansKR-Bold.otf", "SansJP-Bold.otf", "SansSC-Bold.otf", "SansTC-Bold.otf", "Latin-Bold.ttf", "Cyrillic-Bold.ttf"), fonts.apply("korean"));
        // an alphabet's target: its own, the other alphabet's, and the fonts of East Asia after them, for a name left in its own characters
        assertEquals(List.of("Latin-Bold.ttf", "Cyrillic-Bold.ttf", "SansJP-Bold.otf", "SansSC-Bold.otf", "SansTC-Bold.otf", "SansKR-Bold.otf"), fonts.apply("latin"));
        assertEquals(List.of("Cyrillic-Bold.ttf", "Latin-Bold.ttf", "SansJP-Bold.otf", "SansSC-Bold.otf", "SansTC-Bold.otf", "SansKR-Bold.otf"), fonts.apply("greek"));
        // the writing system a target is lettered in, as the fonts name it
        assertEquals("japanese", ComicTranslation.script("ja")); assertEquals("chinese-simplified", ComicTranslation.script("zh")); assertEquals("korean", ComicTranslation.script("ko"));
    }

    @Test void whatTheQuestionAndTheListSayComesFromTheManifestAndTheOneListOfPackages() throws Exception {
        // a grown manifest: the same three sentences, its own sizes, and a line for every piece, with no edit to the program
        ComicStandIn.grown();
        assertEquals("Setting it up fetches, once: its models (540 MB), the lettering fonts and hyphenation patterns (64 MB), its word lists (8 MB) and Python packages in an environment of its own (about 450 MB to download, 1.7 GB on the disk). In all 1.1 GB to download and 2.3 GB on the disk, kept under " + ComicHelper.dir() + ". Python 3 must be on this machine.", ComicHelper.question(Chrome.ENGLISH).get(1));
        assertTrue(ComicHelper.questionSaid(Chrome.in("ja")).contains("ヘルパーの単語リスト（8 MB）"), ComicHelper.questionSaid(Chrome.in("ja")));
        List<String> pieces = ComicHelper.pieces();
        assertEquals(15, pieces.size(), "fourteen things in the manifest, a word list among them, and the packages");
        assertTrue(pieces.contains("to fetch    the words of Chinese, so that no line ends inside one: stand-in-words-zh, 8 MB, licence Apache 2.0"), "a kind the manifest came to name is listed as every other is: " + String.join("\n", pieces));
        assertEquals("the models, the fonts, the hyphenation patterns and the word lists", ComicHelper.fetchingSaid(ComicHelper.manifest()));
        assertTrue(pieces.contains("to fetch    the model that reads Korean lettering: stand-in-reader-ko, 12 MB, licence Apache 2.0") && pieces.contains("to fetch    the lettering font for Chinese in traditional characters: Stand-in Sans TC Bold, 16 MB, licence SIL Open Font License 1.1"), String.join("\n", pieces));
        assertEquals(15, ComicHelper.json().path("fetches").size());
        // the packages: one list, from which the marker, the commands, the list of pieces and the API's licences are all made
        assertEquals("torch (the CPU index); manga-ocr fugashi unidic-lite onnxruntime opencv-python-headless pillow numpy pyyaml budoux", ComicHelper.PACKAGES, "what the helper imports is named, each by itself: fugashi and unidic-lite, by which a Japanese line ends where a phrase does, are no longer left to manga-ocr to bring");
        assertEquals(List.of(List.of("torch", "--index-url", "https://download.pytorch.org/whl/cpu"), List.of("manga-ocr", "fugashi", "unidic-lite", "onnxruntime", "opencv-python-headless", "pillow", "numpy", "pyyaml", "budoux")), ComicHelper.pipInstalls(ComicHelper.PYTHON_PACKAGES));
        assertEquals("PyTorch for processors (BSD-3-Clause), manga-ocr (Apache 2.0), fugashi (MIT; the MeCab it carries is under BSD-3-Clause), the Japanese dictionary unidic-lite (MIT; the dictionary itself is under BSD-3-Clause), ONNX Runtime (MIT), OpenCV (Apache 2.0; its package carries FFmpeg libraries under the LGPL, which the helper does not use), Pillow (MIT-CMU), NumPy (BSD-3-Clause), PyYAML (MIT), BudouX (Apache 2.0), and what these need, among them transformers (Apache 2.0) and tqdm (MPL 2.0 and MIT)", ComicHelper.PACKAGES_SAID);
        assertEquals("BSD-3-Clause, Apache 2.0, MIT, MIT-CMU; LGPL (FFmpeg libraries inside OpenCV's package, not used); MPL 2.0 (tqdm)", ComicHelper.packageLicences(ComicHelper.PYTHON_PACKAGES));
        // a package the helper comes to need is one line of the list: every place follows
        List<ComicHelper.Package> more = new ArrayList<>(ComicHelper.PYTHON_PACKAGES); more.add(new ComicHelper.Package("regex", "regex", "Apache 2.0"));
        assertEquals("torch (the CPU index); manga-ocr fugashi unidic-lite onnxruntime opencv-python-headless pillow numpy pyyaml budoux regex", ComicHelper.packagesLine(more));
        assertEquals(List.of("manga-ocr", "fugashi", "unidic-lite", "onnxruntime", "opencv-python-headless", "pillow", "numpy", "pyyaml", "budoux", "regex"), ComicHelper.pipInstalls(more).get(1));
        assertTrue(ComicHelper.packagesSaid(more).contains("BudouX (Apache 2.0), regex (Apache 2.0), and what these need"), ComicHelper.packagesSaid(more));
        // a helper set up with the list as it was before fugashi, unidic-lite, PyYAML and BudouX were named: pip is run in the environment that stands, and nothing is asked
        ComicHelper.manifestFrom(null);
        ComicHelper.RUN = setUp(new ArrayList<>());
        assertEquals("ready", ComicHelper.install(l -> { }));
        Files.writeString(ComicHelper.ready(), ComicHelper.markerText().replace(" fugashi unidic-lite", "").replace(" pyyaml budoux\n", "\n"));
        assertTrue(Files.readString(ComicHelper.ready()).startsWith("packages torch (the CPU index); manga-ocr onnxruntime opencv-python-headless pillow numpy\n"), "the marker of every helper set up before this version");
        assertFalse(ComicHelper.installed()); assertTrue(ComicHelper.needs().packages()); assertFalse(ComicHelper.needs().asks(), "a name more in the list of packages is no question");
    }

    @Test void aHelperSetUpBeforeTheManifestGrewFetchesWhatIsMissingWithoutASecondQuestion() throws Exception {
        // set up as an earlier release had it: the detector, the Japanese reader, the patterns, the two alphabet fonts
        String before = ComicStandIn.before();
        ComicHelper.manifestFrom(() -> before.getBytes(StandardCharsets.UTF_8));
        List<List<String>> ran = new ArrayList<>();
        ComicHelper.RUN = setUp(ran);
        assertEquals("ready", ComicHelper.install(l -> { }));
        assertTrue(ComicHelper.installed()); assertTrue(ComicHelper.readyOrQuiet());
        // this release's manifest names three readers and four fonts more: not set up as this release has it, and nothing to ask
        ComicStandIn.grown();
        assertFalse(ComicHelper.installed());
        ComicHelper.Needs n = ComicHelper.needs();
        assertEquals(List.of("reader-zh", "reader-ko", "reader-alphabets", "words-chinese", "font-japanese", "font-chinese-simplified", "font-chinese-traditional", "font-korean"), n.items().stream().map(ComicHelper.Item::name).toList());
        assertFalse(n.environment()); assertFalse(n.packages(), "the packages are the ones it was set up with"); assertEquals(172_000_000L, n.download());
        assertFalse(n.asks(), "172 MB added to a helper that stands is fetched, said and not asked");
        assertTrue(ComicHelper.readyOrQuiet());
        assertEquals("3 models (100 MB), 4 fonts (64 MB) and 1 word list (8 MB)", n.addsSaid());
        assertEquals("the models, the fonts and the word lists", ComicHelper.fetchingSaid(n.items()));
        assertEquals("The comic helper was set up for an earlier release, at " + ComicHelper.dir() + ". This release adds to it: 3 models (100 MB), 4 fonts (64 MB) and 1 word list (8 MB). That is fetched as the first step of the next comic translated, with no question, or now by researchzosho translate comics install; researchzosho translate comics status --list names each piece with its licence.", ComicHelper.status());
        assertTrue(ComicHelper.statusLine().endsWith("for an earlier release; what this release adds is fetched with the next comic"), ComicHelper.statusLine());
        assertTrue(ComicHelper.pieces().get(1).startsWith("here        the model that reads Japanese lettering") && ComicHelper.pieces().get(2).startsWith("to fetch    the model that reads Chinese lettering"), String.join("\n", ComicHelper.pieces()));
        assertTrue(ComicHelper.pieces().get(14).startsWith("here        Python packages"), ComicHelper.pieces().get(14));
        // a run's first step: said through its progress, never asked, and no package is installed again
        List<String> said = new ArrayList<>();
        ran.clear();
        ComicTranslation.helperReady(ComicTranslation.Said.NOTHING, p -> said.add(p.path("said").asText()), "checked");
        assertEquals("The comic helper was set up for an earlier release; fetching what this release adds: 3 models (100 MB), 4 fonts (64 MB) and 1 word list (8 MB).", said.get(0));
        assertTrue(said.contains("Fetching the models, the fonts and the word lists (172 MB)."), "the fetch says what it brings, by the kinds the manifest names among the missing: " + said);
        assertEquals("The comic helper is set up.", said.get(said.size() - 1));
        assertTrue(ComicHelper.installed());
        assertEquals(2, ran.size(), "the fetch and the check, no environment made and no pip run: " + ran); assertTrue(ran.get(0).contains("fetch") && ran.get(1).contains("check"), ran.toString());
        // a release whose list of packages has one more: pip is run in the environment that stands, without --upgrade, and nothing is asked
        Files.writeString(ComicHelper.ready(), ComicHelper.markerText().replace("packages " + ComicHelper.PACKAGES, "packages torch (the CPU index); manga-ocr onnxruntime"));
        n = ComicHelper.needs();
        assertTrue(n.packages()); assertFalse(n.environment()); assertFalse(n.asks()); assertEquals(0, n.download());
        assertEquals("its Python packages brought to this release's list", n.addsSaid());
        // the list of pieces names what this release's list adds to the one the helper was set up with, which is all pip installs: it said
        // "about 450 MB to download and 1.7 GB on the disk", the whole environment's sizes, of a helper that was to get BudouX (macOS and Windows, 2026-11-14)
        String packages = ComicHelper.pieces().get(14);
        assertEquals("to add to   Python packages in the helper's own environment, which stands: this release's list adds fugashi (MIT; the MeCab it carries is under BSD-3-Clause), the Japanese dictionary unidic-lite (MIT; the dictionary itself is under BSD-3-Clause), OpenCV (Apache 2.0; its package carries FFmpeg libraries under the LGPL, which the helper does not use), Pillow (MIT-CMU), NumPy (BSD-3-Clause), PyYAML (MIT) and BudouX (Apache 2.0); pip installs those the environment lacks, and its other packages stay as they are", packages);
        assertFalse(packages.contains("450 MB") || packages.contains("1.7 GB"), packages);
        assertEquals(List.of("torch", "manga-ocr", "onnxruntime"), ComicHelper.markerPackages());
        ran.clear();
        List<String> brought = new ArrayList<>();
        assertEquals("ready", ComicHelper.install(brought::add));
        assertTrue(brought.contains("Bringing its Python packages to this release's list."), "a helper an earlier release set up is told its packages are brought to this release's list: " + brought);
        assertEquals(4, ran.size(), ran.toString());
        assertTrue(ran.get(0).contains("torch") && !ran.get(0).contains("--upgrade") && !ran.get(1).contains("--upgrade"), "into an environment an earlier release made, pip adds what the list adds and upgrades nothing: " + ran.subList(0, 2));
        // more than the quiet limit to fetch is the person's to say yes to, as the first set-up was; so is a new environment
        Files.delete(ComicHelper.models().resolve("reader-ja/model.bin"));
        n = ComicHelper.needs();
        assertTrue(n.asks(), "400 MB is asked for"); assertFalse(ComicHelper.readyOrQuiet());
        IOException asked = assertThrows(IOException.class, () -> ComicTranslation.helperReady(ComicTranslation.Said.NOTHING, null, "checked"));
        assertTrue(asked.getMessage().startsWith("The comic helper is not set up on this machine, and this comic was sent without a yes to setting it up."), asked.getMessage());
        assertTrue(ComicHelper.status().startsWith("The comic helper is not set up:"), ComicHelper.status());
    }

    /**
     * The guide's table of what a set-up fetches is the manifest's: a row for every model and font with its size and its licence, and
     * the sum under it. A manifest that grows fails here until the guide has the rows, which the failure prints ready to paste.
     */
    @Test void theGuidesTableOfWhatIsFetchedIsTheManifests() throws Exception {
        String guide = ContentDocsTest.doc("LIBRARIAN_HOWTOUSE.md");
        List<String> lacking = new ArrayList<>();
        for (ComicHelper.Item i : ComicHelper.manifest()) {
            if (i.kind().equals("hyphenation")) { if (!guide.contains(ReadIn.name(i.language()))) lacking.add("hyphenation patterns for " + ReadIn.name(i.language())); continue; }
            String row = "| " + i.said() + " (" + i.title() + ") | " + ComicHelper.size(i.bytes()) + " | " + i.licence() + " |";
            if (!guide.contains(row)) lacking.add(row);
        }
        String sum = "In all about " + ComicHelper.size(bytes() + ComicHelper.PACKAGES_DOWNLOAD) + " to download and " + ComicHelper.size(bytes() + ComicHelper.PACKAGES_DISK) + " on the disk, kept under `~/.researchzosho/comic`.";
        if (!guide.contains(sum)) lacking.add(sum);
        assertTrue(lacking.isEmpty(), "docs/public/LIBRARIAN_HOWTOUSE.md, the table under \"The comic helper\", lacks:\n" + String.join("\n", lacking));
        for (ComicHelper.Package p : ComicHelper.PYTHON_PACKAGES) assertTrue(guide.contains(p.said()), "the guide names the Python package " + p.said());
    }

    @Test void theOneQuestionIsThreeSentencesAndTheListOfPiecesNamesEachWithItsLicence() {
        assertFalse(ComicHelper.installed());
        List<String> q = ComicHelper.question(Chrome.ENGLISH);
        assertEquals(List.of(
                "Reading a comic's pages needs the comic helper, which is not set up on this machine yet. It reads the lettering on each page and puts the translation into the speech balloons; it runs on this machine's processor, and no page leaves the machine for it.",
                "Setting it up fetches, once: its models (" + ComicHelper.size(bytes("model")) + "), the lettering fonts and hyphenation patterns (" + small() + "), its word lists (" + ComicHelper.size(bytes("words")) + ") and Python packages in an environment of its own (about 450 MB to download, 1.7 GB on the disk). In all " + ComicHelper.size(bytes() + ComicHelper.PACKAGES_DOWNLOAD) + " to download and " + ComicHelper.size(bytes() + ComicHelper.PACKAGES_DISK) + " on the disk, kept under " + ComicHelper.dir() + ". Python 3 must be on this machine.",
                "All of it is under open licences; researchzosho translate comics status --list names each piece with its licence."), q);
        assertEquals("Set the comic helper up now? (y/N)", ComicHelper.ask(Chrome.ENGLISH));
        assertFalse(String.join(" ", q).matches(".*(tqdm|LGPL|Apache|MIT|manga-ocr).*"), "no licence's name and no package's stands before the yes or no: " + q);
        // the list of pieces: every thing the manifest names and the Python packages, each with its size and its licence
        List<String> pieces = ComicHelper.pieces();
        assertEquals(ComicHelper.manifest().size() + 1, pieces.size());
        String all = String.join("\n", pieces);
        assertTrue(pieces.contains("to fetch    the model that finds the text and the speech balloons on a page: comic-text-and-bubble-detector, 44 MB, licence Apache 2.0"), all);
        assertTrue(pieces.contains("to fetch    the model that reads Japanese lettering: manga-ocr-base, 444 MB, licence Apache 2.0"), all);
        assertTrue(pieces.contains("to fetch    hyphenation patterns for English, 32 KB, licence the GNU all-permissive licence"), all);
        assertTrue(pieces.contains("to fetch    hyphenation patterns for German, 272 KB, licence MIT") && pieces.contains("to fetch    hyphenation patterns for Portuguese, 2 KB, licence BSD 3-clause"), all);
        assertTrue(pieces.contains("to fetch    hyphenation patterns for Russian, 66 KB, licence LPPL 1.2 or later"), all);
        assertTrue(pieces.contains("to fetch    the lettering font for the Latin alphabet: Comic Neue Bold, 56 KB, licence SIL Open Font License 1.1"), all);
        assertTrue(pieces.contains("to fetch    the lettering font for the Cyrillic and Greek alphabets: Comic Relief Bold, 95 KB, licence SIL Open Font License 1.1"), all);
        assertTrue(pieces.get(pieces.size() - 1).startsWith("to install  Python packages in an environment of the helper's own, about 450 MB to download and 1.7 GB on the disk: PyTorch for processors (BSD-3-Clause), manga-ocr (Apache 2.0)"), all);
        assertTrue(all.contains("FFmpeg libraries under the LGPL") && all.contains("tqdm (MPL 2.0 and MIT)"), "what is not under a permissive licence is said as it is: " + all);
        assertTrue(all.contains("fugashi (MIT; the MeCab it carries is under BSD-3-Clause), the Japanese dictionary unidic-lite (MIT; the dictionary itself is under BSD-3-Clause)"), "the two the Japanese line ends go by, each with its licence as its own files state it: " + all);
        for (ComicHelper.Item i : ComicHelper.manifest()) assertTrue(all.contains(i.licence()), i.name() + "'s licence is said");
        // as data, for an API's refusal
        assertEquals(ComicHelper.manifest().size() + 1, ComicHelper.json().path("fetches").size()); assertFalse(ComicHelper.json().path("set_up").asBoolean());
        assertEquals("Apache 2.0", ComicHelper.json().path("fetches").path(1).path("licence").asText());
        // one paragraph for a reply with no lines of its own, and the same in Japanese
        String said = ComicHelper.questionSaid(Chrome.ENGLISH);
        assertFalse(said.contains("\n")); assertTrue(said.startsWith(q.get(0) + " Setting it up fetches, once: ") && said.endsWith(q.get(2)), said);
        String ja = ComicHelper.questionSaid(Chrome.in("ja"));
        assertTrue(ja.contains("ヘルパーのモデル（" + ComicHelper.size(bytes("model")) + "）、文字入れ用の書体とハイフネーションのデータ（" + (small().equals("under 1 MB") ? "1 MB 未満" : small()) + "）、ヘルパーの単語リスト（" + ComicHelper.size(bytes("words")) + "）と専用の環境に入れる Python パッケージ（ダウンロード約 450 MB、ディスク上で 1.7 GB）"), ja);
        assertTrue(ComicHelper.ask(Chrome.in("ja")).startsWith("コミックヘルパーを今設定しますか？"), ComicHelper.ask(Chrome.in("ja")));
    }

    /** A stand-in for the commands a set-up runs: the environment "made" by writing its Python, the fetch by writing every file the manifest names, the check answering that every piece loaded. */
    static ComicHelper.Run setUp(List<List<String>> ran) {
        return (cmd, line) -> {
            ran.add(cmd);
            if (cmd.contains("venv")) { Path py = ComicHelper.python(); Files.createDirectories(py.getParent()); Files.writeString(py, "#!/bin/sh\n"); return new Searx.Result(0, ""); }
            if (cmd.contains("fetch")) {
                for (ComicHelper.Item i : ComicHelper.manifest()) for (String p : i.paths()) { Path f = ComicHelper.models().resolve(p); Files.createDirectories(f.getParent()); Files.writeString(f, "x"); line.accept("fetched  " + p + " " + (p.endsWith(".bin") ? 444135475 : 1200) + " bytes"); }
                return new Searx.Result(0, "");
            }
            if (cmd.contains("check")) return new Searx.Result(0, "{\"piece\": \"torch\", \"kind\": \"package\", \"ok\": true, \"sec\": 0.5}\n{\"checked\": 32, \"failed\": 0, \"sec\": 1.5}\n");
            return new Searx.Result(0, "");
        };
    }

    @Test void aSetUpMakesTheEnvironmentFetchesByTheManifestLoadsOnceAndLeavesItsMarker() throws Exception {
        List<List<String>> ran = new ArrayList<>(); List<String> said = new ArrayList<>(); List<Boolean> tempThere = new ArrayList<>();
        ComicHelper.Run steps = setUp(ran);
        ComicHelper.RUN = (cmd, line) -> { tempThere.add(Files.isDirectory(ComicHelper.setupTemp())); return steps.run(cmd, line); };
        assertEquals("ready", ComicHelper.install(said::add));
        assertTrue(ComicHelper.installed());
        for (String f : ComicHelper.FILES) assertArrayEquals(ComicHelper.resource(f), Files.readAllBytes(ComicHelper.dir().resolve(f)), f + " is installed as the build has it");
        assertEquals(ComicHelper.markerText(), Files.readString(ComicHelper.ready()));
        assertTrue(ComicHelper.dir().startsWith(Config.home()), "kept under the program's own folder: " + ComicHelper.dir());
        // the steps, in order: the environment, torch's processor build from its own index, the other packages, the fetch, the check
        assertEquals(5, ran.size(), ran.toString());
        assertTrue(ran.get(0).containsAll(List.of("-m", "venv")), ran.get(0).toString());
        assertTrue(ran.get(1).containsAll(List.of("install", "torch", "--index-url", "https://download.pytorch.org/whl/cpu")), ran.get(1).toString());
        assertTrue(ran.get(2).containsAll(List.of("install", "manga-ocr", "fugashi", "unidic-lite", "onnxruntime", "opencv-python-headless", "pillow", "numpy", "pyyaml", "budoux")), ran.get(2).toString());
        assertTrue(ran.get(1).contains("--no-cache-dir") && ran.get(2).contains("--no-cache-dir"), "pip leaves no copy of its downloads in the person's own cache folder, outside the helper's and in no size the question says: " + ran.subList(1, 3));
        assertEquals(List.of(ComicHelper.python().toString(), "-I", "-X", "utf8", ComicHelper.script().toString(), "fetch", "--to", ComicHelper.models().toString(), "--user-agent", ComicHelper.USER_AGENT), ran.get(3), "the helper is run in Python's isolated mode and its UTF-8 mode, and fetches as the program names itself");
        // pip is run by the environment's own Python in its isolated mode, as the helper is, never as the environment's pip script: what it compiles
        // of the packages then lies in the environment, where the helper reads it (Apple's Python wrote 133 MB of it to its own cache folder instead)
        for (int i : new int[]{1, 2}) assertEquals(List.of(ComicHelper.python().toString(), "-I", "-m", "pip", "install"), ran.get(i).subList(0, 5), ran.get(i).toString());
        // the last step loads every piece once: each package of the list by the name Python imports it by, and the models folder, whose manifest names the rest
        assertEquals(ComicHelper.checkCommand(), ran.get(4));
        assertEquals(List.of(ComicHelper.python().toString(), "-I", "-X", "utf8", ComicHelper.script().toString(), "check", "--models", ComicHelper.models().toString(), "--threads", "4"), ran.get(4).subList(0, 10));
        assertEquals(List.of("--import", "torch", "--import", "manga_ocr", "--import", "fugashi", "--import", "unidic_lite", "--import", "onnxruntime", "--import", "cv2", "--import", "PIL", "--import", "numpy", "--import", "yaml", "--import", "budoux"), ran.get(4).subList(10, ran.get(4).size()));
        // the set-up's own temporary folder and the folder for what a Python without -I compiles: there while its commands run, gone when it ends
        assertEquals(List.of(true, true, true, true, true), tempThere, "every command of the set-up found the set-up's temporary folder made");
        assertFalse(Files.exists(ComicHelper.setupTemp()), "and it is removed when the set-up ends"); assertFalse(Files.exists(ComicHelper.pycache()));
        assertTrue(ran.get(1).contains("--upgrade") && ran.get(2).contains("--upgrade"), "an environment made now takes each package's current release");
        String lines = String.join("\n", said);
        assertTrue(lines.contains("Installing its Python packages (a download of about 450 MB); this takes some minutes."), lines);
        assertTrue(lines.contains("Fetching the models, the fonts, the hyphenation patterns and the word lists (" + ComicHelper.size(bytes()) + ")."), lines);
        assertTrue(lines.contains("  fetched manga-ocr-base (444 MB)"), "a large file fetched is said as it comes: " + lines);
        assertFalse(lines.contains("vocab.txt"), "a small one is not: " + lines);
        assertEquals("  fetching manga-ocr-base, 134 MB of 444 MB", ComicHelper.fetchedSaid("fetching manga-ocr-base/pytorch_model.bin 134217728 444135475"), "and a large one says how far it is");
        assertEquals("", ComicHelper.fetchedSaid("have     detector/detector_int8.onnx"));
        // a thing of two files, a reader and its small list of characters: the line that says it is fetched gives the thing's size, the one the list of pieces gives, not its larger file's
        ComicHelper.Item korean = ComicHelper.manifest().stream().filter(i -> i.name().equals("reader-korean")).findFirst().orElseThrow();
        assertEquals("  fetched " + korean.title() + " (" + ComicHelper.size(korean.bytes()) + ")", ComicHelper.fetchedSaid("fetched ppocr/korean/inference.onnx 13418787"));
        assertTrue(ComicHelper.pieces().stream().anyMatch(p -> p.contains(korean.title() + ", " + ComicHelper.size(korean.bytes()) + ",")), "the same figure as the list's");
        assertEquals("", ComicHelper.fetchedSaid("fetched ppocr/korean/inference.yml 96039"), "its small file is not a line of its own");
        assertTrue(lines.contains("Loading every piece once, to be sure it works."), lines);
        assertFalse(lines.contains("Every piece loads"), "a check of a second and a half says no time: " + lines);
        // set up: nothing more to fetch, nothing asked
        assertTrue(ComicHelper.needs().nothing());
        assertTrue(ComicHelper.status().startsWith("The comic helper is set up at " + ComicHelper.dir()), ComicHelper.status());
        assertEquals("on this machine, set up at " + ComicHelper.dir(), ComicHelper.statusLine());
        assertTrue(HelpersCli.statusSaid().contains("\n  the comic helper: on this machine, set up at "), HelpersCli.statusSaid());
    }

    @Test void aSetUpThatCannotGoOnSaysWhyAndKeepsWhatItFetched() throws Exception {
        List<String> said = new ArrayList<>();
        // no room on the disk: nothing is made
        ComicHelper.FREE = () -> 1L << 30;
        String r = ComicHelper.install(said::add);
        assertTrue(r.startsWith("!the disk under ") && r.contains("has 1.1 GB free and the comic helper needs about " + ComicHelper.size(bytes() + ComicHelper.PACKAGES_DISK) + ", so nothing was fetched"), r);
        assertFalse(Files.exists(ComicHelper.script()));
        ComicHelper.FREE = () -> 100L << 30;
        // no Python on the machine
        ComicHelper.RUN = (cmd, line) -> { throw new IOException("Cannot run program \"python3\": error=2, No such file or directory"); };
        assertEquals("!Python 3 was not found on this machine: install Python 3 with its venv module, and ask again", ComicHelper.install(said::add));
        // the fetch fails halfway: what came is kept, the helper is not set up, and the next question lists only what is missing
        List<List<String>> ran = new ArrayList<>();
        ComicHelper.Run whole = setUp(ran);
        ComicHelper.RUN = (cmd, line) -> {
            if (!cmd.contains("fetch")) return whole.run(cmd, line);
            for (String p : ComicHelper.byName().get("detector").paths()) { Path f = ComicHelper.models().resolve(p); Files.createDirectories(f.getParent()); Files.writeString(f, "x"); }
            return new Searx.Result(1, "fetched  detector/detector_int8.onnx 43838857 bytes\nurllib.error.URLError: <urlopen error timed out>\n");
        };
        r = ComicHelper.install(said::add);
        assertEquals("!the models could not be fetched: urllib.error.URLError: <urlopen error timed out>. What was fetched is kept; asking again fetches the rest", r);
        assertFalse(ComicHelper.installed()); assertFalse(Files.exists(ComicHelper.ready()));
        ComicHelper.Needs n = ComicHelper.needs();
        assertTrue(n.packages(), "no marker yet: the packages are installed again, which pip makes quick");
        assertFalse(n.items().contains(ComicHelper.byName().get("detector"))); assertTrue(n.items().contains(ComicHelper.byName().get("reader")));
        assertTrue(ComicHelper.pieces().get(0).startsWith("here        the model that finds the text"), "what is here is not listed as to fetch: " + ComicHelper.pieces().get(0));
        assertTrue(ComicHelper.question(Chrome.ENGLISH).get(1).startsWith("Setting it up fetches, once: its models (" + ComicHelper.size(bytes("model") - ComicHelper.byName().get("detector").bytes()) + "), the lettering fonts and hyphenation patterns (" + small() + ") and its word lists (" + ComicHelper.size(bytes("words")) + ")."), "and the question's sizes are of what is still to fetch, the packages of an environment that stands not among them: " + ComicHelper.question(Chrome.ENGLISH).get(1));
        assertTrue(ComicHelper.status().startsWith("The comic helper is not set up: a comic (a CBZ, a CBR, a ZIP or a folder of page pictures) cannot be translated until it is. researchzosho translate comics install sets it up"), ComicHelper.status());
        assertFalse(Files.exists(ComicHelper.setupTemp()), "a set-up that fails removes its temporary folder too");
        // the check itself gives no answer (the helper's own files do not start): said with its last words, and no marker
        ComicHelper.RUN = (cmd, line) -> cmd.contains("check") ? new Searx.Result(1, "Traceback (most recent call last):\nModuleNotFoundError: No module named 'comic'\n") : whole.run(cmd, line);
        assertEquals("!the check of its pieces gave no answer (ModuleNotFoundError: No module named 'comic'); researchzosho translate comics install runs it again", ComicHelper.install(said::add));
        assertFalse(ComicHelper.installed()); assertFalse(Files.exists(ComicHelper.setupTemp()));
        // everything is fetched and only the last step is undone: nothing to say yes to, and it is said as a set-up not finished, never as an earlier release's
        assertFalse(ComicHelper.needs().asks()); assertFalse(ComicHelper.setUpBefore());
        assertEquals("The comic helper's set-up was begun at " + ComicHelper.dir() + " and not finished. What is left (its last steps) is done as the first step of the next comic translated, with no question, or now by researchzosho translate comics install.", ComicHelper.status());
        assertEquals("on this machine, its set-up begun and not finished; the next comic finishes it", ComicHelper.statusLine());
    }

    @Test void anEarlierReleasesFilesAreRefreshedAndAReleaseThatFetchesOtherThingsAsksAgain() throws Exception {
        assertEquals("", ComicHelper.refresh(), "nothing set up: nothing to refresh");
        ComicHelper.RUN = setUp(new ArrayList<>());
        assertEquals("ready", ComicHelper.install(l -> { }));
        assertEquals("", ComicHelper.refresh(), "this release's files: nothing said");
        // a file of the helper as an earlier release had it, and one this release added
        Files.writeString(ComicHelper.dir().resolve("comic/setter.py"), "# an earlier release's setter\n");
        Files.delete(ComicHelper.dir().resolve("comic/picture.py"));
        assertEquals("The comic helper's files are from an earlier release; refreshed.", ComicHelper.refresh());
        assertArrayEquals(ComicHelper.resource("comic/setter.py"), Files.readAllBytes(ComicHelper.dir().resolve("comic/setter.py")));
        assertArrayEquals(ComicHelper.resource("comic/picture.py"), Files.readAllBytes(ComicHelper.dir().resolve("comic/picture.py")));
        assertTrue(ComicHelper.installed(), "a correction to the helper's own files asks nothing of the person");
        assertEquals("", ComicHelper.refresh());
        // a release whose manifest names other files, or other packages: not set up as this release has it, so the question is asked again
        Files.writeString(ComicHelper.ready(), ComicHelper.markerText().replaceFirst("files [0-9a-f]+", "files 0000"));
        assertFalse(ComicHelper.installed(), "another file, or another revision of one: asked again");
        assertTrue(ComicHelper.markerText().matches("packages [^\\n]+\\nfiles [0-9a-f]{64}\\n"), "the marker is keyed on the packages and on the files fetched with their checksums, not on the manifest's wording");
        Files.writeString(ComicHelper.ready(), ComicHelper.markerText().replace("packages " + ComicHelper.PACKAGES, "packages torch (an earlier list)"));
        assertFalse(ComicHelper.installed()); assertTrue(ComicHelper.needs().packages());
        Files.writeString(ComicHelper.ready(), ComicHelper.markerText());
        assertTrue(ComicHelper.installed());
        // a model file gone from the folder: not set up, and the question lists it
        Files.delete(ComicHelper.models().resolve("detector/detector_int8.onnx"));
        assertFalse(ComicHelper.installed());
        assertEquals(List.of("detector"), ComicHelper.needs().items().stream().map(ComicHelper.Item::name).toList());
    }

    /** What the helper's libraries keep goes under its folder, never the person's own cache folder or the machine's temporary one; pip keeps nothing. */
    @Test void whatTheHelpersLibrariesKeepIsKeptUnderItsFolder() {
        Map<String, String> env = new HashMap<>();
        ComicHelper.environment(env);
        assertEquals(ComicHelper.dir().resolve("cache").resolve("hf").toString(), env.get("HF_HOME"));
        assertEquals(ComicHelper.dir().resolve("cache").resolve("torchinductor").toString(), env.get("TORCHINDUCTOR_CACHE_DIR"), "PyTorch's compiler cache, which it otherwise makes in the temporary folder when the models load");
        assertEquals("1", env.get("PIP_NO_CACHE_DIR"), "pip's downloads stay out of the person's own cache folder, the pip that builds a package from source included");
        assertEquals(ComicHelper.dir().resolve("cache").resolve("pycache").toString(), env.get("PYTHONPYCACHEPREFIX"), "what a Python started without -I compiles: the Python of Apple's developer tools otherwise keeps it in ~/Library/Caches/com.apple.python");
        assertNull(env.get("TMPDIR"), "the helper at work keeps the machine's own temporary folder");
        // a set-up's commands have a temporary folder of the set-up's own as well, by each name a system knows it under
        Map<String, String> setup = new HashMap<>();
        ComicHelper.setupEnvironment(setup);
        String tmp = ComicHelper.dir().resolve("cache").resolve("tmp").toString();
        assertEquals(tmp, setup.get("TMPDIR")); assertEquals(tmp, setup.get("TEMP")); assertEquals(tmp, setup.get("TMP"));
        assertEquals(env.get("PYTHONPYCACHEPREFIX"), setup.get("PYTHONPYCACHEPREFIX")); assertEquals("1", setup.get("PIP_NO_CACHE_DIR"));
        assertTrue(ComicHelper.setupTemp().startsWith(ComicHelper.dir()) && ComicHelper.pycache().startsWith(ComicHelper.dir()));
    }

    /** The answer of a check in which the pieces named did not load, as {@code comic/check.py} writes it: a line a piece, then the count. */
    static String checked(String... failed) {
        StringBuilder b = new StringBuilder("{\"piece\": \"torch\", \"kind\": \"package\", \"ok\": true, \"sec\": 0.5}\n");
        for (String f : failed) b.append(f).append('\n');
        return b.append("{\"checked\": 32, \"failed\": ").append(failed.length).append(", \"sec\": 7.4}\n").toString();
    }

    /**
     * A set-up ends by loading every piece once, and its marker is written only when each loaded. Until then it started the helper and
     * took its first line for proof, which since the readers load at their first page proved the page detector alone: with PyYAML or
     * PyTorch not importing it said "set up", and the first comic failed (macOS, 2026-11-14). A piece that does not load is named in a
     * plain sentence; the set-up asked again installs the package anew, and fetches again a file that is not as the manifest has it.
     */
    @Test void aSetUpLoadsEveryPieceOnceAndLeavesItsMarkerOnlyWhenEachLoads() throws Exception {
        List<List<String>> ran = new ArrayList<>(); List<String> said = new ArrayList<>();
        ComicHelper.Run whole = setUp(ran);
        String[] answer = {checked("{\"piece\": \"yaml\", \"kind\": \"package\", \"ok\": false, \"why\": \"No module named 'yaml'\", \"module\": \"yaml\", \"sec\": 0.0}")};
        ComicHelper.RUN = (cmd, line) -> { Searx.Result r = whole.run(cmd, line); return cmd.contains("check") ? new Searx.Result(answer[0].contains("\"ok\": false") ? 1 : 0, answer[0]) : r; };
        // a package that does not import: named by its own name, with the helper's reason and the command that puts it right; no marker
        assertEquals("!PyYAML does not load (No module named 'yaml'); researchzosho translate comics install puts right what does not load", ComicHelper.install(said::add));
        assertFalse(ComicHelper.installed()); assertFalse(Files.exists(ComicHelper.ready()), "no marker for a helper a piece of which does not load");
        assertEquals(List.of("pyyaml"), ComicHelper.unloaded(), "noted by pip's name, for the next set-up");
        assertTrue(ComicHelper.status().startsWith("The comic helper's set-up was begun at " + ComicHelper.dir() + " and not finished."), ComicHelper.status());
        // asked again: pip installs that package anew by itself (it takes a package whose record stands for installed and leaves it), then the list, the fetch, the check
        answer[0] = checked(); ran.clear(); said.clear();
        assertEquals("ready", ComicHelper.install(said::add));
        assertEquals(List.of(ComicHelper.python().toString(), "-I", "-m", "pip", "install", "--quiet", "--no-cache-dir", "--disable-pip-version-check", "--force-reinstall", "--no-deps", "pyyaml"), ran.get(0));
        assertEquals(5, ran.size(), ran.toString());
        assertFalse(ran.get(1).contains("--force-reinstall") || ran.get(2).contains("--force-reinstall") || ran.get(1).contains("--upgrade"), "the list itself is brought as ever: " + ran.subList(1, 3));
        assertTrue(said.contains("Installing anew what did not load at the last set-up: PyYAML."), said.toString());
        assertFalse(said.stream().anyMatch(l -> l.contains("this release's list") || l.contains("Finishing the install")), "a repair says its own line and no other about the packages: no list changed, and nothing was left unfinished: " + said);
        assertTrue(said.contains("  Every piece loads; that took 7 s."), "a check somebody waited for says how long it took: " + said);
        assertTrue(ComicHelper.installed()); assertFalse(Files.exists(ComicHelper.unloadedNote()), "the note goes with the check that passes");
        // a helper that is set up and whose reader's file went bad: found by the same check, named as the list of pieces names it
        ComicHelper.Checked bad = new ComicHelper.Checked(32, List.of(new ComicHelper.Unloaded("reader-korean", "model", "its file ppocr/korean/inference.onnx is not as it was fetched", "")), 3.2, "");
        assertEquals("the model that reads Korean lettering (PP-OCRv5 mobile reader, Korean) does not load (its file ppocr/korean/inference.onnx is not as it was fetched); researchzosho translate comics install puts right what does not load", bad.said());
        ComicHelper.unsetUp(bad);
        assertFalse(ComicHelper.installed()); assertEquals(List.of(), ComicHelper.unloaded(), "a file is the fetch's to bring again: no package is installed anew for it");
        // torch itself, from its own index; fugashi with the dictionary it loads; a reason that names no package of the list is every package's
        ComicHelper.noteUnloaded(new ComicHelper.Checked(10, List.of(new ComicHelper.Unloaded("torch", "package", "DLL load failed while importing _C", "torch._C"), new ComicHelper.Unloaded("fugashi", "package", "its dictionary did not split a sentence into its words", "")), 1, ""));
        assertEquals(List.of("torch", "fugashi", "unidic-lite"), ComicHelper.unloaded());
        ran.clear(); said.clear();
        assertEquals("ready", ComicHelper.install(said::add));
        assertTrue(ran.get(0).containsAll(List.of("--force-reinstall", "--no-deps", "torch", "--index-url", "https://download.pytorch.org/whl/cpu")), ran.get(0).toString());
        assertTrue(ran.get(1).containsAll(List.of("--force-reinstall", "--no-deps", "fugashi", "unidic-lite")) && !ran.get(1).contains("pyyaml"), ran.get(1).toString());
        assertTrue(said.contains("Installing anew what did not load at the last set-up: PyTorch for processors, fugashi and the Japanese dictionary unidic-lite."), said.toString());
        ComicHelper.Unloaded deep = new ComicHelper.Unloaded("reader", "model", "No module named 'tokenizers'", "tokenizers");
        assertEquals("the model that reads Japanese lettering (manga-ocr-base) does not load (No module named 'tokenizers')", new ComicHelper.Checked(12, List.of(deep), 2, "").what());
        ComicHelper.unsetUp(new ComicHelper.Checked(12, List.of(deep), 2, ""));
        assertEquals(List.of(ComicHelper.ALL_PACKAGES), ComicHelper.unloaded());
        ran.clear(); said.clear();
        assertEquals("ready", ComicHelper.install(said::add));
        assertTrue(ran.get(0).contains("--force-reinstall") && !ran.get(0).contains("--no-deps") && ran.get(1).contains("--force-reinstall") && ran.get(1).contains("manga-ocr"), "every package, with what it needs: " + ran.subList(0, 2));
        assertTrue(said.contains("Installing anew what did not load at the last set-up: every Python package (a download of about 450 MB)."), said.toString());
        // one cause is said once, as the package it is: with PyYAML gone, manga-ocr, which imports it, fails for it too
        ComicHelper.Checked twice = new ComicHelper.Checked(10, List.of(new ComicHelper.Unloaded("manga_ocr", "package", "No module named 'yaml'", "yaml"), new ComicHelper.Unloaded("yaml", "package", "No module named 'yaml'", "yaml"), new ComicHelper.Unloaded("reader-latin", "model", "No module named 'yaml'", "yaml")), 1, "");
        assertEquals("PyYAML does not load (No module named 'yaml')", twice.what());
        ComicHelper.noteUnloaded(twice);
        assertEquals(List.of("pyyaml"), ComicHelper.unloaded());
        Files.deleteIfExists(ComicHelper.unloadedNote());
        // other pieces as they are said: a font, a pattern file, a package the list does not name
        assertEquals("the lettering font for Korean (Noto Sans CJK KR Medium)", new ComicHelper.Unloaded("font-korean", "font", "its file fonts/NotoSansCJKkr-Medium.otf is missing", "").said());
        assertEquals("hyphenation patterns for German", new ComicHelper.Unloaded("hyphenation-de", "hyphenation", "it holds no pattern", "").said());
        assertEquals("the Python package regex", new ComicHelper.Unloaded("regex", "package", "No module named 'regex'", "regex").said());
        // a Python the person named is not the helper's own: its packages are theirs to install there, and it is said so
        Config.set(ComicHelper.PYTHON_SETTING, "/opt/own/python3");
        ComicHelper.Checked theirs = new ComicHelper.Checked(10, List.of(new ComicHelper.Unloaded("yaml", "package", "No module named 'yaml'", "yaml")), 1, "");
        assertFalse(theirs.mendable());
        assertEquals("PyYAML does not load (No module named 'yaml'); the helper runs on the Python that RESEARCHZOSHO_COMIC_PYTHON names, where its packages are yours to install", theirs.said());
        assertTrue(bad.mendable(), "a file of the manifest is fetched again whatever Python runs the helper");
        Config.set(ComicHelper.PYTHON_SETTING, "");
    }

    /**
     * {@code translate comics status} loads every piece once and names one that does not load. It said "set up" from the marker and
     * the files' presence alone, so a helper whose package or file went bad after its set-up was "set up" until a comic failed, while
     * the run's own sentences send a person to this command to see how the helper stands. It changes nothing: the repair is
     * {@code translate comics install}, which the sentence names. The places that only ask whether a helper was set up (a plan, the
     * form, the line in {@code researchzosho helpers}) load nothing and stay quick.
     */
    @Test void statusLoadsEveryPieceOfAHelperThatIsSetUpAndNamesOneThatDoesNotLoad(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<List<String>> ran = new ArrayList<>();
        ComicHelper.Run whole = setUp(ran);
        String[] answer = {checked()};
        ComicHelper.RUN = (cmd, line) -> { Searx.Result r = whole.run(cmd, line); return cmd.contains("check") ? new Searx.Result(answer[0].contains("\"ok\": false") ? 1 : 0, answer[0]) : r; };
        assertEquals("ready", ComicHelper.install(l -> { }));
        int[] rc = {-9};
        String[] status = {"researchzosho", "translate", "comics", "status"};
        // every piece loads: the wait is said before it, then how the helper stands, and that each piece loads; nothing else is run
        ran.clear();
        String out = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, status), false);
        assertEquals(0, rc[0], out);
        assertEquals("Loading every piece of the comic helper once, to be sure it works.\n" + ComicHelper.status() + " Every piece loads; that took 7 s.\n", out);
        assertEquals(List.of(ComicHelper.checkCommand()), ran);
        // a package that no longer imports: named, with the command that puts it right, and the answer's code says something is wrong
        answer[0] = checked("{\"piece\": \"yaml\", \"kind\": \"package\", \"ok\": false, \"why\": \"No module named 'yaml'\", \"module\": \"yaml\", \"sec\": 0.0}");
        ran.clear();
        out = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, status), false);
        assertEquals(1, rc[0], out);
        assertEquals("Loading every piece of the comic helper once, to be sure it works.\nThe comic helper at " + ComicHelper.dir() + " does not work as it stands: PyYAML does not load (No module named 'yaml'); researchzosho translate comics install puts right what does not load. A comic that needs that piece cannot be translated until it does.\n", out);
        assertEquals(List.of(ComicHelper.checkCommand()), ran, "the status looks and changes nothing: no package is installed by it");
        assertTrue(Files.exists(ComicHelper.ready()) && ComicHelper.unloaded().isEmpty(), "and it leaves the helper's marker and writes no note: the repair is install's");
        // the list of pieces follows either answer
        out = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, new String[]{"researchzosho", "translate", "comics", "status", "--list"}), false);
        assertTrue(out.contains(" does not work as it stands: PyYAML does not load") && out.contains("\n  here        "), out);
        // a helper that is not set up has nothing to load: the status is the sentence alone, as before, and no check is run
        Files.delete(ComicHelper.ready()); ran.clear();
        out = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, status), false);
        assertEquals(0, rc[0], out);
        assertFalse(out.contains("Loading every piece"), out);
        assertTrue(ran.isEmpty(), ran.toString());
        // the helper's line in `researchzosho helpers` loads nothing
        Files.writeString(ComicHelper.ready(), ComicHelper.markerText()); ran.clear();
        assertTrue(ComicHelper.statusLine().startsWith("on this machine, set up at "), ComicHelper.statusLine());
        assertTrue(ran.isEmpty(), ran.toString());
    }

    /**
     * {@code translate comics install}, asked of a helper that is set up, loads every piece once more: it said "set up already" of a
     * helper whose PyTorch no longer imported, and the comic's own failure pointed at a status that said the same (macOS, 2026-11-14).
     * What does not load is said and put right by the same command.
     */
    @Test void installAskedOfAHelperThatIsSetUpLoadsEveryPieceAndPutsRightWhatDoesNot(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<List<String>> ran = new ArrayList<>();
        ComicHelper.Run whole = setUp(ran);
        String[] answer = {checked()};
        ComicHelper.RUN = (cmd, line) -> { Searx.Result r = whole.run(cmd, line); return cmd.contains("check") ? new Searx.Result(answer[0].contains("\"ok\": false") ? 1 : 0, answer[0]) : r; };
        assertEquals("ready", ComicHelper.install(l -> { }));
        int[] rc = {-9};
        String[] install = {"researchzosho", "translate", "comics", "install"};
        // every piece loads: said, with the time when somebody waited for it, and nothing else is run
        ran.clear();
        String out = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, install), false);
        assertEquals(0, rc[0]);
        assertEquals("The comic helper is set up already, at " + ComicHelper.dir() + ". Loading every piece once, to be sure it still works.\nEvery piece loads; that took 7 s. Nothing was changed.\n", out);
        assertEquals(List.of(ComicHelper.checkCommand()), ran);
        // a package that no longer imports: said, and the same command installs it anew and checks again, with no question
        answer[0] = checked("{\"piece\": \"torch\", \"kind\": \"package\", \"ok\": false, \"why\": \"No module named 'torch'\", \"module\": \"torch\", \"sec\": 0.0}");
        ran.clear();
        List<String> answers = new ArrayList<>(List.of(checked()));   // the check after the repair passes
        ComicHelper.RUN = (cmd, line) -> {
            Searx.Result r = whole.run(cmd, line);
            if (!cmd.contains("check")) return r;
            String a = ran.stream().filter(c -> c.contains("check")).count() == 1 ? answer[0] : answers.get(0);
            return new Searx.Result(a.contains("\"ok\": false") ? 1 : 0, a);
        };
        out = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, install), false);
        assertEquals(0, rc[0], out);
        assertTrue(out.startsWith("The comic helper is set up already, at " + ComicHelper.dir() + ". Loading every piece once, to be sure it still works.\nPyTorch for processors does not load (No module named 'torch'). Putting that right now.\n"), out);
        assertTrue(out.contains("  Installing anew what did not load at the last set-up: PyTorch for processors.\n") && out.endsWith("The comic helper is set up; a comic can be translated now: researchzosho translate <volume.cbz> --to <language>.\n"), out);
        assertFalse(out.contains("Set the comic helper up now?") || out.contains("Setting it up fetches"), "nothing is asked: " + out);
        assertTrue(ran.get(1).containsAll(List.of("-m", "pip", "install", "--force-reinstall", "--no-deps", "torch")), ran.toString());
        assertTrue(ComicHelper.installed());
        // and when it still does not load after that, the command says so and the helper is not set up
        ComicHelper.RUN = (cmd, line) -> { Searx.Result r = whole.run(cmd, line); return cmd.contains("check") ? new Searx.Result(1, answer[0]) : r; };
        out = TranslationDirectionTest.out(() -> rc[0] = TranslateCli.translate(store, install), false);
        assertEquals(1, rc[0], out);
        assertTrue(out.endsWith("The comic helper was not set up: PyTorch for processors does not load (No module named 'torch'); researchzosho translate comics install puts right what does not load.\n"), out);
        assertFalse(ComicHelper.installed());
    }

    @Test void theSettingsThatLeaveItOutAreSaidInOneSentence() throws Exception {
        Config.set(ComicHelper.SETTING, "off");
        assertEquals("The comic helper is off (RESEARCHZOSHO_COMIC=off), and a comic's pages are read by nothing else: turn it on with researchzosho settings set comic on, and ask again.", ComicHelper.barred(Chrome.ENGLISH));
        assertEquals("off (RESEARCHZOSHO_COMIC=off)", ComicHelper.statusLine());
        assertTrue(ComicHelper.install(l -> { }).startsWith("!The comic helper is off"));
        Config.set(ComicHelper.SETTING, "");
        Config.set(Helpers.LOCAL, "off");
        assertTrue(ComicHelper.barred(Chrome.ENGLISH).startsWith("This machine is set to run no heavy helper (local_helpers off), and the comic helper runs only on the library's own machine in this version"), ComicHelper.barred(Chrome.ENGLISH));
        assertTrue(ComicHelper.statusLine().startsWith("not run here (local_helpers off)"));
        Config.set(Helpers.LOCAL, "");
        assertEquals("", ComicHelper.barred(Chrome.ENGLISH));
        assertEquals("on this machine, not set up yet (researchzosho translate comics install)", ComicHelper.statusLine());
        Config.set(ComicHelper.THREADS_SETTING, "2"); assertEquals(2, ComicHelper.threads());
        Config.set(ComicHelper.THREADS_SETTING, "many"); assertEquals(4, ComicHelper.threads());
        Config.set(ComicHelper.THREADS_SETTING, "");
    }
}
