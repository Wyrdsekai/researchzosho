package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The profiles this build knows, and which of them a library has enabled. */
public final class Profiles {

    private Profiles() { }

    public static List<Profile> all() {
        return List.of(new org.researchzosho.librarian.profiles.ScienceProfile(), new org.researchzosho.librarian.profiles.GenealogyProfile());
    }

    public static Profile named(String name) {
        for (Profile p : all()) if (p.name().equalsIgnoreCase(name)) return p;
        return null;
    }

    /** The names enabled on this library: the {@code profiles:} line of catalog/library.md; science by default. */
    public static Set<String> enabled(LibraryStore store) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        out.add("science");
        Path f = store.root().resolve("catalog").resolve("library.md");
        if (Files.exists(f)) {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.startsWith("profiles:")) {
                    out.clear();
                    for (String p : line.substring(9).split(",")) if (!p.isBlank()) out.add(p.strip().toLowerCase());
                }
            }
        }
        return out;
    }

    public static List<Profile> enabledProfiles(LibraryStore store) throws IOException {
        List<Profile> out = new ArrayList<>();
        for (String n : enabled(store)) { Profile p = named(n); if (p != null) out.add(p); }
        return out;
    }

    public static boolean isEnabled(LibraryStore store, String name) throws IOException {
        return enabled(store).contains(name.toLowerCase());
    }

    /** Enable a profile on this library: record it in library.md and let it seed what it needs. */
    public static synchronized void enable(LibraryStore store, String name) throws IOException {
        Profile p = named(name);
        if (p == null) throw new IllegalArgumentException("no profile named " + name + " (known: " + String.join(", ", all().stream().map(Profile::name).toList()) + ")");
        Set<String> now = enabled(store);
        now.add(p.name());
        writeLine(store, now);
        p.enable(store);
        store.circulate("profile-enabled", p.name());
    }

    public static synchronized void disable(LibraryStore store, String name) throws IOException {
        Set<String> now = enabled(store);
        now.remove(name.toLowerCase());
        writeLine(store, now);
        store.circulate("profile-disabled", name);
    }

    private static void writeLine(LibraryStore store, Set<String> names) throws IOException {
        store.identity();   // the file exists with its id before anything else is written into it
        Path f = store.root().resolve("catalog").resolve("library.md");
        List<String> lines = Files.exists(f) ? new ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8)) : new ArrayList<>(List.of("# This library", ""));
        lines.removeIf(l -> l.startsWith("profiles:"));
        lines.add("profiles: " + String.join(", ", names));
        Files.writeString(f, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }
}
