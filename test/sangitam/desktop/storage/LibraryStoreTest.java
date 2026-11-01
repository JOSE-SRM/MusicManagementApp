package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackTags;

import java.nio.file.Path;
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
    }
}
