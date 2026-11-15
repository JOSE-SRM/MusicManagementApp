package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.*;
import sangitam.desktop.model.TrackMetadata.*;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class TrackDetailsPanelTest {
    private Track track(String name, String composer) {
        return new Track(Path.of(name), "Title", "Artist", "Album", 60, "MP3", "Classical",
            new TrackMetadata(Map.of(Field.COMPOSER, new Value(composer, Origin.EMBEDDED)), Set.of("Focus"), 320, 44100, 16, "Stereo", true, 1));
    }
    @Test void editingOneCreditDoesNotFreezeRatingsOrOtherCredits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TrackDetailsPanel panel = new TrackDetailsPanel(List.of(track("song.mp3", "Original")), false, TrackTags.pending());
            assertTrue(panel.changes().metadata().isEmpty());
            JTextArea technical = find(panel, JTextArea.class, "Audio file details");
            assertTrue(technical.getText().contains("320 kbps"));
            assertTrue(technical.getText().contains("44100 Hz"));
            JTextField composer = find(panel, JTextField.class, "Composer");
            composer.setText("Correction");
            var edit = panel.changes();
            assertEquals(Map.of(Field.COMPOSER, "Correction"), edit.metadata());
            assertNull(edit.title()); assertNull(edit.artist()); assertNull(edit.energy()); assertNull(edit.userTags());
            composer.setText("");
            assertEquals("", panel.changes().metadata().get(Field.COMPOSER));
        });
    }
    @Test void mixedAlbumFieldsAndTagReplacementRequireExplicitApplication() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TrackDetailsPanel panel = new TrackDetailsPanel(List.of(track("a.mp3", "A"), track("b.mp3", "B")), true, TrackTags.pending());
            JTextField composer = find(panel, JTextField.class, "Composer");
            assertEquals("", composer.getText()); assertFalse(composer.isEnabled());
            assertTrue(panel.changes().metadata().isEmpty());
            checkbox(panel, "Apply Composer").doClick();
            assertTrue(composer.isEnabled());
            assertEquals(Map.of(Field.COMPOSER, ""), panel.changes().metadata());
            JTextArea tags = find(panel, JTextArea.class, "Custom tags"); tags.setText("Study, My label");
            assertNull(panel.changes().userTags());
            checkbox(panel, "Replace tags on all selected tracks").doClick();
            assertEquals(Set.of("Study", "My label"), panel.changes().userTags());
        });
    }
    @Test void analysisShowsSamplingAndEvidenceAndHidesSupersededEstimates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var current = new AudioFeatures(AudioFeatures.VERSION, 1, 2, 180, -23, -1, 18, 620,
                106, .35, "B♭", "major", .23, 70, 50, "Energetic", 0, List.of(), 5, 99, 116, 18, List.of());
            var panel = new TrackDetailsPanel(List.of(track("song.flac", "Composer")), false,
                new TrackTags(70, 60, true, 1, 2, current));
            String text = find(panel, JTextArea.class, "Local audio analysis").getText();
            assertTrue(text.contains("B♭ major estimated key"));
            assertTrue(text.contains("5 passages"));
            assertTrue(text.contains("99–116 BPM"));
            assertTrue(text.contains("B♭ major · Weak evidence"));
            assertFalse(text.contains("100%"));

            var old = new AudioFeatures(1, 1, 2, 180, -23, -1, 18, 620, 120, .28,
                "D", "minor", 1, 70, 30, "Tense", .74, List.of());
            var stale = new TrackDetailsPanel(List.of(track("song.flac", "Composer")), false,
                new TrackTags(70, 60, true, 1, 2, old));
            String pending = find(stale, JTextArea.class, "Local audio analysis").getText();
            assertTrue(pending.contains("Analysis update pending"));
            assertTrue(pending.contains("Audio sampled"));
            assertFalse(pending.contains("D minor"));
            assertEquals(50, old.recommendationValence());
            var missing = new TrackDetailsPanel(List.of(track("moved.flac", "Composer")), false,
                new TrackTags(70, 60, true, 1, 2, old), true);
            String unavailable = find(missing, JTextArea.class, "Local audio analysis").getText();
            assertTrue(unavailable.contains("Audio file unavailable"));
            assertTrue(unavailable.contains("Audio sampled"));
        });
    }

    @Test void existingAccidentalSpellingsDisplayAsSymbolsWithoutCreatingEdits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var track = new Track(Path.of("fugue.flac"), "Fugue", "Artist", "Album", 180, "FLAC", "Classical",
                TrackMetadata.empty().edit(Map.of(Field.KEY, "F# minor", Field.WORK_KEY, "B-flat major"), null));
            var panel = new TrackDetailsPanel(List.of(track), false, TrackTags.pending());
            assertEquals("F♯ minor", find(panel, JTextField.class, "Musical key").getText());
            assertEquals("B♭ major", find(panel, JTextField.class, "Work key").getText());
            assertTrue(panel.changes().metadata().isEmpty());
            String analysis = find(panel, JTextArea.class, "Local audio analysis").getText();
            assertTrue(analysis.contains("F♯ minor"));
            assertTrue(analysis.contains("B♭ major"));
        });
    }
    static <T extends JComponent> T find(Container parent, Class<T> type, String name) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child) && name.equals(((JComponent) child).getAccessibleContext().getAccessibleName())) return type.cast(child);
            if (child instanceof Container nested) { T result = find(nested, type, name); if (result != null) return result; }
        }
        return null;
    }
    private static JCheckBox checkbox(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JCheckBox box && text.equals(box.getText())) return box;
            if (child instanceof Container nested) { var result = checkbox(nested, text); if (result != null) return result; }
        }
        return null;
    }
}
