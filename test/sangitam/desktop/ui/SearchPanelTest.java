package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.Track;
import sangitam.desktop.storage.LibrarySearchStore.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class SearchPanelTest {
    @Test void pagesOnBackgroundWorkerAndEnterPlaysTheDisplayedResult() throws Exception {
        AtomicReference<SearchPanel> panel = new AtomicReference<>();
        AtomicReference<Track> played = new AtomicReference<>();
        AtomicBoolean offEdt = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> {
            panel.set(new SearchPanel(request -> {
                offEdt.set(!SwingUtilities.isEventDispatchThread());
                return page(request, 201);
            }, played::set, (category, hit) -> { }, track -> new JPopupMenu()));
            panel.get().setCatalogState(Map.of(), Set.of(), Set.of());
        });
        try {
            awaitEdt(() -> count(panel.get()).equals("1–100 of 201"));
            assertTrue(offEdt.get());
            SwingUtilities.invokeAndWait(() -> button(panel.get(), "Next").doClick());
            awaitEdt(() -> count(panel.get()).equals("101–200 of 201"));
            SwingUtilities.invokeAndWait(() -> {
                JList<?> list = TrackDetailsPanelTest.find(panel.get(), JList.class, "SONGS search results");
                assertEquals(100, list.getModel().getSize());
                list.setSelectedIndex(0);
                list.getActionMap().get("open").actionPerformed(new ActionEvent(list, 0, "open"));
            });
            assertEquals("Song 100", played.get().title());
        } finally { SwingUtilities.invokeAndWait(() -> panel.get().close()); }
    }
    @Test void lateOldResultsCannotReplaceANewerSearch() throws Exception {
        AtomicReference<SearchPanel> panel = new AtomicReference<>();
        CountDownLatch oldStarted = new CountDownLatch(1), release = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            panel.set(new SearchPanel(request -> {
                if (request.text().equals("old")) {
                    oldStarted.countDown();
                    boolean waiting = true;
                    while (waiting) { try { waiting = !release.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } }
                }
                return page(request, request.text().equals("old") ? 201 : 1);
            }, track -> { }, (category, hit) -> { }, track -> new JPopupMenu()));
            TrackDetailsPanelTest.find(panel.get(), JTextField.class, "Search library").setText("old");
        });
        try {
            assertTrue(oldStarted.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> TrackDetailsPanelTest.find(panel.get(), JTextField.class, "Search library").setText("new"));
            awaitEdt(() -> count(panel.get()).equals("1–1 of 1"));
            release.countDown();
            Thread.sleep(100);
            SwingUtilities.invokeAndWait(() -> assertEquals("1–1 of 1", count(panel.get())));
        } finally { release.countDown(); SwingUtilities.invokeAndWait(() -> panel.get().close()); }
    }
    private static Page page(Request request, int total) {
        List<Hit> hits = IntStream.range(request.offset(), Math.min(total, request.offset() + request.limit()))
            .mapToObj(index -> new Hit(new Track(Path.of(index + ".mp3"), "Song " + index, "Artist", "Album", 1, "MP3"), "", "", 1)).toList();
        return new Page(hits, total, request);
    }
    private static void awaitEdt(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        AtomicBoolean done = new AtomicBoolean();
        while (!done.get() && System.nanoTime() < until) { SwingUtilities.invokeAndWait(() -> done.set(condition.getAsBoolean())); if (!done.get()) Thread.sleep(10); }
        assertTrue(done.get(), "Swing search update timeout");
    }
    private static String count(SearchPanel panel) { return TrackDetailsPanelTest.find(panel, JLabel.class, "Search result count").getText(); }
    private static JButton button(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && button.getText().equals(text)) return button;
            if (child instanceof Container nested) { var result = button(nested, text); if (result != null) return result; }
        }
        return null;
    }
}
