package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.tools.Tool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** The twelve names wrap what exists: search by place, open by the address's kind, look, records, homes, board, dossier, who, shelf; the old names stay. */
class InstrumentsTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;

    static Tool fake(String name, List<String> calls) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public ObjectNode parametersSchema(ObjectMapper j) { return j.createObjectNode(); }
            @Override public String execute(JsonNode args) { calls.add(name + " " + args); return "result of " + name; }
        };
    }

    @AfterEach void live() { Instruments.HOMES = Instruments::homesLive; Instruments.BOARD = Instruments::boardLive; Instruments.RECORDS = Instruments::recordsLive; Instruments.LOOK = null; }

    static Instrument named(List<Instrument> table, String name) { return table.stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError("no " + name + " on the table: " + table.stream().map(Instrument::name).toList())); }

    @Test void searchLooksInOnePlaceAtATimeThroughTheToolThatLooksThere() throws Exception {
        List<String> calls = new ArrayList<>();
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (String n : List.of("web_search", "video_search", "web_fetch", "video_details", "channel_uploads")) byName.put(n, fake(n, calls));
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<Instrument> table = Instruments.forRun(store, byName, new Researcher.Ask("q", "broad", 0, List.of(), "web", List.of(), 0, List.of(), List.of()));
        assertEquals(List.of("search", "open", "look", "candidates", "sift", "records", "homes", "dossier", "board"), table.stream().map(Instrument::name).toList(), "no family in this library: no who");
        assertEquals(5, byName.size(), "the old names are not taken away");
        Instrument search = named(table, "search");
        assertTrue(search.words().contains("where = web | videos") && search.words().contains("· one call each"), search.words());
        String r = search.asTool().execute(M.readTree("{\"where\":\"videos\",\"query\":\"tote bag\",\"limit\":5}"));
        assertEquals("result of video_search", r);
        assertEquals("video_search {\"query\":\"tote bag\",\"limit\":5}", calls.get(0));
        String none = search.asTool().execute(M.readTree("{\"where\":\"podcasts\",\"query\":\"x\"}"));
        assertTrue(none.startsWith("No search looks in \"podcasts\" on this table. The places on it are: web, videos."), none);
        assertNull(Instruments.needed(), "nothing to ask the person");
    }

    @Test void openReadsAnAddressAsWhatItIs() throws Exception {
        List<String> calls = new ArrayList<>();
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (String n : List.of("web_fetch", "video_details", "channel_uploads")) byName.put(n, fake(n, calls));
        Instrument open = Instruments.open(byName);
        open.asTool().execute(M.readTree("{\"address\":\"https://www.youtube.com/watch?v=dQw4w9WgXcQ\"}"));
        open.asTool().execute(M.readTree("{\"address\":\"https://www.youtube.com/@somechannel/videos\"}"));
        open.asTool().execute(M.readTree("{\"address\":\"https://example.org/a-page\",\"find\":\"price\"}"));
        open.asTool().execute(M.readTree("{\"address\":\"https://www.youtube.com/watch?v=dQw4w9WgXcQ\",\"as\":\"page\"}"));
        assertEquals("video_details {\"video\":\"https://www.youtube.com/watch?v=dQw4w9WgXcQ\",\"transcript\":true}", calls.get(0));
        assertTrue(calls.get(1).startsWith("channel_uploads {\"channel\":\"https://www.youtube.com/@somechannel/videos\"}"), calls.get(1));
        assertEquals("web_fetch {\"url\":\"https://example.org/a-page\",\"find\":\"price\"}", calls.get(2));
        assertTrue(calls.get(3).startsWith("web_fetch"), "as=page overrides the address's kind: " + calls.get(3));
        assertEquals("Give the address to open.", open.asTool().execute(M.readTree("{}")));
    }

    @Test void lookReadsThroughTheVideoFieldAndSaysWhenItHasNoLibrary() throws Exception {
        Instrument look = Instruments.look(null);
        assertTrue(look.words().contains("frames") && look.words().contains("seven minutes per ten minutes"), look.words());
        String absent = look.asTool().execute(M.readTree("{\"video\":\"https://www.youtube.com/watch?v=x\",\"what\":\"a bee\"}"));
        assertTrue(absent.startsWith("The look at a video's frames needs a library"), absent);
        List<String> calls = new ArrayList<>();
        Instruments.LOOK = fake("look", calls);
        assertEquals("result of look", look.asTool().execute(M.readTree("{\"video\":\"v\",\"what\":\"a bee\",\"sparse\":true}")));
        assertEquals("look {\"video\":\"v\",\"what\":\"a bee\",\"sparse\":true}", calls.get(0));
    }

    @Test void theFieldsInstrumentsRunTheirProgramsAndSayTheirCost() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib2")); store.init();
        List<String> ran = new ArrayList<>();
        Instruments.HOMES = (s, name, words, stop) -> { ran.add("homes " + name + " | " + words); return "3 homes fit."; };
        Instruments.BOARD = (employer, towns, words) -> { ran.add("board " + employer + " " + towns + (words.isEmpty() ? "" : " " + words)); return employer + ": 2 opening(s)."; };
        Instruments.RECORDS = (s, address) -> { ran.add("records " + address); return "Records: a roll of 2024."; };
        List<Instrument> table = Instruments.forChat(store);
        assertTrue(named(table, "homes").words().contains("RentCast calls count against its monthly allowance"), "the cost is in the words");
        assertEquals("3 homes fit.", named(table, "homes").asTool().execute(M.readTree("{\"words\":\"a house to buy in Cambridge\"}")));
        assertEquals("homes a house to buy in Cambridge | a house to buy in Cambridge", ran.get(0), "the search is named from the words when no name is given");
        assertEquals("Massachusetts General Hospital: 2 opening(s).", Instruments.call(table, "board", M.readTree("{\"employer\":\"Massachusetts General Hospital\",\"town\":\"Boston, MA\"}")));
        assertEquals("board Massachusetts General Hospital [Boston, MA]", ran.get(1));
        assertEquals("Records: a roll of 2024.", Instruments.call(table, "records", M.readTree("{\"address\":\"1 City Hall Square, Boston, MA\"}")));
        assertNull(Instruments.call(table, "no_such_instrument", M.readTree("{}")));
        assertTrue(Instruments.isInstrument(table, "shelf") && !Instruments.isInstrument(table, "who"), "the chat's table has the shelf, and no who without the family module");
        assertEquals("Say what home is wanted: where, buy or rent, the budget, the rooms.", named(table, "homes").asTool().execute(M.readTree("{}")));
    }

    @Test void aQuestionAnInstrumentStopsOnReachesTheLoop() throws Exception {
        Instrument asks = new Instrument() {
            @Override public String name() { return "asks"; }
            @Override public String words() { return "asks · when · free"; }
            @Override public ObjectNode schema(ObjectMapper m) { return Instruments.schema("x", "string"); }
            @Override public Result run(ObjectNode args, Researcher.Budget budget, BooleanSupplier stop) {
                return new Result(List.of(new Brain.Evidence("asks", "", "two Cambridges found")), new Brain.Needed(List.of(), "Which Cambridge?", List.of("Massachusetts", "England")));
            }
        };
        String seen = asks.asTool().execute(M.readTree("{}"));
        assertEquals("two Cambridges found\n\nQUESTION FOR THE PERSON: Which Cambridge? Choices: Massachusetts | England", seen);
        Brain.Needed n = Instruments.needed();
        assertNotNull(n); assertEquals("Which Cambridge?", n.question()); assertEquals(List.of("Massachusetts", "England"), n.choices());
        assertNull(Instruments.needed(), "taken once");
    }

    @Test void theOldNamesAreTheOnesSearchLooksThrough() {
        assertTrue(Instruments.OLD_NAMES.containsAll(List.of("web_search", "scholar_search", "record_search", "archive_search", "old_newspapers", "ndl_fulltext", "forum_search", "video_search", "video_sites_search", "podcast_search", "product_search", "book_search", "software_archive", "shelf_search")));
    }
}
