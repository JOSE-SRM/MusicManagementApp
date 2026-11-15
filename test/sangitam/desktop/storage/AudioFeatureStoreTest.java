package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AudioFeatureStoreTest {
    @TempDir Path folder;
    @Test void richerFeaturesPersistWithoutReplacingManualEnergyAndStaleWritesAreRejected() throws Exception {
        var store=new LibraryStore(folder.resolve("library.db"));store.initialize();Path path=folder.resolve("song.mp3");
        store.saveScan(List.of(new Track(path,"Song","Artist","Album",120,"MP3")),Map.of(path,new FileStamp(10,20)),Set.of(),List.of(),Map.of());
        store.updateTags(path,"Classical",23,17,10,20);
        var f=new AudioFeatures(1,10,20,90,-20,-3,18,1200,120,.8,"C","major",.6,60,80,"Happy",.6,List.of(20,60));
        store.saveTags(path,new TrackTags(80,80,true,10,20,f));var saved=store.loadCatalog().getFirst();
        assertEquals(23,saved.tags().energy());assertEquals(17,saved.tags().activity());assertEquals(f,saved.tags().features());
        var stale=new AudioFeatures(1,1,1,10,-10,0,1,200,0,0,"","",0,10,10,"Sad",.3,List.of());store.saveTags(path,new TrackTags(1,1,true,1,1,stale));
        assertEquals(f,store.loadCatalog().getFirst().tags().features());
    }

    @Test void failuresSurviveReopenAndSuccessfulRetryPreservesManualRatings() throws Exception {
        Path database = folder.resolve("library.db");
        Path path = folder.resolve("song.flac");
        var store = new LibraryStore(database);
        store.initialize();
        store.saveScan(List.of(new Track(path, "Song", "Artist", "Album", 120, "FLAC")),
            Map.of(path, new FileStamp(10, 20)), Set.of(), List.of(), Map.of());
        store.updateTags(path, "Classical", 23, 17, 10, 20);
        store.saveTags(path, TrackTags.failed(10, 20, TrackTags.Failure.DAMAGED_FLAC));

        var reopened = new LibraryStore(database);
        reopened.initialize();
        TrackTags failed = reopened.loadCatalog().getFirst().tags();
        assertEquals(TrackTags.Failure.DAMAGED_FLAC, failed.failure());
        assertTrue(failed.current(10, 20));
        assertFalse(failed.current(11, 20));
        assertEquals(23, failed.energy());

        var features = new AudioFeatures(AudioFeatures.VERSION, 10, 20, 90, -20, -3, 18, 1200,
            120, .8, "C", "major", .6, 60, 80, "Happy", .6, List.of(20, 60));
        reopened.saveTags(path, new TrackTags(80, 80, true, 10, 20, features));
        reopened.saveTags(path, TrackTags.failed(9, 19, TrackTags.Failure.DAMAGED_FLAC));
        TrackTags retried = reopened.loadCatalog().getFirst().tags();
        assertNull(retried.failure());
        assertEquals(features, retried.features());
        assertEquals(23, retried.energy());
        assertEquals(17, retried.activity());
    }
}
