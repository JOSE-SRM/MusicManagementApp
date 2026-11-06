package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.ShortcutBindings;
import sangitam.desktop.model.ShortcutBindings.Command;
import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AppMenuBarTest {
    @Test void workingMenuActionsAndAcceleratorsFollowSavedBindingsWhilePlaceholdersStayDisabled() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Runnable ignored = () -> { }; var calls = new AtomicInteger(); var sort = new AtomicInteger(-1);
            var menu = new AppMenuBar(ignored, ignored, ignored, ignored, ignored, ignored, ignored, ignored, ignored, ignored, ignored, ignored, ignored, message -> { }, ignored, ignored);
            menu.setKeyboardShortcutsAction(calls::incrementAndGet); menu.setRecentAction(calls::incrementAndGet); menu.setSortAction(sort::set);
            find(menu, "Keyboard shortcuts…").doClick(); find(menu, "Recently played").doClick(); assertEquals(2, calls.get());
            find(menu, "Most played").doClick(); assertEquals(2, sort.get());
            var keys = new EnumMap<Command, String>(Command.class); keys.putAll(ShortcutBindings.defaults(InputEvent.CTRL_DOWN_MASK).bindings()); keys.put(Command.QUICK, "ctrl pressed P");
            menu.setShortcutBindings(new ShortcutBindings(keys)); assertEquals(KeyStroke.getKeyStroke("ctrl pressed P"), find(menu, "Quick actions…").getAccelerator());
            assertFalse(find(menu, "Undo").isEnabled()); assertNull(find(menu, "Undo").getAccelerator()); assertFalse(find(menu, "Check for updates").isEnabled());
        });
    }
    private static JMenuItem find(Container parent, String text) {
        Component[] items = parent instanceof JMenu menu ? menu.getMenuComponents() : parent.getComponents();
        for (Component item : items) {
            if (item instanceof JMenuItem entry && text.equals(entry.getText())) return entry;
            if (item instanceof Container nested) { JMenuItem match = find(nested, text); if (match != null) return match; }
        } return null;
    }
}
