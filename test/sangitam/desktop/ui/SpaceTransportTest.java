package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.Component;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class SpaceTransportTest {
    @Test void focusedControlsAndAutoRepeatDoNotStealTransportButEditorsKeepSpace() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var active = new AtomicBoolean(true);
            var plays = new AtomicInteger();
            var dispatcher = new SpaceTransport(active::get, plays::incrementAndGet);
            for (Component focus : new Component[]{new JButton("Button"), new JList<>(), new JSlider(), new JComboBox<>()}) {
                assertTrue(dispatcher.dispatchKeyEvent(space(focus, KeyEvent.KEY_PRESSED, 0)));
                assertTrue(dispatcher.dispatchKeyEvent(space(focus, KeyEvent.KEY_PRESSED, 0)));
                assertTrue(dispatcher.dispatchKeyEvent(space(focus, KeyEvent.KEY_RELEASED, 0)));
            }
            assertEquals(4, plays.get());
            JTextField editor = new JTextField();
            assertFalse(dispatcher.dispatchKeyEvent(space(editor, KeyEvent.KEY_PRESSED, 0)));
            assertFalse(dispatcher.dispatchKeyEvent(space(new JButton(), KeyEvent.KEY_PRESSED, KeyEvent.SHIFT_DOWN_MASK)));
            active.set(false);
            assertFalse(dispatcher.dispatchKeyEvent(space(new JButton(), KeyEvent.KEY_PRESSED, 0)));
            assertEquals(4, plays.get());
        });
    }

    private static KeyEvent space(Component source, int type, int modifiers) {
        return new KeyEvent(source, type, 1L, modifiers, KeyEvent.VK_SPACE, ' ');
    }
}
