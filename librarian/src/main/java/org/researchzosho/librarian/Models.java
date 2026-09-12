package org.researchzosho.librarian;

import java.util.List;

/**
 * Which model to run, by the graphics card's memory — MEASURED, not read off a leaderboard. On 2026-09-10/11 the same
 * two research questions were run from empty libraries through nineteen drives, and each write-up was judged by what
 * the librarian could do with it: the facts right, claims extracted, citations the checker could read. The table is
 * that result. {@code researchzosho models} prints the row for this machine's card; setup shows it when no model
 * server answers. The serving lines are llama.cpp's server image, which is what the measurements ran on.
 */
public final class Models {

    private Models() { }

    /** One measured choice. {@code file} is the exact GGUF, {@code serve} the command that ran it. */
    public record Choice(String model, String file, String note, String serve) { }

    public record Tier(String card, int minGb, List<Choice> choices) { }

    static String llama(String hf, String file, int ctx, int parallel, String extra) {
        return "docker run -d --name researchzosho-model --gpus all -p 127.0.0.1:8080:8080 -v ~/models:/m ghcr.io/ggml-org/llama.cpp:server-cuda"
                + " -m /m/" + file + " --host 0.0.0.0 --port 8080 --jinja -c " + ctx + " --parallel " + parallel + " -ngl 99 --flash-attn on" + (extra.isEmpty() ? "" : " " + extra)
                + "\n    (the file: https://huggingface.co/" + hf + "/resolve/main/" + file + ")";
    }

    public static final List<Tier> TIERS = List.of(
        new Tier("24 GB or more", 24, List.of(
            new Choice("Qwen3.8-27B at 4-bit", "Qwen3.8-27B-UD-Q4_K_M.gguf", "the reference: the deepest answers, the most claims, the most citations the checker can read; 17 GB file",
                llama("unsloth/Qwen3.8-27B-GGUF", "Qwen3.8-27B-UD-Q4_K_M.gguf", 131072, 4, "--chat-template-kwargs '{\"reasoning_effort\":\"low\"}'")))),
        new Tier("16 GB", 16, List.of(
            new Choice("gpt-oss-20b", "gpt-oss-20b-F16.gguf", "right, 8 claims, about 5 minutes a question; 13 GB in use with two 16k slots — the choice when speed matters",
                llama("unsloth/gpt-oss-20b-GGUF", "gpt-oss-20b-F16.gguf", 32768, 2, "--chat-template-kwargs '{\"reasoning_effort\":\"low\"}'")),
            new Choice("Gemma 4 26B-A4B at 4-bit", "gemma-4-26B-A4B-it-UD-IQ4_XS.gguf", "right and the most careful writer, 10 claims, about nine times slower; 14.6 GB in use",
                llama("unsloth/gemma-4-26B-A4B-it-GGUF", "gemma-4-26B-A4B-it-UD-IQ4_XS.gguf", 32768, 2, "")),
            new Choice("Gemma 4 12B at 4-bit", "gemma-4-12b-it-Q4_K_M.gguf", "right, 10 claims, six times slower; 9 GB in use, the most room for context",
                llama("unsloth/gemma-4-12b-it-GGUF", "gemma-4-12b-it-Q4_K_M.gguf", 32768, 2, "")))),
        new Tier("8 GB", 8, List.of(
            new Choice("Qwen3.5 9B at 4-bit", "Qwen3.5-9B-Q4_K_M.gguf", "right and deep, 10 claims, half its citations readable, about 11 minutes a question; 5.9 GB in use with one 16k slot",
                llama("unsloth/Qwen3.5-9B-GGUF", "Qwen3.5-9B-Q4_K_M.gguf", 16384, 1, "--chat-template-kwargs '{\"enable_thinking\":false}'")),
            new Choice("Gemma 4 12B, smaller 4-bit file", "gemma-4-12b-it-IQ4_XS.gguf", "right, 10 claims, about 30 minutes a question; 7.3 GB in use with one 16k slot",
                llama("unsloth/gemma-4-12b-it-GGUF", "gemma-4-12b-it-IQ4_XS.gguf", 16384, 1, "")))),
        new Tier("4 GB", 4, List.of(
            new Choice("Gemma 4 E4B at 4-bit", "gemma-4-E4B-it-Q4_K_M.gguf", "right, 9 claims, the best citation reader of the small models, about 6 minutes a question; 3.6 GB in use",
                llama("unsloth/gemma-4-E4B-it-GGUF", "gemma-4-E4B-it-Q4_K_M.gguf", 16384, 1, "")))),
        new Tier("2 GB", 2, List.of(
            new Choice("Gemma 4 E2B at 4-bit", "gemma-4-E2B-it-Q4_K_M.gguf", "the claims came out right, but its write-ups were headings with no text; 2 GB in use. A hosted API is the better answer this small",
                llama("unsloth/gemma-4-E2B-it-GGUF", "gemma-4-E2B-it-Q4_K_M.gguf", 16384, 1, ""))))
    );

