package sangitam.desktop.model;

import java.nio.file.Path;
import java.util.List;

/** A named, ordered collection of local track paths stored in SQLite. */
public record Playlist(long id, String name, List<Path> tracks, long folderId, String description, long updatedAt,
                       SmartPlaylistRule rule) {
    public static final long LIKED_ID = -1;
    public static final String LIKED_NAME = "Liked Songs";
    public Playlist { tracks = List.copyOf(tracks); }
    public Playlist(long id, String name, List<Path> tracks) { this(id, name, tracks, 0, "", 0); }
    public Playlist(long id, String name, List<Path> tracks, long folderId, String description, long updatedAt) {
        this(id, name, tracks, folderId, description, updatedAt, null);
    }
    public boolean smart() { return rule != null; }
    public boolean likedSongs() { return id == LIKED_ID; }
    public boolean acceptsManualEdits() { return !smart() && !likedSongs(); }
    @Override public String toString() { return name + (smart() ? " · Smart" : "") + " (" + tracks.size() + ")"; }
}
