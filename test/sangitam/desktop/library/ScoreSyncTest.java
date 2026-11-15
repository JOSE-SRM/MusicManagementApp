package sangitam.desktop.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ScoreSyncTest {
    @TempDir Path directory;

    @Test void audioIsAlignedToMidiWithSilenceAndUnevenTempo() throws Exception {
        int[] pitches = {60, 64, 67, 62, 65, 69, 63, 70, 66, 71, 61, 68};
        var sequence = new Sequence(Sequence.PPQ, 480);
        var track = sequence.createTrack();
        for (int index = 0; index < pitches.length; index++) {
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, pitches[index], 90), index * 480L));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, pitches[index], 0), (index + 1) * 480L));
        }
        Path midi = directory.resolve("score.mid");
        MidiSystem.write(sequence, 1, midi.toFile());
        var reference = ScoreSync.reference(midi);
        assertEquals(pitches.length, reference.notes().size());
        Path audio = directory.resolve("performance.wav");
        int rate = 16000;
        int[] lengths = {8000, 16000, 16000, 9600, 9600, 9600, 9600, 12800, 12800, 6400, 6400, 6400};
        int leading = 12800;
        byte[] samples = new byte[(leading + java.util.Arrays.stream(lengths).sum() + rate) * 2];
        int offset = leading;
        for (int i = 0; i < pitches.length; i++) {
            double hz = 440 * Math.pow(2, (pitches[i] - 69) / 12.0);
            for (int j = 0; j < lengths[i]; j++) {
                short value = (short) (Math.sin(2 * Math.PI * hz * j / rate) * 12000);
                samples[2 * (offset + j)] = (byte) value;
                samples[2 * (offset + j) + 1] = (byte) (value >> 8);
            }
            offset += lengths[i];
        }
        try (var pcm = new AudioInputStream(new ByteArrayInputStream(samples), new AudioFormat(rate, 16, 1, true, false), samples.length / 2)) {
            AudioSystem.write(pcm, AudioFileFormat.Type.WAVE, audio.toFile());
        }
        var alignment = ScoreSync.align(audio, reference);
        assertTrue(alignment.evidence() > .7);
        assertEquals(1.5, alignment.scoreAt(3.45), .32);
        assertEquals(3.3, alignment.audioAt(1.5), .40);
        assertEquals(4.6, alignment.scoreAt(7.4), .45);
        var visualNotes = reference.notes().stream().map(note -> new ScoreSync.Note(note.pitch(), note.start() / 4, note.end() / 4)).toList();
        var visual = new OpticalScoreRecognition.Variant(new ScoreSync.Reference(reference.duration() / 4, visualNotes),
            java.util.List.of(), "written");
        var recognized = ScoreSync.alignOptical(audio, new OpticalScoreRecognition.Result(visual, null, "page-revision"), false);
        assertEquals("page-revision:written", recognized.alignment().referenceId());
        assertEquals(1.5 / 4, recognized.alignment().scoreAt(3.45), .10);
        assertEquals(3.3, recognized.alignment().audioAt(1.5 / 4), .40);

        var late = new java.util.ArrayList<ScoreSync.Note>();
        late.add(new ScoreSync.Note(54, 0, 12));
        for (var note : reference.notes()) late.add(new ScoreSync.Note(note.pitch(), note.start() + 12, note.end() + 12));
        var complete = new OpticalScoreRecognition.Result(new OpticalScoreRecognition.Variant(
            new ScoreSync.Reference(12 + reference.duration(), late), java.util.List.of(), "written"), null, "complete");
        assertTrue(ScoreSync.alignOptical(audio, complete, true).alignment().scoreAt(0) > 10);
        assertThrows(java.io.IOException.class, () -> ScoreSync.alignOptical(audio, complete, true, true));
    }

    @Test void recognizedXmlPreservesPrintedCoordinatesAndRepeatLocations() throws Exception {
        var xml = new StringBuilder("""
            <?xml version="1.0"?><!DOCTYPE score-partwise SYSTEM "https://example.invalid/musicxml.dtd">
            <score-partwise><defaults><page-layout><page-width>1000</page-width><page-height>1400</page-height>
            <page-margins><left-margin>80</left-margin><right-margin>80</right-margin>
            <top-margin>80</top-margin><bottom-margin>80</bottom-margin></page-margins></page-layout></defaults>
            <part id="P1">
            """);
        for (int measure = 0; measure < 4; measure++) {
            xml.append("<measure width='150'>");
            if (measure == 0) xml.append("""
                <print><system-layout><system-margins><left-margin>40</left-margin></system-margins>
                <top-system-distance>70</top-system-distance></system-layout></print>
                <attributes><divisions>2</divisions></attributes><barline><repeat direction='forward'/></barline>
                """);
            for (int note = 0; note < 4; note++) xml.append("""
                <note default-x="%d"><pitch><step>C</step><alter>1</alter><octave>4</octave></pitch><duration>2</duration></note>
                """.formatted(20 + note * 25));
            if (measure == 1) xml.append("<barline><repeat direction='backward'/></barline>");
            xml.append("</measure>");
        }
        xml.append("</part></score-partwise>");
        var file = java.nio.file.Files.writeString(directory.resolve("score-0007.xml"), xml);
        var recognized = OpticalScoreXml.read(java.util.List.of(file), "test");
        assertEquals(16, recognized.written().reference().notes().size());
        assertEquals(61, recognized.written().reference().notes().getFirst().pitch());
        assertEquals(24, recognized.repeated().reference().notes().size());
        var map = EngravedScoreMap.recognized(recognized.repeated().marks());
        assertEquals(7, map.positionAt(.5).page());
        assertEquals(60 / 840.0, map.positionAt(0).x(), 1e-6);
        assertEquals(map.positionAt(0), map.positionAt(4));
        assertEquals(4, map.referenceAt(7, 60 / 840.0, .07, 4.1), 1e-6);
    }

    @Test void numberedPdfBookmarksBoundOnlyTheRequestedMovement() throws Exception {
        Path file = directory.resolve("bookmarked.pdf");
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
            for (int page = 0; page < 8; page++) pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            var outline = new org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline();
            pdf.getDocumentCatalog().setDocumentOutline(outline);
            var first = new org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem();
            first.setTitle("I. Allegro");
            first.setDestination(pdf.getPage(2));
            outline.addLast(first);
            var second = new org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem();
            second.setTitle("II. Adagio");
            second.setDestination(pdf.getPage(5));
            outline.addLast(second);
            pdf.save(file.toFile());
        }
        assertEquals(new ScoreMovementPages.Range(3, 5), ScoreMovementPages.bookmarks(file, 1));
        assertNull(ScoreMovementPages.bookmarks(file, 2));
    }
}
