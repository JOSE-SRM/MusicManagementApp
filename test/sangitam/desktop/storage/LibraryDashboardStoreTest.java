package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.Track;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LibraryDashboardStoreTest {
    @TempDir Path folder;
    @Test void recentMeansRecordedListeningWithAvailabilityStableTiesAndBoundedResults() throws Exception {
        var store = new LibraryStore(folder.resolve("library.db")); store.initialize();
        var tracks = new ArrayList<Track>();
        for (int i = 0; i < 16; i++) tracks.add(new Track(folder.resolve(String.format("%02d.mp3", i)), "Song " + i, "Artist", "Album", 60, "MP3"));
        store.saveAll(tracks);
        var home = new LibraryDashboardStore(store); assertTrue(home.recent(12).isEmpty());
        try (var connection = store.connect(); var query = connection.createStatement()) {
            query.executeUpdate("UPDATE tracks SET last_played_at=100");
            try (var update = connection.prepareStatement("UPDATE tracks SET last_played_at=200,unavailable=1 WHERE path=?")) {
                update.setString(1, tracks.getFirst().path().toString()); update.executeUpdate();
            }
        }
        store.updateDetails(List.of(tracks.get(1).path()), new LibraryStore.DetailChanges("Corrected", null, null, null, null, null));
        var result = home.recent(12); assertEquals(12, result.size()); assertEquals(tracks.get(1).path(), result.getFirst().path());
        assertEquals("Corrected", result.getFirst().title()); assertEquals(tracks.get(12).path(), result.getLast().path());
        assertThrows(IllegalArgumentException.class, () -> home.recent(0)); assertThrows(IllegalArgumentException.class, () -> home.recent(51));
        store.recordPlay(tracks.get(15).path()); assertEquals(tracks.get(15).path(), home.recent(1).getFirst().path());
    }
}
