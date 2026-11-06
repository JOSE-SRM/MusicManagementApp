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
