package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.Track;

import java.nio.file.Path;
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
}
