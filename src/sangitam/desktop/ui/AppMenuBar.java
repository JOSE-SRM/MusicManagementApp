package sangitam.desktop.ui;

import javax.swing.*;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;
import sangitam.desktop.model.ShortcutBindings;
import sangitam.desktop.model.ShortcutBindings.Command;
import java.util.*;

/** Application menus. Live library and playback actions coexist with clearly notified future commands. */
public final class AppMenuBar extends JMenuBar {
    private static final long serialVersionUID = 1L;
    private JCheckBoxMenuItem shuffleItem;
    private JCheckBoxMenuItem repeatItem;
    private JCheckBoxMenuItem libraryItem;
    private final Map<Command, java.util.List<JMenuItem>> shortcutItems = new EnumMap<>(Command.class);
    private Runnable keyboardShortcuts = () -> { };
    private Runnable recentAction = () -> { }, playerVisibility = () -> { };
    private java.util.function.IntConsumer sortAction = ignored -> { };

    public AppMenuBar(Runnable openMedia, Runnable exportPlaylist, Runnable quickActions, Runnable playPause,
                      Runnable previousTrack, Runnable nextTrack, Runnable showSearch,
                      Runnable showDiscover, Runnable preferences, Runnable toggleLibrary,
                      Runnable toggleShuffle, Runnable toggleRepeat, Runnable rescanLibrary,
                      Consumer<String> notify, Runnable exit, Runnable audioSettings) {
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER));

        JMenu file = menu("File");
        file.add(item("Add files or folder…", AppIcons.Kind.FOLDER, KeyEvent.VK_O, openMedia));
        file.add(item("Recently played", AppIcons.Kind.MUSIC, 0, () -> recentAction.run()));
        JMenu export = menu("Export");
        export.add(item("Export current playlist…", AppIcons.Kind.MUSIC, 0, exportPlaylist));
        export.add(unavailable("Export library report…", AppIcons.Kind.MUSIC));
        file.add(export);
        file.addSeparator();
        file.add(item("Exit", AppIcons.Kind.COLLAPSE, KeyEvent.VK_Q, exit));

        JMenu edit = menu("Edit");
        edit.add(item("Quick actions…", AppIcons.Kind.SEARCH, KeyEvent.VK_K, quickActions));
        edit.addSeparator();
        edit.add(unavailable("Undo", AppIcons.Kind.PREVIOUS));
        edit.add(unavailable("Redo", AppIcons.Kind.NEXT));
        edit.addSeparator();
        edit.add(unavailable("Select all", AppIcons.Kind.MUSIC));
        edit.add(item("Find…", AppIcons.Kind.SEARCH, KeyEvent.VK_F, showSearch));
        edit.add(item("Discover online…", AppIcons.Kind.SEARCH, 0, showDiscover));
        edit.add(item("Preferences…", AppIcons.Kind.SETTINGS, 0, preferences));

        JMenu view = menu("View");
        ButtonGroup density = new ButtonGroup();
        JRadioButtonMenuItem comfortable = radioItem("Comfortable", true, notify);
        JRadioButtonMenuItem compact = radioItem("Compact", false, notify);
        density.add(comfortable); density.add(compact);
        comfortable.setEnabled(false); compact.setEnabled(false);
        comfortable.setToolTipText("Density presets are not implemented yet"); compact.setToolTipText(comfortable.getToolTipText());
        view.add(comfortable); view.add(compact);
        view.addSeparator();
        libraryItem = checkActionItem("Show library", toggleLibrary);
        libraryItem.setSelected(true);
        view.add(libraryItem);
        JCheckBoxMenuItem playerBar = checkActionItem("Show player bar", () -> playerVisibility.run()); playerBar.setSelected(true); view.add(playerBar);
        JMenu sort = menu("Sort library by");
        sort.add(item("Alphabetical", AppIcons.Kind.MUSIC, 0, () -> sortAction.accept(1)));
        sort.add(item("Most played", AppIcons.Kind.MUSIC, 0, () -> sortAction.accept(2)));
        sort.add(item("Recent", AppIcons.Kind.MUSIC, 0, () -> sortAction.accept(0)));
        view.add(sort);
        view.add(unavailable("Full screen", AppIcons.Kind.EXPAND));

        JMenu playback = menu("Playback");
        playback.add(item("Play / pause", AppIcons.Kind.PLAY, 0, playPause));
        playback.add(item("Previous track", AppIcons.Kind.PREVIOUS, 0, previousTrack));
        playback.add(item("Next track", AppIcons.Kind.NEXT, 0, nextTrack));
        playback.addSeparator();
        shuffleItem = checkActionItem("Shuffle", toggleShuffle);
        repeatItem = checkActionItem("Repeat current track", toggleRepeat);
        playback.add(shuffleItem);
        playback.add(repeatItem);
        shortcutItems.put(Command.SHUFFLE, java.util.List.of(shuffleItem)); shortcutItems.put(Command.REPEAT, java.util.List.of(repeatItem));
        playback.addSeparator();
        playback.add(item("Audio & equalizer…", AppIcons.Kind.SETTINGS, 0, audioSettings));

        JMenu library = menu("Library");
        library.add(item("Add files or folder…", AppIcons.Kind.FOLDER, 0, openMedia));
        library.add(item("Rescan library…", AppIcons.Kind.FOLDER, 0, rescanLibrary));
        library.add(item("Search local library…", AppIcons.Kind.SEARCH, 0, showSearch));
        library.add(item("Show or hide library panel", AppIcons.Kind.MUSIC, 0, toggleLibrary));

        JMenu tools = menu("Tools");
        tools.add(item("Settings…", AppIcons.Kind.SETTINGS, 0, preferences));
        tools.add(item("Audio & equalizer…", AppIcons.Kind.SETTINGS, 0, audioSettings));
        tools.add(item("Quick actions…", AppIcons.Kind.SEARCH, 0, quickActions));
        tools.add(item("Keyboard shortcuts…", AppIcons.Kind.HELP, 0, () -> keyboardShortcuts.run()));

        JMenu help = menu("Help");
        help.add(item("Keyboard shortcuts…", AppIcons.Kind.HELP, 0, () -> keyboardShortcuts.run()));
        help.add(unavailable("User guide", AppIcons.Kind.HELP));
        help.add(unavailable("Check for updates", AppIcons.Kind.HELP));
        help.addSeparator();
        help.add(item("About Sangitam", AppIcons.Kind.MUSIC, 0, () -> notify.accept("Sangitam desktop prototype")));

        add(file); add(edit); add(view); add(playback); add(library); add(tools); add(help);
    }

    public void setShuffleSelected(boolean selected) { shuffleItem.setSelected(selected); }
    public void setRepeatSelected(boolean selected) { repeatItem.setSelected(selected); }
    public void setLibrarySelected(boolean selected) { libraryItem.setSelected(selected); }
    public void setKeyboardShortcutsAction(Runnable action) { keyboardShortcuts = action; }
    public void setRecentAction(Runnable action) { recentAction = action; }
    public void setPlayerVisibilityAction(Runnable action) { playerVisibility = action; }
    public void setSortAction(java.util.function.IntConsumer action) { sortAction = action; }
    private JMenuItem unavailable(String label, AppIcons.Kind icon) { JMenuItem item = item(label, icon, 0, () -> { }); item.setEnabled(false); item.setToolTipText("Not implemented yet"); return item; }
    public void setShortcutBindings(ShortcutBindings bindings) {
        shortcutItems.forEach((command, items) -> items.forEach(item -> {
            KeyStroke key = bindings.key(command);
            item.setAccelerator(key != null && key.getModifiers() == 0 && key.getKeyCode() == KeyEvent.VK_SPACE ? null : key);
        }));
    }

    private JMenu menu(String text) {
        JMenu menu = new JMenu(text);
        menu.setFont(Theme.BODY);
        menu.setForeground(Theme.TEXT);
        return menu;
    }

    private JMenuItem item(String text, AppIcons.Kind icon, int keyCode, Runnable action) {
        JMenuItem item = new JMenuItem(text, AppIcons.of(icon, 15, Theme.BLUE));
        item.setFont(Theme.BODY);
        item.addActionListener(event -> action.run());
        Command command = switch (text) {
            case "Add files or folder…" -> Command.IMPORT;
            case "Exit" -> Command.QUIT;
            case "Quick actions…" -> Command.QUICK;
            case "Find…", "Search local library…" -> Command.SEARCH;
            case "Play / pause" -> Command.PLAY_PAUSE;
            case "Previous track" -> Command.PREVIOUS;
            case "Next track" -> Command.NEXT;
            case "Audio & equalizer…" -> Command.EQ;
            case "Show or hide library panel" -> Command.LIBRARY;
            default -> null;
        };
        if (command != null) shortcutItems.computeIfAbsent(command, ignored -> new ArrayList<>()).add(item);
        if (keyCode != 0) {
            item.setAccelerator(KeyStroke.getKeyStroke(keyCode,
                java.awt.GraphicsEnvironment.isHeadless() ? java.awt.event.InputEvent.CTRL_DOWN_MASK : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        }
        return item;
    }

    private JCheckBoxMenuItem checkItem(String text, boolean selected, Consumer<String> notify) {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(text, selected);
        item.addActionListener(event -> notify.accept(text + (item.isSelected() ? " enabled" : " disabled")));
        return item;
    }

    private JCheckBoxMenuItem checkActionItem(String text, Runnable action) {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(text, false);
        item.addActionListener(event -> action.run());
        return item;
    }

    private JRadioButtonMenuItem radioItem(String text, boolean selected, Consumer<String> notify) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(text, selected);
        item.addActionListener(event -> notify.accept(text + " view selected"));
        return item;
    }
}
