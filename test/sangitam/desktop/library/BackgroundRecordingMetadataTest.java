package sangitam.desktop.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.*;
import sangitam.desktop.model.TrackMetadata.Field;
import sangitam.desktop.storage.LibrarySearchStore;
import sangitam.desktop.storage.LibraryStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundRecordingMetadataTest {
    @TempDir Path directory;

    @Test void resolvesWorkAndMovementFromLocalReferencesAndKeepsUserValues() throws Exception {
        var library = new LibraryStore(directory.resolve("library.db"));
        library.initialize();
        Path folder = Files.createDirectory(directory.resolve("Beethoven"));
        Path file = Files.write(folder.resolve("movement.mp3"), new byte[8]);
        var track = new Track(file, "Piano Sonata No. 8 'Pathétique' [Phase One]", "Unknown artist", "Unknown album",
            180, "MP3", "Classical");
        var stamp = new FileStamp(Files.size(file), Files.getLastModifiedTime(file).toMillis());
        library.saveScan(List.of(track), Map.of(file, stamp), Set.of(), List.of(), Map.of());
        var resolver = new BackgroundRecordingMetadata(library);
        assertTrue(resolver.update(file, false, false, null).changed());
        var updated = library.loadAll().getFirst().metadata();
        assertEquals("Op. 13", updated.text(Field.OPUS));
        assertEquals("C minor", updated.text(Field.KEY));
        assertEquals("1797–1799", updated.text(Field.COMPOSITION_DATE));
        assertTrue(updated.text(Field.RECORDING_DATE).isEmpty());
        assertFalse(resolver.update(file, false, false, null).changed());
        assertEquals(1, new LibrarySearchStore(library).search(new LibrarySearchStore.Request(
            "1797–1799", LibrarySearchStore.Category.SONGS, true, LibrarySearchStore.Order.ALPHABETICAL, 0, 10)).total());
        library.updateDetails(List.of(file), new LibraryStore.DetailChanges(null, null, null, null, null, null,
            Map.of(Field.KEY, "User key"), null));
        resolver.update(file, false, false, null);
        assertEquals("User key", library.loadAll().getFirst().metadata().text(Field.KEY));
        assertEquals("", library.loadFileCatalog().getFirst().track().metadata().text(Field.OPUS));
    }
}
