package org.researchzosho.librarian;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A list of questions — a syllabus, an exam, the things a person needs answered — straight onto the frontier, in order. */
public final class Questions {

    static final int MAX = org.researchzosho.Config.getInt("RESEARCHZOSHO_QUESTIONS_MAX", 200);
    /** Runs one call may file when the person asks for runs; the rest are filed as open questions. */
    static final int MAX_RUNS = org.researchzosho.Config.getInt("RESEARCHZOSHO_QUESTIONS_RUNS", 10);

    private Questions() { }

    /** One question per line; bullets and numbering stripped; lines that do not ask are left out. */
    public static List<String> parse(String text) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = Items.BULLET.matcher(raw.strip()).replaceFirst("").strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("|")) continue;
            line = line.replaceAll("^\\*\\*(.*)\\*\\*$", "$1").strip();
            boolean asks = line.endsWith("?") || Conversations.ASKS.matcher(line).find();
            if (!asks || line.length() < 12 || line.length() > 400) continue;
            if (seen.add(line.toLowerCase(Locale.ROOT))) out.add(line);
            if (out.size() >= MAX) break;
        }
        return out;
    }
}
