package sangitam.desktop.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** User organization. Folder id zero represents the virtual library root. */
public record LibraryCollections(List<Playlist> playlists, List<Folder> folders, Set<Key> pins) {
    public record Folder(long id, long parentId, String name, List<Path> tracks, long updatedAt) {
        public Folder { tracks = List.copyOf(tracks); }
    }
    public enum Kind { SONG, ARTIST, ALBUM, PLAYLIST, FOLDER }
    public record Key(Kind kind, String id) { }

    public LibraryCollections {
        playlists = List.copyOf(playlists);
        folders = List.copyOf(folders);
        pins = Set.copyOf(pins);
    }

    public static LibraryCollections empty() { return new LibraryCollections(List.of(), List.of(), Set.of()); }
}
