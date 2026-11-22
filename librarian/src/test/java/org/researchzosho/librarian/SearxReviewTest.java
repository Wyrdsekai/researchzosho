package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The search service's settings file after a second reading of 0.8.1. The owner's rule: a file the person edited is theirs, and
 * is left as it is; a file this program wrote and nobody touched is written again when a new version would write another. Each
 * test states one behaviour a reader of the code doubted, and tries it without docker (the command lines are a stand-in's).
 */
class SearxReviewTest {
    static final String KEY = "00112233445566778899aabbccddeeff0011223344556677";

    @TempDir Path home;
    String realHome;
    final Searx.Runner realRunner = Searx.runner;
    final Map<String, String> realEngines = Searx.imageEngines;

    @BeforeEach void up() { realHome = System.getProperty("user.home"); System.setProperty("user.home", home.toString()); Config.invalidate(); Searx.imageEngines = null; }

    @AfterEach void down() { Searx.runner = realRunner; Searx.imageEngines = realEngines; Searx.settingsNote = ""; System.setProperty("user.home", realHome); Config.invalidate(); }

    /** What an image's own settings list, in short: the engines this program names, two of an avoided place, and one it has dropped. */
    static final String IMAGE = "general:\n  debug: false\nengines:\n  - name: seznam\n    engine: seznam\n  - name: naver\n    engine: naver\n  - name: yahoo\n    engine: yahoo\n  - name: wikipedia\n    engine: wikipedia\n"
            + "  - name: bing\n    engine: bing\n  - name: duckduckgo\n    engine: duckduckgo\n  - name: google\n    engine: google\n  - name: qwant\n    engine: qwant\n  - name: startpage\n    engine: startpage\n"
            + "  - name: brave\n    engine: brave\n  - name: mojeek\n    engine: mojeek\n  - name: baidu\n    engine: baidu\n  - name: yandex\n    engine: yandex\n  - name: yandex api\n    engine: yandex_api\n";

    /**
     * A person who only takes something out of the file this program wrote has edited it: an engine's entry (so that SearXNG's own
     * default for it holds again), a name out of the list of engines not loaded (putting that engine back), a comment, the whole
     * list. The file is theirs from then on: no start of the program and no start of the search writes it again, and what they took
     * out stays out. The control: the file as written is this program's own, and is written again when the places change.
     */
    @Test
    void aFileFromWhichThePersonOnlyTookSomethingOutIsTheirs() throws Exception {
        List<String> ran = new ArrayList<>();
        String[] state = {"false"};   // the container is there, stopped
        Searx.runner = cmd -> {
            ran.add(String.join(" ", cmd));
            if (cmd.contains("version")) return new Searx.Result(0, "27.0");
            if (cmd.contains("inspect") && cmd.contains(Searx.CONTAINER)) return new Searx.Result(state[0].isEmpty() ? 1 : 0, state[0]);
            if (cmd.contains("rm")) { state[0] = ""; return new Searx.Result(0, ""); }
            if (cmd.contains("cat")) return new Searx.Result(0, IMAGE);
            return new Searx.Result(1, "no docker here");   // docker run or start: the test stops there
        };
        Searx.imageEngines = Searx.enginesIn("\n" + IMAGE);
        String written = Searx.settings(Searx.avoidedEngines(Avoided.places()), KEY);
        assertEquals(Searx.Whose.OURS, Searx.whose(written), "as this program wrote it (the control)");
        assertTrue(written.contains("      - yandex\n") && written.contains("  - name: bing\n    disabled: true\n") && written.contains("      - baidu\n"), written);
        Map<String, String> edits = new LinkedHashMap<>();
        edits.put("an engine's own entry taken out (bing: SearXNG's default holds again)", written.replace("  - name: bing\n    disabled: true\n", ""));
        edits.put("a name taken out of the list of engines not loaded (yandex put back)", written.replace("      - yandex\n", ""));
        edits.put("two names taken out of that list", written.replace("      - yandex\n", "").replace("      - baidu\n", ""));
        edits.put("every entry this program switched off taken out", written.substring(0, written.indexOf("  - name: bing\n")));
        edits.put("a line of the comment taken out", written.replaceFirst("(?m)^# google cse and google cse images need a key of their own.*\\n", ""));
        edits.put("the last engine's two lines taken out", written.substring(0, written.lastIndexOf("  - name: ")));
        Map<String, String> got = new LinkedHashMap<>();
        Files.createDirectories(Searx.dir());
        Path file = Searx.dir().resolve("settings.yml");
        for (Map.Entry<String, String> e : edits.entrySet()) {
            assertFalse(e.getValue().equals(written), "the edit took something out: " + e.getKey());
            Files.writeString(file, e.getValue());
            state[0] = "false"; ran.clear();
            String whose = Searx.whose(e.getValue()).name();
            Searx.upgrade();
            Searx.start(47999, false);
            boolean kept = Files.readString(file).equals(e.getValue()), remade = ran.stream().anyMatch(c -> c.contains(" rm "));
            got.put(e.getKey(), whose + (kept ? ", left as it is" : ", WRITTEN AGAIN") + (remade ? ", the container made again" : ""));
        }
        System.out.println("[review] B-180 a settings file from which the person only took something out: " + got);
        for (Map.Entry<String, String> e : got.entrySet()) assertEquals("THEIRS, left as it is", e.getValue(), e.getKey());
        // the control for the other side: the untouched file is written again when the avoided places change, its key kept
        Files.writeString(file, written);
        Config.set(Avoided.SETTING, "CN");
        state[0] = "false"; ran.clear();
        Searx.start(47999, false);
        String now = Files.readString(file);
        assertTrue(!now.equals(written) && !now.contains("      - yandex\n") && now.contains("      - baidu\n") && now.contains("  secret_key: \"" + KEY + "\"\n"), "the untouched file is written again for the places as they are now: " + now);
        assertEquals(Searx.Whose.OURS, Searx.whose(now));
    }

    /**
     * On an upgrade with no container there, the file is written before anything read the image's own list of engines. Nothing
     * runs on that file: the next start of the search reads the image's list first and writes the file by it, before it makes the
     * container. So the settings a container ever reads name the image's engines, and leave out every engine the image has in an
     * avoided place (the one a later image added among them).
     */
    @Test
    void theFileAContainerReadsIsWrittenByTheImagesOwnListAlsoAfterAnUpgradeWithNoContainer() throws Exception {
        List<String> ran = new CopyOnWriteArrayList<>();
        List<String> readWhenMade = new ArrayList<>();
        String[] state = {""};   // no container
        Path file = Searx.dir().resolve("settings.yml");
        Searx.runner = cmd -> {
            ran.add(String.join(" ", cmd));
            if (cmd.contains("version")) return new Searx.Result(0, "27.0");
            if (cmd.contains("inspect") && cmd.contains(Searx.CONTAINER)) return new Searx.Result(state[0].isEmpty() ? 1 : 0, state[0]);
            if (cmd.contains("cat")) return new Searx.Result(0, IMAGE);
            if (cmd.contains("run") && cmd.contains("-d")) { readWhenMade.add(Files.readString(file)); return new Searx.Result(1, "the test stops here"); }
            return new Searx.Result(1, "no docker here");
        };
        Files.createDirectories(Searx.dir());
        Files.writeString(file, Searx.earlier(true, KEY));   // 0.8.0's own, never edited
        String did = Searx.upgrade();
        String afterUpgrade = Files.readString(file);
        assertTrue(did.startsWith(Searx.WRITTEN_AGAIN) && readWhenMade.isEmpty(), "the upgrade wrote the file and made no container: " + did + " / " + ran);
        Searx.start(47999, false);
        assertEquals(1, readWhenMade.size(), "the start made the container once: " + ran);
        String read = readWhenMade.get(0);
        System.out.println("[review] B-226 after an upgrade with no container: the file named presearch (not in the image): " + afterUpgrade.contains("presearch") + ", left out yandex api (in the image): " + afterUpgrade.contains("- yandex api")
                + "; the file the container was made on: presearch " + read.contains("presearch") + ", yandex api left out " + read.contains("      - yandex api\n"));
        assertTrue(read.contains("      - yandex api\n") && read.contains("      - yandex\n") && read.contains("      - baidu\n"), "every engine the image has in an avoided place is out of what the container reads: " + read);
        assertFalse(read.contains("presearch"), "and no engine the image does not have is named: " + read);
        assertTrue(read.contains("  secret_key: \"" + KEY + "\"\n"), "its key kept");
    }

    /**
     * The image's own list of engines is read through docker once. A reading that fails (docker busy at that moment) is not
     * remembered as "the image has none": the next start reads again. And two starts at once make one reading, not two.
     */
    @Test
    void aFailedReadingOfTheImagesListIsTriedAgainAndTwoAtOnceReadOnce() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        boolean[] fails = {true};
        CountDownLatch inside = new CountDownLatch(1), go = new CountDownLatch(1);
        boolean[] hold = {false};
        Searx.runner = cmd -> {
            if (!cmd.contains("cat")) return new Searx.Result(0, "");
            reads.incrementAndGet();
            if (hold[0]) { inside.countDown(); go.await(10, TimeUnit.SECONDS); }
            return fails[0] ? new Searx.Result(125, "docker: Error response from daemon") : new Searx.Result(0, IMAGE);
        };
        Searx.readImageEngines();
        assertEquals(null, Searx.known(), "a reading that failed gives no list");
        fails[0] = false;
        Searx.readImageEngines();
        boolean again = Searx.known() != null && Searx.known().contains("yandex api");
        int afterTwo = reads.get();
        Searx.readImageEngines();
        int afterThree = reads.get();
        // two at once
        Searx.imageEngines = null; reads.set(0); hold[0] = true;
        Thread a = new Thread(Searx::readImageEngines), b = new Thread(Searx::readImageEngines);
        a.start();
        assertTrue(inside.await(10, TimeUnit.SECONDS), "the first reading is on its way");
        b.start();
        Thread.sleep(300);   // the second arrives while the first is still reading
        go.countDown();
        a.join(10_000); b.join(10_000);
        System.out.println("[review] B-319, B-316 the image's list: read again after a reading that failed: " + again + " (" + afterTwo + " readings, then " + afterThree + " once it is known); two starts at once made " + reads.get() + " reading(s)");
        assertTrue(again, "the reading that failed was remembered for good");
        assertEquals(2, afterTwo, "one failed, one read");
        assertEquals(2, afterThree, "and a list once read is not read again (the control)");
        assertEquals(1, reads.get(), "two at once read the image twice");
        assertTrue(Searx.known() != null && Searx.known().contains("yandex api"));
    }

    /**
     * An engine's name is read from the image's settings as SearXNG reads it, and written into this program's file so that
     * SearXNG reads the same name back: a name in a matched pair of quotes is the name inside them, anything else is taken as it
     * stands (a quote at one end only is no quoting); and a name that YAML would read as something else (a colon and a space in
     * it, a mark at its beginning, a word that means yes or no, a number) is written in quotes. The control: the plain names of
     * the real images are written as they are.
     */
    @Test
    void anEnginesNameIsReadAndWrittenAsTheNameItIs() throws Exception {
        Map<String, String> read = Searx.enginesIn("\nengines:\n  - name: \"baidu images\"\n    engine: baidu\n  - name: 'sogou wechat'\n    engine: sogou_wechat\n  - name: \"quark'\n    engine: quark\n  - name: yandex's\n    engine: yandex\n  - name: 'it''s yandex'\n    engine: yandex\n  - name: plain name\n    engine: baidu\n");
        System.out.println("[review] B-338 names read from an image's settings: " + read.keySet());
        assertTrue(read.containsKey("baidu images") && read.containsKey("sogou wechat") && read.containsKey("plain name"), "a matched pair of quotes is taken off (the control): " + read);
        assertTrue(read.containsKey("\"quark'") && !read.containsKey("quark"), "a quote of one kind at one end and another at the other is no quoting: " + read.keySet());
        assertTrue(read.containsKey("yandex's") && read.containsKey("it's yandex"), "an apostrophe inside a name, plain and doubled in single quotes: " + read.keySet());
        // written: whatever an image calls an engine of an avoided place, the file names it so that it is read back as that name
        Map<String, String> odd = new LinkedHashMap<>();
        for (String name : List.of("yandex: news", "- yandex", "#yandex", "no", "360", "yandex \"images\"", "yandex's", "yandex # ru", " yandex", "yandex\\api", "[yandex]", "{yandex}", "yandex,ru", "*yandex", "&yandex", "!yandex", "|yandex", ">yandex", "%yandex", "@yandex", "`yandex`", "null", "~", "1e3", "0x1f", "true", "yandex:"))
            odd.put(name, "yandex");
        odd.put("yandex images", "yandex"); odd.put("360search", "360search"); odd.put("z-library", "yandex"); odd.put("lib.rs", "yandex"); odd.put("1337x", "yandex");
        Searx.imageEngines = odd;
        String s = Searx.settings(Searx.avoidedEngines(Set.of("RU", "CN")), KEY);
        Path look = Path.of("build", "tmp", "searx-settings-odd-names.yml");   // kept for a look, and for a reading by a YAML reader outside the suite
        Files.createDirectories(look.getParent()); Files.writeString(look, s);
        List<String> back = Searx.removedIn(s);
        List<String> lost = new ArrayList<>(odd.keySet()); lost.removeAll(back);
        List<String> plainAsBefore = new ArrayList<>();
        for (String name : List.of("yandex images", "360search", "z-library", "lib.rs", "1337x")) if (s.contains("\n      - " + name + "\n")) plainAsBefore.add(name);
        List<String> raw = new ArrayList<>();
        for (String name : List.of("yandex: news", "- yandex", "#yandex", "no", "360", "yandex # ru", "null", "~", "true", "[yandex]", "yandex:")) if (s.contains("\n      - " + name + "\n")) raw.add(name);
        System.out.println("[review] B-101 names an image might give its engines, " + odd.size() + " of them: not read back as written " + lost + "; written with no quotes though YAML reads them otherwise " + raw + "; plain names written plain " + plainAsBefore);
        assertEquals(List.of(), lost, "a name is not read back as it was");
        assertEquals(List.of(), raw, "a name YAML reads as something else is written bare");
        assertEquals(List.of("yandex images", "360search", "z-library", "lib.rs", "1337x"), plainAsBefore, "a plain name is written as it always was (the control)");
        assertEquals(Searx.Whose.OURS, Searx.whose(s), "and the file is still told as this program's own");
    }

    /**
     * What the status says a settings file still loads is read from the file as SearXNG reads it: the list of engines to remove
     * under use_default_settings, or the list of the only ones to keep; a word in a comment or a list of another meaning is neither.
     * The control: this program's own file loads none, and a file that takes SearXNG's defaults whole loads all.
     */
    @Test
    void whatAFileStillLoadsIsReadAsSearxngReadsIt() throws Exception {
        Searx.imageEngines = Searx.enginesIn("\n" + IMAGE);
        Set<String> places = Set.of("RU", "CN");
        List<String> avoided = Searx.avoidedEngines(places);
        assertEquals(List.of("baidu", "yandex", "yandex api"), avoided, "the engines of the avoided places in this image (the instrument)");
        Files.createDirectories(Searx.dir());
        Path file = Searx.dir().resolve("settings.yml");
        Map<String, String> files = new LinkedHashMap<>();
        Map<String, List<String>> expected = new LinkedHashMap<>();
        files.put("this program's own", Searx.settings(avoided, KEY)); expected.put("this program's own", List.of());
        files.put("SearXNG's defaults whole", "use_default_settings: true\nserver:\n  secret_key: \"x\"\n"); expected.put("SearXNG's defaults whole", avoided);
        files.put("a remove list that names two", "use_default_settings:\n  engines:\n    remove:\n      - baidu\n      - \"yandex\"\nserver:\n  limiter: false\n"); expected.put("a remove list that names two", List.of("yandex api"));
        files.put("a remove list written on one line", "use_default_settings:\n  engines:\n    remove: [baidu, 'yandex', \"yandex api\"]\n"); expected.put("a remove list written on one line", List.of());
        files.put("the word in a comment, and the names in a list of what to keep", "# I chose not to remove: these stay\nuse_default_settings:\n  engines:\n    keep_only:\n      - yandex\n      - wikipedia\n"); expected.put("the word in a comment, and the names in a list of what to keep", List.of("yandex"));
        files.put("a list of the only engines to keep, none of them avoided", "use_default_settings:\n  engines:\n    keep_only:\n      - wikipedia\n      - seznam\n"); expected.put("a list of the only engines to keep, none of them avoided", List.of());
        files.put("the names under another key after a remove list that names none of them", "use_default_settings:\n  engines:\n    remove:\n      - bing\nplugins:\n  - baidu\n  - yandex\n  - yandex api\n"); expected.put("the names under another key after a remove list that names none of them", avoided);
        files.put("an engine switched off, which SearXNG still loads", "use_default_settings: true\nengines:\n  - name: yandex\n    disabled: true\n"); expected.put("an engine switched off, which SearXNG still loads", avoided);
        files.put("a file that lists its own engines in full", "use_default_settings: false\nengines:\n  - name: wikipedia\n    engine: wikipedia\n"); expected.put("a file that lists its own engines in full", List.of());
        Map<String, List<String>> got = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : files.entrySet()) { Files.writeString(file, e.getValue()); got.put(e.getKey(), Searx.stillLoaded(file, places)); }
        System.out.println("[review] B-364 the engines of an avoided place a settings file still loads: " + got);
        assertEquals(expected, got);
    }
}
