package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
/** SearXNG through Docker: the settings ResearchZosho writes, and the docker command lines it runs, without docker. */
class SearxTest {

    final List<List<String>> ran = new ArrayList<>();
    final Searx.Runner real = Searx.runner;

    @AfterEach void restore() { Searx.runner = real; }

    @Test
    void theSettingsTurnTheJsonFormatOnAndKeepTheDefaults() {
        String s = Searx.settings(List.of());
        assertTrue(s.contains("use_default_settings: true"), s);
        assertTrue(s.contains("- json"), "the search tool needs json in search.formats: " + s);
        assertTrue(s.contains("secret_key: \"") && !s.contains("secret_key: \"\""), "a secret key is generated: " + s);
        assertNotEquals(Searx.settings(), s, "each call makes its own key");
        assertTrue(s.contains("search:\n  safe_search: 1\n"), "safe search is moderate for the engines that have it: " + s);
    }

    @Test
    void withoutDockerStartSaysSoAndStopsThere() {
        Searx.runner = cmd -> { ran.add(cmd); throw new IOException("docker: not found"); };
        String r = Searx.start(8888);
        assertTrue(r.startsWith("!docker is not on this machine"), r);
        assertEquals(1, ran.size(), "only the version probe ran");
        assertFalse(Searx.haveDocker());
        assertEquals("absent", Searx.state());
    }

    @Test
    void theRunCommandBindsLoopbackOnlyAndRestartsWithTheMachine() {
        List<String> run = Searx.plan(8888).get(0);
        assertEquals("docker", run.get(0));
        assertTrue(run.contains("--restart") && run.contains("unless-stopped"), run.toString());
        assertTrue(run.contains("127.0.0.1:8888:8080"), "loopback only, never the whole network: " + run);
        assertTrue(run.contains(Searx.IMAGE) && run.contains(Searx.CONTAINER));
    }

    @Test
    void stateReadsDockerInspect() {
        Searx.runner = cmd -> new Searx.Result(0, cmd.contains("inspect") ? "true\n" : "");
        assertEquals("running", Searx.state());
        Searx.runner = cmd -> new Searx.Result(0, cmd.contains("inspect") ? "false\n" : "");
        assertEquals("stopped", Searx.state());
        Searx.runner = cmd -> new Searx.Result(1, "Error: No such object");
        assertEquals("absent", Searx.state());
    }

