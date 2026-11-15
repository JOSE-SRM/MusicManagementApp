package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.*;
import sangitam.desktop.storage.SmartPlaylistStore.Preview;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class SmartPlaylistPanelTest {
    @Test void roundTripsRulesAndCommitsTypedSpinnerValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var rule = new SmartPlaylistRule("Piano", "Classical", Set.of("Focus"), new SmartPlaylistRule.Range(20, 60), null, 3, true, SmartPlaylistRule.Order.MOST_PLAYED, 125);
            try (var panel = new SmartPlaylistPanel("Focus", "A rule", rule, ignored -> new Preview(0, 0, List.of()))) {
                assertEquals(rule, panel.rule()); assertEquals("Focus", panel.playlistName()); assertEquals("A rule", panel.description());
                JSpinner limit = find(panel, JSpinner.class, "Track limit");
                ((JSpinner.DefaultEditor) limit.getEditor()).getTextField().setText("75");
                assertEquals(75, panel.rule().limit());
                find(panel, JTextField.class, "Playlist name").setText("  ");
                assertThrows(IllegalArgumentException.class, panel::playlistName);
                find(panel, JSpinner.class, "Minimum energy").setValue(80);
                assertThrows(IllegalArgumentException.class, panel::rule);
                checkbox(panel, "Filter energy").doClick();
                assertNull(panel.rule().energy(), "Disabled rating filters should not block saving");
            }
        });
    }
    @Test void previewRunsOffEdtAndEditingRulesRejectsLateResults() throws Exception {
        AtomicReference<SmartPlaylistPanel> panel = new AtomicReference<>();
        AtomicBoolean offEdt = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            panel.set(new SmartPlaylistPanel("Smart", "", SmartPlaylistRule.defaults(), rule -> {
                offEdt.set(!SwingUtilities.isEventDispatchThread());
                if (rule.text().isBlank()) {
                    started.countDown();
                    boolean done = false;
                    while (!done) try { done = release.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                    return new Preview(90, 90, List.of());
                }
                return new Preview(7, 7, List.of());
            }));
            button(panel.get(), "Preview matches").doClick();
        });
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                find(panel.get(), JTextField.class, "Text contains").setText("new");
                button(panel.get(), "Preview matches").doClick();
            });
            awaitEdt(() -> find(panel.get(), JLabel.class, "Smart playlist preview status").getText().startsWith("7 matching"));
            assertTrue(offEdt.get()); release.countDown(); Thread.sleep(100);
            SwingUtilities.invokeAndWait(() -> assertTrue(find(panel.get(), JLabel.class, "Smart playlist preview status").getText().startsWith("7 matching")));
        } finally { release.countDown(); SwingUtilities.invokeAndWait(() -> panel.get().close()); }
    }
    @Test void previewFailuresAreVisibleAndCanBeRetried() throws Exception {
        AtomicReference<SmartPlaylistPanel> panel = new AtomicReference<>();
        AtomicInteger attempts = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            panel.set(new SmartPlaylistPanel("Smart", "", SmartPlaylistRule.defaults(), rule -> {
                if (attempts.incrementAndGet() == 1) throw new java.sql.SQLException("test failure");
                return new Preview(0, 0, List.of());
            })); button(panel.get(), "Preview matches").doClick();
        });
        try {
            awaitEdt(() -> find(panel.get(), JLabel.class, "Smart playlist preview status").getText().startsWith("Could not preview"));
            SwingUtilities.invokeAndWait(() -> { assertTrue(button(panel.get(), "Preview matches").isEnabled()); button(panel.get(), "Preview matches").doClick(); });
            awaitEdt(() -> find(panel.get(), JLabel.class, "Smart playlist preview status").getText().startsWith("0 matching"));
        } finally { SwingUtilities.invokeAndWait(() -> panel.get().close()); }
    }
    private static void awaitEdt(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4); var done = new AtomicBoolean();
        while (!done.get() && System.nanoTime() < end) { SwingUtilities.invokeAndWait(() -> done.set(condition.getAsBoolean())); if (!done.get()) Thread.sleep(10); }
        assertTrue(done.get(), "Preview update timeout");
    }
    static <T extends JComponent> T find(Container root, Class<T> type, String name) { return TrackDetailsPanelTest.find(root, type, name); }
    private static JButton button(Container root, String text) {
        for (Component item : root.getComponents()) {
            if (item instanceof JButton button && button.getText().equals(text)) return button;
            if (item instanceof Container nested) { var found = button(nested, text); if (found != null) return found; }
        }
        return null;
    }
    private static JCheckBox checkbox(Container root, String text) {
        for (Component item : root.getComponents()) {
            if (item instanceof JCheckBox box && box.getText().equals(text)) return box;
            if (item instanceof Container nested) { var found = checkbox(nested, text); if (found != null) return found; }
        }
        return null;
    }
}
