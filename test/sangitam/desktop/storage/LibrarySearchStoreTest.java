package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.*;
import sangitam.desktop.model.TrackMetadata.*;
import sangitam.desktop.storage.LibrarySearchStore.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LibrarySearchStoreTest {
    @TempDir Path directory;
    private LibraryStore catalog(int count) throws Exception {
        var store = new LibraryStore(directory.resolve("library.db")); store.initialize();
        var tracks = new ArrayList<Track>();
        for (int i = 0; i < count; i++) tracks.add(new Track(directory.resolve("%03d.mp3".formatted(i)), "Same title", "Artist " + i % 3, "Album " + i % 5, 60, "MP3"));
        store.saveAll(tracks); return store;
    }
    private Request request(String text, Category category, Order order, int offset) { return new Request(text, category, false, order, offset, 100); }
    @Test void boundedPagesHaveDeterministicTiesAndNoDuplicatePaths() throws Exception {
        var store = catalog(225); var search = new LibrarySearchStore(store);
        var paths = new HashSet<Path>();
        for (int offset : List.of(0, 100, 200)) {
            Page page = search.search(request("", Category.SONGS, Order.ALPHABETICAL, offset));
            assertEquals(225, page.total()); assertTrue(page.hits().size() <= 100);
            assertEquals(offset < 200, page.hasNext());
            for (Hit hit : page.hits()) assertTrue(paths.add(hit.track().path()));
        }
        assertEquals(225, paths.size());
        assertEquals(15, search.search(request("", Category.ALBUMS, Order.RECENT, 0)).total());
        assertEquals(3, search.search(request("", Category.ARTISTS, Order.MOST_PLAYED, 0)).total());
    }
    @Test void searchesExtendedMetadataUnicodeAndLiteralWildcardsAndHonorsAvailability() throws Exception {
        var store = catalog(4); var search = new LibrarySearchStore(store);
        Path path = directory.resolve("000.mp3");
        store.updateDetails(List.of(path), new LibraryStore.DetailChanges(null, null, null, null, null, null,
            Map.of(Field.COMPOSER, "Čajkovskij"), Set.of("100%_focus\\test")));
        assertEquals(1, search.search(request("ČAJKOVSKIJ", Category.SONGS, Order.RECENT, 0)).total());
        assertEquals(1, search.search(request("%_focus\\", Category.SONGS, Order.RECENT, 0)).total());
        assertEquals(0, search.search(request("%' OR 1=1 --", Category.SONGS, Order.RECENT, 0)).total());
        store.saveScan(List.of(), Map.of(), Set.of(path), List.of(), Map.of());
        assertEquals(3, search.search(new Request("", Category.SONGS, true, Order.RECENT, 0, 100)).total());
        store.recordPlay(path);
        assertEquals(path, search.search(request("", Category.SONGS, Order.MOST_PLAYED, 0)).hits().getFirst().track().path());
        store.updateDetails(List.of(path), new LibraryStore.DetailChanges("Renamed", null, null, null, null, null));
        assertEquals(1, search.search(request("Renamed", Category.SONGS, Order.RECENT, 0)).total());
        assertThrows(IllegalArgumentException.class, () -> new Request("", Category.SONGS, false, Order.RECENT, 0, 1000));
    }
}
