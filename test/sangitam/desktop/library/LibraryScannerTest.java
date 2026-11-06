package sangitam.desktop.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.Track;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Uses generated silent audio, so scan tests never depend on a copyrighted recording. */
class LibraryScannerTest {
    @TempDir Path folder;

    @Test void readsDistinctClassicalCreditsAndTechnicalPropertiesWithoutReplacingThePerformer() throws Exception {
        Path file = folder.resolve("tagged.mp3");
        try (var source = getClass().getResourceAsStream("/audio/proof.mp3")) { Files.copy(source, file); }
        var audio = org.jaudiotagger.audio.AudioFileIO.read(file.toFile());
        var tag = audio.getTag();
        tag.setField(org.jaudiotagger.tag.FieldKey.TITLE, "Piano Sonata in A minor, Op. 27 No. 2: I. Allegro");
        tag.setField(org.jaudiotagger.tag.FieldKey.GENRE, "Classical");
        tag.setField(org.jaudiotagger.tag.FieldKey.COMPOSER, "Composer");
        tag.setField(org.jaudiotagger.tag.FieldKey.CONDUCTOR, "Conductor");
        tag.setField(org.jaudiotagger.tag.FieldKey.ORCHESTRA, "Orchestra");
        tag.setField(org.jaudiotagger.tag.FieldKey.KEY, "G minor");
        org.jaudiotagger.audio.AudioFileIO.write(audio);
        var result = new LibraryScanner().scan(List.of(file), false, ignored -> { }, () -> false);
        assertEquals(1, result.tracks().size(), result.errors().toString());
        Track track = result.tracks().getFirst();
        var data = track.metadata();
        assertEquals("Composer", data.text(sangitam.desktop.model.TrackMetadata.Field.COMPOSER));
        assertEquals("Conductor", data.text(sangitam.desktop.model.TrackMetadata.Field.CONDUCTOR));
        assertEquals("Orchestra", data.text(sangitam.desktop.model.TrackMetadata.Field.ORCHESTRA));
        assertEquals("G minor", data.text(sangitam.desktop.model.TrackMetadata.Field.KEY));
        assertEquals("Sangitam", track.artist());
        assertTrue(data.text(sangitam.desktop.model.TrackMetadata.Field.SOLOIST).isEmpty());
        assertTrue(data.bitrateKbps() > 0); assertTrue(data.sampleRateHz() > 0);
        assertEquals(1, data.extractionVersion());
    }

    @Test void scansMp3AndWavRecursivelyAndSkipsBadFiles() throws Exception {
        Path mp3 = folder.resolve("proof.mp3");
        try (var source = getClass().getResourceAsStream("/audio/proof.mp3")) {
            assertNotNull(source);
            Files.copy(source, mp3);
        }
        Path nested = Files.createDirectory(folder.resolve("nested"));
        writeSilentWav(nested.resolve("silence.wav"));
        Files.writeString(folder.resolve("notes.txt"), "not audio");
        Files.writeString(folder.resolve("broken.mp3"), "not audio");

        LibraryScanner scanner = new LibraryScanner();
        LibraryScanner.Result result = scanner.scan(List.of(folder, mp3), true, ignored -> { }, () -> false);
        assertEquals(2, result.tracks().size());
        assertTrue(result.skipped() >= 2);
        Track tagged = result.tracks().stream().filter(track -> track.format().equals("MP3")).findFirst().orElseThrow();
        assertEquals("Proof Song", tagged.title());
        assertEquals("Sangitam", tagged.artist());
        assertEquals("Test Set", tagged.album());
        assertTrue(tagged.durationSeconds() > 0);

        LibraryScanner.Result shallow = scanner.scan(List.of(folder), false, ignored -> { }, () -> false);
        assertEquals(1, shallow.tracks().size());
    }

    @Test void reportsUnavailableSourcesAndStillScansAvailableFiles() throws Exception {
        Path missing = folder.resolve("disconnected-folder");
        Path valid = folder.resolve("silence.wav");
        writeSilentWav(valid);

        LibraryScanner.Result result = new LibraryScanner().scan(List.of(missing, valid), true,
            ignored -> { }, () -> false);
        assertEquals(List.of(valid), result.tracks().stream().map(Track::path).toList());
        assertEquals(1, result.skipped());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().getFirst().contains(missing.toString()));
    }

    private void writeSilentWav(Path output) throws Exception {
        AudioFormat format = new AudioFormat(44_100, 16, 1, true, false);
        byte[] samples = new byte[44_100 * 2 / 4];
        try (AudioInputStream audio = new AudioInputStream(new ByteArrayInputStream(samples),
            format, samples.length / format.getFrameSize())) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, output.toFile());
        }
    }
}
