package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An other name with a comma in it, such as a book index's "Hart, Tom" or a place "Leeds, Yorkshire", is one name. The list of names in
 * nodes.md separates other names with commas, and read such a name back as two, "Hart" and "Tom": each word alone then led every claim
 * written with it to that entry. A list written before keeps reading as it always did.
 */
class OtherNamesWithACommaTest {

    static LibraryStore store(Path tmp) throws Exception {
        LibraryStore s = new LibraryStore(tmp.resolve("lib"));
        s.init();
        return s;
    }

    static void claim(LibraryStore store, String id, String s, String p, String o) throws Exception {
        String line = s + " " + p + " " + o + ".";
        store.write(new Finding(id, line, List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.medium, "reviewer",
                "2026-09-01T00:00:00Z", "2026-09-01", Finding.Volatility.stable, "", List.of(new Finding.Source("https://example.org/" + id, "n/a", "a page")),
                List.of(), null, line + "\n", new Finding.Triple(s, p, o), List.of()));
    }

    /** The other names nodes.md gives an entry, counted and in order: "2: Hart | Tom". */
    static String names(LibraryStore store, String id) throws Exception {
        Vocabulary.Term t = Vocabulary.read(Graph.nodesFile(store)).get(id);
        return t == null ? "(no entry)" : t.also().size() + ": " + String.join(" | ", t.also());
    }

    @Test
    void anIndexFormIsOneOtherNameAndAWordOfItLeadsNowhere(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        claim(store, "F-0001-a", "Tom Hart", "was born in", "Leeds");
        claim(store, "F-0002-b", "Hart", "founded", "the Hart Mill");
        claim(store, "F-0003-c", "Tom", "wrote", "a letter to the mill");
        Graph.alias(store, "Tom Hart", List.of("Hart, Tom"));
        assertEquals("1: Hart, Tom", names(store, "tom hart"), "the index form is one other name");
        Graph g = Graph.build(store);
        assertEquals("tom hart", g.nodeIdOf("Hart, Tom"));
        assertEquals("hart", g.nodeIdOf("Hart"), "a word of it alone is no other name of Tom Hart");
        assertEquals("tom", g.nodeIdOf("Tom"));
        assertTrue(g.edges().stream().filter(e -> e.from().equals("tom hart")).allMatch(e -> e.findingId().equals("F-0001-a")), "only his own claim is about Tom Hart: " + g.edges());
        String file = Files.readString(Graph.nodesFile(store), StandardCharsets.UTF_8);
        assertTrue(file.contains("| also: \"Hart, Tom\""), "written between double quotes: " + file);
    }

    @Test
    void aPlaceWithACommaStaysOnePlaceAndAJoinKeepsTheNameItFoldsInWhole(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        claim(store, "F-0001-a", "Ann Hale", "was born in", "Leeds, Yorkshire");
        claim(store, "F-0002-b", "The Hale Mill", "stood in", "Yorkshire");
        claim(store, "F-0003-c", "Leeds", "is a city in", "England");
        Graph.setKind(store, "Leeds", "place");
        Graph.alias(store, "Leeds", List.of("Leeds, Yorkshire"));
        assertEquals("1: Leeds, Yorkshire", names(store, "leeds"));
        Graph g = Graph.build(store);
        assertEquals("leeds", g.nodeIdOf("Leeds, Yorkshire"), "the city written with its county is Leeds");
        assertEquals("yorkshire", g.nodeIdOf("Yorkshire"), "the county alone is not Leeds");
        assertTrue(g.edges().stream().anyMatch(e -> e.findingId().equals("F-0002-b") && e.to().equals("yorkshire")), g.edges().toString());

        // a place joined into another: the name folded in becomes one other name of it
        claim(store, "F-0004-d", "Tom Hart", "died in", "Leeds, West Riding");
        Graph.merge(store, "Leeds, West Riding", "Leeds", "person", "one city");
        assertEquals("2: Leeds, Yorkshire | Leeds, West Riding", names(store, "leeds"));
        Graph after = Graph.build(store);
        assertEquals("leeds", after.nodeIdOf("Leeds, West Riding"));
        assertEquals("west riding", after.nodeIdOf("West Riding"));
    }

    @Test
    void everyWriterKeepsANameWithACommaWholeAndTakingOneAwayKeepsTheRest(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        claim(store, "F-0001-a", "Tom Hart", "was born in", "Leeds");
        List<String> given = List.of("Hart, Tom", "Tom \"Red\" Hart, Jr.", "\"Hart, T.\"", "T. Hart");
        Graph.alias(store, "Tom Hart", given);
        assertEquals(given.size() + ": " + String.join(" | ", given), names(store, "tom hart"), "each comes back as it was given");
        assertEquals(1, Graph.dropAliases(store, List.<String[]>of(new String[]{"Tom Hart", "Hart, Tom"})));
        List<String> left = given.subList(1, given.size());
        assertEquals(left.size() + ": " + String.join(" | ", left), names(store, "tom hart"));
        // the same list in any vocabulary file, such as the subjects' and the relations'
        Vocabulary v = new Vocabulary();
        v.alias("leeds", "place: Leeds", List.of("Leeds, Yorkshire", "Leeds"));
        Path file = tmp.resolve("places.md");
        v.write(file, "# Places\n");
        assertEquals(List.of("Leeds, Yorkshire"), Vocabulary.read(file).get("leeds").also());
    }

    @Test
    void aListWrittenBeforeReadsAsItAlwaysDidAndIsWrittenBackAsItWas(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Files.createDirectories(Graph.dir(store));
        String[] lines = {
                "- tom hart — person: Tom Hart | also: Hart, Tom, T. Hart | wikidata: Q42",
                "- ann hale — person: Ann Hale | also: \"Nan\", \"Hale, Ann",
                "- john hale — person: John Hale | also: \"Jack\" Hale, J. Hale"};
        Files.writeString(Graph.nodesFile(store), "# My nodes\n\n" + String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        Vocabulary v = Vocabulary.read(Graph.nodesFile(store));
        for (String line : lines) {
            // the rule every version before read the list by: split at each comma
            List<String> before = new ArrayList<>();
            String list = line.substring(line.indexOf("| also: ") + 8).replaceFirst(" \\| wikidata: .*$", "");
            for (String a : list.split(",")) if (!a.isBlank()) before.add(a.strip());
            assertEquals(before, v.get(line.substring(2, line.indexOf(" — "))).also(), line);
        }
        Graph.alias(store, "Tom Hart", List.of("Thomas Hart"));
        List<String> now = Files.readAllLines(Graph.nodesFile(store), StandardCharsets.UTF_8);
        assertTrue(now.contains("- tom hart — person: Tom Hart | also: Hart, Tom, T. Hart, Thomas Hart | wikidata: Q42"), "the old names stay as they were written: " + now);
        assertTrue(now.contains(lines[1]) && now.contains(lines[2]), "a line nobody changed is written back as it was: " + now);
    }
}
