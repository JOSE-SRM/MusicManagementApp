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
import sangitam.desktop.model.TrackTags;
import sangitam.desktop.model.TrackMetadata;
import sangitam.desktop.session.SessionPlanner;
import sangitam.desktop.storage.LibraryStore;
import sangitam.desktop.storage.LibrarySearchStore;

import javax.swing.SwingUtilities;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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

    @Test void managedActivationAndRefilingPreserveCatalogRelationshipsAndAvoidSourceDuplicates() throws Exception {
        Path sourceFolder = Files.createDirectory(folder.resolve("Originals"));
        Path original = writeWav(sourceFolder.resolve("Mozart - Piano Sonata K. 545 - 1. Allegro.wav"), 1);
        Path root = folder.resolve("Managed");
        var store = new LibraryStore(folder.resolve("managed.db"));
        var service = new LibraryService(store);
        assertEquals(1, scan(service, List.of(sourceFolder), true).added());
        store.savePlaylist("Sonatas", List.of(original));
        new sangitam.desktop.storage.CollectionStore(store).setLiked(original, true);
        store.savePlaybackMemory(new sangitam.desktop.model.PlaybackMemory(original, .3));
        var settings = new ManagedLibraryCopy.Settings(root, ManagedLibraryCopy.Policy.COPY,
            new ManagedLibraryCopy.Options(true, true, true, false, true, false, ManagedLibraryCopy.Layout.FORMS), false);
        var activated = new CompletableFuture<LibraryService.ImportSummary>();
        service.activateManaged(settings, List.of(), true, ignored -> { }, activated::complete,
            error -> activated.completeExceptionally(new IllegalStateException(error)));
        assertTrue(activated.get(20, TimeUnit.SECONDS).errors().isEmpty());
        Track first = service.snapshot().getFirst();
        assertTrue(first.path().startsWith(root));
        assertTrue(first.path().toString().contains("Wolfgang Amadeus Mozart"), first.path().toString());
        assertTrue(first.path().toString().contains("Sonata"), first.path().toString());
        assertEquals(1, store.loadAll().size());
        assertEquals(List.of(first.path()), store.loadPlaylists().getFirst().tracks());
        assertEquals(first.path(), store.loadPlaybackMemory().path());
        assertTrue(new sangitam.desktop.storage.CollectionStore(store).load().likes().contains(first.path()));
        assertEquals(-1, Files.mismatch(original, first.path()));
        Path second = writeWav(sourceFolder.resolve("Haydn - Symphony No. 94.wav"), 1);
        assertEquals(1, scan(service, List.of(), false).added());
        assertEquals(2, service.snapshot().size());
        assertNotNull(service.catalog().currentTrack(second));
        assertEquals(0, scan(service, List.of(), false).added());
        assertEquals(2, store.loadAll().size());

        var edit = new CompletableFuture<String>();
        service.editDetails(List.of(first.path()), new LibraryStore.DetailChanges(null, null, null, null, null, null,
            Map.of(TrackMetadata.Field.WORK, "Revised work folder"), null), edit::complete);
        assertEquals("Track details saved", edit.get(20, TimeUnit.SECONDS));
        Path relocated = service.catalog().currentTrack(original).path();
        assertTrue(relocated.toString().contains("Revised work folder"), relocated.toString());
        assertFalse(Files.exists(first.path()));
        assertEquals(-1, Files.mismatch(original, relocated));
        assertEquals(List.of(relocated), store.loadPlaylists().getFirst().tracks());
        assertEquals(relocated, store.loadPlaybackMemory().path());
        assertTrue(new sangitam.desktop.storage.CollectionStore(store).load().likes().contains(relocated));
        var reopened = new LibraryService(new LibraryStore(folder.resolve("managed.db")));
        assertEquals(0, scan(reopened, List.of(), false).added());
        assertEquals(2, reopened.snapshot().size());
        assertEquals(relocated, reopened.catalog().currentTrack(original).path());
        assertEquals(relocated, reopened.catalog().locations().paths().get(original));
    }

    @Test void writeTagsChangesTheManagedFileAndLeavesTheOriginalBytesIntact() throws Exception {
        Path original = folder.resolve("original.flac");
        try (var fixture = getClass().getResourceAsStream("/audio/tone-16-mono.flac")) {
            Files.copy(fixture, original);
        }
        byte[] untouched = Files.readAllBytes(original);
        var store = new LibraryStore(folder.resolve("tagging.db"));
        var service = new LibraryService(store);
        var settings = new ManagedLibraryCopy.Settings(folder.resolve("Managed"), ManagedLibraryCopy.Policy.COPY,
            new ManagedLibraryCopy.Options(false, false, false, false, false, false, ManagedLibraryCopy.Layout.SIMPLE), true);
        var activated = new CompletableFuture<LibraryService.ImportSummary>();
        service.activateManaged(settings, List.of(original), false, ignored -> { }, activated::complete,
            error -> activated.completeExceptionally(new IllegalStateException(error)));
        assertTrue(activated.get(20, TimeUnit.SECONDS).errors().isEmpty());
        var edited = new CompletableFuture<String>();
        service.editDetails(List.of(service.snapshot().getFirst().path()),
            new LibraryStore.DetailChanges("Étude in B♭", null, "Managed album", null, null, null), edited::complete);
        assertEquals("Track details saved", edited.get(20, TimeUnit.SECONDS));
        Path target = service.snapshot().getFirst().path();
        assertEquals("Étude in B♭.flac", target.getFileName().toString());
        var tags = AudioFileIO.read(target.toFile()).getTag();
        assertEquals("Étude in B♭", tags.getFirst(FieldKey.TITLE));
        assertEquals("Managed album", tags.getFirst(FieldKey.ALBUM));
        assertArrayEquals(untouched, Files.readAllBytes(original));
    }

    @Test void managedTagWriterCanReplaceMp3TitleWithoutChangingEncodedAudio() throws Exception {
        Path path = folder.resolve("managed.mp3");
        try (var fixture = getClass().getResourceAsStream("/audio/proof.mp3")) {
            Files.copy(fixture, path);
        }
        var track = new Track(path, "Étude in F♯", "Unknown artist", "Unknown album", 1, "MP3");
        assertTrue(ManagedAudioTags.write(track));
        assertEquals("Étude in F♯", AudioFileIO.read(path.toFile()).getTag().getFirst(FieldKey.TITLE));
        assertFalse(ManagedAudioTags.write(track));
    }

    @Test void managedRepairRestoresMissingCopiesAndLegacyPlaylistPathsWithoutChangingTheirOrder() throws Exception {
        Path originals = Files.createDirectory(folder.resolve("Originals"));
        Path first = writeWav(originals.resolve("Sonata in F Minor.wav"), 1);
        Path second = writeWav(originals.resolve("Rondo in E Major.wav"), 1);
        Path removed = writeWav(originals.resolve("Adagio.wav"), 1);
        Path database = folder.resolve("repair.db");
        Path root = folder.resolve("Managed");
        var store = new LibraryStore(database);
        var service = new LibraryService(store);
        var settings = new ManagedLibraryCopy.Settings(root, ManagedLibraryCopy.Policy.COPY,
            new ManagedLibraryCopy.Options(false, false, false, false, false, false, ManagedLibraryCopy.Layout.SIMPLE), false);
        var activated = new CompletableFuture<LibraryService.ImportSummary>();
        service.activateManaged(settings, List.of(originals), false, ignored -> { }, activated::complete,
            error -> activated.completeExceptionally(new IllegalStateException(error)));
        assertEquals(3, activated.get(20, TimeUnit.SECONDS).indexed());
        Path managedFirst = service.catalog().currentTrack(first).path();
        Path managedSecond = service.catalog().currentTrack(second).path();
        Path managedRemoved = service.catalog().currentTrack(removed).path();
        var tags = service.catalog().tagsFor(service.catalog().currentTrack(first));
        store.updateDetails(List.of(managedFirst), new LibraryStore.DetailChanges("My sonata", null, null, null, 20, 40,
            Map.of(TrackMetadata.Field.COMPOSER, "My saved composer"), Set.of("My tag")));
        store.recordPlay(managedFirst);
        store.removeFromLibrary(List.of(managedRemoved), null);
        Files.delete(managedRemoved);
        Files.delete(managedFirst);
        Path legacy = folder.resolve("Former playlist folder");
        Path oldFirst = legacy.resolve(first.getFileName());
        Path oldSecond = legacy.resolve(second.getFileName());
        Path unknown = legacy.resolve("Unmatched.wav");
        store.savePlaylist("Old list", List.of(oldFirst, oldSecond, oldFirst, unknown));
        new sangitam.desktop.storage.CollectionStore(store).setLiked(oldFirst, true);
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement("INSERT INTO library_exclusions(path) VALUES(?)")) {
            statement.setString(1, root.toString());
            statement.executeUpdate();
        }

        var reopened = new LibraryService(new LibraryStore(database));
        var completion = new CompletableFuture<LibraryService.ImportSummary>();
        reopened.repairManaged(ignored -> { }, completion::complete,
            error -> completion.completeExceptionally(new IllegalStateException(error)));
        assertEquals(0, completion.get(20, TimeUnit.SECONDS).unavailable());
        assertEquals(List.of(managedFirst, managedSecond, managedFirst, unknown), store.loadPlaylists().getFirst().tracks());
        assertTrue(new sangitam.desktop.storage.CollectionStore(store).load().likes().contains(managedFirst));
        assertEquals(-1, Files.mismatch(first, managedFirst));
        assertFalse(Files.exists(managedRemoved));
        var recovered = reopened.catalog().currentTrack(managedFirst);
        assertEquals("My sonata", recovered.title());
        assertEquals("My saved composer", recovered.metadata().text(TrackMetadata.Field.COMPOSER));
        assertEquals(Set.of("My tag"), recovered.metadata().tags());
        assertEquals(20, reopened.catalog().tagsFor(recovered).energy());
        assertEquals(tags.features(), reopened.catalog().tagsFor(recovered).features());
        assertEquals(1, reopened.catalog().playsFor(recovered));
        assertEquals(recovered, reopened.catalog().currentTrack(oldFirst));
        assertEquals(0, scan(reopened, List.of(), false).unavailable());
    }

    @Test void managedRepairMergesWindowsCaseVariantsAndCarriesBothRecords() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
        Path source = writeWav(folder.resolve("music.wav"), 1);
        var store = new LibraryStore(folder.resolve("cases.db"));
        var service = new LibraryService(store);
        var settings = new ManagedLibraryCopy.Settings(folder.resolve("Managed"), ManagedLibraryCopy.Policy.COPY,
            new ManagedLibraryCopy.Options(false, false, false, false, false, false, ManagedLibraryCopy.Layout.SIMPLE), false);
        var activated = new CompletableFuture<LibraryService.ImportSummary>();
        service.activateManaged(settings, List.of(source), false, ignored -> { }, activated::complete,
            error -> activated.completeExceptionally(new IllegalStateException(error)));
        assertEquals(1, activated.get(20, TimeUnit.SECONDS).indexed());
        Track track = service.snapshot().getFirst();
        Path target = track.path();
        Path duplicate = target.resolveSibling("MUSIC.WAV");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(duplicate));
        store.saveAll(List.of(new Track(duplicate, track.title(), track.artist(), track.album(),
            track.durationSeconds(), track.format(), track.genre())));
        store.updateDetails(List.of(duplicate), new LibraryStore.DetailChanges("Saved title", null, null, null, 26, 34,
            Map.of(TrackMetadata.Field.COMPOSER, "Saved composer"), Set.of("Saved tag")));
        store.savePlaylist("Both paths", List.of(duplicate, target, duplicate));
        assertEquals(2, store.loadAll().size());
        var repaired = new CompletableFuture<LibraryService.ImportSummary>();
        service.repairManaged(ignored -> { }, repaired::complete,
            error -> repaired.completeExceptionally(new IllegalStateException(error)));
        assertEquals(0, repaired.get(20, TimeUnit.SECONDS).unavailable());
        assertEquals(1, store.loadAll().size());
        var saved = store.loadAll().getFirst();
        assertEquals(target.toString(), saved.path().toString());
        assertEquals("Saved title", saved.title());
        assertEquals("Saved composer", saved.metadata().text(TrackMetadata.Field.COMPOSER));
        assertEquals(Set.of("Saved tag"), saved.metadata().tags());
        assertEquals(List.of(target.toString(), target.toString(), target.toString()), store.loadPlaylists().getFirst()
            .tracks().stream().map(Path::toString).toList());
    }

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

    @Test void rescanMeasuresReplacedFlacAndKeepsRatings() throws Exception {
        Path audio = folder.resolve("test.flac");
        byte[] original;
        try (var source = getClass().getResourceAsStream("/audio/tone-16-mono.flac")) {
            assertNotNull(source);
            original = source.readAllBytes();
        }
        Files.write(audio, Arrays.copyOf(original, original.length - 100));
        LibraryStore store = new LibraryStore(folder.resolve("data/library.db"));
        LibraryService service = new LibraryService(store);
        assertEquals(1, scan(service, List.of(audio), false).unanalyzed());
        var failed = service.catalog().tagsFor(service.snapshot().getFirst());
        assertEquals(TrackTags.Failure.DAMAGED_FLAC, failed.failure());
        store.updateTags(audio.toRealPath(), "Classical", 31, 26, failed.fileSize(), failed.modifiedMillis());
        Files.write(audio, original);

        assertEquals(0, scan(service, List.of(), false).unanalyzed());
        var measured = service.catalog().tagsFor(service.snapshot().getFirst());
        assertNull(measured.failure());
        assertEquals(0.5, measured.features().sampledSeconds(), 0.01);
        assertEquals(31, measured.energy());
        assertEquals(26, measured.activity());
    }

    @Test void cancelledAnalysisReportsCancellationAndCanBeRestarted() throws Exception {
        Path audio = folder.resolve("test.flac");
        try (var source = getClass().getResourceAsStream("/audio/tone-16-mono.flac")) {
            assertNotNull(source);
            Files.copy(source, audio);
        }
        var service = new LibraryService(new LibraryStore(folder.resolve("library.db")));
        scan(service, List.of(audio), false);
        var original = service.catalog().tagsFor(service.snapshot().getFirst());
        var cancelled = new CompletableFuture<String>();
        SwingUtilities.invokeAndWait(() -> {
            service.analyzeNow(cancelled::complete);
            service.cancelAnalysis();
        });
        assertTrue(cancelled.get(10, TimeUnit.SECONDS).contains("cancelled"));
        var restarted = new CompletableFuture<String>();
        SwingUtilities.invokeAndWait(() -> service.analyzeNow(restarted::complete));
        assertEquals("All available tracks have current analysis", restarted.get(10, TimeUnit.SECONDS));
        assertEquals(original, service.catalog().tagsFor(service.snapshot().getFirst()));
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
                if (!catalog.tracks().isEmpty() && catalog.tracks().getFirst().metadata().extractionVersion()
                    == TrackMetadata.CURRENT_VERSION) refreshed.complete(catalog);
            });
            service.loadInitial(message -> { if (message.contains("could not") || message.contains("failed")) refreshed.completeExceptionally(new AssertionError(message)); });
        });
        var saved = refreshed.get(10, TimeUnit.SECONDS).tracks().getFirst();
        assertEquals("My title", saved.title());
        assertTrue(saved.metadata().sampleRateHz() > 0);
        assertTrue(store.loadSources().isEmpty());
        assertEquals(1, store.loadAll().size());
    }

    @Test void singleTrackAnalysisReviewsAndSavesOnlyTheSelectedTrack() throws Exception {
        Path one = writeWav(folder.resolve("one.wav"), 35);
        Path two = writeWav(folder.resolve("two.wav"), 1);
        var store = new LibraryStore(folder.resolve("data/library.db"));
        var service = new LibraryService(store);
        scan(service, List.of(one, two), false);
        store.updateDetails(List.of(one), new LibraryStore.DetailChanges(null, null, null, null, 23, 17,
            Map.of(), Set.of("Practice")));
        Track track = service.catalog().byPath().get(one);
        var discarded = new CompletableFuture<String>();
        SwingUtilities.invokeAndWait(() -> service.analyzeTrack(track, AudioAnalyzer.Sampling.OPENING_30, false, "E-flat major",
            tags -> null, message -> { if (!message.startsWith("Analysing")) discarded.complete(message); }));
        assertEquals("Track analysis discarded", discarded.get(10, TimeUnit.SECONDS));
        assertEquals(35, service.catalog().tagsFor(track).features().sampledSeconds(), .01);
        var saved = new CompletableFuture<String>();
        SwingUtilities.invokeAndWait(() -> service.analyzeTrack(track, AudioAnalyzer.Sampling.OPENING_30, false, "C minor",
            tags -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                assertTrue(tags.features().moodTags().isEmpty());
                return List.of("Sorrowful", "Melodious");
            }, message -> { if (!message.startsWith("Analysing")) saved.complete(message); }));
        assertTrue(saved.get(10, TimeUnit.SECONDS).startsWith("Analysis saved"));
        var entry = store.loadCatalog().stream().filter(value -> value.track().path().equals(one)).findFirst().orElseThrow();
        assertEquals("C minor", entry.track().metadata().text(TrackMetadata.Field.KEY));
        assertEquals(Set.of("Practice"), entry.track().metadata().tags());
        assertEquals(23, entry.tags().energy());
        assertEquals(30, entry.tags().features().sampledSeconds(), .01);
        assertEquals(List.of("Melodious", "Sorrowful"), entry.tags().features().moodTags());
        assertTrue(service.catalog().tagsFor(service.catalog().byPath().get(two)).features().moodTags().isEmpty());
        var search = new LibrarySearchStore(store);
        var matches = search.search(new LibrarySearchStore.Request("sorrowful", LibrarySearchStore.Category.SONGS,
            true, LibrarySearchStore.Order.ALPHABETICAL, 0, 20));
        assertEquals(List.of(one), matches.hits().stream().map(hit -> hit.track().path()).toList());
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
