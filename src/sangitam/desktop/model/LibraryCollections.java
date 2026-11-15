package sangitam.desktop.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** User organization. Folder id zero represents the virtual library root. */
public record LibraryCollections(List<Playlist> playlists, List<Folder> folders, Set<Key> pins,
                                 Set<Path> likes, Set<Path> formerSongPins) {
    public record Folder(long id, long parentId, String name, List<Path> tracks, long updatedAt) {
        public Folder { tracks = List.copyOf(tracks); }
    }
    public enum Kind { SONG, ARTIST, ALBUM, PLAYLIST, FOLDER }
    public record Key(Kind kind, String id) { }

    public LibraryCollections {
        playlists = List.copyOf(playlists);
        folders = List.copyOf(folders);
        pins = Set.copyOf(pins);
        likes = Set.copyOf(likes);
        formerSongPins = Set.copyOf(formerSongPins);
    }

    public LibraryCollections(List<Playlist> playlists, List<Folder> folders, Set<Key> pins) {
        this(playlists, folders, pins, Set.of(), Set.of());
    }

    public static LibraryCollections empty() { return new LibraryCollections(List.of(), List.of(), Set.of()); }

    /** Project the stored song likes as one managed playlist. */
    public LibraryCollections withLikedSongs() {
        List<Path> liked = likes.stream().sorted().toList();
        var all = new java.util.ArrayList<>(playlists.stream().filter(playlist -> !playlist.likedSongs()).toList());
        all.addFirst(new Playlist(Playlist.LIKED_ID, Playlist.LIKED_NAME, liked, 0, "Songs marked with the heart", 0));
        return new LibraryCollections(all, folders, pins, likes, formerSongPins);
    }
}
