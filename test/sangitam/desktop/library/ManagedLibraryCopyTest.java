package sangitam.desktop.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackMetadata;
import sangitam.desktop.model.TrackMetadata.Field;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ManagedLibraryCopyTest {
    @TempDir Path directory;
    private final ManagedLibraryCopy copy = new ManagedLibraryCopy();
    private final ManagedLibraryCopy.Options flat = new ManagedLibraryCopy.Options(false, false, false, false,
        false, false, ManagedLibraryCopy.Layout.SIMPLE);

    @Test void previewCreatesNothingAndCopyVerifiesWithoutReplacingConflicts() throws Exception {
        Path first = tone(Files.createDirectory(directory.resolve("one")).resolve("same.wav"), 1);
        Path second = tone(Files.createDirectory(directory.resolve("two")).resolve("same.wav"), 2);
        Path managed = directory.resolve("Managed");
        var plan = copy.preview(managed, List.of(first, second), false, flat, Map.of(), () -> false);
        assertFalse(Files.exists(managed));
        assertEquals(2, plan.entries().size());
        assertTrue(plan.entries().getLast().renamed());
        assertEquals(Files.size(first) + Files.size(second), plan.bytes());
        var result = copy.apply(plan, () -> false, ignored -> { });
        assertTrue(result.failures().isEmpty(), result.failures().toString());
        assertEquals(2, result.indexedPaths().size());
        assertEquals(-1, Files.mismatch(first, result.indexedPaths().getFirst()));
        assertEquals(-1, Files.mismatch(second, result.indexedPaths().getLast()));
        var repeated = copy.preview(managed, List.of(first, second), false, flat, Map.of(), () -> false);
        assertEquals(0, repeated.bytes());
        assertTrue(repeated.entries().stream().allMatch(ManagedLibraryCopy.Entry::reuse));
    }

    @Test void cancellationAndLateConflictsPreserveTheOriginalsAndCompletedCopies() throws Exception {
        Path first = tone(directory.resolve("first.wav"), 1);
        Path second = tone(directory.resolve("second.wav"), 2);
        Path managed = Files.createDirectory(directory.resolve("Managed"));
        var plan = copy.preview(managed, List.of(first, second), false, flat, Map.of(), () -> false);
        var cancel = new AtomicBoolean();
        var stopped = copy.apply(plan, cancel::get, entry -> { if (entry.source().equals(second)) cancel.set(true); });
        assertTrue(stopped.cancelled());
        assertEquals(List.of(managed.resolve("first.wav")), stopped.indexedPaths());
        assertFalse(Files.exists(managed.resolve("second.wav")));
        try (var files = Files.list(managed)) { assertEquals(1, files.count()); }
        var retry = copy.preview(managed, List.of(second), false, flat, Map.of(), () -> false);
        byte[] collision = {4, 8, 15, 16};
        Path target = Files.write(retry.entries().getFirst().target(), collision);
        var failed = copy.apply(retry, () -> false, ignored -> { });
        assertEquals(1, failed.failures().size());
        assertArrayEquals(collision, Files.readAllBytes(target));
        assertTrue(Files.exists(second));
        assertThrows(java.io.IOException.class, () -> copy.preview(managed, List.of(directory), true, flat, Map.of(), () -> false));
    }

    @Test void hardLinkReusesOnlyTheSameFileAndDoesNotOverwriteAnIndependentCopy() throws Exception {
        Path source = tone(directory.resolve("recording.wav"), 1);
        Path managed = Files.createDirectory(directory.resolve("Managed"));
        Path independent = Files.copy(source, managed.resolve("recording.wav"));
        var link = ManagedLibraryCopy.Policy.HARD_LINK;
        var plan = copy.preview(managed, List.of(source), false, flat, link, Map.of(), () -> false);
        assertEquals(0, plan.bytes());
        var entry = plan.entries().getFirst();
        assertTrue(entry.renamed());
        assertFalse(entry.reuse());
        assertFalse(Files.exists(entry.target()));
        var result = copy.apply(plan, () -> false, ignored -> { });
        assertEquals(List.of(entry.target()), result.indexedPaths(), result.failures().toString());
        assertTrue(Files.isSameFile(source, entry.target()));
        assertFalse(Files.isSameFile(source, independent));
        var retry = copy.preview(managed, List.of(source), false, flat, link, Map.of(), () -> false);
        assertEquals(entry.target(), retry.entries().getFirst().target());
        assertTrue(retry.entries().getFirst().reuse());
        Files.delete(independent);
        Files.move(entry.target(), independent);
        var copyPlan = copy.preview(managed, List.of(source), false, flat, Map.of(), () -> false);
        assertFalse(copyPlan.entries().getFirst().reuse());
        assertEquals(Files.size(source), copyPlan.bytes());
        Files.delete(source);
        assertFalse(Files.exists(source));
        assertTrue(Files.size(independent) > 0);
    }

    @Test void hardLinkAllowsLongSourcePathsOnTheSameVolume() throws Exception {
        Path parent = Files.createDirectory(directory.resolve("source"));
        Path source = tone(parent.resolve("long-title-".repeat(14) + ".wav"), 1);
        var plan = copy.preview(directory.resolve("Managed"), List.of(source), false, flat,
            ManagedLibraryCopy.Policy.HARD_LINK, Map.of(), () -> false);
        assertEquals(1, plan.entries().size(), plan.scanNotes().toString());
        var result = copy.apply(plan, () -> false, ignored -> { });
        assertEquals(1, result.indexedPaths().size(), result.failures().toString());
        assertTrue(Files.isSameFile(source, result.indexedPaths().getFirst()));
    }

    @Test void classicalLayoutUsesWorkAndMovementAndSanitizesWindowsNames() {
        var metadata = TrackMetadata.empty().edit(Map.of(Field.COMPOSER, "Ludwig van Beethoven",
            Field.PERIOD, "Classical", Field.WORK, "Sonata: Pathétique", Field.OPUS, "Op. 13",
            Field.FORM, "Sonata", Field.INSTRUMENTATION, "Piano", Field.MOVEMENT, "II. Adagio cantabile"), null);
        var track = new Track(directory.resolve("CON.wav"), "Adagio", "Pianist", "Concert", 180, "WAV", "Classical", metadata);
        var forms = new ManagedLibraryCopy.Options(true, true, true, true, true, false, ManagedLibraryCopy.Layout.FORMS);
        var opus = new ManagedLibraryCopy.Options(true, false, true, false, false, false, ManagedLibraryCopy.Layout.OPUS);
        assertEquals(Path.of("Classical", "Classical", "Ludwig van Beethoven", "Musical Forms", "Sonata", "Piano",
            "Sonata_ Pathétique"), ManagedLibraryCopy.relativeFolder(track, forms));
        assertEquals(Path.of("Classical", "Ludwig van Beethoven", "Opus Numbers", "Op. 13"),
            ManagedLibraryCopy.relativeFolder(track, opus));
        assertEquals("02 - CON.wav", ManagedLibraryCopy.filename(track));
        assertEquals("_NUL", ManagedLibraryCopy.segment("NUL. ", "Unknown"));
    }

    @Test void previewFillsKnownWorkForAnUnindexedSourceAndKeepsTheFilenameEnding() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("Beethoven"));
        Path original = tone(folder.resolve("Performer's Beethoven Piano Sonata No. 29 in B-flat major Hammerklavier - 1. Allegro.wav"), 4);
        var options = new ManagedLibraryCopy.Options(true, true, true, true, true, false, ManagedLibraryCopy.Layout.OPUS);
        Path target = copy.preview(directory.resolve("Managed"), List.of(original), false,
            options, Map.of(), () -> false).entries().getFirst().target();
        assertTrue(target.getParent().endsWith(Path.of("Op. 106", "Piano Sonata No. 29 (Hammerklavier)")));
        assertTrue(target.getFileName().toString().endsWith("Hammerklavier - 1. Allegro.wav"));
    }

    @Test void concertoPhasesShareOneWorkAndTheArchdukeTrioCorrectsAFolderHint() {
        var options = new ManagedLibraryCopy.Options(true, true, true, true, true, false, ManagedLibraryCopy.Layout.FORMS);
        var tracks = java.util.stream.Stream.of("One", "Two", "Three").map(phase -> {
            String title = "Piano Concerto No. 5 'Emperor' [Phase " + phase + "]";
            return ManagedLibraryCopy.withWorkReferences(new Track(directory.resolve(title + ".wav"),
                title, "Ludwig van Beethoven", "Piano concertos", 80, "WAV", "Classical"));
        }).toList();
        var works = tracks.stream().map(track -> ManagedLibraryCopy.relativeFolder(track, options)).distinct().toList();
        assertEquals(1, works.size());
        assertTrue(works.getFirst().endsWith(Path.of("Concerto", "Piano", "Piano Concerto No. 5, Op. 73 (Emperor)")));
        assertEquals(List.of("1", "2", "3"), tracks.stream().map(track -> track.metadata().text(Field.MOVEMENT)).toList());
        var mistaken = new TrackMetadata(Map.of(Field.COMPOSER, new TrackMetadata.Value("Frédéric Chopin",
            TrackMetadata.Origin.FOLDER_HINT)), java.util.Set.of(), 0, 0, 0, "", false, 0);
        var trio = ManagedLibraryCopy.withWorkReferences(new Track(directory.resolve("Archduke.wav"),
            "Piano Trio No. 7 'Archduke'", "Ludwig van Beethoven", "Best Beethoven", 80, "WAV", "Classical", mistaken));
        assertEquals("Ludwig van Beethoven", trio.metadata().text(Field.COMPOSER));
        assertEquals("Op. 97", trio.metadata().text(Field.OPUS));
        var collection = new TrackMetadata(Map.of(Field.WORK, new TrackMetadata.Value("Ballades",
            TrackMetadata.Origin.ONLINE)), java.util.Set.of(), 0, 0, 0, "", false, 0);
        var ballade = ManagedLibraryCopy.withWorkReferences(new Track(directory.resolve("Chopin/Ballade.wav"),
            "Ballade no. 2 - Op. 38", "Unknown artist", "Ballades", 80, "WAV", "Classical", collection));
        assertEquals("Ballade No. 2, Op. 38", ballade.metadata().text(Field.WORK));
    }

    @Test void filingUsesComposerPeriodsCatalogueNumbersAndCanonicalFolderCase() {
        var options = new ManagedLibraryCopy.Options(true, true, true, true, true, false, ManagedLibraryCopy.Layout.FORMS);
        String title = "Brandenburg Concertos, Vol. 1 No. 1 In F Major (Bwv.1046): 2nd. Mov.: Adagio";
        var bach = ManagedLibraryCopy.withWorkReferences(new Track(directory.resolve("movement.wav"), title,
            "Johann Sebastian Bach", "Brandenburg Concertos", 80, "WAV", "Classical"));
        assertEquals("2. Adagio", bach.metadata().text(Field.MOVEMENT));
        assertEquals(Path.of("Classical", "Baroque", "Johann Sebastian Bach", "Musical Forms", "Concerto",
            "Brandenburg Concerto No. 1, BWV 1046"), ManagedLibraryCopy.relativeFolder(bach, options));
        var metadata = TrackMetadata.empty().edit(Map.of(Field.FORM, "concerto", Field.INSTRUMENTATION, "piano",
            Field.COMPOSER, "Tchaikovsky", Field.WORK, "Concerto No. 1"), null);
        var track = new Track(directory.resolve("concerto.wav"), "Concerto No. 1", "Pianist", "Album",
            80, "WAV", "Classical", metadata);
        assertEquals(Path.of("Classical", "Romantic", "Pyotr Ilyich Tchaikovsky", "Musical Forms", "Concerto", "Piano",
            "Concerto No. 1"), ManagedLibraryCopy.relativeFolder(track, options));
    }

    @Test void namedWorkAndCatalogueCluesIdentifyTheParentComposition() {
        var spring = ManagedLibraryCopy.withWorkReferences(new Track(directory.resolve("spring.wav"),
            "The Four Seasons 'Spring', In E Major, Op. 8, No. 3, Allegro", "Vivaldi", "Collection",
            100, "WAV", "Classical"));
        assertEquals("The Four Seasons – Spring, RV 269", spring.metadata().text(Field.WORK));
        assertEquals("Concerto", spring.metadata().text(Field.FORM));
        assertEquals("E major", spring.metadata().text(Field.WORK_KEY));
        var mozart = ManagedLibraryCopy.withWorkReferences(new Track(directory.resolve("minuet.wav"),
            "Eine Kleine Nachtmusik K525, Minuetto Allegro", "Mozart", "Collection", 80, "WAV", "Classical"));
        assertEquals("Eine kleine Nachtmusik, K. 525", mozart.metadata().text(Field.WORK));
        assertEquals("Serenade", mozart.metadata().text(Field.FORM));
        assertEquals("Concerto", ClassicalHints.form("Piano Conerto No. 3"));
        assertEquals("Nocturne", ClassicalHints.form("NocturneOp9No2"));
        assertEquals("Waltz", ClassicalHints.form("Grande Valse Brillante"));
    }

    private static Path tone(Path path, int seed) throws Exception {
        byte[] pcm = new byte[3200];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i * seed);
        try (var stream = new AudioInputStream(new ByteArrayInputStream(pcm),
            new AudioFormat(8000, 16, 1, true, false), pcm.length / 2)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
        return path;
    }
}
