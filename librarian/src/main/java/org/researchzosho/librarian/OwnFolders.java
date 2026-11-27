package org.researchzosho.librarian;

import org.researchzosho.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The programs' own folders (2026-11-26): ResearchZosho's ({@link Config#home()}), CodeZaiku's ({@code ~/.codezaiku}) and the old
 * {@code ~/.codeplane}. They hold the settings with the person's keys, the paired phones, the tokens, the model folder's files and the
 * logs, and nothing in them is read for the library or for a model when a path names it: a path whose real path (links followed) lies
 * inside one is refused with one sentence that names the folder ({@link #refuse}), a document kept from such a file is refused where
 * every document is kept ({@link RawCapture}), and a folder that contains one is read without it ({@link #allows}, {@link #within}).
 *
 * <p>Not refused: a file inside the library's own folder (its shelves, its uploads and copies, wherever the library lives, an account
 * service's libraries under {@code ~/.researchzosho/accounts} among them), and a file the browser posted (those are written to the
 * system's temporary folder). The program's own reads of its own files do not come here.
 */
public final class OwnFolders {
    private OwnFolders() { }

    /** One of the folders: where it is, and whose (the program's name, as the sentence says it). */
    record Folder(Path path, String whose) { }

    /** The sentence a path inside one of the folders is refused with: the path, the folder, the program. */
    static final String INSIDE = "{0} is inside {1}, the folder of {2}'s settings and keys, so the library does not read it.";
    /** The sentence a folder read without one of them ends with. */
    static final String LEFT_OUT = "Left out: {0}, the folder of {1}'s settings and keys.";

    /** The folders, ResearchZosho's first; one folder once (an install that has not moved yet lives in {@code ~/.codezaiku}). */
    static List<Folder> all() {
        Path home = Path.of(System.getProperty("user.home", "."));
        List<Folder> out = new ArrayList<>();
        add(out, Config.home(), "ResearchZosho");
        add(out, home.resolve(".codezaiku"), "CodeZaiku");
        add(out, home.resolve(".codeplane"), "CodePlane");
        return out;
    }

    private static void add(List<Folder> out, Path p, String whose) {
        Path a = p.toAbsolutePath().normalize();
        for (Folder f : out) if (f.path().equals(a) || real(f.path()).equals(real(a))) return;
        out.add(new Folder(a, whose));
    }

    /** A path with its links followed; for a path that does not exist (yet), the real path of the nearest folder above it that does, with the rest after it. */
    static Path real(Path p) {
        Path a = p.toAbsolutePath().normalize();
        try { return a.toRealPath(); } catch (IOException | SecurityException ignored) { }
        Path up = a.getParent();
        Path rest = a.getFileName() == null ? Path.of("") : a.getFileName();
        while (up != null) {
            try { return up.toRealPath().resolve(rest).normalize(); } catch (IOException | SecurityException ignored) { }
            rest = up.getFileName() == null ? rest : up.getFileName().resolve(rest);
            up = up.getParent();
        }
        return a;
    }

    /** Whether the file system compares names without case (macOS and Windows, as they come): there a folder's name in another case is the same folder. */
    private static final boolean NO_CASE = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).matches(".*(mac|darwin|windows).*");

    /** Whether {@code p} is {@code folder} or lies under it; both real paths. */
    static boolean under(Path p, Path folder) {
        if (p.startsWith(folder)) return true;
        if (!NO_CASE) return false;
        String a = p.toString().toLowerCase(Locale.ROOT), b = folder.toString().toLowerCase(Locale.ROOT);
        return a.equals(b) || a.startsWith(b.endsWith(p.getFileSystem().getSeparator()) ? b : b + p.getFileSystem().getSeparator());
    }

    /** The folder {@code p} lies in, by its path as given and by its real path; null when it lies in none, or lies in the library's own folder. */
    static Folder holding(Path p, LibraryStore store) {
        if (p == null) return null;
        Path given = p.toAbsolutePath().normalize(), r = real(p);
        if (store != null) { Path lib = real(store.root()); if (under(r, lib)) return null; }
        for (Folder f : all()) if (under(given, f.path()) || under(r, real(f.path()))) return f;
        return null;
    }

    /** Whether a file found in a folder may be read: it lies in none of the folders, by its path or its real path. */
    public static boolean allowed(Path p) { return holding(p, null) == null; }

    /** The sentence for {@code p}, inside {@code f}. */
    static String said(Path p, Folder f) { return Pages.t(INSIDE, p.toAbsolutePath().normalize(), f.path(), f.whose()); }

    /** Refuses a path inside one of the folders, with the sentence that names it; nothing for any other path. */
    public static void refuse(Path p, LibraryStore store) {
        Folder f = holding(p, store);
        if (f != null) throw ProtocolError.forbidden(said(p, f));
    }

    /** The same for a door that answers with an exception of its own kind: the sentence, or null when the path is not inside one. */
    public static String refusal(Path p, LibraryStore store) { Folder f = holding(p, store); return f == null ? null : said(p, f); }

    /** The folders that lie inside {@code dir} (a person's home folder holds them): what a read of {@code dir} leaves out. */
    static List<Folder> within(Path dir) {
        List<Folder> out = new ArrayList<>();
        if (dir == null) return out;
        Path r = real(dir);
        for (Folder f : all()) if (Files.isDirectory(f.path()) && under(real(f.path()), r) && !under(r, real(f.path()))) out.add(f);
        return out;
    }

    /** What a read of a folder says of the folders it left out, one sentence each; "" when it left none out. */
    static String leftOut(List<Folder> folders) {
        StringBuilder b = new StringBuilder();
        for (Folder f : folders) b.append(b.isEmpty() ? "" : " ").append(Pages.t(LEFT_OUT, f.path(), f.whose()));
        return b.toString();
    }

    /** For a walk of many files: the folders' real paths taken once, and a test for each file. */
    public static Walk walk(LibraryStore store) { return new Walk(store); }

    public static final class Walk {
        private final List<Path> folders = new ArrayList<>();
        private final Path library;
        Walk(LibraryStore store) {
            for (Folder f : all()) { folders.add(f.path()); Path r = real(f.path()); if (!r.equals(f.path())) folders.add(r); }
            library = store == null ? null : real(store.root());
        }
        /** Whether a file found in a walk may be read: its real path (a link followed) lies in none of the folders, or in the library's own. */
        public boolean allows(Path file) {
            Path given = file.toAbsolutePath().normalize();
            for (Path f : folders) if (under(given, f)) return false;
            if (!Files.isSymbolicLink(file) && !anyLinkAbove(given)) return true;   // no link on the way: its path is its real path
            Path r = real(file);
            if (library != null && under(r, library)) return true;
            for (Path f : folders) if (under(r, f)) return false;
            return true;
        }
        /** Whether a folder above {@code p} is a link, each folder looked at once in a walk. */
        private boolean anyLinkAbove(Path p) {
            Path up = p.getParent();
            if (up == null) return false;
            Boolean known = links.get(up);
            if (known != null) return known;
            boolean is = Files.isSymbolicLink(up) || anyLinkAbove(up);
            links.put(up, is);
            return is;
        }
        private final Map<Path, Boolean> links = new HashMap<>();
    }
}