    /** The tier for a card of {@code gb}; null when even the smallest measured model would not fit. */
    public static Tier tierFor(double gb) {
        long rounded = Math.round(gb);   // a 16 GB card reports 15,996 MB: the nearest gigabyte is the size on the box
        for (Tier t : TIERS) if (rounded >= t.minGb()) return t;
        return null;
    }

    /** This machine's card, in GB, from nvidia-smi; 0 when there is none or it cannot be asked. */
    public static double cardGb() {
        try {
            Searx.Result r = Searx.runner.run(List.of("nvidia-smi", "--query-gpu=memory.total", "--format=csv,noheader,nounits"));
            if (r.code() != 0) return 0;
            String first = r.out().strip().split("\\R")[0].strip();
            return Double.parseDouble(first) / 1024.0;
        } catch (Exception e) { return 0; }
    }

    /** The recommendation as a person reads it: the card, the choices for it, how to serve one, and what to put in setup. */
    public static String describe(double gb) {
        StringBuilder b = new StringBuilder();
        Tier t = gb > 0 ? tierFor(gb) : null;
        if (gb <= 0) b.append("No NVIDIA card was found on this machine (or nvidia-smi is not on the PATH).\n");
        else b.append(String.format("This machine's card has %.0f GB.%n", gb));
        if (t == null) {
            b.append("Nothing measured runs well in that. Use a hosted model server for the research runs (setup asks for its address and key); everything else stays on this machine.\n");
        } else {
            b.append("Measured choice").append(t.choices().size() > 1 ? "s" : "").append(" for this size (").append(t.card()).append("), best first:\n");
            for (Choice c : t.choices()) b.append("  ").append(c.model()).append(": ").append(c.note()).append("\n    ").append(c.serve().replace("\n    ", "\n    ")).append("\n");
            b.append("Then give setup the address http://127.0.0.1:8080. The pages, the index and the library stay on this machine whatever drives them.\n");
        }
        b.append("All rows: researchzosho models --all. Measured on the same two questions from empty libraries; one run each, so this is the shape of the field, not a ranking.\n");
        return b.toString();
    }

    public static String describeAll() {
        StringBuilder b = new StringBuilder("Measured choices by card, best first in each row:\n");
        for (Tier t : TIERS) {
            b.append("\n").append(t.card()).append(":\n");
            for (Choice c : t.choices()) b.append("  ").append(c.model()).append(": ").append(c.note()).append("\n");
        }
        b.append("\nMeasured and not recommended: Qwen3 14B and Mistral Small 24B (a fact wrong each; Mistral needs 19 GB), the 27B at 3-bit (slow, under-finds), Qwen3 8B and Llama 3.1 8B (thin), Granite 4.1 8B (right, but 9.4 GB in use with one 16k slot, so not an 8 GB fit, and one unit slip), Phi-4-reasoning-plus, Falcon-H1 7B, SmolLM3 3B, LFM2 8B and Granite 4.0 micro (copied the prompt, answered without sources, or got the facts wrong).\n");
        b.append("researchzosho models prints the row for this machine's card with the command that serves it.\n");
        return b.toString();
    }
}
