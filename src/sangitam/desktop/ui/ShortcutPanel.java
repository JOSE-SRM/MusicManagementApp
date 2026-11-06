package sangitam.desktop.ui;

import sangitam.desktop.model.ShortcutBindings;
import sangitam.desktop.model.ShortcutBindings.Command;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;

/** Capture rather than type Swing key syntax; edits apply only after the caller saves. */
final class ShortcutPanel extends JPanel {
    private final Map<Command, KeyStroke> values = new EnumMap<>(Command.class);
    private final Map<Command, JTextField> fields = new EnumMap<>(Command.class);
    ShortcutPanel(ShortcutBindings current, int menuMask) {
        super(new BorderLayout(0, 12));
        add(UiFactory.label("Click a binding and press your keys. Clear disables it. Windows media keys are separate.", Theme.SMALL, Theme.TEXT_MUTED), BorderLayout.NORTH);
        JPanel rows = new JPanel(new GridLayout(0, 3, 10, 6)); rows.setOpaque(false);
        for (Command command : Command.values()) {
            rows.add(UiFactory.label(command.toString(), Theme.BODY, Theme.TEXT));
            JTextField capture = new JTextField(); capture.setEditable(false); capture.setColumns(17); capture.setFocusTraversalKeysEnabled(true);
            capture.getAccessibleContext().setAccessibleName(command + " shortcut"); fields.put(command, capture);
            capture.addKeyListener(new KeyAdapter() { @Override public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_TAB) return;
                KeyStroke key = KeyStroke.getKeyStroke(event.getKeyCode(), event.getModifiersEx());
                if (ShortcutBindings.allowed(key)) { values.put(command, key); update(command); } event.consume();
            } }); rows.add(capture);
            JButton clear = UiFactory.button("Clear", null, false); clear.getAccessibleContext().setAccessibleName("Clear " + command + " shortcut");
            clear.addActionListener(event -> { values.remove(command); update(command); }); rows.add(clear);
        }
        JScrollPane scroll = new JScrollPane(rows); scroll.setBorder(null); UiFactory.styleScroll(scroll); scroll.setPreferredSize(new Dimension(690, 440)); add(scroll);
        JButton reset = UiFactory.button("Restore defaults", null, false); reset.addActionListener(event -> load(ShortcutBindings.defaults(menuMask))); add(reset, BorderLayout.SOUTH); load(current);
    }
    private void load(ShortcutBindings bindings) { for (Command command : Command.values()) { KeyStroke key = bindings.key(command); if (key == null) values.remove(command); else values.put(command, key); update(command); } }
    private void update(Command command) {
        KeyStroke key = values.get(command); String text = key == null ? "Disabled" : (key.getModifiers() == 0 ? "" : InputEvent.getModifiersExText(key.getModifiers()) + "+") + KeyEvent.getKeyText(key.getKeyCode());
        fields.get(command).setText(text);
    }
    ShortcutBindings value() { var result = new EnumMap<Command, String>(Command.class); values.forEach((command, key) -> result.put(command, key.toString())); return new ShortcutBindings(result); }
}
