package sangitam.desktop.ui;

import sangitam.desktop.library.LibraryService;
import sangitam.desktop.model.LibraryCollections;
import sangitam.desktop.model.LibraryCollections.Folder;
import sangitam.desktop.model.LibraryCollections.Key;
import sangitam.desktop.model.LibraryCollections.Kind;
import sangitam.desktop.model.Playlist;
import sangitam.desktop.model.Track;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A row in the mixed General tab, with ordered track membership for group actions. */
record LibraryEntry(Key key, String name, String detail, List<Track> tracks, long parent, long recent, long plays) {
    Kind kind() { return key.kind(); }
    long id() { return Long.parseLong(key.id()); }
    List<Path> paths() { return tracks.stream().map(Track::path).toList(); }

    static List<LibraryEntry> build(LibraryService.Catalog catalog, LibraryCollections collections) {
        var rows = new ArrayList<LibraryEntry>();
        var byPath = new HashMap<Path, Track>();
        var albums = new LinkedHashMap<String, List<Track>>();
        var artists = new LinkedHashMap<String, List<Track>>();
        for (Track track : catalog.tracks()) {
            byPath.put(track.path(), track);
            String detail = track.artist() + (catalog.unavailable().contains(track.path()) ? " · Unavailable" : "");
            rows.add(entry(catalog, Kind.SONG, track.path().toString(), track.title(), detail, List.of(track), 0, 0));
            if (!track.album().equalsIgnoreCase("Unknown album")) {
                String key = lower(track.album()) + "\u001f" + lower(track.artist());
                albums.computeIfAbsent(key, ignored -> new ArrayList<>()).add(track);
            }
            if (!track.artist().equalsIgnoreCase("Unknown artist")) {
                artists.computeIfAbsent(lower(track.artist()), ignored -> new ArrayList<>()).add(track);
            }
        }
        albums.forEach((key, tracks) -> rows.add(entry(catalog, Kind.ALBUM, key, tracks.getFirst().album(),
            tracks.getFirst().artist(), tracks, 0, 0)));
        artists.forEach((key, tracks) -> rows.add(entry(catalog, Kind.ARTIST, key, tracks.getFirst().artist(),
            tracks.size() + " tracks", tracks, 0, 0)));
        Map<Long, LinkedHashSet<Track>> members = new HashMap<>();
        Map<Long, List<Folder>> children = new HashMap<>();
        for (Folder folder : collections.folders()) {
            members.put(folder.id(), new LinkedHashSet<>(resolve(folder.tracks(), byPath)));
            children.computeIfAbsent(folder.parentId(), ignored -> new ArrayList<>()).add(folder);
        }
        for (Playlist playlist : collections.playlists()) {
            List<Track> tracks = resolve(playlist.tracks(), byPath);
            String detail = playlist.description().isBlank() ? tracks.size() + " tracks" : playlist.description();
            rows.add(entry(catalog, Kind.PLAYLIST, Long.toString(playlist.id()), playlist.name(), detail,
                tracks, playlist.folderId(), playlist.updatedAt()));
            var folder = members.get(playlist.folderId());
            if (folder != null) folder.addAll(tracks);
        }
        addFolders(0, children, members, catalog, rows);
        return List.copyOf(rows);
    }

    private static List<Track> resolve(List<Path> paths, Map<Path, Track> byPath) {
        return paths.stream().map(byPath::get).filter(java.util.Objects::nonNull).toList();
    }

    private static LinkedHashSet<Track> addFolders(long parent, Map<Long, List<Folder>> children,
        Map<Long, LinkedHashSet<Track>> members, LibraryService.Catalog catalog, List<LibraryEntry> rows) {
        var result = members.getOrDefault(parent, new LinkedHashSet<>());
        for (Folder child : children.getOrDefault(parent, List.of())) {
            var tracks = addFolders(child.id(), children, members, catalog, rows);
            rows.add(entry(catalog, Kind.FOLDER, Long.toString(child.id()), child.name(), tracks.size() + " tracks",
                List.copyOf(tracks), parent, child.updatedAt()));
            result.addAll(tracks);
        }
        return result;
    }

    private static LibraryEntry entry(LibraryService.Catalog catalog, Kind kind, String id, String name, String detail,
                                      List<Track> tracks, long parent, long changed) {
        long recent = changed;
        long plays = 0;
        for (Track track : tracks) {
            recent = Math.max(recent, catalog.recent().getOrDefault(track.path(), 0L));
            plays += catalog.playsFor(track);
        }
        return new LibraryEntry(new Key(kind, id), name, detail, List.copyOf(tracks), parent, recent, plays);
    }

    static String lower(String value) { return value.toLowerCase(Locale.ROOT); }
}
