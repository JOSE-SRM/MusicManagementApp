package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.ShortcutBindings;
import sangitam.desktop.model.ShortcutBindings.Command;
import javax.swing.*;
import java.awt.event.*;
import static org.junit.jupiter.api.Assertions.*;

class ShortcutPanelTest {
    @Test void capturedKeysValidateConflictsAndCanBeClearedOrReset() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var defaults = ShortcutBindings.defaults(InputEvent.CTRL_DOWN_MASK); var panel = new ShortcutPanel(defaults, InputEvent.CTRL_DOWN_MASK);
            var fields = AudioSettingsPanelTest.children(panel, JTextField.class);
            JTextField quick = fields.stream().filter(field -> "Quick actions shortcut".equals(field.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
            capture(quick, KeyEvent.VK_P); assertEquals(KeyStroke.getKeyStroke("ctrl pressed P"), panel.value().key(Command.QUICK));
            JTextField search = fields.stream().filter(field -> "Search shortcut".equals(field.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow();
            capture(search, KeyEvent.VK_P); assertThrows(IllegalArgumentException.class, panel::value);
            AudioSettingsPanelTest.children(panel, JButton.class).stream().filter(button -> "Clear Quick actions shortcut".equals(button.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow().doClick();
            assertNull(panel.value().key(Command.QUICK));
            AudioSettingsPanelTest.children(panel, JButton.class).stream().filter(button -> "Restore defaults".equals(button.getText())).findFirst().orElseThrow().doClick(); assertEquals(defaults, panel.value());
        });
    }
    private static void capture(JTextField field, int key) { var event = new KeyEvent(field, KeyEvent.KEY_PRESSED, 1, InputEvent.CTRL_DOWN_MASK, key, KeyEvent.CHAR_UNDEFINED); for (var listener : field.getKeyListeners()) listener.keyPressed(event); }
}
