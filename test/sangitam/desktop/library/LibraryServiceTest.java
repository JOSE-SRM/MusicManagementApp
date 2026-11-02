package sangitam.desktop.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.session.SessionPlanner;
import sangitam.desktop.storage.LibraryStore;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the full scan → SQLite → Swing update path, including a fresh service instance. */
class LibraryServiceTest {
    @TempDir Path folder;

    @Test void importIsSavedAndPublishedOnSwingThread() throws Exception {
        Path audio = folder.resolve("proof.mp3");
        try (var source = getClass().getResourceAsStream("/audio/proof.mp3")) {
            assertNotNull(source);
            Files.copy(source, audio);
        }
        Path database = folder.resolve("data/library.db");
        LibraryService first = new LibraryService(new LibraryStore(database));
        CountDownLatch imported = new CountDownLatch(1);
        AtomicReference<LibraryService.ImportSummary> summary = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<Boolean> onSwingThread = new AtomicReference<>(false);
        first.addListener(tracks -> {
            if (!tracks.isEmpty()) onSwingThread.set(SwingUtilities.isEventDispatchThread());
        });
        first.importMedia(List.of(audio), true, ignored -> { }, result -> {
            summary.set(result);
            imported.countDown();
        }, message -> {
            error.set(message);
            imported.countDown();
        });
        assertTrue(imported.await(10, TimeUnit.SECONDS));
        assertNull(error.get());
        assertEquals(1, summary.get().added());
        assertEquals(1, first.snapshot().size());
        assertTrue(first.catalog().tagsFor(first.snapshot().get(0)).analyzed());
        assertTrue(onSwingThread.get());

        SessionPlanner.Plan session = new SessionPlanner().build(new SessionPlanner.Request(
            SessionPlanner.Intent.UNWIND, 0, 1, false), first.catalog().tracks(),
            first.catalog().tags(), first.catalog().playCounts());
        assertEquals(audio.toRealPath(), session.choices().get(0).track().path());

        CountDownLatch recorded = new CountDownLatch(1);
        first.addCatalogListener(catalog -> {
            if (catalog.playsFor(catalog.tracks().get(0)) == 1) recorded.countDown();
        });
        first.recordPlay(audio.toRealPath(), error::set);
        assertTrue(recorded.await(10, TimeUnit.SECONDS));
        assertNull(error.get());

        LibraryService reopened = new LibraryService(new LibraryStore(database));
        CountDownLatch loaded = new CountDownLatch(1);
        reopened.addListener(tracks -> { if (!tracks.isEmpty()) loaded.countDown(); });
        reopened.loadInitial(ignored -> { });
        assertTrue(loaded.await(10, TimeUnit.SECONDS));
        assertEquals(first.snapshot(), reopened.snapshot());
        assertTrue(reopened.catalog().tagsFor(reopened.snapshot().get(0)).analyzed());
        assertEquals(1, reopened.catalog().playsFor(reopened.snapshot().get(0)));
    }
}
