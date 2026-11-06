package sangitam.desktop.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.mp3.MP3File;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.id3.ID3v1Tag;
import org.jaudiotagger.tag.id3.ID3v24Tag;
import sangitam.desktop.model.FileStamp;
import sangitam.desktop.model.Track;
import sangitam.desktop.session.SessionPlanner;
import sangitam.desktop.storage.LibraryStore;

import javax.swing.SwingUtilities;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
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

    @Test void rescanReusesMetadataAndRetainsUnavailableTracksAcrossReopen() throws Exception {
        Path recursive = Files.createDirectory(folder.resolve("recursive"));
        Path shallow = Files.createDirectory(folder.resolve("shallow"));
        Path gone = writeWav(recursive.resolve("Offline.wav"), 1);
        Path edited = writeWav(recursive.resolve("Edited.wav"), 1);
        writeWav(shallow.resolve("Keep.wav"), 1);
        Path database = folder.resolve("data/library.db");
        LibraryStore store = new LibraryStore(database);
        LibraryService service = new LibraryService(store);
        assertEquals(2, scan(service, List.of(recursive), true).added());
        assertEquals(1, scan(service, List.of(shallow), false).added());
        store.updateTags(edited, "Classical", 40, 25, Files.size(edited), Files.getLastModifiedTime(edited).toMillis());
        store.recordPlay(gone);
        store.savePlaylist("Saved", List.of(gone, edited));
        Path moved = Files.move(gone, folder.resolve("Moved.wav"));
        writeWav(edited, 2);
        Path discovered = writeWav(Files.createDirectory(recursive.resolve("nested")).resolve("New.wav"), 1);
        writeWav(Files.createDirectory(shallow.resolve("nested")).resolve("Ignored.wav"), 1);

        LibraryService reopened = new LibraryService(new LibraryStore(database));
        LibraryService.ImportSummary refreshed = scan(reopened, List.of(), true);
        assertEquals(1, refreshed.added());
        assertEquals(3, refreshed.indexed());
        assertEquals(1, refreshed.reused());
        assertEquals(1, refreshed.unavailable());
        assertEquals(4, reopened.snapshot().size());
        assertTrue(reopened.catalog().unavailable().contains(gone));
        assertTrue(reopened.snapshot().stream().anyMatch(track -> track.path().equals(discovered)));
        Track updated = reopened.snapshot().stream().filter(track -> track.path().equals(edited)).findFirst().orElseThrow();
        assertEquals(2, updated.durationSeconds(), 0.01);
        assertEquals("Classical", updated.genre());
        assertEquals(40, reopened.catalog().tagsFor(updated).energy());
        assertEquals(List.of(gone, edited), store.loadPlaylists().getFirst().tracks());
        assertEquals(1, reopened.catalog().playCounts().get(gone));

        Files.move(moved, gone);
        LibraryService.ImportSummary restored = scan(reopened, List.of(), true);
        assertEquals(0, restored.added());
        assertEquals(4, restored.reused());
        assertTrue(reopened.catalog().unavailable().isEmpty());
    }

    @Test void rescanChecksLegacyPathsWithoutAddingTheirNeighbors() throws Exception {
        Path known = writeWav(folder.resolve("Known.wav"), 1);
        writeWav(folder.resolve("Neighbor.wav"), 1);
        LibraryStore store = new LibraryStore(folder.resolve("data/library.db"));
        store.initialize();
        store.saveAll(List.of(new Track(known, "Old metadata", "Artist", "Album", 0, "WAV")));
        LibraryService reopened = new LibraryService(store);
        LibraryService.ImportSummary summary = scan(reopened, List.of(), true);
        assertEquals(0, summary.added());
        assertEquals(1, summary.indexed());
        assertEquals(0, summary.reused());
        assertEquals("Known", reopened.snapshot().getFirst().title());
        assertTrue(store.loadSources().isEmpty());
    }

    @Test void startupRepairsCachedPlaceholderLabelsWithoutCreatingAnotherTrack() throws Exception {
        Path composer = Files.createDirectories(folder.resolve("Classical/Bach, Johann Sebastian/Keyboard"));
        Path audio = composer.resolve("Partita No. 2.mp3");
        try (var source = getClass().getResourceAsStream("/audio/proof.mp3")) {
            assertNotNull(source);
            Files.copy(source, audio);
        }
        MP3File file = (MP3File) AudioFileIO.read(audio.toFile());
        ID3v24Tag primary = new ID3v24Tag();
        primary.setField(FieldKey.TITLE, "tmp");
        ID3v1Tag legacy = new ID3v1Tag();
        legacy.setField(FieldKey.TITLE, "tmp");
        file.setID3v2Tag(primary);
        file.setID3v1Tag(legacy);
        file.commit();

        Path identity = audio.toRealPath();
        LibraryStore store = new LibraryStore(folder.resolve("data/library.db"));
        store.initialize();
        Track indexed = new Track(identity, "tmp", "Unknown artist", "Album", 1, "MP3");
        FileStamp stamp = new FileStamp(Files.size(identity), Files.getLastModifiedTime(identity).toMillis());
        store.saveScan(List.of(indexed), Map.of(identity, stamp), Set.of(), List.of(), Map.of());
        store.savePlaylist("Saved", List.of(identity));
        store.recordPlay(identity);

        LibraryService service = new LibraryService(store);
        CompletableFuture<LibraryService.Catalog> repaired = new CompletableFuture<>();
        SwingUtilities.invokeAndWait(() -> {
            service.addCatalogListener(catalog -> {
                if (!catalog.tracks().isEmpty() && catalog.tracks().getFirst().title().equals("Partita No. 2")) {
                    repaired.complete(catalog);
                }
            });
            service.loadInitial(ignored -> { });
        });
        LibraryService.Catalog catalog = repaired.get(10, TimeUnit.SECONDS);
        assertEquals(1, catalog.tracks().size());
        assertEquals("Bach, Johann Sebastian", catalog.tracks().getFirst().artist());
        assertEquals(1, catalog.playsFor(catalog.tracks().getFirst()));
        assertEquals(List.of(identity), store.loadPlaylists().getFirst().tracks());
        assertEquals("Partita No. 2", store.loadAll().getFirst().title());
        assertTrue(store.loadSources().isEmpty());
    }

    @Test void removingAFolderKeepsItsFilesOutOfRescansUntilExplicitImport() throws Exception {
        Path music = Files.createDirectory(folder.resolve("music"));
        Path child = Files.createDirectory(music.resolve("album"));
        Path retained = writeWav(music.resolve("Retained.wav"), 1);
        Path removed = writeWav(child.resolve("Removed.wav"), 1);
        LibraryStore store = new LibraryStore(folder.resolve("data/library.db"));
        LibraryService service = new LibraryService(store);
        assertEquals(2, scan(service, List.of(music), true).added());
        var done = new CompletableFuture<String>();
        SwingUtilities.invokeAndWait(() -> service.removeFromLibrary(List.of(), child.toAbsolutePath(), done::complete));
        assertEquals("Removed from library", done.get(10, TimeUnit.SECONDS));
        assertTrue(Files.exists(removed));
        assertEquals(0, scan(service, List.of(), true).added());
        assertEquals(List.of(retained), service.snapshot().stream().map(Track::path).toList());

        assertEquals(1, scan(service, List.of(child), true).added());
        assertTrue(store.loadExclusions().isEmpty());
        assertEquals(2, scan(service, List.of(), true).reused());
    }

    @Test void startupUpgradesLegacyMetadataWithoutOverwritingATitleCorrection() throws Exception {
        Path file = folder.resolve("legacy.mp3");
        try (var source = getClass().getResourceAsStream("/audio/proof.mp3")) { Files.copy(source, file); }
        var store = new LibraryStore(folder.resolve("data/library.db")); store.initialize();
        store.saveAll(List.of(new Track(file, "Old title", "Artist", "Album", 1, "MP3")));
        store.updateDetails(List.of(file), new LibraryStore.DetailChanges("My title", null, null, null, null, null));
        var service = new LibraryService(store);
        var refreshed = new CompletableFuture<LibraryService.Catalog>();
        SwingUtilities.invokeAndWait(() -> {
            service.addCatalogListener(catalog -> {
                if (!catalog.tracks().isEmpty() && catalog.tracks().getFirst().metadata().extractionVersion() == 1) refreshed.complete(catalog);
            });
            service.loadInitial(message -> { if (message.contains("could not") || message.contains("failed")) refreshed.completeExceptionally(new AssertionError(message)); });
        });
        var saved = refreshed.get(10, TimeUnit.SECONDS).tracks().getFirst();
        assertEquals("My title", saved.title());
        assertTrue(saved.metadata().sampleRateHz() > 0);
        assertTrue(store.loadSources().isEmpty());
        assertEquals(1, store.loadAll().size());
    }

    private LibraryService.ImportSummary scan(LibraryService service, List<Path> sources, boolean recursive) throws Exception {
        CompletableFuture<LibraryService.ImportSummary> result = new CompletableFuture<>();
        var error = (java.util.function.Consumer<String>) message -> result.completeExceptionally(new AssertionError(message));
        SwingUtilities.invokeAndWait(() -> {
            if (sources.isEmpty()) service.rescan(ignored -> { }, result::complete, error);
            else service.importMedia(sources, recursive, ignored -> { }, result::complete, error);
        });
        return result.get(10, TimeUnit.SECONDS);
    }

    @Test void restoringFileDetailsRereadsUnchangedFilesAndLeavesUnreadableCorrectionsProtected() throws Exception {
        Path valid = writeWav(folder.resolve("File title.wav"), 1), missing = writeWav(folder.resolve("Missing.wav"), 1);
        Path corrupt = writeWav(folder.resolve("Corrupt.wav"), 1);
        var store = new LibraryStore(folder.resolve("data/library.db")); var service = new LibraryService(store);
        scan(service, List.of(valid, missing, corrupt), false);
        store.updateDetails(List.of(valid, missing, corrupt), new LibraryStore.DetailChanges("Correction", "Keep artist", null, null, null, null));
        Files.delete(missing); Files.writeString(corrupt, "not an audio file");
        var done = new CompletableFuture<String>();
        var reset = new sangitam.desktop.model.DetailReset(Set.of(sangitam.desktop.model.DetailReset.Basic.TITLE), Set.of(), false);
        SwingUtilities.invokeAndWait(() -> service.restoreDetails(List.of(valid, missing, corrupt, folder.resolve("not-indexed.wav")), reset, message -> {
            assertTrue(SwingUtilities.isEventDispatchThread()); done.complete(message);
        }));
        String report = done.get(10, TimeUnit.SECONDS);
        assertTrue(report.startsWith("Restored file details for 1 tracks"), report); assertTrue(report.contains("3 unchanged"));
        Track restored = service.snapshot().stream().filter(track -> track.path().equals(valid)).findFirst().orElseThrow();
        assertEquals("File title", restored.title()); assertEquals("Keep artist", restored.artist());
        assertTrue(service.snapshot().stream().filter(track -> !track.path().equals(valid)).allMatch(track -> track.title().equals("Correction")));
        scan(service, List.of(), true);
        assertEquals("File title", store.loadAll().stream().filter(track -> track.path().equals(valid)).findFirst().orElseThrow().title());
        assertEquals(3, store.loadAll().size()); assertEquals(3, store.loadSources().size());
    }

    private Path writeWav(Path path, int seconds) throws Exception {
        AudioFormat format = new AudioFormat(44_100, 16, 1, true, false);
        int frames = seconds * 44_100;
        try (var stream = new AudioInputStream(new ByteArrayInputStream(new byte[frames * 2]), format, frames)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
        return path.toRealPath();
    }
}
