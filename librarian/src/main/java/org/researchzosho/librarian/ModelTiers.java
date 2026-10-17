package org.researchzosho.librarian;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The measured tier of a model family for research, for best judgement when the person has not set one: A stated nearly every fact
 * of the key on the scouting question and contradicted none, B most, C few or unreliable. From the judged runs of 2026-10-15 and 16
 * (46-fact key, two judges); a family name is matched at the start of a name part, the longest name first.
 */
public final class ModelTiers {

    private ModelTiers() { }

    static final Map<String, String> FAMILIES = new LinkedHashMap<>();
    static {
        for (String a : new String[]{"kimi-k3", "gpt-6.1-sol", "gpt-6-sol", "claude-fable", "deepseek-v4.1-flash", "glm-5.3-flash", "minimax-m3", "gemini-4-argon", "gemini-4-pro"}) FAMILIES.put(a, "A");
        for (String b : new String[]{"claude-sonnet", "claude-opus", "glm-5.3", "nemotron-3-ultra", "qwen3.8-27b", "deepseek-v4-pro", "gemini-4"}) FAMILIES.put(b, "B");
        for (String c : new String[]{"claude-haiku", "gpt-6-luna", "hy4", "mimo-v2.6", "qwen3.5-9b"}) FAMILIES.put(c, "C");
    }

    /** The tier for a model id as a server or gateway reports it; C when the family is not known. */
    public static String of(String model) {
        if (model == null) return "C";
        String m = model.toLowerCase(Locale.ROOT).replace('_', '-');
        int slash = m.lastIndexOf('/');
        if (slash >= 0) m = m.substring(slash + 1);
        if (m.endsWith(".gguf")) m = m.substring(0, m.length() - 5);
        for (Map.Entry<String, String> e : FAMILIES.entrySet().stream().sorted((a, b) -> b.getKey().length() - a.getKey().length()).toList()) {
            int at = m.indexOf(e.getKey());
            if (at < 0) continue;
            boolean startOk = at == 0 || !Character.isLetterOrDigit(m.charAt(at - 1));
            int end = at + e.getKey().length();
            boolean endOk = end >= m.length() || !Character.isLetterOrDigit(m.charAt(end)) || m.charAt(end) == '.';
            if (startOk && endOk) return e.getValue();
        }
        return "C";
    }
}
