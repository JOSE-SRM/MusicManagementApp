package sangitam.desktop.model;

import java.nio.file.Path;
import java.util.List;

/** A named, ordered collection of local track paths stored in SQLite. */
public record Playlist(long id, String name, List<Path> tracks, long folderId, String description, long updatedAt) {
    public Playlist { tracks = List.copyOf(tracks); }
    public Playlist(long id, String name, List<Path> tracks) { this(id, name, tracks, 0, "", 0); }
    @Override public String toString() { return name + " (" + tracks.size() + ")"; }
}
