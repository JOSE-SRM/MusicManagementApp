package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.*;
import sangitam.desktop.model.TrackMetadata.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DetailResetTest {
    @TempDir Path folder;
    private Track file(Path path, String title, String composer) {
        return new Track(path, title, "File artist", "File album", 60, "MP3", "Classical",
            new TrackMetadata(Map.of(Field.COMPOSER, new Value(composer, Origin.EMBEDDED)), Set.of("File tag"), 320, 44100, 16, "Stereo", true, 1));
    }
    @Test void selectedOverridesFollowFilesAgainWhileOtherCorrectionsAndRatingsSurvive() throws Exception {
        var store = new LibraryStore(folder.resolve("library.db")); store.initialize();
        Path path = folder.resolve("song.mp3");
        var stamp = new FileStamp(100, 200);
        store.saveScan(List.of(file(path, "First", "First composer")), Map.of(path, stamp), Set.of(), List.of(), Map.of());
        store.updateDetails(List.of(path), new LibraryStore.DetailChanges("My title", "My artist", "My album", "My genre", 73, 42,
            Map.of(Field.COMPOSER, "My composer", Field.CONDUCTOR, "My conductor", Field.WORK, "My work"), Set.of("My tag")));
        store.recordPlay(path); store.savePlaylist("Keep", List.of(path));
        var reset = new DetailReset(Set.of(DetailReset.Basic.TITLE, DetailReset.Basic.GENRE), Set.of(Field.COMPOSER, Field.WORK), true);
        assertEquals(1, store.restoreDetails(List.of(file(path, "Second", "Second composer")), reset));
        Track result = store.loadAll().getFirst();
        assertEquals("Second", result.title()); assertEquals("Classical", result.genre());
        assertEquals("My artist", result.artist()); assertEquals("My album", result.album());
        assertEquals("Second composer", result.metadata().text(Field.COMPOSER));
        assertEquals(Origin.EMBEDDED, result.metadata().fields().get(Field.COMPOSER).origin());
        assertFalse(result.metadata().fields().containsKey(Field.WORK));
        assertEquals("My conductor", result.metadata().text(Field.CONDUCTOR));
        assertEquals(Set.of("File tag"), result.metadata().tags());
        var entry = store.loadCatalog().getFirst();
        assertEquals(73, entry.tags().energy()); assertEquals(42, entry.tags().activity()); assertTrue(entry.manualTags());
        assertEquals(1, entry.playCount()); assertEquals(stamp, entry.stamp()); // partial reads must not hide a later full rescan
        assertEquals(List.of(path), store.loadPlaylists().getFirst().tracks());
        store.saveAll(List.of(file(path, "Third", "Third composer")));
        result = store.loadAll().getFirst();
        assertEquals("Third", result.title()); assertEquals("Third composer", result.metadata().text(Field.COMPOSER));
        assertEquals("My artist", result.artist()); assertEquals("My conductor", result.metadata().text(Field.CONDUCTOR));
        try (var connection = store.connect(); var query = connection.createStatement(); var row = query.executeQuery("SELECT search_text FROM track_metadata")) {
            assertTrue(row.next()); assertTrue(row.getString(1).contains("third composer")); assertFalse(row.getString(1).contains("my composer"));
        }
    }
    @Test void failedBatchRollsBackEarlierTracksAndDoesNotInsertRemovedTracks() throws Exception {
        var store = new LibraryStore(folder.resolve("library.db")); store.initialize();
        Path a = folder.resolve("a.mp3"), b = folder.resolve("b.mp3");
        store.saveAll(List.of(file(a, "A", "Composer"), file(b, "B", "Composer")));
        try (var connection = store.connect(); var sql = connection.prepareStatement("UPDATE track_metadata SET payload='invalid' WHERE path=?")) {
            sql.setString(1, b.toString()); sql.executeUpdate();
        }
        var reset = new DetailReset(Set.of(DetailReset.Basic.TITLE), Set.of(), false);
        assertThrows(SQLException.class, () -> store.restoreDetails(List.of(file(a, "Changed", "Composer"), file(b, "Changed", "Composer")), reset));
        try (var connection = store.connect(); var query = connection.prepareStatement("SELECT title FROM tracks WHERE path=?")) {
            query.setString(1, a.toString()); try (var row = query.executeQuery()) { assertTrue(row.next()); assertEquals("A", row.getString(1)); }
        }
        assertEquals(0, store.restoreDetails(List.of(file(folder.resolve("removed.mp3"), "Removed", "Composer")), reset));
    }
}
