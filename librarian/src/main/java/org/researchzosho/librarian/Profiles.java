package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * The profiles this build knows, and which of them a library has enabled. The build knows a profile by its line in the services file
 * under META-INF that names the Profile interface: the core names none of them, so it builds and its suite runs with none.
 */
public final class Profiles {

    private Profiles() { }

    private static final List<Class<? extends Profile>> TYPES = types();

    private static List<Class<? extends Profile>> types() {
        List<Class<? extends Profile>> out = new ArrayList<>();
        for (ServiceLoader.Provider<Profile> p : ServiceLoader.load(Profile.class, Profiles.class.getClassLoader()).stream().toList()) out.add(p.type());
        return List.copyOf(out);
    }

    /** One of each profile this build knows, new: a profile's command keeps what it was given for the one command. */
    public static List<Profile> all() {
        List<Profile> out = new ArrayList<>();
        for (Class<? extends Profile> t : TYPES) {
            try { out.add(t.getDeclaredConstructor().newInstance()); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("the profile " + t.getName() + " cannot be made: " + e, e); }
        }
        return out;
    }

    private static final List<Profile> KNOWN = List.copyOf(all());

    /**
     * One of each profile, kept for the whole run: for what a profile declares and for its hooks, which keep nothing between calls. A
     * profile's command takes a new one ({@link #all}).
     */
    public static List<Profile> known() { return KNOWN; }

    /** The writers whose claims are each profile's own work, by profile name: data that never changes, read once. */
    private static volatile Map<String, List<String>> ownWriters;

    public static Map<String, List<String>> ownWriters() {
        Map<String, List<String>> w = ownWriters;
        if (w == null) {
            Map<String, List<String>> m = new LinkedHashMap<>();
            for (Profile p : known()) if (!p.ownWriters().isEmpty()) m.put(p.name(), List.copyOf(p.ownWriters()));
            ownWriters = w = Collections.unmodifiableMap(m);
        }
        return w;
    }

    public static Profile named(String name) {
        for (Profile p : all()) if (p.name().equalsIgnoreCase(name)) return p;
        return null;
    }

    /** The names enabled on this library: the {@code profiles:} line of catalog/library.md; every profile when there is no such line. */
    public static Set<String> enabled(LibraryStore store) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        for (Profile p : all()) out.add(p.name());
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

    /** The enabled profiles, as {@link #known} holds them: for their rules and hooks. */
    public static List<Profile> enabledProfiles(LibraryStore store) throws IOException {
        List<Profile> out = new ArrayList<>();
        for (String n : enabled(store)) for (Profile p : known()) if (p.name().equalsIgnoreCase(n)) { out.add(p); break; }
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
