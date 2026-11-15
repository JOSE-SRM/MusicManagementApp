package sangitam.desktop.model;

import java.nio.file.Path;

/** A file or folder explicitly selected for import. */
public record LibrarySource(Path path, boolean recursive) {
    public LibrarySource {
        path = path.toAbsolutePath().normalize();
    }
}