    /**
     * The settings this program writes take the engines based in a place the library avoids out of what SearXNG loads (0.8.1): the
     * person's own SearXNG cannot reach them, not even from its own page. Which places is the person's setting; unset, China, Hong Kong
     * and Macau.
     */
    @Test
    void theSettingsLeaveOutTheEnginesBasedInAPlaceTheLibraryAvoids(@TempDir Path home) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString()); Config.invalidate();
        try {
            String s = Searx.settings();
            assertTrue(s.contains("use_default_settings:\n  engines:\n    remove:\n"), s);
            for (String name : List.of("baidu", "baidu images", "baidu kaifa", "sogou", "sogou images", "sogou videos", "sogou wechat", "360search", "360search videos", "quark", "quark images", "bilibili", "iqiyi", "acfun", "chinaso news"))
                assertTrue(s.contains("\n      - " + name + "\n"), name + " is taken out: " + s);
            // Russia is one of the places avoided unless the person says otherwise: its engines go too, by module and by name, and are not switched on
            for (String name : List.of("yandex", "yandex images", "yandex music", "habrahabr", "kozmonavt")) assertTrue(s.contains("\n      - " + name + "\n"), name + " is taken out: " + s);
            assertFalse(s.contains("  - name: yandex\n"), "an engine that is not loaded is not switched on: " + s);
            assertFalse(s.contains("      - naver") || s.contains("      - seznam"), "an engine of a place that is not avoided stays: " + s);
            assertTrue(s.contains("  - name: seznam\n    disabled: false\n") && s.contains("  - name: naver\n    disabled: false\n") && s.contains("  - name: yahoo\n    disabled: false\n") && s.contains("  - name: wikipedia\n    disabled: false\n"), "and the rest of the measured set stays on: " + s);
            assertTrue(s.contains("  - name: google cse\n    disabled: true\n"), "google cse answers nothing without a key and is not sent the words: " + s);
            // the person names fewer places: the engines of the others stay, and Yandex is switched on again as measured
            Config.set(Avoided.SETTING, "CN, HK, MO");
            s = Searx.settings();
            assertTrue(s.contains("\n      - baidu\n") && !s.contains("      - yandex") && s.contains("  - name: yandex\n    disabled: false\n"), s);
            // no place avoided: SearXNG's defaults whole, as before 0.8.1
            Config.set(Avoided.SETTING, "none");
            assertEquals(Set.of(), Avoided.places());
            assertTrue(Searx.settings().contains("use_default_settings: true\n"), Searx.settings());
        } finally { Config.set(Avoided.SETTING, ""); System.setProperty("user.home", real); Config.invalidate(); }
    }

    @Test
    void aPictureSearchSendsItsWordsAsWordsToo() {
        assertTrue(Images.searxUrl("!bdi red fox :zh").contains("&q=bdi+red+fox+zh&"), Images.searxUrl("!bdi red fox :zh"));
    }

    @Test
    void theEnginesToLeaveOutAreNamedAsTheImageNamesThem() {
        // an image whose settings name another engine of a recorded module: it is left out too, by the module, not by a list of names
        String yaml = "general:\n  debug: false\nengines:\n  - name: baidu\n    engine: baidu\n    shortcut: bd\n  - name: baidu news\n    engine: baidu\n    baidu_category: news\n"
                + "  - name: wikipedia\n    engine: wikipedia\n  - name: 'sogou wechat'\n    engine: sogou_wechat\n  - name: mojeek\n    shortcut: mjk\n";
        Map<String, String> read = Searx.enginesIn(yaml);
        assertEquals(Map.of("baidu", "baidu", "baidu news", "baidu", "wikipedia", "wikipedia", "sogou wechat", "sogou_wechat", "mojeek", "mojeek"), read);
        Map<String, String> was = Searx.imageEngines;
        Searx.imageEngines = read;
        try { assertEquals(List.of("baidu", "baidu news", "sogou wechat"), Searx.avoidedEngines(Set.of("CN"))); }
        finally { Searx.imageEngines = was; }
        assertEquals(List.of(), Searx.avoidedEngines(Set.of()), "no place avoided: nothing left out");
        assertEquals(15, Searx.avoidedEngines(Set.of("CN", "HK", "MO")).size(), "the recorded names, when no image was read: " + Searx.avoidedEngines(Set.of("CN", "HK", "MO")));
    }

    /** A settings file from before 0.8.1 is never rewritten unasked; status says what it still loads and the one command. */
    @Test
    void aSettingsFileThatStillLoadsThoseEnginesIsSaidWithTheCommandThatWritesItAgain(@TempDir Path home) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString()); Config.invalidate();
        try {
            assertEquals("", Searx.avoidedNote(), "no settings file: nothing to say");
            Files.createDirectories(Searx.dir());
            Path file = Searx.dir().resolve("settings.yml");
            Files.writeString(file, Searx.settings(List.of()));   // as 0.8.0 wrote it
            String before = Files.readString(file);
            String note = Searx.avoidedNote();
            assertTrue(note.contains("still load 20 search engines based in China, Hong Kong, Macao, Cuba, Iran, North Korea, Russia and Venezuela (") && note.contains("baidu") && note.contains("yandex") && note.contains("`researchzosho search start --fresh`"), note);
            assertTrue(note.contains("If you put them there yourself, nothing needs doing"), note);
            assertEquals(before, Files.readString(file), "the file is not touched");
            assertEquals(20, Searx.stillLoaded(file, Avoided.places()).size());
            Files.writeString(file, Searx.settings());   // as 0.8.1 writes it
            assertEquals("", Searx.avoidedNote());
            // the person took one name back out of the remove list: that one is said
            Files.writeString(file, Files.readString(file).replace("      - baidu\n", ""));
            assertEquals(List.of("baidu"), Searx.stillLoaded(file, Avoided.places()));
            // a file that lists its own engines in full names what it wants
            Files.writeString(file, "use_default_settings: false\nengines:\n  - name: wikipedia\n    engine: wikipedia\n");
            assertEquals("", Searx.avoidedNote());
        } finally { System.setProperty("user.home", real); Config.invalidate(); }
    }

    /**
     * A settings file that is there when a new version starts is this program's own, unchanged, or the person's. Told apart by the
     * text alone: with its secret key read out of it, the file is letter for letter what an earlier version wrote (two texts, 2026-09-09
     * and 2026-09-23 to 0.8.0), or what this version writes for the engines its own remove list names; anything else is the person's.
     */
    @Test
    void aSettingsFileIsOursOnlyWhenNotALetterOfItChanged() {
        String key = "00112233445566778899aabbccddeeff0011223344556677";
        String as080 = Searx.earlier(true, key), asFirst = Searx.earlier(false, key);
        assertTrue(as080.contains("  - name: yandex\n    disabled: false\n") && as080.contains("  safe_search: 1\n") && !asFirst.contains("safe_search"), "the two texts the versions before 0.8.1 wrote");
        assertEquals(Searx.Whose.EARLIER, Searx.whose(as080));
        assertEquals(Searx.Whose.EARLIER, Searx.whose(asFirst));
        assertEquals(Searx.Whose.EARLIER, Searx.whose(as080.replace("\n", "\r\n")), "the same text with another machine's line ends");
        assertEquals(Searx.Whose.OURS, Searx.whose(Searx.settings(List.of("baidu", "yandex"), key)));
        assertEquals(Searx.Whose.OURS, Searx.whose(Searx.settings(List.of(), key)));
        // the person's: a comment added, an engine switched, an engine put back
        assertEquals(Searx.Whose.THEIRS, Searx.whose(as080 + "# mine\n"));
        assertEquals(Searx.Whose.THEIRS, Searx.whose(as080.replace("  - name: bing\n    disabled: true\n", "  - name: bing\n    disabled: false\n")));
        assertEquals(Searx.Whose.THEIRS, Searx.whose(Searx.settings(List.of("baidu", "yandex"), key).replace("      - yandex\n", "") + "  - name: yandex\n    disabled: false\n"));
        assertEquals(Searx.Whose.THEIRS, Searx.whose("use_default_settings: true\n"));
    }

    /**
     * `search status` says every time which of three the settings file is, read off the file itself: this version's own, unchanged,
     * with the engines of the avoided places left out (named); this program's and unchanged but not what this version writes (0.8.0's,
     * or this version's written before a place was added to those avoided), which the service's next start or `search start` writes
     * again; the person's, left as it is, with the command that replaces it. Nothing when there is no file. With no place avoided the
     * first says nothing of places. In Japanese each is the language file's sentence. The status reads no image: the recorded names
     * of the engines stand in, as in a command of its own.
     */
    @Test
    void searchStatusSaysEveryTimeWhoseTheSettingsFileIs(@TempDir Path home) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString()); Config.invalidate();
        Map<String, String> engines = Searx.imageEngines;
        try {
            Searx.imageEngines = null;
            String places = Avoided.names(Avoided.places()), placesJa = Avoided.names(Avoided.places(), Chrome.in("ja"));   // the places avoided when nothing is set
            String key = "00112233445566778899aabbccddeeff0011223344556677";
            Path file = Searx.dir().resolve("settings.yml");
            String nothing = Searx.settingsSaid();
            boolean nothingIsThePersons = Searx.settingsAreThePersons();
            Files.createDirectories(Searx.dir());
            Files.writeString(file, Searx.earlier(true, key));
            String earlier = Searx.settingsSaid();
            boolean earlierIsThePersons = Searx.settingsAreThePersons();
            String earlierStillLoads = Searx.avoidedNote();
            Files.writeString(file, Searx.settings(Searx.avoidedEngines(Avoided.places()), key));
            String ours = Searx.settingsSaid();
            Chrome.here("ja");
            String oursJa = Searx.settingsSaid();
            Chrome.clear();
            Config.set(Avoided.SETTING, "CN, HK, MO");   // written while Russia was not among the places avoided, then added
            Files.writeString(file, Searx.settings(Searx.avoidedEngines(Avoided.places()), key));
            String forFewer = Searx.settingsSaid();
            Config.set(Avoided.SETTING, "");
            String placeAdded = Searx.settingsSaid();
            Files.writeString(file, Searx.settings(Searx.avoidedEngines(Avoided.places()), key) + "# mine\n");
            String theirs = Searx.settingsSaid();
            boolean theirsIsThePersons = Searx.settingsAreThePersons();
            Config.set(Avoided.SETTING, "none");
            Files.writeString(file, Searx.settings(List.of(), key));
            String noPlace = Searx.settingsSaid();
            System.out.println("[searx] the settings file in search status: none " + List.of(nothing) + "; 0.8.0's: " + earlier + " | this version's: " + ours + " | in Japanese: " + oursJa + " | for fewer places: " + forFewer
                    + " | a place added since: " + placeAdded + " | the person's: " + theirs + " | no place avoided: " + noPlace);
            assertEquals("", nothing, "no file, nothing said");
            // the note on what a file still loads says "if you put them there yourself, nothing needs doing": search status adds it for the person's file only
            assertFalse(nothingIsThePersons, "no file is nobody's");
            assertFalse(earlierIsThePersons, "0.8.0's own file, unedited, is not the person's: it is written again, and the note for an edited file is not put under that");
            assertFalse(earlierStillLoads.isEmpty(), "though it does still load such engines (the control: the note has something to say of it)");
            assertTrue(theirsIsThePersons, "the edited file is the person's");
            String again = "SearXNG's settings (" + file + ") are ones ResearchZosho wrote and nobody changed, but not the ones this version writes. They are written again the next time the library's service starts, or now by researchzosho search start.";
            assertEquals(again, earlier, "0.8.0's own file");
            assertEquals("SearXNG's settings (" + file + ") are the ones ResearchZosho wrote for this version, unchanged. They leave out the search engines based in " + places + ".", ours);
            assertEquals(ours.replace(places, "China, Hong Kong and Macao"), forFewer, "this version's for the places avoided then");
            assertEquals(again, placeAdded, "a place added since: it still loads that place's engines, and is written again");
            assertEquals("using the settings.yml already in " + Searx.dir() + " as it is (edited since ResearchZosho wrote it, or not its own; `researchzosho search start --fresh` replaces it with the measured engine set)", theirs, "the person's: the sentence search start says");
            assertEquals("SearXNG's settings (" + file + ") are the ones ResearchZosho wrote for this version, unchanged.", noPlace);
            assertTrue(!oursJa.contains("ResearchZosho wrote") && !oursJa.contains("They leave out") && oursJa.contains(file.toString()) && oursJa.contains(placesJa), "in Japanese, the language file's sentences: " + oursJa);
        } finally { Chrome.clear(); Searx.imageEngines = engines; Config.set(Avoided.SETTING, ""); System.setProperty("user.home", real); Config.invalidate(); }
    }

    /**
     * On an upgrade: a file an earlier version wrote, unchanged, is written again without the engines of the avoided places, its key
     * kept, and the container is made again so that it reads it. A file the person edited is left to the letter, with Yandex in it if
     * they put it there, however often the program starts; the notice says what it loads.
     */
    @Test
    void anEarlierVersionsOwnFileIsWrittenAgainAndAnEditedOneIsLeftAlone(@TempDir Path home) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString()); Config.invalidate();
        Searx.Runner was = Searx.runner;
        List<String> ran = new ArrayList<>();
        String[] state = {"true"};   // the container runs
        Searx.runner = cmd -> {
            ran.add(String.join(" ", cmd));
            if (cmd.contains("version")) return new Searx.Result(0, "27.0");
            if (cmd.contains("inspect")) return new Searx.Result(state[0].isEmpty() ? 1 : 0, state[0]);
            if (cmd.contains("rm")) { state[0] = ""; return new Searx.Result(0, ""); }
            if (cmd.contains("cat")) return new Searx.Result(1, "");   // the image's own list cannot be read: the recorded names are used
            return new Searx.Result(1, "no docker here");   // docker run: the test stops there
        };
        try {
            Files.createDirectories(Searx.dir());
            Path file = Searx.dir().resolve("settings.yml");
            String key = "00112233445566778899aabbccddeeff0011223344556677";
            // 0.8.0's own file, never edited
            Files.writeString(file, Searx.earlier(true, key));
            String did = Searx.upgrade();
            String now = Files.readString(file);
            assertTrue(did.startsWith(Searx.WRITTEN_AGAIN), did + " / " + ran);
            // said once where a person looks after an upgrade: the status command reads it, and it is gone
            assertEquals(did, Searx.afterUpgrade());
            assertEquals("", Searx.afterUpgrade());
            assertTrue(now.contains("  secret_key: \"" + key + "\"\n"), "its key is kept: " + now);
            assertTrue(now.contains("\n      - yandex\n") && now.contains("\n      - baidu\n") && !now.contains("  - name: yandex\n"), "Yandex and the engines of the other avoided places are out: " + now);
            assertTrue(ran.stream().anyMatch(c -> c.equals("docker rm -f " + Searx.CONTAINER)), "the container is made again so that it reads the file: " + ran);
            assertEquals(Searx.Whose.OURS, Searx.whose(now));
            ran.clear();
            assertEquals("", Searx.upgrade(), "and the next start finds nothing to do");
            assertEquals(now, Files.readString(file));
            assertTrue(ran.stream().noneMatch(c -> c.contains(" rm ") || c.contains(" run ")), ran.toString());
            // the person puts Yandex back, the one way the documentation gives: its line out of the remove list, and switched on under engines
            String theirs = now.replace("      - yandex\n", "") + "  - name: yandex\n    disabled: false\n";
            Files.writeString(file, theirs);
            state[0] = "false"; ran.clear();   // the container is there, stopped
            assertEquals("", Searx.upgrade(), "an edited file is the person's");
            Searx.start(47999, false);
            assertEquals(theirs, Files.readString(file), "neither a start of the program nor a start of the search takes the engine out again");
            assertTrue(ran.stream().noneMatch(c -> c.contains(" rm ")), "and the container is not made again: " + ran);
            assertTrue(Searx.settingsNote.contains("as it is") && Searx.avoidedNote().contains("(yandex)") && Searx.avoidedNote().contains("If you put them there yourself, nothing needs doing"), Searx.settingsNote + " / " + Searx.avoidedNote());
            // an edited file from 0.8.0, Yandex on by this program's hand but something else changed by the person: theirs, the notice only
            String edited080 = Searx.earlier(true, key).replace("  limiter: false\n", "  limiter: false\n  bind_address: \"0.0.0.0\"\n");
            Files.writeString(file, edited080);
            assertEquals("", Searx.upgrade());
            assertEquals(edited080, Files.readString(file));
            assertTrue(Searx.avoidedNote().contains("yandex"), Searx.avoidedNote());
            // only --fresh replaces it
            state[0] = "true";
            Searx.start(47999, true);
            assertEquals(Searx.Whose.OURS, Searx.whose(Files.readString(file)));
        } finally { Searx.runner = was; Searx.settingsNote = ""; System.setProperty("user.home", real); Config.invalidate(); }
    }

    /**
     * SearXNG's list of engines changes from image to image, and the program installs whichever is current: the settings it writes name
     * only engines the image on the machine has, in the remove list and in the list it switches on and off. Checked against the lists of
     * two real images: the one of July 2026 and the one of 2026-11-20 (which no longer has presearch, and has a Yandex engine more).
     */
    @Test
    void theSettingsNameOnlyEnginesTheImageHas(@TempDir Path home) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString()); Config.invalidate();
        Map<String, String> was = Searx.imageEngines;
        try {
            for (String tag : List.of("2026.7.26", "2026.11.20")) {
                String list;
                try (var in = SearxTest.class.getResourceAsStream("searxng-engines-" + tag + ".txt")) { list = "\n" + new String(in.readAllBytes(), StandardCharsets.UTF_8); }
                Searx.imageEngines = Searx.enginesIn(list);
                assertTrue(Searx.imageEngines.size() > 300, tag + ": " + Searx.imageEngines.size());
                String s = Searx.settings(Searx.avoidedEngines(Avoided.places()), "00112233445566778899aabbccddeeff0011223344556677");
                List<String> strangers = new ArrayList<>();
                for (String name : Searx.namesIn(s)) if (!Searx.imageEngines.containsKey(name)) strangers.add("engines: " + name);
                for (String name : Searx.removedIn(s)) if (!Searx.imageEngines.containsKey(name)) strangers.add("remove: " + name);
                assertEquals(List.of(), strangers, tag + ": an engine the image does not have is named");
                assertTrue(Searx.removedIn(s).containsAll(List.of("baidu", "yandex", "yandex images", "yandex music", "habrahabr")), tag + ": " + Searx.removedIn(s));
                assertEquals(tag.equals("2026.11.20"), Searx.removedIn(s).contains("yandex api"), tag + ": the Yandex engine the newer image adds goes too");
                assertEquals(tag.equals("2026.7.26"), Searx.namesIn(s).contains("presearch"), tag + ": presearch is named only where the image has it");
                assertEquals(Searx.Whose.OURS, Searx.whose(s), tag);
                // the contact the current SearXNG wants before it loads Wikipedia's engines: the project's site, in the file for either image
                assertTrue(s.contains("\ngeneral:\n  contact_url: \"https://researchzosho.org\"\n"), tag + ": " + s);
                Path out = Path.of("build", "tmp", "searx-settings-" + tag + ".yml");   // kept for a look, and for a start of the real image
                Files.createDirectories(out.getParent()); Files.writeString(out, s);
            }
            // a file written for one image is still told as ours when the machine's image is another
            Searx.imageEngines = Searx.enginesIn("\nengines:\n  - name: wikipedia\n    engine: wikipedia\n");
            assertEquals(Searx.Whose.OURS, Searx.whose(Files.readString(Path.of("build", "tmp", "searx-settings-2026.7.26.yml"))));
        } finally { Searx.imageEngines = was; System.setProperty("user.home", real); Config.invalidate(); }
    }

    /**
     * The program fetches the search service's image once. An image fetched long ago is said in the status with the command that
     * fetches the current one; that command makes the container again and leaves an edited settings file as it is.
     */
    @Test
    void anOldImageIsSaidAndUpdateFetchesTheCurrentOne(@TempDir Path home) throws Exception {
        // (below, first: a file of ours written again at an upgrade while the image is old says both, once)
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString()); Config.invalidate();
        Searx.Runner was = Searx.runner;
        Map<String, String> engines = Searx.imageEngines;
        List<String> ran = new ArrayList<>();
        String[] made = {"2026-07-26T16:58:36.123456789Z"};
        String[] state = {"false"};
        Searx.runner = cmd -> {
            ran.add(String.join(" ", cmd));
            if (cmd.contains("version")) return new Searx.Result(0, "27.0");
            if (cmd.contains("image") && cmd.contains("inspect")) return new Searx.Result(0, made[0] + "\n");
            if (cmd.contains("pull")) { made[0] = LocalDate.now() + "T10:00:00Z"; return new Searx.Result(0, "Status: Downloaded newer image"); }
            if (cmd.contains("inspect")) return new Searx.Result(state[0].isEmpty() ? 1 : 0, state[0]);
            if (cmd.contains("rm")) { state[0] = ""; return new Searx.Result(0, ""); }
            return new Searx.Result(1, "no docker here");
        };
        try {
            assertEquals("2026-07-26", Searx.imageMade());
            Files.createDirectories(Searx.dir());
            Files.writeString(Searx.dir().resolve("settings.yml"), Searx.earlier(true, "00112233445566778899aabbccddeeff0011223344556677"));
            assertTrue(Searx.oldImageAtUpdate().startsWith("The SearXNG image on this machine is from 2026-07-26"), "the update's own message carries the old image's line when the file is ours");
            String atUpgrade = Searx.upgrade();
            assertTrue(atUpgrade.startsWith(Searx.WRITTEN_AGAIN) && atUpgrade.contains("The SearXNG image on this machine is from 2026-07-26") && atUpgrade.contains("`researchzosho search update`"), atUpgrade);
            assertEquals(atUpgrade, Searx.afterUpgrade(), "and the status command says the same, once");
            assertFalse(ran.stream().anyMatch(c -> c.contains(" pull ")), "an upgrade never fetches an image by itself: " + ran);
            state[0] = "false"; ran.clear();
            String note = Searx.imageNote();
            assertTrue(note.startsWith("The SearXNG image on this machine is from 2026-07-26 (") && note.contains("`researchzosho search update`") && note.contains("your settings file stays as it is"), note);
            Files.createDirectories(Searx.dir());
            String theirs = Searx.earlier(true, "00112233445566778899aabbccddeeff0011223344556677") + "# mine\n";
            Files.writeString(Searx.dir().resolve("settings.yml"), theirs);
            Searx.update(47998);
            assertTrue(ran.contains("docker pull " + Searx.IMAGE) && ran.contains("docker rm -f " + Searx.CONTAINER), ran.toString());
            assertTrue(ran.indexOf("docker pull " + Searx.IMAGE) < ran.indexOf("docker rm -f " + Searx.CONTAINER), "the image first, then the container: " + ran);
            assertEquals(theirs, Files.readString(Searx.dir().resolve("settings.yml")), "an edited settings file is left as it is");
            assertEquals("", Searx.oldImageAtUpdate(), "an edited file is the person's: the update's message says nothing of the image");
            assertEquals("", Searx.imageNote(), "an image of today is not said");
        } finally { Searx.runner = was; Searx.imageEngines = engines; Searx.settingsNote = ""; System.setProperty("user.home", real); Config.invalidate(); }
    }
}
