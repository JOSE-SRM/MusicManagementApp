package sangitam.desktop.model;

import javax.swing.KeyStroke;
import java.awt.event.*;
import java.util.*;

/** App-local shortcuts. Hardware media keys stay under Windows' control. */
public record ShortcutBindings(Map<Command, String> bindings) {
    public enum Command {
        IMPORT("Add music"), QUIT("Quit"), QUICK("Quick actions"), SEARCH("Search"), PLAY_PAUSE("Play / pause"), PREVIOUS("Previous track"), NEXT("Next track"),
        VOLUME_UP("Volume up"), VOLUME_DOWN("Volume down"), STOP("Stop"), SHUFFLE("Shuffle"), REPEAT("Repeat current"), EQ("Audio & equalizer"), QUEUE("Queue / history"), LIBRARY("Show library"), SPECTRUM("Spectrum");
        private final String label;
        Command(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public ShortcutBindings {
        var copy = new EnumMap<Command, String>(Command.class); copy.putAll(bindings);
        var used = new HashMap<KeyStroke, Command>();
        for (Command command : Command.values()) {
            String text = copy.getOrDefault(command, "").strip(); copy.put(command, text);
            if (text.isEmpty()) continue;
            KeyStroke key = KeyStroke.getKeyStroke(text);
            if (!allowed(key)) throw new IllegalArgumentException("Choose a modified key or function key for " + command + ". Space is also available; Enter, Escape and Tab are reserved.");
            Command previous = used.putIfAbsent(key, command);
            if (previous != null) throw new IllegalArgumentException(previous + " and " + command + " use the same key.");
            copy.put(command, key.toString());
        }
        bindings = Collections.unmodifiableMap(copy);
    }
    public KeyStroke key(Command command) { String value = bindings.get(command); return value == null || value.isBlank() ? null : KeyStroke.getKeyStroke(value); }
    public static boolean allowed(KeyStroke key) {
        if (key == null || key.isOnKeyRelease() || key.getKeyCode() == 0) return false;
        int code = key.getKeyCode();
        if (Set.of(KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_META, KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE, KeyEvent.VK_TAB).contains(code)) return false;
        return code == KeyEvent.VK_SPACE || (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12) || (key.getModifiers() & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0;
    }
    public static ShortcutBindings defaults(int menuMask) {
        var result = new EnumMap<Command, String>(Command.class);
        Command[] commands = Command.values();
        int[] codes = {KeyEvent.VK_O, KeyEvent.VK_Q, KeyEvent.VK_K, KeyEvent.VK_F, KeyEvent.VK_SPACE, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN,
            KeyEvent.VK_S, KeyEvent.VK_H, KeyEvent.VK_R, KeyEvent.VK_E, KeyEvent.VK_J, KeyEvent.VK_L, KeyEvent.VK_V};
        Set<Command> shifted = Set.of(Command.STOP, Command.SHUFFLE, Command.REPEAT, Command.SPECTRUM);
        for (int i = 0; i < commands.length; i++) result.put(commands[i], KeyStroke.getKeyStroke(codes[i], commands[i] == Command.PLAY_PAUSE ? 0 : menuMask | (shifted.contains(commands[i]) ? InputEvent.SHIFT_DOWN_MASK : 0)).toString());
        return new ShortcutBindings(result);
    }
}
