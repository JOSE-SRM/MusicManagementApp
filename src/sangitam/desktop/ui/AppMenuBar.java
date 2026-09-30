package sangitam.desktop.ui;

import javax.swing.*;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

/** Professional application menu bar. IMPORTANT: Commands are GUI placeholders and only publish notifications. */
public final class AppMenuBar extends JMenuBar {
    private static final long serialVersionUID = 1L;

    public AppMenuBar(Runnable openMedia, Runnable quickActions, Consumer<String> notify, Runnable exit) {
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER));

        JMenu file = menu("File");
        file.add(item("Add files or folder…", AppIcons.Kind.FOLDER, KeyEvent.VK_O, openMedia));
        file.add(item("Open recent", AppIcons.Kind.MUSIC, 0, () -> notify.accept("Open recent selected")));
        JMenu export = menu("Export");
        export.add(item("Export playlist…", AppIcons.Kind.MUSIC, 0, () -> notify.accept("Export playlist selected")));
        export.add(item("Export library report…", AppIcons.Kind.MUSIC, 0, () -> notify.accept("Export report selected")));
        file.add(export);
        file.addSeparator();
        file.add(item("Exit", AppIcons.Kind.COLLAPSE, KeyEvent.VK_Q, exit));

        JMenu edit = menu("Edit");
        edit.add(item("Quick actions…", AppIcons.Kind.SEARCH, KeyEvent.VK_K, quickActions));
        edit.addSeparator();
        edit.add(item("Undo", AppIcons.Kind.PREVIOUS, KeyEvent.VK_Z, () -> notify.accept("Undo selected")));
        edit.add(item("Redo", AppIcons.Kind.NEXT, KeyEvent.VK_Y, () -> notify.accept("Redo selected")));
        edit.addSeparator();
        edit.add(item("Select all", AppIcons.Kind.MUSIC, KeyEvent.VK_A, () -> notify.accept("Select all selected")));
        edit.add(item("Find…", AppIcons.Kind.SEARCH, KeyEvent.VK_F, () -> notify.accept("Find selected")));
        edit.add(item("Preferences…", AppIcons.Kind.SETTINGS, 0, () -> notify.accept("Preferences selected")));

        JMenu view = menu("View");
        ButtonGroup density = new ButtonGroup();
        JRadioButtonMenuItem comfortable = radioItem("Comfortable", true, notify);
        JRadioButtonMenuItem compact = radioItem("Compact", false, notify);
        density.add(comfortable); density.add(compact);
        view.add(comfortable); view.add(compact);
        view.addSeparator();
        view.add(checkItem("Show library", true, notify));
        view.add(checkItem("Show player bar", true, notify));
        JMenu sort = menu("Sort library by");
        sort.add(item("Title", AppIcons.Kind.MUSIC, 0, () -> notify.accept("Library sorted by title")));
        sort.add(item("Artist", AppIcons.Kind.MUSIC, 0, () -> notify.accept("Library sorted by artist")));
        sort.add(item("Recently added", AppIcons.Kind.MUSIC, 0, () -> notify.accept("Library sorted by recently added")));
        view.add(sort);
        view.add(item("Full screen", AppIcons.Kind.EXPAND, KeyEvent.VK_F11, () -> notify.accept("Full screen selected")));

        JMenu playback = menu("Playback");
        playback.add(item("Play / pause", AppIcons.Kind.PLAY, KeyEvent.VK_SPACE, () -> notify.accept("Play / pause pressed")));
        playback.add(item("Previous track", AppIcons.Kind.PREVIOUS, 0, () -> notify.accept("Previous track pressed")));
        playback.add(item("Next track", AppIcons.Kind.NEXT, 0, () -> notify.accept("Next track pressed")));
        playback.addSeparator();
        playback.add(checkItem("Shuffle", false, notify));
        playback.add(checkItem("Repeat", false, notify));

        JMenu help = menu("Help");
        help.add(item("Keyboard shortcuts", AppIcons.Kind.HELP, 0, () -> notify.accept("Keyboard shortcuts selected")));
        help.add(item("User guide", AppIcons.Kind.HELP, 0, () -> notify.accept("User guide selected")));
        help.add(item("Check for updates", AppIcons.Kind.HELP, 0, () -> notify.accept("Check for updates selected")));
        help.addSeparator();
        help.add(item("About Sangitam", AppIcons.Kind.MUSIC, 0, () -> notify.accept("Sangitam desktop prototype")));

        add(file); add(edit); add(view); add(playback); add(help);
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
        if (keyCode != 0) {
            item.setAccelerator(KeyStroke.getKeyStroke(keyCode,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        }
        return item;
    }

    private JCheckBoxMenuItem checkItem(String text, boolean selected, Consumer<String> notify) {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(text, selected);
        item.addActionListener(event -> notify.accept(text + (item.isSelected() ? " enabled" : " disabled")));
        return item;
    }

    private JRadioButtonMenuItem radioItem(String text, boolean selected, Consumer<String> notify) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(text, selected);
        item.addActionListener(event -> notify.accept(text + " view selected"));
        return item;
    }
}
