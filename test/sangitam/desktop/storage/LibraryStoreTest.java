package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackTags;
import sangitam.desktop.history.ListenEvent;
import sangitam.desktop.model.PlaybackMemory;

import java.nio.file.Path;
import java.net.URI;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies that import data survives a new store instance and a repeat import updates one row. */
class LibraryStoreTest {
    @TempDir Path folder;

    @Test void persistsAndUpsertsTracks() throws Exception {
        Path database = folder.resolve("data/library.db");
        LibraryStore first = new LibraryStore(database);
        first.initialize();
        Track original = new Track(folder.resolve("song.mp3"), "First title", "Artist", "Album", 67, "MP3");
        first.saveAll(List.of(original));

        LibraryStore reopened = new LibraryStore(database);
        reopened.initialize();
        assertEquals(List.of(original), reopened.loadAll());
        Track edited = new Track(original.path(), "Updated title", "Artist", "Album", 67, "MP3");
        reopened.saveAll(List.of(edited));
        assertEquals(List.of(edited), first.loadAll());
    }

    @Test void upgradesAnExistingLibraryWithoutLosingTracks() throws Exception {
        Path database = folder.resolve("old-library.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE tracks (path TEXT PRIMARY KEY, title TEXT NOT NULL, "
                + "artist TEXT NOT NULL, album TEXT NOT NULL, duration_seconds REAL NOT NULL, format TEXT NOT NULL)");
            statement.executeUpdate("INSERT INTO tracks VALUES ('" + folder.resolve("old.mp3")
                + "', 'Old song', 'Artist', 'Album', 180, 'MP3')");
        }
        LibraryStore store = new LibraryStore(database);
        store.initialize();
        assertEquals(1, store.loadAll().size());
        Track restored = store.loadAll().get(0);
        assertEquals("Unknown genre", restored.genre());
        store.saveTags(restored.path(), new TrackTags(27, 16, true, 400, 123));
        store.recordPlay(restored.path());
        LibraryStore.CatalogEntry entry = store.loadCatalog().get(0);
        assertEquals(27, entry.tags().energy());
        assertEquals(1, entry.playCount());
        assertEquals(1, store.loadListens().size());
        assertEquals("Sangitam", store.loadListens().get(0).source());
    }

    @Test void repeatedProviderSyncDoesNotDuplicateSavedPlays() throws Exception {
        LibraryStore store = new LibraryStore(folder.resolve("history.db"));
        store.initialize();
        ListenEvent listen = new ListenEvent("Spotify", "track:time", "Song", "Artist", "Album",
            1_700_000_000_000L, URI.create("https://open.spotify.com/track/test"), null);
        assertEquals(1, store.saveListens(List.of(listen)));
        assertEquals(0, store.saveListens(List.of(listen)));
        assertEquals(List.of(listen), store.loadListens());
    }

    @Test void playlistsKeepOrderAndLastPositionSurvivesRestart() throws Exception {
        Path database = folder.resolve("playlist-library.db");
        LibraryStore store = new LibraryStore(database);
        store.initialize();
        Path first = folder.resolve("first.mp3");
        Path second = folder.resolve("second.mp3");
        store.savePlaylist("Road trip", List.of(second, first));
        store.savePlaybackMemory(new PlaybackMemory(second, 47.5));

        LibraryStore reopened = new LibraryStore(database);
        reopened.initialize();
        assertEquals(List.of(second, first), reopened.loadPlaylists().get(0).tracks());
        assertEquals(47.5, reopened.loadPlaybackMemory().positionSeconds());
        reopened.savePlaylist("road trip", List.of(first));
        assertEquals(1, reopened.loadPlaylists().size());
        assertEquals(List.of(first), reopened.loadPlaylists().get(0).tracks());
        reopened.deletePlaylist(reopened.loadPlaylists().get(0).id());
        assertTrue(reopened.loadPlaylists().isEmpty());
    }

    @Test void manualCorrectionsSurviveRescanAndAutomaticAnalysis() throws Exception {
        LibraryStore store = new LibraryStore(folder.resolve("corrections.db"));
        store.initialize();
        Track original = new Track(folder.resolve("song.mp3"), "Song", "Artist", "Album", 180,
            "MP3", "Unknown genre");
        store.saveAll(List.of(original));
        store.updateTags(original.path(), "Rock", 86, 72, 1234, 5678);
        store.saveAll(List.of(original));
        store.saveTags(original.path(), new TrackTags(5, 4, true, 1234, 5678));
        LibraryStore.CatalogEntry saved = store.loadCatalog().get(0);
        assertEquals("Rock", saved.track().genre());
        assertEquals(86, saved.tags().energy());
        assertEquals(72, saved.tags().activity());
        assertTrue(saved.manualTags());
    }

    @Test void oldAnalysisIsMarkedPendingUntilRecomputed() throws Exception {
        Path database = folder.resolve("old-analysis.db");
        LibraryStore store = new LibraryStore(database);
        store.initialize();
        Track song = new Track(folder.resolve("song.mp3"), "Song", "Artist", "Album", 180, "MP3");
        store.saveAll(List.of(song));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var update = connection.prepareStatement(
                 "UPDATE tracks SET energy=10, activity=2, analyzed=1, file_size=123, file_modified=456 WHERE path=?")) {
            update.setString(1, song.path().toString());
            update.executeUpdate();
        }
        assertFalse(store.loadCatalog().get(0).tags().analyzed());
        store.saveTags(song.path(), new TrackTags(75, 70, true, 123, 456));
        assertEquals(75, store.loadCatalog().get(0).tags().energy());
    }
}
