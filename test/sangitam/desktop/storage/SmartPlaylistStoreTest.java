package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.*;
import sangitam.desktop.model.SmartPlaylistRule.*;
import sangitam.desktop.model.LibraryCollections.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SmartPlaylistStoreTest {
    @TempDir Path directory;
    private LibraryStore library() throws Exception { var store = new LibraryStore(directory.resolve("library.db")); store.initialize(); return store; }
    private Path song(LibraryStore store, String file, String title, String genre, Set<String> tags) throws Exception {
        Path path = directory.resolve(file + ".mp3");
        var metadata = new TrackMetadata(Map.of(), tags, 128, 44100, 0, "Stereo", false, 1);
        store.saveAll(List.of(new Track(path, title, "Artist", "Album", 60, "MP3", genre, metadata)));
        return path;
    }
    private SmartPlaylistRule rule(String text, String genre, Set<String> tags, Range energy, Range movement, int plays, boolean available, Order order, int limit) {
        return new SmartPlaylistRule(text, genre, tags, energy, movement, plays, available, order, limit);
    }
    @Test void membershipReevaluatesAfterImportTagEditsAvailabilityAndPlays() throws Exception {
        var store = library(); var collections = new CollectionStore(store);
        Path first = song(store, "first", "Piano Sonata", "Classical", Set.of("Focus", "Étude"));
        Path second = song(store, "second", "Piano Concerto", "Classical", Set.of("Focus"));
        var rule = rule("piano", "classical", Set.of("focus", "étude"), new Range(20, 60), new Range(0, 50), 1, true, Order.MOST_PLAYED, 100);
        collections.createSmartPlaylist("Focus", "Measured piano", 0, rule);
        assertTrue(store.loadPlaylists().getFirst().tracks().isEmpty());
        store.updateDetails(List.of(first), new LibraryStore.DetailChanges(null, null, null, null, 40, 30));
        store.recordPlay(first);
        var saved = store.loadPlaylists().getFirst();
        assertTrue(saved.smart()); assertEquals(rule, saved.rule()); assertEquals(List.of(first), saved.tracks());
        store.updateDetails(List.of(second), new LibraryStore.DetailChanges(null, null, null, null, 45, 20, Map.of(), Set.of("FOCUS", "ÉTUDE")));
        store.recordPlay(second); store.recordPlay(second);
        assertEquals(List.of(second, first), store.loadPlaylists().getFirst().tracks());
        store.saveScan(List.of(), Map.of(), Set.of(second), List.of(), Map.of());
        assertEquals(List.of(first), store.loadPlaylists().getFirst().tracks());
        store.updateDetails(List.of(first), new LibraryStore.DetailChanges(null, null, null, null, null, null, Map.of(), Set.of("Other")));
        assertTrue(store.loadPlaylists().getFirst().tracks().isEmpty());
        store.initialize();
        assertEquals(rule, new LibraryStore(directory.resolve("library.db")).loadPlaylists().getFirst().rule());
        Path imported = song(store, "new", "Piano Trio", "Classical", Set.of("Focus", "Étude"));
        store.updateDetails(List.of(imported), new LibraryStore.DetailChanges(null, null, null, null, 35, 20));
        store.recordPlay(imported);
        assertEquals(List.of(imported), store.loadPlaylists().getFirst().tracks(), "Newly imported matches join the saved rule");
    }
    @Test void preservesStaticListsAndRejectsAllManualWritesToSmartMembership() throws Exception {
        var store = library(); var collections = new CollectionStore(store);
        Path path = song(store, "one", "Song", "Jazz", Set.of());
        store.savePlaylist("Regular", List.of(path));
        collections.createSmartPlaylist("Smart", "", 0, SmartPlaylistRule.defaults());
        long id = store.loadPlaylists().stream().filter(Playlist::smart).findFirst().orElseThrow().id();
        assertThrows(SQLException.class, () -> collections.addPlaylistTracks(id, List.of(path)));
        assertThrows(SQLException.class, () -> collections.createPlaylist("Smart", 0, List.of(path), true));
        assertThrows(SQLException.class, () -> store.savePlaylist("Smart", List.of(path)));
        try (var connection = store.connect(); var sql = connection.createStatement(); var row = sql.executeQuery("SELECT COUNT(*) FROM playlist_items WHERE playlist_id=" + id)) {
            row.next(); assertEquals(0, row.getInt(1));
        }
        assertEquals(List.of(path), store.loadPlaylists().stream().filter(p -> !p.smart()).findFirst().orElseThrow().tracks());
        var changed = rule("", "Jazz", Set.of(), null, null, 0, true, Order.ALPHABETICAL, 5);
        collections.editSmartPlaylist(id, "Renamed", "A rule", changed);
        assertEquals(changed, store.loadPlaylists().stream().filter(Playlist::smart).findFirst().orElseThrow().rule());
        long regular = store.loadPlaylists().stream().filter(p -> !p.smart()).findFirst().orElseThrow().id();
        assertThrows(SQLException.class, () -> collections.editSmartPlaylist(regular, "Oops", "", changed));
    }
    @Test void movesPinsAndDeletesRulesWithTheirOwningFoldersAndPlaylists() throws Exception {
        var store = library(); var collections = new CollectionStore(store);
        collections.createFolder("Parent", 0); long folder = collections.load().folders().getFirst().id();
        collections.createSmartPlaylist("Dynamic", "", 0, SmartPlaylistRule.defaults());
        long id = store.loadPlaylists().getFirst().id();
        collections.movePlaylist(id, folder); collections.setPinned(new Key(Kind.PLAYLIST, Long.toString(id)), true);
        assertEquals(folder, store.loadPlaylists().getFirst().folderId());
        collections.deleteFolder(folder);
        assertTrue(store.loadPlaylists().isEmpty()); assertTrue(collections.load().pins().isEmpty());
        collections.createSmartPlaylist("Second", "", 0, SmartPlaylistRule.defaults());
        store.deletePlaylist(store.loadPlaylists().getFirst().id());
        try (var connection = store.connect(); var sql = connection.createStatement(); var row = sql.executeQuery("SELECT COUNT(*) FROM smart_playlist_rules")) {
            row.next(); assertEquals(0, row.getInt(1));
        }
    }
    @Test void previewAndSavedMatchesShareLiteralFiltersLimitsAndRatingFreshness() throws Exception {
        var store = library(); var collections = new CollectionStore(store);
        for (int i = 0; i < 25; i++) song(store, "track" + i, "Same 100%_literal\\", "Classical", Set.of("Focus"));
        var rule = rule("%_literal\\", "", Set.of(), null, null, 0, false, Order.ALPHABETICAL, 22);
        var preview = new SmartPlaylistStore(store).preview(rule);
        assertEquals(25, preview.matching()); assertEquals(22, preview.included()); assertEquals(20, preview.examples().size());
        collections.createSmartPlaylist("Literal", "", 0, rule);
        var paths = store.loadPlaylists().getFirst().tracks(); assertEquals(22, paths.size());
        assertEquals(preview.examples().stream().map(Track::path).toList(), paths.subList(0, 20));
        var energy = rule("", "", Set.of(), new Range(0, 100), null, 0, false, Order.RECENT, 100);
        assertEquals(0, new SmartPlaylistStore(store).preview(energy).matching());
        try (var connection = store.connect(); var sql = connection.createStatement()) { sql.executeUpdate("UPDATE tracks SET analyzed=1,energy=50,analysis_version=0"); }
        assertEquals(0, new SmartPlaylistStore(store).preview(energy).matching(), "Obsolete analysis is not a fresh rating");
        store.updateDetails(List.of(directory.resolve("track0.mp3")), new LibraryStore.DetailChanges(null, null, null, null, 50, 20));
        assertEquals(1, new SmartPlaylistStore(store).preview(energy).matching());
        var injection = rule("%' OR 1=1 --", "", Set.of(), null, null, 0, true, Order.RECENT, 100);
        assertEquals(0, new SmartPlaylistStore(store).preview(injection).matching());
    }
    @Test void rejectsInvalidRulesAndReportsCorruptSavedJson() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new Range(80, 20));
        assertThrows(IllegalArgumentException.class, () -> rule("", "", Set.of(), null, null, -1, true, Order.RECENT, 100));
        assertThrows(IllegalArgumentException.class, () -> rule("", "", Set.of(), null, null, 0, true, Order.RECENT, 5001));
        var store = library(); var collections = new CollectionStore(store);
        collections.createSmartPlaylist("Smart", "", 0, SmartPlaylistRule.defaults());
        try (var connection = store.connect(); var sql = connection.createStatement()) { sql.executeUpdate("UPDATE smart_playlist_rules SET rule_json='{broken'"); }
        assertThrows(SQLException.class, store::loadPlaylists);
    }

    @Test void regularCopyIsAFixedSnapshotWhileTheSmartSourceKeepsUpdating() throws Exception {
        var store = library(); var collections = new CollectionStore(store);
        Path first = song(store, "first", "A song", "Jazz", Set.of());
        collections.createSmartPlaylist("Dynamic", "", 0, SmartPlaylistRule.defaults());
        collections.createPlaylist("Snapshot", 0, store.loadPlaylists().getFirst().tracks(), false);
        Path second = song(store, "second", "B song", "Jazz", Set.of());
        var saved = store.loadPlaylists();
        assertEquals(Set.of(first, second), Set.copyOf(saved.stream().filter(Playlist::smart).findFirst().orElseThrow().tracks()));
        assertEquals(List.of(first), saved.stream().filter(p -> !p.smart()).findFirst().orElseThrow().tracks());
    }
}
