package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.LibraryCollections.Key;
import sangitam.desktop.model.LibraryCollections.Kind;
import sangitam.desktop.model.Track;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CollectionStoreTest {
    @TempDir Path directory;

    @Test void migratesExistingPlaylistsAndPersistsMovesPinsAndMembership() throws Exception {
        Path db = directory.resolve("test.db");
        Path first = directory.resolve("one.mp3");
        Path second = directory.resolve("two.mp3");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var schema = connection.createStatement()) {
            schema.executeUpdate("CREATE TABLE playlists(id INTEGER PRIMARY KEY,name TEXT NOT NULL UNIQUE COLLATE NOCASE)");
            schema.executeUpdate("INSERT INTO playlists VALUES (42,'Favorites')");
            schema.executeUpdate("""
                CREATE TABLE playlist_items(playlist_id INTEGER NOT NULL, position INTEGER NOT NULL,
                    path TEXT NOT NULL, PRIMARY KEY(playlist_id,position))
                """);
            try (var song = connection.prepareStatement("INSERT INTO playlist_items VALUES (42,0,?)")) {
                song.setString(1, first.toString());
                song.executeUpdate();
            }
        }
        LibraryStore library = new LibraryStore(db);
        library.initialize();
        CollectionStore store = new CollectionStore(library);
        assertEquals(List.of(first), store.load().playlists().getFirst().tracks());
        assertEquals(42, store.load().playlists().getFirst().id());
        store.createFolder("Collection", 0);
        long parent = store.load().folders().getFirst().id();
        store.createFolder("Piano", parent);
        long child = store.load().folders().getLast().id();
        store.createPlaylist("Favorites", child, List.of(second), false);
        store.addPlaylistTracks(42, List.of(second, first));
        store.movePlaylist(42, parent);
        store.addFolderTracks(parent, List.of(first));
        Key pin = new Key(Kind.PLAYLIST, "42");
        store.setPinned(pin, true);
        assertThrows(SQLException.class, () -> store.moveFolder(parent, child));
        assertThrows(SQLException.class, () -> store.movePlaylist(42, child));
        store.editPlaylist(42, "Listening", "A saved list");
        store.moveFolder(child, 0);

        library.initialize();
        var reopened = new CollectionStore(new LibraryStore(db)).load();
        var playlist = reopened.playlists().stream().filter(value -> value.id() == 42).findFirst().orElseThrow();
        assertEquals("Listening", playlist.name());
        assertEquals("A saved list", playlist.description());
        assertEquals(parent, playlist.folderId());
        assertEquals(List.of(first, second), playlist.tracks());
        assertEquals(List.of(first), reopened.folders().getFirst().tracks());
        assertEquals(0, reopened.folders().getLast().parentId());
        assertTrue(reopened.pins().contains(pin));

        store.deleteFolder(parent);
        assertEquals(1, store.load().playlists().size());
        assertFalse(store.load().pins().contains(pin));
    }

    @Test void trackDetailsSurviveReimportAndRemovalLeavesPlaylistReferences() throws Exception {
        LibraryStore store = new LibraryStore(directory.resolve("test.db"));
        store.initialize();
        Path path = directory.resolve("test.mp3");
        Track original = new Track(path, "Original", "Old artist", "Album", 120, "MP3");
        store.saveAll(List.of(original));
        store.savePlaylist("Saved", List.of(path));
        store.updateDetails(List.of(path), new LibraryStore.DetailChanges("Edited", "Artist", null, "Classical", 50, 70));
        store.saveAll(List.of(new Track(path, "Original", "Old artist", "Updated album", 180, "MP3")));
        var entry = store.loadCatalog().getFirst();
        assertEquals("Edited", entry.track().title());
        assertEquals("Artist", entry.track().artist());
        assertEquals("Updated album", entry.track().album());
        assertEquals("Classical", entry.track().genre());
        assertEquals(70, entry.tags().activity());
        store.removeFromLibrary(List.of(path), null);
        assertTrue(store.loadAll().isEmpty());
        assertEquals(List.of(path), store.loadExclusions());
        assertEquals(List.of(path), store.loadPlaylists().getFirst().tracks());
    }
}
