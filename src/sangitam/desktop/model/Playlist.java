package sangitam.desktop.model;

import java.nio.file.Path;
import java.util.List;

/** A named, ordered collection of local track paths stored in SQLite. */
public record Playlist(long id, String name, List<Path> tracks) {
    public Playlist { tracks = List.copyOf(tracks); }
    @Override public String toString() { return name + " (" + tracks.size() + ")"; }
}
