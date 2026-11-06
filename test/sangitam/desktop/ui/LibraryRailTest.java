package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.*;
import sangitam.desktop.storage.SmartPlaylistStore.Preview;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class LibraryRailTest {
    @Test void expandingInsideTheVerticalQueueRailUsesWorkspaceWidth() throws Exception {
        var panel = new AtomicReference<LibraryPanel>(); var notified = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> {
            LibraryPanel library = new LibraryPanel(new LibraryPanel.Actions() {
                public void importMusic(long folder) { } public void rescan() { } public void play(List<Track> tracks) { }
                public void queue(List<Track> tracks) { } public void editDetails(List<Track> tracks, boolean group) { } public void restoreDetails(List<Track> tracks) { }
                public void remove(List<Path> paths, Path scope) { } public void open(LibraryEntry entry) { } public void organize(String text, LibraryPanel.CollectionEdit edit) { }
                public void deletePlaylist(long id) { } public void entriesChanged() { } public Preview previewSmart(SmartPlaylistRule rule) { return new Preview(0, 0, List.of()); }
            }); panel.set(library); library.setExpansionListener(notified::set);
            JPanel workspace = new JPanel(new BorderLayout()); workspace.setSize(1000, 700);
            JSplitPane rail = new JSplitPane(JSplitPane.VERTICAL_SPLIT, library, new JPanel()); workspace.add(rail, BorderLayout.EAST); AudioSettingsPanelTest.layout(workspace);
            AudioSettingsPanelTest.children(library, JButton.class).stream().filter(button -> "Expand library".equals(button.getToolTipText())).findFirst().orElseThrow().doClick();
        });
        try {
            var width = new AtomicInteger(); long deadline = System.nanoTime() + 3_000_000_000L;
            while (width.get() < 968 && System.nanoTime() < deadline) { SwingUtilities.invokeAndWait(() -> width.set(panel.get().getPreferredSize().width)); if (width.get() < 968) Thread.sleep(25); }
            assertTrue(notified.get()); assertEquals(968, width.get());
        } finally { SwingUtilities.invokeAndWait(() -> panel.get().close()); }
    }
}
