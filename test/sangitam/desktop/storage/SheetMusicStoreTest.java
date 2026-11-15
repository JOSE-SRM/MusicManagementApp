package sangitam.desktop.storage;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.library.ScorePdf;
import sangitam.desktop.model.FileStamp;
import sangitam.desktop.model.Track;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SheetMusicStoreTest {
    @TempDir Path directory;

    @Test void copiedScannedScoreReopensAndKeepsPerRecordingTimingsAfterRefiling() throws Exception {
        Path pdf = directory.resolve("Édition.pdf");
        var scan = new BufferedImage(18, 24, BufferedImage.TYPE_INT_RGB);
        try (var document = new PDDocument()) {
            for (int i = 0; i < 3; i++) {
                var page = new PDPage(new PDRectangle(180, 240));
                document.addPage(page);
                try (var content = new PDPageContentStream(document, page)) {
                    content.drawImage(LosslessFactory.createFromImage(document, scan), 0, 0, 180, 240);
                }
            }
            document.save(pdf.toFile());
        }
        var imported = ScorePdf.read(pdf);
        assertEquals(3, imported.pages());
        var image = ScorePdf.render(imported.pdf(), 2, 1f);
        assertEquals(180, image.getWidth());
        assertEquals(Color.BLACK.getRGB(), image.getRGB(90, 120));

        Path database = directory.resolve("library.db");
        var library = new LibraryStore(database);
        library.initialize();
        var first = track("first.flac");
        var second = track("second.flac");
        library.saveAll(List.of(first, second));
        var scores = new SheetMusicStore(library);
        assertThrows(SQLException.class, () -> scores.add(directory.resolve("unknown.flac"), "Score", 3, imported.pdf()));
        var firstEdition = scores.add(first.path(), pdf.getFileName().toString(), 3, imported.pdf());
        var secondEdition = scores.add(second.path(), pdf.getFileName().toString(), 3, imported.pdf());
        var marked = scores.saveTiming(firstEdition, 2, 23.5);
        assertThrows(SQLException.class, () -> scores.saveTiming(firstEdition, 3, 30.0));
        assertThrows(IllegalArgumentException.class, () -> scores.saveTiming(marked, 3, 20.0));
        scores.saveTiming(marked, 3, 61.0);
        Files.delete(pdf);

        Path target = directory.resolve("managed/refiled.flac");
        new ManagedLibraryStore(library).relocate(first.path(), target, new FileStamp(20, 30));
        var reopened = new SheetMusicStore(new LibraryStore(database));
        var edition = reopened.editions(target).getFirst();
        assertEquals(edition, reopened.editions(first.path()).getFirst());
        assertEquals(firstEdition.id(), edition.id());
        assertEquals(1, edition.pageAt(3));
        assertEquals(2, edition.pageAt(23.5));
        assertEquals(3, edition.pageAt(75));
        assertArrayEquals(imported.pdf(), reopened.pdf(edition.id()));
        assertEquals(secondEdition, reopened.editions(second.path()).getFirst());
        assertEquals(1, reopened.saveTiming(edition, 2, null).pageAt(40));
        reopened.remove(edition.id());
        assertTrue(reopened.editions(target).isEmpty());
        assertEquals(1, reopened.editions(second.path()).size());
    }

    @Test void invalidPdfCannotBeImported() throws Exception {
        Path invalid = Files.writeString(directory.resolve("invalid.pdf"), "This file is not a score");
        assertThrows(IOException.class, () -> ScorePdf.read(invalid));
    }

    @Test void managedWorkSharesOneCompletePdfButKeepsRecordingsSeparate() throws Exception {
        var library = new LibraryStore(directory.resolve("library.db"));
        Path root = Files.createDirectory(directory.resolve("Music"));
        library.saveManagedRoot(root);
        var first = movement("first.wav", "Phase One");
        var second = movement("second.wav", "Phase Two");
        var alternate = movement("alternate.wav", "Phase One");
        library.saveAll(List.of(first, second, alternate));
        var scores = new ManagedScoreStore(library);
        var parts = List.of(new ManagedScoreStore.Part(1, pdf(2), new byte[]{1}, "https://www.mutopiaproject.org/1"),
            new ManagedScoreStore.Part(2, pdf(3), new byte[]{2}, "https://www.mutopiaproject.org/2"));
        var asset = scores.save(first, "Pathétique", parts);
        assertTrue(asset.file().startsWith(root.resolve("Sheet Music")));
        assertEquals(5, ScorePdf.read(asset.file()).pages());
        assertEquals(3, asset.movement(2).firstPage());
        assertEquals(asset, scores.list(second).getFirst());
        assertEquals(asset, scores.save(alternate, "Pathétique", parts));
        var marks = new java.util.TreeMap<Integer, Double>();
        marks.put(1, 0.5);
        scores.saveRecording(first, asset, new ManagedScoreStore.Recording(marks,
            new sangitam.desktop.library.ScoreSync.Alignment(.5, .2, new double[]{0, .2, .4}, .81)));
        assertNull(scores.recording(alternate, asset).alignment());
        assertNull(scores.recording(second, asset).alignment());
        Path moved = Files.move(first.path(), directory.resolve("refiled.wav"));
        new ManagedLibraryStore(library).relocate(first.path(), moved, new FileStamp(Files.size(moved), Files.getLastModifiedTime(moved).toMillis()));
        assertEquals(.81, scores.recording(first, asset).alignment().evidence());
        Files.writeString(moved, "replacement recording with a different file size");
        assertNull(scores.recording(first, asset).alignment());
    }

    private Track movement(String filename, String phase) throws Exception {
        Path path = Files.writeString(directory.resolve(filename), "audio fixture for association");
        return new Track(path, "Pathétique [" + phase + "]", "Beethoven", "Recording", 90, "WAV");
    }

    private static byte[] pdf(int count) throws Exception {
        try (var document = new PDDocument(); var output = new java.io.ByteArrayOutputStream()) {
            for (int index = 0; index < count; index++) document.addPage(new PDPage(new PDRectangle(180, 240)));
            document.save(output);
            return output.toByteArray();
        }
    }

    private Track track(String filename) {
        return new Track(directory.resolve(filename), "Same work", "Performer", "Album", 90, "FLAC");
    }
}
