package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.storage.LibrarySearchStore.*;
import java.nio.file.Path;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in synthetic database benchmark. It does not load audio or open app windows. */
@EnabledIfSystemProperty(named = "sangitam.catalogBenchmark", matches = "true")
class CatalogBenchmarkTest {
    @TempDir Path directory;
    @Test void measuresBoundedSearchOnOneHundredThousandLegacyRows() throws Exception {
        var store = new LibraryStore(directory.resolve("benchmark.db")); store.initialize();
        long start = System.nanoTime();
        try (var connection = store.connect(); var insert = connection.prepareStatement("INSERT INTO tracks(path,title,artist,album,duration_seconds,format,genre,added_at,play_count) VALUES(?,?,?,?,180,'MP3','Classical',?,?)")) {
            connection.setAutoCommit(false);
            for (int i = 0; i < 100_000; i++) {
                insert.setString(1, directory.resolve(i + ".mp3").toString());
                insert.setString(2, "Sonata %06d".formatted(i));
                insert.setString(3, "Artist " + i % 100); insert.setString(4, "Album " + i % 1000);
                insert.setInt(5, i); insert.setInt(6, i % 500); insert.addBatch();
            }
            insert.executeBatch(); connection.commit();
        }
        double insertMs = (System.nanoTime() - start) / 1e6;
        var search = new LibrarySearchStore(store);
        for (String text : new String[] { "", "Sonata 099", "does-not-exist" }) {
            start = System.nanoTime();
            Page page = search.search(new Request(text, Category.SONGS, false, Order.RECENT, 0, 100));
            double queryMs = (System.nanoTime() - start) / 1e6;
            assertTrue(page.hits().size() <= 100);
            assertEquals(text.isEmpty() ? 100_000 : text.equals("Sonata 099") ? 1000 : 0, page.total());
            System.out.printf(Locale.ROOT, "CATALOG_BENCH rows=100000 insert_ms=%.1f query=%s query_ms=%.1f matches=%d loaded=%d%n", insertMs, text.isEmpty() ? "all" : text, queryMs, page.total(), page.hits().size());
        }
        var rule = new sangitam.desktop.model.SmartPlaylistRule("Sonata 099", "Classical", java.util.Set.of(), null, null,
            0, true, sangitam.desktop.model.SmartPlaylistRule.Order.RECENT, 75);
        start = System.nanoTime();
        var preview = new SmartPlaylistStore(store).preview(rule);
        double previewMs = (System.nanoTime() - start) / 1e6;
        assertEquals(1000, preview.matching()); assertEquals(75, preview.included()); assertEquals(20, preview.examples().size());
        new CollectionStore(store).createSmartPlaylist("Measured rule", "", 0, rule);
        start = System.nanoTime();
        var resolved = store.loadPlaylists().getFirst();
        double resolveMs = (System.nanoTime() - start) / 1e6;
        assertEquals(75, resolved.tracks().size());
        System.out.printf(Locale.ROOT, "SMART_BENCH rows=100000 preview_ms=%.1f resolve_ms=%.1f matches=%d included=%d examples=%d%n",
            previewMs, resolveMs, preview.matching(), resolved.tracks().size(), preview.examples().size());
    }
}
