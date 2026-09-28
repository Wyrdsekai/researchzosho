package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A file written beside itself and moved into place, so that a program stopped while writing it (the computer went off, the command was
 * interrupted) leaves the old file or the new one, never half of one.
 */
public final class AtomicWrite {
    private AtomicWrite() { }

    public static void text(Path file, String text) throws IOException {
        Path tmp = beside(file);
        try { Files.writeString(tmp, text, StandardCharsets.UTF_8); into(tmp, file); }
        finally { Files.deleteIfExists(tmp); }
    }

    /** Each line ended as {@link Files#write(Path, Iterable, java.nio.charset.Charset, java.nio.file.OpenOption...)} ends it. */
    public static void lines(Path file, Iterable<String> lines) throws IOException {
        Path tmp = beside(file);
        try { Files.write(tmp, lines, StandardCharsets.UTF_8); into(tmp, file); }
        finally { Files.deleteIfExists(tmp); }
    }

    /** A copy that is whole or not there at all. */
    public static void copy(Path from, Path to) throws IOException {
        Path tmp = beside(to);
        try { Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING); into(tmp, to); }
        finally { Files.deleteIfExists(tmp); }
    }

    // a name of its own for each write, so two programs writing one file never share the file beside it
    private static Path beside(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        return Files.createTempFile(dir, "." + file.getFileName() + ".", ".tmp");
    }

    private static void into(Path tmp, Path file) throws IOException {
        // Windows refuses to replace a file another program has open at that moment (the service reading it): a few tries, then a plain copy
        for (int i = 0; ; i++) {
            try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); return; }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); return; }
            catch (FileSystemException busy) {
                if (i >= 20) { Files.copy(tmp, file, StandardCopyOption.REPLACE_EXISTING); return; }
                try { Thread.sleep(25); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); throw busy; }
            }
        }
    }
}
