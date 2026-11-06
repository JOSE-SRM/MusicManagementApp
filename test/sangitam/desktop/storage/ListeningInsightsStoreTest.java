package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.history.ListenEvent;
import sangitam.desktop.model.*;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ListeningInsightsStoreTest {
    @TempDir Path folder;

    @Test void measuredTimeKeepsRemovedRecordingsAndNeverAddsProviderPlaysAsMinutes() throws Exception {
        var library = new LibraryStore(folder.resolve("library.db"));
        library.initialize();
        Path sonata = folder.resolve("sonata.mp3"), removed = folder.resolve("removed.mp3");
        var metadata = TrackMetadata.empty().edit(Map.of(TrackMetadata.Field.PERIOD, "Romantic"), null);
        Track track = new Track(sonata, "Sonata", "Composer", "Recital", 180, "MP3", "Classical", metadata);
        library.saveScan(List.of(track), Map.of(sonata, new FileStamp(100, 100)), Set.of(), List.of(), Map.of());
        var features = new AudioFeatures(1, 100, 100, 90, -30, -15, 8, 500, 80, .6, "C", "minor", .7, 20, 30, "Calm", .6, List.of());
        library.saveTags(sonata, new TrackTags(20, 12, true, 100, 100, features));
        new ArtistStore(library).addListening(Map.of(sonata, 60d, removed, 20d));
        library.recordPlay(sonata);
        library.saveListens(List.of(new ListenEvent("Spotify", "example", "Sonata", "Composer", "Recital", 1, null, null)));

        var store = new ListeningInsightsStore(library);
        var snapshot = store.load();
        assertEquals(80, snapshot.seconds());
        assertEquals(2, snapshot.recordings());
        assertEquals(Map.of("Sangitam", 1L, "Spotify", 1L), snapshot.sourcePlays());
        assertEquals(new ListeningInsightsStore.Bucket("Classical", 60), snapshot.genres().getFirst());
        assertEquals(new ListeningInsightsStore.Bucket("Romantic", 60), snapshot.periods().getFirst());
        assertEquals(new ListeningInsightsStore.Bucket("Calm", 60), snapshot.moods().getFirst());
        assertEquals(new ListeningInsightsStore.Bucket("Removed recordings", 20), snapshot.genres().getLast());

        library.saveScan(List.of(track), Map.of(sonata, new FileStamp(101, 101)), Set.of(), List.of(), Map.of());
        assertEquals("Analysis pending", store.load().moods().getFirst().label());
    }
}
