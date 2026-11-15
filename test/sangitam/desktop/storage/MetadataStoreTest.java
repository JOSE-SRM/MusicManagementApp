package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.*;
import sangitam.desktop.model.TrackMetadata.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MetadataStoreTest {
    @TempDir Path directory;
    private Track track(Path path, String composer, String conductor, int bitrate) {
        return new Track(path, "Sonata", "Performer", "Album", 30, "MP3", "Classical",
            new TrackMetadata(Map.of(Field.COMPOSER, new Value(composer, Origin.EMBEDDED),
                Field.CONDUCTOR, new Value(conductor, Origin.EMBEDDED), Field.WORK, new Value("Old work", Origin.EMBEDDED)),
                Set.of("Imported"), bitrate, 44100, 16, "Stereo", true, 1));
    }
    @Test void manualRolesAndClearedFieldsSurviveReimportWhileTechnicalDataRefreshes() throws Exception {
        var store = new LibraryStore(directory.resolve("library.db")); store.initialize();
        Path path = directory.resolve("song.mp3");
        store.saveAll(List.of(track(path, "Composer A", "Conductor A", 128)));
        store.updateDetails(List.of(path), new LibraryStore.DetailChanges(null, null, null, null, null, null,
            Map.of(Field.COMPOSER, "My correction", Field.CONDUCTOR, ""), Set.of("Focus", "User label")));
        store.saveAll(List.of(track(path, "Composer B", "Conductor B", 320)));
        store = new LibraryStore(directory.resolve("library.db")); store.initialize();
        Track saved = store.loadAll().getFirst();
        assertEquals("My correction", saved.metadata().text(Field.COMPOSER));
        assertEquals("", saved.metadata().text(Field.CONDUCTOR));
        assertEquals(Origin.USER, saved.metadata().fields().get(Field.CONDUCTOR).origin());
        assertEquals(320, saved.metadata().bitrateKbps());
        assertEquals(Set.of("Focus", "User label"), saved.metadata().tags());
        assertEquals("Old work", saved.metadata().text(Field.WORK));
        assertEquals("Performer", saved.artist());
    }
    @Test void removingATrackCleansMetadataButPreservesPlaylistAndHistoryReferences() throws Exception {
        var store = new LibraryStore(directory.resolve("library.db")); store.initialize();
        Path path = directory.resolve("song.mp3"); store.saveAll(List.of(track(path, "Composer", "Conductor", 128)));
        store.savePlaylist("Keep", List.of(path)); store.recordPlay(path);
        store.removeFromLibrary(List.of(path), null);
        try (var connection = store.connect(); var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT COUNT(*) FROM track_metadata")) {
            assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
        }
        assertEquals(List.of(path), store.loadPlaylists().getFirst().tracks());
        assertEquals(1, store.loadListens().size());
    }
    @Test void malformedStoredMetadataIsReportedInsteadOfDiscardingManualCorrections() throws Exception {
        var store = new LibraryStore(directory.resolve("library.db")); store.initialize();
        store.saveAll(List.of(track(directory.resolve("song.mp3"), "Composer", "Conductor", 128)));
        try (var connection = store.connect(); var sql = connection.createStatement()) { sql.executeUpdate("UPDATE track_metadata SET payload='{broken'"); }
        assertThrows(java.sql.SQLException.class, store::loadAll);
    }
}
