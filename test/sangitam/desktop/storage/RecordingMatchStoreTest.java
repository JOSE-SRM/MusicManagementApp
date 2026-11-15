package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.*;
import sangitam.desktop.model.TrackMetadata.Field;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RecordingMatchStoreTest {
    @TempDir Path directory;

    @Test void confirmedFillSurvivesRestartAndKeepsFileMetadataAndManualChangesSeparate() throws Exception {
        Path database = directory.resolve("catalog.db");
        var library = new LibraryStore(database);
        library.initialize();
        Track track = track();
        index(library, track);
        var store = new RecordingMatchStore(library);
        var release = new RecordingCandidate.Release(UUID.randomUUID(), "Piano recordings", "2010");
        var candidate = new RecordingCandidate(UUID.randomUUID(), track.title(), "Étienne", "", 180000, List.of(release));
        store.save(store.load(track.path()), candidate, release, true, "reviewed text query");

        assertEquals("Unknown artist", library.loadFileCatalog().getFirst().track().artist());
        assertEquals("Étienne", library.loadAll().getFirst().artist());
        assertEquals(release.title(), library.loadAll().getFirst().album());
        var search = new LibrarySearchStore(library);
        assertEquals("Étienne", search.search(new LibrarySearchStore.Request("ÉTIENNE", LibrarySearchStore.Category.SONGS,
            true, LibrarySearchStore.Order.ALPHABETICAL, 0, 20)).hits().getFirst().artist());

        library = new LibraryStore(database);
        library.initialize();
        store = new RecordingMatchStore(library);
        var beforeEdit = store.load(track.path());
        assertEquals(candidate.id(), beforeEdit.saved().id());
        assertEquals("reviewed text query", beforeEdit.saved().query());
        library.updateDetails(List.of(track.path()), new LibraryStore.DetailChanges(null, "Manual artist", null, null, null, null));
        assertEquals("Manual artist", library.loadAll().getFirst().artist());
        var matchStore = store;
        assertThrows(java.sql.SQLException.class, () -> matchStore.save(beforeEdit, candidate, release, true, "stale"));
        store.remove(store.load(track.path()));
        assertEquals("Manual artist", library.loadAll().getFirst().artist());
        assertEquals("Unknown album", library.loadAll().getFirst().album());
    }

    @Test void weakEvidenceIsLinkOnlyAndFileReplacementDisablesAFormerFill() throws Exception {
        var library = new LibraryStore(directory.resolve("catalog.db"));
        library.initialize();
        Track track = track();
        index(library, track);
        var store = new RecordingMatchStore(library);
        var otherPerformance = new RecordingCandidate(UUID.randomUUID(), track.title(), "Other Pianist", "", 180000, List.of());
        store.save(store.load(track.path()), otherPerformance, null, true, "test");
        assertEquals("Unknown artist", library.loadAll().getFirst().artist());
        assertNull(store.load(track.path()).saved().artistFill());
        var correct = new RecordingCandidate(UUID.randomUUID(), track.title(), "Étienne", "", 180000, List.of());
        store.save(store.load(track.path()), correct, null, true, "test");
        var previous = store.load(track.path());
        library.restoreDetails(List.of(track), new DetailReset(Set.of(DetailReset.Basic.ARTIST), Set.of(), false));
        assertEquals("Unknown artist", library.loadAll().getFirst().artist());
        assertThrows(java.sql.SQLException.class, () -> store.save(previous, correct, null, true, "stale"));
        store.save(store.load(track.path()), correct, null, true, "test");
        var beforeReplacement = store.load(track.path());
        Files.write(track.path(), new byte[16]);
        assertThrows(java.io.IOException.class, () -> store.save(beforeReplacement, correct, null, true, "stale file"));
        index(library, track);
        assertEquals("Unknown artist", library.loadAll().getFirst().artist());
        assertNotEquals(store.load(track.path()).stamp(), store.load(track.path()).saved().stamp());
    }

    private Track track() throws Exception {
        Path file = Files.write(directory.resolve("fixture.wav"), new byte[8]);
        return new Track(file, "Piano Sonata No. 8: Allegro", "Unknown artist", "Unknown album", 180,
            "WAV", "Classical", TrackMetadata.empty().edit(Map.of(Field.SOLOIST, "Étienne"), null));
    }

    private static void index(LibraryStore library, Track track) throws Exception {
        var stamp = new FileStamp(Files.size(track.path()), Files.getLastModifiedTime(track.path()).toMillis());
        library.saveScan(List.of(track), Map.of(track.path(), stamp), Set.of(), List.of(), Map.of());
    }
}
