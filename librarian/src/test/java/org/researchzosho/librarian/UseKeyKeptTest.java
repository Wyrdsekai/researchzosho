package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A use's key is kept in its job's record and put back when the library starts again (round F, the second review, money 2): the key lived
 * only in memory, so a job resumed after a restart could neither end its use nor ask its state, and the use stayed open for a day with its
 * cap unwatched. Tests restart a library in one process, where the memory survived; this one starts from the record alone.
 */
class UseKeyKeptTest {
    static final ObjectMapper M = new ObjectMapper();

    @Test
    void aResumedJobEndsItsUseWithTheKeyItsRecordKept(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Jobs before = new Jobs(store, j -> "");
        String id = before.submit("research", "person", (ObjectNode) M.readTree("{\"question\":\"Why did the ferry close?\"}"));
        String use = "use_" + System.nanoTime(), key = "k" + Long.toHexString(System.nanoTime());
        Path f = before.activeDir().resolve(id + ".json");
        ObjectNode j = (ObjectNode) M.readTree(Files.readString(f, StandardCharsets.UTF_8));
        j.put("use", use).put("use_key", key);
        Files.writeString(f, j.toString(), StandardCharsets.UTF_8);
        assertEquals("", ServiceUses.keyOf(use), "a new process knows nothing of it");
        new Jobs(store, x -> "").pickUpLeftovers();
        assertEquals(key, ServiceUses.keyOf(use), "the key comes back from the job's record");
    }
}
