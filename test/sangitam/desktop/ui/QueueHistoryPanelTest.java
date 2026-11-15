package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.Track;
import sangitam.desktop.session.SessionPlanner.Choice;
import javax.swing.*;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class QueueHistoryPanelTest {
    @Test void queueKeepsDuplicatePositionsAndHistoryPlaysItsSelectedTrack() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var index = new AtomicInteger(-1); var played = new AtomicReference<Track>();
            var panel = new QueueHistoryPanel(index::set, played::set, ignored -> new JPopupMenu(), () -> { });
            var track = new Track(Path.of("song.mp3"), "Song", "Artist", "Album", 60, "MP3");
            panel.setQueue(List.of(new Choice(track, "One"), new Choice(track, "Repeat")), 1); panel.setRecent(List.of(track));
            var lists = AudioSettingsPanelTest.children(panel, JList.class); assertEquals(2, lists.size());
            assertEquals(2, lists.getFirst().getModel().getSize()); lists.getFirst().getActionMap().get("play").actionPerformed(new ActionEvent(panel, 0, "")); assertEquals(1, index.get());
            lists.getLast().setSelectedIndex(0); lists.getLast().getActionMap().get("play").actionPerformed(new ActionEvent(panel, 0, "")); assertEquals(track, played.get());
        });
    }
}
