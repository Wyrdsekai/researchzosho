package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The nightly copy. The library is markdown and small logs; a zip of everything but the
 * rebuildable index, kept for a week, is the whole backup story. Target directory:
 * {@code RESEARCHZOSHO_LIBRARY_BACKUP}, default a sibling of the library named
 * {@code <library>-backups}; {@code RESEARCHZOSHO_BACKUP_KEEP} copies are kept (default 7).
 */
public final class Backup {

    private Backup() { }

    static final int KEEP = org.researchzosho.Config.getInt("RESEARCHZOSHO_BACKUP_KEEP", 7);

    public static Path dir(LibraryStore store) {
        String o = org.researchzosho.Config.get("RESEARCHZOSHO_LIBRARY_BACKUP");
        if (o != null && !o.isBlank()) return Path.of(o);
        Path root = store.root().toAbsolutePath();
        String name = root.getFileName() == null ? "library" : root.getFileName().toString();
        return root.getParent() == null ? Path.of(name + "-backups") : root.getParent().resolve(name + "-backups");
    }

    /** Write today's zip (overwriting today's earlier one) and prune to {@code keep}. Returns the zip path. */
    public static Path run(LibraryStore store, Path target, int keep) throws IOException {
        Files.createDirectories(target);
        String name = store.root().toAbsolutePath().getFileName() == null ? "library" : store.root().toAbsolutePath().getFileName().toString();
        Path zip = target.resolve(name + "-" + LocalDate.now() + ".zip");
        Path tmp = target.resolve(zip.getFileName() + ".part-" + System.nanoTime());   // unique: two writers never share it
        Path root = store.root().toAbsolutePath();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
            try (var walk = Files.walk(root)) {
                for (Path p : walk.sorted().toList()) {
                    Path rel = root.relativize(p);
                    if (rel.toString().isEmpty() || rel.startsWith(".index")) continue;
                    if (Files.isDirectory(p)) continue;
                    out.putNextEntry(new ZipEntry(rel.toString().replace('\\', '/')));
                    Files.copy(p, out);
                    out.closeEntry();
                }
            }
        }
        Files.move(tmp, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        prune(target, name, keep);
        return zip;
    }

    static void prune(Path target, String name, int keep) throws IOException {
        List<Path> zips = new ArrayList<>();
        try (var s = Files.list(target)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (n.startsWith(name + "-") && n.endsWith(".zip")) zips.add(p);
            }
        }
        zips.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));   // newest first (dated names)
        for (int i = keep; i < zips.size(); i++) Files.deleteIfExists(zips.get(i));
    }
}
