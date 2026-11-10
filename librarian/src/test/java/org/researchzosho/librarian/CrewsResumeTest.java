package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The nightly tasks keep each task's outcome as it ends: a crews job the restart caught goes on from the task it was on, and the tasks done before are not done again. */
class CrewsResumeTest {

    @Test void aCrewsJobStoppedAfterAFewTasksGoesOnFromTheNext(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Crews.Cadence night = new Crews.Cadence(false, false);
        Checkpoint first = Checkpoint.open(store, "J-0005", "crews", "night");
        List<Crews.Step> all = Crews.keeping(() -> false, first, () -> Crews.runAll(store, "", "m", (q, w) -> null, 0, night));
        first.close();
        assertTrue(all.size() > 8, all.toString());
        // the process ended after its fourth task: the record as it stood then
        Path artifacts = Checkpoint.dir(store, "J-0005").resolve(Checkpoint.ARTIFACTS);
        List<String> kept = new ArrayList<>();
        int steps = 0;
        for (String line : Files.readAllLines(artifacts, StandardCharsets.UTF_8)) { if (line.contains("\"step|")) { if (steps == 4) break; steps++; } kept.add(line); }
        Files.write(artifacts, kept, StandardCharsets.UTF_8);
        List<String> doneBefore = all.subList(0, 4).stream().map(Crews.Step::name).toList();
        long logged = Files.readAllLines(store.root().resolve("catalog").resolve("crews.log")).size();

        Checkpoint second = Checkpoint.open(store, "J-0005", "crews", "night");
        assertTrue(second.resumed());
        List<Crews.Step> again = Crews.keeping(() -> false, second, () -> Crews.runAll(store, "", "m", (q, w) -> null, 0, night));
        second.close();
        assertEquals(all.stream().map(Crews.Step::name).toList(), again.stream().map(Crews.Step::name).toList(), "the same tasks, in the same order");
        for (int i = 0; i < again.size(); i++) {
            boolean before = doneBefore.contains(again.get(i).name());
            assertEquals(before, again.get(i).outcome().endsWith("(done before the restart)"), again.get(i).toString());
            if (before) assertEquals(all.get(i).outcome() + " (done before the restart)", again.get(i).outcome());
        }
        long loggedAfter = Files.readAllLines(store.root().resolve("catalog").resolve("crews.log")).size();
        assertEquals(all.size() - 4, loggedAfter - logged, "only the tasks after the stop ran and wrote their line");
        long stepped = all.stream().filter(st -> !st.outcome().startsWith("skipped — no drive")).count();   // the model's tasks are skipped with no model, and not kept
        assertEquals("the task after " + all.get(all.size() - 1).name() + " (" + stepped + " done)", Checkpoint.stateOf(store, "J-0005").path("said").asText());
    }
}
