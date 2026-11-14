package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MetricX-24's own script on a card with no room (0.7.5), run for real with made-up torch and transformers modules that refuse the
 * card: without --no-cpu-fallback it moves to the processor, as before; with it, it says why in one line and ends with 3, and the
 * processor is never used. Run where a python3 is on the PATH; skipped where none is (the script is Python; nothing else here needs it).
 */
class MetricxScriptNoRoomTest {

    /** A torch whose card refuses the model (0.4 GB free), and a transformers that loads a model of nothing; each writes where the model went. */
    static final String TORCH = """
            import os
            class OutOfMemoryError(RuntimeError): pass
            class _Props: uuid = "dddd3333-3333-3333-3333-333333333333"
            class cuda:
                OutOfMemoryError = OutOfMemoryError
                @staticmethod
                def is_available(): return os.environ.get("STUB_CARD", "yes") == "yes"
                @staticmethod
                def empty_cache(): pass
                @staticmethod
                def mem_get_info(i=0): return (int(0.43 * 2**30), 48 * 2**30)
                @staticmethod
                def get_device_name(i=0): return "NVIDIA RTX 6000 Ada Generation"
                @staticmethod
                def get_device_properties(i=0): return _Props()
            float32 = "float32"; bfloat16 = "bfloat16"; long = "long"
            def no_grad():
                class _N:
                    def __enter__(self): return self
                    def __exit__(self, *a): return False
                return _N()
            """;
    static final String TRANSFORMERS = """
            import os
            class AutoTokenizer:
                @staticmethod
                def from_pretrained(*a, **k): return object()
            class MT5ForConditionalGeneration:
                @classmethod
                def from_pretrained(cls, *a, **k): return cls()
                def to(self, device):
                    if device == "cuda":
                        import torch
                        raise torch.cuda.OutOfMemoryError("CUDA out of memory")
                    open(os.environ["STUB_LOG"], "a").write("model on " + device + "\\n")
                    return self
                def float(self): return self
                def eval(self): return self
            """;

    record Ran(int code, String out, String log) { }

    static Ran run(Path tmp, boolean refuse, boolean card) throws Exception {
        String py = null;
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) if (Files.isExecutable(Path.of(dir, "python3"))) { py = Path.of(dir, "python3").toString(); break; }
        assumeTrue(py != null, "no python3 on the PATH");
        Path stubs = tmp.resolve("stubs"), script = tmp.resolve("metricx_score.py"), log = tmp.resolve("where.log");
        Files.createDirectories(stubs);
        Files.writeString(stubs.resolve("torch.py"), TORCH); Files.writeString(stubs.resolve("transformers.py"), TRANSFORMERS);
        try (InputStream in = TranslationScores.class.getResourceAsStream("/org/researchzosho/librarian/metricx_score.py")) { Files.write(script, in.readAllBytes()); }
        Files.deleteIfExists(log); Files.createFile(log);
        List<String> cmd = new ArrayList<>(List.of(py, script.toString(), "--check"));
        if (refuse) cmd.add("--no-cpu-fallback");
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("PYTHONPATH", stubs.toString());
        pb.environment().put("STUB_LOG", log.toString());
        pb.environment().put("STUB_CARD", card ? "yes" : "no");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        return new Ran(p.exitValue(), out, Files.readString(log));
    }

    @Test void withoutTheOptionTheProcessorTakesItAsBefore(@TempDir Path tmp) throws Exception {
        Ran r = run(tmp, false, true);
        assertEquals(0, r.code(), r.out());
        assertTrue(r.out().contains("\"ready\": true, \"device\": \"cpu\""), r.out());
        assertEquals("model on cpu\n", r.log());
    }

    @Test void withTheOptionNoRoomIsSaidAndTheProcessorIsNeverUsed(@TempDir Path tmp) throws Exception {
        Ran r = run(tmp, true, true);
        assertEquals(3, r.code(), r.out());
        assertTrue(r.out().contains("{\"ready\": false, \"not_loaded\": \"no room\", \"free_gb\": 0.4}"), r.out());
        assertEquals("", r.log(), "the model never went to the processor");
        Ran none = run(tmp, true, false);
        assertEquals(3, none.code(), none.out());
        assertTrue(none.out().contains("{\"ready\": false, \"not_loaded\": \"no card\"}"), none.out());
        assertEquals("", none.log());
    }
}
