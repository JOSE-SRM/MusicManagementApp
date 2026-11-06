package sangitam.desktop.ui;

import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.event.KeyEvent;
import java.util.function.BooleanSupplier;

/** Runs before focused button, list, slider and combo-box bindings. */
final class SpaceTransport implements KeyEventDispatcher {
    private final BooleanSupplier enabled;
    private final Runnable toggle;
    private boolean down;

    SpaceTransport(BooleanSupplier enabled, Runnable toggle) {
        this.enabled = enabled;
        this.toggle = toggle;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (down && event.getID() == KeyEvent.KEY_TYPED && event.getKeyChar() == ' ') return true;
        if (event.getKeyCode() != KeyEvent.VK_SPACE) return false;
        if (event.getID() == KeyEvent.KEY_RELEASED && down) {
            down = false;
            return true;
        }
        if (down) return true;
        if (!enabled.getAsBoolean() || event.getID() != KeyEvent.KEY_PRESSED
            || event.getModifiersEx() != 0 || isTyping(event.getComponent())) return false;
        down = true;
        toggle.run();
        return true;
    }

    private static boolean isTyping(Component focus) {
        return focus instanceof JTextComponent text && text.isEditable() && text.isEnabled();
    }

    void reset() { down = false; }
}
