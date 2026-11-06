package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.*;
import javax.swing.*;
import java.awt.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DetailResetPanelTest {
    @Test void restoringRequiresExplicitSelectionAndSelectAllCanBeCleared() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new DetailResetPanel(3); assertTrue(panel.selection().empty());
            find(panel, JCheckBox.class, "Title").doClick();
            assertEquals(Set.of(DetailReset.Basic.TITLE), panel.selection().basic());
            assertTrue(panel.selection().credits().isEmpty()); assertFalse(panel.selection().customTags());
            find(panel, JButton.class, "Select all details").doClick();
            assertEquals(DetailReset.Basic.values().length, panel.selection().basic().size());
            assertEquals(TrackMetadata.Field.values().length, panel.selection().credits().size()); assertTrue(panel.selection().customTags());
            find(panel, JButton.class, "Clear selection").doClick(); assertTrue(panel.selection().empty());
        });
    }
    private static <T extends AbstractButton> T find(Container root, Class<T> type, String text) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && text.equals(((AbstractButton) child).getText())) return type.cast(child);
            if (child instanceof Container nested) { T found = find(nested, type, text); if (found != null) return found; }
        }
        return null;
    }
}
