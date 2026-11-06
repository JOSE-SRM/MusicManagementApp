package sangitam.desktop.ui;

import sangitam.desktop.library.LibraryService;
import sangitam.desktop.model.*;
import sangitam.desktop.model.LibraryCollections.*;
import java.nio.file.Path;
import java.util.*;

/** Built on the collection worker. Only bounded rows and their cover candidates reach Home. */
record HomeLibraryData(List<LibraryEntry> recent, List<LibraryEntry> pinned, Set<Path> unavailable, Map<Path, FileStamp> stamps) {
    static final int RECENT_LIMIT = 12, PINNED_LIMIT = 8;
    HomeLibraryData { recent = List.copyOf(recent); pinned = List.copyOf(pinned); unavailable = Set.copyOf(unavailable); stamps = Map.copyOf(stamps); }
    static HomeLibraryData build(LibraryService.Catalog catalog, LibraryCollections collections, List<Track> played) {
        var recent = played.stream().limit(RECENT_LIMIT).map(track -> new LibraryEntry(new Key(Kind.SONG, track.path().toString()),
            track.title(), track.artist(), List.of(track), 0, 0, 0)).toList();
        var playlists = collections.playlists().stream().filter(playlist -> collections.pins().contains(new Key(Kind.PLAYLIST, Long.toString(playlist.id()))))
            .sorted(Comparator.comparing(Playlist::name, String.CASE_INSENSITIVE_ORDER).thenComparingLong(Playlist::id)).limit(PINNED_LIMIT).toList();
        Set<Path> wanted = new HashSet<>(); playlists.forEach(playlist -> wanted.addAll(playlist.tracks()));
        Map<Path, Track> tracks = new HashMap<>();
        if (!wanted.isEmpty()) for (Track track : catalog.tracks()) if (wanted.contains(track.path())) tracks.put(track.path(), track);
        var pinned = playlists.stream().map(playlist -> {
            List<Track> members = playlist.tracks().stream().map(tracks::get).filter(Objects::nonNull).toList();
            String description = (playlist.smart() ? "Smart · " : "") + members.size() + " tracks";
            return new LibraryEntry(new Key(Kind.PLAYLIST, Long.toString(playlist.id())), playlist.name(), description, members, playlist.folderId(), playlist.updatedAt(), 0);
        }).toList();
        Map<Path, FileStamp> stamps = new HashMap<>(); Set<Path> unavailable = new HashSet<>();
        for (LibraryEntry entry : java.util.stream.Stream.concat(recent.stream(), pinned.stream()).toList()) {
            for (Track track : entry.tracks().stream().limit(3).toList()) {
                if (catalog.stamps().containsKey(track.path())) stamps.put(track.path(), catalog.stamps().get(track.path()));
                if (catalog.unavailable().contains(track.path())) unavailable.add(track.path());
            }
        }
        return new HomeLibraryData(recent, pinned, unavailable, stamps);
    }
}
