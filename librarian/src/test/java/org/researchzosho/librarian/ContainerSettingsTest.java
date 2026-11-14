package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-machine settings folder outlives a container: RESEARCHZOSHO_HOME places it, and the image, both compose files and the fleet
 * script put it on the settings volume, so a library's month of paid searches (and the rest kept there) is not lost when its container
 * is recreated or moved.
 */
class ContainerSettingsTest {

    /**
     * A file of the repository, from the module's folder or the repository's. A guide under docs/public/ is found where the public
     * export puts it too (docs/, or the root for the few it promotes), so the test runs in both trees.
     */
    static Path repo(String path) {
        List<String> where = new ArrayList<>(List.of(path));
        if (path.startsWith("docs/public/")) { String name = path.substring("docs/public/".length()); where.add("docs/" + name); where.add(name); }
        for (String w : where) for (Path p : new Path[]{Path.of("..").resolve(w), Path.of(w)}) if (Files.exists(p)) return p;
        throw new AssertionError(path + " not found from " + Path.of("").toAbsolutePath());
    }

    @Test void theSettingsFolderIsWhereRESEARCHZOSHO_HOMEPutsIt(@TempDir Path tmp) throws Exception {
        Path state = tmp.resolve("config").resolve("home");
        String said = CrossProcess.output(CrossProcess.start(Map.of("RESEARCHZOSHO_HOME", state.toString()), tmp.resolve("home"), "home")).strip();
        assertEquals(state.toString(), said, "the folder the variable names, not one under the user's home");
        String unset = CrossProcess.output(CrossProcess.start(Map.of("RESEARCHZOSHO_HOME", ""), tmp.resolve("home"), "home")).strip();
        assertEquals(tmp.resolve("home").resolve(".researchzosho").toString(), unset, "unset, it is ~/.researchzosho as before");
    }

    @Test void theImageTheComposeFilesAndTheFleetPutItOnTheSettingsVolume() throws Exception {
        String image = Files.readString(repo("packaging/docker/Dockerfile"));
        assertTrue(image.contains("RESEARCHZOSHO_HOME=/config/home") && image.matches("(?s).*VOLUME \\[[^]]*\"/config\"[^]]*].*"), "the image sets it under its /config volume");
        for (String compose : new String[]{"docker-compose.yml", "docker-compose.cloud.yml"}) {
            String c = Files.readString(repo(compose));
            assertTrue(c.contains("RESEARCHZOSHO_HOME: /config/home") && c.contains(":/config"), compose + " mounts /config and puts the folder there");
        }
        String fleet = Files.readString(repo("scripts/fleet.sh"));
        long runs = fleet.lines().filter(l -> l.contains("docker run -d") || l.contains("docker run -d --name")).count(), set = fleet.split("RESEARCHZOSHO_HOME=/config/home", -1).length - 1;
        assertTrue(runs >= 2 && set >= runs, "every library the fleet starts, here and on another server, has it: " + runs + " starts, " + set + " set");
        assertTrue(Files.readString(repo("packaging/docker/entrypoint.sh")).contains("mkdir -p \"$RESEARCHZOSHO_HOME\""), "made at start");
    }

    /**
     * The image runs no heavy helper on the node itself: it has neither Python nor Docker, so a library in it would try to set MetricX-24
     * up at every start and fail. The image, both compose files and every library the fleet starts say so, and the cloud guide says how
     * a deployment points its libraries at a helpers machine instead.
     */
    @Test void theImageTheComposeFilesAndTheFleetRunNoScorerOnTheNodeAndTheGuideSaysWhereToScore() throws Exception {
        String image = Files.readString(repo("packaging/docker/Dockerfile"));
        assertTrue(image.contains("RESEARCHZOSHO_LOCAL_HELPERS=off"), "the image");
        assertFalse(image.matches("(?s).*apt-get install[^\\n]*python.*"), "the image has no Python: the reason it runs no scorer");
        for (String compose : new String[]{"docker-compose.yml", "docker-compose.cloud.yml"})
            assertTrue(Files.readString(repo(compose)).contains("RESEARCHZOSHO_LOCAL_HELPERS: \"off\""), compose);
        String fleet = Files.readString(repo("scripts/fleet.sh"));
        long runs = fleet.lines().filter(l -> l.contains("docker run -d")).count(), set = fleet.split("RESEARCHZOSHO_LOCAL_HELPERS=off", -1).length - 1;
        assertTrue(runs >= 2 && set >= runs, "every library the fleet starts: " + runs + " starts, " + set + " set");
        String guide = Files.readString(repo("docs/public/CLOUD.md"));
        assertTrue(guide.contains("researchzosho settings set helpers http://") && guide.contains("researchzosho settings set helpers_secret ")
                && guide.contains("researchzosho helpers secret"), "the guide says how a library is pointed at a helpers machine, with its secret");
    }
}
