package sangitam.desktop.ui;

import sangitam.desktop.library.LibraryService;
import sangitam.desktop.model.*;
import sangitam.desktop.model.LibraryCollections.*;
import java.nio.file.Path;
import java.util.*;

record ArtistPageData(List<Track> tracks, List<Track> popular, List<Track> liked, List<LibraryEntry> albums,
                      List<String> collaborators, double seconds) {
    static ArtistPageData build(String name, LibraryService.Catalog catalog, LibraryCollections organization, Map<Path, Double> listening) {
        double seconds = catalog.tracks().stream().filter(track -> ArtistCredits.features(track, name)).mapToDouble(track -> listening.getOrDefault(track.path(), 0d)).sum();
        return build(name, catalog, organization, seconds);
    }
    static ArtistPageData build(String name, LibraryService.Catalog catalog, LibraryCollections organization, double seconds) {
        List<Track> tracks = catalog.tracks().stream().filter(track -> ArtistCredits.features(track, name)).toList();
        var popular = tracks.stream().sorted(Comparator.<Track>comparingInt(catalog::playsFor).reversed()
            .thenComparing(Track::title, String.CASE_INSENSITIVE_ORDER).thenComparing(track -> track.path().toString())).limit(10).toList();
        var liked = tracks.stream().filter(track -> organization.pins().contains(new Key(Kind.SONG, track.path().toString()))).toList();
        Set<String> albumIds = new HashSet<>();
        for (Track track : tracks) if (hasAlbum(track)) albumIds.add(albumId(track));
        var albums = new TreeMap<String, List<Track>>();
        for (Track track : catalog.tracks()) if (albumIds.contains(albumId(track))) albums.computeIfAbsent(albumId(track), ignored -> new ArrayList<>()).add(track);
        var albumEntries = albums.entrySet().stream().map(album -> new LibraryEntry(new Key(Kind.ALBUM, album.getKey()), album.getValue().getFirst().album(),
            album.getValue().size() + " tracks · " + album.getValue().getFirst().artist(), List.copyOf(album.getValue()), 0, 0, 0))
            .sorted(Comparator.comparing(LibraryEntry::name, String.CASE_INSENSITIVE_ORDER)).toList();
        var collaborators = new HashMap<String, Integer>(); var labels = new HashMap<String, String>();
        for (Track track : tracks) for (var credit : ArtistCredits.of(track)) {
            String key = ArtistCredits.key(credit.name());
            if (!key.equals(ArtistCredits.key(name))) { collaborators.merge(key, 1, Integer::sum); labels.putIfAbsent(key, credit.name()); }
        }
        var partners = collaborators.keySet().stream().sorted(Comparator.<String>comparingInt(collaborators::get).reversed().thenComparing(key -> key))
            .limit(12).map(key -> labels.get(key) + "\u001f" + collaborators.get(key)).toList();
        return new ArtistPageData(tracks, popular, liked, albumEntries, partners, seconds);
    }
    static boolean hasAlbum(Track track) { return !track.album().isBlank() && !track.album().equalsIgnoreCase("Unknown album"); }
    static String albumId(Track track) { return LibraryEntry.lower(track.album()) + "\u001f" + LibraryEntry.lower(track.artist()); }
}
